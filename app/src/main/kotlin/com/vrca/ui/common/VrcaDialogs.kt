package com.vrca.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Shared, compact confirm dialog so every "are you sure?" popup across the public
 * AND admin builds reads the same way (consistent shape, body style, and a clear
 * destructive tint). Routes through M3 [AlertDialog] so it inherits the app's dark
 * dialog surface and rounded corners.
 *
 * [destructive] tints the confirm label error-red (sign-out, kill, retract, etc.).
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = confirmEnabled,
                colors = if (destructive) ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ) else ButtonDefaults.textButtonColors()
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissLabel) }
        }
    )
}

/**
 * Compact single-select picker rendered as a scannable grid of pills inside a
 * height-capped dialog — far tidier than a tall full-screen [androidx.compose.material3.DropdownMenu]
 * for long option lists (e.g. the ~29 timezone offsets). The current [selected]
 * pill is highlighted; tapping a pill selects it and closes the dialog.
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
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
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

/**
 * A small selectable pill. Highlighted when [selected]. Reused by the timezone
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
