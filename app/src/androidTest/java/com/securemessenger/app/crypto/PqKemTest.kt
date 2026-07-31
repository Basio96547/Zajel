package com.securemessenger.app.crypto

import com.securemessenger.core.crypto.PqKem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Verifies the post-quantum ML-KEM-768 primitive: encapsulate/decapsulate
 * agree on a 32-byte secret, and a wrong key does not.
 */
class PqKemTest {

    @Test
    fun encapsulateDecapsulate_agreeOnSecret() {
        val kp = PqKem.generateKeyPair()
        val enc = PqKem.encapsulate(kp.publicKey)
        val recovered = PqKem.decapsulate(kp.secretKey, enc.ciphertext)

        assertEquals(32, enc.sharedSecret.size)
        assertArrayEquals(enc.sharedSecret, recovered)
    }

    @Test
    fun wrongKey_doesNotRecoverSecret() {
        val kp = PqKem.generateKeyPair()
        val other = PqKem.generateKeyPair()
        val enc = PqKem.encapsulate(kp.publicKey)
        // ML-KEM decapsulation with the wrong key yields an implicit-rejection
        // secret, which must differ from the real one.
        val recovered = PqKem.decapsulate(other.secretKey, enc.ciphertext)
        assertFalse(enc.sharedSecret.contentEquals(recovered))
    }
}
