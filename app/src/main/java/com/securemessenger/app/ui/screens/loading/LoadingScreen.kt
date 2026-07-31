package com.securemessenger.app.ui.screens.loading

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.securemessenger.app.ui.onboardingBackground
import com.securemessenger.app.ui.theme.LocalMessengerColors
import com.securemessenger.app.ui.theme.MessengerTheme
import com.securemessenger.app.ui.theme.SemanticColors

/** Shown briefly while an existing account unlocks itself with its device-stored key. */
@Composable
fun LoadingScreen() {
    val mc = LocalMessengerColors.current
    Box(
        modifier = Modifier.fillMaxSize().onboardingBackground(mc.onboardingGradient),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(26.dp)) {
            Box(
                modifier = Modifier
                    .size(78.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, SemanticColors.purple))),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = Color.White, modifier = Modifier.size(38.dp))
            }
            Box(
                modifier = Modifier
                    .width(150.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(mc.glassOnCard.copy(alpha = 0.12f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.6f)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
            Text(
                "جارٍ فك تشفير بياناتك…",
                style = MaterialTheme.typography.bodyMedium,
                color = mc.receivedMeta
            )
        }
    }
}

@Preview(name = "Loading — dark", showBackground = true)
@Composable
private fun LoadingScreenDarkPreview() {
    MessengerTheme(darkTheme = true) { LoadingScreen() }
}

@Preview(name = "Loading — light", showBackground = true)
@Composable
private fun LoadingScreenLightPreview() {
    MessengerTheme(darkTheme = false) { LoadingScreen() }
}
