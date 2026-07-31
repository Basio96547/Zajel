package com.securemessenger.desktop

import com.securemessenger.core.B64
import com.securemessenger.core.Platform
import com.securemessenger.core.crypto.AckToken
import com.securemessenger.core.crypto.LibsodiumWrapper
import com.securemessenger.core.crypto.MailboxToken
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    private val sessions = ConcurrentHashMap<String, SignalProtocol>()
    private val responderEphemerals = ConcurrentHashMap<String, String>()
    private val initiatorOtkIds = ConcurrentHashMap<String, Int>()
    private val initiatorPqCiphertexts = ConcurrentHashMap<String, ByteArray>()

    private val contactLocks = mutableMapOf<String, Mutex>()
    private fun lockFor(contactId: String): Mutex =
        synchronized(contactLocks) { contactLocks.getOrPut(contactId) { Mutex() } }

    /** Processed envelope ids -> the ack token to re-send, so a duplicate never re-advances the ratchet. */
    private val seenEnvelopes = object : LinkedHashMap<String, String?>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>?): Boolean = size > 512
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
                delay(OUTBOX_RETRY_MS)
                runCatching { retryOutbox() }
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
    }

    private suspend fun retryOutbox() {
        for (entry in store.allOutbox()) {
            if (sendDirect(entry.recipientId, entry.envelope)) continue
            relay?.enqueue(entry.recipientId, entry.envelope)
        }
    }

    // ================= sending =================

    suspend fun sendMessage(contactId: String, text: String): Boolean = try {
        lockFor(contactId).withLock { sendLocked(contactId, text) }
        true
    } catch (e: Exception) {
        Platform.log.error(TAG, "sendMessage failed: ${e.message}", e)
        false
    }

    private suspend fun sendLocked(contactId: String, text: String) {
        val contact = store.contact(contactId) ?: throw IllegalStateException("جهة اتصال غير معروفة")
        val protocol = initiatorSession(contactId)
        val encrypted = protocol.encryptMessage(MessagePadding.pad(text.toByteArray(Charsets.UTF_8)))
        val weAreInitiator = !responderEphemerals.containsKey(contactId)

        val envelopeId = java.util.UUID.randomUUID().toString()
        val clientMessageId = java.util.UUID.randomUUID().toString()
        val ackToken = AckToken.compute(store.identity.secretKey, envelopeId)

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

        val envelope = Envelopes.seal(
            Envelopes.TYPE_MESSAGE, inner, contact.publicKey, envelopeId, contactId
        ).toString()

        store.addMessage(
            DesktopStore.StoredMessage(contactId, true, text, System.currentTimeMillis(), clientMessageId)
        )
        store.addOutbox(DesktopStore.StoredOutbox(envelopeId, contactId, envelope, clientMessageId))
        onStateChanged()
        sendToContact(contactId, envelope)
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
                // Receipts and typing indicators are accepted and acknowledged
                // so the phone stops retrying them, but this client has no UI
                // for either yet — dropping them silently is correct, ignoring
                // them without an ack would leave them in the phone's outbox.
                Envelopes.TYPE_RECEIPT -> {
                    json.optString("id").takeIf { it.isNotBlank() }?.let { id ->
                        val inner = runCatching { unseal(json) }.getOrNull()
                        reply(Envelopes.ack(id, inner?.optString("ackToken")?.takeIf { it.isNotBlank() }))
                    }
                }
                Envelopes.TYPE_TYPING, Envelopes.TYPE_NOISE -> {}
            }
        } catch (e: Exception) {
            Platform.log.error(TAG, "handleIncoming failed: ${e.message}", e)
        }
    }

    /**
     * Run [handler] exactly once per envelope id, however many times it arrives.
     *
     * A resend whose ack was lost, and the same envelope racing in over both the
     * socket and the relay, are both normal. The handler advances the ratchet,
     * so running it twice would fail to decrypt and could surface a duplicate;
     * a repeat therefore reuses the remembered ack token instead.
     */
    private suspend fun onceOnly(type: String, json: JSONObject, handler: suspend () -> String?): String? {
        val id = json.optString("id").takeIf { it.isNotBlank() } ?: return handler()
        val key = "$type:$id"
        synchronized(seenEnvelopes) { if (seenEnvelopes.containsKey(key)) return seenEnvelopes[key] }
        val token = handler()
        synchronized(seenEnvelopes) { seenEnvelopes[key] = token }
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

        val text = String(plaintext, Charsets.UTF_8)
        val messageId = json.optString("messageId").takeIf { it.isNotBlank() }
        // A reply arrives as a structured payload rather than raw text; read the
        // quoted form when present so it doesn't render as a blob of JSON.
        val body = com.securemessenger.core.crypto.ChatPayloads.tryParseText(plaintext)?.text ?: text

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

    private suspend fun initiatorSession(contactId: String): SignalProtocol = cached(contactId) ?: run {
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
    fun pairFromPayload(payload: String): Result<String> = runCatching {
        val json = JSONObject(payload)
        val theirId = json.getString("u")
        if (theirId == store.userId) throw IllegalArgumentException("هذا رمزك أنت")
        val publicKey = B64.fromHex(json.getString("k")) ?: throw IllegalArgumentException("مفتاح غير صالح")
        store.upsertContact(
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
            )
        )
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
}
