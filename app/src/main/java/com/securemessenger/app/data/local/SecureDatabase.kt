package com.securemessenger.app.data.local

import android.content.Context
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.securemessenger.app.data.model.*

/**
 * SecureDatabase - SQLCipher encrypted Room database.
 * All data at rest is encrypted using SQLCipher (AES-256).
 *
 * Security features:
 * - Full database encryption (AES-256)
 * - Key derived from master key stored in Android Keystore
 * - No plaintext data ever written to disk
 * - WAL mode disabled for better security (no journal files)
 */
@Database(
    entities = [
        UserProfile::class,
        Contact::class,
        Session::class,
        EncryptedMessage::class,
        KeyBundle::class,
        RatchetSession::class,
        OutboxEnvelope::class,
        PendingSend::class,
        SeenEnvelope::class,
        IncomingConnectionRequest::class,
        OutgoingConnectionRequest::class
    ],
    version = 13,
    exportSchema = false
)
abstract class SecureDatabase : RoomDatabase() {

    abstract fun userProfileDao(): UserProfileDao
    abstract fun contactDao(): ContactDao
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun keyBundleDao(): KeyBundleDao
    abstract fun ratchetSessionDao(): RatchetSessionDao
    abstract fun outboxDao(): OutboxDao
    abstract fun pendingSendDao(): PendingSendDao
    abstract fun seenEnvelopeDao(): SeenEnvelopeDao
    abstract fun incomingConnectionRequestDao(): IncomingConnectionRequestDao
    abstract fun outgoingConnectionRequestDao(): OutgoingConnectionRequestDao

    companion object {
        // Named to match the calculator disguise — anyone poking at this
        // app's private storage (root file explorer, adb, a forensic tool)
        // should see calculator-shaped file names, not "securemessenger".
        const val DATABASE_NAME = "calc_history.db"

        /**
         * UTF-8 encode a passphrase CharArray without materializing a String
         * (Strings are immutable and cannot be wiped). Zeroes the intermediate
         * encoder buffer before returning the freshly-allocated byte[].
         */
        private fun charArrayToUtf8Bytes(chars: CharArray): ByteArray {
            val byteBuffer = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(chars))
            val bytes = ByteArray(byteBuffer.remaining())
            byteBuffer.get(bytes)
            if (byteBuffer.hasArray()) java.util.Arrays.fill(byteBuffer.array(), 0.toByte())
            return bytes
        }

        /**
         * Adds `contacts.pinnedAt` (nullable — SQLite defaults new columns on
         * existing rows to NULL, which is exactly "not pinned"). Every other
         * column is untouched, so existing contacts, sessions and message
         * history survive. See SecureDatabaseMigrationTest.
         */
        val MIGRATION_10_11: Migration = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE contacts ADD COLUMN pinnedAt INTEGER")
            }
        }

        /**
         * Adds the two connection-request tables backing the optional
         * username-directory search/introduce flow. Both are brand new
         * tables — nothing existing is touched, so every other row in the
         * database survives untouched. See SecureDatabaseMigrationTest.
         */
        val MIGRATION_11_12: Migration = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS incoming_connection_requests (
                        senderIdentityPublicKeyHex TEXT NOT NULL PRIMARY KEY,
                        senderUserId TEXT NOT NULL,
                        senderUsername TEXT NOT NULL,
                        senderSigningPublicKey BLOB NOT NULL,
                        pairSecretEncrypted BLOB NOT NULL,
                        directAddress TEXT,
                        receivedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS outgoing_connection_requests (
                        recipientIdentityPublicKeyHex TEXT NOT NULL PRIMARY KEY,
                        recipientUsername TEXT NOT NULL,
                        recipientSigningPublicKey BLOB NOT NULL,
                        mintedPairSecretEncrypted BLOB NOT NULL,
                        sentAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Gives every message a conversation key, and indexes it.
         *
         * `messages.contactId` is derivable from what is already in each row —
         * a received message's counterpart is its sender, a sent one's is its
         * recipient — so the backfill is exact and needs no guessing. Nothing
         * is dropped or rewritten beyond filling in the new column.
         *
         * The column is nullable on purpose. A NOT NULL column needs a DEFAULT
         * to be added to a populated table, and Room's schema validation
         * compares that default against what SQLite reports; a disagreement
         * there fails the migration, and this database falls back to
         * destructive migration when a step fails — which for a user of this
         * app means every message they have, with no backup and no export. A
         * nullable column has nothing to disagree about. See
         * SecureDatabaseMigrationTest.
         */
        val MIGRATION_12_13: Migration = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN contactId TEXT")
                db.execSQL(
                    "UPDATE messages SET contactId = " +
                        "CASE WHEN direction = 0 THEN senderId ELSE recipientId END"
                )
                // The name is Room's own convention for this index. It has to
                // match exactly or Room's validation reports the schema as
                // altered and the destructive fallback takes the data.
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_messages_contactId_timestamp " +
                        "ON messages (contactId, timestamp)"
                )
            }
        }

        @Volatile
        private var INSTANCE: SecureDatabase? = null

        /**
         * Get or create the encrypted database instance.
         * @param context Application context
         * @param passphrase The encryption passphrase (should be derived from master key)
         */
        fun getInstance(context: Context, passphrase: CharArray): SecureDatabase {
            return INSTANCE ?: synchronized(this) {
                // A leftover file under the old ("secure_messenger.db") name
                // would give away what this app really is to anyone poking at
                // its private storage — remove it if an earlier build left one.
                context.deleteDatabase("secure_messenger.db")

                // Initialize SQLCipher's native core.
                System.loadLibrary("sqlcipher")

                // Encode the CharArray passphrase to UTF-8 bytes WITHOUT ever
                // building an immutable String (a String can't be zeroed and
                // would linger in the heap as the plaintext DB key). SQLCipher's
                // factory zeroes the byte[] it is handed after opening the DB.
                val factory = SupportOpenHelperFactory(charArrayToUtf8Bytes(passphrase))

                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SecureDatabase::class.java,
                    DATABASE_NAME
                ).apply {
                    openHelperFactory(factory)
                    // Security: Disable WAL for better security
                    setJournalMode(JournalMode.TRUNCATE)
                    // Known version steps get an explicit, data-preserving
                    // migration — this app has no backup/export, so silently
                    // dropping and recreating tables would be real, unrecoverable
                    // data loss for anyone already using it. Destructive fallback
                    // stays only as a last resort for a gap no migration covers
                    // (e.g. a downgrade). NOTE: this is about schema versioning,
                    // it has nothing to do with encryption.
                    addMigrations(MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
                    fallbackToDestructiveMigration()
                    addCallback(object : Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            // These are PER-CONNECTION pragmas: they must run on
                            // EVERY open, not just onCreate, or secure_delete
                            // silently reverts to OFF on every launch after the
                            // first and deleted rows linger in freelist pages.
                            // They return a row, so run via query() (execSQL
                            // rejects result-returning statements).
                            db.query("PRAGMA foreign_keys = ON").close()
                            db.query("PRAGMA secure_delete = ON").close()
                        }
                    })
                }.build()

                INSTANCE = instance
                instance
            }
        }

        /**
         * Close and clear the database instance.
         */
        fun closeDatabase() {
            INSTANCE?.close()
            INSTANCE = null
        }

        /**
         * Wipe the database file securely.
         */
        fun wipeDatabase(context: Context) {
            context.deleteDatabase(DATABASE_NAME)
            // Also delete any journal or WAL files
            context.getDatabasePath("${DATABASE_NAME}-journal").delete()
            context.getDatabasePath("${DATABASE_NAME}-wal").delete()
        }
    }
}
