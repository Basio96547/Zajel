package com.securemessenger.app.ui.screens.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.securemessenger.app.SecureMessengerApp
import com.securemessenger.app.network.ConnectionState
import com.securemessenger.app.ui.liquid.AuroraBackdrop
import com.securemessenger.app.ui.liquid.LiquidTheme
import com.securemessenger.app.ui.liquid.LocalLiquid
import com.securemessenger.app.ui.liquid.liquidGlow
import com.securemessenger.app.ui.liquid.liquidSurface
import com.securemessenger.app.ui.viewmodel.ChatListViewModel
import com.securemessenger.app.ui.viewmodel.ConnectionRequestsViewModel
import com.securemessenger.app.ui.viewmodel.ContactUiModel

/**
 * The home screen, rebuilt on the liquid-glass layer in
 * [com.securemessenger.app.ui.liquid].
 *
 * Its contract is unchanged on purpose — same parameters, same view models,
 * same navigation indices — because the redesign was scoped to how this
 * screen looks and moves, not to what it does. Nothing outside this file,
 * ChatListItem.kt and LiquidGlass.kt was touched, so every other screen still
 * renders exactly as it did.
 *
 * The depth is composed of four planes, back to front: the drifting aurora,
 * the list that parallaxes over it, the header that starts invisible and
 * frosts over as content slides beneath it, and the navigation and action
 * controls floating highest with the deepest shadows.
 */
@Composable
fun ChatListScreen(
    onConversationClick: (String) -> Unit,
    onNewChatClick: () -> Unit,
    onConnectionRequestsClick: () -> Unit = {},
    viewModel: ChatListViewModel = viewModel(),
    connectionRequestsViewModel: ConnectionRequestsViewModel = viewModel()
) {
    val contacts by viewModel.contacts.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val incomingRequests by connectionRequestsViewModel.incomingRequests.collectAsState()

    ChatListContent(
        contacts = contacts,
        isLoading = isLoading,
        pendingRequestCount = incomingRequests.size,
        onConversationClick = onConversationClick,
        onTogglePin = viewModel::togglePin,
        onNewChatClick = onNewChatClick,
        onConnectionRequestsClick = onConnectionRequestsClick
    )
}

/**
 * The screen itself, given plain data instead of view models.
 *
 * Split out for a concrete reason rather than tidiness: with the view models
 * wired in, this screen could only ever be rendered by a device that had
 * finished setup and unlocked its database, which made it impossible to
 * photograph, preview, or test against a populated list. Taking a
 * `List<ContactUiModel>` makes all three trivial and costs the caller four
 * lines.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatListContent(
    contacts: List<ContactUiModel>,
    isLoading: Boolean,
    pendingRequestCount: Int,
    onConversationClick: (String) -> Unit,
    onTogglePin: (String) -> Unit,
    onNewChatClick: () -> Unit,
    onConnectionRequestsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    LiquidTheme {
        val density = LocalDensity.current

        var searchQuery by remember { mutableStateOf("") }
        val visibleContacts = remember(contacts, searchQuery) {
            if (searchQuery.isBlank()) contacts
            else contacts.filter {
                it.displayName.contains(searchQuery, ignoreCase = true) ||
                    it.lastMessage.contains(searchQuery, ignoreCase = true)
            }
        }

        val listState = rememberLazyListState()

        // How far the header has folded away, 0..1. Read through derivedStateOf
        // so scrolling only recomposes what actually depends on it.
        val collapse by remember {
            derivedStateOf {
                if (listState.firstVisibleItemIndex > 0) 1f
                else (listState.firstVisibleItemScrollOffset / 160f).coerceIn(0f, 1f)
            }
        }

        // Rows only play their entrance once. Without this the animation
        // replays every time a recycled row scrolls back into view, which
        // reads as a glitch rather than as polish.
        val entered = remember { mutableSetOf<String>() }

        var headerHeightPx by remember { mutableIntStateOf(0) }
        val headerHeight = with(density) { headerHeightPx.toDp() }

        Box(modifier = modifier.fillMaxSize()) {
            AuroraBackdrop(
                parallaxPx = {
                    // Approximate, and that is fine: this drives a parallax
                    // cue, not a layout. One row is roughly 92dp tall.
                    val approxRow = 92f * density.density
                    (listState.firstVisibleItemIndex * approxRow + listState.firstVisibleItemScrollOffset)
                        .coerceIn(0f, 1600f)
                }
            )

            when {
                isLoading -> LoadingList(topPadding = headerHeight)
                contacts.isEmpty() -> EmptyState(onNewChatClick = onNewChatClick, topPadding = headerHeight)
                visibleContacts.isEmpty() -> NoResults(query = searchQuery, topPadding = headerHeight)
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = headerHeight + 6.dp,
                        bottom = 116.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(visibleContacts, key = { _, contact -> contact.id }) { index, contact ->
                        val delay = if (contact.id in entered) -1 else index.coerceAtMost(9) * 45
                        LaunchedEffect(contact.id) { entered.add(contact.id) }
                        // A conversation moving within its group — pinned or
                        // not, see ChatListViewModel's sort — glides to its new
                        // position instead of teleporting.
                        Box(modifier = Modifier.animateItemPlacement()) {
                            ChatListItem(
                                contact = contact,
                                onClick = { onConversationClick(contact.id) },
                                onTogglePin = { onTogglePin(contact.id) },
                                entranceDelayMillis = delay
                            )
                        }
                    }
                }
            }

            // ---- plane 3: the header ----
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .onSizeChanged { headerHeightPx = it.height }
            ) {
                // The frosted pane only materialises once something is
                // scrolling underneath it; at rest the title floats directly
                // on the aurora.
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = collapse }
                        .liquidSurface(
                            shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp),
                            raised = true,
                            elevation = 16.dp
                        )
                )
                Column(modifier = Modifier.statusBarsPadding()) {
                    HeaderTitle(
                        collapse = collapse,
                        conversationCount = contacts.size
                    )
                    SearchField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    LiquidConnectionStrip()
                    PendingRequestsBanner(
                        count = pendingRequestCount,
                        onClick = onConnectionRequestsClick
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            // Nothing floats here any more, and that is the point.
            //
            // This corner held a "محادثة جديدة" button whose destination is
            // the pairing screen — which is now the "جهات الاتصال" tab, one
            // row below it. That duplication is the very thing that got the
            // bottom bar deleted the first time ("one screen, two controls,
            // two different names, same corner"); restoring the bar without
            // removing the button would have just rebuilt the defect with the
            // roles reversed. The settings and profile icons that had moved
            // into the header are gone for the same reason: a root
            // destination is reached from the bar, not from an ad-hoc icon on
            // one screen's header.
            //
            // The empty state keeps its own call to action — with no
            // conversations there is nothing else on screen to say.
        }
    }
}

/** Large at rest, condensed once the list moves under it — one title, two sizes, animated between. */
@Composable
private fun HeaderTitle(
    collapse: Float,
    conversationCount: Int
) {
    val palette = LocalLiquid.current
    val primary = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 20.dp, top = 14.dp - (6 * collapse).dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "المحادثات",
                fontSize = (30f - 9f * collapse).sp,
                fontWeight = FontWeight.Bold,
                color = palette.onSurface
            )
            // The subtitle is the first thing to go — it is context, and
            // context is what you stop needing once you are reading.
            //
            // It also absorbed the old "مشفّر" chip, which sat beside a line
            // already ending in the word مشفّرة: a badge repeating the
            // sentence next to it. The lock is now a glyph on the sentence
            // itself, and the space that freed is where the two icons
            // opposite are standing.
            if (collapse < 0.98f) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.graphicsLayer {
                        alpha = 1f - collapse
                        translationY = -collapse * 6.dp.toPx()
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(11.dp),
                        tint = primary
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = conversationSubtitle(conversationCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.muted
                    )
                }
            }
        }
    }
}

/**
 * Arabic counts the noun, not just the number.
 *
 * The first version said "5 محادثة مشفّرة", which is simply wrong: 3–10 take
 * the plural, 2 takes the dual, and only 11 and up return to the singular.
 * A count that reads as broken grammar undermines a screen whose whole job is
 * to look trustworthy.
 */
private fun conversationSubtitle(count: Int): String = when {
    count <= 0 -> "مشفّرة من طرف إلى طرف"
    count == 1 -> "محادثة واحدة مشفّرة"
    count == 2 -> "محادثتان مشفّرتان"
    count <= 10 -> "$count محادثات مشفّرة"
    else -> "$count محادثة مشفّرة"
}

/**
 * Pending introductions, somewhere the count can actually be acted on.
 *
 * It used to be a red dot on the bar entry that opened the *new chat* screen
 * — one level away from the requests it was counting, and silent about what
 * it meant. A banner that names the thing, goes straight to it, and vanishes
 * entirely at zero says more and costs nothing when there is nothing to say.
 */
@Composable
private fun PendingRequestsBanner(count: Int, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    AnimatedVisibility(visible = count > 0, enter = fadeIn(), exit = fadeOut()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .liquidSurface(shape = RoundedCornerShape(14.dp), raised = true, elevation = 8.dp)
                .clickableNoRipple(onClick)
                .padding(horizontal = 13.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.MarkEmailUnread,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = primary
            )
            Spacer(Modifier.width(9.dp))
            Text(
                text = pendingRequestsLabel(count),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = primary,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Default.ChevronLeft,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = primary.copy(alpha = 0.7f)
            )
        }
    }
}

/** Same counting rules as [conversationSubtitle] — Arabic does not take a bare number and a singular noun. */
private fun pendingRequestsLabel(count: Int): String = when {
    count == 1 -> "طلب تواصل واحد بانتظارك"
    count == 2 -> "طلبا تواصل بانتظارك"
    count <= 10 -> "$count طلبات تواصل بانتظارك"
    else -> "$count طلب تواصل بانتظارك"
}

/**
 * The connection state, in this screen's own material.
 *
 * The shared [ConnectionStatusBar] draws an opaque, full-bleed Material
 * surface — right for the screens that use it, and a flat slab laid across a
 * design made of floating glass. The screenshot made that impossible to
 * defend. Same information, same wording, inset and frosted like everything
 * else here; the shared one stays exactly as it is for its own callers.
 */
@Composable
private fun LiquidConnectionStrip() {
    val palette = LocalLiquid.current
    val state by SecureMessengerApp.instance.connectionState.collectAsState()

    AnimatedVisibility(visible = state !is ConnectionState.Connected, enter = fadeIn(), exit = fadeOut()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .liquidSurface(shape = RoundedCornerShape(14.dp), elevation = 4.dp)
                .padding(horizontal = 13.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (state) {
                is ConnectionState.Connecting -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(13.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(9.dp))
                    Text("جارٍ الاتصال…", style = MaterialTheme.typography.labelMedium, color = palette.muted)
                }
                is ConnectionState.Error, is ConnectionState.Disconnected -> {
                    Icon(
                        Icons.Default.CloudOff,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        "غير متصل — سيُعاد المحاولة تلقائياً",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.muted,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Text(
                        "إعادة المحاولة",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickableNoRipple {
                            SecureMessengerApp.instance.messagingClient?.retryNow()
                        }
                    )
                }
                else -> {}
            }
        }
    }
}

/** Glass pill that lights from within when focused. */
@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalLiquid.current
    val primary = MaterialTheme.colorScheme.primary
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .liquidSurface(shape = RoundedCornerShape(18.dp), raised = focused, elevation = if (focused) 14.dp else 6.dp)
            .then(
                if (focused) Modifier.border(1.5.dp, primary.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
                else Modifier
            )
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = if (focused) primary else palette.muted,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    "بحث في المحادثات…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.muted.copy(alpha = 0.85f)
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                interactionSource = interaction,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = palette.onSurface),
                cursorBrush = SolidColor(primary),
                modifier = Modifier.fillMaxWidth()
            )
        }
        AnimatedVisibility(visible = value.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "مسح البحث",
                tint = palette.muted,
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .clickableNoRipple { onValueChange("") }
            )
        }
    }
}

@Composable
private fun EmptyState(onNewChatClick: () -> Unit, topPadding: androidx.compose.ui.unit.Dp) {
    val palette = LocalLiquid.current
    val primary = MaterialTheme.colorScheme.primary
    val breath = rememberInfiniteTransition(label = "emptyBreath")
    val scale by breath.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(3200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "emptyBreathScale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = topPadding, start = 32.dp, end = 32.dp, bottom = 120.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .liquidGlow(primary, radius = 18.dp, alpha = 0.28f)
                    .liquidSurface(shape = CircleShape, raised = true, elevation = 18.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(46.dp),
                    tint = primary
                )
            }
            Spacer(modifier = Modifier.height(26.dp))
            Text(
                text = "ابدأ محادثتك المشفّرة الأولى",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = palette.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "كل رسالة، صورة وملف صوتي تبقى مشفّرة من طرفٍ إلى طرف — لا أحد غيركما يقرأها",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.muted,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(26.dp))
            Row(
                modifier = Modifier
                    .height(50.dp)
                    .liquidGlow(primary, radius = 10.dp, alpha = 0.28f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Brush.verticalGradient(listOf(primary, primary.copy(alpha = 0.86f))))
                    .clickableNoRipple(onNewChatClick)
                    .padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
                Spacer(modifier = Modifier.width(8.dp))
                // Says where it goes. It opens the pairing tab, which is
                // named "جهات الاتصال" one row below — a button labelled
                // "محادثة جديدة" that lands you on a QR code was the third
                // name this one destination was being given.
                Text("أضف جهة اتصال", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun NoResults(query: String, topPadding: androidx.compose.ui.unit.Dp) {
    val palette = LocalLiquid.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = topPadding, start = 32.dp, end = 32.dp, bottom = 120.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.SearchOff,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = palette.muted
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "لا نتائج لـ «$query»",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.muted,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun LoadingList(topPadding: androidx.compose.ui.unit.Dp) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = topPadding + 6.dp, start = 12.dp, end = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        repeat(6) { ChatListItemSkeleton() }
    }
}

/** One placeholder row, shape-matched to [ChatListItem] — shown only until the first real snapshot lands. */
@Composable
private fun ChatListItemSkeleton() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .liquidSurface(shape = RoundedCornerShape(22.dp), elevation = 8.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ShimmerBox(modifier = Modifier.size(52.dp), shape = RoundedCornerShape(26.dp))
        Spacer(modifier = Modifier.width(13.dp))
        Column(modifier = Modifier.weight(1f)) {
            ShimmerBox(modifier = Modifier.width(130.dp).height(16.dp))
            Spacer(modifier = Modifier.height(9.dp))
            ShimmerBox(modifier = Modifier.width(190.dp).height(13.dp))
        }
    }
}

/**
 * Click without Material's ripple. The whole surface language here is glass
 * that springs under pressure; a spreading ink circle belongs to a different
 * material entirely.
 */
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    this.clickable(
        interactionSource = interaction,
        indication = null,
        onClick = onClick
    )
}

// ---------- moved out ----------
// (ChatListItem, formatChatTime, previews: ChatListItem.kt)
// (Avatar, ShimmerBox, ConnectionStatusBar: ChatCommon.kt — shared, untouched)
