package com.vrca.app

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vrca.ui.theme.GremlinColors

/**
 * Shown on the open after a crash, during the safe start (CrashReport): what crashed, the full
 * report folded away, and two buttons (user, 2026-10-23): Start VRC-A, and Download report (a .txt
 * in Downloads, for sending to the developer). Boot Cat's failed face sets the tone.
 */
@Composable
internal fun CrashScreen(report: String, onStart: () -> Unit) {
    val ctx = LocalContext.current
    val summary = remember(report) { CrashReport.summary(report) }
    val time = remember(report) { CrashReport.time(report).substringBeforeLast(':') }
    var open by remember { mutableStateOf(false) }
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
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(report.toByteArray()) }
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
                    if (time.isNotBlank()) Text(time, style = MaterialTheme.typography.bodyMedium, color = GremlinColors.TextMuted)
                }
            }
            Spacer(Modifier.height(18.dp))
            Layer(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp).animateContentSize(tween(260, easing = FastOutSlowInEasing))) {
                    // What failed and where, in one line.
                    Text(
                        sanitizeBackendRefs(summary), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
                        color = GremlinColors.Negative, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(12.dp))
                    // The full report, folded away.
                    val turn by animateFloatAsState(if (open) 180f else 0f, tween(220), label = "detailsArrow")
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { open = !open }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Details", style = MaterialTheme.typography.labelLarge, color = GremlinColors.TextMuted, modifier = Modifier.weight(1f))
                        Icon(Icons.Filled.KeyboardArrowDown, null, tint = GremlinColors.TextMuted, modifier = Modifier.graphicsLayer { rotationZ = turn })
                    }
                    AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        Box(
                            Modifier.padding(top = 8.dp).fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp))
                                .background(GremlinColors.Card).verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState()).padding(12.dp)
                        ) {
                            // Sanitized on screen only; the downloaded report keeps the raw text.
                            Text(
                                sanitizeBackendRefs(report).ifBlank { "(no report text)" }, fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall, color = GremlinColors.TextMuted, softWrap = false
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                com.vrca.ui.common.GremlinButton(
                    "Download report", kind = com.vrca.ui.common.GremlinButtonKind.Secondary,
                    bootIcon = BootIcons.Updates,
                    size = com.vrca.ui.common.GremlinButtonSize.Large,
                    onClick = {
                        if (leaving) return@GremlinButton
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val name = CrashReport.saveToDownloads(ctx, report)
                            saved = if (name != null) "Saved to Downloads: $name" else "Couldn't save the report"
                        } else picker.launch(CrashReport.fileName())
                    },
                    modifier = Modifier.weight(1f)
                )
                com.vrca.ui.common.GremlinButton(
                    "Start VRC-A", { leaving = true }, Modifier.weight(1f),
                    icon = androidx.compose.material.icons.Icons.Filled.PlayArrow, size = com.vrca.ui.common.GremlinButtonSize.Large
                )
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
