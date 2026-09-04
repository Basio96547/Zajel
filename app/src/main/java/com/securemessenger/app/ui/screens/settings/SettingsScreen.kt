package com.securemessenger.app.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.securemessenger.app.BuildConfig
import com.securemessenger.app.SecureMessengerApp
import com.securemessenger.app.security.AppSettings
import com.securemessenger.app.service.MessengerService
import com.securemessenger.app.ui.GlassTopBar
import com.securemessenger.app.ui.glassBackground
import com.securemessenger.app.ui.glassCard
import com.securemessenger.app.ui.screens.chat.Avatar
import com.securemessenger.app.ui.theme.LocalMessengerColors
import com.securemessenger.app.ui.theme.SemanticColors
import kotlinx.coroutines.launch

/**
 * SettingsScreen - Security settings and app configuration.
 *
 * Every option here is fully wired: toggles persist through [AppSettings] and
 * take effect immediately (message TTL), and the data actions perform a real
 * secure wipe. There is deliberately no backup/export feature — nothing about
 * this account should ever exist outside this device's encrypted local
 * storage. Screenshot/screen-recording prevention used to be a toggle here;
 * it's now unconditional (MainActivity) — a hidden messenger with an opt-out
 * on that would defeat its own purpose.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    /** Null on the tab root — a root has nowhere to go back to. */
    onBackClick: (() -> Unit)? = null,
    onVerificationClick: () -> Unit,
    onStealthModeClick: () -> Unit,
    onDataWiped: () -> Unit,
    onProfileClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val mc = LocalMessengerColors.current

    val themeMode by AppSettings.themeMode.collectAsState()
    var autoDestructEnabled by remember { mutableStateOf(AppSettings.isAutoDestructEnabled(context)) }
    var autoDestructTime by remember {
        mutableStateOf(AppSettings.secondsToLabel(AppSettings.getAutoDestructSeconds(context)))
    }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showDestructTimeDialog by remember { mutableStateOf(false) }
    var showWipeDialog by remember { mutableStateOf(false) }
    var showCodeDialog by remember { mutableStateOf(false) }
    var showDuressDialog by remember { mutableStateOf(false) }
    // Codes are stored only as salted hashes and can never be read back, so the
    // UI tracks only whether each is *set*, never the value itself.
    var accessCodeSet by remember { mutableStateOf(AppSettings.hasAccessCode(context)) }
    var duressCodeSet by remember { mutableStateOf(AppSettings.hasDuressCode(context)) }
    var relayEnabled by remember { mutableStateOf(AppSettings.isRelayEnabled(context)) }
    var backgroundDelivery by remember { mutableStateOf(AppSettings.isBackgroundDeliveryEnabled(context)) }
    var notificationMode by remember { mutableStateOf(AppSettings.notificationMode(context)) }
    var showNotificationDialog by remember { mutableStateOf(false) }
    var coverTrafficEnabled by remember { mutableStateOf(AppSettings.isCoverTrafficEnabled(context)) }
    // An empty relay URL means this build has no relay path compiled in at all,
    // so both switches below would be meaningless — hide the whole section.
    val relayAvailable = remember { BuildConfig.RELAY_URL.isNotBlank() }
    var showCryptoGlossary by remember { mutableStateOf(false) }
    val username = AppSettings.getUsername(context)

    // The row below is a preview of ProfileScreen, and it used to disagree
    // with it about who you are: it printed the username twice (once as the
    // name, once as the handle) and drew initials over a gradient no matter
    // what. So you could set a display name and a photo on the very screen
    // this row opens, come back, and find neither — the app quietly telling
    // you the edit hadn't taken. Same two reads ProfileScreen does, guarded
    // the same way, because both can run before the database is open.
    var myDisplayName by remember { mutableStateOf("") }
    var myAvatar by remember { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(Unit) {
        try {
            myDisplayName = SecureMessengerApp.instance.repository.getMyDisplayName() ?: ""
        } catch (e: Exception) {
            myDisplayName = ""
        }
    }
    LaunchedEffect(Unit) {
        try {
            SecureMessengerApp.instance.repository.getMyAvatarFlow().collect { myAvatar = it }
        } catch (e: Exception) {
            myAvatar = null
        }
    }
    val themeLabel = when (themeMode) {
        AppSettings.ThemeMode.SYSTEM -> "تلقائي"
        AppSettings.ThemeMode.LIGHT -> "فاتح"
        AppSettings.ThemeMode.DARK -> "داكن"
    }

    Box(modifier = Modifier.fillMaxSize().glassBackground(mc.listGradient)) {
        Scaffold(
            containerColor = Color.Transparent,
            // No back arrow when this is the tab root, and one when it isn't.
            //
            // The screen has now been all three states: originally it carried
            // no onBack at all while AppNavigation passed one it never called
            // (a dead parameter with a live wire attached), then a back arrow
            // and no bar, and now a root of the restored tab bar. Passing the
            // callback straight through to GlassTopBar — which already takes
            // a nullable onBack — means the caller decides, which is the only
            // party that knows how this screen was reached.
            topBar = { GlassTopBar(title = "الإعدادات", onBack = onBackClick) },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Profile row — avatar, display name, username, tap to open the full profile.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassCard()
                        .clickable(onClick = onProfileClick)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar(
                        name = myDisplayName.ifBlank { username ?: "؟" },
                        size = 48.dp,
                        id = username ?: "me",
                        avatarBytes = myAvatar
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            myDisplayName.ifBlank { username ?: "بلا اسم" },
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = mc.glassOnCard
                        )
                        Text(username?.let { "⁦@$it⁩" } ?: "—", style = MaterialTheme.typography.bodySmall, color = mc.glassOnCard.copy(alpha = 0.55f))
                    }
                    RowChevron()
                }

                Column {
                    SettingsGroupLabel("المظهر")
                    SettingsGroupCard {
                        SettingsRow(
                            icon = Icons.Default.Palette,
                            iconTint = SemanticColors.pink,
                            title = "السمة",
                            onClick = { showThemeDialog = true },
                            trailing = { RowValue(themeLabel) }
                        )
                    }
                }

                Column {
                    SettingsGroupLabel("الأمان والخصوصية")
                    SettingsGroupCard {
                        SettingsRow(
                            icon = Icons.Default.Bolt,
                            iconTint = SemanticColors.orange,
                            title = "رسائل ذاتية التدمير",
                            trailing = {
                                Switch(
                                    checked = autoDestructEnabled,
                                    onCheckedChange = {
                                        autoDestructEnabled = it
                                        AppSettings.setAutoDestructEnabled(context, it)
                                    }
                                )
                            }
                        )
                        if (autoDestructEnabled) {
                            SettingsDivider()
                            SettingsRow(
                                icon = Icons.Default.Timer,
                                iconTint = SemanticColors.orange,
                                title = "وقت التدمير التلقائي",
                                onClick = { showDestructTimeDialog = true },
                                trailing = { RowValue(autoDestructTime) }
                            )
                        }
                        SettingsDivider()
                        SettingsRow(
                            icon = Icons.Default.VerifiedUser,
                            iconTint = SemanticColors.green,
                            title = "التحقق من المفاتيح",
                            onClick = onVerificationClick,
                            trailing = { RowChevron() }
                        )
                        SettingsDivider()
                        SettingsRow(
                            icon = Icons.Default.VisibilityOff,
                            iconTint = SemanticColors.purple,
                            title = "الوضع السري",
                            onClick = onStealthModeClick,
                            trailing = { RowChevron() }
                        )
                        SettingsDivider()
                        SettingsRow(
                            icon = Icons.Default.Dialpad,
                            iconTint = SemanticColors.amber,
                            title = "رمز الفتح ورمز الطوارئ",
                            onClick = { showCodeDialog = true },
                            trailing = { RowChevron() }
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "رمز الفتح: " + (if (accessCodeSet) "مُعيَّن" else "غير مُعيَّن") +
                            "  ·  رمز الطوارئ: " + (if (duressCodeSet) "مُفعَّل" else "غير مُفعَّل"),
                        style = MaterialTheme.typography.labelSmall,
                        color = mc.glassOnCard.copy(alpha = 0.4f),
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glassCard()
                            .clickable { showCryptoGlossary = true }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            "Double Ratchet · ML-KEM-768 hybrid · Sealed Sender",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = mc.glassOnCard.copy(alpha = 0.6f)
                        )
                    }
                }

                if (relayAvailable) {
                    Column {
                        SettingsGroupLabel("المراسلة خارج الشبكة المحلية")
                        SettingsGroupCard {
                            SettingsRow(
                                icon = Icons.Default.CloudQueue,
                                iconTint = SemanticColors.pink,
                                title = "صندوق البريد الأعمى",
                                trailing = {
                                    Switch(
                                        checked = relayEnabled,
                                        onCheckedChange = {
                                            relayEnabled = it
                                            AppSettings.setRelayEnabled(context, it)
                                        }
                                    )
                                }
                            )
                            if (relayEnabled) {
                                SettingsDivider()
                                SettingsRow(
                                    icon = Icons.Default.Shuffle,
                                    iconTint = SemanticColors.purple,
                                    title = "حركة تمويهية",
                                    trailing = {
                                        Switch(
                                            checked = coverTrafficEnabled,
                                            onCheckedChange = {
                                                coverTrafficEnabled = it
                                                AppSettings.setCoverTrafficEnabled(context, it)
                                            }
                                        )
                                    }
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (!relayEnabled) {
                                "مُطفأ: المراسلة تعمل على الشبكة المحلية فقط، ولا يخرج أي شيء منها."
                            } else if (coverTrafficEnabled) {
                                "الوسيط لا يرى المحتوى ولا المُرسِل ولا المُستقبِل ولا الحجم. " +
                                    "الحركة التمويهية تُخفي التوقيت أيضاً، مقابل استهلاك بطارية وبيانات مستمر. " +
                                    "يبقى مكشوفاً له: عنوان IP ووقت الاتصال."
                            } else {
                                "الوسيط لا يرى المحتوى ولا المُرسِل ولا المُستقبِل ولا الحجم — " +
                                    "لكنه يستطيع ربط توقيت الإرسال بتوقيت الاستلام. فعّل الحركة التمويهية لإخفاء ذلك."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = mc.glassOnCard.copy(alpha = 0.4f),
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }

                Column {
                    SettingsGroupLabel("الاستقبال")
                    SettingsGroupCard {
                        SettingsRow(
                            icon = Icons.Default.Notifications,
                            iconTint = SemanticColors.green,
                            title = "الاستقبال والتطبيق مغلق",
                            trailing = {
                                Switch(
                                    checked = backgroundDelivery,
                                    onCheckedChange = { enabled ->
                                        backgroundDelivery = enabled
                                        AppSettings.setBackgroundDeliveryEnabled(context, enabled)
                                        if (enabled) MessengerService.start(context)
                                        else MessengerService.stop(context)
                                    }
                                )
                            }
                        )
                        if (backgroundDelivery) {
                            SettingsDivider()
                            SettingsRow(
                                icon = Icons.Default.Visibility,
                                iconTint = SemanticColors.orange,
                                title = "ما يظهر في الإشعار",
                                trailing = {
                                    Text(
                                        when (notificationMode) {
                                            AppSettings.NotificationMode.SILENT -> "بلا إشعار"
                                            AppSettings.NotificationMode.NEUTRAL -> "محايد"
                                            AppSettings.NotificationMode.FULL -> "الاسم والنص"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = mc.glassOnCard.copy(alpha = 0.55f)
                                    )
                                },
                                onClick = { showNotificationDialog = true }
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (!backgroundDelivery) {
                            "مُطفأ: لا تصل الرسائل إلا أثناء فتح المراسل، فيجب أن يكون مفتوحاً لدى الطرفين في الوقت نفسه. " +
                                "لا شيء يضيع — الرسالة تنتظر في صندوق الصادر وفي الوسيط ٤٨ ساعة — لكنها تصل متأخرة."
                        } else {
                            "الرسائل تصل والتطبيق مغلق. المقابل صريح: يفرض أندرويد إشعاراً دائماً لا يُزال " +
                                "لأي تطبيق يعمل في الخلفية — وهو هنا بهيئة الحاسبة، لكنه يبقى دليلاً على أن التطبيق يعمل."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = mc.glassOnCard.copy(alpha = 0.4f),
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }

                Column {
                    SettingsGroupLabel("البيانات")
                    SettingsGroupCard {
                        SettingsRow(
                            icon = Icons.Default.DeleteForever,
                            iconTint = SemanticColors.red,
                            title = "مسح كامل للبيانات",
                            danger = true,
                            onClick = { showWipeDialog = true }
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    text = "الإصدار ${BuildConfig.VERSION_NAME} · ${if (BuildConfig.DEBUG) "Debug" else "Release"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = mc.glassOnCard.copy(alpha = 0.35f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                // Clears the floating tab bar this screen now sits under.
                Spacer(modifier = Modifier.height(104.dp))
            }
        }
    }

    if (showNotificationDialog) {
        val labels = listOf("بلا إشعار", "تنبيه محايد فقط", "الاسم ومقتطف من النص")
        SettingsDropdownSheet(
            title = "ما يظهر في الإشعار",
            options = labels,
            selectedOption = when (notificationMode) {
                AppSettings.NotificationMode.SILENT -> labels[0]
                AppSettings.NotificationMode.NEUTRAL -> labels[1]
                AppSettings.NotificationMode.FULL -> labels[2]
            },
            onDismiss = { showNotificationDialog = false },
            onOptionSelected = { label ->
                val mode = when (label) {
                    labels[0] -> AppSettings.NotificationMode.SILENT
                    labels[2] -> AppSettings.NotificationMode.FULL
                    else -> AppSettings.NotificationMode.NEUTRAL
                }
                notificationMode = mode
                AppSettings.setNotificationMode(context, mode)
            }
        )
    }

    if (showThemeDialog) {
        SettingsDropdownSheet(
            title = "السمة",
            options = listOf("تلقائي", "فاتح", "داكن"),
            selectedOption = themeLabel,
            onDismiss = { showThemeDialog = false },
            onOptionSelected = { label ->
                val mode = when (label) {
                    "فاتح" -> AppSettings.ThemeMode.LIGHT
                    "داكن" -> AppSettings.ThemeMode.DARK
                    else -> AppSettings.ThemeMode.SYSTEM
                }
                AppSettings.setThemeMode(context, mode)
            }
        )
    }

    if (showDestructTimeDialog) {
        SettingsDropdownSheet(
            title = "وقت التدمير التلقائي",
            options = AppSettings.autoDestructLabels,
            selectedOption = autoDestructTime,
            onDismiss = { showDestructTimeDialog = false },
            onOptionSelected = {
                autoDestructTime = it
                AppSettings.setAutoDestructSeconds(context, AppSettings.labelToSeconds(it))
            }
        )
    }

    if (showWipeDialog) {
        AlertDialog(
            onDismissRequest = { showWipeDialog = false },
            containerColor = mc.glassCardStrong,
            icon = {
                Icon(
                    imageVector = Icons.Default.DeleteForever,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("مسح جميع البيانات", color = mc.glassOnCard) },
            text = {
                Text(
                    "سيتم حذف جميع الرسائل والمفاتيح والمحادثات بشكل نهائي ولا يمكن التراجع عن ذلك. هل أنت متأكد؟",
                    color = mc.glassOnCard.copy(alpha = 0.75f)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showWipeDialog = false
                        scope.launch {
                            try {
                                SecureMessengerApp.instance.repository.wipeAllData()
                            } catch (_: Exception) {
                                // Even on failure, fall through to a clean setup state.
                            }
                            // The database is gone — clear the setup markers too so
                            // the app correctly returns to the setup wizard instead
                            // of trying to auto-unlock a profile that no longer exists.
                            AppSettings.clearUsername(context)
                            onDataWiped()
                        }
                    }
                ) {
                    Text("مسح نهائي", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showWipeDialog = false }) {
                    Text("إلغاء", color = mc.glassOnCard)
                }
            }
        )
    }

    if (showCodeDialog) {
        var input by remember { mutableStateOf("") }
        // Refuse a new access code that matches the already-configured duress
        // code — silently letting the two collide would neutralize the panic
        // wipe: onEquals() only treats an entered code as duress when it does
        // NOT also match the access code, so a collision makes the duress
        // branch permanently unreachable, with nothing telling the user their
        // panic code stopped working.
        val collidesWithDuressCode = input.isNotEmpty() && AppSettings.verifyDuressCode(context, input)
        // The calculator's keypad cannot type a leading zero, so a code that
        // starts with one is unenterable — and since this dialog is behind the
        // code it just replaced, saving one locks the owner out for good. This
        // screen used to say "3 to 10" while Setup said "4 to 10"; both now
        // quote AppSettings.isTypeableCode, which is the only rule.
        val startsWithZero = input.startsWith("0")
        val codeAcceptable = AppSettings.isTypeableCode(input) && !collidesWithDuressCode
        AlertDialog(
            onDismissRequest = { showCodeDialog = false },
            containerColor = mc.glassCardStrong,
            title = { Text("رمز فتح المراسل", color = mc.glassOnCard) },
            text = {
                Column {
                    Text(
                        "من شاشة الحاسبة، اكتب هذا الرقم كعملية حسابية عادية ثم اضغط = لفتح المراسل السري.",
                        style = MaterialTheme.typography.bodySmall,
                        color = mc.glassOnCard.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(12.dp))
                    Box(
                        modifier = Modifier.glassCard(radius = 12.dp)
                    ) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { new -> input = new.filter { it.isDigit() }.take(10) },
                            singleLine = true,
                            label = { Text("رقم من 4 إلى 10 أرقام، لا يبدأ بصفر") },
                            isError = collidesWithDuressCode || startsWithZero,
                            supportingText = {
                                when {
                                    collidesWithDuressCode -> Text("يجب أن يختلف عن رمز الطوارئ")
                                    startsWithZero -> Text("لا يبدأ بصفر — لن تستطيع كتابته في الحاسبة")
                                }
                            },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                            )
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (codeAcceptable) {
                            AppSettings.setAccessCode(context, input)
                            accessCodeSet = true
                            showCodeDialog = false
                        }
                    },
                    enabled = codeAcceptable
                ) {
                    Text("حفظ")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCodeDialog = false
                    showDuressDialog = true
                }) {
                    Text("رمز الطوارئ ›", color = mc.glassOnCard)
                }
            }
        )
    }

    if (showDuressDialog) {
        var input by remember { mutableStateOf("") }
        val collidesWithAccessCode = input.isNotEmpty() && AppSettings.verifyAccessCode(context, input)
        // Same untypeable-code rule as the access code, and here it fails even
        // quieter: an unenterable panic code doesn't lock anyone out, it just
        // never fires. The owner would believe they have a wipe-under-duress
        // and find out otherwise at the exact moment it mattered.
        val startsWithZero = input.startsWith("0")
        val duressAcceptable =
            input.isEmpty() || (AppSettings.isTypeableCode(input) && !collidesWithAccessCode)
        AlertDialog(
            onDismissRequest = { showDuressDialog = false },
            containerColor = mc.glassCardStrong,
            icon = {
                Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            },
            title = { Text("رمز الطوارئ", color = mc.glassOnCard) },
            text = {
                Column {
                    Text(
                        "إن كتبت هذا الرقم من شاشة الحاسبة بدل رمز الفتح الحقيقي ثم ضغطت =، تُمسح جميع رسائل ومفاتيح المراسل بصمت في الخلفية دون أي تغيير ظاهر على الحاسبة. اتركه فارغاً لتعطيل هذه الميزة.",
                        style = MaterialTheme.typography.bodySmall,
                        color = mc.glassOnCard.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(12.dp))
                    Box(
                        modifier = Modifier.glassCard(radius = 12.dp)
                    ) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { new -> input = new.filter { it.isDigit() }.take(10) },
                            singleLine = true,
                            label = { Text("رقم من 4 إلى 10 أرقام لا يبدأ بصفر، أو فارغ للتعطيل") },
                            isError = collidesWithAccessCode || startsWithZero,
                            supportingText = {
                                when {
                                    collidesWithAccessCode -> Text("يجب أن يختلف عن رمز فتح المراسل")
                                    startsWithZero -> Text("لا يبدأ بصفر — لن تستطيع كتابته في الحاسبة")
                                }
                            },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                            )
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (input.isEmpty()) {
                            AppSettings.setDuressCode(context, null)
                            duressCodeSet = false
                            showDuressDialog = false
                        } else if (duressAcceptable) {
                            AppSettings.setDuressCode(context, input)
                            duressCodeSet = true
                            showDuressDialog = false
                        }
                    },
                    enabled = duressAcceptable
                ) {
                    Text("حفظ")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDuressDialog = false }) {
                    Text("إلغاء", color = mc.glassOnCard)
                }
            }
        )
    }

    if (showCryptoGlossary) {
        AlertDialog(
            onDismissRequest = { showCryptoGlossary = false },
            containerColor = mc.glassCardStrong,
            title = { Text("ماذا يعني هذا؟", color = mc.glassOnCard) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlossaryItem("Double Ratchet", "مفتاح تشفير جديد لكل رسالة تقريباً — كشف مفتاح واحد لا يفضح الرسائل السابقة أو اللاحقة.")
                    GlossaryItem("ML-KEM-768 hybrid", "طبقة تشفير إضافية مقاومة للحواسيب الكمّية المستقبلية، فوق التشفير التقليدي وليس بديلاً عنه.")
                    GlossaryItem("Sealed Sender", "الخادم الذي ينقل رسائلك لا يرى من المرسل فعلياً.")
                }
            },
            confirmButton = {
                TextButton(onClick = { showCryptoGlossary = false }) { Text("حسناً") }
            }
        )
    }
}
