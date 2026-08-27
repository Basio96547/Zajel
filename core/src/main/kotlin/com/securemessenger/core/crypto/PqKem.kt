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

    // Fixed sizes for ml_kem_768, per NIST FIPS 203 — not this specific
    // BouncyCastle version's behavior, the standard itself. Validated here
    // because MLKEMPublicKeyParameters/MLKEMPrivateKeyParameters accept
    // whatever-length byte[] they're handed: too-short input throws
    // (harmless), but an OVERSIZED public key, secret key or ciphertext is
    // silently accepted with the excess ignored, producing a normal-looking
    // shared secret with no error — so a corrupted or malformed PQ field in
    // a handshake wouldn't be rejected at this layer, only surface later as
    // an opaque AEAD failure elsewhere, once the resulting wrong shared
    // secret is actually used.
    private const val PUBLIC_KEY_BYTES = 1184
    private const val SECRET_KEY_BYTES = 2400
    private const val CIPHERTEXT_BYTES = 1088

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
        require(publicKey.size == PUBLIC_KEY_BYTES) { "ML-KEM-768 public key must be $PUBLIC_KEY_BYTES bytes, got ${publicKey.size}" }
        val pub = MLKEMPublicKeyParameters(params, publicKey)
        val enc = MLKEMGenerator(SecureRandom()).generateEncapsulated(pub)
        return Encapsulation(enc.encapsulation, enc.secret)
    }

    /** Responder side: recover the same shared secret from [ciphertext]. */
    fun decapsulate(secretKey: ByteArray, ciphertext: ByteArray): ByteArray {
        require(secretKey.size == SECRET_KEY_BYTES) { "ML-KEM-768 secret key must be $SECRET_KEY_BYTES bytes, got ${secretKey.size}" }
        require(ciphertext.size == CIPHERTEXT_BYTES) { "ML-KEM-768 ciphertext must be $CIPHERTEXT_BYTES bytes, got ${ciphertext.size}" }
        val priv = MLKEMPrivateKeyParameters(params, secretKey)
        return MLKEMExtractor(priv).extractSecret(ciphertext)
    }
}
