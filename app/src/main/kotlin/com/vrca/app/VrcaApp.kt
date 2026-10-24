package com.vrca.app

import com.vrca.ui.theme.GremlinColors
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.vrca.BuildConfig
import com.vrca.ui.screen.VrcaScreen
import com.vrca.ui.viewmodel.VrcaViewModel
import com.vrca.update.ReleaseCheckResult
import com.vrca.update.ReleaseInfo
import com.vrca.update.checkFirestoreRelease
import com.vrca.richcontent.RichDocRenderer
import com.vrca.richcontent.RichMediaStore
import com.vrca.richcontent.WhatsNewStore
import com.vrca.richcontent.resolveRichDoc
import com.vrca.vrchat.VrchatAuthManager
import com.vrca.vrchat.VrchatBanChecker
import com.vrca.vrchat.VrchatPipelineService
import com.vrca.vrchat.VrchatPipelineState
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.withTimeoutOrNull
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Divider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * VrcaApp
 *
 * Responsibilities:
 *  - crash gate
 *  - bootstrap gate
 *  - anonymous auth (no login UI)
 *  - caches uid + deviceHash in SharedPreferences ("vrca_remote")
 *  - SAFE public write to:
 *      - users/{deviceHash}  (canonical self doc)
 *      - usersById/{uid}     (mapping uid -> deviceHash)
 */
@Composable
fun VrcaApp() {
    // The boot screen's last beat (BootReveal) draws on top of the app as it appears.
    androidx.compose.foundation.layout.Box {
        VrcaAppContent()
        BootRevealOverlay()
    }
}

@Composable
private fun VrcaAppContent() {
    val ctx = LocalContext.current

    /* -------------------------
       Crash gate
       ------------------------- */

    // A crash report waiting means this is a safe start (CrashReport): nothing optional has
    // started, so the crash screen always gets to show. "Start VRC-A" ends it and boot goes on.
    if (CrashReport.safeStart) {
        // Read at launch, before any UI (CrashReport.checkAtLaunch).
        CrashScreen(CrashReport.screenReport, CrashReport.screenHistory, onStart = { CrashReport.release(ctx) })
        // Send the crash to the developer now, not only after Start VRC-A (a crash was never reported when
        // the app was closed on this screen). The sign-in is kept from the last run, so the boot isn't needed.
        LaunchedEffect(Unit) { CrashUpload.sendPending(ctx) }
        // A newer version may already fix it (user, 2026-10-23): the update popup over the crash screen.
        // One read of the release docs; the app's own check runs again after Start VRC-A.
        if (!BuildConfig.IS_ADMIN_BUILD) {
            var update by remember { mutableStateOf<ReleaseCheckResult.UpdateAvailable?>(null) }
            LaunchedEffect(Unit) {
                val hash = ctx.getSharedPreferences("vrca_remote", Context.MODE_PRIVATE).getString("device_id_hash", "").orEmpty()
                update = runCatching { checkFirestoreRelease(BuildConfig.VERSION_CODE, hash) }.getOrNull() as? ReleaseCheckResult.UpdateAvailable
            }
            // Updates can't be skipped (user): always the forced popup, no Later.
            update?.let { UpdatePopup(it.info, forced = true, visible = true, onDismiss = {}) }
        }
        return
    }

    /* -------------------------
       Bootstrap gate
       ------------------------- */

    // Boot-screen gate: show it ONLY on a genuine, deliberate fresh start — a clean
    // SWIPE-reopen — or when bootstrap has genuinely never run (first install / data
    // cleared, i.e. no cached anon auth uid). EVERY other relaunch (OEM/force kill,
    // Activity recreate, headless revival) is a recovery and drops straight into the
    // app with no boot screen. This mirrors how the Quick Toggles / feature-session
    // restore decide "clean swipe vs something else": the swipe flag (captured in
    // MainActivity before it's cleared) is the same marker FeatureSessionStore keys
    // on. `!everBootstrapped` keeps first install safe — bootstrap MUST run there to
    // sign in anonymously + cache the auth uid the ban check reads; on any prior-used
    // app the uid persists in prefs, so a swipe-less relaunch can safely skip boot.
    val openedFromSwipe = remember { VrcaApplication.openedFromSwipe }
    val openedFromBoot = remember { VrcaApplication.openedFromBoot }
    val everBootstrapped = remember {
        ctx.getSharedPreferences("vrca_remote", Context.MODE_PRIVATE)
            .getString("auth_uid", "").orEmpty().isNotBlank()
    }
    // The first open after an app update shows it too (an update kills the process, so the
    // next open would otherwise drop straight in). The version that last finished boot is
    // stored below; a missing value on a prior-used install counts as "just updated" once.
    val bootPrefs = remember { ctx.getSharedPreferences("vrca_remote", Context.MODE_PRIVATE) }
    val justUpdated = remember { bootPrefs.getInt(LAST_BOOT_VERSION_KEY, 0) != BuildConfig.VERSION_CODE }
    // The first open since a device restart is a genuine cold start → show the boot
    // screen too (not just swipe-reopen / first install).
    val warmResume = remember { !(openedFromSwipe || openedFromBoot || !everBootstrapped || justUpdated) }

    val bootOkState = remember { mutableStateOf(warmResume) }
    var bootOk by bootOkState
    LaunchedEffect(bootOk) {
        if (bootOk) bootPrefs.edit().putInt(LAST_BOOT_VERSION_KEY, BuildConfig.VERSION_CODE).apply()
    }
    // The boot screen (BootScreen.kt, run by AppBoot.kt) does every boot check itself, side by
    // side: the device session, terms, the ban check, the VRChat session, updates and the rest.
    // Once they're all in and the main app opens next, AppBoot has the app BUILT behind it
    // ([prepared]: covered by the opaque boot screen, untouchable, dialogs held) before its Ready sweep, so the costly first
    // build happens while nothing on the boot screen moves, not during the hand-off (the frozen
    // panel before Home in the user's video). Same call site before and after boot, so the built
    // app is kept, not rebuilt (its listeners attach once).
    var prepared by remember { mutableStateOf(false) }
    var appReady by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        if (bootOk || prepared) {
            // Drawn for real while prepared (NOT alpha 0, which skipped its first draw and moved
            // that cost onto the reveal: ~0.8 s of empty panel in the user's video). The boot
            // screen above is opaque, so it stays unseen until the boot screen goes.
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().onPlaced { appReady = true }) {
                // The flag goes in as State, not a value: passed as a value, boot finishing changed
                // a parameter of the already-built app and recomposed all of it (its ViewModel isn't a
                // stable type, so nothing could be skipped). That was the ~0.8 s freeze right after the
                // logo reached the top bar in the user's third video.
                VrcaAfterBoot(warmResume = warmResume, bootOk = bootOkState)
            }
        }
        if (!bootOk) {
            AppBoot(
                prepareApp = { com.vrca.ui.common.PanelDialogHold.held.value = true; prepared = true },
                appReady = appReady,
                onDone = { toTopBar ->
                    com.vrca.ui.common.PanelDialogHold.held.value = false
                    if (toTopBar) {
                        // The app already showed through during the logo's glide; the logo has
                        // landed on the top bar's own logo, which can show again.
                        BootReveal.hideTopBarLogo.value = false
                    } else {
                        // Otherwise the app shows underneath and a backdrop fades off it (no hard cut).
                        BootReveal.start(withLogo = false)
                    }
                    bootOk = true
                }
            )
        }
    }
}

/** Everything after the boot gate: ban, terms, onboarding and update gates, then the app itself. */
@Composable
private fun VrcaAfterBoot(warmResume: Boolean, bootOk: androidx.compose.runtime.State<Boolean>) {
    val ctx = LocalContext.current

    /* -------------------------
       Phase 1 ban check (device hash + auth UID)
       Does NOT show the ban screen yet - waits for VRChat login so we can capture the
       alt VRChat ID first. A boot that just ran already did it (reused, no second read);
       on a warm resume (no boot screen) it runs here without holding the UI.
       ------------------------- */

    val booted = !warmResume
    var phase1BanId by remember { mutableStateOf(if (booted) BootResults.banId else null) }
    var phase1Checked by remember { mutableStateOf(booted && BootResults.banChecked) }

    LaunchedEffect(Unit) {
        // Read only here, so boot finishing doesn't recompose this whole tree.
        snapshotFlow { bootOk.value }.first { it }
        if (phase1Checked) return@LaunchedEffect
        val prefs = ctx.getSharedPreferences("vrca_remote", Context.MODE_PRIVATE)
        val deviceHash = prefs.getString("device_id_hash", "") ?: ""
        val authUid    = prefs.getString("auth_uid", "") ?: ""
        if (deviceHash.isNotBlank()) {
            val result = VrchatBanChecker.checkPhase1(deviceHash, authUid)
            phase1BanId = result.banId  // null if clean
        }
        phase1Checked = true
    }

    /* -------------------------
       ToS gate (Firestore-backed, admin-configurable)
       Must be accepted before the VRChat login is shown.
       Reads tosVersion/tosText/tosUrl from Firestore config/app.
       Re-shows when admin bumps the version or updates the text.
       ------------------------- */

    val scope = rememberCoroutineScope()
    val remotePrefs = remember { ctx.getSharedPreferences("vrca_remote", Context.MODE_PRIVATE) }
    val deviceHash = remember { remotePrefs.getString("device_id_hash", "") ?: "" }

    var remoteTosVersion by remember { mutableStateOf(1) }
    var remoteTosText by remember { mutableStateOf("") }
    var remoteTosUrl by remember { mutableStateOf("") }
    var remoteTosUpdatedAtMs by remember { mutableStateOf(0L) }
    // Discord community invite rides the SAME config/app listener (it's the same doc)
    // instead of a second one-shot get() in VrcaScreen — one read, and now live: an
    // admin editing the link updates the button without a relaunch.
    var remoteDiscordInvite by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        val reg = FirebaseFirestore.getInstance()
            .collection("config").document("app")
            .addSnapshotListener { snap, _ ->
                if (snap != null && snap.exists()) {
                    remoteTosVersion = (snap.getLong("tosVersion") ?: 1L).toInt().coerceAtLeast(1)
                    remoteTosText = snap.getString("tosText") ?: ""
                    remoteTosUrl = snap.getString("tosUrl") ?: ""
                    // The terms' own change time (admin stamps it only when the version, text or link
                    // changes). NOT the doc's updatedAt: every Config-tab save stamps that, and keying
                    // on it re-asked everyone for the terms when the admin flipped any setting.
                    remoteTosUpdatedAtMs = snap.getTimestamp("tosUpdatedAt")?.toDate()?.time ?: 0L
                    remoteDiscordInvite = (snap.getString("discordInvite") ?: "").trim()
                    // Global owner VRChat id → local exemption so friend-list
                    // notifications about the admin are never surfaced (self-invite dance).
                    (snap.getString("ownerVrchatId") ?: "").trim().let {
                        if (it.isNotBlank()) com.vrca.vrchat.SelfInviteStore.setOwnerVrchatId(ctx, it)
                    }
                }
            }
        onDispose { reg.remove() }
    }

    val requiredTosVersion = remoteTosVersion.coerceAtLeast(1)

    var tosAccepted by rememberSaveable {
        mutableStateOf(
            TosPrefs.acceptedVersion(ctx) >= requiredTosVersion &&
                TosPrefs.acceptedAtMs(ctx) >= remoteTosUpdatedAtMs
        )
    }

    LaunchedEffect(requiredTosVersion, remoteTosUpdatedAtMs) {
        tosAccepted =
            TosPrefs.acceptedVersion(ctx) >= requiredTosVersion &&
                TosPrefs.acceptedAtMs(ctx) >= remoteTosUpdatedAtMs
    }

    /* -------------------------
       Update check (public build only).
       Runs BEFORE the onboarding gate so the forced-update dialog renders on
       top of the tutorial too — a brand-new user should install the latest
       version before setting anything up.
       ------------------------- */

    // Scope the runtime ViewModel to the Application (process lifetime), NOT this
    // Activity. This is what keeps the chatbox senders, moderation/kill listener,
    // NowPlaying consumer and Firestore sync loops running while the app is
    // backgrounded and the Activity is destroyed. It is torn down only by
    // AppShutdown on a real swipe (which calls viewModelStore.clear()).
    val appOwner = ctx.applicationContext as VrcaApplication
    val vm: VrcaViewModel = viewModel(
        viewModelStoreOwner = appOwner,
        factory = VrcaViewModel.Factory
    )

    // Whether VRC-A's screen is showing: voice to text that Android stopped (by closing
    // VRC-A) comes back then, since a mic can only be started while VRC-A is on screen.
    val screenLifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(screenLifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> vm.setAppOnScreen(true)
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> vm.setAppOnScreen(false)
                else -> {}
            }
        }
        screenLifecycle.lifecycle.addObserver(obs)
        onDispose { screenLifecycle.lifecycle.removeObserver(obs); vm.setAppOnScreen(false) }
    }

    var releaseCheckResult by remember { mutableStateOf<ReleaseCheckResult?>(null) }
    var updateDismissed    by remember { mutableStateOf(false) }

    if (!BuildConfig.IS_ADMIN_BUILD) {
        val prefs = remember { ctx.getSharedPreferences("vrca_remote", Context.MODE_PRIVATE) }
        val deviceHash = remember { prefs.getString("device_id_hash", "") ?: "" }
        // The version each release SOURCE last surfaced, so that when the admin RETRACTS
        // (deletes) a release doc we can drop the popup if it's currently showing THAT
        // release — instead of leaving the bad update on screen until an app restart.
        var lastTargetedCode by remember { mutableStateOf(0L) }
        var lastGlobalCode by remember { mutableStateOf(0L) }

        LaunchedEffect(Unit) {
            // The headset boot's Updates row already asked; reuse that answer.
            // Taken once: a later Activity re-create must ask again, not reuse a stale answer.
            releaseCheckResult = BootResults.takeRelease()
                ?: checkFirestoreRelease(BuildConfig.VERSION_CODE, deviceHash)
        }

        // Real-time detection: snapshot listener on releases/{deviceHash}
        if (deviceHash.isNotBlank()) {
            DisposableEffect(deviceHash) {
                val releaseDocRef = FirebaseFirestore.getInstance()
                    .collection("releases").document(deviceHash)
                val reg = releaseDocRef
                    .addSnapshotListener { snap, _ ->
                        if (snap == null || !snap.exists()) {
                            // Doc gone (admin RETRACTED it, or the self-delete below): if the
                            // popup is currently showing THIS targeted release, drop it and
                            // lift the forced-OSC gate right now — don't wait for a restart.
                            val cur = releaseCheckResult as? ReleaseCheckResult.UpdateAvailable
                            if (cur != null && lastTargetedCode > 0L && cur.info.versionCode == lastTargetedCode) {
                                releaseCheckResult = ReleaseCheckResult.UpToDate
                            }
                            lastTargetedCode = 0L
                            return@addSnapshotListener
                        }
                        val url = snap.getString("downloadUrl").orEmpty()
                        if (url.isBlank()) return@addSnapshotListener
                        // Variant guard: a release APK for a different app package
                        // (headset vs mobile) must never force-install here.
                        if (com.vrca.update.releaseTargetsOtherVariant(
                                snap.getString("packageName").orEmpty())) return@addSnapshotListener
                        val code = snap.getLong("versionCode") ?: return@addSnapshotListener
                        if (code <= BuildConfig.VERSION_CODE) {
                            // The user is already on (or past) this targeted version —
                            // they installed the directed update. Retract the per-device
                            // release doc to save space; it has served its purpose.
                            releaseDocRef.delete()
                            return@addSnapshotListener
                        }
                        val info = ReleaseInfo(
                            versionCode     = code,
                            versionName     = snap.getString("versionName").orEmpty(),
                            downloadUrl     = url,
                            requiredMinCode = snap.getLong("requiredMinCode") ?: 0L,
                            notes           = snap.getString("notes").orEmpty(),
                            bodyDoc         = snap.getString("bodyDoc").orEmpty()
                        )
                        // All directed releases are forced automatically.
                        lastTargetedCode = code
                        releaseCheckResult = ReleaseCheckResult.UpdateAvailable(
                            info, forced = true
                        )
                        updateDismissed = false
                    }
                onDispose { reg.remove() }
            }
        }

        // Live GLOBAL "release to all" detection: foreground-scoped snapshot listener on
        // releases/latest (attaches while the app is open, detaches on background — so it
        // only ever bills the currently-open users when you actually publish, and 0 while
        // closed). Complements the 6h background poll (which still catches an update pushed
        // while backgrounded). Never DOWNGRADES a newer targeted release already shown.
        DisposableEffect(Unit) {
            // Variant-specific global doc: headset reads releases/latest_headset,
            // mobile reads releases/latest — so a headset "release to all" never
            // reaches mobile and vice-versa (Option B).
            val latestRef = FirebaseFirestore.getInstance()
                .collection("releases").document(com.vrca.update.globalReleaseDocId())
            val reg = latestRef.addSnapshotListener { snap, _ ->
                if (snap == null || !snap.exists()) {
                    // Admin RETRACTED the global release: if the popup is currently showing
                    // THIS global release, drop it (and lift the forced-OSC gate) now.
                    val cur = releaseCheckResult as? ReleaseCheckResult.UpdateAvailable
                    if (cur != null && lastGlobalCode > 0L && cur.info.versionCode == lastGlobalCode) {
                        releaseCheckResult = ReleaseCheckResult.UpToDate
                    }
                    lastGlobalCode = 0L
                    return@addSnapshotListener
                }
                val url = snap.getString("downloadUrl").orEmpty()
                if (url.isBlank()) return@addSnapshotListener
                // Variant guard (defense in depth; the doc id already separates the
                // fleets): never force-install a different-package APK.
                if (com.vrca.update.releaseTargetsOtherVariant(
                        snap.getString("packageName").orEmpty())) return@addSnapshotListener
                val code = snap.getLong("versionCode") ?: return@addSnapshotListener
                // Cache patch notes for the currently-installed version so Settings →
                // What's New can show them offline. Zero extra read — this listener
                // already holds the doc; only the doc describing THIS version matches.
                if (code == BuildConfig.VERSION_CODE.toLong()) {
                    WhatsNewStore.cache(
                        ctx, code,
                        snap.getString("versionName").orEmpty(),
                        snap.getString("bodyDoc").orEmpty(),
                        snap.getString("notes").orEmpty()
                    )
                }
                if (code <= BuildConfig.VERSION_CODE) return@addSnapshotListener
                val curCode = (releaseCheckResult as? ReleaseCheckResult.UpdateAvailable)?.info?.versionCode ?: 0L
                if (code <= curCode) return@addSnapshotListener
                lastGlobalCode = code
                releaseCheckResult = ReleaseCheckResult.UpdateAvailable(
                    ReleaseInfo(
                        versionCode = code,
                        versionName = snap.getString("versionName").orEmpty(),
                        downloadUrl = url,
                        requiredMinCode = snap.getLong("requiredMinCode") ?: 0L,
                        notes = snap.getString("notes").orEmpty(),
                        bodyDoc = snap.getString("bodyDoc").orEmpty()
                    ),
                    forced = true
                )
                updateDismissed = false
            }
            onDispose { reg.remove() }
        }

        // Backwards compat: real-time detection via targetedUpdateUrl on user doc
        val liveTargetedUrl = vm.targetedUpdateUrl
        val liveTargetedNotes = vm.targetedUpdateNotes
        LaunchedEffect(liveTargetedUrl) {
            if (liveTargetedUrl.isNotBlank()) {
                val info = ReleaseInfo(
                    versionCode = Long.MAX_VALUE,
                    versionName = "Targeted Update",
                    downloadUrl = liveTargetedUrl,
                    requiredMinCode = 0L,
                    notes = liveTargetedNotes
                )
                releaseCheckResult = ReleaseCheckResult.UpdateAvailable(info, forced = true)
                updateDismissed = false
            }
        }

        // Hard-gate OSC output whenever a forced (required) update is pending, so
        // the user can't keep driving the VRChat chatbox — even backgrounded —
        // until they update. The flag lives on the app-scoped VM so it survives
        // Activity destruction. Cleared if the pending update ever resolves.
        val forcedUpdatePending =
            (releaseCheckResult as? ReleaseCheckResult.UpdateAvailable)?.forced == true
        LaunchedEffect(forcedUpdatePending) {
            vm.applyForceUpdateGate(forcedUpdatePending)
        }
    }

    val updateToShow = releaseCheckResult
    if (!BuildConfig.IS_ADMIN_BUILD && updateToShow is ReleaseCheckResult.UpdateAvailable) {
        // Stays composed while hidden ("Later"), so a download already running still opens the installer.
        UpdatePopup(updateToShow.info, updateToShow.forced, visible = !updateDismissed || updateToShow.forced, onDismiss = { updateDismissed = true })
    }

    /* -------------------------
       First-open onboarding tutorial (docs/ui-revamp.md).
       Pre-seeded complete for existing installs (any prior ToS acceptance)
       so current users never see it. Replayable from Settings.
       ------------------------- */

    var onboardingDone by remember {
        mutableStateOf(
            run {
                val complete = com.vrca.ui.onboarding.OnboardingPrefs.isComplete(ctx)
                // Existing-user pre-seed: an install that accepted ANY ToS version
                // BEFORE the tutorial existed is an upgrade — mark complete so it
                // never shows. But ONLY when the tutorial was never started: once
                // started, accepting the ToS in step 1 sets acceptedVersion > 0, and
                // a crash/close mid-tutorial must RESUME the tutorial, not be mistaken
                // for an existing install. Replay leaves the started flag untouched.
                val existingUser = !complete &&
                    !com.vrca.ui.onboarding.OnboardingPrefs.wasStarted(ctx) &&
                    TosPrefs.acceptedVersion(ctx) > 0
                if (existingUser) com.vrca.ui.onboarding.OnboardingPrefs.markComplete(ctx)
                complete || existingUser
            }
        )
    }
    val onboardingReplay by com.vrca.ui.onboarding.OnboardingState.replayRequested

    if (!onboardingDone || onboardingReplay) {
        com.vrca.ui.onboarding.OnboardingFlow(
            vm = vm,
            tosVersion = requiredTosVersion,
            tosText = remoteTosText,
            tosUrl = remoteTosUrl,
            tosAlreadyAccepted = tosAccepted,
            phase1BanId = phase1BanId,
            replay = onboardingReplay,
            onTosAccepted = {
                TosPrefs.accept(ctx, requiredTosVersion)
                tosAccepted = true
                scope.launch {
                    runCatching {
                        val data = hashMapOf(
                            "tosAcceptedVersion" to requiredTosVersion,
                            "tosAcceptedAt" to Timestamp.now(),
                            "updatedAt" to Timestamp.now()
                        )
                        if (deviceHash.isNotBlank()) {
                            FirebaseFirestore.getInstance()
                                .collection("users").document(deviceHash)
                                .set(data, SetOptions.merge())
                                .await()
                        }
                    }
                }
            },
            onFinish = {
                onboardingDone = true
                com.vrca.ui.onboarding.OnboardingState.replayRequested.value = false
                // Tutorial done (first-run completion AND replay exit both land
                // here) — free the on-demand images. A new user mid-tutorial never
                // reaches this, so their images survive a close/kill and resume works.
                com.vrca.ui.onboarding.TutorialImageStore.clear(ctx)
            }
        )
        return
    }

    if (!tosAccepted) {
        TosGate(
            tosVersion = requiredTosVersion,
            tosText = remoteTosText,
            tosUrl = remoteTosUrl,
            onOpenUrl = { url ->
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            },
            onAccept = {
                TosPrefs.accept(ctx, requiredTosVersion)
                tosAccepted = true

                scope.launch {
                    runCatching {
                        val data = hashMapOf(
                            "tosAcceptedVersion" to requiredTosVersion,
                            "tosAcceptedAt" to Timestamp.now(),
                            "updatedAt" to Timestamp.now()
                        )
                        if (deviceHash.isNotBlank()) {
                            FirebaseFirestore.getInstance()
                                .collection("users").document(deviceHash)
                                .set(data, SetOptions.merge())
                                .await()
                        }
                    }
                }
            }
        )
        return
    }

    /* -------------------------
       VRChat login state (NO forced login screen — see below).
       After a login: runs Phase 2 ban check, starts pipeline service.
       ------------------------- */

    var vrcLoginDone    by remember { mutableStateOf(VrchatAuthManager.isLoggedIn(ctx)) }
    var isBannedPhase2  by remember { mutableStateOf(false) }
    var banPhase2Reason by remember { mutableStateOf("") }
    var phase2Checking  by remember { mutableStateOf(false) }
    var reloginTick     by remember { mutableStateOf(0) }

    // There is NO forced login screen anymore (the only hard login gate is the
    // onboarding tutorial's login step on first run). Being signed out — via a
    // Settings sign-out OR a cold start while logged out — just hard-blocks OSC
    // through VrcaViewModel's auth gate; the app stays fully usable and the user
    // signs back in from Settings → Accounts or the VRChat tab. Each successful
    // re-login re-runs the Phase-2 ban check below (the new account could be a
    // banned identity) and starts the pipeline.
    LaunchedEffect(Unit) {
        VrchatAuthManager.loggedInSignal.collect {
            vrcLoginDone = true
            reloginTick++
        }
    }

    // Cold-start Keystore race (Quest especially, where every reopen is a fresh
    // process): on a cold start the Android Keystore is frequently NOT ready when
    // this composes, so VrchatAuthManager.isLoggedIn() — which reads
    // EncryptedSharedPreferences — returns FALSE TRANSIENTLY even though the VRChat
    // session is perfectly valid (documented in VrchatAuthManager.getPrefs). That
    // left `vrcLoginDone` seeded false with nothing to ever flip it true (only a
    // fresh loggedInSignal does, and no re-login happens), so
    // runPhase2AndStartPipeline never ran → the VRChat pipeline never started. The
    // VM's OSC-gate self-heal waits for a pipeline CONNECTION, so it could never
    // fire either — the chatbox showed "Sending" but transmitted nothing until a
    // full reopen (a process whose Keystore happened to be ready). Re-check for a
    // few seconds and flip `vrcLoginDone` true the moment the session reads back;
    // that starts the pipeline, which lets the existing gate self-heal clear the
    // block. A genuinely logged-out user never reads true, so nothing starts.
    // Not bumped through reloginTick — it's the SAME account, just a delayed read.
    LaunchedEffect(Unit) {
        var attempts = 0
        while (!vrcLoginDone && attempts < 20) {
            kotlinx.coroutines.delay(750)
            attempts++
            if (withContext(Dispatchers.IO) { VrchatAuthManager.isLoggedIn(ctx) }) vrcLoginDone = true
        }
    }

    // If VRC login succeeded but Phase 2 hasn't run yet (first run after login).
    // Also keyed on reloginTick so a Settings re-login (possibly a DIFFERENT
    // VRChat account) re-runs the ban check + pipeline restart.
    LaunchedEffect(vrcLoginDone, reloginTick) {
        if (!vrcLoginDone || phase2Checking) return@LaunchedEffect
        phase2Checking = true
        runPhase2AndStartPipeline(
            ctx, phase1BanId,
            onBanned = { reason -> isBannedPhase2 = true; banPhase2Reason = reason },
            onClean  = { phase2Checking = false }
        )
    }

    if (isBannedPhase2) {
        BannedScreen(reason = banPhase2Reason)
        return
    }

    /* -------------------------
       Main app
       ------------------------- */

    VrcaScreen(chatboxViewModel = vm, discordInvite = remoteDiscordInvite)
}

/**
 * Update dialog (redesigned — docs/ui-revamp.md "Update dialog").
 * Patch notes live in a SCROLLABLE area capped below half the screen so
 * arbitrarily long release notes never clip the dialog off-screen (the old
 * fixed block clipped long notes). Notes render through the same styled-block
 * parser as the ToS, so "- bullet" lines get real bullets. The forced variant
 * (the current default — ALL releases are forced) shows a single full-width
 * Download action; "Later" exists only for the legacy non-forced path.
 */
@Composable
internal fun UpdateDialog(
    info: ReleaseInfo,
    forced: Boolean,
    downloading: Boolean = false,
    /** Download progress 0..1 (null = size not known yet), shown by the downloading Boot Cat. */
    progress: Float? = null,
    /** The download finished: Boot Cat lands into his Done face just before the installer opens. */
    landed: Boolean = false,
    error: String? = null,
    onDismiss: () -> Unit,
    onDownload: (String) -> Unit
) {
    // Rich patch notes (Phase 2): prefer bodyDoc JSON, fall back to legacy `notes`.
    val doc = remember(info.bodyDoc, info.notes) { resolveRichDoc(info.bodyDoc, info.notes) }
    // Update-media lifecycle: pull referenced media while the popup is shown, cull
    // it the moment the dialog leaves the composition (save space).
    val dialogMediaCtx = LocalContext.current
    DisposableEffect(doc) {
        doc?.mediaUrls()?.forEach {
            RichMediaStore.ensureCachedAsync(dialogMediaCtx, it, RichMediaStore.Scope.UPDATE)
        }
        onDispose { RichMediaStore.clearUpdateMedia(dialogMediaCtx) }
    }

    com.vrca.ui.common.PanelDialog(
        onDismissRequest = { if (!forced) onDismiss() },
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !forced,
            dismissOnClickOutside = !forced
        )
    ) {
        // Full-screen sheet (small inset so the rounded corners still read as a card).
        ElevatedCard(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            colors = androidx.compose.material3.CardDefaults.elevatedCardColors(
                // The 1 dp elevation tint Material 3 1.1 put on a `surface` card (1.4 doesn't).
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header: icon + title + version pill
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.SystemUpdate,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Text(
                        if (forced) "Update Required" else "Update Available",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    ) {
                        Text(
                            info.versionName,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // (The "Update Required" title already states this — no redundant line.)

                // Rich patch notes — takes ALL remaining vertical space (weight) so the
                // notes area is large and scrolls; block order gives the "hero image on
                // top" look naturally. Empty state still pushes the actions to the bottom.
                if (doc != null) {
                    Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        tonalElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    ) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp)
                        ) {
                            RichDocRenderer(doc, mediaScope = RichMediaStore.Scope.UPDATE)
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }

                // Error message
                if (error != null) {
                    Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Text(
                            error,
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }

                // Progress indicator
                if (downloading) {
                    androidx.compose.material3.LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth()
                            .height(4.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                    )
                }

                // Actions pinned at the bottom: the Download button and the browser
                // fallback link sit tight together (a compact clickable Text instead of a
                // TextButton, which reserves a 48dp min-height and left a weird big gap).
                val dialogCtx = LocalContext.current
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (forced) {
                        com.vrca.ui.common.GremlinButton(
                            if (downloading) "Downloading" + (progress?.let { " ${(it * 100).toInt()}%" } ?: "")
                            else if (error != null) "Retry Download" else "Download Update",
                            onClick = { if (!downloading) onDownload(info.downloadUrl) },
                            modifier = Modifier.fillMaxWidth(),
                            bootIcon = BootIcons.Updates, downloading = downloading, downloadProgress = progress, downloaded = landed
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                        ) {
                            com.vrca.ui.common.GremlinSecondaryButton(onClick = onDismiss) { Text("Later") }
                            com.vrca.ui.common.GremlinButton(
                                if (downloading) "Downloading" + (progress?.let { " ${(it * 100).toInt()}%" } ?: "")
                                else if (error != null) "Retry" else "Download",
                                onClick = { if (!downloading) onDownload(info.downloadUrl) },
                                bootIcon = BootIcons.Updates, downloading = downloading, downloadProgress = progress, downloaded = landed
                            )
                        }
                    }

                    Text(
                        "Click here if download failed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                runCatching {
                                    dialogCtx.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                            .padding(vertical = 6.dp)
                    )
                }
            }
        }
    }
}

/* =========================================================
   BOOTSTRAP
   ========================================================= */

private const val REMOTE_PREFS_FILE = "vrca_remote"

/** vrca_remote pref: the app version that last got past boot (the boot screen shows once per update). */
private const val LAST_BOOT_VERSION_KEY = "last_boot_version_code"

/** Cap on the new-user boot-time prefetch of the on-demand OSC tutorial images —
 *  a slow / no network never hangs boot; the tutorial step downloads any that
 *  missed with its own spinner. */
internal const val TUTORIAL_PREFETCH_CAP_MS = 8_000L

/** Per-attempt timeout for the cold-start anonymous sign-in (and the bootstrap
 *  Firestore writes). On a fresh first boot signInAnonymously()/the first
 *  Firestore write often HANGS (Play Services / network / token warming) rather
 *  than throwing; without a per-attempt bound one hung call consumed the whole
 *  boot timeout so the retry loop never cycled — "Device session" failed and a
 *  manual Retry (now warm) worked instantly. Bounding each attempt lets the loop
 *  abandon a hung call and retry within the boot window, self-healing the blip. */
private const val ANON_AUTH_ATTEMPT_MS = 6_000L

/** Start the VRChat pipeline foreground service to begin warming presence during
 *  the Device-session boot stage (only called when already logged in). The app
 *  is foregrounded here so the FGS start is allowed; the service itself uses
 *  startForegroundSafely, and a duplicate ACTION_START from the normal post-login
 *  flow is coalesced — so this can't double-start or crash. */
internal fun startPipelineWarmup(ctx: Context) {
    val deviceHash = ctx.getSharedPreferences("vrca_remote", Context.MODE_PRIVATE)
        .getString("device_id_hash", "") ?: ""
    val intent = Intent(ctx, VrchatPipelineService::class.java).apply {
        action = VrchatPipelineService.ACTION_START
        putExtra(VrchatPipelineService.EXTRA_DEVICE_HASH, deviceHash)
    }
    runCatching { ContextCompat.startForegroundService(ctx, intent) }
}

private object RemoteKeys {
    // AdminScreen reads these from prefs (keep these key names stable!)
    const val DEVICE_ID_HASH = "device_id_hash"
    const val AUTH_UID = "auth_uid"

    // Used if ANDROID_ID is unavailable (NOT reinstall-stable)
    const val DEVICE_FALLBACK_RANDOM = "device_id_hash_fallback_random"
}

/**
 * Boot steps:
 *  1) anonymous auth
 *  2) ensure deviceHash exists
 *  3) cache uid + deviceHash locally
 *  4) SAFE public write:
 *      - users/{deviceHash} (canonical)
 *      - usersById/{uid} (mapping)
 *
 * No writes to devices/{deviceHash} here.
 */
internal suspend fun bootstrapFirebaseAndCache(ctx: Context) {
    val auth = FirebaseAuth.getInstance()
    if (auth.currentUser == null) {
        // Anonymous sign-in is the ONE hard network dependency of boot — the
        // rest of bootstrap is runCatching/non-fatal. On a brand-new install's
        // FIRST open the network + Firebase stack is often still cold, so a
        // single attempt fails transiently and the user sees "Device session"
        // error until they reopen (by which point it's warm). Retry a few times
        // with short exponential backoff so a cold-start blip self-heals inside
        // the boot window instead of surfacing a manual-retry error. Well within
        // the 20s outer withTimeout (worst case ~400+800+1600+3000 ≈ 5.8s).
        var lastErr: Throwable? = null
        var delayMs = 400L
        var attempt = 0
        while (auth.currentUser == null && attempt < 4) {
            try {
                // Per-attempt timeout: a cold-start signInAnonymously() often HANGS
                // (not throws). withTimeoutOrNull abandons a hung attempt (cancelling
                // the await) so the loop cycles to the next one — which succeeds once
                // Play Services / network is warm. Without this the first hung call
                // ate the whole outer boot timeout and the retry never ran.
                val ok = withTimeoutOrNull(ANON_AUTH_ATTEMPT_MS) {
                    auth.signInAnonymously().await()
                    true
                }
                if (ok != true && auth.currentUser == null) {
                    lastErr = lastErr ?: IllegalStateException("anon auth attempt timed out")
                }
            } catch (t: Throwable) {
                lastErr = t
            }
            if (auth.currentUser == null && attempt < 3) {
                kotlinx.coroutines.delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(3000L)
            }
            attempt++
        }
        if (auth.currentUser == null) {
            throw lastErr ?: IllegalStateException("Anonymous auth returned null user")
        }
    }
    val uid = auth.currentUser?.uid ?: error("Anonymous auth returned null user")

    val deviceHash = ensureDeviceHash(ctx)

    // Cache for AdminScreen + other screens
    ctx.getSharedPreferences(REMOTE_PREFS_FILE, Context.MODE_PRIVATE)
        .edit()
        .putString(RemoteKeys.AUTH_UID, uid)
        .putString(RemoteKeys.DEVICE_ID_HASH, deviceHash)
        .apply()

    val db = FirebaseFirestore.getInstance()

    // \u2705 SAFE public write to canonical self doc: users/{deviceHash}
    // Keep keys strictly within selfMutableKeys() and consistent with rules:
    // - uid/authUid/currentUid == request.auth.uid
    // - deviceHash/docId == document id (deviceHash)
    val safeUser = hashMapOf<String, Any>(
        // identity/debug
        "docId" to deviceHash,
        "docIdType" to "deviceHash",
        "authUid" to uid,
        "uid" to uid,
        "currentUid" to uid,
        "deviceHash" to deviceHash,

        // activity markers
        "lastSeenAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp(),

        // app identity
        "appId" to BuildConfig.APPLICATION_ID,
        "adminBuild" to BuildConfig.IS_ADMIN_BUILD,
        "versionName" to BuildConfig.VERSION_NAME,
        "versionCode" to BuildConfig.VERSION_CODE
    )

    // \u2705 SAFE public write to mapping doc: usersById/{uid}
    // Must match your rules: keys only [deviceHash, authUid, appId, adminBuild, updatedAt]
    val safeLink = hashMapOf<String, Any>(
        "deviceHash" to deviceHash,
        "authUid" to uid,
        "appId" to BuildConfig.APPLICATION_ID,
        "adminBuild" to BuildConfig.IS_ADMIN_BUILD,
        "updatedAt" to FieldValue.serverTimestamp()
    )

    // Admin build must not appear in the public user directory.
    if (!BuildConfig.IS_ADMIN_BUILD) {
        // Throttle the bootstrap liveness write: the VM cold-open write also
        // writes lastSeenAt/updatedAt seconds later, so this is redundant when
        // a recent write already landed. Skip when the last real self-sync
        // was within 20 min (matches the VM's COLD_OPEN_LIVENESS_THROTTLE_MS).
        val lastSyncMs = ctx.getSharedPreferences("vrca_remote", Context.MODE_PRIVATE)
            .getLong("last_self_sync_ms", 0L)
        val recentWrite = System.currentTimeMillis() - lastSyncMs < 20L * 60L * 1000L

        // Both writes are non-critical for boot (the VM cold-open write repeats
        // them seconds later) — but an un-bounded .await() on a cold Firestore
        // connection can HANG and eat the outer boot timeout even inside
        // runCatching (which catches throws, not hangs). Bound each so a cold-start
        // stall can't fail boot after auth already succeeded.
        if (!recentWrite) {
            runCatching {
                withTimeoutOrNull(ANON_AUTH_ATTEMPT_MS) {
                    db.collection("users").document(deviceHash)
                        .set(safeUser, SetOptions.merge())
                        .await()
                }
            }
        }

        runCatching {
            withTimeoutOrNull(ANON_AUTH_ATTEMPT_MS) {
                db.collection("usersById").document(uid)
                    .set(safeLink, SetOptions.merge())
                    .await()
            }
        }
    }
}

/* =========================================================
   Device hash (matches MainActivity v2 logic)
   ========================================================= */

/**
 * Reads existing device hash from prefs if present.
 * If missing (e.g. unusual boot order), compute it using v2:
 *   SHA-256("v2:<ANDROID_ID>:<SIGNING_CERT_SHA256>")
 * Fallback is stored random (NOT reinstall-stable).
 */
private fun ensureDeviceHash(ctx: Context): String {
    val prefs = ctx.getSharedPreferences(REMOTE_PREFS_FILE, Context.MODE_PRIVATE)

    val existing = prefs.getString(RemoteKeys.DEVICE_ID_HASH, "")?.trim().orEmpty()
    if (existing.isNotBlank()) return existing

    val androidId = runCatching {
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
            ?.trim()
            .orEmpty()
    }.getOrDefault("")

    val signingDigest = runCatching { signingCertSha256Hex(ctx) }.getOrDefault("")

    val computedStable = if (androidId.isNotBlank() && signingDigest.isNotBlank()) {
        sha256Hex("v2:$androidId:$signingDigest")
    } else if (androidId.isNotBlank()) {
        sha256Hex("v2:$androidId:no_signing")
    } else {
        ""
    }

    val finalHash = if (computedStable.isNotBlank()) {
        computedStable
    } else {
        val fallbackExisting = prefs.getString(RemoteKeys.DEVICE_FALLBACK_RANDOM, "")?.trim().orEmpty()
        if (fallbackExisting.isNotBlank()) fallbackExisting
        else {
            val r = secureRandomHex(32)
            prefs.edit().putString(RemoteKeys.DEVICE_FALLBACK_RANDOM, r).apply()
            r
        }
    }

    prefs.edit().putString(RemoteKeys.DEVICE_ID_HASH, finalHash).apply()
    return finalHash
}

private fun signingCertSha256Hex(ctx: Context): String {
    val pm = ctx.packageManager
    val pkg = ctx.packageName

    val certBytes: ByteArray = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray() ?: byteArrayOf()
        } else {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()?.toByteArray() ?: byteArrayOf()
        }
    } catch (_: Throwable) {
        byteArrayOf()
    }

    if (certBytes.isEmpty()) return ""
    return sha256HexBytes(certBytes)
}

private fun sha256Hex(input: String): String =
    sha256HexBytes(input.toByteArray(Charsets.UTF_8))

private fun sha256HexBytes(bytes: ByteArray): String {
    val md = MessageDigest.getInstance("SHA-256")
    val digest = md.digest(bytes)
    val sb = StringBuilder(digest.size * 2)
    for (b in digest) {
        val v = b.toInt() and 0xff
        if (v < 16) sb.append('0')
        sb.append(v.toString(16))
    }
    return sb.toString()
}

private fun secureRandomHex(numBytes: Int): String {
    val rng = SecureRandom()
    val bytes = ByteArray(numBytes)
    rng.nextBytes(bytes)
    return sha256HexBytes(bytes)
}

/* =========================================================
   UI: Bootstrap + Crash
   ========================================================= */

/**
 * Strip backend-implementation names from USER-VISIBLE error/debug text.
 * Exception messages and class names would otherwise reveal which backend the
 * app runs on, which is information an attacker can use \u2014 every string that
 * reaches the screen (boot errors, crash text, debug panel) routes through
 * this. Logs (Logcat) keep the raw text for real debugging.
 */
internal fun sanitizeBackendRefs(text: String): String = text
    // Fully-qualified class/package paths first (most specific).
    .replace(Regex("""com\.google\.(firebase|android\.gms)[.\w$]*"""), "backend")
    // Then any remaining brand tokens, e.g. "FirebaseFirestoreException:
    // PERMISSION_DENIED" -> "server: PERMISSION_DENIED".
    .replace(Regex("""Fire(base|store)\w*""", RegexOption.IGNORE_CASE), "server")

/* =========================================================
   VRChat Phase-2 ban check + pipeline start
   Runs after VRChat login succeeds.
   ========================================================= */

private suspend fun runPhase2AndStartPipeline(
    ctx: Context,
    pendingBanId: String?,
    onBanned: (reason: String) -> Unit,
    onClean: () -> Unit
) {
    val prefs      = ctx.getSharedPreferences("vrca_remote", Context.MODE_PRIVATE)
    val deviceHash = prefs.getString("device_id_hash", "") ?: ""
    val authUid    = prefs.getString("auth_uid", "")       ?: ""
    val vrchatId   = VrchatAuthManager.getStoredUserId(ctx) ?: ""
    val vrcName    = VrchatAuthManager.getStoredDisplayName(ctx) ?: ""

    if (vrchatId.isNotBlank()) {
        val result = VrchatBanChecker.checkPhase2(
            vrchatId       = vrchatId,
            vrchatDisplayName = vrcName,
            deviceHash     = deviceHash,
            authUid        = authUid,
            pendingBanId   = pendingBanId
        )
        if (result.isBanned) {
            onBanned(result.banReason)
            return
        }

        // Write VRChat identity to Firestore user doc
        if (deviceHash.isNotBlank()) {
            runCatching {
                FirebaseFirestore.getInstance()
                    .collection("users").document(deviceHash)
                    .set(
                        mapOf(
                            "vrchatUserId"      to vrchatId,
                            "vrchatDisplayName" to vrcName,
                            "updatedAt"         to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    ).await()
            }
        }
    }

    // Start pipeline service
    val serviceIntent = Intent(ctx, VrchatPipelineService::class.java).apply {
        action = VrchatPipelineService.ACTION_START
        putExtra(VrchatPipelineService.EXTRA_DEVICE_HASH, deviceHash)
    }
    ContextCompat.startForegroundService(ctx, serviceIntent)

    onClean()
}

/* =========================================================
   Ban screen
   ========================================================= */

@Composable
internal fun BannedScreen(reason: String) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Access Denied",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Your account has been banned from VRC-A.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (reason.isNotBlank()) {
                        Divider()
                        Text(
                            "Reason: $reason",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

internal object TosPrefs {
    private const val FILE = "vrca_tos"
    private const val KEY_ACCEPTED_VERSION = "accepted_version"
    private const val KEY_ACCEPTED_AT_MS = "accepted_at_ms"

    fun acceptedVersion(ctx: Context): Int =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY_ACCEPTED_VERSION, 0)

    fun acceptedAtMs(ctx: Context): Long =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getLong(KEY_ACCEPTED_AT_MS, 0L)

    fun accept(ctx: Context, version: Int) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(KEY_ACCEPTED_VERSION, version.coerceAtLeast(1))
            .putLong(KEY_ACCEPTED_AT_MS, System.currentTimeMillis())
            .apply()
    }
}

/* =========================================================================
   ToS rendering (redesigned — docs/ui-revamp.md "ToS screen").

   The admin-configurable tosText is plain text with markdown-ish habits:
   numbered section headers ("3. Data & Privacy"), `*`/`-` bullets, and the
   occasional literal markdown link ("[url](url)") that used to render raw.
   parseTosBlocks() turns that into styled blocks; nothing about the stored
   text format changes, so existing Firestore content renders correctly.
   ========================================================================= */

private sealed class TosBlock {
    data class Header(val text: String) : TosBlock()
    data class Bullet(val text: String) : TosBlock()
    data class Paragraph(val text: String) : TosBlock()
}

/** Collapses literal markdown links "[x](y)" down to just the URL once. */
private fun sanitizeTosLine(line: String): String =
    Regex("""\[([^\]]*)]\(([^)]*)\)""").replace(line) { m ->
        m.groupValues[2].ifBlank { m.groupValues[1] }
    }

private fun parseTosBlocks(text: String): List<TosBlock> {
    val blocks = mutableListOf<TosBlock>()
    val para = StringBuilder()
    fun flushPara() {
        val p = para.toString().trim()
        if (p.isNotBlank()) blocks += TosBlock.Paragraph(p)
        para.clear()
    }
    text.lines().forEach { raw ->
        val line = sanitizeTosLine(raw.trim())
        when {
            line.isBlank() -> flushPara()
            // "1. Agreement" / "10. Contact" — numbered section headers
            Regex("""^\d+\.\s+\S""").containsMatchIn(line) -> {
                flushPara(); blocks += TosBlock.Header(line)
            }
            line.startsWith("* ") || line.startsWith("- ") -> {
                flushPara(); blocks += TosBlock.Bullet(line.drop(2).trim())
            }
            else -> {
                if (para.isNotEmpty()) para.append(' ')
                para.append(line)
            }
        }
    }
    flushPara()
    return blocks
}

/** Shared styled-text body for ToS sections and update-dialog patch notes. */
@Composable
private fun StyledBlocks(blocks: List<TosBlock>, warnHeaderKeywords: List<String> = emptyList()) {
    var inWarnSection = false
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is TosBlock.Header -> {
                    inWarnSection = warnHeaderKeywords.any { block.text.contains(it, ignoreCase = true) }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        block.text,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (inWarnSection) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary
                    )
                }
                is TosBlock.Bullet -> Row {
                    Text(
                        "•  ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        block.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is TosBlock.Paragraph -> Text(
                    block.text,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
internal fun TosGate(
    tosVersion: Int,
    tosText: String,
    tosUrl: String,
    onOpenUrl: (String) -> Unit,
    onAccept: () -> Unit
) {
    var checked by rememberSaveable { mutableStateOf(false) }

    val fallbackText = remember {
        """
TERMS OF SERVICE (SUMMARY)

By using this app, you agree to:
- Use it responsibly and legally
- Not use it to harass, spam, or impersonate others
- Understand VRChat chatbox limits apply and messages may be trimmed
- Accept that settings/history are stored locally on your device
- You may be moderated (warned/banned) for abuse

If you do not agree, close the app.
        """.trimIndent()
    }

    val blocks = remember(tosText) { parseTosBlocks(tosText.ifBlank { fallbackText }) }

    Surface {
        Column(Modifier.fillMaxSize()) {
            // Sticky header: title + version pill (never scrolls away)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Terms of Service", style = MaterialTheme.typography.headlineSmall)
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                ) {
                    Text(
                        "v$tosVersion",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Scrollable body
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ElevatedCard {
                    Column(Modifier.padding(14.dp)) {
                        StyledBlocks(
                            blocks,
                            warnHeaderKeywords = listOf("Risk", "Moderation", "Acceptable Use")
                        )
                    }
                }
                if (tosUrl.isNotBlank()) {
                    com.vrca.ui.common.GremlinSecondaryButton(
                        onClick = { onOpenUrl(tosUrl.trim()) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open full ToS link")
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            // Pinned accept bar (checkbox, not a settings-style switch)
            Surface(tonalElevation = 3.dp) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable { checked = !checked },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = checked,
                            onCheckedChange = { checked = it }
                        )
                        Text(
                            "I agree to the Terms of Service",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    com.vrca.ui.common.GremlinButton(
                        onClick = onAccept,
                        enabled = checked,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Accept & Continue")
                    }
                }
            }
        }
    }
}

/**
 * The update popup and its download (DownloadManager, real progress for the downloading Boot Cat,
 * the installer when it finishes). Shown by the app, and over the crash screen when a newer version
 * is out (the crash may already be fixed). [visible] false hides the dialog but keeps a running
 * download going.
 */
@Composable
internal fun UpdatePopup(info: ReleaseInfo, forced: Boolean, visible: Boolean, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var downloadId         by remember { mutableLongStateOf(-1L) }
    // The update download's progress (0..1, null = size not known yet), for the downloading Boot Cat.
    var downloadProgress   by remember { mutableStateOf<Float?>(null) }
    // Download finished: Boot Cat lands into Done for a moment before the installer opens.
    var downloadLanded     by remember { mutableStateOf(false) }
    var downloadDone       by remember { mutableStateOf(false) }
    var downloadError      by remember { mutableStateOf<String?>(null) }

    // Listen for DownloadManager completion
    if (downloadId >= 0L && !downloadDone) {
        DisposableEffect(downloadId) {
            val dm = ctx.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as DownloadManager
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: android.content.Context, intent: Intent) {
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                    if (id != downloadId) return
                    val query = DownloadManager.Query().setFilterById(id)
                    // dm.query() returns null on some OEM/download-provider states — the old
                    // unconditional cursor.moveToFirst() then NPE-crashed the app while an update
                    // download was pending. Null-guard + never let this receiver crash the process.
                    val cursor = try { dm.query(query) } catch (_: Throwable) { null } ?: return
                    if (cursor.moveToFirst()) {
                        val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val status = cursor.getInt(statusCol)

                        if (status == DownloadManager.STATUS_FAILED) {
                            val reasonCol = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                            val reason = cursor.getInt(reasonCol)
                            downloadError = when (reason) {
                                DownloadManager.ERROR_HTTP_DATA_ERROR,
                                DownloadManager.ERROR_UNHANDLED_HTTP_CODE ->
                                    "Download failed — the download URL may require authentication (is the GitHub repo private?)"
                                DownloadManager.ERROR_FILE_ERROR ->
                                    "Download failed — could not write file to storage"
                                else -> "Download failed (error code $reason)"
                            }
                            downloadDone = true
                            cursor.close()
                            return
                        }

                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            val localUriCol = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                            val localUri = cursor.getString(localUriCol)
                            if (localUri != null) {
                                val file = File(Uri.parse(localUri).path!!)
                                // GitHub private repos redirect unauthenticated requests
                                // to the login page — DownloadManager downloads the HTML
                                // and reports success. Catch it: real APKs are >100 KB.
                                if (file.length() < 100_000L) {
                                    downloadError = "Download failed — got a web page instead of an APK. The GitHub repo is likely private. Make the repo public or use a public download URL."
                                    downloadDone = true
                                    file.delete()
                                    cursor.close()
                                    return
                                }
                                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                    FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file
                                    )
                                } else {
                                    Uri.fromFile(file)
                                }
                                val install = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, "application/vnd.android.package-archive")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                // A missing FileProvider authority / no installer activity throws —
                                // surface it instead of crashing the app mid-update.
                                downloadLanded = true
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    runCatching { context.startActivity(install) }
                                        .onFailure { downloadError = "Couldn't open the installer. Tap the download link to install manually." }
                                    downloadDone = true
                                }, LANDED_HOLD_MS)
                            }
                        }
                    }
                    cursor.close()
                }
            }
            val registered = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ctx.registerReceiver(
                        receiver,
                        IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                        android.content.Context.RECEIVER_EXPORTED
                    )
                } else {
                    ctx.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
                }
            }.isSuccess
            // Only unregister what we registered — an unregister of a never-registered
            // receiver throws IllegalArgumentException and would crash on dispose.
            onDispose { if (registered) runCatching { ctx.unregisterReceiver(receiver) } }
        }
    }

    // Read the download's bytes from DownloadManager twice a second while it runs (off the main thread).
    LaunchedEffect(downloadId, downloadDone) {
        downloadProgress = null
        if (downloadId < 0L || downloadDone) return@LaunchedEffect
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return@LaunchedEffect
        while (true) {
            downloadProgress = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    dm.query(DownloadManager.Query().setFilterById(downloadId))?.use { c ->
                        if (!c.moveToFirst()) null else {
                            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                            if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
                        }
                    }
                }.getOrNull()
            }
            kotlinx.coroutines.delay(500)
        }
    }

    if (visible) {
        UpdateDialog(
            info = info,
            forced = forced,
            downloading = downloadId >= 0L && !downloadDone,
            progress = downloadProgress,
            landed = downloadLanded,
            error = downloadError,
            onDismiss = onDismiss,
            onDownload = { url ->
                downloadError = null
                // DownloadManager can be disabled/unavailable on some OEM builds (reported
                // on Samsung Galaxy) — getSystemService returns null or enqueue() throws
                // (IllegalArgumentException "Unknown URL"/SecurityException). Catch every
                // failure and fall back to opening the APK URL in the browser so the user
                // can always get the update instead of the popup silently doing nothing.
                val fileName = "vrc-a-update.apk"
                var enqueued = false
                try {
                    val dm = ctx.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as? DownloadManager
                    if (dm != null) {
                        val dest = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
                        if (dest.exists()) dest.delete()
                        val request = DownloadManager.Request(Uri.parse(url)).apply {
                            setTitle("VRC-A Update")
                            setDescription("Downloading update...")
                            setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, fileName)
                            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                        }
                        downloadId = dm.enqueue(request)
                        downloadDone = false
                        downloadLanded = false
                        enqueued = true
                    }
                } catch (_: Throwable) {
                    // fall through to the browser fallback below
                }
                if (!enqueued) {
                    try {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        downloadError = "In-app download unavailable on this device — " +
                            "opened the update in your browser instead."
                    } catch (_: Throwable) {
                        downloadError = "Couldn't start the download. Update manually from: $url"
                    }
                }
            }
        )
    }
}

/** How long Boot Cat's Done landing (~1 s) shows before the installer opens. */
private const val LANDED_HOLD_MS = 1100L
