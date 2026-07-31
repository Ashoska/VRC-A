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
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.vrca.app.VrcaApplication
import com.vrca.discord.DiscordLoginWebView
import com.vrca.osc.VrcaOsc
import com.vrca.ui.common.CompactSectionCard
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
    // Set the moment the genuine first-run tutorial begins. It's what lets a
    // crash/close mid-tutorial RESUME the tutorial instead of being mistaken for an
    // existing install: once the user accepts the ToS in onboarding step 1,
    // TosPrefs.acceptedVersion > 0, which the existing-user pre-seed keys on — but a
    // started-and-not-complete onboarding must keep showing, so the pre-seed only
    // applies when onboarding was never started.
    private const val KEY_STARTED = "started"

    fun isComplete(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_COMPLETE, false)

    fun markComplete(ctx: Context) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_COMPLETE, true).remove(KEY_STEP).apply()
    }

    /** True once the first-run tutorial has actually begun on this install. */
    fun wasStarted(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_STARTED, false)

    fun markStarted(ctx: Context) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_STARTED, true).apply()
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

/**
 * Robust reachability ping. The bare `InetAddress.isReachable` frequently
 * fails on Android (apps can't open raw ICMP sockets, and the TCP-port-7
 * fallback is almost never open), reporting "no reply" for devices that
 * answer pings fine. The system `ping` binary has the right capabilities on
 * every Android build, so it is the authoritative fallback. Used by the
 * tutorial's IP + test steps AND the Home Connection card.
 */
suspend fun pingHost(address: String, timeoutMs: Int = 2000): Boolean =
    withContext(Dispatchers.IO) {
        val viaApi = try {
            java.net.InetAddress.getByName(address).isReachable(timeoutMs)
        } catch (_: Throwable) {
            false
        }
        if (viaApi) return@withContext true
        try {
            val proc = Runtime.getRuntime().exec(
                arrayOf("/system/bin/ping", "-c", "1", "-W", "2", address)
            )
            proc.waitFor() == 0
        } catch (_: Throwable) {
            false
        }
    }

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
    vm: com.vrca.ui.viewmodel.VrcaViewModel,
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

    // Mark the genuine tutorial as started so a crash/close mid-tutorial RESUMES it
    // (the existing-user ToS pre-seed in VrcaApp is skipped once this is set). Replay
    // never sets it — replaying must not change first-run state.
    LaunchedEffect(Unit) { if (!replay) OnboardingPrefs.markStarted(ctx) }

    // The OSC tutorial images aren't bundled in the APK — download them on entry.
    // New users prefetch at boot (VrcaApp); replay downloads them fresh here.
    // Idempotent: cached images are skipped. Cleanup happens in VrcaApp onFinish.
    LaunchedEffect(Unit) { TutorialImageStore.ensureDownloaded(ctx) }

    fun advance() { if (step < STEP_COUNT - 1) step++ else { OnboardingPrefs.markComplete(ctx); onFinish() } }
    fun back() { if (step > 0) step-- }
    fun finishNow() { OnboardingPrefs.markComplete(ctx); onFinish() }

    // Hard gates completed state (replay shows them as checkmarks)
    val tosDone = tosAlreadyAccepted
    val loginDone = remember { mutableStateOf(VrchatAuthManager.isLoggedIn(ctx)) }
    // Final hard gate: a test message must be confirmed against the saved IP
    // before Finish unlocks (replay is exempt — it has Exit tutorial anyway).
    val testDone = remember { mutableStateOf(false) }

    // Tapping empty space anywhere in the tutorial clears text-field focus —
    // a focused field otherwise stays selected while reading/navigating.
    val focusManager = LocalFocusManager.current

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { focusManager.clearFocus() })
                }
        ) {
            // Step content — monitor-shape framing: center at a comfortable max
            // width on a wide Quest panel (a phone stays under the cap and fills as
            // before). The footer below keeps full panel width as the window chrome.
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
              Box(Modifier.fillMaxHeight().widthIn(max = 640.dp)) {
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
                    5 -> StepNotificationTypes(vm)
                    6 -> StepDiscord()
                    7 -> StepTestMessage(
                        passed = testDone.value,
                        onPassed = { testDone.value = true },
                        onGoToIpStep = { step = 3 }
                    )
                }
              }
            }

            // Footer: step label + progress pills + nav. Hard gates hide
            // "Next"/"Finish" until done.
            val hardGateBlocked = (step == 0 && !tosDone) ||
                (step == 1 && !loginDone.value) ||
                (step == STEP_COUNT - 1 && !testDone.value && !replay)
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(
                        "Step ${step + 1} of $STEP_COUNT",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(STEP_COUNT) { i ->
                            // Current step is an elongated pill; visited steps are
                            // filled and tappable to jump back; future steps muted.
                            val w by animateDpAsState(
                                targetValue = if (i == step) 24.dp else 8.dp,
                                label = "onboardingDot"
                            )
                            Box(
                                Modifier
                                    .padding(horizontal = 3.dp)
                                    .size(width = w, height = 8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (i <= step) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .clickable(enabled = i < step) { step = i }
                            )
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
    if (alreadyLoggedIn) {
        StepContainer(
            title = "VRChat account",
            subtitle = "VRC-A is connected to your VRChat account."
        ) {
            CompletedGateCard("Already signed in to VRChat.")
        }
        return
    }

    // The platform-status warning ("VRChat is having issues — it's not you")
    // now lives INSIDE VrchatLoginScreen so the Settings / VRChat-tab login
    // takeovers show it too; embedding it here as well would double it up.
    Box(Modifier.fillMaxSize()) {
        VrchatLoginScreen(pendingBanId = phase1BanId) { _, _ -> onLoggedIn() }
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
        // Exact-package match. The flat settings-string contains() check
        // false-positived on the public build whenever the ADMIN build
        // (com.scrapw.chatbox.admin — a superstring of the public package)
        // had Notification Access enabled.
        androidx.core.app.NotificationManagerCompat
            .getEnabledListenerPackages(ctx)
            .contains(ctx.packageName)
    }
    val batteryExempt = remember(refreshTick) {
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(ctx.packageName)
    }
    val installAllowed = remember(refreshTick) {
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()
    }
    val overlayAllowed = remember(refreshTick) {
        Settings.canDrawOverlays(ctx)
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshTick++ }

    StepContainer(
        title = "Permissions",
        subtitle = "These keep VRC-A working reliably. Each one opens the exact system page."
    ) {
        CompactSectionCard(
            title = "A permission won't turn on?",
            summary = "Read this if a switch is greyed out or says \"Restricted setting\"",
            collapsible = true,
            initiallyExpanded = false,
            persistExpansion = false
        ) {
            Text(
                "Because VRC-A is installed from outside the Play Store, Android 13+ " +
                "blocks some permissions (like Notification Access) until you unlock " +
                "them. If a switch is greyed out, or you see \"Restricted setting\" / " +
                "\"For your security, this setting is currently unavailable\":\n" +
                "1. Press and hold the VRC-A icon on your home screen or app drawer.\n" +
                "2. Tap \"App info\" (the i icon).\n" +
                "3. Open the 3-dot menu (top right).\n" +
                "4. Tap \"Allow restricted settings\".\n" +
                "5. Go back to the permission and turn it on. It will work now.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
                TrustNote(
                    "On Samsung the menu item may instead read \"Allow restricted " +
                    "settings\" after you tap the 3-dot menu. If you don't see it, the " +
                    "permission isn't restricted on your phone and you can toggle it directly."
                )
            }
        }
        // POST_NOTIFICATIONS — not prompted on the headset (Quest doesn't surface
        // the app notifications the way a phone does).
        if (!com.vrca.BuildConfig.IS_HEADSET_BUILD) {
            PermissionRow(
                title = "Notifications",
                why = "So friend activity, invites and group alerts can reach you.",
                granted = notifGranted
            ) {
                if (Build.VERSION.SDK_INT >= 33) {
                    permLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
        // (Notification Access / Now Playing removed from the tutorial — media
        // detection is moving to Spotify auth, so it's no longer required/prompted.)
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
        // Install-updates (unknown sources) — not on the headset: distributed via
        // the Meta store, so no in-app APK install.
        if (!com.vrca.BuildConfig.IS_HEADSET_BUILD) {
            PermissionRow(
                title = "Install updates",
                why = "Lets VRC-A install its own update APKs when a new version is pushed.",
                granted = installAllowed
            ) {
                runCatching {
                    ctx.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                            .setData(Uri.parse("package:${ctx.packageName}"))
                    )
                }
            }
        }
        PermissionRow(
            title = "Display over other apps (optional)",
            why = "Only needed if you use the floating chatbox overlay.",
            granted = overlayAllowed
        ) {
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        .setData(Uri.parse("package:${ctx.packageName}"))
                )
            }
        }
        CompactSectionCard(
            title = "Turn off background restrictions",
            summary = "Stops the OS pausing notifications and music detection",
            collapsible = true,
            initiallyExpanded = false,
            persistExpansion = false
        ) {
            Text(
                "If notifications or music detection stop while VRC-A is in the " +
                "background, the phone is restricting it. To fix:\n" +
                "1. Press and hold the VRC-A icon on your home screen or app drawer.\n" +
                "2. Tap \"App info\" (the i icon).\n" +
                "3. Open the 3-dot menu (top right) and pick \"Allow background activity\", " +
                "or open Battery and set it to \"Unrestricted\".\n" +
                "4. Make sure VRC-A is NOT in any \"restricted\", \"sleeping\" or " +
                "\"deep sleeping\" apps list.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            com.vrca.ui.common.OemPowerGuidance.forThisDevice()?.let { g ->
                TrustNote("${g.brand}: ${g.steps}")
            }
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

    suspend fun persistIp(addr: String) {
        repo.saveIpSlotAddress(1, addr)
        repo.saveIpAddress(addr) // active runtime target
        saved = true
    }

    // Auto-save what's typed — no button press needed. Debounced 500ms after the
    // last keystroke so we don't write on every character. The "Check" button is
    // now purely an OPTIONAL reachability test, not the only way to save.
    LaunchedEffect(ip) {
        val addr = ip.trim()
        if (addr.isBlank()) return@LaunchedEffect
        delay(500)
        if (addr == ip.trim()) persistIp(addr) // still current after the debounce
    }

    fun saveAndCheck() {
        val addr = ip.trim()
        if (addr.isBlank()) return
        scope.launch {
            persistIp(addr) // flush immediately (don't wait on the debounce)
            checking = true
            reachable = pingHost(addr)
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
                NumberedInstruction(1, "Put the headset on and open Settings (the gear icon at the end of the dock)")
                NumberedInstruction(2, "Select Wi-Fi, then tap the network with \"Connected\" under it")
                NumberedInstruction(3, "Scroll down in the network details panel that opens")
                NumberedInstruction(4, "Find \"IP address\" (four numbers with dots, like 192.168.1.23) and type it below")
            }
        }

        // Collapsed by default (no persistence) so the IP input below sits in
        // view without scrolling — users were missing the field under this card.
        CompactSectionCard(
            title = "Good to know",
            summary = "Tips: IPv6 vs IPv4, same Wi-Fi, finding it on PC",
            collapsible = true,
            initiallyExpanded = false,
            persistExpansion = false
        ) {
            Text(
                "• Ignore the long address with letters and colons (that's IPv6). " +
                "You want the short dotted one.\n" +
                "• Phone and headset must be on the SAME Wi-Fi network.\n" +
                "• The IP can change when the router restarts or you switch networks. " +
                "You can update it any time in Home → Connection.\n" +
                "• On PC: press Win+R, type cmd, run ipconfig and read \"IPv4 Address\".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        OutlinedTextField(
            value = ip,
            onValueChange = { ip = it; saved = false; reachable = null },
            label = { Text("IP address") },
            placeholder = { Text("192.168.1.23") },
            supportingText = { Text("Saves automatically as you type.") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Optional: just tests whether the address answers a ping. The IP is
            // already saved automatically above, so this is not required.
            OutlinedButton(onClick = { saveAndCheck() }, enabled = ip.isNotBlank() && !checking) {
                Text("Check reachability")
            }
            when {
                checking -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                reachable == true -> KitStatusChip("Reachable", KitTone.Success)
                reachable == false && saved -> KitStatusChip("Saved, no reply yet", KitTone.Warning)
                saved -> KitStatusChip("Saved", KitTone.Success)
            }
        }
        if (reachable == false && saved) {
            TrustNote(
                "This address isn't answering yet. Double-check it: the final test " +
                "step requires the device to respond before you can finish."
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
                com.vrca.ui.common.TutorialImage(
                    index = 1,
                    contentDescription = "VRChat Action Menu showing Options"
                )
                NumberedInstruction(2, "Select Options, then find OSC")
                com.vrca.ui.common.TutorialImage(
                    index = 2,
                    contentDescription = "Options submenu with OSC highlighted"
                )
                NumberedInstruction(3, "Select OSC")
                com.vrca.ui.common.TutorialImage(
                    index = 3,
                    contentDescription = "OSC submenu showing Enabled toggle off"
                )
                NumberedInstruction(4, "Set OSC to Enabled")
                com.vrca.ui.common.TutorialImage(
                    index = 4,
                    contentDescription = "OSC Enabled toggle turned on"
                )
            }
        }
        TrustNote(
            "Phone and headset/PC must be on the same Wi-Fi network. " +
            "If OSC ever acts up in VRChat, the same menu has an OSC → Reset option."
        )
    }
}

/* -------------------------------------------------------------------------
   Step 6 — Notification types
   ------------------------------------------------------------------------- */

@Composable
private fun StepNotificationTypes(vm: com.vrca.ui.viewmodel.VrcaViewModel) {
    // The FULL per-type toggle UI (same component as Settings → Notifications),
    // not just section master-switches — the user picks and chooses every
    // individual type on first setup, expanding sections like usual. Each
    // toggle persists straight to DataStore through the VM, so nothing extra
    // needs to be written when the step is left.
    StepContainer(
        title = "Notifications",
        subtitle = "Pick exactly what you want to hear about. Expand a section to fine-tune every type. You can change any of these later in Settings."
    ) {
        ElevatedCard {
            Column(Modifier.padding(14.dp)) {
                com.vrca.ui.settings.NotificationToggleSection(vm = vm)
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
        subtitle = "Optional: show your VRChat activity on your Discord profile. You can skip this and set it up later in Settings under Accounts."
    ) {
        if (connected) {
            CompletedGateCard("Discord connected. Rich Presence enabled.")
            return@StepContainer
        }
        ElevatedCard {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Before you connect", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
                Text(
                    "• Uses a hidden Discord web session on this device\n" +
                    "• This is not an approved Discord integration. Discord could " +
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
private fun StepTestMessage(
    passed: Boolean,
    onPassed: () -> Unit,
    onGoToIpStep: () -> Unit
) {
    val ctx = LocalContext.current
    val repo = (ctx.applicationContext as VrcaApplication).userPreferencesRepository

    var running by rememberSaveable { mutableStateOf(false) }
    var attempts by remember { mutableStateOf(0) }
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

    // HARD GATE: the saved address must answer a ping before Finish unlocks.
    // While the test runs, ping attempts repeat every 3s and the step passes
    // the moment one succeeds. There is deliberately NO manual "it showed up"
    // bypass: a wrong IP must be fixed, not waved through. pingHost falls back
    // to the system ping binary, so devices the bare isReachable misses still
    // pass.
    LaunchedEffect(running, targetIp) {
        if (!running || passed || targetIp.isBlank()) return@LaunchedEffect
        attempts = 0
        while (true) {
            attempts++
            if (pingHost(targetIp)) {
                onPassed()
                return@LaunchedEffect
            }
            delay(3000)
        }
    }

    StepContainer(
        title = "Test it!",
        subtitle = "This step is required: your headset or PC must respond before you can finish the tutorial."
    ) {
        // Preview bubble — teaches preview == chatbox
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    if (running || passed) "VRC-A connected ✓" else "(test not running)",
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
            }
        }

        if (passed) {
            CompletedGateCard("Device responded. You're all set, press Finish!")
        }

        if (targetIp.isBlank()) {
            TrustNote("No IP saved yet. The test needs the address from the earlier step.")
            OutlinedButton(onClick = onGoToIpStep, modifier = Modifier.fillMaxWidth()) {
                Text("Go to the address step")
            }
        } else {
            Text(
                "Sending to $targetIp:$targetPort",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (!passed) {
            Button(
                onClick = { running = !running },
                enabled = targetIp.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (running) "Stop test" else "Send test message")
            }
        }

        if (running && !passed) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    "Waiting for the device to answer" +
                        (if (attempts > 1) " (attempt $attempts)" else "") + "...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ElevatedCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No answer yet?", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "• Phone and headset on the same Wi-Fi?\n" +
                        "• Is the IP address right? Re-check it in the headset's Wi-Fi details\n" +
                        "• Is the headset awake? A sleeping headset won't answer\n" +
                        "• Is OSC enabled? (previous step)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = onGoToIpStep, modifier = Modifier.fillMaxWidth()) {
                        Text("Fix the address")
                    }
                }
            }
        }
        TrustNote("The message clears automatically when you stop or finish the tutorial.")
    }
}
