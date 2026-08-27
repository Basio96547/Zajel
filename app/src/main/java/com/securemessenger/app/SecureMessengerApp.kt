package com.securemessenger.app

import android.app.Application
import android.content.Context
import com.securemessenger.app.crypto.AndroidKeyStoreManager
import com.securemessenger.core.crypto.ChatPayloads
import com.securemessenger.core.crypto.MediaCodec
import com.securemessenger.app.data.repository.SecureRepository
import com.securemessenger.app.network.ConnectionState
import com.securemessenger.app.network.SecureMessagingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class SecureMessengerApp : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val repository: SecureRepository by lazy {
        SecureRepository(applicationContext)
    }

    // Backed by a StateFlow so callers that fire right after login/unlock (e.g. a
    // read receipt sent the instant a conversation opens) can wait for the client
    // to finish connecting instead of silently no-op'ing on a null reference.
    private val _messagingClientFlow = MutableStateFlow<SecureMessagingClient?>(null)
    val messagingClientFlow: StateFlow<SecureMessagingClient?> = _messagingClientFlow

    var messagingClient: SecureMessagingClient?
        get() = _messagingClientFlow.value
        private set(value) {
            _messagingClientFlow.value = value
        }

    // Follows whichever client is current (the calculator disguise recreates
    // it on every reveal), so a connection-status bar keeps working across
    // hide/reveal cycles instead of freezing on the previous client's state.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val connectionState: StateFlow<ConnectionState> = _messagingClientFlow
        .flatMapLatest { client -> client?.connectionState ?: MutableStateFlow(ConnectionState.Disconnected) }
        .stateIn(applicationScope, SharingStarted.WhileSubscribed(5000), ConnectionState.Disconnected)

    /** Suspend until the messaging client has finished (re)connecting, or timeout. */
    suspend fun awaitMessagingClient(timeoutMs: Long = 10_000): SecureMessagingClient? =
        withTimeoutOrNull(timeoutMs) { messagingClientFlow.filterNotNull().first() }

    private var incomingJob: Job? = null

    suspend fun initializeMessagingClient() {
        val userId = repository.getUserId() ?: return
        if (BuildConfig.OFFLINE_MODE) return

        messagingClient?.disconnect()
        incomingJob?.cancel()

        messagingClient = SecureMessagingClient(
            context = applicationContext,
            repository = repository,
            userId = userId
        ).also { client ->
            client.connect()

            // Persist every decrypted incoming message so it appears in the
            // recipient's conversation (and creates the contact if new).
            incomingJob = applicationScope.launch {
                for (received in client.incomingMessages) {
                        try {
                            // A control op (reaction/edit/delete) modifies an
                            // existing message rather than creating a new one —
                            // it never creates a contact and never shows as a
                            // new bubble, so handle it before anything else.
                            val control = ChatPayloads.tryParseControl(received.plaintext)
                            if (control != null) {
                                repository.applyIncomingControl(control, received.senderId)
                            } else {
                                // No directory to resolve a display name from anymore —
                                // a message can only ever arrive from someone we already
                                // paired with directly (QR), so this is just a safety net.
                                val senderUsername: String? = null
                                val media = MediaCodec.tryParseWirePayload(received.plaintext)
                                val textPayload = ChatPayloads.tryParseText(received.plaintext)
                                when {
                                    media != null -> repository.saveIncomingMediaMessage(
                                        senderId = received.senderId,
                                        senderIdentityKey = received.senderIdentityKey,
                                        media = media,
                                        ttlSeconds = received.ttlSeconds,
                                        clientMessageId = received.messageId,
                                        senderUsername = senderUsername
                                    )
                                    // A reply: plain text plus the quoted-message linkage.
                                    textPayload != null -> repository.saveIncomingMessage(
                                        senderId = received.senderId,
                                        senderIdentityKey = received.senderIdentityKey,
                                        plaintext = textPayload.text.toByteArray(Charsets.UTF_8),
                                        ttlSeconds = received.ttlSeconds,
                                        clientMessageId = received.messageId,
                                        senderUsername = senderUsername,
                                        replyToClientId = textPayload.replyToClientId,
                                        replySnippet = textPayload.replySnippet
                                    )
                                    else -> repository.saveIncomingMessage(
                                        senderId = received.senderId,
                                        senderIdentityKey = received.senderIdentityKey,
                                        plaintext = received.plaintext,
                                        ttlSeconds = received.ttlSeconds,
                                        clientMessageId = received.messageId,
                                        senderUsername = senderUsername
                                    )
                                }
                                // A plain text message — the overwhelmingly
                                // common case — carries no ChatPayloads wrapper
                                // (that only appears on replies), so its preview
                                // has to come from the raw plaintext. An earlier
                                // version read `textPayload?.text ?: media?.let { null }`,
                                // whose second branch is `null` by construction:
                                // the preview was therefore null for every
                                // ordinary message, and the "الاسم ومقتطف من النص"
                                // notification setting could never show any text.
                                val preview = when {
                                    media != null -> null // nothing readable to preview
                                    textPayload != null -> textPayload.text
                                    else -> runCatching { received.plaintext.toString(Charsets.UTF_8) }.getOrNull()
                                }
                                announceArrival(received.senderId, preview)
                            }
                        } catch (_: Exception) {
                            // Ignore a single malformed/undeliverable message.
                        }
                }
            }
        }
    }

    /**
     * Tell the user something arrived, at whatever detail level they chose.
     *
     * Skipped entirely while the messenger is on screen — they are already
     * looking at the conversation, and a notification for a message visibly
     * landing in front of them is pure noise (and one more thing on the lock
     * screen if the phone is picked up by someone else a moment later).
     */
    private suspend fun announceArrival(senderId: String, previewText: String?) {
        if (com.securemessenger.app.security.DisguiseState.isRevealed.value) return
        val name = try {
            repository.getContactNickname(senderId)
        } catch (e: Exception) {
            null
        }
        com.securemessenger.app.service.MessengerService.notifyMessage(
            context = applicationContext,
            senderName = name ?: senderId.take(8),
            preview = previewText?.take(80)
        )
    }

    /**
     * Stops this device's own local relay + network discovery entirely — the
     * "طفى حسب الاستخدام" half of the local-only design. Called the instant
     * the disguise hides, mirroring how [initializeMessagingClient] is only
     * ever called on reveal. No external server exists to still be "logged
     * into" while hidden, unlike the old always-on relay connection.
     */
    fun stopMessagingClient() {
        incomingJob?.cancel()
        incomingJob = null
        messagingClient?.disconnect()
        messagingClient = null
        // Every hide is also the natural checkpoint for purging any
        // plaintext media a viewer decrypted to cacheDir for an external app
        // to open — see SecureRepository.purgeDecryptedMediaCache. Only a
        // full wipe used to clean this up at all. Safe even on the very
        // first hide of a fresh install: this only touches a cache
        // directory, not the (possibly not yet initialize()'d) database.
        repository.purgeDecryptedMediaCache()
    }

    override fun onCreate() {
        super.onCreate()

        // Hand :core its platform pieces before anything can touch crypto.
        // :core compiles against the LazySodium API but ships no native library
        // of its own precisely so this app and the desktop client can each
        // supply their own — same Kotlin, same key derivation, different .so.
        com.securemessenger.core.Platform.installSodium(
            com.goterl.lazysodium.LazySodiumAndroid(com.goterl.lazysodium.SodiumAndroid())
        )
        com.securemessenger.core.Platform.installLogger(object : com.securemessenger.core.CoreLogger {
            override fun debug(tag: String, message: String) {
                android.util.Log.d(tag, message)
            }
            override fun warn(tag: String, message: String, error: Throwable?) {
                android.util.Log.w(tag, message, error)
            }
            override fun error(tag: String, message: String, error: Throwable?) {
                android.util.Log.e(tag, message, error)
            }
        })

        // A leftover prefs file under the old ("secure_prefs") name would give
        // away what this app really is to anyone poking at its private
        // storage — remove it if an earlier build left one.
        deleteSharedPreferences("secure_prefs")

        // Initialize Android KeyStore
        try {
            AndroidKeyStoreManager.getOrCreateMasterKey()
        } catch (e: Exception) {
            // Handle initialization error
            e.printStackTrace()
        }

        // Self-destructing messages' TTL is only ever actually enforced by
        // this periodic WorkManager job (see MessageCleanupWorker) — nothing
        // else deletes an expired message. It used to be scheduled only from
        // MessageCleanupService.onCreate(), but nothing in the app ever
        // starts that Service (only BootReceiver called
        // schedulePeriodicCleanup directly, on ACTION_BOOT_COMPLETED), so a
        // fresh install that hadn't yet been through a reboot had no
        // enforcement at all — every "disappearing" message just... stayed,
        // silently breaking the one promise self-destruct messages make.
        // enqueueUniquePeriodicWork + KEEP below makes this idempotent, so
        // calling it here too (on every process start, not just after a
        // reboot) is safe even though BootReceiver still also calls it.
        com.securemessenger.app.service.MessageCleanupService.schedulePeriodicCleanup(this)
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        instance = this
    }

    companion object {
        // Singleton instance for global access
        lateinit var instance: SecureMessengerApp
            private set
    }
}
