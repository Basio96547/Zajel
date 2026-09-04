package com.securemessenger.app.ui.screens.verification

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.securemessenger.app.SecureMessengerApp
import com.securemessenger.app.crypto.AndroidKeyStoreManager
import com.securemessenger.app.data.repository.KeyScanResult
import com.securemessenger.app.security.AppSettings
import com.securemessenger.app.ui.GlassTopBar
import com.securemessenger.app.ui.formatContactName
import com.securemessenger.app.ui.screens.chat.QrImage
import com.securemessenger.app.ui.glassBackground
import com.securemessenger.app.ui.glassCard
import com.securemessenger.app.ui.theme.LocalMessengerColors
import com.securemessenger.app.ui.theme.SemanticColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyVerificationScreen(
    contactId: String?,
    onBackClick: () -> Unit
) {
    var showMyQR by remember { mutableStateOf(true) }
    var verificationStatus by remember { mutableStateOf<VerificationStatus>(VerificationStatus.None) }
    var myPublicKeyHex by remember { mutableStateOf<String?>(null) }
    var fingerprint by remember { mutableStateOf<String?>(null) }
    var myUserId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val repository = remember { SecureMessengerApp.instance.repository }
    val mc = LocalMessengerColors.current
    val context = LocalContext.current

    // Who is being verified.
    //
    // Reached from a conversation or from contact details, that is the
    // contactId argument and this screen has always worked. Reached from
    // Settings → "التحقق من المفاتيح" it is null, and the screen was a dead
    // end: it showed your own code, disabled the scan tile, and said "اختر
    // جهة اتصال أولاً" — an instruction with nothing on the screen able to
    // carry it out. An entry point in the security section that can only ever
    // perform half of its one job is worse than no entry point, because the
    // user goes looking for the other half.
    //
    // So the choice happens here now: with no contact given, the scan control
    // opens the contact list and verification proceeds against whoever is
    // picked. The argument still wins when there is one — arriving from a
    // conversation must never ask you which conversation.
    var chosenContactId by remember { mutableStateOf(contactId) }
    var chosenContactName by remember { mutableStateOf<String?>(null) }
    var contacts by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var contactsLoaded by remember { mutableStateOf(false) }
    var showContactPicker by remember { mutableStateOf(false) }

    // Guarded like every other read on this screen: the repository throws
    // while its database is closed, and an exception out of a LaunchedEffect
    // is not a state a composable recovers from.
    LaunchedEffect(Unit) {
        try {
            contacts = repository.getAllContactsOnce().map { contact ->
                val name = try {
                    String(
                        AndroidKeyStoreManager.decryptWithMasterKey(contact.displayNameEncrypted),
                        Charsets.UTF_8
                    )
                } catch (e: Exception) {
                    contact.id.take(8)
                }
                contact.id to formatContactName(name)
            }
        } catch (e: Exception) {
            contacts = emptyList()
        } finally {
            contactsLoaded = true
        }
    }

    // The name of whoever is being verified, once the list is in — including
    // the case where the id came in as an argument, which this screen used to
    // never name at all.
    LaunchedEffect(chosenContactId, contacts) {
        val id = chosenContactId
        chosenContactName = if (id == null) null
        else contacts.firstOrNull { it.first == id }?.second ?: id.take(8)
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val scanned = result.contents
        val target = chosenContactId
        if (scanned != null && target != null) {
            scope.launch {
                verificationStatus = when (repository.verifyScannedKey(target, scanned)) {
                    KeyScanResult.MATCH -> VerificationStatus.Verified
                    KeyScanResult.MISMATCH -> VerificationStatus.Failed
                    KeyScanResult.NOT_A_KEY -> VerificationStatus.NotAKey
                }
            }
        }
    }

    fun launchScan() {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("وجّه الكاميرا نحو رمز QR الخاص بجهة الاتصال")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
        )
    }

    /** Scan straight away when we know the target, otherwise ask who first. */
    fun scanOrChooseFirst() {
        if (chosenContactId != null) {
            showMyQR = false
            launchScan()
        } else {
            showContactPicker = true
        }
    }

    // Every field on this screen fell back to "جاري التحميل…" with nothing to
    // ever replace it: these three reads were unguarded, so a repository that
    // is not open does not merely leave the screen loading forever — it
    // throws out of a LaunchedEffect, which is not a state this composable
    // can recover from. Now it fails visibly and says so.
    // "Finished" is the state that matters, not "threw".
    //
    // The first version of this guard only caught exceptions, and the
    // photograph showed why that was not enough: these reads return null
    // rather than throwing when there is nothing to read, so every field sat
    // on "جاري التحميل…" forever with loadFailed still false. A screen that
    // has finished loading and has nothing is not loading — it failed, and
    // should say so.
    var loadAttempted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            myPublicKeyHex = repository.getPublicKeyHex()
            fingerprint = repository.getPublicKeyFingerprint()
            myUserId = repository.getUserId()
        } catch (e: Exception) {
            // Leave the fields null; the label below reads that as failure.
        } finally {
            loadAttempted = true
        }
    }
    val pendingLabel = if (loadAttempted) "تعذّر قراءة مفاتيحك" else "جاري التحميل..."
    var myUsername by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        myUsername = try {
            AppSettings.getUsername(context)
        } catch (e: Exception) {
            null
        }
    }
    // Scanning needs a target: either one was handed to us, or there is a
    // list to pick one from.
    val canScan = chosenContactId != null || contacts.isNotEmpty()

    // The same code the pairing screen draws — not a second, different one.
    //
    // This used to render the bare hex identity key through its own encoder,
    // producing a QR that only this screen could read, while the app called
    // both it and the pairing code "رمز QR". Now there is one payload
    // (QrImage.identityPayload) and one renderer, so "أرني رمزك" has a single
    // answer no matter which screen either person is looking at.
    //
    // Built with no pair secret: this screen shows your identity to someone
    // who is already a contact, so there is nothing to mint and no reason to
    // hand out relay access to prove who you are.
    val qrPayload = remember(myUserId, myPublicKeyHex, myUsername) {
        val id = myUserId
        val key = myPublicKeyHex
        if (id.isNullOrBlank() || key.isNullOrBlank()) null
        else QrImage.identityPayload(
            userId = id,
            publicKeyHex = key,
            username = myUsername ?: id.take(8)
        )
    }
    val myQRBitmap = remember(qrPayload) { qrPayload?.let { QrImage.render(it) } }

    Box(modifier = Modifier.fillMaxSize().glassBackground(mc.listGradient)) {
        Column(modifier = Modifier.fillMaxSize()) {
            GlassTopBar(title = "التحقق من المفاتيح", onBack = onBackClick)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (showMyQR) {
                    Box(
                        modifier = Modifier
                            .size(170.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(Color.White)
                            .padding(14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (myQRBitmap != null) {
                            Image(
                                bitmap = myQRBitmap.asImageBitmap(),
                                contentDescription = "رمزي",
                                modifier = Modifier.fillMaxSize()
                            )
                        } else if (!loadAttempted) {
                            CircularProgressIndicator()
                        } else {
                            // A spinner that never stops is a lie told slowly.
                            // Once the read has been attempted and produced no
                            // key, there is no code to draw and no more
                            // spinning to do.
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = SemanticColors.red
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(170.dp)
                            .glassCard(radius = 22.dp, strong = true)
                            .clickable(enabled = canScan) { scanOrChooseFirst() },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = mc.glassOnCard.copy(alpha = 0.7f)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = when {
                                    chosenContactId != null -> "اضغط للمسح"
                                    contacts.isNotEmpty() -> "اضغط لاختيار جهة الاتصال"
                                    contactsLoaded -> "لا توجد جهات اتصال بعد"
                                    else -> "جاري التحميل..."
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = mc.glassOnCard.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                when (verificationStatus) {
                    is VerificationStatus.Verified -> StatusPill(
                        icon = Icons.Default.CheckCircle,
                        tint = SemanticColors.green,
                        title = "المفاتيح متطابقة",
                        subtitle = "هويّة جهة الاتصال موثّقة ✓"
                    )
                    is VerificationStatus.Failed -> StatusPill(
                        icon = Icons.Default.Error,
                        tint = SemanticColors.red,
                        title = "فشل التحقق",
                        subtitle = "المفاتيح غير متطابقة — قد يكون هناك تنصت"
                    )
                    // Deliberately not red, and deliberately not the word
                    // "فشل": nothing failed and nobody is being impersonated.
                    // The app just got handed a QR it doesn't own.
                    is VerificationStatus.NotAKey -> StatusPill(
                        icon = Icons.Default.Info,
                        tint = SemanticColors.amber,
                        title = "هذا ليس رمز تحقّق",
                        subtitle = "الرمز الممسوح لا يحتوي مفتاح هوية — اطلب رمز التحقق ثم أعد المحاولة"
                    )
                    // Naming the target, which this screen never did: with a
                    // contactId it silently assumed you knew, and without one
                    // there was nobody to name.
                    else -> Text(
                        text = when {
                            showMyQR -> "شارك رمز QR مع جهة اتصالك للتحقق"
                            chosenContactName != null ->
                                "امسح رمز «$chosenContactName» — يُقارَن تلقائياً برقم أمانها الفعلي"
                            else -> "اختر جهة الاتصال التي تريد التحقق منها"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = mc.glassOnCard.copy(alpha = 0.6f)
                    )
                }

                if (showMyQR) {
                    Spacer(Modifier.height(16.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glassCard()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "بصمة المفتاح",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = fingerprint?.chunked(2)?.joinToString(" ") ?: pendingLabel,
                            style = MaterialTheme.typography.bodyMedium.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr),
                            color = mc.glassOnCard,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Identity to share with a contact so they can add you and
                    // start an encrypted conversation (used by "محادثة جديدة").
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glassCard()
                    ) {
                        SelectionContainer {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "شارك هذه المعلومات مع جهة اتصالك",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "معرّف المستخدم",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = mc.glassOnCard.copy(alpha = 0.65f)
                                )
                                Text(
                                    text = myUserId ?: pendingLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = mc.glassOnCard,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "المفتاح العام (64 حرف)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = mc.glassOnCard.copy(alpha = 0.65f)
                                )
                                Text(
                                    text = myPublicKeyHex ?: pendingLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = mc.glassOnCard,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 26.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(enabled = canScan) { scanOrChooseFirst() }
                        .padding(vertical = 13.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = chosenContactName?.let { "مسح رمز «$it»" } ?: "مسح رمز جهة الاتصال",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                // Only offered when the target wasn't fixed by the caller:
                // arriving from a conversation, "who" is not a question.
                if (contactId == null && chosenContactId != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glassCard(radius = 14.dp)
                            .clickable { showContactPicker = true }
                            .padding(vertical = 13.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text("تغيير جهة الاتصال", color = mc.glassOnCard, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassCard(radius = 14.dp)
                        .clickable { showMyQR = true }
                        .padding(vertical = 13.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text("عرض رمزي", color = mc.glassOnCard, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    if (showContactPicker) {
        AlertDialog(
            onDismissRequest = { showContactPicker = false },
            containerColor = mc.glassCardStrong,
            titleContentColor = mc.glassOnCard,
            textContentColor = mc.glassOnCard,
            title = { Text("التحقق من مَن؟") },
            text = {
                if (contacts.isEmpty()) {
                    Text("لا توجد جهات اتصال بعد — أضف جهة اتصال عبر رمز QR أولاً.")
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(contacts, key = { it.first }) { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        chosenContactId = entry.first
                                        // A verdict belongs to the contact it
                                        // was reached for. Carrying "موثّق"
                                        // across to a different one would be a
                                        // lie about the single most important
                                        // thing this screen ever says.
                                        verificationStatus = VerificationStatus.None
                                        showContactPicker = false
                                        showMyQR = false
                                        launchScan()
                                    }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = mc.glassOnCard.copy(alpha = 0.6f),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = entry.second,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = mc.glassOnCard,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showContactPicker = false }) { Text("إلغاء") }
            }
        )
    }
}

@Composable
private fun StatusPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(tint.copy(alpha = 0.12f))
            .border(0.5.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = tint)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = tint.copy(alpha = 0.8f))
        }
    }
}
