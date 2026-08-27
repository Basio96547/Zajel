package com.securemessenger.core.net

import com.securemessenger.core.B64
import com.securemessenger.core.crypto.LibsodiumWrapper
import org.json.JSONObject

/**
 * The on-the-wire frame format, in one place.
 *
 * This is the file that decides whether an Android phone and a desktop client
 * can talk to each other at all. Every frame either side sends is built here
 * and read here, so there is no second implementation to drift out of sync —
 * a field renamed, a signature covering one byte less, a base64 variant
 * changed, and the two platforms would silently stop being able to open each
 * other's messages.
 *
 * Every frame has the same two-layer shape:
 *
 *   { "type": …, "id": …?, "sealed": base64(crypto_box_seal(inner)) }
 *
 * The outer layer is plain routing metadata — the minimum a transport needs to
 * hand the frame to the right place. The inner object holds everything that
 * actually says anything (who sent it, what it says, which prekeys it used) and
 * is anonymously sealed to the recipient's identity key, so neither a device
 * sharing the LAN nor the blind relay ever sees any of it.
 *
 * `id` is present only on frames the recipient must acknowledge. The ephemeral
 * ones — typing, challenge, bundle_announce — carry no id, because nothing
 * retries them. It is a random UUID and says nothing about who either party is.
 *
 * **There is deliberately no recipient field out here.** One used to be written
 * (`recipientId`), and nothing in either client ever read it back — it was a
 * write-only field that put a stable user id in the clear on every message,
 * receipt and typing frame travelling the local `ws://` socket, which is
 * precisely the linkage the rotating discovery token exists to deny a passive
 * observer. Removed 2026-07-31. A direct socket needs no such field: a frame
 * arriving on our own server is by definition addressed to us, and one we
 * cannot unseal is not ours to read. Do not reintroduce it — routing that a
 * transport genuinely needs belongs to the transport (the relay's mailbox id),
 * not to a field naming a person.
 */
object Envelopes {

    const val TYPE_MESSAGE = "message"
    const val TYPE_RECEIPT = "receipt"
    const val TYPE_TYPING = "typing"
    const val TYPE_ACK = "ack"
    const val TYPE_CHALLENGE = "challenge"
    const val TYPE_BUNDLE_ANNOUNCE = "bundle_announce"

    /**
     * An unsolicited self-introduction, delivered via the directory
     * service's introduction mailbox rather than a QR scan — see
     * `MailboxToken.introMailboxId` and `DirectoryProtocol`. [TYPE_INTRO_ACCEPT]
     * is the reply once the recipient chooses to accept; both carry the same
     * [DirectoryProtocol.SelfIntroduction] shape, just addressed in opposite
     * directions.
     */
    const val TYPE_INTRO_REQUEST = "intro_request"
    const val TYPE_INTRO_ACCEPT = "intro_accept"

    /** Cover traffic. Deliberately has no handler anywhere — being ignored is its entire purpose. */
    const val TYPE_NOISE = "noise"

    /** The literal frame a cover-traffic blob carries. */
    const val NOISE_ENVELOPE = """{"type":"noise"}"""

    /**
     * Wrap [inner] as an anonymous sealed box addressed to [recipientPublicKey].
     *
     * crypto_box_seal carries no sender identity of its own — that is the point.
     * Who sent it is stated *inside* the sealed payload, where only the intended
     * recipient can read it.
     */
    fun seal(
        type: String,
        inner: JSONObject,
        recipientPublicKey: ByteArray,
        envelopeId: String? = null
    ): JSONObject {
        val sealed = LibsodiumWrapper.sealTo(inner.toString().toByteArray(Charsets.UTF_8), recipientPublicKey)
        return JSONObject().apply {
            put("type", type)
            envelopeId?.let { put("id", it) }
            put("sealed", B64.encode(sealed))
        }
    }

    /**
     * Recover the inner object from an outer frame. A frame with no `sealed`
     * field is returned as-is — `ack` frames are plain, since everything in one
     * is either public (the routing id) or already unforgeable (the ack token).
     */
    fun open(outer: JSONObject, ourPublicKey: ByteArray, ourSecretKey: ByteArray): JSONObject {
        if (!outer.has("sealed")) return outer
        val sealed = B64.decode(outer.getString("sealed"))
        val inner = LibsodiumWrapper.sealOpen(sealed, ourPublicKey, ourSecretKey)
        return JSONObject(String(inner, Charsets.UTF_8))
    }

    /**
     * The exact byte string an outgoing prekey bundle is signed over, and that
     * a receiver re-derives to verify.
     *
     * The order and the inclusion of every field matter and must never change on
     * one platform alone. It deliberately covers more than the identity key:
     * without the ML-KEM key and the one-time prekey under the same signature,
     * an on-path relay could swap in a post-quantum key of its own, or replay a
     * stale one-time key, and nothing would catch it. The nonce is what makes it
     * a live answer rather than a recording of an earlier one.
     */
    fun bundleSigningPayload(
        nonce: ByteArray,
        signedPreKey: ByteArray,
        identityKey: ByteArray,
        mlkemPublicKey: ByteArray,
        oneTimePreKey: ByteArray
    ): ByteArray = nonce + signedPreKey + identityKey + mlkemPublicKey + oneTimePreKey

    /**
     * Build the `bundle_announce` payload for our own keys, signed over
     * [bundleSigningPayload] with [sign].
     *
     * Built here rather than per-platform so the JSON field names, the base64
     * variant and the signed byte string are produced by the same code that
     * [parseBundle] verifies with — the one arrangement in which a phone and a
     * desktop client cannot disagree about what a valid bundle looks like.
     */
    fun buildBundleAnnounce(
        userId: String,
        nonce: ByteArray,
        identityKey: ByteArray,
        signedPreKey: ByteArray,
        signedPreKeyId: Int,
        signingPublicKey: ByteArray?,
        mlkemPublicKey: ByteArray,
        oneTimePreKeyId: Int?,
        oneTimePreKey: ByteArray?,
        sign: (ByteArray) -> ByteArray
    ): JSONObject {
        val signedPayload = bundleSigningPayload(
            nonce, signedPreKey, identityKey, mlkemPublicKey, oneTimePreKey ?: ByteArray(0)
        )
        return JSONObject().apply {
            put("type", TYPE_BUNDLE_ANNOUNCE)
            put("userId", userId)
            put("nonce", B64.encode(nonce))
            put("identityKey", B64.encode(identityKey))
            put("signedPreKey", B64.encode(signedPreKey))
            put("signedPreKeyId", signedPreKeyId)
            put("signedPreKeySignature", B64.encode(sign(signedPayload)))
            signingPublicKey?.let { put("signingPublicKey", B64.encode(it)) }
            if (mlkemPublicKey.isNotEmpty()) put("mlkemPublicKey", B64.encode(mlkemPublicKey))
            if (oneTimePreKey != null && oneTimePreKeyId != null) {
                put("oneTimePreKey", JSONObject().apply {
                    put("id", oneTimePreKeyId)
                    put("key", B64.encode(oneTimePreKey))
                })
            }
        }
    }

    /** The `challenge` that must precede any bundle handout — proves the exchange is live. */
    fun buildChallenge(userId: String, nonce: ByteArray): JSONObject =
        JSONObject().apply {
            put("nonce", B64.encode(nonce))
            put("userId", userId)
        }

    /** The `ack` a recipient sends back so the sender can clear its outbox row. */
    fun ack(envelopeId: String, ackToken: String?): String =
        JSONObject().apply {
            put("type", TYPE_ACK)
            put("id", envelopeId)
            ackToken?.let { put("ackToken", it) }
        }.toString()

    /**
     * A prekey bundle, as it travels inside a sealed `bundle_announce`.
     *
     * [parse] returns null — never a partially-trusted object — for anything
     * that fails verification, so a caller can't accidentally use a bundle whose
     * signature or nonce didn't check out.
     */
    data class PrekeyBundle(
        val identityKey: ByteArray,
        val signedPreKey: ByteArray,
        val signingPublicKey: ByteArray? = null,
        val oneTimePreKey: ByteArray?,
        val oneTimePreKeyId: Int? = null,
        val mlkemPublicKey: ByteArray? = null
    )

    /**
     * Read and verify a `bundle_announce` payload.
     *
     * [expectedNonce] is the nonce we challenged with; a bundle that doesn't
     * echo it exactly is refused as a possible replay of a bundle captured off
     * the network earlier.
     */
    fun parseBundle(json: JSONObject, expectedNonce: ByteArray?): PrekeyBundle? {
        return try {
            val otk = json.optJSONObject("oneTimePreKey")
            val identityKey = B64.decode(json.getString("identityKey"))
            val signedPreKey = B64.decode(json.getString("signedPreKey"))
            val signingPublicKey = B64.decodeOrNull(json.optString("signingPublicKey"))
            val signature = B64.decodeOrNull(json.optString("signedPreKeySignature"))
            val nonce = B64.decodeOrNull(json.optString("nonce")) ?: ByteArray(0)

            if (expectedNonce != null && !nonce.contentEquals(expectedNonce)) return null

            val mlkemPublicKey = B64.decodeOrNull(json.optString("mlkemPublicKey")) ?: ByteArray(0)
            val otkPublicKey = otk?.let { B64.decode(it.getString("key")) } ?: ByteArray(0)

            val signedPayload =
                bundleSigningPayload(nonce, signedPreKey, identityKey, mlkemPublicKey, otkPublicKey)
            if (signingPublicKey == null || signature == null ||
                !LibsodiumWrapper.verifyDetached(signedPayload, signature, signingPublicKey)
            ) {
                return null
            }

            PrekeyBundle(
                identityKey = identityKey,
                signedPreKey = signedPreKey,
                signingPublicKey = signingPublicKey,
                oneTimePreKey = otkPublicKey.takeIf { it.isNotEmpty() },
                oneTimePreKeyId = otk?.let { if (it.has("id")) it.getInt("id") else null },
                mlkemPublicKey = mlkemPublicKey.takeIf { it.isNotEmpty() }
            )
        } catch (e: Exception) {
            null
        }
    }
}
