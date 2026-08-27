package com.securemessenger.app.ui.screens.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.securemessenger.core.crypto.MediaCodec
import com.securemessenger.app.ui.gesture.swipeToTrigger
import com.securemessenger.app.ui.theme.Dims
import com.securemessenger.app.ui.theme.LocalMessengerColors
import com.securemessenger.app.ui.viewmodel.MessageUiModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------- message bubble ----------

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun MessageBubble(
    message: MessageUiModel,
    isLastInGroup: Boolean,
    loadMedia: MediaLoader,
    onLongPress: () -> Unit,
    onSwipeReply: () -> Unit,
    onOpenImageViewer: (MediaCodec.LocalMedia) -> Unit,
    seenKeys: MutableSet<String>,
    isHighlighted: Boolean = false,
    onJumpToReplyTarget: (String) -> Unit = {},
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {}
) {
    val mc = LocalMessengerColors.current
    val isSent = message.direction == 1
    val time = remember(message.timestamp) {
        SimpleDateFormat("HH:mm", chatTimeLocale).format(Date(message.timestamp))
    }
    // The little corner "tail" only draws on the last bubble of a consecutive
    // run from the same sender — every other bubble in the group is fully
    // rounded, which is what reads as "one grouped block" instead of a stack
    // of identical speech-bubble tails.
    val tailCorner = if (isLastInGroup) Dims.bubbleTail else Dims.bubbleRadius
    val shape = if (isSent)
        RoundedCornerShape(Dims.bubbleRadius, Dims.bubbleRadius, tailCorner, Dims.bubbleRadius)
    else
        RoundedCornerShape(Dims.bubbleRadius, Dims.bubbleRadius, Dims.bubbleRadius, tailCorner)
    var swipeProgress by remember { mutableStateOf(0f) }

    // Plays a spring pop-in once, the first time this exact message ever
    // enters composition — scrolling it back into view later (after the lazy
    // list disposed it) is a no-op since the key is already marked seen.
    val bubbleKey = remember(message.id, message.timestamp) { message.clientId ?: "${message.id}-${message.timestamp}" }
    val alreadySeen = remember(bubbleKey) { bubbleKey in seenKeys }
    LaunchedEffect(bubbleKey) { seenKeys.add(bubbleKey) }
    val visibleState = remember(bubbleKey) {
        MutableTransitionState(alreadySeen).apply { targetState = true }
    }
    val highlightColor by animateColorAsState(
        targetValue = if (isHighlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else Color.Transparent,
        animationSpec = tween(if (isHighlighted) 150 else 500),
        label = "bubbleHighlight"
    )

    AnimatedVisibility(
        visibleState = visibleState,
        enter = scaleIn(
            initialScale = 0.85f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
        ) + fadeIn()
    ) {
    Box(modifier = Modifier.fillMaxWidth().background(highlightColor, RoundedCornerShape(Dims.bubbleRadius))) {
        // Reply icon revealed on whichever side the bubble is being dragged
        // toward — a quick drag-and-release replies without opening the menu.
        if (swipeProgress != 0f) {
            Icon(
                imageVector = Icons.Default.Reply,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(if (swipeProgress > 0) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = Dims.s16)
                    .graphicsLayer {
                        alpha = kotlin.math.abs(swipeProgress)
                        val s = 0.6f + 0.4f * kotlin.math.abs(swipeProgress)
                        scaleX = s
                        scaleY = s
                    }
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 1.dp)
                .swipeToTrigger(
                    enabled = !message.isDeleted && !isSelectionMode,
                    onProgress = { swipeProgress = it },
                    onTriggered = onSwipeReply
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (isSent) Arrangement.Absolute.Right else Arrangement.Absolute.Left
        ) {
            if (isSelectionMode) {
                SelectionCheckmark(isSelected, onToggleSelect)
                Spacer(Modifier.width(Dims.s8))
            }
            Column(horizontalAlignment = if (isSent) Alignment.End else Alignment.Start) {
            Column(
                modifier = Modifier
                    .widthIn(max = Dims.bubbleMaxWidth)
                    .clip(shape)
                    .background(if (isSent) mc.sentBubble else mc.receivedBubble)
                    .combinedClickable(
                        onClick = { if (isSelectionMode) onToggleSelect() },
                        onLongClick = { if (!isSelectionMode) onLongPress() }
                    )
                    .padding(horizontal = Dims.s12, vertical = Dims.s8)
            ) {
                // Quoted-message bar for replies — tapping it jumps to (and
                // briefly flashes) the original message.
                if (message.replySnippet != null) {
                    Row(
                        modifier = Modifier
                            .padding(bottom = 6.dp)
                            .let { m ->
                                message.replyToClientId?.let { target ->
                                    m.clickable { onJumpToReplyTarget(target) }
                                } ?: m
                            }
                    ) {
                        Box(
                            Modifier
                                .width(3.dp)
                                .heightIn(min = 24.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background((if (isSent) mc.onSent else MaterialTheme.colorScheme.primary).copy(alpha = 0.6f))
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            message.replySnippet,
                            style = MaterialTheme.typography.labelMedium,
                            color = (if (isSent) mc.onSent else mc.onReceived).copy(alpha = 0.85f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                when {
                    message.isDeleted -> Text(
                        "🚫 حُذفت هذه الرسالة",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isSent) mc.sentMeta else mc.receivedMeta,
                        fontStyle = FontStyle.Italic
                    )
                    message.isExpired -> Text(
                        "انتهت صلاحية هذه الرسالة",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    message.media != null -> Column {
                        MediaContent(message.media, loadMedia, isSent, onOpenImageViewer)
                        val caption = message.media.caption
                        if (!caption.isNullOrBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = caption,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isSent) mc.onSent else mc.onReceived
                            )
                        }
                    }
                    else -> Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isSent) mc.onSent else mc.onReceived
                    )
                }
                Row(
                    modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (message.edited && !message.isDeleted) "$time · عُدّلت" else time,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSent) mc.sentMeta else mc.receivedMeta
                    )
                    if (isSent && !message.isDeleted) {
                        Spacer(Modifier.width(4.dp))
                        // Crossfades between the three real delivery states
                        // instead of an instant swap — sending → sent → read
                        // each becomes visible as it actually happens.
                        val tickState = when {
                            message.isRead -> "read"
                            message.isPending -> "pending"
                            else -> "sent"
                        }
                        Crossfade(targetState = tickState, label = "readTick") { state ->
                            when (state) {
                                "pending" -> Icon(
                                    imageVector = Icons.Default.Schedule,
                                    contentDescription = "قيد الإرسال",
                                    modifier = Modifier.size(13.dp),
                                    tint = mc.sentMeta
                                )
                                "read" -> Icon(
                                    imageVector = Icons.Default.DoneAll,
                                    contentDescription = "مقروءة",
                                    modifier = Modifier.size(15.dp),
                                    tint = mc.readTick
                                )
                                else -> Icon(
                                    imageVector = Icons.Default.Done,
                                    contentDescription = "مُرسَلة",
                                    modifier = Modifier.size(15.dp),
                                    tint = mc.sentMeta
                                )
                            }
                        }
                    }
                }
            }

            // Reaction chips just below the bubble.
            val reactionGroups = listOfNotNull(message.reactionTheirs, message.reactionMine).groupingBy { it }.eachCount()
            if (reactionGroups.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    reactionGroups.forEach { (emoji, count) ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(mc.receivedBubble)
                                .border(0.5.dp, mc.headerBorder, RoundedCornerShape(10.dp))
                                .padding(horizontal = 7.dp, vertical = 1.dp)
                        ) {
                            Text("$emoji $count", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        }
    }
    }
}

@Composable
private fun SelectionCheckmark(isSelected: Boolean, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color(0x33808080))
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}

// ---------- moved out ----------
// (AlbumBubble, AlbumThumbnail: AlbumBubble.kt)
// (EncryptedBanner, DateSeparator: ChatCommon.kt)
