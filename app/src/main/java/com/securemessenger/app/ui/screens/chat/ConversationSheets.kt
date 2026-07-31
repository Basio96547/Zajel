package com.securemessenger.app.ui.screens.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.securemessenger.app.ui.gesture.pressScale
import com.securemessenger.app.ui.theme.Dims
import com.securemessenger.app.ui.viewmodel.MessageUiModel
import kotlinx.coroutines.launch

// ---------- attachment picker sheet ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachmentSheet(
    onDismiss: () -> Unit,
    onCameraClick: () -> Unit,
    onGalleryClick: () -> Unit,
    onAlbumClick: () -> Unit,
    onFileClick: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dims.s16, vertical = Dims.s16),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            AttachmentOption(Icons.Default.CameraAlt, "كاميرا", onCameraClick)
            AttachmentOption(Icons.Default.Image, "المعرض", onGalleryClick)
            AttachmentOption(Icons.Default.PhotoLibrary, "ألبوم", onAlbumClick)
            AttachmentOption(Icons.Default.InsertDriveFile, "ملف", onFileClick)
        }
        Spacer(Modifier.height(Dims.s16))
    }
}

@Composable
private fun AttachmentOption(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.pressScale().clickable(onClick = onClick).padding(Dims.s8)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(Dims.s6))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

// ---------- long-press action sheet ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageActionSheet(
    target: MessageUiModel,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDeleteForEveryone: () -> Unit,
    onDeleteForMe: () -> Unit,
    onSelect: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    val isMine = target.direction == 1
    val isText = target.media == null && !target.isDeleted
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // Quick reaction row — biased toward the same side as the bubble
        // itself (sent bubbles sit on the right, so the row leans right too)
        // instead of a generic evenly-spaced strip, so the menu reads as
        // belonging to that specific message rather than the screen at large.
        val reactionScope = rememberCoroutineScope()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dims.s16, vertical = Dims.s8),
            horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
        ) {
            listOf("👍", "❤️", "😂", "😮", "😢", "🙏").forEach { emoji ->
                val selected = target.reactionMine == emoji
                val scale = remember { Animatable(1f) }
                Text(
                    text = emoji,
                    fontSize = 30.sp,
                    modifier = Modifier
                        .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
                        .clip(CircleShape)
                        .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
                        .clickable {
                            // A little spring "explosion" on the emoji itself —
                            // overshoots past full size then settles back.
                            reactionScope.launch {
                                scale.animateTo(1.5f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh))
                                scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
                            }
                            onReact(if (selected) "" else emoji)
                        }
                        .padding(6.dp)
                )
            }
        }
        Divider()
        ActionRow(Icons.Default.Reply, "رد", onReply)
        if (isText) ActionRow(Icons.Default.ContentCopy, "نسخ", onCopy)
        if (isMine && isText) ActionRow(Icons.Default.Edit, "تعديل", onEdit)
        ActionRow(Icons.Default.CheckCircle, "تحديد", onSelect)
        if (isMine && !target.isDeleted) ActionRow(Icons.Default.DeleteForever, "حذف لدى الجميع", onDeleteForEveryone, danger = true)
        ActionRow(Icons.Default.Delete, "حذف لديّ فقط", onDeleteForMe)
        Spacer(Modifier.height(Dims.s16))
    }
}

@Composable
private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, danger: Boolean = false) {
    val tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Dims.s16, vertical = Dims.s12),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(Dims.s16))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}
