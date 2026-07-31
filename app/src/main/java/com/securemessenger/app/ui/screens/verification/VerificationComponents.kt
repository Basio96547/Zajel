package com.securemessenger.app.ui.screens.verification

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter

internal fun generateQRCode(data: String): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix: BitMatrix = writer.encode(data, BarcodeFormat.QR_CODE, 200, 200)
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.RGB_565)
        for (x in 0 until 200) {
            for (y in 0 until 200) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bitmap
    } catch (_: Exception) {
        null
    }
}

sealed class VerificationStatus {
    object None : VerificationStatus()
    object Verified : VerificationStatus()
    object Failed : VerificationStatus()
}
