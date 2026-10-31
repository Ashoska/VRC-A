package com.vrca.app

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vrca.ui.common.GremlinButton
import com.vrca.ui.common.GremlinButtonKind
import com.vrca.ui.common.GremlinButtonSize
import com.vrca.ui.common.widenIn
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.vrca.ui.theme.GremlinColors as G

/** The support server link (config/app.discordInvite), set by VrcaApp's config listener. */
val StandingSupportUrl = mutableStateOf("")

/** True while the standing popup is asked for (Settings, a disabled-feature line); VrcaApp shows it. */
val StandingPopupOpen = mutableStateOf(false)

/**
 * The line that takes a disabled control's place (approved mock 2026-10-31): the countdown Boot Cat draining, what
 * and how long; tapping it opens the standing popup. Shows nothing while [feature] is on. The control itself should
 * also be greyed and off ([Standing.isOff]).
 */
@Composable
fun StandingDisabledLine(feature: Standing.Feature, modifier: Modifier = Modifier) {
    val s = Standing.state.value
    Standing.tick.longValue
    val banned = Standing.banUntil(s)
    val until = banned ?: Standing.offUntil(feature, s) ?: return
    val name = if (banned != null) "VRC-A" else feature.label
    Row(
        modifier.clip(RoundedCornerShape(10.dp)).background(G.Caution.copy(alpha = 0.10f)).clickable { StandingPopupOpen.value = true }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BootCatCountdown(Standing.leftFraction(until, Standing.givenFor(if (banned != null) "" else feature.label, s)), Modifier.size(18.dp), G.Caution, smooth = false)
        Spacer(Modifier.width(8.dp))
        Text("$name disabled · " + Standing.leftText(until).replaceFirstChar { it.lowercase() }, style = MaterialTheme.typography.bodySmall,
            color = G.Caution, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(6.dp))
        Text("›", style = MaterialTheme.typography.bodyMedium, color = G.Caution)
    }
}

/**
 * The account standing popup (revamp 2, approved 2026-10-31): floats over whatever is underneath (the app, opened from
 * Settings or by itself when an action is new or changed; or the boot screen, which waits behind it). It opens like
 * the boot screen ([Unfold], SAO style): the screen darkens, each card unfolds from a line across its middle (standing
 * first, actions just after), the pieces widen in, the buttons last; Close or Back folds it shut, then [onClosed].
 * Banned: no Close, it stays. No actions on record: the standing card alone, centred. Everything shown is the live
 * [Standing.state], so an admin's edit (a hidden evidence file, a changed length) shows at once.
 */
@Composable
fun StandingPopup(onClosed: () -> Unit, animate: Boolean = true) {
    val ctx = LocalContext.current
    val s = Standing.state.value
    Standing.tick.longValue
    val banned = Standing.banUntil(s) != null
    val level = if (banned) Standing.Level.Banned else s?.level ?: Standing.Level.Good
    val actions = s?.actions.orEmpty()
    val start = if (animate) 0f else 1f
    val dim = remember { Animatable(start) }
    val panelL = remember { Animatable(start) }
    val panelR = remember { Animatable(start) }
    val partsL = remember { Animatable(start) }
    val partsR = remember { Animatable(start) }
    var closing by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Standing.Evidence?>(null) }
    LaunchedEffect(closing) {
        fun t(ms: Int) = tween<Float>(ms, easing = FastOutSlowInEasing)
        if (!closing) coroutineScope {
            if (dim.value >= 1f) return@coroutineScope
            launch { dim.animateTo(1f, t(450)) }
            delay(180); launch { panelL.animateTo(1f, t(700)) }
            delay(130); launch { panelR.animateTo(1f, t(700)) }
            delay(470); launch { partsL.animateTo(1f, tween(700)) }
            delay(130); partsR.animateTo(1f, tween(700))
        } else coroutineScope {
            launch { partsR.animateTo(0f, t(200)) }
            launch { partsL.animateTo(0f, t(200)) }
            delay(120); launch { panelR.animateTo(0f, t(420)) }
            delay(90); panelL.animateTo(0f, t(420))
            dim.animateTo(0f, t(300))
            Standing.markSeen(ctx, s)
            onClosed()
        }
    }
    val close = { if (!banned) closing = true }
    androidx.activity.compose.BackHandler(enabled = !closing) { if (viewing != null) viewing = null else close() }
    // A ban lifted while it's open (admin edit or the length ran out): it can be closed again.
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = dim.value }.background(Color.Black.copy(alpha = 0.82f))
            .clickable(interactionSource = null, indication = null) {})
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val wide = maxWidth >= 700.dp
            val uri = androidx.compose.ui.platform.LocalUriHandler.current
            val support = StandingSupportUrl.value
            val closeBtn: @Composable () -> Unit = { GremlinButton("Close", { close() }, size = GremlinButtonSize.Large) }
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            val supportBtn: @Composable () -> Unit = {
                // Always enabled (it used to grey out while the link hadn't loaded, which is always the case during
                // boot): without the link yet, it reads config/app once, then opens it.
                GremlinButton("Support server", {
                    if (support.isNotBlank()) runCatching { uri.openUri(support) }
                    else scope.launch {
                        val link = runCatching {
                            com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("config").document("app").get()
                                .await().getString("discordInvite")?.trim()
                        }.getOrNull().orEmpty()
                        if (link.isNotBlank()) { StandingSupportUrl.value = link; runCatching { uri.openUri(link) } }
                    }
                }, kind = GremlinButtonKind.Secondary, size = GremlinButtonSize.Large,
                    icon = ImageVector.vectorResource(com.vrca.R.drawable.ic_discord))
            }
            val btnL = Modifier.widenIn { stagger(partsL.value, 4, 5) }
            val btnR = Modifier.widenIn { stagger(partsR.value, 2, 3) }
            val scroll = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp)
            val onEvidence: (Standing.Evidence) -> Unit = { viewing = it }
            if (wide) Column(scroll, horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Unfold({ panelL.value }, Modifier.width(440.dp).fillMaxHeight()) {
                        StandingCard(level, s, { partsL.value }, Modifier.fillMaxSize())
                    }
                    if (actions.isNotEmpty()) Unfold({ panelR.value }, Modifier.width(440.dp).fillMaxHeight()) {
                        ActionsCard(actions, { partsR.value }, onEvidence, Modifier.fillMaxSize())
                    }
                }
                // The pair meets across the gap between the cards; one card only: its button centred under it.
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val one = actions.isEmpty()
                    Box(Modifier.width(440.dp).then(btnL), contentAlignment = if (one) Alignment.Center else Alignment.CenterEnd) { if (!banned) closeBtn() }
                    if (!one) Box(Modifier.width(440.dp).then(btnR), contentAlignment = Alignment.CenterStart) { supportBtn() }
                }
            } else Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().then(scroll), horizontalAlignment = Alignment.CenterHorizontally) {
                Unfold({ panelL.value }, Modifier.fillMaxWidth()) { StandingCard(level, s, { partsL.value }, Modifier.fillMaxWidth()) }
                if (actions.isNotEmpty()) Unfold({ panelR.value }, Modifier.padding(top = 16.dp).fillMaxWidth()) {
                    ActionsCard(actions, { partsR.value }, onEvidence, Modifier.fillMaxWidth())
                }
                Row(Modifier.padding(top = 12.dp).then(btnR), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (!banned) closeBtn()
                    if (actions.isNotEmpty()) supportBtn()
                }
            }
        }
        // A file the admin hides while it's open disappears at once (it's gone from the live standing).
        val v = viewing
        if (v != null && actions.any { a -> a.evidence.any { it.id == v.id } }) EvidenceViewer(v) { viewing = null }
        else if (v != null) viewing = null
    }
}

private fun stagger(reveal: Float, i: Int, n: Int): Float {
    val span = 0.45f
    val start = if (n <= 1) 0f else (1f - span) * i / (n - 1)
    return ((reveal - start) / span).coerceIn(0f, 1f)
}

private val dateFmt = java.text.SimpleDateFormat("d MMM", java.util.Locale.UK)
private val updatedFmt = java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.UK)

@Composable
private fun StandingCard(level: Standing.Level, s: Standing.State?, parts: () -> Float, modifier: Modifier) = Layer(modifier) {
    val off = Standing.disabled(s)
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp)) {
        Row(Modifier.widenIn { stagger(parts(), 0, 5) }, verticalAlignment = Alignment.CenterVertically) {
            BootCat(level.mark, 0, Modifier.size(52.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Account standing", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = G.Text)
                if (s != null && s.rev > 0) Text("Updated " + updatedFmt.format(java.util.Date(s.rev)), style = MaterialTheme.typography.bodyMedium, color = G.TextMuted)
            }
        }
        Column(Modifier.widenIn { stagger(parts(), 1, 5) }) {
            Text(level.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, color = level.color, modifier = Modifier.padding(top = 16.dp))
            Text(level.line, style = MaterialTheme.typography.bodyMedium, color = G.Text, modifier = Modifier.padding(top = 2.dp))
        }
        Meter(level, Modifier.padding(top = 14.dp).widenIn { stagger(parts(), 2, 5) })
        Text("Disabled", style = MaterialTheme.typography.labelLarge, color = G.TextMuted, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp).widenIn { stagger(parts(), 3, 5) })
        Column(Modifier.widenIn { stagger(parts(), 3, 5) }.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(G.Card).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (off.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                BootCat(Mark.Done, 0, Modifier.size(22.dp)); Spacer(Modifier.width(12.dp))
                Text("Nothing. Every feature is on.", style = MaterialTheme.typography.bodyMedium, color = G.Text)
            }
            off.forEach { (name, until) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BootCatCountdown(Standing.leftFraction(until, Standing.givenFor(name, s)), Modifier.size(22.dp), level.color, smooth = false)
                    Spacer(Modifier.width(12.dp))
                    Text(name, style = MaterialTheme.typography.bodyMedium, color = G.Text, modifier = Modifier.weight(1f))
                    Text(Standing.leftText(until), style = MaterialTheme.typography.bodySmall, color = G.TextMuted)
                }
            }
        }
    }
}

@Composable
private fun Meter(level: Standing.Level, modifier: Modifier) = Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Standing.Level.entries.forEach { l ->
        val on = l == level
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                .background(if (l.ordinal <= level.ordinal) l.color.copy(alpha = if (on) 1f else 0.35f) else G.Raised))
            Text(l.label, style = MaterialTheme.typography.bodySmall, color = if (on) G.Text else G.TextMuted, maxLines = 2,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun ActionsCard(actions: List<Standing.Action>, parts: () -> Float, onEvidence: (Standing.Evidence) -> Unit, modifier: Modifier) = Layer(modifier) {
    val (active, past) = actions.partition { it.active }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp)) {
        Row(Modifier.widenIn { stagger(parts(), 0, 3) }, verticalAlignment = Alignment.CenterVertically) {
            Text("Actions on your account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, color = G.Text, modifier = Modifier.weight(1f))
            if (active.isNotEmpty()) Text("${active.size} active", style = MaterialTheme.typography.bodySmall, color = G.TextMuted)
        }
        Column(Modifier.padding(top = 12.dp).widenIn { stagger(parts(), 1, 3) }.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(G.Card)) {
            (active + past).forEachIndexed { k, a ->
                if (k > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(G.Edge))
                // The newest active one starts open: it's usually why the popup opened.
                androidx.compose.runtime.key(a.id) { ActionRow(a, startOpen = k == 0 && a.active, onEvidence) }
            }
        }
    }
}

private fun kindLabel(k: String) = when (k) { "ban" -> "Ban"; "restriction" -> "Restriction"; else -> "Warning" }

@Composable
private fun ActionRow(a: Standing.Action, startOpen: Boolean, onEvidence: (Standing.Evidence) -> Unit) {
    var open by remember { mutableStateOf(startOpen) }
    val tone = if (!a.active) G.TextMuted else when (a.kind) { "ban" -> G.Alert; "restriction" -> G.Caution; else -> G.Warning }
    Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 14.dp, vertical = 11.dp).animateContentSize(tween(220))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(tone, CircleShape)); Spacer(Modifier.width(10.dp))
            Text(kindLabel(a.kind), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = tone)
            Spacer(Modifier.width(8.dp))
            Text(a.title, style = MaterialTheme.typography.bodyMedium, color = if (a.active) G.Text else G.TextMuted, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (a.given > 0) Text(dateFmt.format(java.util.Date(a.given)), style = MaterialTheme.typography.bodySmall, color = G.TextMuted)
            Spacer(Modifier.width(10.dp))
            val turn by animateFloatAsState(if (open) 180f else 0f, label = "chev")
            Canvas(Modifier.size(12.dp).graphicsLayer { rotationZ = turn }) {
                val w = 1.6.dp.toPx(); val c = G.TextMuted
                drawLine(c, Offset(size.width * 0.2f, size.height * 0.38f), Offset(size.width * 0.5f, size.height * 0.66f), w, StrokeCap.Round)
                drawLine(c, Offset(size.width * 0.5f, size.height * 0.66f), Offset(size.width * 0.8f, size.height * 0.38f), w, StrokeCap.Round)
            }
        }
        if (open) Column(Modifier.padding(start = 18.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (a.message.isNotBlank()) Text(a.message, style = MaterialTheme.typography.bodySmall, color = G.Text)
            if (a.evidence.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                a.evidence.forEach { e -> androidx.compose.runtime.key(e.id) { EvidenceThumb(e) { onEvidence(e) } } }
            }
            val names = a.off.keys.mapNotNull { Standing.Feature.of(it)?.label }
            if (a.kind == "ban") Text("Disabled everything in VRC-A", style = MaterialTheme.typography.bodySmall, color = G.TextMuted)
            else if (names.isNotEmpty()) Text("Disabled " + names.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = G.TextMuted)
        }
    }
}

// ---- evidence -------------------------------------------------------------------------------------------------
private fun evidenceUrl(id: String) = com.vrca.vrchat.AvatarGlobalDb.WORKER_URL + "/evidence/" + id
private fun isVideo(kind: String) = kind == "mp4" || kind == "webm"

/** Evidence loads with no disk cache: a file the admin hides or deletes leaves nothing on the phone. */
@Volatile private var evidenceLoader: coil.ImageLoader? = null
private fun evidenceImageLoader(ctx: android.content.Context): coil.ImageLoader = evidenceLoader ?: synchronized(StandingSupportUrl) {
    evidenceLoader ?: coil.ImageLoader.Builder(ctx.applicationContext)
        .components {
            if (android.os.Build.VERSION.SDK_INT >= 28) add(coil.decode.ImageDecoderDecoder.Factory()) else add(coil.decode.GifDecoder.Factory())
        }
        .memoryCache { coil.memory.MemoryCache.Builder(ctx.applicationContext).maxSizeBytes(8 * 1024 * 1024).build() }
        .diskCache(null)
        .build().also { evidenceLoader = it }
}

@Composable
private fun evidenceRequest(id: String): coil.request.ImageRequest {
    val ctx = LocalContext.current
    return remember(id) {
        coil.request.ImageRequest.Builder(ctx).data(evidenceUrl(id))
            .addHeader(com.vrca.vrchat.AvatarGlobalDb.CDN_APP_HEADER, com.vrca.vrchat.AvatarGlobalDb.CDN_APP_MARK)
            .diskCachePolicy(coil.request.CachePolicy.DISABLED).build()
    }
}

@Composable
private fun EvidenceThumb(e: Standing.Evidence, onClick: () -> Unit) {
    val ctx = LocalContext.current
    Box(Modifier.width(72.dp).aspectRatio(16f / 10f).clip(RoundedCornerShape(8.dp)).background(G.Panel)
        .border(1.dp, G.Edge, RoundedCornerShape(8.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        if (isVideo(e.kind)) BootIconView(BootIcons.Start, { G.TextMuted }, { G.TextMuted }, Modifier.size(22.dp))
        else coil.compose.AsyncImage(evidenceRequest(e.id), null, evidenceImageLoader(ctx), Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

/** One file, big, over the popup: a picture or GIF, or a video streamed (never saved). Tap or Back closes it. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun EvidenceViewer(e: Standing.Evidence, onClose: () -> Unit) {
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)).clickable(interactionSource = null, indication = null) { onClose() }
        .padding(24.dp), contentAlignment = Alignment.Center) {
        // Both kinds take the box of the file's own shape (width / height), fitted inside the screen: a phone-shaped
        // or square clip was stretched into a fixed 16:9 box before ("squished").
        var ratio by remember(e.id) { mutableStateOf(0f) }
        val fit = if (ratio > 0f) Modifier.aspectRatio(ratio) else Modifier.fillMaxSize()
        if (!isVideo(e.kind)) coil.compose.AsyncImage(evidenceRequest(e.id), null, evidenceImageLoader(ctx), fit,
            contentScale = ContentScale.Fit,
            onSuccess = { st -> val d = st.result.drawable; if (d.intrinsicWidth > 0 && d.intrinsicHeight > 0) ratio = d.intrinsicWidth.toFloat() / d.intrinsicHeight })
        else {
            val exo = remember(e.id) {
                val http = androidx.media3.datasource.DefaultHttpDataSource.Factory().setDefaultRequestProperties(
                    mapOf(com.vrca.vrchat.AvatarGlobalDb.CDN_APP_HEADER to com.vrca.vrchat.AvatarGlobalDb.CDN_APP_MARK))
                androidx.media3.exoplayer.ExoPlayer.Builder(ctx)
                    .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(http)).build().apply {
                        setMediaItem(androidx.media3.common.MediaItem.fromUri(evidenceUrl(e.id)))
                        repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
                        prepare(); playWhenReady = true
                    }
            }
            androidx.compose.runtime.DisposableEffect(exo) {
                val l = object : androidx.media3.common.Player.Listener {
                    override fun onVideoSizeChanged(v: androidx.media3.common.VideoSize) {
                        if (v.width > 0 && v.height > 0) ratio = v.width * v.pixelWidthHeightRatio / v.height
                    }
                }
                exo.addListener(l)
                onDispose { exo.removeListener(l); exo.release() }
            }
            // A TextureView (like the rich-content videos: SurfaceView renders black inside Compose); it loops.
            androidx.compose.ui.viewinterop.AndroidView({ c ->
                android.view.TextureView(c).also { exo.setVideoTextureView(it) }
            }, if (ratio > 0f) Modifier.aspectRatio(ratio) else Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        }
    }
}
