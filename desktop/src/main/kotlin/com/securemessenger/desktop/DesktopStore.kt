package com.securemessenger.desktop

import com.goterl.lazysodium.interfaces.PwHash
import com.securemessenger.core.B64
import com.securemessenger.core.Platform
import com.securemessenger.core.crypto.LibsodiumWrapper
import com.securemessenger.core.crypto.SignalProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * All persistent state for the desktop client, encrypted at rest.
 *
 * The Android app keeps its data in SQLCipher with the key held by the Android
 * Keystore — hardware-backed, and tied to the device unlock. A desktop PC has
 * no equivalent the app can rely on across Windows, macOS and Linux, so the key
 * comes from a passphrase the user types at launch, stretched with Argon2id
 * (libsodium `crypto_pwhash`, moderate limits) so a stolen file can't be
 * brute-forced cheaply.
 *
 * That difference is worth being honest about rather than papering over: the
 * desktop store is exactly as strong as the passphrase chosen for it, and it is
 * unlocked for as long as the app is running. It is not equivalent to the
 * phone's Keystore protection, and the UI says so at setup.
 *
 * The whole state is one JSON document written atomically. A personal
 * messenger's history is small, and a single encrypted document has no
 * half-written intermediate states, no separate journal/WAL files to leak
 * plaintext, and nothing to migrate — properties worth more here than the
 * query flexibility a database would add.
 */
class DesktopStore(private val file: File) {

    /** Header format: `smd1:<base64 salt>:<base64 sealed state>`. Only the salt is public. */
    private companion object {
        const val MAGIC = "smd1"
        const val SALT_BYTES = 16
    }

    private var masterKey: ByteArray? = null

    // ---- in-memory state, mirrored to disk on every mutation ----

    var userId: String = ""
        private set
    var displayName: String = ""

    lateinit var identity: SignalProtocol.IdentityKeyPair
        private set
    lateinit var signedPreKey: SignalProtocol.PreKeyPair
        private set
    var signingPublicKey: ByteArray = ByteArray(0)
        private set
    var signingSecretKey: ByteArray = ByteArray(0)
        private set
    var mlkemPublicKey: ByteArray = ByteArray(0)
        private set
    var mlkemSecretKey: ByteArray = ByteArray(0)
        private set

    private val oneTimePreKeys = mutableListOf<StoredPreKey>()
    private val contacts = linkedMapOf<String, StoredContact>()
    private val sessions = mutableMapOf<String, StoredSession>()
    private val messages = mutableListOf<StoredMessage>()
    private val outbox = linkedMapOf<String, StoredOutbox>()

    /** Pair secrets we minted by showing a QR, not yet claimed by whoever scanned it. */
    private val pendingPairSecrets = mutableListOf<String>()

    data class StoredPreKey(val id: Int, val publicKey: ByteArray, val secretKey: ByteArray, var used: Boolean)

    data class StoredContact(
        val id: String,
        val publicKey: ByteArray,
        var displayName: String,
        /** Scanned off THEIR QR — we deposit into the mailbox it addresses. */
        var relaySendSecret: ByteArray? = null,
        /** Minted by US and taken by them — we listen on the mailbox it addresses. */
        var relayRecvSecret: ByteArray? = null,
        /** "host:port" that worked, came in on a QR, or was typed by hand. */
        var directAddress: String? = null
    )

    data class StoredSession(
        val state: SignalProtocol.SessionState,
        val isInitiator: Boolean,
        val responderEphemeralHex: String?,
        val initiatorOtkId: Int?
    )

    data class StoredMessage(
        val contactId: String,
        val outgoing: Boolean,
        val text: String,
        val timestamp: Long,
        val clientMessageId: String?,
        var delivered: Boolean = false
    )

    data class StoredOutbox(
        val id: String,
        val recipientId: String,
        val envelope: String,
        val clientMessageId: String?
    )

    // ================= lifecycle =================

    fun exists(): Boolean = file.exists()

    /**
     * Open an existing store. Returns false when the passphrase is wrong — the
     * secretbox simply fails to authenticate, which is indistinguishable from a
     * tampered file and is treated the same way.
     */
    fun unlock(passphrase: CharArray): Boolean {
        val raw = try {
            file.readText()
        } catch (e: Exception) {
            return false
        }
        val parts = raw.split(":")
        if (parts.size != 3 || parts[0] != MAGIC) return false
        val salt = B64.decodeOrNull(parts[1]) ?: return false
        val key = deriveKey(passphrase, salt)
        val plain = try {
            LibsodiumWrapper.decryptSymmetric(B64.decode(parts[2]), key)
        } catch (e: Exception) {
            LibsodiumWrapper.secureWipe(key)
            return false
        }
        masterKey = key
        load(JSONObject(String(plain, Charsets.UTF_8)))
        java.util.Arrays.fill(plain, 0)
        return true
    }

    /** Create a brand-new identity and write the first encrypted state file. */
    fun create(passphrase: CharArray, name: String) {
        val salt = LibsodiumWrapper.generateSecretKey(SALT_BYTES)
        masterKey = deriveKey(passphrase, salt)
        saltForFile = salt

        userId = B64.toHex(LibsodiumWrapper.generateSecretKey(16))
        displayName = name

        identity = SignalProtocol.IdentityKeyPair.generate()
        signedPreKey = SignalProtocol.PreKeyPair.generate(1)
        val signing = LibsodiumWrapper.generateSigningKeyPair()
        signingPublicKey = signing.first
        signingSecretKey = signing.second
        val kem = com.securemessenger.core.crypto.PqKem.generateKeyPair()
        mlkemPublicKey = kem.publicKey
        mlkemSecretKey = kem.secretKey

        // A pool of one-time prekeys, same idea as the phone's: each initial
        // handshake burns one, and running out silently downgrades the
        // handshake's forward secrecy, so there has to be plenty of headroom.
        repeat(100) { i ->
            val pk = SignalProtocol.PreKeyPair.generate(i + 1)
            oneTimePreKeys += StoredPreKey(pk.id, pk.publicKey, pk.secretKey, used = false)
        }
        save()
    }

    private var saltForFile: ByteArray = ByteArray(0)

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): ByteArray {
        val key = ByteArray(32)
        val pw = String(passphrase).toByteArray(Charsets.UTF_8)
        // MEMLIMIT_MODERATE is already a JNA NativeLong — passing it through
        // .toLong() picks the wrong overload and won't compile.
        val ok = Platform.sodium.cryptoPwHash(
            key, key.size, pw, pw.size, salt,
            PwHash.OPSLIMIT_MODERATE, PwHash.MEMLIMIT_MODERATE,
            PwHash.Alg.PWHASH_ALG_ARGON2ID13
        )
        java.util.Arrays.fill(pw, 0)
        if (!ok) throw IllegalStateException("فشل اشتقاق مفتاح التشفير من عبارة المرور")
        return key
    }

    // ================= mutations =================

    @Synchronized
    fun upsertContact(contact: StoredContact) {
        contacts[contact.id] = contact
        save()
    }

    fun contact(id: String): StoredContact? = contacts[id]
    fun allContacts(): List<StoredContact> = contacts.values.toList()

    @Synchronized
    fun setDirectAddress(contactId: String, hostPort: String?) {
        contacts[contactId]?.let { it.directAddress = hostPort?.trim()?.takeIf(String::isNotBlank); save() }
    }

    @Synchronized
    fun bindRelayRecvSecret(contactId: String, secret: ByteArray) {
        contacts[contactId]?.let { if (it.relayRecvSecret == null) { it.relayRecvSecret = secret; save() } }
    }

    fun unusedPreKeys(): List<SignalProtocol.PreKeyPair> =
        oneTimePreKeys.filterNot { it.used }.map { SignalProtocol.PreKeyPair(it.id, it.publicKey, it.secretKey) }

    fun preKeySecret(id: Int): ByteArray? = oneTimePreKeys.firstOrNull { it.id == id }?.secretKey

    @Synchronized
    fun markPreKeyUsed(id: Int) {
        oneTimePreKeys.firstOrNull { it.id == id }?.let { it.used = true; save() }
    }

    fun session(contactId: String): StoredSession? = sessions[contactId]

    @Synchronized
    fun saveSession(contactId: String, session: StoredSession) {
        sessions[contactId] = session
        save()
    }

    @Synchronized
    fun addMessage(message: StoredMessage) {
        messages += message
        save()
    }

    fun messagesWith(contactId: String): List<StoredMessage> =
        messages.filter { it.contactId == contactId }.sortedBy { it.timestamp }

    @Synchronized
    fun markDelivered(clientMessageId: String?) {
        if (clientMessageId == null) return
        messages.firstOrNull { it.clientMessageId == clientMessageId }?.let { it.delivered = true; save() }
    }

    @Synchronized
    fun addOutbox(entry: StoredOutbox) {
        outbox[entry.id] = entry
        save()
    }

    @Synchronized
    fun removeOutbox(id: String) {
        val removed = outbox.remove(id)
        if (removed != null) {
            markDelivered(removed.clientMessageId)
            save()
        }
    }

    fun allOutbox(): List<StoredOutbox> = outbox.values.toList()

    fun pendingSecrets(): List<String> = pendingPairSecrets.toList()

    @Synchronized
    fun addPendingSecret(hex: String) {
        pendingPairSecrets.remove(hex)
        pendingPairSecrets.add(0, hex)
        // Same cap as the phone: every outstanding secret is a mailbox we have
        // to keep polling, so an unbounded list would grow the relay's view of
        // us for no benefit.
        while (pendingPairSecrets.size > 5) pendingPairSecrets.removeAt(pendingPairSecrets.lastIndex)
        save()
    }

    @Synchronized
    fun removePendingSecret(hex: String) {
        if (pendingPairSecrets.remove(hex)) save()
    }

    // ================= persistence =================

    @Synchronized
    private fun save() {
        val key = masterKey ?: return
        val json = JSONObject().apply {
            put("userId", userId)
            put("displayName", displayName)
            put("identityPublic", B64.encode(identity.publicKey))
            put("identitySecret", B64.encode(identity.secretKey))
            put("spkId", signedPreKey.id)
            put("spkPublic", B64.encode(signedPreKey.publicKey))
            put("spkSecret", B64.encode(signedPreKey.secretKey))
            put("signPublic", B64.encode(signingPublicKey))
            put("signSecret", B64.encode(signingSecretKey))
            put("kemPublic", B64.encode(mlkemPublicKey))
            put("kemSecret", B64.encode(mlkemSecretKey))
            put("otks", JSONArray().apply {
                oneTimePreKeys.forEach {
                    put(JSONObject().apply {
                        put("id", it.id); put("pub", B64.encode(it.publicKey))
                        put("sec", B64.encode(it.secretKey)); put("used", it.used)
                    })
                }
            })
            put("contacts", JSONArray().apply {
                contacts.values.forEach {
                    put(JSONObject().apply {
                        put("id", it.id); put("pub", B64.encode(it.publicKey)); put("name", it.displayName)
                        it.relaySendSecret?.let { s -> put("send", B64.encode(s)) }
                        it.relayRecvSecret?.let { s -> put("recv", B64.encode(s)) }
                        it.directAddress?.let { a -> put("addr", a) }
                    })
                }
            })
            put("sessions", JSONArray().apply {
                sessions.forEach { (contactId, s) ->
                    put(JSONObject().apply {
                        put("contact", contactId)
                        put("initiator", s.isInitiator)
                        s.responderEphemeralHex?.let { put("respEph", it) }
                        s.initiatorOtkId?.let { put("otkId", it) }
                        put("state", encodeSessionState(s.state))
                    })
                }
            })
            put("messages", JSONArray().apply {
                messages.forEach {
                    put(JSONObject().apply {
                        put("contact", it.contactId); put("out", it.outgoing); put("text", it.text)
                        put("ts", it.timestamp); put("delivered", it.delivered)
                        it.clientMessageId?.let { c -> put("cid", c) }
                    })
                }
            })
            put("outbox", JSONArray().apply {
                outbox.values.forEach {
                    put(JSONObject().apply {
                        put("id", it.id); put("to", it.recipientId); put("env", it.envelope)
                        it.clientMessageId?.let { c -> put("cid", c) }
                    })
                }
            })
            put("pending", JSONArray(pendingPairSecrets))
        }

        val sealed = LibsodiumWrapper.encryptSymmetric(json.toString().toByteArray(Charsets.UTF_8), key)
        // Write to a sibling then move: a crash mid-write leaves the previous
        // good file intact rather than a truncated one that can never be opened
        // again — and this file is the only copy of the identity.
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText("$MAGIC:${B64.encode(saltForFile)}:${B64.encode(sealed)}")
        tmp.copyTo(file, overwrite = true)
        tmp.delete()
    }

    private fun load(json: JSONObject) {
        userId = json.getString("userId")
        displayName = json.optString("displayName")
        identity = SignalProtocol.IdentityKeyPair(
            B64.decode(json.getString("identityPublic")), B64.decode(json.getString("identitySecret"))
        )
        signedPreKey = SignalProtocol.PreKeyPair(
            json.getInt("spkId"), B64.decode(json.getString("spkPublic")), B64.decode(json.getString("spkSecret"))
        )
        signingPublicKey = B64.decode(json.getString("signPublic"))
        signingSecretKey = B64.decode(json.getString("signSecret"))
        mlkemPublicKey = B64.decode(json.getString("kemPublic"))
        mlkemSecretKey = B64.decode(json.getString("kemSecret"))

        oneTimePreKeys.clear()
        json.optJSONArray("otks")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                oneTimePreKeys += StoredPreKey(
                    o.getInt("id"), B64.decode(o.getString("pub")), B64.decode(o.getString("sec")), o.optBoolean("used")
                )
            }
        }
        contacts.clear()
        json.optJSONArray("contacts")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                contacts[o.getString("id")] = StoredContact(
                    id = o.getString("id"),
                    publicKey = B64.decode(o.getString("pub")),
                    displayName = o.optString("name"),
                    relaySendSecret = B64.decodeOrNull(o.optString("send")),
                    relayRecvSecret = B64.decodeOrNull(o.optString("recv")),
                    directAddress = o.optString("addr").takeIf { it.isNotBlank() }
                )
            }
        }
        sessions.clear()
        json.optJSONArray("sessions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                sessions[o.getString("contact")] = StoredSession(
                    state = decodeSessionState(o.getJSONObject("state")),
                    isInitiator = o.optBoolean("initiator", true),
                    responderEphemeralHex = o.optString("respEph").takeIf { it.isNotBlank() },
                    initiatorOtkId = if (o.has("otkId")) o.getInt("otkId") else null
                )
            }
        }
        messages.clear()
        json.optJSONArray("messages")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                messages += StoredMessage(
                    contactId = o.getString("contact"), outgoing = o.getBoolean("out"),
                    text = o.getString("text"), timestamp = o.getLong("ts"),
                    clientMessageId = o.optString("cid").takeIf { it.isNotBlank() },
                    delivered = o.optBoolean("delivered")
                )
            }
        }
        outbox.clear()
        json.optJSONArray("outbox")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                outbox[o.getString("id")] = StoredOutbox(
                    o.getString("id"), o.getString("to"), o.getString("env"),
                    o.optString("cid").takeIf { it.isNotBlank() }
                )
            }
        }
        pendingPairSecrets.clear()
        json.optJSONArray("pending")?.let { arr ->
            for (i in 0 until arr.length()) pendingPairSecrets += arr.getString(i)
        }
        // The salt is needed again on every save; it lives in the file header.
        saltForFile = B64.decode(file.readText().split(":")[1])
    }

    private fun encodeSessionState(s: SignalProtocol.SessionState): JSONObject = JSONObject().apply {
        put("root", B64.encode(s.rootKey))
        put("cks", B64.encode(s.chainKeySend))
        s.chainKeyReceive?.let { put("ckr", B64.encode(it)) }
        put("dhPub", B64.encode(s.dhRatchetPublicKey))
        put("dhSec", B64.encode(s.dhRatchetSecretKey))
        put("sc", s.sendChainCounter)
        put("rc", s.receiveChainCounter)
        s.theirRatchetPublicKey?.let { put("theirDh", B64.encode(it)) }
        put("prev", s.previousSendChainLength)
        s.initiatorEphemeralPublicKey?.let { put("initEph", B64.encode(it)) }
        put("skipped", JSONObject().apply {
            s.skippedMessageKeys.forEach { (k, v) -> put(k, B64.encode(v)) }
        })
    }

    private fun decodeSessionState(o: JSONObject): SignalProtocol.SessionState {
        val skipped = mutableMapOf<String, ByteArray>()
        o.optJSONObject("skipped")?.let { obj ->
            obj.keys().forEach { k -> B64.decodeOrNull(obj.optString(k))?.let { skipped[k] = it } }
        }
        return SignalProtocol.SessionState(
            rootKey = B64.decode(o.getString("root")),
            chainKeySend = B64.decode(o.getString("cks")),
            chainKeyReceive = B64.decodeOrNull(o.optString("ckr")),
            dhRatchetPublicKey = B64.decode(o.getString("dhPub")),
            dhRatchetSecretKey = B64.decode(o.getString("dhSec")),
            sendChainCounter = o.getInt("sc"),
            receiveChainCounter = o.getInt("rc"),
            theirRatchetPublicKey = B64.decodeOrNull(o.optString("theirDh")),
            previousSendChainLength = o.optInt("prev"),
            initiatorEphemeralPublicKey = B64.decodeOrNull(o.optString("initEph")),
            skippedMessageKeys = skipped
        )
    }
}
