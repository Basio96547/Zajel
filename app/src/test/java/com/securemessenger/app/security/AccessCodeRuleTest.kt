package com.securemessenger.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides whether a chosen unlock code can ever be entered.
 *
 * This exists because of a defect with the worst possible consequence in this
 * app: setup accepted a code beginning with 0, hashed it, stored it, disarmed
 * the fresh-install bootstrap path, and handed the user a messenger they could
 * never open again. The calculator's keypad — the only place a code is ever
 * typed — starts its display at "0" and has the first digit *replace* it, so
 * "0" followed by "1" reads as 1, exactly as a real calculator behaves. A code
 * of "0123" can therefore be chosen and can never be produced. Every escape
 * route is behind the same gate: a wipe would clear the code, but the wipe is
 * inside the messenger, and SecretCodeReceiver only re-arms Setup when *no*
 * code is set, which is not the case here. The keys and every message stay on
 * the device, permanently unreachable.
 *
 * A JVM test, like MediaSandboxGeometryTest and for the same reason: this
 * predicate touches no Android class and no native code, and the check that
 * guards against locking someone out of their own data should not be the one
 * that needs a phone plugged in to run.
 */
class AccessCodeRuleTest {

    @Test
    fun acceptsAnOrdinaryCode() {
        assertTrue(AppSettings.isTypeableCode("1234"))
        assertTrue(AppSettings.isTypeableCode("9070605"))
        assertTrue(AppSettings.isTypeableCode("1234567890"))
    }

    @Test
    fun rejectsALeadingZero() {
        // The whole point: these are typeable-looking and untypeable.
        assertFalse(AppSettings.isTypeableCode("0123"))
        assertFalse(AppSettings.isTypeableCode("0000"))
        assertFalse(AppSettings.isTypeableCode("01234567"))
    }

    @Test
    fun acceptsAZeroThatIsNotFirst() {
        // Only the leading position is unreachable — the keypad appends
        // everything after it verbatim.
        assertTrue(AppSettings.isTypeableCode("1000"))
        assertTrue(AppSettings.isTypeableCode("10203"))
    }

    @Test
    fun rejectsLengthsOutsideTheOneAgreedRange() {
        // Setup demanded 4–10 while the Settings dialog offered 3–10; both now
        // ask this function, so the same secret can no longer have two rules
        // depending on which screen set it.
        assertFalse(AppSettings.isTypeableCode(""))
        assertFalse(AppSettings.isTypeableCode("123"))
        assertFalse(AppSettings.isTypeableCode("12345678901"))
    }

    @Test
    fun rejectsAnythingThatIsNotDigits() {
        // The keypad has ten digits, a dot and four operators; nothing else can
        // reach the code check at all.
        assertFalse(AppSettings.isTypeableCode("12a4"))
        assertFalse(AppSettings.isTypeableCode("12.4"))
        assertFalse(AppSettings.isTypeableCode("1 234"))
    }
}
