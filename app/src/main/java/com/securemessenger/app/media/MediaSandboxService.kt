package com.securemessenger.app.media

import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import androidx.annotation.RequiresApi
import java.io.FileInputStream

/**
 * Decodes peer-controlled image bytes, inside a process that has nothing worth
 * stealing.
 *
 * This runs under `android:isolatedProcess="true"`, which is the entire point:
 * the platform puts it in the restricted `isolated_app` SELinux domain with an
 * empty permission set — no INTERNET, no access to the app's files, no
 * Keystore, no content providers. If a memory-safety bug in Skia's image
 * parsers is exploited by a crafted image, the code that wakes up is in an
 * empty box and still has a Binder boundary between it and anything valuable.
 * Compare that with today's fallback path, where the same bug lands directly in
 * a process holding the message database key and every ratchet session.
 *
 * What deliberately does NOT happen here: decryption. The main process unseals
 * the media and passes plaintext in. libsodium's secretbox is a fixed-shape,
 * heavily audited primitive and a far worse candidate for a parser bug than a
 * media container; more importantly, moving it here would hand a key to the one
 * process we assume can be compromised. Everything this process can see, the
 * sender who made the image already had.
 *
 * This class must stay small and dependency-free. Every line here is code the
 * untrusted side runs, so anything imported becomes attack surface that a
 * successful exploit inherits.
 */
class MediaSandboxService : Service() {

    private val binder = object : IMediaSandbox.Stub() {
        override fun decodeImage(
            src: ParcelFileDescriptor?,
            byteLength: Long,
            maxDimension: Int
        ): Bundle {
            // The sandbox is only ever reached on API 27+ (see MediaSandbox);
            // SharedMemory does not exist before that.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
                return failure(MediaSandbox.ERR_UNSUPPORTED)
            }
            return try {
                decode(src, byteLength, maxDimension)
            } catch (e: OutOfMemoryError) {
                // Caught, not propagated: an image whose declared dimensions
                // survive the sampling maths but still cannot be allocated is a
                // decode failure, not a reason to take the process down.
                failure(MediaSandbox.ERR_TOO_LARGE)
            } catch (e: Throwable) {
                failure(MediaSandbox.ERR_MALFORMED)
            } finally {
                try { src?.close() } catch (_: Exception) {}
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O_MR1)
    private fun decode(src: ParcelFileDescriptor?, byteLength: Long, maxDimension: Int): Bundle {
        if (src == null || byteLength <= 0 || byteLength > MAX_INPUT_BYTES || maxDimension <= 0) {
            return failure(MediaSandbox.ERR_MALFORMED)
        }

        val bytes = ByteArray(byteLength.toInt())
        FileInputStream(src.fileDescriptor).use { input ->
            var read = 0
            while (read < bytes.size) {
                val n = input.read(bytes, read, bytes.size - read)
                if (n < 0) break
                read += n
            }
            if (read != bytes.size) return failure(MediaSandbox.ERR_MALFORMED)
        }

        // Same two-pass, bounded decode the in-process path uses: read the
        // header for the declared size, pick a sample factor, then decode once
        // at roughly the size we actually want. Decoding full-resolution and
        // scaling afterwards would allocate the very buffer the bound exists to
        // avoid — and inside a process whose death is cheap but not free.
        val header = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, header)
        if (header.outWidth <= 0 || header.outHeight <= 0) return failure(MediaSandbox.ERR_MALFORMED)

        var sample = 1
        while (header.outWidth / (sample * 2) >= maxDimension &&
            header.outHeight / (sample * 2) >= maxDimension
        ) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: return failure(MediaSandbox.ERR_MALFORMED)

        val byteCount = bitmap.width * bitmap.height * 4
        if (byteCount <= 0 || byteCount > MAX_PIXEL_BYTES) {
            bitmap.recycle()
            return failure(MediaSandbox.ERR_TOO_LARGE)
        }

        val shared = SharedMemory.create("decoded", byteCount)
        val buffer = shared.mapReadWrite()
        return try {
            bitmap.copyPixelsToBuffer(buffer)
            // Read-only from here on, including for us: the receiver should not
            // have to trust that this process left the buffer alone after
            // handing it over.
            shared.setProtect(android.system.OsConstants.PROT_READ)
            Bundle().apply {
                putBoolean(MediaSandbox.KEY_OK, true)
                putInt(MediaSandbox.KEY_ERR, MediaSandbox.ERR_NONE)
                putInt(MediaSandbox.KEY_WIDTH, bitmap.width)
                putInt(MediaSandbox.KEY_HEIGHT, bitmap.height)
                // SharedMemory is itself Parcelable and marshals as its
                // descriptor, so the pixels never enter the transaction.
                //
                // It is deliberately not closed here: the framework only
                // duplicates the descriptor while writing the reply, which
                // happens after this method returns, so closing now would send
                // a dead fd. That leaves one descriptor held per decode until
                // the process ends — acceptable precisely because this process
                // is disposable and the client drops the binding when idle,
                // which kills it outright.
                putParcelable(MediaSandbox.KEY_PIXELS, shared)
            }
        } finally {
            SharedMemory.unmap(buffer)
            bitmap.recycle()
        }
    }

    private fun failure(code: Int) = Bundle().apply {
        putBoolean(MediaSandbox.KEY_OK, false)
        putInt(MediaSandbox.KEY_ERR, code)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private companion object {
        /** Mirrors SecureRepository.MAX_MEDIA_BYTES plus slack; a larger input is refused rather than parsed. */
        const val MAX_INPUT_BYTES = 8L * 1024 * 1024

        /** ~2160² ARGB_8888 with headroom. Bounds what one decode can ask the system for. */
        const val MAX_PIXEL_BYTES = 64 * 1024 * 1024
    }
}
