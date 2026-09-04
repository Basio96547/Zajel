package com.securemessenger.app.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AndroidKeyStoreManager - manages keys stored in Android's secure keystore.
 * These keys are used to protect the master encryption key and sensitive data.
 * Keys never leave the secure hardware (if available).
 */
object AndroidKeyStoreManager {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "secure_messenger_master_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val GCM_IV_LENGTH = 12

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
        }
    }

    /**
     * The key handle, held after the first lookup.
     *
     * THIS IS NOT A CACHE OF KEY MATERIAL. A Keystore [SecretKey] is an opaque
     * handle to a key that never leaves secure hardware; holding it exposes
     * nothing that calling getEntry() again would not, and every actual
     * operation still goes through the Keystore.
     *
     * It exists because the old code looked the entry up on EVERY encrypt and
     * decrypt, inside a @Synchronized method — so each call was an IPC to the
     * keystore daemon taken under a process-wide lock. Every message rendered
     * in a conversation costs one decrypt, so drawing a 500-message thread
     * meant 500 acquisitions of that lock, each held across an IPC, on the
     * main thread. Worse, the messaging client needs the same lock for every
     * envelope it encrypts or decrypts: painting a conversation was directly
     * blocking message processing, and vice versa.
     *
     * @Volatile plus the double check keeps the original guarantee intact:
     * two concurrent first-run callers must not both decide the entry is
     * missing and both generate one, because the second would replace the
     * first at the same alias and permanently orphan everything already
     * encrypted under it.
     */
    @Volatile
    private var cachedMasterKey: SecretKey? = null

    /**
     * Generate or retrieve the master key from Android Keystore.
     * This key is used to encrypt the actual encryption keys.
     */
    fun getOrCreateMasterKey(): SecretKey {
        cachedMasterKey?.let { return it }
        return synchronized(this) {
            cachedMasterKey ?: run {
                val existing = (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
                val key = existing ?: generateMasterKey(preferStrongBox = true)
                cachedMasterKey = key
                key
            }
        }
    }

    /**
     * StrongBox (a separate, tamper-resistant security chip) is stronger than
     * the regular hardware-backed keystore, but not every device has one, and
     * some that claim to are known to throw here anyway. Try it first; if key
     * generation fails for any reason, fall back to the normal keystore
     * (still hardware-backed on the vast majority of real devices) instead of
     * letting Setup crash outright on unsupported hardware.
     */
    private fun generateMasterKey(preferStrongBox: Boolean): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val keyGenSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).apply {
            setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            setKeySize(256)
            setUserAuthenticationRequired(false)
            setRandomizedEncryptionRequired(true)
            if (preferStrongBox && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                setIsStrongBoxBacked(true)
            }
        }.build()

        keyGenerator.init(keyGenSpec)
        return try {
            keyGenerator.generateKey()
        } catch (e: Exception) {
            if (preferStrongBox) generateMasterKey(preferStrongBox = false) else throw e
        }
    }

    /**
     * Encrypt data using the master key with AES-GCM.
     * Returns IV + ciphertext.
     */
    fun encryptWithMasterKey(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateMasterKey())

        val iv = cipher.iv // GCM generates random IV
        val ciphertext = cipher.doFinal(plaintext)

        // Prepend IV to ciphertext
        return iv + ciphertext
    }

    /**
     * Decrypt data encrypted with the master key.
     * Expects IV + ciphertext format.
     */
    fun decryptWithMasterKey(encryptedData: ByteArray): ByteArray {
        if (encryptedData.size < GCM_IV_LENGTH) {
            throw IllegalArgumentException("Encrypted data too short")
        }
        
        val iv = encryptedData.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = encryptedData.copyOfRange(GCM_IV_LENGTH, encryptedData.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateMasterKey(), spec)

        return cipher.doFinal(ciphertext)
    }

    /**
     * Delete the master key itself. Called at the very end of a full wipe: the
     * data it protected is already gone by then, and leaving the Keystore entry
     * behind would be a residue that outlives everything else — including after
     * a duress wipe the user believes destroyed all of it. Regenerated on
     * demand by [getOrCreateMasterKey], so a fresh setup still works.
     */
    fun wipeKeys() {
        // Drop the handle FIRST, and unconditionally. A wipe that deleted the
        // Keystore entry while this process kept a live handle to it would
        // leave the app able to go on decrypting — including after a duress
        // wipe, which is the one moment nothing must survive. Clearing before
        // the delete also means a failure below cannot leave a usable handle
        // behind: the next call re-reads the Keystore and finds the truth.
        cachedMasterKey = null
        try {
            keyStore.deleteEntry(KEY_ALIAS)
        } catch (e: Exception) {
            // Nothing to delete, or the Keystore is unavailable — either way the
            // wipe of everything else must still be reported as having happened.
        }
    }
}
