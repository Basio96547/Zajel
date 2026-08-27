package com.securemessenger.desktop

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import com.securemessenger.core.CoreLogger
import com.securemessenger.core.Platform
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Reproduces, and guards against, a real bug: DesktopMessagingClient.scope
 * used to be a `val`, created once at construction. stop() cancels its Job;
 * a coroutine launched on an already-cancelled scope never actually runs its
 * body at all (structured concurrency — a child inherits the parent's
 * cancelled state), so EVERY `scope.launch` call made after a stop()+start()
 * cycle on the same instance silently no-oped. That includes the periodic
 * token-rotation/outbox-retry jobs, and — what this test actually exercises
 * end-to-end — the socket server's own
 * `onEnvelope = { text, reply -> scope.launch { handleIncoming(...) } }`
 * handler: an incoming challenge or message received after a restart was
 * silently dropped at the coroutine level, not merely delayed, with nothing
 * logged anywhere to say so.
 *
 * This app keeps one DesktopMessagingClient instance for its whole lifetime
 * and calls stop()/start() on that same instance (e.g. around a settings
 * change), so this was a real, user-reachable path, not a theoretical one.
 */
class ScopeReuseTest {

    @Before
    fun installPlatform() {
        Platform.installSodium(LazySodiumJava(SodiumJava()))
        Platform.installLogger(object : CoreLogger {
            override fun debug(tag: String, message: String) {}
            override fun warn(tag: String, message: String, error: Throwable?) {}
            override fun error(tag: String, message: String, error: Throwable?) {}
        })
    }

    private fun newStore(name: String): DesktopStore {
        val dir = Files.createTempDirectory("sm-test").toFile()
        val store = DesktopStore(File(dir, "store.dat"))
        store.create("test-passphrase".toCharArray(), name)
        return store
    }

    @Test
    fun `a client that stopped and restarted still receives messages`() {
        val storeA = newStore("جهاز-أ")
        val storeB = newStore("جهاز-ب")

        val received = CountDownLatch(1)
        var receivedText: String? = null

        val clientA = DesktopMessagingClient(
            storeA, relayUrl = "",
            onMessage = { _, text, _ -> receivedText = text; received.countDown() },
            onStateChanged = {}
        )
        val clientB = DesktopMessagingClient(storeB, relayUrl = "", onMessage = { _, _, _ -> }, onStateChanged = {})

        try {
            // === FIRST CYCLE — pair while both are up, then stop A. Deliberately
            // no message sent yet: no ratchet session exists between them, so
            // the send below has to run a full X3DH handshake through A's
            // RESTARTED socket server — exactly the path the bug broke.
            clientA.start()
            clientB.start()

            val idB = clientA.pairFromPayload(clientB.myQrPayload()).getOrThrow()
            val idA = clientB.pairFromPayload(clientA.myQrPayload()).getOrThrow()
            clientA.setDirectAddress(idB, "127.0.0.1:${clientB.boundPort}")

            clientA.stop()

            // === SECOND CYCLE — restart A on the SAME instance, exactly the
            // reuse pattern the app's own start/stop lifecycle uses.
            clientA.start()
            // A's bound port can change on restart if the fixed one hadn't
            // freed up yet — B has to be told the current one, same as any
            // real reconnect would require.
            clientB.setDirectAddress(idA, "127.0.0.1:${clientA.boundPort}")

            val sent = runBlocking { clientB.sendMessage(idA, "بعد إعادة التشغيل") }
            assertTrue("send reported failure", sent)

            assertTrue(
                "message never arrived at the restarted client — scope reuse bug",
                received.await(30, TimeUnit.SECONDS)
            )
            assertEquals("بعد إعادة التشغيل", receivedText)
        } finally {
            clientA.stop()
            clientB.stop()
        }
    }
}
