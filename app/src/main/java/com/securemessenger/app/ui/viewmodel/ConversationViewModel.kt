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

    /**
     * How much is actually lost if [lastFailedAction] gets silently replaced
     * by a later, unrelated failure before the user ever taps "إعادة" — see
     * [reportFailure]. Several independently-launched coroutines in this
     * class (a send, a media send, a reaction/edit/delete delivery) can all
     * fail around the same time and each wants to set this single shared
     * slot; without ranking them, whichever happened to finish last simply
     * overwrote whatever was there, even a strictly more important failure.
     */
    private enum class FailureSeverity {
        /**
         * The message was never durably saved ANYWHERE — repository.sendMessageToContact
         * / sendMediaMessage itself threw. There is no other safety net for
         * this: SecureMessagingClient's pending-send/outbox retries only
         * ever see a message that made it into local storage in the first
         * place. Losing this retry action means that message can never be
         * resent at all, only retyped from scratch.
         */
        SAVE_FAILED,
        /**
         * The message WAS saved locally but delivery failed or hasn't
         * happened yet. This is nowhere near as costly to lose track of: the
         * durable outbox / pending-send retry (SecureMessagingClient) keeps
         * retrying it automatically regardless of whether this Snackbar's
         * retry button is ever tapped — losing lastFailedAction here only
         * costs the convenience of retrying sooner than the next automatic
         * sweep.
         */
        DELIVERY_FAILED
    }

    private var lastFailureSeverity: FailureSeverity? = null

    /**
     * Show an error and remember how to retry it — but never let a
     * DELIVERY_FAILED (self-healing via the durable outbox regardless) silently
     * bump a still-pending SAVE_FAILED (no other safety net at all) out of
     * [lastFailedAction] before the user ever sees or retries it.
     */
    private fun reportFailure(message: String, severity: FailureSeverity, action: (() -> Unit)? = null) {
        if (lastFailureSeverity == FailureSeverity.SAVE_FAILED && severity == FailureSeverity.DELIVERY_FAILED) return
        _error.value = message
        lastFailedAction = action
        lastFailureSeverity = severity
    }

    /** Clears the currently-shown error (the Snackbar dismissed/timed out on its own). */
    fun clearError() {
        _error.value = null
    }

    /** Re-run whatever just failed, if anything did. */
    fun retryLastAction() {
        val action = lastFailedAction ?: return
        lastFailedAction = null
        lastFailureSeverity = null
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
                reportFailure("فشل حفظ الرسالة: ${e.message}", FailureSeverity.SAVE_FAILED) { sendMessage(trimmed) }
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
                        reportFailure(
                            "غير متصل بالشبكة المحلية — الرسالة محفوظة وستُرسَل عند الاتصال",
                            FailureSeverity.DELIVERY_FAILED
                        ) { app.messagingClient?.retryNow() }
                        return@launch
                    }
                    val delivered = withContext(Dispatchers.IO) {
                        client.sendMessage(contactId, trimmed.toByteArray(Charsets.UTF_8), ttlSeconds, messageId)
                    }
                    if (!delivered) {
                        reportFailure(
                            "تعذّر الإرسال الآن — سيُعاد المحاولة تلقائياً عند اتصال الطرف الآخر",
                            FailureSeverity.DELIVERY_FAILED
                        ) { app.messagingClient?.retryNow() }
                    }
                } catch (e: Exception) {
                    reportFailure("تعذّر الإرسال الآن", FailureSeverity.DELIVERY_FAILED) { app.messagingClient?.retryNow() }
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
                reportFailure("فشل حفظ الملف: ${e.message}", FailureSeverity.SAVE_FAILED) {
                    sendMedia(bytes, mimeType, fileName, mediaType, waveform, caption)
                }
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
                        reportFailure(
                            "غير متصل بالشبكة المحلية — سيُعاد الإرسال عند الاتصال",
                            FailureSeverity.DELIVERY_FAILED
                        ) { app.messagingClient?.retryNow() }
                        return@launch
                    }
                    val delivered = withContext(Dispatchers.IO) {
                        client.sendMessage(contactId, sent.wirePayload, ttlSeconds, messageId)
                    }
                    if (!delivered) {
                        reportFailure(
                            "تعذّر إرسال الملف الآن — سيُعاد المحاولة تلقائياً",
                            FailureSeverity.DELIVERY_FAILED
                        ) { app.messagingClient?.retryNow() }
                    }
                } catch (e: Exception) {
                    reportFailure("تعذّر إرسال الملف الآن", FailureSeverity.DELIVERY_FAILED) { app.messagingClient?.retryNow() }
                } finally {
                    if (showingHandshake) _establishingSession.value = false
                }
            }
        }
    }

    // ==================== Message interactions ====================

    /**
     * Deliver an already-built wire payload over the relay in the background.
     * @param messageId travels in the envelope as a read-receipt reference
     * for a real chat message — pass null for a reaction/edit/delete control
     * op, which isn't receipted. It doubles as the durable pending-send
     * tracking key on the SecureMessagingClient side, though: passing null
     * there means a session-establishment failure for THIS specific send has
     * no durable retry at all (see react/editMessage/deleteForEveryone,
     * which pass a fresh id generated purely for that tracking, not for any
     * receipt).
     */
    private fun deliver(wirePayload: ByteArray, messageId: String?, ttlSeconds: Int?) {
        val app = SecureMessengerApp.instance
        app.applicationScope.launch {
            try {
                val client = app.awaitMessagingClient() ?: run {
                    reportFailure(
                        "غير متصل بالشبكة المحلية — سيُعاد الإرسال عند الاتصال",
                        FailureSeverity.DELIVERY_FAILED
                    ) { app.messagingClient?.retryNow() }
                    return@launch
                }
                val delivered = withContext(Dispatchers.IO) {
                    client.sendMessage(contactId, wirePayload, ttlSeconds, messageId)
                }
                if (!delivered) {
                    reportFailure(
                        "تعذّر الإرسال الآن — سيُعاد المحاولة تلقائياً",
                        FailureSeverity.DELIVERY_FAILED
                    ) { app.messagingClient?.retryNow() }
                }
            } catch (_: Exception) {
                reportFailure("تعذّر الإرسال الآن", FailureSeverity.DELIVERY_FAILED) { app.messagingClient?.retryNow() }
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
                reportFailure("فشل حفظ الرسالة: ${e.message}", FailureSeverity.SAVE_FAILED) { sendReply(trimmed, replyToClientId) }
                null
            } finally {
                _isSending.value = false
            }
            val messageId = sent?.message?.clientMessageId ?: return@launch
            deliver(sent.wirePayload, messageId, ttlSeconds)
        }
    }

    /**
     * A fresh id purely so a control-op send that fails during session
     * establishment gets the same durable pending-send retry net a regular
     * message gets (see SecureMessagingClient.sendMessage's savePendingSend,
     * keyed by this id) — never used for read-receipt purposes, unlike a
     * real message's clientMessageId. Harmless as the envelope's own
     * "messageId" field too: SecureMessengerApp's incoming-message handling
     * only reads that field for a NON-control payload; a control op is
     * routed by its decrypted content, not by this id.
     */
    private fun controlOpTrackingId() = java.util.UUID.randomUUID().toString()

    /** Toggle an emoji reaction on a message and mirror it to the peer. */
    fun react(targetClientId: String, emoji: String) {
        viewModelScope.launch {
            val payload = try {
                repository.reactToMessage(targetClientId, emoji)
            } catch (_: Exception) {
                return@launch
            }
            deliver(payload, controlOpTrackingId(), null)
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
            deliver(payload, controlOpTrackingId(), null)
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
            deliver(payload, controlOpTrackingId(), null)
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
