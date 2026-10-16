package com.vrca.app

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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Public
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

/** One row of the boot checklist. [blocking] rows must be Done before boot hands off. */
data class BootCheck(
    val label: String,
    val state: BootState,
    val detail: String = "",
    val blocking: Boolean = false,
    val icon: ImageVector? = null
)

/** Status panel: VRChat's platform status and our own servers'. Null while still checking. */
data class BootStatus(
    val vrchatOk: Boolean? = null,
    val vrchatDetail: String = "",
    /** The app's own backend (sync, avatar search). */
    val serversOk: Boolean? = null,
    val serversDetail: String = ""
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
    backdrop: BootBackdrop = BootBackdrop.Lanes
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
                        logo(WIDE_LOGO_HEIGHT)
                        Spacer(Modifier.height(28.dp))
                        Unfold({ panelR.value }, Modifier.fillMaxWidth()) {
                            StatusPanel(status, Modifier.fillMaxWidth(), partsR.value, rowHeight = WIDE_ROW_HEIGHT)
                        }
                    }
                    Unfold({ panelL.value }, Modifier.weight(1.15f)) {
                        LoadingPanel(checks, error, retryInSec, onRetry, onContinue, Modifier.fillMaxWidth(), partsL.value, roomy = true)
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
                            LoadingPanel(checks, error, retryInSec, onRetry, onContinue, Modifier.fillMaxSize(), partsL.value)
                        }
                        Unfold({ panelR.value }, Modifier.fillMaxWidth()) {
                            StatusPanel(status, Modifier.fillMaxWidth(), partsR.value)
                        }
                    }
                }
            }
            Text(
                "v" + BuildConfig.VERSION_NAME.removePrefix("v"),
                style = MaterialTheme.typography.labelSmall,
                color = GremlinColors.TextMuted.copy(alpha = 0.55f),
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
                modifier = Modifier.fillMaxHeight().offset(y = 5.dp * maxScale).alpha(fadeIn)
            )
            Image(
                bitmap = logo, contentDescription = "VRC-A", filterQuality = FilterQuality.High,
                // The fade sits on the images themselves, not on the oversized box around them:
                // a see-through layer whose content spills past its bounds drew nothing in the
                // UI Lab's software renderer (the intro's fade-in recorded as blank).
                modifier = Modifier.fillMaxHeight().alpha(fadeIn)
            )
        }
    }
}

private val LOGO_HEIGHT = 104.dp
/** Headset: the logo has the whole left column, so it's bigger. */
private val WIDE_LOGO_HEIGHT = 210.dp
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
        Box(
            Modifier.matchParentSize().graphicsLayer {
                val p = progress()
                val line = (p / LINE_SHARE).coerceIn(0f, 1f)
                val open = ((p - LINE_SHARE) / (1f - LINE_SHARE)).coerceIn(0f, 1f)
                scaleX = line
                alpha = line * (1f - open)
            },
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(GremlinColors.AccentSoft, CircleShape))
        }
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
    roomy: Boolean = false
) {
    val accent = MaterialTheme.colorScheme.primary
    val finished = checks.count { it.state in Settled }
    val pieces = checks.size + 3 // header, segment bar, each check, the buttons
    Layer(modifier) {
        Column(Modifier.then(if (roomy) Modifier.fillMaxWidth() else Modifier.fillMaxSize()).padding(horizontal = 18.dp, vertical = 16.dp)) {
            Box(Modifier.widenIn(stagger(reveal, 0, pieces))) { PanelHeader(Icons.Filled.RocketLaunch, "Starting up") {
                Text(
                    "$finished/${checks.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = GremlinColors.TextMuted,
                    modifier = Modifier.background(GremlinColors.Card, CircleShape)
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                )
            } }
            Spacer(Modifier.height(12.dp))
            // One segment per check, coloured by its state: the whole boot readable at a glance.
            Row(
                Modifier.fillMaxWidth().height(5.dp).widenIn(stagger(reveal, 1, pieces)),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                checks.forEach { c ->
                    val seg by animateColorAsState(
                        if (c.state == BootState.Waiting) GremlinColors.Raised else c.state.tone(accent), label = "seg"
                    )
                    Box(Modifier.weight(1f).fillMaxHeight().background(seg, CircleShape))
                }
            }
            Spacer(Modifier.height(12.dp))
            Column(if (roomy) Modifier else Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (roomy) 8.dp else 4.dp)) {
                checks.forEachIndexed { i, c ->
                    BootCheckTile(c, Modifier.widenIn(stagger(reveal, 2 + i, pieces)), height = if (roomy) WIDE_ROW_HEIGHT else 31.dp)
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
    val active = check.state == BootState.Working || check.state == BootState.Failed
    val edge by animateColorAsState(if (active) tone.copy(alpha = 0.7f) else Color.Transparent, label = "tileEdge")
    Row(
        modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(12.dp))
            .background(if (dim) GremlinColors.Card.copy(alpha = 0.5f) else GremlinColors.Card)
            .border(1.dp, edge, RoundedCornerShape(10.dp))
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
            maxLines = 1
        )
        Spacer(Modifier.weight(1f).width(10.dp))
        if (check.detail.isNotBlank()) {
            Text(
                check.detail,
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
        BootStateMark(check.state)
    }
}

@Composable
private fun BootStateMark(state: BootState) {
    val accent = MaterialTheme.colorScheme.primary
    // Settled marks pop in with a small overshoot.
    val pop by animateFloatAsState(
        if (state in Settled || state == BootState.Failed) 1f else 0.6f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "pop"
    )
    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        when (state) {
            BootState.Working, BootState.Background -> CircularProgressIndicator(
                modifier = Modifier.size(15.dp), strokeWidth = 2.dp, color = state.tone(accent)
            )
            BootState.Waiting -> Box(Modifier.size(7.dp).background(GremlinColors.Raised, CircleShape))
            else -> {
                val icon = when (state) {
                    BootState.Done -> Icons.Filled.Check
                    BootState.Warn -> Icons.Filled.PriorityHigh
                    BootState.Failed -> Icons.Filled.Close
                    else -> Icons.Filled.Remove
                }
                val fill = if (state == BootState.Skipped) GremlinColors.Raised else state.tone(accent)
                Box(
                    Modifier.size(19.dp).scale(pop).background(fill, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        icon, null, modifier = Modifier.size(13.dp),
                        tint = if (state == BootState.Skipped) GremlinColors.TextMuted else GremlinColors.Backdrop
                    )
                }
            }
        }
    }
}

/** One service in the Status panel: icon, name, state text and a coloured dot. Built like a check row. */
@Composable
private fun ServiceRow(icon: ImageVector, name: String, ok: Boolean?, detail: String, modifier: Modifier, height: Dp) {
    val tone = when (ok) {
        true -> GremlinColors.Positive
        false -> GremlinColors.Caution
        null -> GremlinColors.Inactive
    }
    val edge by animateColorAsState(if (ok == false) tone.copy(alpha = 0.6f) else Color.Transparent, label = "serviceEdge")
    Row(
        modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(12.dp))
            .background(GremlinColors.Card)
            .border(1.dp, edge, RoundedCornerShape(10.dp))
            .padding(start = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tone, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(10.dp))
        Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = GremlinColors.Text, maxLines = 1)
        Spacer(Modifier.weight(1f).width(10.dp))
        Text(
            when (ok) {
                true -> "Online"
                false -> detail.ifBlank { "Having issues" }
                null -> "Checking..."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (ok == false) GremlinColors.Caution else GremlinColors.TextMuted,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 170.dp)
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(9.dp).background(tone, CircleShape))
    }
}

/** Is VRChat up, and are our own servers up. Wraps its rows (no fixed height). */
@Composable
private fun StatusPanel(status: BootStatus, modifier: Modifier, reveal: Float = 1f, rowHeight: Dp = 31.dp) {
    Layer(modifier) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
            Box(Modifier.widenIn(stagger(reveal, 0, 3))) { PanelHeader(Icons.Filled.Insights, "Status") }
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ServiceRow(Icons.Filled.Public, "VRChat", status.vrchatOk, status.vrchatDetail, Modifier.widenIn(stagger(reveal, 1, 3)), rowHeight)
                // Never names the backend (public UI rule): just "VRC-A servers".
                ServiceRow(Icons.Filled.Dns, "VRC-A servers", status.serversOk, status.serversDetail, Modifier.widenIn(stagger(reveal, 2, 3)), rowHeight)
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
