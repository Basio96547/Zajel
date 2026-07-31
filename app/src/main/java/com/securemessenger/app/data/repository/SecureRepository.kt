package com.securemessenger.app.data.repository

import android.content.Context
import com.securemessenger.app.crypto.AndroidKeyStoreManager
import com.securemessenger.core.crypto.ChatPayloads
import com.securemessenger.core.crypto.LibsodiumWrapper
import com.securemessenger.core.crypto.MediaCodec
import com.securemessenger.core.crypto.PqKem
import com.securemessenger.core.crypto.SignalProtocol
import com.securemessenger.app.data.local.SecureDatabase
import com.securemessenger.app.data.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * A Double-Ratchet session rebuilt from storage, plus the client bookkeeping
 * needed to resume it (whether we initiated, the establishing ephemeral, OTK id).
 */
data class LoadedRatchetSession(
    val protocol: SignalProtocol,
    val isInitiator: Boolean,
    val responderEphemeralHex: String?,
    val initiatorOtkId: Int?
)

/**
 * SecureRepository - manages all data operations with encryption/decryption.
 * Acts as the single source of truth for the application data layer.
 */
class SecureRepository(private val context: Context) {

    private var database: SecureDatabase? = null
    private var dbPassphrase: CharArray? = null

    private fun requireDb(): SecureDatabase =
        database ?: throw IllegalStateException("Repository used before initialize() was called")

    /**
     * Initialize the database with a secure passphrase.
     */
    suspend fun initialize(passphrase: CharArray) {
        withContext(Dispatchers.IO) {
            dbPassphrase = passphrase
            database = SecureDatabase.getInstance(context, passphrase)
        }
    }

    /**
     * Close the database and wipe the passphrase from memory.
     */
    fun close() {
        // Zero the actual passphrase CharArray in place. The old code mapped it
        // to a brand-new byte[] and wiped only that throwaway copy, leaving the
        // real passphrase live in the heap until GC.
        dbPassphrase?.let { java.util.Arrays.fill(it, 0.toChar()) }
        dbPassphrase = null
        SecureDatabase.closeDatabase()
        database = null
    }

    // ==================== User Profile ====================

    suspend fun createProfile(): UserProfile = withContext(Dispatchers.IO) {
        val identityKeyPair = SignalProtocol.IdentityKeyPair.generate()
        val signedPreKey = SignalProtocol.PreKeyPair.generate(0)

        val encryptedIdentitySecret = AndroidKeyStoreManager.encryptWithMasterKey(identityKeyPair.secretKey)
        val encryptedSignedPreKeySecret = AndroidKeyStoreManager.encryptWithMasterKey(signedPreKey.secretKey)

        // Post-quantum ML-KEM keypair for the hybrid handshake.
        val mlkem = PqKem.generateKeyPair()
        val encryptedMlkemSecret = AndroidKeyStoreManager.encryptWithMasterKey(mlkem.secretKey)

        // Ed25519 signing keypair — signs signedPreKeyPublic so contacts can
        // verify the relay handed them our real prekey, not a swapped-in one.
        val (signingPublic, signingSecret) = LibsodiumWrapper.generateSigningKeyPair()
        val encryptedSigningSecret = AndroidKeyStoreManager.encryptWithMasterKey(signingSecret)

        val profile = UserProfile(
            publicKey = identityKeyPair.publicKey,
            secretKeyEncrypted = encryptedIdentitySecret,
            signedPreKeyId = signedPreKey.id,
            signedPreKeyPublic = signedPreKey.publicKey,
            signedPreKeySecretEncrypted = encryptedSignedPreKeySecret,
            mlkemPublicKey = mlkem.publicKey,
            mlkemSecretEncrypted = encryptedMlkemSecret,
            signingPublicKey = signingPublic,
            signingSecretKeyEncrypted = encryptedSigningSecret
        )

        requireDb().userProfileDao().insertProfile(profile)
        profile
    }

    suspend fun getProfile(): UserProfile? = withContext(Dispatchers.IO) {
        database?.userProfileDao()?.getProfileOnce()
    }

    suspend fun getIdentityKeyPair(): SignalProtocol.IdentityKeyPair? = withContext(Dispatchers.IO) {
        val profile = getProfile() ?: return@withContext null

        val secretKey = AndroidKeyStoreManager.decryptWithMasterKey(profile.secretKeyEncrypted)
        SignalProtocol.IdentityKeyPair(profile.publicKey, secretKey)
    }

    suspend fun getSignedPreKeyPair(): SignalProtocol.PreKeyPair? = withContext(Dispatchers.IO) {
        val profile = getProfile() ?: return@withContext null

        val secretKey = AndroidKeyStoreManager.decryptWithMasterKey(profile.signedPreKeySecretEncrypted)
        SignalProtocol.PreKeyPair(profile.signedPreKeyId, profile.signedPreKeyPublic, secretKey)
    }

    /** Our post-quantum ML-KEM public key (published in the prekey bundle). */
    suspend fun getMlkemPublicKey(): ByteArray? = withContext(Dispatchers.IO) {
        getProfile()?.mlkemPublicKey?.takeIf { it.isNotEmpty() }
    }

    /** Our post-quantum ML-KEM secret, to decapsulate an initiator's ciphertext. */
    suspend fun getMlkemSecret(): ByteArray? = withContext(Dispatchers.IO) {
        val enc = getProfile()?.mlkemSecretEncrypted?.takeIf { it.isNotEmpty() } ?: return@withContext null
        AndroidKeyStoreManager.decryptWithMasterKey(enc)
    }

    /** Our Ed25519 signing public key (published in the prekey bundle). */
    suspend fun getSigningPublicKey(): ByteArray? = withContext(Dispatchers.IO) {
        getProfile()?.signingPublicKey?.takeIf { it.isNotEmpty() }
    }

    /** Our Ed25519 signing secret, to sign our published signedPreKey. */
    suspend fun getSigningSecretKey(): ByteArray? = withContext(Dispatchers.IO) {
        val enc = getProfile()?.signingSecretKeyEncrypted?.takeIf { it.isNotEmpty() } ?: return@withContext null
        AndroidKeyStoreManager.decryptWithMasterKey(enc)
    }

    suspend fun getCurrentSessionId(contactId: String): String? = withContext(Dispatchers.IO) {
        database?.sessionDao()?.getCurrentSession(contactId)?.sessionId
    }

    // ==================== Contacts ====================

    suspend fun addContact(contact: Contact) = withContext(Dispatchers.IO) {
        database?.contactDao()?.insertContact(contact)
    }

    fun getContacts(): Flow<List<Contact>> {
        return requireDb().contactDao().getAllContacts()
    }

    /** One-shot snapshot — used to rebuild the local-discovery token map, not for UI observation. */
    suspend fun getAllContactsOnce(): List<Contact> = withContext(Dispatchers.IO) {
        database?.contactDao()?.getAllContacts()?.first() ?: emptyList()
    }

    suspend fun getContact(contactId: String): Contact? = withContext(Dispatchers.IO) {
        database?.contactDao()?.getContact(contactId)
    }

    // ==================== Avatars (local-only, never transmitted) ====================
    // Nothing here ever touches the relay — there is no profile-photo-sync
    // protocol between contacts. A contact's avatar is exactly like a phone
    // address book photo: something *you* set locally, purely for your own
    // recognition, encrypted at rest the same way a display name is.

    suspend fun setContactAvatar(contactId: String, rawImageBytes: ByteArray?) = withContext(Dispatchers.IO) {
        val encrypted = rawImageBytes?.let { AndroidKeyStoreManager.encryptWithMasterKey(downscaleAvatar(it)) }
        database?.contactDao()?.setAvatar(contactId, encrypted)
    }

    suspend fun getContactAvatar(contactId: String): ByteArray? = withContext(Dispatchers.IO) {
        val encrypted = getContact(contactId)?.avatarEncrypted ?: return@withContext null
        try {
            AndroidKeyStoreManager.decryptWithMasterKey(encrypted)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun setMyAvatar(rawImageBytes: ByteArray?) = withContext(Dispatchers.IO) {
        val encrypted = rawImageBytes?.let { AndroidKeyStoreManager.encryptWithMasterKey(downscaleAvatar(it)) }
        database?.userProfileDao()?.setAvatar(encrypted)
    }

    suspend fun setMyDisplayName(name: String) = withContext(Dispatchers.IO) {
        val encrypted = name.trim().takeIf { it.isNotBlank() }?.let {
            AndroidKeyStoreManager.encryptWithMasterKey(it.toByteArray(Charsets.UTF_8))
        }
        database?.userProfileDao()?.setDisplayName(encrypted)
    }

    suspend fun getMyDisplayName(): String? = withContext(Dispatchers.IO) {
        val encrypted = getProfile()?.displayNameEncrypted ?: return@withContext null
        try {
            String(AndroidKeyStoreManager.decryptWithMasterKey(encrypted), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /** Reactive so a Profile screen updates the instant the photo changes. */
    fun getMyAvatarFlow(): Flow<ByteArray?> =
        requireDb().userProfileDao().getProfile().map { profile ->
            profile?.avatarEncrypted?.let {
                try { AndroidKeyStoreManager.decryptWithMasterKey(it) } catch (_: Exception) { null }
            }
        }

    /** Downscale + JPEG-compress before encrypting — avatars don't need full camera resolution. */
    private fun downscaleAvatar(rawImageBytes: ByteArray, maxDimension: Int = 256): ByteArray {
        val original = android.graphics.BitmapFactory.decodeByteArray(rawImageBytes, 0, rawImageBytes.size)
            ?: return rawImageBytes
        val scale = maxDimension.toFloat() / maxOf(original.width, original.height)
        val scaled = if (scale < 1f) {
            android.graphics.Bitmap.createScaledBitmap(
                original, (original.width * scale).toInt().coerceAtLeast(1),
                (original.height * scale).toInt().coerceAtLeast(1), true
            )
        } else original
        val out = java.io.ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
        if (scaled !== original) scaled.recycle()
        original.recycle()
        return out.toByteArray()
    }

    suspend fun verifyContact(contactId: String, verificationData: ByteArray) = withContext(Dispatchers.IO) {
        database?.contactDao()?.setVerified(contactId, true, verificationData)
    }

    suspend fun blockContact(contactId: String) = withContext(Dispatchers.IO) {
        database?.contactDao()?.setBlocked(contactId, true)
    }

    suspend fun unblockContact(contactId: String) = withContext(Dispatchers.IO) {
        database?.contactDao()?.setBlocked(contactId, false)
    }

    suspend fun setContactMuted(contactId: String, muted: Boolean) = withContext(Dispatchers.IO) {
        database?.contactDao()?.setMuted(contactId, muted)
    }

    suspend fun setContactNickname(contactId: String, nickname: String?) = withContext(Dispatchers.IO) {
        val encrypted = nickname?.takeIf { it.isNotBlank() }?.let {
            AndroidKeyStoreManager.encryptWithMasterKey(it.toByteArray(Charsets.UTF_8))
        }
        database?.contactDao()?.setNickname(contactId, encrypted)
    }

    suspend fun getContactNickname(contactId: String): String? = withContext(Dispatchers.IO) {
        val encrypted = getContact(contactId)?.nicknameEncrypted ?: return@withContext null
        try {
            String(AndroidKeyStoreManager.decryptWithMasterKey(encrypted), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Compare a scanned QR payload (hex-encoded public key) against a
     * contact's actual pinned public key — the real safety-number check.
     * Purely local: no network/session needed, unlike the message pipeline.
     */
    suspend fun verifyScannedKey(contactId: String, scannedPublicKeyHex: String): Boolean = withContext(Dispatchers.IO) {
        val contact = getContact(contactId) ?: return@withContext false
        val scannedBytes = try {
            scannedPublicKeyHex.trim().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (_: Exception) {
            return@withContext false
        }
        val expected = LibsodiumWrapper.blake2b(contact.publicKey, length = 32)
        val scanned = LibsodiumWrapper.blake2b(scannedBytes, length = 32)
        val matches = expected.contentEquals(scanned)
        if (matches) verifyContact(contactId, scannedBytes)
        matches
    }

    suspend fun deleteContact(contactId: String) = withContext(Dispatchers.IO) {
        database?.messageDao()?.getMessagesForContactOnce(contactId)?.forEach { deleteMediaBlobFor(it) }
        database?.messageDao()?.deleteMessagesForContact(contactId)
        database?.sessionDao()?.deleteAllSessionsForContact(contactId)
        database?.keyBundleDao()?.deleteAllPreKeysForContact(contactId)
        database?.ratchetSessionDao()?.delete(contactId)
        database?.contactDao()?.deleteContactById(contactId)
    }

    // ==================== Sessions ====================

    suspend fun createSession(contactId: String, protocol: SignalProtocol): String = withContext(Dispatchers.IO) {
        val state = protocol.exportState()
        val sessionId = "${contactId}_${System.currentTimeMillis()}"

        val encryptedRootKey = AndroidKeyStoreManager.encryptWithMasterKey(state.rootKey)
        val encryptedSendChainKey = AndroidKeyStoreManager.encryptWithMasterKey(state.chainKeySend)
        val encryptedReceiveChainKey = state.chainKeyReceive?.let {
            AndroidKeyStoreManager.encryptWithMasterKey(it)
        }
        val encryptedDhSecret = AndroidKeyStoreManager.encryptWithMasterKey(state.dhRatchetSecretKey)

        val session = Session(
            contactId = contactId,
            sessionId = sessionId,
            rootKeyEncrypted = encryptedRootKey,
            chainKeySendEncrypted = encryptedSendChainKey,
            chainKeyReceiveEncrypted = encryptedReceiveChainKey,
            dhRatchetPublic = state.dhRatchetPublicKey,
            dhRatchetSecretEncrypted = encryptedDhSecret,
            sendChainCounter = state.sendChainCounter,
            receiveChainCounter = state.receiveChainCounter
        )

        database?.sessionDao()?.insertSession(session)
        sessionId
    }

    suspend fun loadSession(sessionId: String): SignalProtocol? = withContext(Dispatchers.IO) {
        val session = database?.sessionDao()?.getSessionById(sessionId) ?: return@withContext null
        val identityKeyPair = getIdentityKeyPair() ?: return@withContext null
        val signedPreKeyPair = getSignedPreKeyPair() ?: return@withContext null

        val rootKey = AndroidKeyStoreManager.decryptWithMasterKey(session.rootKeyEncrypted)
        val sendChainKey = AndroidKeyStoreManager.decryptWithMasterKey(session.chainKeySendEncrypted)
        val receiveChainKey = session.chainKeyReceiveEncrypted?.let {
            AndroidKeyStoreManager.decryptWithMasterKey(it)
        }
        val dhSecret = AndroidKeyStoreManager.decryptWithMasterKey(session.dhRatchetSecretEncrypted)

        val protocol = SignalProtocol(
            identityKeyPair = identityKeyPair,
            signedPreKeyPair = signedPreKeyPair,
            oneTimePreKeys = emptyList()
        )

        protocol.importState(
            SignalProtocol.SessionState(
                rootKey = rootKey,
                chainKeySend = sendChainKey,
                chainKeyReceive = receiveChainKey,
                dhRatchetPublicKey = session.dhRatchetPublic,
                dhRatchetSecretKey = dhSecret,
                sendChainCounter = session.sendChainCounter,
                receiveChainCounter = session.receiveChainCounter
            )
        )

        protocol
    }

    suspend fun saveSessionState(sessionId: String, protocol: SignalProtocol) = withContext(Dispatchers.IO) {
        val state = protocol.exportState()

        val encryptedRootKey = AndroidKeyStoreManager.encryptWithMasterKey(state.rootKey)
        val encryptedSendChainKey = AndroidKeyStoreManager.encryptWithMasterKey(state.chainKeySend)
        val encryptedReceiveChainKey = state.chainKeyReceive?.let {
            AndroidKeyStoreManager.encryptWithMasterKey(it)
        }
        val encryptedDhSecret = AndroidKeyStoreManager.encryptWithMasterKey(state.dhRatchetSecretKey)

        database?.sessionDao()?.updateSessionState(
            sessionId = sessionId,
            rootKey = encryptedRootKey,
            sendKey = encryptedSendChainKey,
            receiveKey = encryptedReceiveChainKey,
            dhPublic = state.dhRatchetPublicKey,
            dhSecret = encryptedDhSecret,
            sendCounter = state.sendChainCounter,
            receiveCounter = state.receiveChainCounter,
            timestamp = System.currentTimeMillis()
        )
    }

    // ==================== Messages ====================

    suspend fun sendMessage(
        sessionId: String,
        senderId: String,
        recipientId: String,
        plaintext: ByteArray,
        ttlSeconds: Int? = null
    ): EncryptedMessage = withContext(Dispatchers.IO) {
        val protocol = loadSession(sessionId) ?: throw IllegalStateException("No session found")
        val encryptedMessage = protocol.encryptMessage(plaintext)
        saveSessionState(sessionId, protocol)

        val message = EncryptedMessage(
            sessionId = sessionId,
            senderId = senderId,
            recipientId = recipientId,
            direction = 1,
            type = 0,
            ciphertext = encryptedMessage.ciphertext,
            iv = byteArrayOf(),
            expiresAt = ttlSeconds?.let { System.currentTimeMillis() + it * 1000L },
            // Real "read" state comes only from the recipient's read receipt
            // (see markMessagesReadByClientIds) — not assumed at send time.
            isRead = false,
            metadataEncrypted = AndroidKeyStoreManager.encryptWithMasterKey(plaintext),
            clientMessageId = java.util.UUID.randomUUID().toString()
        )

        val messageId = database?.messageDao()?.insertMessage(message) ?: throw Exception("Failed to insert message")
        message.copy(id = messageId)
    }

    fun getMessages(sessionId: String): Flow<List<EncryptedMessage>> {
        return requireDb().messageDao().getMessagesForSession(sessionId)
    }

    fun getAllMessages(): Flow<List<EncryptedMessage>> {
        return requireDb().messageDao().getAllMessages()
    }

    /**
     * Persist a message that arrived (and was already decrypted) from the relay
     * so it shows up in the recipient's conversation. If the sender is unknown,
     * a contact is created automatically from the identity key in the envelope.
     *
     * @return the sessionId the message was stored under, or null if it could
     *         not be stored (unknown sender with no identity key).
     */
    suspend fun saveIncomingMessage(
        senderId: String,
        senderIdentityKey: ByteArray?,
        plaintext: ByteArray,
        ttlSeconds: Int? = null,
        clientMessageId: String? = null,
        senderUsername: String? = null,
        replyToClientId: String? = null,
        replySnippet: String? = null
    ): String? = withContext(Dispatchers.IO) {
        // Make sure we have a contact + prekeys so a session (and therefore a
        // stable sessionId shared with the conversation screen) can be created.
        if (getContact(senderId) == null) {
            val key = senderIdentityKey ?: return@withContext null
            addContact(
                Contact(
                    id = senderId,
                    publicKey = key,
                    // Prefer their claimed @username over the raw id so the chat
                    // list and conversation header show something recognizable.
                    displayNameEncrypted = AndroidKeyStoreManager.encryptWithMasterKey(
                        (senderUsername ?: senderId).toByteArray(Charsets.UTF_8)
                    )
                )
            )
            storeContactPrekeys(senderId, key, key)
        }

        val sessionId = getOrCreateSessionForContact(senderId)
        val userId = getUserId() ?: ""

        val message = EncryptedMessage(
            sessionId = sessionId,
            senderId = senderId,
            recipientId = userId,
            direction = 0, // received
            type = 0,
            ciphertext = ByteArray(0),
            iv = ByteArray(0),
            expiresAt = ttlSeconds?.let { System.currentTimeMillis() + it * 1000L },
            isRead = false,
            metadataEncrypted = AndroidKeyStoreManager.encryptWithMasterKey(plaintext),
            clientMessageId = clientMessageId,
            replyToClientId = replyToClientId,
            replySnippetEncrypted = replySnippet?.let {
                AndroidKeyStoreManager.encryptWithMasterKey(it.toByteArray(Charsets.UTF_8))
            }
        )

        database?.messageDao()?.insertMessage(message)
        sessionId
    }

    // ==================== Message interactions (reactions / reply / edit / delete) ====================

    /** Outgoing payload plus (for replies) the stored message row. */
    data class SentInteraction(val wirePayload: ByteArray, val message: EncryptedMessage? = null)

    /** Short display snippet of a message, for quoting it in a reply. */
    suspend fun snippetOf(clientMessageId: String): String = withContext(Dispatchers.IO) {
        val msg = database?.messageDao()?.getMessageByClientId(clientMessageId) ?: return@withContext ""
        decryptDisplayText(msg).take(80)
    }

    /** Send a reply that quotes an earlier message; stores it locally and returns the wire payload. */
    suspend fun sendReplyMessage(
        contactId: String,
        text: String,
        replyToClientId: String,
        ttlSeconds: Int? = null
    ): SentInteraction = withContext(Dispatchers.IO) {
        val sessionId = getOrCreateSessionForContact(contactId)
        val userId = getUserId() ?: throw IllegalStateException("User profile not found")
        val snippet = snippetOf(replyToClientId)

        val protocol = loadSession(sessionId) ?: throw IllegalStateException("No session found")
        // What's stored locally is just the reply's own text; the reply linkage
        // lives in the dedicated columns, not inside the encrypted text blob.
        val encrypted = protocol.encryptMessage(text.toByteArray(Charsets.UTF_8))
        saveSessionState(sessionId, protocol)

        val message = EncryptedMessage(
            sessionId = sessionId,
            senderId = userId,
            recipientId = contactId,
            direction = 1,
            type = 0,
            ciphertext = encrypted.ciphertext,
            iv = byteArrayOf(),
            expiresAt = ttlSeconds?.let { System.currentTimeMillis() + it * 1000L },
            isRead = false,
            metadataEncrypted = AndroidKeyStoreManager.encryptWithMasterKey(text.toByteArray(Charsets.UTF_8)),
            clientMessageId = UUID.randomUUID().toString(),
            replyToClientId = replyToClientId,
            replySnippetEncrypted = AndroidKeyStoreManager.encryptWithMasterKey(snippet.toByteArray(Charsets.UTF_8))
        )
        val id = database?.messageDao()?.insertMessage(message) ?: throw Exception("Failed to insert message")
        SentInteraction(ChatPayloads.buildReplyText(text, replyToClientId, snippet), message.copy(id = id))
    }

    /** Toggle my emoji reaction on a message locally and return the wire payload to mirror it to the peer. */
    suspend fun reactToMessage(targetClientId: String, emoji: String): ByteArray = withContext(Dispatchers.IO) {
        val msg = database?.messageDao()?.getMessageByClientId(targetClientId)
        val current = parseReactions(msg?.reactionsJson)
        if (emoji.isBlank()) current.remove("me") else current["me"] = emoji
        database?.messageDao()?.updateReactions(targetClientId, serializeReactions(current))
        ChatPayloads.buildReaction(targetClientId, emoji)
    }

    /** Edit the text of a message I sent, locally, and return the wire payload. */
    suspend fun editMessage(targetClientId: String, newText: String): ByteArray = withContext(Dispatchers.IO) {
        database?.messageDao()?.applyEdit(
            targetClientId,
            AndroidKeyStoreManager.encryptWithMasterKey(newText.toByteArray(Charsets.UTF_8)),
            System.currentTimeMillis()
        )
        ChatPayloads.buildEdit(targetClientId, newText)
    }

    /** Delete-for-everyone: tombstone the message locally and return the wire payload. */
    suspend fun deleteForEveryone(targetClientId: String): ByteArray = withContext(Dispatchers.IO) {
        database?.messageDao()?.getMessageByClientId(targetClientId)?.let { deleteMediaBlobFor(it) }
        database?.messageDao()?.markDeletedByClientId(targetClientId)
        ChatPayloads.buildDelete(targetClientId)
    }

    /** Delete only my local copy of a message — no network, peer keeps theirs. */
    suspend fun deleteForMe(messageId: Long) = withContext(Dispatchers.IO) {
        database?.messageDao()?.getMessage(messageId)?.let { deleteMediaBlobFor(it) }
        database?.messageDao()?.deleteById(messageId)
    }

    /** Apply a control op that arrived from the peer (react / edit / delete). */
    suspend fun applyIncomingControl(control: ChatPayloads.Control) = withContext(Dispatchers.IO) {
        val dao = database?.messageDao() ?: return@withContext
        when (control.op) {
            ChatPayloads.OP_REACT -> {
                val msg = dao.getMessageByClientId(control.targetClientId) ?: return@withContext
                val current = parseReactions(msg.reactionsJson)
                val emoji = control.emoji ?: ""
                if (emoji.isBlank()) current.remove("them") else current["them"] = emoji
                dao.updateReactions(control.targetClientId, serializeReactions(current))
            }
            ChatPayloads.OP_EDIT -> {
                val newText = control.text ?: return@withContext
                dao.applyEdit(
                    control.targetClientId,
                    AndroidKeyStoreManager.encryptWithMasterKey(newText.toByteArray(Charsets.UTF_8)),
                    System.currentTimeMillis()
                )
            }
            ChatPayloads.OP_DELETE -> dao.markDeletedByClientId(control.targetClientId)
        }
    }

    private fun parseReactions(json: String?): MutableMap<String, String> {
        if (json.isNullOrBlank()) return mutableMapOf()
        return try {
            val obj = org.json.JSONObject(json)
            val map = mutableMapOf<String, String>()
            obj.keys().forEach { k -> map[k] = obj.getString(k) }
            map
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private fun serializeReactions(map: Map<String, String>): String? {
        if (map.isEmpty()) return null
        val obj = org.json.JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        return obj.toString()
    }

    /** Decrypt a reply's cached snippet for display (empty if none/failed). */
    fun decryptReplySnippet(message: EncryptedMessage): String? {
        val enc = message.replySnippetEncrypted ?: return null
        return try {
            String(AndroidKeyStoreManager.decryptWithMasterKey(enc), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    // ==================== Media messages ====================

    companion object {
        /** Maximum raw (pre-encryption) file size accepted for a media message. */
        const val MAX_MEDIA_BYTES = 4 * 1024 * 1024
    }

    private fun mediaDir(): File = File(context.filesDir, "media").apply { mkdirs() }

    private fun saveMediaFile(ref: String, bytes: ByteArray) {
        File(mediaDir(), ref).writeBytes(bytes)
    }

    private fun readMediaFile(ref: String): ByteArray? {
        val file = File(mediaDir(), ref)
        return if (file.exists()) file.readBytes() else null
    }

    /**
     * If [message] is a media message, delete the encrypted blob it references
     * from disk. Text messages have no blob, so this is a no-op for them. Call
     * this on EVERY path that removes/tombstones a message — otherwise the
     * encrypted .bin file outlives the row and survives even a full wipe,
     * leaking the number/size/timestamps of exchanged media.
     */
    private fun deleteMediaBlobFor(message: EncryptedMessage) {
        val ref = decryptMediaDescriptor(message)?.ref ?: return
        try {
            File(mediaDir(), ref).delete()
        } catch (_: Exception) {
        }
    }

    data class SentMedia(val message: EncryptedMessage, val wirePayload: ByteArray)

    /**
     * Encrypt a file with a fresh random key (on top of the Double Ratchet that
     * will encrypt it again for transit), store the ciphertext locally, and
     * return both the local message row and the wire payload to hand to
     * [com.securemessenger.app.network.SecureMessagingClient.sendMessage].
     */
    suspend fun sendMediaMessage(
        contactId: String,
        fileBytes: ByteArray,
        mimeType: String,
        fileName: String,
        mediaType: Int,
        ttlSeconds: Int? = null,
        waveform: List<Float> = emptyList(),
        caption: String? = null
    ): SentMedia = withContext(Dispatchers.IO) {
        val sessionId = getOrCreateSessionForContact(contactId)
        val userId = getUserId() ?: throw IllegalStateException("User profile not found")

        val key = LibsodiumWrapper.generateSecretKey()
        val cipherBytes = LibsodiumWrapper.encryptSymmetric(fileBytes, key)
        val ref = "${UUID.randomUUID()}.bin"
        saveMediaFile(ref, cipherBytes)

        val localDescriptor = MediaCodec.buildLocalDescriptor(mediaType, mimeType, fileName, fileBytes.size, key, ref, waveform, caption)
        val message = EncryptedMessage(
            sessionId = sessionId,
            senderId = userId,
            recipientId = contactId,
            direction = 1,
            type = mediaType,
            ciphertext = ByteArray(0),
            iv = ByteArray(0),
            expiresAt = ttlSeconds?.let { System.currentTimeMillis() + it * 1000L },
            isRead = false,
            metadataEncrypted = AndroidKeyStoreManager.encryptWithMasterKey(localDescriptor),
            clientMessageId = UUID.randomUUID().toString()
        )
        val id = database?.messageDao()?.insertMessage(message) ?: throw Exception("Failed to insert message")

        val wirePayload = MediaCodec.buildWirePayload(mediaType, mimeType, fileName, key, cipherBytes, waveform, caption)
        SentMedia(message.copy(id = id), wirePayload)
    }

    /** Persist an incoming media message: store its ciphertext locally under a new reference. */
    suspend fun saveIncomingMediaMessage(
        senderId: String,
        senderIdentityKey: ByteArray?,
        media: MediaCodec.WireMedia,
        ttlSeconds: Int? = null,
        clientMessageId: String? = null,
        senderUsername: String? = null
    ): String? = withContext(Dispatchers.IO) {
        // MAX_MEDIA_BYTES is enforced on the SEND side (ConversationViewModel)
        // before a legitimate local pick/record ever reaches sendMediaMessage —
        // but a remote peer talks the wire protocol directly and isn't bound by
        // that check at all. Without a receive-side cap here, a malicious peer
        // could push an arbitrarily large ciphertext straight into local
        // storage and into the decoders (BitmapFactory/MediaMetadataRetriever)
        // that later parse it. crypto_secretbox adds only ~40 bytes of
        // overhead, so comparing raw ciphertext size against the same limit is
        // effectively a plaintext-size cap.
        if (media.ciphertext.size > MAX_MEDIA_BYTES + 64) {
            android.util.Log.w("SecureRepository", "rejecting oversized incoming media from $senderId (${media.ciphertext.size} bytes)")
            return@withContext null
        }

        if (getContact(senderId) == null) {
            val key = senderIdentityKey ?: return@withContext null
            addContact(
                Contact(
                    id = senderId,
                    publicKey = key,
                    displayNameEncrypted = AndroidKeyStoreManager.encryptWithMasterKey(
                        (senderUsername ?: senderId).toByteArray(Charsets.UTF_8)
                    )
                )
            )
            storeContactPrekeys(senderId, key, key)
        }

        val sessionId = getOrCreateSessionForContact(senderId)
        val userId = getUserId() ?: ""

        val ref = "${UUID.randomUUID()}.bin"
        saveMediaFile(ref, media.ciphertext)
        val localDescriptor = MediaCodec.buildLocalDescriptor(
            media.mediaType, media.mimeType, media.fileName, media.ciphertext.size, media.key, ref, media.waveform, media.caption
        )

        val message = EncryptedMessage(
            sessionId = sessionId,
            senderId = senderId,
            recipientId = userId,
            direction = 0,
            type = media.mediaType,
            ciphertext = ByteArray(0),
            iv = ByteArray(0),
            expiresAt = ttlSeconds?.let { System.currentTimeMillis() + it * 1000L },
            isRead = false,
            metadataEncrypted = AndroidKeyStoreManager.encryptWithMasterKey(localDescriptor),
            clientMessageId = clientMessageId
        )

        database?.messageDao()?.insertMessage(message)
        sessionId
    }

    /** Decrypt the (small) local descriptor for a media message row. */
    fun decryptMediaDescriptor(message: EncryptedMessage): MediaCodec.LocalMedia? {
        val bytes = message.metadataEncrypted ?: return null
        return try {
            MediaCodec.parseLocalDescriptor(AndroidKeyStoreManager.decryptWithMasterKey(bytes))
        } catch (_: Exception) {
            null
        }
    }

    /** Read and decrypt the actual file bytes referenced by a local media descriptor. */
    suspend fun loadDecryptedMediaBytes(local: MediaCodec.LocalMedia): ByteArray? = withContext(Dispatchers.IO) {
        val cipherBytes = readMediaFile(local.ref) ?: return@withContext null
        try {
            LibsodiumWrapper.decryptSymmetric(cipherBytes, local.key)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Mark every unread received message in a conversation as read locally and
     * return the (non-null) clientMessageIds so the caller can notify the
     * sender with a read receipt.
     */
    suspend fun markReceivedAsReadAndGetIds(sessionId: String): List<String> = withContext(Dispatchers.IO) {
        val unread = database?.messageDao()?.getUnreadReceivedMessages(sessionId) ?: emptyList()
        if (unread.isEmpty()) return@withContext emptyList()
        database?.messageDao()?.markAllReceivedAsRead(sessionId)
        unread.mapNotNull { it.clientMessageId }
    }

    /**
     * Apply an incoming read receipt: mark as read only the messages we actually
     * sent TO [senderId] (the contact reporting the read). Scoping by recipient
     * means a forged receipt can't flip messages sent to anyone else.
     */
    suspend fun markMessagesReadByClientIds(clientMessageIds: List<String>, senderId: String) = withContext(Dispatchers.IO) {
        if (clientMessageIds.isNotEmpty()) {
            database?.messageDao()?.markSentMessagesReadByClientIdsForRecipient(clientMessageIds, senderId)
        }
    }

    fun decryptDisplayText(message: EncryptedMessage): String {
        // Media rows store their (small) MediaCodec JSON descriptor in this same
        // field, not a chat-worthy string — show a label instead of raw JSON.
        when (message.type) {
            MediaCodec.TYPE_IMAGE -> return "📷 صورة"
            MediaCodec.TYPE_VIDEO -> return "🎥 فيديو"
            MediaCodec.TYPE_AUDIO -> return "🎤 رسالة صوتية"
        }
        return try {
            message.metadataEncrypted?.let { encrypted ->
                val plaintext = AndroidKeyStoreManager.decryptWithMasterKey(encrypted)
                String(plaintext, Charsets.UTF_8)
            } ?: "••••••••••••••"
        } catch (_: Exception) {
            "••••••••••••••"
        }
    }

    suspend fun getUserId(): String? = withContext(Dispatchers.IO) {
        getProfile()?.id
    }

    suspend fun getOrCreateSessionForContact(contactId: String): String = withContext(Dispatchers.IO) {
        val existing = database?.sessionDao()?.getCurrentSession(contactId)
        if (existing != null) return@withContext existing.sessionId

        val identityKeyPair = getIdentityKeyPair()
            ?: throw IllegalStateException("Identity key not found")
        val signedPreKeyPair = getSignedPreKeyPair()
            ?: throw IllegalStateException("Signed prekey not found")
        val contact = getContact(contactId)
            ?: throw IllegalStateException("Contact not found")

        val remoteSignedPreKey = database?.keyBundleDao()
            ?.getAllPreKeysForContact(contactId)
            ?.firstOrNull { !it.isOneTime }
            ?.publicKey ?: contact.publicKey

        val remoteOneTimePreKey = database?.keyBundleDao()
            ?.getUnusedRemotePreKey(contactId)
            ?.publicKey

        val protocol = SignalProtocol(
            identityKeyPair = identityKeyPair,
            signedPreKeyPair = signedPreKeyPair,
            // SignalProtocol never actually reads its own one-time-prekey list
            // (it consumes the *recipient's* OTK below, not this one) — this
            // used to decrypt every one of our own unused OTK secrets out of
            // the Keystore on every session creation for no reason, needlessly
            // exposing single-use secrets in memory ahead of when they're
            // actually needed.
            oneTimePreKeys = emptyList()
        )

        protocol.initializeAsInitiator(
            recipientIdentityKey = contact.publicKey,
            recipientSignedPreKey = remoteSignedPreKey,
            recipientOneTimePreKey = remoteOneTimePreKey
        )

        createSession(contactId, protocol)
    }

    suspend fun sendMessageToContact(
        contactId: String,
        plaintext: String,
        ttlSeconds: Int? = null
    ): EncryptedMessage = withContext(Dispatchers.IO) {
        val sessionId = getOrCreateSessionForContact(contactId)
        val userId = getUserId() ?: throw IllegalStateException("User profile not found")
        sendMessage(sessionId, userId, contactId, plaintext.toByteArray(Charsets.UTF_8), ttlSeconds)
    }

    suspend fun storeContactPrekeys(
        contactId: String,
        identityKey: ByteArray,
        signedPreKey: ByteArray,
        oneTimePreKey: ByteArray? = null,
        signingPublicKey: ByteArray? = null
    ) = withContext(Dispatchers.IO) {
        database?.keyBundleDao()?.deleteAllPreKeysForContact(contactId)

        database?.keyBundleDao()?.insertKeyBundle(
            KeyBundle(
                contactId = contactId,
                preKeyId = 0,
                publicKey = signedPreKey,
                secretKeyEncrypted = null,
                isOneTime = false,
                isUsed = false
            )
        )

        oneTimePreKey?.let { otk ->
            database?.keyBundleDao()?.insertKeyBundle(
                KeyBundle(
                    contactId = contactId,
                    preKeyId = 1,
                    publicKey = otk,
                    secretKeyEncrypted = null,
                    isOneTime = true,
                    isUsed = false
                )
            )
        }

        val existing = getContact(contactId)
        if (existing == null) {
            addContact(
                Contact(
                    id = contactId,
                    publicKey = identityKey,
                    signingPublicKey = signingPublicKey,
                    displayNameEncrypted = AndroidKeyStoreManager.encryptWithMasterKey(
                        contactId.toByteArray(Charsets.UTF_8)
                    )
                )
            )
        } else if (existing.signingPublicKey == null && signingPublicKey != null) {
            // Fill in the pin for a contact added before this field existed.
            database?.contactDao()?.updateContact(existing.copy(signingPublicKey = signingPublicKey))
        }
    }

    suspend fun getPublicKeyFingerprint(): String? = withContext(Dispatchers.IO) {
        val profile = getProfile() ?: return@withContext null
        val hash = LibsodiumWrapper.blake2b(profile.publicKey, length = 32)
        hash.take(4).joinToString("") { "%02x".format(it) }
    }

    suspend fun getPublicKeyHex(): String? = withContext(Dispatchers.IO) {
        getProfile()?.publicKey?.joinToString("") { "%02x".format(it) }
    }

    suspend fun addContactWithPublicKey(
        contactId: String,
        publicKeyHex: String,
        displayName: String,
        relaySendSecret: ByteArray? = null
    ) = withContext(Dispatchers.IO) {
        val publicKey = hexToBytes(publicKeyHex)
        require(publicKey.size == 32) { "Public key must be 32 bytes (64 hex chars)" }

        addContact(
            Contact(
                id = contactId,
                publicKey = publicKey,
                displayNameEncrypted = AndroidKeyStoreManager.encryptWithMasterKey(
                    displayName.toByteArray(Charsets.UTF_8)
                ),
                relaySendSecretEncrypted = relaySendSecret?.let {
                    AndroidKeyStoreManager.encryptWithMasterKey(it)
                }
            )
        )

        storeContactPrekeys(contactId, publicKey, publicKey)
    }

    /**
     * The secret addressing the mailbox we deposit into for this contact. Null
     * means they were paired before relay support (or from an older QR) — they
     * are reachable over the local network only, and nothing about them is ever
     * handed to the relay.
     */
    suspend fun getRelaySendSecret(contactId: String): ByteArray? =
        decryptContactSecret(getContact(contactId)?.relaySendSecretEncrypted)

    /** The secret addressing the mailbox we listen on for this contact, once they've used it. */
    suspend fun getRelayRecvSecret(contactId: String): ByteArray? =
        decryptContactSecret(getContact(contactId)?.relayRecvSecretEncrypted)

    private suspend fun decryptContactSecret(encrypted: ByteArray?): ByteArray? = withContext(Dispatchers.IO) {
        val enc = encrypted ?: return@withContext null
        try {
            AndroidKeyStoreManager.decryptWithMasterKey(enc)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Bind one of our minted secrets to the contact who turned out to be using
     * it. Called the first time a relay message actually lands in that mailbox
     * — before then we only know we handed the secret to *somebody*.
     *
     * Ignored if this contact already has an inbound secret, so a later message
     * arriving on a stale, still-pending secret can never repoint an
     * established channel.
     */
    suspend fun bindRelayRecvSecret(contactId: String, secret: ByteArray) = withContext(Dispatchers.IO) {
        val contact = getContact(contactId) ?: return@withContext
        if (contact.relayRecvSecretEncrypted != null) return@withContext
        addContact(
            contact.copy(relayRecvSecretEncrypted = AndroidKeyStoreManager.encryptWithMasterKey(secret))
        )
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "").replace(":", "")
        require(clean.length % 2 == 0) { "Invalid hex string" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    suspend fun deleteExpiredMessages(): Int = withContext(Dispatchers.IO) {
        val dao = database?.messageDao() ?: return@withContext 0
        // Delete the on-disk media blobs of expiring messages BEFORE their rows
        // (and thus the descriptors that name the blob files) are removed.
        dao.getExpiredMessages().forEach { deleteMediaBlobFor(it) }
        dao.markAllExpiredMessages()
        dao.deleteExpiredMessages()
    }

    // ==================== Key Bundles ====================

    suspend fun generatePreKeys(count: Int = 100): List<KeyBundle> = withContext(Dispatchers.IO) {
        val bundles = mutableListOf<KeyBundle>()
        val lastPreKey = database?.keyBundleDao()?.getOurLastPreKeyId()
        var startId = lastPreKey?.preKeyId ?: 0

        for (i in 1..count) {
            val preKey = SignalProtocol.PreKeyPair.generate(startId + i)
            val encryptedSecret = AndroidKeyStoreManager.encryptWithMasterKey(preKey.secretKey)

            val bundle = KeyBundle(
                contactId = "", // Use empty string instead of null if it's our own
                preKeyId = preKey.id,
                publicKey = preKey.publicKey,
                secretKeyEncrypted = encryptedSecret,
                isOneTime = true,
                isUsed = false
            )

            database?.keyBundleDao()?.insertKeyBundle(bundle)
            bundles.add(bundle)
        }

        bundles
    }

    suspend fun getUnusedPreKeys(): List<KeyBundle> = withContext(Dispatchers.IO) {
        database?.keyBundleDao()?.getOurUnusedPreKeys() ?: emptyList()
    }

    /**
     * Return the decrypted secret of one of our own one-time prekeys by its id,
     * needed by the responder to complete X3DH (the DH4 term). Null if unknown.
     */
    suspend fun getOurPreKeySecret(preKeyId: Int): ByteArray? = withContext(Dispatchers.IO) {
        val bundle = database?.keyBundleDao()?.getOurPreKeyById(preKeyId) ?: return@withContext null
        bundle.secretKeyEncrypted?.let { AndroidKeyStoreManager.decryptWithMasterKey(it) }
    }

    suspend fun markPreKeyAsUsed(preKeyId: Int) = withContext(Dispatchers.IO) {
        database?.keyBundleDao()?.markOurPreKeyAsUsed(preKeyId)
    }

    // ==================== Ratchet session persistence ====================

    /**
     * Persist a live Double-Ratchet session so the conversation survives an app
     * restart. Secret key material is encrypted with the Keystore master key.
     */
    suspend fun saveRatchetSession(
        contactId: String,
        protocol: SignalProtocol,
        isInitiator: Boolean,
        responderEphemeralHex: String?,
        initiatorOtkId: Int?
    ) = withContext(Dispatchers.IO) {
        val s = protocol.exportState()
        val skippedJson = org.json.JSONObject()
        s.skippedMessageKeys.forEach { (k, v) ->
            skippedJson.put(k, android.util.Base64.encodeToString(v, android.util.Base64.NO_WRAP))
        }
        val entity = RatchetSession(
            contactId = contactId,
            rootKeyEnc = AndroidKeyStoreManager.encryptWithMasterKey(s.rootKey),
            chainKeySendEnc = AndroidKeyStoreManager.encryptWithMasterKey(s.chainKeySend),
            chainKeyReceiveEnc = s.chainKeyReceive?.let { AndroidKeyStoreManager.encryptWithMasterKey(it) },
            dhRatchetPublic = s.dhRatchetPublicKey,
            dhRatchetSecretEnc = AndroidKeyStoreManager.encryptWithMasterKey(s.dhRatchetSecretKey),
            theirRatchetPublic = s.theirRatchetPublicKey,
            sendChainCounter = s.sendChainCounter,
            receiveChainCounter = s.receiveChainCounter,
            previousSendChainLength = s.previousSendChainLength,
            initiatorEphemeralPublic = s.initiatorEphemeralPublicKey,
            isInitiator = isInitiator,
            responderEphemeralHex = responderEphemeralHex,
            initiatorOtkId = initiatorOtkId,
            skippedKeysEnc = AndroidKeyStoreManager.encryptWithMasterKey(
                skippedJson.toString().toByteArray(Charsets.UTF_8)
            )
        )
        database?.ratchetSessionDao()?.upsert(entity)
    }

    /**
     * Rebuild a persisted Double-Ratchet session, or null if none is stored.
     */
    suspend fun loadRatchetSession(contactId: String): LoadedRatchetSession? = withContext(Dispatchers.IO) {
        val e = database?.ratchetSessionDao()?.get(contactId) ?: return@withContext null
        val identity = getIdentityKeyPair() ?: return@withContext null
        val signedPreKey = getSignedPreKeyPair() ?: return@withContext null

        val skipped = mutableMapOf<String, ByteArray>()
        e.skippedKeysEnc?.let { enc ->
            val json = org.json.JSONObject(String(AndroidKeyStoreManager.decryptWithMasterKey(enc), Charsets.UTF_8))
            json.keys().forEach { k -> skipped[k] = android.util.Base64.decode(json.getString(k), android.util.Base64.NO_WRAP) }
        }

        val state = SignalProtocol.SessionState(
            rootKey = AndroidKeyStoreManager.decryptWithMasterKey(e.rootKeyEnc),
            chainKeySend = AndroidKeyStoreManager.decryptWithMasterKey(e.chainKeySendEnc),
            chainKeyReceive = e.chainKeyReceiveEnc?.let { AndroidKeyStoreManager.decryptWithMasterKey(it) },
            dhRatchetPublicKey = e.dhRatchetPublic,
            dhRatchetSecretKey = AndroidKeyStoreManager.decryptWithMasterKey(e.dhRatchetSecretEnc),
            sendChainCounter = e.sendChainCounter,
            receiveChainCounter = e.receiveChainCounter,
            theirRatchetPublicKey = e.theirRatchetPublic,
            previousSendChainLength = e.previousSendChainLength,
            initiatorEphemeralPublicKey = e.initiatorEphemeralPublic,
            skippedMessageKeys = skipped
        )
        val protocol = SignalProtocol(identity, signedPreKey, emptyList())
        protocol.importState(state)
        LoadedRatchetSession(protocol, e.isInitiator, e.responderEphemeralHex, e.initiatorOtkId)
    }

    // ==================== Outbox (durable outgoing queue) ====================

    /**
     * Persist an outgoing sealed envelope before attempting to send it. Kept
     * until [deleteOutboxEnvelope] is called for the same id (the relay's ack),
     * so a lost connection or killed process can't silently drop it.
     */
    suspend fun saveOutboxEnvelope(id: String, recipientId: String, envelope: String, clientMessageId: String? = null) = withContext(Dispatchers.IO) {
        database?.outboxDao()?.insert(
            OutboxEnvelope(id = id, recipientId = recipientId, envelope = envelope, clientMessageId = clientMessageId)
        )
    }

    suspend fun deleteOutboxEnvelope(id: String) = withContext(Dispatchers.IO) {
        database?.outboxDao()?.deleteById(id)
    }

    suspend fun getAllOutboxEnvelopes(): List<OutboxEnvelope> = withContext(Dispatchers.IO) {
        database?.outboxDao()?.getAll() ?: emptyList()
    }

    /** clientMessageId set of every message still awaiting the relay's ack — drives the "sending…" tick. */
    fun observePendingClientMessageIds(): Flow<Set<String>> =
        requireDb().outboxDao().observeAll().map { list -> list.mapNotNull { it.clientMessageId }.toSet() }

    // ==================== Cleanup ====================

    suspend fun wipeAllData() = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
        // Runs as NonCancellable: a duress wipe must complete even if the app is
        // torn down the instant it starts.
        database?.apply {
            clearAllTables()
            close()
        }
        SecureDatabase.wipeDatabase(context)
        database = null

        // Everything that lives OUTSIDE the database file must go too, or the
        // wipe (including a duress wipe the user believes destroyed everything)
        // leaves recoverable traces:
        //  - encrypted media blobs under filesDir/media
        //  - anything decrypted to cache for viewing/sharing/capture
        //  - the access & duress codes AND the DB passphrase in SecurePreferences
        runCatching { mediaDir().deleteRecursively() }
        runCatching { File(context.cacheDir, "decrypted_media").deleteRecursively() }
        runCatching { File(context.cacheDir, "camera_capture").deleteRecursively() }
        runCatching { File(context.cacheDir, "qr_share").deleteRecursively() }
        dbPassphrase?.let { java.util.Arrays.fill(it, ' ') }
        dbPassphrase = null
        runCatching { com.securemessenger.app.security.SecurePreferences.clearAll(context) }
        // Last, once nothing else needs it: the Keystore master key itself.
        // Everything it protected is already gone above; leaving the entry
        // behind would be the one trace that survives a "complete" wipe.
        runCatching { AndroidKeyStoreManager.wipeKeys() }
    }
}
