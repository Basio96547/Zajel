package com.securemessenger.app.network

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.securemessenger.core.crypto.AckToken
import com.securemessenger.app.data.local.SecureDatabase
import com.securemessenger.app.data.repository.SecureRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Instrumented (on-device) verification of authenticated transport acks.
 *
 * The ack security logic can't be exercised end-to-end in-process (real
 * delivery needs NSD discovery + two devices), so this drives the receive side
 * directly: it puts an entry in the durable outbox, then feeds ack frames into
 * the same [SecureMessagingClient.handleAck] the socket calls, and asserts the
 * outbox is cleared only for a genuine ack.
 *
 * Runs as androidTest because it needs libsodium native + the Android Keystore +
 * SQLCipher, none of which exist under a plain JVM unit test.
 */
@RunWith(AndroidJUnit4::class)
class AckAuthenticationTest {

    private lateinit var context: Context
    private lateinit var repo: SecureRepository
    private lateinit var client: SecureMessagingClient

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        // Start from a clean, isolated database.
        SecureDatabase.closeDatabase()
        SecureDatabase.wipeDatabase(context)
        repo = SecureRepository(context)
        repo.initialize("ack-test-passphrase".toCharArray())
        val profile = repo.createProfile() // gives this device an identity keypair
        client = SecureMessagingClient(context, repo, profile.id)
    }

    @After
    fun tearDown() {
        try { repo.close() } catch (_: Exception) {}
        SecureDatabase.closeDatabase()
        SecureDatabase.wipeDatabase(context)
    }

    private suspend fun stageOutboxEntry(): String {
        val id = UUID.randomUUID().toString()
        repo.saveOutboxEnvelope(id, "contact-1", """{"type":"message","id":"$id"}""")
        assertTrue("precondition: entry is queued", outboxContains(id))
        return id
    }

    private suspend fun outboxContains(id: String): Boolean =
        repo.getAllOutboxEnvelopes().any { it.id == id }

    /** A genuine ack (recipient echoed the sealed token) clears the outbox entry. */
    @Test
    fun validAck_clearsOutboxEntry() = runBlocking {
        val id = stageOutboxEntry()
        val token = client.ackTokenForTest(id)
        assertNotNull("a profile identity must yield a token", token)

        client.handleAckForTest(JSONObject().put("id", id).put("ackToken", token))

        assertFalse("valid ack must clear the outbox entry", outboxContains(id))
    }

    /** A forged token (on-path attacker who saw only the cleartext id) is rejected. */
    @Test
    fun forgedAck_keepsOutboxEntry() = runBlocking {
        val id = stageOutboxEntry()
        val forged = Base64.encodeToString(ByteArray(16) { 0 }, Base64.NO_WRAP)

        client.handleAckForTest(JSONObject().put("id", id).put("ackToken", forged))

        assertTrue("forged ack must NOT clear the outbox entry", outboxContains(id))
    }

    /** A legacy peer's tokenless ack fails closed (entry kept, keeps retrying). */
    @Test
    fun legacyAckWithoutToken_keepsOutboxEntry() = runBlocking {
        val id = stageOutboxEntry()

        client.handleAckForTest(JSONObject().put("id", id))

        assertTrue("tokenless (legacy) ack must NOT clear the outbox entry", outboxContains(id))
    }

    /** An ack for a DIFFERENT envelope id (even with a valid-looking token) doesn't touch ours. */
    @Test
    fun ackForOtherEnvelope_keepsOurEntry() = runBlocking {
        val id = stageOutboxEntry()
        val otherId = UUID.randomUUID().toString()
        val tokenForOther = client.ackTokenForTest(otherId)

        client.handleAckForTest(JSONObject().put("id", otherId).put("ackToken", tokenForOther))

        assertTrue("an ack for another id must not clear our entry", outboxContains(id))
    }

    /** Token derivation: deterministic, unique per envelope id, and bound to the identity secret. */
    @Test
    fun ackToken_isDeterministicUniqueAndSecretBound() {
        val secretA = ByteArray(32) { 1 }
        val secretB = ByteArray(32) { 2 }

        // Same (secret, id) -> same token.
        assertEquals(AckToken.compute(secretA, "env-1"), AckToken.compute(secretA, "env-1"))
        // Different envelope id -> different token.
        assertNotEquals(AckToken.compute(secretA, "env-1"), AckToken.compute(secretA, "env-2"))
        // Different identity secret -> different token (a forger can't reproduce it).
        assertNotEquals(AckToken.compute(secretA, "env-1"), AckToken.compute(secretB, "env-1"))
    }
}
