package com.securemessenger.desktop

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import com.securemessenger.core.CoreLogger
import com.securemessenger.core.Platform
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Two real clients, two real sockets, one real conversation.
 *
 * This is the test that actually answers "does the desktop build work" — not
 * whether the crypto primitives are self-consistent (they're shared with the
 * phone and already covered on-device), but whether the desktop client's own
 * orchestration gets a message from one identity to another: pair, challenge,
 * verify the signed bundle, run X3DH + ML-KEM, ratchet, seal, send over a real
 * WebSocket, decrypt, and authenticate the ack back.
 *
 * mDNS is deliberately not involved. Both sides are pointed at each other by
 * explicit address, which is precisely the fallback path that exists because
 * multicast discovery cannot be relied on — so this exercises the route a real
 * phone/PC pair is most likely to end up using.
 */
class LoopbackMessagingTest {

    @Before
    fun installPlatform() {
        Platform.installSodium(LazySodiumJava(SodiumJava()))
        Platform.installLogger(object : CoreLogger {
            override fun debug(tag: String, message: String) = println("[D] $tag: $message")
            override fun warn(tag: String, message: String, error: Throwable?) = println("[W] $tag: $message")
            override fun error(tag: String, message: String, error: Throwable?) = println("[E] $tag: $message")
        })
    }

    private fun newStore(name: String): DesktopStore {
        val dir = Files.createTempDirectory("sm-test").toFile()
        val store = DesktopStore(File(dir, "store.dat"))
        store.create("a-test-passphrase".toCharArray(), name)
        return store
    }

    @Test
    fun `two clients pair and exchange a message over a direct address`() {
        val storeA = newStore("جهاز-أ")
        val storeB = newStore("جهاز-ب")

        val received = CountDownLatch(1)
        var receivedText: String? = null
        var receivedFrom: String? = null

        val clientA = DesktopMessagingClient(storeA, relayUrl = "", onMessage = { _, _, _ -> }, onStateChanged = {})
        val clientB = DesktopMessagingClient(
            storeB,
            relayUrl = "",
            onMessage = { from, text, _ ->
                receivedFrom = from
                receivedText = text
                received.countDown()
            },
            onStateChanged = {}
        )

        try {
            clientA.start()
            clientB.start()

            // Mutual pairing, exactly as two humans scanning each other's codes.
            // One-way pairing is a real failure mode — it looks like it worked
            // and then only carries traffic in one direction — so the test does
            // both halves on purpose.
            val idB = clientA.pairFromPayload(clientB.myQrPayload()).getOrThrow()
            val idA = clientB.pairFromPayload(clientA.myQrPayload()).getOrThrow()
            assertEquals(storeB.userId, idB)
            assertEquals(storeA.userId, idA)

            // Point them at each other explicitly: both are on 127.0.0.1 in this
            // JVM, and the second one to start had to fall back to an
            // OS-assigned port, so the addresses are not interchangeable.
            clientA.setDirectAddress(idB, "127.0.0.1:${clientB.boundPort}")
            clientB.setDirectAddress(idA, "127.0.0.1:${clientA.boundPort}")

            val sent = runBlocking { clientA.sendMessage(idB, "مرحباً من الكمبيوتر") }
            assertTrue("send reported failure", sent)

            assertTrue(
                "message never arrived at the second client",
                received.await(30, TimeUnit.SECONDS)
            )
            assertEquals("مرحباً من الكمبيوتر", receivedText)
            assertEquals(storeA.userId, receivedFrom)

            // The ack has to come back and clear the outbox, otherwise the
            // message would sit "sending…" forever — the exact symptom this
            // whole round of work started from.
            val cleared = waitFor(15_000) { storeA.allOutbox().isEmpty() }
            assertTrue("outbox was never cleared by an authenticated ack", cleared)

            // And the sender's own copy must be marked delivered, so the UI can
            // distinguish "gone" from "queued".
            val ownCopy = storeA.messagesWith(idB).firstOrNull { it.outgoing }
            assertNotNull(ownCopy)
            assertTrue("outgoing message never marked delivered", ownCopy!!.delivered)
        } finally {
            clientA.stop()
            clientB.stop()
        }
    }

    @Test
    fun `a reply travels back on the same session`() {
        val storeA = newStore("أ")
        val storeB = newStore("ب")

        val gotAtB = CountDownLatch(1)
        val gotAtA = CountDownLatch(1)
        var backText: String? = null

        val clientA = DesktopMessagingClient(
            storeA, "", onMessage = { _, text, _ -> backText = text; gotAtA.countDown() }, onStateChanged = {}
        )
        val clientB = DesktopMessagingClient(
            storeB, "", onMessage = { _, _, _ -> gotAtB.countDown() }, onStateChanged = {}
        )

        try {
            clientA.start()
            clientB.start()
            val idB = clientA.pairFromPayload(clientB.myQrPayload()).getOrThrow()
            val idA = clientB.pairFromPayload(clientA.myQrPayload()).getOrThrow()
            clientA.setDirectAddress(idB, "127.0.0.1:${clientB.boundPort}")
            clientB.setDirectAddress(idA, "127.0.0.1:${clientA.boundPort}")

            runBlocking { clientA.sendMessage(idB, "سؤال") }
            assertTrue(gotAtB.await(30, TimeUnit.SECONDS))

            // B answers without ever having initiated: it must respond on the
            // session A established, not build a competing one. Getting this
            // wrong is how two peers end up unable to decrypt each other.
            runBlocking { clientB.sendMessage(idA, "جواب") }
            assertTrue("reply never arrived", gotAtA.await(30, TimeUnit.SECONDS))
            assertEquals("جواب", backText)
        } finally {
            clientA.stop()
            clientB.stop()
        }
    }

    @Test
    fun `a read receipt marks the sender's copy read, and a typing pulse is seen`() {
        val storeA = newStore("قارئ-أ")
        val storeB = newStore("قارئ-ب")

        val gotAtB = CountDownLatch(1)

        val clientA = DesktopMessagingClient(storeA, "", onMessage = { _, _, _ -> }, onStateChanged = {})
        val clientB = DesktopMessagingClient(
            storeB, "", onMessage = { _, _, _ -> gotAtB.countDown() }, onStateChanged = {}
        )

        try {
            clientA.start()
            clientB.start()
            val idB = clientA.pairFromPayload(clientB.myQrPayload()).getOrThrow()
            val idA = clientB.pairFromPayload(clientA.myQrPayload()).getOrThrow()
            clientA.setDirectAddress(idB, "127.0.0.1:${clientB.boundPort}")
            clientB.setDirectAddress(idA, "127.0.0.1:${clientA.boundPort}")

            // Typing: a bare pulse from A, with no message ever sent, must
            // still make B report A as typing within the display window.
            runBlocking { clientA.sendTypingSignal(idB) }
            assertTrue("typing pulse never arrived", waitFor(5_000) { clientB.isTyping(idA) })

            // Read receipt: B receives a message, marks it seen (what the UI
            // does the moment the conversation is open), and tells A. A's own
            // copy must flip from delivered to read once that comes back —
            // the exact distinction the double-check UI relies on.
            runBlocking { clientA.sendMessage(idB, "هل قرأتها؟") }
            assertTrue("message never arrived at B", gotAtB.await(30, TimeUnit.SECONDS))

            val newlySeen = storeB.markSeenLocallyAndGetNewIds(idA)
            assertEquals(1, newlySeen.size)
            runBlocking { assertTrue("receipt failed to send", clientB.sendReadReceipt(idA, newlySeen)) }

            val readConfirmed = waitFor(15_000) {
                storeA.messagesWith(idB).firstOrNull { it.outgoing }?.read == true
            }
            assertTrue("sender's copy was never marked read", readConfirmed)
        } finally {
            clientA.stop()
            clientB.stop()
        }
    }

    @Test
    fun `an unlocked store round-trips through the encrypted file`() {
        val dir = Files.createTempDirectory("sm-persist").toFile()
        val file = File(dir, "store.dat")
        val passphrase = "another-test-passphrase"

        val original = DesktopStore(file)
        original.create(passphrase.toCharArray(), "اسمي")
        val userId = original.userId
        val identityPublic = original.identity.publicKey.copyOf()

        val reopened = DesktopStore(file)
        assertTrue("correct passphrase was rejected", reopened.unlock(passphrase.toCharArray()))
        assertEquals(userId, reopened.userId)
        assertEquals("اسمي", reopened.displayName)
        assertTrue(identityPublic.contentEquals(reopened.identity.publicKey))

        // A wrong passphrase must fail closed, not throw something the UI would
        // have to guess at, and not half-load a partially decrypted state.
        val wrong = DesktopStore(file)
        assertTrue("wrong passphrase was accepted", !wrong.unlock("not-the-passphrase".toCharArray()))
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
