package com.securemessenger.app.media

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * The tests ADR-0001 said could not be written without a device.
 *
 * The sandbox's whole value is a claim about what happens when decoding goes
 * wrong — the process is powerless, and its death is survivable. Neither can be
 * shown by compiling, and neither is shown by a decode that succeeds. So the
 * two that matter here are [sandboxRunsUnderItsOwnIsolatedUid], which proves
 * the platform really applied the isolation rather than silently ignoring the
 * manifest flag, and [killingTheSandboxLeavesTheAppAliveAndItRecovers], which
 * kills the process the way a successful exploit or an OOM would and checks
 * that the app neither dies nor stays broken.
 */
@RunWith(AndroidJUnit4::class)
class MediaSandboxTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun shell(command: String): String {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use {
            it.readBytes().toString(Charsets.UTF_8)
        }
    }

    /** A real, valid PNG — the sandbox must handle the ordinary case before anything else is interesting. */
    private fun samplePng(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, android.graphics.Color.rgb((x * 7) % 256, (y * 11) % 256, 90))
            }
        }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            .toByteArray()
            .also { bitmap.recycle() }
    }

    private fun sandboxProcessLine(): String? =
        shell("ps -A -o USER,PID,NAME").lineSequence()
            .firstOrNull { it.contains("mediaSandbox") }

    @Test
    fun decodesARealImageInTheSandbox() {
        assumeTrue("SharedMemory needs API 27+", MediaSandbox.isSupported)
        val bytes = samplePng(300, 200)

        val decoded = runBlocking { MediaSandbox.decode(context, bytes, maxDimension = 400) }

        assertNotNull("the sandbox returned nothing for a valid PNG", decoded)
        assertEquals(300, decoded!!.width)
        assertEquals(200, decoded.height)
    }

    /**
     * Proves the isolation is real, not merely declared.
     *
     * `android:isolatedProcess="true"` is the entire security argument, and it
     * is applied by the platform, not by us — so the thing worth asserting is
     * that the platform actually did it. An isolated process runs under its own
     * ephemeral uid in the 99000+ range, distinct from the app's, which is
     * exactly what stops it reaching our files, our Keystore entries and the
     * network. If this ever fails, the sandbox has quietly become an ordinary
     * process of ours and buys nothing.
     */
    @Test
    fun sandboxRunsUnderItsOwnIsolatedUid() {
        assumeTrue("SharedMemory needs API 27+", MediaSandbox.isSupported)
        runBlocking { MediaSandbox.decode(context, samplePng(64, 64), maxDimension = 64) }

        val line = sandboxProcessLine()
        assertNotNull("no :mediaSandbox process found while bound", line)

        val user = line!!.trim().split(Regex("\\s+"))[0]
        val ourUser = shell("ps -A -o USER,NAME").lineSequence()
            .first { it.trimEnd().endsWith("com.securemessenger.app") }
            .trim().split(Regex("\\s+"))[0]

        assertTrue(
            "sandbox runs as '$user', the app runs as '$ourUser' — they must differ, " +
                "or isolatedProcess did not take effect",
            user != ourUser
        )
        assertTrue(
            "an isolated process should run under an ephemeral u*_i* uid, got '$user'",
            user.contains("_i")
        )
    }

    @Test
    fun malformedBytesFailCleanlyRatherThanCrashing() {
        assumeTrue("SharedMemory needs API 27+", MediaSandbox.isSupported)
        // Right magic bytes, garbage body: gets past a superficial sniff and
        // into the actual parser, which is where the interesting failures live.
        val corrupt = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
            ByteArray(4096) { (it * 31).toByte() }

        val decoded = runBlocking { MediaSandbox.decode(context, corrupt, maxDimension = 400) }

        assertNull("a malformed image must come back as a clean failure", decoded)
        // Still usable afterwards: one bad image must not poison the path.
        val good = runBlocking { MediaSandbox.decode(context, samplePng(80, 80), maxDimension = 200) }
        assertNotNull("a valid image after a malformed one still has to decode", good)
    }

    /**
     * The containment claim, tested the only way it can be: kill the process
     * outright, exactly as a successful memory-safety exploit or the
     * out-of-memory killer would, and see what survives.
     *
     * Two assertions matter. The app process is still running — trivially true
     * because this test keeps executing, which is the point: today, without the
     * sandbox, a native crash in the decoder would have taken this process with
     * it. And the next decode succeeds, proving the client notices the death,
     * discards the binding and builds a fresh process instead of wedging.
     */
    @Test
    fun killingTheSandboxLeavesTheAppAliveAndItRecovers() {
        assumeTrue("SharedMemory needs API 27+", MediaSandbox.isSupported)

        assertNotNull(
            "precondition: a decode has to work before killing anything",
            runBlocking { MediaSandbox.decode(context, samplePng(120, 90), maxDimension = 200) }
        )

        val line = sandboxProcessLine()
        assertNotNull("no :mediaSandbox process to kill", line)
        val pid = line!!.trim().split(Regex("\\s+"))[1]
        shell("kill -9 $pid")

        // The app is obviously alive — this line is running in it. Asserted
        // anyway so the intent is on the record rather than implied.
        assertTrue("the app process must survive the sandbox dying", android.os.Process.myPid() > 0)

        val after = runBlocking { MediaSandbox.decode(context, samplePng(120, 90), maxDimension = 200) }
        assertNotNull("after the sandbox was killed, the next decode must rebuild it", after)
        assertEquals(120, after!!.width)
    }
}
