package com.securemessenger.app.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.securemessenger.app.ui.GlassTopBar
import com.securemessenger.app.ui.glassBackground
import com.securemessenger.app.ui.glassCard
import com.securemessenger.app.ui.theme.LocalMessengerColors
import com.securemessenger.app.ui.viewmodel.ConnectionRequestsViewModel
import com.securemessenger.app.ui.viewmodel.IncomingRequestUiModel

/**
 * Incoming self-introductions found via username search, waiting on an
 * explicit accept/reject — see IncomingConnectionRequest. Accepting turns
 * one into a real, normally-encrypted conversation (same underlying flow
 * as scanning a QR code); rejecting deletes it with no reply of any kind,
 * indistinguishable from the sender's side from "hasn't checked yet".
 */
@Composable
fun ConnectionRequestsScreen(
    onBackClick: () -> Unit,
    onAccepted: (contactId: String) -> Unit,
    viewModel: ConnectionRequestsViewModel = viewModel()
) {
    val requests by viewModel.incomingRequests.collectAsState()
    val actionInProgress by viewModel.actionInProgress.collectAsState()
    val mc = LocalMessengerColors.current

    Box(modifier = Modifier.fillMaxSize().glassBackground(mc.listGradient)) {
        Column(modifier = Modifier.fillMaxSize()) {
            GlassTopBar(title = "طلبات الاتصال", onBack = onBackClick)

            if (requests.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier.size(72.dp).background(mc.glassCardStrong, shape = CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.MailOutline, contentDescription = null, tint = mc.glassOnCard.copy(alpha = 0.6f), modifier = Modifier.size(32.dp))
                        }
                        Spacer(Modifier.height(18.dp))
                        Text(
                            "لا توجد طلبات اتصال بانتظار الرد",
                            style = MaterialTheme.typography.bodyMedium,
                            color = mc.glassOnCard.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(requests, key = { it.senderIdentityPublicKeyHex }) { request ->
                        ConnectionRequestRow(
                            request = request,
                            busy = actionInProgress == request.senderIdentityPublicKeyHex,
                            onAccept = {
                                viewModel.accept(request.senderIdentityPublicKeyHex) { success ->
                                    // acceptConnectionRequest creates the Contact
                                    // keyed by senderUserId (from the verified
                                    // introduction, not the request's own primary
                                    // key) — that's the id ConversationScreen needs.
                                    if (success) onAccepted(request.senderUserId)
                                }
                            },
                            onReject = { viewModel.reject(request.senderIdentityPublicKeyHex) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionRequestRow(
    request: IncomingRequestUiModel,
    busy: Boolean,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    val mc = LocalMessengerColors.current
    Row(
        modifier = Modifier.fillMaxWidth().glassCard(radius = 16.dp).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(44.dp).background(mc.glassCardStrong, shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Person, contentDescription = null, tint = mc.glassOnCard.copy(alpha = 0.7f), modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("⁦@${request.senderUsername}⁩", style = MaterialTheme.typography.titleSmall, color = mc.glassOnCard)
            Text("يريد بدء محادثة معك", style = MaterialTheme.typography.bodySmall, color = mc.glassOnCard.copy(alpha = 0.55f))
        }
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = onReject) {
                Icon(Icons.Default.Close, contentDescription = "رفض", tint = mc.glassOnCard.copy(alpha = 0.5f))
            }
            Button(onClick = onAccept, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                Text("قبول", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
