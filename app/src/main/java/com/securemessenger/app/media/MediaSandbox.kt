package com.securemessenger.app.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "MediaSandbox"

/**
 * Main-process half of the media sandbox: hands peer-controlled image bytes to
 * a permissionless process and takes back pixels, never a parser.
 *
 * The contract this deliberately keeps is that **it can always be ignored**.
 * Every failure path — old Android, a device whose policy refuses the binding,
 * a crash, a hang, a malformed image — returns null, and the caller decodes
 * in-process exactly as it did before. That is what makes it safe to put in
 * front of the image path at all: the worst case is today's behaviour, not a
 * broken chat.
 */
object MediaSandbox {

    const val KEY_OK = "ok"
    const val KEY_ERR = "err"
    const val KEY_PIXELS = "pixels"
    const val KEY_WIDTH = "w"
    const val KEY_HEIGHT = "h"

    const val ERR_NONE = 0
    const val ERR_MALFORMED = 1
    const val ERR_TOO_LARGE = 2
    const val ERR_UNSUPPORTED = 4

    /**
     * A compromised sandbox can hang instead of crashing, which would otherwise
     * freeze an image bubble forever. Generous enough for a large photo on a
     * slow device, short enough that a deliberate stall is a blip.
     */
    private const val DECODE_TIMEOUT_MS = 8_000L

    /** Binding spawns a process; a cold start is not instant, but it is not seconds either. */
    private const val BIND_TIMEOUT_MS = 5_000L

    /**
     * One decode at a time. Not for correctness — the service could take
     * concurrent calls — but because each in-flight decode holds a full pixel
     * buffer, and a screenful of bubbles decoding at once would multiply that
     * by however many are visible.
     */
    private val gate = Mutex()

    @Volatile
    private var connection: Connection? = null

    /**
     * Whether this device can use the sandbox at all. SharedMemory arrived in
     * API 27 and there is no sane way to move megabytes of pixels back without
     * it, so API 26 simply keeps the in-process path.
     */
    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1

    /**
     * Decode [bytes] in the isolated process, or return null if anything at all
     * gets in the way. Never throws.
     */
    suspend fun decode(context: Context, bytes: ByteArray, maxDimension: Int): Bitmap? {
        if (!isSupported || bytes.isEmpty()) return null
        val app = context.applicationContext
        return gate.withLock {
            withContext(Dispatchers.IO) {
                try {
                    decodeLocked(app, bytes, maxDimension)
                } catch (e: Exception) {
                    Log.w(TAG, "sandbox decode failed, falling back in-process: ${e.javaClass.simpleName}")
                    dropConnection(app)
                    null
                }
            }
        }
    }

    private suspend fun decodeLocked(context: Context, bytes: ByteArray, maxDimension: Int): Bitmap? {
        val service = connect(context) ?: return null

        // A pipe, not shared memory, for the inbound direction. Shared memory
        // would mean reaching into SharedMemory's parcel layout to get a
        // descriptor AIDL accepts, which is undocumented and would break
        // silently on a platform change; a pipe is a first-class AIDL type. The
        // outbound direction still uses SharedMemory because pixels are far
        // larger and arrive as a Parcelable the platform marshals for us.
        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]

        val result: Bundle? = try {
            coroutineScope {
                launch(Dispatchers.IO) {
                    try {
                        ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { it.write(bytes) }
                    } catch (e: Exception) {
                        // The reader going away mid-write is normal on timeout.
                        Log.d(TAG, "feeding the sandbox stopped early: ${e.javaClass.simpleName}")
                    }
                }
                withTimeoutOrNull(DECODE_TIMEOUT_MS) {
                    service.decodeImage(readSide, bytes.size.toLong(), maxDimension)
                }
            }
        } finally {
            try { readSide.close() } catch (_: Exception) {}
        }

        if (result == null) {
            // A hang is the one failure a crashed process cannot produce, and
            // the one a hostile process would choose. Drop the binding: Android
            // destroys an isolated process the moment its last binding goes, so
            // this is also how we kill it.
            Log.w(TAG, "sandbox timed out — dropping the process")
            dropConnection(context)
            return null
        }

        if (!result.getBoolean(KEY_OK, false)) {
            // A clean "no" is the sandbox working, not failing: a malformed
            // image should surface as a broken-image placeholder, and that is
            // exactly what null does here.
            return null
        }

        val shared = pixelsOf(result) ?: return null
        val width = result.getInt(KEY_WIDTH)
        val height = result.getInt(KEY_HEIGHT)
        if (width <= 0 || height <= 0) {
            shared.close()
            return null
        }

        return try {
            val pixels = shared.mapReadOnly()
            try {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    .apply { copyPixelsFromBuffer(pixels) }
            } finally {
                SharedMemory.unmap(pixels)
            }
        } finally {
            shared.close()
        }
    }

    @Suppress("DEPRECATION")
    private fun pixelsOf(result: Bundle): SharedMemory? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            result.getParcelable(KEY_PIXELS, SharedMemory::class.java)
        } else {
            result.getParcelable(KEY_PIXELS)
        }

    private suspend fun connect(context: Context): IMediaSandbox? {
        connection?.let { existing ->
            val live = existing.service
            if (live != null && live.asBinder().isBinderAlive) return live
            // Dead or half-built: unbind properly before building another, or
            // the old binding leaks and the process may linger.
            dropConnection(context)
        }

        val pending = CompletableDeferred<IMediaSandbox?>()
        val conn = Connection(pending)
        val bound = try {
            context.bindService(
                Intent(context, MediaSandboxService::class.java),
                conn,
                Context.BIND_AUTO_CREATE
            )
        } catch (e: Exception) {
            false
        }
        if (!bound) {
            Log.w(TAG, "bindService refused — staying on the in-process decoder")
            try { context.unbindService(conn) } catch (_: Exception) {}
            return null
        }
        connection = conn

        val service = withTimeoutOrNull(BIND_TIMEOUT_MS) { pending.await() }
        if (service == null) {
            Log.w(TAG, "sandbox did not come up in time")
            dropConnection(context)
        }
        return service
    }

    private fun dropConnection(context: Context) {
        val conn = connection ?: return
        connection = null
        try { context.unbindService(conn) } catch (_: Exception) {}
    }

    /**
     * Binding state. [onServiceDisconnected] fires when the isolated process
     * dies — including when it dies mid-decode because a crafted image found a
     * bug in a native parser, which is precisely the event this whole design
     * exists to survive. It only marks the binder dead; the unbinding itself is
     * left to the next [connect], which has a Context to do it properly.
     */
    private class Connection(val pending: CompletableDeferred<IMediaSandbox?>) : ServiceConnection {
        @Volatile
        var service: IMediaSandbox? = null

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IMediaSandbox.Stub.asInterface(binder)
            pending.complete(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "sandbox process died — the main process is unaffected, which is the point")
            service = null
            if (!pending.isCompleted) pending.complete(null)
        }
    }
}
