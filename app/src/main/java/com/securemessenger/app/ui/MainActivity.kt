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
import androidx.lifecycle.lifecycleScope
import com.securemessenger.app.security.AppSettings
import com.securemessenger.app.security.DisguiseState
import com.securemessenger.app.ui.navigation.AppNavigation
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * MainActivity - Entry point for the app UI.
 *
 * Extends FragmentActivity (not the plainer ComponentActivity) because
 * BiometricPrompt requires one to host its confirmation dialog.
 */
class MainActivity : FragmentActivity() {

    // Debounces DisguiseState.hide() below — see onStop()/onStart().
    private var hideJob: Job? = null

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

    /**
     * A brief interruption (switching to reply to an unrelated notification,
     * sharing a photo out and straight back, the screen timing out for a
     * second) used to hide the disguise instantly — forcing the full access
     * code + biometric unlock again over a momentary switch, not just a real
     * "walk away." Now debounced: hiding only actually happens if the app
     * hasn't returned to the foreground within HIDE_GRACE_PERIOD_MS (see
     * onStart()). Nothing is exposed to anyone else during that window either
     * way — FLAG_SECURE is set unconditionally in onCreate (independent of
     * reveal state), so the recents switcher/a screenshot attempt is blocked
     * the whole time regardless of how long this grace period is.
     *
     * Deliberately short: this is a debounce for a stray app-switch, not a
     * "stay logged in" session. Every extra second here is a window where
     * anyone who has the phone already unlocked (past the lockscreen) sees
     * the real messenger with no code or biometric prompt at all.
     */
    override fun onStop() {
        super.onStop()
        hideJob?.cancel()
        hideJob = lifecycleScope.launch {
            delay(HIDE_GRACE_PERIOD_MS)
            DisguiseState.hide()
        }
    }

    /** Back in the foreground within the grace period — cancel the pending hide, no re-unlock needed. */
    override fun onStart() {
        super.onStart()
        hideJob?.cancel()
    }

    private companion object {
        const val HIDE_GRACE_PERIOD_MS = 10_000L
    }
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
