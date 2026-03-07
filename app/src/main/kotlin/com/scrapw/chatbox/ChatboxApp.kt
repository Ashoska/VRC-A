// app/src/main/kotlin/com/scrapw/chatbox/ChatboxApp.kt
package com.scrapw.chatbox

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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.scrapw.chatbox.ui.ChatboxViewModel
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import java.io.File
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * ChatboxApp
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
fun ChatboxApp() {
    val ctx = LocalContext.current

    /* -------------------------
       Crash gate
       ------------------------- */

    val crashPrefs = remember {
        ctx.getSharedPreferences(ChatboxApplication.CRASH_PREFS_FILE, Context.MODE_PRIVATE)
    }
    val lastCrashText = remember {
        crashPrefs.getString(ChatboxApplication.CRASH_KEY_TEXT, "").orEmpty()
    }

    var allowBoot by remember { mutableStateOf(lastCrashText.isBlank()) }

    if (!allowBoot) {
        CrashScreen(
            crashText = lastCrashText,
            onClear = {
                crashPrefs.edit()
                    .remove(ChatboxApplication.CRASH_KEY_TEXT)
                    .commit()
            },
            onContinue = {
                crashPrefs.edit()
                    .remove(ChatboxApplication.CRASH_KEY_TEXT)
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
                bootstrapFirebaseAndCache(ctx)
                bootOk = true
            } catch (t: Throwable) {
                bootError = (t.message ?: t.toString()).take(4000)
            } finally {
                bootWorking = false
            }
        }
        return
    }

    /* -------------------------
       Update check (public build only)
       ------------------------- */

    var releaseCheckResult by remember { mutableStateOf<ReleaseCheckResult?>(null) }
    var updateDismissed    by remember { mutableStateOf(false) }
    var downloadId         by remember { mutableLongStateOf(-1L) }
    var downloadDone       by remember { mutableStateOf(false) }

    if (!BuildConfig.IS_ADMIN_BUILD) {
        LaunchedEffect(Unit) {
            releaseCheckResult = checkFirestoreRelease(BuildConfig.VERSION_CODE)
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
                        val localUriCol = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                        val status = cursor.getInt(statusCol)
                        val localUri = cursor.getString(localUriCol)
                        if (status == DownloadManager.STATUS_SUCCESSFUL && localUri != null) {
                            val file = File(Uri.parse(localUri).path!!)
                            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file
                                )
                            } else {
                                Uri.fromFile(file)
                            }
                            val install = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                                setDataAndType(uri, "application/vnd.android.package-archive")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(install)
                            downloadDone = true
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
            onDismiss = { updateDismissed = true },
            onDownload = { url ->
                val dm = ctx.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as DownloadManager
                val fileName = "vrc-a-update.apk"
                // Delete any previous partial download
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

    val vm: ChatboxViewModel = viewModel(factory = ChatboxViewModel.Factory)
    ChatboxScreen(chatboxViewModel = vm)
}

@Composable
private fun UpdateDialog(
    info: ReleaseInfo,
    forced: Boolean,
    downloading: Boolean = false,
    onDismiss: () -> Unit,
    onDownload: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!forced) onDismiss() },
        title = {
            Text(if (forced) "Update Required" else "Update Available")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Version ${info.versionName} is available.")
                if (info.notes.isNotBlank()) {
                    Text(
                        info.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (forced) {
                    Text(
                        "This update is required to continue using the app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (!downloading) onDownload(info.downloadUrl) },
                enabled = !downloading
            ) {
                Text(if (downloading) "Downloading..." else "Download")
            }
        },
        dismissButton = if (!forced) ({
            TextButton(onClick = onDismiss) { Text("Later") }
        }) else null
    )
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

    // If rules deny, still let app continue (VM will keep trying later).
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
