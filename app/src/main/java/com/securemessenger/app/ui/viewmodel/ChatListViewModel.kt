package com.securemessenger.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securemessenger.app.SecureMessengerApp
import com.securemessenger.app.crypto.AndroidKeyStoreManager
import com.securemessenger.app.data.model.Contact
import com.securemessenger.app.data.model.EncryptedMessage
import com.securemessenger.app.data.repository.SecureRepository
import com.securemessenger.app.ui.formatContactName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ContactUiModel(
    val id: String,
    val displayName: String,
    val isVerified: Boolean,
    val lastMessage: String,
    val lastTimestamp: Long,
    val unreadCount: Int,
    val avatarBytes: ByteArray? = null,
    val lastIsMine: Boolean = false,
    val lastIsRead: Boolean = false,
    val lastIsSelfDestruct: Boolean = false,
    val pinnedAt: Long? = null
)

class ChatListViewModel(
    private val repository: SecureRepository = SecureMessengerApp.instance.repository
) : ViewModel() {

    private val _isLoading = MutableStateFlow(true)
    /** True only until the first contacts/messages snapshot arrives — drives the list-skeleton. */
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /**
     * Chat list with a last-message preview, time and unread badge — recomputed
     * reactively whenever contacts or messages change (Telegram/WhatsApp style).
     *
     * Sort is decided here, in exactly one place: pinned conversations first
     * (most-recently-pinned first), then everything else by last activity.
     * Neither the screen nor any other layer re-sorts this list.
     */
    val contacts: StateFlow<List<ContactUiModel>> =
        combine(
            repository.getContacts(),
            // One row per conversation, and one COUNT per conversation, both
            // computed in SQL against the (contactId, timestamp) index.
            //
            // This used to collect repository.getAllMessages() — literally
            // every message in the database, held in memory, re-emitted in
            // full on every write to the table, then filtered once per contact.
            // Drawing the home screen cost contacts × messages and grew with
            // the history forever, on the main thread. Nothing about that was
            // visible until a device had enough messages on it.
            repository.observeLatestMessagePerContact(),
            repository.observeUnreadCounts()
        ) { contacts, latest, unreadCounts ->
            _isLoading.value = false
            val lastByContact = latest.associateBy { it.contactId }
            val unreadByContact = unreadCounts.associate { it.contactId to it.unreadCount }
            contacts.map { contact ->
                val last = lastByContact[contact.id]
                contact.toUiModel(
                    lastMessage = last?.let { preview(it) } ?: "",
                    lastTimestamp = last?.timestamp ?: contact.addedAt,
                    unreadCount = unreadByContact[contact.id] ?: 0,
                    lastIsMine = last?.direction == 1,
                    lastIsRead = last?.isRead == true,
                    lastIsSelfDestruct = last?.expiresAt != null && last.isExpired == false
                )
            }.sortedWith(
                compareByDescending<ContactUiModel> { it.pinnedAt != null }
                    .thenByDescending { it.pinnedAt ?: it.lastTimestamp }
            )
        }
            // Contact names, avatars and the last-message preview are all
            // Keystore-decrypted in the mapping above; none of it belongs on
            // the main thread, which is where the collector for a stateIn in
            // viewModelScope otherwise puts it.
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun togglePin(contactId: String) {
        val currentlyPinned = contacts.value.firstOrNull { it.id == contactId }?.pinnedAt != null
        viewModelScope.launch {
            repository.setContactPinned(contactId, !currentlyPinned)
        }
    }

    private fun preview(message: EncryptedMessage): String {
        if (message.isDeleted) return "🚫 حُذفت"
        if (message.isExpired) return "•••"
        val text = repository.decryptDisplayText(message)
        return if (message.direction == 1) "أنت: $text" else text
    }

    private fun Contact.toUiModel(
        lastMessage: String,
        lastTimestamp: Long,
        unreadCount: Int,
        lastIsMine: Boolean,
        lastIsRead: Boolean,
        lastIsSelfDestruct: Boolean
    ): ContactUiModel {
        val name = try {
            String(
                AndroidKeyStoreManager.decryptWithMasterKey(displayNameEncrypted),
                Charsets.UTF_8
            )
        } catch (_: Exception) {
            id.take(8)
        }
        val avatar = avatarEncrypted?.let {
            try { AndroidKeyStoreManager.decryptWithMasterKey(it) } catch (_: Exception) { null }
        }
        return ContactUiModel(
            id = id,
            displayName = formatContactName(name),
            isVerified = isVerified,
            lastMessage = lastMessage,
            lastTimestamp = lastTimestamp,
            unreadCount = unreadCount,
            avatarBytes = avatar,
            lastIsMine = lastIsMine,
            lastIsRead = lastIsRead,
            lastIsSelfDestruct = lastIsSelfDestruct,
            pinnedAt = pinnedAt
        )
    }
}
