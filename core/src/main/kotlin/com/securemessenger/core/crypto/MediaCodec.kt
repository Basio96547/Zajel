package com.securemessenger.core.crypto

import com.securemessenger.core.B64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Media messages get an extra symmetric-encryption layer on top of the usual
 * Double-Ratchet + sealed-sender pipeline: the raw file is encrypted with a
 * fresh random key (crypto_secretbox), and that key + ciphertext are what
 * actually travel as the ratchet's "plaintext" — so a media message is
 * protected by (1) this per-file key, (2) the Double Ratchet, (3) the
 * sealed-sender envelope, and (4) SQLCipher + Keystore at rest locally.
 */
object MediaCodec {
    const val TYPE_TEXT = 0
    const val TYPE_IMAGE = 1
    const val TYPE_FILE = 2
    const val TYPE_VIDEO = 3
    const val TYPE_AUDIO = 4

    // Only these bytes are allowed to survive sanitizeFileName — everything
    // else (path separators, control characters, anything else) is stripped.
    private val SAFE_FILENAME_CHARS = Regex("[^A-Za-z0-9أ-ي ._-]")

    data class WireMedia(
        val mediaType: Int,
        val mimeType: String,
        val fileName: String,
        val key: ByteArray,
        val ciphertext: ByteArray,
        // Voice-message amplitude envelope (0f..1f per bucket), empty for
        // anything else. Small enough to just ride along as plain JSON.
        val waveform: List<Float> = emptyList(),
        val caption: String? = null
    )

    data class LocalMedia(
        val mediaType: Int,
        val mimeType: String,
        val fileName: String,
        val size: Int,
        val key: ByteArray,
        val ref: String,
        val waveform: List<Float> = emptyList(),
        val caption: String? = null
    )

    private fun waveformToJson(waveform: List<Float>): JSONArray? {
        if (waveform.isEmpty()) return null
        return JSONArray(waveform.map { (it * 255f).toInt().coerceIn(0, 255) })
    }

    private fun waveformFromJson(array: JSONArray?): List<Float> {
        if (array == null) return emptyList()
        return (0 until array.length()).map { array.getInt(it) / 255f }
    }

    /** What actually goes over the wire (as the ratchet's plaintext) for a media message. */
    fun buildWirePayload(
        mediaType: Int, mimeType: String, fileName: String, key: ByteArray, ciphertext: ByteArray,
        waveform: List<Float> = emptyList(), caption: String? = null
    ): ByteArray {
        val json = JSONObject().apply {
            put("k", "media")
            put("mediaType", mediaType)
            put("mimeType", mimeType)
            put("fileName", fileName)
            put("key", B64.encode(key))
            put("data", B64.encode(ciphertext))
            waveformToJson(waveform)?.let { put("wf", it) }
            caption?.takeIf { it.isNotBlank() }?.let { put("cap", it) }
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * A peer-supplied file name, reduced to a bare basename built only from an
     * allow-listed character set (letters, digits, space, dot, dash,
     * underscore — no path separators, no "..", no control characters). This
     * is the ONLY sanitization point for every file name that ever reaches a
     * File() constructor (see openInExternalApp) — without it, a malicious
     * peer's chosen fileName could flow straight into a filesystem path with
     * zero validation (path traversal / arbitrary file placement).
     */
    private fun sanitizeFileName(raw: String): String {
        val basename = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = basename.replace(SAFE_FILENAME_CHARS, "_").trim().take(120)
        // "." and ".." are filesystem-reserved path segments — meaningful to
        // File(parent, child) even with zero slashes (e.g. File(dir, "..")
        // resolves to dir's PARENT). Both survive the character allow-list
        // above untouched (a bare dot is a legitimate filename character), so
        // they need an explicit, separate check: a peer sending exactly ".."
        // as the fileName would otherwise make File(dir, media.fileName) in
        // openInExternalApp resolve one directory level up.
        return when {
            cleaned.isBlank() || cleaned == "." || cleaned == ".." -> "file"
            else -> cleaned
        }
    }

    /** Peer-supplied mediaType must be one of the types this app actually knows how to render. */
    private fun isKnownMediaType(type: Int): Boolean =
        type == TYPE_IMAGE || type == TYPE_FILE || type == TYPE_VIDEO || type == TYPE_AUDIO

    /** Best-effort sniff: is this plaintext a media payload, or plain text? */
    fun tryParseWirePayload(plaintext: ByteArray): WireMedia? {
        return try {
            val text = String(plaintext, Charsets.UTF_8)
            if (!text.startsWith("{")) return null
            val json = JSONObject(text)
            if (json.optString("k") != "media") return null
            val mediaType = json.getInt("mediaType")
            if (!isKnownMediaType(mediaType)) return null
            WireMedia(
                mediaType = mediaType,
                // The peer's claimed mimeType is NEVER trusted for anything
                // security-relevant (e.g. an Intent's setDataAndType) — callers
                // that open the file externally must derive the type themselves
                // from the sanitized extension. Kept here only for display.
                mimeType = json.getString("mimeType"),
                fileName = sanitizeFileName(json.getString("fileName")),
                key = B64.decode(json.getString("key")),
                ciphertext = B64.decode(json.getString("data")),
                waveform = waveformFromJson(json.optJSONArray("wf")),
                caption = json.optString("cap").takeIf { it.isNotBlank() }
            )
        } catch (_: Exception) {
            null
        }
    }

    /** The small descriptor stored locally (encrypted) — points at the on-disk ciphertext instead of embedding it. */
    fun buildLocalDescriptor(
        mediaType: Int, mimeType: String, fileName: String, size: Int, key: ByteArray, ref: String,
        waveform: List<Float> = emptyList(), caption: String? = null
    ): ByteArray {
        val json = JSONObject().apply {
            put("mediaType", mediaType)
            put("mimeType", mimeType)
            put("fileName", fileName)
            put("size", size)
            put("key", B64.encode(key))
            put("ref", ref)
            waveformToJson(waveform)?.let { put("wf", it) }
            caption?.takeIf { it.isNotBlank() }?.let { put("cap", it) }
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    fun parseLocalDescriptor(bytes: ByteArray): LocalMedia? {
        return try {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            LocalMedia(
                mediaType = json.getInt("mediaType"),
                mimeType = json.getString("mimeType"),
                fileName = json.getString("fileName"),
                size = json.getInt("size"),
                key = B64.decode(json.getString("key")),
                ref = json.getString("ref"),
                waveform = waveformFromJson(json.optJSONArray("wf")),
                caption = json.optString("cap").takeIf { it.isNotBlank() }
            )
        } catch (_: Exception) {
            null
        }
    }
}
