package com.securemessenger.core.net

import com.securemessenger.core.B64
import com.securemessenger.core.crypto.LibsodiumWrapper
import com.securemessenger.core.crypto.MailboxToken
import com.securemessenger.core.crypto.MessagePadding
import org.json.JSONObject

/**
 * RelayBlob — the wire format of a single opaque blob handed to the blind relay.
 *
 * Kept separate from [RelayClient] (which owns the polling, scheduling and
 * cover traffic) because this part is pure: bytes in, bytes out, no coroutines
 * and no state. That makes the one thing the relay's privacy actually rests on
 * directly testable.
 *
 * A blob is `secretbox(pad(json), key)` where the json carries a chunk of the
 * real envelope plus the bookkeeping needed to reassemble it:
 *
 *   { "g": group, "i": index, "n": count, "d": base64(chunk), "z": filler }
 *
 * Three deliberate properties:
 *
 *  - **The envelope is encrypted, not just its payload.** The app's outer
 *    envelope carries the message type, its routing id and the recipient's user
 *    id in clear; without this layer the relay would read all three.
 *  - **Length is rounded to a size class.** `z` pads the json up to a multiple
 *    of [SIZE_CLASS_BYTES], so every short message — real or cover traffic —
 *    looks identical on the wire.
 *  - **Splitting happens inside the encryption.** A large message becomes
 *    several same-sized blobs rather than one big one, so the relay sees
 *    traffic it cannot group back into a single message.
 */
object RelayBlob {

    /** Payload bytes per chunk. Sized so one sealed, padded, base64'd blob stays under the relay's 200 KB cap. */
    const val CHUNK_BYTES = 100 * 1024

    /** Upper bound on chunks per message — a backstop, not a size the UI can reach in normal use. */
    const val MAX_CHUNKS = 512

    /** Everything is padded up to a multiple of this, so the relay learns a size class instead of a length. */
    const val SIZE_CLASS_BYTES = 1024

    /** Cost in JSON characters of the filler field itself: `,"z":""`. */
    private const val FILLER_FIELD_OVERHEAD = 8

    /** One reassembled-or-standalone piece of a message, as it came off the wire. */
    data class Chunk(val group: String, val index: Int, val count: Int, val data: ByteArray)

    /**
     * Split [payload] and seal each chunk under the key derived from [pairSecret].
     *
     * [group] ties the chunks of one message together for the recipient; it is
     * inside the encryption, so it tells the relay nothing. [minSizeClasses]
     * lets cover traffic land in the same size classes as real short messages
     * instead of being uniformly tiny and therefore identifiable.
     */
    fun seal(
        pairSecret: ByteArray,
        payload: ByteArray,
        group: String,
        minSizeClasses: Int = 1
    ): List<String> {
        val key = MailboxToken.blobKey(pairSecret)
        val count = ((payload.size + CHUNK_BYTES - 1) / CHUNK_BYTES).coerceAtLeast(1)
        require(count <= MAX_CHUNKS) { "payload too large for the relay" }

        return (0 until count).map { index ->
            val from = index * CHUNK_BYTES
            val chunk = payload.copyOfRange(from, minOf(from + CHUNK_BYTES, payload.size))

            val obj = JSONObject().apply {
                put("g", group)
                put("i", index)
                put("n", count)
                put("d", B64.encode(chunk))
            }
            val bare = obj.toString().length
            val target = maxOf(
                ((bare + FILLER_FIELD_OVERHEAD) / SIZE_CLASS_BYTES + 1) * SIZE_CLASS_BYTES,
                minSizeClasses * SIZE_CLASS_BYTES
            )
            obj.put("z", "0".repeat((target - bare - FILLER_FIELD_OVERHEAD).coerceAtLeast(0)))

            val padded = MessagePadding.pad(obj.toString().toByteArray(Charsets.UTF_8))
            B64.encode(LibsodiumWrapper.encryptSymmetric(padded, key))
        }
    }

    /**
     * Open one blob. Returns null for anything that doesn't decrypt or doesn't
     * parse — a blob we can't open is either corrupt or someone guessing at
     * mailbox ids, and neither is worth distinguishing.
     */
    fun open(pairSecret: ByteArray, blobBase64: String): Chunk? = try {
        val sealed = B64.decode(blobBase64)
        val padded = LibsodiumWrapper.decryptSymmetric(sealed, MailboxToken.blobKey(pairSecret))
        val json = JSONObject(String(MessagePadding.unpad(padded), Charsets.UTF_8))
        val group = json.optString("g")
        val index = json.optInt("i")
        val count = json.optInt("n", 1)
        if (group.isBlank() || count < 1 || count > MAX_CHUNKS || index < 0 || index >= count) null
        else Chunk(group, index, count, B64.decode(json.getString("d")))
    } catch (e: Exception) {
        null
    }
}
