package com.vrca.ui.screen

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Divider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vrca.ui.settings.ToggleRow
import com.vrca.ui.viewmodel.VrcaViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun HomePage(
    vm: VrcaViewModel,
    snackbarHostState: SnackbarHostState,
    onOpenSettings: () -> Unit,
    announcements: List<AnnouncementUi>,
    moderation: ModerationUi,
    isBanned: Boolean
) {
    val uiState by vm.messengerUiState.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val connectionBring = remember { BringIntoViewRequester() }
    val manualSendBring = remember { BringIntoViewRequester() }

    var tutorialExpanded by remember { mutableStateOf(UiPrefs.readTutorialExpanded(ctx)) }

    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    LaunchedEffect(Unit) { overlayGranted = Settings.canDrawOverlays(ctx) }

    val pm = remember(ctx) { ctx.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var batteryOk by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(ctx.packageName)) }
    LaunchedEffect(Unit) { batteryOk = pm.isIgnoringBatteryOptimizations(ctx.packageName) }

    val notifOk = vm.listenerConnected
    val ipOk = uiState.ipAddress.isNotBlank() && uiState.ipAddress != "127.0.0.1"

    val topAnnouncements = remember(announcements) {
        announcements
            .sortedWith(compareByDescending<AnnouncementUi> { it.priority }.thenByDescending { it.createdAt })
            .take(3)
    }

    PageContainer {
        if (topAnnouncements.isNotEmpty()) {
            SectionCard(
                title = "Announcements",
                subtitle = "Latest updates from the app team."
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    topAnnouncements.forEach { a ->
                        ElevatedCard(
                            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(a.title.ifBlank { "Announcement" }, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    a.body.ifBlank { "" },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }

        // VRChat server status warning
        VrchatStatusBanner()

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

        SectionCard(
            title = "VRChat Preview",
            titleStyle = MaterialTheme.typography.headlineSmall,
            subtitle = "What will appear in VRChat.",
            actions = {
                SendStatusChip(sending = vm.oscSending)
            }
        ) {
            val previewTextRaw = vm.debugLastCombinedOsc.ifBlank { "(nothing active)" }
            val previewText = remember(previewTextRaw) { vrChatSafePreview(previewTextRaw) }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(280.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = 420.dp)
                        .fillMaxWidth(0.92f),
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
                                textAlign = TextAlign.Center,
                                softWrap = true,
                                maxLines = 9,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Canvas(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                        .height(200.dp)
                        .width(170.dp)
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
                    Text("Stop sending", color = MaterialTheme.colorScheme.onError)
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
                    var cardReorderMode by remember { mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Quick Toggles", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (vm.oscSending) "Edits show live"
                                else "Press Start when Ready",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (cardReorderMode) {
                                TextButton(
                                    onClick = { vm.resetCardOrder() },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) { Text("Reset", style = MaterialTheme.typography.labelSmall) }
                            }
                            TextButton(
                                onClick = { cardReorderMode = !cardReorderMode },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    if (cardReorderMode) "Done" else "Edit",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }

                    // Reorderable component rows - order = top-to-bottom in chatbox output
                    vm.cardOrder.forEachIndexed { idx: Int, component: String ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Component toggle or time row
                            Box(Modifier.weight(1f)) {
                                when {
                                    component == "Pinned" -> ToggleRow("Pinned", vm.afkEnabled, enabled = !isBanned) { vm.setAfkEnabledFlag(it) }
                                    component == "Cycle" -> ToggleRow("Cycle", vm.cycleEnabled, enabled = !isBanned) { vm.setCycleEnabledFlag(it) }
                                    component == "NowPlaying" -> ToggleRow("Now Playing", vm.spotifyEnabled, enabled = !isBanned) { vm.setSpotifyEnabledFlag(it) }
                                    component == "Time" -> Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Time")
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            var timeModeMenuOpen by remember { mutableStateOf(false) }
                                            val timeModeOptions: List<String> = remember {
                                                buildList {
                                                    add("Device"); add("UTC")
                                                    for (h in 1..14) add("UTC+$h")
                                                    for (h in 1..12) add("UTC-$h")
                                                }
                                            }
                                            Box {
                                                OutlinedButton(
                                                    onClick = { timeModeMenuOpen = true },
                                                    enabled = !isBanned,
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                                ) {
                                                    Text(vm.timeMode, style = MaterialTheme.typography.bodySmall)
                                                    Icon(Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp))
                                                }
                                                DropdownMenu(expanded = timeModeMenuOpen, onDismissRequest = { timeModeMenuOpen = false }) {
                                                    timeModeOptions.forEach { mode: String ->
                                                        DropdownMenuItem(text = { Text(mode) }, onClick = { vm.updateTimeMode(mode); timeModeMenuOpen = false })
                                                    }
                                                }
                                            }
                                            Switch(checked = vm.timeEnabled, onCheckedChange = { vm.updateTimeEnabled(it) }, enabled = !isBanned)
                                        }
                                    }
                                }
                            }
                            if (cardReorderMode) {
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

                    // Display option, not a chatbox component: shrinks the in-game
                    // bubble background (testing). Persists across close/swipe.
                    Divider(color = MaterialTheme.colorScheme.outlineVariant)
                    ToggleRow(
                        "Minimal chatbox bubble",
                        vm.minimalChatboxBg,
                        enabled = !isBanned,
                        description = "Shrinks the bubble behind your text in VRChat. Uses 2 of the 144 characters."
                    ) { vm.setMinimalChatboxBgFlag(it) }
                }
            }
        }

        SectionCard(
            title = "Setup Tutorial",
            subtitle = "Tap each step to open settings or jump to the right place.",
            actions = {
                IconButton(onClick = {
                    tutorialExpanded = !tutorialExpanded
                    UiPrefs.writeTutorialExpanded(ctx, tutorialExpanded)
                }) {
                    Icon(
                        imageVector = if (tutorialExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null
                    )
                }
            }
        ) {
            AnimatedVisibility(
                visible = tutorialExpanded,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it / 3 }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 3 })
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TutorialStep(
                        number = 0,
                        title = "Enable OSC in VRChat",
                        subtitle = "VRChat -> Settings -> OSC -> Enable OSC.",
                        icon = Icons.Filled.Bolt,
                        primary = "How"
                    ) { scope.launch { snackbarHostState.showSnackbar("Open VRChat -> Settings -> OSC -> Enable OSC") } }

                    TutorialStep(
                        number = 1,
                        title = "Enable Notification Access",
                        subtitle = if (notifOk) "Enabled." else "Required for Now Playing detection.",
                        icon = Icons.Filled.MusicNote,
                        primary = "Open"
                    ) { ctx.startActivity(vm.notificationAccessIntent()) }

                    TutorialStep(
                        number = 2,
                        title = "Allow Overlay permission",
                        subtitle = if (overlayGranted) "Enabled." else "Only needed if you use overlay.",
                        icon = Icons.Filled.Bolt,
                        primary = "Open"
                    ) {
                        ctx.startActivity(vm.overlayPermissionIntent())
                        overlayGranted = Settings.canDrawOverlays(ctx)
                    }

                    TutorialStep(
                        number = 3,
                        title = "Disable Battery Optimization",
                        subtitle = if (batteryOk) "Disabled (good)." else "Stops Android pausing the app when screen is off.",
                        icon = Icons.Filled.Power,
                        primary = "Request"
                    ) {
                        ctx.startActivity(vm.batteryOptimizationIntent())
                        batteryOk = pm.isIgnoringBatteryOptimizations(ctx.packageName)
                    }

                    TutorialStep(
                        number = 4,
                        title = "Set Headset IP",
                        subtitle = if (ipOk) "Looks set." else "Quest/PC IP on the same Wi-Fi.",
                        icon = Icons.Filled.Wifi,
                        primary = "Go"
                    ) { scope.launch { connectionBring.bringIntoView() } }

                    TutorialStep(
                        number = 5,
                        title = "Manual Test Send",
                        subtitle = "Type a message and hit Send.",
                        icon = Icons.Filled.ChevronRight,
                        primary = "Go"
                    ) { scope.launch { manualSendBring.bringIntoView() } }
                }
            }
        }

        SectionCard(
            title = "Connection",
            subtitle = "Headset IP (Quest / PC)."
        ) {
            Column(Modifier.bringIntoViewRequester(connectionBring)) {
                com.vrca.ui.conversation.IpField(
                    chatboxViewModel = vm,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        SectionCard(
            title = "Manual Send",
            subtitle = "One-off message (doesn't affect Pinned/Cycle/Now Playing)."
        ) {
            Column(Modifier.bringIntoViewRequester(manualSendBring)) {
                OutlinedTextField(
                    value = vm.messageText.value,
                    onValueChange = { v: TextFieldValue -> vm.onMessageTextChange(v) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    label = { Text("Message") },
                    enabled = !isBanned
                )
                Button(
                    onClick = { vm.sendMessage() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isBanned
                ) {
                    Text("Send")
                }
            }
        }
    }
}


/**
 * Compact OSC send-state indicator. A filled amber circle + "Sending" while OSC
 * is transmitting; a muted grey circle + "Idle" when nothing is being sent. This
 * is the at-a-glance answer to "is the chatbox actually updating right now?".
 */
@Composable
private fun SendStatusChip(sending: Boolean) {
    val dotColor = if (sending) Color(0xFFFFC107) else MaterialTheme.colorScheme.outline
    val label = if (sending) "Sending" else "Idle"
    val container =
        if (sending) Color(0xFFFFC107).copy(alpha = 0.16f)
        else MaterialTheme.colorScheme.surfaceVariant
    Surface(
        shape = MaterialTheme.shapes.large,
        color = container
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun TutorialStep(
    number: Int,
    title: String,
    subtitle: String,
    icon: ImageVector,
    primary: String,
    onPrimary: () -> Unit
) {
    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onPrimary() }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null)
                }
            }

            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = "$number. $title",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            TextButton(onClick = onPrimary, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                Text(primary)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Filled.ChevronRight, contentDescription = null)
            }
        }
    }
}
