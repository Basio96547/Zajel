package com.securemessenger.app.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.securemessenger.app.SecureMessengerApp
import com.securemessenger.app.security.AppSettings
import com.securemessenger.app.ui.GlassTopBar
import com.securemessenger.app.ui.glassBackground
import com.securemessenger.app.ui.glassCard
import com.securemessenger.app.ui.screens.chat.decodeSampledBitmap
import com.securemessenger.app.ui.theme.Dims
import com.securemessenger.app.ui.theme.LocalMessengerColors
import com.securemessenger.app.ui.theme.SemanticColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * My own profile: photo, display name (local-only — never synced to
 * contacts, who always just see my username), username, and the security
 * number contacts can verify me against.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onBackClick: () -> Unit,
    onVerifyClick: () -> Unit,
    onShowQrClick: () -> Unit = {}
) {
    val repository = remember { SecureMessengerApp.instance.repository }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val mc = LocalMessengerColors.current

    var avatarBytes by remember { mutableStateOf<ByteArray?>(null) }
    var displayName by remember { mutableStateOf("") }
    var username by remember { mutableStateOf(AppSettings.getUsername(context) ?: "") }
    var fingerprint by remember { mutableStateOf<String?>(null) }
    var showNameDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        repository.getMyAvatarFlow().collect { avatarBytes = it }
    }
    LaunchedEffect(Unit) {
        displayName = repository.getMyDisplayName() ?: ""
        fingerprint = repository.getPublicKeyFingerprint()
    }

    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
            if (bytes != null) {
                repository.setMyAvatar(bytes)
                avatarBytes = bytes
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().glassBackground(mc.listGradient)) {
        Column(modifier = Modifier.fillMaxSize()) {
            GlassTopBar(
                title = "ملفي الشخصي",
                onBack = onBackClick,
                actions = {
                    IconButton(onClick = { showNameDialog = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "تعديل الاسم", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box {
                    val initials = remember(displayName, username) {
                        val name = displayName.ifBlank { username.ifBlank { "?" } }
                        val parts = name.trim().removePrefix("@").split(" ").filter { it.isNotBlank() }
                        when {
                            parts.size >= 2 -> "${parts[0].first()}${parts[1].first()}".uppercase()
                            parts.size == 1 -> parts[0].take(2).uppercase()
                            else -> "?"
                        }
                    }
                    val bitmap = remember(avatarBytes) {
                        avatarBytes?.let { decodeSampledBitmap(it, maxDimension = 300)?.asImageBitmap() }
                    }
                    Box(
                        modifier = Modifier
                            .size(Dims.avatarProfile)
                            .clip(CircleShape)
                            .let { m ->
                                if (bitmap == null) m.background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, SemanticColors.purple)))
                                else m
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (bitmap != null) {
                            androidx.compose.foundation.Image(
                                bitmap = bitmap,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                            )
                        } else {
                            Text(initials, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 34.sp)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable { avatarPicker.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = "تغيير الصورة", tint = Color.White, modifier = Modifier.size(15.dp))
                    }
                }
                Spacer(Modifier.height(Dims.s8))
                Text(
                    displayName.ifBlank { "بلا اسم" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = mc.glassOnCard
                )
                Text(
                    "الاسم محلي فقط — لا يُرسل لأحد",
                    style = MaterialTheme.typography.bodySmall,
                    color = mc.glassOnCard.copy(alpha = 0.55f)
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = Dims.s12),
                verticalArrangement = Arrangement.spacedBy(Dims.s12)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassCard()
                        .padding(14.dp)
                ) {
                    Text("اسم المستخدم", style = MaterialTheme.typography.labelSmall, color = mc.glassOnCard.copy(alpha = 0.5f))
                    Spacer(Modifier.height(3.dp))
                    Text(
                        username.ifBlank { "—" }.let { if (it != "—") "⁦@$it⁩" else it },
                        style = MaterialTheme.typography.bodyLarge,
                        color = mc.glassOnCard
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                        .border(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                        .clickable(onClick = onVerifyClick)
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Fingerprint, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("رقم أمانك", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(8.dp))
                    val chunks = fingerprint?.chunked(4)?.take(6) ?: listOf("————", "————", "————", "————", "————", "————")
                    Text(
                        text = chunks.chunked(3).joinToString("\n") { it.joinToString(" ") },
                        style = MaterialTheme.typography.bodyMedium.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr),
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 2.sp,
                        lineHeight = MaterialTheme.typography.bodyMedium.fontSize * 1.7,
                        color = mc.glassOnCard.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "شاركه لتأكيد هويتك عبر قناة أخرى.",
                        style = MaterialTheme.typography.labelSmall,
                        color = mc.glassOnCard.copy(alpha = 0.5f)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dims.s12, vertical = Dims.s16)
                    .glassCard(radius = 14.dp)
                    .clickable(onClick = onShowQrClick)
                    .padding(vertical = 13.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.QrCode, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Text("عرض رمز QR", style = MaterialTheme.typography.bodyMedium, color = mc.glassOnCard)
            }
        }
    }

    if (showNameDialog) {
        var draft by remember { mutableStateOf(displayName) }
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            containerColor = mc.glassCardStrong,
            titleContentColor = mc.glassOnCard,
            textContentColor = mc.glassOnCard,
            title = { Text("الاسم المعروض") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        repository.setMyDisplayName(draft.trim())
                        displayName = draft.trim()
                        showNameDialog = false
                    }
                }) { Text("حفظ") }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false }) { Text("إلغاء") }
            }
        )
    }
}
