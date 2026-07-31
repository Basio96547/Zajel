package com.hisn.app.audit

/**
 * HygieneScore - a single 0..100 number computed from findings, so the user gets
 * one figure they understand. Pure and unit-tested; the UI shows the REAL result,
 * never a hard-coded value.
 *
 * The score is a hygiene indicator, not a safety guarantee — 100 does not mean
 * "not spied on".
 */
object HygieneScore {

    fun penalty(severity: Severity): Int = when (severity) {
        Severity.CRITICAL -> 40
        Severity.HIGH -> 25
        Severity.MEDIUM -> 8
        Severity.LOW -> 3
        Severity.OK -> 0
    }

    fun compute(findings: List<Finding>): Int =
        (100 - findings.sumOf { penalty(it.severity) }).coerceIn(0, 100)

    /** A coarse band for colouring the gauge. */
    fun band(score: Int): Severity = when {
        score >= 85 -> Severity.OK
        score >= 60 -> Severity.MEDIUM
        else -> Severity.HIGH
    }
}
