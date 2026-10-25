package com.vrca.ui.settings

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vrca.data.SettingsStates
import com.vrca.ui.viewmodel.VrcaViewModel
import kotlinx.coroutines.launch

// Small building blocks still used across Settings and other pages (the pre-revamp Home /
// Automations / Media pages that lived here were removed, 2026-10-23).

@Composable
private fun PageContainer(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}


@Composable
internal fun SectionCard(
    title: String,
    subtitle: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    ElevatedCard {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(text = title, style = MaterialTheme.typography.titleMedium)
                    if (!subtitle.isNullOrBlank()) {
                        Text(text = subtitle, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (actions != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
                }
            }
            content()
        }
    }
}

/* =========================
   HOME (Preview + Wizard)
   ========================= */

@Composable
internal fun ToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    description: String? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(label)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
internal fun SubSectionLabel(text: String, topPadding: Dp = 4.dp) {
    Spacer(Modifier.height(topPadding))
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

// -------------------------
// NEW: sectioned Settings page (UI-only, uses existing VM + SettingsStates)
// -------------------------

private enum class SettingsSection(val title: String) {
    General("General"),
    Interface("Interface"),
    Osc("OSC"),
    Notifications("Notifications"),
    About("About")
}

@Composable
fun SettingsPage(
    vm: VrcaViewModel,
    modifier: Modifier = Modifier
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val uiState by vm.messengerUiState.collectAsState()

    var section by rememberSaveable { mutableStateOf(SettingsSection.General) }

    // Overlay toggle (existing app state)
    val overlayState = SettingsStates.overlayState()

    PageContainer {
        // Section chooser (left list, mobile-friendly)
        ElevatedCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SettingsSection.entries.forEach { s ->
                    val selected = (s == section)
                    com.vrca.ui.common.GremlinButton(
                        onClick = { section = s },
                        kind = if (selected) com.vrca.ui.common.GremlinButtonKind.Primary else com.vrca.ui.common.GremlinButtonKind.Secondary
                    ) { Text(s.title) }
                }
            }
        }

        when (section) {
            SettingsSection.General -> {
                SectionCard(
                    title = "Setup",
                    subtitle = "Permissions that keep OSC + Now Playing stable."
                ) {
                    com.vrca.ui.common.GremlinButton(
                        onClick = { vm.launchNotificationAccess(ctx) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Open Notification Access") }

                    com.vrca.ui.common.GremlinSecondaryButton(
                        onClick = { ctx.startActivity(vm.overlayPermissionIntent()) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Open Overlay Permission") }

                    com.vrca.ui.common.GremlinSecondaryButton(
                        onClick = { ctx.startActivity(vm.batteryOptimizationIntent()) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Disable Battery Optimization (request)") }
                }

                SectionCard(
                    title = "Overlay",
                    subtitle = "Optional: only if you use the overlay."
                ) {
                    ToggleRow("Overlay enabled", overlayState.value) { overlayState.value = it }
                    Text(
                        text = if (Settings.canDrawOverlays(ctx)) "Permission: granted" else "Permission: not granted",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            SettingsSection.Interface -> {
                SectionCard(
                    title = "UI",
                    subtitle = "These only change how the app looks."
                ) {
                    ToggleRow("Pinned presets collapsed", vm.afkPresetsCollapsed) { vm.updateAfkPresetsCollapsed(it) }
                    ToggleRow("Cycle presets collapsed", vm.cyclePresetsCollapsed) { vm.updateCyclePresetsCollapsed(it) }
                }
            }

            SettingsSection.Osc -> {
                SectionCard(
                    title = "OSC Target",
                    subtitle = "Where Chatbox sends messages."
                ) {
                    var ip by rememberSaveable(stateSaver = TextFieldValue.Saver) {
                        mutableStateOf(TextFieldValue(uiState.ipAddress))
                    }
                    LaunchedEffect(uiState.ipAddress) {
                        if (ip.text.isBlank()) ip = TextFieldValue(uiState.ipAddress)
                    }

                    OutlinedTextField(
                        value = ip,
                        onValueChange = { ip = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Headset IP address") },
                        placeholder = { Text("Example: 192.168.1.23") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        com.vrca.ui.common.GremlinButton(
                            onClick = { vm.ipAddressApply(ip.text.trim()) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Apply") }

                        com.vrca.ui.common.GremlinSecondaryButton(
                            onClick = { ip = TextFieldValue(uiState.ipAddress) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Reset") }
                    }

                    Text(
                        text = "Current target: ${uiState.ipAddress}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            SettingsSection.Notifications -> {
                NotificationToggleSection(vm = vm)
            }

            SettingsSection.About -> {
                SectionCard(
                    title = "About",
                    subtitle = "Quick help + info"
                ) {
                    val text = """
Chatbox (VRChat Assistant)
Made by: Ashoska Mitsu Sisko

Home shows a live preview of what will appear in VRChat.
KILL stops everything and clears the chatbox.

Troubleshoot:
- Nothing appears: check IP + OSC enabled + same Wi-Fi
- Now Playing blank: enable Notification Access, restart app, play music
- Stops when screen off: disable battery optimization
                    """.trimIndent()

                    SelectionContainer {
                        Text(text = text, fontFamily = FontFamily.Monospace)
                    }

                    Divider()

                    com.vrca.ui.common.GremlinSecondaryButton(
                        onClick = { vm.launchNotificationAccess(ctx) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Info, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Open Notification Access (help)")
                    }
                }
            }
        }
    }
}
