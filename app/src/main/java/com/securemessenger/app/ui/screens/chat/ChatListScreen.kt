package com.securemessenger.app.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.securemessenger.app.ui.GlassBottomNavBar
import com.securemessenger.app.ui.GlassNavItem
import com.securemessenger.app.ui.GlassTopBar
import com.securemessenger.app.ui.glassBackground
import com.securemessenger.app.ui.glassCard
import com.securemessenger.app.ui.theme.LocalMessengerColors
import com.securemessenger.app.ui.theme.MessengerTheme
import com.securemessenger.app.ui.theme.SemanticColors
import com.securemessenger.app.ui.viewmodel.ChatListViewModel
import com.securemessenger.app.ui.viewmodel.ContactUiModel

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChatListScreen(
    onConversationClick: (String) -> Unit,
    onSettingsClick: () -> Unit,
    onNewChatClick: () -> Unit,
    onProfileClick: () -> Unit = {},
    viewModel: ChatListViewModel = viewModel()
) {
    val contacts by viewModel.contacts.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val mc = LocalMessengerColors.current

    var searchQuery by remember { mutableStateOf("") }
    val searchFocusRequester = remember { FocusRequester() }
    val visibleContacts = remember(contacts, searchQuery) {
        if (searchQuery.isBlank()) contacts
        else contacts.filter {
            it.displayName.contains(searchQuery, ignoreCase = true) || it.lastMessage.contains(searchQuery, ignoreCase = true)
        }
    }
    val navItems = remember {
        listOf(
            GlassNavItem("المحادثات", Icons.Default.ChatBubble),
            GlassNavItem("جهات الاتصال", Icons.Default.Group),
            GlassNavItem("الإعدادات", Icons.Default.Settings),
            GlassNavItem("ملفي", Icons.Default.Person)
        )
    }

    Box(modifier = Modifier.fillMaxSize().glassBackground(mc.listGradient)) {
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            topBar = {
                Column {
                    GlassTopBar(
                        title = "المحادثات",
                        actions = {
                            IconButton(onClick = { searchFocusRequester.requestFocus() }) {
                                Icon(Icons.Default.Search, contentDescription = "بحث في المحادثات", tint = mc.glassOnCard.copy(alpha = 0.7f))
                            }
                        }
                    )
                    SearchRow(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        focusRequester = searchFocusRequester,
                        textColor = mc.glassOnCard
                    )
                    ConnectionStatusBar()
                }
            },
            bottomBar = {
                GlassBottomNavBar(
                    items = navItems,
                    selectedIndex = 0,
                    onSelect = { index ->
                        when (index) {
                            1 -> onNewChatClick()
                            2 -> onSettingsClick()
                            3 -> onProfileClick()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .navigationBarsPadding()
                )
            }
        ) { paddingValues ->
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                if (isLoading) {
                    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
                        repeat(6) { ChatListItemSkeleton() }
                    }
                } else if (contacts.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(96.dp)
                                    .clip(CircleShape)
                                    .background(mc.glassCardStrong),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    modifier = Modifier.size(44.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = "ابدأ محادثتك المشفّرة الأولى",
                                style = MaterialTheme.typography.titleMedium,
                                color = mc.glassOnCard,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "كل رسالة، صورة وملف صوتي تبقى مشفّرة من طرفٍ إلى طرف — لا أحد غيركما يقرأها",
                                style = MaterialTheme.typography.bodyMedium,
                                color = mc.glassOnCard.copy(alpha = 0.65f),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(onClick = onNewChatClick) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("محادثة جديدة")
                            }
                        }
                    }
                } else if (visibleContacts.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "لا نتائج",
                            style = MaterialTheme.typography.bodyMedium,
                            color = mc.glassOnCard.copy(alpha = 0.65f)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(visibleContacts, key = { it.id }) { contact ->
                            // A conversation with a new message jumps to the top of
                            // the list (sorted by lastTimestamp) — this animates that
                            // reordering instead of it just teleporting there.
                            Box(modifier = Modifier.animateItemPlacement()) {
                                ChatListItem(
                                    contact = contact,
                                    onClick = { onConversationClick(contact.id) }
                                )
                            }
                        }
                    }
                }

                // Floating "+" FAB — sits above the bottom nav, on the
                // physical left edge per the reference (BottomEnd resolves
                // to the left in this RTL layout).
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 86.dp)
                        .size(54.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = onNewChatClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Add, contentDescription = "محادثة جديدة", tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(26.dp))
                }
            }
        }
    }
}

@Composable
private fun SearchRow(
    value: String,
    onValueChange: (String) -> Unit,
    focusRequester: FocusRequester,
    textColor: androidx.compose.ui.graphics.Color
) {
    val mc = LocalMessengerColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .glassCard(radius = 14.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = textColor.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text("بحث…", style = MaterialTheme.typography.bodyMedium, color = textColor.copy(alpha = 0.45f))
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = textColor),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
            )
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
}

private fun formatChatTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
    val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    val pattern = if (sameDay) "HH:mm" else "dd/MM"
    return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(java.util.Date(timestamp))
}

/** One shimmering placeholder row, shape-matched to [ChatListItem] — shown only until the first real snapshot loads. */
@Composable
private fun ChatListItemSkeleton() {
    val mc = LocalMessengerColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassCard(radius = 18.dp)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ShimmerBox(modifier = Modifier.size(48.dp), shape = CircleShape)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            ShimmerBox(modifier = Modifier.width(120.dp).height(16.dp))
            Spacer(modifier = Modifier.height(8.dp))
            ShimmerBox(modifier = Modifier.width(180.dp).height(13.dp))
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
fun ChatListItem(
    contact: ContactUiModel,
    onClick: () -> Unit
) {
    val mc = LocalMessengerColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassCard(radius = 18.dp)
            .clickable(onClick = onClick)
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
