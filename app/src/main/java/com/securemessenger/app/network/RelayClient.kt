package com.securemessenger.app.network

import android.content.Context
import android.util.Base64
import android.util.Log
import com.securemessenger.core.crypto.LibsodiumWrapper
import com.securemessenger.core.crypto.MailboxToken
import com.securemessenger.core.crypto.MessagePadding
import com.securemessenger.core.net.RelayBlob
import com.securemessenger.app.data.repository.SecureRepository
import com.securemessenger.app.security.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "RelayClient"

/**
 * RelayClient — the fallback path for contacts who aren't on the local network.
 *
 * Everything here is built so the relay learns as little as physically possible
 * while still being able to do its one job:
 *
 *  - **It never sees an identity.** Messages are addressed to a rotating
 *    16-byte mailbox id derived from a secret that only the two devices hold
 *    (see [MailboxToken]). No user id, no public key, no account.
 *  - **It never sees an envelope.** The app's outer envelope is *not* encrypted
 *    — only the payload sealed inside it is — so it would otherwise hand the
 *    relay the message type, its routing id and the recipient's user id in
 *    clear. Every blob is therefore re-encrypted under a key derived from the
 *    same pair secret before it leaves.
 *  - **It never sees a length.** Blobs are padded up to a multiple of
 *    [SIZE_CLASS_BYTES], so all short messages look identical on the wire.
 *  - **It never sees a conversation.** Large messages are split into
 *    same-sized blobs; cover traffic (when enabled) deposits real, correctly
 *    addressed, indistinguishable dummies; and real sends are delayed by a
 *    random jitter so a deposit can't be lined up against a collection.
 *
 * What it still sees, and no amount of encryption fixes: the IP address a
 * request comes from, and the moment it arrives.
 *
 * Delivery is by polling rather than a held-open socket, deliberately. A
 * persistent subscription would tell the relay "these mailbox ids all belong to
 * one device" for as long as it stayed open; a poll only reveals that for the
 * instant it takes, and the poll happens on its own randomized schedule whether
 * or not there is anything to collect — so the timing of a poll says nothing
 * about the timing of a message.
 */
class RelayClient(
    context: Context,
    private val repository: SecureRepository,
    private val baseUrl: String,
    private val http: OkHttpClient,
    private val onEnvelope: suspend (envelopeJson: String, arrivedOnPendingSecretHex: String?) -> Unit
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val random = java.security.SecureRandom()

    private var pollJob: Job? = null
    private var coverJob: Job? = null

    /** mailbox id we listen on -> the secret that decrypts it (and, if it isn't bound to a contact yet, which minted secret it was). */
    private val subscriptions = ConcurrentHashMap<String, Subscription>()

    /** group id -> the chunks of a split message seen so far. */
    private val partials = ConcurrentHashMap<String, Partial>()

    private data class Subscription(val secret: ByteArray, val pendingSecretHex: String?)

    private class Partial(val count: Int) {
        val chunks = arrayOfNulls<ByteArray>(count)
        val startedAt = System.currentTimeMillis()
        var received = 0
    }

    val isEnabled: Boolean get() = baseUrl.isNotBlank() && AppSettings.isRelayEnabled(appContext)

    fun start() {
        if (!isEnabled) return
        stop()
        pollJob = scope.launch {
            while (isActive) {
                delay(randomBetween(POLL_MIN_MS, POLL_MAX_MS))
                try {
                    refreshSubscriptions()
                    prunePartials()
                    pollOnce()
                } catch (e: Exception) {
                    Log.w(TAG, "poll failed", e)
                }
            }
        }
        coverJob = scope.launch {
            while (isActive) {
                delay(randomBetween(COVER_MIN_MS, COVER_MAX_MS))
                if (!AppSettings.isCoverTrafficEnabled(appContext)) continue
                try {
                    sendCoverBlob()
                } catch (e: Exception) {
                    Log.w(TAG, "cover traffic failed", e)
                }
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        coverJob?.cancel()
        pollJob = null
        coverJob = null
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    /**
     * Recompute which mailboxes we listen on. Two sources: contacts whose
     * inbound channel is already bound, and secrets we have minted but nobody
     * has used yet — we have to listen on all of the latter because a displayed
     * QR doesn't tell us who photographed it.
     */
    suspend fun refreshSubscriptions() {
        val next = HashMap<String, Subscription>()
        for (contact in repository.getAllContactsOnce()) {
            val recv = repository.getRelayRecvSecret(contact.id) ?: continue
            MailboxToken.inboundIds(recv).forEach { next[it] = Subscription(recv, null) }
        }
        for (hex in AppSettings.pendingPairSecrets(appContext)) {
            val secret = MailboxToken.pairSecretFromHex(hex) ?: continue
            // A bound contact's id wins over a still-pending one if they ever
            // collide, so the pending pass runs second only for ids nobody claimed.
            MailboxToken.inboundIds(secret).forEach { id ->
                if (!next.containsKey(id)) next[id] = Subscription(secret, hex)
            }
        }
        subscriptions.keys.retainAll(next.keys)
        subscriptions.putAll(next)
    }

    /**
     * Hand an envelope to the relay for a contact we can't reach directly.
     *
     * Returns false when there is **no relay path at all** for this contact —
     * relaying switched off, no relay URL compiled in, or (the common one) no
     * outbound pair secret, which is the case for anyone paired before relay
     * support, paired from a QR whose secret was deliberately stripped, or
     * paired in only one direction. The caller has to know the difference,
     * because "no path" is permanent until the two devices re-pair, whereas a
     * failed deposit is just a retry away.
     *
     * This used to return true unconditionally, and the missing-secret case
     * exited from *inside* the launched coroutine — so the send path reported
     * success, the UI drew a delivered-looking message, and the envelope went
     * nowhere, forever, with nothing logged. That is precisely the "الرسالة
     * تُرسل ولا تصل" symptom.
     *
     * Beyond that check the deposit stays fire-and-forget: it may sit behind a
     * deliberate random delay, and the durable outbox is what guarantees
     * eventual delivery, so blocking the send path on the HTTP round trip
     * would buy nothing. A caller that DOES need to know whether the deposit
     * actually landed — the periodic retry sweep, which is already off the
     * interactive path — should call [enqueueAwait] instead.
     */
    suspend fun enqueue(contactId: String, envelopeJson: String): Boolean {
        if (!isEnabled) return false
        if (repository.getRelaySendSecret(contactId) == null) {
            Log.w(TAG, "no outbound pair secret for $contactId — this contact has no relay path")
            return false
        }
        scope.launch { enqueueAwait(contactId, envelopeJson) }
        return true
    }

    /**
     * Same as [enqueue], but waits for the HTTP deposit to actually finish and
     * reports whether it truly succeeded, instead of firing it in the
     * background and reporting only "was queued." Deliberately not what the
     * interactive send path uses (see [enqueue]) — the jitter delay plus the
     * round trip would make sending feel stuck. The periodic retry sweep
     * ([SecureMessagingClient.retryOutbox]) already runs off the UI, though,
     * and needs the real outcome: it only advances its anti-pileup floor after
     * a deposit that actually landed, never one that failed before ever
     * leaving the device (which would otherwise be retried no sooner than
     * RELAY_RETRY_FLOOR_MS later for no reason — nothing was piled up).
     */
    suspend fun enqueueAwait(contactId: String, envelopeJson: String): Boolean {
        if (!isEnabled) return false
        val secret = repository.getRelaySendSecret(contactId) ?: run {
            Log.w(TAG, "no outbound pair secret for $contactId — this contact has no relay path")
            return false
        }
        return try {
            // Break the "A deposited at T, B collected at T+ε" correlation
            // the relay could otherwise draw between two mailboxes.
            if (AppSettings.isCoverTrafficEnabled(appContext)) {
                delay(randomBetween(0, MAX_SEND_JITTER_MS))
            }
            depositBlobs(
                MailboxToken.outboundId(secret),
                sealIntoBlobs(secret, envelopeJson.toByteArray(Charsets.UTF_8))
            )
        } catch (e: Exception) {
            Log.w(TAG, "relay deposit failed", e)
            false
        }
    }

    // ---- receiving ----

    private suspend fun pollOnce() {
        val ids = subscriptions.keys.toList()
        if (ids.isEmpty()) return
        for (batch in ids.chunked(MAX_FETCH_IDS)) {
            val response = post("/f", JSONObject().put("m", JSONArray(batch)).toString()) ?: continue
            val items = JSONObject(response).optJSONArray("items") ?: continue
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val mailboxId = item.optString("m")
                val body = item.optString("b")
                if (mailboxId.isBlank() || body.isBlank()) continue
                // Each blob is handled independently, exactly as the local
                // socket path already does per frame. Handling them in
                // sequence would let one slow handler stall the rest of the
                // batch — and that is not hypothetical: a send waiting on a
                // prekey bundle holds that contact's lock, so a chat message
                // ahead of the bundle in the same batch would block the very
                // reply the send is waiting for, until it timed out.
                scope.launch {
                    try {
                        handleBlob(mailboxId, body)
                    } catch (e: Exception) {
                        // A blob we can't open is not worth a retry — it is
                        // either corrupt or someone guessing at mailbox ids.
                        Log.w(TAG, "discarding undecryptable blob", e)
                    }
                }
            }
        }
    }

    private suspend fun handleBlob(mailboxId: String, bodyBase64: String) {
        val subscription = subscriptions[mailboxId] ?: return
        val chunk = RelayBlob.open(subscription.secret, bodyBase64) ?: return

        if (chunk.count == 1) {
            onEnvelope(String(chunk.data, Charsets.UTF_8), subscription.pendingSecretHex)
            return
        }

        // Reject a group whose declared chunk count alone implies a payload
        // far beyond any legitimate media message, before ever accumulating
        // its chunks. The real size cap (SecureRepository.MAX_MEDIA_BYTES) is
        // only checked once the full envelope has been reassembled,
        // JSON-parsed and base64-decoded — by then a malicious peer has
        // already made this device do several full-payload-sized buffer
        // copies for nothing. The x8 multiplier is headroom for how much
        // bigger a message's wire form is than its raw content — sealed
        // envelope base64 stacked with this relay layer's own base64, plus
        // ciphertext/JSON overhead — generous enough that a legitimate
        // MAX_MEDIA_BYTES attachment is never affected, while anything
        // aiming at RelayBlob's own much larger MAX_CHUNKS ceiling is caught
        // immediately instead of after the fact.
        val maxPlausibleGroupBytes = SecureRepository.MAX_MEDIA_BYTES.toLong() * 8
        if (chunk.count.toLong() * RelayBlob.CHUNK_BYTES > maxPlausibleGroupBytes) {
            Log.w(TAG, "rejecting implausibly large chunk group (count=${chunk.count}) before accumulating it")
            return
        }

        // Cap how many distinct in-progress groups get tracked at once.
        // RelayBlob.open already bounds any ONE group's chunk count
        // (MAX_CHUNKS), but nothing previously bounded the NUMBER of
        // different groups — a peer we already share a pair secret with (a
        // paired contact gone malicious, or anyone still holding an
        // unclaimed "pending" QR secret) could deposit endless chunks each
        // claiming a fresh group id and a count >= 2 they never finish
        // sending, and only prunePartials() — a once-per-poll, 10-minute-TTL
        // sweep — would ever reclaim them. This is a hard backstop under
        // that sweep, not a replacement for it: a real multi-chunk message
        // already being tracked is never rejected, only a chunk that would
        // start a BRAND NEW group once the cap is reached — far more
        // simultaneous in-flight large messages than a personal messenger's
        // real traffic ever needs.
        if (partials.size >= MAX_PARTIAL_GROUPS && !partials.containsKey(chunk.group)) {
            Log.w(TAG, "dropping chunk for new group — $MAX_PARTIAL_GROUPS groups already in flight")
            return
        }

        val partial = partials.getOrPut(chunk.group) { Partial(chunk.count) }
        if (partial.count != chunk.count) return
        val complete = synchronized(partial) {
            if (partial.chunks[chunk.index] == null) {
                partial.chunks[chunk.index] = chunk.data
                partial.received++
            }
            partial.received == chunk.count
        }
        // Only the thread that completed the set delivers it, so two chunks
        // landing at once can't produce the message twice.
        if (!complete || partials.remove(chunk.group) == null) return

        val joined = ByteArray(partial.chunks.sumOf { it?.size ?: 0 })
        var offset = 0
        for (part in partial.chunks) {
            val bytes = part ?: return
            System.arraycopy(bytes, 0, joined, offset, bytes.size)
            offset += bytes.size
        }
        onEnvelope(String(joined, Charsets.UTF_8), subscription.pendingSecretHex)
    }

    /** Drop half-arrived messages whose remaining chunks are never coming. */
    private fun prunePartials() {
        val cutoff = System.currentTimeMillis() - PARTIAL_TTL_MS
        partials.entries.removeAll { it.value.startedAt < cutoff }
    }

    // ---- sending ----

    /** Wire format lives in [RelayBlob] — this just supplies a fresh group id per message. */
    private fun sealIntoBlobs(secret: ByteArray, payload: ByteArray, minSizeClasses: Int = 1): List<String> {
        val group = ByteArray(8).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        return RelayBlob.seal(secret, payload, group, minSizeClasses)
    }

    /** True only if every batch's deposit call actually got a response back — see [enqueueAwait]. */
    private suspend fun depositBlobs(mailboxId: String, blobs: List<String>): Boolean {
        var allSucceeded = true
        for (batch in blobs.chunked(MAX_DEPOSIT_ITEMS)) {
            val items = JSONArray()
            batch.forEach { items.put(JSONObject().put("m", mailboxId).put("b", it)) }
            if (post("/d", JSONObject().put("items", items).toString()) == null) allSucceeded = false
        }
        return allSucceeded
    }

    /**
     * A dummy message: real encryption, real padding, a real mailbox, a real
     * deposit — identical to a genuine message in every respect the relay can
     * observe. The recipient unwraps it, sees an envelope type it has no
     * handler for, and drops it.
     */
    private suspend fun sendCoverBlob() {
        val candidates = repository.getAllContactsOnce().filter { it.relaySendSecretEncrypted != null }
        if (candidates.isEmpty()) return
        val target = candidates[random.nextInt(candidates.size)]
        val secret = repository.getRelaySendSecret(target.id) ?: return
        // Vary the size across the same classes short real messages fall into,
        // so cover traffic doesn't become identifiable by being uniformly small.
        val sizeClasses = 1 + random.nextInt(COVER_MAX_SIZE_CLASSES)
        depositBlobs(
            MailboxToken.outboundId(secret),
            sealIntoBlobs(secret, NOISE_ENVELOPE.toByteArray(Charsets.UTF_8), minSizeClasses = sizeClasses)
        )
    }

    // ---- transport ----

    private suspend fun post(path: String, body: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl$path")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // Silently returning null here meant an unreachable or
                    // rejecting relay was indistinguishable from "nothing to
                    // collect" — a whole transport could be dead with not one
                    // line in logcat to say so.
                    Log.w(TAG, "relay $path returned ${response.code}")
                    null
                } else {
                    response.body?.string() ?: ""
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "relay $path unreachable: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun randomBetween(minMs: Long, maxMs: Long): Long =
        if (maxMs <= minMs) minMs else minMs + (random.nextDouble() * (maxMs - minMs)).toLong()

    companion object {
        /** The envelope a cover-traffic blob carries. No handler exists for this type, so the recipient discards it. */
        const val NOISE_ENVELOPE = """{"type":"noise"}"""

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        // Polling is frequent because the messenger only runs while it is
        // actually revealed on screen — there is no background service, so this
        // never drains the battery in the user's pocket.
        private const val POLL_MIN_MS = 6_000L
        private const val POLL_MAX_MS = 14_000L

        private const val COVER_MIN_MS = 25_000L
        private const val COVER_MAX_MS = 95_000L
        private const val COVER_MAX_SIZE_CLASSES = 3

        /** Longest a real send may be held back to decorrelate it from the recipient's collection. */
        private const val MAX_SEND_JITTER_MS = 8_000L

        /** Must match MAX_FETCH_IDS / MAX_DEPOSIT_ITEMS in the relay Worker. */
        private const val MAX_FETCH_IDS = 48
        private const val MAX_DEPOSIT_ITEMS = 32

        private const val PARTIAL_TTL_MS = 10 * 60 * 1000L

        /** Hard cap on distinct in-progress (incomplete) chunk groups tracked at once — see handleBlob. */
        private const val MAX_PARTIAL_GROUPS = 64
    }
}
