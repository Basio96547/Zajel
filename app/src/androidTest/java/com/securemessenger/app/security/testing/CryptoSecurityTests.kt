package com.securemessenger.app.security.testing

import com.securemessenger.core.crypto.LibsodiumWrapper
import com.securemessenger.core.crypto.SignalProtocol
import org.junit.Assert.*
import org.junit.Test

/**
 * Cryptographic Security Tests
 * Tests for the encryption layer to ensure proper implementation.
 */
class CryptoSecurityTests {

    @Test
    fun testSymmetricEncryptionDecryption() {
        // Generate random key
        val key = LibsodiumWrapper.generateSecretKey()
        val plaintext = "Hello, World!".toByteArray()

        // Encrypt
        val ciphertext = LibsodiumWrapper.encryptSymmetric(plaintext, key)

        // Decrypt
        val decrypted = LibsodiumWrapper.decryptSymmetric(ciphertext, key)

        // Verify
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun testSymmetricEncryptionAuthentication() {
        val key = LibsodiumWrapper.generateSecretKey()
        val plaintext = "Test message".toByteArray()

        val ciphertext = LibsodiumWrapper.encryptSymmetric(plaintext, key)

        // Tamper with ciphertext
        ciphertext[ciphertext.size / 2] = (ciphertext[ciphertext.size / 2].toInt() xor 0xFF).toByte()

        // Decryption should fail
        try {
            LibsodiumWrapper.decryptSymmetric(ciphertext, key)
            fail("Should have thrown exception for tampered ciphertext")
        } catch (e: Exception) {
            // Expected
        }
    }

    @Test
    fun testAsymmetricEncryption() {
        // Generate keypairs
        val aliceKeyPair = LibsodiumWrapper.generateKeyPair()
        val bobKeyPair = LibsodiumWrapper.generateKeyPair()

        val plaintext = "Secret message from Alice".toByteArray()

        // Alice encrypts for Bob
        val ciphertext = LibsodiumWrapper.encryptAsymmetric(
            plaintext,
            bobKeyPair.first,
            aliceKeyPair.second
        )

        // Bob decrypts
        val decrypted = LibsodiumWrapper.decryptAsymmetric(
            ciphertext,
            aliceKeyPair.first,
            bobKeyPair.second
        )

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun testKeyAgreement() {
        val aliceKeyPair = LibsodiumWrapper.generateKeyPair()
        val bobKeyPair = LibsodiumWrapper.generateKeyPair()

        // Both parties derive the same shared secret
        val aliceSharedSecret = LibsodiumWrapper.deriveSharedSecret(
            aliceKeyPair.second,
            bobKeyPair.first
        )
        val bobSharedSecret = LibsodiumWrapper.deriveSharedSecret(
            bobKeyPair.second,
            aliceKeyPair.first
        )

        assertArrayEquals(aliceSharedSecret, bobSharedSecret)
    }

    @Test
    fun testSecureMemoryWipe() {
        val sensitiveData = ByteArray(32) { 0x42 }

        // Wipe
        LibsodiumWrapper.secureWipe(sensitiveData)

        // Verify all bytes are zeroed
        sensitiveData.forEach { byte ->
            assertEquals(0, byte.toInt())
        }
    }

    @Test
    fun testConstantTimeCompare() {
        val a = byteArrayOf(1, 2, 3, 4, 5)
        val b = byteArrayOf(1, 2, 3, 4, 5)
        val c = byteArrayOf(1, 2, 3, 4, 6)

        assertTrue(LibsodiumWrapper.constantTimeCompare(a, b))
        assertFalse(LibsodiumWrapper.constantTimeCompare(a, c))
        assertFalse(LibsodiumWrapper.constantTimeCompare(a, byteArrayOf(1, 2, 3)))
    }

    @Test
    fun testSignalProtocolEncryption() {
        // Generate identity keys
        val aliceIdentity = SignalProtocol.IdentityKeyPair.generate()
        val bobIdentity = SignalProtocol.IdentityKeyPair.generate()

        // Generate prekeys
        val alicePreKey = SignalProtocol.PreKeyPair.generate(1)
        val bobPreKey = SignalProtocol.PreKeyPair.generate(2)

        // Create protocols
        val aliceProtocol = SignalProtocol(aliceIdentity, alicePreKey, emptyList())
        val bobProtocol = SignalProtocol(bobIdentity, bobPreKey, emptyList())

        // Alice must establish a sending chain before she can encrypt — the
        // protocol no longer derives one from public keys at construction, so a
        // caller has to initialize (X3DH) first. Here Alice initiates toward Bob.
        aliceProtocol.initializeAsInitiator(
            recipientIdentityKey = bobIdentity.publicKey,
            recipientSignedPreKey = bobPreKey.publicKey,
            recipientOneTimePreKey = null
        )

        // Alice sends message to Bob
        val plaintext = "Hello Bob!".toByteArray()
        val encryptedMessage = aliceProtocol.encryptMessage(plaintext)

        // Bob would need to initialize with Alice's keys first
        // This is a simplified test
        assertNotNull(encryptedMessage.ciphertext)
        assertTrue(encryptedMessage.ciphertext.isNotEmpty())
    }

    @Test
    fun testHashFunctionDeterministic() {
        val data = "Test data".toByteArray()

        val hash1 = LibsodiumWrapper.blake2b(data)
        val hash2 = LibsodiumWrapper.blake2b(data)

        assertArrayEquals(hash1, hash2)
    }

    @Test
    fun testHashFunctionCollisionResistance() {
        val data1 = "Data 1".toByteArray()
        val data2 = "Data 2".toByteArray()

        val hash1 = LibsodiumWrapper.blake2b(data1)
        val hash2 = LibsodiumWrapper.blake2b(data2)

        assertFalse(LibsodiumWrapper.constantTimeCompare(hash1, hash2))
    }
}
