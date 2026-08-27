package com.securemessenger.app.media

import android.media.MediaDataSource

/**
 * Lets MediaPlayer read decrypted audio/video straight from a byte array —
 * no temp file ever touches disk for in-app playback (matches how every
 * other part of this app avoids writing decrypted plaintext to storage).
 */
class ByteArrayMediaDataSource(private val data: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        // A crafted/corrupt container's internal offset table can drive a
        // MediaDataSource seek to a negative or otherwise invalid position —
        // a documented historical bug class in media parsers. Only the upper
        // bound was checked before, so a negative position sailed through to
        // System.arraycopy with a negative srcPos, throwing an
        // IndexOutOfBoundsException the JNI bridge calling this method isn't
        // necessarily prepared for. IOException is: it's this method's own
        // declared exception type.
        if (position < 0 || offset < 0 || size < 0) {
            throw java.io.IOException("invalid readAt request: position=$position, offset=$offset, size=$size")
        }
        if (position >= data.size) return -1
        val length = minOf(size.toLong(), (data.size - position)).toInt()
        System.arraycopy(data, position.toInt(), buffer, offset, length)
        return length
    }

    override fun getSize(): Long = data.size.toLong()

    override fun close() {}
}
