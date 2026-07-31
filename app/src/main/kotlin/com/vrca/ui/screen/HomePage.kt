package com.vrca.ui.screen

import android.content.Context
import android.os.PowerManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.zIndex
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vrca.BuildConfig
import com.vrca.ui.common.CompactSectionCard
import com.vrca.ui.common.KitStatusChip
import com.vrca.ui.common.KitTone
import com.vrca.ui.common.StatusDot
import com.vrca.ui.common.TogglePill
import com.vrca.ui.settings.ToggleRow
import com.vrca.ui.viewmodel.VrcaViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A single in-app announcement (Phase 4): collapsible status-banner-style card, NOT
 * dismissable, rich body via the shared RichDocRenderer. Unread NEW dot until first
 * expanded; priority > 0 auto-expands. Media served from the ann/ cache.
 */
@Composable
private fun AnnouncementCard(a: AnnouncementUi) {
    val ctx = LocalContext.current
    val doc = remember(a.bodyDoc, a.body) {
        com.vrca.richcontent.resolveRichDoc(a.bodyDoc, a.body)
    }
    var expanded by rememberSaveable(a.id) { mutableStateOf(a.priority > 0) }
    val unseen = a.id !in com.vrca.ui.common.AnnouncementSeenState.seen
    LaunchedEffect(expanded) {
        if (expanded) com.vrca.ui.common.AnnouncementSeenState.markSeen(ctx, a.id)
    }
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (unseen) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    a.title.ifBlank { "Announcement" },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                // Priority drives sort order + auto-expand only — deliberately NOT
                // shown to end users (an internal admin ranking, not display content).
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand"
                )
            }
            if (expanded) {
                if (doc != null) {
                    com.vrca.richcontent.RichDocRenderer(
                        doc,
                        mediaScope = com.vrca.richcontent.RichMediaStore.Scope.ANNOUNCEMENT
                    )
                } else if (a.body.isNotBlank()) {
                    Text(a.body, style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                val preview = remember(doc, a.body) {
                    (doc?.toPlainText() ?: a.body).lineSequence()
                        .firstOrNull { it.isNotBlank() }.orEmpty()
                }
                if (preview.isNotBlank()) {
                    Text(
                        preview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun HomePage(
    vm: VrcaViewModel,
    onNavigate: (AppPage) -> Unit,
    announcements: List<AnnouncementUi>,
    moderation: ModerationUi,
    isBanned: Boolean,
    vrcLinked: Boolean
) {
    val uiState by vm.messengerUiState.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val connectionBring = remember { BringIntoViewRequester() }
    // Keeps the Manual Send field visible while typing — the live preview above it
    // grows as you type and would otherwise push the input line out of view.
    val manualBring = remember { BringIntoViewRequester() }
    var manualFieldFocused by remember { mutableStateOf(false) }

    val pm = remember(ctx) { ctx.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var batteryOk by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(ctx.packageName)) }
    LaunchedEffect(Unit) { batteryOk = pm.isIgnoringBatteryOptimizations(ctx.packageName) }

    // Gate on the REAL granted permission (NotificationManagerCompat), not
    // vm.listenerConnected — that flag stays false after a process restart until
    // the OS rebinds the listener, falsely flagging the setup item as incomplete
    // even though access is granted. Re-checked on re-entry (same as batteryOk).
    var notifOk by remember {
        mutableStateOf(
            androidx.core.app.NotificationManagerCompat
                .getEnabledListenerPackages(ctx).contains(ctx.packageName)
        )
    }
    LaunchedEffect(Unit) {
        notifOk = androidx.core.app.NotificationManagerCompat
            .getEnabledListenerPackages(ctx).contains(ctx.packageName)
    }
    var ipOk by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        // Headset: OSC is automatic (localhost), so the connection is always "set" —
        // never flag "IP not set" in setup health.
        if (BuildConfig.IS_HEADSET_BUILD) { ipOk = true; return@LaunchedEffect }
        vm.userPreferencesRepository.ipAddress.collect { ip ->
            ipOk = ip.isNotBlank() && ip != "127.0.0.1"
        }
    }

    // Re-check the revocable system grants every time the app returns to the
    // foreground. An OEM update (notably Samsung One UI) can silently REVOKE the
    // battery-optimization exemption or Notification Access while VRC-A is in the
    // background; without this the setup-health card would keep showing them green
    // until a full recomposition. On ON_RESUME the card re-surfaces the now-red
    // item so the user is nudged to re-grant it.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryOk = pm.isIgnoringBatteryOptimizations(ctx.packageName)
                notifOk = androidx.core.app.NotificationManagerCompat
                    .getEnabledListenerPackages(ctx).contains(ctx.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // 1s wall-clock tick while sending — drives the uptime label and the
    // "Next cycle in Ns" countdown. No ticking while idle.
    var nowTickMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(vm.oscSending) {
        while (vm.oscSending) {
            nowTickMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    // Shared Edit mode: reorders the chatbox COMPONENT order (Quick Toggles)
    // AND the page-level card order — one mode, every card participates.
    var cardReorderMode by remember { mutableStateOf(false) }
    var homeOrder by remember { mutableStateOf(UiPrefs.readHomeCardOrder(ctx)) }
    fun moveHomeCard(key: String, delta: Int) {
        val order = homeOrder.toMutableList()
        val idx = order.indexOf(key)
        val to = idx + delta
        if (idx < 0 || to < 0 || to >= order.size) return
        order[idx] = order[to]; order[to] = key
        homeOrder = order
        UiPrefs.writeHomeCardOrder(ctx, order)
    }

    val sortedAnnouncements = remember(announcements) {
        announcements.sortedWith(
            compareByDescending<AnnouncementUi> { it.priority }.thenByDescending { it.createdAt }
        )
    }

    PageContainer {
        if (sortedAnnouncements.isNotEmpty()) {
            SectionCard(
                title = "Announcements",
                subtitle = "Latest updates from the app team."
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    sortedAnnouncements.forEach { a ->
                        key(a.id) { AnnouncementCard(a) }
                    }
                }
            }
        }

        // VRChat server status warning
        VrchatStatusBanner()

        // Confirmed-dead VRChat session: OSC is gated, so explain why + offer sign-in.
        if (vm.vrchatAuthDead) {
            com.vrca.ui.common.VrchatSessionExpiredBanner(
                onSignIn = { onNavigate(AppPage.VrchatStatus) }
            )
        }

        if (moderation.warned && !(moderation.banned || moderation.deviceBanned)) {
            SectionCard(
                title = "Account warning",
                subtitle = "This warning is shown to you only."
            ) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("You have been warned.", style = MaterialTheme.typography.titleSmall)
                        Text(
                            moderation.warnReason.ifBlank { "No reason provided." },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        // Setup health checklist — Home-only replacement for the old every-tab
        // "Setup incomplete" banner. Shows ONLY the red rows; disappears
        // entirely when everything is green. Each row deep-links to its fix.
        val healthItems = buildList {
            if (!vrcLinked) add(
                HealthItem(
                    "VRChat account not linked",
                    "OSC sending is blocked until you sign in."
                ) { onNavigate(AppPage.VrchatStatus) }
            )
            if (ipOk == false) add(
                HealthItem(
                    "Headset IP not set",
                    "Set your Quest/PC IP in the Connection card."
                ) { scope.launch { connectionBring.bringIntoView() } }
            )
            if (!batteryOk) add(
                HealthItem(
                    "Battery optimization is on",
                    "Android may pause VRC-A when the screen is off."
                ) {
                    ctx.startActivity(vm.batteryOptimizationIntent())
                    batteryOk = pm.isIgnoringBatteryOptimizations(ctx.packageName)
                }
            )
            if (vm.spotifyEnabled && !notifOk) add(
                HealthItem(
                    "Notification Access missing",
                    "Now Playing is on but can't detect media without it."
                ) { ctx.startActivity(vm.notificationAccessIntent()) }
            )
        }
        if (healthItems.isNotEmpty()) {
            SetupHealthCard(healthItems)
        }

        // Reorder mode exit — unmissable full-width button at the top of the
        // page (the small Done in the Quick Toggles header can be off-screen
        // while the user is moving the upper cards around).
        if (cardReorderMode) {
            Button(
                onClick = { cardReorderMode = false },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Done reordering")
            }
        }

        homeOrder.forEach { cardKey ->
            HomeCardSlot(
                label = when (cardKey) {
                    "Preview" -> "Preview & toggles"
                    "Connection" -> "Connection"
                    else -> "Manual Send"
                },
                reorderMode = cardReorderMode,
                canMoveUp = homeOrder.indexOf(cardKey) > 0,
                canMoveDown = homeOrder.indexOf(cardKey) < homeOrder.size - 1,
                onMove = { delta -> moveHomeCard(cardKey, delta) }
            ) {
                when (cardKey) {
                    "Preview" -> PreviewAndTogglesCard(
                        vm = vm,
                        isBanned = isBanned,
                        nowTickMs = nowTickMs,
                        cardReorderMode = cardReorderMode,
                        onToggleReorderMode = { cardReorderMode = it },
                        onResetOrder = {
                            vm.resetCardOrder()
                            homeOrder = UiPrefs.HOME_CARDS_DEFAULT
                            UiPrefs.writeHomeCardOrder(ctx, UiPrefs.HOME_CARDS_DEFAULT)
                        },
                        onNavigate = onNavigate
                    )

                    "Connection" -> Column(Modifier.bringIntoViewRequester(connectionBring)) {
                        ConnectionCard(vm = vm, ipAddress = uiState.ipAddress, ipOk = ipOk ?: true)
                    }

                    "ManualSend" -> CompactSectionCard(
                        title = "Manual Send",
                        icon = Icons.Filled.Send,
                        summary = if (vm.manualLiveMode) "Live typing" else "Type a manual message"
                    ) {
                        val budget = vm.manualCharBudget()
                        val msgLen = vm.messageText.value.text.length
                        val over = msgLen > budget

                        // Instant / Live segmented toggle (compact, on-vibe).
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(false, true).forEach { live ->
                                val selected = vm.manualLiveMode == live
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable(enabled = !isBanned) { vm.setManualLiveModeFlag(live) }
                                ) {
                                    Text(
                                        if (live) "Live" else "Instant",
                                        textAlign = TextAlign.Center,
                                        style = MaterialTheme.typography.labelLarge,
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                                    )
                                }
                            }
                        }

                        // Scroll style is Live-only.
                        if (vm.manualLiveMode) {
                            ToggleRow(
                                label = "Scroll",
                                description = "Scroll to the 4 newest lines.",
                                checked = vm.manualScroll,
                                enabled = !isBanned
                            ) { vm.setManualScrollFlag(it) }
                        }

                        // Bring the input back into view as the live preview above
                        // grows/shifts while typing (a small delay lets it relayout
                        // first, then we scroll the field into view).
                        LaunchedEffect(vm.messageText.value.text, manualFieldFocused) {
                            if (manualFieldFocused) {
                                kotlinx.coroutines.delay(60)
                                runCatching { manualBring.bringIntoView() }
                            }
                        }
                        OutlinedTextField(
                            value = vm.messageText.value,
                            onValueChange = { v: TextFieldValue -> vm.onMessageTextChange(v) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .bringIntoViewRequester(manualBring)
                                .onFocusChanged { manualFieldFocused = it.isFocused },
                            minLines = 2,
                            label = { Text("Message") },
                            isError = over && !vm.manualLiveMode,
                            enabled = !isBanned,
                            supportingText = {
                                Text(
                                    "$msgLen / $budget" +
                                        if (vm.manualLiveMode) "  ·  newest lines shown live" else "",
                                    // In Live mode going over budget is expected —
                                    // Scroll rolls older lines off — so don't alarm.
                                    color = if (over && !vm.manualLiveMode) MaterialTheme.colorScheme.error
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (!vm.manualLiveMode) {
                                Button(
                                    onClick = { vm.sendMessage() },
                                    modifier = Modifier.weight(1f),
                                    enabled = !isBanned && msgLen > 0 && !over
                                ) { Text("Send") }
                            }
                            OutlinedButton(
                                onClick = { vm.clearManual() },
                                modifier = Modifier.weight(1f),
                                enabled = !isBanned
                            ) { Text("Clear") }
                        }
                    }
                }
            }
        }
    }
}

/* =========================
   Setup health checklist
   ========================= */

private data class HealthItem(
    val title: String,
    val subtitle: String,
    val onFix: () -> Unit
)

@Composable
private fun SetupHealthCard(items: List<HealthItem>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Setup health",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            items.forEach { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { item.onFix() }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusDot(KitTone.Error, size = 8)
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Text(
                            item.subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                        )
                    }
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = "Fix",
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/* =========================
   Reorderable page-card slot
   ========================= */

/** Wraps a Home card; in Edit mode a slim strip above it carries up/down
 *  arrows so the page-level card order is reorderable like the toggles. */
@Composable
private fun HomeCardSlot(
    label: String,
    reorderMode: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (reorderMode) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row {
                        IconButton(onClick = { onMove(-1) }, enabled = canMoveUp, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move $label up", modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { onMove(1) }, enabled = canMoveDown, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move $label down", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
        content()
    }
}

/* =========================
   Preview + Start/Stop + Quick Toggles
   ========================= */

@Composable
private fun PreviewAndTogglesCard(
    vm: VrcaViewModel,
    isBanned: Boolean,
    nowTickMs: Long,
    cardReorderMode: Boolean,
    onToggleReorderMode: (Boolean) -> Unit,
    onResetOrder: () -> Unit,
    onNavigate: (AppPage) -> Unit
) {
    val ctx = LocalContext.current
    var previewExpanded by remember { mutableStateOf(UiPrefs.readPreviewExpanded(ctx)) }

    SectionCard(
        title = "VRChat Preview",
        titleStyle = MaterialTheme.typography.headlineSmall,
        subtitle = "What will appear in VRChat.",
        actions = {
            // The invisible-border eye toggle lives BELOW the preview (left of
            // the character counter), not here — with it in this row the wider
            // "Sending" chip squeezed the headline title into clipping.
            // No uptime in the chip — "Sending · 2h 14m" squeezed the
            // headline title into wrapping ("VRChat / Preview"). The Stop
            // button right below carries the uptime instead.
            SendStatusChip(sending = vm.oscSending)
            IconButton(
                onClick = {
                    previewExpanded = !previewExpanded
                    UiPrefs.writePreviewExpanded(ctx, previewExpanded)
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    if (previewExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (previewExpanded) "Collapse preview" else "Expand preview",
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    ) {
        val previewTextRaw = vm.debugLastCombinedOsc.ifBlank { "(nothing active)" }
        val previewText = remember(previewTextRaw) { vrChatSafePreview(previewTextRaw) }

        if (previewExpanded) {
            // Full in-game simulation — the original visual identity (bubble
            // over the avatar silhouette). Do not restyle (two redesigns were
            // reverted per user preference).
            //
            // No height cap and no Center arrangement: a max-height Column with
            // Arrangement.Center lets oversized content escape BOTH edges of
            // the card ("the preview overflows while sending"). The bubble text
            // is already capped at 9 lines, so letting the card grow naturally
            // is bounded and can never spill.
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier
                        .widthIn(max = 420.dp)
                        .fillMaxWidth(0.92f)
                        .heightIn(min = 96.dp)
                ) {
                    if (vm.minimalChatboxBg) {
                        // Invisible Chatbox Border simulation: in-game the bubble
                        // collapses to a NARROW VERTICAL capsule the full height of
                        // the text, centered behind it — mirror that exactly. The
                        // inner Box wraps the text's intrinsic height so the pill
                        // tracks however many lines are showing.
                        Box(
                            Modifier
                                .align(Alignment.Center)
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(IntrinsicSize.Min),
                                contentAlignment = Alignment.Center
                            ) {
                                // zIndex(-1f) forces the pill BEHIND the text —
                                // it rendered on top of the preview text on-device.
                                Surface(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .width(26.dp)
                                        .fillMaxHeight()
                                        .zIndex(-1f),
                                    tonalElevation = 3.dp,
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {}
                                SelectionContainer {
                                    Text(
                                        text = previewText,
                                        modifier = Modifier.fillMaxWidth(),
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodyMedium,
                                        textAlign = TextAlign.Center,
                                        softWrap = true,
                                        maxLines = 9,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    } else {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            tonalElevation = 3.dp,
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Box(
                                Modifier
                                    .heightIn(min = 96.dp)
                                    .padding(12.dp)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                SelectionContainer {
                                    Text(
                                        text = previewText,
                                        modifier = Modifier.fillMaxWidth(),
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodyMedium,
                                        // Explicit bright color: Surface(surfaceVariant)
                                        // switches LocalContentColor to the muted
                                        // onSurfaceVariant, which dimmed the preview
                                        // text ("the box is on top of the text").
                                        // In-game chatbox text is white — match it.
                                        color = MaterialTheme.colorScheme.onSurface,
                                        textAlign = TextAlign.Center,
                                        softWrap = true,
                                        maxLines = 9,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                Canvas(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .height(120.dp)
                        .width(120.dp)
                ) {
                    val w = size.width
                    val h = size.height

                    drawCircle(
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.08f),
                        radius = w * 0.18f,
                        center = Offset(w * 0.5f, h * 0.20f)
                    )

                    val path = Path().apply {
                        moveTo(w * 0.50f, h * 0.36f)
                        cubicTo(w * 0.18f, h * 0.40f, w * 0.18f, h * 0.96f, w * 0.50f, h * 0.98f)
                        cubicTo(w * 0.82f, h * 0.96f, w * 0.82f, h * 0.40f, w * 0.50f, h * 0.36f)
                        close()
                    }
                    drawPath(path, color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.06f))
                }
            }
        } else if (vm.minimalChatboxBg) {
            // Collapsed "live chip" with the Invisible Chatbox Border ON: mirror
            // the expanded simulation — text floating over a narrow vertical
            // pill instead of the full bubble (the collapsed chip used to ignore
            // the toggle).
            Box(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        previewExpanded = true
                        UiPrefs.writePreviewExpanded(ctx, true)
                    }
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .width(26.dp)
                            .fillMaxHeight()
                            .zIndex(-1f),
                        tonalElevation = 3.dp,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {}
                    Text(
                        text = previewText,
                        modifier = Modifier.fillMaxWidth(),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        softWrap = true,
                        maxLines = 9,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        } else {
            // Collapsed "live chip": the same bubble styling without the avatar
            // simulation. Shows the FULL chatbox content (same 9-line cap as the
            // expanded bubble) — a 3-line cap cut content off, which read as the
            // preview being broken.
            Surface(
                tonalElevation = 3.dp,
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        previewExpanded = true
                        UiPrefs.writePreviewExpanded(ctx, true)
                    }
            ) {
                Text(
                    text = previewText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    // Same explicit bright color as the expanded bubble — the
                    // surfaceVariant Surface otherwise dims text to onSurfaceVariant.
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    softWrap = true,
                    maxLines = 9,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Eye toggle (left) + character budget (right) on one row under the
        // preview. The eye is the Invisible Chatbox Border quick-toggle —
        // tiny, unlabeled by design; the preview's reaction IS the
        // explanation. The counter shows how much of VRChat's 144-char
        // chatbox limit the current combined output uses (142 with the
        // invisible border, which reserves 2 chars for its control suffix).
        // Amber near the cap, red over.
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { vm.setMinimalChatboxBgFlag(!vm.minimalChatboxBg) },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    if (vm.minimalChatboxBg) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (vm.minimalChatboxBg) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Cycle countdown sits inline so it doesn't shift the layout
            // when it appears/disappears while sending.
            if (vm.oscSending && vm.cycleEnabled && vm.nextCycleAtMs > 0L) {
                val secsLeft = ((vm.nextCycleAtMs - nowTickMs) / 1000L).coerceAtLeast(0L)
                Spacer(Modifier.width(4.dp))
                Text(
                    "Next cycle in ${secsLeft}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.weight(1f))
            val used = vm.debugLastCombinedOsc.length
            val budget = if (vm.minimalChatboxBg) 142 else 144
            Text(
                "$used / $budget characters",
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    used > budget -> MaterialTheme.colorScheme.error
                    used > budget - 30 -> androidx.compose.ui.graphics.Color(0xFFFFB300)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }

        // Primary Start / Stop control — gates whether the configured toggles
        // actually transmit over OSC. Start launches the senders for whatever is
        // toggled on; Stop halts sending and clears the VRChat chatbox WITHOUT
        // untoggling anything (so Start resumes exactly what was set up).
        if (vm.oscSending) {
            Button(
                onClick = { vm.stopSending() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(
                    Icons.Filled.Stop,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onError
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Stop sending" + if (vm.sendingSinceMs > 0L)
                        " · ${formatUptime(nowTickMs - vm.sendingSinceMs)}" else "",
                    color = MaterialTheme.colorScheme.onError
                )
            }
        } else {
            Button(
                onClick = { vm.startSending() },
                enabled = !isBanned,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Start sending")
            }
        }

        // Warning chips under the preview
        if (vm.cycleTrimWarning.isNotBlank()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "${vm.cycleTrimWarning}",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }

        ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

                // Quick Toggles title row with Edit/Done toggle and Reset button
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Quick Toggles", style = MaterialTheme.typography.titleSmall)
                        Text(
                            (if (vm.oscSending) "Edits show live" else "Press Start when ready") +
                                " · hold a pill to edit",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (cardReorderMode) {
                            TextButton(
                                onClick = onResetOrder,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) { Text("Reset", style = MaterialTheme.typography.labelSmall) }
                        }
                        TextButton(
                            onClick = { onToggleReorderMode(!cardReorderMode) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                if (cardReorderMode) "Done" else "Edit",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }

                if (cardReorderMode) {
                    // Edit mode: vertical rows with up/down arrows — order =
                    // top-to-bottom in the chatbox output.
                    QuickTogglesReorderList(vm = vm, isBanned = isBanned)
                } else {
                    // Normal mode: compact 2×2 pill grid in chatbox-output order.
                    QuickTogglesGrid(vm = vm, isBanned = isBanned, onNavigate = onNavigate)
                }
            }
        }
    }
}

/** Vertical list of TogglePills in [VrcaViewModel.cardOrder] order. Long-press
 *  jumps to the feature's edit page (Time long-press opens its UTC menu). */
@Composable
private fun QuickTogglesGrid(
    vm: VrcaViewModel,
    isBanned: Boolean,
    onNavigate: (AppPage) -> Unit
) {
    var timeMenuOpen by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        vm.cardOrder.forEach { component ->
            when (component) {
                "Pinned" -> TogglePill(
                    label = "Pinned",
                    icon = Icons.Filled.PushPin,
                    checked = vm.afkEnabled,
                    enabled = !isBanned,
                    onLongPress = { onNavigate(AppPage.Automations) },
                    modifier = Modifier.fillMaxWidth()
                ) { vm.setAfkEnabledFlag(it) }

                "Cycle" -> TogglePill(
                    label = "Cycle",
                    icon = Icons.Filled.Loop,
                    checked = vm.cycleEnabled,
                    enabled = !isBanned,
                    onLongPress = { onNavigate(AppPage.Automations) },
                    modifier = Modifier.fillMaxWidth()
                ) { vm.setCycleEnabledFlag(it) }

                "NowPlaying" -> TogglePill(
                    label = "Now Playing",
                    icon = Icons.Filled.MusicNote,
                    checked = vm.spotifyEnabled,
                    enabled = !isBanned,
                    onLongPress = { onNavigate(AppPage.Music) },
                    modifier = Modifier.fillMaxWidth()
                ) { vm.setSpotifyEnabledFlag(it) }

                "Time" -> Box {
                    val tzMode = vm.timeMode
                    val tzLabel = remember(tzMode) {
                        if (tzMode == "Device" || tzMode == "LOCAL" || tzMode.isBlank()) "Device"
                        else {
                            val zone = com.vrca.ui.common.resolveTimeZone(tzMode)
                            if (tzMode.startsWith("UTC")) com.vrca.ui.common.zoneOffsetLabel(zone)
                            else "${com.vrca.ui.common.timeZoneCity(tzMode)} (${com.vrca.ui.common.zoneOffsetLabel(zone)})"
                        }
                    }
                    TogglePill(
                        label = "Time · $tzLabel",
                        icon = Icons.Filled.Schedule,
                        checked = vm.timeEnabled,
                        enabled = !isBanned,
                        onLongPress = { timeMenuOpen = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { vm.updateTimeEnabled(it) }
                    if (timeMenuOpen) {
                        com.vrca.ui.common.VrcaTimeZoneDialog(
                            currentMode = vm.timeMode,
                            use24h = vm.time24h,
                            onSelect = { vm.updateTimeMode(it) },
                            onDismiss = { timeMenuOpen = false }
                        )
                    }
                }
            }
        }
    }
}

/** Edit-mode vertical list — the pre-existing reorder UI for the chatbox
 *  component order, unchanged in behavior. */
@Composable
private fun QuickTogglesReorderList(vm: VrcaViewModel, isBanned: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        vm.cardOrder.forEachIndexed { idx: Int, component: String ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f)) {
                    when (component) {
                        "Pinned" -> ToggleRow("Pinned", vm.afkEnabled, enabled = !isBanned) { vm.setAfkEnabledFlag(it) }
                        "Cycle" -> ToggleRow("Cycle", vm.cycleEnabled, enabled = !isBanned) { vm.setCycleEnabledFlag(it) }
                        "NowPlaying" -> ToggleRow("Now Playing", vm.spotifyEnabled, enabled = !isBanned) { vm.setSpotifyEnabledFlag(it) }
                        "Time" -> Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Time · ${vm.timeMode}")
                            Switch(checked = vm.timeEnabled, onCheckedChange = { vm.updateTimeEnabled(it) }, enabled = !isBanned)
                        }
                    }
                }
                Row {
                    IconButton(
                        onClick = {
                            val order = vm.cardOrder.toMutableList()
                            if (idx > 0) { val tmp = order[idx]; order[idx] = order[idx - 1]; order[idx - 1] = tmp; vm.updateCardOrder(order) }
                        },
                        enabled = idx > 0
                    ) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up", modifier = Modifier.size(18.dp))
                    }
                    IconButton(
                        onClick = {
                            val order = vm.cardOrder.toMutableList()
                            if (idx < order.size - 1) { val tmp = order[idx]; order[idx] = order[idx + 1]; order[idx + 1] = tmp; vm.updateCardOrder(order) }
                        },
                        enabled = idx < vm.cardOrder.size - 1
                    ) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

/* =========================
   Connection card
   ========================= */

@Composable
private fun ConnectionCard(
    vm: VrcaViewModel,
    ipAddress: String,
    ipOk: Boolean
) {
    // Headset build: VRChat runs on THIS Quest, so OSC targets localhost
    // automatically — no IP to enter. The phone build keeps the manual IP field
    // (it must point at the headset/PC over the LAN until a device-link exists).
    val isHeadset = BuildConfig.IS_HEADSET_BUILD

    val repo = vm.userPreferencesRepository
    val activeSlot by repo.activeIpSlot.collectAsState(initial = 1)
    val n1 by repo.ip1Name.collectAsState(initial = "Home")
    val n2 by repo.ip2Name.collectAsState(initial = "Hotspot")
    val n3 by repo.ip3Name.collectAsState(initial = "Other")
    val slotName = when (activeSlot) { 2 -> n2; 3 -> n3; else -> n1 }

    // Live reachability: lightweight periodic ping of the active target. OSC is
    // UDP fire-and-forget, so "no reply" is a WARNING (many devices simply
    // don't answer pings), not proof the target is dead — but a green check
    // catches "started sending into a dead IP" before the user wonders why
    // nothing shows in VRChat. Skipped on the headset (localhost is always up).
    var reachable by remember(ipAddress) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(ipAddress, isHeadset) {
        if (isHeadset || !ipOk) return@LaunchedEffect
        while (true) {
            // Robust ping (isReachable + system ping fallback) — the bare
            // InetAddress.isReachable misses devices that ICMP-reply fine
            // (no raw-socket permission), showing a false "No reply".
            reachable = com.vrca.ui.onboarding.pingHost(ipAddress)
            delay(20_000L)
        }
    }

    CompactSectionCard(
        title = "Connection",
        icon = Icons.Filled.Wifi,
        // Two lines while collapsed: the IP with the slot's device name under
        // it (the summary Text allows 2 lines).
        summary = when {
            isHeadset -> "This headset · 127.0.0.1"
            ipOk -> "$ipAddress\n$slotName"
            else -> "No headset IP set"
        },
        trailing = {
            when {
                isHeadset -> KitStatusChip("Automatic", KitTone.Success)
                !ipOk -> KitStatusChip("Not set", KitTone.Error)
                reachable == null -> KitStatusChip("Checking", KitTone.Neutral)
                reachable == true -> KitStatusChip("Reachable", KitTone.Success)
                else -> KitStatusChip("No reply", KitTone.Warning)
            }
        }
    ) {
        if (isHeadset) {
            Text(
                "VRChat runs on this headset, so VRC-A sends to it automatically at 127.0.0.1 — no IP to set. Just make sure OSC is enabled in VRChat.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            com.vrca.ui.conversation.IpField(
                chatboxViewModel = vm,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/* =========================
   Status chip + uptime
   ========================= */

private fun formatUptime(elapsedMs: Long): String {
    val s = (elapsedMs / 1000L).coerceAtLeast(0L)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m"
        else -> "${s / 3600}h ${(s % 3600) / 60}m"
    }
}

/**
 * Compact OSC send-state indicator. A filled amber circle + "Sending · 2h 14m"
 * while OSC is transmitting (uptime counts since Start was pressed and survives
 * OEM kills); a muted grey circle + "Idle" when nothing is being sent. This is
 * the at-a-glance answer to "is the chatbox actually updating right now?".
 */
@Composable
private fun SendStatusChip(sending: Boolean, uptime: String? = null) {
    val label = when {
        sending && uptime != null -> "Sending · $uptime"
        sending -> "Sending"
        else -> "Idle"
    }
    KitStatusChip(label, if (sending) KitTone.Warning else KitTone.Neutral)
}
