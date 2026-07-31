package com.securemessenger.app.ui.screens.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.securemessenger.core.crypto.MediaCodec
import com.securemessenger.app.media.VoiceRecorder
import com.securemessenger.app.ui.gesture.pressScale
import com.securemessenger.app.ui.glassCard
import com.securemessenger.app.ui.theme.Dims
import com.securemessenger.app.ui.theme.LocalMessengerColors
import kotlinx.coroutines.launch

// ---------- input bar ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSendClick: () -> Unit,
    onAttachClick: () -> Unit,
    onVoiceMessageReady: (VoiceRecorder.RecordingResult) -> Unit,
    onVoiceError: (String) -> Unit,
    sending: Boolean,
    // True while an attachment is staged and waiting for an (optional)
    // caption — the send button stays active even with an empty text field.
    forceSendButton: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mc = LocalMessengerColors.current
    var isRecording by remember { mutableStateOf(false) }
    var cancelProgress by remember { mutableStateOf(0f) }
    var elapsedMs by remember { mutableStateOf(0L) }
    var showEmojiPanel by remember { mutableStateOf(false) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) onVoiceError("يحتاج التسجيل الصوتي إذن الوصول إلى الميكروفون")
    }

    // Live "0:03" readout while actively recording.
    LaunchedEffect(isRecording) {
        if (!isRecording) {
            elapsedMs = 0L
            return@LaunchedEffect
        }
        val start = System.currentTimeMillis()
        while (isRecording) {
            elapsedMs = System.currentTimeMillis() - start
            kotlinx.coroutines.delay(150)
        }
    }

    Column(modifier = Modifier.navigationBarsPadding().padding(horizontal = Dims.s8, vertical = Dims.s6)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glassCard(radius = 26.dp, strong = true)
                .padding(horizontal = Dims.s8, vertical = Dims.s6),
            verticalAlignment = Alignment.Bottom
        ) {
            if (isRecording) {
                // The whole text field morphs into a live recording readout —
                // exactly what's actually happening, not a disabled field.
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .glassCard(radius = 22.dp)
                        .padding(horizontal = Dims.s16),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error)
                    )
                    Spacer(Modifier.width(Dims.s8))
                    Text(formatElapsed(elapsedMs), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (cancelProgress > 0.7f) "أفلت للإلغاء" else "اسحب للإلغاء",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (cancelProgress > 0.7f) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                IconButton(onClick = onAttachClick) {
                    Icon(Icons.Default.AttachFile, contentDescription = "إرفاق ملف")
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("رسالة…") },
                    maxLines = 6,
                    shape = RoundedCornerShape(22.dp),
                    // Autocorrect is itself a form of personalized learning —
                    // the keyboard builds suggestion history from what's typed
                    // here. Compose Foundation in this BOM doesn't yet expose
                    // the raw EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING bit
                    // (that needs a BasicTextField platformImeOptions hook
                    // added in a later Compose release), so this is the
                    // strongest mitigation available without dropping to a
                    // classic AndroidView EditText just for this one field.
                    keyboardOptions = KeyboardOptions(
                        autoCorrect = false
                    ),
                    leadingIcon = {
                        IconButton(onClick = { showEmojiPanel = !showEmojiPanel }) {
                            Icon(
                                if (showEmojiPanel) Icons.Default.Keyboard else Icons.Default.EmojiEmotions,
                                contentDescription = "لوحة الإيموجي"
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    )
                )
            }
            Spacer(Modifier.width(Dims.s8))
            // Scale+fade between mic and send instead of an instant swap —
            // the button visibly morphs the moment typing starts/stops. Stays
            // on the "mic" branch throughout a recording (value is blank the
            // whole time), so the gesture below never gets torn down mid-press.
            AnimatedContent(
                targetState = value.isBlank() && !forceSendButton,
                transitionSpec = {
                    (scaleIn(initialScale = 0.7f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.7f) + fadeOut())
                },
                label = "micOrSend"
            ) { isBlank ->
                if (isBlank) {
                    FilledIconButton(
                        onClick = {},
                        modifier = Modifier
                            .size(48.dp)
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitPointerEvent().changes.firstOrNull() ?: return@awaitEachGesture
                                    val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (!granted) {
                                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        return@awaitEachGesture
                                    }
                                    try {
                                        VoiceRecorder.start()
                                    } catch (e: Exception) {
                                        onVoiceError(e.message ?: "تعذّر بدء التسجيل")
                                        return@awaitEachGesture
                                    }
                                    isRecording = true
                                    cancelProgress = 0f
                                    var canceled = false
                                    // Track this one finger until it lifts — a
                                    // drag past the threshold (any direction)
                                    // marks the recording for cancellation.
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                        if (change == null || !change.pressed) break
                                        change.consume()
                                        val dx = change.position.x - down.position.x
                                        val dy = change.position.y - down.position.y
                                        val distance = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
                                        cancelProgress = (distance / 160f).coerceIn(0f, 1f)
                                        if (distance > 160f) canceled = true
                                    }
                                    isRecording = false
                                    cancelProgress = 0f
                                    if (canceled) {
                                        VoiceRecorder.cancel()
                                    } else {
                                        scope.launch {
                                            try {
                                                val result = VoiceRecorder.stopAndEncode()
                                                // 44 bytes = just the WAV header, no actual audio captured.
                                                if (result.wav.size <= 44) {
                                                    onVoiceError("لم يُسجَّل أي صوت — حاول مجدداً")
                                                } else {
                                                    onVoiceMessageReady(result)
                                                }
                                            } catch (e: Exception) {
                                                onVoiceError(e.message ?: "فشل تسجيل الرسالة الصوتية")
                                            }
                                        }
                                    }
                                }
                            },
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (isRecording) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = "رسالة صوتية — اضغط مطوّلاً للتسجيل",
                            tint = Color.White
                        )
                    }
                } else {
                    FilledIconButton(
                        onClick = onSendClick,
                        enabled = (value.isNotBlank() || forceSendButton) && !sending,
                        modifier = Modifier.size(48.dp).pressScale(),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "إرسال", tint = Color.White)
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = showEmojiPanel,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            EmojiPanel(onEmojiClick = { emoji -> onValueChange(value + emoji) })
        }
    }
}

/** A grid of common emojis — an in-app alternative to the system IME's picker. */
@Composable
private fun EmojiPanel(onEmojiClick: (String) -> Unit) {
    val emojis = listOf(
        "😀", "😂", "😍", "😊", "😉", "😘", "😢", "😭", "😡", "😱",
        "👍", "👎", "❤️", "🔥", "🎉", "🙏", "👏", "💪", "😴", "🤔",
        "😎", "🥳", "😅", "🙄", "😇", "🤗", "😳", "🤣", "💔", "✨"
    )
    LazyVerticalGrid(
        columns = GridCells.Fixed(8),
        modifier = Modifier.fillMaxWidth().height(180.dp).padding(Dims.s8)
    ) {
        items(emojis) { emoji ->
            Text(
                emoji,
                fontSize = 24.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEmojiClick(emoji) }
                    .padding(vertical = 8.dp)
            )
        }
    }
}

// ---------- reply/edit compose strip ----------

@Composable
internal fun ComposeContextStrip(isEdit: Boolean, snippet: String, onCancel: () -> Unit) {
    val mc = LocalMessengerColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dims.s8)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glassCard(strong = true)
                .padding(horizontal = Dims.s12, vertical = Dims.s8),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(Modifier.width(Dims.s8))
            Column(Modifier.weight(1f)) {
                Text(
                    if (isEdit) "تعديل الرسالة" else "الرد على",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    snippet,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = "إلغاء")
            }
        }
    }
}

/** Shown above the input while an attachment is staged — the text field below becomes its caption. */
@Composable
internal fun MediaCaptionStrip(media: PickedFile, onCancel: () -> Unit) {
    val thumbnail = remember(media) {
        if (media.mediaType == MediaCodec.TYPE_IMAGE) {
            decodeSampledBitmap(media.bytes, maxDimension = 120)?.asImageBitmap()
        } else null
    }
    val mc = LocalMessengerColors.current
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = Dims.s8)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glassCard(strong = true)
                .padding(horizontal = Dims.s12, vertical = Dims.s8),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x22000000)),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnail != null) {
                    Image(bitmap = thumbnail, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Icon(
                        when (media.mediaType) {
                            MediaCodec.TYPE_VIDEO -> Icons.Default.Movie
                            MediaCodec.TYPE_AUDIO -> Icons.Default.Mic
                            else -> Icons.Default.InsertDriveFile
                        },
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.width(Dims.s8))
            Column(Modifier.weight(1f)) {
                Text("إضافة تعليق (اختياري)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    media.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = "إلغاء")
            }
        }
    }
}
