package com.securemessenger.app.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * A biometric-gated AES key in the Android Keystore whose ONLY purpose is to
 * make the disguise-reveal prompt cryptographically real. Revealing the
 * messenger requires an auth-bound Keystore operation to actually run
 * (a [Cipher] under a key that only unlocks after a successful strong-biometric
 * authentication) — not merely a boolean a hooked callback could flip. See
 * [BiometricGate].
 */
object DisguiseGateKey {

    private const val ALIAS = "disguise_gate_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /**
     * A fresh ENCRYPT-mode cipher bound to the gate key, to hand to
     * BiometricPrompt.CryptoObject. Throws if the key is missing/invalidated —
     * callers wrap this and fall back to a plain prompt so no one is locked out.
     */
    fun encryptCipher(): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return cipher
    }

    /** Drop the key (e.g. after a biometric-enrollment change invalidated it). */
    fun reset() {
        try {
            keyStore().deleteEntry(ALIAS)
        } catch (_: Exception) {
        }
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun getOrCreateKey(): SecretKey {
        (keyStore().getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).apply {
            setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            setKeySize(256)
            // Require a fresh user authentication for every single use...
            setUserAuthenticationRequired(true)
            // ...and invalidate the key if fingerprints are added/removed, so a
            // freshly-enrolled biometric can't be used to slip past the gate.
            setInvalidatedByBiometricEnrollment(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Per-use (timeout 0) strong-biometric auth.
                setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            }
            // On API < 30, setUserAuthenticationRequired(true) alone already
            // means "per-use, strong-biometric via CryptoObject" (the default
            // validity duration), which is exactly what we want.
        }.build()

        generator.init(spec)
        return generator.generateKey()
    }
}
