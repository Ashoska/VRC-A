package com.vrca.richcontent

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Renders a [RichDoc] as a responsive vertical block stack. Shared by the Update
 * popup, Settings "What's New", and the Announcements cards.
 *
 * Phase 1 = images fully wired (Coil, local-file-first via [RichMediaStore], tap →
 * pinch-zoom). Video renders an inert poster placeholder; real playback lands in
 * Phase 5. Text supports **bold** / *italic* / [c=#hex] color and clickable URLs.
 *
 * [mediaScope] tells [RichImage] where to cache — announcements pass ANNOUNCEMENT
 * (persistent, admin-controlled cull), the update popup passes UPDATE (ephemeral).
 */
@Composable
fun RichDocRenderer(
    doc: RichDoc,
    modifier: Modifier = Modifier,
    mediaScope: RichMediaStore.Scope = RichMediaStore.Scope.ANNOUNCEMENT
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        doc.blocks.forEach { block ->
            when (block) {
                is RichBlock.Heading -> {
                    val color = parseHexColor(block.color) ?: MaterialTheme.colorScheme.primary
                    Text(
                        block.text,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = color
                    )
                }
                is RichBlock.Text -> RichText(
                    raw = block.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                is RichBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.items.forEach { item ->
                        Row {
                            Text(
                                "•  ",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            RichText(
                                raw = item,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                is RichBlock.Image -> RichImage(block.url, mediaScope)
                is RichBlock.Video -> RichVideo(block)
                is RichBlock.Callout -> RichCallout(block)
                RichBlock.Divider -> Divider(
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
        }
    }
}

/**
 * Text with inline markup + clickable auto-linkified URLs. Uses `Text` (which renders
 * every SpanStyle — bold/italic/color — reliably) plus tap-to-open, instead of the
 * deprecated `ClickableText` (which wasn't applying the bold/italic spans on-device).
 */
@Composable
private fun RichText(raw: String, style: TextStyle, color: Color) {
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(raw, linkColor) { buildInlineAnnotated(raw, linkColor) }
    val uriHandler = LocalUriHandler.current
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = annotated,
        style = style.copy(color = color),
        onTextLayout = { layout = it },
        modifier = Modifier.pointerInput(annotated) {
            detectTapGestures { pos ->
                val lr = layout ?: return@detectTapGestures
                annotated.urlAt(lr.getOffsetForPosition(pos))?.let {
                    runCatching { uriHandler.openUri(it) }
                }
            }
        }
    )
}

/** Tinted highlight box. tone = info (primary) / warn (amber) / success (green). */
@Composable
private fun RichCallout(block: RichBlock.Callout) {
    val accent = when (block.tone.lowercase()) {
        "success" -> Color(0xFF22C55E)          // clear green
        "warn", "warning" -> Color(0xFFF59E0B)  // clear amber/orange
        else -> Color(0xFF3B82F6)               // clear blue (info)
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = accent.copy(alpha = 0.18f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(vertical = 10.dp, horizontal = 12.dp)) {
            Box(
                Modifier
                    .width(4.dp)
                    .heightIn(min = 20.dp)
                    .background(accent, RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.width(10.dp))
            RichText(
                raw = block.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** Full-width image, own intrinsic ratio, local-file-first, tap → pinch-zoom. */
@Composable
private fun RichImage(url: String, scope: RichMediaStore.Scope) {
    val ctx = LocalContext.current
    var file by remember(url) { mutableStateOf(RichMediaStore.resolve(ctx, url)) }
    var fullscreen by rememberSaveable(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        if (file == null && url.isNotBlank()) {
            RichMediaStore.ensureCached(ctx, url, scope)
            file = RichMediaStore.resolve(ctx, url)
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { fullscreen = true }
    ) {
        // model = local file once cached, else the URL (Coil network fallback).
        coil.compose.AsyncImage(
            model = file ?: url,
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth()
        )
    }

    if (fullscreen) {
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            var scale by rememberSaveable { mutableStateOf(1f) }
            var offsetX by rememberSaveable { mutableStateOf(0f) }
            var offsetY by rememberSaveable { mutableStateOf(0f) }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.92f))
                    .clickable { fullscreen = false }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offsetX += pan.x
                            offsetY += pan.y
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = coil.compose.rememberAsyncImagePainter(model = file ?: url),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY
                        )
                )
            }
        }
    }
}

/**
 * Process-scoped "which video is playing" token so only ONE video plays at a time
 * (Phase 5). Starting a video sets [playingKey] to that card's token; every other
 * RichVideo observes it and drops back to its poster (releasing its player).
 */
private object ActiveVideoState {
    var playingKey by mutableStateOf<Any?>(null)
}

/**
 * Video block (Phase 5): poster + play button; tap plays inline WITH AUDIO via the
 * built-in VideoView (zero dependencies). Single active player — a new play stops
 * any other. The player is released when it leaves the composition (onRelease) or
 * another video takes over.
 */
@Composable
private fun RichVideo(block: RichBlock.Video) {
    val ctx = LocalContext.current
    val token = remember(block.url) { Any() }
    val playing = ActiveVideoState.playingKey === token
    val posterModel = block.poster?.let { RichMediaStore.resolve(ctx, it) ?: it }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 200.dp),
            contentAlignment = Alignment.Center
        ) {
            if (playing && block.url.isNotBlank()) {
                var prepared by remember(block.url) { mutableStateOf(false) }
                var failed by remember(block.url) { mutableStateOf(false) }
                AndroidView(
                    factory = { c ->
                        android.widget.VideoView(c).apply {
                            setVideoURI(android.net.Uri.parse(block.url))
                            setOnPreparedListener { mp -> prepared = true; mp.isLooping = false; start() }
                            setOnCompletionListener { ActiveVideoState.playingKey = null }
                            setOnErrorListener { _, _, _ -> failed = true; true }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
                    onRelease = { runCatching { it.stopPlayback() } }
                )
                // Buffering feedback (matters on slow connections — progressive play
                // starts before the whole file arrives).
                if (!prepared && !failed) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(36.dp))
                }
                if (failed) {
                    Text(
                        "Couldn't play this video.",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                if (posterModel != null) {
                    coil.compose.AsyncImage(
                        model = posterModel,
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.45f),
                    modifier = Modifier
                        .size(64.dp)
                        .clickable { if (block.url.isNotBlank()) ActiveVideoState.playingKey = token }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.PlayCircle,
                            contentDescription = "Play",
                            tint = Color.White,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }
            }
        }
    }
}
