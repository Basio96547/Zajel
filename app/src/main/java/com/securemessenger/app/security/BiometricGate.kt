package com.securemessenger.app.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Gates revealing the hidden messenger behind the device's own biometric/PIN
 * lock — so knowing the calculator access code alone (something someone
 * glancing over a shoulder could learn) isn't enough on its own; the phone
 * must also be unlocked by whoever is holding it right now.
 *
 * On devices with a strong (Class 3) biometric the reveal is bound to a real
 * Keystore operation ([DisguiseGateKey]): a hooked "auth succeeded" callback
 * can't reveal the app without actually passing the biometric, because the
 * gate only opens if an auth-unlocked cipher successfully runs. Weaker
 * configurations fall back to a plain prompt so no one is locked out.
 */
object BiometricGate {

    private const val ALLOWED = BiometricManager.Authenticators.BIOMETRIC_WEAK or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

    // A tiny constant we actually push through the auth-bound cipher to PROVE a
    // real biometric happened.
    private val GATE_PROBE = byteArrayOf(0x53, 0x4d, 0x00, 0x01)

    /**
     * Whether the device has something enrolled (fingerprint, face, or a
     * PIN/pattern/password) to authenticate against. If nothing is enrolled we
     * deliberately fail open (skip the gate) rather than lock the user out of
     * their own messages over a control the OS itself can't provide here.
     */
    fun canAuthenticate(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(ALLOWED) == BiometricManager.BIOMETRIC_SUCCESS

    fun authenticate(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onFailure: () -> Unit
    ) {
        val strongAvailable = BiometricManager.from(activity)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

        if (strongAvailable) {
            // Recreate the key once if it was invalidated (biometrics changed).
            val cipher = runCatching { DisguiseGateKey.encryptCipher() }.getOrNull()
                ?: runCatching { DisguiseGateKey.reset(); DisguiseGateKey.encryptCipher() }.getOrNull()
            if (cipher != null) {
                authenticateCryptoBound(activity, cipher, onSuccess, onFailure)
                return
            }
        }
        // Weak biometric / device-credential only, or crypto key unavailable.
        authenticatePlain(activity, onSuccess, onFailure)
    }

    private fun authenticateCryptoBound(
        activity: FragmentActivity,
        cipher: javax.crypto.Cipher,
        onSuccess: () -> Unit,
        onFailure: () -> Unit
    ) {
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("تأكيد الهوية")
            .setSubtitle("افتح قفل الجهاز للدخول إلى المراسل")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("إلغاء")
            .build()

        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    // Run the auth-unlocked cipher; reveal ONLY if it actually
                    // works — the step a mere callback hook can't fake.
                    val proven = try {
                        result.cryptoObject?.cipher?.doFinal(GATE_PROBE) != null
                    } catch (_: Exception) {
                        false
                    }
                    if (proven) onSuccess() else onFailure()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onFailure()
                }

                override fun onAuthenticationFailed() {
                    // One bad reading — the prompt stays open for another try.
                }
            }
        )
        try {
            prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
        } catch (_: Exception) {
            authenticatePlain(activity, onSuccess, onFailure)
        }
    }

    private fun authenticatePlain(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onFailure: () -> Unit
    ) {
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("تأكيد الهوية")
            .setSubtitle("افتح قفل الجهاز للدخول إلى المراسل")
            .setAllowedAuthenticators(ALLOWED)
            .build()

        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Includes user-cancel, lockout, and any hard failure — all
                    // treated the same: stay hidden behind the calculator.
                    onFailure()
                }

                override fun onAuthenticationFailed() {}
            }
        )
        prompt.authenticate(promptInfo)
    }
}
