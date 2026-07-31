package com.securemessenger.app.ui.screens.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.securemessenger.app.ui.glassCard
import com.securemessenger.core.crypto.MediaCodec
import com.securemessenger.app.data.model.EncryptedMessage
import com.securemessenger.app.ui.theme.LocalMessengerColors

/** Reusable tiles for [ContactDetailScreen]. */

@Composable
internal fun MediaGridTile(
    message: EncryptedMessage,
    repository: com.securemessenger.app.data.repository.SecureRepository,
    onImageClick: (androidx.compose.ui.graphics.ImageBitmap) -> Unit
) {
    val mc = LocalMessengerColors.current
    val local = remember(message.id) { repository.decryptMediaDescriptor(message) }
    var bitmap by remember(message.id) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }

    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(message.id) {
        if (local?.mediaType == MediaCodec.TYPE_IMAGE) {
            val bytes = repository.loadDecryptedMediaBytes(local)
            // This used to be a raw BitmapFactory.decodeByteArray with no
            // bound at all — the only image surface in the app that had none,
            // despite showing exactly the same peer-controlled bytes as the
            // chat bubbles. A grid tile is ~100dp, so 400px is already generous
            // and the missing bound was pure exposure: a small file declaring
            // an enormous pixel size would have attempted a huge allocation
            // here. Now bounded AND sandboxed, like every other image path.
            bitmap = bytes?.let { decodeGuarded(context, it, maxDimension = 400)?.asImageBitmap() }
        }
    }

    val bmp = bitmap
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .glassCard(radius = 6.dp)
            .then(if (bmp != null) Modifier.clickable { onImageClick(bmp) } else Modifier),
        contentAlignment = Alignment.Center
    ) {
        when {
            bmp != null -> Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            local?.mediaType == MediaCodec.TYPE_VIDEO -> Icon(Icons.Default.Movie, contentDescription = null)
            local?.mediaType == MediaCodec.TYPE_AUDIO -> Icon(Icons.Default.Mic, contentDescription = null)
            else -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }
    }
}
