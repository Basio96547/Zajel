package com.securemessenger.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.securemessenger.app.ui.screens.calculator.CalculatorScreen
import com.securemessenger.app.ui.GlassTopBar
import com.securemessenger.app.ui.glassBackground
import com.securemessenger.app.ui.screens.chat.ConnectionRequestsScreen
import com.securemessenger.app.ui.screens.chat.DateSeparator
import com.securemessenger.app.ui.screens.chat.EncryptedBanner
import com.securemessenger.app.ui.screens.chat.MediaLoader
import com.securemessenger.app.ui.screens.chat.MessageBubble
import com.securemessenger.app.ui.screens.chat.MessageInput
import com.securemessenger.app.ui.screens.chat.ContactDetailScreen
import com.securemessenger.app.ui.screens.chat.NewChatScreen
import com.securemessenger.app.ui.screens.chat.UsernameSearchScreen
import com.securemessenger.app.ui.screens.settings.ProfileScreen
import com.securemessenger.app.ui.screens.settings.SettingsScreen
import com.securemessenger.app.ui.screens.setup.SetupScreen
import com.securemessenger.app.ui.screens.verification.KeyVerificationScreen
import com.securemessenger.app.ui.theme.MessengerTheme
import com.securemessenger.app.ui.viewmodel.MessageUiModel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A walk through the app, one screen per test.
 *
 * The design was generalised by re-pointing shared primitives rather than by
 * editing screens, and the honest thing to say about that was that only two
 * screens had actually been looked at. This is the rest of the walk: every
 * screen that renders from callbacks alone gets photographed so its result
 * can be judged rather than assumed.
 *
 * One test per screen on purpose. A screen that throws — several read the
 * repository, and this device has a fresh install with no completed setup —
 * fails alone and tells us something, instead of taking the tour down with
 * it.
 */
@RunWith(AndroidJUnit4::class)
class ScreenTourTest {

    @get:Rule
    val compose = createComposeRule()

    @Test fun tourCalculator() = shoot("tour-calculator") {
        CalculatorScreen(onUnlock = {}, onDuress = {})
    }

    @Test fun tourSetup() = shoot("tour-setup") {
        SetupScreen(onSetupComplete = {})
    }

    @Test fun tourSettings() = shoot("tour-settings") {
        SettingsScreen(
            onBackClick = {}, onVerificationClick = {}, onStealthModeClick = {},
            onDataWiped = {}, onProfileClick = {}
        )
    }

    @Test fun tourProfile() = shoot("tour-profile") {
        ProfileScreen(onBackClick = {}, onVerifyClick = {}, onShowQrClick = {})
    }

    @Test fun tourNewChat() = shoot("tour-newchat") {
        NewChatScreen(onBackClick = {}, onContactAdded = {})
    }

    @Test fun tourUsernameSearch() = shoot("tour-username-search") {
        UsernameSearchScreen(onBackClick = {})
    }

    @Test fun tourKeyVerification() = shoot("tour-verification") {
        KeyVerificationScreen(contactId = null, onBackClick = {})
    }

    @Test fun tourConnectionRequests() = shoot("tour-requests") {
        ConnectionRequestsScreen(onBackClick = {}, onAccepted = {})
    }

    @Test fun tourContactDetail() = shoot("tour-contact-detail") {
        // A contact id that resolves to nothing, on purpose: this renders the
        // screen's empty shell, which is what it must survive showing anyway.
        // Before its reads were guarded it did not survive it — it threw.
        ContactDetailScreen(contactId = "no-such-contact", onBackClick = {}, onVerifyClick = {})
    }

    /**
     * The conversation — the app's most-used screen, and until now the only
     * one nobody had seen.
     *
     * It is composed here from its own parts rather than called as
     * ConversationScreen, and the difference matters. MessageBubble used to
     * take the entire ConversationViewModel to reach one method on it
     * (loadMediaBytes), which meant no bubble could be drawn without an open
     * database. That dependency is now a MediaLoader — the single function
     * actually used — so the real bubbles, the real composer and the real top
     * bar all render from sample data.
     *
     * What is deliberately NOT rebuilt is the screen's action wiring: edit,
     * react, and both kinds of delete still belong to the view model. A slip
     * between "delete for me" and "delete for everyone" is precisely what a
     * photograph cannot catch, and there is no paired contact on this device
     * to test the real behaviour against. Narrowing one dependency was worth
     * doing; rewiring destructive actions for a screenshot was not.
     */
    @Test fun tourConversation() = shoot("tour-conversation") {
        val loader = MediaLoader { null }
        val seen = remember { mutableSetOf<String>() }
        Box(modifier = Modifier.fillMaxSize().glassBackground(listOf(Color(0xFF06080D)))) {
            Column(modifier = Modifier.fillMaxSize()) {
                GlassTopBar(title = "سارة", onBack = {})
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp)
                ) {
                    EncryptedBanner()
                    DateSeparator("اليوم")
                    sampleConversation.forEachIndexed { index, message ->
                        MessageBubble(
                            message = message,
                            isLastInGroup = index == sampleConversation.lastIndex ||
                                sampleConversation[index + 1].direction != message.direction,
                            loadMedia = loader,
                            onLongPress = {},
                            onSwipeReply = {},
                            onOpenImageViewer = {},
                            seenKeys = seen
                        )
                    }
                }
                MessageInput(
                    value = "",
                    onValueChange = {},
                    onSendClick = {},
                    onAttachClick = {},
                    onVoiceMessageReady = {},
                    onVoiceError = {},
                    sending = false
                )
            }
        }
    }

    private val sampleConversation = listOf(
        MessageUiModel(
            id = 1L, clientId = "m1", text = "وصلتك الصورة؟", direction = 0,
            timestamp = System.currentTimeMillis() - 9 * 60_000, isRead = true, isExpired = false
        ),
        MessageUiModel(
            id = 2L, clientId = "m2", text = "إي وصلت، شكراً", direction = 1,
            timestamp = System.currentTimeMillis() - 7 * 60_000, isRead = true, isExpired = false
        ),
        MessageUiModel(
            id = 3L, clientId = "m3", text = "نتفق على الموعد بكرة إن شاء الله", direction = 1,
            timestamp = System.currentTimeMillis() - 6 * 60_000, isRead = false, isExpired = false,
            isPending = true
        ),
        MessageUiModel(
            id = 4L, clientId = "m4", text = "تمام", direction = 0,
            timestamp = System.currentTimeMillis() - 4 * 60_000, isRead = true, isExpired = false,
            reactionMine = "👍", replyToClientId = "m3", replySnippet = "نتفق على الموعد بكرة"
        ),
        MessageUiModel(
            id = 5L, clientId = "m5", text = "رسالة معدّلة", direction = 1,
            timestamp = System.currentTimeMillis() - 2 * 60_000, isRead = true, isExpired = false,
            edited = true
        )
    )

    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.setContent { MessengerTheme(darkTheme = true) { content() } }
        compose.waitForIdle()

        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "screenshots"
        ).apply { mkdirs() }
        val out = File(dir, "$name.png")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        assertTrue("captured image has no area", bitmap.width > 100 && bitmap.height > 100)
    }
}
