package com.securemessenger.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * A back-navigation arrow that points the right way in RTL layouts. This
 * project's Compose Material Icons version predates the built-in
 * Icons.AutoMirrored set, so the fixed-direction arrow is flipped manually
 * instead of just showing a left-pointing arrow regardless of layout direction.
 */
@Composable
fun BackIcon(contentDescription: String? = "رجوع") {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Icon(
        imageVector = Icons.Default.ArrowBack,
        contentDescription = contentDescription,
        modifier = Modifier.graphicsLayer(scaleX = if (isRtl) -1f else 1f)
    )
}
