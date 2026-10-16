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
 * What the headset boot found, handed to [VrcaApp] so the gates after boot reuse it instead of
 * repeating the same reads (the ban check, the release check).
 */
internal object HeadsetBootResults {
    /** True once Account standing ran; [banId] is then the phase-1 ban id (null = clean). */
    @Volatile var banChecked = false
    @Volatile var banId: String? = null
    /** The Updates row's release check, or null if it didn't finish. */
    @Volatile var release: ReleaseCheckResult? = null
}

/** The checklist rows, in screen order (dependency order: the device session comes first). */
private enum class Row(val label: String, val blocking: Boolean) {
    Device("Device session", true),
    Terms("Terms of Service", true),
    Account("Account standing", true),
    VrchatSignIn("VRChat sign-in", false),
    Discord("Discord", false),
    VrchatHere("VRChat on this headset", false),
    Updates("Updates", true)
}

private data class RowState(val state: BootState = BootState.Waiting, val note: BootNote? = null)

/**
 * The headset's boot: every check starts at once (Terms of Service and Account standing wait for
 * the device session, which they need), each row shows where its check is, and boot hands off when
 * the blocking rows (device session, terms, account, updates) have finished, the screen has fully
 * opened and the Ready sweep has played. The other rows can still be finishing when it opens.
 *
 * A failed device session shows Retry (counting down to an automatic retry) and, when this headset
 * has signed in before, Continue anyway. Nothing here writes; reads are the ones boot already did
 * (sign-in, ban check, release check) plus one `config/app` read for the terms row.
 */
@Composable
internal fun HeadsetBoot(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val rows = remember { mutableStateListOf(*Array(Row.entries.size) { RowState() }) }
    var status by remember { mutableStateOf(BootStatus()) }
    var attempt by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryIn by remember { mutableStateOf<Int?>(null) }
    var shown by remember { mutableStateOf(false) }
    var blockingDone by remember { mutableStateOf(false) }
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
    LaunchedEffect(Unit) { status = status.copy(vrchat = fetchVrchatPlatform()) }

    LaunchedEffect(attempt) {
        error = null
        retryIn = null
        blockingDone = false
        Row.entries.forEach { set(it, BootState.Waiting) }
        coroutineScope {
            // Rows that don't need the device session start straight away.
            launch { vrchatSignIn(ctx) { s, n -> set(Row.VrchatSignIn, s, n) } }
            launch { discord(ctx) { s, n -> set(Row.Discord, s, n) } }
            launch { vrchatHere { s, n -> set(Row.VrchatHere, s, n) } }
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
                    HeadsetBootResults.banId = result.banId
                    HeadsetBootResults.banChecked = true
                    // A ban is shown by the ban gate after boot; here it's just noted.
                    if (result.isBanned) set(Row.Account, BootState.Warn, BootNote.Restricted)
                    else set(Row.Account, BootState.Done, BootNote.Good)
                }
            }
            val updates = launch {
                set(Row.Updates, BootState.Working)
                val r = withTimeoutOrNull(10_000L) { checkFirestoreRelease(BuildConfig.VERSION_CODE, deviceHash) }
                HeadsetBootResults.release = r?.takeIf { it !is ReleaseCheckResult.Failed }
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
            terms.join(); account.join(); updates.join(); tutorial.join()
            blockingDone = true
        }
    }

    // A failed device session retries itself after a short countdown.
    LaunchedEffect(error, attempt) {
        if (error == null) return@LaunchedEffect
        for (s in 5 downTo 1) { retryIn = s; delay(1000) }
        retryIn = null
        attempt++
    }

    // Hand off once the blocking rows are done AND the screen has fully opened, after the Ready
    // sweep has had time to play (so the ticks are seen landing, even on a fast boot).
    var exiting by remember { mutableStateOf(false) }
    LaunchedEffect(blockingDone, shown) {
        if (blockingDone && shown) {
            delay(READY_HOLD_MS)
            exiting = true
        }
    }
    // The logo only flies into the top bar when the main app is what opens next; a terms, sign-in,
    // first-run or ban screen comes first otherwise, so it just fades out there.
    val toMainApp = exiting && rows[Row.Terms.ordinal].state == BootState.Done &&
        rows[Row.Account.ordinal].note != BootNote.Restricted &&
        VrchatAuthManager.isLoggedIn(ctx) && com.vrca.ui.onboarding.OnboardingPrefs.isComplete(ctx)

    BootScreen(
        checks = Row.entries.map { r ->
            val s = rows[r.ordinal]
            BootCheck(r.label, s.state, s.note, r.blocking, icons[r])
        },
        status = status,
        error = error,
        retryInSec = retryIn,
        onRetry = { attempt++ },
        onContinue = if (everSignedIn) ({ exiting = true }) else null,
        userName = userName,
        onShown = { shown = true },
        exit = exiting,
        exitToTopBar = toMainApp,
        onExited = onDone
    )
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

/** VRChat sign-in: not signed in is skipped (the login screen follows); otherwise start the live
 *  link and wait for it. It doesn't hold boot up: if it's slow it keeps going in the app. */
private suspend fun vrchatSignIn(ctx: Context, set: (BootState, BootNote?) -> Unit) {
    if (!VrchatAuthManager.isLoggedIn(ctx)) { set(BootState.Skipped, BootNote.SignInNeeded); return }
    set(BootState.Working, null)
    startPipelineWarmup(ctx)
    val up = withTimeoutOrNull(12_000L) { VrchatPipelineState.isConnectedFlow.first { it } }
    if (up != null) set(BootState.Done, BootNote.SignedIn) else set(BootState.Background, BootNote.Connecting)
}

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
