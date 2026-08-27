package com.securemessenger.app.network

import com.securemessenger.app.BuildConfig
import com.securemessenger.core.crypto.MailboxToken
import com.securemessenger.core.net.RelayBlob
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * End-to-end verification of the blind mailbox path, run on a real device
 * against the **actually deployed relay** — not a mock. What it proves:
 *
 *  - a message survives seal → deposit → collect → open unchanged,
 *  - a message larger than one chunk reassembles correctly,
 *  - the relay's read is destructive (nothing is left behind for a second reader),
 *  - a blob is worthless to anyone holding a different pair secret,
 *  - every blob on the wire is padded to a size class, so short messages don't
 *    leak their length.
 *
 * Skipped automatically on a build with no relay URL compiled in.
 */
class RelayRoundtripTest {

    private val relayUrl = BuildConfig.RELAY_URL.trimEnd('/')
    private val http = OkHttpClient()
    private val jsonType = "application/json".toMediaType()

    private fun post(path: String, body: String): String? {
        val request = Request.Builder().url("$relayUrl$path").post(body.toRequestBody(jsonType)).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string() ?: ""
        }
    }

    private fun deposit(mailboxId: String, blobs: List<String>) {
        val items = JSONArray()
        blobs.forEach { items.put(JSONObject().put("m", mailboxId).put("b", it)) }
        assertNotNull("deposit rejected by relay", post("/d", JSONObject().put("items", items).toString()))
    }

    private fun collect(mailboxId: String): List<String> {
        val raw = post("/f", JSONObject().put("m", JSONArray(listOf(mailboxId))).toString())
        assertNotNull("fetch rejected by relay", raw)
        val items = JSONObject(raw!!).getJSONArray("items")
        return (0 until items.length()).map { items.getJSONObject(it).getString("b") }
    }

    /** Reassemble collected blobs the way RelayClient does. */
    private fun reassemble(secret: ByteArray, blobs: List<String>): ByteArray {
        val chunks = blobs.mapNotNull { RelayBlob.open(secret, it) }.sortedBy { it.index }
        assertTrue("no chunk opened", chunks.isNotEmpty())
        assertEquals("missing chunks", chunks.first().count, chunks.size)
        return chunks.fold(ByteArray(0)) { acc, c -> acc + c.data }
    }

    @Test
    fun shortMessage_roundTripsThroughTheRealRelay() {
        assumeTrue("no relay compiled into this build", relayUrl.isNotBlank())

        val secret = MailboxToken.newPairSecret()
        val mailbox = MailboxToken.outboundId(secret)
        val envelope = """{"type":"message","id":"round-trip","sealed":"whatever"}"""

        deposit(mailbox, RelayBlob.seal(secret, envelope.toByteArray(Charsets.UTF_8), group = "g1"))

        val collected = collect(mailbox)
        assertEquals(1, collected.size)
        assertEquals(envelope, String(reassemble(secret, collected), Charsets.UTF_8))
    }

    @Test
    fun collectIsDestructive_secondReadFindsNothing() {
        assumeTrue("no relay compiled into this build", relayUrl.isNotBlank())

        val secret = MailboxToken.newPairSecret()
        val mailbox = MailboxToken.outboundId(secret)
        deposit(mailbox, RelayBlob.seal(secret, "once".toByteArray(Charsets.UTF_8), group = "g2"))

        assertEquals(1, collect(mailbox).size)
        // Nothing is retained after delivery — there is no copy left to subpoena.
        assertEquals(0, collect(mailbox).size)
    }

    @Test
    fun largeMessage_splitsAndReassembles() {
        assumeTrue("no relay compiled into this build", relayUrl.isNotBlank())

        val secret = MailboxToken.newPairSecret()
        val mailbox = MailboxToken.outboundId(secret)
        // Three chunks' worth, with a recognisable pattern so a mis-ordered
        // reassembly can't accidentally pass.
        val payload = ByteArray(RelayBlob.CHUNK_BYTES * 2 + 1234) { (it % 251).toByte() }

        val blobs = RelayBlob.seal(secret, payload, group = "g3")
        assertEquals(3, blobs.size)
        deposit(mailbox, blobs)

        val collected = collect(mailbox)
        assertEquals(3, collected.size)
        val reassembled = reassemble(secret, collected)
        assertEquals(payload.size, reassembled.size)
        assertTrue("payload corrupted in transit", payload.contentEquals(reassembled))
    }

    @Test
    fun blobIsUselessWithoutTheRightPairSecret() {
        assumeTrue("no relay compiled into this build", relayUrl.isNotBlank())

        val secret = MailboxToken.newPairSecret()
        val impostor = MailboxToken.newPairSecret()
        val blob = RelayBlob.seal(secret, "secret text".toByteArray(Charsets.UTF_8), group = "g4").single()

        assertNotNull("the real secret should open it", RelayBlob.open(secret, blob))
        assertNull("a different pair secret must not open it", RelayBlob.open(impostor, blob))
    }

    @Test
    fun blobLengthCollapsesIntoSizeClasses_soLengthDoesNotLeak() {
        val secret = MailboxToken.newPairSecret()
        fun blobLength(len: Int) =
            RelayBlob.seal(secret, ByteArray(len) { 'x'.code.toByte() }, group = "g5").single().length

        // Payloads that fit the same class are indistinguishable on the wire.
        // Note the ceiling is ~740 raw bytes, not 1024: base64 inflates the
        // payload by 4/3 before the class is chosen.
        assertEquals(blobLength(1), blobLength(40))
        assertEquals(blobLength(40), blobLength(200))
        assertEquals(blobLength(200), blobLength(700))

        // Across a wide sweep of real lengths, the relay only ever sees a
        // handful of distinct sizes — that collapse is the actual property, not
        // one single size for everything.
        val observed = (1..3000 step 23).map { blobLength(it) }.distinct()
        assertTrue(
            "expected a few size classes across 130 distinct payload lengths, saw ${observed.size}",
            observed.size <= 4
        )
    }

    @Test
    fun mailboxIdRotatesAndToleratesClockSkew() {
        val secret = MailboxToken.newPairSecret()
        val now = System.currentTimeMillis()

        val inbound = MailboxToken.inboundIds(secret, now)
        // ±2 windows (5 total), not ±1 — see MailboxToken's class doc for why
        // ±1 doesn't actually cover the relay's 48h retention in every phase.
        assertEquals("should listen on 2 windows either side of the current one", 5, inbound.size)
        assertEquals("all five windows must be distinct ids", 5, inbound.distinct().size)
        assertTrue("the id we send to must be one we also listen on", inbound.contains(MailboxToken.outboundId(secret, now)))

        // A different window is an unrelated-looking id, which is the whole point.
        val bucket = MailboxToken.currentBucket(now)
        assertTrue(MailboxToken.mailboxId(secret, bucket) != MailboxToken.mailboxId(secret, bucket + 1))
        // And a different secret never collides with ours.
        assertTrue(MailboxToken.mailboxId(secret, bucket) != MailboxToken.mailboxId(MailboxToken.newPairSecret(), bucket))
    }
}
