package com.securemessenger.app.media

import android.media.MediaDataSource

/**
 * Lets MediaPlayer read decrypted audio/video straight from a byte array —
 * no temp file ever touches disk for in-app playback (matches how every
 * other part of this app avoids writing decrypted plaintext to storage).
 */
class ByteArrayMediaDataSource(private val data: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= data.size) return -1
        val length = minOf(size.toLong(), (data.size - position)).toInt()
        System.arraycopy(data, position.toInt(), buffer, offset, length)
        return length
    }

    override fun getSize(): Long = data.size.toLong()

    override fun close() {}
}
