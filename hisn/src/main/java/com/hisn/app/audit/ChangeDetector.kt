package com.hisn.app.audit

/** Lightweight snapshot of a scan, kept so the next scan can be compared to it. */
data class SnapFinding(val id: String, val title: String, val severity: Severity)
data class ScanSnapshot(val score: Int, val findings: List<SnapFinding>)

enum class ChangeDirection { IMPROVED, WORSENED }

/**
 * A meaningful change between two scans, with human reasons. Never produced for a
 * first scan or a no-op scan — the rule is: no alert without a clear reason.
 */
data class Change(
    val direction: ChangeDirection,
    val oldScore: Int,
    val newScore: Int,
    val reasons: List<String>
)

/**
 * ChangeDetector - pure comparison of a previous [ScanSnapshot] to new findings.
 * Returns null when nothing worth notifying about changed. Unit-tested.
 */
object ChangeDetector {

    fun detect(previous: ScanSnapshot?, newFindings: List<Finding>, newScore: Int): Change? {
        if (previous == null) return null // first scan — nothing to compare

        val oldById = previous.findings.associateBy { it.id }
        val worsened = mutableListOf<String>()
        val improved = mutableListOf<String>()

        for (f in newFindings) {
            val old = oldById[f.id] ?: continue
            when {
                f.severity.rank > old.severity.rank ->
                    worsened += "${f.title}: من ${label(old.severity)} إلى ${label(f.severity)}"
                f.severity.rank < old.severity.rank ->
                    improved += "${f.title}: من ${label(old.severity)} إلى ${label(f.severity)}"
            }
        }

        if (worsened.isEmpty() && improved.isEmpty() && newScore == previous.score) return null

        return if (worsened.isNotEmpty() || newScore < previous.score) {
            Change(ChangeDirection.WORSENED, previous.score, newScore,
                worsened.ifEmpty { listOf("انخفضت درجة النظافة") })
        } else {
            Change(ChangeDirection.IMPROVED, previous.score, newScore,
                improved.ifEmpty { listOf("ارتفعت درجة النظافة") })
        }
    }

    fun snapshotOf(findings: List<Finding>): ScanSnapshot =
        ScanSnapshot(
            score = HygieneScore.compute(findings),
            findings = findings.map { SnapFinding(it.id, it.title, it.severity) }
        )

    private fun label(s: Severity): String = when (s) {
        Severity.CRITICAL -> "حرِج"
        Severity.HIGH -> "خطر"
        Severity.MEDIUM -> "متوسط"
        Severity.LOW -> "منخفض"
        Severity.OK -> "سليم"
    }
}
