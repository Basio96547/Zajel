package com.securemessenger.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * UserProfile - stores the local user's profile information.
 * All sensitive fields are encrypted before storage.
 */
@Entity(tableName = "user_profile")
data class UserProfile(
    @PrimaryKey
    val id: String = generateSecureId(),
    val publicKey: ByteArray,          // Identity public key (not encrypted, needed for key exchange)
    val secretKeyEncrypted: ByteArray, // Identity secret key (encrypted with master key)
    val signedPreKeyId: Int,
    val signedPreKeyPublic: ByteArray,
    val signedPreKeySecretEncrypted: ByteArray,
    // Post-quantum ML-KEM keypair for the hybrid handshake.
    val mlkemPublicKey: ByteArray = ByteArray(0),
    val mlkemSecretEncrypted: ByteArray = ByteArray(0),
    // Ed25519 signing keypair — signs this profile's signed prekey so a
    // recipient (or a compromised relay in between) can't swap it unnoticed.
    // Separate from `publicKey` above, which is an X25519 key used for DH and
    // can't be used to verify a signature.
    val signingPublicKey: ByteArray = ByteArray(0),
    val signingSecretKeyEncrypted: ByteArray = ByteArray(0),
    val createdAt: Long = System.currentTimeMillis(),
    val lastBackupAt: Long? = null,
    // Own profile photo, Keystore-encrypted like a contact's avatarEncrypted.
    // Never uploaded anywhere — there is no profile-photo-sync protocol.
    val avatarEncrypted: ByteArray? = null,
    // The friendly name typed during Setup — shown to me on my own Profile
    // card. Local-only, like the avatar: contacts still only ever learn my
    // username (there is no profile-broadcast protocol to push this to them).
    val displayNameEncrypted: ByteArray? = null
) {
    companion object {
        fun generateSecureId(): String {
            val random = java.security.SecureRandom()
            val bytes = ByteArray(16)
            random.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * Contact - stores contact information.
 * Contacts are identified by their public identity key.
 */
@Entity(tableName = "contacts")
data class Contact(
    @PrimaryKey
    val id: String,                    // Contact's user ID
    val publicKey: ByteArray,          // Identity public key
    // Signing (Ed25519) public key, pinned the first time we learn it (trust-
    // on-first-use) — later signed-prekey fetches are verified against this
    // same key, so a relay can't swap in a different signer unnoticed.
    val signingPublicKey: ByteArray? = null,
    val displayNameEncrypted: ByteArray, // Encrypted display name
    val avatarHash: String? = null,    // Hash of avatar (not the avatar itself for privacy)
    val isVerified: Boolean = false,   // Whether their key has been verified
    val verificationData: ByteArray? = null, // QR code verification data
    val addedAt: Long = System.currentTimeMillis(),
    val lastSeenAt: Long? = null,
    val isBlocked: Boolean = false,
    // Small (downscaled) avatar photo, Keystore-encrypted like displayNameEncrypted —
    // never leaves the device, never touches the relay. Null = show the gradient
    // initials avatar instead.
    val avatarEncrypted: ByteArray? = null,
    // Local-only display override; if set, shown instead of displayNameEncrypted
    // everywhere in the UI (the real name is still used for key/session lookups).
    val nicknameEncrypted: ByteArray? = null,
    val isMuted: Boolean = false,
    // Relay pair secrets. Each is 32 bytes that reached the other device by
    // being photographed off a QR code, never over a network, and each carries
    // exactly one direction of traffic (see MailboxToken).
    //
    // Scanned off THEIR QR — we deposit into the mailbox it addresses. Null for
    // contacts paired before relay support, or from an older QR: those have no
    // relay path at all and stay local-network-only.
    val relaySendSecretEncrypted: ByteArray? = null,
    // Minted by US and photographed by them — we listen on the mailbox it
    // addresses. Null until their first relay message actually lands in one of
    // our outstanding minted secrets, which is what tells us which one they
    // took; bound to this contact at that moment and never changed after.
    val relayRecvSecretEncrypted: ByteArray? = null
)

/**
 * Session - stores Signal Protocol session state for each contact.
 * Each session contains the ratchet state for E2EE.
 */
@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey
    val id: Long? = null,
    val contactId: String,             // Foreign key to contacts.id
    val sessionId: String,             // Unique session identifier
    val rootKeyEncrypted: ByteArray,   // Encrypted root key
    val chainKeySendEncrypted: ByteArray, // Encrypted send chain key
    val chainKeyReceiveEncrypted: ByteArray?, // Encrypted receive chain key
    val dhRatchetPublic: ByteArray,
    val dhRatchetSecretEncrypted: ByteArray,
    val sendChainCounter: Int,
    val receiveChainCounter: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis()
)

/**
 * EncryptedMessage - stores encrypted messages locally.
 * Messages are encrypted both with Signal Protocol and at-rest encryption.
 */
@Entity(tableName = "messages")
data class EncryptedMessage(
    @PrimaryKey
    val id: Long? = null,
    val sessionId: String,             // Foreign key to sessions.sessionId
    val senderId: String,
    val recipientId: String,
    val direction: Int,                // 0 = received, 1 = sent
    val type: Int,                     // 0 = text, 1 = image, 2 = system
    val ciphertext: ByteArray,         // Signal Protocol encrypted content
    val iv: ByteArray,                 // Initialization vector
    val timestamp: Long = System.currentTimeMillis(),
    val expiresAt: Long? = null,       // Self-destruct timestamp (null = never expires)
    val isRead: Boolean = false,
    val isExpired: Boolean = false,
    val metadataEncrypted: ByteArray? = null, // Additional encrypted metadata
    // Shared between sender and recipient so a read receipt (which travels by
    // this id) can mark the *sender's* copy of the message as read.
    val clientMessageId: String? = null,
    // --- Social message-interaction fields (all local; the whole row is
    //     SQLCipher-encrypted, and any sensitive text here is additionally
    //     Keystore-encrypted, matching how metadataEncrypted is handled). ---
    // Emoji reactions as a small JSON object {"me":"👍","them":"❤️"}; either
    // side may be absent. Just emoji, not sensitive content, so plaintext.
    val reactionsJson: String? = null,
    // If this message is a reply, the clientMessageId it replies to, plus a
    // short Keystore-encrypted snippet of that message cached for display so
    // we never have to decrypt/scan the whole thread to render the quote.
    val replyToClientId: String? = null,
    val replySnippetEncrypted: ByteArray? = null,
    // Delete-for-everyone tombstone: the row stays (so the ordering/quote
    // references survive) but renders as "حُذفت"; the text is wiped locally.
    val isDeleted: Boolean = false,
    // Non-null once the sender edits the text; UI shows a "معدّلة" marker.
    val editedAt: Long? = null
)

/**
 * RatchetSession - persisted Double-Ratchet session state for the messaging
 * client, so conversations survive an app restart. Secret material is encrypted
 * with the Android Keystore master key before being stored (on top of SQLCipher).
 */
@Entity(tableName = "ratchet_sessions")
data class RatchetSession(
    @PrimaryKey
    val contactId: String,
    val rootKeyEnc: ByteArray,
    val chainKeySendEnc: ByteArray,
    val chainKeyReceiveEnc: ByteArray?,
    val dhRatchetPublic: ByteArray,
    val dhRatchetSecretEnc: ByteArray,
    val theirRatchetPublic: ByteArray?,
    val sendChainCounter: Int,
    val receiveChainCounter: Int,
    val previousSendChainLength: Int,
    val initiatorEphemeralPublic: ByteArray?,
    val isInitiator: Boolean,
    val responderEphemeralHex: String?,
    val initiatorOtkId: Int?,
    val skippedKeysEnc: ByteArray?,
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * OutboxEnvelope - a sealed message/receipt envelope not yet acknowledged by
 * the relay. Persisted (not just held in memory) so an app restart, a killed
 * process, or the calculator disguise's background/foreground cycle (which
 * recreates the messaging client) can't silently drop an outgoing message
 * before it ever reaches the relay. Removed only when the relay's "ack" for
 * this id comes back.
 */
@Entity(tableName = "outbox")
data class OutboxEnvelope(
    @PrimaryKey
    val id: String,
    val recipientId: String,
    val envelope: String,
    val createdAt: Long = System.currentTimeMillis(),
    // Set only for plain chat messages (null for read receipts/control ops) so
    // the UI can show a "sending…" clock for exactly this message until the
    // relay's ack removes the row.
    val clientMessageId: String? = null
)

/**
 * KeyBundle - stores prekeys for the Signal Protocol.
 * Prekeys are used for initial key exchange with new contacts.
 */
@Entity(tableName = "key_bundles")
data class KeyBundle(
    @PrimaryKey
    val id: Long? = null,
    val contactId: String,             // Foreign key to contacts.id (null for our own prekeys)
    val preKeyId: Int,
    val publicKey: ByteArray,
    val secretKeyEncrypted: ByteArray?, // null for remote prekeys
    val isOneTime: Boolean = true,
    val isUsed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
