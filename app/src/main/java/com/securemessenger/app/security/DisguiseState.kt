package com.securemessenger.app.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the hidden messenger is currently revealed (true) or the app is
 * showing its real-calculator disguise (false). Starts hidden — every fresh
 * launch, and every time the app leaves the foreground, shows the calculator
 * first; only the secret access code reveals the messenger again.
 */
object DisguiseState {
    private val _isRevealed = MutableStateFlow(false)
    val isRevealed: StateFlow<Boolean> = _isRevealed

    fun reveal() {
        _isRevealed.value = true
    }

    fun hide() {
        _isRevealed.value = false
    }
}
