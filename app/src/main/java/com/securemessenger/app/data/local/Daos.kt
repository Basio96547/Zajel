package com.securemessenger.app.data.local

import androidx.room.*
import com.securemessenger.app.data.model.*
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for user profile operations.
 */
@Dao
interface UserProfileDao {

    @Query("SELECT * FROM user_profile LIMIT 1")
    fun getProfile(): Flow<UserProfile?>

    @Query("SELECT * FROM user_profile LIMIT 1")
    suspend fun getProfileOnce(): UserProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: UserProfile)

    @Query("UPDATE user_profile SET avatarEncrypted = :avatar")
    suspend fun setAvatar(avatar: ByteArray?)

    @Query("UPDATE user_profile SET displayNameEncrypted = :name")
    suspend fun setDisplayName(name: ByteArray?)
}

/**
 * Data Access Object for contact operations.
 */
@Dao
interface ContactDao {

    @Query("SELECT * FROM contacts ORDER BY displayNameEncrypted")
    fun getAllContacts(): Flow<List<Contact>>

    @Query("SELECT * FROM contacts WHERE id = :contactId")
    suspend fun getContact(contactId: String): Contact?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContact(contact: Contact)

    @Update
    suspend fun updateContact(contact: Contact)

    @Query("UPDATE contacts SET isVerified = :verified, verificationData = :data WHERE id = :contactId")
    suspend fun setVerified(contactId: String, verified: Boolean, data: ByteArray?)

    @Query("UPDATE contacts SET isBlocked = :blocked WHERE id = :contactId")
    suspend fun setBlocked(contactId: String, blocked: Boolean)

    @Query("UPDATE contacts SET isMuted = :muted WHERE id = :contactId")
    suspend fun setMuted(contactId: String, muted: Boolean)

    @Query("UPDATE contacts SET nicknameEncrypted = :nickname WHERE id = :contactId")
    suspend fun setNickname(contactId: String, nickname: ByteArray?)

    @Delete
    suspend fun deleteContact(contact: Contact)

    @Query("DELETE FROM contacts WHERE id = :contactId")
    suspend fun deleteContactById(contactId: String)

    @Query("UPDATE contacts SET avatarEncrypted = :avatar WHERE id = :contactId")
    suspend fun setAvatar(contactId: String, avatar: ByteArray?)
}

/**
 * Data Access Object for session operations.
 */
@Dao
interface SessionDao {

    @Query("SELECT * FROM sessions WHERE contactId = :contactId ORDER BY lastUsedAt DESC LIMIT 1")
    suspend fun getCurrentSession(contactId: String): Session?

    @Query("SELECT * FROM sessions WHERE sessionId = :sessionId")
    suspend fun getSessionById(sessionId: String): Session?

    @Insert
    suspend fun insertSession(session: Session): Long

    @Query("UPDATE sessions SET rootKeyEncrypted = :rootKey, chainKeySendEncrypted = :sendKey, " +
           "chainKeyReceiveEncrypted = :receiveKey, dhRatchetPublic = :dhPublic, " +
           "dhRatchetSecretEncrypted = :dhSecret, sendChainCounter = :sendCounter, " +
           "receiveChainCounter = :receiveCounter, lastUsedAt = :timestamp " +
           "WHERE sessionId = :sessionId")
    suspend fun updateSessionState(
        sessionId: String,
        rootKey: ByteArray,
        sendKey: ByteArray,
        receiveKey: ByteArray?,
        dhPublic: ByteArray,
        dhSecret: ByteArray,
        sendCounter: Int,
        receiveCounter: Int,
        timestamp: Long
    )

    @Query("DELETE FROM sessions WHERE contactId = :contactId")
    suspend fun deleteAllSessionsForContact(contactId: String)
}

/**
 * Data Access Object for message operations.
 */
@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesForSession(sessionId: String): Flow<List<EncryptedMessage>>

    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    fun getAllMessages(): Flow<List<EncryptedMessage>>

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getMessage(messageId: Long): EncryptedMessage?

    @Query("SELECT * FROM messages WHERE expiresAt IS NOT NULL AND expiresAt <= :currentTime AND isExpired = 0")
    suspend fun getExpiredMessages(currentTime: Long = System.currentTimeMillis()): List<EncryptedMessage>

    @Insert
    suspend fun insertMessage(message: EncryptedMessage): Long

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId AND direction = 0 AND isRead = 0")
    suspend fun getUnreadReceivedMessages(sessionId: String): List<EncryptedMessage>

    @Query("UPDATE messages SET isRead = 1 WHERE sessionId = :sessionId AND direction = 0 AND isRead = 0")
    suspend fun markAllReceivedAsRead(sessionId: String)

    // Deliberately scoped to :recipientId: only flips messages we actually sent
    // TO them, so a forged or replayed read receipt can never mark arbitrary
    // messages as read. (An unscoped variant existed alongside this one with no
    // callers — removed, so it can't be reached for by mistake.)
    @Query("UPDATE messages SET isRead = 1 WHERE clientMessageId IN (:clientMessageIds) AND direction = 1 AND recipientId = :recipientId")
    suspend fun markSentMessagesReadByClientIdsForRecipient(clientMessageIds: List<String>, recipientId: String)

    @Query("UPDATE messages SET isExpired = 1 WHERE expiresAt <= :currentTime AND isExpired = 0")
    suspend fun markAllExpiredMessages(currentTime: Long = System.currentTimeMillis()): Int

    @Query("DELETE FROM messages WHERE isExpired = 1")
    suspend fun deleteExpiredMessages(): Int

    @Query("SELECT * FROM messages WHERE sessionId IN (SELECT sessionId FROM sessions WHERE contactId = :contactId)")
    suspend fun getMessagesForContactOnce(contactId: String): List<EncryptedMessage>

    @Query("DELETE FROM messages WHERE sessionId IN (SELECT sessionId FROM sessions WHERE contactId = :contactId)")
    suspend fun deleteMessagesForContact(contactId: String)

    // ---- social message-interaction ops (reactions / edit / delete) ----

    @Query("SELECT * FROM messages WHERE clientMessageId = :clientId LIMIT 1")
    suspend fun getMessageByClientId(clientId: String): EncryptedMessage?

    @Query("UPDATE messages SET reactionsJson = :reactionsJson WHERE clientMessageId = :clientId")
    suspend fun updateReactions(clientId: String, reactionsJson: String?)

    // Delete-for-everyone: keep the row (so replies pointing at it still
    // resolve) but wipe its content and mark it a tombstone.
    @Query("UPDATE messages SET isDeleted = 1, metadataEncrypted = NULL, ciphertext = X'' WHERE clientMessageId = :clientId")
    suspend fun markDeletedByClientId(clientId: String)

    @Query("UPDATE messages SET metadataEncrypted = :newTextEncrypted, editedAt = :editedAt WHERE clientMessageId = :clientId")
    suspend fun applyEdit(clientId: String, newTextEncrypted: ByteArray, editedAt: Long)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteById(messageId: Long)
}

/**
 * Data Access Object for key bundle operations.
 */
@Dao
interface KeyBundleDao {

    @Query("SELECT * FROM key_bundles WHERE (contactId IS NULL OR contactId = '') AND isUsed = 0 ORDER BY preKeyId LIMIT 100")
    suspend fun getOurUnusedPreKeys(): List<KeyBundle>

    @Query("SELECT * FROM key_bundles WHERE (contactId IS NULL OR contactId = '') ORDER BY preKeyId DESC LIMIT 1")
    suspend fun getOurLastPreKeyId(): KeyBundle?

    @Query("SELECT * FROM key_bundles WHERE preKeyId = :preKeyId AND (contactId IS NULL OR contactId = '') LIMIT 1")
    suspend fun getOurPreKeyById(preKeyId: Int): KeyBundle?

    @Query("SELECT * FROM key_bundles WHERE contactId = :contactId AND isUsed = 0 LIMIT 1")
    suspend fun getUnusedRemotePreKey(contactId: String): KeyBundle?

    @Query("SELECT * FROM key_bundles WHERE contactId = :contactId")
    suspend fun getAllPreKeysForContact(contactId: String): List<KeyBundle>

    @Insert
    suspend fun insertKeyBundle(keyBundle: KeyBundle): Long

    @Query("UPDATE key_bundles SET isUsed = 1 WHERE preKeyId = :preKeyId AND (contactId IS NULL OR contactId = '')")
    suspend fun markOurPreKeyAsUsed(preKeyId: Int)

    @Query("DELETE FROM key_bundles WHERE contactId = :contactId")
    suspend fun deleteAllPreKeysForContact(contactId: String)
}

/**
 * Data Access Object for the durable outgoing-envelope queue.
 */
@Dao
interface OutboxDao {

    @Query("SELECT * FROM outbox ORDER BY createdAt ASC")
    suspend fun getAll(): List<OutboxEnvelope>

    @Query("SELECT * FROM outbox ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<OutboxEnvelope>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(envelope: OutboxEnvelope)

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM outbox")
    suspend fun deleteAll()
}

/**
 * Data Access Object for persisted Double-Ratchet sessions.
 */
@Dao
interface RatchetSessionDao {

    @Query("SELECT * FROM ratchet_sessions WHERE contactId = :contactId")
    suspend fun get(contactId: String): RatchetSession?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: RatchetSession)

    @Query("DELETE FROM ratchet_sessions WHERE contactId = :contactId")
    suspend fun delete(contactId: String)

    @Query("DELETE FROM ratchet_sessions")
    suspend fun deleteAll()
}
