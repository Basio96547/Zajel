package com.securemessenger.app.ui.screens.chat

import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.securemessenger.core.crypto.MediaCodec
import java.io.File

// ---------- file picking ----------
// Extracted from ConversationScreen.kt — see the pointer comment there.

/** A fresh cache file exposed via FileProvider — where the camera app writes the captured photo. */
internal fun createCameraOutputUri(context: android.content.Context): Uri {
    val dir = File(context.cacheDir, "camera_capture").apply { mkdirs() }
    val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

internal data class PickedFile(val bytes: ByteArray, val mimeType: String, val fileName: String, val mediaType: Int)

internal fun readPickedFile(context: android.content.Context, uri: Uri): PickedFile? {
    val resolver = context.contentResolver
    val mimeType = resolver.getType(uri) ?: return null
    val mediaType = when {
        mimeType.startsWith("image/") -> MediaCodec.TYPE_IMAGE
        mimeType.startsWith("video/") -> MediaCodec.TYPE_VIDEO
        mimeType.startsWith("audio/") -> MediaCodec.TYPE_AUDIO
        // Anything else (PDF, docx, …) — the "ملف" attachment option should
        // actually accept arbitrary files, not silently drop them.
        else -> MediaCodec.TYPE_FILE
    }
    var fileName = "file"
    resolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) fileName = cursor.getString(idx) ?: fileName
    }
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    return PickedFile(bytes, mimeType, fileName, mediaType)
}
