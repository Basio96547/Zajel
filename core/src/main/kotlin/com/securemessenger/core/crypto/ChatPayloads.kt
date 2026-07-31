package com.securemessenger.core.crypto

import org.json.JSONObject

/**
 * Wire payloads for social message-interaction features. These ride the exact
 * same Double-Ratchet + sealed-sender pipeline as a plain text message — they
 * ARE the ratchet's "plaintext" — so the relay only ever sees an opaque blob,
 * same as any message. The receiver sniffs the decrypted plaintext:
 *   - MediaCodec media payload  → k == "media"
 *   - control op (react/edit/del) → k == "ctl"  (modifies an existing message)
 *   - structured text (a reply)   → k == "txt"  (a new message quoting another)
 *   - anything else               → a raw plain-text message (backward compatible)
 */
object ChatPayloads {

    const val OP_REACT = "react"
    const val OP_EDIT = "edit"
    const val OP_DELETE = "del"

    /** A control op that modifies an already-delivered message rather than creating one. */
    data class Control(
        val op: String,
        val targetClientId: String,
        val emoji: String? = null,   // for react ("" = remove reaction)
        val text: String? = null     // for edit (the new text)
    )

    /** A plain-text message that quotes an earlier one. */
    data class TextPayload(
        val text: String,
        val replyToClientId: String?,
        val replySnippet: String?
    )

    // ---------- build (outgoing) ----------

    fun buildReaction(targetClientId: String, emoji: String): ByteArray =
        JSONObject().apply {
            put("k", "ctl"); put("op", OP_REACT); put("target", targetClientId); put("emoji", emoji)
        }.toString().toByteArray(Charsets.UTF_8)

    fun buildEdit(targetClientId: String, newText: String): ByteArray =
        JSONObject().apply {
            put("k", "ctl"); put("op", OP_EDIT); put("target", targetClientId); put("text", newText)
        }.toString().toByteArray(Charsets.UTF_8)

    fun buildDelete(targetClientId: String): ByteArray =
        JSONObject().apply {
            put("k", "ctl"); put("op", OP_DELETE); put("target", targetClientId)
        }.toString().toByteArray(Charsets.UTF_8)

    /** A reply carries the reply target + a short snippet so the peer can render the quote. */
    fun buildReplyText(text: String, replyToClientId: String, snippet: String): ByteArray =
        JSONObject().apply {
            put("k", "txt"); put("text", text); put("replyTo", replyToClientId)
            put("snippet", snippet.take(80))
        }.toString().toByteArray(Charsets.UTF_8)

    // ---------- parse (incoming) ----------

    fun tryParseControl(plaintext: ByteArray): Control? = parseObj(plaintext)?.let { json ->
        if (json.optString("k") != "ctl") return null
        val op = json.optString("op").takeIf { it.isNotBlank() } ?: return null
        val target = json.optString("target").takeIf { it.isNotBlank() } ?: return null
        Control(
            op = op,
            targetClientId = target,
            emoji = if (json.has("emoji")) json.optString("emoji") else null,
            text = if (json.has("text")) json.optString("text") else null
        )
    }

    fun tryParseText(plaintext: ByteArray): TextPayload? = parseObj(plaintext)?.let { json ->
        if (json.optString("k") != "txt") return null
        TextPayload(
            text = json.optString("text"),
            replyToClientId = json.optString("replyTo").takeIf { it.isNotBlank() },
            replySnippet = json.optString("snippet").takeIf { it.isNotBlank() }
        )
    }

    private fun parseObj(plaintext: ByteArray): JSONObject? = try {
        val text = String(plaintext, Charsets.UTF_8)
        if (!text.startsWith("{")) null else JSONObject(text)
    } catch (_: Exception) {
        null
    }
}
