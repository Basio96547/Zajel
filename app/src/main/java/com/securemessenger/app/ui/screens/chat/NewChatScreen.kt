package com.securemessenger.app.ui.screens.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
// Deliberately PhotoLibrary rather than Image: the icon name Image would clash
// with the foundation Image composable already imported here.
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.securemessenger.app.BuildConfig
import com.securemessenger.app.SecureMessengerApp
import com.securemessenger.core.crypto.MailboxToken
import com.securemessenger.app.security.AppSettings
import com.securemessenger.app.ui.GlassTopBar
import com.securemessenger.app.ui.glassCard
import com.securemessenger.app.ui.onboardingBackground
import com.securemessenger.app.ui.theme.LocalMessengerColors
import com.securemessenger.app.ui.viewmodel.ConnectionRequestsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * No directory server to search anymore — adding someone is an in-person
 * action: show them your QR (or scan theirs) while you're physically
 * together, exactly the "meet and pair" model this local-only design needs
 * instead of "search a global username."
 */
@Composable
fun NewChatScreen(
    /** Null on the tab root — a root has nowhere to go back to. */
    onBackClick: (() -> Unit)? = null,
    onContactAdded: (String) -> Unit,
    onSearchByUsernameClick: () -> Unit = {},
    onConnectionRequestsClick: () -> Unit = {},
    connectionRequestsViewModel: ConnectionRequestsViewModel = viewModel()
) {
    val repository = remember { SecureMessengerApp.instance.repository }
    val mc = LocalMessengerColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pendingRequests by connectionRequestsViewModel.incomingRequests.collectAsState()
    val clipboard = LocalClipboardManager.current
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // Neutral confirmation (e.g. "تم النسخ") — kept separate from errorMessage,
    // which always renders in the error color; reusing it for good news would
    // make a successful copy look like a failure.
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var myUsername by remember { mutableStateOf("") }
    var showShareDialog by remember { mutableStateOf(false) }
    // Copy-as-text pairing: for a peer with no camera to scan a QR at all
    // (a desktop/laptop, most commonly) — the desktop app already has a
    // "paste the code's text" field for exactly this. Sharing an IMAGE across
    // devices needs some file-transfer channel (cloud, email, USB); plain
    // text can go through literally anything, including a note to yourself.
    var showCopyDialog by remember { mutableStateOf(false) }
    // Set when pairing was refused because the scanned key differs from one
    // already pinned for this contact — holds the raw scan so "تابع على أي
    // حال" can retry it with explicit confirmation. See
    // SecureRepository.addContactWithPublicKey / ContactPairResult.
    var pendingKeyChangeScan by remember { mutableStateOf<String?>(null) }

    var myQrPayload by remember { mutableStateOf<String?>(null) }
    // Kept so the share/copy paths can tell AppSettings that *this* secret has
    // left the device — see markPairSecretExported. It used to be a local
    // inside the effect below, which is why nothing downstream could know the
    // difference between a code drawn on screen and one mailed to someone.
    var myPairSecretHex by remember { mutableStateOf<String?>(null) }
    // "Finished" rather than "succeeded" — the same distinction the key
    // verification screen had to learn. These reads return null rather than
    // throwing when there is no profile to read, so a spinner keyed on
    // "payload is still null" spins forever and tells the user a load is in
    // progress that already ended. This screen is a tab root now, so it is far
    // easier to land on in that state than it was as a pushed screen.
    var qrLoadAttempted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val userId = try { repository.getUserId() } catch (e: Exception) { null }
        val publicKeyHex = try { repository.getPublicKeyHex() } catch (e: Exception) { null }
        if (userId != null && publicKeyHex != null) {
            val username = AppSettings.getUsername(context)
            myUsername = username ?: userId.take(8)
            // A fresh pair secret per displayed QR. It addresses this pair's
            // mailboxes on the blind relay and reaches the other device by
            // being photographed, never by crossing a network — so the relay
            // can never compute which mailboxes belong to one conversation.
            // Held as "pending" until whoever scans it identifies themselves in
            // the first message they drop into it.
            val pairSecretHex = MailboxToken.pairSecretToHex(MailboxToken.newPairSecret())
            AppSettings.addPendingPairSecret(context, pairSecretHex)
            myPairSecretHex = pairSecretHex
            SecureMessengerApp.instance.messagingClient?.refreshRelaySubscriptions()
            myQrPayload = QrImage.identityPayload(
                userId = userId,
                publicKeyHex = publicKeyHex,
                username = myUsername,
                // Where this device is listening right now. Lets the scanner
                // reach us even on a network that blocks the multicast mDNS
                // discovery depends on — which is common enough on consumer
                // routers that pairing "succeeding" and then silently never
                // delivering was the likeliest way this app failed.
                directAddress = SecureMessengerApp.instance.messagingClient?.myDirectAddress(),
                pairSecretHex = pairSecretHex
            )
        }
        qrLoadAttempted = true
    }
    val myQrBitmap = remember(myQrPayload) { myQrPayload?.let { QrImage.render(it) } }

    // Both entry points — camera and picked image — end here, so a code read
    // from a picture pairs on exactly the same path as one read off a screen.
    suspend fun pairFromPayload(scanned: String, allowKeyChange: Boolean = false) {
        isLoading = true
        errorMessage = null
        try {
            val json = JSONObject(scanned)
            val scannedUserId = json.getString("u")
            val identityKeyHex = json.getString("k")
            val displayName = json.optString("n").ifBlank { scannedUserId.take(8) }
            // Absent on a code from an older build, and on one shared with the
            // relay secret deliberately stripped — either way that contact
            // pairs fine, it just stays local-network-only.
            val pairSecretHex = json.optString("s").takeIf { it.isNotBlank() }
            // Their listening address at the moment they drew the code. Absent
            // on codes from older builds — those still pair, they just rely on
            // mDNS finding them.
            val directAddress = json.optString("a").takeIf { it.isNotBlank() }
            val client = SecureMessengerApp.instance.messagingClient
            if (client == null) {
                errorMessage = "المراسل غير مُفعّل الآن — أعد المحاولة"
                return
            }
            val result = client.pairWithScannedContact(
                scannedUserId, identityKeyHex, displayName, pairSecretHex, directAddress,
                allowKeyChange = allowKeyChange
            )
            when (result) {
                com.securemessenger.app.data.repository.ContactPairResult.ADDED,
                com.securemessenger.app.data.repository.ContactPairResult.UNCHANGED ->
                    onContactAdded(scannedUserId)
                com.securemessenger.app.data.repository.ContactPairResult.KEY_CHANGED ->
                    // Don't scan-loop or show an error — surface the warning
                    // dialog instead and let the user decide. See
                    // SecureRepository.addContactWithPublicKey.
                    pendingKeyChangeScan = scanned
                null -> errorMessage = "تعذّر إضافة جهة الاتصال — تأكد أن الرمز صحيح"
            }
        } catch (e: Exception) {
            errorMessage = "رمز QR غير صالح لهذا التطبيق"
        } finally {
            isLoading = false
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val scanned = result.contents ?: return@rememberLauncherForActivityResult
        scope.launch { pairFromPayload(scanned) }
    }

    // Reading the code out of a saved picture instead of through the lens. This
    // is the only way to pair two instances living on the same phone, where no
    // camera can ever see the other one's screen.
    val imagePickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isLoading = true
            errorMessage = null
            val payload = withContext(Dispatchers.IO) { QrImage.decodeFromImage(context, uri) }
            isLoading = false
            if (payload == null) errorMessage = "لم يُعثر على رمز QR في هذه الصورة"
            else pairFromPayload(payload)
        }
    }

    Box(modifier = Modifier.fillMaxSize().onboardingBackground(mc.onboardingGradient)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // "جهات الاتصال", not "محادثة جديدة".
            //
            // This one screen was being called three different things — the
            // home screen's floating button said "محادثة جديدة", the profile
            // screen's row said "عرض رمز QR", and the code's own comments
            // called it "جهات الاتصال". It is the place your code lives and
            // where you scan someone else's, so it is named after that and
            // reached from one control: its tab.
            GlassTopBar(title = "جهات الاتصال", onBack = onBackClick)

            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.White)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (myQrBitmap != null) {
                        Image(
                            bitmap = myQrBitmap.asImageBitmap(),
                            contentDescription = "رمزي",
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if (!qrLoadAttempted) {
                        CircularProgressIndicator()
                    } else {
                        // Read finished, nothing to encode. A spinner here
                        // would claim a load is still running that has already
                        // ended, which is the slowest possible way to lie.
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            modifier = Modifier.size(44.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
                // An empty username rendered as a bare "@" — a label with
                // nothing to label.
                if (myUsername.isNotBlank()) {
                    Text(
                        text = "⁦@$myUsername⁩",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = mc.glassOnCard
                    )
                } else if (qrLoadAttempted) {
                    Text(
                        text = "تعذّر قراءة هويتك",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
                // Conditional, because the flat claim stopped being true.
                //
                // This read "لا يوجد دليل مركزي — الإضافة عبر QR فقط"
                // unconditionally, while the username-search entry further
                // down this same screen appears whenever DIRECTORY_URL is
                // compiled in. A build with the directory enabled therefore
                // denied having a directory and then offered one, on one
                // screen, a few hundred pixels apart.
                //
                // In an app whose entire proposition is that it tells you the
                // truth about who can reach you, that is not a copy nit.
                Text(
                    text = if (BuildConfig.DIRECTORY_URL.isNotBlank()) {
                        "شارك رمزك مع صديق ليضيفك.\nأو ابحث باسم المستخدم أدناه — لا تبدأ محادثة إلا بموافقة الطرفين."
                    } else {
                        "شارك رمزك مع صديق ليضيفك.\nلا يوجد دليل مركزي — الإضافة عبر QR فقط."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = mc.glassOnCard.copy(alpha = 0.65f),
                    textAlign = TextAlign.Center,
                    lineHeight = MaterialTheme.typography.bodyMedium.fontSize * 1.7
                )
                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
                if (statusMessage != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = statusMessage!!,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Column(
                // Clears the floating tab bar this screen now sits under.
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(enabled = !isLoading) {
                            scanLauncher.launch(
                                ScanOptions()
                                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                    .setPrompt("وجّه الكاميرا نحو رمز صديقك")
                                    .setBeepEnabled(false)
                            )
                        }
                        .padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("مسح رمز صديق", color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassCard()
                        .clickable(enabled = !isLoading) { imagePickLauncher.launch("image/*") }
                        .padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = mc.glassOnCard, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("اختيار رمز من الصور", color = mc.glassOnCard, style = MaterialTheme.typography.titleSmall)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassCard()
                        .clickable(enabled = myQrPayload != null) { showShareDialog = true }
                        .padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = mc.glassOnCard, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("مشاركة رمزي كصورة", color = mc.glassOnCard, style = MaterialTheme.typography.titleSmall)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassCard()
                        .clickable(enabled = myQrPayload != null) { showCopyDialog = true }
                        .padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, tint = mc.glassOnCard, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("نسخ رمزي كنص (لجهاز بلا كاميرا)", color = mc.glassOnCard, style = MaterialTheme.typography.titleSmall)
                }

                // Additive second path alongside QR above, not a replacement —
                // hidden entirely (not shown disabled) when this build carries
                // no directory service at all, same convention BuildConfig.RELAY_URL
                // already uses for the relay-dependent parts of this screen.
                if (BuildConfig.DIRECTORY_URL.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glassCard()
                            .clickable { onSearchByUsernameClick() }
                            .padding(vertical = 14.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.PersonSearch, contentDescription = null, tint = mc.glassOnCard, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("البحث باسم المستخدم", color = mc.glassOnCard, style = MaterialTheme.typography.titleSmall)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glassCard()
                            .clickable { onConnectionRequestsClick() }
                            .padding(vertical = 14.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.MailOutline, contentDescription = null, tint = mc.glassOnCard, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (pendingRequests.isEmpty()) "طلبات الاتصال الواردة"
                            else "طلبات الاتصال الواردة (${pendingRequests.size})",
                            color = mc.glassOnCard,
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                }
            }
        }
    }

    if (showShareDialog) {
        val payload = myQrPayload
        AlertDialog(
            onDismissRequest = { showShareDialog = false },
            title = { Text("مشاركة الباركود") },
            text = {
                Text(
                    "الرمز الكامل يحتوي سرّ الاقتران الذي يتيح المراسلة خارج الشبكة المحلية. " +
                        "إرساله عبر تطبيق آخر يُخرج هذا السرّ من جهازك: من يعترض الصورة يستطيع " +
                        "تعطيل استقبالك ورؤية توقيت الرسائل — لكنه لا يستطيع قراءة محتواها.\n\n" +
                        "يبقى الرمز المُشارَك صالحاً ${AppSettings.EXPORTED_SECRET_VALIDITY_DAYS} أيام؛ " +
                        "بعدها يحتاج من يمسحه رمزاً جديداً منك.\n\n" +
                        "إن لم تكن القناة موثوقة، شارك النسخة المحلية فقط."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = payload != null,
                    onClick = {
                        showShareDialog = false
                        // Before the share, not after: this is the moment the
                        // code stops being a disposable on-screen one, and it
                        // must survive the next few visits to this screen.
                        myPairSecretHex?.let { AppSettings.markPairSecretExported(context, it) }
                        if (payload != null && !QrImage.share(context, payload, "أضفني: @$myUsername")) {
                            errorMessage = "تعذّرت مشاركة الصورة"
                        }
                    }
                ) { Text("الرمز الكامل") }
            },
            dismissButton = {
                TextButton(
                    enabled = payload != null,
                    onClick = {
                        showShareDialog = false
                        val localOnly = payload?.let { QrImage.stripRelaySecret(it) }
                        if (localOnly != null && !QrImage.share(context, localOnly, "أضفني: @$myUsername")) {
                            errorMessage = "تعذّرت مشاركة الصورة"
                        }
                    }
                ) { Text("محلي فقط (آمن)") }
            }
        )
    }

    if (showCopyDialog) {
        val payload = myQrPayload
        AlertDialog(
            onDismissRequest = { showCopyDialog = false },
            title = { Text("نسخ الرمز كنص") },
            text = {
                Text(
                    "الصق هذا النص في تطبيق سطح المكتب (حقل «ألصق نصّ الرمز هنا»)، أو في أي " +
                        "قناة أخرى تصل بها إلى الجهاز الآخر — لا حاجة لكاميرا أو نقل صورة.\n\n" +
                        "الرمز الكامل يحتوي سرّ الاقتران الذي يتيح المراسلة خارج الشبكة المحلية؛ " +
                        "من يطّلع عليه يستطيع تعطيل استقبالك ورؤية توقيت الرسائل — لكنه لا يقدر " +
                        "على قراءة محتواها. يبقى صالحاً ${AppSettings.EXPORTED_SECRET_VALIDITY_DAYS} أيام. " +
                        "إن كنتما على نفس الشبكة المحلية الآن، النسخة المحلية " +
                        "تكفي ولا تُخرج أي سرّ من جهازك."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = payload != null,
                    onClick = {
                        payload?.let { clipboard.setText(AnnotatedString(it)) }
                        myPairSecretHex?.let { AppSettings.markPairSecretExported(context, it) }
                        statusMessage = "نُسخ الرمز الكامل — الصقه في الجهاز الآخر"
                        showCopyDialog = false
                    }
                ) { Text("نسخ الكامل") }
            },
            dismissButton = {
                TextButton(
                    enabled = payload != null,
                    onClick = {
                        val localOnly = payload?.let { QrImage.stripRelaySecret(it) }
                        localOnly?.let { clipboard.setText(AnnotatedString(it)) }
                        statusMessage = "نُسخ الرمز المحلي — الصقه في الجهاز الآخر"
                        showCopyDialog = false
                    }
                ) { Text("محلي فقط (آمن)") }
            }
        )
    }

    pendingKeyChangeScan?.let { rawScan ->
        val scannedUserId = remember(rawScan) {
            runCatching { JSONObject(rawScan).optString("u") }.getOrDefault("")
        }
        AlertDialog(
            onDismissRequest = { pendingKeyChangeScan = null },
            icon = { Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("مفتاح الأمان تغيّر") },
            text = {
                Text(
                    "مفتاح أمان جهة الاتصال" +
                        (scannedUserId.takeIf { it.isNotBlank() }?.let { " \"${it.take(8)}\"" } ?: "") +
                        " مختلف عن المفتاح المحفوظ لديك من قبل.\n\n" +
                        "قد يعني هذا أنّهم أعادوا تثبيت التطبيق على جهاز جديد — أو أنّ شخصاً آخر يحاول " +
                        "انتحال شخصيتهم. تابع فقط إن كنت متأكداً من هوية الطرف الآخر الآن، ويُفضّل " +
                        "التحقق معهم مباشرة قبل المتابعة."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val raw = pendingKeyChangeScan
                    pendingKeyChangeScan = null
                    if (raw != null) scope.launch { pairFromPayload(raw, allowKeyChange = true) }
                }) {
                    Text("تابع على أي حال", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingKeyChangeScan = null }) { Text("إلغاء") }
            }
        )
    }
}

