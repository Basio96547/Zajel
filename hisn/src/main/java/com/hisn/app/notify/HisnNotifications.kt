package com.hisn.app.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hisn.app.R
import com.hisn.app.audit.Change
import com.hisn.app.audit.ChangeDirection
import com.hisn.app.ui.MainActivity

/**
 * HisnNotifications - posts one clear notification when a scan finds a meaningful
 * change. Rule enforced by the caller: no notification without a reason.
 */
object HisnNotifications {

    private const val CHANNEL = "hygiene_changes"
    const val NOTIF_ID = 4201

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL, "تغيّرات النظافة الأمنية", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "تنبيهك عند تغيّر واضح في وضع أمان جهازك" }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun notifyChange(context: Context, change: Change) {
        if (!hasPermission(context)) {
            android.util.Log.w("Hisn", "notifyChange skipped: POST_NOTIFICATIONS not granted")
            return
        }
        android.util.Log.i("Hisn", "notifyChange: ${change.direction} ${change.reasons}")

        val worsened = change.direction == ChangeDirection.WORSENED
        val title = if (worsened) "⚠ تراجع في نظافة جهازك" else "✓ تحسّن في نظافة جهازك"
        val text = change.reasons.joinToString("\n") +
            "\nالدرجة: ${change.oldScore} ← ${change.newScore}"

        val pending = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_hisn)
            .setContentTitle(title)
            .setContentText(change.reasons.firstOrNull() ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setColor(if (worsened) Color.parseColor("#E15656") else Color.parseColor("#43A047"))
            .setPriority(if (worsened) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIF_ID, notif)
    }

    private fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }
}
