package com.securemessenger.app.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * SecurePreferences - Encrypted key-value storage using AndroidX Security.
 * Used for storing non-sensitive preferences with encryption.
 */
object SecurePreferences {

    // Calculator-themed, like the database file — this name is what shows up
    // if anyone inspects the app's private storage directly.
    private const val PREFS_NAME = "calc_settings"

    /**
     * Get or create the MasterKey for EncryptedSharedPreferences.
     */
    private fun getMasterKey(context: Context): MasterKey {
        return MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    /**
     * Get encrypted shared preferences instance.
     */
    fun getPreferences(context: Context) = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        getMasterKey(context),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /**
     * Store a string value securely.
     */
    fun putString(context: Context, key: String, value: String) {
        getPreferences(context).edit().putString(key, value).apply()
    }

    /**
     * Retrieve a string value securely.
     */
    fun getString(context: Context, key: String, defaultValue: String? = null): String? {
        return getPreferences(context).getString(key, defaultValue)
    }

    /**
     * Store a boolean value securely.
     */
    fun putBoolean(context: Context, key: String, value: Boolean) {
        getPreferences(context).edit().putBoolean(key, value).apply()
    }

    /**
     * Retrieve a boolean value securely.
     */
    fun getBoolean(context: Context, key: String, defaultValue: Boolean = false): Boolean {
        return getPreferences(context).getBoolean(key, defaultValue)
    }

    /**
     * Store an integer value securely.
     */
    fun putInt(context: Context, key: String, value: Int) {
        getPreferences(context).edit().putInt(key, value).apply()
    }

    /**
     * Retrieve an integer value securely.
     */
    fun getInt(context: Context, key: String, defaultValue: Int = 0): Int {
        return getPreferences(context).getInt(key, defaultValue)
    }

    /**
     * Store a long value securely.
     */
    fun putLong(context: Context, key: String, value: Long) {
        getPreferences(context).edit().putLong(key, value).apply()
    }

    /**
     * Retrieve a long value securely.
     */
    fun getLong(context: Context, key: String, defaultValue: Long = 0L): Long {
        return getPreferences(context).getLong(key, defaultValue)
    }

    /**
     * Remove a key.
     */
    fun remove(context: Context, key: String) {
        getPreferences(context).edit().remove(key).apply()
    }

    /**
     * Wipe every stored preference — used by a full/duress data wipe so the
     * access code, duress code and DB passphrase can't be recovered afterwards.
     */
    fun clearAll(context: Context) {
        getPreferences(context).edit().clear().apply()
    }
}
