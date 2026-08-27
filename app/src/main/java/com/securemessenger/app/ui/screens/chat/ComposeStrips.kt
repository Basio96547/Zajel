package com.securemessenger.app.ui.screens.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.securemessenger.core.crypto.MediaCodec
import com.securemessenger.app.ui.glassCard
import com.securemessenger.app.ui.theme.Dims
import com.securemessenger.app.ui.theme.LocalMessengerColors

// ---------- reply/edit compose strip ----------
// Extracted from MessageInput.kt — see the pointer comment in ConversationScreen.kt.

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
