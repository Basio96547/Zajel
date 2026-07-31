package com.securemessenger.app.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests X3DH key agreement symmetry between initiator and responder.
 * Requires Android instrumented test environment for Libsodium native libs.
 */
class SignalProtocolTest {

    @Test
    fun x3dh_initiatorAndResponderDeriveSameRootKey() {
        // Alice (initiator)
        val aliceIdentity = SignalProtocol.IdentityKeyPair.generate()
        val aliceSignedPreKey = SignalProtocol.PreKeyPair.generate(0)
        val aliceEphemeral = com.securemessenger.core.crypto.LibsodiumWrapper.generateKeyPair()

        // Bob (responder)
        val bobIdentity = SignalProtocol.IdentityKeyPair.generate()
        val bobSignedPreKey = SignalProtocol.PreKeyPair.generate(0)

        // Initiator X3DH (Signal spec)
        val dh1Init = LibsodiumWrapper.deriveSharedSecret(aliceIdentity.secretKey, bobSignedPreKey.publicKey)
        val dh2Init = LibsodiumWrapper.deriveSharedSecret(aliceEphemeral.second, bobIdentity.publicKey)
        val dh3Init = LibsodiumWrapper.deriveSharedSecret(aliceEphemeral.second, bobSignedPreKey.publicKey)
        val combinedInit = dh1Init + dh2Init + dh3Init
        val masterInit = LibsodiumWrapper.hkdf(combinedInit, byteArrayOf(), "X3DH".toByteArray(), 64)

        // Responder X3DH
        val dh1Resp = LibsodiumWrapper.deriveSharedSecret(bobSignedPreKey.secretKey, aliceIdentity.publicKey)
        val dh2Resp = LibsodiumWrapper.deriveSharedSecret(bobIdentity.secretKey, aliceEphemeral.first)
        val dh3Resp = LibsodiumWrapper.deriveSharedSecret(bobSignedPreKey.secretKey, aliceEphemeral.first)
        val combinedResp = dh1Resp + dh2Resp + dh3Resp
        val masterResp = LibsodiumWrapper.hkdf(combinedResp, byteArrayOf(), "X3DH".toByteArray(), 64)

        assertArrayEquals(masterInit, masterResp)
    }

    @Test
    fun signalProtocol_encryptDecryptRoundtrip() {
        val aliceIdentity = SignalProtocol.IdentityKeyPair.generate()
        val aliceSignedPreKey = SignalProtocol.PreKeyPair.generate(0)
        val bobIdentity = SignalProtocol.IdentityKeyPair.generate()
        val bobSignedPreKey = SignalProtocol.PreKeyPair.generate(0)

        val aliceProtocol = SignalProtocol(aliceIdentity, aliceSignedPreKey, emptyList())
        aliceProtocol.initializeAsInitiator(
            recipientIdentityKey = bobIdentity.publicKey,
            recipientSignedPreKey = bobSignedPreKey.publicKey
        )

        val ephemeralKey = aliceProtocol.getInitiatorEphemeralPublicKey()
        assertTrue(ephemeralKey != null)

        val bobProtocol = SignalProtocol(bobIdentity, bobSignedPreKey, emptyList())
        bobProtocol.initializeAsResponder(
            initiatorIdentityKey = aliceIdentity.publicKey,
            initiatorEphemeralKey = ephemeralKey!!,
            ourSignedPreKeySecret = bobSignedPreKey.secretKey,
            ourIdentitySecretKey = bobIdentity.secretKey
        )

        val plaintext = "Hello SecureMessenger".toByteArray()
        val encrypted = aliceProtocol.encryptMessage(plaintext)
        val decrypted = bobProtocol.decryptMessage(encrypted)

        assertTrue(plaintext.contentEquals(decrypted))
    }
}
