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
import com.securemessenger.app.ui.viewmodel.ConnectionRequestsViewModel
import com.securemessenger.app.ui.viewmodel.ContactUiModel

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChatListScreen(
    onConversationClick: (String) -> Unit,
    onSettingsClick: () -> Unit,
    onNewChatClick: () -> Unit,
    onProfileClick: () -> Unit = {},
    viewModel: ChatListViewModel = viewModel(),
    connectionRequestsViewModel: ConnectionRequestsViewModel = viewModel()
) {
    val contacts by viewModel.contacts.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val incomingRequests by connectionRequestsViewModel.incomingRequests.collectAsState()
    val mc = LocalMessengerColors.current

    var searchQuery by remember { mutableStateOf("") }
    val visibleContacts = remember(contacts, searchQuery) {
        if (searchQuery.isBlank()) contacts
        else contacts.filter {
            it.displayName.contains(searchQuery, ignoreCase = true) || it.lastMessage.contains(searchQuery, ignoreCase = true)
        }
    }
    // "جهات الاتصال" doubles as the entry point to pending connection
    // requests found via username search — badged the same way an unread
    // count would be, so a waiting request is never silently missed.
    val navItems = remember(incomingRequests.size) {
        listOf(
            GlassNavItem("المحادثات", Icons.Default.ChatBubble),
            GlassNavItem("جهات الاتصال", Icons.Default.Group, badgeCount = incomingRequests.size),
            GlassNavItem("الإعدادات", Icons.Default.Settings),
            GlassNavItem("ملفي", Icons.Default.Person)
        )
    }

    Box(modifier = Modifier.fillMaxSize().glassBackground(mc.listGradient)) {
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            topBar = {
                Column {
                    GlassTopBar(title = "المحادثات")
                    SearchRow(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
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
                            // A conversation moving within its group — pinned or
                            // not, see ChatListViewModel's sort — animates into its
                            // new position instead of just teleporting there.
                            Box(modifier = Modifier.animateItemPlacement()) {
                                ChatListItem(
                                    contact = contact,
                                    onClick = { onConversationClick(contact.id) },
                                    onTogglePin = { viewModel.togglePin(contact.id) }
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
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
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

// ---------- moved out ----------
// (ChatListItem, formatChatTime, ChatListItemDarkPreview, ChatListItemLightPreview: ChatListItem.kt)
