package com.securemessenger.app.ui.screens.chat

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.securemessenger.core.crypto.MediaCodec
import com.securemessenger.app.ui.GlassTopBar
import com.securemessenger.app.ui.glassBackground
import com.securemessenger.app.ui.gesture.pressScale
import com.securemessenger.app.ui.theme.Dims
import com.securemessenger.app.ui.theme.LocalMessengerColors
import com.securemessenger.app.ui.viewmodel.ConversationViewModel
import com.securemessenger.app.ui.viewmodel.MessageUiModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ConversationScreen(
    contactId: String,
    onBackClick: () -> Unit,
    onVerificationClick: () -> Unit,
    onContactInfoClick: () -> Unit = {},
    viewModel: ConversationViewModel = viewModel(
        key = contactId,
        factory = ConversationViewModelFactory(contactId)
    )
) {
    var messageText by remember { mutableStateOf("") }
    val messages by viewModel.messages.collectAsState()
    val isSending by viewModel.isSending.collectAsState()
    val error by viewModel.error.collectAsState()
    val contactName by viewModel.contactName.collectAsState()
    val contactAvatar by viewModel.contactAvatar.collectAsState()
    val contactVerified by viewModel.contactVerified.collectAsState()
    val isContactTyping by viewModel.isContactTyping.collectAsState()
    val establishingSession by viewModel.establishingSession.collectAsState()
    // One stable adapter, not a fresh lambda per recomposition — the media

    // composables key remembered work on it.

    val loadMedia = remember(viewModel) { MediaLoader { viewModel.loadMediaBytes(it) } }

    val listState = rememberLazyListState()
    val mc = LocalMessengerColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Every bubble key that has already played its spring entrance — survives
    // recomposition so scrolling history back into view never replays it,
    // only a genuinely new bubble does.
    val seenBubbleKeys = remember { mutableSetOf<String>() }
    // Which bubble to briefly flash (jumped to via a reply-quote tap).
    var highlightedClientId by remember { mutableStateOf<String?>(null) }
    // Message-interaction UI state
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var actionTarget by remember { mutableStateOf<MessageUiModel?>(null) }
    var replyingTo by remember { mutableStateOf<MessageUiModel?>(null) }
    var editingTarget by remember { mutableStateOf<MessageUiModel?>(null) }
    var viewerMedia by remember { mutableStateOf<MediaCodec.LocalMedia?>(null) }
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Errors surface as a transient Snackbar with a retry action instead of a
    // permanent red line — the outbox already retries automatically, so this
    // just gives the user visible control/reassurance on top of that.
    LaunchedEffect(error) {
        val message = error ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = "إعادة",
            duration = SnackbarDuration.Long
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.retryLastAction()
        else viewModel.clearError()
    }

    var showAttachSheet by remember { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var pendingMedia by remember { mutableStateOf<PickedFile?>(null) }

    val attachLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val picked = withContext(Dispatchers.IO) { readPickedFile(context, uri) }
            if (picked == null) return@launch
            // Media waits for an optional caption instead of sending instantly —
            // the normal text field becomes the caption composer.
            pendingMedia = picked
        }
    }

    // Several photos picked together send as separate messages in quick
    // succession — close enough in time that the conversation renders them
    // as one mosaic album instead of a stack of individual bubbles.
    val multiPhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            uris.forEach { uri ->
                val picked = withContext(Dispatchers.IO) { readPickedFile(context, uri) }
                if (picked != null) viewModel.sendMedia(picked.bytes, picked.mimeType, picked.fileName, picked.mediaType)
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingCameraUri
        pendingCameraUri = null
        if (!success || uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } finally {
                    // The plaintext photo only ever exists on disk for the
                    // instant the camera app needs to write into it.
                    try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                }
            }
            if (bytes != null) pendingMedia = PickedFile(bytes, "image/jpeg", "photo.jpg", MediaCodec.TYPE_IMAGE)
        }
    }

    Box(modifier = Modifier.fillMaxSize().glassBackground(mc.chatGradient)) {
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
          Column {
            if (selectionMode) {
                GlassTopBar(
                    navigationIcon = {
                        IconButton(onClick = { selectionMode = false; selectedIds = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = "إلغاء التحديد")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            val texts = messages.filter { it.id in selectedIds && !it.isDeleted && it.media == null }
                                .sortedBy { it.timestamp }.joinToString("\n") { it.text }
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(texts))
                            selectionMode = false
                            selectedIds = emptySet()
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "نسخ")
                        }
                        IconButton(onClick = {
                            selectedIds.forEach { viewModel.deleteForMe(it) }
                            selectionMode = false
                            selectedIds = emptySet()
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "حذف لديّ")
                        }
                    }
                ) {
                    Text(
                        text = "${selectedIds.size} محددة",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
            GlassTopBar(
                onBack = onBackClick,
                actions = {
                    IconButton(onClick = { viewModel.reportError("المكالمات غير متاحة في هذا الإصدار") }) {
                        Icon(Icons.Default.Call, contentDescription = "اتصال")
                    }
                    var menuExpanded by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "المزيد")
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("بحث في المحادثة") },
                                onClick = { menuExpanded = false; searchActive = !searchActive; if (!searchActive) searchQuery = "" }
                            )
                            DropdownMenuItem(
                                text = { Text("التحقّق من المفتاح") },
                                onClick = { menuExpanded = false; onVerificationClick() }
                            )
                            DropdownMenuItem(
                                text = { Text("معلومات جهة الاتصال") },
                                onClick = { menuExpanded = false; onContactInfoClick() }
                            )
                        }
                    }
                }
            ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f).clickable(onClick = onContactInfoClick)
                    ) {
                        Avatar(
                            name = contactName.ifBlank { contactId },
                            size = Dims.avatarSmall,
                            id = contactId,
                            avatarBytes = contactAvatar
                        )
                        Spacer(Modifier.width(Dims.s12))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = contactName.ifBlank { "محادثة" },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                                if (contactVerified) {
                                    Spacer(Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.Verified,
                                        contentDescription = "متحقق",
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            // Third state alongside typing/encrypted: the first
                            // message to a contact needs a real network
                            // handshake (several seconds) before it can be
                            // delivered — without this, the only feedback
                            // during that wait was a tiny pending-tick icon on
                            // the bubble, easy to miss, and the app could feel
                            // frozen even though it's working normally.
                            val subtitleState = when {
                                establishingSession -> "handshake"
                                isContactTyping -> "typing"
                                else -> "idle"
                            }
                            Crossfade(targetState = subtitleState, label = "subtitle") { state ->
                                when (state) {
                                    "handshake" -> Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(11.dp),
                                            strokeWidth = 1.5.dp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            "جارٍ إنشاء اتصال آمن…",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    "typing" -> Text(
                                        "يكتب الآن…",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    // Concrete redesign: a permanent, monospace
                                    // trust line reflecting the real verification
                                    // state instead of a generic "encrypted" label
                                    // — this is the one place the crypto trust
                                    // model is always visible, not tucked behind
                                    // a separate screen.
                                    else -> Text(
                                        text = if (contactVerified)
                                            "موثّق · Double Ratchet + ML-KEM-768"
                                        else
                                            "غير موثّق · Double Ratchet + ML-KEM-768",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (contactVerified) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
            }
            }
            ConnectionStatusBar()
          }
        }
    ) { paddingValues ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
        ) {
            if (searchActive) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dims.s12, vertical = Dims.s4),
                    placeholder = { Text("ابحث في الرسائل…") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "مسح")
                            }
                        }
                    }
                )
            }

            // The search filters entirely locally over already-decrypted text —
            // no server index, nothing leaves the device.
            val shownMessages = if (searchActive && searchQuery.isNotBlank()) {
                messages.filter { !it.isDeleted && it.text.contains(searchQuery, ignoreCase = true) }
            } else messages
            val rows = remember(shownMessages) { buildRows(shownMessages) }

            // Whether the viewport is already showing the newest item — only
            // then does an incoming message auto-scroll; someone reading
            // scrollback shouldn't get yanked to the bottom.
            val isNearBottom by remember {
                derivedStateOf {
                    val info = listState.layoutInfo
                    val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf true
                    last >= info.totalItemsCount - 2
                }
            }
            val unreadCount = remember(messages) { messages.count { it.direction == 0 && !it.isRead } }
            val distinctDayCount = remember(rows) { rows.count { it is ChatRow.Day } }
            val topVisibleDateLabel by remember {
                derivedStateOf {
                    val visible = listState.layoutInfo.visibleItemsInfo
                    val topIndex = visible.firstOrNull()?.index ?: return@derivedStateOf null
                    for (i in topIndex downTo 0) {
                        val row = rows.getOrNull(i)
                        if (row is ChatRow.Day) return@derivedStateOf row.label
                    }
                    null
                }
            }

            LaunchedEffect(rows.size) {
                if (rows.isEmpty()) return@LaunchedEffect
                val lastIsMine = (rows.last() as? ChatRow.Msg)?.message?.direction == 1
                if (isNearBottom || lastIsMine) {
                    listState.animateScrollToItem(rows.size - 1)
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = Dims.s12, vertical = Dims.s8),
                    verticalArrangement = Arrangement.spacedBy(Dims.s2)
                ) {
                    item(key = "encrypted-banner") { EncryptedBanner() }
                    items(rows, key = { it.key }) { row ->
                        // A new message (or day separator) slides/fades into its
                        // place instead of just popping onto the screen — Compose
                        // handles both a fresh insertion and existing items
                        // shifting to make room for it. Consecutive bubbles from
                        // the same sender sit closer together; a new group gets
                        // a little breathing room above it.
                        Box(
                            modifier = Modifier
                                .animateItemPlacement()
                                .padding(top = if (row is ChatRow.Msg && row.isFirstInGroup) Dims.s6 else 0.dp)
                        ) {
                            when (row) {
                                is ChatRow.Day -> DateSeparator(row.label)
                                is ChatRow.Msg -> MessageBubble(
                                    message = row.message,
                                    isLastInGroup = row.isLastInGroup,
                                    loadMedia = loadMedia,
                                    onLongPress = { if (!row.message.isDeleted) actionTarget = row.message },
                                    onSwipeReply = { if (!row.message.isDeleted) replyingTo = row.message },
                                    onOpenImageViewer = { viewerMedia = it },
                                    seenKeys = seenBubbleKeys,
                                    isHighlighted = row.message.clientId != null && row.message.clientId == highlightedClientId,
                                    onJumpToReplyTarget = { targetClientId ->
                                        val index = rows.indexOfFirst { r -> (r as? ChatRow.Msg)?.message?.clientId == targetClientId }
                                        if (index >= 0) {
                                            scope.launch {
                                                listState.animateScrollToItem(index)
                                                highlightedClientId = targetClientId
                                                kotlinx.coroutines.delay(1200)
                                                if (highlightedClientId == targetClientId) highlightedClientId = null
                                            }
                                        }
                                    },
                                    isSelectionMode = selectionMode,
                                    isSelected = row.message.id != null && row.message.id in selectedIds,
                                    onToggleSelect = {
                                        row.message.id?.let { id ->
                                            selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                                            if (selectedIds.isEmpty()) selectionMode = false
                                        }
                                    }
                                )
                                is ChatRow.Album -> AlbumBubble(
                                    images = row.images,
                                    loadMedia = loadMedia,
                                    onOpenImageViewer = { viewerMedia = it }
                                )
                            }
                        }
                    }
                }

                // Floating date badge for whichever day is scrolled to the top
                // of the viewport right now — only worth showing when the
                // conversation actually spans more than one day.
                androidx.compose.animation.AnimatedVisibility(
                    visible = distinctDayCount > 1 && topVisibleDateLabel != null,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = Dims.s8),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x66000000))
                            .padding(horizontal = Dims.s12, vertical = Dims.s4)
                    ) {
                        Text(
                            topVisibleDateLabel ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White
                        )
                    }
                }

                // Scroll-to-bottom affordance with an unread badge — appears
                // only once the user has scrolled away from the newest message.
                // Fully-qualified: inside this Box, plain `AnimatedVisibility`
                // resolves ambiguously against the enclosing Column's
                // ColumnScope overload — force the non-scoped top-level one.
                androidx.compose.animation.AnimatedVisibility(
                    visible = !isNearBottom,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(Dims.s16),
                    enter = scaleIn() + fadeIn(),
                    exit = scaleOut() + fadeOut()
                ) {
                    BadgedBox(badge = {
                        if (unreadCount > 0) {
                            Badge { Text(if (unreadCount > 99) "99+" else unreadCount.toString()) }
                        }
                    }) {
                        SmallFloatingActionButton(
                            modifier = Modifier.pressScale(),
                            onClick = {
                                scope.launch { listState.animateScrollToItem((rows.size - 1).coerceAtLeast(0)) }
                            },
                            containerColor = MaterialTheme.colorScheme.surface
                        ) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "انزل للأسفل")
                        }
                    }
                }
            }

            // Media-caption strip takes priority over reply/edit — sending a
            // captioned attachment isn't also a reply/edit in this version.
            // Both slide/fade in instead of just popping above the input.
            androidx.compose.animation.AnimatedVisibility(
                visible = pendingMedia != null,
                enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                pendingMedia?.let { media ->
                    MediaCaptionStrip(
                        media = media,
                        onCancel = {
                            pendingMedia = null
                            messageText = ""
                        }
                    )
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = pendingMedia == null && (replyingTo != null || editingTarget != null),
                enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                ComposeContextStrip(
                    isEdit = editingTarget != null,
                    snippet = (editingTarget ?: replyingTo)?.let {
                        if (it.media != null) "وسائط" else it.text
                    } ?: "",
                    onCancel = {
                        replyingTo = null
                        editingTarget = null
                        messageText = ""
                    }
                )
            }

            MessageInput(
                value = messageText,
                onValueChange = {
                    messageText = it
                    if (it.isNotBlank()) viewModel.notifyTyping()
                },
                onSendClick = {
                    val media = pendingMedia
                    if (media != null) {
                        viewModel.sendMedia(media.bytes, media.mimeType, media.fileName, media.mediaType, caption = messageText.trim())
                        pendingMedia = null
                        messageText = ""
                    } else if (messageText.isNotBlank()) {
                        when {
                            editingTarget?.clientId != null -> {
                                viewModel.editMessage(editingTarget!!.clientId!!, messageText)
                                editingTarget = null
                            }
                            replyingTo?.clientId != null -> {
                                viewModel.sendReply(messageText, replyingTo!!.clientId!!)
                                replyingTo = null
                            }
                            else -> viewModel.sendMessage(messageText)
                        }
                        messageText = ""
                    }
                },
                onAttachClick = { showAttachSheet = true },
                forceSendButton = pendingMedia != null,
                onVoiceMessageReady = { result ->
                    viewModel.sendMedia(result.wav, "audio/wav", "voice.wav", MediaCodec.TYPE_AUDIO, result.waveform)
                },
                onVoiceError = { viewModel.reportError(it) },
                sending = isSending
            )
        }

        // Long-press action menu: quick reactions + reply/copy/edit/delete.
        actionTarget?.let { target ->
            MessageActionSheet(
                target = target,
                onDismiss = { actionTarget = null },
                onReact = { emoji ->
                    target.clientId?.let { viewModel.react(it, emoji) }
                    actionTarget = null
                },
                onReply = { replyingTo = target; actionTarget = null },
                onCopy = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(target.text))
                    actionTarget = null
                },
                onEdit = {
                    editingTarget = target
                    messageText = target.text
                    actionTarget = null
                },
                onDeleteForEveryone = {
                    target.clientId?.let { viewModel.deleteForEveryone(it) }
                    actionTarget = null
                },
                onDeleteForMe = {
                    target.id?.let { viewModel.deleteForMe(it) }
                    actionTarget = null
                },
                onSelect = {
                    target.id?.let {
                        selectionMode = true
                        selectedIds = setOf(it)
                    }
                    actionTarget = null
                }
            )
        }

        viewerMedia?.let { media ->
            FullScreenImageViewer(media = media, loadMedia = loadMedia, onDismiss = { viewerMedia = null })
        }

        if (showAttachSheet) {
            AttachmentSheet(
                onDismiss = { showAttachSheet = false },
                onCameraClick = {
                    showAttachSheet = false
                    // No CAMERA permission needed here — this delegates to
                    // whatever camera app is installed via an intent, the same
                    // way OpenDocument() avoids needing storage permission.
                    val uri = createCameraOutputUri(context)
                    pendingCameraUri = uri
                    cameraLauncher.launch(uri)
                },
                onGalleryClick = {
                    showAttachSheet = false
                    attachLauncher.launch(arrayOf("image/*", "video/*"))
                },
                onAlbumClick = {
                    showAttachSheet = false
                    multiPhotoLauncher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onFileClick = {
                    showAttachSheet = false
                    attachLauncher.launch(arrayOf("*/*"))
                }
            )
        }
    }
    }
}

// ---------- message bubble ----------
// (ChatRow, buildRows, dayKey, dayLabel: ConversationRows.kt)
// (PickedFile, readPickedFile, createCameraOutputUri: AttachmentPicking.kt)
// (MessageBubble: MessageBubble.kt)
// (AlbumBubble, AlbumThumbnail: AlbumBubble.kt)
// (MediaContent family, FullScreenImageViewer: MediaContent.kt)
// (MessageInput, EmojiPanel: MessageInput.kt)
// (ComposeContextStrip, MediaCaptionStrip: ComposeStrips.kt)
// (AttachmentSheet, MessageActionSheet: ConversationSheets.kt)
// (EncryptedBanner, DateSeparator, ShimmerBox, ConnectionStatusBar, Avatar: ChatCommon.kt)
