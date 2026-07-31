package com.hisn.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.hisn.app.audit.AuditLog
import com.hisn.app.audit.Finding
import com.hisn.app.audit.HygieneScore
import com.hisn.app.audit.Severity
import com.hisn.app.scan.HygieneScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HisnTheme { HisnScreen() } }
    }
}

@Composable
private fun HisnTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = Color(0xFF2E7D32),
        background = Color(0xFF0A0A0F),
        surface = Color(0xFF15151F),
        onBackground = Color(0xFFE0E0E0),
        onSurface = Color(0xFFE0E0E0),
        error = Color(0xFFE53935)
    )
    MaterialTheme(colorScheme = colors, content = content)
}

private fun severityColor(s: Severity): Color = when (s) {
    Severity.CRITICAL -> Color(0xFFD32F2F)
    Severity.HIGH -> Color(0xFFE53935)
    Severity.MEDIUM -> Color(0xFFFB8C00)
    Severity.LOW -> Color(0xFFFDD835)
    Severity.OK -> Color(0xFF43A047)
}

private fun severityLabel(s: Severity): String = when (s) {
    Severity.CRITICAL -> "حرِج"
    Severity.HIGH -> "خطر"
    Severity.MEDIUM -> "متوسط"
    Severity.LOW -> "منخفض"
    Severity.OK -> "سليم"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HisnScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var findings by remember { mutableStateOf<List<Finding>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var scannedOnce by remember { mutableStateOf(false) }

    // Ask once for notification permission so background change-alerts can show.
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun scan() {
        if (scanning) return
        scanning = true
        scope.launch {
            // The UI shares the one scan path with the background worker; it just
            // doesn't post a notification for a scan the user triggered.
            val outcome = HygieneScanner.scanAndRecord(context, notify = false)
            findings = outcome.findings
            scannedOnce = true
            scanning = false
        }
    }

    var exportMsg by remember { mutableStateOf<String?>(null) }

    fun exportLog() {
        scope.launch {
            exportMsg = try {
                val result = AuditLog.export(context, System.currentTimeMillis())
                val dir = File(context.getExternalFilesDir(null), "audit").apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val file = File(dir, "hisn_audit_$stamp.json")
                withContext(Dispatchers.IO) { file.writeText(result.json) }
                "تم تصدير ${result.entryCount} سجلّ (موقّع ومقاوم للتلاعب):\n${file.absolutePath}"
            } catch (e: Exception) {
                "تعذّر التصدير: ${e.message}"
            }
        }
    }

    LaunchedEffect(Unit) { scan() }

    val worst = findings.maxByOrNull { it.severity.rank }?.severity ?: Severity.OK
    val issues = findings.count { it.severity.rank >= Severity.MEDIUM.rank }
    val score = HygieneScore.compute(findings)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("حصن")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        context.startActivity(android.content.Intent(context, VerifyActivity::class.java))
                    }) {
                        Icon(Icons.Default.FactCheck, contentDescription = "التحقّق من سجلّ")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            StatusHeader(score, worst, issues, scanning, scannedOnce)

            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(findings, key = { it.id }) { FindingCard(it) }
                item { Disclaimer() }
            }

            Column(Modifier.padding(16.dp)) {
                if (exportMsg != null) {
                    Text(
                        exportMsg!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { scan() },
                        enabled = !scanning,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (scanning) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text("جاري الفحص…")
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("فحص الآن")
                        }
                    }
                    OutlinedButton(
                        onClick = { exportLog() },
                        enabled = scannedOnce,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("تصدير السجلّ")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusHeader(score: Int, worst: Severity, issues: Int, scanning: Boolean, scannedOnce: Boolean) {
    val color = if (!scannedOnce) MaterialTheme.colorScheme.primary else severityColor(HygieneScore.band(score))
    Column(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (scannedOnce) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "$score",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
                Text(
                    text = " / 100",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(bottom = 10.dp)
                )
            }
            Text("درجة النظافة الأمنية", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Spacer(Modifier.height(6.dp))
        } else {
            Icon(Icons.Default.Shield, contentDescription = null, tint = color, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(8.dp))
        }
        Text(
            text = when {
                !scannedOnce -> "جاري الفحص الأول…"
                issues == 0 -> "لا مشاكل واضحة في النظافة الأمنية"
                else -> "$issues نقطة تحتاج انتباهك"
            },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
        )
    }
}

@Composable
private fun FindingCard(f: Finding) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(Modifier.padding(14.dp)) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(f.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    SeverityChip(f.severity)
                }
                Spacer(Modifier.height(4.dp))
                Text(f.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                if (f.recommendation.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Row {
                        Icon(Icons.Default.TipsAndUpdates, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(f.recommendation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SeverityChip(s: Severity) {
    val c = severityColor(s)
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(c.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(severityLabel(s), style = MaterialTheme.typography.labelSmall, color = c, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Disclaimer() {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.padding(14.dp)) {
            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "هذا فحص نظافة أمنية ورفع كلفة على المهاجم — وليس حماية من تجسّس الدول (مثل بيغاسوس). "
                    + "للتحليل الجنائي استخدم أدوات مثل MVT على حاسوب، وفعّل وضع القفل (Lockdown).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}
