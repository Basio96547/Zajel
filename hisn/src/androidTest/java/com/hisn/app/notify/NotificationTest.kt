package com.hisn.app.notify

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.hisn.app.audit.Change
import com.hisn.app.audit.ChangeDirection
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Proves the notification path actually renders a posted notification (channel,
 * permission, builder, post) — the piece that on-device state changes couldn't
 * reliably trigger from adb.
 */
class NotificationTest {

    @get:Rule
    val permission: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun changeNotification_isPostedWithReasonAndScores() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancelAll()

        HisnNotifications.ensureChannel(context)
        HisnNotifications.notifyChange(
            context,
            Change(
                direction = ChangeDirection.WORSENED,
                oldScore = 84,
                newScore = 59,
                reasons = listOf("خدمات إمكانية الوصول: من سليم إلى خطر")
            )
        )

        // activeNotifications can lag the post slightly.
        var posted = nm.activeNotifications.firstOrNull { it.id == HisnNotifications.NOTIF_ID }
        var tries = 0
        while (posted == null && tries++ < 20) {
            Thread.sleep(100)
            posted = nm.activeNotifications.firstOrNull { it.id == HisnNotifications.NOTIF_ID }
        }
        assertTrue("notification should be posted", posted != null)

        val extras = posted!!.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        assertTrue("title marks a decline", title.contains("تراجع"))
        assertTrue("reason is shown", big.contains("إمكانية الوصول"))
        assertTrue("old/new scores are shown", big.contains("84") && big.contains("59"))

        nm.cancelAll()
    }
}
