package com.securemessenger.desktop

import com.securemessenger.core.B64
import com.securemessenger.core.Platform
import com.securemessenger.core.crypto.AckToken
import com.securemessenger.core.crypto.ChatPayloads
import com.securemessenger.core.crypto.LibsodiumWrapper
import com.securemessenger.core.crypto.MailboxToken
import com.securemessenger.core.crypto.MediaCodec
import com.securemessenger.core.crypto.MessagePadding
import com.securemessenger.core.crypto.PqKem
import com.securemessenger.core.crypto.SignalProtocol
import com.securemessenger.core.net.Envelopes
import com.securemessenger.core.net.LanAddress
import com.securemessenger.core.net.LocalRelayServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val TAG = "DesktopMessaging"

/** Same fixed port the Android app listens on, so a peer's stored address keeps working across platforms. */
const val LOCAL_RELAY_PORT = 47601

private const val TOKEN_ROTATION_MS = 60 * 60 * 1000L
private const val SERVER_IDLE_TIMEOUT_MS = 90 * 1000
private const val LOCAL_BUNDLE_TIMEOUT_MS = 8_000L
private const val RELAY_BUNDLE_TIMEOUT_MS = 75_000L
private const val OUTBOX_RETRY_MS = 60 * 1000L
private const val BUNDLE_HANDOUT_MIN_INTERVAL_MS = 10_000L
// Same window the Android app clears a typing pulse after (ConversationViewModel).
private const val TYPING_TIMEOUT_MS = 4_000L

/**
 * The desktop client's half of the protocol.
 *
 * Deliberately a sibling of the Android `SecureMessagingClient` rather than a
 * port of it: the two orchestrate differently (a PC has no calculator disguise,
 * no battery budget, and can stay listening) but they speak the *same* wire
 * protocol, because everything that goes on the wire — the ratchet, the sealed
 * envelopes, the bundle signatures, the relay blobs — comes from :core and is
 * literally the same compiled code on both sides.
 *
 * That split is the whole design: behaviour may differ per platform, format may
 * not.
 */
class DesktopMessagingClient(
    private val store: DesktopStore,
    private val relayUrl: String,
    private val onMessage: (contactId: String, text: String, clientMessageId: String?) -> Unit,
    private val onStateChanged: () -> Unit
) {
    // var, not val: stop() cancels this scope's Job, and a cancelled
    // CoroutineScope can't usefully host new work — a coroutine launched on
    // it starts with isActive already false, so every `while (isActive)`
    // periodic job below (token rotation, outbox retry) would exit
    // immediately without doing anything. start() replaces it with a fresh
    // scope whenever the current one is no longer active, so the same
    // client instance can be stopped and restarted (this app keeps one
    // instance for its whole lifetime, unlike the Android app which
    // constructs a fresh one on every reveal).
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Sockets to peers on this LAN, bound to the local Wi-Fi/Ethernet address.
     *
     * Same reasoning as the Android client's Wi-Fi pinning: with a VPN up, the
     * default route sends everything into the tunnel, including a connection to
     * a phone one hop away on the same Wi-Fi. Binding the socket's *source*
     * address to the LAN interface makes the OS pick the matching route
     * instead. A desktop with no VPN is unaffected — this is the address it
     * would have chosen anyway.
     */
    private val localHttp = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .socketFactory(LocalBoundSocketFactory)
        .build()

    /**
     * Relay traffic, deliberately left on the default route so it keeps going
     * through the VPN. The relay is the one party that unavoidably sees an IP
     * address — exactly where a tunnel is worth having.
     */
    private val relayHttp = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var server: LocalRelayServer? = null
    var boundPort: Int = LOCAL_RELAY_PORT
        private set

    private val discovery = DesktopDiscovery()
    private val peerSockets = ConcurrentHashMap<String, WebSocket>()
    private val discoveredByToken = ConcurrentHashMap<String, DesktopPeer>()
    private val pendingBundleRequests = ConcurrentHashMap<String, CompletableDeferred<Envelopes.PrekeyBundle>>()
    private val pendingChallengeNonces = ConcurrentHashMap<String, ByteArray>()
    private val lastBundleHandout = ConcurrentHashMap<String, Long>()
    /** contactId -> when their last typing pulse arrived. Ephemeral, never persisted. */
    private val lastTypingAt = ConcurrentHashMap<String, Long>()

    private val sessions = ConcurrentHashMap<String, SignalProtocol>()
    private val responderEphemerals = ConcurrentHashMap<String, String>()
    private val initiatorOtkIds = ConcurrentHashMap<String, Int>()
    private val initiatorPqCiphertexts = ConcurrentHashMap<String, ByteArray>()

    private val contactLocks = mutableMapOf<String, Mutex>()
    private fun lockFor(contactId: String): Mutex =
        synchronized(contactLocks) { contactLocks.getOrPut(contactId) { Mutex() } }

    // Guards "establish an initiator session for this contact if none
    // exists" (X3DH: a live prekey-bundle fetch, up to RELAY_BUNDLE_TIMEOUT_MS)
    // — deliberately separate from lockFor's ratchet lock, which is also
    // needed to decrypt an incoming message from this same contact and must
    // never sit blocked behind a slow handshake to them. See
    // initiatorSession / sendMessageInternal.
    private val handshakeLocks = mutableMapOf<String, Mutex>()
    private fun handshakeLockFor(contactId: String): Mutex =
        synchronized(handshakeLocks) { handshakeLocks.getOrPut(contactId) { Mutex() } }

    // Envelopes already SUCCESSFULLY processed, keyed by "type:id" — only
    // ever holds one the handler actually finished, never one it dropped (no
    // session yet, lost a simultaneous-initiation race). Caching a drop
    // would make it permanent: the sender's outbox keeps resending the
    // identical envelope, and every resend would be discarded from this
    // cache before the handler — by now possibly able to succeed — ever ran
    // again. See onceOnly.
    private val seenEnvelopes = object : LinkedHashMap<String, String>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 512
    }

    private val relay: DesktopRelayClient? = relayUrl.takeIf { it.isNotBlank() }?.let {
        DesktopRelayClient(store, it.trimEnd('/'), relayHttp) { json, pendingHex ->
            handleRelayEnvelope(json, pendingHex)
        }
    }

    @Volatile
    var status: String = "غير متصل"
        private set

    private var jobs = mutableListOf<Job>()

    // ================= lifecycle =================

    fun start() {
        // stop() cancelled the previous scope's Job — a coroutine launched
        // on a cancelled scope never actually runs its body, so every
        // periodic job below would silently no-op on a restart without
        // this. See the `scope` property's own doc comment.
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val srv = try {
            LocalRelayServer(LOCAL_RELAY_PORT).also { it.start(SERVER_IDLE_TIMEOUT_MS, false); boundPort = LOCAL_RELAY_PORT }
        } catch (e: java.io.IOException) {
            // Port taken — most often the app is already running, or something
            // else on this PC grabbed it. An OS-assigned port still works
            // because whatever we bind is what gets advertised.
            Platform.log.warn(TAG, "port $LOCAL_RELAY_PORT unavailable, using an OS-assigned one")
            LocalRelayServer(0).also { it.start(SERVER_IDLE_TIMEOUT_MS, false); boundPort = it.listeningPort }
        }
        srv.onEnvelope = { text, reply -> scope.launch { handleIncoming(text, reply) } }
        server = srv

        // Discovery is a convenience, not a prerequisite. jmDNS can fail
        // outright on an interface it doesn't like, and on a machine with a
        // VPN or a container bridge that is not rare — but a remembered direct
        // address still works perfectly without it, so a discovery failure must
        // not take the whole client down with it.
        runCatching {
            discovery.start(boundPort, myToken()) { peers ->
                // REPLACE, not merge — serviceRemoved drops the peer and calls
                // back with the whole remaining list, so anything missing here
                // has left the network. Merging kept departed peers "discovered"
                // forever, which made every send to them wait out the full
                // connect timeout on a dead address before trying the relay.
                discoveredByToken.keys.retainAll(peers.mapTo(HashSet()) { it.token })
                peers.forEach { discoveredByToken[it.token] = it }
                scope.launch { peers.forEach { peer -> contactIdForToken(peer.token)?.let { flushOutboxTo(it) } } }
            }
        }.onFailure {
            Platform.log.warn(TAG, "mDNS unavailable — direct addresses still work", it)
        }

        relay?.start(scope)
        status = "يستمع على ${myDirectAddress() ?: "المنفذ $boundPort"}"

        jobs += scope.launch {
            var last = myToken()
            while (isActive) {
                delay(5 * 60 * 1000L)
                val now = myToken()
                if (now != last) {
                    last = now
                    discovery.register(boundPort, now)
                }
            }
        }
        jobs += scope.launch {
            while (isActive) {
                // Runs immediately on every start(), not just every
                // OUTBOX_RETRY_MS after — otherwise a message stuck since
                // before the last stop() would sit untouched for up to a
                // minute after the app comes back up.
                runCatching {
                    retryOutbox()
                    retryPendingSends()
                }
                delay(OUTBOX_RETRY_MS)
            }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        relay?.stop()
        discovery.stop()
        peerSockets.values.forEach { runCatching { it.close(1000, "bye") } }
        peerSockets.clear()
        runCatching { server?.stop() }
        server = null
        status = "غير متصل"
        scope.cancel()
    }

    fun myDirectAddress(): String? =
        (discovery.boundAddress ?: LanAddress.best())?.let { "$it:$boundPort" }

    // ================= identity / discovery tokens =================

    private fun currentBucket(): Long = System.currentTimeMillis() / TOKEN_ROTATION_MS

    /** Byte-for-byte the same derivation the Android client uses, or neither side recognises the other. */
    private fun shortToken(publicKey: ByteArray, bucket: Long): String =
        B64.toHex(
            LibsodiumWrapper.blake2b(publicKey + bucket.toString().toByteArray(Charsets.UTF_8), length = 4)
        )

    private fun myToken(): String = shortToken(store.identity.publicKey, currentBucket())

    private fun contactIdForToken(token: String): String? {
        val bucket = currentBucket()
        return store.allContacts().firstOrNull { contact ->
            (-1L..1L).any { shortToken(contact.publicKey, bucket + it) == token }
        }?.id
    }

    // ================= transport =================

    private suspend fun connectionTo(contactId: String): WebSocket? {
        peerSockets[contactId]?.let { return it }
        val contact = store.contact(contactId) ?: return null
        val bucket = currentBucket()
        val discovered = (-1L..1L).firstNotNullOfOrNull { discoveredByToken[shortToken(contact.publicKey, bucket + it)] }
        if (discovered != null) {
            open(contactId, discovered.host, discovered.port)?.let { return it }
        }
        // Same fallback as the phone: a remembered address keeps a contact
        // reachable on the many networks where multicast discovery never works.
        val remembered = contact.directAddress ?: return null
        val (host, port) = LanAddress.parse(remembered, LOCAL_RELAY_PORT) ?: return null
        // Never dial ourselves — see LanAddress.isOwnAddress. Two machines on
        // the same VPN commonly share a tunnel address, and reaching our own
        // socket produces an unopenable sealed frame and an endless timeout.
        // Host AND port: another instance on this same machine is reached
        // through this machine's address too, just on a different port.
        if (port == boundPort && LanAddress.isOwnAddress(host)) {
            Platform.log.warn(TAG, "stored address $host:$port for $contactId is our own socket — discarding it")
            store.setDirectAddress(contactId, null)
            return null
        }
        if (discovered != null && discovered.host == host && discovered.port == port) return null
        return open(contactId, host, port)
    }

    private suspend fun open(contactId: String, host: String, port: Int): WebSocket? {
        val opened = CompletableDeferred<WebSocket?>()
        val socket = localHttp.newWebSocket(
            Request.Builder().url("ws://$host:$port/ws").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    peerSockets[contactId] = webSocket
                    store.setDirectAddress(contactId, "$host:$port")
                    if (!opened.isCompleted) opened.complete(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    scope.launch { handleIncoming(text) { reply -> webSocket.send(reply) } }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    peerSockets.remove(contactId)
                    if (!opened.isCompleted) opened.complete(null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    peerSockets.remove(contactId)
                }
            }
        )
        return withTimeoutOrNull(5000) { opened.await() } ?: run {
            runCatching { socket.cancel() }
            null
        }
    }

    private suspend fun sendDirect(contactId: String, envelope: String): Boolean =
        connectionTo(contactId)?.let { runCatching { it.send(envelope) }.getOrDefault(false) } ?: false

    private suspend fun sendToContact(contactId: String, envelope: String): Boolean {
        if (sendDirect(contactId, envelope)) return true
        return relay?.enqueue(contactId, envelope) ?: false
    }

    private suspend fun flushOutboxTo(contactId: String) {
        store.allOutbox().filter { it.recipientId == contactId }.forEach { sendDirect(contactId, it.envelope) }
        retryPendingSendsTo(contactId)
    }

    private suspend fun retryOutbox() {
        for (entry in store.allOutbox()) {
            if (sendDirect(entry.recipientId, entry.envelope)) continue
            relay?.enqueue(entry.recipientId, entry.envelope)
        }
    }

    // ================= sending =================

    /** Send a brand-new, user-typed message. For re-attempting one that already has a local row, see [retryPendingSends]. */
    suspend fun sendMessage(contactId: String, text: String): Boolean {
        // Saved locally FIRST, unconditionally — the message must appear in
        // the sender's own history even if establishing a session with the
        // recipient (a live X3DH round trip, up to ~75s over the relay)
        // fails or times out. This used to only create the local row AFTER
        // a successful handshake, so a failed one didn't just fail to
        // deliver — the message the user typed never appeared anywhere at
        // all, not even in their own chat, and nothing ever retried it.
        val clientMessageId = java.util.UUID.randomUUID().toString()
        store.addMessage(DesktopStore.StoredMessage(contactId, true, text, System.currentTimeMillis(), clientMessageId))
        onStateChanged()
        return sendMessageAndTrack(contactId, text, clientMessageId)
    }

    /**
     * Attempt (or re-attempt) delivery for a message that already has a
     * local row — either just created above, or a previous attempt that
     * failed and was persisted as a pending send (see [retryPendingSends]).
     * Never creates a new local row itself, so a retry can't duplicate the
     * bubble in the sender's own history.
     */
    private suspend fun sendMessageAndTrack(contactId: String, text: String, clientMessageId: String): Boolean {
        return try {
            sendMessageInternal(contactId, text, clientMessageId)
            store.removePendingSend(clientMessageId)
            true
        } catch (e: Exception) {
            Platform.log.error(TAG, "sendMessage failed: ${e.message}", e)
            // Establishing a session needs the recipient to answer live (an
            // X3DH prekey fetch, via initiatorSession) — when that fails,
            // the message never reaches the outbox, so nothing else would
            // ever retry it. Persisting it here is what retryPendingSends /
            // retryPendingSendsTo actually act on.
            store.addPendingSend(DesktopStore.StoredPendingSend(clientMessageId, contactId, text))
            false
        }
    }

    private suspend fun sendMessageInternal(contactId: String, text: String, clientMessageId: String) {
        val contact = store.contact(contactId) ?: throw IllegalStateException("جهة اتصال غير معروفة")

        // May need a live round trip to fetch the recipient's prekey bundle
        // (up to RELAY_BUNDLE_TIMEOUT_MS ≈ 75s). Deliberately outside the
        // per-contact ratchet lock below, so a slow handshake with this
        // contact can never block a message arriving FROM them (handleMessage
        // takes that same lock to decrypt). Concurrent callers for the same
        // contact are serialized by initiatorSession's own handshake lock.
        initiatorSession(contactId)

        val envelopeId = java.util.UUID.randomUUID().toString()
        val ackToken = AckToken.compute(store.identity.secretKey, envelopeId)

        val envelope = lockFor(contactId).withLock {
            // Re-read the current session here rather than trust a reference
            // captured before this lock: a message arriving FROM this same
            // contact concurrently (simultaneous initiation) can rebuild it
            // as a responder session between the call above and this lock
            // being acquired (see responderSession / sessionForIncoming's
            // tie-break, which runs under this very lock). Reading it fresh
            // guarantees this always encrypts with — and correctly describes
            // as initiator or not — whichever session is actually current.
            val protocol = cached(contactId) ?: throw IllegalStateException("session vanished for $contactId")
            val encrypted = protocol.encryptMessage(MessagePadding.pad(text.toByteArray(Charsets.UTF_8)))
            val weAreInitiator = !responderEphemerals.containsKey(contactId)

            val inner = JSONObject().apply {
                put("senderId", store.userId)
                put("ackToken", ackToken)
                put("ciphertext", B64.encode(encrypted.ciphertext))
                put("dhPublicKey", B64.encode(encrypted.dhPublicKey))
                put("chainCounter", encrypted.chainCounter)
                put("previousChainLength", encrypted.previousChainLength)
                put("timestamp", System.currentTimeMillis())
                put("messageId", clientMessageId)
                put("senderIdentityKey", B64.encode(store.identity.publicKey))
                if (weAreInitiator) {
                    protocol.getInitiatorEphemeralPublicKey()?.let { put("initiatorEphemeralKey", B64.encode(it)) }
                    initiatorOtkIds[contactId]?.let { put("oneTimePreKeyId", it) }
                    initiatorPqCiphertexts[contactId]?.let { put("pqKemCiphertext", B64.encode(it)) }
                }
            }

            persistSession(contactId)
            Envelopes.seal(Envelopes.TYPE_MESSAGE, inner, contact.publicKey, envelopeId).toString()
        }

        store.addOutbox(DesktopStore.StoredOutbox(envelopeId, contactId, envelope, clientMessageId))
        onStateChanged()
        sendToContact(contactId, envelope)
    }

    /**
     * Re-attempt every message that never made it as far as the outbox —
     * session establishment failed or timed out the first time. Unlike
     * [retryOutbox], each of these needs a full resend (sendMessageAndTrack),
     * not just a transport retry of an already-sealed envelope — there is no
     * envelope yet. Run concurrently: one contact stuck on a ~75s relay
     * timeout must not delay retrying everyone else.
     */
    private suspend fun retryPendingSends() = kotlinx.coroutines.coroutineScope {
        for (pending in store.allPendingSends()) {
            launch { sendMessageAndTrack(pending.contactId, pending.text, pending.clientMessageId) }
        }
    }

    /** Same as [retryPendingSends], scoped to messages waiting on one contact — see [flushOutboxTo]. */
    private suspend fun retryPendingSendsTo(contactId: String) = kotlinx.coroutines.coroutineScope {
        for (pending in store.pendingSendsForContact(contactId)) {
            launch { sendMessageAndTrack(pending.contactId, pending.text, pending.clientMessageId) }
        }
    }

    // ================= receiving =================

    private suspend fun handleIncoming(jsonString: String, reply: (String) -> Unit = {}) {
        try {
            val json = JSONObject(jsonString)
            when (json.getString("type")) {
                Envelopes.TYPE_MESSAGE -> {
                    val token = onceOnly(Envelopes.TYPE_MESSAGE, json) { handleMessage(json) }
                    json.optString("id").takeIf { it.isNotBlank() }?.let { reply(Envelopes.ack(it, token)) }
                }
                Envelopes.TYPE_ACK -> handleAck(json)
                Envelopes.TYPE_CHALLENGE -> handleChallenge(json, reply)
                Envelopes.TYPE_BUNDLE_ANNOUNCE -> handleBundleAnnounce(json)
                Envelopes.TYPE_RECEIPT -> {
                    val token = onceOnly(Envelopes.TYPE_RECEIPT, json) { handleReceipt(json) }
                    json.optString("id").takeIf { it.isNotBlank() }?.let { id -> reply(Envelopes.ack(id, token)) }
                }
                // Ephemeral, no id to dedup against and no ack expected — same
                // as the phone. Each pulse just refreshes the display window.
                Envelopes.TYPE_TYPING -> handleTyping(json)
                Envelopes.TYPE_NOISE -> {}
            }
        } catch (e: Exception) {
            Platform.log.error(TAG, "handleIncoming failed: ${e.message}", e)
        }
    }

    /**
     * Run [handler] exactly once per envelope id, however many times it
     * *successfully* arrives.
     *
     * A resend whose ack was lost, and the same envelope racing in over both
     * the socket and the relay, are both normal. The handler advances the
     * ratchet, so running it twice on an envelope it already succeeded on
     * would fail to decrypt and could surface a duplicate — a repeat of THAT
     * reuses the remembered ack token instead.
     *
     * A repeat of an envelope the handler previously *dropped* (returned
     * null without throwing — no session yet, lost a simultaneous-initiation
     * race) is different: nothing was consumed, so it's both safe and
     * necessary to run the handler again. The sender's durable outbox keeps
     * resending that exact envelope regardless, for as long as whatever
     * blocked it might take to resolve — caching the drop as if it were
     * final would silently blackhole the message forever.
     */
    private suspend fun onceOnly(type: String, json: JSONObject, handler: suspend () -> String?): String? {
        val id = json.optString("id").takeIf { it.isNotBlank() } ?: return handler()
        val key = "$type:$id"
        synchronized(seenEnvelopes) { seenEnvelopes[key] }?.let { return it }
        val token = handler()
        if (token != null) {
            synchronized(seenEnvelopes) { seenEnvelopes[key] = token }
        }
        return token
    }

    private fun unseal(outer: JSONObject): JSONObject =
        Envelopes.open(outer, store.identity.publicKey, store.identity.secretKey)

    private suspend fun handleMessage(outer: JSONObject): String? {
        val json = unseal(outer)
        val ackToken = json.optString("ackToken").takeIf { it.isNotBlank() }
        val senderId = json.getString("senderId")

        val encrypted = SignalProtocol.EncryptedMessage(
            ciphertext = B64.decode(json.getString("ciphertext")),
            chainCounter = json.getInt("chainCounter"),
            dhPublicKey = B64.decode(json.getString("dhPublicKey")),
            previousChainLength = json.optInt("previousChainLength", 0)
        )

        val plaintext = lockFor(senderId).withLock {
            val protocol = sessionForIncoming(senderId, json) ?: return null
            val decrypted = MessagePadding.unpad(protocol.decryptMessage(encrypted))
            persistSession(senderId)
            decrypted
        }

        val messageId = json.optString("messageId").takeIf { it.isNotBlank() }

        // This client has no UI (yet) for modifying an existing message or
        // for rendering media — sniff the decrypted plaintext's wire "k"
        // discriminator the same way the phone does, and handle each shape
        // on purpose instead of falling through to the raw-text branch
        // below. That branch used to run for EVERY unrecognized shape,
        // which meant a reaction/edit/delete control op or a media message
        // from a phone contact rendered as the literal JSON wire payload —
        // e.g. {"k":"ctl","op":"react",...} — permanently stored as if it
        // were a real chat message.
        if (ChatPayloads.tryParseControl(plaintext) != null) {
            // Accepted and acknowledged (below) so the sender's outbox
            // clears it, same as TYPE_RECEIPT/TYPE_TYPING elsewhere in this
            // file — but nothing is stored or shown here. Silently doing
            // nothing is more honest than either garbling raw JSON into the
            // chat or half-applying an edit/reaction this client can't
            // fully represent (no reactionsJson/edit/delete fields on
            // StoredMessage yet).
            return ackToken
        }

        val body = when {
            MediaCodec.tryParseWirePayload(plaintext) != null ->
                // Acknowledged like any other message (the sender must not
                // keep retrying it), but shown as an honest placeholder
                // instead of either the raw JSON or silently vanishing —
                // media really did arrive, the user just can't see it here.
                "📎 وسائط غير مدعومة على سطح المكتب — افتحها من الهاتف"
            // A reply arrives as a structured payload rather than raw text;
            // read the quoted form when present so it doesn't render as a
            // blob of JSON either.
            else -> ChatPayloads.tryParseText(plaintext)?.text ?: String(plaintext, Charsets.UTF_8)
        }

        store.addMessage(DesktopStore.StoredMessage(senderId, false, body, System.currentTimeMillis(), messageId))
        onMessage(senderId, body, messageId)
        onStateChanged()
        return ackToken
    }

    private fun handleAck(json: JSONObject) {
        val id = json.optString("id").takeIf { it.isNotBlank() } ?: return
        val provided = json.optString("ackToken").takeIf { it.isNotBlank() } ?: return
        val expected = AckToken.compute(store.identity.secretKey, id)
        // Only an ack proving it came from the real recipient clears the outbox:
        // the token is derived from a key only this device holds and travelled
        // sealed inside the envelope, so an on-path forger who saw the plain id
        // still cannot produce it.
        if (LibsodiumWrapper.constantTimeCompare(B64.decode(provided), B64.decode(expected))) {
            store.removeOutbox(id)
            onStateChanged()
        }
    }

    private fun handleReceipt(outer: JSONObject): String? {
        val json = runCatching { unseal(outer) }.getOrNull() ?: return null
        val senderId = json.optString("senderId").takeIf { it.isNotBlank() } ?: return null
        // A sealed envelope proves nothing about who sent it on its own, so
        // only accept receipts from a known contact — same rule as the phone.
        // markReadByRecipient further scopes by that contact, so a receipt
        // can only flip messages we actually sent them.
        if (store.contact(senderId) == null) return null
        val idsArray = json.optJSONArray("messageIds")
        if (idsArray != null) {
            val ids = (0 until idsArray.length()).map { idsArray.getString(it) }
            store.markReadByRecipient(senderId, ids)
            onStateChanged()
        }
        return json.optString("ackToken").takeIf { it.isNotBlank() }
    }

    private fun handleTyping(outer: JSONObject) {
        val json = runCatching { unseal(outer) }.getOrNull() ?: return
        val senderId = json.optString("senderId").takeIf { it.isNotBlank() } ?: return
        lastTypingAt[senderId] = System.currentTimeMillis()
        onStateChanged()
    }

    // ================= handshake =================

    private suspend fun handleChallenge(outer: JSONObject, reply: (String) -> Unit) {
        try {
            val json = unseal(outer)
            val claimed = json.optString("userId").takeIf { it.isNotBlank() } ?: return
            val contact = store.contact(claimed) ?: run {
                Platform.log.warn(TAG, "challenge from an unknown requester — refusing")
                return
            }
            // Each handout burns a one-time prekey, so an attacker who learned a
            // valid userId could otherwise drain the whole pool by asking repeatedly.
            val now = System.currentTimeMillis()
            lastBundleHandout[claimed]?.let { if (now - it < BUNDLE_HANDOUT_MIN_INTERVAL_MS) return }
            lastBundleHandout[claimed] = now

            val nonce = B64.decode(json.optString("nonce"))
            reply(Envelopes.seal(Envelopes.TYPE_BUNDLE_ANNOUNCE, buildBundle(nonce), contact.publicKey).toString())
        } catch (e: Exception) {
            Platform.log.error(TAG, "handleChallenge failed: ${e.message}", e)
        }
    }

    private fun buildBundle(nonce: ByteArray): JSONObject {
        val otk = store.unusedPreKeys().firstOrNull()
        val bundle = Envelopes.buildBundleAnnounce(
            userId = store.userId,
            nonce = nonce,
            identityKey = store.identity.publicKey,
            signedPreKey = store.signedPreKey.publicKey,
            signedPreKeyId = store.signedPreKey.id,
            signingPublicKey = store.signingPublicKey,
            mlkemPublicKey = store.mlkemPublicKey,
            oneTimePreKeyId = otk?.id,
            oneTimePreKey = otk?.publicKey,
            sign = { LibsodiumWrapper.signDetached(it, store.signingSecretKey) }
        )
        // Retired the moment it leaves, not when a handshake completes — the
        // same "one-time" key must never be served to two requesters.
        otk?.let { store.markPreKeyUsed(it.id) }
        return bundle
    }

    private fun handleBundleAnnounce(outer: JSONObject) {
        val json = unseal(outer)
        val theirId = json.optString("userId").takeIf { it.isNotBlank() } ?: return
        val bundle = Envelopes.parseBundle(json, pendingChallengeNonces[theirId]) ?: return

        // Never build a session from a self-declared identity key: it must match
        // the key pinned when this contact was added by QR, or any device on the
        // LAN could answer for them and become a live man-in-the-middle.
        val pinned = store.contact(theirId)?.publicKey
        if (pinned == null || !bundle.identityKey.contentEquals(pinned)) {
            Platform.log.error(TAG, "bundle_announce identity mismatch for $theirId — refusing", null)
            return
        }
        pendingBundleRequests[theirId]?.let { if (!it.isCompleted) it.complete(bundle) }
    }

    private suspend fun fetchBundle(contactId: String): Envelopes.PrekeyBundle {
        val deferred = CompletableDeferred<Envelopes.PrekeyBundle>()
        pendingBundleRequests[contactId] = deferred
        val nonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        pendingChallengeNonces[contactId] = nonce
        try {
            val contact = store.contact(contactId) ?: throw IllegalStateException("جهة اتصال غير معروفة")
            val challenge = Envelopes.seal(
                Envelopes.TYPE_CHALLENGE,
                Envelopes.buildChallenge(store.userId, nonce),
                contact.publicKey
            ).toString()

            val socket = connectionTo(contactId)
            val overRelay = socket == null
            if (socket != null) {
                socket.send(challenge)
            } else if (relay?.enqueue(contactId, challenge) != true) {
                throw IllegalStateException(
                    "لا يوجد مسار لهذه الجهة: غير ظاهرة على الشبكة، ولا عنوان محفوظ، ولا قناة وسيط."
                )
            }

            val timeout = if (overRelay) RELAY_BUNDLE_TIMEOUT_MS else LOCAL_BUNDLE_TIMEOUT_MS
            return withTimeoutOrNull(timeout) { deferred.await() } ?: throw IllegalStateException(
                if (overRelay) "لم يردّ الطرف الآخر عبر الوسيط — تأكد أن التطبيق مفتوح لديه"
                else "انتهت مهلة انتظار حزمة مفاتيح جهة الاتصال"
            )
        } finally {
            pendingBundleRequests.remove(contactId)
            pendingChallengeNonces.remove(contactId)
        }
    }

    // ================= sessions =================

    private fun cached(contactId: String): SignalProtocol? {
        sessions[contactId]?.let { return it }
        val stored = store.session(contactId) ?: return null
        val protocol = SignalProtocol(store.identity, store.signedPreKey, store.unusedPreKeys())
        protocol.importState(stored.state)
        sessions[contactId] = protocol
        if (stored.isInitiator) responderEphemerals.remove(contactId)
        else responderEphemerals[contactId] = stored.responderEphemeralHex ?: ""
        stored.initiatorOtkId?.let { initiatorOtkIds[contactId] = it }
        return protocol
    }

    private fun persistSession(contactId: String) {
        val protocol = sessions[contactId] ?: return
        store.saveSession(
            contactId,
            DesktopStore.StoredSession(
                state = protocol.exportState(),
                isInitiator = !responderEphemerals.containsKey(contactId),
                responderEphemeralHex = responderEphemerals[contactId],
                initiatorOtkId = initiatorOtkIds[contactId]
            )
        )
    }

    private suspend fun initiatorSession(contactId: String): SignalProtocol {
        cached(contactId)?.let { return it }
        // Two sends fired in quick succession to a contact with no session
        // yet would otherwise both start X3DH at once — each registers
        // itself in pendingBundleRequests[contactId], and the second
        // overwrites the first's entry, so the first's reply (when it
        // arrives) is delivered to the wrong waiter and times out despite a
        // valid bundle_announce having come back. Serialize per contact.
        return handshakeLockFor(contactId).withLock {
            // Re-check: another call may have finished establishing the
            // session while this one was waiting for the lock.
            cached(contactId)?.let { return@withLock it }
            val bundle = fetchBundle(contactId)
            val protocol = SignalProtocol(store.identity, store.signedPreKey, store.unusedPreKeys())

            val encapsulation = bundle.mlkemPublicKey?.let { PqKem.encapsulate(it) }
            if (bundle.mlkemPublicKey != null && encapsulation == null) {
                throw IllegalStateException("الطرف الآخر أعلن مفتاحاً ما بعد الكمّي وفشل التغليف — رفض الرجوع لمستوى أضعف")
            }

            protocol.initializeAsInitiator(
                recipientIdentityKey = bundle.identityKey,
                recipientSignedPreKey = bundle.signedPreKey,
                recipientOneTimePreKey = bundle.oneTimePreKey,
                pqSharedSecret = encapsulation?.sharedSecret
            )
            bundle.oneTimePreKeyId?.let { initiatorOtkIds[contactId] = it }
            encapsulation?.let { initiatorPqCiphertexts[contactId] = it.ciphertext }
            sessions[contactId] = protocol
            responderEphemerals.remove(contactId)
            protocol
        }
    }

    private fun sessionForIncoming(senderId: String, json: JSONObject): SignalProtocol? {
        val ephemeralHex = json.optString("initiatorEphemeralKey")
        val existing = cached(senderId)
        if (ephemeralHex.isEmpty()) return existing
        if (existing == null) return responderSession(senderId, json)
        if (responderEphemerals.containsKey(senderId)) {
            return if (responderEphemerals[senderId] == ephemeralHex) existing
            else responderSession(senderId, json)
        }
        // Both sides initiated at once. Break the tie the same way the Android
        // client does — the smaller userId stays initiator — so the two always
        // converge on one session instead of talking past each other.
        return if (senderId < store.userId) responderSession(senderId, json) else null
    }

    private fun responderSession(senderId: String, json: JSONObject): SignalProtocol {
        val initiatorEphemeral = B64.decode(json.getString("initiatorEphemeralKey"))
        val initiatorIdentity = B64.decodeOrNull(json.optString("senderIdentityKey"))
            ?: store.contact(senderId)?.publicKey
            ?: throw IllegalStateException("لا يوجد مفتاح هوية للمرسل")

        val otkId = if (json.has("oneTimePreKeyId")) json.optInt("oneTimePreKeyId") else null
        val otkSecret = otkId?.let { store.preKeySecret(it) }
        val pqSecret = B64.decodeOrNull(json.optString("pqKemCiphertext"))?.let {
            PqKem.decapsulate(store.mlkemSecretKey, it)
        }

        val protocol = SignalProtocol(store.identity, store.signedPreKey, store.unusedPreKeys())
        protocol.initializeAsResponder(
            initiatorIdentityKey = initiatorIdentity,
            initiatorEphemeralKey = initiatorEphemeral,
            ourSignedPreKeySecret = store.signedPreKey.secretKey,
            ourIdentitySecretKey = store.identity.secretKey,
            ourOneTimePreKeySecret = otkSecret,
            pqSharedSecret = pqSecret
        )
        if (otkSecret != null && otkId != null) store.markPreKeyUsed(otkId)
        sessions[senderId] = protocol
        responderEphemerals[senderId] = json.optString("initiatorEphemeralKey")
        return protocol
    }

    // ================= relay =================

    private suspend fun handleRelayEnvelope(envelopeJson: String, pendingSecretHex: String?) {
        try {
            val outer = JSONObject(envelopeJson)
            if (outer.optString("type") == Envelopes.TYPE_NOISE) return

            // The relay has no socket to answer on, so the sender has to be
            // identified before dispatch. Chat frames name them "senderId", the
            // handshake frames "userId" — reading only the first would leave a
            // relayed challenge with nowhere to send its reply.
            val senderId = runCatching {
                val inner = unseal(outer)
                inner.optString("senderId").takeIf { it.isNotBlank() }
                    ?: inner.optString("userId").takeIf { it.isNotBlank() }
            }.getOrNull()

            // A blob that landed in a mailbox we minted but hadn't yet attached
            // to anyone is how we learn who scanned that QR code.
            if (pendingSecretHex != null && senderId != null && store.contact(senderId) != null) {
                MailboxToken.pairSecretFromHex(pendingSecretHex)?.let { store.bindRelayRecvSecret(senderId, it) }
                store.removePendingSecret(pendingSecretHex)
                relay?.refreshSubscriptions()
            }

            handleIncoming(envelopeJson) { ackJson ->
                senderId?.let { id -> scope.launch { relay?.enqueue(id, ackJson) } }
            }
        } catch (e: Exception) {
            Platform.log.error(TAG, "handleRelayEnvelope failed: ${e.message}", e)
        }
    }

    // ================= pairing =================

    /** The QR payload this client shows for a phone to scan. Same schema the phone produces. */
    fun myQrPayload(): String {
        val secretHex = MailboxToken.pairSecretToHex(MailboxToken.newPairSecret())
        store.addPendingSecret(secretHex)
        relay?.refreshSubscriptions()
        return JSONObject().apply {
            put("u", store.userId)
            put("k", B64.toHex(store.identity.publicKey))
            put("n", store.displayName.ifBlank { store.userId.take(8) })
            put("s", secretHex)
            myDirectAddress()?.let { put("a", it) }
        }.toString()
    }

    /** Consume a QR payload produced by a phone (or another desktop). */
    /** Thrown by [pairFromPayload] when the scanned key differs from one already pinned — see [DesktopStore.ContactPairResult.KEY_CHANGED]. */
    class KeyChangedException(val payload: String, val contactId: String) :
        Exception("مفتاح أمان جهة الاتصال تغيّر منذ آخر اقتران")

    /**
     * @param allowKeyChange only pass true once the caller has explicitly
     * warned the user this contact's pinned key is about to change and
     * they've confirmed it — see [KeyChangedException] and
     * [DesktopStore.upsertContact].
     */
    fun pairFromPayload(payload: String, allowKeyChange: Boolean = false): Result<String> = runCatching {
        val json = JSONObject(payload)
        val theirId = json.getString("u")
        if (theirId == store.userId) throw IllegalArgumentException("هذا رمزك أنت")
        val publicKeyHex = json.getString("k")
        val publicKey = B64.fromHex(publicKeyHex) ?: throw IllegalArgumentException("مفتاح غير صالح")
        // The Android app requires exactly 32 bytes here; this side skipped
        // that check, so a malformed key silently made it into storage and
        // only failed later, less predictably, wherever it was first used
        // for a DH. Fail the same way, in the same place, as the phone does.
        if (publicKey.size != 32) throw IllegalArgumentException("المفتاح يجب أن يكون 32 بايت (64 محرف hex)")
        val result = store.upsertContact(
            DesktopStore.StoredContact(
                id = theirId,
                publicKey = publicKey,
                displayName = json.optString("n").ifBlank { theirId.take(8) },
                relaySendSecret = json.optString("s").takeIf { it.isNotBlank() }
                    ?.let { MailboxToken.pairSecretFromHex(it) },
                // A hint naming one of our own addresses is worse than none.
                directAddress = json.optString("a").takeIf { hint ->
                    hint.isNotBlank() && LanAddress.parse(hint, LOCAL_RELAY_PORT)
                        ?.let { (host, p) -> p != boundPort || !LanAddress.isOwnAddress(host) } == true
                }
            ),
            allowKeyChange = allowKeyChange
        )
        if (result == DesktopStore.ContactPairResult.KEY_CHANGED) {
            throw KeyChangedException(payload, theirId)
        }
        relay?.refreshSubscriptions()
        onStateChanged()
        theirId
    }

    /** Why a message to this contact will or won't get through — same three states the phone reports. */
    fun describeRoute(contactId: String): String {
        val contact = store.contact(contactId) ?: return "جهة اتصال غير معروفة"
        val bucket = currentBucket()
        val discovered = (-1L..1L).any { discoveredByToken.containsKey(shortToken(contact.publicKey, bucket + it)) }
        return when {
            peerSockets.containsKey(contactId) -> "متصل مباشرة"
            discovered -> "ظاهر على الشبكة المحلية"
            contact.directAddress != null -> "سيُجرّب العنوان المحفوظ (${contact.directAddress})"
            contact.relaySendSecret != null -> "عبر الوسيط الأعمى (قد يتأخر)"
            else -> "لا يوجد مسار — أعيدا الاقتران أو أدخل عنواناً مباشراً"
        }
    }

    fun setDirectAddress(contactId: String, hostPort: String?) {
        store.setDirectAddress(contactId, hostPort)
        peerSockets.remove(contactId)?.let { runCatching { it.close(1000, "address changed") } }
    }

    /** True while a typing pulse from this contact is still within its display window. */
    fun isTyping(contactId: String): Boolean =
        (System.currentTimeMillis() - (lastTypingAt[contactId] ?: 0L)) < TYPING_TIMEOUT_MS

    /**
     * Tell a contact we're actively typing to them right now. Direct-only,
     * like the phone: a pulse that lands after the relay's poll interval is
     * worthless — the conversation has moved on by then — while still
     * costing a real, observable mailbox deposit for nothing.
     */
    suspend fun sendTypingSignal(contactId: String): Boolean {
        val contact = store.contact(contactId) ?: return false
        return try {
            val inner = JSONObject().put("senderId", store.userId)
            sendDirect(contactId, Envelopes.seal(Envelopes.TYPE_TYPING, inner, contact.publicKey).toString())
        } catch (e: Exception) {
            false
        }
    }

    /** Tell a sender we've read the messages they sent us. */
    suspend fun sendReadReceipt(contactId: String, clientMessageIds: List<String>): Boolean {
        if (clientMessageIds.isEmpty()) return true
        val contact = store.contact(contactId) ?: return false
        return try {
            val envelopeId = java.util.UUID.randomUUID().toString()
            val inner = JSONObject().apply {
                put("senderId", store.userId)
                put("messageIds", JSONArray(clientMessageIds))
                put("ackToken", AckToken.compute(store.identity.secretKey, envelopeId))
            }
            val envelope = Envelopes.seal(Envelopes.TYPE_RECEIPT, inner, contact.publicKey, envelopeId).toString()
            store.addOutbox(DesktopStore.StoredOutbox(envelopeId, contactId, envelope, null))
            onStateChanged()
            sendToContact(contactId, envelope)
        } catch (e: Exception) {
            false
        }
    }
}
