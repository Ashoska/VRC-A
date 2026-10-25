package com.vrca.app

import android.os.Build
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vrca.ui.theme.GremlinColors

/**
 * Shown on the open after a crash, during the safe start (CrashReport): where it crashed and what went
 * wrong in plain words, a few plain facts (no code), and Start VRC-A. The full report goes to the
 * developer by itself (CrashUpload). Boot Cat's failed face sets the tone. [history] = the crashes of the past 3 days, newest (this one) first.
 */
@Composable
internal fun CrashScreen(report: String, history: List<CrashReport.Entry>, onStart: () -> Unit, onDone: () -> Unit = {}) {
    val ctx = LocalContext.current
    val where = remember(report) { CrashReport.whereOf(report) }
    val what = remember(report) { CrashReport.whatOf(report) }
    val time = remember(report) { niceTime(CrashReport.time(report)) }
    val version = remember(report) { CrashReport.versionOf(report) }
    // The other crashes of the past 3 days (the history includes this one).
    val earlier = remember(history) { history.drop(1) }
    // The Details rows, worked out once here (not while drawing).
    val details = remember(report, earlier) {
        listOf(
            "Build" to CrashReport.field(report, "VersionCode"),
            "Device" to listOf(CrashReport.field(report, "Device"), CrashReport.field(report, "SDK").let { if (it.isBlank()) "" else "Android SDK $it" })
                .filter { it.isNotBlank() }.joinToString("  ·  "),
            "Earlier" to earlier.joinToString("\n") { "${niceTime(CrashReport.time(it.text))}  ·  ${CrashReport.whereOf(it.text)}" },
        ).filter { it.second.isNotBlank() }.map { it.first to sanitizeBackendRefs(it.second) }
    }
    // Start VRC-A (user idea, 2026-10-24: the button takes the load): the button shows the thinking Boot
    // Cat, the held start-up work runs while it's up ([onStart]; the cat's loop runs on the RenderThread, so
    // it keeps moving while the main thread is busy), then the screen fades out to the bare backdrop with
    // nothing heavy left ([onDone]) and the boot screen's intro fades its logo in on the same backdrop.
    // Before, the work ran after the fade, on a blank screen.
    var leaving by remember { mutableStateOf(false) }
    val fade = remember { androidx.compose.animation.core.Animatable(1f) }
    androidx.compose.runtime.LaunchedEffect(leaving) {
        if (!leaving) return@LaunchedEffect
        // Let the icon-to-cat swap (GremlinButton's 300 ms crossfade) finish first: a blocked main thread would
        // freeze it halfway, while the cat's own loop keeps running on the RenderThread.
        kotlinx.coroutines.delay(START_SWAP_MS)
        onStart()
        fade.animateTo(0f, tween(LEAVE_MS, easing = FastOutSlowInEasing))
        onDone()
    }
    Box(Modifier.fillMaxSize().background(GremlinColors.Backdrop), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth()
                .graphicsLayer { alpha = fade.value; val k = 0.98f + 0.02f * fade.value; scaleX = k; scaleY = k }
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BootCat(Mark.Failed, 0, Modifier.size(52.dp))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("VRC-A crashed last time", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = GremlinColors.Text)
                    Text(
                        listOf(version, time).filter { it.isNotBlank() }.joinToString("  ·  "),
                        style = MaterialTheme.typography.bodyMedium, color = GremlinColors.TextMuted
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Layer(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
                    // Start VRC-A beside the title block (user pick 2026-10-24) when the card is wide enough; on a
                    // narrow phone that squeezed the text into short lines, so there it goes full width at the bottom.
                    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val wide = maxWidth >= WIDE_CARD
                        Column {
                            Row(verticalAlignment = Alignment.Top) {
                                Column(Modifier.weight(1f).padding(end = if (wide) 14.dp else 0.dp)) {
                                    // Where it crashed and what went wrong, in plain words (never code names).
                                    Text(
                                        "Crashed in $where", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
                                        color = GremlinColors.Negative, maxLines = 2, overflow = TextOverflow.Ellipsis
                                    )
                                    Text(what, style = MaterialTheme.typography.bodyMedium, color = GremlinColors.Text, modifier = Modifier.padding(top = 2.dp))
                                    // Only when it isn't a one-off.
                                    if (earlier.isNotEmpty()) Text(
                                        "Crashed ${earlier.size + 1} times in the last 3 days", style = MaterialTheme.typography.bodySmall,
                                        color = GremlinColors.Caution, modifier = Modifier.padding(top = 6.dp)
                                    )
                                }
                                if (wide) StartButton(Modifier, leaving) { leaving = true }
                            }
                            // Details, always shown (user: no expand/collapse). Plain facts only (user: don't expose
                            // our code): the full report, stack trace included, only goes to the developer (CrashUpload).
                            if (details.isNotEmpty()) Column(
                                Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                    .background(GremlinColors.Card).padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // Only what isn't already shown above (where, what, version and time are).
                                details.forEach { (label, value) -> DetailRow(label, value) }
                            }
                            if (!wide) StartButton(Modifier.fillMaxWidth().padding(top = 14.dp), leaving) { leaving = true }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StartButton(modifier: Modifier, starting: Boolean, onClick: () -> Unit) = com.vrca.ui.common.GremlinButton(
    // Same label while starting, so the button keeps its width; the cat shows it's working.
    "Start VRC-A", { if (!starting) onClick() }, modifier,
    bootIcon = BootIcons.Start, loading = starting, size = com.vrca.ui.common.GremlinButtonSize.Large
)

/** From this card width the button sits beside the title (narrower: full width at the bottom). */
private val WIDE_CARD = 440.dp

/** How long Start VRC-A shows the loading cat before the held start-up work runs (the icon swap). */
private const val START_SWAP_MS = 340L

/** How long the crash screen takes to fade out after Start VRC-A. */
private const val LEAVE_MS = 260

@Composable
private fun DetailRow(label: String, value: String) {
    if (value.isBlank()) return
    Row {
        Text(label, style = MaterialTheme.typography.bodySmall, color = GremlinColors.TextMuted, modifier = Modifier.width(84.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = GremlinColors.Text)
    }
}

/** "2026-10-23 22:59:04" → "23 Oct 22:59" (the report's own text if it doesn't parse). */
private fun niceTime(t: String): String = runCatching {
    val d = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).parse(t)!!
    // English like the rest of the app (the device locale showed "24 10月" on a phone set to Chinese).
    java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.US).format(d)
}.getOrDefault(t)
