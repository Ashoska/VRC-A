package com.vrca.app

import androidx.compose.ui.draw.drawBehind
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
 * The app's boot (every build): once the intro has finished every check starts (Terms of Service,
 * Account standing and Updates wait for the device session, which they need), each row shows where
 * its check is, and boot hands off when every row has its answer and the blocking rows (device
 * session, terms, account, VRChat sign-in, updates) passed, after the Ready sweep.
 *
 * A blocking check that couldn't reach the server holds boot on Retry (counting down to an
 * automatic retry); there is no "Continue anyway" (user rule: they're needed for moderation and to
 * know the device is really connected). Nothing here writes; reads are the ones boot already did
 * (sign-in, ban check, release check) plus one `config/app` read for the terms row.
 */
@Composable
internal fun AppBoot(
    /** Build the app behind the boot screen now (it stays hidden until [onDone]). */
    prepareApp: () -> Unit = {},
    /** True once that app has been built and laid out. */
    appReady: Boolean = false,
    onDone: (logoInTopBar: Boolean) -> Unit
) {
    val ctx = LocalContext.current
    val rows = remember { mutableStateListOf(*Array(Row.entries.size) { RowState() }) }
    var status by remember { mutableStateOf(BootStatus()) }
    var attempt by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryIn by remember { mutableStateOf<Int?>(null) }
    // The checks wait until the intro has finished and every row is on screen (`onShown`). Starting
    // them as the panels began to unfold put the device-session setup and the Discord WebView on
    // the unfold itself: on the Quest (VRChat holding ~93% CPU) the panels froze for ~1 s and the
    // rows appeared already ticked (user video). Now the intro has the device to itself and every
    // row is seen going from Waiting to Working to its answer.
    var shown by remember { mutableStateOf(false) }
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
    // One-time main-thread setup, done BEFORE the first frame (in this first composition, while the
    // system's launch splash is still up) instead of when the checks start. In the user's Quest
    // video the checklist froze ~1.3 s right as the checks began, with VRChat holding ~93% CPU.
    // The costly first-in-process pieces on the main thread there: Firebase's first instances, and
    // Chromium loading for the first WebView (the Discord row starts its WebView). A throwaway
    // WebView loads Chromium now; the RPC's own WebView is then cheap to create.
    remember {
        runCatching { com.google.firebase.auth.FirebaseAuth.getInstance(); FirebaseFirestore.getInstance() }
        BootDiscordStart.sent = false   // once per boot run, not per process
        if (discordWillStart(ctx)) {
            runCatching { android.webkit.WebView(ctx.applicationContext).destroy() }
            // Discord starts HERE, at the very start of boot, not with the other checks: its hidden
            // WebView has to load the whole discord.com app (seconds) while every other check is one
            // quick request, so started with them it finished long after (user: "looks silly").
            // Started now, its load overlaps the launch splash + intro (~3 s head start).
            startDiscordRpc(ctx)
        }
        // The rows' spinner (an AnimatedVectorDrawable): its first load parses the XML and its
        // animators, ~0.2 s on the Quest the first time a row starts working (third user video).
        com.vrca.ui.common.preloadRenderThreadSpinner(ctx)
        true
    }
    // The shared watched name (rule: everything shown is watched state, ui-kit.md): it fills in
    // when the encrypted store opens in the background and changes on a boot-screen sign-in.
    val storedSession by VrchatAuthManager.session.collectAsState()
    val userName = storedSession.displayName.ifBlank { null }
    val discordVector = ImageVector.vectorResource(com.vrca.R.drawable.ic_discord)
    val icons = remember(discordVector) {
        val discordIcon = BootIcon(discordVector)
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
            // Off the main thread, and only written when it changed (a write rebuilds the Status card).
            val (link, mbps) = withContext(Dispatchers.IO) { internetStatus(ctx) }
            if (link != status.internet || mbps != status.internetMbps) status = status.copy(internet = link, internetMbps = mbps)
            delay(1000)
        }
    }
    // VRChat's own status page.
    // Fetch FIRST, then copy: `status.copy(vrchat = fetch())` reads `status` before the fetch
    // suspends and writes that stale copy back after it, wiping a servers/internet result that
    // landed meanwhile (the VRC-A servers row stuck on "Checking..." on the headset).
    LaunchedEffect(shown) {
        if (!shown) return@LaunchedEffect
        val vrchat = fetchVrchatPlatform()
        status = status.copy(vrchat = vrchat)
    }

    LaunchedEffect(attempt, shown) {
        if (!shown) return@LaunchedEffect
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
                    set(Row.Account, BootState.Failed, BootNote.CantConnect)
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
                    null -> set(Row.Updates, BootState.Failed, BootNote.TimedOut)
                    else -> set(Row.Updates, BootState.Failed, BootNote.CantConnect)
                }
            }
            // A new user's first open: fetch the tutorial's pictures while the screen is up.
            val tutorial = launch {
                val newUser = withContext(Dispatchers.IO) {
                    !VrchatAuthManager.isLoggedIn(ctx) && !com.vrca.ui.onboarding.OnboardingPrefs.isComplete(ctx)
                }
                if (newUser) {
                    withTimeoutOrNull(TUTORIAL_PREFETCH_CAP_MS) { com.vrca.ui.onboarding.TutorialImageStore.ensureDownloaded(ctx) }
                }
            }
            terms.join(); account.join(); updates?.join(); tutorial.join()
            tutorialDone = true
            // User rule: a blocking check that couldn't get an answer holds boot (no "Continue
            // anyway"): the device session, terms, account standing (moderation) and updates have
            // to actually reach the server. Same Retry pill + 5 s auto-retry as a failed device session.
            if (listOf(Row.Terms, Row.Account, Row.Updates).any { it.inThisBuild && rows[it.ordinal].state == BootState.Failed }) {
                error = "Can't connect"
            }
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

    // Hand off once the blocking rows are done AND the screen has fully opened AND every other row
    // has succeeded or failed too (user rule: non-blocking rows can't stop boot, but boot still
    // waits for their answer; each has its own timeout, Discord's being the longest at 25 s since
    // its hidden WebView has to load discord.com first). The hold runs from that moment, so the
    // header's Ready sweep always plays in full before the fold.
    //
    // Before the Ready sweep, when the main app is what opens next, the app is BUILT behind the
    // boot screen ([prepareApp], hidden, pop-ups held) and the sweep waits for it ([appReady]).
    // Its first build is the costly part of the hand-off (~2.4 s in the lab, the frozen panel
    // before Home in the user's video: the app's code isn't precompiled on a sideloaded install).
    // Done here, every row is settled and nothing on the panel is moving, so the hand-off after
    // it is just the fold and the fade over an app that's already there.
    var exiting by remember { mutableStateOf(false) }
    var celebrate by remember { mutableStateOf(false) }
    val appReadyNow by rememberUpdatedState(appReady)
    // The main app (not a terms, sign-in, first-run or ban screen) is what opens next. A function,
    // asked only when it's needed: as a value it re-read the saved VRChat login (encrypted store)
    // and onboarding prefs on EVERY AppBoot recomposition, i.e. on every row change.
    fun mainAppNext() = rows[Row.Terms.ordinal].state == BootState.Done &&
        rows[Row.Account.ordinal].note != BootNote.Restricted &&
        VrchatAuthManager.isLoggedIn(ctx) && com.vrca.ui.onboarding.OnboardingPrefs.isComplete(ctx)
    // Decided once, when the hand-off is about to start.
    var toMainApp by remember { mutableStateOf(false) }
    LaunchedEffect(blockingDone, shown) {
        if (blockingDone && shown) {
            snapshotFlow { ROWS.all { rows[it.ordinal].state in SETTLED } }.first { it }
            val mainNext = withContext(Dispatchers.IO) { mainAppNext() }
            if (mainNext) {
                prepareApp()
                withTimeoutOrNull(APP_BUILD_CAP_MS) { snapshotFlow { appReadyNow }.first { it } }
                // ...and DRAWN: its first draw (text, icons, images) is the other first-time cost.
                repeat(2) { androidx.compose.runtime.withFrameNanos { } }
            }
            celebrate = true
            delay(READY_HOLD_MS)
            toMainApp = BuildConfig.IS_HEADSET_BUILD && mainNext
            // The app shows through during the logo's glide (BootScreen): keep its own top-bar logo
            // hidden until the flying one lands on that spot, so there are never two.
            if (toMainApp) BootReveal.hideTopBarLogo.value = true
            exiting = true
        }
    }
    // The logo only flies into the top bar when the main app is what opens next; it just fades
    // out otherwise. (Headset only: the phone/admin top bar shows the name in words, not the logo.)

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
        userName = userName,
        onShown = { shown = true },
        celebrate = celebrate,
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
/** Longest the Ready sweep waits for the app to be built behind the boot screen. */
private const val APP_BUILD_CAP_MS = 6_000L

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
        while (!withContext(Dispatchers.IO) { VrchatAuthManager.isLoggedIn(ctx) }) delay(400)
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

/**
 * Starts the Discord RPC service once per boot run. The service only sets `isRunning` after its
 * WebView exists, and a second START before that re-runs its fresh start, rebuilding the WebView and
 * throwing away the page load in progress, so the boot screen never sends it twice.
 */
private object BootDiscordStart { @Volatile var sent = false }

private fun startDiscordRpc(ctx: Context) {
    if (BootDiscordStart.sent) return
    BootDiscordStart.sent = true
    runCatching {
        androidx.core.content.ContextCompat.startForegroundService(
            ctx, android.content.Intent(ctx, com.vrca.discord.DiscordRpcService::class.java)
                .setAction(com.vrca.discord.DiscordRpcService.ACTION_START)
        )
    }.onFailure { BootDiscordStart.sent = false }
}

/** Whether the Discord row will start the RPC service (and so its WebView) this boot. */
private fun discordWillStart(ctx: Context): Boolean {
    if (com.vrca.discord.DiscordRpcService.isRunning) return false
    val repo = (ctx.applicationContext as VrcaApplication).userPreferencesRepository
    return kotlinx.coroutines.runBlocking {
        withTimeoutOrNull(500L) { repo.discordRpcEnabled.first() && repo.discordSessionSeeded.first() }
    } == true
}

/** Discord: skipped ("Off") when the Discord status link isn't turned on; otherwise follows it. */
private suspend fun discord(ctx: Context, set: (BootState, BootNote?) -> Unit) {
    val repo = (ctx.applicationContext as VrcaApplication).userPreferencesRepository
    if (!repo.discordRpcEnabled.first()) { set(BootState.Skipped, BootNote.Off); return }
    set(BootState.Working, BootNote.Connecting)
    // Normally already started at the very start of boot (see AppBoot's first composition); this
    // covers a retry, or prefs that weren't readable then. Not via the VRChat pipeline's onOpen
    // auto-start, which would be seconds later on a cold boot.
    if (repo.discordSessionSeeded.first() && !com.vrca.discord.DiscordRpcService.isRunning) startDiscordRpc(ctx)
    val end = withTimeoutOrNull(25_000L) {
        DiscordRpcState.statusFlow.first {
            it == DiscordRpcStatus.CONNECTED || it == DiscordRpcStatus.SESSION_EXPIRED || it == DiscordRpcStatus.FAILED
        }
    }
    if (end == DiscordRpcStatus.CONNECTED) {
        // Hold the tick until the hidden page has drawn its first picture (~0.4 s main-thread
        // freeze on the Quest), so that freeze happens here, while this row's RenderThread
        // spinner turns, and not during the hand-off to Home (user's recording: Home popped in).
        // Capped: a page that isn't on screen may never report it.
        val t0 = android.os.SystemClock.uptimeMillis()
        val drawn = withTimeoutOrNull(DISCORD_PICTURE_CAP_MS) { DiscordRpcState.pageDrawnFlow.first { it } } != null
        MainThreadWatch.note(ctx, "discord first picture: " +
            if (drawn) "${android.os.SystemClock.uptimeMillis() - t0} ms after connected" else "not reported within ${DISCORD_PICTURE_CAP_MS} ms")
    }
    when (end) {
        DiscordRpcStatus.CONNECTED -> set(BootState.Done, BootNote.Connected)
        DiscordRpcStatus.SESSION_EXPIRED -> set(BootState.Warn, BootNote.SignInNeeded)
        DiscordRpcStatus.FAILED -> set(BootState.Warn, BootNote.CantConnect)
        else -> set(BootState.Warn, BootNote.TimedOut)
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
    if (snap == null) { set(BootState.Failed, BootNote.CantConnect); return }
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
    /** True while the boot screen's logo flies into the top bar over the already-visible app. */
    val hideTopBarLogo = mutableStateOf(false)
    private val logo = mutableStateOf(false)
    val withLogo: Boolean get() = logo.value
    fun start(withLogo: Boolean) { logo.value = withLogo; active.value = true }
    fun end() { active.value = false }
}

@Composable
internal fun BootRevealOverlay() {
    if (!BootReveal.active.value) return
    val fade = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(Unit) {
        // Moves from its first frame (LinearOutSlowIn): FastOutSlowIn's slow start, after the boot
        // screen's own exit, read as ~0.5 s of plain dark panel before Home (user's recordings).
        fade.animateTo(0f, androidx.compose.animation.core.tween(REVEAL_MS, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
        BootReveal.end()
    }
    androidx.compose.foundation.layout.Box(
        androidx.compose.ui.Modifier.fillMaxSize()
            // Fade read while drawing: repaints, doesn't rebuild (ui-kit.md performance rules).
            .drawBehind { drawRect(com.vrca.ui.theme.GremlinColors.Backdrop.copy(alpha = fade.value)) }
            // Swallow taps while it's up (it's only ~half a second).
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
    ) {
        if (BootReveal.withLogo) {
            androidx.compose.foundation.Image(
                com.vrca.ui.common.appLogoPainter(), null,
                modifier = androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.TopCenter)
                    .padding(top = 4.dp).height(56.dp)
            )
        }
    }
}

private const val DISCORD_PICTURE_CAP_MS = 1_500L
private const val REVEAL_MS = 450
