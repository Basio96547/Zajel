package com.securemessenger.app.ui.screens.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.securemessenger.core.crypto.MediaCodec
import com.securemessenger.app.ui.theme.Dims
import com.securemessenger.app.ui.theme.LocalMessengerColors
import com.securemessenger.app.ui.viewmodel.ConversationViewModel
import com.securemessenger.app.ui.viewmodel.MessageUiModel

// ---------- mosaic photo album ----------
// Extracted from MessageBubble.kt — see the pointer comment there.

@Composable
internal fun AlbumBubble(
    images: List<MessageUiModel>,
    viewModel: ConversationViewModel,
    onOpenImageViewer: (MediaCodec.LocalMedia) -> Unit
) {
    val mc = LocalMessengerColors.current
    val isSent = images.first().direction == 1
    val shape = RoundedCornerShape(Dims.bubbleRadius)
    val columns = if (images.size <= 4) 2 else 3

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = if (isSent) Arrangement.Absolute.Right else Arrangement.Absolute.Left
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = Dims.bubbleMaxWidth)
                .clip(shape)
                .background(if (isSent) mc.sentBubble else mc.receivedBubble)
                .padding(4.dp)
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier
                    .width(240.dp)
                    .heightIn(max = 320.dp),
                userScrollEnabled = false,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(images, key = { it.id ?: it.timestamp }) { msg ->
                    msg.media?.let { media ->
                        AlbumThumbnail(
                            media = media,
                            viewModel = viewModel,
                            onClick = { onOpenImageViewer(media) }
                        )
                    }
                }
            }
            Text(
                "${images.size} صور",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, start = 4.dp)
            )
        }
    }
}

@Composable
private fun AlbumThumbnail(media: MediaCodec.LocalMedia, viewModel: ConversationViewModel, onClick: () -> Unit) {
    var bitmap by remember(media.ref) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(media.ref) {
        val bytes = viewModel.loadMediaBytes(media)
        // Album thumbnails render as soon as the bubble appears, with no tap —
        // another zero-click decode of someone else's bytes, so it goes through
        // the sandbox like the single-image bubble does.
        bitmap = bytes?.let { decodeGuarded(context, it, maxDimension = 400)?.asImageBitmap() }
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x14000000))
            .clickable(enabled = bitmap != null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(bitmap = bmp, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            ShimmerBox(modifier = Modifier.fillMaxSize(), shape = RoundedCornerShape(6.dp))
        }
    }
}
