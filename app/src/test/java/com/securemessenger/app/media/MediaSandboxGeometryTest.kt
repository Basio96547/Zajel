package com.securemessenger.app.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard on the media sandbox's **return** channel — the one direction in
 * which a process we assume can be compromised gets to speak to the process
 * that still holds every key.
 *
 * The pixels coming back are safe by construction: copied as raw bytes and
 * never re-parsed, so there is no decoder on the trusted side to attack. The
 * numbers beside them are not. `width` and `height` are attacker-chosen
 * integers that feed straight into an allocation, so a sandbox answering
 * "50000 x 50000" would make the *main* process attempt ten gigabytes and die
 * of OutOfMemoryError — converting a contained compromise back into a crash of
 * exactly the process the sandbox exists to protect.
 *
 * This is the first JVM unit test in :app, and the reason is worth stating
 * because the module's own build file says there are none: every other test
 * here touches libsodium, whose native library cannot load off-device, so they
 * all belong in androidTest. This one touches nothing — no Android class, no
 * native code, just arithmetic on four numbers. Requiring a connected phone to
 * check the handling of hostile input would make the least convenient test the
 * one guarding the most adversarial input.
 */
class MediaSandboxGeometryTest {

    @Test
    fun acceptsAnHonestDecode() {
        assertTrue(
            "an ordinary in-bounds image must pass, or the guard is just a bug",
            MediaSandbox.isPlausibleResult(300, 200, 400, 300L * 200 * 4)
        )
    }

    @Test
    fun acceptsAPanoramaThatOvershootsOnOneAxis() {
        // inSampleSize halves until the SMALLER side is under the bound, so a
        // long, thin image legitimately exceeds it on its long axis. A guard
        // that refused this would reject real photographs.
        assertTrue(
            MediaSandbox.isPlausibleResult(1500, 400, 400, 1500L * 400 * 4)
        )
    }

    @Test
    fun acceptsAVeryWidePanorama() {
        // 10:1. An earlier version capped BOTH sides at 4x the requested bound,
        // which silently rejected any aspect ratio past 4:1 — a real 360°
        // panorama among them. The smaller side is what the request governs;
        // the long side is bounded by the byte ceiling, not by a ratio.
        assertTrue(
            "a 10:1 panorama is a real photograph, not an attack",
            MediaSandbox.isPlausibleResult(4000, 400, 400, 4000L * 400 * 4)
        )
    }

    @Test
    fun refusesAnAbsurdClaimedSize() {
        assertFalse(
            "50000x50000 is ~10GB; it must be refused before reaching createBitmap",
            MediaSandbox.isPlausibleResult(50_000, 50_000, 400, Long.MAX_VALUE)
        )
    }

    /**
     * Pins the arithmetic to Long, which the test above does NOT do.
     *
     * With a 400px bound, 50000x50000 is rejected by the *side* gate before any
     * multiplication happens — so that test would still pass if the area maths
     * were written in Int, and would tell us nothing. These inputs deliberately
     * clear the side gate (a 20000 bound allows a smaller side up to 80000) so
     * execution actually reaches `width * height`.
     *
     * 50000 * 50000 = 2.5e9, past Int.MAX_VALUE (2.147e9). In Int that wraps
     * negative, which makes `needed > MAX_PIXEL_BYTES` false and
     * `bufferBytes >= needed` true for any buffer at all — the guard would
     * return true for the single most dangerous input it can receive. In Long
     * it is 1e10 bytes and refused. If someone ever "simplifies" those .toLong()
     * calls away, this is the test that fails.
     */
    @Test
    fun areaArithmeticDoesNotOverflowIntoAcceptance() {
        assertFalse(
            "50000x50000 past the side gate must still be refused — Int overflow would accept it",
            MediaSandbox.isPlausibleResult(50_000, 50_000, 20_000, Long.MAX_VALUE)
        )
        // Just inside the Int overflow threshold (46341^2 > 2^31) with a buffer
        // claim that only a wrapped, negative `needed` would ever satisfy.
        assertFalse(
            MediaSandbox.isPlausibleResult(46_341, 46_341, 20_000, 0L)
        )
        // The far corner, and the one that actually found a bug: Int.MAX_VALUE
        // on both axes is ~1.8e19 bytes, past Long's ~9.2e18 ceiling. Moving
        // the arithmetic to Long fixed the Int wrap but not this one — Long
        // wraps negative here too and every later check inverts identically.
        // The fix was ordering, not width: cap each side against the byte
        // ceiling BEFORE multiplying, so the product is provably in range.
        assertFalse(
            "Long overflows here too — the per-side cap must run before the multiplication",
            MediaSandbox.isPlausibleResult(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Long.MAX_VALUE)
        )
        // A side past the byte ceiling on its own, with an innocent other side.
        assertFalse(
            MediaSandbox.isPlausibleResult(Int.MAX_VALUE, 4, Int.MAX_VALUE, Long.MAX_VALUE)
        )
    }

    @Test
    fun refusesABigClaimBackedByASmallBuffer() {
        // The cheapest lie available. Without this check copyPixelsFromBuffer
        // would eventually notice — but only after the oversized allocation
        // had already been attempted, which is the damage.
        assertFalse(MediaSandbox.isPlausibleResult(1000, 1000, 400, 64))
    }

    @Test
    fun refusesAnythingOverTheAbsoluteCeilingHoweverLargeTheRequestWas() {
        // 8000x8000x4 is 256MB. Even a caller that asked for a 4000px bound
        // does not get to allocate that.
        assertFalse(MediaSandbox.isPlausibleResult(8000, 8000, 4000, Long.MAX_VALUE))
    }

    @Test
    fun refusesNonPositiveGeometry() {
        assertFalse("zero width", MediaSandbox.isPlausibleResult(0, 100, 400, 4096))
        assertFalse("negative height", MediaSandbox.isPlausibleResult(100, -1, 400, 4096))
        assertFalse("nonsense bound", MediaSandbox.isPlausibleResult(100, 100, 0, 4096))
    }
}
