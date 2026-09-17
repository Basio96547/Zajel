package com.securemessenger.app.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.TileService
import com.securemessenger.app.security.AppSettings
import com.securemessenger.app.ui.MainActivity

/**
 * Quick Settings tile — a second, OS-standard way back in when stealth mode
 * has hidden the launcher icon, alongside the SECRET_CODE dialer trick
 * (SecretCodeReceiver). Some OEM dialers (parts of Samsung/Xiaomi's stock
 * dialer, most third-party dialer apps) never deliver the
 * android.provider.Telephony.SECRET_CODE broadcast at all, which would
 * otherwise leave a user with a hidden icon and genuinely no way back in
 * short of wiping the app's own data.
 *
 * The tile itself is visible to anyone who opens the Quick Settings editor —
 * exactly the kind of tell a ForegroundService notification would have been
 * (see the session's earlier discussion) — so it deliberately reuses the
 * app's own public identity (name/icon) everywhere it's shown, the same way
 * MainActivity's launcher entry already does. It is NOT added
 * to the user's active tiles automatically; it only ever appears if someone
 * deliberately opens the tile editor and adds it.
 *
 * Tapping it does exactly what SecretCodeReceiver does (re-enable the
 * launcher icon, clear the stealth setting) and then opens the app — it does
 * NOT bypass the access code + biometric gate, which still guard the
 * messenger itself.
 */
class StealthExitTileService : TileService() {

    override fun onClick() {
        super.onClick()

        try {
            val alias = ComponentName(packageName, "$packageName.LauncherAlias")
            packageManager.setComponentEnabledSetting(
                alias,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            AppSettings.setStealthEnabled(this, false)
        } catch (_: Exception) {
        }

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // TileService#startActivityAndCollapse(Intent) is deprecated from API
        // 34 onward and throws UnsupportedOperationException there — the
        // PendingIntent overload is required on 34+, while it doesn't exist
        // pre-34.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(launchIntent)
        }
    }
}
