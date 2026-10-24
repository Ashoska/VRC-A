package com.vrca.app

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
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
 * wrong in plain words, a few plain facts (no code), and two buttons (user, 2026-10-23): Start VRC-A,
 * and Download report (a .txt in Downloads with the full report, for sending to the developer). Boot
 * Cat's failed face sets the tone. [history] = the crashes of the past 3 days, newest (this one) first.
 */
@Composable
internal fun CrashScreen(report: String, history: List<CrashReport.Entry>, onStart: () -> Unit) {
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
    // What Download report saves: this crash in full, then the earlier ones.
    val file = remember(report, history) { CrashReport.fullReport(report, history) }
    // Start VRC-A: the screen fades out to the bare backdrop first, then the app starts (the held
    // start-up work runs there, unseen) and the boot screen's intro fades its logo in on the same backdrop.
    var leaving by remember { mutableStateOf(false) }
    val fade = remember { androidx.compose.animation.core.Animatable(1f) }
    androidx.compose.runtime.LaunchedEffect(leaving) {
        if (!leaving) return@LaunchedEffect
        fade.animateTo(0f, tween(LEAVE_MS, easing = FastOutSlowInEasing))
        onStart()
    }
    // "Saved to Downloads: <name>" / "Couldn't save the report" once Download report is pressed.
    var saved by remember { mutableStateOf<String?>(null) }
    // Android 9 and older: the system picker chooses where the .txt goes.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) saved = runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(file.toByteArray()) }
            "Report saved"
        }.getOrDefault("Couldn't save the report")
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
                    // Details, always shown (user: no expand/collapse). Plain facts only (user: don't
                    // expose our code): the full report, stack trace included, is only in the downloaded file.
                    if (details.isNotEmpty()) Column(
                        Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(GremlinColors.Card).padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Only what isn't already shown above (where, what, version and time are).
                        details.forEach { (label, value) -> DetailRow(label, value) }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            // Side by side when both labels fit at half width; stacked otherwise (a phone with a large,
            // bold font showed "Download" with "report" cut off).
            val measurer = androidx.compose.ui.text.rememberTextMeasurer()
            val style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                val density = androidx.compose.ui.platform.LocalDensity.current
                val widest = remember(style, density) {
                    listOf("Download report", "Start VRC-A").maxOf { measurer.measure(it, style).size.width }
                }
                // Label + icon (22dp) + gap (8dp) + side padding (2 x 22dp), against half the row.
                val fits = with(density) { widest.toDp() + 22.dp + 8.dp + 44.dp } <= (maxWidth - 12.dp) / 2
                @Composable fun Buttons(mod: Modifier) {
                    com.vrca.ui.common.GremlinButton(
                        "Download report", kind = com.vrca.ui.common.GremlinButtonKind.Secondary,
                        bootIcon = BootIcons.Updates,
                        size = com.vrca.ui.common.GremlinButtonSize.Large,
                        onClick = {
                            if (leaving) return@GremlinButton
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                val name = CrashReport.saveToDownloads(ctx, file)
                                saved = if (name != null) "Saved to Downloads: $name" else "Couldn't save the report"
                            } else picker.launch(CrashReport.fileName())
                        },
                        modifier = mod
                    )
                    com.vrca.ui.common.GremlinButton(
                        "Start VRC-A", { leaving = true }, mod,
                        bootIcon = BootIcons.Start, size = com.vrca.ui.common.GremlinButtonSize.Large
                    )
                }
                if (fits) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Buttons(Modifier.weight(1f)) }
                else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { Buttons(Modifier.fillMaxWidth()) }
            }
            AnimatedVisibility(saved != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Text(
                    saved.orEmpty(), style = MaterialTheme.typography.bodySmall,
                    color = if (saved?.startsWith("Couldn't") == true) GremlinColors.Negative else GremlinColors.TextMuted,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
        }
    }
}

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
