package com.securemessenger.app.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.securemessenger.app.data.model.Contact
import com.securemessenger.app.data.model.EncryptedMessage
import com.securemessenger.app.data.model.IncomingConnectionRequest
import com.securemessenger.app.data.model.KeyBundle
import com.securemessenger.app.data.model.OutboxEnvelope
import com.securemessenger.app.data.model.OutgoingConnectionRequest
import com.securemessenger.app.data.model.PendingSend
import com.securemessenger.app.data.model.RatchetSession
import com.securemessenger.app.data.model.SeenEnvelope
import com.securemessenger.app.data.model.Session
import com.securemessenger.app.data.model.UserProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Contact exactly as it stood at database version 10, before pinnedAt —
 * redeclared here (not imported from the app's own Entities.kt, which now
 * has pinnedAt) so [OldSchemaDatabase] below is a genuine pre-migration
 * schema, not the current one.
 */
@Entity(tableName = "contacts")
data class OldContact(
    @PrimaryKey val id: String,
    val publicKey: ByteArray,
    val signingPublicKey: ByteArray? = null,
    val displayNameEncrypted: ByteArray,
    val avatarHash: String? = null,
    val isVerified: Boolean = false,
    val verificationData: ByteArray? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val lastSeenAt: Long? = null,
    val isBlocked: Boolean = false,
    val avatarEncrypted: ByteArray? = null,
    val nicknameEncrypted: ByteArray? = null,
    val isMuted: Boolean = false,
    val relaySendSecretEncrypted: ByteArray? = null,
    val relayRecvSecretEncrypted: ByteArray? = null
)

@Dao
interface OldContactDao {
    @Insert
    suspend fun insert(contact: OldContact)
}

/**
 * `messages` exactly as it stood through database version 12 — before
 * `contactId` and the (contactId, timestamp) index.
 *
 * Same reasoning as [OldContact]: every "before" database below has to be a
 * genuine pre-migration schema. Importing the real EncryptedMessage would give
 * the v10, v11 and v12 fixtures a column and an index those versions never
 * had, and Room's validation would then compare a doctored past against the
 * present and report whatever it liked.
 */
@Entity(tableName = "messages")
data class OldEncryptedMessage(
    @PrimaryKey val id: Long? = null,
    val sessionId: String,
    val senderId: String,
    val recipientId: String,
    val direction: Int,
    val type: Int,
    val ciphertext: ByteArray,
    val iv: ByteArray,
    val timestamp: Long = System.currentTimeMillis(),
    val expiresAt: Long? = null,
    val isRead: Boolean = false,
    val isExpired: Boolean = false,
    val metadataEncrypted: ByteArray? = null,
    val clientMessageId: String? = null,
    val reactionsJson: String? = null,
    val replyToClientId: String? = null,
    val replySnippetEncrypted: ByteArray? = null,
    val isDeleted: Boolean = false,
    val editedAt: Long? = null
)

@Dao
interface OldMessageDao {
    @Insert
    suspend fun insert(message: OldEncryptedMessage)
}

/**
 * Every entity here except [OldContact] is the real production class —
 * those tables are untouched by MIGRATION_10_11, so Room should generate the
 * exact same schema for them as it does for the real SecureDatabase. Only
 * Contact needs a stand-in, which keeps the hand-maintained surface to one
 * class instead of the whole database.
 */
@Database(
    entities = [
        UserProfile::class, OldContact::class, Session::class, OldEncryptedMessage::class,
        KeyBundle::class, RatchetSession::class, OutboxEnvelope::class, PendingSend::class, SeenEnvelope::class
    ],
    version = 10,
    exportSchema = false
)
abstract class OldSchemaDatabase : RoomDatabase() {
    abstract fun oldContactDao(): OldContactDao
}

/**
 * Proves MIGRATION_10_11 (contacts.pinnedAt) preserves an existing contact
 * row, instead of falling through to fallbackToDestructiveMigration — which
 * would silently drop every contact, session and message this app has no
 * backup/export path to ever recover.
 *
 * The "before" database is built by Room itself from [OldSchemaDatabase],
 * not hand-written SQL, so its schema is what a real v10 install actually
 * had rather than a best-effort transcription of it. Room's own migration
 * validation additionally catches any shape mismatch across every table
 * (not just contacts) once the real SecureDatabase reopens this same file
 * at v11 — this test failed exactly that way on its first run, against a
 * hand-written "before" table that only declared `contacts` and omitted
 * every other table Room expected to already exist.
 */
class SecureDatabaseMigrationTest {

    private val passphrase = "migration-test-passphrase".toByteArray(Charsets.UTF_8)

    @Test
    fun migration10to11_preservesExistingContactAndDefaultsPinnedAtToNull() {
        System.loadLibrary("sqlcipher")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_test_10_11.db"
        context.deleteDatabase(dbName)

        try {
            // 1) Build a real v10 database and insert one real contact row.
            val oldDb = Room.databaseBuilder(context, OldSchemaDatabase::class.java, dbName)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .build()
            runBlocking {
                oldDb.oldContactDao().insert(
                    OldContact(
                        id = "contact-1",
                        publicKey = ByteArray(32) { 1 },
                        displayNameEncrypted = ByteArray(16) { 2 },
                        addedAt = 1_700_000_000_000L
                    )
                )
            }
            oldDb.close()

            // 2) Reopen the SAME file at version 11 with only MIGRATION_10_11
            //    registered — no destructive fallback, so a shape mismatch
            //    throws here instead of silently "succeeding".
            //
            //    [V11SchemaDatabase], not the real SecureDatabase: this test
            //    is about one migration step, and the real class is at v12.
            //    Opening it here asked Room for a 10 -> 12 path, got only the
            //    10 -> 11 half, and failed with "A migration from 10 to 12 was
            //    required but not found" — the test's own comment already said
            //    "at version 11" while the code no longer was. The 11 -> 12
            //    step has its own test below; chaining both here would test
            //    neither in isolation.
            val db = Room.databaseBuilder(context, V11SchemaDatabase::class.java, dbName)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .addMigrations(SecureDatabase.MIGRATION_10_11)
                .build()

            try {
                val contact = runBlocking { db.contactDao().getContact("contact-1") }
                assertEquals("the pre-existing contact row must survive the migration", "contact-1", contact?.id)
                assertNull("pinnedAt must default to null for a pre-existing row", contact?.pinnedAt)

                runBlocking { db.contactDao().setPinned("contact-1", 1_800_000_000_000L) }
                val pinned = runBlocking { db.contactDao().getContact("contact-1") }
                assertEquals(1_800_000_000_000L, pinned?.pinnedAt)
            } finally {
                db.close()
            }
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    /**
     * Every entity here is the real production class, at v12's shape minus
     * the two connection-request tables — nothing about Contact (or anything
     * else) changed between v11 and v12, so unlike [OldSchemaDatabase] above
     * no hand-maintained stand-in entity is needed, only a smaller entity
     * list at version 11.
     */
    @Database(
        entities = [
            UserProfile::class, Contact::class, Session::class, OldEncryptedMessage::class,
            KeyBundle::class, RatchetSession::class, OutboxEnvelope::class, PendingSend::class, SeenEnvelope::class
        ],
        version = 11,
        exportSchema = false
    )
    abstract class V11SchemaDatabase : RoomDatabase() {
        abstract fun contactDao(): ContactDao
    }

    /**
     * v12: every production entity at the shape it had before `contactId`.
     *
     * The 11 -> 12 test below used to reopen the real [SecureDatabase] here,
     * which worked only while the real class happened to be at version 12. It
     * is at 13 now, so that same line would ask Room for an 11 -> 13 path and
     * fail on the missing half — exactly the trap the 10 -> 11 test above
     * already documented falling into once. Each step gets its own fixture so
     * each is tested in isolation and none of them moves when the next
     * version lands.
     */
    @Database(
        entities = [
            UserProfile::class, Contact::class, Session::class, OldEncryptedMessage::class,
            KeyBundle::class, RatchetSession::class, OutboxEnvelope::class, PendingSend::class,
            SeenEnvelope::class, IncomingConnectionRequest::class, OutgoingConnectionRequest::class
        ],
        version = 12,
        exportSchema = false
    )
    abstract class V12SchemaDatabase : RoomDatabase() {
        abstract fun contactDao(): ContactDao
        abstract fun oldMessageDao(): OldMessageDao
        abstract fun incomingConnectionRequestDao(): IncomingConnectionRequestDao
        abstract fun outgoingConnectionRequestDao(): OutgoingConnectionRequestDao
    }

    /**
     * Proves MIGRATION_11_12 (the two connection-request tables) preserves
     * existing data and produces exactly the schema Room expects for the two
     * new entities — same "build the before-database with Room itself, not
     * hand-written SQL" reasoning as the 10->11 test above.
     */
    @Test
    fun migration11to12_preservesExistingContactAndCreatesConnectionRequestTables() {
        System.loadLibrary("sqlcipher")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_test_11_12.db"
        context.deleteDatabase(dbName)

        try {
            // 1) Build a real v11 database and insert one real contact row.
            val oldDb = Room.databaseBuilder(context, V11SchemaDatabase::class.java, dbName)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .build()
            runBlocking {
                oldDb.contactDao().insertContact(
                    Contact(
                        id = "contact-1",
                        publicKey = ByteArray(32) { 1 },
                        displayNameEncrypted = ByteArray(16) { 2 },
                        addedAt = 1_700_000_000_000L
                    )
                )
            }
            oldDb.close()

            // 2) Reopen the SAME file at version 12 with only MIGRATION_11_12
            //    registered — one step, in isolation.
            val db = Room.databaseBuilder(context, V12SchemaDatabase::class.java, dbName)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .addMigrations(SecureDatabase.MIGRATION_11_12)
                .build()

            try {
                val contact = runBlocking { db.contactDao().getContact("contact-1") }
                assertEquals("the pre-existing contact row must survive the migration", "contact-1", contact?.id)

                runBlocking {
                    db.incomingConnectionRequestDao().insert(
                        IncomingConnectionRequest(
                            senderIdentityPublicKeyHex = "aa".repeat(32),
                            senderUserId = "sender-1",
                            senderUsername = "basil",
                            senderSigningPublicKey = ByteArray(32) { 4 },
                            pairSecretEncrypted = ByteArray(48) { 5 }
                        )
                    )
                    db.outgoingConnectionRequestDao().insert(
                        OutgoingConnectionRequest(
                            recipientIdentityPublicKeyHex = "bb".repeat(32),
                            recipientUsername = "sara",
                            recipientSigningPublicKey = ByteArray(32) { 8 },
                            mintedPairSecretEncrypted = ByteArray(48) { 7 }
                        )
                    )
                }

                val incoming = runBlocking { db.incomingConnectionRequestDao().get("aa".repeat(32)) }
                assertEquals("basil", incoming?.senderUsername)
                val outgoing = runBlocking { db.outgoingConnectionRequestDao().get("bb".repeat(32)) }
                assertEquals("sara", outgoing?.recipientUsername)
            } finally {
                db.close()
            }
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    /**
     * Proves MIGRATION_12_13 gives every existing message the right
     * conversation key.
     *
     * This is the migration with the most to lose. `contactId` is what both
     * screens now read by, so a backfill that guessed wrong would not corrupt
     * anything visibly — it would simply make old messages belong to nobody,
     * and every conversation would open empty with the history still sitting
     * in the table. The rule has two directions and they use opposite columns:
     * a received message's counterpart is its sender, a sent one's is its
     * recipient. Both are asserted, because getting one right and the other
     * backwards is the plausible mistake.
     */
    @Test
    fun migration12to13_backfillsContactIdForBothDirections() {
        System.loadLibrary("sqlcipher")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_test_12_13.db"
        context.deleteDatabase(dbName)

        try {
            // 1) A real v12 database holding one received and one sent message.
            val oldDb = Room.databaseBuilder(context, V12SchemaDatabase::class.java, dbName)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .build()
            runBlocking {
                oldDb.oldMessageDao().insert(
                    OldEncryptedMessage(
                        id = 1L,
                        sessionId = "session-1",
                        senderId = "them",
                        recipientId = "me",
                        direction = 0,
                        type = 0,
                        ciphertext = ByteArray(0),
                        iv = ByteArray(0),
                        timestamp = 1_700_000_000_000L
                    )
                )
                oldDb.oldMessageDao().insert(
                    OldEncryptedMessage(
                        id = 2L,
                        sessionId = "session-1",
                        senderId = "me",
                        recipientId = "them",
                        direction = 1,
                        type = 0,
                        ciphertext = ByteArray(0),
                        iv = ByteArray(0),
                        timestamp = 1_700_000_001_000L
                    )
                )
            }
            oldDb.close()

            // 2) Reopen at v13 through the real production class, with only
            //    MIGRATION_12_13 registered.
            val db = Room.databaseBuilder(context, SecureDatabase::class.java, dbName)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .addMigrations(SecureDatabase.MIGRATION_12_13)
                .build()

            try {
                // Read them back the way the app now does: by contact.
                val thread = runBlocking {
                    db.messageDao().getRecentMessagesForContact("them", 50).first()
                }
                assertEquals(
                    "both messages must land in the conversation with \"them\" — " +
                        "the received one keyed by its sender, the sent one by its recipient",
                    listOf(1L, 2L),
                    thread.map { it.id }
                )
                assertEquals("them", thread[0].contactId)
                assertEquals("them", thread[1].contactId)

                // And nothing was left without a key.
                val orphaned = runBlocking {
                    db.messageDao().getRecentMessagesForContact("me", 50).first()
                }
                assertEquals("no message may be filed under our own id", emptyList<Long>(), orphaned.map { it.id })
            } finally {
                db.close()
            }
        } finally {
            context.deleteDatabase(dbName)
        }
    }
}
