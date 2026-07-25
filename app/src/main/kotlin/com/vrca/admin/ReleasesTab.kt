package com.vrca.admin

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.vrca.BuildConfig
import com.vrca.richcontent.RichBlock
import com.vrca.richcontent.RichDoc
import com.vrca.richcontent.resolveRichDoc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/* =========================================================
   RELEASES TAB
   Pick the public APK on-device. versionCode + versionName are
   read automatically from the APK file. The APK is uploaded to
   GitHub Releases (free, no storage subscription needed) via the
   GitHub API. The resulting download URL + metadata is then
   written to Firestore releases/latest so public clients pick it
   up on next launch.

   Public clients only prompt if releases/latest.versionCode
   is strictly GREATER than BuildConfig.VERSION_CODE, so users
   already on the pushed version or newer see nothing.

   Setup (once):
     Add to keystore.properties (already gitignored):
       githubPat=ghp_xxxxxxxxxxxxxxxxxxxx   <- PAT with Contents write
       githubOwner=your-username
       githubRepo=your-repo-name
   ========================================================= */

// ---- GitHub API helpers (uses OkHttp for proper 307/308 redirect handling) ----

internal data class GithubReleaseResult(
    val releaseId: Long,
    val uploadUrl: String,   // template like https://uploads.github.com/...{?name,label}
    val htmlUrl: String
)

internal suspend fun githubCreateRelease(
    owner: String, repo: String, pat: String,
    tagName: String, releaseName: String, body: String
): GithubReleaseResult = withContext(Dispatchers.IO) {
    val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val payload = JSONObject().apply {
        put("tag_name", tagName)
        put("name", releaseName)
        put("body", body)
        put("draft", false)
        put("prerelease", false)
    }.toString()

    val url = "https://api.github.com/repos/$owner/$repo/releases"
    var lastError: Exception? = null

    for (attempt in 1..5) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $pat")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()

            if (response.isSuccessful) {
                val json = JSONObject(responseBody)
                return@withContext GithubReleaseResult(
                    releaseId = json.getLong("id"),
                    uploadUrl = json.getString("upload_url"),
                    htmlUrl   = json.getString("html_url")
                )
            }

            lastError = Exception("GitHub create release failed (${response.code}): $responseBody")
            response.close()
        } catch (e: Exception) {
            lastError = Exception(
                "Release create attempt $attempt failed (${e::class.simpleName}): ${e.message}" +
                    (e.cause?.let { " caused by ${it::class.simpleName}: ${it.message}" } ?: ""),
                e
            )
        }

        if (attempt < 5) {
            val backoff = attempt * 2000L
            kotlinx.coroutines.delay(backoff)
        }
    }
    throw lastError ?: Exception("GitHub create release failed after 5 attempts")
}

internal suspend fun githubUploadAsset(
    uploadUrlTemplate: String, pat: String,
    fileName: String, apkFile: File,
    onProgress: (Float) -> Unit
): String = withContext(Dispatchers.IO) {
    val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .build()

    // upload_url comes back from githubCreateRelease as a canonical template,
    // e.g. https://uploads.github.com/repos/OWNER/REPO/releases/ID/assets{?name,label}
    // Using it (rather than rebuilding from owner/repo) means a renamed repo's
    // redirect was already resolved during create — so the binary POST goes
    // straight to the canonical host with no redirect that would close the
    // upload sink mid-stream ("IllegalStateException: closed").
    val encodedName = java.net.URLEncoder.encode(fileName, "UTF-8")
    val base = uploadUrlTemplate.substringBefore("{")
    val url = if (base.contains("?")) "$base&name=$encodedName" else "$base?name=$encodedName"
    var lastError: Exception? = null

    for (attempt in 1..5) {
        try {
            onProgress(0f)
            fun buildBody() = object : RequestBody() {
                override fun contentType() = "application/vnd.android.package-archive".toMediaType()
                override fun contentLength() = apkFile.length()
                override fun writeTo(sink: BufferedSink) {
                    val totalBytes = apkFile.length().toFloat()
                    var writtenBytes = 0L
                    val buf = ByteArray(64 * 1024)
                    apkFile.inputStream().use { inp ->
                        while (true) {
                            val n = inp.read(buf)
                            if (n == -1) break
                            sink.write(buf, 0, n)
                            writtenBytes += n
                            if (totalBytes > 0f) onProgress(writtenBytes / totalBytes)
                        }
                    }
                }
            }

            fun buildReq(target: String) = Request.Builder()
                .url(target)
                .header("Authorization", "Bearer $pat")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .post(buildBody())
                .build()

            var response = client.newCall(buildReq(url)).execute()
            var redirects = 0
            while (response.code in intArrayOf(301, 302, 307, 308) && redirects < 3) {
                val location = response.header("Location")
                response.close()
                if (location.isNullOrBlank()) break
                redirects++
                onProgress(0f)
                response = client.newCall(buildReq(location)).execute()
            }
            val responseBody = response.body?.string().orEmpty()

            if (response.isSuccessful) {
                return@withContext JSONObject(responseBody).getString("browser_download_url")
            }

            lastError = Exception("GitHub upload failed (${response.code}): $responseBody")
            response.close()
        } catch (e: Exception) {
            lastError = Exception(
                "Upload attempt $attempt failed (${e::class.simpleName}): ${e.message}" +
                    (e.cause?.let { " caused by ${it::class.simpleName}: ${it.message}" } ?: ""),
                e
            )
        }

        if (attempt < 5) {
            val backoff = attempt * 2000L
            kotlinx.coroutines.delay(backoff)
        }
    }
    throw lastError ?: Exception("GitHub upload failed after 5 attempts")
}

// ---- Composable ----

@Suppress("DEPRECATION")
internal fun parseApkInfo(ctx: Context, apkPath: String): Pair<Long, String>? {
    return try {
        val pm = ctx.packageManager
        val pi = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(
                apkPath,
                android.content.pm.PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            pm.getPackageArchiveInfo(apkPath, 0)
        } ?: return null
        val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
            pi.longVersionCode else pi.versionCode.toLong()
        code to (pi.versionName.orEmpty())
    } catch (_: Throwable) { null }
}

internal suspend fun copyUriToCache(ctx: Context, uri: android.net.Uri): File? {
    return try {
        val tmp = File(ctx.cacheDir, "upload_tmp.apk")
        ctx.contentResolver.openInputStream(uri)?.use { inp ->
            tmp.outputStream().use { out -> inp.copyTo(out) }
        }
        tmp
    } catch (_: Throwable) { null }
}

internal fun formatTimestampForRelease(ts: Timestamp?): String {
    if (ts == null) return "?"
    val ms = ts.seconds * 1000L + (ts.nanoseconds / 1_000_000L)
    if (ms <= 0L) return "?"
    val now = System.currentTimeMillis()
    val diff = now - ms

    // Future timestamps or clock skew: just show a compact date.
    if (diff < 0L) {
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))
    }

    val sec = diff / 1000L
    val min = sec / 60L
    val hr = min / 60L
    val day = hr / 24L

    val rel = when {
        sec < 60L -> "${sec}s ago"
        min < 60L -> "${min}m ago"
        hr < 48L -> "${hr}h ago"
        else -> "${day}d ago"
    }

    val abs = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))
    return "$abs ($rel)"
}

@Composable
internal fun ReleasesTab(
    db: FirebaseFirestore,
    setGlobalLoading: (Boolean) -> Unit,
    setError: (String?) -> Unit
) {
    val ctx   = LocalContext.current
    val scope = rememberCoroutineScope()

    val githubPat   = BuildConfig.GITHUB_PAT
    val githubOwner = BuildConfig.GITHUB_OWNER
    val githubRepo  = BuildConfig.GITHUB_REPO
    val credsMissing = githubPat.isBlank() || githubOwner.isBlank() || githubRepo.isBlank()

    // ---- current live release ----
    var liveVersionCode by rememberSaveable { mutableLongStateOf(0L) }
    var liveVersionName by rememberSaveable { mutableStateOf("") }
    var liveDownloadUrl by rememberSaveable { mutableStateOf("") }
    var liveRequiredMin by rememberSaveable { mutableLongStateOf(0L) }
    var liveNotes       by rememberSaveable { mutableStateOf("") }
    var livePublishedAt by remember { mutableStateOf<Timestamp?>(null) }
    var loaded          by remember { mutableStateOf(false) }

    // ---- picked APK ----
    var pickedFileName  by rememberSaveable { mutableStateOf("") }
    var parsedCode      by rememberSaveable { mutableLongStateOf(0L) }
    var parsedName      by rememberSaveable { mutableStateOf("") }
    var parseError      by rememberSaveable { mutableStateOf("") }
    var cachedApkPath   by remember { mutableStateOf("") }

    // ---- optional fields ----
    var editRequiredMin by rememberSaveable { mutableStateOf("") }
    // editNotes + releaseBlocks are backed by AdminRuntime (PROCESS lifetime) so the
    // rich update-log content survives the media picker recreating the Activity. The
    // detail/editor subtree is briefly disposed on recreation, past Compose's
    // saved-state restoration window, so rememberSaveable was NOT enough (it cleared).
    var editNotes       by remember { AdminRuntime.editorNotesFor("release") }
    val releaseBlocks = remember { AdminRuntime.editorBlocksFor("release") }

    // ---- upload state ----
    var uploadPhase     by remember { mutableStateOf("") }
    var uploadProgress  by remember { mutableStateOf(0f) }
    var uploading       by remember { mutableStateOf(false) }
    var uploadDone      by remember { mutableStateOf(false) }

    // Seed the editor from the currently-published release exactly ONCE so the admin
    // EDITS the live content instead of starting blank. The "seeded" flag lives in
    // AdminRuntime (process lifetime) so the LaunchedEffect(Unit) re-run on an
    // Activity recreation (media picker) never re-seeds over in-progress edits.
    suspend fun loadCurrent() {
        setGlobalLoading(true)
        runCatching {
            val snap = db.collection("releases").document("latest").get().await()
            if (snap.exists()) {
                liveVersionCode = snap.getLong("versionCode") ?: 0L
                liveVersionName = snap.getString("versionName").orEmpty()
                liveDownloadUrl = snap.getString("downloadUrl").orEmpty()
                liveRequiredMin = snap.getLong("requiredMinCode") ?: 0L
                liveNotes       = snap.getString("notes").orEmpty()
                livePublishedAt = snap.getTimestamp("publishedAt")
                if (!AdminRuntime.isEditorSeeded("release")) {
                    if (editNotes.isBlank()) editNotes = liveNotes
                    if (releaseBlocks.isEmpty()) {
                        resolveRichDoc(snap.getString("bodyDoc"), liveNotes)?.blocks?.let {
                            releaseBlocks.clear(); releaseBlocks.addAll(it)
                        }
                    }
                }
            }
            AdminRuntime.markEditorSeeded("release")
            loaded = true
        }.onFailure { e -> setError(e.message ?: "Failed to load release") }
        setGlobalLoading(false)
    }

    LaunchedEffect(Unit) { loadCurrent() }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        parseError = ""; parsedCode = 0L; parsedName = ""
        pickedFileName = ""; cachedApkPath = ""; uploadDone = false

        scope.launch {
            val tmp = copyUriToCache(ctx, uri)
            if (tmp == null) { parseError = "Could not read the selected file."; return@launch }
            cachedApkPath = tmp.absolutePath

            pickedFileName = runCatching {
                ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    c.moveToFirst()
                    c.getString(c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME))
                }
            }.getOrNull() ?: "release.apk"

            val info = parseApkInfo(ctx, tmp.absolutePath)
            if (info == null) {
                parseError = "Could not read version info.\nMake sure this is a valid APK."
                return@launch
            }
            parsedCode = info.first
            parsedName = info.second
        }
    }

    fun startUpload() {
        val apkPath = cachedApkPath
        if (apkPath.isBlank() || parsedCode == 0L) return

        scope.launch {
            uploading = true; uploadDone = false; uploadProgress = 0f; setError(null)

            runCatching {
                val apkFile = File(apkPath)
                val tagName  = "v$parsedCode"
                val relName  = "v${parsedName.ifBlank { parsedCode.toString() }}"
                val fileName = "chatbox-vrc-a-${relName}.apk"
                    .replace(Regex("[^a-zA-Z0-9._-]"), "_")

                // Rich in-app notes (Phase 3): one doc, media on GitHub. notes is the
                // plaintext fallback for the GitHub release body + legacy/old clients.
                val releaseDoc = RichDoc(blocks = releaseBlocks.toList())
                val bodyDocJson = if (releaseDoc.blocks.isEmpty()) "" else releaseDoc.toJson()
                val notesPlain = editNotes.trim().ifBlank { releaseDoc.toPlainText() }

                // Step 1: create GitHub release
                uploadPhase = "Creating GitHub release..."
                val release = githubCreateRelease(
                    owner       = githubOwner,
                    repo        = githubRepo,
                    pat         = githubPat,
                    tagName     = tagName,
                    releaseName = relName,
                    body        = notesPlain.ifBlank { "Release $relName" }
                )

                // Step 2: upload APK asset with progress
                uploadPhase = "Uploading APK..."
                val downloadUrl = githubUploadAsset(
                    uploadUrlTemplate = release.uploadUrl,
                    pat        = githubPat,
                    fileName   = fileName,
                    apkFile    = apkFile,
                    onProgress = { uploadProgress = it }
                )

                // Step 3: write Firestore releases/latest
                uploadPhase = "Publishing release info..."
                val data = hashMapOf<String, Any>(
                    "versionCode"       to parsedCode,
                    "versionName"       to parsedName,
                    "downloadUrl"       to downloadUrl,
                    "requiredMinCode"   to (editRequiredMin.toLongOrNull() ?: 0L),
                    "notes"             to notesPlain,
                    "bodyDoc"           to bodyDocJson,
                    "publishedAt"       to FieldValue.serverTimestamp(),
                    "publishedByDevice" to BuildConfig.APPLICATION_ID
                )
                db.collection("releases").document("latest")
                    .set(data, SetOptions.merge())
                    .await()

                loadCurrent()
                uploadDone = true
                uploadPhase = ""

                // clean up temp file
                runCatching { apkFile.delete() }
                cachedApkPath = ""; pickedFileName = ""; parsedCode = 0L; parsedName = ""
                AdminRuntime.clearEditor("release")  // blocks + notes + seeded flag

            }.onFailure { e ->
                setError(e.message ?: "Upload failed")
                uploadPhase = ""
            }

            uploading = false
        }
    }

    // Saveable so the scroll position is RESTORED after the media picker recreates the
    // Activity (a plain rememberScrollState reset the admin to the top).
    val releaseScroll = rememberSaveable(saver = androidx.compose.foundation.ScrollState.Saver) {
        androidx.compose.foundation.ScrollState(0)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(releaseScroll),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        // ---- Credentials warning ----
        if (credsMissing) {
            Card(colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("GitHub credentials not configured", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Add to keystore.properties:\n" +
                        "  githubPat=ghp_xxxxxxxxxxxxxxxxxxxx\n" +
                        "  githubOwner=your-username\n" +
                        "  githubRepo=your-repo-name\n\n" +
                        "The PAT needs Contents: write permission on the repo.",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // ---- Current live release ----
        ElevatedCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Current Live Release", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { scope.launch { loadCurrent() } }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reload")
                    }
                }
                if (!loaded) {
                    CircularProgressIndicator()
                } else if (liveVersionCode == 0L && liveDownloadUrl.isBlank()) {
                    Text("No release published yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("versionCode=$liveVersionCode  name=${liveVersionName.ifBlank { "(blank)" }}",
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text("requiredMinCode=$liveRequiredMin",
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text("publishedAt=${formatTimestampForRelease(livePublishedAt)}",
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (liveDownloadUrl.isNotBlank()) {
                        Text("url=${liveDownloadUrl.take(72)}${if (liveDownloadUrl.length > 72) "..." else ""}",
                            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (liveNotes.isNotBlank()) {
                        Text(liveNotes.lines().firstOrNull().orEmpty().take(100),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    var showRetractConfirm by remember { mutableStateOf(false) }
                    OutlinedButton(
                        onClick = { showRetractConfirm = true },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Delete, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Retract Release")
                    }
                    if (showRetractConfirm) {
                        com.vrca.ui.common.VrcaConfirmDialog(
                            title = "Retract live release?",
                            body = "This removes the update prompt for all users. Existing installs are not affected.",
                            confirmLabel = "Retract",
                            destructive = true,
                            onConfirm = {
                                showRetractConfirm = false
                                scope.launch {
                                    runCatching {
                                        db.collection("releases").document("latest").delete().await()
                                        liveVersionCode = 0L; liveVersionName = ""; liveDownloadUrl = ""
                                        liveRequiredMin = 0L; liveNotes = ""; livePublishedAt = null
                                    }.onFailure { setError(it.message) }
                                }
                            },
                            onDismiss = { showRetractConfirm = false }
                        )
                    }
                }
            }
        }

        // ---- Publish new release ----
        ElevatedCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Publish New Release", style = MaterialTheme.typography.titleMedium)
                Text("Pick the public APK. Version info is read automatically, then the APK is uploaded to GitHub Releases.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)

                OutlinedButton(
                    onClick = { filePicker.launch("*/*") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uploading && !credsMissing
                ) {
                    Icon(Icons.Filled.AttachFile, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (pickedFileName.isBlank()) "Pick APK file" else pickedFileName)
                }

                if (parseError.isNotBlank()) {
                    Card(colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )) {
                        Text(parseError, modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (parsedCode > 0L) {
                    Card(colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Read from APK", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("versionCode = $parsedCode",
                                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                            Text("versionName = ${parsedName.ifBlank { "(blank)" }}",
                                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    OutlinedTextField(
                        value = editRequiredMin,
                        onValueChange = { editRequiredMin = it.filter { c -> c.isDigit() } },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Force-update below this versionCode (0 = optional)") },
                        placeholder = { Text("0") }, enabled = !uploading
                    )

                    OutlinedTextField(
                        value = editNotes,
                        onValueChange = { editNotes = it },
                        modifier = Modifier.fillMaxWidth(), minLines = 3,
                        label = { Text("Plain release notes (GitHub + legacy clients)") },
                        enabled = !uploading
                    )

                    Text(
                        "Rich in-app notes (optional) — shown in the update popup and What's New",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    RichDocEditor(blocks = releaseBlocks, githubPat = githubPat)

                    if (uploading) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LinearProgressIndicator(
                                progress = uploadProgress,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(uploadPhase.ifBlank { "Working..." },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    if (uploadDone) {
                        Card(colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )) {
                            Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Filled.CheckCircle, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary)
                                Text("Published! Users on older versions will be prompted to update.",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    Button(
                        onClick = { startUpload() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uploading && parsedCode > 0L && cachedApkPath.isNotBlank() && !credsMissing
                    ) {
                        Icon(Icons.Filled.CloudUpload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Upload & Publish v$parsedName ($parsedCode)")
                    }

                    Text(
                        "Users on versionCode >= $parsedCode will not be prompted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
    }
}
