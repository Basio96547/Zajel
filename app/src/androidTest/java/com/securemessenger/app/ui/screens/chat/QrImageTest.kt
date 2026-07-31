package com.securemessenger.app.ui.screens.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.securemessenger.core.crypto.MailboxToken
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Covers pairing by picture rather than by camera: the code has to survive
 * being rendered, sent through another app, and read back out of whatever that
 * app did to it.
 */
class QrImageTest {

    private fun samplePayload(withSecret: Boolean = true, asciiName: Boolean = false): String =
        JSONObject().apply {
            put("u", "a1b2c3d4e5f6a7b8")
            put("k", "9f".repeat(32))
            put("n", if (asciiName) "Test Name" else "اسم تجريبي")
            if (withSecret) put("s", MailboxToken.pairSecretToHex(MailboxToken.newPairSecret()))
        }.toString()

    @Test
    fun renderedCodeDecodesBackToTheSamePayload() {
        val payload = samplePayload()
        val bitmap = QrImage.render(payload)
        assertNotNull("render produced nothing", bitmap)
        assertEquals(payload, QrImage.decodeFromBitmap(bitmap!!))
    }

    /**
     * Guards the round trip against payload-dependent failure.
     *
     * This test found a real product bug, and the way it found it is the
     * lesson: the single-shot test above failed once in a full suite, passed on
     * a re-run, and always passed alone. A first version of this test ran 60
     * payloads, came back clean, and I concluded content did not matter. **That
     * conclusion was wrong — it was one lucky run.** A later run of the same 60
     * failed 3 times, about 5%, and printed the payloads.
     *
     * The cause was `EncodeHintType.MARGIN = 2` in [QrImage]. That hint is in
     * modules, and the QR specification requires a quiet zone of 4 — so the
     * codes were out of spec, and whether one could be read back depended on
     * the data pattern. Fixed by using 4. Since a scanned code is the ONLY way
     * to add a contact, a 5% failure rate meant one pairing in twenty failing
     * with nothing to show the user why.
     *
     * Two things worth keeping from that: a clean run of a probabilistic test
     * is not evidence of absence, and this test prints the exact payload on
     * failure so the next occurrence is reproducible instead of re-argued.
     */
    /**
     * Isolates the one remaining variable: the non-ASCII display name.
     *
     * The failing payloads differ from the passing ones only in a random hex
     * secret, and the fault survived both a spec-correct quiet zone and a much
     * larger render size — so it is not geometry. What is left is the encoding
     * itself: the Arabic name forces ZXing into a UTF-8/ECI byte segment, and
     * ECI handling is a plausible place for an encoder and decoder to disagree
     * for particular byte sequences.
     *
     * If this passes while [everyRandomPayloadSurvivesTheRoundTrip] fails, the
     * non-ASCII segment is the cause and the fix belongs in how the payload is
     * built, not in the renderer.
     */
    @Test
    fun asciiOnlyPayloadsAlwaysSurvive() {
        val failures = (1..ROUND_TRIP_SAMPLES).count {
            val payload = samplePayload(asciiName = true)
            QrImage.render(payload)?.let { QrImage.decodeFromBitmap(it) } != payload
        }
        assertEquals("ASCII-only payloads failing too — the cause is not the charset", 0, failures)
    }

    @Test
    fun everyRandomPayloadSurvivesTheRoundTrip() {
        val failures = mutableListOf<String>()
        repeat(ROUND_TRIP_SAMPLES) {
            val payload = samplePayload()
            val bitmap = QrImage.render(payload)
            if (bitmap == null) {
                failures += "render returned null for: $payload"
                return@repeat
            }
            if (QrImage.decodeFromBitmap(bitmap) != payload) {
                // Retry the SAME payload immediately. This is the measurement
                // that separates the two possible worlds, and doing it inline
                // means one run answers the question instead of a week of
                // re-arguing it:
                //   all retries fail  -> the fault is in this content; the
                //                        encoder or the decode settings are
                //                        wrong for some patterns.
                //   retries mostly ok -> the content is fine and the fault is
                //                        non-deterministic; look at the
                //                        environment, not the payload.
                val retryFailures = (1..RETRY_PROBES).count {
                    QrImage.render(payload)?.let { bmp -> QrImage.decodeFromBitmap(bmp) } != payload
                }
                failures += "$retryFailures/$RETRY_PROBES retries also failed | $payload"
            }
        }
        assertTrue(
            "${failures.size}/$ROUND_TRIP_SAMPLES pairing codes did not survive render→decode.\n" +
                "Each line shows how many immediate retries of that SAME payload also failed —\n" +
                "all-fail means content-dependent, mostly-pass means non-deterministic:\n" +
                failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    private companion object {
        /**
         * Sized for the fault it exists to catch. The pre-fix failure rate was
         * about 5%, so 60 samples had a real chance of coming back clean — and
         * one such run is exactly what produced the wrong "content doesn't
         * matter" conclusion. At 200, a 5% fault is effectively certain to
         * appear, and the test still costs a couple of seconds.
         */
        const val ROUND_TRIP_SAMPLES = 200

        /** Immediate re-attempts of a payload that just failed, to tell a content fault from a flaky one. */
        const val RETRY_PROBES = 8
    }

    @Test
    fun codeSurvivesJpegRecompression() {
        // Messaging apps re-encode shared images. If the code stopped reading
        // after that, sharing it as a picture would be useless in practice —
        // this is what the high error-correction level is buying.
        val payload = samplePayload()
        val original = QrImage.render(payload)!!

        for (quality in intArrayOf(90, 60, 40)) {
            val bytes = ByteArrayOutputStream().also {
                original.compress(Bitmap.CompressFormat.JPEG, quality, it)
            }.toByteArray()
            val reloaded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertEquals(
                "code became unreadable after JPEG quality $quality",
                payload,
                QrImage.decodeFromBitmap(reloaded)
            )
        }
    }

    @Test
    fun codeSurvivesBeingScaledDown() {
        // A forwarded image is often resized by the sending app.
        val payload = samplePayload()
        val original = QrImage.render(payload)!!
        val scaled = Bitmap.createScaledBitmap(original, 320, 320, true)
        assertEquals(payload, QrImage.decodeFromBitmap(scaled))
    }

    @Test
    fun localOnlyShareDropsTheRelaySecretAndKeepsTheRest() {
        val full = samplePayload()
        val stripped = QrImage.stripRelaySecret(full)

        val before = JSONObject(full)
        val after = JSONObject(stripped)
        assertTrue("the full code should carry a relay secret", before.has("s"))
        assertFalse("the safe variant must not carry the relay secret", after.has("s"))
        // Everything else is public information and must survive, or the
        // stripped code would not pair at all.
        assertEquals(before.getString("u"), after.getString("u"))
        assertEquals(before.getString("k"), after.getString("k"))
        assertEquals(before.getString("n"), after.getString("n"))

        // And it still round-trips through an image.
        assertEquals(stripped, QrImage.decodeFromBitmap(QrImage.render(stripped)!!))
    }

    @Test
    fun aPictureWithNoCodeInItReturnsNull() {
        val blank = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.LTGRAY)
        }
        assertNull(QrImage.decodeFromBitmap(blank))
    }
}
