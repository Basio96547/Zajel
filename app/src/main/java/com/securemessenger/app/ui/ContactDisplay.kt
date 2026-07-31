package com.securemessenger.app.ui

private val USERNAME_LIKE = Regex("^[a-z0-9_]{3,20}$")

/**
 * A stored contact name is either a claimed @username or (when we never
 * managed to resolve one) a raw hex user id. Show the former Telegram-style,
 * with an @ prefix, and leave anything else — including a truncated id — as is.
 */
fun formatContactName(raw: String): String =
    if (USERNAME_LIKE.matches(raw)) "@$raw" else raw
