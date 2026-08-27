package com.securemessenger.core.net

import com.securemessenger.core.B64
import com.securemessenger.core.crypto.LibsodiumWrapper
import org.json.JSONObject

/**
 * Everything specific to the optional username-directory path: claiming a
 * handle, and introducing yourself to someone you found by one instead of by
 * QR. [Envelopes] stays the general-purpose outer frame and prekey-bundle
 * protocol; this file owns the signed payloads a directory-found contact
 * needs that a QR-paired one never did.
 *
 * Every signing-payload function here has a byte-identical twin in
 * `directory/src/crypto.ts` (except [introSigningPayload], which the
 * directory server never sees or verifies — see its own doc). The two must
 * never drift: `DirectoryProtocolTest` hardcodes vectors produced by the
 * TypeScript side and asserts this file reproduces them exactly.
 */
object DirectoryProtocol {

    /**
     * The exact bytes a username claim is signed over. The directory
     * verifies this with the *supplied* `signingPublicKey` — proving the
     * caller holds the matching secret key — not against anything already on
     * file, since for a brand-new claim there is nothing on file yet.
     *
     * Only [username] is variable-length, and it's placed before three
     * fixed-length fields (32, 32, 8 bytes), so the encoding is unambiguous
     * without a length prefix: whatever bytes remain after the fixed
     * 72-byte suffix are the username, no other split is possible. The NUL
     * after it is a belt-and-suspenders boundary marker, not load-bearing —
     * [com.securemessenger.app.ui.screens.setup.SetupScreen]'s
     * `USERNAME_REGEX` already forbids byte 0 from ever appearing inside it.
     */
    fun claimSigningPayload(
        username: String,
        identityPublicKey: ByteArray,
        signingPublicKey: ByteArray,
        timestampMillis: Long
    ): ByteArray =
        "sm-directory-claim|v1|".toByteArray(Charsets.UTF_8) +
            username.toByteArray(Charsets.UTF_8) +
            byteArrayOf(0) +
            identityPublicKey +
            signingPublicKey +
            longToBigEndianBytes(timestampMillis)

    /**
     * One party introducing itself to the other — as the unsolicited opener
     * ([Envelopes.TYPE_INTRO_REQUEST]) or as the reply once accepted
     * ([Envelopes.TYPE_INTRO_ACCEPT]). Both directions carry the same
     * fields: exactly the information a QR code carries today (who I am, and
     * the secret you should address me on), just delivered through the
     * directory's introduction mailbox instead of a camera.
     *
     * [pairSecret] follows the same one-way convention as everywhere else in
     * this app (see `MailboxToken`'s class doc): the sender of *this*
     * envelope minted it and listens on it, so the other side sends on it.
     * A full introduction exchange therefore mints two secrets, exactly like
     * a mutual QR scan does.
     */
    data class SelfIntroduction(
        val senderUserId: String,
        val senderUsername: String,
        val senderIdentityPublicKey: ByteArray,
        val senderSigningPublicKey: ByteArray,
        val pairSecret: ByteArray,
        val directAddress: String?,
        val timestampMillis: Long,
        val signature: ByteArray
    )

    /**
     * The exact bytes a [SelfIntroduction] is signed over. Binding
     * [addresseeIdentityPublicKey] into the signature means a captured
     * signed introduction can't be replayed at anyone but the party it was
     * actually built for — copying someone's genuine request and re-sending
     * it to a different target fails verification there.
     */
    fun introSigningPayload(
        senderIdentityPublicKey: ByteArray,
        addresseeIdentityPublicKey: ByteArray,
        pairSecret: ByteArray,
        timestampMillis: Long
    ): ByteArray =
        "sm-directory-intro|v1|".toByteArray(Charsets.UTF_8) +
            senderIdentityPublicKey +
            addresseeIdentityPublicKey +
            pairSecret +
            longToBigEndianBytes(timestampMillis)

    /**
     * Builds and signs a [SelfIntroduction] payload, ready to pass as `inner`
     * to [Envelopes.seal]. No separate display-name parameter: per
     * `UserProfile.displayNameEncrypted`'s own doc in Entities.kt, a contact
     * only ever learns your *username* — [senderUsername] is what the
     * recipient stores as the new Contact's display name on accept, same as
     * a QR pairing's `n` field already works today.
     */
    fun buildSelfIntroduction(
        senderUserId: String,
        senderUsername: String,
        senderIdentityPublicKey: ByteArray,
        senderSigningPublicKey: ByteArray,
        addresseeIdentityPublicKey: ByteArray,
        pairSecret: ByteArray,
        directAddress: String?,
        timestampMillis: Long,
        sign: (ByteArray) -> ByteArray
    ): JSONObject {
        val signature = sign(
            introSigningPayload(senderIdentityPublicKey, addresseeIdentityPublicKey, pairSecret, timestampMillis)
        )
        return JSONObject().apply {
            put("senderUserId", senderUserId)
            put("senderUsername", senderUsername)
            put("senderIdentityPublicKey", B64.encode(senderIdentityPublicKey))
            put("senderSigningPublicKey", B64.encode(senderSigningPublicKey))
            put("pairSecret", B64.encode(pairSecret))
            directAddress?.let { put("directAddress", it) }
            put("timestamp", timestampMillis)
            put("signature", B64.encode(signature))
        }
    }

    /** Structural parse only — never trust a [SelfIntroduction] this returns until [verifySelfIntroduction] passes. */
    fun parseSelfIntroduction(json: JSONObject): SelfIntroduction? = try {
        SelfIntroduction(
            senderUserId = json.getString("senderUserId"),
            senderUsername = json.getString("senderUsername"),
            senderIdentityPublicKey = B64.decode(json.getString("senderIdentityPublicKey")),
            senderSigningPublicKey = B64.decode(json.getString("senderSigningPublicKey")),
            pairSecret = B64.decode(json.getString("pairSecret")),
            directAddress = json.optString("directAddress").takeIf { it.isNotBlank() },
            timestampMillis = json.getLong("timestamp"),
            signature = B64.decode(json.getString("signature"))
        )
    } catch (e: Exception) {
        null
    }

    /**
     * Verify a parsed [SelfIntroduction] was actually signed by the holder
     * of [trustedSigningPublicKey] and addressed to us — never trust
     * `intro.senderSigningPublicKey` for this, only the caller-supplied
     * [trustedSigningPublicKey]; see the two call sites' very different
     * trust sources:
     *
     *  - Verifying an `intro_request`: we have never talked to this sender
     *    before, so [trustedSigningPublicKey] MUST come from a *fresh*
     *    directory lookup of `intro.senderUsername`. The signing key already
     *    sitting on the payload is only a hint for which key to expect —
     *    trusting it directly would let anyone claim to be anyone.
     *  - Verifying an `intro_accept`: we already captured the recipient's
     *    signing key ourselves, at lookup time, before we ever sent the
     *    original request — the locally-cached copy from that lookup is the
     *    trusted key, no second lookup needed.
     */
    fun verifySelfIntroduction(
        intro: SelfIntroduction,
        ourIdentityPublicKey: ByteArray,
        trustedSigningPublicKey: ByteArray,
        nowMillis: Long = System.currentTimeMillis(),
        toleranceMillis: Long = 10 * 60 * 1000
    ): Boolean {
        if (Math.abs(nowMillis - intro.timestampMillis) > toleranceMillis) return false
        val payload = introSigningPayload(
            intro.senderIdentityPublicKey, ourIdentityPublicKey, intro.pairSecret, intro.timestampMillis
        )
        return LibsodiumWrapper.verifyDetached(payload, intro.signature, trustedSigningPublicKey)
    }

    /**
     * The exact bytes an `/introductions/fetch` proof is signed over —
     * [nonce] is fresh per call so a captured proof can't be replayed to
     * drain a mailbox a second time; [timestampMillis] bounds how long a
     * proof stays valid even before the nonce is checked.
     */
    fun introFetchSigningPayload(nonce: ByteArray, timestampMillis: Long): ByteArray =
        "sm-directory-intro-fetch|v1|".toByteArray(Charsets.UTF_8) + nonce + longToBigEndianBytes(timestampMillis)

    /** Big-endian 8-byte encoding — must match `directory/src/crypto.ts`'s `timestampToBytes` bit-for-bit. */
    private fun longToBigEndianBytes(value: Long): ByteArray {
        val out = ByteArray(8)
        for (i in 0 until 8) out[7 - i] = ((value shr (i * 8)) and 0xFF).toByte()
        return out
    }
}
