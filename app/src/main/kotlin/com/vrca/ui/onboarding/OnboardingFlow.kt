package com.vrca.ui.onboarding

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.vrca.app.VrcaApplication
import com.vrca.discord.DiscordLoginWebView
import com.vrca.osc.VrcaOsc
import com.vrca.ui.common.KitStatusChip
import com.vrca.ui.common.KitTone
import com.vrca.ui.common.StatusDot
import com.vrca.ui.common.TrustNote
import com.vrca.vrchat.VrchatAuthManager
import com.vrca.vrchat.VrchatLoginScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/* =========================================================================
   First-open onboarding tutorial (docs/ui-revamp.md, "Onboarding").

   Pager flow — order is FINAL:
     1 Welcome+ToS (hard gate)  2 VRChat login (hard gate)  3 Permissions
     4 IP entry (slot 1)        5 Enable OSC                6 Notifications
     7 Discord RPC (skippable)  8 Test message finale

   Mechanics:
   - `vrca_onboarding/complete` gates the flow; VrcaApp PRE-SEEDS it for
     existing installs (accepted ToS already present) so current users never
     see the tutorial.
   - Current step index persists so process death mid-onboarding resumes in
     place (headset-on-face interruptions are guaranteed).
   - Replay (Settings row) re-runs the flow with hard gates rendered as
     completed checkmark steps instead of re-prompting.
   ========================================================================= */

object OnboardingPrefs {
    private const val FILE = "vrca_onboarding"
    private const val KEY_COMPLETE = "complete"
    private const val KEY_STEP = "step"

    fun isComplete(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_COMPLETE, false)

    fun markComplete(ctx: Context) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_COMPLETE, true).remove(KEY_STEP).apply()
    }

    fun savedStep(ctx: Context): Int =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY_STEP, 0)

    fun saveStep(ctx: Context, step: Int) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(KEY_STEP, step).apply()
    }
}

/** Process-level replay trigger — Settings sets it, VrcaApp observes it. */
object OnboardingState {
    val replayRequested = mutableStateOf(false)
}

/* -------------------------------------------------------------------------
   VRChat platform status (login-step warning).
   The pipeline's status poll only runs POST-login, so the login step does
   its own one-shot fetch of the public status summary. Free HTTP call.
   ------------------------------------------------------------------------- */

internal data class VrchatStatusWarning(val indicator: String, val description: String)

internal suspend fun fetchVrchatStatusWarning(): VrchatStatusWarning? = withContext(Dispatchers.IO) {
    try {
        val conn = java.net.URL("https://status.vrchat.com/api/v2/summary.json")
            .openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 6000
        conn.useCaches = false
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val status = JSONObject(body).optJSONObject("status") ?: return@withContext null
        val indicator = status.optString("indicator", "none")
        if (indicator == "none" || indicator.isBlank()) null
        else VrchatStatusWarning(indicator, status.optString("description", "VRChat is having issues"))
    } catch (_: Throwable) {
        null
    }
}

/* -------------------------------------------------------------------------
   Flow
   ------------------------------------------------------------------------- */

private const val STEP_COUNT = 8

@Composable
fun OnboardingFlow(
    tosVersion: Int,
    tosText: String,
    tosUrl: String,
    tosAlreadyAccepted: Boolean,
    phase1BanId: String?,
    replay: Boolean,
    onTosAccepted: () -> Unit,
    onFinish: () -> Unit
) {
    val ctx = LocalContext.current
    var step by rememberSaveable {
        mutableStateOf(if (replay) 0 else OnboardingPrefs.savedStep(ctx).coerceIn(0, STEP_COUNT - 1))
    }

    // Persist resume point (not in replay — replay always starts clean).
    LaunchedEffect(step) { if (!replay) OnboardingPrefs.saveStep(ctx, step) }

    fun advance() { if (step < STEP_COUNT - 1) step++ else { OnboardingPrefs.markComplete(ctx); onFinish() } }
    fun back() { if (step > 0) step-- }
    fun finishNow() { OnboardingPrefs.markComplete(ctx); onFinish() }

    // Hard gates completed state (replay shows them as checkmarks)
    val tosDone = tosAlreadyAccepted
    val loginDone = remember { mutableStateOf(VrchatAuthManager.isLoggedIn(ctx)) }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Step content
            Box(Modifier.weight(1f)) {
                when (step) {
                    0 -> StepWelcomeTos(tosVersion, tosText, tosUrl, tosDone, onAccept = {
                        onTosAccepted(); advance()
                    }, onNext = { advance() })
                    1 -> StepVrchatLogin(loginDone.value, phase1BanId, onLoggedIn = {
                        loginDone.value = true; advance()
                    }, onNext = { advance() })
                    2 -> StepPermissions()
                    3 -> StepIpEntry()
                    4 -> StepEnableOsc()
                    5 -> StepNotificationTypes()
                    6 -> StepDiscord()
                    7 -> StepTestMessage()
                }
            }

            // Footer: progress dots + nav. Hard gates hide "Next" until done.
            val hardGateBlocked = (step == 0 && !tosDone) || (step == 1 && !loginDone.value)
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(STEP_COUNT) { i ->
                            Box(
                                Modifier
                                    .padding(3.dp)
                                    .size(if (i == step) 9.dp else 7.dp)
                                    .clip(CircleShape)
                                    .then(
                                        Modifier.clickable(enabled = i < step) { step = i }
                                    )
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (i <= step) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.fillMaxSize()
                                ) {}
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (step > 0) {
                            TextButton(onClick = { back() }) { Text("Back") }
                        } else Spacer(Modifier.width(8.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (replay) {
                                TextButton(onClick = { finishNow() }) { Text("Exit tutorial") }
                                Spacer(Modifier.width(4.dp))
                            }
                            if (!hardGateBlocked) {
                                Button(onClick = { advance() }) {
                                    Text(if (step == STEP_COUNT - 1) "Finish" else "Next")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/* -------------------------------------------------------------------------
   Shared step scaffolding
   ------------------------------------------------------------------------- */

@Composable
private fun StepContainer(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        content()
    }
}

@Composable
private fun CompletedGateCard(text: String) {
    ElevatedCard {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = com.vrca.ui.theme.SlimeSuccess
            )
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun NumberedInstruction(number: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
            modifier = Modifier.size(26.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    "$number",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/* -------------------------------------------------------------------------
   Step 1 — Welcome + ToS
   ------------------------------------------------------------------------- */

@Composable
private fun StepWelcomeTos(
    tosVersion: Int,
    tosText: String,
    tosUrl: String,
    alreadyAccepted: Boolean,
    onAccept: () -> Unit,
    onNext: () -> Unit
) {
    val ctx = LocalContext.current
    if (alreadyAccepted) {
        StepContainer(
            title = "Welcome to VRC-A",
            subtitle = "Your VRChat chatbox companion. This quick setup gets everything working."
        ) {
            CompletedGateCard("Terms of Service v$tosVersion already accepted.")
        }
        return
    }
    // Not yet accepted: render the full redesigned ToS gate as the step body.
    // Its own Accept button advances the flow; the pager footer hides Next.
    com.vrca.app.TosGate(
        tosVersion = tosVersion,
        tosText = tosText,
        tosUrl = tosUrl,
        onOpenUrl = { url ->
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        },
        onAccept = onAccept
    )
}

/* -------------------------------------------------------------------------
   Step 2 — VRChat login (+ platform status warning)
   ------------------------------------------------------------------------- */

@Composable
private fun StepVrchatLogin(
    alreadyLoggedIn: Boolean,
    phase1BanId: String?,
    onLoggedIn: () -> Unit,
    onNext: () -> Unit
) {
    var statusWarning by remember { mutableStateOf<VrchatStatusWarning?>(null) }
    LaunchedEffect(Unit) { statusWarning = fetchVrchatStatusWarning() }

    if (alreadyLoggedIn) {
        StepContainer(
            title = "VRChat account",
            subtitle = "VRC-A is connected to your VRChat account."
        ) {
            CompletedGateCard("Already signed in to VRChat.")
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        statusWarning?.let { w ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth().padding(12.dp).clip(MaterialTheme.shapes.medium)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "VRChat is having platform issues right now (${w.description}).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        "Login may fail — it's not you. You can retry later.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
        Box(Modifier.weight(1f)) {
            VrchatLoginScreen(pendingBanId = phase1BanId) { _, _ -> onLoggedIn() }
        }
    }
}

/* -------------------------------------------------------------------------
   Step 3 — Permissions
   ------------------------------------------------------------------------- */

@Composable
private fun StepPermissions() {
    val ctx = LocalContext.current
    var refreshTick by remember { mutableStateOf(0) }

    // Re-check statuses whenever the user returns from a system settings page.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val notifGranted = remember(refreshTick) {
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    }
    val listenerEnabled = remember(refreshTick) {
        Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
            ?.contains(ctx.packageName) == true
    }
    val batteryExempt = remember(refreshTick) {
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(ctx.packageName)
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshTick++ }

    StepContainer(
        title = "Permissions",
        subtitle = "Three things keep VRC-A working reliably. Each one opens the exact system page."
    ) {
        PermissionRow(
            title = "Notifications",
            why = "So friend activity, invites and group alerts can reach you.",
            granted = notifGranted
        ) {
            if (Build.VERSION.SDK_INT >= 33) {
                permLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        PermissionRow(
            title = "Notification Access",
            why = "Reads your music player's notification to show Now Playing in the chatbox. Nothing else is read.",
            granted = listenerEnabled
        ) {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        PermissionRow(
            title = "Battery optimization exemption",
            why = "Stops Android pausing VRC-A when the screen is off. Strongly recommended.",
            granted = batteryExempt
        ) {
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:${ctx.packageName}"))
                )
            }
        }
        if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            TrustNote(
                "Samsung: also add VRC-A to Settings → Battery → \"Never sleeping apps\" — " +
                "Samsung kills background apps aggressively even with the exemption."
            )
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    why: String,
    granted: Boolean,
    onRequest: () -> Unit
) {
    ElevatedCard {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatusDot(if (granted) KitTone.Success else KitTone.Warning)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    why,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (granted) {
                Icon(Icons.Filled.Check, contentDescription = "Granted",
                    tint = com.vrca.ui.theme.SlimeSuccess)
            } else {
                OutlinedButton(onClick = onRequest) { Text("Grant") }
            }
        }
    }
}

/* -------------------------------------------------------------------------
   Step 4 — IP entry (writes slot 1, live reachability check)
   ------------------------------------------------------------------------- */

@Composable
private fun StepIpEntry() {
    val ctx = LocalContext.current
    val repo = (ctx.applicationContext as VrcaApplication).userPreferencesRepository
    val scope = rememberCoroutineScope()

    var ip by rememberSaveable { mutableStateOf("") }
    var saved by rememberSaveable { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    // null = not checked, true = reachable, false = couldn't confirm
    var reachable by remember { mutableStateOf<Boolean?>(null) }

    // Pre-fill from an already-saved slot 1 (resume / replay)
    LaunchedEffect(Unit) {
        if (ip.isBlank()) {
            val existing = repo.ip1Address.first()
            if (existing.isNotBlank()) { ip = existing; saved = true }
        }
    }

    fun saveAndCheck() {
        val addr = ip.trim()
        if (addr.isBlank()) return
        scope.launch {
            repo.saveIpSlotAddress(1, addr)
            repo.saveIpAddress(addr) // active runtime target
            saved = true
            checking = true
            reachable = withContext(Dispatchers.IO) {
                try { java.net.InetAddress.getByName(addr).isReachable(1500) }
                catch (_: Throwable) { false }
            }
            checking = false
        }
    }

    StepContainer(
        title = "Headset / PC address",
        subtitle = "VRC-A sends chatbox text over your Wi-Fi to the device running VRChat."
    ) {
        ElevatedCard {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("On Quest:", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
                NumberedInstruction(1, "Open Settings in the headset")
                NumberedInstruction(2, "Wi-Fi → tap your connected network")
                NumberedInstruction(3, "Scroll down — copy the IP address (e.g. 192.168.1.23)")
                TrustNote(
                    "On PC or phone it works the same way: your phone's Wi-Fi details, " +
                    "or on PC run ipconfig and read the IPv4 address."
                )
            }
        }

        OutlinedTextField(
            value = ip,
            onValueChange = { ip = it; saved = false; reachable = null },
            label = { Text("IP address") },
            placeholder = { Text("192.168.1.23") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { saveAndCheck() }, enabled = ip.isNotBlank() && !checking) {
                Text(if (saved) "Re-check" else "Save & check")
            }
            when {
                checking -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                reachable == true -> KitStatusChip("Reachable", KitTone.Success)
                reachable == false && saved -> KitStatusChip("Saved — couldn't confirm", KitTone.Warning)
                saved -> KitStatusChip("Saved", KitTone.Success)
            }
        }
        if (reachable == false && saved) {
            TrustNote(
                "Couldn't ping the device — that's often fine (headsets ignore pings). " +
                "The address is saved; the final step sends a real test message."
            )
        }
        TrustNote("While you're in the headset: the next step happens there too.")
    }
}

/* -------------------------------------------------------------------------
   Step 5 — Enable OSC in VRChat
   ------------------------------------------------------------------------- */

@Composable
private fun StepEnableOsc() {
    StepContainer(
        title = "Enable OSC in VRChat",
        subtitle = "OSC is the channel VRC-A uses to reach your chatbox. It's off by default."
    ) {
        ElevatedCard {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberedInstruction(1, "In VRChat, open the Action Menu (radial menu)")
                NumberedInstruction(2, "Select Options")
                NumberedInstruction(3, "Select OSC")
                NumberedInstruction(4, "Set OSC to Enabled")
            }
        }
        // TODO(assets): TutorialImage frames of the radial menu path go here
        // once the captures are provided (docs/ui-revamp.md, Instructional images).
        TrustNote(
            "Phone and headset/PC must be on the same Wi-Fi network. " +
            "If OSC ever acts up in VRChat, the same menu has an OSC → Reset option."
        )
    }
}

/* -------------------------------------------------------------------------
   Step 6 — Notification types
   ------------------------------------------------------------------------- */

private data class NotifSection(
    val title: String,
    val description: String,
    val apply: suspend (com.vrca.data.UserPreferencesRepository, Boolean) -> Unit
)

private val notifSections = listOf(
    NotifSection("Friend list", "Friend requests, new friends, removals.") { r, v ->
        r.saveNotifFriendRequest(v); r.saveNotifNewFriend(v); r.saveNotifUnfriend(v)
        r.saveNotifVrchatMessage(v)
    },
    NotifSection("Friends activity", "Online/offline, world moves, status, avatar, bio, rank.") { r, v ->
        r.saveNotifFriendOnline(v); r.saveNotifFriendOffline(v); r.saveNotifFriendActive(v)
        r.saveNotifFriendLocation(v); r.saveNotifFriendStatus(v); r.saveNotifFriendAvatar(v)
        r.saveNotifFriendBio(v); r.saveNotifFriendDisplayName(v); r.saveNotifFriendRank(v)
        r.saveNotifGiftReceived(v)
    },
    NotifSection("Invites", "World invites, invite requests, group invites.") { r, v ->
        r.saveNotifInvite(v); r.saveNotifInviteRequest(v); r.saveNotifGroupInvite(v)
    },
    NotifSection("Groups", "Announcements, events, queue, roles, join requests.") { r, v ->
        r.saveNotifGroupAnnouncement(v); r.saveNotifGroupEvent(v); r.saveNotifGroupQueue(v)
        r.saveNotifGroupJoinRequest(v); r.saveNotifGroupRole(v); r.saveNotifGroupInstance(v)
    },
    NotifSection("App & connection", "App updates, server alerts, connection drops, sign-in alerts.") { r, v ->
        r.saveNotifAppUpdate(v); r.saveNotifAnnouncements(v); r.saveNotifConnection(v)
        r.saveNotifAuth(v); r.saveNotifVrchatAlert(v); r.saveNotifVoteToKick(v)
    }
)

@Composable
private fun StepNotificationTypes() {
    val ctx = LocalContext.current
    val repo = (ctx.applicationContext as VrcaApplication).userPreferencesRepository
    val scope = rememberCoroutineScope()

    // Section switches default ON. Only sections the user actually TOUCHES are
    // written — untouched sections keep their per-key defaults, so this step
    // can't silently flip keys whose defaults differ from the section value.
    val states = remember { notifSections.map { mutableStateOf(true) } }
    val touched = remember { notifSections.map { mutableStateOf(false) } }

    StepContainer(
        title = "Notifications",
        subtitle = "Pick what you want to hear about. Every individual type can be fine-tuned later in Settings."
    ) {
        notifSections.forEachIndexed { i, section ->
            ElevatedCard {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(section.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            section.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = states[i].value,
                        onCheckedChange = { v ->
                            states[i].value = v
                            touched[i].value = true
                            scope.launch { section.apply(repo, v) }
                        }
                    )
                }
            }
        }
    }
}

/* -------------------------------------------------------------------------
   Step 7 — Discord RPC (optional)
   ------------------------------------------------------------------------- */

@Composable
private fun StepDiscord() {
    val ctx = LocalContext.current
    val repo = (ctx.applicationContext as VrcaApplication).userPreferencesRepository
    val scope = rememberCoroutineScope()

    var consentChecked by rememberSaveable { mutableStateOf(false) }
    var showLogin by rememberSaveable { mutableStateOf(false) }
    var connected by rememberSaveable { mutableStateOf(false) }

    if (showLogin) {
        DiscordLoginWebView(
            onLoginComplete = {
                scope.launch {
                    repo.saveDiscordRiskAccepted(true)
                    repo.saveDiscordSessionSeeded(true)
                    repo.saveDiscordRpcEnabled(true)
                }
                connected = true
                showLogin = false
            },
            onDismiss = { showLogin = false }
        )
        return
    }

    StepContainer(
        title = "Discord Rich Presence",
        subtitle = "Optional: show your VRChat activity on your Discord profile. You can skip this and set it up later in the VRChat tab."
    ) {
        if (connected) {
            CompletedGateCard("Discord connected — Rich Presence enabled.")
            return@StepContainer
        }
        ElevatedCard {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Before you connect", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
                Text(
                    "• Uses a hidden Discord web session on this device\n" +
                    "• This is not an approved Discord integration — Discord could " +
                    "terminate the session or flag the account\n" +
                    "• Disconnecting may invalidate Discord sessions on other devices\n" +
                    "• Your session stays on-device only and is never sent anywhere",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    Modifier.fillMaxWidth().clickable { consentChecked = !consentChecked },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = consentChecked, onCheckedChange = { consentChecked = it })
                    Text("I understand the risks", style = MaterialTheme.typography.bodyMedium)
                }
                Button(
                    onClick = { showLogin = true },
                    enabled = consentChecked,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Connect Discord") }
            }
        }
    }
}

/* -------------------------------------------------------------------------
   Step 8 — Test message finale
   ------------------------------------------------------------------------- */

@Composable
private fun StepTestMessage() {
    val ctx = LocalContext.current
    val repo = (ctx.applicationContext as VrcaApplication).userPreferencesRepository

    var running by rememberSaveable { mutableStateOf(false) }
    var targetIp by remember { mutableStateOf("") }
    var targetPort by remember { mutableStateOf(9000) }

    LaunchedEffect(Unit) {
        targetIp = repo.ip1Address.first()
        targetPort = repo.port.first()
    }

    // Scoped test sender — deliberately BYPASSES the Start/Stop gate: the
    // master oscSending stays false, no toggles are touched and
    // FeatureSessionStore is never armed, so a process death mid-tutorial
    // resurrects nothing. The chatbox text fades ~30s after the last send, so
    // the loop re-sends on a keepalive cadence while the step is active; on
    // stop/exit one empty send wipes the chatbox clean.
    DisposableEffect(running, targetIp) {
        if (!running || targetIp.isBlank()) return@DisposableEffect onDispose {}
        val osc = VrcaOsc(targetIp, targetPort)
        val job = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                osc.sendMessage("VRC-A connected ✓", true, false)
                delay(2000)
            }
        }
        onDispose {
            job.cancel()
            osc.sendMessage("", true, false)
        }
    }

    StepContainer(
        title = "Test it!",
        subtitle = "If everything is set up, this pins a message in your VRChat chatbox. Skip if you're not in VRChat right now."
    ) {
        // Preview bubble — teaches preview == chatbox
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    if (running) "VRC-A connected ✓" else "(test not running)",
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
            }
        }

        if (targetIp.isBlank()) {
            TrustNote("No IP saved — go back to the address step first.")
        } else {
            Text(
                "Sending to $targetIp:$targetPort",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Button(
            onClick = { running = !running },
            enabled = targetIp.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (running) "Stop test" else "Send test message")
        }

        if (running) {
            ElevatedCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Not showing in VRChat?", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "• Is OSC enabled? (previous step)\n" +
                        "• Phone and headset on the same Wi-Fi?\n" +
                        "• Is the IP address right? (address step)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        TrustNote("The message clears automatically when you stop or finish the tutorial.")
    }
}
