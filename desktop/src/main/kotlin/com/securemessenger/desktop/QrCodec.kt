package com.securemessenger.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * QR in both directions, because pairing has to work without a camera on this side.
 *
 * A phone pairs with this client by pointing its camera at [render] on screen —
 * that direction is easy. The reverse is the awkward one: a PC usually has no
 * camera to point back, so [decodeFile] reads the phone's code out of an image
 * file instead (the Android app can already share its code as a picture), and
 * the UI additionally accepts the payload pasted as plain text.
 */
object QrCodec {

    /**
     * Error correction level H and a wide quiet zone on purpose: the phone may
     * be reading this off a glossy monitor at an angle, and the same settings
     * let a shared screenshot survive being recompressed by a messaging app.
     */
    fun render(payload: String, size: Int = 640): ImageBitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size, hints)
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until size) {
            for (y in 0 until size) {
                image.setRGB(x, y, if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        return image.toComposeImageBitmap()
    }

    /**
     * Read a QR payload out of a saved picture. Returns null if there is no
     * readable code in it.
     *
     * Two binarizers are tried: Hybrid handles ordinary photos well, while
     * GlobalHistogram is markedly better on a flat screenshot of a screen —
     * which is exactly what a code shared from a phone usually is.
     */
    fun decodeFile(file: File): String? {
        val image = try {
            ImageIO.read(file) ?: return null
        } catch (e: Exception) {
            return null
        }
        val source = BufferedImageLuminanceSource(image)
        val hints = mapOf(DecodeHintType.TRY_HARDER to true)
        for (bitmap in listOf(BinaryBitmap(HybridBinarizer(source)), BinaryBitmap(GlobalHistogramBinarizer(source)))) {
            try {
                return MultiFormatReader().decode(bitmap, hints).text
            } catch (e: Exception) {
                // Not readable with this binarizer — fall through and try the next.
            }
        }
        return null
    }
}
