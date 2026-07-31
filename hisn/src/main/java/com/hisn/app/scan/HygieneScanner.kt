package com.hisn.app.scan

import android.content.Context
import com.hisn.app.audit.AuditLog
import com.hisn.app.audit.Change
import com.hisn.app.audit.ChangeDetector
import com.hisn.app.audit.Finding
import com.hisn.app.audit.HygieneScore
import com.hisn.app.audit.SystemAuditor
import com.hisn.app.notify.HisnNotifications

/**
 * HygieneScanner - the one scan path shared by the UI (manual "فحص الآن") and the
 * background worker. It scans, appends to the tamper-evident log, detects change
 * vs. the last snapshot, saves the new snapshot, and (background only) notifies.
 */
object HygieneScanner {

    data class Outcome(val findings: List<Finding>, val change: Change?)

    suspend fun scanAndRecord(context: Context, notify: Boolean): Outcome {
        val findings = SystemAuditor(context).runAudit()
        AuditLog.record(context, System.currentTimeMillis(), findings)

        val previous = AuditLog.loadSnapshot(context)
        val newScore = HygieneScore.compute(findings)
        val change = ChangeDetector.detect(previous, findings, newScore)

        AuditLog.saveSnapshot(context, ChangeDetector.snapshotOf(findings))

        if (notify && change != null) HisnNotifications.notifyChange(context, change)
        return Outcome(findings, change)
    }
}
