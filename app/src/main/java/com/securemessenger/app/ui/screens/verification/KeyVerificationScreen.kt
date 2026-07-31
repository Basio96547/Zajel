package com.securemessenger.app.ui.screens.verification

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.securemessenger.app.SecureMessengerApp
import com.securemessenger.app.ui.GlassTopBar
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

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val scanned = result.contents
        if (scanned != null && contactId != null) {
            scope.launch {
                verificationStatus = if (repository.verifyScannedKey(contactId, scanned)) {
                    VerificationStatus.Verified
                } else {
                    VerificationStatus.Failed
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

    LaunchedEffect(Unit) {
        myPublicKeyHex = repository.getPublicKeyHex()
        fingerprint = repository.getPublicKeyFingerprint()
        myUserId = repository.getUserId()
    }

    val qrData = myPublicKeyHex ?: ""
    val myQRBitmap = remember(qrData) {
        if (qrData.isNotBlank()) generateQRCode(qrData) else null
    }

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
                                contentDescription = "رمز التحقق",
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            CircularProgressIndicator()
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(170.dp)
                            .glassCard(radius = 22.dp, strong = true)
                            .clickable(enabled = contactId != null) { launchScan() },
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
                                text = if (contactId != null) "اضغط للمسح" else "اختر جهة اتصال أولاً",
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
                    else -> Text(
                        text = if (showMyQR) "شارك رمز QR مع جهة اتصالك للتحقق"
                        else "امسح رمز جهة اتصالك — يُقارَن تلقائياً برقم أمانها الفعلي",
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
                            text = fingerprint?.chunked(2)?.joinToString(" ") ?: "جاري التحميل...",
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
                                    text = myUserId ?: "جاري التحميل...",
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
                                    text = myPublicKeyHex ?: "جاري التحميل...",
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
                        .clickable(enabled = contactId != null) { showMyQR = false; launchScan() }
                        .padding(vertical = 13.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("مسح رمز جهة الاتصال", color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
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
