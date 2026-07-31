package com.securemessenger.app.crypto

// These live in :core now. This file sits in com.securemessenger.app.crypto and
// used to reach them unqualified, back when they were in this same package —
// which is exactly why the move to :core broke it silently: a same-package call
// has no import line to update, so nothing pointed at it.
import com.securemessenger.core.crypto.LibsodiumWrapper
import com.securemessenger.core.crypto.MessagePadding
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Verifies the metadata-privacy primitives: fixed-size padding (hides message
 * length) and sealed-sender boxes (hide the sender from the relay).
 */
class MetadataPrivacyTest {

    @Test
    fun padding_roundTripsForVariousLengths() {
        for (len in intArrayOf(0, 1, 5, 100, 251, 252, 300, 1000)) {
            val msg = ByteArray(len) { (it % 251).toByte() }
            val padded = MessagePadding.pad(msg)
            assertEquals("padded size is a bucket multiple", 0, padded.size % 256)
            assertTrue("padded is at least as long as content", padded.size >= len)
            assertArrayEquals(msg, MessagePadding.unpad(padded))
        }
    }

    @Test
    fun padding_hidesLength_shortMessagesShareBucketSize() {
        val a = MessagePadding.pad("hi".toByteArray())
        val b = MessagePadding.pad("a much longer but still small message".toByteArray())
        // Both fit in the first 256-byte bucket → identical on-wire length.
        assertEquals(a.size, b.size)
        assertEquals(256, a.size)
    }

    @Test
    fun sealedBox_onlyRecipientCanOpen() {
        val recipient = LibsodiumWrapper.generateKeyPair() // (public, secret)
        val stranger = LibsodiumWrapper.generateKeyPair()

        val secret = "senderId + headers + ciphertext".toByteArray()
        val sealed = LibsodiumWrapper.sealTo(secret, recipient.first)

        // Sealed blob is not the plaintext.
        assertNotEquals(secret.size, sealed.size)

        // Recipient opens it.
        assertArrayEquals(secret, LibsodiumWrapper.sealOpen(sealed, recipient.first, recipient.second))

        // A stranger cannot.
        try {
            LibsodiumWrapper.sealOpen(sealed, stranger.first, stranger.second)
            fail("Stranger should not be able to open the sealed box")
        } catch (_: Exception) {
            // expected
        }
    }
}
