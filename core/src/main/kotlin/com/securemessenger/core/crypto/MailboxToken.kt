package com.securemessenger.core.crypto

/**
 * MailboxToken — how a pair of devices address each other through the blind
 * relay without ever telling it who they are.
 *
 * The relay is a dumb dead-drop: you hand it an opaque blob and a 16-byte
 * mailbox id, and it hands that blob to whoever asks for that same id. It has
 * no accounts, no registration, and never sees a user id or a public key.
 *
 * A mailbox id is a keyed hash of a **pair secret** — 32 random bytes minted
 * when a QR code is displayed and carried to the other device by the scan
 * itself, so it never crosses any network. Three properties follow:
 *
 *  - The relay cannot compute it (it has no access to the secret), so it can't
 *    tell which mailboxes belong to the same conversation.
 *  - Nobody else can compute it either — not even a mutual contact who knows
 *    both parties' public keys. That is exactly why the id comes from a random
 *    shared secret rather than from the two identity keys.
 *  - It rolls over every [ROTATION_MS], so a relay watching for a month sees a
 *    stream of unrelated ids rather than one long-lived pseudonym.
 *
 * ONE SECRET IS ONE-WAY. Pairing is mutual — each device scans the other's QR
 * — so two secrets exist per conversation, and rather than agree on one of
 * them, each carries a single direction: **whoever minted a secret listens on
 * it, whoever scanned it sends to it.** That removes any need for the two
 * devices to negotiate which secret won, and it means the minting side can bind
 * a secret to a contact lazily, on the first message that actually arrives in
 * it — it never has to know in advance which of its displayed QR codes was
 * photographed.
 *
 * WHY A DAY AND NOT AN HOUR. The relay stores an undelivered blob for 48 hours
 * so a recipient who was offline still gets it. A receiver can only collect
 * from ids it still computes, so the rotation window has to be coarse enough
 * that a message deposited before a rollover is still reachable afterwards.
 *
 * THE TOLERANCE HAS TO BE ±2 WINDOWS, NOT ±1. The receiver's tolerated range
 * is centered on ITS OWN current bucket, which keeps moving forward — it is
 * not centered on the bucket the message was actually sent in. A message
 * sent in the last instant of bucket B is still reachable once the receiver
 * reaches bucket B+1 (±1 covers that), but the receiver reaches B+2 only ~24h
 * later — nowhere near the 48h the relay actually holds it for — and ±1
 * stops listening on B at that point. ±2 windows is what actually guarantees
 * the full 48h retention is covered regardless of *when inside its bucket* a
 * message was sent: worst case (sent an instant before a rollover), the
 * receiver only stops listening on that bucket 3 windows (~72h) after the
 * rollover, i.e. ~48h after the message was actually sent — exactly the
 * retention period, with no gap. (The local mDNS discovery token still
 * rotates hourly — that one is live-only, with nothing stored and nothing to
 * outlive a window, so this phase problem doesn't apply to it.)
 */
object MailboxToken {

    /** How long one mailbox id stays valid. Must be coarse enough to cover the relay's blob retention — see the class note. */
    const val ROTATION_MS = 24 * 60 * 60 * 1000L

    /** Length of a pair secret in bytes. */
    const val PAIR_SECRET_BYTES = 32

    /**
     * How many neighbouring windows either side we also listen on — see the
     * class doc for why this must be 2, not 1, to actually guarantee the
     * relay's full 48-hour retention window is covered regardless of when
     * inside its bucket a message was sent.
     */
    const val BUCKET_TOLERANCE = 2L

    fun currentBucket(nowMillis: Long = System.currentTimeMillis()): Long = nowMillis / ROTATION_MS

    /** Freshly minted whenever a QR code is displayed. Never transmitted over any network. */
    fun newPairSecret(): ByteArray {
        val bytes = ByteArray(PAIR_SECRET_BYTES)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes
    }

    fun pairSecretToHex(secret: ByteArray): String = secret.joinToString("") { "%02x".format(it) }

    fun pairSecretFromHex(hex: String): ByteArray? {
        if (hex.length != PAIR_SECRET_BYTES * 2) return null
        return try {
            ByteArray(PAIR_SECRET_BYTES) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (e: NumberFormatException) {
            null
        }
    }

    /** The mailbox this secret's one direction of traffic flows through during [bucket]. */
    fun mailboxId(pairSecret: ByteArray, bucket: Long): String =
        LibsodiumWrapper.blake2b(
            data = "mbox|v1|$bucket".toByteArray(Charsets.UTF_8),
            key = pairSecret,
            length = 16
        ).joinToString("") { "%02x".format(it) }

    /**
     * Every mailbox id worth asking the relay about for a secret we listen on —
     * the current window plus the tolerated neighbours, so a blob deposited
     * before a rollover is still collectable after it.
     */
    fun inboundIds(pairSecret: ByteArray, now: Long = System.currentTimeMillis()): List<String> {
        val bucket = currentBucket(now)
        return (-BUCKET_TOLERANCE..BUCKET_TOLERANCE).map { mailboxId(pairSecret, bucket + it) }
    }

    /**
     * The single id to deposit into right now. Only the current window is used:
     * the receiver already tolerates ±1, so smearing one message across windows
     * would just triple the traffic the relay gets to see.
     */
    fun outboundId(pairSecret: ByteArray, now: Long = System.currentTimeMillis()): String =
        mailboxId(pairSecret, currentBucket(now))

    /**
     * The key the outer envelope is encrypted under before it is handed to the
     * relay. Without this the relay would still read the envelope's routing
     * fields — the message *type*, its id, and the recipient's user id — since
     * only the inner payload is sealed. Domain-separated from the mailbox id so
     * holding one tells you nothing about the other.
     */
    fun blobKey(pairSecret: ByteArray): ByteArray =
        LibsodiumWrapper.blake2b(
            data = "relay-blob-v1".toByteArray(Charsets.UTF_8),
            key = pairSecret,
            length = 32
        )

    /**
     * The one-shot, non-rotating mailbox a stranger deposits an introduction
     * request into — see `directory/` (the username-lookup service) and
     * `DirectoryProtocol`. This is the single deliberate exception to the
     * three properties in the class doc above:
     *
     *  - It IS computable by anyone who looks the owner up — that is the
     *    entire point of a directory. What stays protected is *reading* it:
     *    the directory's `/introductions/fetch` requires a live signature
     *    from the owner's signing key, so knowing the id is not the same as
     *    being authorized to collect from it.
     *  - It deliberately does NOT rotate like [mailboxId]. Rotation there
     *    defends against a relay correlating one conversation's long-lived
     *    traffic; this id is already permanently computable from public
     *    data, so rotating it would only cost both sides complexity for a
     *    privacy property it was never going to have.
     *  - It is used for exactly one thing — a signed, one-time introduction
     *    — and never for ongoing conversation traffic. Once accepted, a
     *    conversation moves onto the normal symmetric, rotating, unguessable
     *    mailboxes above like any QR-paired contact.
     *
     * Unkeyed (no pair secret exists yet — that is what this exchange is
     * for) and domain-separated from every other hash in this file so this
     * id space can never collide with a real mailbox id's.
     */
    fun introMailboxId(identityPublicKey: ByteArray): String =
        LibsodiumWrapper.blake2b(
            data = "intro|v1|".toByteArray(Charsets.UTF_8) + identityPublicKey,
            length = 16
        ).joinToString("") { "%02x".format(it) }
}
