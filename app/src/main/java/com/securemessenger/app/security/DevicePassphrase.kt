package com.securemessenger.app.security

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/**
 * A random, high-entropy passphrase for the local encrypted database,
 * generated once and stored in Keystore-backed EncryptedSharedPreferences —
 * the app unlocks itself silently instead of asking the user for a password.
 *
 * Reading/writing EncryptedSharedPreferences goes through the Android
 * Keystore and can take a noticeable moment (worse on some devices/first
 * run) — this used to run as a plain blocking call on the Main thread from
 * every reveal of the disguise, which was slow enough on the real device to
 * produce an ANR ("Input dispatching timed out") that looked to the user
 * like the app simply not responding to taps. Always hop to IO.
 */
object DevicePassphrase {

    private const val KEY = "device_db_passphrase"

    suspend fun getOrCreate(context: Context): CharArray = withContext(Dispatchers.IO) {
        val existing = SecurePreferences.getString(context, KEY)
        if (existing != null) return@withContext existing.toCharArray()

        val random = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val passphrase = Base64.encodeToString(random, Base64.NO_WRAP)
        SecurePreferences.putString(context, KEY, passphrase)
        passphrase.toCharArray()
    }
}
