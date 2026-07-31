package com.hisn.app.audit

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AuditLog - append-only, tamper-evident history of scans.
 *
 * At rest it is encrypted (AndroidX Security). Each record is linked into a hash
 * chain ([AuditChain]) so past entries can't be silently edited. Export produces a
 * portable JSON of the whole chain plus a device HMAC "seal", and [verifyExport]
 * re-checks both — this is the log a user could hand to a lawyer to show the
 * device's recorded state before a given date.
 */
object AuditLog {

    private const val PREFS = "hisn_audit_log"
    private const val KEY_HISTORY = "history"
    private const val KEY_SNAPSHOT = "last_snapshot"
    private const val KEY_DEVICE_KEY = "device_hmac_key"
    private const val MAX_ENTRIES = 500
    const val EXPORT_VERSION = 1

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context, PREFS,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // ==================== record / read ====================

    fun record(context: Context, timestamp: Long, findings: List<Finding>): AuditEntry {
        val p = prefs(context)
        val existing = readEntries(p)
        val entry = AuditChain.append(
            prev = existing.lastOrNull(),
            timestamp = timestamp,
            score = HygieneScore.compute(findings),
            critical = findings.count { it.severity == Severity.CRITICAL },
            high = findings.count { it.severity == Severity.HIGH },
            medium = findings.count { it.severity == Severity.MEDIUM },
            low = findings.count { it.severity == Severity.LOW }
        )
        val updated = (existing + entry).takeLast(MAX_ENTRIES)
        p.edit().putString(KEY_HISTORY, entriesToJson(updated).toString()).apply()
        return entry
    }

    fun history(context: Context): List<AuditEntry> = readEntries(prefs(context))

    // ==================== change-detection snapshot ====================

    fun saveSnapshot(context: Context, snapshot: ScanSnapshot) {
        val arr = JSONArray()
        for (f in snapshot.findings) {
            arr.put(JSONObject().apply {
                put("id", f.id); put("title", f.title); put("sev", f.severity.name)
            })
        }
        val root = JSONObject().apply { put("score", snapshot.score); put("findings", arr) }
        prefs(context).edit().putString(KEY_SNAPSHOT, root.toString()).apply()
    }

    fun loadSnapshot(context: Context): ScanSnapshot? {
        val raw = prefs(context).getString(KEY_SNAPSHOT, null) ?: return null
        return try {
            val root = JSONObject(raw)
            val arr = root.getJSONArray("findings")
            val findings = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SnapFinding(o.getString("id"), o.getString("title"), Severity.valueOf(o.getString("sev")))
            }
            ScanSnapshot(root.getInt("score"), findings)
        } catch (e: Exception) {
            null
        }
    }

    // ==================== export / verify ====================

    data class ExportResult(val json: String, val entryCount: Int)

    fun export(context: Context, exportedAt: Long): ExportResult {
        val p = prefs(context)
        val entries = readEntries(p)
        val head = entries.lastOrNull()?.hash ?: AuditChain.GENESIS
        val seal = hmacHex(deviceKey(p), head)
        val root = JSONObject().apply {
            put("app", "hisn")
            put("version", EXPORT_VERSION)
            put("exportedAt", exportedAt)
            put("entries", entriesToJson(entries))
            put("head", head)
            put("deviceSeal", seal)
        }
        return ExportResult(root.toString(2), entries.size)
    }

    /**
     * Result of importing and checking an exported log. Chain integrity is
     * device-independent (anyone can verify entries weren't altered); the device
     * seal can only be confirmed on the device that produced the export.
     */
    data class VerificationReport(
        val parseError: String?,
        val entryCount: Int,
        val chainIntact: Boolean,
        val brokenAtIndex: Int,          // -1 if intact
        val sealMatchesThisDevice: Boolean,
        val firstTs: Long,
        val lastTs: Long,
        val timeline: List<Pair<Long, Int>>  // (timestamp, score)
    )

    fun verifyImport(context: Context, json: String): VerificationReport {
        return try {
            val root = JSONObject(json)
            val entries = jsonToEntries(root.getJSONArray("entries"))
            val chain = AuditChain.verifyDetailed(entries)
            val actualHead = entries.lastOrNull()?.hash ?: AuditChain.GENESIS
            val headMatches = root.optString("head") == actualHead
            val chainIntact = chain.valid && headMatches
            val brokenAt = if (!chain.valid) chain.brokenAtIndex else if (!headMatches) entries.size else -1

            val sealMatches = chainIntact &&
                root.optString("deviceSeal") == hmacHex(deviceKey(prefs(context)), actualHead)

            VerificationReport(
                parseError = null,
                entryCount = entries.size,
                chainIntact = chainIntact,
                brokenAtIndex = brokenAt,
                sealMatchesThisDevice = sealMatches,
                firstTs = entries.firstOrNull()?.timestamp ?: 0L,
                lastTs = entries.lastOrNull()?.timestamp ?: 0L,
                timeline = entries.map { it.timestamp to it.score }
            )
        } catch (e: Exception) {
            VerificationReport("تعذّرت قراءة الملف: ${e.message}", 0, false, -1, false, 0, 0, emptyList())
        }
    }

    // ==================== helpers ====================

    private fun readEntries(p: android.content.SharedPreferences): List<AuditEntry> =
        jsonToEntries(JSONArray(p.getString(KEY_HISTORY, "[]")))

    private fun entriesToJson(entries: List<AuditEntry>): JSONArray {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(JSONObject().apply {
                put("t", e.timestamp); put("score", e.score)
                put("critical", e.critical); put("high", e.high)
                put("medium", e.medium); put("low", e.low)
                put("prev", e.prevHash); put("hash", e.hash)
            })
        }
        return arr
    }

    private fun jsonToEntries(arr: JSONArray): List<AuditEntry> =
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            AuditEntry(
                timestamp = o.optLong("t"), score = o.optInt("score"),
                critical = o.optInt("critical"), high = o.optInt("high"),
                medium = o.optInt("medium"), low = o.optInt("low"),
                prevHash = o.optString("prev"), hash = o.optString("hash")
            )
        }

    private fun deviceKey(p: android.content.SharedPreferences): ByteArray {
        p.getString(KEY_DEVICE_KEY, null)?.let { return hexToBytes(it) }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        p.edit().putString(KEY_DEVICE_KEY, key.joinToString("") { "%02x".format(it) }).apply()
        return key
    }

    private fun hmacHex(key: ByteArray, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
