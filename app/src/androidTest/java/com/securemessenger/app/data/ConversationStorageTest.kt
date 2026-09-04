package com.securemessenger.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.securemessenger.app.data.local.SecureDatabase
import com.securemessenger.app.data.repository.SecureRepository
import com.securemessenger.core.crypto.SignalProtocol
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A message arrives, is stored, and shows up in the conversation — the one
 * thing the app has to do, and until now the one thing nothing tested.
 *
 * The suite covered the pieces on either side of this and never the join:
 * RatchetRoundtripTest exercises the crypto with no database at all,
 * RelayRoundtripTest exercises the transport against the live relay and never
 * touches storage. Between them sat the layer that decides WHERE a received
 * message is filed and WHERE the conversation screen looks for it — and that
 * layer just changed, from the cryptographic session id to the contact. A
 * mismatch there does not crash or corrupt anything: messages arrive, get
 * stored, and every conversation opens empty. Precisely the failure that
 * passing crypto and transport tests would say nothing about.
 *
 * Runs against a real SQLCipher database and the real Keystore, because
 * anything less would be testing a mock of the thing in question.
 */
@RunWith(AndroidJUnit4::class)
class ConversationStorageTest {

    private lateinit var context: Context
    private lateinit var repository: SecureRepository
    private val passphrase = "conversation-storage-test".toCharArray()

    /** A contact whose key is a real X25519 identity, so session setup behaves as it does in the app. */
    private val theirKey = SignalProtocol.IdentityKeyPair.generate().publicKey
    private val them = "them-user-id"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SecureDatabase.closeDatabase()
        SecureDatabase.wipeDatabase(context)
        repository = SecureRepository(context)
        runBlocking {
            repository.initialize(passphrase.copyOf())
            repository.createProfile()
            repository.generatePreKeys(5)
        }
    }

    @After
    fun tearDown() {
        repository.close()
        SecureDatabase.wipeDatabase(context)
    }

    @Test
    fun aReceivedMessageAppearsInTheConversationWithThatContact() = runBlocking {
        // Exactly what SecureMessengerApp does with a decrypted envelope.
        repository.saveIncomingMessage(
            senderId = them,
            senderIdentityKey = theirKey,
            plaintext = "وصلتك الصورة؟".toByteArray(Charsets.UTF_8),
            clientMessageId = "m1"
        )

        val thread = repository.getRecentMessages(them, 50).first()
        assertEquals("the received message must be in this contact's thread", 1, thread.size)
        assertEquals(them, thread[0].contactId)
        assertEquals(0, thread[0].direction)
        assertEquals("وصلتك الصورة؟", repository.decryptDisplayText(thread[0]))
    }

    @Test
    fun bothDirectionsLandInOneThread() = runBlocking {
        // Receiving first is what creates the contact, its prekeys and the
        // session — the same order a real pairing produces.
        repository.saveIncomingMessage(
            senderId = them,
            senderIdentityKey = theirKey,
            plaintext = "مرحبا".toByteArray(Charsets.UTF_8),
            clientMessageId = "m1"
        )
        repository.sendMessageToContact(them, "أهلاً")

        val thread = repository.getRecentMessages(them, 50).first()
        assertEquals("a reply must join the same conversation, not start a second one", 2, thread.size)
        assertEquals(listOf(0, 1), thread.map { it.direction })
        assertEquals(listOf(them, them), thread.map { it.contactId })
        assertEquals("مرحبا", repository.decryptDisplayText(thread[0]))
        assertEquals("أهلاً", repository.decryptDisplayText(thread[1]))
    }

    @Test
    fun theChatListSeesTheSameConversation() = runBlocking {
        repository.saveIncomingMessage(
            senderId = them,
            senderIdentityKey = theirKey,
            plaintext = "أول رسالة".toByteArray(Charsets.UTF_8),
            clientMessageId = "m1"
        )
        repository.saveIncomingMessage(
            senderId = them,
            senderIdentityKey = theirKey,
            plaintext = "آخر رسالة".toByteArray(Charsets.UTF_8),
            clientMessageId = "m2"
        )

        // The home screen's two queries, which replaced loading every message
        // in the database and filtering it per contact.
        val latest = repository.observeLatestMessagePerContact().first()
        assertEquals("one row per conversation", 1, latest.size)
        assertEquals("آخر رسالة", repository.decryptDisplayText(latest[0]))

        val unread = repository.observeUnreadCounts().first()
        assertEquals(1, unread.size)
        assertEquals(them, unread[0].contactId)
        assertEquals(2, unread[0].unreadCount)
    }

    @Test
    fun openingTheConversationClearsItsUnreadCount() = runBlocking {
        repository.saveIncomingMessage(
            senderId = them,
            senderIdentityKey = theirKey,
            plaintext = "غير مقروءة".toByteArray(Charsets.UTF_8),
            clientMessageId = "m1"
        )

        // Read state is keyed by contact now. Keyed by session — as it was —
        // an unread count computed across the conversation could not be
        // cleared once a contact had more than one session.
        val receipted = repository.markReceivedAsReadAndGetIds(them)
        assertEquals("the read receipt must name the message it read", listOf("m1"), receipted)
        assertTrue("nothing may remain unread", repository.observeUnreadCounts().first().isEmpty())
    }

    @Test
    fun aDelayedMessageSitsWhereItWasSent_notWhereItArrived() = runBlocking {
        val nineHoursAgo = System.currentTimeMillis() - 9 * 60 * 60 * 1000
        // Collected now, sent this morning: the relay holds an envelope up to
        // 48h and background delivery is off by default, so this is ordinary,
        // not exotic. Filed at arrival it would jump to the bottom of the
        // thread, and the sender's phone would show the opposite order.
        repository.saveIncomingMessage(
            senderId = them,
            senderIdentityKey = theirKey,
            plaintext = "أرسلتها الصباح".toByteArray(Charsets.UTF_8),
            clientMessageId = "old",
            sentAt = nineHoursAgo
        )
        repository.saveIncomingMessage(
            senderId = them,
            senderIdentityKey = theirKey,
            plaintext = "وهذه الآن".toByteArray(Charsets.UTF_8),
            clientMessageId = "new"
        )

        val thread = repository.getRecentMessages(them, 50).first()
        assertEquals(listOf("old", "new"), thread.map { it.clientMessageId })
        assertTrue(
            "the delayed message must keep its send time",
            thread[0].timestamp < System.currentTimeMillis() - 8 * 60 * 60 * 1000
        )
    }

    @Test
    fun theWindowReturnsTheNewestMessagesAndCanBeWidened() = runBlocking {
        repeat(25) { i ->
            repository.saveIncomingMessage(
                senderId = them,
                senderIdentityKey = theirKey,
                plaintext = "رسالة $i".toByteArray(Charsets.UTF_8),
                clientMessageId = "m$i"
            )
        }

        // A window has to take the NEWEST messages and still hand them back
        // oldest-first, or a conversation would open showing its own ancient
        // history with the recent messages missing.
        val window = repository.getRecentMessages(them, 10).first()
        assertEquals(10, window.size)
        assertEquals("m15", window.first().clientMessageId)
        assertEquals("m24", window.last().clientMessageId)

        assertEquals(25, repository.countMessages(them).first())
        assertEquals(25, repository.getRecentMessages(them, 100).first().size)
    }
}
