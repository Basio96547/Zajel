package com.securemessenger.core.crypto

import com.securemessenger.core.B64

/**
 * Per-envelope delivery token used to authenticate transport `ack` frames.
 *
 * The token is a keyed hash of the envelope's routing id under a key derived
 * (HKDF, domain-separated) from the SENDER's own identity secret. The sender
 * places it sealed inside each outgoing envelope, so only the true recipient
 * can read it; the recipient echoes it back in the `ack`, and the sender
 * re-derives and verifies it before clearing the outbox entry. An on-path
 * observer sees the cleartext id but can neither read the sealed token nor
 * recompute it (it doesn't hold the sender's identity secret), so it can't
 * forge an `ack`.
 *
 * Extracted as a pure function so the security-critical derivation is directly
 * testable without any Context / database / network.
 */
object AckToken {

    private const val INFO = "ack-mac-v1"
    private const val TOKEN_BYTES = 16

    /** Deterministic token for [envelopeId], bound to [identitySecret]. */
    fun compute(identitySecret: ByteArray, envelopeId: String): String {
        val macKey = LibsodiumWrapper.hkdf(
            identitySecret, byteArrayOf(), INFO.toByteArray(Charsets.UTF_8), 32
        )
        val token = LibsodiumWrapper.blake2b(
            envelopeId.toByteArray(Charsets.UTF_8), key = macKey, length = TOKEN_BYTES
        )
        return B64.encode(token)
    }
}
