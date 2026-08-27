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

        // Encode to bytes (Base64.encode), not a String (encodeToString) —
        // then convert those bytes directly to the CharArray this function
        // returns, without an intermediate String. Base64 output is always
        // plain ASCII, so the byte-for-byte cast below is exact and
        // lossless. One String still has to be built, at the putString call
        // below, purely because Android's SharedPreferences API has no
        // CharArray-based write — that's an unavoidable platform limit, not
        // something this function can route around — but this way it's ONE
        // String construction instead of two (encodeToString would have
        // created its own, separate from the one toCharArray() used to
        // build the very value this function returns).
        val random = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val encodedBytes = Base64.encode(random, Base64.NO_WRAP)
        val chars = CharArray(encodedBytes.size) { encodedBytes[it].toInt().toChar() }
        java.util.Arrays.fill(random, 0)
        java.util.Arrays.fill(encodedBytes, 0)
        SecurePreferences.putString(context, KEY, String(chars))
        chars
    }
}
