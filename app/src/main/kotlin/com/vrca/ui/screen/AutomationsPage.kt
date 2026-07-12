package com.vrca.ui.screen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vrca.app.ChatboxSubLine
import com.vrca.app.SubLineCodec
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
    // Cycle fields are keyed by "slide:sub" now that each slide can hold up to 3
    // sub-lines (sub 0 is the main editor line). Seeded from the decoded sub-lines.
    val cycleLineFields = remember { mutableStateMapOf<String, TextFieldValue>() }

    fun syncCycleLineFieldsFromVm() {
        val valid = HashSet<String>()
        vm.cycleLines.forEachIndexed { slide, raw ->
            SubLineCodec.decode(raw).forEachIndexed { sub, s ->
                val k = "$slide:$sub"
                valid.add(k)
                val existing = cycleLineFields[k]
                if (existing == null || existing.text != s.text) cycleLineFields[k] = TextFieldValue(s.text)
            }
        }
        cycleLineFields.keys.toList().forEach { if (it !in valid) cycleLineFields.remove(it) }
    }

    LaunchedEffect(vm.cycleLines.size) { syncCycleLineFieldsFromVm() }
    LaunchedEffect(vm.cycleLines.toList()) { syncCycleLineFieldsFromVm() }
    // Per-slide expand state (sub-lines hidden until the slide is selected).
    val cycleExpanded = remember { mutableStateMapOf<Int, Boolean>() }

    // ---- Drag-to-reorder cycle lines ----
    // UI-only until drop: the picked row (cycleDragIndex) is STABLE for the whole
    // gesture and cycleDragOffsetY follows the finger; the actual reorder
    // (vm.moveCycleLine) happens ONCE on release. So an interrupted drag (invalid
    // drop, tab-away, crash) reverts for free — nothing was mutated. Other rows
    // shift via graphicsLayer to open a gap at the computed target. Offset reads
    // live inside graphicsLayer blocks (no per-frame recomposition).
    var cycleDragIndex by remember { mutableStateOf<Int?>(null) }
    val cycleDragOffsetY = remember { mutableFloatStateOf(0f) }
    val cycleRowHeights = remember { mutableStateMapOf<Int, Int>() }

    fun cycleDropTarget(origin: Int, offsetY: Float): Int {
        val n = vm.cycleLines.size
        if (n <= 1) return origin.coerceIn(0, n - 1)
        var i = origin
        if (offsetY > 0f) {
            var acc = offsetY
            while (i < n - 1) {
                val h = (cycleRowHeights[i + 1] ?: cycleRowHeights[origin] ?: 0).toFloat()
                if (h > 0f && acc > h / 2f) { acc -= h; i++ } else break
            }
        } else if (offsetY < 0f) {
            var acc = offsetY
            while (i > 0) {
                val h = (cycleRowHeights[i - 1] ?: cycleRowHeights[origin] ?: 0).toFloat()
                if (h > 0f && acc < -h / 2f) { acc += h; i-- } else break
            }
        }
        return i
    }

    // Vertical shift (px) to apply to a NON-dragged row to open the gap.
    fun cycleGapShift(idx: Int): Float {
        val from = cycleDragIndex ?: return 0f
        val to = cycleDropTarget(from, cycleDragOffsetY.floatValue)
        val dh = (cycleRowHeights[from] ?: 0).toFloat()
        return when {
            from < to && idx in (from + 1)..to -> -dh   // dragging down → rows above the gap slide up
            to < from && idx in to until from -> dh      // dragging up → rows below the gap slide down
            else -> 0f
        }
    }

    // Pinned sub-line fields, same cursor-safe hoisted-map pattern as the cycle
    // fields above: seed from vm.pinnedSubLines(), reseed only when the stored
    // value changes from a NON-keystroke source (e.g. a preset load), so typing
    // never jumps the cursor.
    val pinnedFields = remember { mutableStateMapOf<Int, TextFieldValue>() }
    fun syncPinnedFieldsFromVm() {
        val subs = vm.pinnedSubLines()
        val valid = subs.indices.toSet()
        pinnedFields.keys.toList().forEach { if (it !in valid) pinnedFields.remove(it) }
        subs.forEachIndexed { idx, sub ->
            val existing = pinnedFields[idx]
            if (existing == null || existing.text != sub.text) pinnedFields[idx] = TextFieldValue(sub.text)
        }
    }
    LaunchedEffect(vm.afkMessage) { syncPinnedFieldsFromVm() }
    // Sub-lines stay hidden until the user selects the line (taps its number/chevron).
    var pinnedExpanded by remember { mutableStateOf(false) }

    // Preset peek dialog state — set by long-pressing a preset chip.
    var peek by remember { mutableStateOf<PresetPeek?>(null) }

    // Preset previews + selection are snapshotted in the PAGE scope for the
    // same AnimatedVisibility-skip reason as the cycle mute flags below:
    // reads inside a CompactSectionCard content lambda don't reliably
    // retrigger it, so the auto-save writing into the selected slot left the
    // chips (and the long-press peek) showing STALE content until the user
    // tabbed out and back. Captured here, a preset edit changes the content
    // lambda instance and the chips re-run; key() at the call site forces the
    // repaint through.
    val afkPresetPreviews = List(3) { vm.getAfkPresetPreview(it + 1) }
    val selectedAfkSlot = vm.selectedAfkPreset
    // Snapshot the pinned sub-lines in PAGE scope (same reason as the cycle mute
    // flags below) so the CompactSectionCard content lambda re-runs when they
    // change (hide toggle / reorder / preset load), not just on tab re-entry.
    val pinnedSubs = vm.pinnedSubLines()
    val cyclePresetPreviews = List(5) { vm.getCyclePresetPreview(it + 1) }
    val selectedCycleSlot = vm.selectedCyclePreset

    PageContainer {
        // =========================
        // Pinned — collapsed = status ("'msg' · ON"), expanded = editor.
        // =========================
        CompactSectionCard(
            title = "Pinned",
            icon = Icons.Filled.PushPin,
            summary = com.vrca.app.SubLineCodec.renderVisible(vm.afkMessage)
                .replace("\n", "  /  ").ifBlank { "No message set" },
            trailing = {
                KitStatusChip(
                    if (vm.afkEnabled) "ON" else "OFF",
                    if (vm.afkEnabled) KitTone.Success else KitTone.Neutral
                )
            }
        ) {
            // The main line shows normally; tapping its number/chevron reveals up
            // to 2 small nested sub-lines (each its own chatbox row).
            SubLineEditor(
                subs = pinnedSubs,
                fields = pinnedFields,
                rowLabel = "Row",
                resolvedLengthOf = { vm.resolveTokens(it).length },
                enabled = !isBanned,
                expanded = pinnedExpanded,
                onToggleExpanded = { pinnedExpanded = !pinnedExpanded },
                onTextChanged = { i, t -> vm.setPinnedSubLineText(i, t) },
                onToggleHidden = { i, h -> vm.setPinnedSubLineHidden(i, h) },
                onMoveUp = { i -> vm.movePinnedSubLine(i, i - 1) },
                onMoveDown = { i -> vm.movePinnedSubLine(i, i + 1) },
                onDelete = { i -> vm.removePinnedSubLine(i) },
                onAdd = { vm.addPinnedSubLine() }
            )
            // Combined total only matters once there's more than one visible row —
            // for a single row the per-row meter already IS the total.
            if (com.vrca.app.SubLineCodec.visibleRowCount(vm.afkMessage) > 1) {
                Spacer(Modifier.height(2.dp))
                CharBudgetMeter(vm.resolveTokens(com.vrca.app.SubLineCodec.renderVisible(vm.afkMessage)).length)
            }

            TokensHint()

            KitSectionHeader(title = "Presets", trailingValue = "tap to switch · hold to peek")
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                (1..3).forEach { slot ->
                    val content = afkPresetPreviews[slot - 1]
                    key(slot, content, slot == selectedAfkSlot) {
                        PresetChip(
                            slot = slot,
                            preview = content,
                            equipped = slot == selectedAfkSlot,
                            enabled = !isBanned,
                            onLoad = { vm.selectAfkPreset(slot) },
                            onPeek = {
                                // Read FRESH at press time — a composition-captured
                                // value can be stale if the chip was skipped.
                                val fresh = vm.getAfkPresetPreview(slot)
                                peek = PresetPeek(
                                    title = "Pinned preset $slot",
                                    subtitle = if (fresh.isBlank()) "Empty" else "${fresh.length} characters",
                                    content = fresh,
                                    icon = Icons.Filled.PushPin,
                                    numbered = false,
                                    onLoad = { vm.selectAfkPreset(slot) }
                                )
                            }
                        )
                    }
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
        // Snapshot the per-line mute flags into a plain List HERE, in the page
        // scope. This is the SAME mechanism the live `activeRaw` highlight uses:
        // the value is captured by the CompactSectionCard content lambda below,
        // so when a mute toggle changes it the lambda instance changes and
        // AnimatedVisibility re-runs the rows. Reading cycleLineEnabled only
        // INSIDE the content lambda (or inside a memoized provider on the row)
        // did NOT work — nothing the content lambda *captures* changed on a mute
        // toggle, so AnimatedVisibility skipped re-invoking content (the header
        // summary updated, the rows did not). Each row gets a plain Boolean so
        // its value param actually differs and it can't be skipped.
        val cycleEnabledFlags: List<Boolean> = vm.cycleLineEnabled.toList()
        // Snapshot each slide's sub-lines in PAGE scope (same AnimatedVisibility-skip
        // reason as the mute flags) so editing a sub-line re-runs the card content.
        val cycleSubs: List<List<ChatboxSubLine>> = vm.cycleLines.map { SubLineCodec.decode(it) }
        // Page-scope snapshot of which row is being dragged (changes only on
        // pick-up/drop, so recomposing on it is cheap) — the per-frame offset is
        // read inside graphicsLayer blocks instead.
        val draggingIdx = cycleDragIndex
        CompactSectionCard(
            title = "Cycle",
            icon = Icons.Filled.Loop,
            summary = buildString {
                val live = cycleSubs.count { subs -> subs.any { !it.hidden && it.text.isNotBlank() } }
                val hidden = vm.cycleLineEnabled.count { !it }
                append("$live lines · ${vm.cycleIntervalSeconds}s")
                if (hidden > 0) append(" · $hidden hidden")
                if (vm.cycleShuffle) append(" · shuffle")
                if (cycleNow.isNotBlank()) append(" · now: “${cycleNow.replace("\n", " / ")}”")
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

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                vm.cycleLines.forEachIndexed { idx, _ ->
                    val lineEn = cycleEnabledFlags.getOrElse(idx) { true }
                    val subs = cycleSubs.getOrElse(idx) { listOf(ChatboxSubLine("", false)) }
                    val expanded = cycleExpanded[idx] ?: false
                    val canDrag = vm.cycleLines.size > 1 && !isBanned
                    val dragHandleMod = if (canDrag) Modifier.pointerInput(idx, vm.cycleLines.size) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { cycleDragIndex = idx; cycleDragOffsetY.floatValue = 0f },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                cycleDragOffsetY.floatValue += dragAmount.y
                            },
                            onDragEnd = {
                                val from = cycleDragIndex
                                if (from != null) {
                                    val to = cycleDropTarget(from, cycleDragOffsetY.floatValue)
                                    if (to != from) vm.moveCycleLine(from, to)
                                }
                                cycleDragIndex = null; cycleDragOffsetY.floatValue = 0f
                            },
                            onDragCancel = { cycleDragIndex = null; cycleDragOffsetY.floatValue = 0f }
                        )
                    } else Modifier
                    key(idx, lineEn, expanded, subs.size) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .zIndex(if (draggingIdx == idx) 1f else 0f)
                                .graphicsLayer {
                                    translationY =
                                        if (cycleDragIndex == idx) cycleDragOffsetY.floatValue
                                        else cycleGapShift(idx)
                                    shadowElevation = if (cycleDragIndex == idx) 10f else 0f
                                }
                                .onSizeChanged { cycleRowHeights[idx] = it.height }
                        ) {
                            // Main line (sub-line 0) — its number badge / chevron expands.
                            val mainField = cycleLineFields["$idx:0"]
                                ?: TextFieldValue(subs.firstOrNull()?.text.orEmpty())
                            CycleLineRow(
                                index = idx,
                                count = vm.cycleLines.size,
                                value = mainField,
                                lineEnabled = lineEn,
                                resolvedLength = vm.resolveTokens(mainField.text).length,
                                isActive = idx == activeRaw,
                                expanded = expanded,
                                subCount = (subs.size - 1).coerceAtLeast(0),
                                dragHandleModifier = dragHandleMod,
                                dragActive = draggingIdx == idx,
                                onExpand = { cycleExpanded[idx] = !expanded },
                                onValueChange = { v: TextFieldValue ->
                                    cycleLineFields["$idx:0"] = v
                                    vm.setCycleSubLineText(idx, 0, v.text)
                                },
                                onToggleEnabled = { vm.setCycleLineEnabled(idx, it) },
                                onDuplicate = { vm.duplicateCycleLine(idx) },
                                onMoveUp = { vm.moveCycleLine(idx, idx - 1) },
                                onMoveDown = { vm.moveCycleLine(idx, idx + 1) },
                                onDelete = { vm.removeCycleLine(idx) },
                                canDuplicate = vm.cycleLines.size < MAX_CYCLE_LINES,
                                enabled = !isBanned
                            )
                            if (expanded) {
                                for (s in 1 until subs.size) {
                                    val sub = subs[s]
                                    key(idx, s, sub.hidden) {
                                        val subField = cycleLineFields["$idx:$s"] ?: TextFieldValue(sub.text)
                                        SubLineRow(
                                            index = s,
                                            count = subs.size,
                                            rowLabel = "Row",
                                            compact = true,
                                            value = subField,
                                            hidden = sub.hidden,
                                            resolvedLength = vm.resolveTokens(subField.text).length,
                                            enabled = !isBanned,
                                            expandable = false,
                                            expanded = false,
                                            subCount = 0,
                                            onExpand = {},
                                            showOverflow = true,
                                            onValueChange = { v ->
                                                cycleLineFields["$idx:$s"] = v
                                                vm.setCycleSubLineText(idx, s, v.text)
                                            },
                                            onToggleHidden = { vm.setCycleSubLineHidden(idx, s, it) },
                                            onMoveUp = { vm.moveCycleSubLine(idx, s, s - 1) },
                                            onMoveDown = { vm.moveCycleSubLine(idx, s, s + 1) },
                                            onDelete = { vm.removeCycleSubLine(idx, s) }
                                        )
                                    }
                                }
                                Row(
                                    Modifier.padding(start = 22.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (subs.size < SubLineCodec.MAX_SUB_LINES) {
                                        TextButton(
                                            onClick = { vm.addCycleSubLine(idx) },
                                            enabled = !isBanned,
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Add row", style = MaterialTheme.typography.labelMedium)
                                        }
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    val visibleRows = subs.count { !it.hidden && it.text.isNotBlank() }
                                    val total = vm.resolveTokens(
                                        SubLineCodec.renderVisible(vm.cycleLines.getOrElse(idx) { "" })
                                    ).length
                                    Text(
                                        if (visibleRows > 1) "up to 3 rows · $total/$VRC_CHAR_BUDGET total"
                                        else "up to 3 chatbox lines",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }
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
                    val firstLine = cyclePresetPreviews[slot - 1]
                    key(slot, firstLine, slot == selectedCycleSlot) {
                        PresetChip(
                            slot = slot,
                            preview = firstLine,
                            equipped = slot == selectedCycleSlot,
                            enabled = !isBanned,
                            onLoad = { vm.selectCyclePreset(slot) },
                            onPeek = {
                                // Read FRESH at press time — a composition-captured
                                // value can be stale if the chip was skipped.
                                peek = PresetPeek(
                                    title = "Cycle preset $slot",
                                    subtitle = vm.getCyclePresetSubtitle(slot),
                                    content = vm.getCyclePresetFull(slot),
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

/* =========================
   Sub-line editor (shared by Pinned rows + Cycle-slide rows)
   The MAIN line (row 0) shows normally; tapping its number badge / chevron
   reveals up to 2 small nested sub-lines (each its own chatbox row) with hide /
   reorder / delete, plus "Add row" and the "up to 3 chatbox lines" caption.
   ========================= */
@Composable
private fun SubLineEditor(
    subs: List<ChatboxSubLine>,
    fields: SnapshotStateMap<Int, TextFieldValue>,
    rowLabel: String,
    resolvedLengthOf: (String) -> Int,
    enabled: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onTextChanged: (Int, String) -> Unit,
    onToggleHidden: (Int, Boolean) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onAdd: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Main line — always visible, full size; number/chevron expand the rest.
        val main = subs.firstOrNull() ?: ChatboxSubLine("", false)
        SubLineRow(
            index = 0,
            count = subs.size,
            rowLabel = rowLabel,
            compact = false,
            value = fields[0] ?: TextFieldValue(main.text),
            hidden = main.hidden,
            resolvedLength = resolvedLengthOf(fields[0]?.text ?: main.text),
            enabled = enabled,
            expandable = true,
            expanded = expanded,
            subCount = (subs.size - 1).coerceAtLeast(0),
            onExpand = onToggleExpanded,
            showOverflow = expanded && subs.size > 1,
            onValueChange = { v -> fields[0] = v; onTextChanged(0, v.text) },
            onToggleHidden = { onToggleHidden(0, it) },
            onMoveUp = {},
            onMoveDown = { onMoveDown(0) },
            onDelete = { onDelete(0) }
        )
        if (expanded) {
            // Nested sub-lines (index 1..) — small + indented so they read as
            // sub-lines of the main line, not standalone lines.
            for (i in 1 until subs.size) {
                val sub = subs[i]
                key(i, sub.hidden) {
                    SubLineRow(
                        index = i,
                        count = subs.size,
                        rowLabel = rowLabel,
                        compact = true,
                        value = fields[i] ?: TextFieldValue(sub.text),
                        hidden = sub.hidden,
                        resolvedLength = resolvedLengthOf(fields[i]?.text ?: sub.text),
                        enabled = enabled,
                        expandable = false,
                        expanded = false,
                        subCount = 0,
                        onExpand = {},
                        showOverflow = true,
                        onValueChange = { v -> fields[i] = v; onTextChanged(i, v.text) },
                        onToggleHidden = { onToggleHidden(i, it) },
                        onMoveUp = { onMoveUp(i) },
                        onMoveDown = { onMoveDown(i) },
                        onDelete = { onDelete(i) }
                    )
                }
            }
            Row(
                Modifier.padding(start = 22.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (subs.size < SubLineCodec.MAX_SUB_LINES) {
                    TextButton(
                        onClick = onAdd,
                        enabled = enabled,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add row", style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    "up to 3 chatbox lines",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun SubLineRow(
    index: Int,
    count: Int,
    rowLabel: String,
    compact: Boolean,
    value: TextFieldValue,
    hidden: Boolean,
    resolvedLength: Int,
    enabled: Boolean,
    expandable: Boolean,
    expanded: Boolean,
    subCount: Int,
    onExpand: () -> Unit,
    showOverflow: Boolean,
    onValueChange: (TextFieldValue) -> Unit,
    onToggleHidden: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    // Sub-lines sit a touch dimmer + indented so they nest under the main line.
    val baseAlpha = if (compact) 0.28f else 0.42f
    val container =
        if (hidden) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = baseAlpha * 0.5f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = baseAlpha)
    val dim = if (hidden) 0.5f else 1f
    val badgeSize = if (compact) 20.dp else 26.dp
    val iconBtn = if (compact) 28.dp else 32.dp
    val iconSize = if (compact) 14.dp else 16.dp
    val rowHeight = if (compact) 34.dp else 42.dp
    val textStyle = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium
    Surface(
        shape = MaterialTheme.shapes.small,
        color = container,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (compact) 22.dp else 0.dp)
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = rowHeight)
                .padding(start = 6.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Main row: number badge = expand/collapse. Sub row: number badge = hide.
            Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = if (!hidden) MaterialTheme.colorScheme.primary.copy(alpha = if (expandable && expanded) 0.30f else 0.16f)
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f),
                onClick = { if (enabled) { if (expandable) onExpand() else onToggleHidden(!hidden) } },
                enabled = enabled,
                modifier = Modifier.size(badgeSize)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (!hidden) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (value.text.isEmpty()) {
                    Text(
                        "$rowLabel ${index + 1}",
                        style = textStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = !focused,
                    maxLines = if (focused) 3 else 1,
                    textStyle = textStyle.copy(
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
            // Hide toggle (eye) — on every row, including the main line, so a user
            // can hide the main row and show only a sub-line.
            IconButton(
                onClick = { onToggleHidden(!hidden) },
                enabled = enabled,
                modifier = Modifier.size(iconBtn)
            ) {
                Icon(
                    if (!hidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    contentDescription = if (!hidden) "Hide row ${index + 1}" else "Show row ${index + 1}",
                    modifier = Modifier.size(iconSize),
                    tint = if (!hidden) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary
                )
            }
            // Main row: overflow only once there are sub-lines to reorder/delete.
            if (showOverflow) {
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        enabled = enabled,
                        modifier = Modifier.size(iconBtn)
                    ) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "Row ${index + 1} actions",
                            modifier = Modifier.size(iconSize),
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
                            text = { Text("Delete") },
                            leadingIcon = {
                                Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error)
                            },
                            onClick = { menuOpen = false; onDelete() }
                        )
                    }
                }
            }
            // Main row: expand chevron (+ "+N" when collapsed with sub-lines).
            if (expandable) {
                if (!expanded && subCount > 0) {
                    Text(
                        "+$subCount",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(
                    onClick = onExpand,
                    enabled = enabled,
                    modifier = Modifier.size(iconBtn)
                ) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Hide sub-lines" else "Add / edit sub-lines",
                        modifier = Modifier.size(iconSize),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/**
 * One cycle line (the MAIN line of a slide, editing sub-line 0). The number badge
 * / trailing chevron expands the slide into its nested sub-lines; the eye mutes
 * the whole slide (dims when off); the overflow menu moves/duplicates/deletes the
 * SLIDE. The line currently being sent is highlighted live ([isActive]). Tokens
 * like {time}/{song} substitute at send time.
 */
@Composable
private fun CycleLineRow(
    index: Int,
    count: Int,
    value: TextFieldValue,
    lineEnabled: Boolean,
    resolvedLength: Int,
    isActive: Boolean,
    expanded: Boolean,
    subCount: Int,
    onExpand: () -> Unit,
    onValueChange: (TextFieldValue) -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDuplicate: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    canDuplicate: Boolean,
    enabled: Boolean,
    dragHandleModifier: Modifier = Modifier,
    dragActive: Boolean = false
) {
    var menuOpen by remember { mutableStateOf(false) }
    // When this field is focused, let a long line wrap onto multiple lines so the
    // whole thing is visible while typing; collapse back to one line on blur.
    var focused by remember { mutableStateOf(false) }
    val container = when {
        dragActive -> MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        isActive -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        !lineEnabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    }
    val dim = if (lineEnabled) 1f else 0.5f
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = container,
        border = if (isActive || dragActive)
            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        else null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 44.dp)
                .padding(start = 4.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Drag handle (long-press to reorder the line). Hidden when there's
            // nothing to reorder (dragHandleModifier is empty).
            Icon(
                Icons.Filled.DragHandle,
                contentDescription = "Drag to reorder line ${index + 1}",
                modifier = dragHandleModifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Spacer(Modifier.width(2.dp))
            // Number badge = expand/collapse the slide's sub-lines (mute moved to
            // the eye). Brighter when expanded.
            Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = if (expanded) 0.32f else 0.18f),
                onClick = { if (enabled) onExpand() },
                enabled = enabled,
                modifier = Modifier.size(28.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
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
            // Expand chevron (+ "+N" when collapsed with sub-lines).
            if (!expanded && subCount > 0) {
                Text(
                    "+$subCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(
                onClick = onExpand,
                enabled = enabled,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Hide sub-lines" else "Add / edit sub-lines",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
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
