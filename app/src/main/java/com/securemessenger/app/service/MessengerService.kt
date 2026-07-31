package com.securemessenger.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.securemessenger.app.R
import com.securemessenger.app.SecureMessengerApp
import com.securemessenger.app.security.AppSettings
import com.securemessenger.app.security.DevicePassphrase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "MessengerService"

/**
 * Keeps the transport alive while the app is closed, so a message arrives when
 * it is sent rather than when the recipient next opens the messenger.
 *
 * Before this existed, both people had to have the disguise revealed at the same
 * moment for anything to be delivered. Nothing was ever *lost* — the sender's
 * durable outbox retries and the blind relay holds an undelivered blob for 48
 * hours — but a message that only lands when you go looking for it is not a
 * messenger, which is exactly the complaint this answers.
 *
 * **Why a foreground service and not something quieter.** Android gives no way
 * to hold a socket open, or to poll on any useful cadence, from the background
 * without one: `WorkManager`'s floor is fifteen minutes, and an ordinary
 * background service is killed within seconds. A foreground service must post a
 * visible, non-dismissible notification — so the notification is dressed to
 * match the app's calculator identity, and the whole feature is a switch that
 * defaults to off, because a permanent "this app is running" indicator is a
 * genuine, if small, cost to the disguise.
 *
 * The database needs no user interaction to open (its key is a random secret in
 * Keystore-backed storage — see [DevicePassphrase]), which is what makes running
 * without a visible UI possible at all.
 */
class MessengerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground must happen promptly after start or the system kills
        // the process with a ForegroundServiceDidNotStartInTimeException — so it
        // runs before any of the slow work below.
        startForeground(ONGOING_ID, buildOngoingNotification())

        scope.launch {
            try {
                val app = SecureMessengerApp.instance
                // The UI normally does this on its way to the chat list; when
                // the service is what started first (boot, or the app being
                // swiped away) nobody else will have.
                app.repository.initialize(DevicePassphrase.getOrCreate(applicationContext))
                app.initializeMessagingClient()
            } catch (e: Exception) {
                Log.e(TAG, "background transport failed to start", e)
                stopSelf()
            }
        }
        // Restarted if the system reclaims us — the point of the service is to
        // be there when a message arrives, which is not a moment we control.
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ONGOING_ID = 4801
        private const val CHANNEL_ONGOING = "calc_engine"
        private const val CHANNEL_MESSAGES = "calc_updates"

        /** Notification id for arriving messages; one shared id so they replace rather than stack. */
        private const val MESSAGE_ID = 4802

        fun start(context: Context) {
            if (!AppSettings.isBackgroundDeliveryEnabled(context)) return
            val intent = Intent(context, MessengerService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // Android 12+ forbids starting a foreground service from the
                // background in most states. Failing here is survivable: the
                // transport still runs whenever the UI is open.
                Log.w(TAG, "could not start background delivery now", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, MessengerService::class.java))
            } catch (_: Exception) {
            }
        }

        private fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return

            // IMPORTANCE_MIN: no sound, no badge, and collapsed to the bottom of
            // the shade — the least conspicuous a mandatory ongoing notification
            // is allowed to be. Its name is deliberately mundane, since the
            // channel list itself is visible in system settings.
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ONGOING,
                    context.getString(R.string.channel_engine),
                    NotificationManager.IMPORTANCE_MIN
                ).apply { setShowBadge(false) }
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_MESSAGES,
                    context.getString(R.string.channel_updates),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }

        private fun openAppIntent(context: Context): PendingIntent? {
            // Deliberately the public launcher alias, which lands on the
            // calculator — tapping a notification must never bypass the access
            // code and biometric gate.
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: return null
            return PendingIntent.getActivity(
                context, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /**
         * Announce an arriving message, at whatever level of detail the user
         * chose. Defaults to saying only that something arrived — a lock-screen
         * preview naming the sender would undo much of what this app is for.
         */
        fun notifyMessage(context: Context, senderName: String?, preview: String?) {
            val mode = AppSettings.notificationMode(context)
            if (mode == AppSettings.NotificationMode.SILENT) return
            createChannels(context)

            val builder = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent(context))
                // Even in FULL mode the lock screen shows only the public
                // version, so a preview is never readable without unlocking.
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

            if (mode == AppSettings.NotificationMode.FULL && senderName != null) {
                builder.setContentTitle(senderName)
                builder.setContentText(preview ?: context.getString(R.string.notif_new_message))
            } else {
                builder.setContentTitle(context.getString(R.string.notif_generic_title))
                builder.setContentText(context.getString(R.string.notif_new_message))
            }

            try {
                NotificationManagerCompat.from(context).notify(MESSAGE_ID, builder.build())
            } catch (e: SecurityException) {
                // POST_NOTIFICATIONS not granted on Android 13+. The message
                // itself already arrived and is in the conversation.
                Log.w(TAG, "notification permission not granted", e)
            }
        }
    }

    private fun buildOngoingNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_engine_running))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(openAppIntent(this))
            .build()
}
