package com.vrca.richcontent

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
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
@Volatile
private var richGifLoader: coil.ImageLoader? = null
private val richGifLoaderLock = Any()

/** Max on-screen height for any single media block, so a tall (portrait) image /
 *  gif / video is sized down instead of taking up the whole screen. */
private val MAX_MEDIA_HEIGHT = 380.dp

/**
 * Coil loader with the GIF decoder registered, so uploaded .gif images animate.
 * Disk + memory caches are CAPPED (its own `rich_image_cache` dir so it doesn't
 * collide with [com.vrca.admin.VrchatImageLoader]'s `image_cache`) — without a
 * cap Coil defaults its disk cache to 2% of free space (hundreds of MB), a silent
 * cache grower. Most rich media loads from the local [RichMediaStore] file anyway;
 * this only bounds the network-fallback path.
 */
private fun richImageLoader(ctx: Context): coil.ImageLoader =
    richGifLoader ?: synchronized(richGifLoaderLock) {
        richGifLoader ?: coil.ImageLoader.Builder(ctx.applicationContext)
            .components {
                if (android.os.Build.VERSION.SDK_INT >= 28) add(coil.decode.ImageDecoderDecoder.Factory())
                else add(coil.decode.GifDecoder.Factory())
            }
            .memoryCache {
                coil.memory.MemoryCache.Builder(ctx.applicationContext)
                    .maxSizeBytes(12 * 1024 * 1024)
                    .build()
            }
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(ctx.applicationContext.cacheDir.resolve("rich_image_cache"))
                    .maxSizeBytes(15L * 1024 * 1024)
                    .build()
            }
            .build()
            .also { richGifLoader = it }
    }

/** Renders one image/gif block: full-width when it has one URL, or SIDE BY SIDE
 *  (each half width) when the admin added a second image to the same block. */
@Composable
private fun RichImageOrPair(url: String, url2: String?, scope: RichMediaStore.Scope, animate: Boolean) {
    if (!url2.isNullOrBlank()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            RichImage(url, scope, animate, Modifier.weight(1f))
            RichImage(url2, scope, animate, Modifier.weight(1f))
        }
    } else {
        RichImage(url, scope, animate)
    }
}

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
                is RichBlock.Image -> RichImageOrPair(block.url, block.url2, mediaScope, animate = false)
                is RichBlock.Gif -> RichImageOrPair(block.url, block.url2, mediaScope, animate = true)
                is RichBlock.Video -> RichVideo(block, mediaScope)
                is RichBlock.Callout -> RichCallout(block)
                RichBlock.Divider -> Divider(
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
        }
    }
}

/**
 * Text with inline markup (bold/italic/color) + tappable auto-linkified URLs.
 *
 * Renders each styled run as its OWN `Text` with **parameter-level** fontWeight /
 * fontStyle / color on `FontFamily.SansSerif` (a real font with bold/italic glyphs) —
 * NOT `SpanStyle`. Some devices (Samsung system fonts) silently ignore per-span
 * weight/style (only color came through), so this is the reliable path. Runs flow +
 * wrap via `FlowRow`; `\n` splits into stacked rows so multi-line text stays intact.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RichText(raw: String, style: TextStyle, color: Color) {
    val runs = remember(raw) { buildInlineRuns(raw) }
    val uriHandler = LocalUriHandler.current
    val linkColor = MaterialTheme.colorScheme.primary
    val richStyle = style.copy(fontFamily = FontFamily.SansSerif)

    val lines = remember(runs) {
        val out = ArrayList<ArrayList<InlineRun>>()
        var cur = ArrayList<InlineRun>()
        for (run in runs) {
            val parts = run.text.split("\n")
            parts.forEachIndexed { idx, part ->
                if (idx > 0) { out.add(cur); cur = ArrayList() }
                if (part.isNotEmpty()) cur.add(run.copy(text = part))
            }
        }
        out.add(cur)
        out
    }

    Column(Modifier.fillMaxWidth()) {
        for (line in lines) {
            if (line.isEmpty()) {
                Text(" ", color = color, style = richStyle)  // preserve blank lines
            } else {
                FlowRow(Modifier.fillMaxWidth()) {
                    for (run in line) {
                        val url = run.url
                        Text(
                            text = run.text,
                            color = if (url != null) linkColor else run.color ?: color,
                            fontWeight = if (run.bold) FontWeight.Bold else null,
                            fontStyle = if (run.italic) FontStyle.Italic else null,
                            textDecoration = if (url != null) TextDecoration.Underline else null,
                            style = richStyle,
                            modifier = if (url != null) {
                                Modifier.clickable { runCatching { uriHandler.openUri(url) } }
                            } else Modifier
                        )
                    }
                }
            }
        }
    }
}

/** Tinted highlight box. tone = info (blue) / warn (red-orange) / success (green). */
@Composable
private fun RichCallout(block: RichBlock.Callout) {
    val accent = when (block.tone.lowercase()) {
        "success" -> Color(0xFF22C55E)          // clear green
        "warn", "warning" -> Color(0xFFF4511E)  // red-orange (reads as a warning)
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

/**
 * Full-width image, own intrinsic ratio, local-file-first, tap → pinch-zoom.
 *
 * When [animate] is true (GIF blocks) a per-frame ticker forces the image to redraw
 * continuously while it's on screen. This is REQUIRED because the announcement card
 * wraps its content in `AnimatedVisibility`: once the expand animation settles,
 * nothing forces a redraw, so Coil's `AnimatedImageDrawable` frames stop advancing
 * until an external redraw (scroll/touch) — the "GIF freezes until I touch the
 * screen" bug. Reading the ticker inside `drawWithContent` re-runs the draw each
 * frame (no recomposition), so the drawable's advancing frames show. The ticker only
 * runs while the composable is present (i.e. the card is expanded).
 */
@Composable
private fun RichImage(
    url: String,
    scope: RichMediaStore.Scope,
    animate: Boolean = false,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    if (url.isBlank()) return  // unfilled editor block — nothing to show
    var file by remember(url) { mutableStateOf(RichMediaStore.resolve(ctx, url)) }
    var fullscreen by rememberSaveable(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        if (file == null && url.isNotBlank()) {
            RichMediaStore.ensureCached(ctx, url, scope)
            file = RichMediaStore.resolve(ctx, url)
        }
    }

    val frameTick = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    if (animate) {
        LaunchedEffect(Unit) {
            while (true) {
                androidx.compose.runtime.withFrameMillis { }
                frameTick.intValue++
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .clickable { fullscreen = true }
    ) {
        // model = local file once cached, else the URL (Coil network fallback).
        // ContentScale.Fit + a max-height cap so a TALL (portrait) image/gif is
        // sized down (letterboxed, centered) instead of taking up the whole screen;
        // a wide image still fills the width.
        coil.compose.AsyncImage(
            model = file ?: url,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            imageLoader = richImageLoader(ctx),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = MAX_MEDIA_HEIGHT)
                .then(
                    if (animate) Modifier.drawWithContent {
                        // Read the tick INSIDE the draw lambda so the draw phase re-runs
                        // every frame; drawContent() then re-rasterises the drawable's
                        // current (advanced) frame even with zero recomposition.
                        frameTick.intValue
                        drawContent()
                    } else Modifier
                )
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
                    painter = coil.compose.rememberAsyncImagePainter(
                        model = file ?: url,
                        imageLoader = richImageLoader(ctx)
                    ),
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
 * Media3 ExoPlayer (reliable inside Compose; VideoView's SurfaceView rendered
 * black). Single active player — a new play stops any other. The player is released
 * when it leaves the composition (DisposableEffect) or another video takes over.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun RichVideo(block: RichBlock.Video, mediaScope: RichMediaStore.Scope) {
    val ctx = LocalContext.current
    val token = remember(block.url) { Any() }
    val playing = ActiveVideoState.playingKey === token
    val posterModel = block.poster?.takeIf { it.isNotBlank() }?.let { RichMediaStore.resolve(ctx, it) ?: it }
    // Prefetch the video into the local cache as soon as the card renders, so tapping
    // play uses a LOCAL file (instant, no buffering) even on a bad connection.
    var localFile by remember(block.url) { mutableStateOf(RichMediaStore.resolve(ctx, block.url)) }
    LaunchedEffect(block.url) {
        if (block.url.isNotBlank() && localFile == null) {
            RichMediaStore.ensureCached(ctx, block.url, mediaScope)
            localFile = RichMediaStore.resolve(ctx, block.url)
        }
    }

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
                var playFailed by remember(block.url) { mutableStateOf(false) }
                var aspect by remember(block.url) { mutableStateOf(16f / 9f) }
                val lf = localFile
                val playUri = if (lf != null) android.net.Uri.fromFile(lf)
                    else android.net.Uri.parse(block.url)
                // ExoPlayer (Media3) rendering into a TextureView plays reliably inside
                // Compose where VideoView's SurfaceView rendered black.
                val exo = remember(playUri) {
                    androidx.media3.exoplayer.ExoPlayer.Builder(ctx).build().apply {
                        setMediaItem(androidx.media3.common.MediaItem.fromUri(playUri))
                        prepare()
                        playWhenReady = true
                        addListener(object : androidx.media3.common.Player.Listener {
                            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                                playFailed = true
                            }
                            override fun onVideoSizeChanged(vs: androidx.media3.common.VideoSize) {
                                if (vs.width > 0 && vs.height > 0) {
                                    val par = if (vs.pixelWidthHeightRatio > 0f) vs.pixelWidthHeightRatio else 1f
                                    aspect = (vs.width * par) / vs.height
                                }
                            }
                            override fun onPlaybackStateChanged(state: Int) {
                                if (state == androidx.media3.common.Player.STATE_ENDED) {
                                    ActiveVideoState.playingKey = null
                                }
                            }
                        })
                    }
                }
                DisposableEffect(exo) { onDispose { runCatching { exo.release() } } }
                AndroidView(
                    factory = { c ->
                        android.view.TextureView(c).also { tv -> exo.setVideoTextureView(tv) }
                    },
                    // Cap height so a tall (portrait) video is sized down instead of
                    // filling the screen; matchHeightConstraintsFirst lets a tall clip fit
                    // the height cap (narrower, centered) while a wide clip fills the width.
                    modifier = Modifier
                        .heightIn(max = MAX_MEDIA_HEIGHT)
                        .aspectRatio(aspect.coerceIn(0.5f, 2.5f), matchHeightConstraintsFirst = true)
                )
                if (playFailed) {
                    Text(
                        "Couldn't play this video.",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            } else {
                if (posterModel != null) {
                    coil.compose.AsyncImage(
                        model = posterModel,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = MAX_MEDIA_HEIGHT)
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
