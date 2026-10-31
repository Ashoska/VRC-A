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
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
 * (drag-and-drop is a later polish). Media uploads go to the Worker (Cloudflare R2
 * `media/rich/`, Worker v54) with a UNIQUE filename per upload (never overwrite — a fresh URL
 * = live edits show instantly and the old URL falls out of the reference set → auto-culled
 * client-side).
 *
 * Firestore cost: ZERO. The editor is pure local state; media goes to Cloudflare, and
 * saving the doc is the caller's single Firestore write.
 */

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

private fun mimeForExt(ext: String): String = when (ext.lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "mp4" -> "video/mp4"
    else -> "application/octet-stream"
}

/** The admin key the Bots tab saved (the Worker's ADMIN_KEY); media uploads and deletes need it. */
private fun workerAdminKey(ctx: Context): String =
    ctx.getSharedPreferences("vrca_admin_local", Context.MODE_PRIVATE).getString("avatar_admin_key", "").orEmpty()

private val mediaClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}

private fun mediaEndpoint(key: String, name: String? = null): String =
    "${com.vrca.vrchat.AvatarGlobalDb.WORKER_URL}/admin/media?key=${java.net.URLEncoder.encode(key, "UTF-8")}" +
        (name?.let { "&name=$it" } ?: "")

/** The file name of media we host: a Cloudflare copy, or an upload to the old GitHub rich-media release
 *  (copied to Cloudflare under the same name by Worker v54). Null for anything else. */
private fun ourMediaName(url: String): String? =
    (com.vrca.richcontent.RichMediaStore.cdnCopyOf(url) ?: url)
        .takeIf { it.startsWith(com.vrca.richcontent.RichMediaStore.CDN_RICH_PREFIX) }
        ?.removePrefix(com.vrca.richcontent.RichMediaStore.CDN_RICH_PREFIX)?.takeIf { it.isNotBlank() }

/** Uploads raw bytes to the Worker (R2 `media/rich/`, unique name per upload); returns the public URL. */
private suspend fun uploadMedia(ctx: Context, bytes: ByteArray, ext: String): String =
    withContext(Dispatchers.IO) {
        val key = workerAdminKey(ctx)
        if (key.isBlank()) throw Exception("Admin key missing: set it in the Bots tab.")
        val name = "rich_${System.currentTimeMillis()}.$ext"   // unique — never overwrite
        val req = Request.Builder().url(mediaEndpoint(key, name))
            .post(bytes.toRequestBody(mimeForExt(ext).toMediaType())).build()
        mediaClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw Exception("Upload failed (${resp.code}): ${body.take(300)}")
            JSONObject(body).getString("url")
        }
    }

internal suspend fun uploadMediaImage(ctx: Context, uri: Uri): String =
    withContext(Dispatchers.IO) {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw Exception("Could not read the selected image.")
        uploadMedia(ctx, bytes, extFor(ctx, uri))
    }

/** Uploads an already-transcoded (or raw) video's bytes as `.mp4`. */
internal suspend fun uploadMediaVideoBytes(ctx: Context, bytes: ByteArray): String =
    uploadMedia(ctx, bytes, "mp4")

/** Best-effort deletes media we host (removed/replaced in an edit) so it doesn't pile up. Never throws. */
internal suspend fun deleteMedia(ctx: Context, urls: List<String>): Unit =
    withContext(Dispatchers.IO) {
        val key = workerAdminKey(ctx).ifBlank { return@withContext }
        for (u in urls) {
            val name = ourMediaName(u) ?: continue
            runCatching { mediaClient.newCall(Request.Builder().url(mediaEndpoint(key, name)).delete().build()).execute().close() }
        }
    }

/**
 * Full orphan sweep: lists every hosted media file and deletes any that no announcement or release
 * references. Returns the count deleted. Owner-only (lists announcements + releases). Best-effort.
 */
internal suspend fun sweepMediaOrphans(ctx: Context, db: FirebaseFirestore): Int =
    withContext(Dispatchers.IO) {
        val key = workerAdminKey(ctx).ifBlank { return@withContext 0 }
        val referenced = HashSet<String>()
        // A failed read must never look like "nothing is referenced" (that would delete everything).
        try {
            for (d in db.collection("announcements").get().await().documents) {
                resolveRichDoc(d.getString("bodyDoc"), d.getString("body"))?.mediaUrls()
                    ?.forEach { u -> ourMediaName(u)?.let(referenced::add) }
            }
            for (d in db.collection("releases").get().await().documents) {
                resolveRichDoc(d.getString("bodyDoc"), d.getString("notes"))?.mediaUrls()
                    ?.forEach { u -> ourMediaName(u)?.let(referenced::add) }
            }
        } catch (_: Exception) { return@withContext 0 }
        try {
            val names = mediaClient.newCall(Request.Builder().url(mediaEndpoint(key)).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext 0
                JSONObject(resp.body?.string().orEmpty()).optJSONArray("names") ?: return@withContext 0
            }
            var deleted = 0
            for (i in 0 until names.length()) {
                val n = names.optString(i)
                if (n.isBlank() || n in referenced) continue
                val ok = mediaClient.newCall(Request.Builder().url(mediaEndpoint(key, n)).delete().build()).execute()
                    .use { it.isSuccessful }
                if (ok) deleted++
            }
            deleted
        } catch (_: Exception) { 0 }
    }

@Composable
internal fun RichDocEditor(
    blocks: SnapshotStateList<RichBlock>,
    editorKey: String,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    // Saveable so the picker callback lands on the right block after the Activity
    // is recreated on return from the file picker.
    var pendingImageIndex by rememberSaveable { mutableIntStateOf(-1) }
    var pendingVideoIndex by rememberSaveable { mutableIntStateOf(-1) }
    var pendingPosterIndex by rememberSaveable { mutableIntStateOf(-1) }
    var pendingGifIndex by rememberSaveable { mutableIntStateOf(-1) }
    // Which slot (0 = primary, 1 = the optional second side-by-side image/gif).
    var pendingImageSlot by rememberSaveable { mutableIntStateOf(0) }
    var pendingGifSlot by rememberSaveable { mutableIntStateOf(0) }
    // Uploads run on AdminRuntime.scope (process lifetime) so they COMPLETE across
    // the Activity recreation the picker triggers (a composition scope would be
    // cancelled mid-upload → the block never gets its URL). Progress/errors are read
    // back from AdminRuntime so they survive the recreation too.
    val uploadingTags by AdminRuntime.uploadingTagsState.collectAsState()
    val uploadErrors by AdminRuntime.uploadErrorsState.collectAsState()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val idx = pendingImageIndex
        val slot = pendingImageSlot
        pendingImageIndex = -1; pendingImageSlot = 0
        if (uri != null && idx in blocks.indices) {
            AdminRuntime.uploadMediaBlock(ctx.applicationContext, editorKey, idx, uri, AdminRuntime.MediaKind.IMAGE, slot)
        }
    }

    val posterPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val idx = pendingPosterIndex
        pendingPosterIndex = -1
        if (uri != null && idx in blocks.indices) {
            AdminRuntime.uploadMediaBlock(ctx.applicationContext, editorKey, idx, uri, AdminRuntime.MediaKind.POSTER)
        }
    }

    val gifPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val idx = pendingGifIndex
        val slot = pendingGifSlot
        pendingGifIndex = -1; pendingGifSlot = 0
        if (uri != null && idx in blocks.indices) {
            AdminRuntime.uploadMediaBlock(ctx.applicationContext, editorKey, idx, uri, AdminRuntime.MediaKind.GIF, slot)
        }
    }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val idx = pendingVideoIndex
        pendingVideoIndex = -1
        if (uri != null && idx in blocks.indices) {
            AdminRuntime.uploadMediaBlock(ctx.applicationContext, editorKey, idx, uri, AdminRuntime.MediaKind.VIDEO)
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Content blocks", style = MaterialTheme.typography.labelLarge)

        blocks.forEachIndexed { index, block ->
            BlockCard(
                index = index,
                count = blocks.size,
                block = block,
                uploading = "$editorKey#$index" in uploadingTags,
                onChange = { if (index in blocks.indices) blocks[index] = it },
                onMoveUp = {
                    if (index > 0) { val t = blocks[index]; blocks[index] = blocks[index - 1]; blocks[index - 1] = t }
                },
                onMoveDown = {
                    if (index < blocks.size - 1) { val t = blocks[index]; blocks[index] = blocks[index + 1]; blocks[index + 1] = t }
                },
                onDuplicate = { blocks.add(index + 1, block) },
                onDelete = { if (index in blocks.indices) blocks.removeAt(index) },
                onPickImage = { slot -> pendingImageIndex = index; pendingImageSlot = slot; picker.launch("image/*") },
                onPickVideo = { pendingVideoIndex = index; videoPicker.launch("video/*") },
                onPickPoster = { pendingPosterIndex = index; posterPicker.launch("image/*") },
                onPickGif = { slot -> pendingGifIndex = index; pendingGifSlot = slot; gifPicker.launch("image/*") }
            )
        }

        // Show any upload error for THIS editor's blocks.
        uploadErrors.entries.filter { it.key.startsWith("$editorKey#") }.forEach { (_, msg) ->
            Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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
            AddBlockChip("GIF", Icons.Filled.Gif) { blocks.add(RichBlock.Gif("")) }
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
    is RichBlock.Gif -> "GIF"
    is RichBlock.Video -> "Video"
    is RichBlock.Callout -> "Callout"
    RichBlock.Divider -> "Divider"
}

@Composable
private fun AddBlockChip(label: String, icon: ImageVector, onClick: () -> Unit) {
    com.vrca.ui.common.GremlinSecondaryButton(onClick = onClick, size = com.vrca.ui.common.GremlinButtonSize.Small) {
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
    onPickImage: (Int) -> Unit,
    onPickVideo: () -> Unit,
    onPickPoster: () -> Unit,
    onPickGif: (Int) -> Unit
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
                    com.vrca.ui.common.GremlinSecondaryButton(onClick = { onChange(block.copy(items = block.items + "")) }) {
                        Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add item")
                    }
                }
                is RichBlock.Image -> {
                    com.vrca.ui.common.GremlinButton(onClick = { onPickImage(0) }, enabled = !uploading) {
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
                    // Optional SECOND image → the two render side by side (each half width).
                    com.vrca.ui.common.GremlinSecondaryButton(onClick = { onPickImage(1) }, enabled = !uploading) {
                        Icon(Icons.Filled.Image, null); Spacer(Modifier.width(6.dp))
                        Text(if (block.url2.isNullOrBlank()) "Add second image (side by side)" else "Replace second image")
                    }
                    if (!block.url2.isNullOrBlank()) {
                        com.vrca.ui.common.GremlinSecondaryButton(onClick = { onChange(block.copy(url2 = null)) }) {
                            Text("Remove second image")
                        }
                    }
                }
                is RichBlock.Gif -> {
                    OutlinedTextField(
                        block.url, { onChange(block.copy(url = it)) },
                        Modifier.fillMaxWidth(),
                        label = { Text("GIF link (direct .gif URL, e.g. media.giphy.com/...)") },
                        singleLine = true
                    )
                    com.vrca.ui.common.GremlinButton(onClick = { onPickGif(0) }, enabled = !uploading) {
                        if (uploading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp)); Text("Uploading")
                        } else {
                            Icon(Icons.Filled.Gif, null); Spacer(Modifier.width(6.dp))
                            Text(if (block.url.isBlank()) "Pick & upload GIF" else "Replace GIF")
                        }
                    }
                    // Optional SECOND gif → the two render side by side (each half width).
                    com.vrca.ui.common.GremlinSecondaryButton(onClick = { onPickGif(1) }, enabled = !uploading) {
                        Icon(Icons.Filled.Gif, null); Spacer(Modifier.width(6.dp))
                        Text(if (block.url2.isNullOrBlank()) "Add second GIF (side by side)" else "Replace second GIF")
                    }
                    if (!block.url2.isNullOrBlank()) {
                        com.vrca.ui.common.GremlinSecondaryButton(onClick = { onChange(block.copy(url2 = null)) }) {
                            Text("Remove second GIF")
                        }
                    }
                }
                is RichBlock.Video -> {
                    com.vrca.ui.common.GremlinButton(onClick = onPickVideo, enabled = !uploading) {
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
                    com.vrca.ui.common.GremlinSecondaryButton(onClick = onPickPoster, enabled = !uploading) {
                        Icon(Icons.Filled.Image, null); Spacer(Modifier.width(6.dp))
                        Text(if (block.poster.isNullOrBlank()) "Add poster image" else "Replace poster")
                    }
                }
                is RichBlock.Callout -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("info", "warn", "success").forEach { tone ->
                            if (block.tone == tone) {
                                com.vrca.ui.common.GremlinButton(onClick = {}, size = com.vrca.ui.common.GremlinButtonSize.Small) { Text(tone) }
                            } else {
                                com.vrca.ui.common.GremlinSecondaryButton(
                                    onClick = { onChange(block.copy(tone = tone)) },
                                    size = com.vrca.ui.common.GremlinButtonSize.Small
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
