package com.vrca.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
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
       ToS gate
       Must be accepted before the VRChat login is shown.
       Uses local SharedPreferences â€" no Firestore needed at this stage.
       Re-shows if ToS version bumped (checked in VrcaScreen too for
       returning users who already logged in).
       ------------------------- */

    val tosPrefs = remember { ctx.getSharedPreferences("vrca_tos", Context.MODE_PRIVATE) }
    // Version 1 = baseline. Increment in Firestore config/app.tosVersion to force re-acceptance.
    // At this stage we only check local prefs; VrcaScreen does the remote version check.
    val localTosAccepted = remember { tosPrefs.getInt("accepted_version", 0) >= 1 }
    var tosGatePassed by remember { mutableStateOf(localTosAccepted) }

    if (!tosGatePassed) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Terms of Use", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Before using VRC-A, please read and accept the Terms of Use.\n\n" +
                    "â€¢ VRC-A sends OSC messages to VRChat on your behalf.\n" +
                    "â€¢ VRC-A connects to VRChat's web API to read your status and notifications.\n" +
                    "â€¢ You are responsible for how you use this app within VRChat's community guidelines.\n" +
                    "â€¢ VRC-A is not affiliated with or endorsed by VRChat Inc.\n" +
                    "â€¢ Your session cookie is stored locally on your device only.\n" +
                    "â€¢ This app may collect device identifiers for moderation purposes.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        tosPrefs.edit().putInt("accepted_version", 1)
                            .putLong("accepted_at_ms", System.currentTimeMillis()).apply()
                        tosGatePassed = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("I Accept") }
                OutlinedButton(
                    onClick = {
                        // Gracefully exit if they decline
                        (ctx as? android.app.Activity)?.finish()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Decline") }
            }
        }
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

    LaunchedEffect(Unit) {
        VrchatAuthManager.loggedOutSignal.collect { vrcLoginDone = false }
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

    // If VRC login succeeded but Phase 2 hasn't run yet (first run after login)
    LaunchedEffect(vrcLoginDone) {
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

    val vm: VrcaViewModel = viewModel(factory = VrcaViewModel.Factory)

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
                        releaseCheckResult = ReleaseCheckResult.UpdateAvailable(
                            info, forced = BuildConfig.VERSION_CODE < info.requiredMinCode
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
                releaseCheckResult = ReleaseCheckResult.UpdateAvailable(info, forced = false)
                updateDismissed = false
            }
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
                val dm = ctx.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as DownloadManager
                val fileName = "vrc-a-update.apk"
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
            }
        )
    }

    /* -------------------------
       Main app
       ------------------------- */

    VrcaScreen(chatboxViewModel = vm)
}

@Composable
private fun UpdateDialog(
    info: ReleaseInfo,
    forced: Boolean,
    downloading: Boolean = false,
    error: String? = null,
    onDismiss: () -> Unit,
    onDownload: (String) -> Unit
) {
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
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (forced) "Update Required" else "Update Available",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        info.versionName,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // Release notes
                if (info.notes.isNotBlank()) {
                    Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        tonalElevation = 1.dp
                    ) {
                        Text(
                            info.notes,
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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

                // Buttons
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                ) {
                    if (!forced) {
                        OutlinedButton(onClick = onDismiss) {
                            Text("Later")
                        }
                    }
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
        runCatching {
            db.collection("users").document(deviceHash)
                .set(safeUser, SetOptions.merge())
                .await()
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
