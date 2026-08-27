package com.securemessenger.app.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.securemessenger.app.ui.screens.calculator.CalculatorScreen
import com.securemessenger.app.ui.screens.chat.ConnectionRequestsScreen
import com.securemessenger.app.ui.screens.chat.NewChatScreen
import com.securemessenger.app.ui.screens.chat.UsernameSearchScreen
import com.securemessenger.app.ui.screens.settings.ProfileScreen
import com.securemessenger.app.ui.screens.settings.SettingsScreen
import com.securemessenger.app.ui.screens.setup.SetupScreen
import com.securemessenger.app.ui.screens.verification.KeyVerificationScreen
import com.securemessenger.app.ui.theme.MessengerTheme
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
