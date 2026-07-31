package com.securemessenger.core

/**
 * Base64, in exactly the encoding this app's wire format has always used.
 *
 * The Android app encodes with `android.util.Base64.NO_WRAP`: the standard
 * RFC 4648 alphabet, padded, with no line breaks. `java.util.Base64`'s basic
 * encoder produces byte-identical output, and it exists on every Android API
 * this app supports (added in API 26; minSdk here is 26) — so both platforms
 * can share one implementation and messages stay mutually readable.
 *
 * Decoding is the lenient direction on purpose. `java.util.Base64`'s strict
 * decoder rejects the stray whitespace and missing padding that `android.util.Base64`
 * quietly accepted; a peer running an older build must not become undecodable
 * over a detail that carries no meaning.
 */
object B64 {

    private val encoder = java.util.Base64.getEncoder()
    private val decoder = java.util.Base64.getDecoder()

    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    fun decode(text: String): ByteArray {
        val cleaned = text.filterNot { it.isWhitespace() }
        // Re-pad to a multiple of 4; the strict decoder would otherwise throw on
        // input an older, more forgiving encoder produced.
        val padded = when (cleaned.length % 4) {
            2 -> "$cleaned=="
            3 -> "$cleaned="
            else -> cleaned
        }
        return decoder.decode(padded)
    }

    /** Null-safe decode for optional wire fields — returns null instead of throwing on junk. */
    fun decodeOrNull(text: String?): ByteArray? {
        if (text.isNullOrBlank()) return null
        return try {
            decode(text)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun fromHex(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return try {
            ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (e: NumberFormatException) {
            null
        }
    }
}
