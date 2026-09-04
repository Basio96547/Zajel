package com.securemessenger.app.ui.screens.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.securemessenger.app.SecureMessengerApp
import com.securemessenger.app.crypto.AndroidKeyStoreManager
import com.securemessenger.core.crypto.MediaCodec
import com.securemessenger.app.data.model.Contact
import com.securemessenger.app.data.model.EncryptedMessage
import com.securemessenger.app.ui.GlassTopBar
import com.securemessenger.app.ui.glassBackground
import com.securemessenger.app.ui.glassCard
import com.securemessenger.app.ui.theme.Dims
import com.securemessenger.app.ui.theme.LocalMessengerColors
import com.securemessenger.app.ui.theme.SemanticColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Contact info screen: avatar (tap to set a local photo), nickname, mute,
 * block/unblock, a link into the real safety-number verification for this
 * specific contact, and the media shared in this conversation so far.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailScreen(
    contactId: String,
    onBackClick: () -> Unit,
    onVerifyClick: () -> Unit
) {
    val repository = remember { SecureMessengerApp.instance.repository }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val mc = LocalMessengerColors.current

    var contact by remember { mutableStateOf<Contact?>(null) }
    var avatarBytes by remember { mutableStateOf<ByteArray?>(null) }
    var displayName by remember { mutableStateOf(contactId.take(8)) }
    var nickname by remember { mutableStateOf("") }
    var mediaMessages by remember { mutableStateOf<List<EncryptedMessage>>(emptyList()) }
    var showBlockConfirm by remember { mutableStateOf(false) }
    var showNicknameDialog by remember { mutableStateOf(false) }
    var showAddressDialog by remember { mutableStateOf(false) }
    var fullscreenImage by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var transport by remember {
        mutableStateOf<com.securemessenger.app.network.TransportStatus?>(null)
    }

    suspend fun reload() {
        val c = repository.getContact(contactId) ?: return
        contact = c
        avatarBytes = repository.getContactAvatar(contactId)
        displayName = try {
            String(AndroidKeyStoreManager.decryptWithMasterKey(c.displayNameEncrypted), Charsets.UTF_8)
        } catch (_: Exception) {
            contactId.take(8)
        }
        nickname = repository.getContactNickname(contactId) ?: ""
        transport = SecureMessengerApp.instance.messagingClient?.transportStatus(contactId)
    }

    // Discovery comes and goes on its own (mDNS ticks, sockets drop, the peer
    // moves networks), so this is polled rather than read once — a status card
    // that froze on whatever was true when the screen opened would be worse
    // than none at all.
    LaunchedEffect(contactId) {
        while (true) {
            transport = SecureMessengerApp.instance.messagingClient?.transportStatus(contactId)
            kotlinx.coroutines.delay(3000)
        }
    }

    // Guarded, like ProfileScreen's and KeyVerificationScreen's reads: every
    // call in here goes to a repository that throws while its database is
    // closed, and an exception inside a LaunchedEffect is not a state a
    // composable recovers from. This is the seventh place in the app with
    // that shape; all of them were found by rendering screens rather than by
    // reading them.
    //
    // The screen already handles knowing nothing — `contact` stays null and
    // it renders its empty shell — so failing quietly here degrades to a
    // state the screen was already built for.
    LaunchedEffect(contactId) {
        try {
            reload()
            // Media only, straight from the query. This used to fetch the
            // current session's entire message history and filter it here —
            // the whole thread decrypted into memory so a grid could show a
            // few thumbnails. It also meant the media of a conversation was
            // scoped to its current cryptographic session rather than to the
            // contact, so anything exchanged under an earlier session was
            // simply absent from "الوسائط المشتركة".
            repository.observeMedia(contactId).collect { list -> mediaMessages = list }
        } catch (e: Exception) {
            mediaMessages = emptyList()
        }
    }

    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
            if (bytes != null) {
                repository.setContactAvatar(contactId, bytes)
                avatarBytes = repository.getContactAvatar(contactId)
            }
        }
    }

    val shownName = nickname.ifBlank { displayName }
    val isMuted = contact?.isMuted == true
    val isBlocked = contact?.isBlocked == true
    val isVerified = contact?.isVerified == true
    // Formatting only — the contact's identity public key is already stored
    // plainly on-device (needed for the ratchet handshake itself), this just
    // hex-encodes it for display instead of hiding it behind a separate
    // screen navigation.
    val contactKeyHex = remember(contact) {
        contact?.publicKey?.joinToString("") { "%02x".format(it) }
    }

    Box(modifier = Modifier.fillMaxSize().glassBackground(mc.listGradient)) {
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            topBar = { GlassTopBar(title = "معلومات جهة الاتصال", onBack = onBackClick) }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Dims.s12)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = Dims.s16, bottom = Dims.s16),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box {
                        Avatar(
                            name = shownName,
                            size = Dims.avatarContactDetail,
                            id = contactId,
                            avatarBytes = avatarBytes
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable { avatarPicker.launch("image/*") },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.CameraAlt,
                                contentDescription = "تغيير الصورة",
                                tint = Color.White,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(Dims.s12))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            shownName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Medium,
                            color = mc.glassOnCard
                        )
                        if (isVerified) {
                            Spacer(Modifier.width(5.dp))
                            Icon(
                                Icons.Default.Verified,
                                contentDescription = "متحقق",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = buildString {
                            append("⁦@").append(displayName.trim().removePrefix("@").ifBlank { contactId.take(8) }).append("⁩")
                            if (nickname.isNotBlank() && nickname != displayName) append(" · اسم محلي: $nickname")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = mc.glassOnCard.copy(alpha = 0.55f),
                        modifier = Modifier.clickable { showNicknameDialog = true }
                    )

                    Spacer(Modifier.height(Dims.s16))

                    // No "اتصال" here anymore. It was `onClick = {}` — a button
                    // that responded to being pressed by doing nothing at all,
                    // which is the worst of the three possible states (works,
                    // says it can't, or silently ignores you). This app has no
                    // calling feature; offering the control anyway made the app
                    // look broken rather than incomplete.
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        QuickAction(icon = Icons.Default.Message, label = "رسالة", onClick = onBackClick)
                        QuickAction(
                            icon = if (isMuted) Icons.Default.NotificationsOff else Icons.Default.Notifications,
                            label = "كتم",
                            onClick = { scope.launch { repository.setContactMuted(contactId, !isMuted); reload() } }
                        )
                    }
                }

                SecurityNumberCard(keyHex = contactKeyHex, onClick = onVerifyClick)

                Spacer(Modifier.height(Dims.s12))

                ConnectivityCard(
                    status = transport,
                    onEditAddress = { showAddressDialog = true }
                )

                Spacer(Modifier.height(Dims.s12))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassCard()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(0.5.dp, mc.divider)
                            .padding(horizontal = Dims.s16, vertical = Dims.s12),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Photo, contentDescription = null, tint = SemanticColors.purple, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(Dims.s12))
                        Text("الوسائط المشتركة", style = MaterialTheme.typography.bodyMedium, color = mc.glassOnCard, modifier = Modifier.weight(1f))
                        Text(mediaMessages.size.toString(), style = MaterialTheme.typography.labelMedium, color = mc.glassOnCard.copy(alpha = 0.55f))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showBlockConfirm = true }
                            .padding(horizontal = Dims.s16, vertical = Dims.s12),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Block, contentDescription = null, tint = SemanticColors.red, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(Dims.s12))
                        Text(
                            if (isBlocked) "إلغاء حظر جهة الاتصال" else "حظر جهة الاتصال",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SemanticColors.red,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(Modifier.height(Dims.s12))

                if (mediaMessages.isEmpty()) {
                    // Words, not three blank tiles.
                    //
                    // This used to render MediaPreviewStrip(emptyList()) — an
                    // empty state that looks exactly like a loaded one that
                    // failed. Photographing the screen showed the result: the
                    // count above says "0" and three grey rectangles sit
                    // underneath it, and a reader cannot tell "there is no
                    // shared media" from "the images did not load".
                    Text(
                        text = "لا وسائط مشتركة بعد",
                        style = MaterialTheme.typography.bodySmall,
                        color = mc.glassOnCard.copy(alpha = 0.5f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Dims.s16),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp),
                        horizontalArrangement = Arrangement.spacedBy(Dims.s8),
                        verticalArrangement = Arrangement.spacedBy(Dims.s8)
                    ) {
                        items(mediaMessages, key = { it.id ?: it.timestamp }) { message ->
                            MediaGridTile(message, repository, onImageClick = { fullscreenImage = it })
                        }
                    }
                }
                Spacer(Modifier.height(Dims.s24))
            }
        }
    }

    if (showNicknameDialog) {
        var draft by remember { mutableStateOf(nickname) }
        AlertDialog(
            onDismissRequest = { showNicknameDialog = false },
            containerColor = mc.glassCardStrong,
            titleContentColor = mc.glassOnCard,
            textContentColor = mc.glassOnCard,
            title = { Text("اسم مستعار محلي") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text(displayName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        repository.setContactNickname(contactId, draft.trim())
                        reload()
                        showNicknameDialog = false
                    }
                }) { Text("حفظ") }
            },
            dismissButton = {
                TextButton(onClick = { showNicknameDialog = false }) { Text("إلغاء") }
            }
        )
    }

    if (showAddressDialog) {
        val client = SecureMessengerApp.instance.messagingClient
        var draft by remember { mutableStateOf(transport?.rememberedAddress ?: "") }
        AlertDialog(
            onDismissRequest = { showAddressDialog = false },
            containerColor = mc.glassCardStrong,
            titleContentColor = mc.glassOnCard,
            textContentColor = mc.glassOnCard,
            title = { Text("عنوان مباشر") },
            text = {
                Column {
                    Text(
                        "يُستعمل فقط إن لم يُعثر على الجهة تلقائياً على الشبكة — وهذا شائع، " +
                            "لأن كثيراً من الراوترات تمنع الاكتشاف التلقائي بين الأجهزة.\n\n" +
                            "اكتب عنوان جهاز الطرف الآخر كما يظهر عنده (مثال: 192.168.1.20:47601). " +
                            "العنوان مجرد دلالة: من يردّ عليه يبقى مضطراً لإثبات هويته بالمفتاح المثبَّت، " +
                            "فعنوان خاطئ يمنع الاتصال ولا يسرّب شيئاً.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(Dims.s12))
                    client?.myDirectAddress()?.let { mine ->
                        Text(
                            "عنوان جهازك أنت: $mine",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(Dims.s8))
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = { Text("192.168.1.20:47601") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        client?.setDirectAddress(contactId, draft.trim().ifBlank { null })
                        transport = client?.transportStatus(contactId)
                        showAddressDialog = false
                    }
                }) { Text("حفظ") }
            },
            dismissButton = {
                TextButton(onClick = { showAddressDialog = false }) { Text("إلغاء") }
            }
        )
    }

    if (showBlockConfirm) {
        AlertDialog(
            onDismissRequest = { showBlockConfirm = false },
            containerColor = mc.glassCardStrong,
            titleContentColor = mc.glassOnCard,
            textContentColor = mc.glassOnCard,
            title = { Text(if (isBlocked) "إلغاء الحظر؟" else "حظر جهة الاتصال؟") },
            text = {
                Text(
                    if (isBlocked) "ستتمكن هذه الجهة من إرسال رسائل إليك مجدداً."
                    else "لن تصلك رسائل جديدة من هذه الجهة حتى تُلغي الحظر."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        if (isBlocked) repository.unblockContact(contactId) else repository.blockContact(contactId)
                        reload()
                        showBlockConfirm = false
                    }
                }) { Text(if (isBlocked) "إلغاء الحظر" else "حظر", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showBlockConfirm = false }) { Text("إلغاء") }
            }
        )
    }

    val shownImage = fullscreenImage
    if (shownImage != null) {
        Dialog(onDismissRequest = { fullscreenImage = null }) {
            Image(
                bitmap = shownImage,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { fullscreenImage = null },
                contentScale = ContentScale.Fit
            )
        }
    }
}

/**
 * Why a message to this contact will or won't get through, said out loud.
 *
 * The app's characteristic failure is a quiet one: the outbox durably holds an
 * undeliverable message and the UI shows a pending clock forever, with nothing
 * distinguishing "they're just offline" from "these two devices have no route
 * to each other at all and never will until you re-pair." The three states need
 * three different actions from the user, so the card names the state and offers
 * the one fix the app can't apply by itself — a direct address.
 */
@Composable
private fun ConnectivityCard(
    status: com.securemessenger.app.network.TransportStatus?,
    onEditAddress: () -> Unit
) {
    val mc = LocalMessengerColors.current
    val tint = when {
        status == null -> mc.glassOnCard.copy(alpha = 0.55f)
        status.isConnected || status.isDiscovered -> SemanticColors.green
        status.hasNoRouteAtAll -> SemanticColors.red
        else -> SemanticColors.orange
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassCard()
            .clickable { onEditAddress() }
            .padding(horizontal = Dims.s16, vertical = Dims.s12)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (status?.isConnected == true || status?.isDiscovered == true) Icons.Default.Wifi
                else Icons.Default.WifiOff,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(Dims.s12))
            Text(
                "حالة الاتصال",
                style = MaterialTheme.typography.bodyMedium,
                color = mc.glassOnCard,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Default.Edit,
                contentDescription = "تعديل العنوان المباشر",
                tint = mc.glassOnCard.copy(alpha = 0.45f),
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.height(Dims.s8))
        Text(
            status?.describe() ?: "جارٍ الفحص…",
            style = MaterialTheme.typography.bodySmall,
            color = tint,
            lineHeight = MaterialTheme.typography.bodySmall.fontSize * 1.6
        )
    }
}

@Composable
private fun QuickAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val mc = LocalMessengerColors.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = mc.glassOnCard.copy(alpha = 0.6f))
    }
}

/** The safety-number card: brand-tinted, monospace fingerprint split across two lines. */
@Composable
private fun SecurityNumberCard(keyHex: String?, onClick: () -> Unit) {
    val mc = LocalMessengerColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .border(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("رقم الأمان", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(8.dp))
        val chunks = keyHex?.chunked(4)?.take(6) ?: listOf("————", "————", "————", "————", "————", "————")
        Text(
            text = chunks.chunked(3).joinToString("\n") { it.joinToString(" ") },
            style = MaterialTheme.typography.bodyMedium.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr),
            fontFamily = FontFamily.Monospace,
            letterSpacing = 2.sp,
            lineHeight = MaterialTheme.typography.bodyMedium.fontSize * 1.7,
            color = mc.glassOnCard.copy(alpha = 0.7f)
        )
    }
}

/** Three equal gradient tiles previewing the most recent shared media. */
@Composable
private fun MediaPreviewStrip(tiles: List<androidx.compose.ui.graphics.ImageBitmap>) {
    val gradients = listOf(
        listOf(Color(0xFF3A4358), Color(0xFF242A38)),
        listOf(Color(0xFF42364F), Color(0xFF28203A)),
        listOf(Color(0xFF2F4A44), Color(0xFF1F3330))
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dims.s8)) {
        gradients.forEach { colors ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(colors))
            )
        }
    }
}
