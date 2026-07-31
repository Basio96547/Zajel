package com.hisn.app.audit

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end tests for exporting a log and verifying an imported copy — the
 * lawyer's-side verification. Chain integrity is device-independent; the device
 * seal only matches on the originating device.
 */
class VerifyImportTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun freshLog() {
        // Start from an empty (new-format) chain.
        ctx.deleteSharedPreferences("hisn_audit_log")
    }

    private fun exportJson(): String {
        AuditLog.record(ctx, 1000L, listOf(Finding("a", "فحص", "d", "", Severity.OK)))
        AuditLog.record(ctx, 2000L, listOf(Finding("a", "فحص", "d", "", Severity.HIGH)))
        return AuditLog.export(ctx, 3000L).json
    }

    @Test
    fun validExport_intactAndSealMatchesThisDevice() {
        val r = AuditLog.verifyImport(ctx, exportJson())
        assertTrue("chain intact", r.chainIntact)
        assertTrue("seal matches this device", r.sealMatchesThisDevice)
        assertEquals(2, r.entryCount)
        assertTrue(r.timeline.isNotEmpty())
    }

    @Test
    fun tamperedEntry_breaksChain() {
        val root = JSONObject(exportJson())
        // Rewrite a stored score without recomputing its hash.
        root.getJSONArray("entries").getJSONObject(0).put("score", 999)

        val r = AuditLog.verifyImport(ctx, root.toString())
        assertFalse("tampering must break the chain", r.chainIntact)
        assertEquals(0, r.brokenAtIndex)
    }

    @Test
    fun foreignDeviceSeal_chainIntactButSealDoesNotMatch() {
        val root = JSONObject(exportJson())
        // Simulate verifying on a different device: seal from elsewhere.
        root.put("deviceSeal", "00".repeat(32))

        val r = AuditLog.verifyImport(ctx, root.toString())
        assertTrue("integrity is still provable", r.chainIntact)
        assertFalse("seal cannot be confirmed here", r.sealMatchesThisDevice)
    }
}
