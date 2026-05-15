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

// -------------------------
// Legacy pages (unchanged logic)
// -------------------------

private enum class AutomationsTab(val title: String) {
    AFK("Pinned"),
    Cycle("Cycle")
}

@Composable
internal fun LegacyChatboxMainPages(
    vm: VrcaViewModel,
    startPage: String,
    modifier: Modifier
) {
    // We use your existing per-page UIs exactly as-is.
    // This just routes based on the requested page.
    Box(modifier = modifier.fillMaxSize()) {
        when (startPage.lowercase()) {
            "home" -> HomePage(vm)
            "automations" -> AutomationsPage(vm)
            "music" -> NowPlayingPage(vm)
            "debug" -> DebugPage(vm)
            else -> HomePage(vm)
        }
    }
}

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
private fun SectionCard(
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
private fun HomePage(vm: VrcaViewModel) {
    val uiState by vm.messengerUiState.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current

    var wizardExpanded by rememberSaveable { mutableStateOf(true) }
    var testSentOnce by rememberSaveable { mutableStateOf(false) }

    val overlayGranted = remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    LaunchedEffect(Unit) { overlayGranted.value = Settings.canDrawOverlays(ctx) }

    val pm = remember(ctx) { ctx.getSystemService(Context.POWER_SERVICE) as PowerManager }
    val batteryOk = remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(ctx.packageName)) }
    LaunchedEffect(Unit) { batteryOk.value = pm.isIgnoringBatteryOptimizations(ctx.packageName) }

    val notifOk = vm.listenerConnected
    val ipOk = uiState.ipAddress.isNotBlank() && uiState.ipAddress != "127.0.0.1"

    PageContainer {
        SectionCard(
            title = "VRChat Preview",
            subtitle = "Exactly what will appear in your chatbox.",
            actions = {
                AssistChip(
                    onClick = { /* settings moved to Settings page */ },
                    label = { Text("Setup") },
                    leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) }
                )
                Button(
                    onClick = { vm.killStopAndClear() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("KILL", color = MaterialTheme.colorScheme.onError)
                }
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
                    shape = MaterialTheme.shapes.large
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
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.10f),
                        radius = w * 0.18f,
                        center = Offset(w * 0.5f, h * 0.20f)
                    )

                    val path = Path().apply {
                        moveTo(w * 0.50f, h * 0.36f)
                        cubicTo(w * 0.18f, h * 0.40f, w * 0.18f, h * 0.96f, w * 0.50f, h * 0.98f)
                        cubicTo(w * 0.82f, h * 0.96f, w * 0.82f, h * 0.40f, w * 0.50f, h * 0.36f)
                        close()
                    }
                    drawPath(path, color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.08f))
                }
            }

            ElevatedCard {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Quick Toggles", style = MaterialTheme.typography.titleSmall)

                    ToggleRow("Pinned", vm.afkEnabled) { vm.setAfkEnabledFlag(it) }
                    ToggleRow("Cycle", vm.cycleEnabled) { vm.setCycleEnabledFlag(it) }
                    ToggleRow("Now Playing", vm.spotifyEnabled) { vm.setSpotifyEnabledFlag(it) }
                }
            }
        }

        SectionCard(
            title = "Setup Wizard",
            subtitle = "Do these once for stable OSC + Now Playing.",
            actions = {
                IconButton(onClick = { wizardExpanded = !wizardExpanded }) {
                    Icon(
                        imageVector = if (wizardExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null
                    )
                }
            }
        ) {
            AnimatedVisibility(visible = wizardExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    WizardStep(
                        number = 1,
                        title = "Enable Notification Access",
                        subtitle = "Required for Now Playing detection.",
                        done = notifOk,
                        icon = Icons.Filled.MusicNote,
                        primary = "Open"
                    ) { ctx.startActivity(vm.notificationAccessIntent()) }

                    WizardStep(
                        number = 2,
                        title = "Allow Overlay permission",
                        subtitle = "Only needed if you use the overlay.",
                        done = overlayGranted.value,
                        icon = Icons.Filled.Bolt,
                        primary = "Open"
                    ) {
                        ctx.startActivity(vm.overlayPermissionIntent())
                        overlayGranted.value = Settings.canDrawOverlays(ctx)
                    }

                    WizardStep(
                        number = 3,
                        title = "Disable Battery Optimization",
                        subtitle = "Stops Android pausing the app while screen is off.",
                        done = batteryOk.value,
                        icon = Icons.Filled.Power,
                        primary = "Request"
                    ) {
                        ctx.startActivity(vm.batteryOptimizationIntent())
                        batteryOk.value = pm.isIgnoringBatteryOptimizations(ctx.packageName)
                    }

                    WizardStep(
                        number = 4,
                        title = "Set Headset IP",
                        subtitle = "Configure IP in the Connection section below.",
                        done = ipOk,
                        icon = Icons.Filled.Wifi,
                        primary = "Done"
                    ) { }

                    WizardStep(
                        number = 5,
                        title = "Test Send",
                        subtitle = "Sends your current combined preview once.",
                        done = testSentOnce,
                        icon = Icons.Filled.CheckCircle,
                        primary = "Send"
                    ) {
                        when {
                            vm.spotifyEnabled -> vm.sendNowPlayingOnce()
                            vm.afkEnabled -> vm.sendAfkNow()
                            vm.messageText.value.text.isNotBlank() -> vm.sendMessage()
                            else -> vm.killStopAndClear()
                        }
                        testSentOnce = true
                    }
                }
            }
        }

        SectionCard(
            title = "Connection",
            subtitle = "Headset IP (Quest / PC)."
        ) {
            com.vrca.ui.conversation.IpField(
                chatboxViewModel = vm,
                modifier = Modifier.fillMaxWidth()
            )
        }

        SectionCard(
            title = "Manual Send",
            subtitle = "One-off message (doesn't affect Pinned/Cycle/Now Playing)."
        ) {
            OutlinedTextField(
                value = vm.messageText.value,
                onValueChange = { vm.onMessageTextChange(it) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                label = { Text("Message") }
            )
            Button(onClick = { vm.sendMessage() }, modifier = Modifier.fillMaxWidth()) {
                Text("Send")
            }
        }
    }
}

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
private fun SubSectionLabel(text: String, topPadding: Dp = 4.dp) {
    Spacer(Modifier.height(topPadding))
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun WizardStep(
    number: Int,
    title: String,
    subtitle: String,
    done: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    primary: String,
    onPrimary: () -> Unit
) {
    ElevatedCard(
        colors = if (done) CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ) else CardDefaults.elevatedCardColors()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "$number. $title",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(8.dp))
                    if (done) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "Done",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            TextButton(onClick = onPrimary) {
                Text(primary)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Filled.ChevronRight, contentDescription = null)
            }
        }
    }
}

/* ==============================
   AUTOMATIONS (Pinned + Cycle)
   ============================== */

@Composable
private fun AutomationsPage(vm: VrcaViewModel) {
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(AutomationsTab.AFK) }

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

    fun afkPresetsPreview(): String {
        val parts = (1..3).map { slot ->
            val p = vm.getAfkPresetPreview(slot).ifBlank { "empty" }
            "${slot}:${p}"
        }
        return parts.joinToString("  *  ").let { if (it.length > 80) it.take(79) + "..." else it }
    }

    fun cyclePresetsPreview(): String {
        val parts = (1..5).map { slot ->
            val p = vm.getCyclePresetPreview(slot).ifBlank { "empty" }
            "${slot}:${p}"
        }
        return parts.joinToString("  *  ").let { if (it.length > 80) it.take(79) + "..." else it }
    }

    PageContainer {
        ElevatedCard {
            Column(Modifier.padding(10.dp)) {
                TabRow(selectedTabIndex = tab.ordinal) {
                    AutomationsTab.entries.forEachIndexed { idx, t ->
                        Tab(
                            selected = (tab.ordinal == idx),
                            onClick = { tab = t },
                            text = { Text(t.title) }
                        )
                    }
                }
            }
        }

        when (tab) {
            AutomationsTab.AFK -> {
                SectionCard(
                    title = "Pinned",
                    subtitle = "Pinned always appears above Cycle + Music."
                ) {
                    ToggleRow("Pinned enabled", vm.afkEnabled) { vm.setAfkEnabledFlag(it) }

                    OutlinedTextField(
                        value = vm.afkMessage,
                        onValueChange = { vm.updateAfkText(it) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Pinned text") }
                    )

                    ElevatedCard {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { vm.updateAfkPresetsCollapsed(!vm.afkPresetsCollapsed) },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Pinned Presets (3)", style = MaterialTheme.typography.titleSmall)
                                    if (vm.afkPresetsCollapsed) {
                                        Text(
                                            afkPresetsPreview(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Icon(
                                    imageVector = if (vm.afkPresetsCollapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                                    contentDescription = null
                                )
                            }

                            if (!vm.afkPresetsCollapsed) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    (1..3).forEach { slot ->
                                        ElevatedCard {
                                            Column(
                                                Modifier.padding(10.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                val preview = vm.getAfkPresetPreview(slot).ifBlank { "(empty)" }
                                                Text("Preset $slot - $preview")

                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    OutlinedButton(
                                                        onClick = { scope.launch { vm.loadAfkPreset(slot) } },
                                                        modifier = Modifier.weight(1f)
                                                    ) { Text("Load") }

                                                    Button(
                                                        onClick = { scope.launch { vm.saveAfkPreset(slot, vm.afkMessage) } },
                                                        modifier = Modifier.weight(1f)
                                                    ) { Text("Save") }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { vm.startAfkSender() },
                            modifier = Modifier.weight(1f),
                            enabled = vm.afkEnabled
                        ) { Text("Start") }

                        OutlinedButton(
                            onClick = { vm.stopAfkSender(clearFromChatbox = true) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Stop") }
                    }

                    OutlinedButton(
                        onClick = { vm.sendAfkNow() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = vm.afkEnabled
                    ) { Text("Send once") }
                }
            }

            AutomationsTab.Cycle -> {
                SectionCard(
                    title = "Cycle",
                    subtitle = "Up to 10 lines. Stop clears instantly."
                ) {
                    ToggleRow("Cycle enabled", vm.cycleEnabled) { vm.setCycleEnabledFlag(it) }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (vm.cycleLines.isEmpty()) {
                            Text("No lines yet. Tap Add Line.", style = MaterialTheme.typography.bodySmall)
                        }

                        vm.cycleLines.forEachIndexed { idx, _ ->
                            val fieldValue =
                                cycleLineFields[idx] ?: TextFieldValue(vm.cycleLines.getOrNull(idx).orEmpty())

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = fieldValue,
                                    onValueChange = { newValue ->
                                        cycleLineFields[idx] = newValue
                                        vm.updateCycleLine(idx, newValue.text)
                                    },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    label = { Text("Line ${idx + 1}") }
                                )
                                Spacer(Modifier.width(8.dp))
                                IconButton(onClick = { vm.removeCycleLine(idx) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Remove line")
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { vm.addCycleLine() },
                                enabled = vm.cycleLines.size < 10,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Add line (${vm.cycleLines.size}/10)")
                            }

                            OutlinedButton(
                                onClick = { vm.clearCycleLines() },
                                enabled = vm.cycleLines.isNotEmpty(),
                                modifier = Modifier.weight(1f)
                            ) { Text("Clear") }
                        }
                    }

                    Text(
                        text = "Cycle speed: fixed at 10 seconds",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    ElevatedCard {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { vm.updateCyclePresetsCollapsed(!vm.cyclePresetsCollapsed) },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Cycle Presets (5)", style = MaterialTheme.typography.titleSmall)
                                    if (vm.cyclePresetsCollapsed) {
                                        Text(
                                            cyclePresetsPreview(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Icon(
                                    imageVector = if (vm.cyclePresetsCollapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                                    contentDescription = null
                                )
                            }

                            if (!vm.cyclePresetsCollapsed) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    (1..5).forEach { slot ->
                                        ElevatedCard {
                                            Column(
                                                Modifier.padding(10.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                val preview = vm.getCyclePresetPreview(slot).ifBlank { "(empty)" }
                                                Text("Preset $slot - $preview")

                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    OutlinedButton(
                                                        onClick = { scope.launch { vm.loadCyclePreset(slot) } },
                                                        modifier = Modifier.weight(1f)
                                                    ) { Text("Load") }

                                                    Button(
                                                        onClick = { scope.launch { vm.saveCyclePreset(slot, vm.cycleLines.toList()) } },
                                                        modifier = Modifier.weight(1f)
                                                    ) { Text("Save") }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { vm.startCycle() },
                            modifier = Modifier.weight(1f),
                            enabled = vm.cycleEnabled && vm.cycleLines.any { it.trim().isNotEmpty() }
                        ) { Text("Start") }

                        OutlinedButton(
                            onClick = { vm.stopCycle(clearFromChatbox = true) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Stop") }
                    }
                }
            }
        }
    }
}

/* =========================
   MUSIC (Now Playing)
   ========================= */

@Composable
private fun MusicPresetPreviewText(
    previewTextProvider: (Float) -> String
) {
    val infinite = rememberInfiniteTransition(label = "musicPresetPreview")
    val tState = infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "musicPresetPreviewT"
    )

    Text(
        text = previewTextProvider(tState.value),
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace
    )
}

@Composable
private fun NowPlayingPage(vm: VrcaViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current

    PageContainer {
        SectionCard(
            title = "Now Playing",
            subtitle = "Uses Notification Access. Stop clears instantly."
        ) {
            ToggleRow("Enable Now Playing block", vm.spotifyEnabled) { vm.setSpotifyEnabledFlag(it) }
            ToggleRow("Demo mode (testing)", vm.spotifyDemoEnabled) { vm.setSpotifyDemoFlag(it) }

            OutlinedButton(
                onClick = { ctx.startActivity(vm.notificationAccessIntent()) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Open Notification Access settings") }

            Text(
                text = "Music refresh speed: fixed at 0.5 seconds",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text("Progress bar preset:", style = MaterialTheme.typography.labelLarge)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..5).forEach { p ->
                    val selected = (vm.spotifyPreset == p)
                    val name = vm.getMusicPresetName(p)

                    ElevatedCard(
                        colors = if (selected) CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        ) else CardDefaults.elevatedCardColors()
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(10.dp)
                                .clickable { vm.updateSpotifyPreset(p) },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(text = name, style = MaterialTheme.typography.titleSmall)
                                MusicPresetPreviewText { t -> vm.renderMusicPresetPreview(p, t) }
                            }
                            if (selected) Text("Selected", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { vm.startNowPlayingSender() },
                    modifier = Modifier.weight(1f),
                    enabled = vm.spotifyEnabled
                ) { Text("Start") }

                OutlinedButton(
                    onClick = { vm.stopNowPlayingSender(clearFromChatbox = true) },
                    modifier = Modifier.weight(1f)
                ) { Text("Stop") }
            }

            OutlinedButton(
                onClick = { vm.sendNowPlayingOnce() },
                modifier = Modifier.fillMaxWidth(),
                enabled = vm.spotifyEnabled
            ) { Text("Send once now (test)") }
        }

        SectionCard(
            title = "Detected / Preview",
            subtitle = "If blank: enable access, restart app, then play music."
        ) {
            Text("Detected: ${vm.nowPlayingDetected}")
            Text("Artist: ${vm.lastNowPlayingArtist}")
            Text("Title: ${vm.lastNowPlayingTitle}")
            Text("App: ${vm.activePackage}")
            Text("Status: ${if (vm.nowPlayingIsPlaying) "Playing" else "Paused"}")
        }
    }
}

/* =========================
   DEBUG
   ========================= */

@Composable
private fun DebugPage(vm: VrcaViewModel) {
    PageContainer {
        SectionCard(
            title = "Listener",
            subtitle = "Confirms Notification Access + media detection."
        ) {
            Text("Listener connected: ${vm.listenerConnected}")
            Text("Active package: ${vm.activePackage}")
            Text("Detected: ${vm.nowPlayingDetected}")
            Text("Playing: ${vm.nowPlayingIsPlaying}")
        }

        SectionCard(
            title = "OSC Output Preview",
            subtitle = "Raw lines + combined output."
        ) {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Pinned:", style = MaterialTheme.typography.labelLarge)
                    Text(vm.debugLastAfkOsc, fontFamily = FontFamily.Monospace)

                    Text("Cycle:", style = MaterialTheme.typography.labelLarge)
                    Text(vm.debugLastCycleOsc, fontFamily = FontFamily.Monospace)

                    Text("Music:", style = MaterialTheme.typography.labelLarge)
                    Text(vm.debugLastMusicOsc, fontFamily = FontFamily.Monospace)

                    Text("Combined:", style = MaterialTheme.typography.labelLarge)
                    Text(vm.debugLastCombinedOsc, fontFamily = FontFamily.Monospace)
                }
            }
        }

        SectionCard(title = "VRChat send status") {
            Text("Last sent to VRChat (ms): ${vm.lastSentToVrchatAtMs}")
        }
    }
}

// -------------------------
// NEW: SlimeVR-like Settings page (UI-only, uses existing VM + SettingsStates)
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
        // Section chooser (SlimeVR left list vibe, but mobile-friendly)
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
                    Button(
                        onClick = { section = s },
                        colors = if (selected) ButtonDefaults.buttonColors()
                        else ButtonDefaults.outlinedButtonColors()
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
                    Button(
                        onClick = { ctx.startActivity(vm.notificationAccessIntent()) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Open Notification Access") }

                    OutlinedButton(
                        onClick = { ctx.startActivity(vm.overlayPermissionIntent()) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Open Overlay Permission") }

                    OutlinedButton(
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
                    ToggleRow("Music demo mode", vm.spotifyDemoEnabled) { vm.setSpotifyDemoFlag(it) }
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
                        Button(
                            onClick = { vm.ipAddressApply(ip.text.trim()) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Apply") }

                        OutlinedButton(
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

                    OutlinedButton(
                        onClick = { ctx.startActivity(vm.notificationAccessIntent()) },
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

/**
 * Makes preview behave nicer:
 * - Inserts zero-width breaks into long unbroken tokens so Text can wrap
 * - Keeps newlines intact
 */
private fun vrChatSafePreview(input: String): String {
    val zwsp = '\u200B'
    val maxToken = 18

    fun breakLongToken(token: String): String {
        if (token.length <= maxToken) return token
        val sb = StringBuilder(token.length + token.length / maxToken)
        var i = 0
        while (i < token.length) {
            val end = (i + maxToken).coerceAtMost(token.length)
            sb.append(token.substring(i, end))
            if (end < token.length) sb.append(zwsp)
            i = end
        }
        return sb.toString()
    }

    return input.lines().joinToString("\n") { line ->
        line.split(" ").joinToString(" ") { breakLongToken(it) }
    }
}

/* =========================
   Notification toggle section
   ========================= */

@Composable
fun NotificationToggleSection(vm: VrcaViewModel) {
    val scope = rememberCoroutineScope()
    val repo = vm.userPreferencesRepository

    val friendRequest      by repo.notifFriendRequest.collectAsState(initial = false)
    val newFriend          by repo.notifNewFriend.collectAsState(initial = false)
    val unfriend           by repo.notifUnfriend.collectAsState(initial = false)
    val vrchatMessage      by repo.notifVrchatMessage.collectAsState(initial = false)
    val friendOnline       by repo.notifFriendOnline.collectAsState(initial = false)
    val friendOffline      by repo.notifFriendOffline.collectAsState(initial = false)
    val friendActive       by repo.notifFriendActive.collectAsState(initial = false)
    val friendLocation     by repo.notifFriendLocation.collectAsState(initial = false)
    val friendStatus       by repo.notifFriendStatus.collectAsState(initial = false)
    val friendAvatar       by repo.notifFriendAvatar.collectAsState(initial = false)
    val friendBio          by repo.notifFriendBio.collectAsState(initial = false)
    val friendDisplayName  by repo.notifFriendDisplayName.collectAsState(initial = false)
    val voteToKick         by repo.notifVoteToKick.collectAsState(initial = false)
    val friendRank         by repo.notifFriendRank.collectAsState(initial = false)
    val invite             by repo.notifInvite.collectAsState(initial = false)
    val inviteRequest      by repo.notifInviteRequest.collectAsState(initial = false)
    val groupInvite        by repo.notifGroupInvite.collectAsState(initial = false)
    val groupAnnouncement  by repo.notifGroupAnnouncement.collectAsState(initial = false)
    val groupEvent         by repo.notifGroupEvent.collectAsState(initial = false)
    val groupQueue         by repo.notifGroupQueue.collectAsState(initial = false)
    val groupJoinRequest   by repo.notifGroupJoinRequest.collectAsState(initial = false)
    val groupRole          by repo.notifGroupRole.collectAsState(initial = false)
    val groupInstance      by repo.notifGroupInstance.collectAsState(initial = false)
    val appUpdate          by repo.notifAppUpdate.collectAsState(initial = true)
    val announcements      by repo.notifAnnouncements.collectAsState(initial = true)
    val connection         by repo.notifConnection.collectAsState(initial = false)
    val auth               by repo.notifAuth.collectAsState(initial = true)
    val vrchatAlert        by repo.notifVrchatAlert.collectAsState(initial = false)
    val giftReceived       by repo.notifGiftReceived.collectAsState(initial = false)

    var friendListExpanded by rememberSaveable { mutableStateOf(false) }
    var friendsActivityExpanded by rememberSaveable { mutableStateOf(false) }
    var invitesExpanded by rememberSaveable { mutableStateOf(false) }
    var groupsExpanded by rememberSaveable { mutableStateOf(false) }
    var appConnectionExpanded by rememberSaveable { mutableStateOf(false) }

    SectionCard(
        title = "Friend list",
        subtitle = "Changes to who's on your friends list.",
        actions = {
            IconButton(onClick = { friendListExpanded = !friendListExpanded }) {
                Icon(
                    if (friendListExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (friendListExpanded) "Collapse" else "Expand"
                )
            }
        }
    ) {
        AnimatedVisibility(
            visible = friendListExpanded,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 3 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 3 })
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ToggleRow("Friend request received", friendRequest,
                    description = "When someone sends you a friend request") {
                    scope.launch { repo.saveNotifFriendRequest(it) }
                }
                ToggleRow("New friend added", newFriend,
                    description = "When someone accepts your request or you accept theirs") {
                    scope.launch { repo.saveNotifNewFriend(it) }
                }
                ToggleRow("Friend removed", unfriend,
                    description = "When someone is no longer on your friends list") {
                    scope.launch { repo.saveNotifUnfriend(it) }
                }
                ToggleRow("VRChat in-app messages", vrchatMessage,
                    description = "Direct messages sent through VRChat") {
                    scope.launch { repo.saveNotifVrchatMessage(it) }
                }
            }
        }
    }

    SectionCard(
        title = "Friends activity",
        subtitle = "What your friends are doing in VRChat.",
        actions = {
            IconButton(onClick = { friendsActivityExpanded = !friendsActivityExpanded }) {
                Icon(
                    if (friendsActivityExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (friendsActivityExpanded) "Collapse" else "Expand"
                )
            }
        }
    ) {
        AnimatedVisibility(
            visible = friendsActivityExpanded,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 3 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 3 })
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SubSectionLabel("Presence", topPadding = 0.dp)
                ToggleRow("Friend came online", friendOnline,
                    description = "When a friend logs into VRChat") {
                    scope.launch { repo.saveNotifFriendOnline(it) }
                }
                ToggleRow("Friend went offline", friendOffline,
                    description = "When a friend leaves VRChat (10-min delay)") {
                    scope.launch { repo.saveNotifFriendOffline(it) }
                }
                ToggleRow("Friend on website (not in VR)", friendActive,
                    description = "When a friend is browsing the VRChat website") {
                    scope.launch { repo.saveNotifFriendActive(it) }
                }
                ToggleRow("Friend changed worlds", friendLocation,
                    description = "When a friend joins a different public world") {
                    scope.launch { repo.saveNotifFriendLocation(it) }
                }

                SubSectionLabel("Profile changes")
                ToggleRow("Friend changed presence", friendStatus,
                    description = "Online, Join Me, Ask Me, or Do Not Disturb") {
                    scope.launch { repo.saveNotifFriendStatus(it) }
                }
                ToggleRow("Friend changed avatar", friendAvatar,
                    description = "When a friend switches to a different avatar") {
                    scope.launch { repo.saveNotifFriendAvatar(it) }
                }
                ToggleRow("Friend updated bio", friendBio,
                    description = "When a friend edits their profile bio") {
                    scope.launch { repo.saveNotifFriendBio(it) }
                }
                ToggleRow("Friend renamed themselves", friendDisplayName,
                    description = "When a friend changes their display name") {
                    scope.launch { repo.saveNotifFriendDisplayName(it) }
                }
                ToggleRow("Friend trust rank changed", friendRank,
                    description = "Known → Trusted, New User → Known, etc.") {
                    scope.launch { repo.saveNotifFriendRank(it) }
                }

                SubSectionLabel("Alerts")
                ToggleRow("Vote-to-kick warnings", voteToKick,
                    description = "When a vote-to-kick is started in your instance") {
                    scope.launch { repo.saveNotifVoteToKick(it) }
                }
            }
        }
    }

    SectionCard(
        title = "Invites",
        subtitle = "World and group invites.",
        actions = {
            IconButton(onClick = { invitesExpanded = !invitesExpanded }) {
                Icon(
                    if (invitesExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (invitesExpanded) "Collapse" else "Expand"
                )
            }
        }
    ) {
        AnimatedVisibility(
            visible = invitesExpanded,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 3 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 3 })
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ToggleRow("World invites", invite,
                    description = "When someone invites you to a world") {
                    scope.launch { repo.saveNotifInvite(it) }
                }
                ToggleRow("Invite requests", inviteRequest,
                    description = "When someone asks for an invite to your instance") {
                    scope.launch { repo.saveNotifInviteRequest(it) }
                }
                ToggleRow("Group invites", groupInvite,
                    description = "When you're invited to join a group") {
                    scope.launch { repo.saveNotifGroupInvite(it) }
                }
            }
        }
    }

    SectionCard(
        title = "Groups",
        subtitle = "Activity in groups you're part of.",
        actions = {
            IconButton(onClick = { groupsExpanded = !groupsExpanded }) {
                Icon(
                    if (groupsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (groupsExpanded) "Collapse" else "Expand"
                )
            }
        }
    ) {
        AnimatedVisibility(
            visible = groupsExpanded,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 3 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 3 })
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SubSectionLabel("Updates", topPadding = 0.dp)
                ToggleRow("Group announcements", groupAnnouncement,
                    description = "Posts from group owners and managers") {
                    scope.launch { repo.saveNotifGroupAnnouncement(it) }
                }
                ToggleRow("Queue ready", groupQueue,
                    description = "When your spot in a group instance queue opens") {
                    scope.launch { repo.saveNotifGroupQueue(it) }
                }
                ToggleRow("New group instance opened", groupInstance,
                    description = "When a new joinable instance is created for a group") {
                    scope.launch { repo.saveNotifGroupInstance(it) }
                }

                SubSectionLabel("Management")
                ToggleRow("Join requests (group managers)", groupJoinRequest,
                    description = "When someone wants to join a group you manage") {
                    scope.launch { repo.saveNotifGroupJoinRequest(it) }
                }
                ToggleRow("Group role / rank changes", groupRole,
                    description = "When your role in a group is changed") {
                    scope.launch { repo.saveNotifGroupRole(it) }
                }
                ToggleRow("Other group activity", groupEvent,
                    description = "Catch-all for other group events") {
                    scope.launch { repo.saveNotifGroupEvent(it) }
                }
            }
        }
    }

    SectionCard(
        title = "App and connection",
        subtitle = "VRC-A system alerts.",
        actions = {
            IconButton(onClick = { appConnectionExpanded = !appConnectionExpanded }) {
                Icon(
                    if (appConnectionExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (appConnectionExpanded) "Collapse" else "Expand"
                )
            }
        }
    ) {
        AnimatedVisibility(
            visible = appConnectionExpanded,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 3 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 3 })
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SubSectionLabel("App", topPadding = 0.dp)
                ToggleRow("New app version available", appUpdate,
                    description = "When a new VRC-A update is available") {
                    scope.launch { repo.saveNotifAppUpdate(it) }
                }
                ToggleRow("Admin announcements", announcements,
                    description = "Messages from the VRC-A developer") {
                    scope.launch { repo.saveNotifAnnouncements(it) }
                }

                SubSectionLabel("VRChat")
                ToggleRow("VRChat connection status", connection,
                    description = "When VRChat monitoring connects or disconnects") {
                    scope.launch { repo.saveNotifConnection(it) }
                }
                ToggleRow("Sign-in required alerts", auth,
                    description = "When your VRChat session expires and needs re-login") {
                    scope.launch { repo.saveNotifAuth(it) }
                }
                ToggleRow("VRChat server alerts", vrchatAlert,
                    description = "Maintenance notices and other VRChat server alerts") {
                    scope.launch { repo.saveNotifVrchatAlert(it) }
                }
                ToggleRow("VRChat Plus / credit gifts", giftReceived,
                    description = "When someone sends you VRChat Plus or credits") {
                    scope.launch { repo.saveNotifGiftReceived(it) }
                }
            }
        }
    }
}
