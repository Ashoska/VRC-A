package com.vrca.app

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.vector.path
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.zIndex
import com.vrca.ui.common.animatedOutline
import com.vrca.ui.common.widenIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.togetherWith
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeOut
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
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
    Ready("Ready"),
    Downloading("Downloading"),
    WillRetry("Will retry"),
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
    /** Connected to a network, but it doesn't reach the internet (no internet behind the Wi-Fi, or a sign-in page). */
    NoAccess("No internet access"),
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
internal fun Layer(
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
     * A new install's first open (tutorial not done): a welcome instead of the two panels. The logo,
     * then Boot Cat under it narrating what he's preparing ([WelcomeLayout]).
     */
    firstOpen: Boolean = false,
    /** First open: Boot Cat has listed every check (AppBoot waits for it before the Ready moment). */
    onWelcomeListed: () -> Unit = {},
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
    // First open: Boot Cat's entrance under the logo (WelcomeLayout).
    val catIn = remember { Animatable(start) }
    val catMove = remember { Animatable(start) }
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
        if (firstOpen) {
            // First open: Boot Cat hops in under the centred logo and says hi before anything moves.
            delay(260)
            catIn.animateTo(1f, tween(560, easing = FastOutSlowInEasing))
            delay(BOOT_CAT_HELLO_MS)
        }
        delay(420)
        launch { settle.animateTo(1f, tween(760, easing = FastOutSlowInEasing)) }
        // First open: Boot Cat jumps across just behind the logo's glide, landing as his panel opens.
        if (firstOpen) {
            launch { delay(BOOT_CAT_JUMP_DELAY_MS); catMove.animateTo(1f, tween(BOOT_CAT_JUMP_MS, easing = LinearEasing)) }
            // His panel opens while he's in the air, so he lands in it rather than waiting on an empty wall.
            launch { delay(160); panelR.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
        }
        delay(300)
        launch { contentIn.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) }
        delay(220)
        launch { panelL.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
        delay(130)
        if (!firstOpen) launch { panelR.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
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
            val pills: @Composable () -> Unit = {
                BootPills(
                    error, retryInSec, onRetry, onSignIn,
                    centreAt = if (wide && !firstOpen) { width, gap -> ((width - gap) * 0.85f / 2f + gap / 2f).toInt() } else null
                )
            }
            if (firstOpen) {
                WelcomeLayout(
                    checks, status, wide, error, logo = logo, pills = pills,
                    catIn = { catIn.value }, move = { catMove.value }, panelIn = { panelR.value }, out = { exitP.value },
                    celebrate = celebrate, onListed = onWelcomeListed
                )
            } else if (wide) {
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
                }
            }
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
        sweep.snapTo(-1f)
        launch { delay(SWEEP_TO_CHIP_MS); chipPop.animateTo(1.18f, tween(140)); chipPop.animateTo(1f, spring(dampingRatio = 0.4f)) }
        sweep.animateTo(checks.size + 1f, tween(650, easing = FastOutSlowInEasing))
    }
    Column {
        Row(Modifier.height(30.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (userName.isNullOrBlank()) "Welcome back" else "Welcome back, $userName",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = GremlinColors.Text,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(10.dp))
        // The bar, then the app version at its end (user, 2026-10-23: next to the title it sat oddly).
        // The chip takes the header tone and pops when the Ready sweep reaches it.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).height(6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
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
        Spacer(Modifier.width(10.dp))
        Text(
            "v" + BuildConfig.VERSION_NAME.removePrefix("v"),
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = tone,
            modifier = Modifier.graphicsLayer { scaleX = chipPop.value; scaleY = chipPop.value }.background(tone.copy(alpha = 0.14f), CircleShape).padding(horizontal = 10.dp, vertical = 3.dp)
        )
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
            // Every check landed well: the cats hop one after another down the list, with the bar's sweep.
            val allDone = celebrate && checks.isNotEmpty() && checks.all { it.state in Settled } && checks.none { it.state == BootState.Failed }
            Column(verticalArrangement = Arrangement.spacedBy(rowGap)) {
                checks.forEachIndexed { i, c ->
                    BootCheckTile(c, Modifier.widenIn { stagger(reveal(), 1 + i, pieces) }, height = rowHeight, index = i, celebrate = allDone)
                }
            }
        }
    }
}

/** A check as a layer-2 tile: category icon, label, reason, and its state mark on the right. */
@Composable
private fun BootCheckTile(check: BootCheck, modifier: Modifier = Modifier, height: Dp = 31.dp, index: Int = 0, celebrate: Boolean = false) {
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
        StateMark(check.state.mark(), variant = index, celebrate = celebrate, hopDelayMs = 250L + index * 130L)
    }
}

/** The state symbols, shared by check rows and Status rows so both panels speak one language. */
internal enum class Mark { Waiting, Working, Background, Done, Warn, Maintenance, Failed, Skipped }

private fun BootState.mark(): Mark = when (this) {
    BootState.Waiting -> Mark.Waiting
    BootState.Working -> Mark.Working
    BootState.Background -> Mark.Background
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
 * A row's state mark: the boot cat ([BootCat]), shared by check rows and Status rows so both panels
 * speak one language. [variant] desyncs rows that think at the same time; [celebrate] + [hopDelayMs]
 * make the all-done hop run one row after another.
 */
@Composable
private fun StateMark(mark: Mark, variant: Int, celebrate: Boolean = false, hopDelayMs: Long = 0) {
    BootCat(mark, variant, Modifier.size(24.dp), celebrate, hopDelayMs)
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
            Mark.Working, Mark.Background -> if (edgeWhileWorking) tone.copy(alpha = 0.7f) else Color.Transparent
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
private fun ServiceRow(
    icon: BootIcon, name: String, text: String, mark: Mark, modifier: Modifier, height: Dp, variant: Int = 0,
    celebrate: Boolean = false, hopDelayMs: Long = 0
) {
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
        StateMark(mark, variant, celebrate, hopDelayMs)
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
                    if (status.internetMbps > 0 && net != InternetLink.None && net != InternetLink.Checking && net != InternetLink.NoAccess)
                        "${net.text} · ${status.internetMbps.coerceAtMost(9999)} Mbps" else net.text,
                    when (net) {
                        InternetLink.Checking -> Mark.Working
                        InternetLink.None -> Mark.Failed
                        // Amber: the headset is fine, the network it's on isn't (user, 2026-10-23).
                        InternetLink.NoAccess -> Mark.Warn
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
                    ServiceRow(icon, name, state.text, state.mark(), Modifier.widenIn { stagger(reveal(), 1 + i, 3) }, rowHeight, variant = 2 + i)
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
    // The app's button (GremlinButton) with the countdown to the automatic retry as its icon: Boot Cat
    // whose outline drains as the seconds pass (BootCatCountdown), the seconds left in the label.
    com.vrca.ui.common.GremlinButton(
        if (retryInSec != null && retryInSec > 0) "Retry  $retryInSec" else "Retry", onRetry,
        size = com.vrca.ui.common.GremlinButtonSize.Large,
        leading = {
            BootCatCountdown(((retryInSec ?: 0).toFloat() / longest).coerceIn(0f, 1f), Modifier.size(28.dp), accent)
        }
    )
}

/** The Internet row: the active connection's type and Android's download-speed estimate (Mbps, 0 = unknown). */
fun internetStatus(context: android.content.Context): Pair<InternetLink, Int> {
    val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return InternetLink.None to 0
    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
    if (caps == null) return InternetLink.None to 0
    // On a network that Android couldn't confirm reaches the internet (nothing behind the Wi-Fi, or a
    // café / hotel sign-in page): a warning, not "Not connected".
    if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) return InternetLink.NoAccess to 0
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
    com.vrca.ui.common.GremlinButton(
        "Sign in to VRChat", onClick, size = com.vrca.ui.common.GremlinButtonSize.Large,
        // The same icon as the VRChat sign-in row it fixes (our own icons, not Material's).
        bootIcon = BootIcons.VrchatSignIn
    )
}

/** One check Boot Cat goes through on the first open: what he says while it runs, and its row once done. */
private class WelcomeStep(val icon: BootIcon, val name: String, val working: String, val result: Pair<Mark, String>?)

/** The first open's checks, in the order Boot Cat goes through them; a result is null while it's running (or failed). */
private fun welcomeSteps(checks: List<BootCheck>, status: BootStatus): List<WelcomeStep> {
    fun check(label: String) = checks.firstOrNull { it.label == label }
    fun row(c: BootCheck?): Pair<Mark, String>? = when (c?.state) {
        BootState.Done, BootState.Warn, BootState.Skipped, BootState.Background -> c.state.mark() to (c.note?.text ?: "")
        else -> null
    }
    fun service(s: ServiceState): Pair<Mark, String>? = if (s == ServiceState.Checking) null else s.mark() to s.text
    val net = status.internet
    return listOf(
        WelcomeStep(
            BootIcons.Internet, "Internet", "Sniffing out your Wi-Fi...",
            when (net) {
                InternetLink.Checking, InternetLink.None -> null
                InternetLink.NoAccess -> Mark.Warn to net.text
                else -> Mark.Done to (if (status.internetMbps > 0) "${net.text} · ${status.internetMbps.coerceAtMost(9999)} Mbps" else net.text)
            }
        ),
        // Order (like the normal boot's dependency order): the connection, then VRC-A's side (this
        // device's session, our servers, the account check), then VRChat, then what comes next.
        WelcomeStep(BootIcons.DeviceSession, "Device session", "Getting this device its name tag...", row(check("Device session"))),
        WelcomeStep(BootIcons.VrcaServers, "VRC-A servers", "Saying hi to the VRC-A servers...", service(status.servers)),
        WelcomeStep(BootIcons.Account, "Account standing", "Checking your paws are clean...", row(check("Account standing"))),
        WelcomeStep(BootIcons.Vrchat, "VRChat", "Checking VRChat is awake...", service(status.vrchat)),
        WelcomeStep(BootIcons.Tutorial, "Tutorial", "Fetching your tutorial and terms...", row(check("Tutorial")))
    )
}

/**
 * First open, with Boot Cat (testers' name for the state cat) as the guide: the logo fades in, he
 * hops in under it and says hi; then the logo glides left like every boot, he glides with his
 * speech bubble to the top of the panel that unfolds on the right, and goes through the checks one
 * by one. Each finished check drops into the list under him, newest on top, pushing the older ones
 * down. His bubble is a fixed size (a bubble sized to its line kept jumping, user 2026-10-23).
 */
@Composable
private fun WelcomeLayout(
    checks: List<BootCheck>, status: BootStatus, wide: Boolean, error: String?,
    logo: @Composable (Dp) -> Unit, pills: @Composable () -> Unit,
    catIn: () -> Float, move: () -> Float, panelIn: () -> Float, out: () -> Float,
    celebrate: Boolean, onListed: () -> Unit
) {
    val steps = welcomeSteps(checks, status)
    val results by rememberUpdatedState(steps.map { it.result })
    // Indexes into [steps], newest first. A Retry restarts the checks (rows back to Waiting), so a
    // listed check that loses its result empties the list and he starts over.
    val listed = remember { mutableStateListOf<Int>() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var next by remember { mutableIntStateOf(0) }
    val listedCallback by rememberUpdatedState(onListed)
    LaunchedEffect(Unit) {
        snapshotFlow { panelIn() >= 1f }.first { it }
        while (true) {
            fun regressed() = listed.any { results[it] == null }
            if (next >= steps.size) {
                listedCallback()
                snapshotFlow { regressed() }.first { it }
            } else {
                val t0 = android.os.SystemClock.uptimeMillis()
                snapshotFlow { results[next] != null || regressed() }.first { it }
                if (!regressed()) {
                    // Each line stays up a moment, so a quick check is still seen being done.
                    delay((BOOT_CAT_LINE_MS - (android.os.SystemClock.uptimeMillis() - t0)).coerceAtLeast(0L))
                    if (!regressed()) {
                        // A lazy list keeps its first row in place when one is added above it (the new
                        // row landed half out of view, scrolled up); keep the list at its top instead.
                        listState.requestScrollToItem(0)
                        listed.add(0, next); next++; continue
                    }
                }
            }
            listed.clear(); next = 0
        }
    }
    // What he says: hello (until he starts moving), then the check he's on, a failure, or his verdict.
    var helloTwo by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshotFlow { catIn() >= 1f }.first { it }
        delay(BOOT_CAT_HELLO_MS / 2)
        helloTwo = true
    }
    val moving by remember { derivedStateOf { move() > 0f } }
    // A check that's taking long gets a second line, so a slow boot doesn't look stuck.
    var slow by remember { mutableStateOf(false) }
    val narrating by remember { derivedStateOf { panelIn() >= 1f } }
    LaunchedEffect(next, narrating) { slow = false; if (!narrating) return@LaunchedEffect; delay(BOOT_CAT_SLOW_MS); slow = true }
    val bubbleBack = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        snapshotFlow { move() >= 1f }.first { it }
        bubbleBack.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 380f))
    }
    val (mark, line) = when {
        !moving && !helloTwo -> Mark.Done to "Hi! I'm Boot Cat"
        // (`narrating`, not panelIn() itself: reading the unfold here rebuilt this every frame of it.)
        !moving || !narrating && next == 0 -> Mark.Done to "I'll get everything ready for you"
        error != null || status.internet == InternetLink.None -> Mark.Failed to welcomeFailure(checks, status)
        next < steps.size -> Mark.Working to (if (slow) "This one's taking a while..." else steps[next].working)
        !celebrate -> Mark.Working to "Almost there..."
        else -> welcomeVerdict(checks, status)
    }
    val accent = MaterialTheme.colorScheme.primary
    val mood by animateColorAsState(
        when (mark) {
            Mark.Done -> GremlinColors.Positive
            Mark.Warn, Mark.Maintenance -> GremlinColors.Caution
            Mark.Failed -> GremlinColors.Negative
            else -> accent
        }.copy(alpha = 0.75f), tween(300), label = "bootCatMood"
    )
    val rowH = if (wide) WIDE_ROW_HEIGHT else 34.dp
    val rowGap = if (wide) 8.dp else 6.dp
    val logoH = if (wide) WIDE_LOGO_HEIGHT else PHONE_LOGO_HEIGHT * 1.15f
    // Where his spot in the panel is (the panel's outer box isn't scaled by its unfold, so this is
    // the open panel's real place), as state written only when it moves.
    val density = LocalDensity.current
    val pad = with(density) { Offset(18.dp.toPx(), 16.dp.toPx()) }
    val box = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    var spot by remember { mutableStateOf<Offset?>(null) }
    var spotW by remember { mutableStateOf(0.dp) }
    // The logo's drawn width (it keeps its picture's shape), read where Boot Cat is placed.
    var logoW by remember { mutableIntStateOf(0) }
    val logoWidth = Modifier.onSizeChanged { if (it.width != logoW) logoW = it.width }
    val allDone = celebrate && next >= steps.size && error == null
    val sweep = remember { Animatable(-2f) }
    val chipPop = remember { Animatable(1f) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { box[0] = it }) {
        val panel: @Composable (Modifier) -> Unit = { m ->
            Unfold(panelIn, m.onGloballyPositioned { c ->
                val root = box[0] ?: return@onGloballyPositioned
                if (!root.isAttached || !c.isAttached) return@onGloballyPositioned
                val at = root.localPositionOf(c, Offset.Zero) + pad
                if (at != spot) spot = at
                val w = with(density) { (c.size.width - 2 * pad.x).toDp() }
                if (w != spotW) spotW = w
            }.then(NoA11y)) {
                Layer(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
                        // The version, like the normal boot's header chip, in the colour of how it's going.
                            val tone by animateColorAsState(
                                when {
                                    error != null -> GremlinColors.Negative
                                    next >= steps.size && celebrate -> GremlinColors.Positive
                                    else -> accent
                                }, label = "welcomeChip"
                            )
                        val chip: @Composable () -> Unit = {
                            Text(
                                "v" + BuildConfig.VERSION_NAME.removePrefix("v"),
                                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = tone,
                                modifier = Modifier.graphicsLayer { scaleX = chipPop.value; scaleY = chipPop.value }
                                    .background(tone.copy(alpha = 0.14f), CircleShape).padding(horizontal = 10.dp, vertical = 3.dp)
                            )
                        }
                        Spacer(Modifier.fillMaxWidth().height(BOOT_CAT_ROW_HEIGHT)) // his spot
                        Spacer(Modifier.height(12.dp))
                        // One segment per check, in check order (the normal boot's bar): its result's colour
                        // once listed, the accent (breathing) on the one he's doing, dark while still to come.
                        // All done: a light sweeps along the strip and the chip pops (the normal boot's Ready moment).
                        LaunchedEffect(allDone) {
                            if (!allDone) { sweep.snapTo(-2f); return@LaunchedEffect }
                            sweep.snapTo(-1f)
                            launch { delay(SWEEP_TO_CHIP_MS); chipPop.animateTo(1.18f, tween(140)); chipPop.animateTo(1f, spring(dampingRatio = 0.4f)) }
                            sweep.animateTo(steps.size + 1f, tween(650, easing = FastOutSlowInEasing))
                        }
                        val pulse = rememberInfiniteTransition(label = "welcomePulse").animateFloat(
                            0.45f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "welcomePulseA"
                        )
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).height(6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            steps.indices.forEach { i ->
                                val done = i in listed
                                val current = i == next && error == null
                                val seg by animateColorAsState(
                                    when {
                                        done -> when (results[i]?.first) {
                                            Mark.Warn, Mark.Maintenance -> GremlinColors.Caution
                                            Mark.Failed -> GremlinColors.Negative
                                            else -> GremlinColors.Positive
                                        }
                                        i == next && error != null -> GremlinColors.Negative
                                        current -> accent
                                        else -> GremlinColors.Raised
                                    }, label = "welcomeSeg"
                                )
                                // How much of the all-done sweep is on this segment right now (0..1).
                                fun glow() = (1f - kotlin.math.abs(sweep.value - i - 0.5f)).coerceIn(0f, 1f)
                                Box(Modifier.weight(1f).fillMaxHeight().graphicsLayer { scaleY = 1f + glow() * 0.6f }.drawBehind {
                                    val a = if (current) pulse.value else 1f
                                    val c = androidx.compose.ui.graphics.lerp(seg.copy(alpha = seg.alpha * a), GremlinColors.Text, glow() * 0.75f)
                                    drawRoundRect(c, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2, size.height / 2))
                                })
                            }
                        }
                        // The version at the strip's end, as on the normal boot.
                        Spacer(Modifier.width(10.dp)); chip()
                        }
                        Spacer(Modifier.height(12.dp))
                        // Finished checks on top (newest first), then the ones still to come as faint rows in
                        // check order. A check keeps its key, so finishing one moves its row from the faint
                        // part up to the top.
                        val order = listed.toList() + steps.indices.filter { it !in listed }
                        androidx.compose.foundation.lazy.LazyColumn(
                            Modifier.fillMaxWidth().height(rowH * steps.size + rowGap * (steps.size - 1)),
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(rowGap), userScrollEnabled = false
                        ) {
                            items(order.size, key = { order[it] }) { k ->
                                val i = order[k]
                                val r = results[i]
                                // All done: the cats hop one after another down the list, like the normal boot.
                                if (i in listed && r != null) ServiceRow(
                                    steps[i].icon, steps[i].name, r.second, r.first, Modifier.animateItem(), rowH, variant = i,
                                    celebrate = allDone, hopDelayMs = 250L + k * 130L
                                )
                                else ComingRow(steps[i].icon, steps[i].name, Modifier.animateItem(), rowH)
                            }
                        }
                    }
                }
            }
        }
        if (wide) {
            Column(Modifier.fillMaxWidth().align(Alignment.Center).padding(horizontal = 32.dp, vertical = 22.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WIDE_GAP)) {
                    Box(Modifier.weight(0.85f), contentAlignment = Alignment.Center) { Box(logoWidth) { logo(logoH) } }
                    // Retry opens under the panel, centred on it.
                    Column(Modifier.weight(1.15f)) { panel(Modifier.fillMaxWidth()); pills() }
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
            ) {
                Box(logoWidth) { logo(logoH) }
                Spacer(Modifier.height(PHONE_GAP + 4.dp))
                panel(Modifier.widthIn(max = PHONE_MAX_WIDTH).fillMaxWidth())
                pills()
            }
        }
        // Boot Cat and his bubble: first under the centred logo, then gliding (with the logo) to his
        // spot at the top of the panel.
        val at = spot
        val helloW = if (wide) 380.dp else 340.dp
        if (at != null) {
            Row(
                // A compact speaker for the hello (centred under the logo), the panel's full width once landed
                // (the bubble is hidden while the width changes).
                NoA11y.width(if (move() < 1f) helloW else spotW).height(BOOT_CAT_ROW_HEIGHT)
                    .offset {
                        val root = box[0]
                        val w = root?.size?.width?.toFloat() ?: 0f
                        val h = root?.size?.height?.toFloat() ?: 0f
                        // Under the centred logo, his left edge in line with the logo's left edge.
                        val hello = Offset((w - logoW) / 2f, h / 2f + logoH.toPx() / 2f + 14.dp.toPx())
                        // A jump: eased across, with an arc on top (his hop, made bigger), and the hop-in on arrival.
                        // Sideways first, then up (his path curves away from the logo instead of
                        // crossing its tail), with a small arc on top.
                        val raw = move()
                        val mx = FastOutSlowInEasing.transform(raw)
                        // (He starts under the logo, so he stays low until he's past its tail, then rises.)
                        val ry = ((raw - 0.4f) / 0.6f).coerceIn(0f, 1f)
                        val my = FastOutSlowInEasing.transform(ry)
                        val arc = -kotlin.math.sin(ry * kotlin.math.PI.toFloat()) * BOOT_CAT_JUMP_ARC.toPx()
                        val p = catIn()
                        val hop = -kotlin.math.sin(p * kotlin.math.PI.toFloat()) * 18.dp.toPx()
                        IntOffset(
                            (hello.x + (at.x - hello.x) * mx).toInt(),
                            (hello.y + (at.y - hello.y) * my + arc + hop).toInt()
                        )
                    }
                    // Leaves with his panel as it folds away (then the rest fades).
                    .graphicsLayer { alpha = (catIn() * 3f).coerceAtMost(1f) * (1f - out()) * (if (move() >= 1f) panelIn() else 1f) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                BootCat(mark, 0, Modifier.size(if (wide) 40.dp else 36.dp), celebrate = celebrate && next >= steps.size && mark == Mark.Done)
                Spacer(Modifier.width(8.dp))
                // His bubble tucks away as he jumps and grows back out of him once he's landed.
                Row(
                    Modifier.weight(1f).fillMaxHeight().graphicsLayer {
                        val m = move()
                        val back = bubbleBack.value
                        alpha = if (m < 1f) (1f - m * 4f).coerceAtLeast(0f) else back
                        val sc = if (m < 1f) 1f - 0.3f * (m * 4f).coerceAtMost(1f) else 0.7f + 0.3f * back
                        scaleX = sc; scaleY = 0.85f + 0.15f * sc
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // The bubble's tail, pointing at him (drawn over the bubble's edge so the edge doesn't cross it).
                    Spacer(Modifier.zIndex(1f).size(9.dp, 14.dp).offset(x = 1.dp).drawBehind {
                        val tail = androidx.compose.ui.graphics.Path().apply {
                            moveTo(size.width, 0f); lineTo(0f, size.height / 2f); lineTo(size.width, size.height); close()
                        }
                        drawPath(tail, GremlinColors.Card)
                        val tip = Offset(0f, size.height / 2f)
                        drawLine(mood, Offset(size.width, 0f), tip, 1.dp.toPx())
                        drawLine(mood, tip, Offset(size.width, size.height), 1.dp.toPx())
                    })
                    // Fits its line (up to the panel's width); a new length grows or shrinks it smoothly to
                    // the right, so he never moves.
                    Box(
                        // The shape, fill and outline go OUTSIDE the resize, so they're drawn at the bubble's
                        // in-between size (inside it they were drawn at the final size and clipped square
                        // at the right while it grew, user 2026-10-23). No bounce: it settles on the line.
                        Modifier.weight(1f, fill = false).fillMaxHeight()
                            .clip(RoundedCornerShape(14.dp)).background(GremlinColors.Card)
                            .animatedOutline({ mood }, corner = 14.dp)
                            .animateContentSize(tween(240, easing = FastOutSlowInEasing))
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        // The old line leaves upwards, then the new one rises in (a crossfade on the spot
                        // overlapped the two mid-change, user 2026-10-23).
                        AnimatedContent(
                            line,
                            transitionSpec = {
                                ((fadeIn(tween(200, delayMillis = 120)) + slideInVertically(tween(260, delayMillis = 100, easing = FastOutSlowInEasing)) { it / 2 }) togetherWith
                                    (fadeOut(tween(120)) + slideOutVertically(tween(180, easing = FastOutSlowInEasing)) { -it / 2 }))
                                    // Only the bubble resizes (this one animating too made the edge move twice).
                                    .using(SizeTransform(clip = false) { _, _ -> snap() })
                            },
                            contentAlignment = Alignment.CenterStart,
                            label = "bootCatLine"
                        ) {
                            Text(
                                it, style = if (wide) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                                color = when (mark) { Mark.Failed -> GremlinColors.Negative; Mark.Warn, Mark.Maintenance -> GremlinColors.Caution; else -> GremlinColors.Text },
                                // Laid out at its full width and clipped by the bubble, so while the bubble grows
                                // to a longer line the text is uncovered instead of squeezed into "...".
                                maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                                modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A check Boot Cat hasn't got to yet: a faint outline with its icon and name. */
@Composable
private fun ComingRow(icon: BootIcon, name: String, modifier: Modifier, height: Dp) {
    Row(
        modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(12.dp))
            .border(1.dp, GremlinColors.Edge, RoundedCornerShape(12.dp)).padding(start = 10.dp, end = 12.dp)
            .graphicsLayer { alpha = 0.5f },
        verticalAlignment = Alignment.CenterVertically
    ) {
        BootIconView(icon, { GremlinColors.TextMuted }, { GremlinColors.TextMuted }, Modifier.size(19.dp))
        Spacer(Modifier.width(10.dp))
        Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = GremlinColors.TextMuted, maxLines = 1)
    }
}

/** Boot Cat's jump to the panel: when it starts after the logo begins gliding, how long, how high. */
private const val BOOT_CAT_JUMP_DELAY_MS = 60L
private const val BOOT_CAT_JUMP_MS = 900
private val BOOT_CAT_JUMP_ARC = 26.dp

/** When the Ready sweep reaches the version chip at the strip's end (it pops then). */
private const val SWEEP_TO_CHIP_MS = 480L

/** Boot Cat's row (him + his bubble). */
private val BOOT_CAT_ROW_HEIGHT = 46.dp

/** How long Boot Cat's hello stays before the logo and he move (two lines, half each). */
internal const val BOOT_CAT_HELLO_MS = 2_600L
/** How long a check runs before Boot Cat says it's taking a while. */
private const val BOOT_CAT_SLOW_MS = 5_000L
/** Shortest time one of Boot Cat's lines stays up. */
private const val BOOT_CAT_LINE_MS = 650L

/** What Boot Cat says when a check failed (boot is held on Retry: nothing that fails lets boot through). */
private fun welcomeFailure(checks: List<BootCheck>, status: BootStatus): String {
    fun state(label: String) = checks.firstOrNull { it.label == label }?.state
    return when {
        status.internet == InternetLink.None -> "I can't find any internet, check your Wi-Fi"
        state("Device session") == BootState.Failed -> "This device won't let me in, trying again"
        state("Account standing") == BootState.Failed -> "I couldn't check your account, trying again"
        state("Tutorial") == BootState.Failed -> "The tutorial and terms got stuck, trying again"
        else -> "VRC-A servers aren't answering, trying again"
    }
}

/** Boot Cat's verdict once everything passed: the first thing worth knowing, else all set. */
private fun welcomeVerdict(checks: List<BootCheck>, status: BootStatus): Pair<Mark, String> {
    val account = checks.firstOrNull { it.label == "Account standing" }?.state
    return when {
        account == BootState.Warn -> Mark.Warn to "Your account has been restricted"
        status.internet == InternetLink.NoAccess -> Mark.Warn to "This Wi-Fi has no internet behind it"
        status.servers == ServiceState.Maintenance -> Mark.Maintenance to "VRC-A servers are having a nap (maintenance)"
        status.servers != ServiceState.Online -> Mark.Warn to "VRC-A servers are a bit wobbly right now"
        status.vrchat == ServiceState.Maintenance -> Mark.Maintenance to "VRChat is under maintenance"
        status.vrchat != ServiceState.Online -> Mark.Warn to "VRChat is having a rough day"
        else -> Mark.Done to "All set! Welcome to VRC-A"
    }
}
