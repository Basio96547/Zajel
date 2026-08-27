package com.securemessenger.core.crypto

import com.securemessenger.core.crypto.LibsodiumWrapper
import javax.crypto.SecretKey

/**
 * SignalProtocol - end-to-end encryption via X3DH + the Double Ratchet.
 *
 * - X3DH establishes the initial shared root key.
 * - A symmetric-key ratchet (one-way KDF per message) gives Forward Secrecy.
 * - A Diffie-Hellman ratchet: whenever a party sees a new ratchet public key it
 *   mixes a fresh DH output into the root key, so a full state compromise heals
 *   once both parties exchange again — Post-Compromise Security.
 * - Out-of-order / skipped messages (within and across ratchet steps) decrypt via
 *   cached message keys, using the previous-chain-length header to bound skips.
 */
class SignalProtocol(
    private val identityKeyPair: IdentityKeyPair,
    private val signedPreKeyPair: PreKeyPair,
    private val oneTimePreKeys: List<PreKeyPair>
) {

    // Ratchet state
    private var dhRatchetPublicKey: ByteArray
    private var dhRatchetSecretKey: ByteArray
    private var chainKeySend: ByteArray
    private var chainKeyReceive: ByteArray? = null
    private var rootKey: ByteArray

    // The peer's current ratchet public key; a different one triggers a DH ratchet.
    private var theirRatchetPublicKey: ByteArray? = null

    // Message counters
    private var sendChainCounter: Int = 0
    private var receiveChainCounter: Int = 0
    // Length of our previous sending chain, sent in the header (PN) so the peer
    // can finish skipping the old chain before ratcheting.
    private var previousSendChainLength: Int = 0

    // X3DH ephemeral key (initiator only, sent with first message)
    private var initiatorEphemeralPublicKey: ByteArray? = null

    // Message keys for messages that arrived out of order or were skipped,
    // keyed by "hex(dhPublicKey):chainCounter". Lets a later/earlier message
    // still decrypt instead of desyncing the whole chain.
    private val skippedMessageKeys = mutableMapOf<String, ByteArray>()
    private val maxSkip = 1000
    // Hard ceiling on the TOTAL number of cached-but-unclaimed skipped keys
    // across the whole session — separate from maxSkip, which only bounds
    // how far a SINGLE message may jump ahead. Without this, a peer who
    // simply never sends the specific in-between counter values needed to
    // consume an entry (trivial: they control their own send timing) could
    // keep adding up to maxSkip fresh entries per message indefinitely —
    // each one persisted into the encrypted on-disk session row too (see
    // SecureRepository.saveRatchetSession) — a slow-burn memory/storage
    // exhaustion from a single contact. A modest multiple of maxSkip is
    // generous headroom for any plausible legitimate reordering (direct
    // socket vs. relay delivery racing, at most) without leaving the map
    // truly unbounded.
    private val maxTotalSkipped = maxSkip * 2

    init {
        // Start from our identity key; the real ratchet root/chain keys are
        // installed by initializeAsInitiator / initializeAsResponder (or
        // importState) before any message is sent. Deliberately NO secret key
        // material is derived here — the previous code seeded rootKey and
        // chainKeySend from *public* keys, so a caller that (incorrectly)
        // encrypted before initializing would have produced a message key that
        // anyone holding those public keys could reproduce. encryptMessage now
        // guards against that state instead.
        dhRatchetPublicKey = identityKeyPair.publicKey
        dhRatchetSecretKey = identityKeyPair.secretKey
        rootKey = ByteArray(0)
        chainKeySend = ByteArray(0)
    }

    /**
     * Initialize the protocol as the initiator (sender).
     * Performs the initial key exchange.
     */
    fun initializeAsInitiator(
        recipientIdentityKey: ByteArray,
        recipientSignedPreKey: ByteArray,
        recipientOneTimePreKey: ByteArray? = null,
        pqSharedSecret: ByteArray? = null
    ) {
        // Perform X3DH key agreement (Signal spec order)
        val ephemeralKeyPair = LibsodiumWrapper.generateKeyPair()

        // DH1: Our identity * Their signed prekey
        val dh1 = LibsodiumWrapper.deriveSharedSecret(
            identityKeyPair.secretKey, recipientSignedPreKey
        )

        // DH2: Our ephemeral * Their identity
        val dh2 = LibsodiumWrapper.deriveSharedSecret(
            ephemeralKeyPair.second, recipientIdentityKey
        )

        // DH3: Our ephemeral * Their signed prekey
        val dh3 = LibsodiumWrapper.deriveSharedSecret(
            ephemeralKeyPair.second, recipientSignedPreKey
        )

        // DH4 (if OTK used): Our ephemeral * Their OTK
        val dh4 = if (recipientOneTimePreKey != null) {
            LibsodiumWrapper.deriveSharedSecret(
                ephemeralKeyPair.second, recipientOneTimePreKey
            )
        } else {
            byteArrayOf()
        }

        // Combine all DH outputs, plus the post-quantum secret for a hybrid
        // (classical + ML-KEM) handshake.
        val combinedDh = dh1 + dh2 + dh3 + dh4 + (pqSharedSecret ?: byteArrayOf())
        val masterSecret = LibsodiumWrapper.hkdf(
            inputKeyMaterial = combinedDh,
            salt = byteArrayOf(),
            info = "X3DH".toByteArray(),
            length = 64
        )

        rootKey = masterSecret.copyOfRange(0, 32)

        // The X3DH ephemeral is only for X3DH; the Double Ratchet uses a fresh
        // ratchet keypair. The recipient's signed prekey is their initial ratchet
        // key, so the initiator performs the first DH ratchet against it now.
        initiatorEphemeralPublicKey = ephemeralKeyPair.first.clone()

        val ratchetKeyPair = LibsodiumWrapper.generateKeyPair()
        dhRatchetPublicKey = ratchetKeyPair.first
        dhRatchetSecretKey = ratchetKeyPair.second
        theirRatchetPublicKey = recipientSignedPreKey.clone()

        // Initial sending chain from DH(ourRatchet, theirSignedPreKey).
        val (newRoot, sendChain) = kdfRootKey(
            rootKey,
            LibsodiumWrapper.deriveSharedSecret(dhRatchetSecretKey, theirRatchetPublicKey!!)
        )
        rootKey = newRoot
        chainKeySend = sendChain
        chainKeyReceive = null
        sendChainCounter = 0
        receiveChainCounter = 0
        previousSendChainLength = 0
    }

    /**
     * Initialize the protocol as the responder (receiver).
     * Computes the same X3DH shared secret as the initiator.
     */
    fun initializeAsResponder(
        initiatorIdentityKey: ByteArray,
        initiatorEphemeralKey: ByteArray,
        ourSignedPreKeySecret: ByteArray,
        ourIdentitySecretKey: ByteArray,
        ourOneTimePreKeySecret: ByteArray? = null,
        pqSharedSecret: ByteArray? = null
    ) {
        // DH1: Our signed prekey * Their identity
        val dh1 = LibsodiumWrapper.deriveSharedSecret(
            ourSignedPreKeySecret, initiatorIdentityKey
        )

        // DH2: Our identity * Their ephemeral
        val dh2 = LibsodiumWrapper.deriveSharedSecret(
            ourIdentitySecretKey, initiatorEphemeralKey
        )

        // DH3: Our signed prekey * Their ephemeral
        val dh3 = LibsodiumWrapper.deriveSharedSecret(
            ourSignedPreKeySecret, initiatorEphemeralKey
        )

        // DH4 (if OTK used): Our OTK * Their ephemeral
        val dh4 = if (ourOneTimePreKeySecret != null) {
            LibsodiumWrapper.deriveSharedSecret(
                ourOneTimePreKeySecret, initiatorEphemeralKey
            )
        } else {
            byteArrayOf()
        }

        val combinedDh = dh1 + dh2 + dh3 + dh4 + (pqSharedSecret ?: byteArrayOf())
        val masterSecret = LibsodiumWrapper.hkdf(
            inputKeyMaterial = combinedDh,
            salt = byteArrayOf(),
            info = "X3DH".toByteArray(),
            length = 64
        )

        rootKey = masterSecret.copyOfRange(0, 32)

        // Our signed prekey is our initial ratchet keypair (the initiator did its
        // first DH ratchet against its public half). We have no chains yet; they
        // are derived when the first message (carrying the initiator's ratchet
        // public key) arrives and triggers our first DH ratchet.
        dhRatchetPublicKey = signedPreKeyPair.publicKey.clone()
        dhRatchetSecretKey = signedPreKeyPair.secretKey.clone()
        theirRatchetPublicKey = null
        chainKeySend = ByteArray(0)
        chainKeyReceive = null
        sendChainCounter = 0
        receiveChainCounter = 0
        previousSendChainLength = 0
    }

    /** Returns the initiator ephemeral public key to include in the first message. */
    fun getInitiatorEphemeralPublicKey(): ByteArray? = initiatorEphemeralPublicKey?.clone()

    /**
     * Encrypt a message using the current chain key.
     * Performs a symmetric key ratchet step.
     *
     * @return EncryptedMessage containing the ciphertext and metadata needed for decryption
     */
    fun encryptMessage(plaintext: ByteArray): EncryptedMessage {
        check(chainKeySend.isNotEmpty()) {
            "Sending chain not initialized — initializeAsInitiator/Responder (or importState) must run first"
        }
        // Derive message key from chain key
        val messageKey = deriveMessageKey(chainKeySend)

        // Encrypt with message key
        val ciphertext = LibsodiumWrapper.encryptSymmetric(plaintext, messageKey)

        // Ratchet chain key forward
        chainKeySend = advanceChainKey(chainKeySend)
        sendChainCounter++
        // Matches decryptMessageInternal's secureWipe of its own one-time
        // message key — this one was never wiped, leaving every outgoing
        // message's key recoverable from heap remnants for longer than
        // necessary on the send side specifically.
        LibsodiumWrapper.secureWipe(messageKey)

        return EncryptedMessage(
            ciphertext = ciphertext,
            chainCounter = sendChainCounter,
            dhPublicKey = dhRatchetPublicKey.clone(),
            previousChainLength = previousSendChainLength
        )
    }

    /**
     * Decrypt a message using the appropriate chain.
     *
     * Handles new DH ratchets, in-order messages, out-of-order/skipped messages
     * (by deriving and caching the intermediate message keys), and messages from
     * a previous chain (via the skipped-key cache).
     */
    fun decryptMessage(encryptedMessage: EncryptedMessage): ByteArray {
        // Decrypting must be ALL-OR-NOTHING. An incoming envelope is sealed to
        // us but its sender is not authenticated at this layer, so anyone on the
        // network can craft a well-formed frame carrying a new ratchet key. The
        // steps below mutate the ratchet (skip/DH-ratchet: new root key, chains,
        // counters) BEFORE the AEAD tag is verified. Without a rollback, a single
        // forged frame would advance—and desync—the session, then fail
        // decryption, permanently breaking every subsequent legitimate message
        // (a remote, unauthenticated denial-of-service). Snapshot the full state
        // up front and restore it on ANY failure so an unverifiable frame leaves
        // the session exactly as it was.
        val snapshot = exportState()
        return try {
            decryptMessageInternal(encryptedMessage)
        } catch (e: Exception) {
            importState(snapshot)
            throw e
        }
    }

    private fun decryptMessageInternal(encryptedMessage: EncryptedMessage): ByteArray {
        // 1) A message whose key we already derived and cached (arrived late).
        val cacheKey = skipKey(encryptedMessage.dhPublicKey, encryptedMessage.chainCounter)
        skippedMessageKeys.remove(cacheKey)?.let { messageKey ->
            val plaintext = LibsodiumWrapper.decryptSymmetric(encryptedMessage.ciphertext, messageKey)
            LibsodiumWrapper.secureWipe(messageKey)
            return plaintext
        }

        // 2) A new ratchet public key means the peer advanced the DH ratchet.
        //    Finish skipping the old receiving chain (up to PN), then ratchet.
        if (!encryptedMessage.dhPublicKey.contentEquals(theirRatchetPublicKey)) {
            skipReceiveChain(encryptedMessage.previousChainLength)
            dhRatchet(encryptedMessage.dhPublicKey)
        }

        val receiveChain = chainKeyReceive
            ?: throw IllegalStateException("No receive chain established")

        // 3) Duplicate / already-consumed and not cached.
        val target = encryptedMessage.chainCounter
        if (target <= receiveChainCounter) {
            throw IllegalStateException("Message key unavailable (duplicate or too old)")
        }
        if (target - receiveChainCounter - 1 > maxSkip) {
            throw IllegalStateException("Too many skipped messages")
        }

        // 4) Cache keys for messages skipped between what we have and this one.
        skipReceiveChain(target - 1)

        // 5) Derive this message's key, decrypt, ratchet the chain forward.
        val messageKey = deriveMessageKey(chainKeyReceive!!)
        val plaintext = LibsodiumWrapper.decryptSymmetric(encryptedMessage.ciphertext, messageKey)
        chainKeyReceive = advanceChainKey(chainKeyReceive!!)
        receiveChainCounter++
        LibsodiumWrapper.secureWipe(messageKey)

        return plaintext
    }

    /**
     * Advance the current receiving chain up to [untilCounter], caching each
     * intermediate message key so out-of-order messages can still be decrypted.
     */
    private fun skipReceiveChain(untilCounter: Int) {
        val chain = chainKeyReceive ?: return
        val theirKey = theirRatchetPublicKey ?: return
        if (untilCounter - receiveChainCounter > maxSkip) {
            throw IllegalStateException("Too many skipped messages")
        }
        if (skippedMessageKeys.size + (untilCounter - receiveChainCounter) > maxTotalSkipped) {
            throw IllegalStateException("Too many unclaimed skipped-message keys accumulated")
        }
        var current = chain
        while (receiveChainCounter < untilCounter) {
            skippedMessageKeys[skipKey(theirKey, receiveChainCounter + 1)] = deriveMessageKey(current)
            current = advanceChainKey(current)
            receiveChainCounter++
        }
        chainKeyReceive = current
    }

    /**
     * Perform a DH ratchet step: derive a new receiving chain from the peer's new
     * ratchet key, generate our own new ratchet key, and derive a new sending
     * chain. Mixing fresh DH output into the root key gives Post-Compromise
     * Security.
     */
    private fun dhRatchet(theirNewRatchetKey: ByteArray) {
        previousSendChainLength = sendChainCounter
        sendChainCounter = 0
        receiveChainCounter = 0
        theirRatchetPublicKey = theirNewRatchetKey.clone()

        // New receiving chain from DH(ourCurrentRatchet, theirNewRatchet).
        val (rootAfterRecv, recvChain) = kdfRootKey(
            rootKey,
            LibsodiumWrapper.deriveSharedSecret(dhRatchetSecretKey, theirRatchetPublicKey!!)
        )
        rootKey = rootAfterRecv
        chainKeyReceive = recvChain

        // Rotate our ratchet key and derive a matching new sending chain.
        val newRatchet = LibsodiumWrapper.generateKeyPair()
        dhRatchetPublicKey = newRatchet.first
        dhRatchetSecretKey = newRatchet.second
        val (rootAfterSend, sendChain) = kdfRootKey(
            rootKey,
            LibsodiumWrapper.deriveSharedSecret(dhRatchetSecretKey, theirRatchetPublicKey!!)
        )
        rootKey = rootAfterSend
        chainKeySend = sendChain
    }

    /** Root-key KDF: mixes a DH output into the root key, yielding (newRoot, chainKey). */
    private fun kdfRootKey(root: ByteArray, dhOutput: ByteArray): Pair<ByteArray, ByteArray> {
        val out = LibsodiumWrapper.hkdf(root + dhOutput, byteArrayOf(), "DoubleRatchetRoot".toByteArray(), 64)
        return out.copyOfRange(0, 32) to out.copyOfRange(32, 64)
    }

    /** Cache key for a skipped message: unique per (ratchet key, position). */
    private fun skipKey(dhPublicKey: ByteArray, counter: Int): String {
        return dhPublicKey.joinToString("") { "%02x".format(it) } + ":" + counter
    }

    /**
     * Derive a message key from the chain key.
     *
     * Uses a keyed hash of a domain-separation constant (0x01) under the
     * *current* chain key. Critically this MUST be independent of
     * [advanceChainKey]: previously both reduced to an unkeyed blake2b of the
     * chain key, so the message key equalled a hash of the *next* chain key —
     * meaning a compromise of the stored (already-advanced) chain key let an
     * attacker recompute the previous message key and decrypt the last message,
     * breaking Forward Secrecy. Keying with the chain key and a distinct
     * constant makes both outputs one-way and mutually underivable.
     */
    private fun deriveMessageKey(chainKey: ByteArray): ByteArray {
        return LibsodiumWrapper.blake2b(byteArrayOf(0x01), key = chainKey, length = 32)
    }

    /**
     * Advance the chain key to the next value — a keyed hash of a *different*
     * domain constant (0x02) under the current chain key. See [deriveMessageKey].
     */
    private fun advanceChainKey(chainKey: ByteArray): ByteArray {
        return LibsodiumWrapper.blake2b(byteArrayOf(0x02), key = chainKey, length = 32)
    }

    /**
     * Get the current state for serialization.
     * Should be encrypted before storage.
     */
    fun exportState(): SessionState {
        return SessionState(
            rootKey = rootKey.clone(),
            chainKeySend = chainKeySend.clone(),
            chainKeyReceive = chainKeyReceive?.clone(),
            dhRatchetPublicKey = dhRatchetPublicKey.clone(),
            dhRatchetSecretKey = dhRatchetSecretKey.clone(),
            sendChainCounter = sendChainCounter,
            receiveChainCounter = receiveChainCounter,
            theirRatchetPublicKey = theirRatchetPublicKey?.clone(),
            previousSendChainLength = previousSendChainLength,
            initiatorEphemeralPublicKey = initiatorEphemeralPublicKey?.clone(),
            skippedMessageKeys = skippedMessageKeys.mapValues { it.value.clone() }
        )
    }

    /**
     * Restore state from serialized data.
     */
    fun importState(state: SessionState) {
        rootKey = state.rootKey.clone()
        chainKeySend = state.chainKeySend.clone()
        chainKeyReceive = state.chainKeyReceive?.clone()
        dhRatchetPublicKey = state.dhRatchetPublicKey.clone()
        dhRatchetSecretKey = state.dhRatchetSecretKey.clone()
        sendChainCounter = state.sendChainCounter
        receiveChainCounter = state.receiveChainCounter
        theirRatchetPublicKey = state.theirRatchetPublicKey?.clone()
        previousSendChainLength = state.previousSendChainLength
        initiatorEphemeralPublicKey = state.initiatorEphemeralPublicKey?.clone()
        skippedMessageKeys.clear()
        state.skippedMessageKeys.forEach { (k, v) -> skippedMessageKeys[k] = v.clone() }
    }

    // ==================== Data Classes ====================

    data class IdentityKeyPair(
        val publicKey: ByteArray,
        val secretKey: ByteArray
    ) {
        init {
            require(publicKey.size == 32) { "Public key must be 32 bytes" }
            require(secretKey.size == 32) { "Secret key must be 32 bytes" }
        }

        companion object {
            fun generate(): IdentityKeyPair {
                val keyPair = LibsodiumWrapper.generateKeyPair()
                return IdentityKeyPair(keyPair.first, keyPair.second)
            }
        }
    }

    data class PreKeyPair(
        val id: Int,
        val publicKey: ByteArray,
        val secretKey: ByteArray
    ) {
        init {
            require(publicKey.size == 32) { "Public key must be 32 bytes" }
            require(secretKey.size == 32) { "Secret key must be 32 bytes" }
        }

        companion object {
            fun generate(id: Int): PreKeyPair {
                val keyPair = LibsodiumWrapper.generateKeyPair()
                return PreKeyPair(id, keyPair.first, keyPair.second)
            }
        }
    }

    data class EncryptedMessage(
        val ciphertext: ByteArray,
        val chainCounter: Int,
        val dhPublicKey: ByteArray,
        val previousChainLength: Int = 0
    )

    data class SessionState(
        var rootKey: ByteArray,
        var chainKeySend: ByteArray,
        var chainKeyReceive: ByteArray?,
        var dhRatchetPublicKey: ByteArray,
        var dhRatchetSecretKey: ByteArray,
        var sendChainCounter: Int,
        var receiveChainCounter: Int,
        var theirRatchetPublicKey: ByteArray? = null,
        var previousSendChainLength: Int = 0,
        var initiatorEphemeralPublicKey: ByteArray? = null,
        var skippedMessageKeys: Map<String, ByteArray> = emptyMap()
    )
}
