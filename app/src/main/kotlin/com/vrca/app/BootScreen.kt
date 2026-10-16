package com.vrca.app

import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.Download
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.path
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.VerticalAlignmentLine
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.vrca.BuildConfig
import com.vrca.ui.theme.GremlinColors

/** Where one boot check stands. */
enum class BootState {
    Waiting,  // not started yet
    Working,  // running now (spinner)
    Done,     // finished fine (green tick)
    Warn,     // finished with a problem that doesn't stop the app (amber !)
    Failed,   // couldn't finish; boot can't go on until it's retried (red x)
    Skipped,  // not needed / not available right now (grey dash), e.g. "VRChat not open"
    Background // still running, but boot doesn't wait for it (keeps going in the app)
}

/**
 * The ONLY words a check row can show on its right. A fixed short list instead of free text (user):
 * free text could run long and get cut off; these always fit one line, so rows never change size.
 * Add a new word here rather than passing a sentence.
 */
enum class BootNote(val text: String) {
    SignedIn("Signed in"),
    SignInNeeded("Sign in needed"),
    Good("Good"),
    Restricted("Restricted"),
    Online("Online"),
    Connecting("Connecting"),
    Found("Found"),
    NotOpen("VRChat not open"),
    UpToDate("Up to date"),
    UpdateReady("Update ready"),
    Connected("Connected"),
    Off("Off"),
    Accepted("Accepted"),
    NewTerms("New terms"),
    CantConnect("Can't connect"),
    TimedOut("Timed out")
}

/** One row of the boot checklist. [blocking] rows must be Done before boot hands off. */
data class BootCheck(
    val label: String,
    val state: BootState,
    val note: BootNote? = null,
    val blocking: Boolean = false,
    val icon: ImageVector? = null
)

/** What a Status row says about a service: a fixed word each (same reason as [BootNote]). */
enum class ServiceState(val text: String) {
    Checking("Checking..."),
    Online("Online"),
    /** VRChat's status page: a minor incident, or one part of VRChat not working. */
    Issues("Having issues"),
    Maintenance("Maintenance"),
    Down("Down")
}

/** The headset's connection type, for the Internet row. */
enum class InternetLink(val text: String) {
    Checking("Checking..."),
    WiFi("Wi-Fi"),
    Ethernet("Ethernet"),
    Mobile("Mobile data"),
    None("Not connected")
}

/** Status panel: the headset's internet, VRChat's platform status and our own servers'. */
data class BootStatus(
    val internet: InternetLink = InternetLink.Checking,
    /** Android's estimate of the download speed; 0 = unknown (then only the link type shows). */
    val internetMbps: Int = 0,
    val vrchat: ServiceState = ServiceState.Checking,
    /** The app's own backend (sync, avatar search). */
    val servers: ServiceState = ServiceState.Checking
)

private val Settled = setOf(BootState.Done, BootState.Warn, BootState.Skipped, BootState.Background)

private fun BootState.tone(accent: Color): Color = when (this) {
    BootState.Done -> GremlinColors.Positive
    BootState.Warn -> GremlinColors.Caution
    BootState.Failed -> GremlinColors.Negative
    BootState.Working -> accent
    BootState.Background -> accent.copy(alpha = 0.5f)
    BootState.Skipped, BootState.Waiting -> GremlinColors.Edge
}

/** Flat drop layer: a solid darker copy of the shape, offset down. Depth without gradients or blur. */
private val DropLayer = Color.Black.copy(alpha = 0.38f)

/**
 * A Gremlin layer: the [color] surface with an [GremlinColors.Edge] outline, a 1dp light top lip
 * (the edge facing the light) and a flat [DropLayer] [drop] dp below it.
 */
@Composable
private fun Layer(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    color: Color = GremlinColors.Panel,
    edge: Color = GremlinColors.Edge,
    drop: Dp = 6.dp,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier) {
        Box(Modifier.matchParentSize().offset(y = drop).background(DropLayer, shape))
        Box(
            Modifier.matchParentSize().clip(shape).background(color).border(1.dp, edge, shape)
        ) {
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp)
                    .background(Color.White.copy(alpha = 0.07f))
            )
        }
        Box(content = content)
    }
}

/**
 * The boot screen: the logo on its plate, a live status pill, then two panels side by side:
 * what's loading (left) and status / what's new (right). On a narrow phone the panels stack.
 * Panels keep a fixed height so nothing jumps when a row changes or an error shows.
 */
@Composable
fun BootScreen(
    checks: List<BootCheck>,
    status: BootStatus,
    error: String? = null,
    retryInSec: Int? = null,
    onRetry: () -> Unit = {},
    onContinue: (() -> Unit)? = null,
    /** Open on the logo alone, big and centred, then glide it into place as the panels arrive. */
    playIntro: Boolean = true,
    /** The back wall's decoration (BootBackdrop.kt); faded in with the intro. */
    backdrop: BootBackdrop = BootBackdrop.Lanes,
    /** The VRChat display name for the checklist's "Welcome back" title; null before sign-in. */
    userName: String? = null
) {
    val working = checks.any { it.state == BootState.Working }
    // Intro, in three beats: the logo fades/grows in at the centre, holds, then settles up into
    // the layout while everything else rises in underneath it. Skipped = all at their end values.
    val logoIn = remember { Animatable(if (playIntro) 0f else 1f) }
    val settle = remember { Animatable(if (playIntro) 0f else 1f) }
    val contentIn = remember { Animatable(if (playIntro) 0f else 1f) }
    // Each part opens on its own (the SAO idea, not a copy): a panel draws a bright line that
    // stretches out from its centre and unfolds from it; then its pieces widen out one by one.
    val start = if (playIntro) 0f else 1f
    val panelL = remember { Animatable(start) }
    val panelR = remember { Animatable(start) }
    val partsL = remember { Animatable(start) }
    val partsR = remember { Animatable(start) }
    // Both coordinates are kept and the logo's centre is derived from them here. Computing it in
    // the logo's own callback needed the box's callback to have run first (their order isn't
    // guaranteed), which could leave the intro waiting on a blank screen until something moved.
    var rootCoords by remember { mutableStateOf<LayoutCoordinates?>(null, neverEqualPolicy()) }
    var logoCoords by remember { mutableStateOf<LayoutCoordinates?>(null, neverEqualPolicy()) }
    val logoCenter: Offset? = run {
        val r = rootCoords; val l = logoCoords
        if (r != null && l != null && r.isAttached && l.isAttached)
            r.localPositionOf(l, Offset.Zero) + Offset(l.size.width / 2f, l.size.height / 2f)
        else null
    }
    // Start once the logo's final slot is known, so the first frame already has it at the centre.
    val placed = logoCenter != null
    LaunchedEffect(placed) {
        if (!playIntro || !placed || logoIn.value > 0f) return@LaunchedEffect
        logoIn.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
        delay(420)
        launch { settle.animateTo(1f, tween(760, easing = FastOutSlowInEasing)) }
        delay(300)
        launch { contentIn.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) }
        delay(220)
        launch { panelL.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
        delay(130)
        launch { panelR.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
        delay(470)
        launch { partsL.animateTo(1f, tween(700)) }
        delay(130)
        partsR.animateTo(1f, tween(700))
    }
    val rise = with(LocalDensity.current) { 22.dp.toPx() }
    val contentLayer = Modifier.graphicsLayer {
        alpha = contentIn.value
        translationY = rise * (1f - contentIn.value)
    }
    Surface(color = GremlinColors.Backdrop) {
        BoxWithConstraints(
            Modifier.fillMaxSize().background(GremlinColors.Backdrop).onGloballyPositioned { rootCoords = it }
        ) {
            BootBackdropLayer(backdrop, alpha = { contentIn.value }, modifier = Modifier.matchParentSize())
            val wide = maxWidth >= 720.dp
            val boxW = constraints.maxWidth.toFloat()
            val boxH = constraints.maxHeight.toFloat()
            // The logo: from the screen's centre to its slot. Phone: starts INTRO_SCALE big and shrinks
            // as it rises. Headset: one size, it just slides left (user: shrinking isn't needed there,
            // a straight slide reads cleaner).
            val startScale = if (wide) 1f else INTRO_SCALE
            val logo: @Composable (Dp) -> Unit = { height ->
                BootLogo(
                    height = height,
                    maxScale = startScale,
                    introScale = { (startScale + (1f - startScale) * settle.value) * (0.9f + 0.1f * logoIn.value) },
                    fadeIn = logoIn.value,
                    modifier = Modifier
                        .onGloballyPositioned { logoCoords = it }
                        .graphicsLayer {
                            val c = logoCenter
                            if (c != null) {
                                translationX = (boxW / 2f - c.x) * (1f - settle.value)
                                translationY = (boxH / 2f - c.y) * (1f - settle.value)
                            }
                        }
                )
            }
            val nameAndPill: @Composable () -> Unit = {
                Column(contentLayer, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "VRChat Assistant",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = GremlinColors.Text
                    )
                    Spacer(Modifier.height(8.dp))
                    StatusPill(checks, error)
                }
            }
            if (wide) {
                // Headset: logo and the Status card on the left; the checklist on the right. Both
                // panels wrap their content (no empty strips) and sit bottom-aligned so their lower
                // edges line up; the pair is centred on the screen. On an error, Retry / Continue
                // anyway open up underneath, meeting at the gap between the two panels.
                Column(Modifier.fillMaxWidth().align(Alignment.Center).padding(horizontal = 32.dp, vertical = 22.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(WIDE_GAP)
                ) {
                    Column(
                        Modifier.weight(0.85f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Just the logo up here (user: no "VRChat Assistant" title or status pill;
                        // the checklist already shows how boot is going).
                        // Sized so its top lines up with the checklist's top (user), just above Status.
                        logo(WIDE_LOGO_HEIGHT)
                        Spacer(Modifier.height(WIDE_LOGO_GAP))
                        Unfold({ panelR.value }, Modifier.fillMaxWidth()) {
                            StatusPanel(status, Modifier.fillMaxWidth(), partsR.value, rowHeight = WIDE_ROW_HEIGHT)
                        }
                    }
                    Unfold({ panelL.value }, Modifier.weight(1.15f)) {
                        LoadingPanel(checks, error, retryInSec, onRetry, onContinue, Modifier.fillMaxWidth(), partsL.value, roomy = true, userName = userName)
                    }
                }
                AnimatedVisibility(error != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    // The pill's seam (where Retry meets Continue anyway) sits on the centre of the gap
                    // between the two panels: Retry under the left one, Continue under the right.
                    Layout(
                        content = { ActionPill(retryInSec, onRetry, onContinue) },
                        modifier = Modifier.fillMaxWidth().padding(top = 20.dp)
                    ) { measurables, constraints ->
                        val pill = measurables.first().measure(constraints.copy(minWidth = 0))
                        val gap = WIDE_GAP.roundToPx()
                        val gapCentre = ((constraints.maxWidth - gap) * 0.85f / 2f + gap / 2f).toInt()
                        layout(constraints.maxWidth, pill.height) {
                            val seam = pill[PillSeam].takeIf { it != AlignmentLine.Unspecified } ?: (pill.width / 2)
                            pill.place((gapCentre - seam).coerceIn(0, constraints.maxWidth - pill.width), 0)
                        }
                    }
                }
                }
            } else {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    logo(LOGO_HEIGHT)
                    Spacer(Modifier.height(14.dp))
                    nameAndPill()
                    Spacer(Modifier.height(16.dp))
                    Column(
                        Modifier.widthIn(max = 460.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Unfold({ panelL.value }, Modifier.fillMaxWidth().height(330.dp)) {
                            LoadingPanel(checks, error, retryInSec, onRetry, onContinue, Modifier.fillMaxSize(), partsL.value, userName = userName)
                        }
                        Unfold({ panelR.value }, Modifier.fillMaxWidth()) {
                            StatusPanel(status, Modifier.fillMaxWidth(), partsR.value)
                        }
                    }
                }
            }
            Text(
                "v" + BuildConfig.VERSION_NAME.removePrefix("v"),
                style = MaterialTheme.typography.labelMedium,
                color = GremlinColors.TextMuted.copy(alpha = 0.85f),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 10.dp).then(contentLayer)
            )
        }
    }
}

/** How big the logo starts in the intro, relative to its final size. */
private const val INTRO_SCALE = 2.1f

/**
 * The logo on its own (no tile: a speech bubble inside a box read oddly), with a flat
 * silhouette copy offset below it as its drop layer. Steady: a "breathing" fade while checks ran
 * was removed (it read as the logo blipping brighter and dimmer; the status pill shows progress).
 */
@Composable
private fun BootLogo(height: Dp, maxScale: Float, introScale: () -> Float, fadeIn: Float, modifier: Modifier = Modifier) {
    // vrca_logo_bar: the logo cropped to its drawing (the full asset is ~35% empty margin).
    val logo = ImageBitmap.imageResource(com.vrca.R.drawable.vrca_logo_bar)
    // Sized by the slot's height, not the bitmap's own pixel size (which capped it at ~214dp wide).
    val aspect = logo.width.toFloat() / logo.height
    // The slot is LOGO_HEIGHT tall, but the logo is laid out at its biggest (intro) size and only
    // ever scaled DOWN: scaling a small one up showed a blurry logo that snapped sharp on landing.
    Box(modifier.height(height), contentAlignment = Alignment.Center) {
        Box(
            Modifier.requiredHeight(height * maxScale).graphicsLayer {
                val s = introScale() / maxScale
                scaleX = s; scaleY = s
            }
        ) {
            Image(
                bitmap = logo, contentDescription = null, filterQuality = FilterQuality.High,
                colorFilter = ColorFilter.tint(DropLayer.copy(alpha = 0.55f)),
                modifier = Modifier.fillMaxHeight().aspectRatio(aspect).offset(y = 5.dp * maxScale).alpha(fadeIn)
            )
            Image(
                bitmap = logo, contentDescription = "VRC-A", filterQuality = FilterQuality.High,
                // The fade sits on the images themselves, not on the oversized box around them:
                // a see-through layer whose content spills past its bounds drew nothing in the
                // UI Lab's software renderer (the intro's fade-in recorded as blank).
                modifier = Modifier.fillMaxHeight().aspectRatio(aspect).alpha(fadeIn)
            )
        }
    }
}

private val LOGO_HEIGHT = 104.dp
/** Headset: the logo has the whole left column, so it's bigger. */
private val WIDE_LOGO_HEIGHT = 238.dp
private val WIDE_LOGO_GAP = 8.dp
/** Headset row height, shared by check rows and status rows so both panels match. */
private val WIDE_ROW_HEIGHT = 38.dp
/** Headset gap between the left column and the checklist; the error buttons meet across it. */
private val WIDE_GAP = 28.dp

/** One-glance summary under the name: Starting up / Ready / Needs attention. */
@Composable
private fun StatusPill(checks: List<BootCheck>, error: String?) {
    val accent = MaterialTheme.colorScheme.primary
    val (text, tone) = when {
        error != null || checks.any { it.state == BootState.Failed } -> "Needs attention" to GremlinColors.Negative
        checks.all { it.state in Settled } -> "Ready" to GremlinColors.Positive
        else -> "Starting up" to accent
    }
    val color by animateColorAsState(tone, label = "bootPill")
    Row(
        Modifier.clip(CircleShape).background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.45f), CircleShape)
            .padding(horizontal = 14.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.SemiBold)
    }
}

/** Split of a part's [Unfold] progress: the line stretches out, then the part opens from it. */
private const val LINE_SHARE = 0.42f

/**
 * Opens one part the SAO way: a bright line stretches out sideways from the part's centre, the
 * part unfolds up and down from that line, and the line fades as it does. [progress] 0..1.
 */
@Composable
private fun Unfold(progress: () -> Float, modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier) {
        Box(
            Modifier.graphicsLayer {
                val p = progress()
                val line = (p / LINE_SHARE).coerceIn(0f, 1f)
                val open = ((p - LINE_SHARE) / (1f - LINE_SHARE)).coerceIn(0f, 1f)
                scaleX = line
                scaleY = 0.015f + 0.985f * open
                alpha = if (line > 0f) 1f else 0f
            }
        ) { content() }
        // The line splits in two as the part opens: one rides its top edge, one its bottom edge,
        // fading as they go, so nothing is ever left across the middle (a single line that faded
        // in place either lingered there or blinked out as the part started to open).
        Box(
            Modifier.matchParentSize().drawBehind {
                val p = progress()
                val line = (p / LINE_SHARE).coerceIn(0f, 1f)
                val open = ((p - LINE_SHARE) / (1f - LINE_SHARE)).coerceIn(0f, 1f)
                val a = line * (1f - open)
                if (a <= 0f) return@drawBehind
                val w = size.width * line
                val x0 = (size.width - w) / 2f
                val half = size.height / 2f * (0.015f + 0.985f * open)
                val stroke = 2.dp.toPx()
                val c = GremlinColors.AccentSoft.copy(alpha = a)
                for (y in listOf(size.height / 2f - half, size.height / 2f + half)) {
                    drawLine(c, Offset(x0, y), Offset(x0 + w, y), strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
        )
    }
}

/** How far piece [i] of [n] has appeared when its panel's pieces are [reveal] (0..1) through. */
private fun stagger(reveal: Float, i: Int, n: Int): Float {
    val span = 0.45f
    val start = if (n <= 1) 0f else (1f - span) * i / (n - 1)
    return ((reveal - start) / span).coerceIn(0f, 1f)
}

/** A piece widening out from its middle as it appears. */
private fun Modifier.widenIn(a: Float) = graphicsLayer {
    scaleX = 0.2f + 0.8f * a
    alpha = a
}

/**
 * The checklist's header: "Welcome back, <VRChat name>" (just "Welcome back" before sign-in), a
 * count chip coloured by how boot is going (accent while running, green when ready, red on a
 * failure), and one bar segment per check in row order. Segments take their row's colour; a
 * running one breathes, so the bar shows what's still going at a glance. VRChat names are at most
 * 15 characters, so the title always fits one line.
 */
@Composable
private fun BootHeader(checks: List<BootCheck>, userName: String?) {
    val accent = MaterialTheme.colorScheme.primary
    val finished = checks.count { it.state in Settled }
    val target = when {
        checks.any { it.state == BootState.Failed } -> GremlinColors.Negative
        checks.all { it.state in Settled } -> GremlinColors.Positive
        else -> accent
    }
    val tone by animateColorAsState(target, label = "headerTone")
    val pulse by rememberInfiniteTransition(label = "segPulse").animateFloat(
        0.45f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "segPulseA"
    )
    // The Ready moment: when the last check settles, a light sweeps along the bar left to right
    // and the count chip pops, so even a very fast boot visibly "lands".
    val done = checks.isNotEmpty() && checks.all { it.state in Settled } && checks.none { it.state == BootState.Failed }
    val sweep = remember { Animatable(-2f) }
    val chipPop = remember { Animatable(1f) }
    LaunchedEffect(done) {
        if (!done) { sweep.snapTo(-2f); return@LaunchedEffect }
        launch { chipPop.animateTo(1.18f, tween(140)); chipPop.animateTo(1f, spring(dampingRatio = 0.4f)) }
        sweep.snapTo(-1f)
        sweep.animateTo(checks.size + 1f, tween(650, easing = FastOutSlowInEasing))
    }
    Column {
        Row(Modifier.height(30.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (userName.isNullOrBlank()) "Welcome back" else "Welcome back, $userName",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = GremlinColors.Text,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            Text(
                "$finished/${checks.size}",
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = tone,
                modifier = Modifier.scale(chipPop.value).background(tone.copy(alpha = 0.14f), CircleShape).padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().height(6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            checks.forEachIndexed { i, c ->
                val seg by animateColorAsState(
                    when (c.state) {
                        BootState.Waiting -> GremlinColors.Raised
                        BootState.Skipped -> GremlinColors.Inactive
                        else -> c.state.tone(accent)
                    },
                    label = "seg"
                )
                val a = if (c.state == BootState.Working) pulse else 1f
                // How much of the Ready sweep is on this segment right now (0..1).
                val glow = (1f - kotlin.math.abs(sweep.value - i - 0.5f)).coerceIn(0f, 1f)
                val color = androidx.compose.ui.graphics.lerp(seg.copy(alpha = seg.alpha * a), GremlinColors.Text, glow * 0.75f)
                Box(
                    Modifier.weight(1f).fillMaxHeight().graphicsLayer { scaleY = 1f + glow * 0.6f }
                        .background(color, CircleShape)
                )
            }
        }
    }
}

@Composable
private fun PanelHeader(icon: ImageVector, title: String, trailing: @Composable () -> Unit = {}) {
    val accent = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(30.dp).background(accent.copy(alpha = 0.16f), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = accent, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(10.dp))
        Text(
            title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            color = GremlinColors.Text, modifier = Modifier.weight(1f)
        )
        trailing()
    }
}

@Composable
private fun LoadingPanel(
    checks: List<BootCheck>,
    error: String?,
    retryInSec: Int?,
    onRetry: () -> Unit,
    onContinue: (() -> Unit)?,
    modifier: Modifier,
    reveal: Float = 1f,
    /** Headset right column: bigger rows, more space between, and the panel wraps its rows. */
    roomy: Boolean = false,
    userName: String? = null
) {
    val pieces = checks.size + 2 // the header, each check, then the buttons
    Layer(modifier) {
        Column(Modifier.then(if (roomy) Modifier.fillMaxWidth() else Modifier.fillMaxSize()).padding(horizontal = 18.dp, vertical = 16.dp)) {
            Box(Modifier.widenIn(stagger(reveal, 0, pieces))) { BootHeader(checks, userName) }
            Spacer(Modifier.height(14.dp))
            Column(if (roomy) Modifier else Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (roomy) 8.dp else 4.dp)) {
                checks.forEachIndexed { i, c ->
                    BootCheckTile(c, Modifier.widenIn(stagger(reveal, 1 + i, pieces)), height = if (roomy) WIDE_ROW_HEIGHT else 31.dp)
                }
            }
            // Headset: the buttons sit under both panels instead (see BootScreen).
            if (error != null && !roomy) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.widenIn(stagger(reveal, pieces - 1, pieces)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ActionPill(retryInSec, onRetry, onContinue)
                }
            }
        }
    }
}

/** A check as a layer-2 tile: category icon, label, reason, and its state mark on the right. */
@Composable
private fun BootCheckTile(check: BootCheck, modifier: Modifier = Modifier, height: Dp = 31.dp) {
    val accent = MaterialTheme.colorScheme.primary
    val tone = check.state.tone(accent)
    val dim = check.state == BootState.Waiting
    val edge = rowEdge(check.state.mark(), tone)
    // A row that fails gives one short sideways shake, so the eye goes straight to it.
    val shake = remember { Animatable(0f) }
    LaunchedEffect(check.state) {
        if (check.state == BootState.Failed) {
            for (x in listOf(10f, -8f, 5f, -3f, 0f)) shake.animateTo(x, tween(55))
        }
    }
    Row(
        modifier.graphicsLayer { translationX = shake.value * density }
            .fillMaxWidth().height(height).clip(RoundedCornerShape(12.dp))
            .background(if (dim) GremlinColors.Card.copy(alpha = 0.5f) else GremlinColors.Card)
            .border(1.dp, edge, RoundedCornerShape(12.dp))
            .padding(start = 10.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (check.icon != null) {
            Icon(
                check.icon, null, modifier = Modifier.size(17.dp),
                tint = if (dim) GremlinColors.TextMuted.copy(alpha = 0.4f) else GremlinColors.TextMuted
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            check.label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (dim) GremlinColors.TextMuted.copy(alpha = 0.5f) else GremlinColors.Text,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(10.dp))
        if (check.note != null) {
            Text(
                check.note.text,
                style = MaterialTheme.typography.bodySmall,
                color = when (check.state) {
                    BootState.Failed -> GremlinColors.Negative
                    BootState.Warn -> GremlinColors.Caution
                    else -> GremlinColors.TextMuted
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp)
            )
            Spacer(Modifier.width(10.dp))
        }
        StateMark(check.state.mark())
    }
}

/** The state symbols, shared by check rows and Status rows so both panels speak one language. */
private enum class Mark { Waiting, Working, Done, Warn, Maintenance, Failed, Skipped }

private fun BootState.mark(): Mark = when (this) {
    BootState.Waiting -> Mark.Waiting
    BootState.Working, BootState.Background -> Mark.Working
    BootState.Done -> Mark.Done
    BootState.Warn -> Mark.Warn
    BootState.Failed -> Mark.Failed
    BootState.Skipped -> Mark.Skipped
}

private fun ServiceState.mark(): Mark = when (this) {
    ServiceState.Checking -> Mark.Working
    ServiceState.Online -> Mark.Done
    ServiceState.Issues -> Mark.Warn
    ServiceState.Maintenance -> Mark.Maintenance
    ServiceState.Down -> Mark.Failed
}

/**
 * A state symbol. Changing state swaps it: the old one shrinks and fades out while the new one
 * springs in with a small overshoot, so every change is seen without anything moving the row.
 */
@Composable
private fun StateMark(mark: Mark) {
    val accent = MaterialTheme.colorScheme.primary
    AnimatedContent(
        mark,
        transitionSpec = {
            (scaleIn(spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.3f) + fadeIn(tween(120))) togetherWith
                (scaleOut(tween(140), targetScale = 0.3f) + fadeOut(tween(140)))
        },
        contentAlignment = Alignment.Center,
        label = "stateMark"
    ) { m ->
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when (m) {
                Mark.Waiting -> Box(Modifier.size(7.dp).background(GremlinColors.Raised, CircleShape))
                Mark.Working -> CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp, color = accent)
                else -> {
                    val (icon, fill) = when (m) {
                        Mark.Done -> Icons.Filled.Check to GremlinColors.Positive
                        Mark.Warn -> Icons.Filled.PriorityHigh to GremlinColors.Caution
                        Mark.Maintenance -> Icons.Filled.Build to GremlinColors.Caution
                        Mark.Failed -> Icons.Filled.Close to GremlinColors.Negative
                        else -> Icons.Filled.Remove to GremlinColors.Raised
                    }
                    Box(Modifier.size(19.dp).background(fill, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(
                            icon, null, modifier = Modifier.size(if (m == Mark.Maintenance) 11.dp else 13.dp),
                            tint = if (m == Mark.Skipped) GremlinColors.TextMuted else GremlinColors.Backdrop
                        )
                    }
                }
            }
        }
    }
}

/**
 * A row's outline: a steady [tone] edge while it needs attention, and a one-off flash in [tone]
 * that fades out when it lands on a good result (so "it worked" is felt, then gets out of the way).
 */
@Composable
private fun rowEdge(mark: Mark, tone: Color, edgeWhileWorking: Boolean = true): Color {
    val flash = remember { Animatable(0f) }
    var last by remember { mutableStateOf(mark) }
    LaunchedEffect(mark) {
        if (mark == Mark.Done && last != Mark.Done) {
            flash.snapTo(0.8f)
            flash.animateTo(0f, tween(700, easing = FastOutSlowInEasing))
        }
        last = mark
    }
    val steady by animateColorAsState(
        when (mark) {
            Mark.Working -> if (edgeWhileWorking) tone.copy(alpha = 0.7f) else Color.Transparent
            Mark.Warn, Mark.Maintenance, Mark.Failed -> tone.copy(alpha = 0.6f)
            else -> Color.Transparent
        },
        label = "rowEdge"
    )
    return if (flash.value > 0f) GremlinColors.Positive.copy(alpha = flash.value) else steady
}

/** One service in the Status panel: icon, name, its fixed state word and a coloured dot. Built like a check row. */
@Composable
private fun ServiceRow(icon: ImageVector, name: String, text: String, mark: Mark, modifier: Modifier, height: Dp) {
    val target = when (mark) {
        Mark.Done -> GremlinColors.Positive
        Mark.Warn, Mark.Maintenance -> GremlinColors.Caution
        Mark.Failed -> GremlinColors.Negative
        else -> GremlinColors.Inactive
    }
    val tone by animateColorAsState(target, label = "serviceTone")
    val warn = mark == Mark.Warn || mark == Mark.Maintenance || mark == Mark.Failed
    // No outline while just checking: three outlined rows at once on every open read as alarm.
    val edge = rowEdge(mark, tone, edgeWhileWorking = false)
    Row(
        modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(12.dp))
            .background(GremlinColors.Card)
            .border(1.dp, edge, RoundedCornerShape(12.dp))
            .padding(start = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Crossfade(icon, label = "serviceIcon") { Icon(it, null, tint = tone, modifier = Modifier.size(17.dp)) }
        Spacer(Modifier.width(10.dp))
        Text(
            name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = GremlinColors.Text,
            maxLines = 1, modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(10.dp))
        val textColor by animateColorAsState(if (warn) target else GremlinColors.TextMuted, label = "serviceText")
        Crossfade(text, label = "serviceText") { Text(it, style = MaterialTheme.typography.bodySmall, color = textColor, maxLines = 1) }
        Spacer(Modifier.width(10.dp))
        StateMark(mark)
    }
}

/** Is VRChat up, and are our own servers up. Wraps its rows (no fixed height). */
@Composable
private fun StatusPanel(status: BootStatus, modifier: Modifier, reveal: Float = 1f, rowHeight: Dp = 31.dp) {
    Layer(modifier) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
            // No header (user): the rows speak for themselves.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val net = status.internet
                ServiceRow(
                    if (net == InternetLink.None) Icons.Filled.WifiOff else Icons.Filled.Wifi, "Internet",
                    if (status.internetMbps > 0 && net != InternetLink.None && net != InternetLink.Checking)
                        "${net.text} · ${status.internetMbps.coerceAtMost(9999)} Mbps" else net.text,
                    when (net) {
                        InternetLink.Checking -> Mark.Working
                        InternetLink.None -> Mark.Failed
                        else -> Mark.Done
                    },
                    Modifier.widenIn(stagger(reveal, 0, 3)), rowHeight
                )
                for ((i, row) in listOf(
                    // Both service rows share the server icon (user): they're both "is that server up".
                    Triple(Icons.Filled.Dns, "VRChat", status.vrchat),
                    // Never names the backend (public UI rule): just "VRC-A servers".
                    Triple(Icons.Filled.Dns, "VRC-A servers", status.servers)
                ).withIndex()) {
                    val (icon, name, state) = row
                    ServiceRow(icon, name, state.text, state.mark(), Modifier.widenIn(stagger(reveal, 1 + i, 3)), rowHeight)
                }
            }
        }
    }
}


/**
 * Retry and Continue anyway joined into one pill. Retry's countdown drains as a ring round the
 * seconds left (the ring's full length is the longest countdown seen, since the wait can vary).
 */
@Composable
private fun ActionPill(retryInSec: Int?, onRetry: () -> Unit, onContinue: (() -> Unit)?) {
    val accent = MaterialTheme.colorScheme.primary
    var longest by remember { mutableIntStateOf(1) }
    if ((retryInSec ?: 0) > longest) longest = retryInSec ?: 0
    val ring by animateFloatAsState(((retryInSec ?: 0).toFloat() / longest).coerceIn(0f, 1f), label = "retryRing")
    Layer(Modifier.height(50.dp), shape = CircleShape, color = GremlinColors.Raised, drop = 4.dp) {
        Row(Modifier.fillMaxHeight().padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.reportSeam().fillMaxHeight().clip(CircleShape).background(accent).clickable(onClick = onRetry).padding(start = 8.dp, end = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = ring,
                        modifier = Modifier.fillMaxSize(), strokeWidth = 2.5.dp,
                        color = GremlinColors.Backdrop, trackColor = GremlinColors.Backdrop.copy(alpha = 0.18f)
                    )
                    Text("${retryInSec ?: ""}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = GremlinColors.Backdrop)
                }
                Spacer(Modifier.width(10.dp))
                Text("Retry", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = GremlinColors.Backdrop)
            }
            if (onContinue != null) {
                Row(
                    Modifier.fillMaxHeight().clip(CircleShape).clickable(onClick = onContinue).padding(start = 18.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Continue anyway", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = GremlinColors.Text)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = GremlinColors.TextMuted, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** The x where the pill's Retry half ends, so the pill can be lined up by its seam. */
private val PillSeam = VerticalAlignmentLine(::minOf)

private fun Modifier.reportSeam() = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    layout(p.width, p.height, mapOf(PillSeam to p.width)) { p.place(0, 0) }
}

/** The Internet row: the active connection's type and Android's download-speed estimate (Mbps, 0 = unknown). */
fun internetStatus(context: android.content.Context): Pair<InternetLink, Int> {
    val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return InternetLink.None to 0
    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
    if (caps == null || !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return InternetLink.None to 0
    val link = when {
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> InternetLink.Ethernet
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> InternetLink.Mobile
        else -> InternetLink.WiFi
    }
    return link to (caps.linkDownstreamBandwidthKbps / 1000).coerceAtLeast(0)
}

/** The Starting up rows' left icons (Discord's comes from R.drawable.ic_discord). */
object BootIcons {
    val DeviceSession: ImageVector get() = VrHeadset
    val Terms: ImageVector get() = Icons.AutoMirrored.Filled.Article
    val Account: ImageVector get() = Icons.Filled.Shield
    val VrchatSignIn: ImageVector get() = Icons.Filled.Person
    val VrchatHere: ImageVector get() = Icons.Filled.ViewInAr
    val Updates: ImageVector get() = Icons.Filled.Download

    /**
     * A VR headset (Material has none), in the bold goggle style (user's reference): one wide visor
     * with an outlined rim and a nose notch, side straps, and the head strap arcing over the top.
     * Our own drawing on Material's 24-unit grid, filled, so it sits with the stock icons.
     */
    val VrHeadset: ImageVector by lazy {
        ImageVector.Builder("VrHeadset", 24.dp, 24.dp, 24f, 24f).apply {
            val ink = SolidColor(Color.Black)
            // Head strap over the top, and a short centre strap.
            path(stroke = ink, strokeLineWidth = 2.2f, strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round) {
                moveTo(4.6f, 8.6f)
                curveTo(5.6f, 3.6f, 18.4f, 3.6f, 19.4f, 8.6f)
            }
            path(fill = ink) {
                moveTo(11f, 3.6f); horizontalLineTo(13f); verticalLineTo(7.4f); horizontalLineTo(11f); close()
                // Side straps.
                moveTo(0.8f, 11f); horizontalLineTo(2.6f); verticalLineTo(15.6f); horizontalLineTo(0.8f); close()
                moveTo(21.4f, 11f); horizontalLineTo(23.2f); verticalLineTo(15.6f); horizontalLineTo(21.4f); close()
            }
            // Visor rim: the outline minus a slightly smaller copy leaves a ring...
            path(fill = ink, pathFillType = PathFillType.EvenOdd) {
                visor(0f)
                visor(1.3f)
            }
            // ...and the lens fills the middle, a thin gap inside the rim.
            path(fill = ink) { visor(2.5f) }
        }.build()
    }

    /** The visor outline, shrunk by [d] on every side (nose notch included). */
    private fun androidx.compose.ui.graphics.vector.PathBuilder.visor(d: Float) {
        val l = 2.6f + d; val t = 7.6f + d; val r = 21.4f - d; val b = 19.6f - d
        val rad = (4.2f - d).coerceAtLeast(0.6f)
        val nose = 2.3f - d * 0.15f   // half-width of the notch at the bottom edge
        val apex = 15.6f - d * 0.8f    // how high the notch reaches
        moveTo(l + rad, t)
        horizontalLineTo(r - rad)
        arcTo(rad, rad, 0f, false, true, r, t + rad)
        verticalLineTo(b - rad)
        arcTo(rad, rad, 0f, false, true, r - rad, b)
        horizontalLineTo(12f + nose + 0.8f)
        curveTo(12f + nose * 0.4f, b, 12f + nose * 0.5f, apex, 12f, apex)
        curveTo(12f - nose * 0.5f, apex, 12f - nose * 0.4f, b, 12f - nose - 0.8f, b)
        horizontalLineTo(l + rad)
        arcTo(rad, rad, 0f, false, true, l, b - rad)
        verticalLineTo(t + rad)
        arcTo(rad, rad, 0f, false, true, l + rad, t)
        close()
    }
}
