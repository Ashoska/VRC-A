package com.vrca.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NewReleases
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
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

    // Live permission statuses — re-checked whenever the user returns from a
    // system settings page (same ON_RESUME pattern as the onboarding step).
    var permRefreshTick by remember { mutableStateOf(0) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) permRefreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val notifGranted = remember(permRefreshTick) {
        android.os.Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    // Exact-package match (NotificationManagerCompat) — a substring check on
    // the flat settings string false-positives against the admin build's
    // package (com.scrapw.chatbox.admin contains the public package name).
    val listenerGranted = remember(permRefreshTick) {
        androidx.core.app.NotificationManagerCompat
            .getEnabledListenerPackages(ctx)
            .contains(ctx.packageName)
    }
    val batteryExempt = remember(permRefreshTick) {
        (ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager)
            .isIgnoringBatteryOptimizations(ctx.packageName)
    }
    val installAllowed = remember(permRefreshTick) {
        android.os.Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()
    }
    val overlayAllowed = remember(permRefreshTick) {
        android.provider.Settings.canDrawOverlays(ctx)
    }
    // Headset only: All-files access lets the log reader find VRChat's output log.
    val filesAccessGranted = remember(permRefreshTick) {
        com.vrca.vrchat.InstanceRosterManager.hasStoragePermission()
    }

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
                description = "Change your time format to 12 or 24 hour mode."
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
                icon = Icons.Filled.Refresh,
                title = "Replay setup tutorial",
                subtitle = "Walk through OSC, IP, permissions and notifications again.",
                primary = "Start"
            ) { com.vrca.ui.onboarding.OnboardingState.replayRequested.value = true }
            var showWhatsNew by remember { mutableStateOf(false) }
            SettingsRow(
                icon = Icons.Filled.NewReleases,
                title = "What's New",
                subtitle = "Patch notes for your current version.",
                primary = "View"
            ) { showWhatsNew = true }
            StorageRow()
            if (showWhatsNew) WhatsNewDialog(onDismiss = { showWhatsNew = false })
        }

        // -- Notifications (moved here from the VRChat tab — configuration,
        //    not daily use; docs/ui-revamp.md Settings) --
        SectionCard(title = "Notifications") {
            com.vrca.ui.settings.NotificationToggleSection(vm = vm)
        }

        // -- Permissions (every permission the app uses lives here, each with
        //    its live granted status) --
        SectionCard(title = "Permissions") {
            // POST_NOTIFICATIONS — not prompted on the headset (Quest doesn't surface
            // the app notifications the way a phone does).
            if (!BuildConfig.IS_HEADSET_BUILD) {
                SettingsRow(
                    icon = Icons.Filled.Notifications,
                    title = "Notifications",
                    subtitle = "Friend activity, invites and group alerts.",
                    primary = "Open",
                    granted = notifGranted
                ) {
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                        )
                    }
                }
            }

            // (Notification Access / Now Playing removed — media detection is moving
            // to Spotify auth, so it's no longer required/prompted on any build.)

            SettingsRow(
                icon = Icons.Filled.Power,
                title = "Battery Optimization",
                subtitle = "Stops Android pausing VRC-A when the screen is off. Strongly recommended.",
                primary = "Open",
                granted = batteryExempt
            ) { ctx.startActivity(vm.batteryOptimizationIntent()) }

            // Notification access — required for Now Playing (Spotify/YouTube/YT Music)
            // on EVERY build.
            SettingsRow(
                icon = Icons.Filled.MusicNote,
                title = "Notification access",
                subtitle = if (BuildConfig.IS_HEADSET_BUILD)
                    "Lets VRC-A read Now Playing. Opens Android's settings — tap VRC-A in the list, then turn on Allow notification access."
                else
                    "Lets VRC-A read Now Playing. Turn it on in the list that opens.",
                primary = "Open",
                granted = listenerGranted
            ) { vm.launchNotificationAccess(ctx) }

            // Quest-only: the "Allow restricted settings" step. Android 13+ GREYS
            // OUT the notification-access toggle for SIDELOADED apps until the user
            // opens App Info -> 3-dot menu -> "Allow restricted settings". Shown
            // only while access is still missing.
            if (BuildConfig.IS_HEADSET_BUILD && !listenerGranted) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Toggle greyed out? Allow restricted settings", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "On a sideloaded app, Android hides the notification toggle until you allow restricted settings. Do this once, then turn on notification access above.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "1. Tap \"Open App info\" below.\n" +
                            "2. Tap the 3-dot menu (top right).\n" +
                            "3. Tap \"Allow restricted settings\" (enter your headset PIN if asked).\n" +
                            "4. Go back and turn on Allow notification access.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { vm.launchAppInfo(ctx) }) { Text("Open App info") }
                    }
                }
            }

            // Install-updates (unknown sources) — not on the headset: it's
            // distributed through the Meta store, so no in-app APK install.
            if (!BuildConfig.IS_HEADSET_BUILD) {
                SettingsRow(
                    icon = Icons.Filled.SystemUpdate,
                    title = "Install updates",
                    subtitle = "Lets VRC-A install its own update APKs when a new version is pushed.",
                    primary = "Open",
                    granted = installAllowed
                ) {
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                                .setData(android.net.Uri.parse("package:${ctx.packageName}"))
                        )
                    }
                }
            }

            SettingsRow(
                icon = Icons.Filled.Bolt,
                title = "Overlay Permission",
                subtitle = "Only needed if you use the floating overlay.",
                primary = "Open",
                granted = overlayAllowed
            ) { ctx.startActivity(vm.overlayPermissionIntent()) }

            // Headset only: read VRChat's log to build the instance roster. The
            // rest of the roster setup (pick log folder, VRChat Logging = FULL)
            // lives on the Home "In your instance" panel.
            if (BuildConfig.IS_HEADSET_BUILD) {
                SettingsRow(
                    icon = Icons.Filled.Folder,
                    title = "All files access",
                    subtitle = "Lets VRC-A read VRChat's log to show who's in your instance.",
                    primary = "Open",
                    granted = filesAccessGranted
                ) {
                    runCatching {
                        ctx.startActivity(
                            com.vrca.vrchat.InstanceRosterManager.allFilesAccessIntent(ctx)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            }
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
                answer = buildString {
                    append("- Disable Battery Optimization for VRC-A (App section above)\n")
                    val g = com.vrca.ui.common.OemPowerGuidance.forThisDevice()
                    if (g != null) {
                        append("\nYour phone (")
                        append(g.brand)
                        append(") restricts background apps. ")
                        append(g.steps)
                    } else {
                        append("- Keep VRC-A out of any \"restricted\" or \"sleeping\" apps list")
                    }
                }
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
                        Text("Sync (last issue)", style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = lastFirebaseIssue?.let { com.vrca.app.sanitizeBackendRefs(it) }
                                ?: "(none captured)",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )

                        Text("Moderation listener (last error)", style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = moderationError?.let { com.vrca.app.sanitizeBackendRefs(it) }?.ifBlank { "(none)" } ?: "(none)",
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

                        if (BuildConfig.IS_HEADSET_BUILD) {
                            Text("OSC-in (VRChat → VRC-A :9001)", style = MaterialTheme.typography.labelMedium)
                            var oscInDiag by remember { mutableStateOf(com.vrca.osc.VrcaOscState.diagString()) }
                            androidx.compose.runtime.LaunchedEffect(Unit) {
                                while (true) {
                                    oscInDiag = com.vrca.osc.VrcaOscState.diagString()
                                    kotlinx.coroutines.delay(1000)
                                }
                            }
                            SelectionContainer {
                                Text(
                                    oscInDiag,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            // OSCQuery probe: VRChat won't PUSH params on Quest, but
                            // its wiki says Android VRChat SERVES param values over
                            // OSCQuery HTTP. This scans for VRChat's _oscjson._tcp
                            // service + reads MuteSelf to prove we can PULL params.
                            Text("OSCQuery probe (pull params)", style = MaterialTheme.typography.labelMedium)
                            var oscQ by remember { mutableStateOf(com.vrca.osc.VrcaOscState.oscQueryDiag) }
                            androidx.compose.runtime.LaunchedEffect(Unit) {
                                while (true) {
                                    oscQ = com.vrca.osc.VrcaOscState.oscQueryDiag
                                    kotlinx.coroutines.delay(1000)
                                }
                            }
                            TextButton(onClick = { com.vrca.osc.VrcaOscQuery.start(ctx) }) {
                                Text("Start / rescan OSCQuery")
                            }
                            SelectionContainer {
                                Text(
                                    oscQ,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            // Instance roster: shows the log file + parsed
                            // location/world/roster + the last log lines with what the
                            // parser made of each — so a "not in a world" with a
                            // readable log reveals which lines didn't match.
                            Text("Instance roster (log parse)", style = MaterialTheme.typography.labelMedium)
                            var rosterDiag by remember { mutableStateOf("") }
                            androidx.compose.runtime.LaunchedEffect(Unit) {
                                while (true) {
                                    rosterDiag = runCatching {
                                        com.vrca.vrchat.InstanceRosterManager.diagString(ctx)
                                    }.getOrDefault("(err)")
                                    kotlinx.coroutines.delay(1500)
                                }
                            }
                            SelectionContainer {
                                Text(
                                    rosterDiag,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

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

                        androidx.compose.material3.Divider(Modifier.padding(vertical = 4.dp))
                        ChatboxWidthCalibrationTool(vm)
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

/** Settings → What's New: the installed version's patch notes, offline, via the
 *  shared rich-content renderer. Media pulls on open, culls on close (upd scope). */
@Composable
private fun WhatsNewDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val whatsNew = remember {
        com.vrca.richcontent.WhatsNewStore.forVersion(ctx, BuildConfig.VERSION_CODE.toLong())
    }
    DisposableEffect(whatsNew) {
        whatsNew?.doc?.mediaUrls()?.forEach {
            com.vrca.richcontent.RichMediaStore.ensureCachedAsync(
                ctx, it, com.vrca.richcontent.RichMediaStore.Scope.UPDATE
            )
        }
        onDispose { com.vrca.richcontent.RichMediaStore.clearUpdateMedia(ctx) }
    }
    com.vrca.ui.common.VrcaCardDialog(onDismiss = onDismiss) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "What's New",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                ) {
                    Text(
                        BuildConfig.VERSION_NAME,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            val wn = whatsNew
            if (wn != null) {
                val screenH = LocalConfiguration.current.screenHeightDp
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = (screenH * 0.55f).dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    com.vrca.richcontent.RichDocRenderer(
                        wn.doc,
                        mediaScope = com.vrca.richcontent.RichMediaStore.Scope.UPDATE
                    )
                }
            } else {
                Text(
                    "Patch notes for your version will appear here after your next update.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    primary: String,
    granted: Boolean? = null,
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
            if (granted != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(
                                color = if (granted) androidx.compose.ui.graphics.Color(0xFF2BCF5C)
                                        else MaterialTheme.colorScheme.error,
                                shape = androidx.compose.foundation.shape.CircleShape
                            )
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (granted) "Granted" else "Not granted",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (granted) androidx.compose.ui.graphics.Color(0xFF2BCF5C)
                                else MaterialTheme.colorScheme.error
                    )
                }
            }
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
        // VRChat row — status line mirrors the Discord row's format so the
        // two accounts read consistently ("Connected — Presence on/off").
        val pipelineConnected by com.vrca.vrchat.VrchatPipelineState.isConnectedFlow.collectAsState()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("VRChat", style = MaterialTheme.typography.titleSmall)
                Text(
                    when {
                        !vrcSignedIn -> "Signed out — chatbox sending is blocked"
                        pipelineConnected -> "Connected — Presence on"
                        else -> "Connected — Presence connecting..."
                    },
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
                TextButton(onClick = { confirmDiscordDisconnect = true }) { Text("Sign out") }
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

        // Spotify row — official-API media detection (replaces the notification
        // listener). Connect once via OAuth; we then poll currently-playing.
        val spotifyConnected by com.vrca.spotify.SpotifyAuthManager.connectedFlow.collectAsState()
        androidx.compose.runtime.LaunchedEffect(Unit) {
            com.vrca.spotify.SpotifyAuthManager.refreshConnectedState(ctx)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Spotify", style = MaterialTheme.typography.titleSmall)
                Text(
                    when {
                        !com.vrca.spotify.SpotifyAuthManager.isConfigured() -> "Not set up yet"
                        spotifyConnected -> "Connected — Now Playing from Spotify"
                        else -> "Not connected"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (spotifyConnected) {
                TextButton(onClick = { com.vrca.spotify.SpotifyAuthManager.logout(ctx) }) { Text("Disconnect") }
            } else if (com.vrca.spotify.SpotifyAuthManager.isConfigured()) {
                TextButton(onClick = {
                    runCatching {
                        val url = com.vrca.spotify.SpotifyAuthManager.buildAuthUrl(ctx)
                        ctx.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }) { Text("Connect") }
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
        com.vrca.ui.common.VrcaCardDialog(onDismiss = { showRiskConsent = false }) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Discord Rich Presence",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "This feature runs a hidden Discord web session on your device to show " +
                    "VRChat activity on your Discord profile.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("Please be aware:", style = MaterialTheme.typography.labelLarge)
                Text(
                    "• A background Discord web session will be active while enabled\n" +
                    "• This uses additional battery and data\n" +
                    "• Your Discord session cookies are stored on-device only\n" +
                    "• While unlikely, Discord could flag unusual client behavior\n" +
                    "• Disconnecting clears your Discord session, and Discord may also " +
                    "invalidate your sessions on other devices when it detects an " +
                    "unauthorized client, logging you out everywhere\n" +
                    "• You can disable this at any time from settings",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { riskChecked = !riskChecked }
                            .padding(end = 12.dp)
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = riskChecked,
                            onCheckedChange = { riskChecked = it }
                        )
                        Text("I understand and accept these risks",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { showRiskConsent = false }) { Text("Cancel") }
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
                }
            }
        }
    }

    // Discord login WebView (moved with the card).
    if (showDiscordLogin) {
        com.vrca.ui.common.VrcaCardDialog(
            onDismiss = { showDiscordLogin = false },
            dismissOnOutsideTap = false
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Sign in to Discord",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { showDiscordLogin = false }) { Text("Cancel") }
                }
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
        }
    }

    if (confirmVrcSignOut) {
        com.vrca.ui.common.VrcaConfirmDialog(
            title = com.vrca.ui.common.VrcaDialogCopy.VRC_SIGN_OUT_TITLE,
            body = com.vrca.ui.common.VrcaDialogCopy.VRC_SIGN_OUT_BODY,
            confirmLabel = "Sign out",
            destructive = true,
            onConfirm = {
                confirmVrcSignOut = false
                com.vrca.vrchat.VrchatAuthManager.logout(ctx)
            },
            onDismiss = { confirmVrcSignOut = false }
        )
    }

    if (confirmDiscordDisconnect) {
        com.vrca.ui.common.VrcaConfirmDialog(
            title = com.vrca.ui.common.VrcaDialogCopy.DISCORD_SIGN_OUT_TITLE,
            body = com.vrca.ui.common.VrcaDialogCopy.DISCORD_SIGN_OUT_BODY,
            confirmLabel = "Sign out",
            destructive = true,
            onConfirm = {
                confirmDiscordDisconnect = false
                scope.launch {
                    repo.saveDiscordRpcEnabled(false)
                    repo.saveDiscordSessionSeeded(false)
                    android.webkit.CookieManager.getInstance().removeAllCookies(null)
                    val svcIntent = android.content.Intent(ctx, com.vrca.discord.DiscordRpcService::class.java)
                    svcIntent.action = com.vrca.discord.DiscordRpcService.ACTION_STOP
                    ctx.startService(svcIntent)
                }
            },
            onDismiss = { confirmDiscordDisconnect = false }
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

/** Dev-only EXACT chatbox line-width calibration harness (plan §8.4). Send a line
 *  of one repeated char, read the wrap point in-headset, record the max count per
 *  char, Save → TitleCleaner wraps by measured widths instead of estimates. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChatboxWidthCalibrationTool(vm: VrcaViewModel) {
    val ctx = LocalContext.current
    val measured = remember {
        androidx.compose.runtime.mutableStateMapOf<Char, String>().apply {
            com.vrca.nowplaying.ChatboxCalibration.measuredChars().forEach { (c, n) -> put(c, n.toString()) }
        }
    }
    var calibrated by remember { mutableStateOf(com.vrca.nowplaying.ChatboxCalibration.calibrated) }
    var testChar by remember { mutableStateOf('M') }
    var count by remember { mutableStateOf(20) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Chatbox width calibration", style = MaterialTheme.typography.labelMedium)
        Text(
            "Send a line of one repeated character, watch where it wraps in VRChat, and enter " +
                "the highest count that stays on ONE line. Repeat per char, then Save.  " +
                if (calibrated) "Status: CALIBRATED." else "Status: using estimates.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text("Test: '$testChar' × $count", style = MaterialTheme.typography.bodySmall)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            com.vrca.nowplaying.ChatboxCalibration.REFERENCE_CHARS.forEach { c ->
                androidx.compose.material3.FilterChip(
                    selected = testChar == c,
                    onClick = { testChar = c },
                    label = { Text(if (c == ' ') "␣" else c.toString()) }
                )
            }
        }
        androidx.compose.material3.Slider(
            value = count.toFloat(),
            onValueChange = { count = it.toInt().coerceIn(1, 80) },
            valueRange = 1f..80f
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.Button(onClick = {
                vm.sendCalibrationLine(testChar.toString().repeat(count))
            }) { Text("Send × $count") }
            androidx.compose.material3.OutlinedButton(onClick = { vm.sendCalibrationLine("") }) {
                Text("Clear chatbox")
            }
        }

        // --- AUTO sequence: fire each reference char (over-long so it wraps) one
        // at a time; screenshot each in VRChat and send the shots to the dev, who
        // reads the wrap points and fills the exact table. ---
        var seqIndex by remember { mutableStateOf(-1) }
        fun roughUnits(c: Char): Float = when {
            c == 'm' || c == 'w' || c == 'M' || c == 'W' -> 1.4f
            c.isUpperCase() || c.isDigit() -> 1.2f
            c in " ijltfr.,':;!|()[]-" -> 0.6f
            c.code in 0xFF00..0xFF60 || c.code in 0x2E80..0x9FFF ||
                c.code in 0xAC00..0xD7AF || c.code in 0x3000..0x303F -> 2.0f
            else -> 1.0f
        }
        fun seqSend(i: Int) {
            val c = com.vrca.nowplaying.ChatboxCalibration.REFERENCE_CHARS[i]
            val n = (40f / roughUnits(c)).toInt().coerceIn(8, 140)
            vm.sendCalibrationLine(c.toString().repeat(n))
        }
        androidx.compose.material3.Divider(Modifier.padding(vertical = 2.dp))
        Text("Auto sequence — screenshot each line in VRChat and send the shots to the dev:",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.Button(onClick = { seqIndex = 0; seqSend(0) }) {
                Text(if (seqIndex < 0) "Start sequence" else "Restart")
            }
            if (seqIndex >= 0) {
                androidx.compose.material3.OutlinedButton(
                    onClick = { if (seqIndex > 0) { seqIndex--; seqSend(seqIndex) } },
                    enabled = seqIndex > 0
                ) { Text("Prev") }
                androidx.compose.material3.Button(onClick = {
                    if (seqIndex < com.vrca.nowplaying.ChatboxCalibration.REFERENCE_CHARS.size - 1) {
                        seqIndex++; seqSend(seqIndex)
                    }
                }) { Text("Next") }
            }
        }
        if (seqIndex >= 0) {
            val c = com.vrca.nowplaying.ChatboxCalibration.REFERENCE_CHARS[seqIndex]
            Text(
                "Showing ${seqIndex + 1}/${com.vrca.nowplaying.ChatboxCalibration.REFERENCE_CHARS.size}: " +
                    "'${if (c == ' ') "␣ (space)" else c.toString()}' — screenshot the chatbox, then Next.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        androidx.compose.material3.Divider(Modifier.padding(vertical = 2.dp))

        Text("Measured max count per char (auto-filled from your screenshots, or type them):",
            style = MaterialTheme.typography.bodySmall)
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            com.vrca.nowplaying.ChatboxCalibration.REFERENCE_CHARS.forEach { c ->
                androidx.compose.material3.OutlinedTextField(
                    value = measured[c] ?: "",
                    onValueChange = { measured[c] = it.filter { ch -> ch.isDigit() }.take(3) },
                    modifier = Modifier.width(78.dp),
                    label = { Text(if (c == ' ') "␣" else c.toString()) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    )
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.Button(onClick = {
                val counts = measured.mapNotNull { (c, s) ->
                    s.toIntOrNull()?.takeIf { it > 0 }?.let { c to it }
                }.toMap()
                com.vrca.nowplaying.ChatboxCalibration.save(ctx, counts)
                calibrated = com.vrca.nowplaying.ChatboxCalibration.calibrated
            }) { Text("Save calibration") }
            androidx.compose.material3.OutlinedButton(onClick = {
                com.vrca.nowplaying.ChatboxCalibration.clear(ctx)
                measured.clear()
                calibrated = false
            }) { Text("Clear") }
        }
    }
}
