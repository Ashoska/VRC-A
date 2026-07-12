package com.vrca.ui.screen

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
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
    // When the dragged line hovers over ANOTHER line's CENTER, that's a DEMOTE
    // (nest it as a sub-line of the target) instead of a reorder. null = reorder.
    var cycleDemoteTarget by remember { mutableStateOf<Int?>(null) }

    // Absolute-position drop target: where the dragged row's CENTER sits vs the
    // midpoints of the other rows (in the original layout). Returns the post-removal
    // insertion index, which is exactly moveCycleLine's `to`. More consistent near
    // the ends / between rows than an incremental half-height walk.
    fun cycleDropTarget(origin: Int, offsetY: Float): Int {
        val n = vm.cycleLines.size
        if (n <= 1) return 0
        val heights = (0 until n).map { (cycleRowHeights[it] ?: 0).toFloat() }
        val originTop = heights.take(origin).sum()
        val draggedH = heights.getOrElse(origin) { 0f }
        val draggedCenter = originTop + offsetY + draggedH / 2f
        var target = 0
        var top = 0f
        for (i in 0 until n) {
            if (i == origin) { top += heights[i]; continue }
            val mid = top + heights[i] / 2f
            if (draggedCenter > mid) target++
            top += heights[i]
        }
        return target.coerceIn(0, n - 1)
    }

    // Which line (if any) the dragged row's center is hovering over the MIDDLE of —
    // a demote target. Only the center band counts (the edges stay reorder zones).
    fun cycleDemoteHover(origin: Int, offsetY: Float): Int? {
        val n = vm.cycleLines.size
        if (n <= 1) return null
        val heights = (0 until n).map { (cycleRowHeights[it] ?: 0).toFloat() }
        val originTop = heights.take(origin).sum()
        val draggedH = heights.getOrElse(origin) { 0f }
        val center = originTop + offsetY + draggedH / 2f
        var top = 0f
        for (i in 0 until n) {
            val h = heights[i]
            if (i != origin && center >= top + h * 0.30f && center <= top + h * 0.70f) return i
            top += h
        }
        return null
    }

    // Auto-scroll the page while dragging near the top/bottom edge so lines that
    // are off-screen can be reached. The page's ScrollState is hoisted here; the
    // viewport window bounds come from PageContainer's onViewport; each row reports
    // its window top so we can tell where the finger is relative to the viewport.
    val pageScroll = rememberScrollState()
    var viewportTopPx by remember { mutableFloatStateOf(0f) }
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    val cycleRowWindowTops = remember { mutableStateMapOf<Int, Float>() }
    // Shared auto-scroll direction (signed px/frame, 0 = none) — set by whichever
    // drag (cycle main line, cycle sub, or pinned sub) is active. Only one drag runs
    // at a time, so a single shared knob is safe.
    val cycleAutoDir = remember { mutableFloatStateOf(0f) }
    val edgePx = with(LocalDensity.current) { 96.dp.toPx() }
    // How far past a section card's edge the finger may stray and still have the drop
    // accepted (clamped to the nearest slot). Beyond this → the drag reverts.
    val DRAG_ACCEPT_MARGIN = with(LocalDensity.current) { 72.dp.toPx() }

    // ---- Drag-to-reorder SUB-LINES within a block (pinned block + each cycle
    // slide's sub-lines) ---- Same UI-only, commit-on-drop model as the cycle
    // main-line drag, but scoped to one block (keyed by `subDragKey`).
    val dropBarColor = MaterialTheme.colorScheme.primary
    var subDragKey by remember { mutableStateOf<String?>(null) }
    var subDragIndex by remember { mutableStateOf<Int?>(null) }
    val subDragOffsetY = remember { mutableFloatStateOf(0f) }
    val subRowHeights = remember { mutableStateMapOf<String, Int>() } // "$key#$i" -> px
    val subRowWindowTops = remember { mutableStateMapOf<String, Float>() } // "$key#$i" -> window y
    // Window-Y of each sub-drag GRAB HANDLE (the badge). The pointer position from
    // detectDragGesturesAfterLongPress is relative to this node, so handleTop + pos.y
    // is the exact finger window-Y — more reliable than the row-level top (cycle sub
    // rows sit inside a graphicsLayer'd Column, where the row top wasn't landing).
    val subHandleTops = remember { mutableStateMapOf<String, Float>() } // "$key#$i" -> window y

    // Unified auto-scroll: runs while ANY drag is active (cycle main line OR a sub
    // line in either section, incl. Pinned) and folds the scrolled distance into
    // whichever drag's offset is live so the row stays under the finger and rows
    // that scroll into view become reachable.
    LaunchedEffect(cycleDragIndex, subDragKey) {
        if (cycleDragIndex == null && subDragKey == null) { cycleAutoDir.floatValue = 0f; return@LaunchedEffect }
        while (cycleDragIndex != null || subDragKey != null) {
            val dir = cycleAutoDir.floatValue
            if (dir != 0f && viewportHeightPx > 0) {
                val before = pageScroll.value
                pageScroll.scrollBy(dir)
                val scrolled = (pageScroll.value - before).toFloat()
                if (cycleDragIndex != null) cycleDragOffsetY.floatValue += scrolled
                else subDragOffsetY.floatValue += scrolled
            }
            withFrameNanos { }
        }
    }

    // ---- Cross-section drag (Pinned <-> Cycle) ----
    // Section card window rects (for detecting which section the finger is over) +
    // hoisted expand state (so hovering the OTHER section can auto-expand it).
    val pinnedCardExpanded = rememberSaveable { mutableStateOf(false) }
    val cycleCardExpanded = rememberSaveable { mutableStateOf(false) }
    var pinnedCardRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var cycleCardRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var crossTarget by remember { mutableStateOf<String?>(null) } // "pinned" | "cycle" | null
    // Insertion boundary in the TARGET section while cross-dragging (drives the drop
    // bar drawn in that section AND the positional insert on release). -1 = none.
    var crossDropIndex by remember { mutableStateOf(-1) }
    // Last finger window-Y of the active drag — used at drop to tell "still in the UI
    // (accept, clamp to nearest slot)" from "dragged completely out (revert)".
    val dragFingerY = remember { mutableFloatStateOf(0f) }

    fun sectionAt(fingerY: Float): String? = when {
        pinnedCardRect?.let { fingerY >= it.top && fingerY <= it.bottom } == true -> "pinned"
        cycleCardRect?.let { fingerY >= it.top && fingerY <= it.bottom } == true -> "cycle"
        else -> null
    }

    // Where in the TARGET section the finger sits: number of that section's rows whose
    // vertical midpoint is above the finger = the insertion index (0..count). Rows that
    // haven't reported a window-top yet (e.g. a just-expanded section) are skipped.
    fun crossDropBoundary(section: String, fingerY: Float): Int {
        var b = 0
        if (section == "cycle") {
            for (i in 0 until vm.cycleLines.size) {
                val top = cycleRowWindowTops[i] ?: continue
                val h = (cycleRowHeights[i] ?: 0).toFloat()
                if (fingerY > top + h / 2f) b++
            }
        } else {
            for (i in 0 until vm.pinnedSubLines().size) {
                val top = subRowWindowTops["pinned#$i"] ?: continue
                val h = (subRowHeights["pinned#$i"] ?: 0).toFloat()
                if (fingerY > top + h / 2f) b++
            }
        }
        return b
    }

    // Update crossTarget for a drag whose ORIGIN section is [origin]; auto-expand the
    // hovered other section so its rows come into view, and track the drop boundary.
    fun updateCross(origin: String, fingerY: Float) {
        val sec = sectionAt(fingerY)
        val cross = if (sec != null && sec != origin) sec else null
        crossTarget = cross
        when (cross) {
            "cycle" -> { cycleCardExpanded.value = true; crossDropIndex = crossDropBoundary("cycle", fingerY) }
            "pinned" -> { pinnedCardExpanded.value = true; crossDropIndex = crossDropBoundary("pinned", fingerY) }
            else -> crossDropIndex = -1
        }
    }

    fun subDropTarget(key: String, origin: Int, count: Int, offsetY: Float): Int {
        if (count <= 1) return 0
        val h = (0 until count).map { (subRowHeights["$key#$it"] ?: 0).toFloat() }
        val originTop = h.take(origin).sum()
        val draggedH = h.getOrElse(origin) { 0f }
        val center = originTop + offsetY + draggedH / 2f
        var target = 0; var top = 0f
        for (i in 0 until count) {
            if (i == origin) { top += h[i]; continue }
            val mid = top + h[i] / 2f
            if (center > mid) target++
            top += h[i]
        }
        return target.coerceIn(0, count - 1)
    }

    // Badge grab modifier for a sub-row (long-press to reorder within its block).
    // Dropped OUTSIDE the block bounds → onOutside (promote to its own line for
    // cycle sub-lines; null = just revert, e.g. pinned which has no slide level).
    fun subDragHandle(
        key: String,
        index: Int,
        count: Int,
        onOutside: (() -> Unit)? = null,
        onCrossSection: ((String) -> Unit)? = null,
        onMove: (Int, Int) -> Unit
    ): Modifier =
        Modifier
            .onGloballyPositioned { subHandleTops["$key#$index"] = it.positionInWindow().y }
            .pointerInput(key, index, count) {
            val origin = if (key == "pinned") "pinned" else "cycle"
            detectDragGesturesAfterLongPress(
                onDragStart = { subDragKey = key; subDragIndex = index; subDragOffsetY.floatValue = 0f },
                onDrag = { c, d ->
                    c.consume()
                    subDragOffsetY.floatValue += d.y
                    // Finger window-Y from the GRAB HANDLE's own position (pos is relative
                    // to it) — falls back to the row top. Fixes cycle sub-rows whose row
                    // top wasn't landing (they sit inside a graphicsLayer'd Column), which
                    // left crossTarget/section detection dead so no drop bar ever showed.
                    val anchorY = subHandleTops["$key#$index"] ?: subRowWindowTops["$key#$index"] ?: 0f
                    val fingerY = anchorY + c.position.y
                    dragFingerY.floatValue = fingerY
                    updateCross(origin, fingerY)
                    // Auto-scroll the page when the finger nears a viewport edge (so a
                    // pinned/sub drag can reach rows off-screen, and cross-dragging can
                    // scroll the OTHER section into view).
                    cycleAutoDir.floatValue = when {
                        viewportHeightPx <= 0 -> 0f
                        fingerY < viewportTopPx + edgePx -> -14f
                        fingerY > viewportTopPx + viewportHeightPx - edgePx -> 14f
                        else -> 0f
                    }
                },
                onDragEnd = {
                    val from = subDragIndex
                    if (from != null && subDragKey == key) {
                        val cross = crossTarget
                        val offset = subDragOffsetY.floatValue
                        val h = (0 until count).map { (subRowHeights["$key#$it"] ?: 0) }
                        val originTop = h.take(from).sum().toFloat()
                        val draggedH = h.getOrElse(from) { 0 }.toFloat()
                        val totalH = h.sum().toFloat()
                        val curTop = originTop + offset
                        // "Clearly out of the block" — a cycle sub promotes to its own line.
                        // Generous margin so a small over-drag past the first/last sub-row
                        // still reorders within the block instead of promoting.
                        val outMargin = draggedH * 1.4f
                        val outside = curTop < -outMargin || curTop > (totalH - draggedH) + outMargin
                        // Still within this section's card (+ margin)? then accept a reorder
                        // clamped to the nearest slot; only a drop fully outside reverts.
                        val originCardRect = if (key == "pinned") pinnedCardRect else cycleCardRect
                        val withinCard = originCardRect?.let { r ->
                            dragFingerY.floatValue in (r.top - DRAG_ACCEPT_MARGIN)..(r.bottom + DRAG_ACCEPT_MARGIN)
                        } ?: true
                        when {
                            cross != null && onCrossSection != null -> onCrossSection(cross)
                            // Promote (cycle sub → own line) ONLY when dragged clearly out
                            // of the block but STILL within the section card. Dragged fully
                            // out of the UI → fall through → revert (back to its sub area).
                            outside && withinCard && onOutside != null -> onOutside()
                            withinCard -> {
                                val to = subDropTarget(key, from, count, offset)
                                if (to != from) onMove(from, to)
                            }
                        }
                    }
                    subDragKey = null; subDragIndex = null; subDragOffsetY.floatValue = 0f
                    crossTarget = null; crossDropIndex = -1; cycleAutoDir.floatValue = 0f
                },
                onDragCancel = {
                    subDragKey = null; subDragIndex = null; subDragOffsetY.floatValue = 0f
                    crossTarget = null; crossDropIndex = -1; cycleAutoDir.floatValue = 0f
                }
            )
        }

    // Wrapper modifier for a sub-row: float the dragged one, draw the drop bar,
    // measure heights. Local index space is 0..count-1 within the block.
    fun subRowMod(key: String, index: Int, count: Int): Modifier = Modifier
        .zIndex(if (subDragKey == key && subDragIndex == index) 1f else 0f)
        .graphicsLayer {
            translationY = if (subDragKey == key && subDragIndex == index) subDragOffsetY.floatValue else 0f
            shadowElevation = if (subDragKey == key && subDragIndex == index) 8f else 0f
        }
        .drawBehind {
            val barH = 3.dp.toPx(); val r = CornerRadius(barH / 2f)
            // Incoming cross-section drop: a cycle line/sub being dragged INTO the
            // pinned block (the only block that receives cross drops). subDragKey is
            // null here for a cycle-main-line source; for a cycle-sub source it's set
            // to that slide's key, so gate on "this is the pinned block + crossing in".
            if (key == "pinned" && crossTarget == "pinned" && subDragKey != "pinned") {
                val boundary = crossDropIndex
                when {
                    boundary == index -> drawRoundRect(dropBarColor, Offset(0f, -barH - 1f), Size(size.width, barH), r)
                    index == count - 1 && boundary >= count ->
                        drawRoundRect(dropBarColor, Offset(0f, size.height + 1f), Size(size.width, barH), r)
                }
                return@drawBehind
            }
            val from = subDragIndex ?: return@drawBehind
            if (subDragKey != key || from == index) return@drawBehind
            // Dragged sub is crossing into the other section — its bar shows there.
            if (crossTarget != null) return@drawBehind
            val target = subDropTarget(key, from, count, subDragOffsetY.floatValue)
            if (target == from) return@drawBehind
            val boundary = if (target <= from) target else target + 1
            when {
                boundary == index -> drawRoundRect(dropBarColor, Offset(0f, -barH - 1f), Size(size.width, barH), r)
                index == count - 1 && boundary >= count ->
                    drawRoundRect(dropBarColor, Offset(0f, size.height + 1f), Size(size.width, barH), r)
            }
        }
        .onSizeChanged { subRowHeights["$key#$index"] = it.height }
        .onGloballyPositioned { subRowWindowTops["$key#$index"] = it.positionInWindow().y }

    // Page-scope snapshots so a card's content lambda re-runs on sub-drag start/end
    // (the dragActive opaque styling is a composition-time read; the float/bar are
    // deferred layer/draw reads). Offset changes never hit these (drag stays smooth).
    val subDragKeySnap = subDragKey
    val subDragIndexSnap = subDragIndex

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

    PageContainer(
        scrollState = pageScroll,
        onViewport = { top, h -> viewportTopPx = top; viewportHeightPx = h }
    ) {
        // =========================
        // Pinned — collapsed = status ("'msg' · ON"), expanded = editor.
        // =========================
        CompactSectionCard(
            title = "Pinned",
            icon = Icons.Filled.PushPin,
            summary = com.vrca.app.SubLineCodec.renderVisible(vm.afkMessage)
                .replace("\n", "  /  ").ifBlank { "No message set" },
            expandedState = pinnedCardExpanded,
            modifier = Modifier
                .onGloballyPositioned { pinnedCardRect = it.boundsInWindow() }
                .then(
                    if (crossTarget == "pinned")
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
                    else Modifier
                ),
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
                // Force the sub-rows visible while a cycle item is being dragged INTO
                // Pinned, so every slot (and its drop bar) shows during the cross-drag.
                expanded = pinnedExpanded || crossTarget == "pinned",
                onToggleExpanded = { pinnedExpanded = !pinnedExpanded },
                onTextChanged = { i, t -> vm.setPinnedSubLineText(i, t) },
                onToggleHidden = { i, h -> vm.setPinnedSubLineHidden(i, h) },
                onMoveUp = { i -> vm.movePinnedSubLine(i, i - 1) },
                onMoveDown = { i -> vm.movePinnedSubLine(i, i + 1) },
                onDelete = { i -> vm.removePinnedSubLine(i) },
                onAdd = { vm.addPinnedSubLine() },
                activeDragIndex = if (subDragKeySnap == "pinned") subDragIndexSnap else null,
                dragModifierFor = { i ->
                    subDragHandle(
                        "pinned", i, pinnedSubs.size,
                        onCrossSection = { sec -> if (sec == "cycle") vm.movePinnedRowToCycle(i, crossDropIndex) }
                    ) { f, t -> vm.movePinnedSubLine(f, t) }
                },
                rowModifierFor = { i -> subRowMod("pinned", i, pinnedSubs.size) }
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
        // read inside graphicsLayer / drawBehind blocks instead.
        val draggingIdx = cycleDragIndex
        // Is the current demote target valid (fits within the 3-row cap)?
        val cycleDemoteValid = cycleDemoteTarget?.let { t ->
            draggingIdx != null && vm.canDemoteCycleInto(draggingIdx, t)
        } ?: false
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
            expandedState = cycleCardExpanded,
            modifier = Modifier
                .onGloballyPositioned { cycleCardRect = it.boundsInWindow() }
                .then(
                    if (crossTarget == "cycle")
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
                    else Modifier
                ),
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
                                val fingerY = (cycleRowWindowTops[idx] ?: 0f) + change.position.y
                                dragFingerY.floatValue = fingerY
                                // Over the Pinned section? → cross-section move.
                                updateCross("cycle", fingerY)
                                // Demote target: hovering another line's centre nests
                                // this line into it (only when NOT crossing sections).
                                cycleDemoteTarget = if (crossTarget == null)
                                    cycleDemoteHover(idx, cycleDragOffsetY.floatValue) else null
                                // Auto-scroll when the finger nears a viewport edge.
                                cycleAutoDir.floatValue = when {
                                    viewportHeightPx <= 0 -> 0f
                                    fingerY < viewportTopPx + edgePx -> -14f
                                    fingerY > viewportTopPx + viewportHeightPx - edgePx -> 14f
                                    else -> 0f
                                }
                            },
                            onDragEnd = {
                                val from = cycleDragIndex
                                var structuralMove = false
                                if (from != null) {
                                    val offset = cycleDragOffsetY.floatValue
                                    val cross = crossTarget
                                    val demote = cycleDemoteTarget
                                    when {
                                        // Move the whole line into Pinned at the hovered slot.
                                        cross == "pinned" -> { vm.moveCycleLineToPinned(from, crossDropIndex); structuralMove = true }
                                        // Nest into another line's sub-lines (if it fits).
                                        demote != null -> {
                                            vm.demoteCycleLineInto(from, demote) // no-op if invalid → reverts
                                            structuralMove = true
                                        }
                                        else -> {
                                            // Reorder. Accept (clamped to the nearest slot) as
                                            // long as the finger is still within the Cycle card
                                            // plus a generous margin — a small over-drag past the
                                            // first/last row still lands. Only a drop COMPLETELY
                                            // out of the card reverts.
                                            val within = cycleCardRect?.let { r ->
                                                dragFingerY.floatValue in (r.top - DRAG_ACCEPT_MARGIN)..(r.bottom + DRAG_ACCEPT_MARGIN)
                                            } ?: true
                                            if (within) {
                                                val to = cycleDropTarget(from, offset)
                                                if (to != from) { vm.moveCycleLine(from, to); structuralMove = true }
                                            }
                                        }
                                    }
                                }
                                cycleDragIndex = null; cycleDragOffsetY.floatValue = 0f
                                cycleAutoDir.floatValue = 0f; cycleDemoteTarget = null; crossTarget = null; crossDropIndex = -1
                                // cycleExpanded is keyed by INDEX; any reorder/demote/cross
                                // shifts indices, so a stale entry would leave a DIFFERENT
                                // line showing expanded (and the moved line collapsed). Reset
                                // it after a structural move so expand state is never stranded.
                                if (structuralMove) { cycleExpanded.clear(); pinnedExpanded = false }
                            },
                            onDragCancel = {
                                cycleDragIndex = null; cycleDragOffsetY.floatValue = 0f
                                cycleAutoDir.floatValue = 0f; cycleDemoteTarget = null; crossTarget = null; crossDropIndex = -1
                            }
                        )
                    } else Modifier
                    key(idx, lineEn, expanded, subs.size) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .zIndex(if (draggingIdx == idx) 1f else 0f)
                                .graphicsLayer {
                                    // Only the dragged row translates (follows the
                                    // finger); the rest stay put and a bright bar
                                    // shows the drop position (drawBehind below).
                                    translationY = if (cycleDragIndex == idx) cycleDragOffsetY.floatValue else 0f
                                    shadowElevation = if (cycleDragIndex == idx) 10f else 0f
                                }
                                .drawBehind {
                                    val barH = 3.dp.toPx()
                                    val n = vm.cycleLines.size
                                    val radius = CornerRadius(barH / 2f)
                                    // Incoming cross-section drop (a pinned row/sub being
                                    // dragged INTO Cycle) — cycleDragIndex is null here.
                                    if (cycleDragIndex == null && crossTarget == "cycle" && subDragKey != null) {
                                        val boundary = crossDropIndex
                                        when {
                                            boundary == idx -> drawRoundRect(
                                                color = dropBarColor,
                                                topLeft = Offset(0f, -barH - 1f),
                                                size = Size(size.width, barH),
                                                cornerRadius = radius
                                            )
                                            idx == n - 1 && boundary >= n -> drawRoundRect(
                                                color = dropBarColor,
                                                topLeft = Offset(0f, size.height + 1f),
                                                size = Size(size.width, barH),
                                                cornerRadius = radius
                                            )
                                        }
                                        return@drawBehind
                                    }
                                    val from = cycleDragIndex ?: return@drawBehind
                                    if (from == idx) return@drawBehind
                                    // The dragged cycle line is heading into Pinned — its
                                    // bar shows over there, not in this list.
                                    if (crossTarget != null) return@drawBehind
                                    // No insertion bar while hovering a demote target.
                                    if (cycleDemoteTarget != null) return@drawBehind
                                    val target = cycleDropTarget(from, cycleDragOffsetY.floatValue)
                                    if (target == from) return@drawBehind
                                    val boundary = if (target <= from) target else target + 1
                                    when {
                                        boundary == idx -> drawRoundRect(
                                            color = dropBarColor,
                                            topLeft = Offset(0f, -barH - 1f),
                                            size = Size(size.width, barH),
                                            cornerRadius = radius
                                        )
                                        idx == n - 1 && boundary >= n -> drawRoundRect(
                                            color = dropBarColor,
                                            topLeft = Offset(0f, size.height + 1f),
                                            size = Size(size.width, barH),
                                            cornerRadius = radius
                                        )
                                    }
                                }
                                .onSizeChanged { cycleRowHeights[idx] = it.height }
                                .onGloballyPositioned { cycleRowWindowTops[idx] = it.positionInWindow().y }
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
                                demoteTargetValid = if (cycleDemoteTarget == idx) cycleDemoteValid else null,
                                dragInvalid = draggingIdx == idx && cycleDemoteTarget != null && !cycleDemoteValid,
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
                            // Collapse the sub-lines WHILE this line is being dragged
                            // (cycleExpanded is untouched, so they reopen on drop and
                            // the drag gesture isn't disposed by a key change). The
                            // row shrinks → drop math uses its true collapsed height.
                            if (expanded && draggingIdx != idx) {
                                // Sub-lines reorder AMONG THEMSELVES (indices 1..) — a
                                // 0-based block "cyc:$idx" of m = subs.size-1 rows, so
                                // local index j = s-1 maps to real sub index j+1.
                                val subKey = "cyc:$idx"
                                val m = subs.size - 1
                                for (s in 1 until subs.size) {
                                    val sub = subs[s]
                                    val j = s - 1
                                    val subActive = subDragKeySnap == subKey && subDragIndexSnap == j
                                    // NOTE: subActive is deliberately NOT in the key() — it flips
                                    // true the instant a drag starts, and a key change disposes+
                                    // recreates this row, which CANCELS the in-progress
                                    // detectDragGesturesAfterLongPress gesture (the "can't drag
                                    // sub-lines" bug). dragActive styling still updates because
                                    // subActive is read from the page-scope subDragKeySnap/
                                    // subDragIndexSnap snapshots, so the content lambda re-runs and
                                    // the row recomposes IN PLACE (key stable → gesture survives).
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
                                            onDelete = { vm.removeCycleSubLine(idx, s) },
                                            modifier = subRowMod(subKey, j, m),
                                            dragHandleModifier = subDragHandle(
                                                subKey, j, m,
                                                onOutside = { vm.promoteCycleSubLine(idx, s) },
                                                onCrossSection = { sec -> if (sec == "pinned") vm.moveCycleSubToPinned(idx, s, crossDropIndex) }
                                            ) { f, t -> vm.moveCycleSubLine(idx, f + 1, t + 1) },
                                            dragActive = subActive
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
    onAdd: () -> Unit,
    activeDragIndex: Int? = null,
    dragModifierFor: (Int) -> Modifier = { Modifier },
    rowModifierFor: (Int) -> Modifier = { Modifier }
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Main line — always visible, full size; number/chevron expand the rest.
        val main = subs.firstOrNull() ?: ChatboxSubLine("", false)
        // key on hidden so the main row repaints the instant its eye toggles (same
        // AnimatedVisibility-skip fix as the sub-rows / cycle mute rows).
        // activeDragIndex is deliberately NOT in the key — a key change on drag start
        // disposes+recreates the row and cancels the long-press-drag gesture (the
        // "can't drag pinned rows" bug). dragActive styling still updates because
        // activeDragIndex is derived from the page-scope subDragKeySnap snapshot.
        key(main.hidden, subs.size) {
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
                onDelete = { onDelete(0) },
                modifier = rowModifierFor(0),
                dragHandleModifier = dragModifierFor(0),
                dragActive = activeDragIndex == 0
            )
        }
        if (expanded) {
            // Nested sub-lines (index 1..) — small + indented so they read as
            // sub-lines of the main line, not standalone lines.
            for (i in 1 until subs.size) {
                val sub = subs[i]
                // activeDragIndex NOT in the key — see the main-row note above (a key
                // change on drag start would cancel the in-progress gesture).
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
                        onDelete = { onDelete(i) },
                        modifier = rowModifierFor(i),
                        dragHandleModifier = dragModifierFor(i),
                        dragActive = activeDragIndex == i
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
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier,
    dragActive: Boolean = false
) {
    var menuOpen by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    // Sub-lines sit a touch dimmer + indented so they nest under the main line.
    val baseAlpha = if (compact) 0.28f else 0.42f
    val container = when {
        // A dragged row floats over the others → must be opaque.
        dragActive -> lerp(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.primary, 0.18f)
        hidden -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = baseAlpha * 0.5f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = baseAlpha)
    }
    val dim = if (hidden) 0.5f else 1f
    val badgeSize = if (compact) 20.dp else 26.dp
    val iconBtn = if (compact) 28.dp else 32.dp
    val iconSize = if (compact) 14.dp else 16.dp
    val rowHeight = if (compact) 34.dp else 42.dp
    val textStyle = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium
    Surface(
        shape = MaterialTheme.shapes.small,
        color = container,
        border = if (dragActive)
            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        else null,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = if (compact) 22.dp else 0.dp)
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = rowHeight)
                .padding(start = 6.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Number badge = the drag GRAB point (long-press to reorder within the
            // block). Tapping does nothing — expand is the chevron, hide is the eye —
            // so grabbing can't accidentally toggle expand/hide.
            Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = if (!hidden) MaterialTheme.colorScheme.primary.copy(alpha = if (expandable && expanded) 0.30f else 0.16f)
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f),
                modifier = Modifier.size(badgeSize).then(dragHandleModifier)
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
    dragActive: Boolean = false,
    demoteTargetValid: Boolean? = null,
    dragInvalid: Boolean = false
) {
    var menuOpen by remember { mutableStateOf(false) }
    // When this field is focused, let a long line wrap onto multiple lines so the
    // whole thing is visible while typing; collapse back to one line on blur.
    var focused by remember { mutableStateOf(false) }
    val container = when {
        // Dragged row hovering an INVALID demote target → red-ish so the user sees
        // it won't fit. Valid dragged → opaque primary (floats over others).
        dragActive && dragInvalid -> lerp(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.error, 0.22f)
        dragActive -> lerp(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.primary, 0.18f)
        // A demote target: green-tint "nest here" when it fits, red when it won't.
        demoteTargetValid == true -> lerp(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.primary, 0.24f)
        demoteTargetValid == false -> lerp(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.error, 0.16f)
        isActive -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        !lineEnabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    }
    val borderColor = when {
        demoteTargetValid == false || (dragActive && dragInvalid) -> MaterialTheme.colorScheme.error
        isActive || dragActive || demoteTargetValid == true -> MaterialTheme.colorScheme.primary
        else -> null
    }
    val dim = if (lineEnabled) 1f else 0.5f
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = container,
        border = borderColor?.let { androidx.compose.foundation.BorderStroke(1.dp, it) },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 44.dp)
                .padding(start = 6.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Number badge = the drag GRAB point (long-press to reorder). Tapping it
            // does NOT expand — expansion is the chevron only — so trying to grab
            // the line can't accidentally toggle its sub-lines.
            Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = if (expanded) 0.32f else 0.18f),
                modifier = Modifier.size(28.dp).then(dragHandleModifier)
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
