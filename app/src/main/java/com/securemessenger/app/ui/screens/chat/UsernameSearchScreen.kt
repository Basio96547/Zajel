package com.securemessenger.app.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.securemessenger.app.SecureMessengerApp
import com.securemessenger.app.data.repository.SecureRepository
import com.securemessenger.app.network.DirectoryClient
import com.securemessenger.app.network.SecureMessagingClient
import com.securemessenger.app.ui.GlassTopBar
import com.securemessenger.app.ui.glassCard
import com.securemessenger.app.ui.onboardingBackground
import com.securemessenger.app.ui.theme.LocalMessengerColors
import com.securemessenger.core.B64
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed class LookupState {
    data object Idle : LookupState()
    data object Loading : LookupState()
    data class Found(val result: DirectoryClient.LookupResult.Found) : LookupState()
    data object NotFound : LookupState()
    data object Unreachable : LookupState()
}

/**
 * Search for someone by @username instead of QR — additive to [NewChatScreen]'s
 * existing QR flow, not a replacement for it. Finding someone here never adds
 * them directly; it only offers to send a connection request they must
 * explicitly accept (see [SecureMessagingClient.sendConnectionRequest]) —
 * the remote equivalent of showing them your QR code, not of pairing itself.
 */
@Composable
fun UsernameSearchScreen(onBackClick: () -> Unit) {
    val repository = remember { SecureMessengerApp.instance.repository }
    val mc = LocalMessengerColors.current
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var lookupState by remember { mutableStateOf<LookupState>(LookupState.Idle) }
    var alreadyPending by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var justSent by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        sendError = null
        justSent = false
        val trimmed = query.trim().lowercase()
        if (trimmed.length < 3) {
            lookupState = LookupState.Idle
            return@LaunchedEffect
        }
        lookupState = LookupState.Loading
        delay(400) // debounce — no point looking up every keystroke
        val client = SecureMessengerApp.instance.messagingClient
        if (client == null) {
            lookupState = LookupState.Unreachable
            return@LaunchedEffect
        }
        val found = client.lookupUsername(trimmed)
        lookupState = if (found != null) LookupState.Found(found) else LookupState.NotFound
        if (found != null) {
            alreadyPending = repository.getOutgoingConnectionRequest(B64.toHex(found.identityPublicKey)) != null
        }
    }

    Box(modifier = Modifier.fillMaxSize().onboardingBackground(mc.onboardingGradient)) {
        Column(modifier = Modifier.fillMaxSize()) {
            GlassTopBar(title = "البحث باسم المستخدم", onBack = onBackClick)

            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
                Spacer(modifier = Modifier.height(8.dp))
                SearchField(value = query, onValueChange = { query = it }, textColor = mc.glassOnCard)
                Spacer(modifier = Modifier.height(20.dp))

                when (val state = lookupState) {
                    // Centred in the space it actually has. Before typing,
                    // this hint is the only thing on the screen, and it was
                    // pinned under the search field with three quarters of
                    // the display empty below it.
                    LookupState.Idle -> Text(
                        text = "اكتب 3 أحرف على الأقل من اسم المستخدم للبحث.\nالنتيجة تحتاج موافقتك أنت — والطرف الآخر — قبل أن تصبح محادثة.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = mc.glassOnCard.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .wrapContentHeight(Alignment.CenterVertically)
                    )
                    LookupState.Loading -> Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    LookupState.NotFound -> EmptyResultMessage("لا يوجد مستخدم بهذا الاسم", mc.glassOnCard)
                    LookupState.Unreachable -> EmptyResultMessage("تعذّر الوصول إلى الدليل الآن — تأكد من اتصالك بالإنترنت", mc.glassOnCard)
                    is LookupState.Found -> FoundResultCard(
                        username = state.result.username,
                        alreadyPending = alreadyPending || justSent,
                        sending = sending,
                        errorMessage = sendError,
                        onSendRequest = {
                            scope.launch {
                                sending = true
                                sendError = null
                                val client = SecureMessengerApp.instance.messagingClient
                                val result = client?.sendConnectionRequest(state.result.username)
                                sending = false
                                when (result) {
                                    SecureMessagingClient.ConnectionRequestResult.Sent -> justSent = true
                                    SecureMessagingClient.ConnectionRequestResult.AlreadyPending -> alreadyPending = true
                                    SecureMessagingClient.ConnectionRequestResult.CannotAddSelf ->
                                        sendError = "هذا يوزرنيمك أنت"
                                    SecureMessagingClient.ConnectionRequestResult.UsernameNotFound ->
                                        sendError = "لم يعد هذا الاسم متاحاً"
                                    SecureMessagingClient.ConnectionRequestResult.Error, null ->
                                        sendError = "تعذّر إرسال الطلب — حاول مجدداً"
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, textColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().glassCard(radius = 14.dp).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = textColor.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text("@يوزرنيم", style = MaterialTheme.typography.bodyLarge, color = textColor.copy(alpha = 0.45f))
            }
            BasicTextField(
                value = value,
                onValueChange = { onValueChange(it.filter { c -> !c.isWhitespace() }) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = textColor),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun EmptyResultMessage(text: String, textColor: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = textColor.copy(alpha = 0.65f),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 32.dp)
    )
}

@Composable
private fun FoundResultCard(
    username: String,
    alreadyPending: Boolean,
    sending: Boolean,
    errorMessage: String?,
    onSendRequest: () -> Unit
) {
    val mc = LocalMessengerColors.current
    Column(
        modifier = Modifier.fillMaxWidth().glassCard(radius = 18.dp).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(64.dp).background(mc.glassCardStrong, shape = androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.PersonSearch, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text("⁦@$username⁩", style = MaterialTheme.typography.titleMedium, color = mc.glassOnCard)
        Spacer(Modifier.height(16.dp))

        if (alreadyPending) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = mc.glassOnCard.copy(alpha = 0.55f), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("بانتظار الرد", style = MaterialTheme.typography.bodyMedium, color = mc.glassOnCard.copy(alpha = 0.65f))
            }
        } else {
            Button(onClick = onSendRequest, enabled = !sending, modifier = Modifier.fillMaxWidth()) {
                if (sending) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text("طلب اتصال")
                }
            }
        }
        if (errorMessage != null) {
            Spacer(Modifier.height(10.dp))
            Text(errorMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
    }
}
