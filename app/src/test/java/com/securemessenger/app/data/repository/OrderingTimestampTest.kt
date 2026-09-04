package com.securemessenger.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where an arriving message lands in the thread.
 *
 * Every envelope has always carried the sender's send time, and this app threw
 * it away and stamped the arrival instead. With the relay holding an
 * undelivered message for up to 48 hours — and background delivery off by
 * default, so collection often happens much later than sending — that made the
 * two devices disagree about the order of their own conversation: a message
 * sent at 09:00 and collected at 18:00 sat at 18:00 on one phone and 09:00 on
 * the other, under different day separators, and a reply could appear above the
 * message it answered.
 *
 * Using the claim fixes that, but the claim comes from a device whose clock
 * this one cannot check, so it gets a window instead of trust. A JVM test
 * because the rule is arithmetic — no Android, no database, no device.
 */
class OrderingTimestampTest {

    private val now = 1_700_000_000_000L
    private val hour = 60L * 60 * 1000

    private fun at(sentAt: Long?) = SecureRepository.orderingTimestamp(sentAt, now)

    @Test
    fun anEnvelopeWithoutATimestampUsesArrival() {
        // Codes from an older build carry no send time; nothing to honour.
        assertEquals(now, at(null))
        assertEquals(now, at(0L))
    }

    @Test
    fun aRealDelayIsHonoured() {
        // The whole point: sent nine hours ago, collected now, filed then.
        assertEquals(now - 9 * hour, at(now - 9 * hour))
    }

    @Test
    fun aClaimOlderThanTheRelayCouldHoldIsRejected() {
        // The relay keeps an envelope 48h. A "sent" time from before that
        // cannot be a real delay — it is a wrong clock, and honouring it would
        // bury a message that just arrived somewhere deep in the scrollback
        // where nobody will see it appear.
        assertEquals(now, at(now - 49 * hour))
        assertEquals(now, at(now - 400 * hour))
    }

    @Test
    fun aClaimFromTheFutureIsRejected() {
        // A clock running ahead would pin the message to the bottom of the
        // thread, below everything sent after it, until real time caught up.
        assertEquals(now, at(now + 10 * 60 * 1000))
    }

    @Test
    fun smallForwardSkewIsClampedToArrivalRatherThanRejected() {
        // Phones disagree by seconds all the time. That is not a broken clock,
        // so the message is not pushed to the back of the queue — it is simply
        // not allowed to claim a time that hasn't happened yet.
        assertEquals(now, at(now + 30_000))
    }

    @Test
    fun theBoundariesThemselvesAreAccepted() {
        // Exactly at the retention edge is still a possible delivery.
        assertEquals(now - 48 * hour, at(now - 48 * hour))
        assertEquals(now, at(now + 5 * 60 * 1000))
    }
}
