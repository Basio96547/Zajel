package com.securemessenger.app.data.local

import android.content.Context
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
        OutboxEnvelope::class
    ],
    version = 9,
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
                    // A schema-version mismatch drops and recreates the tables
                    // (this app's data is ephemeral and re-establishable — it is
                    // never migrated). NOTE: this is about schema versioning, it
                    // has nothing to do with encryption.
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
