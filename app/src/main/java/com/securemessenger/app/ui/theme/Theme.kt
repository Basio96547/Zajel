package com.securemessenger.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.securemessenger.app.ui.liquid.LiquidTheme

/**
 * Design system — one place for spacing, shape and the chat-bubble palette.
 * Brand/semantic colors live in Color.kt; typography lives in Type.kt. Values
 * here are pinned exactly to the approved reference mockups (Telegram-style),
 * not approximated — screens read from here instead of hard-coding values, so
 * the look stays pixel-consistent across the app.
 */

// ---- Spacing: a strict 4dp grid ----
object Dims {
    val s2 = 2.dp
    val s4 = 4.dp
    val s6 = 6.dp
    val s8 = 8.dp
    val s12 = 12.dp
    val s16 = 16.dp
    val s24 = 24.dp
    val avatarSmall = 38.dp
    val avatarContactDetail = 84.dp
    val avatarProfile = 96.dp
    val bubbleRadius = 16.dp
    val bubbleTail = 5.dp
    val bubbleMaxWidth = 300.dp
}

/** Chat-specific colours that Material's scheme doesn't model. */
data class MessengerColors(
    val sentBubble: Color,
    val receivedBubble: Color,
    val onSent: Color,
    val onReceived: Color,
    val sentMeta: Color,
    val receivedMeta: Color,
    val readTick: Color,
    // Thin, low-alpha separators between rows (settings sections, composer
    // top edge, chat-list rows).
    val divider: Color,
    // Stronger, near-solid border used specifically at screen-level header
    // and footer edges (conversation top bar / input bar).
    val headerBorder: Color,
    // Page backdrops: two colors each. In dark mode both stops are the same
    // flat color (mockups render most dark screens as flat, not gradient);
    // in light mode they are a genuine soft two-stop diagonal gradient.
    val listGradient: List<Color>,
    val chatGradient: List<Color>,
    // Radial backdrop reserved for onboarding/security moments (Setup,
    // Loading, Stealth Mode).
    val onboardingGradient: List<Color>,
    val glassCard: Color,
    val glassCardStrong: Color,
    val glassOnCard: Color,
)

val LocalMessengerColors = staticCompositionLocalOf {
    // Sensible dark default; overridden by MessengerTheme.
    MessengerColors(
        sentBubble = Brand, receivedBubble = Color(0xFF1A1E27),
        onSent = Color.White, onReceived = Color(0xFFEEF0F4),
        sentMeta = Color(0xFFD8F0FB), receivedMeta = Color(0xFF6B7180),
        readTick = Color.White,
        divider = Color(0x0FFFFFFF),
        headerBorder = Color(0xFF1C1F27),
        listGradient = listOf(Color(0xFF0B0D12), Color(0xFF0B0D12)),
        chatGradient = listOf(Color(0xFF0B0D12), Color(0xFF0B0D12)),
        onboardingGradient = listOf(Color(0xFF141826), Color(0xFF0A0C11)),
        glassCard = Color(0x0DFFFFFF), glassCardStrong = Color(0x17FFFFFF),
        glassOnCard = Color(0xFFF2F4F8)
    )
}

private val DarkColors = darkColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    secondary = AccentPurple,
    background = Color(0xFF0B0D12),
    onBackground = Color(0xFFF2F4F8),
    surface = Color(0xFF0B0D12),
    onSurface = Color(0xFFF2F4F8),
    surfaceVariant = Color(0xFF15181F),
    onSurfaceVariant = Color(0xFF8B91A3),
    error = SemanticRed,
    onError = Color.White,
    outlineVariant = Color(0x0FFFFFFF),
)

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    secondary = AccentPurple,
    background = Color(0xFFEEF1F5),
    onBackground = Color(0xFF1A1D24),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1D24),
    surfaceVariant = Color(0xFFF1F3F6),
    onSurfaceVariant = Color(0xFF5A6070),
    error = SemanticRed,
    onError = Color.White,
    outlineVariant = Color(0x0F000000),
)

private val DarkMessenger = MessengerColors(
    sentBubble = Brand,
    receivedBubble = Color(0xFF1A1E27),
    onSent = Color.White, onReceived = Color(0xFFEEF0F4),
    sentMeta = Color(0xFFD8F0FB), receivedMeta = Color(0xFF6B7180),
    readTick = Color.White,
    divider = Color(0x0FFFFFFF),
    headerBorder = Color(0xFF1C1F27),
    listGradient = listOf(Color(0xFF0B0D12), Color(0xFF0B0D12)),
    chatGradient = listOf(Color(0xFF0B0D12), Color(0xFF0B0D12)),
    onboardingGradient = listOf(Color(0xFF141826), Color(0xFF0A0C11)),
    glassCard = Color(0x0DFFFFFF), glassCardStrong = Color(0x17FFFFFF),
    glassOnCard = Color(0xFFF2F4F8)
)

private val LightMessenger = MessengerColors(
    sentBubble = Brand,
    receivedBubble = Color(0xE6FFFFFF),
    onSent = Color.White, onReceived = Color(0xFF1A1D24),
    sentMeta = Color(0xFFD8F0FB), receivedMeta = Color(0xFF9AA0AC),
    readTick = Color.White,
    divider = Color(0x0F000000),
    headerBorder = Color(0xFFDBE0E8),
    listGradient = listOf(Color(0xFFEEF1F5), Color(0xFFE6EBF1)),
    chatGradient = listOf(Color(0xFFE9EEF4), Color(0xFFE0E6EE)),
    onboardingGradient = listOf(Color(0xFFF4F7FB), Color(0xFFE4EAF1)),
    glassCard = Color(0xBFFFFFFF), glassCardStrong = Color(0xCCFFFFFF),
    glassOnCard = Color(0xFF1A1D24)
)

// Generous, Telegram-like rounding — cards/sheets/dialogs default to this
// scale unless a screen specifies its own shape explicitly.
val ConcreteShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun MessengerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val messenger = if (darkTheme) DarkMessenger else LightMessenger
    CompositionLocalProvider(LocalMessengerColors provides messenger) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = ConcreteShapes,
            content = { LiquidTheme(dark = darkTheme) { content() } }
        )
    }
}
