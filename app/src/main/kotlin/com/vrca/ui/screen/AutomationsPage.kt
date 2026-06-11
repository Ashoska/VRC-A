package com.vrca.ui.screen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vrca.ui.common.CompactSectionCard
import com.vrca.ui.common.KitSectionHeader
import com.vrca.ui.common.KitStatusChip
import com.vrca.ui.common.KitTone
import com.vrca.ui.viewmodel.VrcaViewModel
import kotlinx.coroutines.launch

// VRChat's chatbox hard limit — the combined output is trimmed past this, so
// the editors meter against it (mirrors VrcaViewModel.VRC_MAX_CHARS).
private const val VRC_CHAR_BUDGET = 144

@Composable
internal fun AutomationsPage(vm: VrcaViewModel, isBanned: Boolean) {
    val scope = rememberCoroutineScope()

    val cycleLineFields = remember { mutableStateMapOf<Int, TextFieldValue>() }

    fun syncCycleLineFieldsFromVm() {
        val valid = vm.cycleLines.indices.toSet()
        cycleLineFields.keys.toList().forEach { if (it !in valid) cycleLineFields.remove(it) }
        vm.cycleLines.forEachIndexed { idx, text ->
            val existing = cycleLineFields[idx]
            if (existing == null || existing.text != text) cycleLineFields[idx] = TextFieldValue(text)
        }
    }

    LaunchedEffect(vm.cycleLines.size) { syncCycleLineFieldsFromVm() }
    LaunchedEffect(vm.cycleLines.toList()) { syncCycleLineFieldsFromVm() }

    // Preset peek dialog state — set by long-pressing a preset chip.
    var peek by remember { mutableStateOf<PresetPeek?>(null) }

    PageContainer {
        // =========================
        // Pinned — collapsed = status ("'msg' · ON"), expanded = editor.
        // =========================
        CompactSectionCard(
            title = "Pinned",
            icon = Icons.Filled.PushPin,
            summary = vm.afkMessage.trim().ifBlank { "No message set" },
            trailing = {
                KitStatusChip(
                    if (vm.afkEnabled) "ON" else "OFF",
                    if (vm.afkEnabled) KitTone.Success else KitTone.Neutral
                )
            }
        ) {
            OutlinedTextField(
                value = vm.afkMessage,
                onValueChange = { s: String -> vm.updateAfkText(s) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Pinned text") },
                supportingText = { CharBudgetMeter(vm.afkMessage.length) },
                enabled = !isBanned
            )

            KitSectionHeader(title = "Presets", trailingValue = "tap to load · hold to peek")
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                (1..3).forEach { slot ->
                    val content = vm.getAfkPresetPreview(slot)
                    PresetChip(
                        slot = slot,
                        preview = content,
                        equipped = content.isNotBlank() && content == vm.afkMessage.trim(),
                        enabled = !isBanned,
                        onLoad = { scope.launch { vm.loadAfkPreset(slot) } },
                        onPeek = {
                            peek = PresetPeek(
                                title = "Pinned preset $slot",
                                content = content,
                                onLoad = { scope.launch { vm.loadAfkPreset(slot) } },
                                onSave = { scope.launch { vm.saveAfkPreset(slot, vm.afkMessage) } }
                            )
                        }
                    )
                }
            }
        }

        // =========================
        // Cycle — collapsed = "5 lines · 10s · now: '…'", expanded = editor.
        // =========================
        val cycleNow = if (vm.oscSending && vm.cycleEnabled) vm.cycleCurrentLine() else ""
        CompactSectionCard(
            title = "Cycle",
            icon = Icons.Filled.Loop,
            summary = buildString {
                append("${vm.cycleLines.count { it.isNotBlank() }} lines · ${vm.cycleIntervalSeconds}s")
                if (cycleNow.isNotBlank()) append(" · now: “$cycleNow”")
            },
            trailing = {
                KitStatusChip(
                    if (vm.cycleEnabled) "ON" else "OFF",
                    if (vm.cycleEnabled) KitTone.Success else KitTone.Neutral
                )
            }
        ) {
            if (vm.cycleLines.isEmpty()) {
                Text("No lines yet. Tap Add Line.", style = MaterialTheme.typography.bodySmall)
            }

            vm.cycleLines.forEachIndexed { idx, _ ->
                val fieldValue =
                    cycleLineFields[idx] ?: TextFieldValue(vm.cycleLines.getOrNull(idx).orEmpty())

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = fieldValue,
                        onValueChange = { v: TextFieldValue ->
                            cycleLineFields[idx] = v
                            vm.updateCycleLine(idx, v.text)
                        },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Line ${idx + 1}") },
                        // Per-line meter only once the line approaches the
                        // budget — 10 always-on meters would just be noise.
                        supportingText = if (fieldValue.text.length > 100) {
                            { CharBudgetMeter(fieldValue.text.length) }
                        } else null,
                        enabled = !isBanned
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { vm.removeCycleLine(idx) }, enabled = !isBanned) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove line")
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { vm.addCycleLine() },
                    enabled = !isBanned && vm.cycleLines.size < 10,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add line (${vm.cycleLines.size}/10)")
                }

                OutlinedButton(
                    onClick = { vm.clearCycleLines() },
                    enabled = !isBanned && vm.cycleLines.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) { Text("Clear") }
            }

            // Cycle speed dropdown
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Cycle speed:", style = MaterialTheme.typography.bodySmall)
                var cycleSpeedMenuOpen by remember { mutableStateOf(false) }
                val cycleSpeedOptions = listOf(2, 5, 10, 20, 40)
                Box {
                    OutlinedButton(
                        onClick = { cycleSpeedMenuOpen = true },
                        enabled = !isBanned,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("${vm.cycleIntervalSeconds}s", style = MaterialTheme.typography.bodySmall)
                        Icon(Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(
                        expanded = cycleSpeedMenuOpen,
                        onDismissRequest = { cycleSpeedMenuOpen = false }
                    ) {
                        cycleSpeedOptions.forEach { sec ->
                            DropdownMenuItem(
                                text = { Text("${sec} seconds") },
                                onClick = {
                                    vm.updateCycleIntervalSeconds(sec)
                                    cycleSpeedMenuOpen = false
                                }
                            )
                        }
                    }
                }
            }

            KitSectionHeader(title = "Presets", trailingValue = "tap to load · hold to peek")
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                (1..5).forEach { slot ->
                    val firstLine = vm.getCyclePresetPreview(slot)
                    val full = vm.getCyclePresetFull(slot)
                    val equipped = full.isNotBlank() &&
                        full.lines().map { it.trim() }.filter { it.isNotEmpty() } ==
                        vm.cycleLines.map { it.trim() }.filter { it.isNotEmpty() }
                    PresetChip(
                        slot = slot,
                        preview = firstLine,
                        equipped = equipped,
                        enabled = !isBanned,
                        onLoad = { scope.launch { vm.loadCyclePreset(slot) } },
                        onPeek = {
                            peek = PresetPeek(
                                title = "Cycle preset $slot",
                                content = full,
                                onLoad = { scope.launch { vm.loadCyclePreset(slot) } },
                                onSave = { scope.launch { vm.saveCyclePreset(slot, vm.cycleLines.toList()) } }
                            )
                        }
                    )
                }
            }
        }
    }

    // Preset peek dialog (long-press): full content + Load / Save-here.
    peek?.let { p ->
        AlertDialog(
            onDismissRequest = { peek = null },
            title = { Text(p.title) },
            text = {
                Text(
                    p.content.ifBlank { "(empty)" },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                Button(
                    onClick = { p.onLoad(); peek = null },
                    enabled = !isBanned && p.content.isNotBlank()
                ) { Text("Load") }
            },
            dismissButton = {
                TextButton(
                    onClick = { p.onSave(); peek = null },
                    enabled = !isBanned
                ) { Text("Save current here") }
            }
        )
    }
}

private data class PresetPeek(
    val title: String,
    val content: String,
    val onLoad: () -> Unit,
    val onSave: () -> Unit
)

/**
 * Horizontal preset chip: slot number + first words of the content. Tap to
 * equip; long-press to peek the full content (and save the current text into
 * the slot). Highlight = the slot whose content matches what's equipped now —
 * no separate "Selected" label.
 */
@Composable
private fun PresetChip(
    slot: Int,
    preview: String,
    equipped: Boolean,
    enabled: Boolean,
    onLoad: () -> Unit,
    onPeek: () -> Unit
) {
    val container =
        if (equipped) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        else MaterialTheme.colorScheme.surfaceVariant
    val contentColor =
        if (equipped) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        shape = MaterialTheme.shapes.large,
        color = container,
        modifier = Modifier
            .widthIn(min = 72.dp, max = 160.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onTap = { onLoad() },
                    onLongPress = { onPeek() }
                )
            }
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "$slot",
                style = MaterialTheme.typography.labelLarge,
                color = contentColor
            )
            Text(
                preview.ifBlank { "empty" },
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Inline "87/144" budget meter — warns before the trim happens instead of
 *  after. Amber when close, red when the line alone would already be cut. */
@Composable
private fun CharBudgetMeter(length: Int) {
    val color = when {
        length > VRC_CHAR_BUDGET -> MaterialTheme.colorScheme.error
        length > VRC_CHAR_BUDGET - 30 -> com.vrca.ui.theme.SlimeWarning
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text("$length/$VRC_CHAR_BUDGET", style = MaterialTheme.typography.labelSmall, color = color)
}
