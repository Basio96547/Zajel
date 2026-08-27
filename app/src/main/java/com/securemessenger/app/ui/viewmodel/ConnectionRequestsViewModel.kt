package com.securemessenger.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securemessenger.app.SecureMessengerApp
import com.securemessenger.app.data.repository.SecureRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
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

    /**
     * Built lazily, and that is a crash fix rather than a style preference.
     *
     * This used to call `repository.getIncomingConnectionRequests()` directly
     * in the property initialiser, so the call ran while the view model was
     * being *constructed*. The repository throws
     * `IllegalStateException("used before initialize()")` if its database is
     * not open yet, and an exception thrown from a view model's constructor
     * takes the whole screen down with it — this one is a default argument of
     * ConnectionRequestsScreen, NewChatScreen *and* ChatListScreen, so it is
     * three screens, including the home screen.
     *
     * Normal navigation reaches those only through the reveal gate, by which
     * point the database is open. The shape that does not is process death
     * and restore, where Navigation-Compose brings back a saved back stack
     * before anything has initialised — precisely the case RevealedOnly
     * exists to survive.
     *
     * `flow { emitAll(...) }` defers the call to first collection, and the
     * catch means a repository that is still closed yields an empty list
     * instead of a fatal exception. An empty list is the honest answer at
     * that moment: there is no open database to have requests in.
     */
    val incomingRequests: StateFlow<List<IncomingRequestUiModel>> =
        flow { emitAll(repository.getIncomingConnectionRequests()) }
            .map { requests ->
                requests
                    .map { IncomingRequestUiModel(it.senderIdentityPublicKeyHex, it.senderUserId, it.senderUsername, it.receivedAt) }
                    .sortedByDescending { it.receivedAt }
            }
            .catch { emit(emptyList()) }
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
