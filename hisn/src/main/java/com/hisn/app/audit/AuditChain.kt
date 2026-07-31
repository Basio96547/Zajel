package com.hisn.app.audit

import java.security.MessageDigest

/**
 * One recorded scan in the tamper-evident audit log.
 *
 * Each entry carries the hash of the previous entry, so the whole history forms
 * a hash chain: altering, inserting, or deleting any past entry breaks every hash
 * after it. This is what lets a user later demonstrate that their device state was
 * recorded on a given date and hasn't been edited since.
 */
data class AuditEntry(
    val timestamp: Long,
    val score: Int,
    val critical: Int,
    val high: Int,
    val medium: Int,
    val low: Int,
    val prevHash: String,
    val hash: String
)

/**
 * AuditChain - pure hash-chain logic (no Android APIs), so it is unit-tested on
 * the JVM. Integrity here means "tamper-evident": we can prove the log wasn't
 * silently edited. (Non-repudiation to a third party without the device is a
 * separate, future concern — see README.)
 */
object AuditChain {

    const val GENESIS = "GENESIS"

    /** Deterministic content of an entry that the hash commits to. */
    fun payload(timestamp: Long, score: Int, critical: Int, high: Int, medium: Int, low: Int, prevHash: String): String =
        "$timestamp|$score|$critical|$high|$medium|$low|$prevHash"

    fun hashOf(timestamp: Long, score: Int, critical: Int, high: Int, medium: Int, low: Int, prevHash: String): String =
        sha256Hex(payload(timestamp, score, critical, high, medium, low, prevHash))

    /** Build the next entry that links to [prev] (or GENESIS for the first). */
    fun append(prev: AuditEntry?, timestamp: Long, score: Int, critical: Int, high: Int, medium: Int, low: Int): AuditEntry {
        val prevHash = prev?.hash ?: GENESIS
        return AuditEntry(
            timestamp, score, critical, high, medium, low, prevHash,
            hash = hashOf(timestamp, score, critical, high, medium, low, prevHash)
        )
    }

    /** valid=true iff the whole chain links correctly; brokenAtIndex is -1 then. */
    data class ChainResult(val valid: Boolean, val brokenAtIndex: Int)

    /** Device-independent integrity check — pure SHA-256, needs no secret key. */
    fun verifyDetailed(entries: List<AuditEntry>): ChainResult {
        var expectedPrev = GENESIS
        for ((i, e) in entries.withIndex()) {
            if (e.prevHash != expectedPrev) return ChainResult(false, i)
            val recomputed = hashOf(e.timestamp, e.score, e.critical, e.high, e.medium, e.low, e.prevHash)
            if (recomputed != e.hash) return ChainResult(false, i)
            expectedPrev = e.hash
        }
        return ChainResult(true, -1)
    }

    /** True iff every entry links correctly to its predecessor. */
    fun verify(entries: List<AuditEntry>): Boolean = verifyDetailed(entries).valid

    fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
