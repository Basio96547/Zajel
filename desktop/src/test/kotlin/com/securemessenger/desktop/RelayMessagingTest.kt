package com.securemessenger.desktop

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import com.securemessenger.core.CoreLogger
import com.securemessenger.core.Platform
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val LOCAL_RELAY_URL = "http://127.0.0.1:8787"

/**
 * Two real clients, no sockets between them at all — everything routes through
 * the same blind-mailbox Cloudflare Worker a phone and a desktop actually use
 * when they aren't on the same network.
 *
 * [LoopbackMessagingTest] proves the desktop client's own orchestration works;
 * it does that over a direct WebSocket, which is also the path most likely to
 * win a race against the relay when two clients happen to share a machine or a
 * LAN. This test removes that race on purpose — neither side is ever given the
 * other's address — so a passing run means the message actually crossed
 * `relay/`'s `/d` and `/f` endpoints: real deposit, real poll, real blind
 * fetch, and back. Watching `relay`'s own `wrangler dev` console while this
 * runs shows those two routes being hit directly.
 *
 * Requires a local relay: `cd relay && npm run dev` (default port 8787,
 * no database or extra setup). Skipped, not failed, if nothing answers there —
 * this is a deliberate opt-in check against real infrastructure, the same
 * posture [com.securemessenger.app.network.IntroductionRoundtripTest] takes
 * toward the directory service.
 *
 * Timeouts are much longer than [LoopbackMessagingTest]'s: [DesktopRelayClient]
 * jitters both the deposit (up to 8s, so the relay can't correlate a deposit
 * time with a fetch time) and the poll interval (6-14s), so a one-way trip can
 * legitimately take over 20 seconds, and this test makes two of them.
 *
 * The QR payload ([DesktopMessagingClient.myQrPayload]) carries an `"a"` field
 * with the advertiser's own LAN address — this is a P2P-first client, and two
 * instances on one machine (or one LAN) will otherwise find and use that
 * address directly, which is faster and never touches the relay at all. A
 * first version of this test skipped that field entirely and, without
 * noticing, ended up re-testing [LoopbackMessagingTest]'s path — the relay's
 * own request log showed nothing but this test's own health check. `stripAddress`
 * below removes `"a"` before the payload ever reaches the peer, so pairing
 * carries no usable local address and the relay is the only path either side
 * has left. [describeRoute] is asserted on for the same reason: not just that
 * the message arrived, but that it arrived by the intended route.
 */
class RelayMessagingTest {

    @Before
    fun installPlatform() {
        Platform.installSodium(LazySodiumJava(SodiumJava()))
        Platform.installLogger(object : CoreLogger {
            override fun debug(tag: String, message: String) = println("[D] $tag: $message")
            override fun warn(tag: String, message: String, error: Throwable?) = println("[W] $tag: $message")
            override fun error(tag: String, message: String, error: Throwable?) = println("[E] $tag: $message")
        })
    }

    @Before
    fun ensureLocalRelayIsRunning() {
        val reachable = try {
            OkHttpClient().newCall(
                Request.Builder().url("$LOCAL_RELAY_URL/f").post(ByteArray(0).toRequestBody(null)).build()
            ).execute().use { true }
        } catch (e: Exception) {
            false
        }
        assumeTrue(
            "no local relay answering on $LOCAL_RELAY_URL — start one with `cd relay && npm run dev` first",
            reachable
        )
    }

    private fun newStore(name: String): DesktopStore {
        val dir = Files.createTempDirectory("sm-relay-test").toFile()
        val store = DesktopStore(File(dir, "store.dat"))
        store.create("a-test-passphrase".toCharArray(), name)
        return store
    }

    /** Drop the advertised LAN address so pairing leaves the relay as the only route. */
    private fun stripAddress(payload: String): String =
        JSONObject(payload).apply { remove("a") }.toString()

    @Test
    fun `two clients pair and hold a conversation entirely through the relay`() {
        val storeA = newStore("جهاز-أ")
        val storeB = newStore("جهاز-ب")

        val gotAtB = CountDownLatch(1)
        val gotAtA = CountDownLatch(1)
        var textAtB: String? = null
        var fromAtB: String? = null
        var textAtA: String? = null

        val clientA = DesktopMessagingClient(
            storeA, LOCAL_RELAY_URL,
            onMessage = { _, text, _ -> textAtA = text; gotAtA.countDown() },
            onStateChanged = {}
        )
        val clientB = DesktopMessagingClient(
            storeB, LOCAL_RELAY_URL,
            onMessage = { from, text, _ -> fromAtB = from; textAtB = text; gotAtB.countDown() },
            onStateChanged = {}
        )

        try {
            clientA.start()
            clientB.start()

            // Mutual pairing over the QR payload with the address stripped —
            // see the class doc. Only the mailbox pair secret survives, which
            // is exactly what two phones on different networks would have.
            val idB = clientA.pairFromPayload(stripAddress(clientB.myQrPayload())).getOrThrow()
            val idA = clientB.pairFromPayload(stripAddress(clientA.myQrPayload())).getOrThrow()
            assertEquals(storeB.userId, idB)
            assertEquals(storeA.userId, idA)
            println("[test] paired: A=${storeA.userId.take(8)} B=${storeB.userId.take(8)}")

            val routeAtoB = clientA.describeRoute(idB)
            val routeBtoA = clientB.describeRoute(idA)
            println("[test] route A->B: $routeAtoB")
            println("[test] route B->A: $routeBtoA")
            assertTrue("A->B should have no local route, only the relay: $routeAtoB", routeAtoB.contains("الوسيط"))
            assertTrue("B->A should have no local route, only the relay: $routeBtoA", routeBtoA.contains("الوسيط"))

            println("[test] --- A sends over the relay ---")
            val sent = runBlocking { clientA.sendMessage(idB, "مرحباً عبر المرحل") }
            assertTrue("send reported failure", sent)

            assertTrue(
                "message never arrived at B — check the relay dev server log for /d and /f hits",
                gotAtB.await(90, TimeUnit.SECONDS)
            )
            assertEquals("مرحباً عبر المرحل", textAtB)
            assertEquals(storeA.userId, fromAtB)
            println("[test] B received: \"$textAtB\" from ${fromAtB?.take(8)}")

            val clearedAtA = waitFor(60_000) { storeA.allOutbox().isEmpty() }
            assertTrue("A's outbox was never cleared by an authenticated ack from B", clearedAtA)
            val ownCopy = storeA.messagesWith(idB).firstOrNull { it.outgoing }
            assertNotNull(ownCopy)
            assertTrue("outgoing message never marked delivered", ownCopy!!.delivered)
            println("[test] A's copy confirmed delivered (ack round-tripped through the relay)")

            println("[test] --- B replies over the relay, on the session A opened ---")
            val replied = runBlocking { clientB.sendMessage(idA, "وصلت، هذا ردي") }
            assertTrue("reply reported failure", replied)

            assertTrue(
                "reply never arrived back at A",
                gotAtA.await(90, TimeUnit.SECONDS)
            )
            assertEquals("وصلت، هذا ردي", textAtA)
            println("[test] A received the reply: \"$textAtA\"")

            val clearedAtB = waitFor(60_000) { storeB.allOutbox().isEmpty() }
            assertTrue("B's outbox was never cleared by an authenticated ack from A", clearedAtB)
            println("[test] conversation complete — both directions confirmed delivered over the relay")
        } finally {
            clientA.stop()
            clientB.stop()
        }
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(200)
        }
        return condition()
    }
}
