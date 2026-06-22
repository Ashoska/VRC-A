package com.vrca.ui.screen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vrca.ui.common.CompactSectionCard
import com.vrca.ui.common.KitSectionHeader
import com.vrca.ui.common.KitStatusChip
import com.vrca.ui.common.KitTone
import com.vrca.ui.common.SelectPill
import com.vrca.ui.common.VrcaCardDialog
import com.vrca.ui.viewmodel.VrcaViewModel
import kotlinx.coroutines.delay

private val MAX_CYCLE_LINES = VrcaViewModel.MAX_CYCLE_LINES

// VRChat's chatbox hard limit — the combined output is trimmed past this, so
// the editors meter against it (mirrors VrcaViewModel.VRC_MAX_CHARS).
private const val VRC_CHAR_BUDGET = 144

@Composable
internal fun AutomationsPage(vm: VrcaViewModel, isBanned: Boolean) {
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
                supportingText = { CharBudgetMeter(vm.resolveTokens(vm.afkMessage).length) },
                enabled = !isBanned
            )

            TokensHint()

            KitSectionHeader(title = "Presets", trailingValue = "tap to switch · hold to peek")
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
                        equipped = slot == vm.selectedAfkPreset,
                        enabled = !isBanned,
                        onLoad = { vm.selectAfkPreset(slot) },
                        onPeek = {
                            peek = PresetPeek(
                                title = "Pinned preset $slot",
                                subtitle = if (content.isBlank()) "Empty" else "${content.length} characters",
                                content = content,
                                icon = Icons.Filled.PushPin,
                                numbered = false,
                                onLoad = { vm.selectAfkPreset(slot) }
                            )
                        }
                    )
                }
            }
        }

        // =========================
        // Cycle — collapsed = "5 lines · 10s · now: '…'", expanded = editor.
        // =========================
        // Live "now sending" highlight needs a ticker — cycleIndex isn't observable,
        // so pulse a counter every second while sending to recompose the rows.
        var cycleTick by remember { mutableIntStateOf(0) }
        LaunchedEffect(vm.oscSending, vm.cycleEnabled) {
            while (vm.oscSending && vm.cycleEnabled) {
                delay(1000L)
                cycleTick++
            }
        }
        val sending = vm.oscSending && vm.cycleEnabled
        val cycleNow = if (sending) vm.cycleCurrentLine() else ""
        val activeRaw = remember(cycleTick, sending, vm.cycleLines.size) {
            if (sending) vm.cycleActiveRawIndex() else -1
        }
        CompactSectionCard(
            title = "Cycle",
            icon = Icons.Filled.Loop,
            summary = buildString {
                val live = vm.cycleLines.count { it.isNotBlank() }
                append("$live lines · ${vm.cycleIntervalSeconds}s")
                if (vm.cycleShuffle) append(" · shuffle")
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
                Text("No lines yet. Tap Add line.", style = MaterialTheme.typography.bodySmall)
            }

            // Compact line rows: inline number, text field, a mute dot, and an
            // overflow menu (move up/down, duplicate, delete) so all the new
            // controls stay on one slim row. The currently-sending line is
            // highlighted live while the cycle runs.
            //
            // Mute repaint: a SnapshotStateList index set (cycleLineEnabled[i]=x)
            // was NOT reliably invalidating this scope, and a bare
            // `@Suppress("UNUSED_EXPRESSION") vm.cycleMuteRev` read could be
            // dropped as a discarded statement — so the eye/shading only updated
            // after some other interaction. Fix: read cycleMuteRev into a USED
            // value (a remember key) and materialize the enabled flags off it, so
            // every mute toggle provably recomposes the parent and re-feeds each
            // row a fresh lineEnabled (no row-identity change, so text/focus stay).
            val muteRev = vm.cycleMuteRev
            val lineCount = vm.cycleLines.size
            val enabledFlags = remember(muteRev, lineCount) {
                List(lineCount) { vm.cycleLineEnabled.getOrElse(it) { true } }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                vm.cycleLines.forEachIndexed { idx, _ ->
                    val fieldValue =
                        cycleLineFields[idx] ?: TextFieldValue(vm.cycleLines.getOrNull(idx).orEmpty())
                    CycleLineRow(
                        index = idx,
                        count = vm.cycleLines.size,
                        value = fieldValue,
                        lineEnabled = enabledFlags.getOrElse(idx) { true },
                        // Count the RESOLVED token length, not the literal "{world}".
                        resolvedLength = vm.resolveTokens(fieldValue.text).length,
                        isActive = idx == activeRaw,
                        onValueChange = { v: TextFieldValue ->
                            cycleLineFields[idx] = v
                            vm.updateCycleLine(idx, v.text)
                        },
                        onToggleEnabled = { vm.setCycleLineEnabled(idx, it) },
                        onDuplicate = { vm.duplicateCycleLine(idx) },
                        onMoveUp = { vm.moveCycleLine(idx, idx - 1) },
                        onMoveDown = { vm.moveCycleLine(idx, idx + 1) },
                        onDelete = { vm.removeCycleLine(idx) },
                        canDuplicate = vm.cycleLines.size < MAX_CYCLE_LINES,
                        enabled = !isBanned
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { vm.addCycleLine() },
                    enabled = !isBanned && vm.cycleLines.size < MAX_CYCLE_LINES,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add line (${vm.cycleLines.size}/$MAX_CYCLE_LINES)")
                }

                OutlinedButton(
                    onClick = { vm.clearCycleLines() },
                    enabled = !isBanned && vm.cycleLines.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) { Text("Clear") }
            }

            // Dynamic tokens hint — substituted live at send time.
            TokensHint()

            // Shuffle mode — random rotation that avoids repeating the last lines.
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(start = 14.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Shuffle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Shuffle order", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Random, without repeating recent lines",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = vm.cycleShuffle,
                        onCheckedChange = { vm.setCycleShuffleFlag(it) },
                        enabled = !isBanned
                    )
                }
            }

            // Cycle speed — inline one-tap chips (no dropdown menu to open).
            KitSectionHeader(title = "Cycle speed")
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(2, 5, 10, 20, 40).forEach { sec ->
                    SelectPill(
                        label = "${sec}s",
                        selected = vm.cycleIntervalSeconds == sec,
                        enabled = !isBanned,
                        onClick = { vm.updateCycleIntervalSeconds(sec) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            KitSectionHeader(title = "Presets", trailingValue = "tap to switch · hold to peek")
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                (1..5).forEach { slot ->
                    val firstLine = vm.getCyclePresetPreview(slot)
                    val full = vm.getCyclePresetFull(slot)
                    PresetChip(
                        slot = slot,
                        preview = firstLine,
                        equipped = slot == vm.selectedCyclePreset,
                        enabled = !isBanned,
                        onLoad = { vm.selectCyclePreset(slot) },
                        onPeek = {
                            peek = PresetPeek(
                                title = "Cycle preset $slot",
                                subtitle = vm.getCyclePresetSubtitle(slot),
                                content = full,
                                icon = Icons.Filled.Loop,
                                numbered = true,
                                onLoad = { vm.selectCyclePreset(slot) }
                            )
                        }
                    )
                }
            }
        }
    }

    // Preset peek dialog (long-press): iconed header + per-line content + switch.
    peek?.let { p ->
        VrcaCardDialog(onDismiss = { peek = null }) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header: tinted icon circle + title/subtitle + close affordance.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                p.icon, null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            p.title,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (p.subtitle.isNotBlank()) {
                            Text(
                                p.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        onClick = { peek = null },
                        shape = androidx.compose.foundation.shape.CircleShape,
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
                // Content: each non-blank line as its own numbered (cycle) or plain
                // (pinned) row in a tinted card — far cleaner than a raw monospace blob.
                val lines = p.content.lines().map { it.trim() }.filter { it.isNotEmpty() }
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (lines.isEmpty()) {
                        Text(
                            "This preset is empty.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 300.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(vertical = 8.dp)
                        ) {
                            lines.forEachIndexed { i, line ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    if (p.numbered) {
                                        Text(
                                            "${i + 1}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.width(22.dp)
                                        )
                                    }
                                    Text(
                                        line,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { peek = null }) { Text("Close") }
                    Button(
                        onClick = { p.onLoad(); peek = null },
                        enabled = !isBanned
                    ) { Text("Switch to this preset") }
                }
            }
        }
    }
}

private data class PresetPeek(
    val title: String,
    val subtitle: String,
    val content: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val numbered: Boolean,
    val onLoad: () -> Unit
)

/**
 * Dynamic-tokens hint shown under both the Pinned and Cycle editors. The tokens
 * substitute live at send time; the char meter counts the RESOLVED length.
 */
@Composable
private fun TokensHint() {
    Text(
        "Currently experimenting, feel free to try by putting these tags into your " +
            "pinned and cycle message:  {time}  {song}  {world}  {players}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

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

/**
 * One compact cycle line, restyled for the revamp: a slim bordered field with an
 * inline tappable number badge (mutes the line — dims when off), the text field,
 * an always-on per-line char meter, and a single overflow menu carrying the new
 * controls (move up/down, duplicate, delete). The line currently being sent is
 * highlighted live ([isActive]). Tokens like {time}/{song} substitute at send time.
 */
@Composable
private fun CycleLineRow(
    index: Int,
    count: Int,
    value: TextFieldValue,
    lineEnabled: Boolean,
    resolvedLength: Int,
    isActive: Boolean,
    onValueChange: (TextFieldValue) -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDuplicate: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    canDuplicate: Boolean,
    enabled: Boolean
) {
    var menuOpen by remember { mutableStateOf(false) }
    // When this field is focused, let a long line wrap onto multiple lines so the
    // whole thing is visible while typing; collapse back to one line on blur.
    var focused by remember { mutableStateOf(false) }
    val container = when {
        isActive -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        !lineEnabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    }
    val dim = if (lineEnabled) 1f else 0.5f
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = container,
        border = if (isActive)
            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        else null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 44.dp)
                .padding(start = 6.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Number badge doubles as the mute toggle (most-used per-line action).
            Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = if (lineEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f),
                onClick = { if (enabled) onToggleEnabled(!lineEnabled) },
                enabled = enabled,
                modifier = Modifier.size(28.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (lineEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (value.text.isEmpty()) {
                    Text(
                        "Line ${index + 1}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = !focused,
                    maxLines = if (focused) 6 else 1,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim)
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused }
                )
            }
            if (value.text.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                CharBudgetMeter(resolvedLength)
            }
            // Mute quick-toggle icon (clear affordance alongside the number badge).
            IconButton(
                onClick = { onToggleEnabled(!lineEnabled) },
                enabled = enabled,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    if (lineEnabled) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    contentDescription = if (lineEnabled) "Mute line ${index + 1}" else "Unmute line ${index + 1}",
                    modifier = Modifier.size(18.dp),
                    tint = if (lineEnabled) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary
                )
            }
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    enabled = enabled,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "Line ${index + 1} actions",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Move up") },
                        enabled = index > 0,
                        leadingIcon = { Icon(Icons.Filled.ArrowUpward, null) },
                        onClick = { menuOpen = false; onMoveUp() }
                    )
                    DropdownMenuItem(
                        text = { Text("Move down") },
                        enabled = index < count - 1,
                        leadingIcon = { Icon(Icons.Filled.ArrowDownward, null) },
                        onClick = { menuOpen = false; onMoveDown() }
                    )
                    DropdownMenuItem(
                        text = { Text("Duplicate") },
                        enabled = canDuplicate,
                        leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                        onClick = { menuOpen = false; onDuplicate() }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = {
                            Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error)
                        },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
            }
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
