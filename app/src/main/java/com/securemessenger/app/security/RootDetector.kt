package com.securemessenger.app.security

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Best-effort root/jailbreak detection, checked only at the reveal gate
 * (after the correct access code is typed) — never on the calculator itself,
 * so a wrong guess looks identical whether or not the device is rooted.
 *
 * This is a deterrent, not a hard security boundary: root-hiding tools
 * (Magisk DenyList/Zygisk, hidden su binaries, etc.) can defeat any
 * user-space check like this one. It stops casual/accidental exposure
 * (an unlocked bootloader, a visible su binary, a known root manager app)
 * but a determined attacker who has already rooted the device to specifically
 * target this app can likely hide from it. Real protection against a rooted
 * device reading process memory or DB files still comes from encryption at
 * rest (SQLCipher) and the OS keystore, not from this check.
 */
object RootDetector {

    private val suPaths = listOf(
        "/system/app/Superuser.apk",
        "/sbin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/su",
        "/su/bin/su",
        "/system/xbin/daemonsu",
        "/system/etc/init.d/99SuperSUDaemon"
    )

    private val rootPackages = listOf(
        "com.topjohnwu.magisk",
        "eu.chainfire.supersu",
        "com.noshufou.android.su",
        "com.noshufou.android.su.elite",
        "com.koushikdutta.superuser",
        "com.thirdparty.superuser",
        "com.yellowes.su",
        "com.kingroot.kinguser",
        "com.kingo.root",
        "com.smedialink.oneclickroot",
        "com.zhiqupk.root.global",
        "com.alephzain.framaroot",
        "me.weishu.kernelsu"
    )

    fun isDeviceRooted(context: Context): Boolean =
        hasSuBinary() || hasRootPackage(context) || hasTestKeys() || canExecuteSu() || hasMagiskTraces()

    private fun hasSuBinary(): Boolean =
        suPaths.any { path -> try { File(path).exists() } catch (_: Exception) { false } }

    private fun hasRootPackage(context: Context): Boolean {
        val pm = context.packageManager
        return rootPackages.any { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /** Official Google-signed builds ship with "release-keys"; custom/dev builds (common on rooted devices) use "test-keys". */
    private fun hasTestKeys(): Boolean =
        Build.TAGS?.contains("test-keys") == true

    private fun canExecuteSu(): Boolean {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf("which", "su"))
            !process.inputStream.bufferedReader().readLine().isNullOrEmpty()
        } catch (_: Exception) {
            false
        } finally {
            process?.destroy()
        }
    }

    private fun hasMagiskTraces(): Boolean = try {
        File("/sbin/.magisk").exists() ||
            File("/cache/magisk.log").exists() ||
            File("/data/adb/magisk").exists()
    } catch (_: Exception) {
        false
    }
}
