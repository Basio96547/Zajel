package com.securemessenger.app.ui.screens.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.securemessenger.app.ui.glassCard
import com.securemessenger.app.ui.theme.LocalMessengerColors
import com.securemessenger.app.ui.theme.MessengerTheme
import com.securemessenger.app.ui.theme.SemanticColors
import com.securemessenger.app.ui.viewmodel.ContactUiModel

// ---------- chat list row ----------
// Extracted from ChatListScreen.kt — see the pointer comment there.

private fun formatChatTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
    val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    val pattern = if (sameDay) "HH:mm" else "dd/MM"
    return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(java.util.Date(timestamp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatListItem(
    contact: ContactUiModel,
    onClick: () -> Unit,
    onTogglePin: () -> Unit = {}
) {
    val mc = LocalMessengerColors.current
    var showMenu by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glassCard(radius = 18.dp)
                .combinedClickable(onClick = onClick, onLongClick = { showMenu = true })
                .padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
        Avatar(
            name = contact.displayName,
            size = 48.dp,
            id = contact.id,
            avatarBytes = contact.avatarBytes
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = contact.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = mc.glassOnCard,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (contact.isVerified) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.Verified,
                        contentDescription = "متحقق",
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                if (contact.pinnedAt != null) {
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = "مثبّتة",
                        modifier = Modifier.size(13.dp),
                        tint = mc.glassOnCard.copy(alpha = 0.55f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    text = formatChatTime(contact.lastTimestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = mc.glassOnCard.copy(alpha = 0.55f)
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    contact.lastIsSelfDestruct -> {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = SemanticColors.orange
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                    }
                    contact.lastIsMine && contact.lastMessage.isNotBlank() -> {
                        Icon(
                            imageVector = if (contact.lastIsRead) Icons.Default.DoneAll else Icons.Default.Done,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                }
                Text(
                    text = if (contact.lastIsSelfDestruct) "رسالة ذاتية التدمير"
                    else contact.lastMessage.ifBlank { "اضغط لبدء المحادثة" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = mc.glassOnCard.copy(alpha = 0.65f),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (contact.unreadCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minWidth = 20.dp)
                            .height(20.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (contact.unreadCount > 99) "99+" else contact.unreadCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
        }

        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text(if (contact.pinnedAt != null) "إلغاء تثبيت المحادثة" else "تثبيت المحادثة") },
                leadingIcon = { Icon(imageVector = Icons.Default.PushPin, contentDescription = null) },
                onClick = {
                    showMenu = false
                    onTogglePin()
                }
            )
        }
    }
}

@Preview(name = "Chat list item — dark", showBackground = true)
@Composable
private fun ChatListItemDarkPreview() {
    MessengerTheme(darkTheme = true) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChatListItem(
                contact = ContactUiModel(
                    id = "1", displayName = "سارة", isVerified = true,
                    lastMessage = "وش رأيك بالتصميم الجديد؟", lastTimestamp = 0L, unreadCount = 2
                ),
                onClick = {}
            )
            ChatListItem(
                contact = ContactUiModel(
                    id = "2", displayName = "أحمد", isVerified = false,
                    lastMessage = "تمام، شكراً لك", lastTimestamp = 0L, unreadCount = 0,
                    lastIsMine = true, lastIsRead = true
                ),
                onClick = {}
            )
        }
    }
}

@Preview(name = "Chat list item — light", showBackground = true)
@Composable
private fun ChatListItemLightPreview() {
    MessengerTheme(darkTheme = false) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChatListItem(
                contact = ContactUiModel(
                    id = "1", displayName = "سارة", isVerified = true,
                    lastMessage = "وش رأيك بالتصميم الجديد؟", lastTimestamp = 0L, unreadCount = 2
                ),
                onClick = {}
            )
        }
    }
}
