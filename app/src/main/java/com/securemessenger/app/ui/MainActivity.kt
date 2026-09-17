package com.securemessenger.app.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.securemessenger.app.security.AppSettings
import com.securemessenger.app.ui.navigation.AppNavigation

/**
 * MainActivity - Entry point for the app UI.
 *
 * Extends FragmentActivity (not the plainer ComponentActivity) because
 * BiometricPrompt requires one to host its confirmation dialog.
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Screenshot/screen-recording prevention is mandatory, not a user
        // toggle — a hidden messenger with an opt-out on this is a
        // contradiction, so there is nothing to "respect" here anymore.
        // Gated on BuildConfig.DEBUG only so screenshots can be taken while
        // developing/reviewing the UI (adb screencap, this-conversation
        // verification, etc.) — any release build still enforces it
        // unconditionally, so there's nothing to remember to revert later.
        if (!com.securemessenger.app.BuildConfig.DEBUG) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }

        enableEdgeToEdge()

        setContent {
            SecureMessengerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation()
                }
            }
        }
    }

    // The hide-on-leaving debounce used to live here, on this Activity's
    // onStop/onStart. It is now on the PROCESS lifecycle — see
    // SecureMessengerApp — because an Activity stopping does not mean the user
    // left the app, and reading it that way broke QR pairing outright: opening
    // the scanner stops this Activity, so the ten-second countdown ran while
    // the user was still aiming the camera.
}

@Composable
fun SecureMessengerTheme(
    content: @Composable () -> Unit
) {
    // The whole app now runs on the shared design system — light/dark follows
    // the system by default, or the user's explicit choice from Settings.
    val mode by AppSettings.themeMode.collectAsState()
    val darkTheme = when (mode) {
        AppSettings.ThemeMode.LIGHT -> false
        AppSettings.ThemeMode.DARK -> true
        AppSettings.ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    com.securemessenger.app.ui.theme.MessengerTheme(darkTheme = darkTheme, content = content)
}
