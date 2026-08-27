package com.securemessenger.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securemessenger.app.SecureMessengerApp
import com.securemessenger.app.data.repository.SecureRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class IncomingRequestUiModel(
    val senderIdentityPublicKeyHex: String,
    val senderUserId: String,
    val senderUsername: String,
    val receivedAt: Long
)

/**
 * Pending self-introductions found via username search, waiting on an
 * explicit accept/reject — see IncomingConnectionRequest. No decryption
 * needed for the list itself: senderUsername is stored plaintext (it's
 * already public in the directory), unlike Contact.displayNameEncrypted.
 */
class ConnectionRequestsViewModel(
    private val repository: SecureRepository = SecureMessengerApp.instance.repository
) : ViewModel() {

    val incomingRequests: StateFlow<List<IncomingRequestUiModel>> =
        repository.getIncomingConnectionRequests()
            .map { requests ->
                requests
                    .map { IncomingRequestUiModel(it.senderIdentityPublicKeyHex, it.senderUserId, it.senderUsername, it.receivedAt) }
                    .sortedByDescending { it.receivedAt }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Key of the request currently being accepted/rejected, if any — drives a per-row spinner instead of blocking the whole list. */
    private val _actionInProgress = MutableStateFlow<String?>(null)
    val actionInProgress: StateFlow<String?> = _actionInProgress.asStateFlow()

    fun accept(senderIdentityPublicKeyHex: String, onResult: (Boolean) -> Unit) {
        if (_actionInProgress.value != null) return
        viewModelScope.launch {
            _actionInProgress.value = senderIdentityPublicKeyHex
            val success = try {
                SecureMessengerApp.instance.messagingClient?.acceptConnectionRequest(senderIdentityPublicKeyHex) ?: false
            } finally {
                _actionInProgress.value = null
            }
            onResult(success)
        }
    }

    fun reject(senderIdentityPublicKeyHex: String) {
        if (_actionInProgress.value != null) return
        viewModelScope.launch {
            _actionInProgress.value = senderIdentityPublicKeyHex
            try {
                SecureMessengerApp.instance.messagingClient?.rejectConnectionRequest(senderIdentityPublicKeyHex)
            } finally {
                _actionInProgress.value = null
            }
        }
    }
}
