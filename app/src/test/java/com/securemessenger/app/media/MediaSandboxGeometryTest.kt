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
    fun refusesAnAbsurdClaimedSize() {
        assertFalse(
            "50000x50000 is ~10GB; it must be refused before reaching createBitmap",
            MediaSandbox.isPlausibleResult(50_000, 50_000, 400, Long.MAX_VALUE)
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
