// app/src/main/kotlin/com/scrapw/chatbox/ChatboxScreen.kt
package com.scrapw.chatbox

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Divider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scrapw.chatbox.ui.ChatboxViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class ChatboxPage(val title: String) {
    Home("Home"),
    Automations("Automations"),
    Music("Music"),
    Debug("Debug"),
    Info("Info")
}

private enum class ChatboxAutomationsTab(val title: String) {
    AFK("AFK"),
    Cycle("Cycle")
}

private enum class ChatboxInfoTab(val title: String) {
    Overview("Overview"),
    Tutorial("Tutorial"),
    Troubleshoot("Help"),
    FullDoc("Full Doc")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatboxScreen(
    chatboxViewModel: ChatboxViewModel = viewModel(factory = ChatboxViewModel.Factory)
) {
    var page by rememberSaveable { mutableStateOf(ChatboxPage.Home) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showInfoSheet by remember { mutableStateOf(false) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            DrawerContent(
                current = page,
                onSelect = { chosen ->
                    page = chosen
                    scope.launch { drawerState.close() }
                    if (chosen == ChatboxPage.Info) showInfoSheet = true
                },
                onOpenSettings = {
                    showSettingsSheet = true
                    scope.launch { drawerState.close() }
                },
                onOpenInfo = {
                    showInfoSheet = true
                    scope.launch { drawerState.close() }
                }
            )
        }
    ) {
        Scaffold(
            modifier = Modifier
                .statusBarsPadding()
                .navigationBarsPadding(),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Chatbox") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Menu")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSettingsSheet = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    }
                )
            },
            contentWindowInsets = WindowInsets(0)
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                Crossfade(targetState = page, label = "page_crossfade") { p ->
                    when (p) {
                        ChatboxPage.Home -> HomePage(chatboxViewModel, onOpenSettings = { showSettingsSheet = true })
                        ChatboxPage.Automations -> AutomationsPage(chatboxViewModel)
                        ChatboxPage.Music -> NowPlayingPage(chatboxViewModel)
                        ChatboxPage.Debug -> DebugPage(chatboxViewModel)
                        ChatboxPage.Info -> HomePage(chatboxViewModel, onOpenSettings = { showSettingsSheet = true }) // sheet handles info
                    }
                }
            }

            if (showSettingsSheet) SettingsSheet(vm = chatboxViewModel, onDismiss = { showSettingsSheet = false })
            if (showInfoSheet) InfoSheet(onDismiss = { showInfoSheet = false })
        }
    }
}

/* =========================
   LEFT NAV DRAWER
   ========================= */

@Composable
private fun DrawerContent(
    current: ChatboxPage,
    onSelect: (ChatboxPage) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenInfo: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(min = 300.dp, max = 380.dp),
        color = colors.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Navigation", style = MaterialTheme.typography.titleLarge)

            DrawerSectionHeader("Main")
            DrawerItem(ChatboxPage.Home.title, Icons.Filled.Home, current == ChatboxPage.Home) { onSelect(ChatboxPage.Home) }
            DrawerItem(ChatboxPage.Automations.title, Icons.Filled.Sync, current == ChatboxPage.Automations) { onSelect(ChatboxPage.Automations) }
            DrawerItem(ChatboxPage.Music.title, Icons.Filled.MusicNote, current == ChatboxPage.Music) { onSelect(ChatboxPage.Music) }

            DrawerSectionHeader("Tools")
            DrawerItem(ChatboxPage.Debug.title, Icons.Filled.BugReport, current == ChatboxPage.Debug) { onSelect(ChatboxPage.Debug) }

            DrawerSectionHeader("Setup")
            DrawerItem("Settings & Permissions", Icons.Filled.Settings, false) { onOpenSettings() }
            DrawerItem("Info", Icons.Filled.Info, false) { onOpenInfo() }

            Spacer(Modifier.weight(1f))

            Divider()
            Text(
                text = "VRC-A / Chatbox",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DrawerSectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun DrawerItem(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    NavigationDrawerItem(
        label = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = null) },
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large),
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            unselectedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            selectedTextColor = MaterialTheme.colorScheme.onSecondaryContainer,
            unselectedTextColor = MaterialTheme.colorScheme.onSurface,
            selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}

/* =========================
   COMMON UI
   ========================= */

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
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = colors.surfaceVariant,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(text = title, style = MaterialTheme.typography.titleMedium)
                    if (!subtitle.isNullOrBlank()) {
                        Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
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
   HOME
   ========================= */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomePage(
    vm: ChatboxViewModel,
    onOpenSettings: () -> Unit
) {
    val uiState by vm.messengerUiState.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var ipInput by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(uiState.ipAddress))
    }
    LaunchedEffect(uiState.ipAddress) {
        if (ipInput.text.isBlank()) ipInput = TextFieldValue(uiState.ipAddress)
    }

    var wizardExpanded by rememberSaveable { mutableStateOf(true) }

    val overlayGranted = remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    LaunchedEffect(Unit) { overlayGranted.value = Settings.canDrawOverlays(ctx) }

    val pm = remember(ctx) { ctx.getSystemService(Context.POWER_SERVICE) as PowerManager }
    val batteryOk = remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(ctx.packageName)) }
    LaunchedEffect(Unit) { batteryOk.value = pm.isIgnoringBatteryOptimizations(ctx.packageName) }

    val notifOk = vm.listenerConnected
    val ipOk = uiState.ipAddress.isNotBlank() && uiState.ipAddress != "127.0.0.1"

    var connectionY by remember { mutableStateOf(0) }
    var manualSendY by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionCard(
            title = "VRChat Preview",
            subtitle = "Exactly what will appear in your chatbox.",
            actions = {
                OutlinedButton(
                    onClick = { onOpenSettings() },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Setup")
                }

                Button(
                    onClick = { vm.killStopAndClear() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text("KILL", color = MaterialTheme.colorScheme.onError)
                }
            }
        ) {
            val previewTextRaw = vm.debugLastCombinedOsc.ifBlank { "(nothing active)" }
            val previewText = remember(previewTextRaw) { vrChatSafePreview(previewTextRaw) }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 0.dp,
                tonalElevation = 0.dp
            ) {
                Box(Modifier.fillMaxSize()) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 14.dp)
                            .widthIn(max = 420.dp)
                            .fillMaxWidth(0.92f),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shadowElevation = 0.dp,
                        tonalElevation = 0.dp
                    ) {
                        Box(
                            Modifier
                                .heightIn(min = 96.dp)
                                .padding(14.dp)
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
                                    maxLines = 10,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Canvas(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 10.dp)
                            .height(210.dp)
                            .width(180.dp)
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
            }

            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 0.dp,
                tonalElevation = 0.dp
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Quick Toggles", style = MaterialTheme.typography.titleSmall)
                    ToggleRow("AFK", vm.afkEnabled) { vm.setAfkEnabledFlag(it) }
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
            AnimatedVisibility(
                visible = wizardExpanded,
                enter = fadeIn() + slideInHorizontally(initialOffsetX = { it / 8 }),
                exit = fadeOut() + slideOutHorizontally(targetOffsetX = { it / 8 })
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TutorialStep(
                        number = 0,
                        icon = Icons.Filled.Bolt,
                        title = "Enable OSC in VRChat",
                        subtitle = "VRChat → Settings → OSC → Enable OSC.",
                        badge = null,
                        primary = "How"
                    ) {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://docs.vrchat.com/docs/osc-overview")))
                    }

                    TutorialStep(
                        number = 1,
                        icon = Icons.Filled.MusicNote,
                        title = "Enable Notification Access",
                        subtitle = "Required for Now Playing detection.",
                        badge = if (notifOk) "ON" else "OFF",
                        primary = "Open"
                    ) { ctx.startActivity(vm.notificationAccessIntent()) }

                    TutorialStep(
                        number = 2,
                        icon = Icons.Filled.Bolt,
                        title = "Allow Overlay permission",
                        subtitle = "Only needed if you use overlay.",
                        badge = if (overlayGranted.value) "ON" else "OFF",
                        primary = "Open"
                    ) {
                        ctx.startActivity(vm.overlayPermissionIntent())
                        overlayGranted.value = Settings.canDrawOverlays(ctx)
                    }

                    TutorialStep(
                        number = 3,
                        icon = Icons.Filled.Power,
                        title = "Disable Battery Optimization",
                        subtitle = "Stops Android pausing the app while screen is off.",
                        badge = if (batteryOk.value) "ON" else "OFF",
                        primary = "Request"
                    ) {
                        ctx.startActivity(vm.batteryOptimizationIntent())
                        batteryOk.value = pm.isIgnoringBatteryOptimizations(ctx.packageName)
                    }

                    TutorialStep(
                        number = 4,
                        icon = Icons.Filled.Wifi,
                        title = "Set Headset IP",
                        subtitle = "Go to Connection and press Apply.",
                        badge = if (ipOk) "SET" else "NOT SET",
                        primary = "Go"
                    ) { scope.launch { scrollState.animateScrollTo(connectionY) } }

                    TutorialStep(
                        number = 5,
                        icon = Icons.Filled.ChevronRight,
                        title = "Send a test message",
                        subtitle = "Go to Manual Send to test output.",
                        badge = null,
                        primary = "Go"
                    ) { scope.launch { scrollState.animateScrollTo(manualSendY) } }
                }
            }
        }

        SectionCard(
            title = "Connection",
            subtitle = "Headset IP (Quest / PC)."
        ) {
            Column(
                modifier = Modifier.onGloballyPositioned { coords ->
                    connectionY = coords.positionInParent().y.roundToInt().coerceAtLeast(0)
                },
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = ipInput,
                    onValueChange = { ipInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Headset IP address") },
                    placeholder = { Text("Example: 192.168.1.23") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            val ip = ipInput.text.trim()
                            runCatching { vm.ipAddressApply(ip) }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Apply") }

                    OutlinedButton(
                        onClick = { ipInput = TextFieldValue(uiState.ipAddress) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Reset") }
                }

                Text(
                    text = "Current target: ${uiState.ipAddress}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SectionCard(
            title = "Manual Send",
            subtitle = "One-off message (doesn’t affect AFK/Cycle/Now Playing)."
        ) {
            Column(
                modifier = Modifier.onGloballyPositioned { coords ->
                    manualSendY = coords.positionInParent().y.roundToInt().coerceAtLeast(0)
                },
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = vm.messageText.value,
                    onValueChange = { vm.onMessageTextChange(it) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    label = { Text("Message") }
                )
                Button(onClick = { vm.sendMessage() }, modifier = Modifier.fillMaxWidth()) { Text("Send") }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun TutorialStep(
    number: Int,
    icon: ImageVector,
    title: String,
    subtitle: String,
    badge: String?,
    primary: String,
    onPrimary: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onPrimary() }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null) }
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "$number. $title",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!badge.isNullOrBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            tonalElevation = 0.dp,
                            shadowElevation = 0.dp
                        ) {
                            Text(
                                text = badge,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
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

/* =========================
   AUTOMATIONS
   ========================= */

@Composable
private fun AutomationsPage(vm: ChatboxViewModel) {
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(ChatboxAutomationsTab.AFK) }

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
        return parts.joinToString("  •  ").let { if (it.length > 80) it.take(79) + "…" else it }
    }

    fun cyclePresetsPreview(): String {
        val parts = (1..5).map { slot ->
            val p = vm.getCyclePresetPreview(slot).ifBlank { "empty" }
            "${slot}:${p}"
        }
        return parts.joinToString("  •  ").let { if (it.length > 80) it.take(79) + "…" else it }
    }

    PageContainer {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = 0.dp,
            tonalElevation = 0.dp
        ) {
            Column(Modifier.padding(10.dp)) {
                TabRow(selectedTabIndex = tab.ordinal) {
                    ChatboxAutomationsTab.entries.forEachIndexed { idx, t ->
                        Tab(selected = (tab.ordinal == idx), onClick = { tab = t }, text = { Text(t.title) })
                    }
                }
            }
        }

        when (tab) {
            ChatboxAutomationsTab.AFK -> {
                SectionCard(title = "AFK", subtitle = "AFK always appears above Cycle + Music.") {
                    ToggleRow("AFK enabled", vm.afkEnabled) { vm.setAfkEnabledFlag(it) }

                    OutlinedTextField(
                        value = vm.afkMessage,
                        onValueChange = { vm.updateAfkText(it) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("AFK text") }
                    )

                    Surface(
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 0.dp,
                        tonalElevation = 0.dp
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth().clickable { vm.updateAfkPresetsCollapsed(!vm.afkPresetsCollapsed) },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("AFK Presets (3)", style = MaterialTheme.typography.titleSmall)
                                    if (vm.afkPresetsCollapsed) {
                                        Text(afkPresetsPreview(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Icon(if (vm.afkPresetsCollapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess, contentDescription = null)
                            }

                            AnimatedVisibility(visible = !vm.afkPresetsCollapsed, enter = fadeIn(), exit = fadeOut()) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    (1..3).forEach { slot ->
                                        Surface(
                                            shape = MaterialTheme.shapes.extraLarge,
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            shadowElevation = 0.dp,
                                            tonalElevation = 0.dp
                                        ) {
                                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                val preview = vm.getAfkPresetPreview(slot).ifBlank { "(empty)" }
                                                Text("Preset $slot — $preview")

                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    OutlinedButton(onClick = { scope.launch { vm.loadAfkPreset(slot) } }, modifier = Modifier.weight(1f)) { Text("Load") }
                                                    Button(onClick = { scope.launch { vm.saveAfkPreset(slot, vm.afkMessage) } }, modifier = Modifier.weight(1f)) { Text("Save") }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { vm.startAfkSender() }, modifier = Modifier.weight(1f), enabled = vm.afkEnabled) { Text("Start") }
                        OutlinedButton(onClick = { vm.stopAfkSender(clearFromChatbox = true) }, modifier = Modifier.weight(1f)) { Text("Stop") }
                    }

                    OutlinedButton(onClick = { vm.sendAfkNow() }, modifier = Modifier.fillMaxWidth(), enabled = vm.afkEnabled) { Text("Send once") }
                }
            }

            ChatboxAutomationsTab.Cycle -> {
                SectionCard(title = "Cycle", subtitle = "Up to 10 lines. Stop clears instantly.") {
                    ToggleRow("Cycle enabled", vm.cycleEnabled) { vm.setCycleEnabledFlag(it) }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (vm.cycleLines.isEmpty()) Text("No lines yet. Tap Add Line.", style = MaterialTheme.typography.bodySmall)

                        vm.cycleLines.forEachIndexed { idx, _ ->
                            val fieldValue = cycleLineFields[idx] ?: TextFieldValue(vm.cycleLines.getOrNull(idx).orEmpty())
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
                                IconButton(onClick = { vm.removeCycleLine(idx) }) { Icon(Icons.Filled.Delete, contentDescription = "Remove line") }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.addCycleLine() }, enabled = vm.cycleLines.size < 10, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.Add, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Add line (${vm.cycleLines.size}/10)")
                            }
                            OutlinedButton(onClick = { vm.clearCycleLines() }, enabled = vm.cycleLines.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Clear") }
                        }
                    }

                    Text("Cycle speed: fixed at 10 seconds", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface, shadowElevation = 0.dp, tonalElevation = 0.dp) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                Modifier.fillMaxWidth().clickable { vm.updateCyclePresetsCollapsed(!vm.cyclePresetsCollapsed) },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Cycle Presets (5)", style = MaterialTheme.typography.titleSmall)
                                    if (vm.cyclePresetsCollapsed) {
                                        Text(cyclePresetsPreview(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Icon(if (vm.cyclePresetsCollapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess, contentDescription = null)
                            }

                            AnimatedVisibility(visible = !vm.cyclePresetsCollapsed, enter = fadeIn(), exit = fadeOut()) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    (1..5).forEach { slot ->
                                        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceVariant, shadowElevation = 0.dp, tonalElevation = 0.dp) {
                                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                val preview = vm.getCyclePresetPreview(slot).ifBlank { "(empty)" }
                                                Text("Preset $slot — $preview")

                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    OutlinedButton(onClick = { scope.launch { vm.loadCyclePreset(slot) } }, modifier = Modifier.weight(1f)) { Text("Load") }
                                                    Button(onClick = { scope.launch { vm.saveCyclePreset(slot, vm.cycleLines.toList()) } }, modifier = Modifier.weight(1f)) { Text("Save") }
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

                        OutlinedButton(onClick = { vm.stopCycle(clearFromChatbox = true) }, modifier = Modifier.weight(1f)) { Text("Stop") }
                    }
                }
            }
        }
    }
}

/* =========================
   MUSIC
   ========================= */

@Composable
private fun MusicPresetPreviewText(previewTextProvider: (Float) -> String) {
    val infinite = rememberInfiniteTransition(label = "musicPreview")
    val t = infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 2200, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "musicPreviewT"
    )
    Text(text = previewTextProvider(t.value), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
}

@Composable
private fun NowPlayingPage(vm: ChatboxViewModel) {
    val ctx = LocalContext.current

    PageContainer {
        SectionCard(title = "Now Playing", subtitle = "Uses Notification Access. Stop clears instantly.") {
            ToggleRow("Enable Now Playing block", vm.spotifyEnabled) { vm.setSpotifyEnabledFlag(it) }
            ToggleRow("Demo mode (testing)", vm.spotifyDemoEnabled) { vm.setSpotifyDemoFlag(it) }

            OutlinedButton(onClick = { ctx.startActivity(vm.notificationAccessIntent()) }, modifier = Modifier.fillMaxWidth()) {
                Text("Open Notification Access settings")
            }

            Text("Music refresh speed: fixed at 2 seconds", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Progress bar preset:", style = MaterialTheme.typography.labelLarge)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..5).forEach { p ->
                    val selected = (vm.spotifyPreset == p)
                    val name = vm.getMusicPresetName(p)

                    Surface(
                        shape = MaterialTheme.shapes.extraLarge,
                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                        shadowElevation = 0.dp,
                        tonalElevation = 0.dp
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp).clickable { vm.updateSpotifyPreset(p) },
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
                Button(onClick = { vm.startNowPlayingSender() }, modifier = Modifier.weight(1f), enabled = vm.spotifyEnabled) { Text("Start") }
                OutlinedButton(onClick = { vm.stopNowPlayingSender(clearFromChatbox = true) }, modifier = Modifier.weight(1f)) { Text("Stop") }
            }

            OutlinedButton(onClick = { vm.sendNowPlayingOnce() }, modifier = Modifier.fillMaxWidth(), enabled = vm.spotifyEnabled) {
                Text("Send once now (test)")
            }
        }

        SectionCard(title = "Detected / Preview", subtitle = "If blank: enable access, restart app, then play music.") {
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
private fun DebugPage(vm: ChatboxViewModel) {
    PageContainer {
        SectionCard(title = "Listener", subtitle = "Confirms Notification Access + media detection.") {
            Text("Listener connected: ${vm.listenerConnected}")
            Text("Active package: ${vm.activePackage}")
            Text("Detected: ${vm.nowPlayingDetected}")
            Text("Playing: ${vm.nowPlayingIsPlaying}")
        }

        SectionCard(title = "OSC Output Preview", subtitle = "Raw lines + combined output.") {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("AFK:", style = MaterialTheme.typography.labelLarge)
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

/* =========================
   SETTINGS SHEET
   ========================= */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(vm: ChatboxViewModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Settings, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text("Settings & Setup", style = MaterialTheme.typography.titleMedium)
            }

            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceVariant, shadowElevation = 0.dp, tonalElevation = 0.dp) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Permissions", style = MaterialTheme.typography.titleSmall)

                    SettingsRow(Icons.Filled.MusicNote, "Notification Access", "Required for Now Playing detection.", "Open") {
                        ctx.startActivity(vm.notificationAccessIntent())
                    }
                    SettingsRow(Icons.Filled.Bolt, "Overlay Permission", "Only needed if you use overlay.", "Open") {
                        ctx.startActivity(vm.overlayPermissionIntent())
                    }
                    SettingsRow(Icons.Filled.Power, "Battery Optimization", "Stops Android pausing when screen is off.", "Request") {
                        ctx.startActivity(vm.batteryOptimizationIntent())
                    }
                }
            }

            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Info, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Close")
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    primary: String,
    onPrimary: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable { onPrimary() }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(modifier = Modifier.size(38.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null) }
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        TextButton(onClick = onPrimary) {
            Text(primary)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Filled.ChevronRight, contentDescription = null)
        }
    }
}

/* =========================
   INFO SHEET
   ========================= */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfoSheet(onDismiss: () -> Unit) {
    var infoTab by rememberSaveable { mutableStateOf(ChatboxInfoTab.Overview) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Info", style = MaterialTheme.typography.titleMedium)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChatboxInfoTab.entries.forEach { t ->
                    val selected = (t == infoTab)
                    val colors = if (selected) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors()
                    Button(
                        onClick = { infoTab = t },
                        colors = colors,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }

            val overview = remember {
                """
Chatbox (VRChat Assistant)
Made by: Ashoska Mitsu Sisko

Home shows a live VR-style preview so you always know what will appear in VRChat.
Use KILL to instantly stop everything and clear the chatbox.
                """.trimIndent()
            }
            val tutorial = remember {
                """
TUTORIAL

1) VRChat → Settings → OSC → Enable OSC
2) Phone + headset on same Wi-Fi
3) Find headset IP (Quest: Wi-Fi → network → Advanced)
4) Home → Connection → Apply
5) Manual Send to test

Now Playing:
Settings → Notification Access → enable Chatbox
Restart Chatbox, play music, then Music → Start.
                """.trimIndent()
            }
            val help = remember {
                """
TROUBLESHOOT

Nothing appears in VRChat:
- Check IP
- OSC enabled
- Same Wi-Fi

Now Playing blank:
- Enable Notification Access
- Restart app
- Start playing music (notification must exist)

Stops sending when screen is off:
- Disable Battery Optimization
- Keep notifications allowed
                """.trimIndent()
            }
            val fullDoc = remember {
                """
Chatbox

Home is the control center.
If anything gets stuck sending: press KILL (stops + clears VRChat).
                """.trimIndent()
            }

            val text = when (infoTab) {
                ChatboxInfoTab.Overview -> overview
                ChatboxInfoTab.Tutorial -> tutorial
                ChatboxInfoTab.Troubleshoot -> help
                ChatboxInfoTab.FullDoc -> fullDoc
            }

            SelectionContainer {
                Text(text = text, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            }

            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Info, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Close")
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/* =========================
   UTIL
   ========================= */

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
