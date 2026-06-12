package com.vrca.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vrca.BuildConfig
import com.vrca.ui.settings.ToggleRow
import com.vrca.ui.viewmodel.VrcaViewModel
import kotlinx.coroutines.launch

@Composable
internal fun SettingsPage(
    vm: VrcaViewModel,
    lastFirebaseIssue: String?,
    moderationError: String?
) {
    val ctx = LocalContext.current
    var debugExpanded by rememberSaveable { mutableStateOf(false) }
    var showVrchatLogin by rememberSaveable { mutableStateOf(false) }

    // Inline VRChat re-login (Accounts → Sign in). Full-screen takeover; on
    // success VrchatAuthManager.loggedInSignal lifts the OSC gate and VrcaApp
    // re-runs the Phase-2 ban check. pendingBanId=null — the re-login ban check
    // runs through the normal Phase-2 path keyed on reloginTick. Cancel renders
    // INSIDE the login screen (the old top-bar Row placement caused layout
    // issues against the app bar).
    if (showVrchatLogin) {
        com.vrca.vrchat.VrchatLoginScreen(
            pendingBanId = null,
            onCancel = { showVrchatLogin = false }
        ) { _, _ ->
            showVrchatLogin = false
        }
        return
    }

    PageContainer {
        // -- Accounts --
        AccountsSection(vm, onSignInVrchat = { showVrchatLogin = true })

        // -- Chatbox display --
        SectionCard(title = "Chatbox display") {
            ToggleRow(
                label = "Invisible Chatbox Border",
                checked = vm.minimalChatboxBg,
                description = "Shrinks the chatbox background."
            ) { vm.setMinimalChatboxBgFlag(it) }
            ToggleRow(
                label = "Time format",
                checked = vm.time24h,
                description = "On = 24-hour clock (17:20). Off = 12-hour (5:20 PM)."
            ) { vm.setTime24hFlag(it) }
        }

        // -- App --
        SectionCard(title = "App") {
            Text(
                "Version ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            SettingsRow(
                icon = Icons.Filled.Power,
                title = "Battery Optimization",
                subtitle = "Stops Android pausing VRC-A when the screen is off. Strongly recommended.",
                primary = "Request"
            ) { ctx.startActivity(vm.batteryOptimizationIntent()) }
            SettingsRow(
                icon = Icons.Filled.Refresh,
                title = "Replay setup tutorial",
                subtitle = "Walk through OSC, IP, permissions and notifications again.",
                primary = "Start"
            ) { com.vrca.ui.onboarding.OnboardingState.replayRequested.value = true }
            StorageRow()
        }

        // -- Notifications (moved here from the VRChat tab — configuration,
        //    not daily use; docs/ui-revamp.md Settings) --
        SectionCard(title = "Notifications") {
            com.vrca.ui.settings.NotificationToggleSection(vm = vm)
        }

        // -- Permissions (every permission the app uses lives here) --
        SectionCard(title = "Permissions") {
            SettingsRow(
                icon = Icons.Filled.Notifications,
                title = "Notifications",
                subtitle = "Friend activity, invites and group alerts.",
                primary = "Open"
            ) {
                runCatching {
                    ctx.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    )
                }
            }

            SettingsRow(
                icon = Icons.Filled.MusicNote,
                title = "Notification Access",
                subtitle = "Required for Now Playing detection.",
                primary = "Open"
            ) { ctx.startActivity(vm.notificationAccessIntent()) }

            SettingsRow(
                icon = Icons.Filled.SystemUpdate,
                title = "Install updates",
                subtitle = "Lets VRC-A install its own update APKs when a new version is pushed.",
                primary = "Open"
            ) {
                runCatching {
                    ctx.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                            .setData(android.net.Uri.parse("package:${ctx.packageName}"))
                    )
                }
            }

            SettingsRow(
                icon = Icons.Filled.Bolt,
                title = "Overlay Permission",
                subtitle = "Only needed if you use overlay.",
                primary = "Open"
            ) { ctx.startActivity(vm.overlayPermissionIntent()) }
        }

        // -- About --
        SectionCard(title = "About") {
            Text(
                "VRC-A (made by Ashoska Mitsu Sisko)",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(2.dp))
            listOf(
                "Sends OSC chatbox text to your Quest/PC target",
                "Includes: Pinned, Cycle, Now Playing, Manual Send",
                "Use Stop to halt all senders and clear the VRChat chatbox"
            ).forEach { AboutBullet(it) }
        }

        // -- Help (collapsible FAQ) --
        SectionCard(title = "Help") {
            HelpFaqRow(
                question = "Nothing appears in VRChat",
                answer = "- VRChat -> Settings -> OSC -> Enable OSC\n" +
                    "- Phone + headset on the same Wi-Fi\n" +
                    "- Set the correct headset IP (Home -> Connection)\n" +
                    "- Press Start on Home\n" +
                    "- Try Manual Send"
            )
            HelpFaqRow(
                question = "Now Playing is blank",
                answer = "- Enable Notification Access\n" +
                    "- Reopen the app\n" +
                    "- Start music so a notification exists"
            )
            HelpFaqRow(
                question = "Stops sending with screen off",
                answer = "- Disable Battery Optimization for VRC-A (App section above)\n" +
                    "- On Samsung: add VRC-A to \"Never sleeping apps\""
            )
        }

        // -- Debug (collapsible) --
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(
                    Modifier.fillMaxWidth().clickable { debugExpanded = !debugExpanded },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Debug", style = MaterialTheme.typography.titleMedium)
                    Icon(
                        if (debugExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (debugExpanded) "Collapse" else "Expand"
                    )
                }
                AnimatedVisibility(visible = debugExpanded) {
                    Column(
                        Modifier.padding(top = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Firebase (last issue)", style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = lastFirebaseIssue ?: "(none captured)",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )

                        Text("Moderation listener (last error)", style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = moderationError?.ifBlank { "(none)" } ?: "(none)",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )

                        Text("Listener", style = MaterialTheme.typography.labelMedium)
                        Text("Connected: ${vm.listenerConnected}", style = MaterialTheme.typography.bodySmall)
                        Text("Active package: ${vm.activePackage}", style = MaterialTheme.typography.bodySmall)
                        Text("Detected: ${vm.nowPlayingDetected}", style = MaterialTheme.typography.bodySmall)
                        Text("Playing: ${vm.nowPlayingIsPlaying}", style = MaterialTheme.typography.bodySmall)
                        Text("Title: ${vm.lastNowPlayingTitle}", style = MaterialTheme.typography.bodySmall)
                        Text("Artist: ${vm.lastNowPlayingArtist}", style = MaterialTheme.typography.bodySmall)

                        Text("OSC Output Preview", style = MaterialTheme.typography.labelMedium)
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Pinned: ${vm.debugLastAfkOsc}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                Text("Cycle: ${vm.debugLastCycleOsc}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                Text("Music: ${vm.debugLastMusicOsc}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                Text("Combined: ${vm.debugLastCombinedOsc}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            }
                        }

                        Text("Last sent to VRChat (ms): ${vm.lastSentToVrchatAtMs}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** Real styled bullet row for the About card (replaces the literal "- "
 *  dashes flagged in docs/ui-revamp.md). */
@Composable
private fun AboutBullet(text: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            "•",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun HelpFaqRow(question: String, answer: String) {
    var expanded by rememberSaveable(question) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                question,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AnimatedVisibility(visible = expanded) {
            Text(
                answer,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 10.dp)
            )
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
        Modifier
            .fillMaxWidth()
            .clickable { onPrimary() }
            .padding(vertical = 6.dp),
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
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        TextButton(onClick = onPrimary) {
            Text(primary)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Filled.ChevronRight, contentDescription = null)
        }
    }
}

/* =========================================================================
   Accounts (docs/ui-revamp.md, Settings): VRChat + Discord rows with sign-out
   and inline re-login. Signing out of VRChat hard-blocks all OSC output (the
   VrcaViewModel auth gate) until a VRChat account is signed in again.
   ========================================================================= */

@Composable
private fun AccountsSection(vm: VrcaViewModel, onSignInVrchat: () -> Unit) {
    val ctx = LocalContext.current
    val repo = (ctx.applicationContext as com.vrca.app.VrcaApplication).userPreferencesRepository
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // VRChat state: vm.vrchatLoggedOut flips reactively on the auth signals;
    // also consult isLoggedIn for cold truth (covers process restarts).
    val vrcSignedIn = !vm.vrchatLoggedOut &&
        com.vrca.vrchat.VrchatAuthManager.isLoggedIn(ctx)
    val vrcName = com.vrca.vrchat.VrchatAuthManager.getStoredDisplayName(ctx) ?: ""

    val discordSeeded by repo.discordSessionSeeded.collectAsStateInitially(false)
    val discordEnabled by repo.discordRpcEnabled.collectAsStateInitially(false)
    val discordRiskAccepted by repo.discordRiskAccepted.collectAsStateInitially(false)
    val discordStatus by com.vrca.discord.DiscordRpcState.statusFlow.collectAsState()
    val discordFailureMsg by com.vrca.discord.DiscordRpcState.failureMessageFlow.collectAsState()

    var confirmVrcSignOut by rememberSaveable { mutableStateOf(false) }
    var confirmDiscordDisconnect by rememberSaveable { mutableStateOf(false) }
    var showDiscordLogin by rememberSaveable { mutableStateOf(false) }
    var showRiskConsent by rememberSaveable { mutableStateOf(false) }

    fun setDiscordRpcEnabled(enabled: Boolean) {
        scope.launch {
            repo.saveDiscordRpcEnabled(enabled)
            val svcIntent = android.content.Intent(ctx, com.vrca.discord.DiscordRpcService::class.java)
            if (enabled) {
                svcIntent.action = com.vrca.discord.DiscordRpcService.ACTION_START
                ctx.startForegroundService(svcIntent)
            } else {
                svcIntent.action = com.vrca.discord.DiscordRpcService.ACTION_STOP
                ctx.startService(svcIntent)
            }
        }
    }

    SectionCard(title = "Accounts") {
        // VRChat row
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("VRChat", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (vrcSignedIn) vrcName.ifBlank { "Signed in" } else "Signed out — chatbox sending is blocked",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (vrcSignedIn) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error
                )
            }
            if (vrcSignedIn) {
                TextButton(onClick = { confirmVrcSignOut = true }) { Text("Sign out") }
            } else {
                TextButton(onClick = onSignInVrchat) { Text("Sign in") }
            }
        }

        // Discord row — full Rich Presence management lives HERE now (moved
        // from the VRChat tab per docs/ui-revamp.md; that tab keeps only a
        // small RPC status chip on the identity header).
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Discord", style = MaterialTheme.typography.titleSmall)
                Text(
                    when {
                        !discordSeeded -> "Not connected"
                        discordStatus == com.vrca.discord.DiscordRpcStatus.SESSION_EXPIRED -> "Session expired — sign in again"
                        discordStatus == com.vrca.discord.DiscordRpcStatus.FAILED -> "Connection failed"
                        discordEnabled && discordStatus == com.vrca.discord.DiscordRpcStatus.CONNECTED -> "Connected — Rich Presence on"
                        discordEnabled -> "Connecting Rich Presence..."
                        else -> "Connected — Rich Presence off"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (discordStatus == com.vrca.discord.DiscordRpcStatus.SESSION_EXPIRED ||
                        discordStatus == com.vrca.discord.DiscordRpcStatus.FAILED)
                        MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (discordFailureMsg != null) {
                    Text(
                        discordFailureMsg!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            if (discordSeeded) {
                TextButton(onClick = { confirmDiscordDisconnect = true }) { Text("Disconnect") }
            } else {
                TextButton(onClick = {
                    if (!discordRiskAccepted) showRiskConsent = true else showDiscordLogin = true
                }) { Text("Sign in") }
            }
        }
        if (discordSeeded) {
            ToggleRow(
                label = "Discord Rich Presence",
                checked = discordEnabled,
                description = "Show your VRChat activity on your Discord profile."
            ) { enabled ->
                if (enabled && !discordRiskAccepted) showRiskConsent = true
                else setDiscordRpcEnabled(enabled)
            }
            if (discordStatus == com.vrca.discord.DiscordRpcStatus.SESSION_EXPIRED) {
                TextButton(onClick = { showDiscordLogin = true }) { Text("Sign in to Discord again") }
            }
        }
    }

    // Discord risk consent (moved with the card; consent persists once).
    if (showRiskConsent) {
        var riskChecked by remember { mutableStateOf(false) }
        var confirmEnabled by remember { mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(riskChecked) {
            if (riskChecked) {
                confirmEnabled = false
                kotlinx.coroutines.delay(4000)
                confirmEnabled = true
            } else {
                confirmEnabled = false
            }
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showRiskConsent = false },
            title = { Text("Discord Rich Presence") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "This feature runs a hidden Discord web session on your device to show " +
                        "VRChat activity on your Discord profile.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("Please be aware:", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "• A background Discord web session will be active while enabled\n" +
                        "• This uses additional battery and data\n" +
                        "• Your Discord session cookies are stored on-device only\n" +
                        "• While unlikely, Discord could flag unusual client behavior\n" +
                        "• Disconnecting clears your Discord session — Discord may also " +
                        "invalidate your sessions on other devices when it detects an " +
                        "unauthorized client, logging you out everywhere\n" +
                        "• You can disable this at any time from settings",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { riskChecked = !riskChecked }
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = riskChecked,
                            onCheckedChange = { riskChecked = it }
                        )
                        Text("I understand and accept these risks",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.Button(
                    onClick = {
                        scope.launch {
                            repo.saveDiscordRiskAccepted(true)
                            showRiskConsent = false
                            if (discordSeeded) setDiscordRpcEnabled(true)
                            else showDiscordLogin = true
                        }
                    },
                    enabled = confirmEnabled
                ) { Text(if (confirmEnabled) "Continue" else "Please wait...") }
            },
            dismissButton = {
                TextButton(onClick = { showRiskConsent = false }) { Text("Cancel") }
            }
        )
    }

    // Discord login WebView (moved with the card).
    if (showDiscordLogin) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDiscordLogin = false },
            confirmButton = {},
            text = {
                Box(Modifier.fillMaxWidth().height(500.dp)) {
                    com.vrca.discord.DiscordLoginWebView(
                        onLoginComplete = {
                            scope.launch {
                                repo.saveDiscordSessionSeeded(true)
                                showDiscordLogin = false
                            }
                        },
                        onDismiss = { showDiscordLogin = false }
                    )
                }
            }
        )
    }

    if (confirmVrcSignOut) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmVrcSignOut = false },
            title = { Text("Sign out of VRChat?") },
            text = { Text("Chatbox sending stops immediately and stays blocked until you sign in again. Your toggles and messages are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmVrcSignOut = false
                    com.vrca.vrchat.VrchatAuthManager.logout(ctx)
                }) { Text("Sign out") }
            },
            dismissButton = {
                TextButton(onClick = { confirmVrcSignOut = false }) { Text("Cancel") }
            }
        )
    }

    if (confirmDiscordDisconnect) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDiscordDisconnect = false },
            title = { Text("Disconnect Discord?") },
            text = { Text("Rich Presence stops and the on-device Discord session is cleared. Disconnecting may invalidate Discord sessions on other devices.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscordDisconnect = false
                    scope.launch {
                        repo.saveDiscordRpcEnabled(false)
                        repo.saveDiscordSessionSeeded(false)
                        android.webkit.CookieManager.getInstance().removeAllCookies(null)
                        val svcIntent = android.content.Intent(ctx, com.vrca.discord.DiscordRpcService::class.java)
                        svcIntent.action = com.vrca.discord.DiscordRpcService.ACTION_STOP
                        ctx.startService(svcIntent)
                    }
                }) { Text("Disconnect") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscordDisconnect = false }) { Text("Cancel") }
            }
        )
    }
}

/** Small helper: collectAsState with an initial value (Flow<T> ext). */
@Composable
private fun <T> kotlinx.coroutines.flow.Flow<T>.collectAsStateInitially(initial: T) =
    this.collectAsState(initial = initial)

/**
 * Storage row: live measured cache size + a button to the system App Info
 * page — Android doesn't let an app fully wipe its own cache, so the system
 * page's Clear Cache is the honest route. Clearing cache never touches
 * logins or settings.
 */
@Composable
private fun StorageRow() {
    val ctx = LocalContext.current
    var cacheBytes by androidx.compose.runtime.remember { mutableStateOf(-1L) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        cacheBytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            fun dirSize(dir: java.io.File?): Long {
                if (dir == null || !dir.exists()) return 0L
                return dir.walkBottomUp().fold(0L) { acc, f -> acc + (if (f.isFile) f.length() else 0L) }
            }
            dirSize(ctx.cacheDir) + dirSize(ctx.externalCacheDir) + dirSize(ctx.codeCacheDir)
        }
    }

    val sizeLabel = when {
        cacheBytes < 0 -> "measuring..."
        cacheBytes < 1024 * 1024 -> "${cacheBytes / 1024} KB"
        else -> String.format("%.1f MB", cacheBytes / (1024.0 * 1024.0))
    }

    SettingsRow(
        icon = Icons.Filled.Bolt,
        title = "Storage",
        subtitle = "Cache: $sizeLabel. Opens the system page where Clear Cache lives — it never touches logins or settings.",
        primary = "Open"
    ) {
        runCatching {
            ctx.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.parse("package:${ctx.packageName}"))
            )
        }
    }
}
