package com.securemessenger.app.ui.screens.settings

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.securemessenger.app.security.AppSettings
import com.securemessenger.app.ui.GlassTopBar
import com.securemessenger.app.ui.glassCard
import com.securemessenger.app.ui.onboardingBackground
import com.securemessenger.app.ui.theme.LocalMessengerColors
import com.securemessenger.app.ui.theme.MessengerTheme
import com.securemessenger.app.ui.theme.SemanticColors

/**
 * StealthModeScreen - Hide app icon and enable secret access.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StealthModeScreen(
    onBackClick: () -> Unit,
    onEnableStealth: () -> Unit
) {
    val context = LocalContext.current
    val packageManager = context.packageManager
    val mc = LocalMessengerColors.current

    var isStealthEnabled by remember { mutableStateOf(AppSettings.isStealthEnabled(context)) }
    var showWarning by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().onboardingBackground(mc.onboardingGradient)) {
        Column(modifier = Modifier.fillMaxSize()) {
            GlassTopBar(title = "الوضع السري", onBack = onBackClick)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(92.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(SemanticColors.purple.copy(alpha = 0.15f))
                        .border(1.dp, SemanticColors.purple.copy(alpha = 0.4f), RoundedCornerShape(26.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.VisibilityOff, contentDescription = null, tint = SemanticColors.purple, modifier = Modifier.size(44.dp))
                }

                Spacer(Modifier.height(24.dp))

                Text(
                    "إخفاء التطبيق",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = mc.glassOnCard
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    text = "عند التفعيل، تختفي أيقونة التطبيق الحقيقية من الشاشة الرئيسية. يبقى الوصول عبر الكود السري أو بلاطة الإعدادات السريعة فقط.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = mc.glassOnCard.copy(alpha = 0.65f),
                    textAlign = TextAlign.Center,
                    lineHeight = MaterialTheme.typography.bodyMedium.fontSize * 1.7
                )

                Spacer(Modifier.height(6.dp))

                Text(
                    text = if (showDetails) "إخفاء التفاصيل ▲" else "كيف أعيد فتحه لاحقاً؟ ▾",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { showDetails = !showDetails }
                )

                AnimatedVisibility(
                    visible = showDetails,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                            .glassCard()
                            .padding(16.dp)
                    ) {
                        Text(
                            "الطريقة الأولى — كود سري من تطبيق الاتصال:",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(8.dp))
                        StepItem(number = "1", text = "افتح تطبيق الهاتف (الاتصال)")
                        StepItem(number = "2", text = "اكتب الكود السري: *#*#73287#*#*")
                        StepItem(number = "3", text = "ستعود أيقونة التطبيق للظهور (يعتمد على تطبيق الاتصال في جهازك)")

                        Spacer(Modifier.height(12.dp))
                        Text(
                            "الطريقة الثانية — بلاطة الإعدادات السريعة:",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(8.dp))
                        StepItem(number = "1", text = "اسحب شريط الإشعارات مرّتين")
                        StepItem(number = "2", text = "اضغط أيقونة التعديل (✎) وأضِف بلاطة \"زاجل\"")
                        StepItem(number = "3", text = "اضغط عليها في أي وقت لاحق لإعادة فتح التطبيق فوراً")
                    }
                }

                Spacer(Modifier.height(24.dp))

                Row(
                    modifier = Modifier
                        .glassCard()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("تفعيل الإخفاء", style = MaterialTheme.typography.bodyLarge, color = mc.glassOnCard)
                    Spacer(Modifier.width(14.dp))
                    Switch(
                        checked = isStealthEnabled,
                        onCheckedChange = { enable ->
                            if (enable) {
                                showWarning = true
                            } else {
                                val alias = ComponentName(context, "${context.packageName}.LauncherAlias")
                                packageManager.setComponentEnabledSetting(
                                    alias,
                                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                                    PackageManager.DONT_KILL_APP
                                )
                                isStealthEnabled = false
                                AppSettings.setStealthEnabled(context, false)
                            }
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = SemanticColors.purple)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp)
                    .padding(bottom = 26.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SemanticColors.red.copy(alpha = 0.1f))
                    .border(0.5.dp, SemanticColors.red.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.WarningAmber, contentDescription = null, tint = SemanticColors.red, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "احفظ رمز الفتح جيداً — لا طريقة لاستعادة الوصول بدونه.",
                    style = MaterialTheme.typography.labelSmall,
                    color = SemanticColors.red,
                    lineHeight = MaterialTheme.typography.labelSmall.fontSize * 1.6
                )
            }
        }
    }

    if (showWarning) {
        AlertDialog(
            onDismissRequest = { showWarning = false },
            containerColor = mc.glassCardStrong,
            titleContentColor = mc.glassOnCard,
            textContentColor = mc.glassOnCard,
            title = { Text("تأكيد إخفاء التطبيق") },
            text = {
                Text("هل أنت متأكد أنك تريد إخفاء أيقونة التطبيق؟ تذكر الكود السري: *#*#73287#*#*")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val alias = ComponentName(context, "${context.packageName}.LauncherAlias")
                        packageManager.setComponentEnabledSetting(
                            alias,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP
                        )
                        isStealthEnabled = true
                        AppSettings.setStealthEnabled(context, true)
                        showWarning = false
                        onEnableStealth()
                    }
                ) {
                    Text("نعم، إخفاء")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWarning = false }) {
                    Text("إلغاء")
                }
            }
        )
    }
}

@Preview(name = "Stealth mode — dark", showBackground = true)
@Composable
private fun StealthModeScreenDarkPreview() {
    MessengerTheme(darkTheme = true) { StealthModeScreen(onBackClick = {}, onEnableStealth = {}) }
}

@Preview(name = "Stealth mode — light", showBackground = true)
@Composable
private fun StealthModeScreenLightPreview() {
    MessengerTheme(darkTheme = false) { StealthModeScreen(onBackClick = {}, onEnableStealth = {}) }
}

@Composable
fun StepItem(
    number: String,
    text: String
) {
    val mc = LocalMessengerColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Text(
            text = "$number.",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(24.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = mc.glassOnCard.copy(alpha = 0.85f)
        )
    }
}
