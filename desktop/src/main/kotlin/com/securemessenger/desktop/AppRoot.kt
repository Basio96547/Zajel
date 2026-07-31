package com.securemessenger.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Top-level state machine: locked → unlocked, with a first-run branch that
 * creates a brand-new identity.
 *
 * Deliberately no calculator disguise here. The phone's disguise exists because
 * a phone gets picked up, unlocked and looked through by other people; that
 * threat model doesn't transfer to a desktop, and a fake calculator window would
 * add a confusing surface without adding protection. What does transfer — the
 * encrypted-at-rest store and the passphrase gate — is kept.
 */
@Composable
fun AppRoot(file: File, relayUrl: String) {
    val scope = rememberCoroutineScope()
    var store by remember { mutableStateOf<DesktopStore?>(null) }
    var client by remember { mutableStateOf<DesktopMessagingClient?>(null) }

    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF4C8DFF))) {
        Surface(modifier = Modifier.fillMaxSize()) {
            val activeStore = store
            val activeClient = client
            if (activeStore == null || activeClient == null) {
                UnlockScreen(file) { openedStore ->
                    val messaging = DesktopMessagingClient(
                        store = openedStore,
                        relayUrl = relayUrl,
                        onMessage = { _, _, _ -> },
                        onStateChanged = { }
                    )
                    scope.launch(Dispatchers.IO) { messaging.start() }
                    store = openedStore
                    client = messaging
                }
            } else {
                MainScreen(activeStore, activeClient)
            }
        }
    }
}

@Composable
private fun UnlockScreen(file: File, onOpened: (DesktopStore) -> Unit) {
    val scope = rememberCoroutineScope()
    // Keyed on `generation` so discarding the identity rebuilds this screen in
    // first-run mode instead of leaving it pointed at a deleted file.
    var generation by remember { mutableStateOf(0) }
    val store = remember(generation) { DesktopStore(file) }
    val isFirstRun = remember(generation) { !store.exists() }
    var confirmDiscard by remember { mutableStateOf(false) }

    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun submit() {
        error = null
        if (passphrase.length < 8) {
            error = "عبارة المرور قصيرة جداً — 8 محارف على الأقل"
            return
        }
        if (isFirstRun && passphrase != confirm) {
            error = "العبارتان غير متطابقتين"
            return
        }
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    if (isFirstRun) {
                        store.create(passphrase.toCharArray(), name.ifBlank { "مستخدم" }); true
                    } else {
                        store.unlock(passphrase.toCharArray())
                    }
                }.getOrElse { false }
            }
            busy = false
            if (ok) onOpened(store) else error = "عبارة المرور غير صحيحة، أو الملف تالف"
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.width(420.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                if (isFirstRun) "إنشاء هوية جديدة" else "فتح القفل",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(10.dp))
            Text(
                if (isFirstRun)
                    "تُشتق مفاتيح التشفير من هذه العبارة عبر Argon2id. " +
                        "لا توجد طريقة لاستعادتها: نسيانها يعني فقدان الهوية وإعادة الاقتران مع الجميع.\n\n" +
                        "تنبيه صريح: على الهاتف يحمي المفتاحَ عتادُ الجهاز (Keystore). " +
                        "على الكمبيوتر الحماية بقوة هذه العبارة وحدها."
                else "أدخل عبارة المرور لفكّ تشفير بياناتك المحلية.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                lineHeight = MaterialTheme.typography.bodySmall.fontSize * 1.7
            )
            Spacer(Modifier.height(22.dp))

            if (isFirstRun) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("اسم العرض") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
            }

            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text("عبارة المرور") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = if (isFirstRun) ImeAction.Next else ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth()
            )

            if (isFirstRun) {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text("تأكيد عبارة المرور") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(18.dp))
            Button(
                onClick = { submit() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                else Text(if (isFirstRun) "إنشاء" else "فتح")
            }

            // Without this the app is a dead end for anyone who forgets the
            // passphrase: nothing can decrypt the file, and the only way
            // forward is deleting it by hand from a directory most people will
            // never find. The escape hatch is destructive, so it says exactly
            // what it destroys and asks first.
            if (!isFirstRun) {
                Spacer(Modifier.height(14.dp))
                TextButton(onClick = { confirmDiscard = true }) {
                    Text("نسيت العبارة — إنشاء هوية جديدة", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("حذف الهوية الحالية؟") },
            text = {
                Text(
                    "لا يمكن فكّ تشفير البيانات بدون عبارة المرور، ولا توجد نسخة احتياطية منها في أي مكان.\n\n" +
                        "المتابعة تحذف الهوية والمحادثات وجهات الاتصال على هذا الكمبيوتر نهائياً، " +
                        "وتبدأ من الصفر بهوية جديدة — أي أن على كل جهة اتصال أن تقترن بك من جديد."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    file.delete()
                    passphrase = ""
                    confirm = ""
                    error = null
                    confirmDiscard = false
                    generation++
                }) { Text("حذف والبدء من جديد", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("إلغاء") } }
        )
    }
}
