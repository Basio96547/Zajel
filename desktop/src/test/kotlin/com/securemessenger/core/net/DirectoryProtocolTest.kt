package com.securemessenger.core.net

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import com.securemessenger.core.Platform
import com.securemessenger.core.crypto.LibsodiumWrapper
import com.securemessenger.core.crypto.MailboxToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Cross-language contract tests. The three hex vectors below were not
 * hand-computed — they came out of `directory/src/crypto.ts`'s actual
 * `claimSigningPayload`/`introFetchSigningPayload`/`introMailboxId` for these
 * exact inputs (Node 26 runs that file directly; see the generating script's
 * history if it ever needs re-deriving). If a change here ever makes these
 * assertions fail, the byte layout has drifted from the TypeScript side —
 * fix the mismatch, do not update the hardcoded hex to match new Kotlin
 * output, or the two platforms silently stop agreeing on what a signature
 * covers.
 */
class DirectoryProtocolTest {

    @Before
    fun installPlatform() {
        Platform.installSodium(LazySodiumJava(SodiumJava()))
    }

    private val identityPublicKey = ByteArray(32) { (it + 1).toByte() }
    private val signingPublicKey = ByteArray(32) { (it + 33).toByte() }

    @Test
    fun `claimSigningPayload matches the TypeScript vector`() {
        val payload = DirectoryProtocol.claimSigningPayload(
            username = "basil_test",
            identityPublicKey = identityPublicKey,
            signingPublicKey = signingPublicKey,
            timestampMillis = 1770000000000L
        )
        assertEquals(
            "736d2d6469726563746f72792d636c61696d7c76317c626173696c5f74657374000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f400000019c1c38a400",
            payload.toHex()
        )
    }

    @Test
    fun `introFetchSigningPayload matches the TypeScript vector`() {
        val nonce = ByteArray(16) { (it + 65).toByte() }
        val payload = DirectoryProtocol.introFetchSigningPayload(nonce, timestampMillis = 1770000005000L)
        assertEquals(
            "736d2d6469726563746f72792d696e74726f2d66657463687c76317c4142434445464748494a4b4c4d4e4f500000019c1c38b788",
            payload.toHex()
        )
    }

    @Test
    fun `introMailboxId matches the TypeScript vector`() {
        assertEquals("61caa997df51a1d787912dd84abbc0a7", MailboxToken.introMailboxId(identityPublicKey))
    }

    @Test
    fun `a claim signature verifies against its own payload`() {
        val (pub, secret) = LibsodiumWrapper.generateSigningKeyPair()
        val payload = DirectoryProtocol.claimSigningPayload("someone", identityPublicKey, pub, 1770000000000L)
        val signature = LibsodiumWrapper.signDetached(payload, secret)
        assertTrue(LibsodiumWrapper.verifyDetached(payload, signature, pub))
    }

    @Test
    fun `changing any single field invalidates the claim signature`() {
        val (pub, secret) = LibsodiumWrapper.generateSigningKeyPair()
        val payload = DirectoryProtocol.claimSigningPayload("someone", identityPublicKey, pub, 1770000000000L)
        val signature = LibsodiumWrapper.signDetached(payload, secret)

        val tamperedUsername = DirectoryProtocol.claimSigningPayload("someone2", identityPublicKey, pub, 1770000000000L)
        assertFalse(LibsodiumWrapper.verifyDetached(tamperedUsername, signature, pub))

        val tamperedTimestamp = DirectoryProtocol.claimSigningPayload("someone", identityPublicKey, pub, 1770000000001L)
        assertFalse(LibsodiumWrapper.verifyDetached(tamperedTimestamp, signature, pub))
    }

    // ---- SelfIntroduction round trip ----

    private fun signer(secretKey: ByteArray): (ByteArray) -> ByteArray = { LibsodiumWrapper.signDetached(it, secretKey) }

    @Test
    fun `a built introduction parses back to the same fields and verifies`() {
        val (senderIdentityPub, _) = LibsodiumWrapper.generateKeyPair()
        val (senderSigningPub, senderSigningSecret) = LibsodiumWrapper.generateSigningKeyPair()
        val (addresseeIdentityPub, _) = LibsodiumWrapper.generateKeyPair()
        val pairSecret = MailboxToken.newPairSecret()

        val json = DirectoryProtocol.buildSelfIntroduction(
            senderUserId = "abc123",
            senderUsername = "basil",
            senderIdentityPublicKey = senderIdentityPub,
            senderSigningPublicKey = senderSigningPub,
            addresseeIdentityPublicKey = addresseeIdentityPub,
            pairSecret = pairSecret,
            directAddress = "192.168.1.5:9000",
            timestampMillis = 1770000000000L,
            sign = signer(senderSigningSecret)
        )

        val parsed = DirectoryProtocol.parseSelfIntroduction(json)
        assertNotNull(parsed)
        parsed!!
        assertEquals("abc123", parsed.senderUserId)
        assertEquals("basil", parsed.senderUsername)
        assertTrue(senderIdentityPub.contentEquals(parsed.senderIdentityPublicKey))
        assertTrue(pairSecret.contentEquals(parsed.pairSecret))
        assertEquals("192.168.1.5:9000", parsed.directAddress)

        assertTrue(
            DirectoryProtocol.verifySelfIntroduction(
                parsed, ourIdentityPublicKey = addresseeIdentityPub,
                trustedSigningPublicKey = senderSigningPub, nowMillis = 1770000000000L
            )
        )
    }

    @Test
    fun `an introduction verified against the wrong addressee key fails`() {
        val (senderIdentityPub, _) = LibsodiumWrapper.generateKeyPair()
        val (senderSigningPub, senderSigningSecret) = LibsodiumWrapper.generateSigningKeyPair()
        val (realAddresseePub, _) = LibsodiumWrapper.generateKeyPair()
        val (someoneElsePub, _) = LibsodiumWrapper.generateKeyPair()

        val json = DirectoryProtocol.buildSelfIntroduction(
            senderUserId = "abc123", senderUsername = "basil",
            senderIdentityPublicKey = senderIdentityPub, senderSigningPublicKey = senderSigningPub,
            addresseeIdentityPublicKey = realAddresseePub, pairSecret = MailboxToken.newPairSecret(),
            directAddress = null, timestampMillis = 1770000000000L, sign = signer(senderSigningSecret)
        )
        val parsed = DirectoryProtocol.parseSelfIntroduction(json)!!

        // A signed request captured off the wire and replayed at a different
        // target must not verify there — this is the whole point of binding
        // the addressee into introSigningPayload.
        assertFalse(
            DirectoryProtocol.verifySelfIntroduction(
                parsed, ourIdentityPublicKey = someoneElsePub,
                trustedSigningPublicKey = senderSigningPub, nowMillis = 1770000000000L
            )
        )
    }

    @Test
    fun `an introduction is rejected if the trusted key doesn't match who actually signed it`() {
        val (senderIdentityPub, _) = LibsodiumWrapper.generateKeyPair()
        val (senderSigningPub, senderSigningSecret) = LibsodiumWrapper.generateSigningKeyPair()
        val (impostorSigningPub, _) = LibsodiumWrapper.generateSigningKeyPair()
        val (addresseePub, _) = LibsodiumWrapper.generateKeyPair()

        val json = DirectoryProtocol.buildSelfIntroduction(
            senderUserId = "abc123", senderUsername = "basil",
            senderIdentityPublicKey = senderIdentityPub, senderSigningPublicKey = senderSigningPub,
            addresseeIdentityPublicKey = addresseePub, pairSecret = MailboxToken.newPairSecret(),
            directAddress = null, timestampMillis = 1770000000000L, sign = signer(senderSigningSecret)
        )
        val parsed = DirectoryProtocol.parseSelfIntroduction(json)!!

        // This is the anti-impersonation check itself: verifying against the
        // payload's OWN embedded senderSigningPublicKey would always pass
        // (an attacker signs with whatever key they like and embeds the
        // matching public key) — only a signing key obtained independently
        // (a fresh directory lookup, in the real caller) can catch this.
        assertFalse(
            DirectoryProtocol.verifySelfIntroduction(
                parsed, ourIdentityPublicKey = addresseePub,
                trustedSigningPublicKey = impostorSigningPub, nowMillis = 1770000000000L
            )
        )
    }

    @Test
    fun `an introduction outside the timestamp tolerance is rejected`() {
        val (senderIdentityPub, _) = LibsodiumWrapper.generateKeyPair()
        val (senderSigningPub, senderSigningSecret) = LibsodiumWrapper.generateSigningKeyPair()
        val (addresseePub, _) = LibsodiumWrapper.generateKeyPair()

        val json = DirectoryProtocol.buildSelfIntroduction(
            senderUserId = "abc123", senderUsername = "basil",
            senderIdentityPublicKey = senderIdentityPub, senderSigningPublicKey = senderSigningPub,
            addresseeIdentityPublicKey = addresseePub, pairSecret = MailboxToken.newPairSecret(),
            directAddress = null, timestampMillis = 1770000000000L, sign = signer(senderSigningSecret)
        )
        val parsed = DirectoryProtocol.parseSelfIntroduction(json)!!

        val farInTheFuture = 1770000000000L + 11 * 60 * 1000
        assertFalse(
            DirectoryProtocol.verifySelfIntroduction(
                parsed, ourIdentityPublicKey = addresseePub,
                trustedSigningPublicKey = senderSigningPub, nowMillis = farInTheFuture
            )
        )
    }

    @Test
    fun `parseSelfIntroduction returns null for a malformed payload`() {
        assertNull(DirectoryProtocol.parseSelfIntroduction(org.json.JSONObject("{}")))
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
