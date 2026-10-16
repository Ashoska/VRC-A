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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
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

/** Right-hand panel: VRChat's platform status, the release notes after an update, a stat. */
data class BootStatus(
    /** Null while still checking. */
    val vrchatOk: Boolean? = null,
    val vrchatDetail: String = "",
    /** Version just updated to, with its notes; null when this open isn't right after an update. */
    val updatedTo: String? = null,
    val updateNotes: List<String> = emptyList(),
    val lifetimeSends: Long = 0
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
                // Headset: logo, name, pill and the Status card on the left; the checklist on the right.
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(28.dp)
                ) {
                    Column(
                        Modifier.weight(0.85f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Just the logo up here (user: no "VRChat Assistant" title or status pill;
                        // the checklist already shows how boot is going).
                        logo(WIDE_LOGO_HEIGHT)
                        Spacer(Modifier.height(28.dp))
                        Unfold({ panelR.value }, Modifier.fillMaxWidth().height(250.dp)) {
                            StatusPanel(status, Modifier.fillMaxSize(), partsR.value)
                        }
                    }
                    Unfold({ panelL.value }, Modifier.weight(1.15f).height(540.dp)) {
                        LoadingPanel(checks, error, retryInSec, onRetry, onContinue, Modifier.fillMaxSize(), partsL.value, roomy = true)
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
                        Unfold({ panelR.value }, Modifier.fillMaxWidth().height(250.dp)) {
                            StatusPanel(status, Modifier.fillMaxSize(), partsR.value)
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
private val WIDE_LOGO_HEIGHT = 150.dp

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
            Modifier.fillMaxSize().graphicsLayer {
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
    /** Taller panel (headset right column): bigger rows, more space between. */
    roomy: Boolean = false
) {
    val accent = MaterialTheme.colorScheme.primary
    val finished = checks.count { it.state in Settled }
    val pieces = checks.size + 3 // header, segment bar, each check, the buttons
    Layer(modifier) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp)) {
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
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (roomy) 10.dp else 4.dp)) {
                checks.forEachIndexed { i, c ->
                    BootCheckTile(c, Modifier.widenIn(stagger(reveal, 2 + i, pieces)), height = if (roomy) 48.dp else 31.dp)
                }
            }
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.widenIn(stagger(reveal, pieces - 1, pieces)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(onClick = onRetry) {
                        Text(if (retryInSec != null) "Retry in ${retryInSec}s" else "Retry")
                    }
                    if (onContinue != null) {
                        OutlinedButton(onClick = onContinue) { Text("Continue anyway") }
                    }
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

/** A small layer-2 card inside a panel. */
@Composable
private fun InfoCard(modifier: Modifier = Modifier, edge: Color = Color.Transparent, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(GremlinColors.Card)
            .border(1.dp, edge, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 11.dp),
        content = content
    )
}

@Composable
private fun StatusPanel(status: BootStatus, modifier: Modifier, reveal: Float = 1f) {
    val accent = MaterialTheme.colorScheme.primary
    Layer(modifier) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp)) {
            Box(Modifier.widenIn(stagger(reveal, 0, 4))) { PanelHeader(Icons.Filled.Insights, "Status") }
            Spacer(Modifier.height(12.dp))
            val vrcTone = when (status.vrchatOk) {
                true -> GremlinColors.Positive
                false -> GremlinColors.Caution
                null -> GremlinColors.Inactive
            }
            InfoCard(Modifier.fillMaxWidth().widenIn(stagger(reveal, 1, 4)), edge = if (status.vrchatOk == false) vrcTone.copy(alpha = 0.6f) else Color.Transparent) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Public, null, tint = vrcTone, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("VRChat", style = MaterialTheme.typography.titleSmall, color = GremlinColors.Text)
                        Text(
                            when (status.vrchatOk) {
                                true -> "All systems operational"
                                false -> status.vrchatDetail.ifBlank { "Having issues" }
                                null -> "Checking..."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (status.vrchatOk == false) GremlinColors.Caution else GremlinColors.TextMuted,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Box(Modifier.size(9.dp).background(vrcTone, CircleShape))
                }
            }
            Spacer(Modifier.height(10.dp))
            if (status.updatedTo != null) {
                InfoCard(Modifier.fillMaxWidth().weight(1f).widenIn(stagger(reveal, 2, 4)), edge = accent.copy(alpha = 0.45f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AutoAwesome, null, tint = accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Updated to v${status.updatedTo.removePrefix("v")}",
                            style = MaterialTheme.typography.titleSmall, color = GremlinColors.Text,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "NEW", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                            color = GremlinColors.Backdrop,
                            modifier = Modifier.background(accent, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    status.updateNotes.take(4).forEach { note ->
                        Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(5.dp).background(accent, CircleShape))
                            Spacer(Modifier.width(9.dp))
                            Text(
                                note, style = MaterialTheme.typography.bodyMedium, color = GremlinColors.Text,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            } else {
                Spacer(Modifier.weight(1f))
            }
            // Hidden until it's a number worth showing, so a fresh install never sees "0".
            if (status.lifetimeSends >= 100) {
                InfoCard(Modifier.fillMaxWidth().widenIn(stagger(reveal, 3, 4))) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            String.format(java.util.Locale.US, "%,d", status.lifetimeSends),
                            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = accent
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "chatbox updates sent", style = MaterialTheme.typography.bodySmall,
                            color = GremlinColors.TextMuted, modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
