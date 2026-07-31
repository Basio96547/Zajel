package com.securemessenger.app.receiver

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.securemessenger.app.security.AppSettings

/**
 * Stealth-mode re-entry. When the user dials the secret code (*#*#73287#*#*),
 * the system dialer fires a SECRET_CODE broadcast that this receiver handles by
 * re-enabling the launcher icon. This does NOT open or reveal the messenger —
 * it only restores the calculator icon; the access code + biometric gate still
 * guard the messenger itself.
 */
class SecretCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val alias = ComponentName(context.packageName, "${context.packageName}.LauncherAlias")
            context.packageManager.setComponentEnabledSetting(
                alias,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            AppSettings.setStealthEnabled(context, false)
        } catch (_: Exception) {
        }
    }
}
