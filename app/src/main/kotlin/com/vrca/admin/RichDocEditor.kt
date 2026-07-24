package com.vrca.admin

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vrca.richcontent.RichBlock
import com.vrca.richcontent.RichDoc
import com.vrca.richcontent.RichDocRenderer
import com.vrca.richcontent.RichMediaStore
import com.vrca.richcontent.resolveRichDoc
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/*
 * Admin block editor (Phase 3). Authors a RichDoc as an ordered, reorderable list
 * of blocks with a live preview using the SAME RichDocRenderer users see. Reused by
 * both AnnouncementsTab and ReleasesTab. Reorder is via move up/down controls
 * (drag-and-drop is a later polish). Image upload pushes to the public image-store
 * GitHub repo via the Contents API with a UNIQUE filename per upload (never
 * overwrite — the raw CDN caches briefly, so a fresh URL = live edits show instantly and
 * the old URL falls out of the reference set → auto-culled client-side).
 *
 * Firestore cost: ZERO. The editor is pure local state; media goes to GitHub, and
 * saving the doc is the caller's single Firestore write.
 */

private const val IMG_REPO_OWNER = "Ashoska"
private const val IMG_REPO_NAME = "VRC-A-Image-store"
private const val IMG_REPO_BRANCH = "main"

private fun extFor(ctx: Context, uri: Uri): String {
    val mime = ctx.contentResolver.getType(uri).orEmpty()
    return when {
        mime.contains("png") -> "png"
        mime.contains("gif") -> "gif"
        mime.contains("webp") -> "webp"
        mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
        else -> "png"
    }
}

private const val MEDIA_RELEASE_TAG = "rich-media"

@Volatile
private var cachedMediaUploadUrl: String? = null

private fun mimeForExt(ext: String): String = when (ext.lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "mp4" -> "video/mp4"
    else -> "application/octet-stream"
}

/**
 * Ensures the auto-managed `rich-media` release exists in the image-store repo and
 * returns its asset upload_url template (cached process-wide). Media uploads as
 * STREAMED release assets (raw bytes) rather than the base64 Contents API — no 33%
 * base64 inflation, no giant in-memory JSON, and it streams like the fast APK upload
 * (which is why APK pushes were near-instant while base64 media pushes crawled and
 * saturated the connection).
 */
private suspend fun ensureMediaReleaseUploadUrl(pat: String): String {
    cachedMediaUploadUrl?.let { return it }
    return withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val getReq = Request.Builder()
            .url("https://api.github.com/repos/$IMG_REPO_OWNER/$IMG_REPO_NAME/releases/tags/$MEDIA_RELEASE_TAG")
            .header("Authorization", "Bearer $pat")
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()
        val getResp = client.newCall(getReq).execute()
        val getBody = getResp.body?.string().orEmpty()
        val getCode = getResp.code
        getResp.close()
        if (getCode == 200) {
            JSONObject(getBody).getString("upload_url").also { cachedMediaUploadUrl = it }
        } else {
            githubCreateRelease(
                owner = IMG_REPO_OWNER, repo = IMG_REPO_NAME, pat = pat,
                tagName = MEDIA_RELEASE_TAG, releaseName = "VRC-A Rich Media",
                body = "Auto-managed rich-content media (images / videos / gifs)."
            ).uploadUrl.also { cachedMediaUploadUrl = it }
        }
    }
}

/** Uploads raw bytes as a STREAMED release asset; returns the download URL. */
private suspend fun uploadBytesToImageStore(bytes: ByteArray, ext: String, pat: String): String =
    withContext(Dispatchers.IO) {
        if (pat.isBlank()) throw Exception("GitHub token missing in this build.")
        val template = ensureMediaReleaseUploadUrl(pat)
        val fileName = "rich_${System.currentTimeMillis()}.$ext"   // unique — never overwrite
        val base = template.substringBefore("{")
        val url = if (base.contains("?")) "$base&name=$fileName" else "$base?name=$fileName"
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $pat")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .post(bytes.toRequestBody(mimeForExt(ext).toMediaType()))
            .build()
        val resp = client.newCall(req).execute()
        val body = resp.body?.string().orEmpty()
        val code = resp.code
        resp.close()
        if (code in 200..299) {
            JSONObject(body).getString("browser_download_url")
        } else {
            if (code == 404) cachedMediaUploadUrl = null   // release vanished → recreate next time
            throw Exception("Upload failed ($code): ${body.take(300)}")
        }
    }

internal suspend fun githubUploadImage(ctx: Context, uri: Uri, pat: String): String =
    withContext(Dispatchers.IO) {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw Exception("Could not read the selected image.")
        uploadBytesToImageStore(bytes, extFor(ctx, uri), pat)
    }

/** Uploads an already-transcoded (or raw) video's bytes as `.mp4`. */
internal suspend fun githubUploadVideoBytes(bytes: ByteArray, pat: String): String =
    uploadBytesToImageStore(bytes, "mp4", pat)

private const val MEDIA_DL_PREFIX =
    "https://github.com/$IMG_REPO_OWNER/$IMG_REPO_NAME/releases/download/$MEDIA_RELEASE_TAG/"

/**
 * Deletes a media file we uploaded (by its download URL) from the image-store repo's
 * rich-media release, so removed/replaced media doesn't accumulate as orphans. No-op
 * for URLs not hosted by us. Best-effort — never throws.
 */
internal suspend fun githubDeleteFileByUrl(url: String, pat: String): Unit =
    withContext(Dispatchers.IO) {
        if (pat.isBlank() || !url.startsWith(MEDIA_DL_PREFIX)) return@withContext
        val name = url.removePrefix(MEDIA_DL_PREFIX)
        if (name.isBlank()) return@withContext
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            // Find the asset id by name from the release, then delete it.
            val getReq = Request.Builder()
                .url("https://api.github.com/repos/$IMG_REPO_OWNER/$IMG_REPO_NAME/releases/tags/$MEDIA_RELEASE_TAG")
                .header("Authorization", "Bearer $pat")
                .header("Accept", "application/vnd.github+json")
                .get()
                .build()
            val getResp = client.newCall(getReq).execute()
            val getBody = getResp.body?.string().orEmpty()
            val getCode = getResp.code
            getResp.close()
            if (getCode != 200) return@withContext
            val assets = JSONObject(getBody).optJSONArray("assets") ?: return@withContext
            var assetId = -1L
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name") == name) { assetId = a.optLong("id", -1L); break }
            }
            if (assetId < 0) return@withContext
            val delReq = Request.Builder()
                .url("https://api.github.com/repos/$IMG_REPO_OWNER/$IMG_REPO_NAME/releases/assets/$assetId")
                .header("Authorization", "Bearer $pat")
                .header("Accept", "application/vnd.github+json")
                .delete()
                .build()
            client.newCall(delReq).execute().close()
        } catch (_: Exception) {
        }
    }

/** Best-effort deletes a batch of our-repo media URLs from the image-store repo. */
internal suspend fun githubDeleteMedia(urls: List<String>, pat: String) {
    for (u in urls) githubDeleteFileByUrl(u, pat)
}

/**
 * Full orphan sweep: lists every file in the image-store `rich/` folder and deletes
 * any whose raw URL isn't referenced by an announcement or release. Returns the count
 * deleted. Owner-only (lists announcements + releases). Best-effort.
 */
internal suspend fun githubSweepOrphans(db: FirebaseFirestore, pat: String): Int =
    withContext(Dispatchers.IO) {
        if (pat.isBlank()) return@withContext 0
        val referenced = HashSet<String>()
        runCatching {
            for (d in db.collection("announcements").get().await().documents) {
                resolveRichDoc(d.getString("bodyDoc"), d.getString("body"))?.mediaUrls()
                    ?.let { referenced.addAll(it) }
            }
        }
        runCatching {
            for (d in db.collection("releases").get().await().documents) {
                resolveRichDoc(d.getString("bodyDoc"), d.getString("notes"))?.mediaUrls()
                    ?.let { referenced.addAll(it) }
            }
        }
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            val getReq = Request.Builder()
                .url("https://api.github.com/repos/$IMG_REPO_OWNER/$IMG_REPO_NAME/releases/tags/$MEDIA_RELEASE_TAG")
                .header("Authorization", "Bearer $pat")
                .header("Accept", "application/vnd.github+json")
                .get()
                .build()
            val getResp = client.newCall(getReq).execute()
            val getBody = getResp.body?.string().orEmpty()
            val getCode = getResp.code
            getResp.close()
            if (getCode != 200) return@withContext 0
            val assets = JSONObject(getBody).optJSONArray("assets") ?: return@withContext 0
            var deleted = 0
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val dlUrl = a.optString("browser_download_url")
                val assetId = a.optLong("id", -1L)
                if (dlUrl.isBlank() || assetId < 0 || dlUrl in referenced) continue
                val delReq = Request.Builder()
                    .url("https://api.github.com/repos/$IMG_REPO_OWNER/$IMG_REPO_NAME/releases/assets/$assetId")
                    .header("Authorization", "Bearer $pat")
                    .header("Accept", "application/vnd.github+json")
                    .delete()
                    .build()
                val r = client.newCall(delReq).execute()
                if (r.isSuccessful) deleted++
                r.close()
            }
            deleted
        } catch (_: Exception) {
            0
        }
    }

@Composable
internal fun RichDocEditor(
    blocks: SnapshotStateList<RichBlock>,
    githubPat: String,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingImageIndex by remember { mutableIntStateOf(-1) }
    var pendingVideoIndex by remember { mutableIntStateOf(-1) }
    var pendingPosterIndex by remember { mutableIntStateOf(-1) }
    var uploadingIndex by remember { mutableIntStateOf(-1) }
    var uploadError by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val idx = pendingImageIndex
        pendingImageIndex = -1
        if (uri != null && idx in blocks.indices) {
            scope.launch {
                uploadError = null
                uploadingIndex = idx
                runCatching { githubUploadImage(ctx, uri, githubPat) }
                    .onSuccess { url ->
                        if (idx in blocks.indices) {
                            (blocks[idx] as? RichBlock.Image)?.let { blocks[idx] = it.copy(url = url) }
                        }
                    }
                    .onFailure { uploadError = it.message ?: "Upload failed" }
                uploadingIndex = -1
            }
        }
    }

    val posterPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val idx = pendingPosterIndex
        pendingPosterIndex = -1
        if (uri != null && idx in blocks.indices) {
            scope.launch {
                uploadError = null
                uploadingIndex = idx
                runCatching { githubUploadImage(ctx, uri, githubPat) }
                    .onSuccess { url ->
                        if (idx in blocks.indices) {
                            (blocks[idx] as? RichBlock.Video)?.let { blocks[idx] = it.copy(poster = url) }
                        }
                    }
                    .onFailure { uploadError = it.message ?: "Poster upload failed" }
                uploadingIndex = -1
            }
        }
    }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val idx = pendingVideoIndex
        pendingVideoIndex = -1
        if (uri != null && idx in blocks.indices) {
            scope.launch {
                uploadError = null
                uploadingIndex = idx
                runCatching {
                    // Admin build transcodes to small HEVC; public stub returns null →
                    // fall back to the raw file. The size guard keeps clips small so they cache fast.
                    val transcoded = transcodeVideoForUpload(ctx, uri)
                    val bytes = if (transcoded != null) {
                        val b = transcoded.readBytes(); runCatching { transcoded.delete() }; b
                    } else {
                        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: throw Exception("Could not read the selected video.")
                    }
                    if (bytes.size > 19_000_000) {
                        throw Exception(
                            "Video is ${bytes.size / 1_000_000}MB after compression — please use a " +
                                "shorter clip so it stays small and loads fast."
                        )
                    }
                    githubUploadVideoBytes(bytes, githubPat)
                }
                    .onSuccess { url ->
                        if (idx in blocks.indices) {
                            (blocks[idx] as? RichBlock.Video)?.let { blocks[idx] = it.copy(url = url) }
                        }
                    }
                    .onFailure { uploadError = it.message ?: "Video upload failed" }
                uploadingIndex = -1
            }
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Content blocks", style = MaterialTheme.typography.labelLarge)

        blocks.forEachIndexed { index, block ->
            BlockCard(
                index = index,
                count = blocks.size,
                block = block,
                uploading = uploadingIndex == index,
                onChange = { if (index in blocks.indices) blocks[index] = it },
                onMoveUp = {
                    if (index > 0) { val t = blocks[index]; blocks[index] = blocks[index - 1]; blocks[index - 1] = t }
                },
                onMoveDown = {
                    if (index < blocks.size - 1) { val t = blocks[index]; blocks[index] = blocks[index + 1]; blocks[index + 1] = t }
                },
                onDuplicate = { blocks.add(index + 1, block) },
                onDelete = { if (index in blocks.indices) blocks.removeAt(index) },
                onPickImage = { pendingImageIndex = index; picker.launch("image/*") },
                onPickVideo = { pendingVideoIndex = index; videoPicker.launch("video/*") },
                onPickPoster = { pendingPosterIndex = index; posterPicker.launch("image/*") }
            )
        }

        uploadError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Text("Add block", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AddBlockChip("Heading", Icons.Filled.Title) { blocks.add(RichBlock.Heading("")) }
            AddBlockChip("Text", Icons.AutoMirrored.Filled.Notes) { blocks.add(RichBlock.Text("")) }
            AddBlockChip("Bullets", Icons.AutoMirrored.Filled.FormatListBulleted) { blocks.add(RichBlock.Bullets(listOf(""))) }
            AddBlockChip("Image", Icons.Filled.Image) { blocks.add(RichBlock.Image("")) }
            AddBlockChip("Callout", Icons.Filled.Info) { blocks.add(RichBlock.Callout("info", "")) }
            AddBlockChip("Video", Icons.Filled.Videocam) { blocks.add(RichBlock.Video("", null)) }
            AddBlockChip("Divider", Icons.Filled.HorizontalRule) { blocks.add(RichBlock.Divider) }
        }

        if (blocks.isNotEmpty()) {
            Text("Preview", style = MaterialTheme.typography.labelLarge)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(Modifier.padding(12.dp)) {
                    RichDocRenderer(
                        RichDoc(blocks = blocks.toList()),
                        mediaScope = RichMediaStore.Scope.ANNOUNCEMENT
                    )
                }
            }
        }
    }
}

private fun blockLabel(b: RichBlock): String = when (b) {
    is RichBlock.Heading -> "Heading"
    is RichBlock.Text -> "Text"
    is RichBlock.Bullets -> "Bullets"
    is RichBlock.Image -> "Image"
    is RichBlock.Video -> "Video"
    is RichBlock.Callout -> "Callout"
    RichBlock.Divider -> "Divider"
}

@Composable
private fun AddBlockChip(label: String, icon: ImageVector, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
        Icon(icon, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}

@Composable
private fun BlockCard(
    index: Int,
    count: Int,
    block: RichBlock,
    uploading: Boolean,
    onChange: (RichBlock) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onPickImage: () -> Unit,
    onPickVideo: () -> Unit,
    onPickPoster: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${index + 1}. ${blockLabel(block)}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onMoveUp, enabled = index > 0) {
                    Icon(Icons.Filled.ArrowUpward, "Move up")
                }
                IconButton(onClick = onMoveDown, enabled = index < count - 1) {
                    Icon(Icons.Filled.ArrowDownward, "Move down")
                }
                IconButton(onClick = onDuplicate) { Icon(Icons.Filled.ContentCopy, "Duplicate") }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete") }
            }

            when (block) {
                is RichBlock.Heading -> {
                    OutlinedTextField(
                        block.text, { onChange(block.copy(text = it)) },
                        Modifier.fillMaxWidth(), label = { Text("Heading text") }, singleLine = true
                    )
                    OutlinedTextField(
                        block.color ?: "", { onChange(block.copy(color = it.ifBlank { null })) },
                        Modifier.fillMaxWidth(), label = { Text("Color hex, optional (e.g. #4CAF50)") }, singleLine = true
                    )
                }
                is RichBlock.Text -> {
                    OutlinedTextField(
                        block.text, { onChange(block.copy(text = it)) },
                        Modifier.fillMaxWidth(), label = { Text("Text") }, minLines = 2
                    )
                    Text(
                        "**bold**  *italic*  [c=#ff5555]color[/c]  — links auto-detected",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is RichBlock.Bullets -> {
                    block.items.forEachIndexed { j, item ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                item,
                                { newV -> onChange(block.copy(items = block.items.toMutableList().also { it[j] = newV })) },
                                Modifier.weight(1f), label = { Text("Item ${j + 1}") }, singleLine = true
                            )
                            IconButton(onClick = {
                                val next = block.items.toMutableList().also { it.removeAt(j) }
                                onChange(block.copy(items = next.ifEmpty { listOf("") }))
                            }) { Icon(Icons.Filled.Delete, "Remove item") }
                        }
                    }
                    OutlinedButton(onClick = { onChange(block.copy(items = block.items + "")) }) {
                        Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add item")
                    }
                }
                is RichBlock.Image -> {
                    Button(onClick = onPickImage, enabled = !uploading) {
                        if (uploading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp)); Text("Uploading")
                        } else {
                            Icon(Icons.Filled.Image, null); Spacer(Modifier.width(6.dp))
                            Text(if (block.url.isBlank()) "Pick & upload image" else "Replace image")
                        }
                    }
                }
                is RichBlock.Video -> {
                    Button(onClick = onPickVideo, enabled = !uploading) {
                        if (uploading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp)); Text("Processing")
                        } else {
                            Icon(Icons.Filled.Videocam, null); Spacer(Modifier.width(6.dp))
                            Text(if (block.url.isBlank()) "Pick & upload video" else "Replace video")
                        }
                    }
                    OutlinedButton(onClick = onPickPoster, enabled = !uploading) {
                        Icon(Icons.Filled.Image, null); Spacer(Modifier.width(6.dp))
                        Text(if (block.poster.isNullOrBlank()) "Add poster image" else "Replace poster")
                    }
                }
                is RichBlock.Callout -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("info", "warn", "success").forEach { tone ->
                            if (block.tone == tone) {
                                Button(onClick = {}, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) { Text(tone) }
                            } else {
                                OutlinedButton(
                                    onClick = { onChange(block.copy(tone = tone)) },
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
                                ) { Text(tone) }
                            }
                        }
                    }
                    OutlinedTextField(
                        block.text, { onChange(block.copy(text = it)) },
                        Modifier.fillMaxWidth(), label = { Text("Callout text") }, minLines = 2
                    )
                }
                RichBlock.Divider -> Divider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}
