package com.hisn.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hisn.app.audit.AuditLog
import com.hisn.app.audit.HygieneScore
import com.hisn.app.audit.Severity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VerifyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF2E7D32), background = Color(0xFF0A0A0F),
                    surface = Color(0xFF15151F), onBackground = Color(0xFFE0E0E0),
                    onSurface = Color(0xFFE0E0E0), error = Color(0xFFE15656)
                )
            ) { VerifyScreen() }
        }
    }
}

private fun sevColor(s: Severity) = when (s) {
    Severity.HIGH, Severity.CRITICAL -> Color(0xFFE15656)
    Severity.MEDIUM -> Color(0xFFE0973C)
    Severity.LOW -> Color(0xFFFDD835)
    Severity.OK -> Color(0xFF43A047)
}

private fun fmt(ts: Long) =
    if (ts <= 0) "—" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VerifyScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<AuditLog.VerificationReport?>(null) }
    var loading by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        loading = true
        scope.launch {
            val json = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }
            report = if (json == null) null else AuditLog.verifyImport(context, json)
            loading = false
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("التحقّق من سجلّ") }) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(
                "استورد ملف سجلّ (JSON) مُصدَّراً من «حصن» للتحقّق من سلامته.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { picker.launch(arrayOf("application/json", "text/plain", "*/*")) },
                modifier = Modifier.fillMaxWidth(), enabled = !loading
            ) {
                Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (loading) "جاري القراءة…" else "اختر ملف السجلّ")
            }

            report?.let { r ->
                Spacer(Modifier.height(16.dp))
                if (r.parseError != null) {
                    ResultCard(false, "ملف غير صالح", r.parseError)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { ChainCard(r) }
                        item { SealCard(r) }
                        item { SummaryCard(r) }
                        if (r.timeline.isNotEmpty()) {
                            item {
                                Text("الدرجة عبر الزمن", style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                            }
                            items(r.timeline.reversed()) { (ts, score) -> TimelineRow(ts, score) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChainCard(r: AuditLog.VerificationReport) {
    if (r.chainIntact) {
        ResultCard(true, "السلسلة سليمة",
            "لم تُعدَّل أو تُحذَف أو يُعَد ترتيب أي من الـ ${r.entryCount} إدخالاً منذ التصدير.")
    } else {
        ResultCard(false, "السلسلة مكسورة",
            "اكتُشف تعديل عند الإدخال رقم ${r.brokenAtIndex + 1}. لا يمكن الوثوق بهذا السجلّ.")
    }
}

@Composable
private fun SealCard(r: AuditLog.VerificationReport) {
    when {
        !r.chainIntact -> {}
        r.sealMatchesThisDevice -> ResultCard(true, "ختم الجهاز مطابق",
            "هذا الملف صُدِّر من هذا الجهاز نفسه ولم يُعبَث برأسه.")
        else -> ResultCard(null, "ختم الجهاز غير مؤكَّد هنا",
            "السلامة مؤكّدة، لكن ختم الجهاز لا يُتحقّق منه إلا على جهاز المُصدِّر (صُدِّر من جهاز آخر). " +
                "التحقّق من طرف ثالث يحتاج توقيعاً غير متماثل — خطوة مستقبلية.")
    }
}

@Composable
private fun SummaryCard(r: AuditLog.VerificationReport) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Text("الملخّص", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text("عدد الفحوصات المسجّلة: ${r.entryCount}", style = MaterialTheme.typography.bodyMedium)
            Text("من: ${fmt(r.firstTs)}", style = MaterialTheme.typography.bodyMedium)
            Text("إلى: ${fmt(r.lastTs)}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun TimelineRow(ts: Long, score: Int) {
    val color = sevColor(HygieneScore.band(score))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(fmt(ts), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.width(130.dp))
        Box(
            Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.surface)
        ) {
            Box(Modifier.fillMaxWidth(score / 100f).fillMaxHeight().background(color))
        }
        Spacer(Modifier.width(10.dp))
        Text("$score", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
            color = color, modifier = Modifier.width(34.dp))
    }
}

@Composable
private fun ResultCard(ok: Boolean?, title: String, body: String) {
    val color = when (ok) { true -> Color(0xFF43A047); false -> Color(0xFFE15656); null -> Color(0xFFE0973C) }
    val icon = when (ok) { true -> Icons.Default.VerifiedUser; false -> Icons.Default.GppBad; null -> Icons.Default.GppMaybe }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = color)
                Spacer(Modifier.height(4.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f))
            }
        }
    }
}
