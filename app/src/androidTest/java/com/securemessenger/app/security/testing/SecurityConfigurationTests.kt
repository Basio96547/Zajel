package com.securemessenger.app.security.testing

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * These assertions read the MERGED, INSTALLED manifest via PackageManager —
 * i.e. what's actually on the device after Gradle's manifest merger combines
 * this app's AndroidManifest.xml with every dependency library's own
 * manifest. That is why the allow-lists below include entries never written
 * in our own AndroidManifest.xml (they're contributed by a library) — this
 * file was previously never run on a real device (no emulator was available
 * for most of this project's security-hardening work); running it for the
 * first time on 2026-07-09 surfaced that both permission lists and the
 * cleartext-traffic assertion had drifted from the app's actual, evolved
 * feature set. Fixed here rather than papering over with a skip/ignore.
 */
@RunWith(AndroidJUnit4::class)
class SecurityConfigurationTests {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun testNoDangerousPermissions() {
        // CAMERA and RECORD_AUDIO are deliberately NOT in this list (see the
        // allow-list below) — they are genuine, security-relevant features of
        // this app: CAMERA is how a contact's identity key is verified
        // in-person via QR (the app's actual MITM-resistance/TOFU mechanism —
        // NewChatScreen, KeyVerificationScreen), and RECORD_AUDIO is voice
        // messages (VoiceRecorder). Everything below this app genuinely has no
        // use for and must never request.
        val dangerousPermissions = listOf(
            "android.permission.READ_CONTACTS",
            "android.permission.WRITE_CONTACTS",
            "android.permission.READ_SMS",
            "android.permission.WRITE_SMS",
            "android.permission.RECEIVE_SMS",
            "android.permission.READ_CALL_LOG",
            "android.permission.WRITE_CALL_LOG",
            "android.permission.PROCESS_OUTGOING_CALLS",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION"
        )

        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        )

        val requestedPermissions = packageInfo.requestedPermissions ?: emptyArray()

        dangerousPermissions.forEach { permission ->
            assertFalse(
                "App should not request dangerous permission: $permission",
                requestedPermissions.contains(permission)
            )
        }
    }

    @Test
    fun testRequiredPermissionsOnly() {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        )

        val allowedPermissions = setOf(
            // Declared directly in AndroidManifest.xml.
            "android.permission.INTERNET",
            "android.permission.USE_BIOMETRIC",
            "android.permission.VIBRATE",
            "android.permission.WAKE_LOCK",
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.CHANGE_WIFI_STATE",
            // Multicast is needed for the mDNS/NSD packets local peer
            // discovery relies on — some Wi-Fi drivers/APs otherwise filter them.
            "android.permission.CHANGE_WIFI_MULTICAST_STATE",
            "android.permission.ACCESS_NETWORK_STATE",
            // Runtime-requested, only while actually recording:
            "android.permission.RECORD_AUDIO",   // voice messages
            // Background delivery (MessengerService), opt-in and off by default.
            // Android offers no way to keep a socket alive from the background
            // without a foreground service, and no foreground service without a
            // visible notification — so these three travel together, and the
            // feature is a switch rather than a constant precisely because they
            // weaken the calculator disguise. Added to the manifest when that
            // feature landed; this list was not updated with it, which is what
            // made this test fail rather than any permission creep.
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            "android.permission.POST_NOTIFICATIONS",
            // Contributed by dependency library manifests, not requested by us:
            "android.permission.CAMERA",          // zxing-android-embedded (QR scanning)
            "android.permission.USE_FINGERPRINT", // androidx.biometric's pre-API28 compat shim
            // Platform-synthesized (Android 13+) for a dependency's dynamically
            // registered, non-exported broadcast receiver — not something any
            // app manifest declares directly.
            "${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        )

        val requestedPermissions = packageInfo.requestedPermissions ?: emptyArray()

        // Collected and asserted once, rather than failing on the first
        // offender: permissions usually arrive in groups (a feature pulls in
        // three at a time, as the background-delivery set above shows), and a
        // failure naming only the first sends the reader round the loop once
        // per permission instead of showing the whole drift at a glance.
        val unexpected = requestedPermissions.filterNot { allowedPermissions.contains(it) }
        assertTrue(
            "App requests ${unexpected.size} permission(s) not on the reviewed allowlist: " +
                "${unexpected.joinToString()}. Each one is a deliberate decision — add it here " +
                "WITH the reason it exists, or remove it from the manifest.",
            unexpected.isEmpty()
        )
    }

    @Test
    fun testBackupDisabled() {
        val appInfo = context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.GET_META_DATA
        )

        val allowBackup = (appInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP) != 0
        assertFalse("Backup should be disabled", allowBackup)
    }

    /**
     * Cleartext IS intentionally allowed — there is no external server at
     * all; every socket this app opens is a direct ws:// connection to
     * another instance of itself on the same local network, and the payload
     * riding over it is already fully Double-Ratchet/sealed-sender encrypted
     * at the application layer before it ever touches the socket (see
     * network_security_config.xml's own comment). A prior version of this
     * test asserted the opposite, left over from an earlier architecture that
     * talked to a real external backend over HTTPS — this test was never
     * actually run on a device to catch that drift until 2026-07-09.
     */
    @Test
    fun testCleartextTrafficIntentionallyAllowedForLocalP2P() {
        val appInfo = context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.GET_META_DATA
        )
        val allowsCleartext = (appInfo.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC) != 0
        assertTrue(
            "Expected cleartext to be allowed for the local-only P2P socket — " +
                "if this now fails, either the manifest changed (fine, update this test) " +
                "or the app started talking to a real external endpoint again (then this " +
                "flag SHOULD be tightened to a domain-scoped exception, not left broad).",
            allowsCleartext
        )
    }

    @Test
    fun testSecureRandomGeneration() {
        val secureRandom = java.security.SecureRandom()
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)

        val uniqueBytes = bytes.toSet().size
        assertTrue("Random bytes should have sufficient entropy", uniqueBytes > 10)
    }
}
