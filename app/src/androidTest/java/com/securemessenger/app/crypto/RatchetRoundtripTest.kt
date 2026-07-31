package com.securemessenger.app.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Instrumented (on-device) verification of the messaging crypto path — libsodium
 * native libs are only available in the androidTest environment.
 *
 * Covers: full initiator -> responder decryption (with/without a one-time prekey),
 * out-of-order / skipped delivery, and a two-way conversation.
 */
class RatchetRoundtripTest {

    private fun newInitiatorResponder(useOtk: Boolean): Pair<SignalProtocol, SignalProtocol> {
        val aliceId = SignalProtocol.IdentityKeyPair.generate()
        val aliceSpk = SignalProtocol.PreKeyPair.generate(0)
        val bobId = SignalProtocol.IdentityKeyPair.generate()
        val bobSpk = SignalProtocol.PreKeyPair.generate(0)
        val bobOtk = SignalProtocol.PreKeyPair.generate(7)

        val alice = SignalProtocol(aliceId, aliceSpk, emptyList())
        alice.initializeAsInitiator(
            recipientIdentityKey = bobId.publicKey,
            recipientSignedPreKey = bobSpk.publicKey,
            recipientOneTimePreKey = if (useOtk) bobOtk.publicKey else null
        )

        val bob = SignalProtocol(bobId, bobSpk, emptyList())
        bob.initializeAsResponder(
            initiatorIdentityKey = aliceId.publicKey,
            initiatorEphemeralKey = alice.getInitiatorEphemeralPublicKey()!!,
            ourSignedPreKeySecret = bobSpk.secretKey,
            ourIdentitySecretKey = bobId.secretKey,
            ourOneTimePreKeySecret = if (useOtk) bobOtk.secretKey else null
        )
        return alice to bob
    }

    @Test
    fun firstMessageDecrypts_withoutOtk() {
        val (alice, bob) = newInitiatorResponder(useOtk = false)
        val msg = "أهلاً".toByteArray()
        assertArrayEquals(msg, bob.decryptMessage(alice.encryptMessage(msg)))
    }

    @Test
    fun firstMessageDecrypts_withOtk() {
        val (alice, bob) = newInitiatorResponder(useOtk = true)
        val msg = "first message via one-time prekey".toByteArray()
        assertArrayEquals(msg, bob.decryptMessage(alice.encryptMessage(msg)))
    }

    @Test
    fun manyMessagesInOrder() {
        val (alice, bob) = newInitiatorResponder(useOtk = true)
        for (i in 1..20) {
            val m = "message #$i".toByteArray()
            assertArrayEquals(m, bob.decryptMessage(alice.encryptMessage(m)))
        }
    }

    @Test
    fun outOfOrderAndSkippedMessages() {
        val (alice, bob) = newInitiatorResponder(useOtk = true)

        val e1 = alice.encryptMessage("one".toByteArray())
        val e2 = alice.encryptMessage("two".toByteArray())
        val e3 = alice.encryptMessage("three".toByteArray())
        val e4 = alice.encryptMessage("four".toByteArray())

        // Bob receives them jumbled: 1, 3, 2, 4.
        assertEquals("one", String(bob.decryptMessage(e1)))
        assertEquals("three", String(bob.decryptMessage(e3)))
        assertEquals("two", String(bob.decryptMessage(e2)))
        assertEquals("four", String(bob.decryptMessage(e4)))
    }

    /**
     * Hybrid X25519 + ML-KEM handshake: both sides mix the same PQ secret into
     * X3DH and the conversation works. A different PQ secret breaks it — proving
     * the PQ secret genuinely contributes to the session key.
     */
    @Test
    fun hybridPostQuantumHandshake() {
        val aliceId = SignalProtocol.IdentityKeyPair.generate()
        val aliceSpk = SignalProtocol.PreKeyPair.generate(0)
        val bobId = SignalProtocol.IdentityKeyPair.generate()
        val bobSpk = SignalProtocol.PreKeyPair.generate(0)
        val bobOtk = SignalProtocol.PreKeyPair.generate(7)
        val bobKem = PqKem.generateKeyPair()

        val enc = PqKem.encapsulate(bobKem.publicKey)
        val bobSecret = PqKem.decapsulate(bobKem.secretKey, enc.ciphertext)

        val alice = SignalProtocol(aliceId, aliceSpk, emptyList())
        alice.initializeAsInitiator(bobId.publicKey, bobSpk.publicKey, bobOtk.publicKey, pqSharedSecret = enc.sharedSecret)
        val bob = SignalProtocol(bobId, bobSpk, emptyList())
        bob.initializeAsResponder(
            initiatorIdentityKey = aliceId.publicKey,
            initiatorEphemeralKey = alice.getInitiatorEphemeralPublicKey()!!,
            ourSignedPreKeySecret = bobSpk.secretKey,
            ourIdentitySecretKey = bobId.secretKey,
            ourOneTimePreKeySecret = bobOtk.secretKey,
            pqSharedSecret = bobSecret
        )
        assertEquals("pq secure", String(bob.decryptMessage(alice.encryptMessage("pq secure".toByteArray()))))

        // Wrong PQ secret on the responder → cannot decrypt.
        val bobWrong = SignalProtocol(bobId, bobSpk, emptyList())
        bobWrong.initializeAsResponder(
            initiatorIdentityKey = aliceId.publicKey,
            initiatorEphemeralKey = alice.getInitiatorEphemeralPublicKey()!!,
            ourSignedPreKeySecret = bobSpk.secretKey,
            ourIdentitySecretKey = bobId.secretKey,
            ourOneTimePreKeySecret = bobOtk.secretKey,
            pqSharedSecret = ByteArray(32) { 9 }
        )
        try {
            bobWrong.decryptMessage(alice.encryptMessage("nope".toByteArray()))
            org.junit.Assert.fail("Mismatched PQ secret must not decrypt")
        } catch (_: Exception) {
            // expected
        }
    }

    @Test
    fun bidirectionalConversation() {
        val (alice, bob) = newInitiatorResponder(useOtk = true)

        assertEquals("hi bob", String(bob.decryptMessage(alice.encryptMessage("hi bob".toByteArray()))))
        assertEquals("hi alice", String(alice.decryptMessage(bob.encryptMessage("hi alice".toByteArray()))))
        assertEquals("how are you", String(bob.decryptMessage(alice.encryptMessage("how are you".toByteArray()))))
    }

    /**
     * Many alternating rounds. Each direction change makes the peer publish a new
     * ratchet key, so this exercises a DH ratchet step on every turn (the
     * Post-Compromise-Security path).
     */
    @Test
    fun manyBidirectionalRounds_exerciseDhRatchet() {
        val (alice, bob) = newInitiatorResponder(useOtk = true)
        for (i in 1..15) {
            val a = "a$i"
            assertEquals(a, String(bob.decryptMessage(alice.encryptMessage(a.toByteArray()))))
            val b = "b$i"
            assertEquals(b, String(alice.decryptMessage(bob.encryptMessage(b.toByteArray()))))
        }
    }

    /**
     * A message from an *old* sending chain arrives after the receiver has already
     * advanced to a new DH-ratchet chain. It must still decrypt via the
     * previous-chain-length skip mechanism.
     */
    @Test
    fun outOfOrderAcrossDhRatchet() {
        val (alice, bob) = newInitiatorResponder(useOtk = true)

        val a1 = alice.encryptMessage("a1".toByteArray())
        val a2 = alice.encryptMessage("a2".toByteArray()) // delayed; same (old) chain

        assertEquals("a1", String(bob.decryptMessage(a1)))
        // Bob replies -> Alice ratchets on receive.
        assertEquals("b1", String(alice.decryptMessage(bob.encryptMessage("b1".toByteArray()))))
        // Alice sends on her NEW chain -> Bob ratchets to it.
        val a3 = alice.encryptMessage("a3".toByteArray())
        assertEquals("a3", String(bob.decryptMessage(a3)))
        // The delayed a2 (from the old chain) finally arrives — still decrypts.
        assertEquals("a2", String(bob.decryptMessage(a2)))
    }

    /**
     * Serializing a live session (exportState) and restoring it into a fresh
     * object (importState) must resume the conversation seamlessly — including a
     * message that was skipped before the "restart". This is what lets a real
     * session survive an app restart via the encrypted DB.
     */
    @Test
    fun sessionStateSurvivesExportImport() {
        val aliceId = SignalProtocol.IdentityKeyPair.generate()
        val aliceSpk = SignalProtocol.PreKeyPair.generate(0)
        val bobId = SignalProtocol.IdentityKeyPair.generate()
        val bobSpk = SignalProtocol.PreKeyPair.generate(0)
        val bobOtk = SignalProtocol.PreKeyPair.generate(7)

        val alice = SignalProtocol(aliceId, aliceSpk, emptyList())
        alice.initializeAsInitiator(bobId.publicKey, bobSpk.publicKey, bobOtk.publicKey)
        val bob = SignalProtocol(bobId, bobSpk, emptyList())
        bob.initializeAsResponder(
            initiatorIdentityKey = aliceId.publicKey,
            initiatorEphemeralKey = alice.getInitiatorEphemeralPublicKey()!!,
            ourSignedPreKeySecret = bobSpk.secretKey,
            ourIdentitySecretKey = bobId.secretKey,
            ourOneTimePreKeySecret = bobOtk.secretKey
        )

        // Exchange, ratchet, and create a skipped message (a2 delayed).
        assertEquals("m1", String(bob.decryptMessage(alice.encryptMessage("m1".toByteArray()))))
        assertEquals("r1", String(alice.decryptMessage(bob.encryptMessage("r1".toByteArray()))))
        val a2 = alice.encryptMessage("a2".toByteArray())
        val a3 = alice.encryptMessage("a3".toByteArray())
        assertEquals("a3", String(bob.decryptMessage(a3))) // skips a2 → cached

        // "Restart" Bob through export/import.
        val state = bob.exportState()
        val bobResumed = SignalProtocol(bobId, bobSpk, emptyList())
        bobResumed.importState(state)

        // The previously-skipped a2 still decrypts (skipped keys persisted)...
        assertEquals("a2", String(bobResumed.decryptMessage(a2)))
        // ...and the conversation continues both ways.
        assertEquals("a4", String(bobResumed.decryptMessage(alice.encryptMessage("a4".toByteArray()))))
        assertEquals("r2", String(alice.decryptMessage(bobResumed.encryptMessage("r2".toByteArray()))))
    }

    /**
     * Simulates the receiver restarting: its in-memory session is gone, so it
     * rebuilds a fresh responder session from the same X3DH inputs. Because the
     * chains are deterministic, it re-derives the same keys and catches up to the
     * sender's current position via the skipped-key mechanism.
     */
    @Test
    fun receiverRebuildsSessionAfterRestart() {
        val aliceId = SignalProtocol.IdentityKeyPair.generate()
        val aliceSpk = SignalProtocol.PreKeyPair.generate(0)
        val bobId = SignalProtocol.IdentityKeyPair.generate()
        val bobSpk = SignalProtocol.PreKeyPair.generate(0)
        val bobOtk = SignalProtocol.PreKeyPair.generate(7)

        val alice = SignalProtocol(aliceId, aliceSpk, emptyList())
        alice.initializeAsInitiator(bobId.publicKey, bobSpk.publicKey, bobOtk.publicKey)
        val ephemeral = alice.getInitiatorEphemeralPublicKey()!!

        // Alice sends three messages; Bob only "receives" the first before restart.
        val e1 = alice.encryptMessage("before restart".toByteArray())
        val e2 = alice.encryptMessage("during outage".toByteArray())
        val e3 = alice.encryptMessage("after restart".toByteArray())

        fun freshBob(): SignalProtocol {
            val bob = SignalProtocol(bobId, bobSpk, emptyList())
            bob.initializeAsResponder(
                initiatorIdentityKey = aliceId.publicKey,
                initiatorEphemeralKey = ephemeral,
                ourSignedPreKeySecret = bobSpk.secretKey,
                ourIdentitySecretKey = bobId.secretKey,
                ourOneTimePreKeySecret = bobOtk.secretKey
            )
            return bob
        }

        val bobBefore = freshBob()
        assertEquals("before restart", String(bobBefore.decryptMessage(e1)))

        // Restart: brand-new Bob session, then messages 2 and 3 arrive.
        val bobAfter = freshBob()
        assertEquals("during outage", String(bobAfter.decryptMessage(e2)))
        assertEquals("after restart", String(bobAfter.decryptMessage(e3)))
    }
}
