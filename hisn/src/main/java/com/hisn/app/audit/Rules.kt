package com.hisn.app.audit

/**
 * Rules - the pure severity-classification logic, separated from any Android API
 * so it can be unit-tested on the JVM against concrete scenarios (rooted device,
 * open USB debugging, accessibility abuse, no screen lock, ...).
 *
 * Keeping detection decisions here (and not inline in [SystemAuditor]) is what
 * makes the auditor's behaviour verifiable rather than asserted.
 */
object Rules {

    fun screenLock(secure: Boolean): Severity =
        if (secure) Severity.OK else Severity.HIGH

    fun usbDebugging(enabled: Boolean): Severity =
        if (enabled) Severity.MEDIUM else Severity.OK

    /** monthsOld < 0 means "unknown". */
    fun securityPatch(monthsOld: Int): Severity = when {
        monthsOld < 0 -> Severity.LOW
        monthsOld <= 2 -> Severity.OK
        monthsOld <= 5 -> Severity.MEDIUM
        else -> Severity.HIGH
    }

    fun rootIndicators(present: Boolean): Severity =
        if (present) Severity.HIGH else Severity.OK

    /** Any third-party accessibility service can read the whole screen. */
    fun accessibility(nonSystemServiceCount: Int): Severity =
        if (nonSystemServiceCount > 0) Severity.HIGH else Severity.OK

    fun deviceAdmin(nonSystemAdminCount: Int): Severity =
        if (nonSystemAdminCount > 0) Severity.MEDIUM else Severity.OK

    fun sideloadedApps(count: Int): Severity =
        if (count > 0) Severity.MEDIUM else Severity.OK

    /** An app holding 3+ sensitive permissions (mic/cam/sms/...) at once. */
    fun sensitivePermissionCombo(grantedSensitiveCount: Int): Severity =
        if (grantedSensitiveCount >= 3) Severity.MEDIUM else Severity.OK
}
