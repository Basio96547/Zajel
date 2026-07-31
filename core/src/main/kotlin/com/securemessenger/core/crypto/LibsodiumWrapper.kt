package com.securemessenger.core.crypto

import com.goterl.lazysodium.interfaces.Box
import com.goterl.lazysodium.interfaces.SecretBox
import com.goterl.lazysodium.interfaces.Sign
import com.securemessenger.core.Platform

/**
 * LibsodiumWrapper - high-level encryption API over libsodium.
 *
 * The concrete binding (Android .so vs desktop native) is injected through
 * [Platform.installSodium], so this file — and therefore every key derivation
 * the two platforms must agree on — is one implementation, not two.
 */
object LibsodiumWrapper {

    private val lazySodium get() = Platform.sodium

    fun generateSecretKey(keyLength: Int = 32): ByteArray {
        return lazySodium.randomBytesBuf(keyLength)
    }

    fun generateKeyPair(): Pair<ByteArray, ByteArray> {
        val keyPair = lazySodium.cryptoBoxKeypair()
        return Pair(keyPair.publicKey.asBytes, keyPair.secretKey.asBytes)
    }

    fun generateSigningKeyPair(): Pair<ByteArray, ByteArray> {
        val keyPair = lazySodium.cryptoSignKeypair()
        return Pair(keyPair.publicKey.asBytes, keyPair.secretKey.asBytes)
    }

    // crypto_secretbox (XSalsa20-Poly1305): authenticated encryption of the
    // body. It does NOT support associated data — callers that need to bind
    // extra fields must fold them into the key/plaintext, not pass them here
    // (there used to be an `associatedData` parameter that was silently ignored,
    // which falsely implied the header was authenticated).
    fun encryptSymmetric(plaintext: ByteArray, key: ByteArray): ByteArray {
        val nonce = lazySodium.randomBytesBuf(SecretBox.NONCEBYTES)
        val ciphertext = ByteArray(plaintext.size + SecretBox.MACBYTES)

        val result = lazySodium.cryptoSecretBoxEasy(
            ciphertext, plaintext, plaintext.size.toLong(), nonce, key
        )

        if (!result) {
            throw RuntimeException("Encryption failed")
        }

        return nonce + ciphertext
    }

    fun decryptSymmetric(ciphertext: ByteArray, key: ByteArray): ByteArray {
        if (ciphertext.size < SecretBox.NONCEBYTES + SecretBox.MACBYTES) {
            throw IllegalArgumentException("Ciphertext too short")
        }

        val nonce = ciphertext.copyOfRange(0, SecretBox.NONCEBYTES)
        val actualCiphertext = ciphertext.copyOfRange(SecretBox.NONCEBYTES, ciphertext.size)

        val plaintext = ByteArray(actualCiphertext.size - SecretBox.MACBYTES)
        val result = lazySodium.cryptoSecretBoxOpenEasy(
            plaintext, actualCiphertext, actualCiphertext.size.toLong(), nonce, key
        )

        if (!result) {
            throw RuntimeException("Decryption failed")
        }

        return plaintext
    }

    fun encryptAsymmetric(plaintext: ByteArray, recipientPublicKey: ByteArray, senderSecretKey: ByteArray): ByteArray {
        val nonce = lazySodium.randomBytesBuf(Box.NONCEBYTES)
        val ciphertext = ByteArray(plaintext.size + Box.MACBYTES)

        val result = lazySodium.cryptoBoxEasy(
            ciphertext, plaintext, plaintext.size.toLong(), nonce, recipientPublicKey, senderSecretKey
        )

        if (!result) {
            throw RuntimeException("Asymmetric encryption failed")
        }

        return nonce + ciphertext
    }

    fun decryptAsymmetric(ciphertext: ByteArray, senderPublicKey: ByteArray, recipientSecretKey: ByteArray): ByteArray {
        if (ciphertext.size < Box.NONCEBYTES + Box.MACBYTES) {
            throw IllegalArgumentException("Ciphertext too short")
        }

        val nonce = ciphertext.copyOfRange(0, Box.NONCEBYTES)
        val actualCiphertext = ciphertext.copyOfRange(Box.NONCEBYTES, ciphertext.size)

        val plaintext = ByteArray(actualCiphertext.size - Box.MACBYTES)
        val result = lazySodium.cryptoBoxOpenEasy(
            plaintext, actualCiphertext, actualCiphertext.size.toLong(), nonce, senderPublicKey, recipientSecretKey
        )

        if (!result) {
            throw RuntimeException("Asymmetric decryption failed")
        }

        return plaintext
    }

    /**
     * Anonymous "sealed box": encrypt to a recipient's public key such that only
     * the recipient can open it and the ciphertext reveals nothing about the
     * sender (libsodium crypto_box_seal). Used for sealed-sender metadata privacy.
     */
    fun sealTo(message: ByteArray, recipientPublicKey: ByteArray): ByteArray {
        val cipher = ByteArray(message.size + Box.SEALBYTES)
        val ok = lazySodium.cryptoBoxSeal(cipher, message, message.size.toLong(), recipientPublicKey)
        if (!ok) throw RuntimeException("Seal failed")
        return cipher
    }

    /**
     * Open a sealed box with the recipient's own keypair.
     */
    fun sealOpen(sealed: ByteArray, recipientPublicKey: ByteArray, recipientSecretKey: ByteArray): ByteArray {
        if (sealed.size < Box.SEALBYTES) throw IllegalArgumentException("Sealed box too short")
        val message = ByteArray(sealed.size - Box.SEALBYTES)
        val ok = lazySodium.cryptoBoxSealOpen(message, sealed, sealed.size.toLong(), recipientPublicKey, recipientSecretKey)
        if (!ok) throw RuntimeException("Seal open failed")
        return message
    }

    fun deriveSharedSecret(mySecretKey: ByteArray, theirPublicKey: ByteArray): ByteArray {
        val sharedSecret = ByteArray(32)
        val result = lazySodium.cryptoScalarMult(sharedSecret, mySecretKey, theirPublicKey)

        if (!result) {
            throw RuntimeException("Key derivation failed")
        }

        return sharedSecret
    }

    fun blake2b(data: ByteArray, key: ByteArray? = null, length: Int = 64): ByteArray {
        val hash = ByteArray(length)
        val keyBytes = key ?: byteArrayOf()

        lazySodium.cryptoGenericHash(hash, length, data, data.size.toLong(), keyBytes, keyBytes.size)
        return hash
    }

    fun hkdf(inputKeyMaterial: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 64): ByteArray {
        val prk = blake2b(inputKeyMaterial, salt, 32)
        val output = ByteArray(length)

        var counter = 1
        var offset = 0
        var prevBlock = byteArrayOf()

        while (offset < length) {
            val block = blake2b(prevBlock + info + byteArrayOf(counter.toByte()), prk, 32)
            val copyLen = minOf(32, length - offset)
            System.arraycopy(block, 0, output, offset, copyLen)
            offset += copyLen
            prevBlock = block
            counter++
        }

        return output
    }

    fun sign(message: ByteArray, secretKey: ByteArray): ByteArray {
        val signature = ByteArray(Sign.ED25519_BYTES)
        val result = lazySodium.cryptoSignDetached(signature, message, message.size.toLong(), secretKey)

        if (!result) {
            throw RuntimeException("Signing failed")
        }

        return signature + message
    }

    /** Detached Ed25519 signature — just the 64 signature bytes, message sent separately. */
    fun signDetached(message: ByteArray, secretKey: ByteArray): ByteArray {
        val signature = ByteArray(Sign.ED25519_BYTES)
        val result = lazySodium.cryptoSignDetached(signature, message, message.size.toLong(), secretKey)
        if (!result) {
            throw RuntimeException("Signing failed")
        }
        return signature
    }

    /** Verify a detached Ed25519 signature. Never throws — returns false on any failure. */
    fun verifyDetached(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        if (signature.size != Sign.ED25519_BYTES) return false
        return try {
            lazySodium.cryptoSignVerifyDetached(signature, message, message.size, publicKey)
        } catch (_: Exception) {
            false
        }
    }

    fun verify(signedMessage: ByteArray, publicKey: ByteArray): ByteArray {
        if (signedMessage.size < Sign.ED25519_BYTES) {
            throw IllegalArgumentException("Signed message too short")
        }

        val signature = signedMessage.copyOfRange(0, Sign.ED25519_BYTES)
        val message = signedMessage.copyOfRange(Sign.ED25519_BYTES, signedMessage.size)

        val result = lazySodium.cryptoSignVerifyDetached(signature, message, message.size, publicKey)

        if (!result) {
            throw RuntimeException("Signature verification failed")
        }

        return message
    }

    fun secureWipe(array: ByteArray) {
        lazySodium.sodium.sodium_memzero(array, array.size)
    }

    fun secureWipe(vararg arrays: ByteArray) {
        arrays.forEach { secureWipe(it) }
    }

    fun constantTimeCompare(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        return lazySodium.sodium.sodium_memcmp(a, b, a.size) == 0
    }
}
