package com.securemessenger.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Chat list on the left, conversation on the right — the ordinary desktop messenger shape. */
@Composable
fun MainScreen(store: DesktopStore, client: DesktopMessagingClient) {
    var selected by remember { mutableStateOf<String?>(null) }
    var showPairing by remember { mutableStateOf(false) }
    // The store is mutated from network coroutines, so the UI polls a revision
    // counter rather than trying to make every mutation path emit — simpler,
    // and at this message volume the cost is irrelevant.
    var revision by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(700)
            revision++
        }
    }

    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.width(300.dp).fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
        ) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        store.displayName.ifBlank { store.userId.take(8) },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    key(revision) {
                        Text(
                            client.myDirectAddress() ?: "غير متصل بشبكة",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
                IconButton(onClick = { showPairing = true }) {
                    Icon(Icons.Default.Add, contentDescription = "إضافة جهة اتصال")
                }
            }
            Divider()
            key(revision) {
                val contacts = store.allContacts()
                if (contacts.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "لا توجد جهات اتصال بعد.\nاضغط + لعرض رمزك أو قراءة رمز صديق.",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        )
                    }
                } else {
                    LazyColumn {
                        items(contacts, key = { it.id }) { contact ->
                            val isSelected = contact.id == selected
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                        else Color.Transparent
                                    )
                                    .clickable { selected = contact.id }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier.size(38.dp).clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(contact.displayName.take(1).uppercase(), fontWeight = FontWeight.Medium)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(contact.displayName, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        store.messagesWith(contact.id).lastOrNull()?.text?.take(34) ?: "—",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Divider(Modifier.fillMaxHeight().width(1.dp))

        val active = selected
        if (active == null) {
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Text(
                    "اختر محادثة",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        } else {
            ConversationPane(store, client, active, revision, Modifier.weight(1f))
        }
    }

    if (showPairing) {
        PairingDialog(client) { showPairing = false }
    }
}

@Composable
private fun ConversationPane(
    store: DesktopStore,
    client: DesktopMessagingClient,
    contactId: String,
    revision: Int,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var draft by remember(contactId) { mutableStateOf("") }
    var error by remember(contactId) { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var showAddress by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val contact = store.contact(contactId) ?: return
    val messages = key(revision) { store.messagesWith(contactId) }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty() || sending) return
        draft = ""
        error = null
        sending = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { client.sendMessage(contactId, text) }
            sending = false
            // A failed send is not a lost message — it stays in the durable
            // outbox and retries — but the user has to be told *why* nothing is
            // moving, which is the one thing the phone build used to hide.
            if (!ok) error = "تعذّر الإرسال الآن: ${client.describeRoute(contactId)}"
        }
    }

    Column(modifier.fillMaxHeight()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(contact.displayName, style = MaterialTheme.typography.titleMedium)
                key(revision) {
                    Text(
                        client.describeRoute(contactId),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            TextButton(onClick = { showAddress = true }) { Text("عنوان مباشر") }
        }
        Divider()

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = 14.dp)
        ) {
            items(messages) { message ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (message.outgoing) Arrangement.End else Arrangement.Start
                ) {
                    Column(
                        Modifier.widthIn(max = 520.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (message.outgoing) MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .padding(horizontal = 14.dp, vertical = 9.dp)
                    ) {
                        Text(message.text, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            buildString {
                                append(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp)))
                                // Outgoing messages stay marked "جارٍ" until the
                                // recipient's authenticated ack clears the outbox —
                                // so a message that never arrives never looks sent.
                                if (message.outgoing) append(if (message.delivered) " ✓" else " · جارٍ")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        )
                    }
                }
            }
        }

        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("اكتب رسالة…") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() })
            )
            Spacer(Modifier.width(10.dp))
            FilledIconButton(onClick = { send() }, enabled = draft.isNotBlank() && !sending) {
                Icon(Icons.Default.Send, contentDescription = "إرسال")
            }
        }
    }

    if (showAddress) {
        var draftAddress by remember { mutableStateOf(contact.directAddress ?: "") }
        AlertDialog(
            onDismissRequest = { showAddress = false },
            title = { Text("عنوان مباشر") },
            text = {
                Column {
                    Text(
                        "يُستعمل عندما لا يُعثر على الجهة تلقائياً على الشبكة — وهذا شائع، " +
                            "لأن كثيراً من الراوترات تمنع الاكتشاف التلقائي بين الأجهزة.\n\n" +
                            "العنوان دلالة فقط: من يردّ عليه يبقى مضطراً لإثبات هويته بالمفتاح المثبَّت.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(10.dp))
                    client.myDirectAddress()?.let {
                        Text(
                            "عنوان هذا الكمبيوتر: $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    OutlinedTextField(
                        value = draftAddress,
                        onValueChange = { draftAddress = it },
                        placeholder = { Text("192.168.1.20:47601") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    client.setDirectAddress(contactId, draftAddress.trim().ifBlank { null })
                    showAddress = false
                }) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { showAddress = false }) { Text("إلغاء") } }
        )
    }
}

/**
 * Pairing, in both directions.
 *
 * Showing a code the phone's camera can read is the easy half. The other half
 * is harder on a PC with no camera, so this accepts the phone's code either as
 * an image file (the Android app can share it as a picture) or pasted as text.
 */
@Composable
private fun PairingDialog(client: DesktopMessagingClient, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val payload = remember { client.myQrPayload() }
    val qr = remember(payload) { runCatching { QrCodec.render(payload, 520) }.getOrNull() }
    var pasted by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    fun consume(text: String) {
        client.pairFromPayload(text)
            .onSuccess { message = "تمت إضافة جهة الاتصال ✓" }
            .onFailure { message = "رمز غير صالح: ${it.message}" }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("إضافة جهة اتصال") },
        text = {
            Column(Modifier.width(560.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "١) امسح هذا الرمز بكاميرا هاتفك من شاشة «محادثة جديدة».",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(10.dp))
                if (qr != null) {
                    Image(
                        bitmap = qr,
                        contentDescription = "رمز الاقتران",
                        modifier = Modifier.size(240.dp).clip(RoundedCornerShape(10.dp))
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "٢) الاقتران متبادل: لا بدّ أن يقرأ هذا الكمبيوتر رمزَ الهاتف أيضاً، " +
                        "وإلا سار الاتجاه الواحد فقط. من الهاتف: «مشاركة رمزي كصورة» ثم افتح الصورة هنا.",
                    style = MaterialTheme.typography.bodySmall
                )

                // A PC commonly holds several private addresses at once (Wi-Fi,
                // a hotspot it is serving, a VPN tunnel). Only one of them is
                // reachable from the phone, and picking wrong is invisible —
                // everything "works" and nothing arrives. So show them all
                // rather than silently committing to the top guess.
                val addresses = remember { com.securemessenger.core.net.LanAddress.candidates() }
                if (addresses.size > 1) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "لهذا الكمبيوتر أكثر من عنوان. إن لم ينجح الاكتشاف التلقائي، " +
                            "جرّب في الهاتف العنوان الذي يشترك مع شبكته (الأول هو الأرجح):",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(4.dp))
                    addresses.forEach { address ->
                        Text(
                            "• $address:${client.boundPort}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        scope.launch {
                            val picked = withContext(Dispatchers.IO) { pickImageFile() }
                            if (picked == null) return@launch
                            val decoded = withContext(Dispatchers.IO) { QrCodec.decodeFile(picked) }
                            if (decoded == null) message = "لم يُعثر على رمز QR في هذه الصورة"
                            else consume(decoded)
                        }
                    }) { Text("فتح صورة الرمز") }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pasted,
                    onValueChange = { pasted = it },
                    label = { Text("أو ألصق نصّ الرمز هنا") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { consume(pasted.trim()) },
                    enabled = pasted.isNotBlank()
                ) { Text("إضافة من النص") }

                message?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("إغلاق") } }
    )
}

/** AWT's native file dialog — Compose Desktop has no built-in picker. */
private fun pickImageFile(): File? {
    val dialog = FileDialog(null as Frame?, "اختر صورة رمز QR", FileDialog.LOAD)
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val name = dialog.file ?: return null
    return File(dir, name)
}
