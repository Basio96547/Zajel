package com.securemessenger.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Brand + semantic color palette (shared across light/dark). Values are
 * pinned exactly to the approved reference mockups (Telegram-style), not
 * approximated — screens read from here instead of hard-coding hex values.
 */
val Brand = Color(0xFF2AABEE)
val AccentPurple = Color(0xFF7F77DD)
val SemanticRed = Color(0xFFE24B4A)
val SemanticGreen = Color(0xFF1D9E75)
val SemanticOrange = Color(0xFFD85A30)
val SemanticAmber = Color(0xFFBA7517)
val SemanticPink = Color(0xFFD4537E)

/** Named accessors for the semantic palette above — used wherever a screen needs a specific accent by meaning rather than by hex. */
object SemanticColors {
    val red = SemanticRed
    val green = SemanticGreen
    val orange = SemanticOrange
    val amber = SemanticAmber
    val pink = SemanticPink
    val purple = AccentPurple
}
