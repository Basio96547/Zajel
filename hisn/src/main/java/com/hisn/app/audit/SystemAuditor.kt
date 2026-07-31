package com.hisn.app.audit

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * SystemAuditor - runs a set of on-device security-hygiene checks that a normal
 * (non-root) Android app is actually allowed to perform.
 *
 * IMPORTANT (honesty): these are hygiene and indicator checks. They RAISE THE
 * COST for an attacker and surface risky configuration/apps. They do NOT detect
 * or stop Pegasus-class spyware, which runs above app privilege.
 */
class SystemAuditor(private val context: Context) {

    private val knownStores = setOf(
        "com.android.vending",        // Google Play
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.amazon.venezia",
        "com.sec.android.app.samsungapps",
        "com.huawei.appmarket",
        "org.fdroid.fdroid"
    )

    private val sensitivePermissions = mapOf(
        "android.permission.RECORD_AUDIO" to "الميكروفون",
        "android.permission.CAMERA" to "الكاميرا",
        "android.permission.READ_SMS" to "الرسائل",
        "android.permission.RECEIVE_SMS" to "استقبال الرسائل",
        "android.permission.READ_CONTACTS" to "جهات الاتصال",
        "android.permission.ACCESS_FINE_LOCATION" to "الموقع الدقيق",
        "android.permission.READ_CALL_LOG" to "سجل المكالمات",
        "android.permission.SYSTEM_ALERT_WINDOW" to "الرسم فوق التطبيقات"
    )

    suspend fun runAudit(): List<Finding> = withContext(Dispatchers.Default) {
        val findings = mutableListOf<Finding>()
        findings += checkScreenLock()
        findings += checkSecurityPatch()
        findings += checkUsbDebugging()
        findings += checkRootIndicators()
        findings += checkAccessibilityServices()
        findings += checkDeviceAdmins()
        findings.addAll(checkInstalledApps())
        findings.sortedByDescending { it.severity.rank }
    }

    private fun checkScreenLock(): Finding {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val secure = km.isDeviceSecure
        return Finding(
            id = "screen_lock",
            title = "قفل الشاشة",
            detail = if (secure) "قفل شاشة آمن مُفعّل." else "لا يوجد قفل شاشة آمن (PIN/نمط/كلمة مرور).",
            recommendation = if (secure) "" else "فعّل قفل شاشة قوياً — أول خط دفاع عند فقدان الجهاز.",
            severity = Rules.screenLock(secure)
        )
    }

    private fun checkSecurityPatch(): Finding {
        val patch = Build.VERSION.SECURITY_PATCH // e.g. "2025-05-01"
        val monthsOld = monthsSince(patch)
        val severity = Rules.securityPatch(monthsOld)
        return Finding(
            id = "security_patch",
            title = "مستوى ترقيع الأمان",
            detail = "آخر تصحيح أمني: $patch" + if (monthsOld >= 0) " (قبل ~$monthsOld شهر)" else "",
            recommendation = if (severity == Severity.OK) "" else "حدّث النظام — الثغرات القديمة أسهل استغلالاً.",
            severity = severity
        )
    }

    private fun checkUsbDebugging(): Finding {
        val enabled = Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        return Finding(
            id = "usb_debugging",
            title = "تصحيح USB",
            detail = if (enabled) "تصحيح USB مُفعّل." else "تصحيح USB مُعطّل.",
            recommendation = if (enabled) "أطفئ تصحيح USB إلا عند الحاجة — يوسّع سطح الهجوم عبر الكابل." else "",
            severity = Rules.usbDebugging(enabled)
        )
    }

    private fun checkRootIndicators(): Finding {
        val suPaths = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/system/app/Superuser.apk", "/data/local/xbin/su", "/data/local/bin/su"
        )
        val hasSu = suPaths.any { File(it).exists() }
        val testKeys = Build.TAGS?.contains("test-keys") == true
        val rooted = hasSu || testKeys
        return Finding(
            id = "root",
            title = "مؤشرات الروت",
            detail = if (rooted) "عُثر على مؤشرات روت (su/test-keys)." else "لا مؤشرات روت ظاهرة.",
            recommendation = if (rooted) "جهاز مروّت = حماية النظام مكسورة. تجنّب استخدامه لبيانات حسّاسة." else "",
            severity = Rules.rootIndicators(rooted)
        )
    }

    private fun checkAccessibilityServices(): Finding {
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val services = enabled.split(":").filter { it.isNotBlank() }
        val nonSystem = services.mapNotNull { it.substringBefore("/").takeIf { p -> p.isNotBlank() } }
            .filter { pkg -> !isSystemApp(pkg) }
            .distinct()
        return Finding(
            id = "accessibility",
            title = "خدمات إمكانية الوصول",
            detail = if (nonSystem.isEmpty()) "لا خدمات وصول من طرف ثالث مُفعّلة."
            else "خدمات وصول مُفعّلة من: ${nonSystem.joinToString(", ") { appLabel(it) }}",
            recommendation = if (nonSystem.isEmpty()) ""
            else "إمكانية الوصول تقرأ كل ما على شاشتك. عطّلها لأي تطبيق لا تثق به تماماً.",
            severity = Rules.accessibility(nonSystem.size)
        )
    }

    private fun checkDeviceAdmins(): Finding {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admins = dpm.activeAdmins.orEmpty()
            .map { it.packageName }
            .filter { !isSystemApp(it) }
            .distinct()
        return Finding(
            id = "device_admin",
            title = "تطبيقات مسؤول الجهاز",
            detail = if (admins.isEmpty()) "لا تطبيقات طرف ثالث بصلاحية مسؤول الجهاز."
            else "صلاحية مسؤول الجهاز لدى: ${admins.joinToString(", ") { appLabel(it) }}",
            recommendation = if (admins.isEmpty()) ""
            else "مسؤول الجهاز صلاحية قوية (قفل/مسح). راجع من يملكها وأزل غير الموثوق.",
            severity = Rules.deviceAdmin(admins.size)
        )
    }

    private fun checkInstalledApps(): List<Finding> {
        val pm = context.packageManager
        val packages = try {
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        } catch (e: Exception) {
            return listOf(
                Finding(
                    "apps_error", "فحص التطبيقات",
                    "تعذّر تعداد التطبيقات: ${e.message}", "", Severity.LOW
                )
            )
        }

        val sideloaded = mutableListOf<String>()
        val highRisk = mutableListOf<Pair<String, List<String>>>()

        for (info in packages) {
            val pkg = info.packageName
            if (pkg == context.packageName) continue
            val isSystem = (info.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
            if (isSystem) continue

            // Sideloaded (installed outside a known store)?
            val installer = installerOf(pkg)
            if (installer == null || installer !in knownStores) {
                sideloaded += appLabel(pkg)
            }

            // Granted sensitive permissions
            val requested = info.requestedPermissions
            val flags = info.requestedPermissionsFlags
            if (requested != null && flags != null) {
                val granted = mutableListOf<String>()
                for (i in requested.indices) {
                    val isGranted = flags[i] and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
                    val label = sensitivePermissions[requested[i]]
                    if (isGranted && label != null) granted += label
                }
                if (granted.size >= 3) highRisk += appLabel(pkg) to granted.distinct()
            }
        }

        val results = mutableListOf<Finding>()

        results += Finding(
            id = "sideloaded",
            title = "تطبيقات من خارج المتجر",
            detail = if (sideloaded.isEmpty()) "لا تطبيقات مثبّتة من خارج متجر معروف."
            else "${sideloaded.size} تطبيق: ${sideloaded.take(6).joinToString(", ")}" +
                if (sideloaded.size > 6) "…" else "",
            recommendation = if (sideloaded.isEmpty()) ""
            else "التطبيقات المُنزّلة يدوياً أعلى خطراً. تأكّد من مصدر كل منها.",
            severity = Rules.sideloadedApps(sideloaded.size)
        )

        if (highRisk.isNotEmpty()) {
            results += Finding(
                id = "high_risk_perms",
                title = "تطبيقات بصلاحيات حسّاسة متعددة",
                detail = highRisk.take(6).joinToString("؛ ") { (app, perms) ->
                    "$app (${perms.joinToString("، ")})"
                } + if (highRisk.size > 6) "…" else "",
                recommendation = "راجع صلاحيات هذه التطبيقات في الإعدادات، واسحب ما لا يلزم.",
                severity = Severity.MEDIUM
            )
        } else {
            results += Finding(
                "high_risk_perms", "تطبيقات بصلاحيات حسّاسة متعددة",
                "لا تطبيقات طرف ثالث تجمع 3+ صلاحيات حسّاسة.", "", Severity.OK
            )
        }

        return results
    }

    // ==================== helpers ====================

    private fun installerOf(pkg: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(pkg).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(pkg)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun isSystemApp(pkg: String): Boolean {
        return try {
            val ai = context.packageManager.getApplicationInfo(pkg, 0)
            ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
        } catch (e: Exception) {
            false
        }
    }

    private fun appLabel(pkg: String): String {
        return try {
            val ai = context.packageManager.getApplicationInfo(pkg, 0)
            context.packageManager.getApplicationLabel(ai).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    /** Approx. whole months between a "yyyy-MM-dd" patch date and now; -1 if unparseable. */
    private fun monthsSince(dateStr: String?): Int {
        if (dateStr.isNullOrBlank()) return -1
        return try {
            val parts = dateStr.split("-")
            val year = parts[0].toInt()
            val month = parts[1].toInt()
            val cal = java.util.Calendar.getInstance()
            val nowYear = cal.get(java.util.Calendar.YEAR)
            val nowMonth = cal.get(java.util.Calendar.MONTH) + 1
            (nowYear - year) * 12 + (nowMonth - month)
        } catch (e: Exception) {
            -1
        }
    }
}
