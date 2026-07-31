package com.hisn.app.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the pure detection/scoring/chain logic. These run on plain
 * JVM (no device needed) and pin the auditor's behaviour against concrete
 * scenarios instead of asserting it works.
 */
class AuditLogicTest {

    // ---------- Rules: real scenarios ----------

    @Test fun usbDebuggingOpen_isMedium() =
        assertEquals(Severity.MEDIUM, Rules.usbDebugging(true))

    @Test fun rootedDevice_isHigh() =
        assertEquals(Severity.HIGH, Rules.rootIndicators(true))

    @Test fun noScreenLock_isHigh() =
        assertEquals(Severity.HIGH, Rules.screenLock(false))

    @Test fun screenLockPresent_isOk() =
        assertEquals(Severity.OK, Rules.screenLock(true))

    @Test fun accessibilityService_isHigh() =
        assertEquals(Severity.HIGH, Rules.accessibility(1))

    @Test fun appWithThreeSensitivePerms_isMedium() =
        assertEquals(Severity.MEDIUM, Rules.sensitivePermissionCombo(3))

    @Test fun appWithTwoSensitivePerms_isOk() =
        assertEquals(Severity.OK, Rules.sensitivePermissionCombo(2))

    @Test fun securityPatch_bands() {
        assertEquals(Severity.OK, Rules.securityPatch(1))
        assertEquals(Severity.MEDIUM, Rules.securityPatch(4))
        assertEquals(Severity.HIGH, Rules.securityPatch(9))
        assertEquals(Severity.LOW, Rules.securityPatch(-1)) // unknown
    }

    // ---------- HygieneScore ----------

    private fun f(sev: Severity) = Finding("id-$sev", "t", "d", "", sev)

    @Test fun cleanDevice_scores100() =
        assertEquals(100, HygieneScore.compute(List(5) { f(Severity.OK) }))

    @Test fun penaltiesAddUp() {
        // HIGH(25) + MEDIUM(8) + MEDIUM(8) = 41 -> 59
        val score = HygieneScore.compute(listOf(f(Severity.HIGH), f(Severity.MEDIUM), f(Severity.MEDIUM)))
        assertEquals(59, score)
    }

    @Test fun scoreFloorsAtZero() {
        val score = HygieneScore.compute(List(10) { f(Severity.CRITICAL) })
        assertEquals(0, score)
    }

    // ---------- AuditChain: tamper-evidence ----------

    private fun sampleChain(): List<AuditEntry> {
        val e1 = AuditChain.append(null, 1000L, 90, 0, 0, 1, 0)
        val e2 = AuditChain.append(e1, 2000L, 75, 0, 1, 0, 0)
        val e3 = AuditChain.append(e2, 3000L, 100, 0, 0, 0, 0)
        return listOf(e1, e2, e3)
    }

    @Test fun validChain_verifies() =
        assertTrue(AuditChain.verify(sampleChain()))

    @Test fun alteredEntry_breaksChain() {
        val chain = sampleChain().toMutableList()
        // Attacker rewrites the score of the middle entry but keeps its hash.
        chain[1] = chain[1].copy(score = 100)
        assertFalse(AuditChain.verify(chain))
    }

    @Test fun deletedEntry_breaksChain() {
        val chain = sampleChain()
        val withoutMiddle = listOf(chain[0], chain[2])
        assertFalse(AuditChain.verify(withoutMiddle))
    }

    @Test fun reorderedEntries_breakChain() {
        val chain = sampleChain()
        val reordered = listOf(chain[0], chain[2], chain[1])
        assertFalse(AuditChain.verify(reordered))
    }

    @Test fun emptyChain_isTriviallyValid() =
        assertTrue(AuditChain.verify(emptyList()))

    // ---------- ChangeDetector ----------

    private fun finding(id: String, title: String, sev: Severity) = Finding(id, title, "d", "", sev)

    @Test fun firstScan_noChange() {
        val new = listOf(finding("lock", "قفل الشاشة", Severity.HIGH))
        assertEquals(null, ChangeDetector.detect(null, new, HygieneScore.compute(new)))
    }

    @Test fun identicalScan_noChange() {
        val new = listOf(finding("lock", "قفل الشاشة", Severity.HIGH))
        val prev = ChangeDetector.snapshotOf(new)
        assertEquals(null, ChangeDetector.detect(prev, new, HygieneScore.compute(new)))
    }

    @Test fun accessibilityGained_isWorsened() {
        val before = listOf(finding("acc", "خدمات إمكانية الوصول", Severity.OK))
        val after = listOf(finding("acc", "خدمات إمكانية الوصول", Severity.HIGH))
        val change = ChangeDetector.detect(ChangeDetector.snapshotOf(before), after, HygieneScore.compute(after))!!
        assertEquals(ChangeDirection.WORSENED, change.direction)
        assertTrue(change.reasons.first().contains("إمكانية الوصول"))
    }

    @Test fun screenLockAdded_isImproved() {
        val before = listOf(finding("lock", "قفل الشاشة", Severity.HIGH))
        val after = listOf(finding("lock", "قفل الشاشة", Severity.OK))
        val change = ChangeDetector.detect(ChangeDetector.snapshotOf(before), after, HygieneScore.compute(after))!!
        assertEquals(ChangeDirection.IMPROVED, change.direction)
        assertEquals(75, change.oldScore)   // 100 - 25
        assertEquals(100, change.newScore)
    }
}
