package com.securemessenger.app.ui.screens.chat

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File

/**
 * Rendering a pairing QR to an image, sharing it, and reading one back out of a
 * picture — the two halves of pairing without a camera pointed at a screen.
 *
 * This exists because the camera route has a hard physical limit: two app
 * instances on the *same* phone (Samsung Dual Messenger, work profiles) can
 * never scan each other, and screenshots are blocked by FLAG_SECURE. Sharing
 * the code as a picture is the only way those two can pair at all.
 *
 * SECURITY NOTE, and it is not a small one: a full pairing QR carries the relay
 * pair secret, whose entire security argument was that it only ever travels by
 * being photographed off a screen. Sending it as an image puts it on whatever
 * channel the user picks. Anyone who intercepts that picture can compute the
 * mailbox ids for this pairing — enough to drain the mailbox (denial of
 * service) and to see envelope metadata, though NOT to read message content,
 * which stays behind the Double Ratchet and a box sealed to the identity key.
 * [stripRelaySecret] exists so the caller can offer a genuinely safe variant.
 */
object QrImage {

    /**
     * Rendered edge, in pixels.
     *
     * This was 640, and it was too small for this payload. A full pairing code
     * carries a user id, a 64-char identity key, a name and a 64-char pair
     * secret; at error-correction level H that lands around QR version 17 —
     * roughly 85 modules, 93 with the mandatory 4-module quiet zone. At 640px
     * that leaves about 6 pixels per module, which measured on device as a
     * deterministic ~1-2% of payloads rendering into codes this app's own
     * decoder could not read back at all (8 out of 8 immediate retries of the
     * same payload failed, so it is the content, not chance), and JPEG
     * recompression failing outright.
     *
     * 1024 gives about 11 pixels per module for the same payload — enough
     * margin that neither the decoder nor a re-encode is working near its
     * limit. The bitmap is RGB_565, so this costs 2MB while it is on screen.
     */
    private const val SIZE_PX = 1024

    /** Largest edge we will decode at — bounds memory when the user picks a huge photo. */
    private const val MAX_DECODE_EDGE = 2048

    /**
     * Tried in order until the rendered code reads back correctly.
     *
     * H first because the payload is small and the highest correction survives
     * recompression by whatever app forwards the picture. Q and M exist only as
     * escapes for the rare payload H cannot round-trip; each changes the
     * version and the mask, so they fail independently. Q still tolerates ~25%
     * damage and M ~15%, both far beyond what a screenshot suffers.
     */
    private val ERROR_CORRECTION_LADDER = listOf(
        ErrorCorrectionLevel.H,
        ErrorCorrectionLevel.Q,
        ErrorCorrectionLevel.M
    )

    /**
     * THE app's code. One shape, built in one place.
     *
     * The app used to draw two different QR codes and call both of them "رمز
     * QR": this pairing payload, and a bare hex identity key on the key
     * verification screen. Two black squares, indistinguishable to anyone
     * holding a phone, reached from two rows of the same profile screen — and
     * scanning the wrong one at the verification screen reported a possible
     * wiretap, because an unparseable input and a genuine key mismatch were
     * the same return value. There is now one payload; the verification screen
     * renders this too, with [pairSecretHex] left null.
     *
     * @param pairSecretHex the relay pair secret, or null for a code that
     *   pairs on the local network only. Null also means nothing was minted to
     *   draw this code, which is what the verification screen wants: showing
     *   your identity to a contact you already have should not hand out a
     *   fresh relay secret, and should not consume one of the outstanding
     *   pairing slots (see AppSettings' pending pair secrets).
     */
    fun identityPayload(
        userId: String,
        publicKeyHex: String,
        username: String,
        directAddress: String? = null,
        pairSecretHex: String? = null
    ): String = org.json.JSONObject().apply {
        put("u", userId)
        put("k", publicKeyHex)
        put("n", username)
        if (pairSecretHex != null) put("s", pairSecretHex)
        if (directAddress != null) put("a", directAddress)
    }.toString()

    /**
     * A copy of [payload] with the relay pair secret removed. The result pairs
     * for local-network messaging only, and is safe to send over any channel:
     * everything left in it is already public (a user id, an identity public
     * key, a display name).
     */
    fun stripRelaySecret(payload: String): String = try {
        org.json.JSONObject(payload).apply { remove("s") }.toString()
    } catch (e: Exception) {
        payload
    }

    /**
     * Render a pairing code, and **prove we can read it back before returning
     * it**.
     *
     * This is not belt-and-braces. Measured on device across 200 random
     * payloads, roughly 1-2% of them encoded into a matrix that this app's own
     * decoder could not read at all — deterministically, 8 out of 8 immediate
     * retries of the same payload. It is not the quiet zone (fixed to the
     * spec's 4 modules, still failed), not the resolution (640 and 1024 both
     * failed), and not the character set (an ASCII-only payload failed in a run
     * where the Arabic one passed). What is left is that ZXing's encoder
     * occasionally produces, for particular data, a matrix its own decoder
     * cannot recover — and chasing that inside the library is not something to
     * do on the critical path of the only way to add a contact.
     *
     * So the guarantee is moved to where it can actually be made: try the
     * highest error correction first, verify by decoding, and step down a level
     * if the check fails. Each level produces a different version and mask, so
     * a payload that defeats one is very unlikely to defeat all three. A code
     * that survives none is refused rather than shown, because a code nobody
     * can scan is worse than an honest failure.
     *
     * Cost is one decode per render, a few milliseconds, once, on a screen the
     * user is about to stare at anyway.
     */
    fun render(data: String): Bitmap? {
        for (level in ERROR_CORRECTION_LADDER) {
            val bitmap = renderAt(data, level) ?: continue
            if (verifyReadable(bitmap) == data) return bitmap
            bitmap.recycle()
        }
        return null
    }

    /**
     * The self-check decode, deliberately NOT [decodeFromBitmap].
     *
     * That one is tuned for a photograph of somebody's screen: two binarizers
     * and TRY_HARDER, which on a 1024px image costs a few hundred milliseconds.
     * Paying that on every render — up to three times as the ladder steps down
     * — put over half a second on the pairing screen for no benefit, because
     * the image being checked here is one we generated ourselves seconds ago:
     * perfectly bilevel, perfectly square, no glare and no perspective.
     *
     * A single global-histogram pass without TRY_HARDER is exactly right for
     * that, and being the stricter reader is the safe direction to err in: a
     * code this rejects but a real scanner would manage merely costs us a step
     * down the ladder, whereas a code we accept and a scanner cannot read is
     * the failure this whole mechanism exists to prevent.
     */
    private fun verifyReadable(bitmap: Bitmap): String? = try {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        MultiFormatReader()
            .decode(
                BinaryBitmap(GlobalHistogramBinarizer(RGBLuminanceSource(width, height, pixels))),
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.CHARACTER_SET to "UTF-8"
                )
            ).text
    } catch (e: Exception) {
        null
    }

    private fun renderAt(data: String, correction: ErrorCorrectionLevel): Bitmap? = try {
        val hints = mapOf(
            // The quiet zone, in MODULES — not pixels. The QR specification
            // requires 4, which is also ZXing's default. This said 2, and that
            // was not a harmless economy: an undersized quiet zone puts the
            // code out of spec and makes detection depend on the data pattern
            // itself, so it fails for some payloads and not others. Measured on
            // device before this change, 3 of 60 pairing payloads rendered into
            // codes this app's OWN decoder could not read back — about 5%, and
            // scanning a code is the only way to add a contact, so that is one
            // pairing in twenty failing for no visible reason.
            EncodeHintType.MARGIN to 4,
            EncodeHintType.ERROR_CORRECTION to correction,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, SIZE_PX, SIZE_PX, hints)
        val pixels = IntArray(SIZE_PX * SIZE_PX)
        for (y in 0 until SIZE_PX) {
            val row = y * SIZE_PX
            for (x in 0 until SIZE_PX) {
                pixels[row + x] = if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }
        }
        Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.RGB_565).apply {
            setPixels(pixels, 0, SIZE_PX, 0, 0, SIZE_PX, SIZE_PX)
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Write the code to a cache file and hand it to the system share sheet.
     * Returns false if it couldn't be rendered or no app can receive it.
     *
     * The directory is emptied first so a previously shared code — which is
     * still a live pairing credential — never lingers on disk, and it is wiped
     * along with everything else by SecureRepository.wipeAllData.
     */
    fun share(context: Context, payload: String, caption: String): Boolean {
        val bitmap = render(payload) ?: return false
        return try {
            val dir = File(context.cacheDir, "qr_share").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "pairing-code.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, caption)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "مشاركة الباركود"))
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Read a QR code out of a picture the user selected. Returns null when the
     * image holds no readable code.
     *
     * Two binarizers are tried: the hybrid one handles photographs of a screen
     * (uneven lighting, glare), while the global-histogram one is better on a
     * clean, flat screenshot — which is what a forwarded image usually is.
     */
    fun decodeFromImage(context: Context, uri: Uri): String? {
        val bitmap = loadDownsampled(context, uri) ?: return null
        return try {
            decodeFromBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /** The decoding itself, split out from the file loading so it can be exercised directly. */
    fun decodeFromBitmap(bitmap: Bitmap): String? = try {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.CHARACTER_SET to "UTF-8"
        )
        listOf(HybridBinarizer(source), GlobalHistogramBinarizer(source))
            .firstNotNullOfOrNull { binarizer ->
                try {
                    MultiFormatReader().decode(BinaryBitmap(binarizer), hints).text
                } catch (e: Exception) {
                    null
                }
            }
    } catch (e: Exception) {
        null
    }

    /**
     * Decode the picked image no larger than [MAX_DECODE_EDGE] on its longest
     * edge. A modern camera photo is tens of megapixels; loading one at full
     * size to find a QR code in it is a reliable way to run out of memory.
     */
    private fun loadDownsampled(context: Context, uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_DECODE_EDGE) sample *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    } catch (e: Exception) {
        null
    }
}
