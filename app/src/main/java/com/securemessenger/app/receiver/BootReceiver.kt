package com.securemessenger.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.securemessenger.app.service.MessageCleanupService
import com.securemessenger.app.service.MessengerService

/**
 * BootReceiver - restores the periodic cleanup job, and background message
 * delivery when the user has enabled it, after the device restarts.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON") {
            // Never let a scheduling failure crash the app on boot.
            try {
                MessageCleanupService.schedulePeriodicCleanup(context)
            } catch (e: Exception) {
                android.util.Log.e("BootReceiver", "cleanup scheduling failed", e)
            }
            // A reboot would otherwise leave background delivery silently off
            // until the user next opened the app — exactly the state this
            // feature exists to avoid. `start` is itself a no-op when the
            // setting is off.
            try {
                MessengerService.start(context)
            } catch (e: Exception) {
                android.util.Log.e("BootReceiver", "background delivery restart failed", e)
            }
        }
    }
}
