package com.vrca.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import com.google.firebase.firestore.FirebaseFirestore
import com.vrca.BuildConfig
import com.vrca.discord.DiscordRpcState
import com.vrca.discord.DiscordRpcStatus
import com.vrca.update.ReleaseCheckResult
import com.vrca.update.checkFirestoreRelease
import com.vrca.vrchat.AvatarGlobalDb
import com.vrca.vrchat.InstanceRosterManager
import com.vrca.vrchat.VrchatAuthManager
import com.vrca.vrchat.VrchatBanChecker
import com.vrca.vrchat.VrchatPipelineState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the boot found, handed to [VrcaApp] so the gates after boot reuse it instead of
 * repeating the same reads (the ban check, the release check).
 */
internal object BootResults {
    /** True once Account standing ran; [banId] is then the phase-1 ban id (null = clean). */
    @Volatile var banChecked = false
    @Volatile var banId: String? = null
    /** The Updates row's release check, or null if it didn't finish. */
    @Volatile var release: ReleaseCheckResult? = null
    /** The release check, handed over once (then cleared so it can't go stale). */
    fun takeRelease(): ReleaseCheckResult? = release.also { release = null }
}

/** The checklist rows, in screen order (dependency order: the device session comes first). */
private enum class Row(val label: String, val blocking: Boolean) {
    Device("Device session", true),
    Terms("Terms of Service", true),
    Account("Account standing", true),
    VrchatSignIn("VRChat sign-in", true),
    Discord("Discord", false),
    VrchatHere("VRChat on this headset", false),
    Updates("Updates", true);

    /** Rows a build doesn't have: the VRChat log is only on the headset, and the admin build
     *  never checks for updates. */
    val inThisBuild: Boolean
        get() = when (this) {
            VrchatHere -> BuildConfig.IS_HEADSET_BUILD
            Updates -> !BuildConfig.IS_ADMIN_BUILD
            else -> true
        }
}

/** This build's rows, in screen order. */
private val ROWS = Row.entries.filter { it.inThisBuild }

private data class RowState(val state: BootState = BootState.Waiting, val note: BootNote? = null)

/**
 * The app's boot (every build): every check starts at once (Terms of Service and Account standing wait for
 * the device session, which they need), each row shows where its check is, and boot hands off when
 * the blocking rows (device session, terms, account, updates) have finished, the screen has fully
 * opened and the Ready sweep has played. The other rows can still be finishing when it opens.
 *
 * A failed device session shows Retry (counting down to an automatic retry) and, when this device
 * has signed in before, Continue anyway. Nothing here writes; reads are the ones boot already did
 * (sign-in, ban check, release check) plus one `config/app` read for the terms row.
 */
@Composable
internal fun AppBoot(onDone: (logoInTopBar: Boolean) -> Unit) {
    val ctx = LocalContext.current
    val rows = remember { mutableStateListOf(*Array(Row.entries.size) { RowState() }) }
    var status by remember { mutableStateOf(BootStatus()) }
    var attempt by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryIn by remember { mutableStateOf<Int?>(null) }
    var shown by remember { mutableStateOf(false) }
    // The checks wait for the panels to start opening: the intro animates on its own (no network or
    // device-session work fighting its first frames) and every row is seen going from Working to done.
    var opening by remember { mutableStateOf(false) }
    var tutorialDone by remember { mutableStateOf(false) }
    var signInAttempt by remember { mutableIntStateOf(0) }
    var showLogin by remember { mutableStateOf(false) }
    // Boot may go on once every blocking row has settled without failing (a failed device session
    // waits on Retry, a failed VRChat sign-in waits on signing in).
    val blockingDone = tutorialDone && ROWS.filter { it.blocking }.all { rows[it.ordinal].state in SETTLED }
    val signInNeeded = rows[Row.VrchatSignIn.ordinal].let { it.state == BootState.Failed && it.note == BootNote.SignInNeeded }
    // VRChat's status-page answer, once it's in (the sign-in row needs it to judge a failure).
    suspend fun vrchatStatus(): ServiceState =
        withTimeoutOrNull(8_000L) { snapshotFlow { status.vrchat }.first { it != ServiceState.Checking } } ?: ServiceState.Issues
    val everSignedIn = remember {
        ctx.getSharedPreferences("vrca_remote", Context.MODE_PRIVATE).getString("auth_uid", "").orEmpty().isNotBlank()
    }
    val userName = remember { VrchatAuthManager.getStoredDisplayName(ctx) }
    val discordIcon = ImageVector.vectorResource(com.vrca.R.drawable.ic_discord)
    val icons = remember(discordIcon) {
        mapOf(
            Row.Device to BootIcons.DeviceSession, Row.Terms to BootIcons.Terms, Row.Account to BootIcons.Account,
            Row.VrchatSignIn to BootIcons.VrchatSignIn, Row.Discord to discordIcon,
            Row.VrchatHere to BootIcons.VrchatHere, Row.Updates to BootIcons.Updates
        )
    }
    fun set(row: Row, state: BootState, note: BootNote? = null) { rows[row.ordinal] = RowState(state, note) }

    // Internet: re-read every second so plugging in / dropping Wi-Fi shows straight away.
    LaunchedEffect(Unit) {
        while (true) {
            val (link, mbps) = internetStatus(ctx)
            status = status.copy(internet = link, internetMbps = mbps)
            delay(1000)
        }
    }
    // VRChat's own status page.
    LaunchedEffect(opening) { if (opening) status = status.copy(vrchat = fetchVrchatPlatform()) }

    LaunchedEffect(attempt, opening) {
        if (!opening) return@LaunchedEffect
        error = null
        retryIn = null
        tutorialDone = false
        Row.entries.forEach { set(it, BootState.Waiting) }
        coroutineScope {
            // Rows that don't need the device session start straight away.
            launch { vrchatSignIn(ctx, { vrchatStatus() }) { s, n -> set(Row.VrchatSignIn, s, n) } }
            launch { discord(ctx) { s, n -> set(Row.Discord, s, n) } }
            if (Row.VrchatHere.inThisBuild) launch { vrchatHere { s, n -> set(Row.VrchatHere, s, n) } }
            val worker = async(Dispatchers.IO) { AvatarGlobalDb.fetchHealth() != null }

            set(Row.Device, BootState.Working)
            val deviceOk = orNull { kotlinx.coroutines.withTimeout(30_000L) { bootstrapFirebaseAndCache(ctx) } } != null
            launch {
                val workerOk = worker.await()
                status = status.copy(
                    servers = when {
                        deviceOk && workerOk -> ServiceState.Online
                        deviceOk || workerOk -> ServiceState.Issues
                        else -> ServiceState.Down
                    }
                )
            }
            if (!deviceOk) {
                set(Row.Device, BootState.Failed, BootNote.CantConnect)
                error = "Can't connect"
                return@coroutineScope
            }
            set(Row.Device, BootState.Done, BootNote.Connected)

            // Everything that needs the device session, side by side.
            val prefs = ctx.getSharedPreferences("vrca_remote", Context.MODE_PRIVATE)
            val deviceHash = prefs.getString("device_id_hash", "").orEmpty()
            val authUid = prefs.getString("auth_uid", "").orEmpty()
            val terms = launch { terms(ctx) { s, n -> set(Row.Terms, s, n) } }
            val account = launch {
                set(Row.Account, BootState.Working)
                val result = orNull { VrchatBanChecker.checkPhase1(deviceHash, authUid) }
                if (result == null) {
                    set(Row.Account, BootState.Warn, BootNote.CantConnect)
                } else {
                    BootResults.banId = result.banId
                    BootResults.banChecked = true
                    // A ban is shown by the ban gate after boot; here it's just noted.
                    if (result.isBanned) set(Row.Account, BootState.Warn, BootNote.Restricted)
                    else set(Row.Account, BootState.Done, BootNote.Good)
                }
            }
            val updates = if (!Row.Updates.inThisBuild) null else launch {
                set(Row.Updates, BootState.Working)
                val r = withTimeoutOrNull(10_000L) { checkFirestoreRelease(BuildConfig.VERSION_CODE, deviceHash) }
                BootResults.release = r?.takeIf { it !is ReleaseCheckResult.Failed }
                when (r) {
                    ReleaseCheckResult.UpToDate -> set(Row.Updates, BootState.Done, BootNote.UpToDate)
                    is ReleaseCheckResult.UpdateAvailable -> set(Row.Updates, BootState.Warn, BootNote.UpdateReady)
                    null -> set(Row.Updates, BootState.Warn, BootNote.TimedOut)
                    else -> set(Row.Updates, BootState.Warn, BootNote.CantConnect)
                }
            }
            // A new user's first open: fetch the tutorial's pictures while the screen is up.
            val tutorial = launch {
                if (!VrchatAuthManager.isLoggedIn(ctx) && !com.vrca.ui.onboarding.OnboardingPrefs.isComplete(ctx)) {
                    withTimeoutOrNull(TUTORIAL_PREFETCH_CAP_MS) { com.vrca.ui.onboarding.TutorialImageStore.ensureDownloaded(ctx) }
                }
            }
            terms.join(); account.join(); updates?.join(); tutorial.join()
            tutorialDone = true
        }
    }

    // After signing in from the boot screen, check the VRChat session again.
    LaunchedEffect(signInAttempt) {
        if (signInAttempt == 0) return@LaunchedEffect
        vrchatSignIn(ctx, { vrchatStatus() }) { s, n -> set(Row.VrchatSignIn, s, n) }
    }

    // A failed device session retries itself after a short countdown.
    LaunchedEffect(error, attempt) {
        if (error == null) return@LaunchedEffect
        for (s in 5 downTo 1) { retryIn = s; delay(1000) }
        retryIn = null
        attempt++
    }

    // Hand off once the blocking rows are done AND the screen has fully opened. The header's Ready
    // sweep only plays when EVERY row has settled, so the other rows get a short grace first; one
    // still going after it (Discord connecting, the log not found yet) carries on in the background,
    // which counts as settled. The hold then runs from that moment, so the sweep always plays in full
    // instead of being skipped or cut off by the fold.
    var exiting by remember { mutableStateOf(false) }
    LaunchedEffect(blockingDone, shown) {
        if (blockingDone && shown) {
            val allSettled = withTimeoutOrNull(NON_BLOCKING_GRACE_MS) {
                snapshotFlow { ROWS.all { rows[it.ordinal].state in SETTLED } }.first { it }
            } != null
            if (!allSettled) ROWS.filter { rows[it.ordinal].state !in SETTLED }
                .forEach { set(it, BootState.Background, rows[it.ordinal].note) }
            delay(READY_HOLD_MS)
            exiting = true
        }
    }
    // The logo only flies into the top bar when the main app is what opens next; a terms, sign-in,
    // first-run or ban screen comes first otherwise, so it just fades out there.
    // (Headset only: the phone/admin top bar shows the name in words, not the logo.)
    val toMainApp = exiting && BuildConfig.IS_HEADSET_BUILD && rows[Row.Terms.ordinal].state == BootState.Done &&
        rows[Row.Account.ordinal].note != BootNote.Restricted &&
        VrchatAuthManager.isLoggedIn(ctx) && com.vrca.ui.onboarding.OnboardingPrefs.isComplete(ctx)

    androidx.compose.foundation.layout.Box {
    BootScreen(
        checks = ROWS.map { r ->
            val s = rows[r.ordinal]
            BootCheck(r.label, s.state, s.note, r.blocking, icons[r])
        },
        status = status,
        error = error,
        retryInSec = retryIn,
        onRetry = { attempt++ },
        onContinue = if (everSignedIn) ({ exiting = true }) else null,
        userName = userName,
        onOpening = { opening = true },
        onShown = { shown = true },
        exit = exiting,
        exitToTopBar = toMainApp,
        onExited = { onDone(toMainApp) },
        onSignIn = if (signInNeeded && error == null) ({ showLogin = true }) else null
    )
    // Sign in right here, over the boot screen (which stays underneath, so it doesn't replay).
    if (showLogin) {
        androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize(), color = com.vrca.ui.theme.GremlinColors.Backdrop) {
            com.vrca.vrchat.VrchatLoginScreen(
                pendingBanId = BootResults.banId,
                onCancel = { showLogin = false }
            ) { _, _ ->
                showLogin = false
                signInAttempt++
            }
        }
    }
    }
}

/**
 * [block]'s result, or null if it threw or timed out. Unlike runCatching it lets a real
 * cancellation through (a Retry restarts the checks by cancelling the old run).
 */
private suspend fun <T> orNull(block: suspend () -> T): T? = try {
    block()
} catch (e: kotlinx.coroutines.TimeoutCancellationException) {
    null
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Throwable) {
    null
}

/** How long Ready stays up before the app opens (covers the Ready sweep). */
private const val READY_HOLD_MS = 900L
private const val NON_BLOCKING_GRACE_MS = 2_500L

/**
 * VRChat sign-in. Boot needs a working VRChat session (user rule), with one exception: when
 * VRChat itself is having problems AND this headset has a saved session, boot goes on (amber
 * "VRChat issues") since signing in again wouldn't help. Any other failure means the session is
 * gone (expired, signed out) and the row asks to sign in, which holds boot until they do.
 *  - First run (tutorial not done): skipped; the tutorial's own sign-in step handles it.
 *  - The saved session is re-read for a few seconds first: on a cold start the Keystore can make
 *    it read as missing for a moment (see VrcaApp's vrcLoginDone re-check).
 *  - 401: a silent re-login with the saved password is tried before asking.
 *  - The live VRChat link is started once the session is good; boot doesn't wait for it.
 */
private suspend fun vrchatSignIn(ctx: Context, vrchatStatus: suspend () -> ServiceState, set: (BootState, BootNote?) -> Unit) {
    if (!com.vrca.ui.onboarding.OnboardingPrefs.isComplete(ctx)) { set(BootState.Skipped, BootNote.SignInNeeded); return }
    set(BootState.Working, null)
    val saved = withTimeoutOrNull(4_000L) {
        while (!VrchatAuthManager.isLoggedIn(ctx)) delay(400)
        true
    } == true
    if (!saved) { set(BootState.Failed, BootNote.SignInNeeded); return }
    var v = orNull { VrchatAuthManager.validateSessionDetailed(ctx) } ?: VrchatAuthManager.SessionValidity.UNKNOWN
    repeat(2) {
        if (v == VrchatAuthManager.SessionValidity.UNKNOWN) {
            delay(1_500L)
            v = orNull { VrchatAuthManager.validateSessionDetailed(ctx) } ?: VrchatAuthManager.SessionValidity.UNKNOWN
        }
    }
    when (v) {
        VrchatAuthManager.SessionValidity.VALID -> {
            set(BootState.Done, BootNote.SignedIn); startPipelineWarmup(ctx)
        }
        VrchatAuthManager.SessionValidity.UNAUTHORIZED ->
            if (orNull { VrchatAuthManager.autoRelogin(ctx) } == true) {
                set(BootState.Done, BootNote.SignedIn); startPipelineWarmup(ctx)
            } else {
                set(BootState.Failed, BootNote.SignInNeeded)
            }
        VrchatAuthManager.SessionValidity.UNKNOWN ->
            if (vrchatStatus() != ServiceState.Online) {
                set(BootState.Warn, BootNote.VrchatIssues); startPipelineWarmup(ctx)
            } else {
                set(BootState.Failed, BootNote.SignInNeeded)
            }
    }
}

/** States that let boot go on. */
private val SETTLED = setOf(BootState.Done, BootState.Warn, BootState.Skipped, BootState.Background)

/** Discord: skipped ("Off") when the Discord status link isn't turned on; otherwise follows it. */
private suspend fun discord(ctx: Context, set: (BootState, BootNote?) -> Unit) {
    val repo = (ctx.applicationContext as VrcaApplication).userPreferencesRepository
    if (!repo.discordRpcEnabled.first()) { set(BootState.Skipped, BootNote.Off); return }
    set(BootState.Working, BootNote.Connecting)
    val end = withTimeoutOrNull(12_000L) {
        DiscordRpcState.statusFlow.first {
            it == DiscordRpcStatus.CONNECTED || it == DiscordRpcStatus.SESSION_EXPIRED || it == DiscordRpcStatus.FAILED
        }
    }
    when (end) {
        DiscordRpcStatus.CONNECTED -> set(BootState.Done, BootNote.Connected)
        DiscordRpcStatus.SESSION_EXPIRED -> set(BootState.Warn, BootNote.SignInNeeded)
        DiscordRpcStatus.FAILED -> set(BootState.Warn, BootNote.CantConnect)
        else -> set(BootState.Background, BootNote.Connecting)
    }
}

/** VRChat on this headset, from the log reader (started at app launch on the headset build). */
private suspend fun vrchatHere(set: (BootState, BootNote?) -> Unit) {
    set(BootState.Working, null)
    // Give the reader a moment to find the log on a cold start.
    val ui = withTimeoutOrNull(2_500L) {
        InstanceRosterManager.flow.first {
            it.status == InstanceRosterManager.Status.LIVE || it.status == InstanceRosterManager.Status.IDLE
        }
    } ?: InstanceRosterManager.flow.value
    when (ui.status) {
        InstanceRosterManager.Status.LIVE, InstanceRosterManager.Status.IDLE -> set(BootState.Done, BootNote.Found)
        InstanceRosterManager.Status.NO_LOG -> set(BootState.Skipped, BootNote.NotOpen)
        InstanceRosterManager.Status.NEEDS_PERMISSION -> set(BootState.Skipped, BootNote.NoAccess)
    }
}

/**
 * Terms of Service: one `config/app` read (the terms version the admin last set) against what this
 * headset accepted. New terms don't fail boot; the terms screen after boot asks for them.
 */
private suspend fun terms(ctx: Context, set: (BootState, BootNote?) -> Unit) {
    set(BootState.Working, null)
    val snap = withTimeoutOrNull(8_000L) {
        orNull { FirebaseFirestore.getInstance().collection("config").document("app").get().await() }
    }
    if (snap == null) { set(BootState.Warn, BootNote.CantConnect); return }
    val version = (snap.getLong("tosVersion") ?: 1L).toInt().coerceAtLeast(1)
    val updatedAt = snap.getTimestamp("updatedAt")?.toDate()?.time ?: 0L
    val accepted = TosPrefs.acceptedVersion(ctx) >= version && TosPrefs.acceptedAtMs(ctx) >= updatedAt
    if (accepted) set(BootState.Done, BootNote.Accepted) else set(BootState.Warn, BootNote.NewTerms)
}

/** VRChat's public status page, as one of the Status row's fixed words. */
private suspend fun fetchVrchatPlatform(): ServiceState = withContext(Dispatchers.IO) {
    try {
        val conn = java.net.URL("https://status.vrchat.com/api/v2/summary.json").openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 6000; conn.readTimeout = 6000; conn.useCaches = false
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val json = org.json.JSONObject(body)
        val inMaintenance = json.optJSONArray("scheduled_maintenances")?.let { list ->
            (0 until list.length()).any { list.optJSONObject(it)?.optString("status") == "in_progress" }
        } ?: false
        when (json.optJSONObject("status")?.optString("indicator", "none")) {
            null, "", "none" -> if (inMaintenance) ServiceState.Maintenance else ServiceState.Online
            "minor" -> ServiceState.Issues
            "maintenance" -> ServiceState.Maintenance
            else -> ServiceState.Down
        }
    } catch (_: Throwable) {
        ServiceState.Issues
    }
}

/**
 * The hand-off from the headset boot screen to the app: for a moment after boot the boot backdrop
 * stays on top of the app and fades away, so the app fades in rather than cutting in. When the logo
 * flew into the top bar, a copy of it holds there (the app's own top-bar logo is right underneath
 * in the same spot) until the fade is done, so it never blinks.
 */
internal object BootReveal {
    val active = mutableStateOf(false)
    var withLogo = false
        private set
    fun start(withLogo: Boolean) { this.withLogo = withLogo; active.value = true }
    fun end() { active.value = false }
}

@Composable
internal fun BootRevealOverlay() {
    if (!BootReveal.active.value) return
    val fade = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(Unit) {
        fade.animateTo(0f, androidx.compose.animation.core.tween(REVEAL_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        BootReveal.end()
    }
    androidx.compose.foundation.layout.Box(
        androidx.compose.ui.Modifier.fillMaxSize()
            .background(com.vrca.ui.theme.GremlinColors.Backdrop.copy(alpha = fade.value))
            // Swallow taps while it's up (it's only ~half a second).
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
    ) {
        if (BootReveal.withLogo) {
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(com.vrca.R.drawable.vrca_logo_vector), null,
                modifier = androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.TopCenter)
                    .padding(top = 4.dp).height(56.dp)
            )
        }
    }
}

private const val REVEAL_MS = 650
