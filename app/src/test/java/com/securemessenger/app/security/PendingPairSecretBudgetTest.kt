package com.securemessenger.app.security

import com.securemessenger.app.security.AppSettings.PendingSecret
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which outstanding pair secrets survive, and which get evicted.
 *
 * A pair secret is minted when the pairing screen draws your QR, and it is the
 * only thing that tells this device which relay mailbox to listen on for
 * whoever scans that code. Drop it and the failure is completely silent: the
 * other side pairs, sends, and their envelope lands in a mailbox nobody is
 * watching. No error on either phone, no log, no retry that can help — the
 * conversation simply stays local-network-only forever.
 *
 * That is what a single five-slot budget produced. Sharing your code as an
 * image or as text is explicitly the "scan this later, from somewhere else"
 * path, and five subsequent visits to the pairing screen quietly threw the
 * shared one away. So the rule now has two budgets, and this test exists
 * because a rule whose violation is invisible in the app has to be visible
 * somewhere.
 */
class PendingPairSecretBudgetTest {

    private fun displayed(hex: String, at: Long = 0L) = PendingSecret(hex, at, exported = false)
    private fun exported(hex: String, at: Long = 0L) = PendingSecret(hex, at, exported = true)

    private fun List<PendingSecret>.hexes() = map { it.hex }

    @Test
    fun newestOnScreenCodeGoesFirstAndIsNotExported() {
        val after = AppSettings.withNewSecret(listOf(displayed("aa")), "bb", now = 10L)
        assertEquals(listOf("bb", "aa"), after.hexes())
        assertFalse(after.first().exported)
        assertEquals(10L, after.first().issuedAt)
    }

    @Test
    fun onScreenCodesStayCappedAtFive() {
        var list = emptyList<PendingSecret>()
        repeat(8) { i -> list = AppSettings.withNewSecret(list, "s$i", now = i.toLong()) }
        assertEquals(5, list.size)
        // Newest five, oldest three gone — the disposable case, unchanged.
        assertEquals(listOf("s7", "s6", "s5", "s4", "s3"), list.hexes())
    }

    @Test
    fun aSharedCodeSurvivesABurstOfOnScreenCodes() {
        // The regression this whole change exists for: share your code, then
        // open the pairing screen five more times.
        var list = AppSettings.withNewSecret(emptyList(), "shared", now = 0L)
        list = AppSettings.withExported(list, "shared", now = 1L)
        repeat(5) { i -> list = AppSettings.withNewSecret(list, "onscreen$i", now = 10L + i) }

        assertTrue(
            "the shared code was evicted — the friend who has it would silently never reach us",
            list.any { it.hex == "shared" && it.exported }
        )
        // And it did not eat into the on-screen budget either.
        assertEquals(5, list.count { !it.exported })
    }

    @Test
    fun exportingRestampsTheClock() {
        // A code drawn hours ago and shared now has to be judged from the
        // moment it was shared, not the moment it was drawn.
        val list = AppSettings.withExported(listOf(displayed("aa", at = 100L)), "aa", now = 900L)
        assertEquals(900L, list.single().issuedAt)
        assertTrue(list.single().exported)
    }

    @Test
    fun exportingIsIdempotentAndIgnoresUnknownCodes() {
        val already = listOf(exported("aa", at = 5L))
        assertSame(already, AppSettings.withExported(already, "aa", now = 99L))
        assertSame(already, AppSettings.withExported(already, "nope", now = 99L))
    }

    @Test
    fun sharedCodesHaveTheirOwnCeilingToo() {
        // Bounded on purpose: every outstanding secret is a set of mailboxes
        // this device polls, so "keep them all" is not an option.
        var list = emptyList<PendingSecret>()
        repeat(7) { i ->
            list = AppSettings.withNewSecret(list, "e$i", now = i.toLong())
            list = AppSettings.withExported(list, "e$i", now = i.toLong())
        }
        assertEquals(5, list.size)
        assertEquals(5, list.count { it.exported })
        assertEquals(listOf("e6", "e5", "e4", "e3", "e2"), list.filter { it.exported }.hexes())
    }
}
