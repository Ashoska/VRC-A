package com.vrca.app

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import com.vrca.ui.common.animatedOutline
import com.vrca.ui.common.widenIn
import androidx.compose.ui.graphics.toArgb
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.filled.Build
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
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
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.derivedStateOf
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
    NoAccess("No log access"),
    VrchatIssues("VRChat issues"),
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
    val icon: BootIcon? = null
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

/** A row icon's detail colour: the state's tone, but muted (not the dark edge colour) while idle. */
private fun BootState.detailTone(accent: Color): Color = when (this) {
    BootState.Skipped, BootState.Waiting -> GremlinColors.TextMuted
    else -> tone(accent)
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
 * The headset boot screen (driven by HeadsetBoot): the logo over the Status card on the left, the
 * checklist on the right, the Retry / Sign in pill underneath. Laid out on the fixed 1024x640dp
 * panel; rows are fixed-height and say one fixed word each, so nothing moves when a row changes.
 */
@Composable
fun BootScreen(
    checks: List<BootCheck>,
    status: BootStatus,
    error: String? = null,
    retryInSec: Int? = null,
    onRetry: () -> Unit = {},
    /** Open on the logo alone, big and centred, then glide it into place as the panels arrive. */
    playIntro: Boolean = true,
    /** The back wall's decoration (BootBackdrop.kt); faded in with the intro. */
    backdrop: BootBackdrop = BootBackdrop.Lanes,
    /** The VRChat display name for the checklist's "Welcome back" title; null before sign-in. */
    userName: String? = null,
    /**
     * False holds the checklist header's Ready moment (sweep + chip pop) even once every row has
     * settled; it plays when this turns true (AppBoot builds the app behind the screen first).
     */
    celebrate: Boolean = true,
    /** Called once the intro has finished and every row is on screen (right away without an intro). */
    onShown: () -> Unit = {},
    /**
     * Set true to play the way out: the panels fold back into their lines, the wall fades, and the
     * logo either glides + shrinks into the app's top-bar logo spot ([exitToTopBar], when the main
     * app is what opens next) or fades out in place. [onExited] runs when it's finished.
     */
    exit: Boolean = false,
    exitToTopBar: Boolean = true,
    onExited: () -> Unit = {},
    /** Non-null while VRChat needs signing in: shows a "Sign in to VRChat" pill under the panels. */
    onSignIn: (() -> Unit)? = null
) {
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
    // Both coordinates are kept and the logo's centre is derived from them. Computing it in the
    // logo's own callback needed the box's callback to have run first (their order isn't
    // guaranteed), which could leave the intro waiting on a blank screen until something moved.
    // PLAIN holders, read only where the logo is drawn: they're written on every layout pass, and
    // as state (never-equal) every pass rebuilt the whole screen, including frames where only a
    // row's text changed (UI Lab `perf`: 48 frames in a row). Only [placed] is state, set once.
    val coords = remember { arrayOfNulls<LayoutCoordinates>(2) } // [0] the screen, [1] the logo
    fun logoCenter(): Offset? {
        val r = coords[0]; val l = coords[1]
        return if (r != null && l != null && r.isAttached && l.isAttached)
            r.localPositionOf(l, Offset.Zero) + Offset(l.size.width / 2f, l.size.height / 2f)
        else null
    }
    // The logo's centre as state, written only when it actually moves (Offset compares by value,
    // so an unchanged layout pass invalidates nothing; the never-equal coordinates were the
    // per-pass rebuild above). The logo's layer block reads it, so it re-runs whenever the slot
    // moves: under strong skipping nothing else re-applies that block (the logo started in its
    // slot instead of centred, user's 2026-10-19 recording).
    var slotCenter by remember { mutableStateOf<Offset?>(null) }
    // Start once the logo's final slot is known, so the first frame already has it at the centre.
    var placed by remember { mutableStateOf(false) }
    fun notePlaced() {
        val c = logoCenter() ?: return
        if (c != slotCenter) slotCenter = c
        if (!placed) placed = true
    }
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
    val exitP = remember { Animatable(0f) }
    val exitedCallback by rememberUpdatedState(onExited)
    LaunchedEffect(exit) {
        if (!exit) return@LaunchedEffect
        // Reverse of the opening: right panel first, then the left, folding back to their lines.
        launch { panelR.animateTo(0f, tween(EXIT_FOLD_MS, easing = FastOutSlowInEasing)) }
        launch { delay(90); panelL.animateTo(0f, tween(EXIT_FOLD_MS, easing = FastOutSlowInEasing)) }
        delay(260)
        exitP.animateTo(1f, tween(EXIT_LOGO_MS, easing = FastOutSlowInEasing))
        exitedCallback()
    }
    val shownCallback by rememberUpdatedState(onShown)
    LaunchedEffect(Unit) {
        snapshotFlow { partsL.value >= 1f && partsR.value >= 1f }.first { it }
        shownCallback()
    }
    val rise = with(LocalDensity.current) { 22.dp.toPx() }
    val contentLayer = Modifier.graphicsLayer {
        alpha = contentIn.value * (1f - exitP.value)
        translationY = rise * (1f - contentIn.value)
    }
    // Accessibility: on the Quest an accessibility service is on, so Compose keeps a description of
    // everything on screen, and any element marked important hides what's under it. The boot screen
    // used to (its text and the Surface's traversal group), so the app built underneath during
    // the hand-off ([prepareApp]) only got described when the boot screen went: a 0.4 s main-thread
    // walk right at the hand-off (MainThreadWatch, user's 2026-10-18 recording). Now the decorative
    // parts are left out ([NoA11y]) and a plain Box replaces Surface (its empty pointerInput still
    // keeps taps off the app underneath), so the app is described while it's built, behind a still
    // screen. Retry and Sign in stay accessible.
    Box(Modifier.fillMaxSize().pointerInput(Unit) {}) {
        BoxWithConstraints(
            Modifier.fillMaxSize()
                // When the main app is next (already built and drawn underneath), the backdrop
                // fades out during the second part of the logo's glide, so the logo lands on an app
                // that's already appearing instead of on an empty panel (user's recordings showed a
                // ~0.3 s plain dark beat between the landing and the old reveal fade).
                .drawBehind {
                    val a = if (exitToTopBar) 1f - ((exitP.value - REVEAL_FROM) / (1f - REVEAL_FROM)).coerceIn(0f, 1f) else 1f
                    if (a > 0f) drawRect(GremlinColors.Backdrop.copy(alpha = a))
                }
                .onGloballyPositioned { coords[0] = it; notePlaced() }
        ) {
            BootBackdropLayer(backdrop, alpha = { contentIn.value * (1f - exitP.value) }, modifier = Modifier.matchParentSize())
            // Wide = the headset panel (or a tablet); a phone gets the stacked column.
            val wide = maxWidth >= 720.dp && maxHeight >= 560.dp
            val screenH = maxHeight
            val boxW = constraints.maxWidth.toFloat()
            val boxH = constraints.maxHeight.toFloat()
            // The logo: from the screen's centre to its slot, one size the whole way (user: a
            // straight slide reads cleaner than shrinking as it moves).
            val logo: @Composable (Dp) -> Unit = { height ->
                BootLogo(
                    height = height,
                    introScale = {
                        val toBar = if (exitToTopBar) 1f + (TOP_BAR_LOGO_HEIGHT / height - 1f) * exitP.value else 1f
                        (0.9f + 0.1f * logoIn.value) * toBar
                    },
                    // A lambda, read while drawing: as a value it rebuilt the whole screen on every
                    // frame of the logo intro and the fold (UI Lab `perf`).
                    fadeIn = { logoIn.value * (if (exitToTopBar) 1f else 1f - exitP.value) },
                    modifier = NoA11y
                        .onGloballyPositioned { coords[1] = it; notePlaced() }
                        .graphicsLayer {
                            val c = slotCenter
                            if (c != null) {
                                translationX = (boxW / 2f - c.x) * (1f - settle.value)
                                translationY = (boxH / 2f - c.y) * (1f - settle.value)
                                if (exitToTopBar && exitP.value > 0f) {
                                    // To the top bar's logo: centred, half the 64dp bar down.
                                    translationX += (boxW / 2f - c.x) * exitP.value
                                    translationY += (TOP_BAR_CENTER_Y.toPx() - c.y) * exitP.value
                                }
                            }
                        }
                )
            }
            val versionText: @Composable (Modifier) -> Unit = { m ->
                Text(
                    "v" + BuildConfig.VERSION_NAME.removePrefix("v"),
                    style = MaterialTheme.typography.labelMedium,
                    color = GremlinColors.TextMuted.copy(alpha = 0.85f),
                    modifier = m.then(contentLayer).then(NoA11y)
                )
            }
            val pills: @Composable () -> Unit = {
                BootPills(
                    error, retryInSec, onRetry, onSignIn,
                    centreAt = if (wide) { width, gap -> ((width - gap) * 0.85f / 2f + gap / 2f).toInt() } else null
                )
            }
            if (wide) {
                // Headset (or any wide screen): logo and the Status card on the left; the checklist
                // on the right. Both panels wrap their content (no empty strips) and sit bottom-aligned so their lower
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
                        Unfold({ panelR.value }, Modifier.fillMaxWidth().then(NoA11y)) {
                            StatusPanel(status, Modifier.fillMaxWidth(), { partsR.value }, rowHeight = WIDE_ROW_HEIGHT)
                        }
                    }
                    Unfold({ panelL.value }, Modifier.weight(1.15f).then(NoA11y)) {
                        LoadingPanel(checks, Modifier.fillMaxWidth(), { partsL.value }, userName = userName, celebrate = celebrate)
                    }
                }
                pills()
                }
            } else {
                // Phone: one column, logo, Status, checklist, then the pill, centred; it scrolls
                // only if a short screen can't fit it all.
                // On a short phone the pill can open below the fold: scroll down to it when it does.
                val scroll = rememberScrollState()
                val pillUp = error != null || onSignIn != null
                LaunchedEffect(pillUp) {
                    if (pillUp) { delay(250); scroll.animateScrollTo(scroll.maxValue) }
                }
                Column(
                    Modifier.fillMaxSize().verticalScroll(scroll)
                        .heightIn(min = maxHeight).padding(horizontal = 16.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // A short phone gets a smaller logo and tighter rows so it all still fits.
                    val compact = screenH < 760.dp
                    val rowH = if (compact) 32.dp else WIDE_ROW_HEIGHT
                    val rowGap = if (compact) 6.dp else 8.dp
                    logo(if (compact) PHONE_LOGO_HEIGHT_COMPACT else PHONE_LOGO_HEIGHT)
                    Spacer(Modifier.height(PHONE_GAP + 4.dp))
                    Column(Modifier.widthIn(max = PHONE_MAX_WIDTH).fillMaxWidth()) {
                        Unfold({ panelR.value }, Modifier.fillMaxWidth().then(NoA11y)) {
                            StatusPanel(status, Modifier.fillMaxWidth(), { partsR.value }, rowHeight = rowH, rowGap = rowGap)
                        }
                        Spacer(Modifier.height(PHONE_GAP))
                        Unfold({ panelL.value }, Modifier.fillMaxWidth().then(NoA11y)) {
                            LoadingPanel(checks, Modifier.fillMaxWidth(), { partsL.value }, userName = userName, rowHeight = rowH, rowGap = rowGap, celebrate = celebrate)
                        }
                        pills()
                    }
                    // Phone: the version sits under everything (a corner overlay would cover rows).
                    Spacer(Modifier.height(10.dp))
                    versionText(Modifier)
                }
            }
            if (wide) versionText(Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 10.dp))
        }
    }
}

/** Left out of accessibility (see BootScreen): decorative, and it would hide the app built underneath. */
private val NoA11y = Modifier.clearAndSetSemantics {}

/**
 * Retry (a blocking check couldn't reach the server; boot waits, user rule: no "Continue anyway")
 * or Sign in to VRChat, opening under the panels. [centreAt] places the pill's centre: on the
 * headset on the gap between the two panels; on a phone (null) it's centred.
 */
@Composable
private fun BootPills(
    error: String?, retryInSec: Int?, onRetry: () -> Unit,
    onSignIn: (() -> Unit)?, centreAt: ((width: Int, gap: Int) -> Int)?
) {
    // Kept while it animates away (onSignIn is already null by then).
    var lastSignIn by remember { mutableStateOf<(() -> Unit)?>(null) }
    if (onSignIn != null) lastSignIn = onSignIn
    AnimatedVisibility(error != null || onSignIn != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Layout(
            content = {
                val signIn = lastSignIn
                if (error == null && signIn != null) SignInPill(signIn) else RetryPill(retryInSec, onRetry)
            },
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp)
        ) { measurables, constraints ->
            val pill = measurables.first().measure(constraints.copy(minWidth = 0))
            val x = if (centreAt == null) (constraints.maxWidth - pill.width) / 2
                else centreAt(constraints.maxWidth, WIDE_GAP.roundToPx()) - pill.width / 2
            layout(constraints.maxWidth, pill.height) {
                pill.place(x.coerceIn(0, (constraints.maxWidth - pill.width).coerceAtLeast(0)), 0)
            }
        }
    }
}

/** The app's top-bar logo (VrcaScreen, headset): 56dp tall, centred in the 64dp bar. */
private val TOP_BAR_LOGO_HEIGHT = 56.dp
private val TOP_BAR_CENTER_Y = 32.dp
private const val EXIT_FOLD_MS = 420
private const val EXIT_LOGO_MS = 700
/** Point of the logo's glide (0-1) where the backdrop starts fading to show the app. */
private const val REVEAL_FROM = 0.35f

/**
 * The logo on its own (no tile: a speech bubble inside a box read oddly), with a flat
 * silhouette copy offset below it as its drop layer. Steady: a "breathing" fade while checks ran
 * was removed (it read as the logo blipping brighter and dimmer).
 */
@Composable
private fun BootLogo(height: Dp, introScale: () -> Float, fadeIn: () -> Float, modifier: Modifier = Modifier) {
    // The logo as a vector (vrca_logo_vector, traced by tools/symbol-lab/logo_trace.py): sharp at any size, which
    // matters on the headset where the whole panel zooms when its window is resized.
    val logo = com.vrca.ui.common.appLogoPainter()
    val aspect = logo.intrinsicSize.width / logo.intrinsicSize.height
    Box(modifier.height(height), contentAlignment = Alignment.Center) {
        Box(
            Modifier.requiredHeight(height).graphicsLayer {
                val s = introScale()
                scaleX = s; scaleY = s
            }
        ) {
            Image(
                painter = logo, contentDescription = null,
                colorFilter = ColorFilter.tint(DropLayer.copy(alpha = 0.55f)),
                modifier = Modifier.fillMaxHeight().aspectRatio(aspect).offset(y = 5.dp).graphicsLayer { alpha = fadeIn() }
            )
            Image(
                painter = logo, contentDescription = "VRC-A",
                // The fade sits on the images themselves, not on the oversized box around them:
                // a see-through layer whose content spills past its bounds drew nothing in the
                // UI Lab's software renderer (the intro's fade-in recorded as blank).
                modifier = Modifier.fillMaxHeight().aspectRatio(aspect).graphicsLayer { alpha = fadeIn() }
            )
        }
    }
}

/** The logo has the whole left column; sized so its top lines up with the checklist's. */
private val WIDE_LOGO_HEIGHT = 238.dp
private val WIDE_LOGO_GAP = 8.dp
/** Phone: the logo above the cards, the gap between pieces, and how wide the cards may get. */
private val PHONE_LOGO_HEIGHT = 150.dp
private val PHONE_LOGO_HEIGHT_COMPACT = 104.dp
private val PHONE_GAP = 12.dp
private val PHONE_MAX_WIDTH = 480.dp
/** Headset row height, shared by check rows and status rows so both panels match. */
private val WIDE_ROW_HEIGHT = 38.dp
/** Headset gap between the left column and the checklist; the error buttons meet across it. */
private val WIDE_GAP = 28.dp

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
/**
 * How many of a panel's [pieces] have fully appeared. Changes only when one finishes, so a panel
 * recomposes [pieces] times during its reveal instead of on every frame (the reveal itself is
 * read in [widenIn], at draw time).
 */
@Composable
private fun revealedCount(reveal: () -> Float, pieces: Int): Int {
    val r by rememberUpdatedState(reveal)
    val count by remember(pieces) { derivedStateOf { (0 until pieces).count { stagger(r(), it, pieces) >= 1f } } }
    return count
}

/**
 * The checklist's header: "Welcome back, <VRChat name>" (just "Welcome back" before sign-in), a
 * count chip coloured by how boot is going (accent while running, green when ready, red on a
 * failure), and one bar segment per check in row order. Segments take their row's colour; a
 * running one breathes, so the bar shows what's still going at a glance. VRChat names are at most
 * 15 characters, so the title always fits one line.
 */
@Composable
private fun BootHeader(checks: List<BootCheck>, userName: String?, celebrate: Boolean = true) {
    val accent = MaterialTheme.colorScheme.primary
    val finished = checks.count { it.state in Settled }
    val target = when {
        checks.any { it.state == BootState.Failed } -> GremlinColors.Negative
        checks.all { it.state in Settled } -> GremlinColors.Positive
        else -> accent
    }
    val tone by animateColorAsState(target, label = "headerTone")
    // The breathing pulse, the Ready sweep and the chip pop are read only in draw/layer lambdas
    // below, never here: read in composition they rebuilt the whole header every frame, which is
    // what stutters on a Quest whose CPU VRChat mostly owns. Now they only repaint.
    val pulse = rememberInfiniteTransition(label = "segPulse").animateFloat(
        0.45f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "segPulseA"
    )
    // The Ready moment: when the last check settles, a light sweeps along the bar left to right
    // and the count chip pops, so even a very fast boot visibly "lands".
    val done = celebrate && checks.isNotEmpty() && checks.all { it.state in Settled } && checks.none { it.state == BootState.Failed }
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
                modifier = Modifier.graphicsLayer { scaleX = chipPop.value; scaleY = chipPop.value }.background(tone.copy(alpha = 0.14f), CircleShape).padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().height(6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            checks.forEachIndexed { i, c ->
                val seg = animateColorAsState(
                    when (c.state) {
                        BootState.Waiting -> GremlinColors.Raised
                        BootState.Skipped -> GremlinColors.Inactive
                        else -> c.state.tone(accent)
                    },
                    label = "seg"
                )
                val working = c.state == BootState.Working
                // How much of the Ready sweep is on this segment right now (0..1).
                fun glow() = (1f - kotlin.math.abs(sweep.value - i - 0.5f)).coerceIn(0f, 1f)
                Box(
                    Modifier.weight(1f).fillMaxHeight().graphicsLayer { scaleY = 1f + glow() * 0.6f }
                        .drawBehind {
                            val base = seg.value
                            val a = if (working) pulse.value else 1f
                            val color = androidx.compose.ui.graphics.lerp(base.copy(alpha = base.alpha * a), GremlinColors.Text, glow() * 0.75f)
                            drawRoundRect(color, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2, size.height / 2))
                        }
                )
            }
        }
    }
}

/** The checklist: the header, then one row per check. Wraps its rows (no fixed height). */
@Composable
private fun LoadingPanel(
    checks: List<BootCheck>, modifier: Modifier, reveal: () -> Float = { 1f }, userName: String? = null,
    rowHeight: Dp = WIDE_ROW_HEIGHT, rowGap: Dp = 8.dp, celebrate: Boolean = true
) {
    val pieces = checks.size + 1 // the header, then each check
    // A row shows "waiting" until it has fully appeared, so a check that finished during the intro
    // still lands its tick on screen instead of arriving already ticked.
    val shown = revealedCount(reveal, pieces)
    val checks = checks.mapIndexed { i, c ->
        if (1 + i >= shown) c.copy(state = BootState.Waiting, note = null) else c
    }
    Layer(modifier) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
            Box(Modifier.widenIn { stagger(reveal(), 0, pieces) }) { BootHeader(checks, userName, celebrate) }
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(rowGap)) {
                checks.forEachIndexed { i, c ->
                    BootCheckTile(c, Modifier.widenIn { stagger(reveal(), 1 + i, pieces) }, height = rowHeight)
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
    // Waiting rows are dimmed; when a row comes alive it fades up over ~0.35 s instead of snapping
    // to full (user: rows' text "quickly popping to white"). Read only in draw/layer lambdas.
    val lit = animateFloatAsState(if (dim) 0f else 1f, tween(350, easing = FastOutSlowInEasing), label = "rowLit")
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
            .drawBehind { drawRect(GremlinColors.Card.copy(alpha = 0.5f + 0.5f * lit.value)) }
            .rowEdge(check.state.mark(), tone)
            // The row's content (not its outline) at the dim level.
            .graphicsLayer { alpha = 0.4f + 0.6f * lit.value }
            .padding(start = 10.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (check.icon != null) {
            // The icon's small detail takes the row's state colour (purple while checking, then
            // green / amber / red); its lines stay muted like the label's side of the row.
            val detail by animateColorAsState(check.state.detailTone(accent), tween(300), label = "iconDetail")
            BootIconView(check.icon, { GremlinColors.TextMuted }, { detail }, Modifier.size(19.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(
            check.label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = GremlinColors.Text,
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
                Mark.Working -> com.vrca.ui.common.RenderThreadSpinner(accent, Modifier.size(15.dp))
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
 * Drawn here (1dp, 12dp corners like the row's clip) with the animations read at DRAW time, so a
 * flash or a colour change only repaints the row instead of rebuilding it every frame.
 */
@Composable
private fun Modifier.rowEdge(mark: Mark, tone: Color, edgeWhileWorking: Boolean = true): Modifier {
    val flash = remember { Animatable(0f) }
    var last by remember { mutableStateOf(mark) }
    LaunchedEffect(mark) {
        if (mark == Mark.Done && last != Mark.Done) {
            // Rise, then fade (it used to jump straight to full: a quick flash on the headset).
            flash.animateTo(0.7f, tween(160, easing = FastOutSlowInEasing))
            flash.animateTo(0f, tween(700, easing = FastOutSlowInEasing))
        }
        last = mark
    }
    val steady = animateColorAsState(
        when (mark) {
            Mark.Working -> if (edgeWhileWorking) tone.copy(alpha = 0.7f) else Color.Transparent
            Mark.Warn, Mark.Maintenance, Mark.Failed -> tone.copy(alpha = 0.6f)
            else -> Color.Transparent
        },
        tween(400, easing = FastOutSlowInEasing),
        label = "rowEdge"
    )
    return animatedOutline({
        val f = flash.value
        if (f > 0f) GremlinColors.Positive.copy(alpha = f) else steady.value
    })
}

/** One service in the Status panel: icon, name, its fixed state word and a coloured dot. Built like a check row. */
@Composable
private fun ServiceRow(icon: BootIcon, name: String, text: String, mark: Mark, modifier: Modifier, height: Dp) {
    val target = when (mark) {
        Mark.Done -> GremlinColors.Positive
        Mark.Warn, Mark.Maintenance -> GremlinColors.Caution
        Mark.Failed -> GremlinColors.Negative
        else -> GremlinColors.Inactive
    }
    val tone by animateColorAsState(target, label = "serviceTone")
    val warn = mark == Mark.Warn || mark == Mark.Maintenance || mark == Mark.Failed
    // No outline while just checking: three outlined rows at once on every open read as alarm.
    Row(
        modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(12.dp))
            .background(GremlinColors.Card)
            .rowEdge(mark, tone, edgeWhileWorking = false)
            .padding(start = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Crossfade(icon, label = "serviceIcon") { BootIconView(it, { tone }, { tone }, Modifier.size(19.dp)) }
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
private fun StatusPanel(status: BootStatus, modifier: Modifier, reveal: () -> Float = { 1f }, rowHeight: Dp = WIDE_ROW_HEIGHT, rowGap: Dp = 8.dp) {
    val shown = revealedCount(reveal, 3)
    Layer(modifier) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
            // No header (user): the rows speak for themselves.
            Column(verticalArrangement = Arrangement.spacedBy(rowGap)) {
                // Same as the checklist: a row says "Checking..." until it has fully appeared.
                val net = if (0 >= shown) InternetLink.Checking else status.internet
                ServiceRow(
                    if (net == InternetLink.None) BootIcons.NoInternet else BootIcons.Internet, "Internet",
                    if (status.internetMbps > 0 && net != InternetLink.None && net != InternetLink.Checking)
                        "${net.text} · ${status.internetMbps.coerceAtMost(9999)} Mbps" else net.text,
                    when (net) {
                        InternetLink.Checking -> Mark.Working
                        InternetLink.None -> Mark.Failed
                        else -> Mark.Done
                    },
                    Modifier.widenIn { stagger(reveal(), 0, 3) }, rowHeight
                )
                for ((i, row) in listOf(
                    // Each service has its own icon now (user, 2026-10-22; they used to share one).
                    Triple(BootIcons.Vrchat, "VRChat", if (1 >= shown) ServiceState.Checking else status.vrchat),
                    // Never names the backend (public UI rule): just "VRC-A servers".
                    Triple(BootIcons.VrcaServers, "VRC-A servers", if (2 >= shown) ServiceState.Checking else status.servers)
                ).withIndex()) {
                    val (icon, name, state) = row
                    ServiceRow(icon, name, state.text, state.mark(), Modifier.widenIn { stagger(reveal(), 1 + i, 3) }, rowHeight)
                }
            }
        }
    }
}


/**
 * The Retry pill. Its countdown to the automatic retry drains as a ring round the seconds left
 * (the ring's full length is the longest countdown seen, since the wait can vary).
 */
@Composable
private fun RetryPill(retryInSec: Int?, onRetry: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    var longest by remember { mutableIntStateOf(1) }
    if ((retryInSec ?: 0) > longest) longest = retryInSec ?: 0
    val ring by animateFloatAsState(((retryInSec ?: 0).toFloat() / longest).coerceIn(0f, 1f), label = "retryRing")
    Layer(Modifier.height(50.dp), shape = CircleShape, color = GremlinColors.Raised, drop = 4.dp) {
        Row(Modifier.fillMaxHeight().padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.fillMaxHeight().clip(CircleShape).background(accent).clickable(onClick = onRetry).padding(start = 8.dp, end = 20.dp),
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
        }
    }
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

/** "Sign in to VRChat": shown under the panels while the VRChat session needs signing in. */
@Composable
private fun SignInPill(onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Layer(
        Modifier.height(50.dp).clip(CircleShape).clickable(onClick = onClick),
        shape = CircleShape, color = accent, edge = Color.Transparent, drop = 4.dp
    ) {
        Row(Modifier.fillMaxHeight().padding(start = 20.dp, end = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Person, null, tint = GremlinColors.Backdrop, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(10.dp))
            Text("Sign in to VRChat", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = GremlinColors.Backdrop)
            Spacer(Modifier.width(10.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = GremlinColors.Backdrop, modifier = Modifier.size(18.dp))
        }
    }
}
