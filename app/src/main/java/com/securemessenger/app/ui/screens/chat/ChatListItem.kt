package com.securemessenger.app.ui.screens.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.securemessenger.app.ui.liquid.LiquidTheme
import com.securemessenger.app.ui.liquid.LocalLiquid
import com.securemessenger.app.ui.liquid.liquidGlow
import com.securemessenger.app.ui.liquid.liquidSurface
import com.securemessenger.app.ui.liquid.rememberPressScale
import com.securemessenger.app.ui.theme.MessengerTheme
import com.securemessenger.app.ui.theme.SemanticColors
import com.securemessenger.app.ui.viewmodel.ContactUiModel
import kotlinx.coroutines.delay

// ---------- chat list row ----------

/**
 * Relative rather than absolute wherever a person would say it that way:
 * "أمس" and a weekday name carry more at a glance than a date does, and the
 * exact date is still there once a conversation is old enough for it to be
 * the useful answer.
 */
/**
 * Arabic day names, Latin digits.
 *
 * The default locale gives Arabic-Indic digits (٢٠:٠٣) while every count in
 * this screen — unread badges, the header subtitle — is rendered by Kotlin's
 * toString in Latin ones. The screenshot made the mismatch obvious: two
 * numbering systems in the same row. Asking for `nu-latn` keeps "أمس" and
 * "الاثنين" in Arabic while making the digits agree with the badges.
 */
private val timeLocale: java.util.Locale = java.util.Locale.Builder()
    .setLocale(java.util.Locale.getDefault())
    .setUnicodeLocaleKeyword("nu", "latn")
    .build()

private fun formatChatTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
    val sameYear = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR)
    val dayDelta = if (sameYear) {
        now.get(java.util.Calendar.DAY_OF_YEAR) - then.get(java.util.Calendar.DAY_OF_YEAR)
    } else {
        Int.MAX_VALUE
    }
    val pattern = when {
        dayDelta == 0 -> "HH:mm"
        dayDelta == 1 -> return "أمس"
        dayDelta in 2..6 -> "EEEE"
        else -> "dd/MM"
    }
    return java.text.SimpleDateFormat(pattern, timeLocale).format(java.util.Date(timestamp))
}

/**
 * One conversation, as a pane of glass.
 *
 * [entranceDelayMillis] staggers the first paint of a list; pass a negative
 * value for a row that has already been on screen, so scrolling back up does
 * not replay the animation. The screen owns that decision because only it
 * knows which ids have been seen — see ChatListScreen's `entered` set.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatListItem(
    contact: ContactUiModel,
    onClick: () -> Unit,
    onTogglePin: () -> Unit = {},
    entranceDelayMillis: Int = -1,
) {
    val palette = LocalLiquid.current
    val haptics = LocalHapticFeedback.current
    val primary = MaterialTheme.colorScheme.primary
    var showMenu by remember { mutableStateOf(false) }

    val interaction = remember { MutableInteractionSource() }
    val pressScale by rememberPressScale(interaction)

    val entrance = remember { Animatable(if (entranceDelayMillis >= 0) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (entranceDelayMillis >= 0) {
            delay(entranceDelayMillis.toLong())
            entrance.animateTo(1f, tween(durationMillis = 420, easing = FastOutSlowInEasing))
        }
    }

    val unread = contact.unreadCount > 0
    val pinned = contact.pinnedAt != null

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    val enter = entrance.value
                    alpha = enter
                    translationY = (1f - enter) * 26.dp.toPx()
                    scaleX = pressScale * (0.96f + 0.04f * enter)
                    scaleY = pressScale * (0.96f + 0.04f * enter)
                }
                // Unread and pinned rows sit slightly higher off the page —
                // depth carrying meaning, not just decoration.
                .liquidSurface(
                    shape = RoundedCornerShape(22.dp),
                    raised = unread || pinned,
                    elevation = if (unread) 18.dp else 10.dp
                )
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        showMenu = true
                    }
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AvatarWithRing(contact = contact, unread = unread)

            Spacer(modifier = Modifier.width(13.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Name and badge share one weighted cell. They used to sit
                    // beside a second `Modifier.weight(1f)` spacer, and two
                    // weights split the free space evenly — so a name was
                    // clipped at roughly half the row no matter how much room
                    // was actually free. "مجموعة العمل" came back from the
                    // device as "مجموعة الع…" with a third of the row empty.
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = contact.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium,
                            color = palette.onSurface,
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
                                tint = primary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    if (pinned) {
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = "مثبّتة",
                            modifier = Modifier.size(13.dp),
                            tint = palette.onSurface.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                    }
                    Text(
                        text = formatChatTime(contact.lastTimestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (unread) primary else palette.muted
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

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
                                tint = primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                    }
                    Text(
                        text = if (contact.lastIsSelfDestruct) "رسالة ذاتية التدمير"
                        else contact.lastMessage.ifBlank { "اضغط لبدء المحادثة" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (unread) palette.onSurface.copy(alpha = 0.85f) else palette.muted,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (unread) {
                        Spacer(modifier = Modifier.width(8.dp))
                        UnreadPill(count = contact.unreadCount)
                    }
                }
            }
        }

        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text(if (pinned) "إلغاء تثبيت المحادثة" else "تثبيت المحادثة") },
                leadingIcon = { Icon(imageVector = Icons.Default.PushPin, contentDescription = null) },
                onClick = {
                    showMenu = false
                    onTogglePin()
                }
            )
        }
    }
}

/** The avatar, wearing a glass ring that lights up when the conversation is waiting on you. */
@Composable
private fun AvatarWithRing(contact: ContactUiModel, unread: Boolean) {
    val palette = LocalLiquid.current
    val primary = MaterialTheme.colorScheme.primary
    Box(contentAlignment = Alignment.Center) {
        val ring = if (unread) {
            Brush.sweepGradient(listOf(primary, primary.copy(alpha = 0.25f), primary))
        } else {
            Brush.verticalGradient(listOf(palette.edgeHigh, palette.edgeLow))
        }
        Box(
            modifier = Modifier
                .size(52.dp)
                .then(if (unread) Modifier.liquidGlow(primary, radius = 6.dp, alpha = 0.35f) else Modifier)
                .border(width = if (unread) 2.dp else 1.dp, brush = ring, shape = CircleShape)
                .padding(if (unread) 3.dp else 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Avatar(
                name = contact.displayName,
                size = 46.dp,
                id = contact.id,
                avatarBytes = contact.avatarBytes
            )
        }
    }
}

/** Unread count as a lit pill — it breathes, slowly, so the eye finds it without it shouting. */
@Composable
private fun UnreadPill(count: Int) {
    val primary = MaterialTheme.colorScheme.primary
    val pulse = rememberInfiniteTransition(label = "unreadPulse")
    val glow by pulse.animateFloat(
        initialValue = 0.28f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(tween(1900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "unreadGlow"
    )
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 22.dp)
            .height(22.dp)
            .liquidGlow(primary, radius = 7.dp, alpha = glow)
            .clip(RoundedCornerShape(11.dp))
            .background(Brush.verticalGradient(listOf(primary, primary.copy(alpha = 0.82f))))
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
    }
}

@Preview(name = "Chat list item — dark", showBackground = true, backgroundColor = 0xFF06080D)
@Composable
private fun ChatListItemDarkPreview() {
    MessengerTheme(darkTheme = true) {
        LiquidTheme(dark = true) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                        lastIsMine = true, lastIsRead = true, pinnedAt = 1L
                    ),
                    onClick = {}
                )
            }
        }
    }
}

@Preview(name = "Chat list item — light", showBackground = true, backgroundColor = 0xFFEDF1F8)
@Composable
private fun ChatListItemLightPreview() {
    MessengerTheme(darkTheme = false) {
        LiquidTheme(dark = false) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
}
