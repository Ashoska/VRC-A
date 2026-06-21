package com.vrca.ui.common

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * The shared card shell every VRC-A popup is built on, so confirms / pickers /
 * editors all read with the same Slime visual identity as [com.vrca.app] UpdateDialog
 * and the VRChat InstanceListDialog: a near-full-width [ElevatedCard] (deep surface,
 * rounded `large` corners, 6dp elevation) instead of a stock M3 AlertDialog. Use this
 * for new dialogs rather than `AlertDialog`.
 */
@Composable
fun VrcaCardDialog(
    onDismiss: () -> Unit,
    dismissOnOutsideTap: Boolean = true,
    content: @Composable () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = dismissOnOutsideTap
        )
    ) {
        ElevatedCard(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            content()
        }
    }
}

/** A header row: optional icon-in-tinted-circle, title, and an optional dismiss X. */
@Composable
private fun DialogHeader(
    title: String,
    icon: ImageVector? = null,
    iconTint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    onClose: (() -> Unit)? = null
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Surface(
                shape = CircleShape,
                color = iconTint.copy(alpha = 0.15f),
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = iconTint, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (onClose != null) {
            Spacer(Modifier.width(8.dp))
            Surface(
                onClick = onClose,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                modifier = Modifier.size(34.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Close, "Close",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * Shared, compact confirm dialog so every "are you sure?" popup across the public
 * AND admin builds reads the same way. Card-styled (see [VrcaCardDialog]).
 *
 * [destructive] tints the confirm button error-red and shows a warning glyph.
 * Copy must avoid em dashes (app-wide rule for user-visible strings).
 */
@Composable
fun VrcaConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    confirmEnabled: Boolean = true,
    dismissLabel: String = "Cancel"
) {
    VrcaCardDialog(onDismiss = onDismiss) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            DialogHeader(
                title = title,
                icon = if (destructive) Icons.Filled.WarningAmber else null,
                iconTint = MaterialTheme.colorScheme.error
            )
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text(dismissLabel) }
                Button(
                    onClick = onConfirm,
                    enabled = confirmEnabled,
                    colors = if (destructive) ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ) else ButtonDefaults.buttonColors()
                ) { Text(confirmLabel) }
            }
        }
    }
}

/**
 * Compact single-select picker rendered as a scannable grid of pills inside a
 * card dialog. The current [selected] pill is highlighted; tapping selects + closes.
 */
@Composable
fun VrcaSingleSelectDialog(
    title: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    columns: Int = 3
) {
    VrcaCardDialog(onDismiss = onDismiss) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            DialogHeader(title = title, onClose = onDismiss)
            Box(Modifier.heightIn(max = 360.dp)) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(options) { opt ->
                        SelectPill(
                            label = opt,
                            selected = opt == selected,
                            onClick = { onSelect(opt); onDismiss() },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

/**
 * Searchable, DST-correct time-zone picker. Shows "Device" first, then a curated
 * common list (or live search results across every IANA zone). Each row reads
 * "Paris 3:42 PM (UTC+2)" with the offset computed live so it follows daylight
 * savings. [currentMode] is the stored timeMode ("Device" / IANA id / legacy "UTC+N").
 */
@Composable
fun VrcaTimeZoneDialog(
    currentMode: String,
    use24h: Boolean,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val results = remember(query) {
        val q = query.trim()
        if (q.isEmpty()) commonTimeZoneIds
        else allTimeZoneIds.filter {
            timeZoneCity(it).contains(q, ignoreCase = true) ||
                timeZoneRegion(it).contains(q, ignoreCase = true) ||
                it.contains(q, ignoreCase = true) ||
                zoneOffsetLabel(resolveTimeZone(it)).contains(q, ignoreCase = true)
        }.take(80)
    }
    val deviceSelected = currentMode == "Device" || currentMode == "LOCAL" || currentMode.isBlank()

    VrcaCardDialog(onDismiss = onDismiss) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DialogHeader(title = "Time zone", icon = Icons.Filled.Public, onClose = onDismiss)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text("Search a city or region") },
                modifier = Modifier.fillMaxWidth()
            )
            Column(
                Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (query.isBlank()) {
                    TimeZoneRow(
                        label = "Device timezone (${zoneOffsetLabel(resolveTimeZone("Device"))})",
                        selected = deviceSelected,
                        onClick = { onSelect("Device"); onDismiss() }
                    )
                }
                results.forEach { id ->
                    TimeZoneRow(
                        label = timeZoneLabel(id, use24h),
                        selected = !deviceSelected && currentMode == id,
                        onClick = { onSelect(id); onDismiss() }
                    )
                }
                if (results.isEmpty()) {
                    Text(
                        "No zones match \"${query.trim()}\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TimeZoneRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val container =
        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        else MaterialTheme.colorScheme.surfaceVariant
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = container,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                Icon(
                    Icons.Filled.Check, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * A small selectable pill. Highlighted when [selected]. Reused by the single-select
 * grid and the inline cycle-speed selector so both read identically.
 */
@Composable
fun SelectPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val container =
        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
        else MaterialTheme.colorScheme.surfaceVariant
    val content =
        if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = MaterialTheme.shapes.large,
        color = container,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        )
    }
}

/**
 * Canonical user-facing copy for the device-owner's OWN account sign-out flows, so
 * the VRChat tab and Settings show identical wording (they previously diverged).
 * Admin remote-logout dialogs are a DIFFERENT action (acting on another user) and
 * keep their own copy. No em dashes.
 */
object VrcaDialogCopy {
    const val VRC_SIGN_OUT_TITLE = "Sign out of VRChat?"
    const val VRC_SIGN_OUT_BODY =
        "Notifications and presence stop, and chatbox sending is blocked until you " +
        "sign back in. Your toggles and messages are kept."

    const val DISCORD_SIGN_OUT_TITLE = "Sign out of Discord?"
    const val DISCORD_SIGN_OUT_BODY =
        "Rich Presence stops and the on-device Discord session is cleared. Signing " +
        "out may also sign Discord out on your other devices."
}
