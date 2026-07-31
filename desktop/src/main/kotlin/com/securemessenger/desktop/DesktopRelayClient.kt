package com.securemessenger.desktop

import com.securemessenger.core.Platform
import com.securemessenger.core.crypto.MailboxToken
import com.securemessenger.core.net.RelayBlob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "DesktopRelay"

/**
 * The blind-mailbox fallback for contacts who aren't on this LAN.
 *
 * Identical wire behaviour to the Android client — the blob format, the mailbox
 * derivation and the padding all come from :core — so a message deposited by a
 * phone is collectable here and vice versa. What differs is the schedule: a
 * desktop has no battery to protect, so it polls on a steadier cadence.
 *
 * What the relay sees is unchanged and worth restating: the IP address and the
 * time of each request. Nothing else — no identity, no envelope, no length, no
 * conversation graph.
 */
class DesktopRelayClient(
    private val store: DesktopStore,
    private val baseUrl: String,
    private val http: OkHttpClient,
    private val onEnvelope: suspend (envelopeJson: String, arrivedOnPendingSecretHex: String?) -> Unit
) {
    private data class Subscription(val secret: ByteArray, val pendingSecretHex: String?)

    private class Partial(val count: Int) {
        val chunks = arrayOfNulls<ByteArray>(count)
        val startedAt = System.currentTimeMillis()
        var received = 0
    }

    private val subscriptions = ConcurrentHashMap<String, Subscription>()
    private val partials = ConcurrentHashMap<String, Partial>()
    private val random = java.security.SecureRandom()
    private var pollJob: Job? = null
    private lateinit var scope: CoroutineScope

    fun start(scope: CoroutineScope) {
        this.scope = scope
        refreshSubscriptions()
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(randomBetween(POLL_MIN_MS, POLL_MAX_MS))
                try {
                    refreshSubscriptions()
                    prunePartials()
                    pollOnce()
                } catch (e: Exception) {
                    Platform.log.warn(TAG, "poll failed: ${e.message}", e)
                }
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * Recompute the mailboxes we listen on: one per contact whose inbound
     * channel is bound, plus every secret we've minted that nobody has claimed
     * yet — a displayed QR doesn't say who photographed it, so all outstanding
     * ones have to be watched until one of them receives something.
     */
    fun refreshSubscriptions() {
        val next = HashMap<String, Subscription>()
        for (contact in store.allContacts()) {
            val recv = contact.relayRecvSecret ?: continue
            MailboxToken.inboundIds(recv).forEach { next[it] = Subscription(recv, null) }
        }
        for (hex in store.pendingSecrets()) {
            val secret = MailboxToken.pairSecretFromHex(hex) ?: continue
            MailboxToken.inboundIds(secret).forEach { id ->
                if (!next.containsKey(id)) next[id] = Subscription(secret, hex)
            }
        }
        subscriptions.keys.retainAll(next.keys)
        subscriptions.putAll(next)
    }

    /**
     * Deposit an envelope for [contactId].
     *
     * Returns false when there is no relay path at all — no outbound pair
     * secret, which is the case for a contact paired from a QR with the secret
     * stripped, or paired in only one direction. The caller must be able to tell
     * that apart from a deposit that merely failed, because it never fixes
     * itself: it needs the two devices to pair again.
     */
    suspend fun enqueue(contactId: String, envelopeJson: String): Boolean {
        if (baseUrl.isBlank()) return false
        val secret = store.contact(contactId)?.relaySendSecret ?: run {
            Platform.log.warn(TAG, "no outbound pair secret for $contactId — no relay path exists")
            return false
        }
        scope.launch {
            try {
                // Break the "A deposited at T, B collected at T+ε" correlation
                // the relay could otherwise draw between two mailboxes.
                delay(randomBetween(0, MAX_SEND_JITTER_MS))
                val group = java.lang.Long.toHexString(random.nextLong())
                depositBlobs(
                    MailboxToken.outboundId(secret),
                    RelayBlob.seal(secret, envelopeJson.toByteArray(Charsets.UTF_8), group)
                )
            } catch (e: Exception) {
                Platform.log.warn(TAG, "relay deposit failed: ${e.message}", e)
            }
        }
        return true
    }

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
                // Each blob gets its own coroutine: a send waiting on a prekey
                // bundle holds that contact's lock, so handling a batch in
                // sequence could block the very reply it is waiting for.
                scope.launch {
                    runCatching { handleBlob(mailboxId, body) }
                        .onFailure { Platform.log.warn(TAG, "discarding undecryptable blob", it) }
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

        val partial = partials.getOrPut(chunk.group) { Partial(chunk.count) }
        if (partial.count != chunk.count) return
        val complete = synchronized(partial) {
            if (partial.chunks[chunk.index] == null) {
                partial.chunks[chunk.index] = chunk.data
                partial.received++
            }
            partial.received == chunk.count
        }
        // Only the coroutine that completed the set delivers it, so two chunks
        // arriving at once can't produce the message twice.
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

    private fun prunePartials() {
        val cutoff = System.currentTimeMillis() - PARTIAL_TTL_MS
        partials.entries.removeAll { it.value.startedAt < cutoff }
    }

    private suspend fun depositBlobs(mailboxId: String, blobs: List<String>) {
        for (batch in blobs.chunked(MAX_DEPOSIT_ITEMS)) {
            val items = JSONArray()
            batch.forEach { items.put(JSONObject().put("m", mailboxId).put("b", it)) }
            post("/d", JSONObject().put("items", items).toString())
        }
    }

    private fun post(path: String, body: String): String? = try {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Platform.log.warn(TAG, "relay $path returned ${response.code}")
                null
            } else {
                response.body?.string() ?: ""
            }
        }
    } catch (e: Exception) {
        Platform.log.warn(TAG, "relay $path unreachable: ${e.message}")
        null
    }

    private fun randomBetween(minMs: Long, maxMs: Long): Long =
        if (maxMs <= minMs) minMs else minMs + (random.nextDouble() * (maxMs - minMs)).toLong()

    private companion object {
        const val POLL_MIN_MS = 6_000L
        const val POLL_MAX_MS = 14_000L
        const val MAX_SEND_JITTER_MS = 8_000L

        /** Must match MAX_FETCH_IDS / MAX_DEPOSIT_ITEMS in the relay Worker. */
        const val MAX_FETCH_IDS = 48
        const val MAX_DEPOSIT_ITEMS = 32

        const val PARTIAL_TTL_MS = 10 * 60 * 1000L
    }
}
