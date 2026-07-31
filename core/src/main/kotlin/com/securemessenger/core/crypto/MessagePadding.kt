package com.securemessenger.core.crypto

/**
 * MessagePadding - pads plaintext to fixed-size buckets before encryption so the
 * ciphertext length does not leak the real message length to an observer.
 *
 * Wire format: [4-byte big-endian real length][plaintext][zero padding], padded
 * up to the next multiple of [BUCKET] bytes.
 */
object MessagePadding {

    private const val BUCKET = 256
    private const val HEADER = 4

    fun pad(plaintext: ByteArray): ByteArray {
        val total = paddedSize(HEADER + plaintext.size)
        val out = ByteArray(total)
        val len = plaintext.size
        out[0] = (len ushr 24).toByte()
        out[1] = (len ushr 16).toByte()
        out[2] = (len ushr 8).toByte()
        out[3] = len.toByte()
        System.arraycopy(plaintext, 0, out, HEADER, plaintext.size)
        return out
    }

    fun unpad(padded: ByteArray): ByteArray {
        if (padded.size < HEADER) throw IllegalArgumentException("Padded data too short")
        val len = ((padded[0].toInt() and 0xFF) shl 24) or
            ((padded[1].toInt() and 0xFF) shl 16) or
            ((padded[2].toInt() and 0xFF) shl 8) or
            (padded[3].toInt() and 0xFF)
        if (len < 0 || HEADER + len > padded.size) throw IllegalArgumentException("Invalid padding length")
        return padded.copyOfRange(HEADER, HEADER + len)
    }

    private fun paddedSize(size: Int): Int {
        val buckets = (size + BUCKET - 1) / BUCKET
        return (if (buckets < 1) 1 else buckets) * BUCKET
    }
}
