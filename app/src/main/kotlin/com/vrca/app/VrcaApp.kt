package com.vrca.app

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
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
import com.vrca.vrchat.VrchatAuthManager
import com.vrca.vrchat.VrchatBanChecker
import com.vrca.vrchat.VrchatLoginScreen
import com.vrca.vrchat.VrchatPipelineService
import androidx.core.content.ContextCompat
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import kotlinx.coroutines.launch
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
    val ctx = LocalContext.current

    /* -------------------------
       Crash gate
       ------------------------- */

    val crashPrefs = remember {
        ctx.getSharedPreferences(VrcaApplication.CRASH_PREFS_FILE, Context.MODE_PRIVATE)
    }
    val lastCrashText = remember {
        crashPrefs.getString(VrcaApplication.CRASH_KEY_TEXT, "").orEmpty()
    }

    var allowBoot by remember { mutableStateOf(lastCrashText.isBlank()) }

    if (!allowBoot) {
        CrashScreen(
            crashText = lastCrashText,
            onClear = {
                crashPrefs.edit()
                    .remove(VrcaApplication.CRASH_KEY_TEXT)
                    .commit()
            },
            onContinue = {
                crashPrefs.edit()
                    .remove(VrcaApplication.CRASH_KEY_TEXT)
                    .commit()
                allowBoot = true
            }
        )
        return
    }

    /* -------------------------
       Bootstrap gate
       ------------------------- */

    var bootOk by remember { mutableStateOf(false) }
    var bootWorking by remember { mutableStateOf(false) }
    var bootError by remember { mutableStateOf<String?>(null) }
    var bootAttempt by remember { mutableStateOf(0) } // increments on Retry

    if (!bootOk) {
        BootstrapScreen(
            working = bootWorking,
            error = bootError,
            onRetry = {
                bootOk = false
                bootWorking = false
                bootError = null
                bootAttempt += 1
            }
        )

        // Run bootstrap once per attempt.
        LaunchedEffect(bootAttempt) {
            if (bootOk || bootWorking) return@LaunchedEffect
            bootWorking = true
            bootError = null

            try {
                kotlinx.coroutines.withTimeout(20_000L) {
                    bootstrapFirebaseAndCache(ctx)
                }
                bootOk = true
            } catch (t: kotlinx.coroutines.TimeoutCancellationException) {
                bootError = "Couldn't reach server. Check your connection and try again."
            } catch (t: Throwable) {
                bootError = (t.message ?: t.toString()).take(4000)
            } finally {
                bootWorking = false
            }
        }
        return
    }

    /* -------------------------
       Phase 1 ban check (device hash + auth UID)
       Runs immediately after bootstrap. Does NOT show ban screen yet -
       waits for VRChat login so we can capture the alt VRChat ID first.
       ------------------------- */

    var phase1BanId   by remember { mutableStateOf<String?>(null) }
    var phase1Checked by remember { mutableStateOf(false) }

    LaunchedEffect(bootOk) {
        if (!bootOk) return@LaunchedEffect
        val prefs = ctx.getSharedPreferences("vrca_remote", Context.MODE_PRIVATE)
        val deviceHash = prefs.getString("device_id_hash", "") ?: ""
        val authUid    = prefs.getString("auth_uid", "") ?: ""
        if (deviceHash.isNotBlank()) {
            val result = VrchatBanChecker.checkPhase1(deviceHash, authUid)
            phase1BanId = result.banId  // null if clean
        }
        phase1Checked = true
    }

    if (bootOk && !phase1Checked) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
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

    DisposableEffect(Unit) {
        val reg = FirebaseFirestore.getInstance()
            .collection("config").document("app")
            .addSnapshotListener { snap, _ ->
                if (snap != null && snap.exists()) {
                    remoteTosVersion = (snap.getLong("tosVersion") ?: 1L).toInt().coerceAtLeast(1)
                    remoteTosText = snap.getString("tosText") ?: ""
                    remoteTosUrl = snap.getString("tosUrl") ?: ""
                    remoteTosUpdatedAtMs = snap.getTimestamp("updatedAt")?.toDate()?.time ?: 0L
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
       First-open onboarding tutorial (docs/ui-revamp.md).
       Pre-seeded complete for existing installs (any prior ToS acceptance)
       so current users never see it. Replayable from Settings.
       ------------------------- */

    var onboardingDone by remember {
        mutableStateOf(
            com.vrca.ui.onboarding.OnboardingPrefs.isComplete(ctx).also { done ->
                // Pre-seed: an install that already accepted ANY ToS version is an
                // existing user — mark complete so the tutorial never shows.
                if (!done && TosPrefs.acceptedVersion(ctx) > 0) {
                    com.vrca.ui.onboarding.OnboardingPrefs.markComplete(ctx)
                }
            } || TosPrefs.acceptedVersion(ctx) > 0
        )
    }
    val onboardingReplay by com.vrca.ui.onboarding.OnboardingState.replayRequested

    if (!onboardingDone || onboardingReplay) {
        com.vrca.ui.onboarding.OnboardingFlow(
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
       VRChat login gate (required for all users)
       Shows VrchatLoginScreen if not yet logged in.
       After login: runs Phase 2 ban check, starts pipeline service.
       ------------------------- */

    var vrcLoginDone    by remember { mutableStateOf(VrchatAuthManager.isLoggedIn(ctx)) }
    var isBannedPhase2  by remember { mutableStateOf(false) }
    var banPhase2Reason by remember { mutableStateOf("") }
    var phase2Checking  by remember { mutableStateOf(false) }
    var reloginTick     by remember { mutableStateOf(0) }

    // A MID-SESSION sign-out (Settings Accounts) deliberately does NOT kick the
    // user back to the login gate anymore — the app stays usable with OSC
    // hard-blocked by VrcaViewModel's auth gate until they sign back in from
    // Settings (docs/ui-revamp.md, Accounts). A cold start while logged out
    // still shows the gate below. Each successful re-login re-runs the Phase-2
    // ban check (the new account could be a banned identity).
    LaunchedEffect(Unit) {
        VrchatAuthManager.loggedInSignal.collect {
            vrcLoginDone = true
            reloginTick++
        }
    }

    if (!vrcLoginDone) {
        VrchatLoginScreen(pendingBanId = phase1BanId) { _, _ ->
            // Login succeeded - mark done, LaunchedEffect below will run Phase 2
            vrcLoginDone = true
        }
        LaunchedEffect(vrcLoginDone) {
            if (!vrcLoginDone) return@LaunchedEffect
            phase2Checking = true
            runPhase2AndStartPipeline(
                ctx, phase1BanId,
                onBanned = { reason -> isBannedPhase2 = true; banPhase2Reason = reason },
                onClean  = { phase2Checking = false }
            )
        }
        return
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
       Update check (public build only)
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

    var releaseCheckResult by remember { mutableStateOf<ReleaseCheckResult?>(null) }
    var updateDismissed    by remember { mutableStateOf(false) }
    var downloadId         by remember { mutableLongStateOf(-1L) }
    var downloadDone       by remember { mutableStateOf(false) }
    var downloadError      by remember { mutableStateOf<String?>(null) }

    if (!BuildConfig.IS_ADMIN_BUILD) {
        val prefs = remember { ctx.getSharedPreferences("vrca_remote", Context.MODE_PRIVATE) }
        val deviceHash = remember { prefs.getString("device_id_hash", "") ?: "" }

        LaunchedEffect(Unit) {
            releaseCheckResult = checkFirestoreRelease(BuildConfig.VERSION_CODE, deviceHash)
        }

        // Real-time detection: snapshot listener on releases/{deviceHash}
        if (deviceHash.isNotBlank()) {
            DisposableEffect(deviceHash) {
                val reg = FirebaseFirestore.getInstance()
                    .collection("releases").document(deviceHash)
                    .addSnapshotListener { snap, _ ->
                        if (snap == null || !snap.exists()) return@addSnapshotListener
                        val url = snap.getString("downloadUrl").orEmpty()
                        if (url.isBlank()) return@addSnapshotListener
                        val code = snap.getLong("versionCode") ?: return@addSnapshotListener
                        if (code <= BuildConfig.VERSION_CODE) return@addSnapshotListener
                        val info = ReleaseInfo(
                            versionCode     = code,
                            versionName     = snap.getString("versionName").orEmpty(),
                            downloadUrl     = url,
                            requiredMinCode = snap.getLong("requiredMinCode") ?: 0L,
                            notes           = snap.getString("notes").orEmpty()
                        )
                        // All directed releases are forced automatically.
                        releaseCheckResult = ReleaseCheckResult.UpdateAvailable(
                            info, forced = true
                        )
                        updateDismissed = false
                    }
                onDispose { reg.remove() }
            }
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

    // Listen for DownloadManager completion
    if (downloadId >= 0L && !downloadDone) {
        DisposableEffect(downloadId) {
            val dm = ctx.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as DownloadManager
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: android.content.Context, intent: Intent) {
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                    if (id != downloadId) return
                    val query = DownloadManager.Query().setFilterById(id)
                    val cursor = dm.query(query)
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
                                context.startActivity(install)
                                downloadDone = true
                            }
                        }
                    }
                    cursor.close()
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(
                    receiver,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    android.content.Context.RECEIVER_EXPORTED
                )
            } else {
                ctx.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
            }
            onDispose { ctx.unregisterReceiver(receiver) }
        }
    }

    val updateToShow = releaseCheckResult
    if (!BuildConfig.IS_ADMIN_BUILD &&
        updateToShow is ReleaseCheckResult.UpdateAvailable &&
        (!updateDismissed || updateToShow.forced)
    ) {
        UpdateDialog(
            info = updateToShow.info,
            forced = updateToShow.forced,
            downloading = downloadId >= 0L && !downloadDone,
            error = downloadError,
            onDismiss = { updateDismissed = true },
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

    /* -------------------------
       Main app
       ------------------------- */

    VrcaScreen(chatboxViewModel = vm)
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
private fun UpdateDialog(
    info: ReleaseInfo,
    forced: Boolean,
    downloading: Boolean = false,
    error: String? = null,
    onDismiss: () -> Unit,
    onDownload: (String) -> Unit
) {
    val noteBlocks = remember(info.notes) { parseTosBlocks(info.notes) }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = { if (!forced) onDismiss() }
    ) {
        ElevatedCard(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            colors = androidx.compose.material3.CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header: title + version pill
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        if (forced) "Update Required" else "Update Available",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    ) {
                        Text(
                            info.versionName,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Release notes — bounded height + scroll so long logs never clip
                if (info.notes.isNotBlank()) {
                    val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
                    Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        tonalElevation = 1.dp
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = (screenH * 0.45f).dp)
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp)
                        ) {
                            StyledBlocks(noteBlocks)
                        }
                    }
                }

                if (forced) {
                    Text(
                        "This update is required to continue using the app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
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

                // Actions: forced = single full-width Download; legacy = Later + Download
                if (forced) {
                    Button(
                        onClick = { if (!downloading) onDownload(info.downloadUrl) },
                        enabled = !downloading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (downloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(18.dp).widthIn(max = 18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.widthIn(min = 8.dp))
                            Text("Downloading")
                        } else {
                            Text(if (error != null) "Retry Download" else "Download Update")
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                    ) {
                        OutlinedButton(onClick = onDismiss) { Text("Later") }
                        Button(
                            onClick = { if (!downloading) onDownload(info.downloadUrl) },
                            enabled = !downloading
                        ) {
                            if (downloading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.height(18.dp).widthIn(max = 18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.widthIn(min = 8.dp))
                                Text("Downloading")
                            } else {
                                Text(if (error != null) "Retry" else "Download")
                            }
                        }
                    }
                }

                // Manual fallback: always-visible link that opens the release URL in the
                // browser, for devices where the in-app DownloadManager flow fails.
                val dialogCtx = LocalContext.current
                TextButton(
                    onClick = {
                        runCatching {
                            dialogCtx.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Download failed? Tap here to download in your browser",
                        style = MaterialTheme.typography.bodySmall
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
private suspend fun bootstrapFirebaseAndCache(ctx: Context) {
    val auth = FirebaseAuth.getInstance()
    if (auth.currentUser == null) {
        auth.signInAnonymously().await()
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

        if (!recentWrite) {
            runCatching {
                db.collection("users").document(deviceHash)
                    .set(safeUser, SetOptions.merge())
                    .await()
            }
        }

        runCatching {
            db.collection("usersById").document(uid)
                .set(safeLink, SetOptions.merge())
                .await()
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
            info.signingInfo.apkContentsSigners.firstOrNull()?.toByteArray() ?: byteArrayOf()
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

@Composable
private fun BootstrapScreen(
    working: Boolean,
    error: String?,
    onRetry: () -> Unit
) {
    Surface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Starting VRC-A\u2026",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(10.dp))

            Text(
                "Preparing device session\u2026",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(18.dp))

            if (working) CircularProgressIndicator() else Spacer(Modifier.height(4.dp))

            if (error != null) {
                Spacer(Modifier.height(18.dp))

                ElevatedCard(modifier = Modifier.widthIn(max = 520.dp)) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Startup error", style = MaterialTheme.typography.titleSmall)
                        Text(
                            error,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Button(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun CrashScreen(
    crashText: String,
    onClear: () -> Unit,
    onContinue: () -> Unit
) {
    Surface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("VRC-A crashed on last launch", style = MaterialTheme.typography.headlineSmall)

            ElevatedCard {
                Column(
                    Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        crashText.ifBlank { "(no crash text saved)" },
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            ) {
                Text("Clear crash + Try boot")
            }

            OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
                Text("Clear crash log only")
            }

            Text(
                "If this screen shows \u201Cno crash text saved\u201D, the process may be dying before the handler can write the log.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

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
private fun BannedScreen(reason: String) {
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

private object TosPrefs {
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
                    OutlinedButton(
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
                    Button(
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
