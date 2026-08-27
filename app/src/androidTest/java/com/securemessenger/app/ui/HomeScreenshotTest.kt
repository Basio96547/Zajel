package com.securemessenger.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.securemessenger.app.ui.screens.chat.ChatListContent
import com.securemessenger.app.ui.theme.MessengerTheme
import com.securemessenger.app.ui.viewmodel.ContactUiModel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Photographs the home screen so a human can judge it.
 *
 * The app sets FLAG_SECURE unconditionally and there is no switch to turn it
 * off — a hidden messenger that lets you disable screenshot protection
 * defeats its own purpose — so `adb shell screencap` of the running app
 * returns a black frame. That is the protection working, not a fault. A test
 * activity carries no such flag, which makes this the only way to see the
 * screen without weakening the app to look at it.
 *
 * Nothing here asserts an appearance. The output is a PNG for a person to
 * look at; the only assertion is that a real, non-empty image was produced,
 * so a silent capture failure cannot masquerade as success.
 */
@RunWith(AndroidJUnit4::class)
class HomeScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val sample = listOf(
        ContactUiModel(
            id = "c1", displayName = "سارة", isVerified = true,
            lastMessage = "وصلتك الصورة؟", lastTimestamp = System.currentTimeMillis() - 4 * 60_000,
            unreadCount = 3
        ),
        ContactUiModel(
            id = "c2", displayName = "مجموعة العمل", isVerified = false,
            lastMessage = "أنت: تمام، أرسلها الليلة", lastTimestamp = System.currentTimeMillis() - 55 * 60_000,
            unreadCount = 0, lastIsMine = true, lastIsRead = true, pinnedAt = System.currentTimeMillis()
        ),
        ContactUiModel(
            id = "c3", displayName = "أحمد", isVerified = true,
            lastMessage = "شكراً لك", lastTimestamp = System.currentTimeMillis() - 26 * 60 * 60_000,
            unreadCount = 0, lastIsMine = false, lastIsRead = true
        ),
        ContactUiModel(
            id = "c4", displayName = "خالد", isVerified = false,
            lastMessage = "", lastTimestamp = System.currentTimeMillis() - 3L * 24 * 60 * 60_000,
            unreadCount = 1, lastIsSelfDestruct = true
        ),
        ContactUiModel(
            id = "c5", displayName = "ليلى", isVerified = false,
            lastMessage = "تمام نتفق على الموعد", lastTimestamp = System.currentTimeMillis() - 6L * 24 * 60 * 60_000,
            unreadCount = 0
        )
    )

    @Test
    fun captureHomeDark() = capture("home-dark", dark = true, contacts = sample)

    @Test
    fun captureHomeLight() = capture("home-light", dark = false, contacts = sample)

    @Test
    fun captureHomeEmpty() = capture("home-empty", dark = true, contacts = emptyList())

    private fun capture(name: String, dark: Boolean, contacts: List<ContactUiModel>) {
        compose.setContent {
            MessengerTheme(darkTheme = dark) {
                ChatListContent(
                    modifier = Modifier.fillMaxSize(),
                    contacts = contacts,
                    isLoading = false,
                    pendingRequestCount = 2,
                    onConversationClick = {},
                    onTogglePin = {},
                    onSettingsClick = {},
                    onNewChatClick = {},
                    onProfileClick = {},
                    onConnectionRequestsClick = {}
                )
            }
        }
        compose.waitForIdle()

        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "screenshots"
        ).apply { mkdirs() }
        val out = File(dir, "$name.png")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        assertTrue("captured image has no area", bitmap.width > 100 && bitmap.height > 100)
        assertTrue("nothing was written to ${out.absolutePath}", out.length() > 0)
    }
}
