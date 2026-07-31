package com.securemessenger.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securemessenger.app.SecureMessengerApp
import com.securemessenger.app.crypto.AndroidKeyStoreManager
import com.securemessenger.core.crypto.MediaCodec
import com.securemessenger.app.data.model.EncryptedMessage
import com.securemessenger.app.data.repository.SecureRepository
import com.securemessenger.app.security.AppSettings
import com.securemessenger.app.ui.formatContactName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MessageUiModel(
    val id: Long?,
    val clientId: String?,
    val text: String,
    val direction: Int,
    val timestamp: Long,
    val isRead: Boolean,
    val isExpired: Boolean,
    val media: MediaCodec.LocalMedia? = null,
    // Social interaction state
    val reactionMine: String? = null,
    val reactionTheirs: String? = null,
    val replyToClientId: String? = null,
    val replySnippet: String? = null,
    val isDeleted: Boolean = false,
    val edited: Boolean = false,
    // True while the outgoing envelope still sits in the durable outbox
    // (queued/in-flight, no relay ack yet) — drives the "sending…" clock tick.
    val isPending: Boolean = false
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConversationViewModel(
    private val contactId: String,
    private val repository: SecureRepository = SecureMessengerApp.instance.repository
) : ViewModel() {

    private val _messages = MutableStateFlow<List<MessageUiModel>>(emptyList())
    val messages: StateFlow<List<MessageUiModel>> = _messages.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // True while sending is doing the (multi-second) first-contact handshake
    // (X3DH key exchange over the LAN) instead of just delivering over an
    // already-open Double Ratchet session. Without this, the only feedback
    // during that wait was a tiny pending-tick icon on the bubble — easy to
    // miss, and the app could feel frozen even though it's working normally.
    private val _establishingSession = MutableStateFlow(false)
    val establishingSession: StateFlow<Boolean> = _establishingSession.asStateFlow()

    private val _contactName = MutableStateFlow("")
    val contactName: StateFlow<String> = _contactName.asStateFlow()

    private val _contactAvatar = MutableStateFlow<ByteArray?>(null)
    val contactAvatar: StateFlow<ByteArray?> = _contactAvatar.asStateFlow()

    private val _contactVerified = MutableStateFlow(false)
    val contactVerified: StateFlow<Boolean> = _contactVerified.asStateFlow()

    private val _isContactTyping = MutableStateFlow(false)
    val isContactTyping: StateFlow<Boolean> = _isContactTyping.asStateFlow()
    private var typingResetJob: Job? = null
    private var lastTypingSentAt = 0L

    private var sessionId: String? = null

    // What to redo when the user taps the error Snackbar's "إعادة" action —
    // set right before each error, cleared once retried (or once superseded).
    private var lastFailedAction: (() -> Unit)? = null

    /** Clears the currently-shown error (the Snackbar dismissed/timed out on its own). */
    fun clearError() {
        _error.value = null
    }

    /** Re-run whatever just failed, if anything did. */
    fun retryLastAction() {
        val action = lastFailedAction ?: return
        lastFailedAction = null
        _error.value = null
        action()
    }

    /** Call on every text change — throttled so it's a light pulse, not a signal per keystroke. */
    fun notifyTyping() {
        val now = System.currentTimeMillis()
        if (now - lastTypingSentAt < 3000) return
        lastTypingSentAt = now
        val app = SecureMessengerApp.instance
        app.applicationScope.launch {
            try {
                app.awaitMessagingClient(timeoutMs = 1500)?.sendTypingSignal(contactId)
            } catch (_: Exception) {
            }
        }
    }

    init {
        viewModelScope.launch {
            _contactName.value = loadContactName()
            val contact = repository.getContact(contactId)
            _contactVerified.value = contact?.isVerified ?: false
            _contactAvatar.value = repository.getContactAvatar(contactId)
        }
        // Follows whichever client is current (survives the disguise's
        // recreate-on-reveal cycle) and reacts only to pulses from this
        // specific contact — a second contact typing elsewhere is silent here.
        viewModelScope.launch {
            SecureMessengerApp.instance.messagingClientFlow
                .flatMapLatest { it?.typingSignals ?: emptyFlow() }
                .filter { it == contactId }
                .collect {
                    _isContactTyping.value = true
                    typingResetJob?.cancel()
                    typingResetJob = viewModelScope.launch {
                        kotlinx.coroutines.delay(4000)
                        _isContactTyping.value = false
                    }
                }
        }
        viewModelScope.launch {
            try {
                sessionId = repository.getCurrentSessionId(contactId)
                    ?: repository.getOrCreateSessionForContact(contactId)
                val sid = sessionId ?: return@launch
                combine(
                    repository.getMessages(sid),
                    repository.observePendingClientMessageIds()
                ) { messageList, pendingIds -> messageList.map { it.toUiModel(pendingIds) } }
                    .collect { uiList ->
                    _messages.value = uiList
                    // We're actively viewing this conversation — mark any newly
                    // received messages read and let the sender know. Sent as a
                    // separate coroutine (not awaited here) so a slow/just-logged-in
                    // client doesn't stall processing of further message updates;
                    // it waits for the client to finish connecting rather than
                    // silently dropping the receipt if it isn't ready yet.
                    val readIds = repository.markReceivedAsReadAndGetIds(sid)
                    if (readIds.isNotEmpty()) {
                        viewModelScope.launch {
                            val client = SecureMessengerApp.instance.awaitMessagingClient()
                            client?.sendReadReceipt(contactId, readIds)
                        }
                    }
                }
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    /**
     * True if delivering to this contact right now would need a fresh X3DH
     * handshake (no ratchet session persisted yet for them) — i.e. this send
     * is about to take several seconds over the network, not be near-instant.
     */
    private suspend fun needsHandshake(): Boolean = repository.loadRatchetSession(contactId) == null

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        val trimmed = text.trim()
        val app = SecureMessengerApp.instance
        val ttlSeconds = AppSettings.messageTtlSeconds(app)

        viewModelScope.launch {
            _error.value = null
            _isSending.value = true
            // 1) Store locally — the message appears in the conversation immediately,
            //    and the input is freed right after (no waiting on the network).
            val saved = try {
                repository.sendMessageToContact(contactId, trimmed, ttlSeconds)
            } catch (e: Exception) {
                _error.value = "فشل حفظ الرسالة: ${e.message}"
                lastFailedAction = { sendMessage(trimmed) }
                null
            } finally {
                _isSending.value = false
            }
            val messageId = saved?.clientMessageId ?: return@launch

            // 2) Deliver over the relay in the background. This never blocks the UI —
            //    slow networks or prekey fetches don't make sending feel stuck. The
            //    messageId travels with it so the recipient's read receipt can
            //    reference this exact message.
            app.applicationScope.launch {
                val showingHandshake = needsHandshake()
                if (showingHandshake) _establishingSession.value = true
                try {
                    // The client can briefly be null right after login/unlock while
                    // it's still connecting — wait for it instead of giving up.
                    val client = app.awaitMessagingClient()
                    if (client == null) {
                        _error.value = "غير متصل بالشبكة المحلية — الرسالة محفوظة وستُرسَل عند الاتصال"
                        lastFailedAction = { app.messagingClient?.retryNow() }
                        return@launch
                    }
                    val delivered = withContext(Dispatchers.IO) {
                        client.sendMessage(contactId, trimmed.toByteArray(Charsets.UTF_8), ttlSeconds, messageId)
                    }
                    if (!delivered) {
                        _error.value = "تعذّر الإرسال الآن — سيُعاد المحاولة تلقائياً عند اتصال الطرف الآخر"
                        lastFailedAction = { app.messagingClient?.retryNow() }
                    }
                } catch (e: Exception) {
                    _error.value = "تعذّر الإرسال الآن"
                    lastFailedAction = { app.messagingClient?.retryNow() }
                } finally {
                    if (showingHandshake) _establishingSession.value = false
                }
            }
        }
    }

    private suspend fun loadContactName(): String {
        val contact = repository.getContact(contactId) ?: return contactId.take(8)
        return try {
            formatContactName(String(AndroidKeyStoreManager.decryptWithMasterKey(contact.displayNameEncrypted), Charsets.UTF_8))
        } catch (_: Exception) {
            contactId.take(8)
        }
    }

    private fun EncryptedMessage.toUiModel(pendingIds: Set<String> = emptySet()): MessageUiModel {
        val media = if (type != MediaCodec.TYPE_TEXT && !isDeleted) repository.decryptMediaDescriptor(this) else null
        val reactions = parseReactionsJson(reactionsJson)
        return MessageUiModel(
            id = id,
            clientId = clientMessageId,
            text = if (isDeleted) "" else if (media == null) repository.decryptDisplayText(this) else "",
            direction = direction,
            timestamp = timestamp,
            isRead = isRead,
            isExpired = isExpired,
            media = media,
            reactionMine = reactions["me"],
            reactionTheirs = reactions["them"],
            replyToClientId = replyToClientId,
            replySnippet = if (replyToClientId != null) repository.decryptReplySnippet(this) else null,
            isDeleted = isDeleted,
            edited = editedAt != null,
            isPending = direction == 1 && clientMessageId != null && pendingIds.contains(clientMessageId)
        )
    }

    private fun parseReactionsJson(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val obj = org.json.JSONObject(json)
            buildMap { obj.keys().forEach { k -> put(k, obj.getString(k)) } }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Decrypt a media message's file bytes for display/playback. */
    suspend fun loadMediaBytes(media: MediaCodec.LocalMedia): ByteArray? = repository.loadDecryptedMediaBytes(media)

    /** Surface a failure that happened outside the send/receive pipeline (e.g. mic access). */
    fun reportError(message: String) {
        _error.value = message
    }

    /**
     * Send a picked/recorded file as an encrypted media message. Mirrors
     * [sendMessage]'s local-first/background-delivery split.
     */
    fun sendMedia(
        bytes: ByteArray,
        mimeType: String,
        fileName: String,
        mediaType: Int,
        waveform: List<Float> = emptyList(),
        caption: String? = null
    ) {
        val app = SecureMessengerApp.instance
        if (bytes.size > SecureRepository.MAX_MEDIA_BYTES) {
            _error.value = "الملف كبير جداً (الحد الأقصى ٤ ميجابايت في هذا الإصدار)"
            return
        }
        val ttlSeconds = AppSettings.messageTtlSeconds(app)

        viewModelScope.launch {
            _error.value = null
            _isSending.value = true
            val sent = try {
                repository.sendMediaMessage(contactId, bytes, mimeType, fileName, mediaType, ttlSeconds, waveform, caption)
            } catch (e: Exception) {
                _error.value = "فشل حفظ الملف: ${e.message}"
                lastFailedAction = { sendMedia(bytes, mimeType, fileName, mediaType, waveform, caption) }
                null
            } finally {
                _isSending.value = false
            }
            if (sent == null) return@launch
            val messageId = sent.message.clientMessageId ?: return@launch

            app.applicationScope.launch {
                val showingHandshake = needsHandshake()
                if (showingHandshake) _establishingSession.value = true
                try {
                    val client = app.awaitMessagingClient()
                    if (client == null) {
                        _error.value = "غير متصل بالشبكة المحلية — سيُعاد الإرسال عند الاتصال"
                        lastFailedAction = { app.messagingClient?.retryNow() }
                        return@launch
                    }
                    val delivered = withContext(Dispatchers.IO) {
                        client.sendMessage(contactId, sent.wirePayload, ttlSeconds, messageId)
                    }
                    if (!delivered) {
                        _error.value = "تعذّر إرسال الملف الآن — سيُعاد المحاولة تلقائياً"
                        lastFailedAction = { app.messagingClient?.retryNow() }
                    }
                } catch (e: Exception) {
                    _error.value = "تعذّر إرسال الملف الآن"
                    lastFailedAction = { app.messagingClient?.retryNow() }
                } finally {
                    if (showingHandshake) _establishingSession.value = false
                }
            }
        }
    }

    // ==================== Message interactions ====================

    /** Deliver an already-built wire payload over the relay in the background. */
    private fun deliver(wirePayload: ByteArray, messageId: String?, ttlSeconds: Int?) {
        val app = SecureMessengerApp.instance
        app.applicationScope.launch {
            try {
                val client = app.awaitMessagingClient() ?: run {
                    _error.value = "غير متصل بالشبكة المحلية — سيُعاد الإرسال عند الاتصال"
                    lastFailedAction = { app.messagingClient?.retryNow() }
                    return@launch
                }
                val delivered = withContext(Dispatchers.IO) {
                    client.sendMessage(contactId, wirePayload, ttlSeconds, messageId)
                }
                if (!delivered) {
                    _error.value = "تعذّر الإرسال الآن — سيُعاد المحاولة تلقائياً"
                    lastFailedAction = { app.messagingClient?.retryNow() }
                }
            } catch (_: Exception) {
                _error.value = "تعذّر الإرسال الآن"
                lastFailedAction = { app.messagingClient?.retryNow() }
            }
        }
    }

    /** Send a reply that quotes an earlier message. */
    fun sendReply(text: String, replyToClientId: String) {
        if (text.isBlank()) return
        val trimmed = text.trim()
        val app = SecureMessengerApp.instance
        val ttlSeconds = AppSettings.messageTtlSeconds(app)
        viewModelScope.launch {
            _error.value = null
            _isSending.value = true
            val sent = try {
                repository.sendReplyMessage(contactId, trimmed, replyToClientId, ttlSeconds)
            } catch (e: Exception) {
                _error.value = "فشل حفظ الرسالة: ${e.message}"
                null
            } finally {
                _isSending.value = false
            }
            val messageId = sent?.message?.clientMessageId ?: return@launch
            deliver(sent.wirePayload, messageId, ttlSeconds)
        }
    }

    /** Toggle an emoji reaction on a message and mirror it to the peer. */
    fun react(targetClientId: String, emoji: String) {
        viewModelScope.launch {
            val payload = try {
                repository.reactToMessage(targetClientId, emoji)
            } catch (_: Exception) {
                return@launch
            }
            deliver(payload, null, null)
        }
    }

    /** Edit the text of a message I sent. */
    fun editMessage(targetClientId: String, newText: String) {
        if (newText.isBlank()) return
        viewModelScope.launch {
            val payload = try {
                repository.editMessage(targetClientId, newText.trim())
            } catch (_: Exception) {
                return@launch
            }
            deliver(payload, null, null)
        }
    }

    /** Delete a message for everyone (tombstone on both sides). */
    fun deleteForEveryone(targetClientId: String) {
        viewModelScope.launch {
            val payload = try {
                repository.deleteForEveryone(targetClientId)
            } catch (_: Exception) {
                return@launch
            }
            deliver(payload, null, null)
        }
    }

    /** Delete a message from my device only. */
    fun deleteForMe(messageId: Long) {
        viewModelScope.launch {
            try {
                repository.deleteForMe(messageId)
            } catch (_: Exception) {
            }
        }
    }
}
