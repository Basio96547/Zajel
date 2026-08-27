package com.securemessenger.app.ui.screens.chat

import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.securemessenger.core.crypto.MediaCodec
import com.securemessenger.app.media.ByteArrayMediaDataSource
import com.securemessenger.app.ui.gesture.rememberZoomState
import com.securemessenger.app.ui.gesture.zoomable
import com.securemessenger.app.ui.theme.Dims
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ---------- media content ----------

/**
 * Decodes at a bounded resolution off the UI thread — a full-res camera photo
 * never bloats memory just to fit a bubble. The inSampleSize loop below keeps
 * the FINAL allocated buffer close to maxDimension² regardless of how large
 * the file's declared width/height are (halving repeatedly until under the
 * cap) — a "decompression bomb" header claiming an enormous resolution is
 * already fully neutralized by this, independent of the source file's
 * declared size. (Note: this bounds the *output allocation*, not the
 * inJustDecodeBounds header-read step itself — a memory-safety bug in Skia's
 * header parser, as opposed to a huge-declared-dimensions bomb, is a
 * different class of risk that no amount of sampling math here can close —
 * that is what [decodeGuarded] and the isolated sandbox behind it are for, and
 * why peer-controlled images should call that rather than this directly.)
 */
internal fun decodeSampledBitmap(bytes: ByteArray, maxDimension: Int): android.graphics.Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        // A malformed file can report non-positive/nonsensical bounds — bail
        // out instead of feeding it into a second decode pass.
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDimension && bounds.outHeight / (sample * 2) >= maxDimension) {
            sample *= 2
        }
        val real = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, real)
    } catch (_: OutOfMemoryError) {
        null
    }
}

/**
 * The decode every peer-controlled image should go through.
 *
 * Tries the isolated sandbox first — where a memory-safety bug in a native
 * parser wakes up in a process with no permissions, no files and no keys — and
 * falls back to [decodeSampledBitmap] in this process when the sandbox is
 * unavailable, refused by device policy, too slow, or has just died. Both paths
 * apply the same bound, so what the user sees is identical either way.
 *
 * A null from the sandbox is deliberately NOT re-tried in-process. The sandbox
 * returns null both for "this image is malformed" and for "I could not answer",
 * and re-decoding locally to tell them apart would hand the dangerous bytes to
 * the very parser this exists to route around. [MediaSandbox.decode] therefore
 * distinguishes them internally: it returns null for both, but only leaves the
 * fallback reachable by reporting unsupported/unavailable before it ever runs
 * a decode.
 */
internal suspend fun decodeGuarded(
    context: android.content.Context,
    bytes: ByteArray,
    maxDimension: Int
): android.graphics.Bitmap? {
    if (com.securemessenger.app.media.MediaSandbox.isSupported) {
        com.securemessenger.app.media.MediaSandbox
            .decode(context, bytes, maxDimension)
            ?.let { return it }
    }
    return withContext(Dispatchers.Default) { decodeSampledBitmap(bytes, maxDimension) }
}

/**
 * The one thing the media composables need from a conversation: bytes for a
 * piece of media, decrypted.
 *
 * They used to take the whole ConversationViewModel to reach a single method
 * on it, which meant MessageBubble, MediaContent, AlbumBubble and the
 * fullscreen viewer could not be rendered — or tested, or photographed —
 * without an open database behind them. Narrowing the dependency to what is
 * actually used costs one interface and buys back all four.
 */
internal fun interface MediaLoader {
    suspend fun load(media: MediaCodec.LocalMedia): ByteArray?
}

@Composable
internal fun MediaContent(
    media: MediaCodec.LocalMedia,
    loadMedia: MediaLoader,
    isSent: Boolean,
    onOpenImageViewer: (MediaCodec.LocalMedia) -> Unit
) {
    val mc = com.securemessenger.app.ui.theme.LocalMessengerColors.current
    when (media.mediaType) {
        MediaCodec.TYPE_IMAGE -> ImageContent(media, loadMedia, onOpenImageViewer)
        MediaCodec.TYPE_VIDEO -> VideoContent(media, loadMedia)
        MediaCodec.TYPE_AUDIO -> VoiceContent(media, loadMedia, isSent)
        MediaCodec.TYPE_FILE -> FileContent(media, loadMedia, Icons.Default.InsertDriveFile, isSent)
        else -> Text(media.fileName, color = if (isSent) mc.onSent else mc.onReceived)
    }
}

@Composable
private fun ImageContent(
    media: MediaCodec.LocalMedia,
    loadMedia: MediaLoader,
    onOpenImageViewer: (MediaCodec.LocalMedia) -> Unit
) {
    var bitmap by remember(media.ref) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var aspectRatio by remember(media.ref) { mutableStateOf(1f) }
    var failed by remember(media.ref) { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(media.ref) {
        val bytes = loadMedia.load(media)
        if (bytes == null) {
            failed = true
            return@LaunchedEffect
        }
        // These bytes came from someone else, and this bubble decodes them with
        // no tap from the user — the zero-click path. It goes through the
        // sandbox; decodeGuarded also keeps the work off the UI thread.
        val decoded = decodeGuarded(context, bytes, maxDimension = 1080)
        if (decoded != null) {
            aspectRatio = (decoded.width.toFloat() / decoded.height.toFloat()).coerceIn(0.5f, 2f)
            bitmap = decoded
        } else {
            failed = true
        }
    }

    Box(
        modifier = Modifier
            .widthIn(max = 240.dp)
            .heightIn(max = 320.dp)
            .aspectRatio(aspectRatio)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x14000000))
            .let { m -> if (bitmap != null) m.clickable { onOpenImageViewer(media) } else m },
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        when {
            bmp != null -> Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = media.fileName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            failed -> Icon(
                Icons.Default.BrokenImage,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.7f)
            )
            else -> ShimmerBox(modifier = Modifier.fillMaxSize(), shape = RoundedCornerShape(12.dp))
        }
    }
}

// ---------- video content ----------

@Composable
private fun VideoContent(media: MediaCodec.LocalMedia, loadMedia: MediaLoader) {
    var thumbnail by remember(media.ref) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var durationMs by remember(media.ref) { mutableStateOf(0L) }
    var bytesCache by remember(media.ref) { mutableStateOf<ByteArray?>(null) }
    var showPlayer by remember { mutableStateOf(false) }
    var failed by remember(media.ref) { mutableStateOf(false) }
    // Security gate: a video's bytes are only decrypted and handed to
    // MediaMetadataRetriever — which parses the container/codec, exactly the
    // class of surface historically targeted by zero-click exploits (e.g.
    // Stagefright) — once the user explicitly taps this specific message.
    // Nothing about a video is parsed automatically just because its bubble
    // scrolled into view.
    var revealed by remember(media.ref) { mutableStateOf(false) }
    var loading by remember(media.ref) { mutableStateOf(false) }

    LaunchedEffect(media.ref, revealed) {
        if (!revealed) return@LaunchedEffect
        loading = true
        val bytes = loadMedia.load(media)
        if (bytes == null) {
            failed = true
            loading = false
            return@LaunchedEffect
        }
        bytesCache = bytes
        withContext(Dispatchers.Default) {
            try {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(ByteArrayMediaDataSource(bytes))
                val frame = retriever.getFrameAtTime(1_000_000L) ?: retriever.getFrameAtTime(0L)
                val duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                retriever.release()
                withContext(Dispatchers.Main) {
                    thumbnail = frame?.asImageBitmap()
                    durationMs = duration
                    if (frame == null) failed = true
                    loading = false
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { failed = true; loading = false }
            }
        }
    }

    Box(
        modifier = Modifier
            .widthIn(max = 240.dp)
            .heightIn(max = 320.dp)
            .aspectRatio(if (thumbnail != null) thumbnail!!.width.toFloat() / thumbnail!!.height.toFloat() else 16f / 9f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x14000000))
            .clickable(enabled = !loading) {
                if (!revealed) revealed = true
                else if (bytesCache != null) showPlayer = true
            },
        contentAlignment = Alignment.Center
    ) {
        val bmp = thumbnail
        when {
            bmp != null -> Image(bitmap = bmp, contentDescription = media.fileName, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            failed -> Icon(Icons.Default.Movie, contentDescription = null, tint = Color.White.copy(alpha = 0.7f))
            loading -> ShimmerBox(modifier = Modifier.fillMaxSize(), shape = RoundedCornerShape(12.dp))
            !revealed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Movie,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(36.dp)
                )
                Spacer(Modifier.height(4.dp))
                Text("اضغط لتحميل الفيديو", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f))
                Text(
                    "${media.size / 1024} كيلوبايت",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
            else -> ShimmerBox(modifier = Modifier.fillMaxSize(), shape = RoundedCornerShape(12.dp))
        }
        // Play button + duration overlay — only once a thumbnail was actually loaded.
        if (bmp != null) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(0x66000000)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "تشغيل", tint = Color.White, modifier = Modifier.size(28.dp))
            }
            if (durationMs > 0) {
                Text(
                    formatElapsed(durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0x99000000))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
    }

    if (showPlayer) {
        val bytes = bytesCache
        if (bytes != null) {
            VideoPlayerDialog(bytes = bytes, onDismiss = { showPlayer = false })
        }
    }
}

@Composable
private fun VideoPlayerDialog(bytes: ByteArray, onDismiss: () -> Unit) {
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var prepared by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    LaunchedEffect(isPlaying, prepared) {
        val mp = mediaPlayer ?: return@LaunchedEffect
        if (!prepared) return@LaunchedEffect
        while (isPlaying) {
            val duration = mp.duration.coerceAtLeast(1)
            progress = (mp.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
            kotlinx.coroutines.delay(200)
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    android.view.SurfaceView(ctx).apply {
                        holder.addCallback(object : android.view.SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                                val mp = MediaPlayer().apply {
                                    setDataSource(ByteArrayMediaDataSource(bytes))
                                    setSurface(holder.surface)
                                    setOnPreparedListener {
                                        prepared = true
                                        start()
                                        isPlaying = true
                                    }
                                    setOnCompletionListener { isPlaying = false; progress = 0f }
                                    prepareAsync()
                                }
                                mediaPlayer = mp
                            }
                            override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {}
                            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {}
                        })
                    }
                }
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0x99000000))
                    .navigationBarsPadding()
                    .padding(Dims.s12)
            ) {
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.3f)
                )
                Spacer(Modifier.height(Dims.s8))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        val mp = mediaPlayer ?: return@IconButton
                        if (isPlaying) { mp.pause(); isPlaying = false } else { mp.start(); isPlaying = true }
                    }) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "إيقاف مؤقت" else "تشغيل",
                            tint = Color.White
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun FileContent(
    media: MediaCodec.LocalMedia,
    loadMedia: MediaLoader,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSent: Boolean
) {
    val mc = com.securemessenger.app.ui.theme.LocalMessengerColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .widthIn(min = 180.dp)
            .clickable(enabled = !opening) {
                opening = true
                scope.launch {
                    val bytes = loadMedia.load(media)
                    opening = false
                    if (bytes != null) openInExternalApp(context, bytes, media)
                }
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(0x33000000)),
            contentAlignment = Alignment.Center
        ) {
            if (opening) CircularProgressIndicator(modifier = Modifier.size(18.dp))
            else Icon(icon, contentDescription = null, tint = if (isSent) mc.onSent else mc.onReceived)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                media.fileName,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isSent) mc.onSent else mc.onReceived,
                maxLines = 1
            )
            Text(
                "${media.size / 1024} كيلوبايت",
                style = MaterialTheme.typography.labelSmall,
                color = if (isSent) mc.sentMeta else mc.receivedMeta
            )
        }
    }
}

@Composable
private fun VoiceContent(media: MediaCodec.LocalMedia, loadMedia: MediaLoader, isSent: Boolean) {
    val mc = com.securemessenger.app.ui.theme.LocalMessengerColors.current
    val scope = rememberCoroutineScope()
    var isPlaying by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var progress by remember { mutableStateOf(0f) }
    var speed by remember { mutableStateOf(1f) }

    DisposableEffect(media.ref) {
        onDispose {
            player?.release()
            player = null
        }
    }

    // Polls playback position while active instead of relying on a callback —
    // MediaPlayer has no progress-flow API, and 10x/sec is plenty smooth for
    // a scrubber bar this small.
    LaunchedEffect(isPlaying) {
        val mp = player ?: return@LaunchedEffect
        while (isPlaying) {
            val duration = mp.duration.coerceAtLeast(1)
            progress = (mp.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
            kotlinx.coroutines.delay(100)
        }
    }

    fun seekTo(fraction: Float) {
        player?.let { mp ->
            mp.seekTo((fraction.coerceIn(0f, 1f) * mp.duration).toInt())
            progress = fraction.coerceIn(0f, 1f)
        }
    }

    Row(
        modifier = Modifier.widthIn(min = 180.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            enabled = !isLoading,
            onClick = {
                val current = player
                if (current != null && isPlaying) {
                    current.pause()
                    isPlaying = false
                    return@IconButton
                }
                if (current != null) {
                    current.start()
                    isPlaying = true
                    return@IconButton
                }
                isLoading = true
                scope.launch {
                    val bytes = loadMedia.load(media)
                    isLoading = false
                    if (bytes == null) return@launch
                    val mp = MediaPlayer().apply {
                        // Reads straight from the decrypted bytes in memory —
                        // no plaintext temp file ever touches disk.
                        setDataSource(ByteArrayMediaDataSource(bytes))
                        setOnCompletionListener { isPlaying = false; progress = 0f }
                        prepare()
                        start()
                    }
                    player = mp
                    isPlaying = true
                }
            }
        ) {
            if (isLoading) CircularProgressIndicator(modifier = Modifier.size(18.dp))
            else Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "إيقاف مؤقت" else "تشغيل",
                tint = if (isSent) mc.onSent else mc.onReceived
            )
        }
        Spacer(Modifier.width(4.dp))
        if (media.waveform.isNotEmpty()) {
            WaveformScrubber(
                waveform = media.waveform,
                progress = progress,
                tint = if (isSent) mc.onSent else mc.onReceived,
                enabled = player != null,
                onSeek = ::seekTo,
                modifier = Modifier.weight(1f).height(28.dp)
            )
        } else {
            Text(
                "رسالة صوتية",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isSent) mc.onSent else mc.onReceived,
                modifier = Modifier.weight(1f)
            )
        }
        // Cycles 1x → 1.5x → 2x → 1x — only meaningful once something is
        // actually loaded to apply the speed to.
        if (player != null) {
            Text(
                "${if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()}x",
                style = MaterialTheme.typography.labelMedium,
                color = if (isSent) mc.onSent else mc.onReceived,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        val next = when (speed) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }
                        speed = next
                        player?.let { mp ->
                            try {
                                mp.playbackParams = mp.playbackParams.setSpeed(next)
                            } catch (_: Exception) {
                            }
                        }
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

/** A row of amplitude bars, filled up to [progress]; drag or tap anywhere to seek. */
@Composable
private fun WaveformScrubber(
    waveform: List<Float>,
    progress: Float,
    tint: Color,
    enabled: Boolean,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onTap = { offset -> onSeek(offset.x / size.width.toFloat()) })
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        onSeek(change.position.x / size.width.toFloat())
                    }
                )
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        waveform.forEachIndexed { index, amplitude ->
            val played = index.toFloat() / waveform.size < progress
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(0.25f + amplitude.coerceIn(0f, 1f) * 0.75f)
                    .clip(RoundedCornerShape(1.dp))
                    .background(tint.copy(alpha = if (played) 1f else 0.4f))
            )
        }
    }
}

private fun openInExternalApp(context: android.content.Context, bytes: ByteArray, media: MediaCodec.LocalMedia) {
    // media.fileName is already sanitized to a bare, safe basename by
    // MediaCodec.tryParseWirePayload (no path separators / traversal), but
    // it's peer-chosen and NOT unique — two different messages can easily
    // share a filename (e.g. both "invoice.pdf"). A random per-invocation
    // subdirectory keeps each decrypted copy from overwriting another one
    // that might still be mid-read by whatever app the previous share sent
    // it to, while still handing this app's own external viewer the clean,
    // original filename (still covered by the existing decrypted_media/
    // FileProvider path — it grants the whole subtree, not just direct
    // children).
    val dir = File(context.cacheDir, "decrypted_media/${java.util.UUID.randomUUID()}").apply { mkdirs() }
    val file = File(dir, media.fileName)
    file.writeBytes(bytes)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    // The peer's claimed mimeType is NEVER trusted for the Intent that opens
    // this file in another app — a malicious peer could otherwise label
    // arbitrary content (e.g. an installable package) with a misleading MIME
    // string to get a different, more dangerous handler to accept it. Derive
    // the type ourselves from the sanitized file extension instead.
    val extension = android.webkit.MimeTypeMap.getFileExtensionFromUrl(file.name)
        ?.lowercase(java.util.Locale.ROOT)
    val resolvedType = extension
        ?.let { android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
        ?: "application/octet-stream"
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, resolvedType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        // No app installed that can open this file type — silently ignore.
    }
}

internal fun formatElapsed(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

// ---------- full-screen image viewer ----------

@Composable
internal fun FullScreenImageViewer(
    media: MediaCodec.LocalMedia,
    loadMedia: MediaLoader,
    onDismiss: () -> Unit
) {
    var bitmap by remember(media.ref) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(media.ref) {
        val bytes = loadMedia.load(media)
        // Sandboxed and bounded, same as every other image surface here. The
        // bound alone stops a decompression bomb — a small file declaring an
        // enormous pixel size, which would otherwise force a multi-gigabyte
        // allocation and an uncaught OutOfMemoryError. The sandbox covers the
        // other half: a bug in the parser itself, which no sampling maths can
        // reach. 2160px looks full-quality on any phone screen.
        bitmap = bytes?.let { decodeGuarded(context, it, maxDimension = 2160)?.asImageBitmap() }
    }
    val zoomState = rememberZoomState(maxScale = 5f)
    var dragOffsetY by remember { mutableStateOf(0f) }
    val scrimAlpha = (1f - (kotlin.math.abs(dragOffsetY) / 900f)).coerceIn(0.25f, 1f)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrimAlpha))
        ) {
            val bmp = bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = media.fileName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationY = dragOffsetY }
                        .zoomable(zoomState)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragEnd = {
                                    if (kotlin.math.abs(dragOffsetY) > 260f) onDismiss() else dragOffsetY = 0f
                                },
                                onDragCancel = { dragOffsetY = 0f },
                                onVerticalDrag = { change, dragAmount ->
                                    // Only drag-to-dismiss at rest zoom — otherwise a
                                    // zoomed-in pan would fight with this gesture.
                                    if (zoomState.scale <= 1f) {
                                        change.consume()
                                        dragOffsetY += dragAmount
                                    }
                                }
                            )
                        }
                )
            } else {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(Dims.s12)
            ) {
                Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = Color.White)
            }
        }
    }
}
