package com.securemessenger.core.crypto

import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyGenerationParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyPairGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters
import java.security.SecureRandom

/**
 * PqKem - post-quantum key encapsulation using ML-KEM-768 (NIST FIPS 203).
 *
 * Used to add a quantum-resistant shared secret to the X3DH handshake: the
 * initiator encapsulates to the recipient's ML-KEM public key and mixes the
 * resulting secret into the root key, so breaking the session requires breaking
 * BOTH X25519 and ML-KEM.
 */
object PqKem {

    private val params: MLKEMParameters = MLKEMParameters.ml_kem_768

    data class KeyPair(val publicKey: ByteArray, val secretKey: ByteArray)
    data class Encapsulation(val ciphertext: ByteArray, val sharedSecret: ByteArray)

    fun generateKeyPair(): KeyPair {
        val generator = MLKEMKeyPairGenerator()
        generator.init(MLKEMKeyGenerationParameters(SecureRandom(), params))
        val pair = generator.generateKeyPair()
        val public = (pair.public as MLKEMPublicKeyParameters).encoded
        val secret = (pair.private as MLKEMPrivateKeyParameters).encoded
        return KeyPair(public, secret)
    }

    /** Initiator side: produce a fresh shared secret + its ciphertext for [publicKey]. */
    fun encapsulate(publicKey: ByteArray): Encapsulation {
        val pub = MLKEMPublicKeyParameters(params, publicKey)
        val enc = MLKEMGenerator(SecureRandom()).generateEncapsulated(pub)
        return Encapsulation(enc.encapsulation, enc.secret)
    }

    /** Responder side: recover the same shared secret from [ciphertext]. */
    fun decapsulate(secretKey: ByteArray, ciphertext: ByteArray): ByteArray {
        val priv = MLKEMPrivateKeyParameters(params, secretKey)
        return MLKEMExtractor(priv).extractSecret(ciphertext)
    }
}
