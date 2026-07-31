package com.securemessenger.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.securemessenger.app.ui.glassCard
import com.securemessenger.app.ui.theme.LocalMessengerColors

/** Reusable row/section building blocks shared across [SettingsScreen]'s sections. */

@Composable
internal fun GlossaryItem(term: String, explanation: String) {
    val mc = LocalMessengerColors.current
    Column {
        Text(term, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
        Text(explanation, style = MaterialTheme.typography.bodySmall, color = mc.glassOnCard.copy(alpha = 0.75f))
    }
}

/** Small muted uppercase-style label above a settings group card, matching the reference. */
@Composable
internal fun SettingsGroupLabel(title: String) {
    val mc = LocalMessengerColors.current
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = mc.glassOnCard.copy(alpha = 0.45f),
        modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 6.dp)
    )
}

/** A single rounded card grouping several [SettingsRow]s, with a hairline divider between them. */
@Composable
internal fun SettingsGroupCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassCard()
    ) {
        content()
    }
}

@Composable
internal fun SettingsDivider() {
    val mc = LocalMessengerColors.current
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 45.dp)
            .height(0.5.dp)
            .background(mc.divider)
    )
}

/**
 * One row inside a [SettingsGroupCard]: a distinctly-tinted leading icon (per
 * Telegram's colorful-icon convention), a title, and a trailing slot for
 * whatever the row needs — a chevron, a switch, or a muted value label.
 */
@Composable
internal fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    title: String,
    onClick: (() -> Unit)? = null,
    danger: Boolean = false,
    trailing: @Composable () -> Unit = {}
) {
    val mc = LocalMessengerColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { m -> onClick?.let { m.clickable(onClick = it) } ?: m }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(19.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (danger) MaterialTheme.colorScheme.error else mc.glassOnCard,
            modifier = Modifier.weight(1f)
        )
        trailing()
    }
}

/** Trailing chevron used for rows that navigate elsewhere. */
@Composable
internal fun RowChevron() {
    val mc = LocalMessengerColors.current
    Icon(Icons.Default.ChevronLeft, contentDescription = null, tint = mc.glassOnCard.copy(alpha = 0.35f), modifier = Modifier.size(18.dp))
}

/** Trailing muted value text (e.g. the current theme choice). */
@Composable
internal fun RowValue(text: String) {
    val mc = LocalMessengerColors.current
    Text(text, style = MaterialTheme.typography.labelMedium, color = mc.glassOnCard.copy(alpha = 0.45f))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsDropdownSheet(
    title: String,
    options: List<String>,
    selectedOption: String,
    onDismiss: () -> Unit,
    onOptionSelected: (String) -> Unit
) {
    val mc = LocalMessengerColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = mc.glassCardStrong,
        titleContentColor = mc.glassOnCard,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOptionSelected(option); onDismiss() }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = option == selectedOption, onClick = { onOptionSelected(option); onDismiss() })
                        Spacer(Modifier.width(8.dp))
                        Text(option, color = mc.glassOnCard)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("إغلاق") }
        }
    )
}
