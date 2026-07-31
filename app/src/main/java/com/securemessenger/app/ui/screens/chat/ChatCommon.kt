package com.securemessenger.app.ui.screens.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.securemessenger.app.SecureMessengerApp
import com.securemessenger.app.network.ConnectionState
import com.securemessenger.app.ui.theme.Dims

/** Small building blocks shared across every chat screen (not just the conversation itself). */

// ---------- shimmer loading placeholder ----------

/** A soft sweeping gradient over a shape — used while media is still decoding/loading. */
@Composable
fun ShimmerBox(modifier: Modifier = Modifier, shape: RoundedCornerShape = RoundedCornerShape(8.dp)) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translate by transition.animateFloat(
        initialValue = -400f,
        targetValue = 800f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslate"
    )
    val base = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    val highlight = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(base, highlight, base),
                    start = androidx.compose.ui.geometry.Offset(translate, 0f),
                    end = androidx.compose.ui.geometry.Offset(translate + 300f, 300f)
                )
            )
    )
}

// ---------- shared connection-status bar ----------

/**
 * Thin live bar reflecting the real WebSocket connection state — follows the
 * client across the calculator disguise's hide/reveal recreation cycle.
 * Hidden entirely while connected so it never adds permanent chrome.
 */
@Composable
fun ConnectionStatusBar() {
    val state by SecureMessengerApp.instance.connectionState.collectAsState()
    AnimatedVisibility(visible = state !is ConnectionState.Connected) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 1.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dims.s16, vertical = Dims.s6),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (state) {
                    is ConnectionState.Connecting -> {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(Dims.s8))
                        Text(
                            "جارٍ الاتصال…",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is ConnectionState.Error, is ConnectionState.Disconnected -> {
                        Icon(
                            Icons.Default.CloudOff, contentDescription = null,
                            modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(Dims.s8))
                        Text(
                            "غير متصل — سيُعاد المحاولة تلقائياً",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        TextButton(onClick = { SecureMessengerApp.instance.messagingClient?.retryNow() }) {
                            Text("إعادة المحاولة", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    else -> {}
                }
            }
        }
    }
}

// ---------- shared avatar ----------

private val avatarColors = listOf(
    Color(0xFF7F77DD), // purple
    Color(0xFF1D9E75), // green
    Color(0xFFBA7517), // amber
    Color(0xFFD4537E), // pink
    Color(0xFF2AABEE), // brand blue
    Color(0xFFD85A30)  // orange
)

private fun avatarInitials(name: String): String {
    val parts = name.trim().removePrefix("@").split(" ").filter { it.isNotBlank() }
    return when {
        parts.size >= 2 -> "${parts[0].first()}${parts[1].first()}".uppercase()
        parts.size == 1 -> parts[0].take(2).uppercase()
        else -> "?"
    }
}

/**
 * Gradient two-letter initials by default (like Telegram's placeholder
 * avatars); shows a real decrypted photo instead when [avatarBytes] is
 * provided, with an optional verified badge overlay.
 */
@Composable
fun Avatar(
    name: String,
    size: androidx.compose.ui.unit.Dp,
    id: String = name,
    avatarBytes: ByteArray? = null,
    isVerified: Boolean = false
) {
    val color = avatarColors[(id.hashCode() and 0x7fffffff) % avatarColors.size]
    val bitmap = remember(avatarBytes) {
        // Bounded decode for consistency with every other image path in this
        // file — avatars are locally-sourced (never transmitted, per
        // SecureRepository's avatar handling), but there's no reason to keep a
        // second, unbounded decode path around either.
        avatarBytes?.let { decodeSampledBitmap(it, maxDimension = 200)?.asImageBitmap() }
    }
    Box(modifier = Modifier.size(size)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .let { m -> if (bitmap == null) m.background(color) else m },
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(avatarInitials(name), color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
        if (isVerified) {
            Icon(
                imageVector = Icons.Default.Verified,
                contentDescription = "متحقق",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(size * 0.36f)
                    .align(Alignment.BottomEnd)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(1.dp)
            )
        }
    }
}
