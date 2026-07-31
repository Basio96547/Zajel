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

    private fun samplePayload(withSecret: Boolean = true): String = JSONObject().apply {
        put("u", "a1b2c3d4e5f6a7b8")
        put("k", "9f".repeat(32))
        put("n", "اسم تجريبي")
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
     * Why it exists, and what it has already settled: on 2026-07-31 the
     * single-shot test above failed once inside a full-suite run, passed on a
     * re-run of the same suite, and passed run after run in isolation. Because
     * every payload carries a fresh random pair secret, the obvious suspect was
     * that some content encodes into a code this renderer cannot read back.
     *
     * **That suspicion is now ruled out**: 60 fresh random payloads survive the
     * round trip. Whatever caused that one failure, it is not the content — so
     * do not "fix" the encoder or the error-correction level on that theory.
     * The remaining hypotheses are environmental (memory or native-resource
     * pressure from the tests that run before this one in a full suite), and
     * none of them is confirmed. **The flake is real and its cause is still
     * unidentified.**
     *
     * This is kept because a pairing code that is unreadable even rarely is not
     * a test nuisance — scanning one is the ONLY way to add a contact, so a
     * user who hits it has no other route. It reports the exact payload on
     * failure so the next occurrence is reproducible instead of re-guessed.
     */
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
            val decoded = QrImage.decodeFromBitmap(bitmap)
            if (decoded != payload) {
                failures += "decoded=${decoded ?: "null"} for: $payload"
            }
        }
        assertTrue(
            "${failures.size}/$ROUND_TRIP_SAMPLES pairing codes did not survive render→decode:\n" +
                failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    private companion object {
        const val ROUND_TRIP_SAMPLES = 60
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
