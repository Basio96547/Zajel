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

    private const val SIZE_PX = 640

    /** Largest edge we will decode at — bounds memory when the user picks a huge photo. */
    private const val MAX_DECODE_EDGE = 2048

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

    fun render(data: String): Bitmap? = try {
        val hints = mapOf(
            // A quiet zone: without the margin, scanners fail on a code that
            // sits flush against the edge of a shared image.
            EncodeHintType.MARGIN to 2,
            // The payload is small, so the highest correction level costs
            // little and survives recompression by whatever app forwards it.
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
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
