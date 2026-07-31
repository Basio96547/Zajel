package com.securemessenger.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import com.securemessenger.core.CoreLogger
import com.securemessenger.core.Platform
import java.io.File

/**
 * Desktop entry point.
 *
 * The store location follows each OS's convention for application data rather
 * than sitting next to the executable, so the encrypted identity survives the
 * app being moved or reinstalled — losing that file means losing the identity,
 * and every contact has to re-pair.
 */
fun storeFile(): File {
    val home = System.getProperty("user.home")
    val dir = when {
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true) ->
            File(System.getenv("APPDATA") ?: "$home\\AppData\\Roaming", "SecureMessenger")
        System.getProperty("os.name").startsWith("Mac", ignoreCase = true) ->
            File("$home/Library/Application Support", "SecureMessenger")
        else -> File("$home/.local/share", "securemessenger")
    }
    dir.mkdirs()
    return File(dir, "store.dat")
}

/**
 * The blind relay this build talks to, matching the Android app's `relayUrl`
 * gradle property. Overridable at runtime and allowed to be empty: an empty
 * value means local network only, with nothing ever leaving the LAN.
 */
/**
 * A window size that comfortably fits the primary display, accounting for the
 * scaling factor the OS applies. Capped at a comfortable reading width so it
 * doesn't sprawl across a large monitor.
 */
private fun preferredWindowSize(): DpSize {
    // Toolkit's screen size is already in user-space (scale-independent) units
    // on every JDK this runs on — the same units Compose sizes windows in. An
    // earlier version divided it by the display scale as well, which on a
    // 200%-scaled laptop asked for a 640-wide window.
    val screen = java.awt.Toolkit.getDefaultToolkit().screenSize
    return DpSize(
        (screen.width * 0.88).coerceAtMost(1180.0).dp,
        (screen.height * 0.88).coerceAtMost(800.0).dp
    )
}

fun relayUrl(): String =
    System.getProperty("securemessenger.relayUrl")
        ?: System.getenv("SECUREMESSENGER_RELAY_URL")
        ?: "https://sm-blind-mailbox.basil0552106933.workers.dev"

fun main() = application {
    // Install the platform pieces :core needs before any crypto can run. This
    // is the desktop libsodium binding; the Android app installs its own.
    Platform.installSodium(LazySodiumJava(SodiumJava()))
    Platform.installLogger(object : CoreLogger {
        override fun debug(tag: String, message: String) = println("[D] $tag: $message")
        override fun warn(tag: String, message: String, error: Throwable?) {
            System.err.println("[W] $tag: $message${error?.let { " (${it.message})" } ?: ""}")
        }
        override fun error(tag: String, message: String, error: Throwable?) {
            System.err.println("[E] $tag: $message${error?.let { " (${it.message})" } ?: ""}")
        }
    })

    // Size to the actual display rather than a fixed 1100x760: on a 1280x800
    // laptop — especially one running Windows at 125%/150% scaling — a fixed
    // window that large opens partly off-screen, which hides the centred unlock
    // form entirely and makes the app look like it launched blank.
    val windowState = rememberWindowState(
        size = preferredWindowSize(),
        position = WindowPosition(Alignment.Center)
    )
    var open by remember { mutableStateOf(true) }

    if (open) {
        Window(
            onCloseRequest = { open = false; exitApplication() },
            state = windowState,
            title = "حاسبة متقدمة — سطح المكتب"
        ) {
            AppRoot(storeFile(), relayUrl())
        }
    }
}
