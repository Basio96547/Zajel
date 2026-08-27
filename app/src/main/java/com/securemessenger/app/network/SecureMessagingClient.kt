package com.securemessenger.app.network

import android.content.Context
import android.util.Log
import com.securemessenger.core.B64
import com.securemessenger.core.crypto.LibsodiumWrapper
import com.securemessenger.core.crypto.MailboxToken
import com.securemessenger.core.crypto.MessagePadding
import com.securemessenger.core.crypto.PqKem
import com.securemessenger.core.crypto.SignalProtocol
import com.securemessenger.app.data.model.OutgoingConnectionRequest
import com.securemessenger.app.data.repository.SecureRepository
import com.securemessenger.app.security.AppSettings
import com.securemessenger.app.network.local.DiscoveredPeer
import com.securemessenger.app.network.local.LanNetworkBinder
import com.securemessenger.app.network.local.LocalDiscovery
import com.securemessenger.core.net.LocalRelayServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "SecureMessagingClient"

/** Fixed local port every instance of the app listens on — only ever reachable on the same local network. */
const val LOCAL_RELAY_PORT = 47601

/** How often the NSD discovery token changes — a passive observer on the LAN sees a new, unlinkable token every window instead of the same one forever. */
private const val TOKEN_ROTATION_MS = 60 * 60 * 1000L
/** How often we check whether the window rolled over — well inside TOKEN_ROTATION_MS so a rotation is never missed for long. */
private const val TOKEN_ROTATION_CHECK_MS = 5 * 60 * 1000L
/** Idle-socket read timeout on the server side — long enough that OkHttp's 30s ping keeps any real connection alive with margin, short enough that a connection nobody is pinging (idle-open by a misbehaving/malicious peer) is eventually reclaimed instead of squatting on a MAX_CONCURRENT_CONNECTIONS slot forever. */
private const val SERVER_IDLE_TIMEOUT_MS = 90 * 1000

/** How often everything still unacknowledged is retried. */
private const val OUTBOX_RETRY_MS = 60 * 1000L
/** Shortest gap between two relay deposits of the *same* envelope, for one freshly stuck — widens the longer it's been undelivered; see [SecureMessagingClient.relayRetryFloorFor]. */
private const val RELAY_RETRY_FLOOR_MS = 15 * 60 * 1000L
/** Once an outbox entry has been undelivered for over an hour — see [SecureMessagingClient.relayRetryFloorFor]. */
private const val RELAY_RETRY_FLOOR_STALE_MS = 60 * 60 * 1000L
/** Once an outbox entry has been undelivered for over a day — see [SecureMessagingClient.relayRetryFloorFor]. */
private const val RELAY_RETRY_FLOOR_ABANDONED_MS = 6 * 60 * 60 * 1000L

/** How long to wait for a prekey bundle over a direct local socket. */
private const val LOCAL_BUNDLE_TIMEOUT_MS = 8_000L
/**
 * How long to wait for one over the relay. Deliberately generous: the reply has
 * to survive our send jitter, the peer's poll interval, their send jitter and
 * our own poll interval — a worst case around 45 seconds before anything has
 * even gone wrong.
 */
private const val RELAY_BUNDLE_TIMEOUT_MS = 75_000L

/** Minimum time between handing our bundle (which burns a one-time prekey) to the same claimed userId — bounds how fast a peer that learned a valid userId can drain the OTK pool by spamming challenges. */
private const val BUNDLE_HANDOUT_MIN_INTERVAL_MS = 10_000L

/** How long a persisted "already handled this envelope" record is kept — see [SecureMessagingClient.onceOnly]. Deliberately generous: it only needs to outlast the longest a legitimate resend could plausibly still be arriving, and a stale row costs nothing but a few bytes. */
private const val SEEN_ENVELOPE_TTL_MS = 30L * 24 * 60 * 60 * 1000L

/** How often we poll the directory for introductions waiting for us — a far rarer event than a chat message, so a slower cadence than [RelayClient]'s own poll is plenty. */
private const val INTRO_POLL_MIN_MS = 20_000L
private const val INTRO_POLL_MAX_MS = 40_000L

/**
 * SecureMessagingClient — fully peer-to-peer. There is no external server of
 * any kind: each device runs its own [LocalRelayServer] (started only while
 * the messenger is revealed) and finds other instances on the same network
 * via [LocalDiscovery] (mDNS/NSD). A contact is reachable only while both
 * devices share a network and both have the messenger open — the durable
 * outbox (unchanged) retries the instant a known contact is (re)discovered.
 *
 * All the Double-Ratchet/X3DH/sealed-sender crypto below is unchanged from
 * the relay-based version — only how bytes travel between two devices
 * changed (direct local socket instead of routing through Cloudflare).
 */
class SecureMessagingClient(
    context: Context,
    private val repository: SecureRepository,
    private val userId: String
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Keeps peer sockets on Wi-Fi/Ethernet even while a VPN holds the default route. */
    private val lanBinder = LanNetworkBinder(appContext)

    // Sockets to peers on this LAN. Pinned to the Wi-Fi network so an active
    // VPN — which normally captures 0.0.0.0/0 — can't swallow a connection to a
    // device one hop away. A ping every 30s keeps a genuinely-alive connection
    // from ever hitting the server's own idle timeout below, while still
    // letting OkHttp notice a truly dead peer quickly (no pong ⇒ onFailure).
    private val localHttp = OkHttpClient.Builder()
        .pingInterval(30, java.util.concurrent.TimeUnit.SECONDS)
        .socketFactory(lanBinder.socketFactory)
        .build()

    // Traffic to the blind relay, deliberately left on the DEFAULT network so it
    // keeps going through the VPN when one is up. The relay is the single party
    // that unavoidably observes an IP address, so this is exactly where a VPN
    // earns its keep — the opposite of the local sockets above, which never
    // leave the LAN and only break when tunnelled.
    private val relayHttp = OkHttpClient.Builder()
        .pingInterval(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // Not `val` — if the fixed port is already taken (e.g. a second, cloned/
    // "dual app" copy of this same app running on this same physical device),
    // connect() replaces this with a fresh instance bound to an OS-assigned
    // port instead.
    private var localServer = LocalRelayServer(LOCAL_RELAY_PORT)
    // Whatever port localServer actually ended up bound to — NOT necessarily
    // LOCAL_RELAY_PORT once the fallback above kicks in. This is the port we
    // advertise via NSD, so peers always connect to where we're really
    // listening.
    private var boundPort = LOCAL_RELAY_PORT
    private val discovery = LocalDiscovery(appContext)
    private var discoveryJob: Job? = null
    private var rotationJob: Job? = null
    private var outboxJob: Job? = null

    /** Serializes discovery restarts — NsdManager does not tolerate overlapping start/stop of the same listener. */
    private val discoveryRestartLock = Mutex()

    /** Envelope id -> when we last deposited it on the relay, for the retry floor in [retryOutbox]. */
    private val relayRetryAt = ConcurrentHashMap<String, Long>()

    // The fallback path for contacts who aren't on this network. Null when the
    // build carries no relay URL at all, which is the strictest configuration:
    // local network only, nothing ever leaves the LAN.
    private val relay: RelayClient? =
        com.securemessenger.app.BuildConfig.RELAY_URL.takeIf { it.isNotBlank() }?.let { url ->
            RelayClient(
                context = appContext,
                repository = repository,
                baseUrl = url.trimEnd('/'),
                http = relayHttp
            ) { envelopeJson, pendingSecretHex -> handleRelayEnvelope(envelopeJson, pendingSecretHex) }
        }

    // The optional username-directory path — wholly separate from [relay]: a
    // build can carry one, both, or neither. Null when no directoryUrl was
    // compiled in, which fully removes username search/claim from the app,
    // same escape hatch as relay's own RELAY_URL check.
    private val directory: DirectoryClient? =
        com.securemessenger.app.BuildConfig.DIRECTORY_URL.takeIf { it.isNotBlank() }?.let { url ->
            DirectoryClient(baseUrl = url.trimEnd('/'), http = relayHttp)
        }

    private var introductionPollJob: Job? = null

    // Envelopes already SUCCESSFULLY processed, keyed by "type:id". The outbox
    // legitimately resends an envelope whose ack was lost, and with two
    // transports the same envelope can also arrive twice at once — both must
    // be acked again but neither may be decrypted again (the ratchet consumed
    // that message key, so a second decrypt would fail and, worse, a
    // duplicate would surface in the chat). The remembered value is the ack
    // token, so a repeat can be acked from cache without touching the ratchet.
    //
    // Only ever holds envelopes the handler actually finished (see onceOnly)
    // — never one it dropped (no session yet, lost a simultaneous-initiation
    // race, unrecognized sender). Caching a drop would make it permanent: the
    // sender's outbox keeps resending the identical envelope, and every one
    // of those resends would be discarded from this cache before the handler
    // — by now possibly able to succeed — ever ran again.
    //
    // In-memory only, so it's a fast path, not the source of truth: it is
    // wiped every time the calculator disguise hides and reveals (a fresh
    // SecureMessagingClient is created each time — see
    // SecureMessengerApp.initializeMessagingClient), which happens far more
    // often than a real app restart. [SecureRepository.getSeenEnvelopeAckToken]
    // is the persisted twin that survives that; see onceOnly.
    private val seenEnvelopes = object : LinkedHashMap<String, String>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 512
    }

    // Memoizes unsealEnvelope, keyed by the sealed ciphertext itself — see
    // unsealEnvelope. Small and short-lived on purpose: its only job is
    // covering the SAME envelope being unsealed twice within one delivery
    // (handleRelayEnvelope peeks at the sender up front, then the normal
    // per-type handler unseals the identical envelope again), not acting as a
    // second dedup layer — that's what seenEnvelopes/onceOnly already do, at
    // the message level rather than the crypto-op level.
    private val unsealCache = object : LinkedHashMap<String, JSONObject>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JSONObject>?): Boolean = size > 32
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // contactId -> currently-open direct socket to that contact's local server.
    private val peerSockets = ConcurrentHashMap<String, WebSocket>()
    // Identity-hash token -> last-resolved network address (refreshed continuously while revealed).
    private val discoveredByToken = ConcurrentHashMap<String, DiscoveredPeer>()
    // Every known contact's token -> contactId, recomputed whenever the contact list changes.
    private val tokenToContactId = ConcurrentHashMap<String, String>()
    // Resolved the moment a contact's bundle_announce arrives on an outbound socket we opened for them.
    private val pendingBundleRequests = ConcurrentHashMap<String, CompletableDeferred<PrekeyBundle>>()
    // The nonce we challenged a contact with while awaiting their bundle — the
    // signed reply must echo this exact value, or it's rejected as a possible
    // replay of an earlier (captured) bundle_announce.
    private val pendingChallengeNonces = ConcurrentHashMap<String, ByteArray>()
    // claimed userId -> last time we handed them our bundle. Used to rate-limit
    // one-time-prekey handouts (see BUNDLE_HANDOUT_MIN_INTERVAL_MS).
    private val lastBundleHandout = ConcurrentHashMap<String, Long>()

    // One bidirectional Double-Ratchet session per contact — used for both
    // sending and receiving so the DH ratchet advances as the conversation goes
    // back and forth (Post-Compromise Security).
    private val sessions = ConcurrentHashMap<String, SignalProtocol>()
    // If we established a session as the *responder*, the initiator ephemeral
    // (hex) that established it. Absent ⇒ we are the initiator for that contact.
    private val responderEphemerals = ConcurrentHashMap<String, String>()
    // Which one-time prekey id we consumed for each recipient's initial X3DH.
    private val initiatorOtkIds = ConcurrentHashMap<String, Int>()
    // The ML-KEM ciphertext we produced for each recipient.
    private val initiatorPqCiphertexts = ConcurrentHashMap<String, ByteArray>()

    private val contactLocks = mutableMapOf<String, Mutex>()
    private fun lockFor(contactId: String): Mutex =
        synchronized(contactLocks) { contactLocks.getOrPut(contactId) { Mutex() } }

    // Guards "establish an initiator session for this contact if none exists"
    // (X3DH: a live prekey-bundle fetch that can take up to
    // RELAY_BUNDLE_TIMEOUT_MS). Deliberately separate from [lockFor]'s ratchet
    // lock: that one is also needed to decrypt an incoming message from this
    // same contact, and must never sit blocked behind a slow handshake to them
    // — see getOrCreateInitiatorSession and sendMessageInternal. This lock only
    // prevents two concurrent sends to a brand-new contact from independently
    // racing X3DH (which would clobber each other's pendingBundleRequests entry
    // and leave one of them waiting the full timeout for a reply that already
    // arrived).
    private val handshakeLocks = mutableMapOf<String, Mutex>()
    private fun handshakeLockFor(contactId: String): Mutex =
        synchronized(handshakeLocks) { handshakeLocks.getOrPut(contactId) { Mutex() } }

    private val _incomingMessages = Channel<MessageReceived>(Channel.UNLIMITED)
    val incomingMessages: ReceiveChannel<MessageReceived> get() = _incomingMessages

    private val _typingSignals = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val typingSignals: SharedFlow<String> = _typingSignals.asSharedFlow()

    private fun wireServer(server: LocalRelayServer) {
        // Deliberately nothing runs on bare onOpen anymore — our bundle is
        // only ever handed out in direct response to a signed "challenge",
        // never pushed proactively to whoever happens to connect.
        server.onEnvelope = { text, reply ->
            scope.launch { handleIncomingMessage(text, reply) }
        }
    }

    /** Starts this device's own local relay, advertises it, and starts watching for known contacts. */
    fun connect() {
        if (_connectionState.value is ConnectionState.Connected) return
        _connectionState.value = ConnectionState.Connecting
        try {
            // Start watching for the Wi-Fi network before any socket is opened,
            // so the very first peer connection is already VPN-exempt.
            lanBinder.start()
            wireServer(localServer)
            try {
                // NanoHTTPD.SOCKET_READ_TIMEOUT (5s) is meant for short-lived
                // plain HTTP requests, not a WebSocket meant to stay open for
                // an entire chat session — using it here forcibly killed
                // every accepted connection the instant 5 seconds passed with
                // no new frame, silently dropping any message sent after the
                // first one on an idle connection. SERVER_IDLE_TIMEOUT_MS
                // (90s) instead, paired with the OkHttp client's 30s ping,
                // gives a real conversation plenty of margin while still
                // eventually reclaiming a connection nobody is pinging —
                // otherwise, combined with MAX_CONCURRENT_CONNECTIONS, a
                // misbehaving/malicious peer could open connections and hold
                // them idle-open forever, permanently occupying every slot.
                localServer.start(SERVER_IDLE_TIMEOUT_MS, false)
                boundPort = LOCAL_RELAY_PORT
            } catch (e: java.io.IOException) {
                // The fixed port is already taken on this device — most
                // likely a second, cloned/"dual app" copy of this same app is
                // also running here (each device is only meant to run one
                // instance; two on the same physical device compete for the
                // same port). Fall back to whatever free port the OS hands
                // us; peers still find us correctly since NSD always
                // advertises the port we actually bound, not the constant.
                Log.w(TAG, "port $LOCAL_RELAY_PORT unavailable, falling back to an OS-assigned port", e)
                localServer = LocalRelayServer(0)
                wireServer(localServer)
                localServer.start(SERVER_IDLE_TIMEOUT_MS, false)
                boundPort = localServer.listeningPort
            }
            _connectionState.value = ConnectionState.Connected
            scope.launch { registerAndDiscover() }
            // Starts its own poll and cover-traffic loops. Bound to the same
            // reveal/hide lifecycle as everything else here — nothing talks to
            // the relay while the calculator disguise is up.
            relay?.start()
            scope.launch { refreshRelaySubscriptions() }
            // Housekeeping for the persisted de-dup table onceOnly() falls
            // back to — see SEEN_ENVELOPE_TTL_MS. Once per reveal is plenty;
            // nothing here is time-critical.
            scope.launch {
                try {
                    repository.pruneSeenEnvelopesOlderThan(SEEN_ENVELOPE_TTL_MS)
                } catch (e: Exception) {
                    Log.w(TAG, "pruneSeenEnvelopesOlderThan failed", e)
                }
            }
            // Retry a username claim that couldn't reach the directory at
            // setup time — see AppSettings.isUsernameClaimedRemotely's doc.
            // A no-op once already claimed, and silently skipped entirely
            // when the directory feature isn't configured or no local
            // username was ever set.
            scope.launch {
                try {
                    ensureUsernameClaimed()
                } catch (e: Exception) {
                    Log.w(TAG, "ensureUsernameClaimed failed", e)
                }
            }
            outboxJob?.cancel()
            outboxJob = scope.launch {
                while (isActive) {
                    // Runs immediately on every connect (reveal), not just
                    // every OUTBOX_RETRY_MS after — otherwise a message stuck
                    // since before the calculator disguise was last hidden
                    // would sit untouched for up to a minute after reopening
                    // it, on top of whatever it had already waited.
                    try {
                        retryOutbox()
                        retryPendingSends()
                    } catch (e: Exception) {
                        Log.w(TAG, "outbox retry failed", e)
                    }
                    delay(OUTBOX_RETRY_MS)
                }
            }
            introductionPollJob?.cancel()
            if (directory != null) {
                introductionPollJob = scope.launch {
                    while (isActive) {
                        delay(
                            INTRO_POLL_MIN_MS +
                                (java.security.SecureRandom().nextDouble() * (INTRO_POLL_MAX_MS - INTRO_POLL_MIN_MS)).toLong()
                        )
                        try {
                            pollIntroductions()
                        } catch (e: Exception) {
                            Log.w(TAG, "introduction poll failed", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "failed to start local relay server", e)
            _connectionState.value = ConnectionState.Error(e.message ?: "تعذّر تشغيل الاستقبال المحلي")
        }
    }

    private suspend fun registerAndDiscover() = discoveryRestartLock.withLock {
        refreshTokenMap()
        try {
            discovery.register(boundPort, myIdentityToken())
        } catch (e: Exception) {
            Log.e(TAG, "register failed", e)
        }
        // cancelAndJoin, not cancel. `cancel()` only *requests* cancellation and
        // returns immediately, so the old flow's `awaitClose` — which calls
        // NsdManager.stopServiceDiscovery — would run at some arbitrary later
        // point, quite possibly AFTER the replacement discovery below had
        // already started. Device logs showed exactly that: register, register,
        // unregister, and discovery left switched off, with the phone then
        // unable to see any peer at all. Joining first makes the teardown
        // strictly precede the restart.
        //
        // The surrounding mutex covers the other half of the same problem: two
        // concurrent callers (connect() and retryNow(), say) interleaving their
        // register/stop calls.
        discoveryJob?.cancelAndJoin()
        discoveryJob = scope.launch {
            discovery.discoverPeers().collect { peers ->
                // REPLACE, not merge. The flow emits the complete current peer
                // set on every change — onServiceLost drops the peer and
                // re-sends the whole list — so anything absent here is gone.
                // Merging instead left a departed device marked "discovered"
                // forever: the contact card went on claiming "ظاهر على الشبكة
                // المحلية" for a peer that had left, and every send burned the
                // full connect timeout dialling an address nobody was listening
                // on before falling back to the relay.
                discoveredByToken.keys.retainAll(peers.mapTo(HashSet()) { it.token })
                for (peer in peers) {
                    discoveredByToken[peer.token] = peer
                    val contactId = tokenToContactId[peer.token] ?: continue
                    scope.launch { flushOutboxTo(contactId) }
                }
            }
        }
        // Re-advertise under a fresh token as each rotation window rolls over
        // — a session left open for hours never broadcasts the one token a
        // passive LAN observer could otherwise correlate with "this device"
        // across the whole day.
        rotationJob?.cancel()
        rotationJob = scope.launch {
            var lastToken = myIdentityToken()
            while (true) {
                delay(TOKEN_ROTATION_CHECK_MS)
                refreshTokenMap()
                val current = myIdentityToken()
                if (current != lastToken) {
                    lastToken = current
                    try {
                        discovery.register(boundPort, current)
                    } catch (e: Exception) {
                        Log.e(TAG, "token rotation re-register failed", e)
                    }
                }
            }
        }
    }

    private fun currentTimeBucket(): Long = System.currentTimeMillis() / TOKEN_ROTATION_MS

    private suspend fun myIdentityToken(): String {
        val identity = getIdentityKeyPair() ?: return "unknown"
        return shortToken(identity.publicKey, currentTimeBucket())
    }

    /**
     * A short, non-reversible, *time-boxed* hash of a public key — changes
     * every [TOKEN_ROTATION_MS] window so it can't be used to track "the same
     * device" across sessions by whoever is just passively watching mDNS
     * traffic on the LAN, while still being independently computable by
     * anyone who already holds that same public key (no live exchange needed
     * to recognize a contact's rotated token).
     */
    private fun shortToken(publicKey: ByteArray, bucket: Long): String =
        LibsodiumWrapper.blake2b(publicKey + bucket.toString().toByteArray(Charsets.UTF_8), length = 4)
            .joinToString("") { "%02x".format(it) }

    /** Recomputes the token→contactId map — call again after adding/pairing a new contact, and periodically as the rotation window moves. */
    suspend fun refreshTokenMap() {
        val contacts = repository.getAllContactsOnce()
        val bucket = currentTimeBucket()
        tokenToContactId.clear()
        contacts.forEach { contact ->
            // Tolerate up to one window of clock skew between the two devices
            // by also accepting the neighboring windows' tokens.
            for (offset in -1L..1L) {
                tokenToContactId[shortToken(contact.publicKey, bucket + offset)] = contact.id
            }
        }
    }

    /** User-triggered "retry now" — re-registers and re-scans instead of waiting for the next discovery tick. */
    fun retryNow() {
        scope.launch {
            registerAndDiscover()
            // The Snackbar this drives is shown for exactly the failures these
            // two cover — a transport-level retry and a session-establishment
            // retry — so a manual "retry" should attempt both immediately
            // rather than leaving the user to wait for the next periodic tick.
            try { retryOutbox() } catch (e: Exception) { Log.w(TAG, "manual outbox retry failed", e) }
            try { retryPendingSends() } catch (e: Exception) { Log.w(TAG, "manual pending-send retry failed", e) }
        }
    }

    /**
     * What this device is listening on, for the user to read out to whoever
     * wants to reach it directly. Null when we aren't on a network at all.
     */
    fun myDirectAddress(): String? =
        com.securemessenger.core.net.LanAddress.best()?.let { "$it:$boundPort" }

    /** Manually pin (or clear) where a contact can be dialled. See [connectionTo]. */
    fun setDirectAddress(contactId: String, hostPort: String?) {
        AppSettings.setDirectAddress(appContext, contactId, hostPort)
        // Drop any dead socket so the next send re-dials using the new address
        // instead of sitting on a connection to the old one.
        peerSockets.remove(contactId)?.let { try { it.close(1000, "address changed") } catch (_: Exception) {} }
    }

    /**
     * Every route we currently have to [contactId], and whether each one is
     * actually usable right now.
     *
     * This exists because the failure this app is most likely to hand a user is
     * also its most silent one: the message is durably queued, the UI shows it
     * pending, and nothing anywhere says whether the contact is undiscoverable,
     * unreachable, or simply has no relay path at all. Those need very
     * different fixes — reconnect the Wi-Fi, type in an address, or re-pair —
     * and the app has to be able to tell them apart out loud.
     */
    suspend fun transportStatus(contactId: String): TransportStatus {
        val contact = repository.getContact(contactId)
            ?: return TransportStatus(false, false, null, false)
        val bucket = currentTimeBucket()
        val discovered = (-1L..1L).any { offset ->
            discoveredByToken.containsKey(shortToken(contact.publicKey, bucket + offset))
        }
        return TransportStatus(
            isConnected = peerSockets.containsKey(contactId),
            isDiscovered = discovered,
            rememberedAddress = AppSettings.directAddress(appContext, contactId),
            hasRelayPath = relay?.isEnabled == true && repository.getRelaySendSecret(contactId) != null
        )
    }

    fun disconnect() {
        relay?.shutdown()
        lanBinder.stop()
        discoveryJob?.cancel()
        rotationJob?.cancel()
        outboxJob?.cancel()
        introductionPollJob?.cancel()
        try { discovery.unregister() } catch (_: Exception) {
        }
        try { localServer.stop() } catch (_: Exception) {
        }
        peerSockets.values.forEach { try { it.close(1000, "bye") } catch (_: Exception) {
        } }
        peerSockets.clear()
        _connectionState.value = ConnectionState.Disconnected
        scope.cancel()
    }

    /**
     * An open (or freshly opened) direct connection to [contactId] — null if
     * not reachable right now.
     *
     * Two ways to find them, tried in order:
     *
     *  1. **mDNS**, when their rotating discovery token is currently visible on
     *     this network. Preferred: it needs no configuration and survives the
     *     peer's address changing.
     *  2. **A remembered address**, from a previous successful connection, the
     *     QR code we paired with, or one the user typed in. This exists because
     *     mDNS is the most failure-prone part of the whole transport — plenty of
     *     routers simply do not pass multicast between clients, and when that
     *     happens two devices on the same Wi-Fi, with a working route between
     *     them, would otherwise never find each other.
     *
     * The fallback grants no trust of its own: whoever answers still has to pass
     * the pinned-identity challenge in [fetchPrekeyBundle] and still can't
     * produce a frame that decrypts under the contact's ratchet. A stale or
     * wrong address costs a failed connection, never a mis-delivery.
     */
    private suspend fun connectionTo(contactId: String): WebSocket? {
        peerSockets[contactId]?.let { return it }
        val contact = repository.getContact(contactId) ?: return null
        val bucket = currentTimeBucket()
        val discovered = (-1L..1L).firstNotNullOfOrNull { offset ->
            discoveredByToken[shortToken(contact.publicKey, bucket + offset)]
        }
        if (discovered != null) {
            openConnection(contactId, discovered.host, discovered.port)?.let { return it }
        }
        // Either they were never discovered, or the discovered address is stale
        // (they moved networks and mDNS hasn't caught up). Fall back to whatever
        // address we have on record for them.
        val remembered = AppSettings.directAddress(appContext, contactId) ?: return null
        val (host, port) = com.securemessenger.core.net.LanAddress
            .parse(remembered, LOCAL_RELAY_PORT) ?: return null
        // An address that is OUR OWN can only ever connect us to ourselves.
        // It happens for real: two devices on the same VPN are often handed the
        // same tunnel address, and if one advertises it the other stores it and
        // then talks to its own socket — receiving its own sealed challenge,
        // failing to open it, and timing out every send, with nothing in the
        // logs pointing at the address. Drop it so the contact falls through to
        // the relay instead of dialling a mirror forever.
        // Host AND port: a second clone of this app on this same device is
        // reached through this device's address too, but on a different port.
        if (port == boundPort && com.securemessenger.core.net.LanAddress.isOwnAddress(host)) {
            Log.w(TAG, "stored address $host:$port for $contactId is our own socket — discarding it")
            AppSettings.setDirectAddress(appContext, contactId, null)
            return null
        }
        // Don't re-dial an address mDNS just handed us and that just failed.
        if (discovered != null && discovered.host == host && discovered.port == port) return null
        Log.d(TAG, "mDNS gave nothing usable for $contactId — trying remembered $host:$port")
        return openConnection(contactId, host, port)
    }

    /**
     * Dial a peer, preferring the Wi-Fi-pinned client so a VPN can't swallow the
     * connection — but never letting that pinning be the reason a reachable peer
     * looks unreachable.
     *
     * Pinning is a bet that the peer is on the same interface we pinned to. It
     * usually is, and when it is it's the difference between working and not.
     * When it isn't — a peer on a second interface, a clone of this app on this
     * same device answering over loopback — the bound socket simply cannot
     * connect, so the default network gets one honest retry before we report
     * failure.
     */
    private suspend fun openConnection(contactId: String, host: String, port: Int): WebSocket? =
        attemptConnection(contactId, host, port, localHttp)
            ?: attemptConnection(contactId, host, port, relayHttp)

    private suspend fun attemptConnection(
        contactId: String,
        host: String,
        port: Int,
        client: OkHttpClient
    ): WebSocket? {
        val opened = CompletableDeferred<WebSocket?>()
        val request = Request.Builder().url("ws://$host:$port/ws").build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                peerSockets[contactId] = webSocket
                // Remember where this contact actually answered. Next time mDNS
                // fails — a different router, a guest network, a resolve that
                // silently errored — this is what keeps them reachable instead
                // of the conversation just going quiet.
                AppSettings.setDirectAddress(appContext, contactId, "$host:$port")
                if (!opened.isCompleted) opened.complete(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { handleIncomingMessage(text) { reply -> webSocket.send(reply) } }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                peerSockets.remove(contactId)
                if (!opened.isCompleted) opened.complete(null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                peerSockets.remove(contactId)
            }
        })
        return withTimeoutOrNull(5000) { opened.await() } ?: run {
            try { socket.cancel() } catch (_: Exception) {
            }
            null
        }
    }

    /** Direct local-network delivery only. False means "not reachable on this LAN right now." */
    private suspend fun sendDirect(contactId: String, envelopeJson: String): Boolean {
        val socket = connectionTo(contactId) ?: return false
        return try {
            socket.send(envelopeJson)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Best-effort send — false just means "couldn't get it out right now,"
     * never a hard error; the outbox already has it.
     *
     * The local network is always tried first and, when it works, the message
     * never touches the relay at all: no third party learns that anything
     * happened, and delivery is immediate. The relay is strictly a fallback for
     * a contact who isn't reachable on this LAN.
     */
    private suspend fun sendToContact(contactId: String, envelopeJson: String): Boolean {
        if (sendDirect(contactId, envelopeJson)) return true
        return relay?.enqueue(contactId, envelopeJson) ?: false
    }

    /**
     * How long an entry must wait before another relay deposit is attempted,
     * given how long it's been sitting undelivered — see [retryOutbox].
     *
     * A flat floor forever means a contact who's genuinely gone (blocked,
     * uninstalled, never coming back) still gets a fresh blob deposited to a
     * mailbox nobody is polling every [RELAY_RETRY_FLOOR_MS] indefinitely —
     * real network + battery cost on this device, and the relay keeps
     * accumulating copies of a message no one will ever collect (until its
     * own independent 48h retention expires each one). Widening the gap the
     * longer an entry has been stuck bounds that cost without ever giving up
     * on the entry — nothing here deletes it; see [retryPendingSends] et al.,
     * which never expire what they hold either. A freshly-stuck entry keeps
     * the tight floor so a genuinely brief blip still recovers quickly.
     */
    private fun relayRetryFloorFor(ageMs: Long): Long = when {
        ageMs < 60 * 60 * 1000L -> RELAY_RETRY_FLOOR_MS               // under 1h old
        ageMs < 24 * 60 * 60 * 1000L -> RELAY_RETRY_FLOOR_STALE_MS    // under 1 day old
        else -> RELAY_RETRY_FLOOR_ABANDONED_MS                       // 1 day or older
    }

    /**
     * Periodic sweep of everything still unacknowledged.
     *
     * Without this the outbox would only ever be retried when a contact turns
     * up on the local network — fine when that was the only transport, but a
     * relay deposit that failed (no connectivity, relay unreachable) would
     * otherwise sit unretried forever for a contact who is never on this LAN.
     *
     * Entries are processed concurrently: a relay deposit now genuinely waits
     * for its outcome (see [RelayClient.enqueueAwait]), and one contact stuck
     * on a slow or failing attempt must not delay everyone else's turn until
     * the next sweep a full [OUTBOX_RETRY_MS] later.
     */
    private suspend fun retryOutbox() = coroutineScope {
        val pending = repository.getAllOutboxEnvelopes()
        val now = System.currentTimeMillis()
        for (entry in pending) {
            launch {
                if (sendDirect(entry.recipientId, entry.envelope)) return@launch
                val last = relayRetryAt[entry.id] ?: 0L
                val floor = relayRetryFloorFor(now - entry.createdAt)
                if (now - last < floor) return@launch
                // Stamped only on an ACTUAL successful deposit — a failed
                // attempt (relay unreachable, transient error) never landed
                // anywhere, so it costs nothing to try again on the very next
                // sweep instead of sitting out the same floor a success would.
                if (relay?.enqueueAwait(entry.recipientId, entry.envelope) == true) {
                    relayRetryAt[entry.id] = now
                }
            }
        }
        // Drop throttle bookkeeping for envelopes that have since been acked.
        // Computed from `pending` as fetched above — nothing above removes an
        // outbox row (only a later ack, via handleAck, does that).
        val live = pending.mapTo(HashSet()) { it.id }
        relayRetryAt.keys.retainAll(live)
    }

    /**
     * Re-attempt every message that never made it as far as the outbox at all
     * — session establishment (the X3DH prekey fetch in
     * getOrCreateInitiatorSession) failed or timed out the first time. Unlike
     * [retryOutbox], each of these needs a full resend (sendMessage), not just
     * a transport retry of an already-sealed envelope — there is no envelope
     * yet. Run concurrently: one contact stuck on a ~75s relay timeout must
     * not delay retrying everyone else.
     */
    private suspend fun retryPendingSends() = coroutineScope {
        for (pending in repository.getAllPendingSends()) {
            launch { sendMessage(pending.recipientId, pending.plaintext, pending.ttlSeconds, pending.clientMessageId) }
        }
    }

    /** Same as [retryPendingSends], scoped to messages waiting on one contact — see [flushOutboxTo]. */
    private suspend fun retryPendingSendsTo(contactId: String) = coroutineScope {
        for (pending in repository.getPendingSendsForContact(contactId)) {
            launch { sendMessage(pending.recipientId, pending.plaintext, pending.ttlSeconds, pending.clientMessageId) }
        }
    }

    /**
     * Recompute which relay mailboxes we listen on. Called after anything that
     * changes the set: pairing a contact, displaying a QR code (which mints a
     * new secret), or binding one of those secrets to whoever used it.
     */
    suspend fun refreshRelaySubscriptions() {
        try {
            relay?.refreshSubscriptions()
        } catch (e: Exception) {
            Log.w(TAG, "refreshRelaySubscriptions failed", e)
        }
    }

    /**
     * An envelope that came in through the relay rather than a direct socket.
     *
     * Two things differ from the local path. The sender has to be identified
     * before dispatch so the ack can be routed back through the relay (there is
     * no open socket to reply on). And when the blob arrived in a mailbox
     * addressed by a secret we minted but hadn't yet attached to anyone, this
     * is the moment we learn who photographed that QR code — so the secret gets
     * bound to them as our permanent inbound channel.
     */
    private suspend fun handleRelayEnvelope(envelopeJson: String, pendingSecretHex: String?) {
        try {
            val outer = JSONObject(envelopeJson)
            // Cover traffic: a real, correctly addressed, fully encrypted blob
            // whose only purpose was to be indistinguishable from this one.
            if (outer.optString("type") == "noise") return

            // Who to answer. Chat frames name the peer "senderId"; the
            // handshake frames (challenge / bundle_announce) name it "userId".
            // Reading only the former left a relayed challenge with nowhere to
            // send its reply, so the handshake could never complete.
            val senderId = try {
                val inner = unsealEnvelope(outer)
                inner.optString("senderId").takeIf { it.isNotBlank() }
                    ?: inner.optString("userId").takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                null
            }

            if (pendingSecretHex != null && senderId != null && repository.getContact(senderId) != null) {
                MailboxToken.pairSecretFromHex(pendingSecretHex)?.let {
                    repository.bindRelayRecvSecret(senderId, it)
                }
                AppSettings.removePendingPairSecret(appContext, pendingSecretHex)
                refreshRelaySubscriptions()
            }

            handleIncomingMessage(envelopeJson) { ackJson ->
                // The reply hook is deliberately non-suspending (the local
                // socket path answers synchronously on the same socket), so the
                // relay's now-suspending deposit is launched rather than
                // awaited. Nothing waits on an ack anyway — the sender's outbox
                // simply stops retrying once it lands.
                senderId?.let { id -> scope.launch { relay?.enqueue(id, ackJson) } }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleRelayEnvelope failed", e)
        }
    }

    /**
     * Best-effort retry of a username claim that couldn't reach the
     * directory when [com.securemessenger.app.ui.screens.setup.SetupScreen]
     * first attempted it (offline at setup time) — see
     * [AppSettings.isUsernameClaimedRemotely]'s doc. A no-op once already
     * marked claimed, once there's no local username to claim, or when the
     * directory feature isn't configured for this build.
     */
    private suspend fun ensureUsernameClaimed() {
        val client = directory ?: return
        if (AppSettings.isUsernameClaimedRemotely(appContext)) return
        val username = AppSettings.getUsername(appContext) ?: return
        val identity = getIdentityKeyPair() ?: return
        val signingPublic = repository.getSigningPublicKey() ?: return
        val signingSecret = repository.getSigningSecretKey() ?: return

        when (client.claim(username, identity.publicKey, signingPublic) { payload ->
            LibsodiumWrapper.signDetached(payload, signingSecret)
        }) {
            DirectoryClient.ClaimResult.Success, DirectoryClient.ClaimResult.AlreadyRegistered ->
                AppSettings.setUsernameClaimedRemotely(appContext, true)
            // Taken (lost a race to someone else after setup already showed
            // it as available) or Error (still offline/unreachable) — leave
            // the flag false either way so this simply retries next reveal.
            // A user who genuinely lost a race has no in-app recovery yet
            // beyond picking a new username from Settings — same limitation
            // SetupScreen's synchronous check already has.
            DirectoryClient.ClaimResult.Taken, DirectoryClient.ClaimResult.Error -> {}
        }
    }

    /**
     * Collect and process every introduction waiting for us. Destructive on
     * the server, like the relay's own fetch (see [DirectoryClient.fetchIntroductions])
     * — a blob that fails to process here is simply lost, not retried. That's
     * acceptable: the sender's own [OutgoingConnectionRequest] is what a "still
     * pending" UI reads, not delivery of any single accept/request blob, so a
     * lost one just means the other side eventually tries again from the
     * search screen — no different in spirit from an unreachable QR pairing
     * needing a second scan.
     */
    private suspend fun pollIntroductions() {
        val client = directory ?: return
        val identity = getIdentityKeyPair() ?: return
        val signingSecret = repository.getSigningSecretKey() ?: return
        val blobs = client.fetchIntroductions(identity.publicKey) { payload ->
            LibsodiumWrapper.signDetached(payload, signingSecret)
        } ?: return
        for (blob in blobs) {
            try {
                handleIntroductionBlob(blob)
            } catch (e: Exception) {
                Log.w(TAG, "discarding unreadable introduction blob", e)
            }
        }
    }

    /**
     * Unseal one collected blob and dispatch by type. Deliberately no
     * chunking and no extra symmetric layer the way relay traffic gets (see
     * [MailboxToken.blobKey]'s doc) — introductions are single, small,
     * one-shot payloads, and crypto_box_seal already fully protects the
     * content. The directory operator sees only that *some* introduction
     * landed for this identity — inherent to a deposit API that necessarily
     * names its recipient, not something an extra layer here would hide.
     */
    private suspend fun handleIntroductionBlob(blobBase64: String) {
        val identity = getIdentityKeyPair() ?: return
        val outer = JSONObject(String(B64.decode(blobBase64), Charsets.UTF_8))
        val inner = com.securemessenger.core.net.Envelopes.open(outer, identity.publicKey, identity.secretKey)
        when (outer.optString("type")) {
            com.securemessenger.core.net.Envelopes.TYPE_INTRO_REQUEST -> handleIntroRequest(inner)
            com.securemessenger.core.net.Envelopes.TYPE_INTRO_ACCEPT -> handleIntroAccept(inner)
        }
    }

    /**
     * Someone we've never talked to found us by username and introduced
     * themselves. Verified BEFORE anything is stored:
     * [com.securemessenger.core.net.DirectoryProtocol.verifySelfIntroduction]
     * needs a signing key obtained independently of this payload, so this
     * does its own fresh directory lookup of the claimed sender username —
     * the whole anti-impersonation property depends on never trusting
     * `senderSigningPublicKey` embedded in the payload itself. Only stored
     * for the user to explicitly accept/reject afterward — nothing here ever
     * creates a Contact directly.
     */
    private suspend fun handleIntroRequest(inner: JSONObject) {
        val client = directory ?: return
        val identity = getIdentityKeyPair() ?: return
        val intro = com.securemessenger.core.net.DirectoryProtocol.parseSelfIntroduction(inner) ?: return
        if (intro.senderUserId == userId) return

        val trustedSigningKey = when (val result = client.lookup(intro.senderUsername)) {
            is DirectoryClient.LookupResult.Found -> {
                // The username must currently resolve to the SAME identity
                // key this introduction claims to be from — otherwise it's
                // either stale (they since registered a different username)
                // or an attempt to borrow someone else's claimed username.
                if (!result.identityPublicKey.contentEquals(intro.senderIdentityPublicKey)) {
                    Log.w(TAG, "intro_request username/identity mismatch — refusing")
                    return
                }
                result.signingPublicKey
            }
            else -> {
                Log.w(TAG, "intro_request from unresolvable username — refusing")
                return
            }
        }

        if (!com.securemessenger.core.net.DirectoryProtocol.verifySelfIntroduction(
                intro, ourIdentityPublicKey = identity.publicKey, trustedSigningPublicKey = trustedSigningKey
            )
        ) {
            Log.w(TAG, "intro_request signature verification failed — refusing")
            return
        }

        // Not an error if this is a resend of one already pending, or from
        // someone already a contact — REPLACE just refreshes what we have.
        repository.saveIncomingConnectionRequest(
            senderIdentityPublicKeyHex = B64.toHex(intro.senderIdentityPublicKey),
            senderUserId = intro.senderUserId,
            senderUsername = intro.senderUsername,
            senderSigningPublicKey = intro.senderSigningPublicKey,
            pairSecret = intro.pairSecret,
            directAddress = intro.directAddress
        )
    }

    /**
     * The other side of a request we sent has accepted it. Verified against
     * the signing key WE already captured ourselves at lookup time — see
     * [OutgoingConnectionRequest.recipientSigningPublicKey] — never a fresh
     * lookup, since by definition we already trust-anchored them before ever
     * sending the original request. See
     * [com.securemessenger.core.net.DirectoryProtocol.verifySelfIntroduction]'s
     * doc for why the two verification paths differ.
     */
    private suspend fun handleIntroAccept(inner: JSONObject) {
        val identity = getIdentityKeyPair() ?: return
        val intro = com.securemessenger.core.net.DirectoryProtocol.parseSelfIntroduction(inner) ?: return
        if (intro.senderUserId == userId) return
        val recipientKeyHex = B64.toHex(intro.senderIdentityPublicKey)
        val outgoing = repository.getOutgoingConnectionRequest(recipientKeyHex) ?: run {
            Log.w(TAG, "intro_accept for a request we have no record of sending — ignoring")
            return
        }

        if (!com.securemessenger.core.net.DirectoryProtocol.verifySelfIntroduction(
                intro, ourIdentityPublicKey = identity.publicKey,
                trustedSigningPublicKey = outgoing.recipientSigningPublicKey
            )
        ) {
            Log.w(TAG, "intro_accept signature verification failed — ignoring")
            return
        }

        // intro.pairSecret is THEIR minted secret (see SelfIntroduction's
        // doc) — we send on it. Mirrors pairWithScannedContact's relaySendSecret exactly.
        repository.addContactWithPublicKey(
            contactId = intro.senderUserId,
            publicKeyHex = B64.toHex(intro.senderIdentityPublicKey),
            displayName = intro.senderUsername,
            relaySendSecret = intro.pairSecret
        )
        // The secret WE minted when we sent the original request — we listen
        // on it. The Contact row above now exists, so this binds immediately
        // rather than waiting for a first message the way a still-anonymous
        // QR-minted secret has to (see AppSettings.pendingPairSecrets):
        // there was never any ambiguity about who this secret was for.
        val ourMintedSecret = decryptOutgoingPairSecret(outgoing) ?: return
        repository.bindRelayRecvSecret(intro.senderUserId, ourMintedSecret)

        intro.directAddress
            ?.takeIf { hint ->
                com.securemessenger.core.net.LanAddress.parse(hint, LOCAL_RELAY_PORT)
                    ?.let { (host, p) -> p != boundPort || !com.securemessenger.core.net.LanAddress.isOwnAddress(host) }
                    ?: false
            }
            ?.let { AppSettings.setDirectAddress(appContext, intro.senderUserId, it) }

        refreshTokenMap()
        refreshRelaySubscriptions()
        repository.deleteOutgoingConnectionRequest(recipientKeyHex)
    }

    private fun decryptOutgoingPairSecret(request: OutgoingConnectionRequest): ByteArray? = try {
        com.securemessenger.app.crypto.AndroidKeyStoreManager.decryptWithMasterKey(request.mintedPairSecretEncrypted)
    } catch (e: Exception) {
        Log.e(TAG, "failed to decrypt our own minted pair secret", e)
        null
    }

    /**
     * Resume everything still owed to [contactId] — called the moment that
     * contact is (re)discovered on the network. Two kinds of unfinished work:
     * envelopes already sealed and sitting in the durable outbox, and messages
     * that never got that far because session establishment failed earlier
     * (see [retryPendingSendsTo]). Outbox rows are only removed on a matching
     * "ack" (see [handleAck]), so resending is always safe even if an earlier
     * attempt actually got through.
     */
    private suspend fun flushOutboxTo(contactId: String) {
        repository.getAllOutboxEnvelopes()
            .filter { it.recipientId == contactId }
            // Direct only: this fires because the contact just appeared on the
            // local network, so that is the path worth trying. Anything that
            // still fails is picked up by [retryOutbox], which is where the
            // rate-limited relay fallback lives.
            .forEach { entry -> sendDirect(contactId, entry.envelope) }
        retryPendingSendsTo(contactId)
    }

    /**
     * Persist an outgoing envelope before ever touching the network, then try
     * direct delivery immediately if the recipient happens to be reachable
     * right now. The row survives an app restart or the calculator disguise
     * recreating this client — cleared only once the recipient's own "ack"
     * for this exact id comes back over a direct connection.
     */
    private suspend fun enqueueAndSend(id: String, recipientId: String, envelopeJson: String, clientMessageId: String? = null) {
        repository.saveOutboxEnvelope(id, recipientId, envelopeJson, clientMessageId)
        sendToContact(recipientId, envelopeJson)
    }

    /** The recipient has taken custody of the envelope with this id — stop retrying it. */
    private suspend fun handleAck(json: JSONObject) {
        val id = json.optString("id").takeIf { it.isNotBlank() } ?: return
        // Only clear the outbox entry for an ack proven to come from the real
        // recipient: the ackToken is derived from a key only WE hold and is sent
        // sealed inside the envelope, so only a peer that actually unsealed it
        // can echo it back. An on-path forger who sees the cleartext id cannot.
        val provided = json.optString("ackToken").takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "ack for $id without token — ignoring")
            return
        }
        val expected = ackTokenFor(id) ?: return
        val ok = LibsodiumWrapper.constantTimeCompare(
            android.util.Base64.decode(provided, android.util.Base64.NO_WRAP),
            android.util.Base64.decode(expected, android.util.Base64.NO_WRAP)
        )
        if (ok) repository.deleteOutboxEnvelope(id)
        else Log.w(TAG, "ack for $id with invalid token — ignoring (possible forgery)")
    }

    /**
     * A per-envelope delivery token bound to a key only this device holds (HKDF
     * of our identity secret, domain-separated). It is placed sealed inside each
     * outgoing envelope so only the true recipient can read it, then echoed back
     * in their ack and verified in [handleAck]. This authenticates acks without a
     * shared session MAC key or any schema change. Both peers must run this build.
     */
    private suspend fun ackTokenFor(envelopeId: String): String? {
        val identity = getIdentityKeyPair() ?: return null
        return com.securemessenger.core.crypto.AckToken.compute(identity.secretKey, envelopeId)
    }

    // ---- test seams (instrumented tests only) ----
    @androidx.annotation.VisibleForTesting
    internal suspend fun ackTokenForTest(envelopeId: String): String? = ackTokenFor(envelopeId)

    @androidx.annotation.VisibleForTesting
    internal suspend fun handleAckForTest(json: JSONObject) = handleAck(json)

    suspend fun sendMessage(recipientId: String, plaintext: ByteArray, ttlSeconds: Int? = null, messageId: String? = null): Boolean {
        return try {
            sendMessageInternal(recipientId, plaintext, ttlSeconds, messageId)
            // Any earlier failed attempt for this exact message (see the catch
            // below) is now moot — it succeeded and is a normal, ack-tracked
            // OutboxEnvelope instead (written by enqueueAndSend).
            messageId?.let { repository.deletePendingSend(it) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage failed", e)
            // Establishing a session needs the recipient to answer live (an
            // X3DH prekey fetch, via getOrCreateInitiatorSession) — when that
            // fails or times out, the message never reaches enqueueAndSend, so
            // the durable outbox never learns about it and nothing would ever
            // retry it. Persisting it here is what retryPendingSends() and
            // retryPendingSendsTo() actually act on — without it, a contact
            // who's briefly unreachable the FIRST time you message them loses
            // that message outright, and the "retry" UI action (which only
            // re-runs discovery) had nothing to retry.
            messageId?.let { repository.savePendingSend(it, recipientId, plaintext, ttlSeconds) }
            false
        }
    }

    private suspend fun sendMessageInternal(recipientId: String, plaintext: ByteArray, ttlSeconds: Int?, messageId: String?) {
            val recipientPublicKey = getContactIdentityKey(recipientId)
                ?: throw IllegalStateException("Unknown recipient public key")

            // Establishes a session if none exists yet — may need a live round
            // trip to fetch the recipient's prekey bundle (up to
            // RELAY_BUNDLE_TIMEOUT_MS ≈ 75s). Deliberately outside the
            // per-contact ratchet lock below, so a slow handshake with this
            // contact can never block a message arriving FROM them
            // (handleMessageEnvelope takes that same lock to decrypt).
            // Concurrent callers for the same contact are serialized by this
            // function's own handshake lock instead — see getOrCreateInitiatorSession.
            getOrCreateInitiatorSession(recipientId)

            // Routing id created up front so we can bind a delivery token to it
            // and seal that token inside the envelope (verified in handleAck).
            val envelopeId = java.util.UUID.randomUUID().toString()
            val ackToken = ackTokenFor(envelopeId)
            val senderIdentityKey = getIdentityKeyPair()?.publicKey

            val envelope = lockFor(recipientId).withLock {
                // Re-read the current session here rather than trust a
                // reference captured before this lock: a message arriving FROM
                // this same contact concurrently (simultaneous initiation) can
                // rebuild it as a responder session between the call above and
                // this lock being acquired (see createResponderSession /
                // selectSessionForIncoming's tie-break, which runs under this
                // very lock keyed by the same contact id). Reading it fresh
                // here guarantees we always encrypt with — and correctly
                // describe as initiator or not — whichever session is actually
                // current, instead of a possibly-abandoned one.
                val protocol = cachedOrPersisted(recipientId)
                    ?: throw IllegalStateException("session vanished for $recipientId")
                // Pad to a fixed bucket so ciphertext length doesn't leak message size.
                val encryptedMessage = protocol.encryptMessage(MessagePadding.pad(plaintext))
                // We are the initiator for this contact unless we established the
                // session as a responder to them.
                val weAreInitiator = !responderEphemerals.containsKey(recipientId)

                // Inner envelope holds everything the recipient needs — including who
                // the sender is and when it was sent. It is sealed to the recipient's
                // key so anyone else on the local network never sees any of it.
                val inner = JSONObject().apply {
                    put("senderId", userId)
                    ackToken?.let { put("ackToken", it) }
                    put("ciphertext", android.util.Base64.encodeToString(encryptedMessage.ciphertext, android.util.Base64.NO_WRAP))
                    put("dhPublicKey", android.util.Base64.encodeToString(encryptedMessage.dhPublicKey, android.util.Base64.NO_WRAP))
                    put("chainCounter", encryptedMessage.chainCounter)
                    put("previousChainLength", encryptedMessage.previousChainLength)
                    put("timestamp", System.currentTimeMillis())
                    // Shared id so a read receipt can reference this exact message.
                    messageId?.let { put("messageId", it) }
                    ttlSeconds?.let { put("ttl", it) }
                    senderIdentityKey?.let {
                        put("senderIdentityKey", android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP))
                    }
                    // X3DH bootstrap fields only when we are initiating — they let the
                    // recipient (re)build their responder session.
                    if (weAreInitiator) {
                        protocol.getInitiatorEphemeralPublicKey()?.let {
                            put("initiatorEphemeralKey", android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP))
                        }
                        initiatorOtkIds[recipientId]?.let { put("oneTimePreKeyId", it) }
                        initiatorPqCiphertexts[recipientId]?.let {
                            put("pqKemCiphertext", android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP))
                        }
                    }
                }

                // The ratchet advanced — persist so a restart can resume the session.
                persistSession(recipientId)

                // The routing id lets the recipient's ack be matched back to this
                // exact outbox entry.
                sealedEnvelope("message", inner, recipientPublicKey, envelopeId)
            }

            // DB write + network send of the already-built envelope — deliberately
            // outside the ratchet lock, since neither touches session state.
            enqueueAndSend(envelopeId, recipientId, envelope.toString(), clientMessageId = messageId)
    }

    private suspend fun handleIncomingMessage(jsonString: String, reply: (String) -> Unit = {}) {
        try {
            val json = JSONObject(jsonString)
            val type = json.getString("type")
            when (type) {
                "message" -> handleAndAck(type, json, reply) { handleMessageEnvelope(json) }
                "receipt" -> handleAndAck(type, json, reply) { handleReceiptEnvelope(json) }
                "typing" -> handleTypingEnvelope(json)
                "ack" -> handleAck(json)
                "bundle_announce" -> handleBundleAnnounce(json)
                "challenge" -> handleChallenge(json, reply)
                // Relay cover traffic. Nothing to do — being ignored is the
                // entire point of it.
                "noise" -> {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleIncomingMessage failed", e)
        }
    }

    /**
     * Run [handler] for an envelope that the sender is waiting on, then send
     * back the acknowledgement that lets them clear it from their outbox.
     *
     * The sender only learns we actually received it once this comes back —
     * over the same direct connection they used, or through the relay if that
     * is how it reached us. The ackToken, knowable only by unsealing the
     * envelope, proves the ack came from the real recipient rather than an
     * on-path forger.
     */
    private suspend fun handleAndAck(
        type: String,
        json: JSONObject,
        reply: (String) -> Unit,
        handler: suspend () -> String?
    ) {
        val ackToken = onceOnly(type, json, handler)
        val id = json.optString("id").takeIf { it.isNotBlank() } ?: return
        reply(JSONObject().apply {
            put("type", "ack"); put("id", id)
            ackToken?.let { put("ackToken", it) }
        }.toString())
    }

    /**
     * Process an envelope exactly once, however many times it *successfully*
     * arrives.
     *
     * A resend whose ack was lost, and the same envelope racing in over both
     * transports at once, are both normal here — but [handler] advances the
     * ratchet, so running it twice would fail to decrypt and could surface a
     * duplicate in the chat. A repeat of an envelope we already handled
     * therefore skips [handler] and reuses the remembered ack token, so the
     * sender still gets the acknowledgement it is waiting for and stops
     * retrying.
     *
     * A repeat of an envelope [handler] previously *dropped* (returned null
     * without throwing — no session yet, lost a simultaneous-initiation race,
     * unrecognized sender) is different: nothing was consumed, so it is both
     * safe and necessary to run [handler] again. The sender's durable outbox
     * will keep resending that exact envelope regardless, on its own retry
     * schedule, for as long as whatever blocked it might take to resolve
     * (e.g. the responder session finishing establishment) — treating that
     * first drop as final would silently blackhole the message forever and
     * leave the sender's copy stuck "sending" with no way to ever un-stick.
     *
     * Checked in two layers: the in-memory map first (fast, and always
     * current within one client lifetime), then the persisted table (covers
     * an envelope handled by an *earlier* client instance — see
     * [seenEnvelopes]).
     */
    private suspend fun onceOnly(type: String, json: JSONObject, handler: suspend () -> String?): String? {
        val id = json.optString("id").takeIf { it.isNotBlank() } ?: return handler()
        val key = "$type:$id"
        synchronized(seenEnvelopes) { seenEnvelopes[key] }?.let { return it }
        repository.getSeenEnvelopeAckToken(key)?.let { persisted ->
            synchronized(seenEnvelopes) { seenEnvelopes[key] = persisted }
            return persisted
        }
        val ackToken = handler()
        if (ackToken != null) {
            synchronized(seenEnvelopes) { seenEnvelopes[key] = ackToken }
            repository.rememberSeenEnvelope(key, ackToken)
        }
        return ackToken
    }

    /**
     * Build the outer, on-the-wire frame for [inner], sealed to [recipientPublicKey].
     *
     * Every outgoing frame has this exact shape, so it is built in exactly one
     * place: an anonymous sealed box (crypto_box_seal — it carries no sender
     * identity of its own) wrapped in the thinnest possible routing header.
     * Frames the recipient must acknowledge carry an [envelopeId] to ack
     * against; ephemeral ones (typing, challenge, bundle_announce) don't.
     *
     * Who the frame is *for* is stated only inside the seal. The outer header
     * used to repeat it in the clear as `recipientId`, which no receiver ever
     * read — see the note in Envelopes.
     */
    private fun sealedEnvelope(
        type: String,
        inner: JSONObject,
        recipientPublicKey: ByteArray,
        envelopeId: String? = null
    ): JSONObject =
        // Delegated to :core so this app and the desktop client build byte-
        // identical frames. Any change to the frame shape must happen there, in
        // one file, or the two platforms quietly stop being able to read each
        // other's messages.
        com.securemessenger.core.net.Envelopes.seal(
            type, inner, recipientPublicKey, envelopeId
        )

    /**
     * Unseal a sealed-sender envelope (used for both chat messages and read
     * receipts). Memoized by the sealed ciphertext string, which is safe
     * because the result is a deterministic function of (that ciphertext, our
     * own identity key pair) and our identity never changes during this
     * object's lifetime — see [unsealCache]. The returned object is shared
     * across every caller that unseals the same envelope: read it, never
     * mutate it.
     */
    private suspend fun unsealEnvelope(outer: JSONObject): JSONObject {
        if (!outer.has("sealed")) return outer
        val sealed = outer.getString("sealed")
        synchronized(unsealCache) { unsealCache[sealed] }?.let { return it }
        val identity = getIdentityKeyPair() ?: throw IllegalStateException("Identity key not found")
        val opened = com.securemessenger.core.net.Envelopes.open(outer, identity.publicKey, identity.secretKey)
        synchronized(unsealCache) { unsealCache[sealed] = opened }
        return opened
    }

    /** Tell a sender that we've read the messages they sent us. */
    suspend fun sendReadReceipt(recipientId: String, messageIds: List<String>): Boolean = withContext(Dispatchers.IO) {
        if (messageIds.isEmpty()) return@withContext true
        try {
            val recipientPublicKey = getContactIdentityKey(recipientId)
                ?: throw IllegalStateException("Unknown recipient public key")

            val envelopeId = java.util.UUID.randomUUID().toString()
            val ackToken = ackTokenFor(envelopeId)
            val inner = JSONObject().apply {
                put("senderId", userId)
                put("messageIds", JSONArray(messageIds))
                ackToken?.let { put("ackToken", it) }
            }
            val envelope = sealedEnvelope("receipt", inner, recipientPublicKey, envelopeId)

            enqueueAndSend(envelopeId, recipientId, envelope.toString())
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendReadReceipt failed", e)
            false
        }
    }

    private suspend fun handleReceiptEnvelope(outer: JSONObject): String? {
        val json = unsealEnvelope(outer)
        // A sealed envelope proves nothing about who sent it, so only accept
        // receipts that name a known contact, and only ever flip messages we
        // actually sent TO that contact (scoped in the DB layer). A forged
        // receipt therefore can't mark arbitrary messages as read.
        val senderId = json.optString("senderId").takeIf { it.isNotBlank() } ?: return null
        if (repository.getContact(senderId) == null) return null
        val messageIds = mutableListOf<String>()
        val idsArray = json.getJSONArray("messageIds")
        for (i in 0 until idsArray.length()) messageIds.add(idsArray.getString(i))
        repository.markMessagesReadByClientIds(messageIds, senderId)
        return json.optString("ackToken").takeIf { it.isNotBlank() }
    }

    /**
     * Tell a contact we're actively typing to them right now. Sent directly
     * (not through the durable outbox) — this is inherently ephemeral, so
     * there's nothing worth persisting or retrying if it doesn't land.
     */
    suspend fun sendTypingSignal(recipientId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val recipientPublicKey = getContactIdentityKey(recipientId) ?: return@withContext false
            val inner = JSONObject().apply { put("senderId", userId) }
            val envelope = sealedEnvelope("typing", inner, recipientPublicKey)
            // Direct only — deliberately never falls back to the relay like
            // sendToContact does. A typing indicator that lands after the
            // relay's poll interval (POLL_MIN_MS..POLL_MAX_MS, so several
            // seconds at best) is worthless — the conversation has moved on by
            // the time it arrives — while still costing a real deposit (an
            // observable, timestamped mailbox write) for nothing. Failing
            // silently when not directly reachable is exactly right, per the
            // doc comment above: this is inherently ephemeral already.
            sendDirect(recipientId, envelope.toString())
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun handleTypingEnvelope(outer: JSONObject) {
        val json = unsealEnvelope(outer)
        val senderId = json.optString("senderId").takeIf { it.isNotBlank() } ?: return
        _typingSignals.emit(senderId)
    }

    private suspend fun handleMessageEnvelope(outer: JSONObject): String? {
        // Unseal the sealed-sender envelope to reveal the real message fields.
        val json = unsealEnvelope(outer)
        // Sealed delivery token — readable only here; echoed back in our ack so
        // the sender can tell a real receipt from an on-path forgery.
        val ackToken = json.optString("ackToken").takeIf { it.isNotBlank() }

        val senderId = json.getString("senderId")
        val ciphertext = android.util.Base64.decode(json.getString("ciphertext"), android.util.Base64.NO_WRAP)
        val dhPublicKey = android.util.Base64.decode(json.getString("dhPublicKey"), android.util.Base64.NO_WRAP)
        val chainCounter = json.getInt("chainCounter")
        val previousChainLength = json.optInt("previousChainLength", 0)

        // Same per-contact lock sendMessage() uses — an incoming message from
        // this contact must not decrypt (advancing the receive chain) at the
        // same moment we're encrypting an outgoing one to them.
        val plaintext = lockFor(senderId).withLock {
            val protocol = selectSessionForIncoming(senderId, json) ?: return null
            val encryptedMessage = SignalProtocol.EncryptedMessage(
                ciphertext = ciphertext,
                chainCounter = chainCounter,
                dhPublicKey = dhPublicKey,
                previousChainLength = previousChainLength
            )
            val decrypted = MessagePadding.unpad(protocol.decryptMessage(encryptedMessage))
            // The receive chain advanced (and maybe a DH ratchet) — persist it.
            persistSession(senderId)
            decrypted
        }

        val senderIdentityKey = json.optString("senderIdentityKey").takeIf { it.isNotBlank() }?.let {
            android.util.Base64.decode(it, android.util.Base64.NO_WRAP)
        }
        val ttlSeconds = if (json.has("ttl")) json.optInt("ttl") else null
        val messageId = json.optString("messageId").takeIf { it.isNotBlank() }

        _incomingMessages.send(MessageReceived(senderId, plaintext, senderIdentityKey, ttlSeconds, messageId))
        return ackToken
    }

    /**
     * Return the in-memory session for a contact, rehydrating it from encrypted
     * storage (populating our bookkeeping maps) if it isn't cached yet.
     */
    private suspend fun cachedOrPersisted(contactId: String): SignalProtocol? {
        sessions[contactId]?.let { return it }
        val loaded = repository.loadRatchetSession(contactId) ?: return null
        sessions[contactId] = loaded.protocol
        if (loaded.isInitiator) responderEphemerals.remove(contactId)
        else responderEphemerals[contactId] = loaded.responderEphemeralHex ?: ""
        loaded.initiatorOtkId?.let { initiatorOtkIds[contactId] = it }
        return loaded.protocol
    }

    /** Persist the current session state so it survives an app restart. */
    private suspend fun persistSession(contactId: String) {
        val protocol = sessions[contactId] ?: return
        try {
            // NonCancellable: the in-memory ratchet has already advanced by the
            // time this runs. If disconnect()'s scope.cancel() fires mid-write,
            // a cancelled write would leave the on-disk session a step behind
            // the one actually used to send/receive — permanently desyncing
            // the chain on reload. This write always finishes once started.
            withContext(NonCancellable) {
                repository.saveRatchetSession(
                    contactId = contactId,
                    protocol = protocol,
                    isInitiator = !responderEphemerals.containsKey(contactId),
                    responderEphemeralHex = responderEphemerals[contactId],
                    initiatorOtkId = initiatorOtkIds[contactId]
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "persistSession failed", e)
        }
    }

    /**
     * Choose (or build) the session to decrypt an incoming message with.
     * Returns null if the message should be dropped.
     */
    private suspend fun selectSessionForIncoming(senderId: String, json: JSONObject): SignalProtocol? {
        val ephemeralHex = json.optString("initiatorEphemeralKey")
        val existing = cachedOrPersisted(senderId)

        // A plain reply (no X3DH bootstrap): continue the existing session, if any.
        if (ephemeralHex.isEmpty()) return existing

        // The sender is initiating a session.
        if (existing == null) return createResponderSession(senderId, json)

        if (responderEphemerals.containsKey(senderId)) {
            // We already respond to them; rebuild only if they re-initiated
            // (different ephemeral, e.g. after their app restarted).
            return if (responderEphemerals[senderId] == ephemeralHex) existing
            else createResponderSession(senderId, json)
        }

        // Simultaneous initiation: both of us created initiator sessions. Break the
        // tie deterministically — the smaller userId stays initiator; the other
        // side (here) rebuilds as responder so both converge on one session.
        return if (senderId < userId) {
            createResponderSession(senderId, json)
        } else {
            null // keep our initiator session; drop their racing message
        }
    }

    private suspend fun createResponderSession(senderId: String, json: JSONObject): SignalProtocol {
        val identityKeyPair = getIdentityKeyPair()
            ?: throw IllegalStateException("Identity key not found")
        val signedPreKeyPair = getSignedPreKey()
            ?: throw IllegalStateException("Signed prekey not found")

        val initiatorEphemeralKey = android.util.Base64.decode(
            json.getString("initiatorEphemeralKey"),
            android.util.Base64.NO_WRAP
        )
        val initiatorIdentityKey = android.util.Base64.decode(
            json.optString("senderIdentityKey").ifBlank {
                getContactIdentityKey(senderId)?.let {
                    android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)
                } ?: ""
            },
            android.util.Base64.NO_WRAP
        )

        // Load the secret of the one-time prekey the initiator consumed (if any)
        // so the DH4 term of X3DH matches on both sides.
        val oneTimePreKeyId = if (json.has("oneTimePreKeyId")) json.optInt("oneTimePreKeyId") else null
        val ourOneTimePreKeySecret = oneTimePreKeyId?.let { repository.getOurPreKeySecret(it) }

        // Post-quantum: decapsulate the initiator's ML-KEM ciphertext with our
        // ML-KEM secret to recover the same shared secret they mixed in.
        val pqSharedSecret = json.optString("pqKemCiphertext").takeIf { it.isNotBlank() }?.let { ct ->
            repository.getMlkemSecret()?.let { secret ->
                PqKem.decapsulate(secret, android.util.Base64.decode(ct, android.util.Base64.NO_WRAP))
            }
        }

        val protocol = SignalProtocol(
            identityKeyPair = identityKeyPair,
            signedPreKeyPair = signedPreKeyPair,
            oneTimePreKeys = getOneTimePreKeys()
        )

        protocol.initializeAsResponder(
            initiatorIdentityKey = initiatorIdentityKey,
            initiatorEphemeralKey = initiatorEphemeralKey,
            ourSignedPreKeySecret = signedPreKeyPair.secretKey,
            ourIdentitySecretKey = identityKeyPair.secretKey,
            ourOneTimePreKeySecret = ourOneTimePreKeySecret,
            pqSharedSecret = pqSharedSecret
        )

        // Retire the consumed prekey so it is never reused.
        if (ourOneTimePreKeySecret != null && oneTimePreKeyId != null) {
            repository.markPreKeyAsUsed(oneTimePreKeyId)
        }

        sessions[senderId] = protocol
        responderEphemerals[senderId] = json.optString("initiatorEphemeralKey")
        return protocol
    }

    private suspend fun getOrCreateInitiatorSession(contactId: String): SignalProtocol {
        cachedOrPersisted(contactId)?.let { return it }
        // Two sends fired in quick succession to a contact with no session yet
        // would otherwise both start X3DH at once — each registers itself in
        // pendingBundleRequests[contactId], and the second overwrites the
        // first's entry, so the first's reply (when it arrives) is delivered
        // to the wrong waiter and the first send times out despite a valid
        // bundle_announce having come back. Serialize per contact instead.
        return handshakeLockFor(contactId).withLock {
            // Re-check: another call may have finished establishing the
            // session while this one was waiting for the lock.
            cachedOrPersisted(contactId)?.let { return@withLock it }
            val prekeyBundle = fetchPrekeyBundle(contactId)
            val identityKeyPair = getIdentityKeyPair()
                ?: throw IllegalStateException("Identity key not found")
            val signedPreKeyPair = getSignedPreKey()
                ?: throw IllegalStateException("Signed prekey not found")

            val protocol = SignalProtocol(
                identityKeyPair = identityKeyPair,
                signedPreKeyPair = signedPreKeyPair,
                oneTimePreKeys = getOneTimePreKeys()
            )

            // Post-quantum: encapsulate to the recipient's ML-KEM key (if any) and
            // fold the resulting secret into the handshake. If the peer DID
            // advertise an ML-KEM key, refuse to silently fall back to a
            // classical-only handshake when encapsulation fails.
            val pqEncapsulation = prekeyBundle.mlkemPublicKey?.let { PqKem.encapsulate(it) }
            if (prekeyBundle.mlkemPublicKey != null && pqEncapsulation == null) {
                throw IllegalStateException("Peer advertised a post-quantum key but encapsulation failed — refusing to downgrade")
            }

            protocol.initializeAsInitiator(
                recipientIdentityKey = prekeyBundle.identityKey,
                recipientSignedPreKey = prekeyBundle.signedPreKey,
                recipientOneTimePreKey = prekeyBundle.oneTimePreKey,
                pqSharedSecret = pqEncapsulation?.sharedSecret
            )

            // Remember the OTK id so the first outgoing envelope can carry it.
            if (prekeyBundle.oneTimePreKey != null && prekeyBundle.oneTimePreKeyId != null) {
                initiatorOtkIds[contactId] = prekeyBundle.oneTimePreKeyId
            }
            pqEncapsulation?.let { initiatorPqCiphertexts[contactId] = it.ciphertext }

            sessions[contactId] = protocol
            responderEphemerals.remove(contactId) // we are the initiator for this contact
            protocol
        }
    }

    /**
     * Directly asks [contactId]'s device for its current prekey bundle over a
     * live local connection — replaces the old HTTP GET against Cloudflare/D1.
     * The peer must be reachable on the local network right now; there is no
     * fallback (no central directory to ask instead).
     */
    private suspend fun fetchPrekeyBundle(contactId: String): PrekeyBundle {
        val deferred = CompletableDeferred<PrekeyBundle>()
        pendingBundleRequests[contactId] = deferred
        val nonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        pendingChallengeNonces[contactId] = nonce
        try {
            val recipientPublicKey = getContactIdentityKey(contactId)
                ?: throw IllegalStateException("Unknown recipient public key")
            // Prove this is a live exchange, not a replayed capture of an
            // earlier bundle_announce: the peer must sign this exact nonce.
            // Including our own userId lets the peer refuse to hand out its
            // bundle to strangers (see handleChallenge). Sealed to the
            // contact's already-pinned identity key (both sides have it from
            // QR pairing before any of this runs) so a passive LAN observer
            // never sees our userId — only ever a size-similar opaque blob,
            // same as every message/receipt/typing frame. Both peers must
            // run this build.
            val inner = JSONObject().apply {
                put("nonce", android.util.Base64.encodeToString(nonce, android.util.Base64.NO_WRAP))
                put("userId", userId)
            }
            val challenge = sealedEnvelope("challenge", inner, recipientPublicKey).toString()

            // This handshake has to travel the same road messages do. It used
            // to demand a direct local socket, which quietly made the relay
            // useless: two devices that can only reach each other through the
            // blind mailbox could deliver messages but could never establish
            // the session those messages need, so the very first send failed.
            val socket = connectionTo(contactId)
            val overRelay = socket == null
            if (socket != null) {
                socket.send(challenge)
            } else if (relay?.enqueue(contactId, challenge) != true) {
                throw Exception("جهة الاتصال غير متاحة على الشبكة الحالية الآن")
            }

            // Through the relay this is a full round trip of deposit → their
            // poll → their reply → our poll, each with its own jitter, so the
            // budget has to be far larger than a direct socket's.
            val timeout = if (overRelay) RELAY_BUNDLE_TIMEOUT_MS else LOCAL_BUNDLE_TIMEOUT_MS
            return withTimeoutOrNull(timeout) { deferred.await() }
                ?: throw Exception(
                    if (overRelay) "لم يردّ الطرف الآخر عبر الوسيط — تأكد أن التطبيق مفتوح لديه"
                    else "انتهت مهلة انتظار حزمة مفاتيح جهة الاتصال"
                )
        } finally {
            pendingBundleRequests.remove(contactId)
            pendingChallengeNonces.remove(contactId)
        }
    }

    /** A peer asked us to prove we're live and hand over our current bundle — answer signed over their nonce. */
    private suspend fun handleChallenge(outer: JSONObject, reply: (String) -> Unit) {
        try {
            // The requester's userId/nonce arrive sealed to our identity key
            // (see fetchPrekeyBundle) — unseal before reading anything.
            val json = unsealEnvelope(outer)
            // Our bundle (identity/signed-prekey/PQ-key, plus a live one-time
            // prekey it burns on the way out) is only ever handed to someone
            // who claims to be one of our own paired contacts — confirmed
            // via a live test: an anonymous prober with no claimed identity
            // could otherwise harvest the full identity bundle AND exhaust
            // the entire one-time-prekey pool in a handful of connections,
            // with zero prior relationship. The claimed userId isn't
            // cryptographically proven at this bootstrap step (that's what
            // the Double Ratchet session built from this bundle establishes
            // afterwards), but it closes off the blind, driveby version of
            // the attack — a stranger would need to already know a specific
            // real contact's userId, not just be anyone on the LAN.
            val claimedUserId = json.optString("userId").takeIf { it.isNotBlank() }
            if (claimedUserId == null || repository.getContact(claimedUserId) == null) {
                Log.w(TAG, "challenge from unrecognized/anonymous requester — refusing")
                return
            }
            // Rate-limit bundle handouts per requester: buildMyBundleAnnounce
            // burns a one-time prekey each time, so without this a peer that
            // learned a valid userId could exhaust the whole OTK pool by
            // repeatedly challenging us.
            val now = System.currentTimeMillis()
            val last = lastBundleHandout[claimedUserId]
            if (last != null && now - last < BUNDLE_HANDOUT_MIN_INTERVAL_MS) {
                Log.w(TAG, "challenge from $claimedUserId throttled — not burning another one-time prekey")
                return
            }
            lastBundleHandout[claimedUserId] = now
            val nonce = android.util.Base64.decode(json.optString("nonce"), android.util.Base64.NO_WRAP)
            val requesterPublicKey = getContactIdentityKey(claimedUserId) ?: return
            // Seal our reply (userId + full identity/PQ bundle) to the
            // requester's pinned key — same reasoning as the challenge frame.
            reply(sealedEnvelope("bundle_announce", buildMyBundleAnnounce(nonce), requesterPublicKey).toString())
        } catch (e: Exception) {
            Log.e(TAG, "handleChallenge failed", e)
        }
    }

    private suspend fun handleBundleAnnounce(outer: JSONObject) {
        // Sealed to us by handleChallenge — unseal before reading anything.
        val json = unsealEnvelope(outer)
        val theirUserId = json.optString("userId").takeIf { it.isNotBlank() } ?: return
        val bundle = parseBundleJson(json, pendingChallengeNonces[theirUserId]) ?: return

        // Critical: never build an X3DH session from a self-declared identity
        // key. Only trust it if it matches the key pinned for this contact at
        // add-time (TOFU from the in-person QR scan) — otherwise any device on
        // the same LAN could answer a contact's discovery token with its own
        // keys and quietly become a live man-in-the-middle.
        val pinnedKey = repository.getContact(theirUserId)?.publicKey
        if (pinnedKey == null || !bundle.identityKey.contentEquals(pinnedKey)) {
            Log.e(TAG, "bundle_announce identityKey mismatch/unknown for $theirUserId — refusing")
            return
        }

        pendingBundleRequests[theirUserId]?.let { deferred ->
            if (!deferred.isCompleted) deferred.complete(bundle)
        }
    }

    /**
     * Read and verify an incoming bundle.
     *
     * The parsing, the nonce check and the signature verification all live in
     * :core — including exactly which fields the signature covers, which is the
     * part that must never differ between the two platforms. A desktop client
     * that signed over a slightly different byte string would have every one of
     * its bundles rejected here as forged, and vice versa.
     */
    private fun parseBundleJson(json: JSONObject, expectedNonce: ByteArray?): PrekeyBundle? {
        val parsed = com.securemessenger.core.net.Envelopes.parseBundle(json, expectedNonce)
        if (parsed == null) {
            Log.e(TAG, "prekey bundle rejected — bad signature, wrong nonce, or malformed")
            return null
        }
        return PrekeyBundle(
            identityKey = parsed.identityKey,
            signedPreKey = parsed.signedPreKey,
            signingPublicKey = parsed.signingPublicKey,
            oneTimePreKey = parsed.oneTimePreKey,
            oneTimePreKeyId = parsed.oneTimePreKeyId,
            mlkemPublicKey = parsed.mlkemPublicKey
        )
    }

    /** Our own current prekey bundle, in the same shape a peer's bundle_announce carries. */
    private suspend fun buildMyBundleAnnounce(nonce: ByteArray): JSONObject {
        val identity = getIdentityKeyPair() ?: throw IllegalStateException("Identity key not found")
        val signedPreKey = getSignedPreKey() ?: throw IllegalStateException("Signed prekey not found")
        val mlkemPublicKey = repository.getMlkemPublicKey() ?: ByteArray(0)
        val otk = getOneTimePreKeys().firstOrNull()

        val signingSecret = repository.getSigningSecretKey()
        val announce = com.securemessenger.core.net.Envelopes.buildBundleAnnounce(
            userId = userId,
            nonce = nonce,
            identityKey = identity.publicKey,
            signedPreKey = signedPreKey.publicKey,
            signedPreKeyId = signedPreKey.id,
            signingPublicKey = repository.getSigningPublicKey(),
            mlkemPublicKey = mlkemPublicKey,
            oneTimePreKeyId = otk?.id,
            oneTimePreKey = otk?.publicKey,
            sign = { payload ->
                signingSecret?.let { LibsodiumWrapper.signDetached(payload, it) } ?: ByteArray(0)
            }
        )

        // Mark the one-time prekey consumed the instant it leaves this device —
        // not only once an initiator finishes X3DH with it — so a second
        // requester (or the same one asking twice) is never handed the same
        // "one-time" key again.
        otk?.let { repository.markPreKeyAsUsed(it.id) }
        return announce
    }

    /**
     * Adds a brand-new contact paired by scanning their QR code, then fetches
     * their prekey bundle right away over the local network (they must be
     * reachable at pairing time — this is the in-person "meet and scan"
     * moment, the local-only equivalent of the old add-by-username flow).
     *
     * [pairSecretHex], when the scanned QR carried one, becomes our *outbound*
     * relay channel to them: they minted it, so they are the side listening on
     * it. Our inbound channel is one of the secrets we minted ourselves, and it
     * gets bound to this contact later, when their first message actually
     * arrives in it. A QR from an older build carries no secret — that contact
     * then simply has no relay path and stays local-network-only.
     */
    /**
     * @param allowKeyChange See [SecureRepository.addContactWithPublicKey] —
     * only pass true once the caller has explicitly warned the user that
     * this contact's pinned key is about to change and they've confirmed it.
     * @return null on an unrelated failure (pairing yourself, a malformed
     * key, an exception) — see [SecureRepository.ContactPairResult] for
     * every other outcome, including the refused-without-confirmation case.
     */
    suspend fun pairWithScannedContact(
        scannedUserId: String,
        identityKeyHex: String,
        displayName: String,
        pairSecretHex: String? = null,
        directAddress: String? = null,
        allowKeyChange: Boolean = false
    ): com.securemessenger.app.data.repository.ContactPairResult? =
        withContext(Dispatchers.IO) {
            try {
                if (scannedUserId == userId) return@withContext null
                val pairSecret = pairSecretHex?.let { MailboxToken.pairSecretFromHex(it) }
                val result = repository.addContactWithPublicKey(
                    scannedUserId, identityKeyHex, displayName,
                    relaySendSecret = pairSecret,
                    allowKeyChange = allowKeyChange
                )
                if (result == com.securemessenger.app.data.repository.ContactPairResult.KEY_CHANGED) {
                    // Refused — nothing below (address hint, discovery,
                    // relay subscriptions) should run for a pairing that
                    // didn't actually go through. The caller re-invokes with
                    // allowKeyChange = true once the user confirms.
                    return@withContext result
                }
                // The address the other device was listening on when it drew
                // that QR. Only a starting hint — a successful connection
                // overwrites it, and mDNS is still tried first — but it means
                // pairing works out of the box on a network where multicast is
                // blocked, instead of appearing to succeed and then never
                // delivering anything.
                // Refuse a hint that names one of our own addresses — see
                // connectionTo. Storing it would point every future send at
                // this device's own listening socket.
                directAddress
                    ?.takeIf { hint ->
                        com.securemessenger.core.net.LanAddress.parse(hint, LOCAL_RELAY_PORT)
                            ?.let { (host, p) ->
                                p != boundPort || !com.securemessenger.core.net.LanAddress.isOwnAddress(host)
                            } ?: false
                    }
                    ?.let { AppSettings.setDirectAddress(appContext, scannedUserId, it) }
                refreshTokenMap()
                refreshRelaySubscriptions()
                result
            } catch (e: Exception) {
                Log.e(TAG, "pairWithScannedContact failed", e)
                null
            }
        }

    /** What [sendConnectionRequest] actually did. */
    sealed class ConnectionRequestResult {
        /** Deposited — see [SecureRepository.getOutgoingConnectionRequests] for "still pending" state, or [handleIntroAccept] once it lands. */
        data object Sent : ConnectionRequestResult()
        /** A request to this exact identity is already outstanding — nothing re-sent, avoids spamming the same person from a repeat search. */
        data object AlreadyPending : ConnectionRequestResult()
        data object UsernameNotFound : ConnectionRequestResult()
        data object CannotAddSelf : ConnectionRequestResult()
        /** Directory unreachable, feature disabled, or any other failure. */
        data object Error : ConnectionRequestResult()
    }

    /**
     * Resolve a username without sending anything — what the search screen
     * calls as the user types, so they see who they'd be contacting before
     * committing to [sendConnectionRequest]. `null` covers both "not found"
     * and "directory unreachable/disabled"; the caller only needs a boolean
     * here, [sendConnectionRequest] is what a real caller distinguishes on.
     */
    suspend fun lookupUsername(username: String): DirectoryClient.LookupResult.Found? {
        val client = directory ?: return null
        return (client.lookup(username) as? DirectoryClient.LookupResult.Found)
    }

    /**
     * Look up [username] and, if found, deposit a signed self-introduction
     * into their introduction mailbox — the remote equivalent of showing
     * someone your QR code. Nothing about this contact is trusted or stored
     * as a real [Contact] yet; that only happens if/when they explicitly
     * accept (see [handleIntroAccept]).
     */
    suspend fun sendConnectionRequest(username: String): ConnectionRequestResult = withContext(Dispatchers.IO) {
        val client = directory ?: return@withContext ConnectionRequestResult.Error
        try {
            val found = when (val result = client.lookup(username)) {
                is DirectoryClient.LookupResult.Found -> result
                DirectoryClient.LookupResult.NotFound -> return@withContext ConnectionRequestResult.UsernameNotFound
                DirectoryClient.LookupResult.Error -> return@withContext ConnectionRequestResult.Error
            }
            val recipientKeyHex = B64.toHex(found.identityPublicKey)
            val identity = getIdentityKeyPair() ?: return@withContext ConnectionRequestResult.Error
            if (found.identityPublicKey.contentEquals(identity.publicKey)) {
                return@withContext ConnectionRequestResult.CannotAddSelf
            }
            if (repository.getOutgoingConnectionRequest(recipientKeyHex) != null) {
                return@withContext ConnectionRequestResult.AlreadyPending
            }

            val myUsername = AppSettings.getUsername(appContext) ?: userId.take(8)
            val signingSecret = repository.getSigningSecretKey() ?: return@withContext ConnectionRequestResult.Error
            val signingPublic = repository.getSigningPublicKey() ?: return@withContext ConnectionRequestResult.Error
            val mintedSecret = MailboxToken.newPairSecret()

            val inner = com.securemessenger.core.net.DirectoryProtocol.buildSelfIntroduction(
                senderUserId = userId,
                senderUsername = myUsername,
                senderIdentityPublicKey = identity.publicKey,
                senderSigningPublicKey = signingPublic,
                addresseeIdentityPublicKey = found.identityPublicKey,
                pairSecret = mintedSecret,
                directAddress = myDirectAddress(),
                timestampMillis = System.currentTimeMillis(),
                sign = { payload -> LibsodiumWrapper.signDetached(payload, signingSecret) }
            )
            val outer = sealedEnvelope(
                com.securemessenger.core.net.Envelopes.TYPE_INTRO_REQUEST, inner, found.identityPublicKey
            )
            val blob = B64.encode(outer.toString().toByteArray(Charsets.UTF_8))

            if (!client.depositIntroduction(found.identityPublicKey, blob)) {
                return@withContext ConnectionRequestResult.Error
            }
            repository.saveOutgoingConnectionRequest(
                recipientIdentityPublicKeyHex = recipientKeyHex,
                recipientUsername = found.username,
                recipientSigningPublicKey = found.signingPublicKey,
                mintedPairSecret = mintedSecret
            )
            ConnectionRequestResult.Sent
        } catch (e: Exception) {
            Log.e(TAG, "sendConnectionRequest failed", e)
            ConnectionRequestResult.Error
        }
    }

    /**
     * Accept a pending [IncomingConnectionRequest]: pins their key as a real
     * [Contact] (mirrors [pairWithScannedContact] exactly — sender's minted
     * secret becomes our outbound channel), mints our own secret for them to
     * use inbound, and deposits a signed intro_accept so they can complete
     * the same steps on their side. False on any failure — the request row
     * is left in place so the user can simply try again.
     */
    suspend fun acceptConnectionRequest(senderIdentityPublicKeyHex: String): Boolean = withContext(Dispatchers.IO) {
        val client = directory ?: return@withContext false
        try {
            val request = repository.getIncomingConnectionRequest(senderIdentityPublicKeyHex) ?: return@withContext false
            val senderIdentityPublicKey = B64.fromHex(senderIdentityPublicKeyHex) ?: return@withContext false
            val theirPairSecret = com.securemessenger.app.crypto.AndroidKeyStoreManager
                .decryptWithMasterKey(request.pairSecretEncrypted)

            repository.addContactWithPublicKey(
                contactId = request.senderUserId,
                publicKeyHex = senderIdentityPublicKeyHex,
                displayName = request.senderUsername,
                relaySendSecret = theirPairSecret
            )

            val identity = getIdentityKeyPair() ?: return@withContext false
            val signingSecret = repository.getSigningSecretKey() ?: return@withContext false
            val signingPublic = repository.getSigningPublicKey() ?: return@withContext false
            val myUsername = AppSettings.getUsername(appContext) ?: userId.take(8)
            val mintedSecret = MailboxToken.newPairSecret()

            // Already have a real Contact row as of the call above, so this
            // binds immediately — no "pending, whoever claims it" ambiguity
            // the way a freshly-displayed QR secret has.
            repository.bindRelayRecvSecret(request.senderUserId, mintedSecret)

            request.directAddress
                ?.takeIf { hint ->
                    com.securemessenger.core.net.LanAddress.parse(hint, LOCAL_RELAY_PORT)
                        ?.let { (host, p) -> p != boundPort || !com.securemessenger.core.net.LanAddress.isOwnAddress(host) }
                        ?: false
                }
                ?.let { AppSettings.setDirectAddress(appContext, request.senderUserId, it) }

            val inner = com.securemessenger.core.net.DirectoryProtocol.buildSelfIntroduction(
                senderUserId = userId,
                senderUsername = myUsername,
                senderIdentityPublicKey = identity.publicKey,
                senderSigningPublicKey = signingPublic,
                addresseeIdentityPublicKey = senderIdentityPublicKey,
                pairSecret = mintedSecret,
                directAddress = myDirectAddress(),
                timestampMillis = System.currentTimeMillis(),
                sign = { payload -> LibsodiumWrapper.signDetached(payload, signingSecret) }
            )
            val outer = sealedEnvelope(
                com.securemessenger.core.net.Envelopes.TYPE_INTRO_ACCEPT, inner, senderIdentityPublicKey
            )
            val blob = B64.encode(outer.toString().toByteArray(Charsets.UTF_8))
            // The Contact is already real regardless of whether this deposit
            // succeeds — worst case they never learn we accepted and their
            // own outgoing request just stays "pending" until they retry.
            client.depositIntroduction(senderIdentityPublicKey, blob)

            refreshTokenMap()
            refreshRelaySubscriptions()
            repository.deleteIncomingConnectionRequest(senderIdentityPublicKeyHex)
            true
        } catch (e: Exception) {
            Log.e(TAG, "acceptConnectionRequest failed", e)
            false
        }
    }

    /** Discard a pending request with no reply of any kind — indistinguishable, from the sender's side, from "hasn't checked yet". */
    suspend fun rejectConnectionRequest(senderIdentityPublicKeyHex: String) = withContext(Dispatchers.IO) {
        repository.deleteIncomingConnectionRequest(senderIdentityPublicKeyHex)
    }

    private suspend fun getIdentityKeyPair(): SignalProtocol.IdentityKeyPair? {
        return repository.getIdentityKeyPair()
    }

    private suspend fun getSignedPreKey(): SignalProtocol.PreKeyPair? {
        return repository.getSignedPreKeyPair()
    }

    private suspend fun getOneTimePreKeys(): List<SignalProtocol.PreKeyPair> {
        return repository.getUnusedPreKeys().mapNotNull { bundle ->
            bundle.secretKeyEncrypted?.let { encrypted ->
                val secret = com.securemessenger.app.crypto.AndroidKeyStoreManager.decryptWithMasterKey(encrypted)
                SignalProtocol.PreKeyPair(bundle.preKeyId, bundle.publicKey, secret)
            }
        }
    }

    private suspend fun getContactIdentityKey(contactId: String): ByteArray? {
        return repository.getContact(contactId)?.publicKey
    }
}

/**
 * A plain-language answer to "can I actually reach this contact, and if not,
 * what would fix it?" — see [SecureMessagingClient.transportStatus].
 */
data class TransportStatus(
    /** A direct socket to them is open right now. */
    val isConnected: Boolean,
    /** Their discovery token is currently visible on this network (mDNS worked). */
    val isDiscovered: Boolean,
    /** "host:port" we can dial even when mDNS finds nothing, or null if we've never had one. */
    val rememberedAddress: String?,
    /** Relaying is on and we hold an outbound pair secret for them — so off-LAN delivery is possible. */
    val hasRelayPath: Boolean
) {
    /** True when there is no way at all to get a message to them; the outbox will queue forever. */
    val hasNoRouteAtAll: Boolean
        get() = !isConnected && !isDiscovered && rememberedAddress == null && !hasRelayPath

    /** A short Arabic description of the current situation, suitable for showing directly. */
    fun describe(): String = when {
        isConnected -> "متصل مباشرة على الشبكة المحلية"
        isDiscovered -> "ظاهر على الشبكة المحلية"
        rememberedAddress != null && hasRelayPath -> "غير ظاهر الآن — سيُجرّب العنوان المحفوظ ثم الوسيط"
        rememberedAddress != null -> "غير ظاهر الآن — سيُجرّب العنوان المحفوظ ($rememberedAddress)"
        hasRelayPath -> "غير ظاهر على الشبكة — التسليم عبر الوسيط الأعمى (قد يتأخر)"
        else -> "لا يوجد أي مسار لهذه الجهة: لم تُكتشف على الشبكة، ولا عنوان محفوظ، ولا قناة وسيط. " +
            "أعيدا الاقتران بمسح رمز QR كامل من الطرفين، أو أدخل عنوان جهازه يدوياً."
    }
}

sealed class ConnectionState {
    object Connecting : ConnectionState()
    object Connected : ConnectionState()
    object Disconnected : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

data class MessageReceived(
    val senderId: String,
    val plaintext: ByteArray,
    val senderIdentityKey: ByteArray? = null,
    val ttlSeconds: Int? = null,
    val messageId: String? = null
)

data class PrekeyBundle(
    val identityKey: ByteArray,
    val signedPreKey: ByteArray,
    val signingPublicKey: ByteArray? = null,
    val oneTimePreKey: ByteArray?,
    val oneTimePreKeyId: Int? = null,
    val mlkemPublicKey: ByteArray? = null
)
