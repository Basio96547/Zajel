package com.securemessenger.app.media

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * Records a voice message as raw PCM, then automatically disguises the
 * speaker's voice by resampling the audio to a different pitch before it's
 * ever encrypted or sent — every voice message sounds different from the
 * sender's real voice. This is a simple pitch+speed shift, not
 * studio-quality voice anonymization.
 */
object VoiceRecorder {
    private const val SAMPLE_RATE = 16_000
    // >1 = higher/faster ("chipmunk"), <1 = deeper/slower. Applied to every
    // recording automatically — there is no "off" switch, by design.
    private const val PITCH_FACTOR = 1.35f

    private const val WAVEFORM_BUCKETS = 40

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val buffer = ByteArrayOutputStream()
    @Volatile private var isRecording = false

    /** [wav]: the pitch-shifted, WAV-wrapped recording. [waveform]: its downsampled amplitude envelope. */
    data class RecordingResult(val wav: ByteArray, val waveform: List<Float>)

    /**
     * Start/stop/cancel all touch the same mutable state (audioRecord,
     * recordingThread, buffer). A quick stop-then-start from the UI could
     * previously overlap — start() reassigning audioRecord while the prior
     * stopAndEncode() coroutine was still tearing down the old one — corrupting
     * both recordings ("sometimes it records, sometimes it doesn't"). Every
     * entry point now serializes on this lock.
     */
    private val lock = Object()

    fun start() {
        synchronized(lock) {
            // Already recording (e.g. a fast double-tap raced ahead of the UI
            // state) — never leak a second AudioRecord on top of the first.
            if (isRecording) return

            val minBufSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            require(minBufSize > 0) { "الجهاز لا يدعم تسجيل الصوت بهذا الإعداد" }

            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufSize * 2
            )
            // AudioRecord can silently fail to initialize (mic busy/HAL error)
            // without throwing — startRecording() on that object throws
            // IllegalStateException, which used to escape uncaught.
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                throw IllegalStateException("تعذّر الوصول إلى الميكروفون — قد يكون قيد الاستخدام من تطبيق آخر")
            }

            buffer.reset()
            audioRecord = record
            isRecording = true
            record.startRecording()

            recordingThread = Thread {
                val chunk = ByteArray(minBufSize)
                while (isRecording) {
                    val read = try {
                        record.read(chunk, 0, chunk.size)
                    } catch (_: Exception) {
                        break
                    }
                    if (read > 0) {
                        synchronized(buffer) { buffer.write(chunk, 0, read) }
                    }
                }
            }.also { it.start() }
        }
    }

    /** Stop recording and return a pitch-shifted, encoded WAV file plus its waveform envelope. */
    suspend fun stopAndEncode(): RecordingResult = withContext(Dispatchers.Default) {
        val rawPcm = synchronized(lock) {
            isRecording = false
            recordingThread?.join(500)
            recordingThread = null
            audioRecord?.apply {
                try { stop() } catch (_: Exception) {}
                release()
            }
            audioRecord = null
            synchronized(buffer) { buffer.toByteArray() }
        }
        val shifted = pitchShift(rawPcm, PITCH_FACTOR)
        RecordingResult(wrapAsWav(shifted, SAMPLE_RATE), extractWaveform(shifted, WAVEFORM_BUCKETS))
    }

    fun cancel() {
        synchronized(lock) {
            isRecording = false
            recordingThread?.join(500)
            recordingThread = null
            audioRecord?.apply {
                try { stop() } catch (_: Exception) {}
                release()
            }
            audioRecord = null
            synchronized(buffer) { buffer.reset() }
        }
    }

    /** Downsample a full recording into [buckets] normalized (0f..1f) peaks for a static waveform. */
    private fun extractWaveform(pcm16: ByteArray, buckets: Int): List<Float> {
        val sampleCount = pcm16.size / 2
        if (sampleCount == 0) return emptyList()
        val samplesPerBucket = (sampleCount / buckets).coerceAtLeast(1)
        val result = ArrayList<Float>(buckets)
        var i = 0
        while (i < sampleCount && result.size < buckets) {
            var peak = 0
            val end = (i + samplesPerBucket).coerceAtMost(sampleCount)
            for (j in i until end) {
                val lo = pcm16[j * 2].toInt() and 0xFF
                val hi = pcm16[j * 2 + 1].toInt()
                val sample = abs(((hi shl 8) or lo).toShort().toInt())
                if (sample > peak) peak = sample
            }
            result.add((peak / 32768f).coerceIn(0f, 1f))
            i += samplesPerBucket
        }
        return result
    }

    /**
     * Pitch (and speed) shift by resampling: reading the original samples at a
     * different rate than they were recorded changes both pitch and tempo.
     * factor > 1 shortens/raises the pitch, factor < 1 lengthens/lowers it.
     */
    private fun pitchShift(pcm16: ByteArray, factor: Float): ByteArray {
        val sampleCount = pcm16.size / 2
        if (sampleCount == 0) return ByteArray(0)
        val samples = ShortArray(sampleCount)
        for (i in 0 until sampleCount) {
            val lo = pcm16[i * 2].toInt() and 0xFF
            val hi = pcm16[i * 2 + 1].toInt()
            samples[i] = ((hi shl 8) or lo).toShort()
        }

        val outCount = (sampleCount / factor).toInt().coerceAtLeast(1)
        val out = ByteArray(outCount * 2)
        for (i in 0 until outCount) {
            val srcPos = i * factor
            val srcIndex = srcPos.toInt().coerceIn(0, sampleCount - 1)
            val sample = samples[srcIndex]
            out[i * 2] = (sample.toInt() and 0xFF).toByte()
            out[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Wrap raw 16-bit mono PCM in a minimal WAV container Android's MediaPlayer can play. */
    private fun wrapAsWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val byteRate = sampleRate * 2
        val header = ByteArray(44)
        fun writeString(offset: Int, s: String) = s.forEachIndexed { i, c -> header[offset + i] = c.code.toByte() }
        fun writeInt(offset: Int, v: Int) {
            header[offset] = (v and 0xFF).toByte()
            header[offset + 1] = ((v shr 8) and 0xFF).toByte()
            header[offset + 2] = ((v shr 16) and 0xFF).toByte()
            header[offset + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun writeShort(offset: Int, v: Int) {
            header[offset] = (v and 0xFF).toByte()
            header[offset + 1] = ((v shr 8) and 0xFF).toByte()
        }

        writeString(0, "RIFF")
        writeInt(4, 36 + pcm.size)
        writeString(8, "WAVE")
        writeString(12, "fmt ")
        writeInt(16, 16)
        writeShort(20, 1) // PCM
        writeShort(22, 1) // mono
        writeInt(24, sampleRate)
        writeInt(28, byteRate)
        writeShort(32, 2) // block align
        writeShort(34, 16) // bits per sample
        writeString(36, "data")
        writeInt(40, pcm.size)

        return header + pcm
    }
}
