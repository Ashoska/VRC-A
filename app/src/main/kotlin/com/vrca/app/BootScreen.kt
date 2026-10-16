package com.vrca.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    val blocking: Boolean = false
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

/**
 * The boot screen (Gremlin layers, no gradient): the logo on top, then two panels side by side:
 * what's loading (left) and status / what's new (right). On a narrow phone the panels stack.
 * Both panels keep a fixed height so nothing jumps when a row changes or an error shows.
 */
@Composable
fun BootScreen(
    checks: List<BootCheck>,
    status: BootStatus,
    error: String? = null,
    retryInSec: Int? = null,
    onRetry: () -> Unit = {},
    onContinue: (() -> Unit)? = null
) {
    val working = checks.any { it.state == BootState.Working }
    Surface(color = GremlinColors.Backdrop) {
        BoxWithConstraints(Modifier.fillMaxSize().background(GremlinColors.Backdrop)) {
            val wide = maxWidth >= 720.dp
            Column(
                Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                BootLogo(working)
                Spacer(Modifier.height(6.dp))
                Text(
                    "VRChat Assistant",
                    style = MaterialTheme.typography.titleMedium,
                    color = GremlinColors.TextMuted
                )
                Spacer(Modifier.height(24.dp))
                if (wide) {
                    Row(
                        Modifier.widthIn(max = 880.dp).fillMaxWidth().height(300.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        LoadingPanel(checks, error, retryInSec, onRetry, onContinue, Modifier.weight(1.15f))
                        StatusPanel(status, Modifier.weight(1f))
                    }
                } else {
                    Column(
                        Modifier.widthIn(max = 460.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        LoadingPanel(checks, error, retryInSec, onRetry, onContinue, Modifier.fillMaxWidth())
                        StatusPanel(status, Modifier.fillMaxWidth())
                    }
                }
            }
            Text(
                "v" + BuildConfig.VERSION_NAME.removePrefix("v"),
                style = MaterialTheme.typography.labelSmall,
                color = GremlinColors.TextMuted.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
            )
        }
    }
}

@Composable
private fun BootLogo(working: Boolean) {
    // A slow breath while anything is loading; still once it's all settled.
    val pulse = rememberInfiniteTransition(label = "bootPulse")
    val breath by pulse.animateFloat(
        initialValue = 1f, targetValue = 0.72f,
        animationSpec = infiniteRepeatable(tween(1300), RepeatMode.Reverse), label = "bootBreath"
    )
    Image(
        // vrca_logo_bar: the logo cropped to its drawing (the full asset is ~35% empty margin).
        bitmap = ImageBitmap.imageResource(com.vrca.R.drawable.vrca_logo_bar),
        contentDescription = "VRC-A",
        filterQuality = FilterQuality.High,
        modifier = Modifier.height(92.dp).alpha(if (working) breath else 1f)
    )
}

/** A layer-1 panel: flat Panel tone, Edge outline, no shadow or gradient. */
@Composable
private fun BootPanel(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = GremlinColors.Panel,
        border = BorderStroke(1.dp, GremlinColors.Edge)
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), content = content)
    }
}

@Composable
private fun PanelHeader(title: String, trailing: String = "") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = GremlinColors.TextMuted,
            modifier = Modifier.weight(1f)
        )
        if (trailing.isNotBlank()) {
            Text(trailing, style = MaterialTheme.typography.labelMedium, color = GremlinColors.TextMuted)
        }
    }
}

@Composable
private fun LoadingPanel(
    checks: List<BootCheck>,
    error: String?,
    retryInSec: Int?,
    onRetry: () -> Unit,
    onContinue: (() -> Unit)?,
    modifier: Modifier
) {
    // Settled = nothing left for boot to wait on (a failed row still needs a retry).
    val finished = checks.count { it.state in setOf(BootState.Done, BootState.Warn, BootState.Skipped, BootState.Background) }
    BootPanel(modifier) {
        PanelHeader("Starting up", "$finished of ${checks.size}")
        Spacer(Modifier.height(8.dp))
        val progress by animateFloatAsState(
            if (checks.isEmpty()) 1f else finished / checks.size.toFloat(), tween(300), label = "bootProgress"
        )
        LinearProgressIndicator(
            progress = progress,
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = if (error != null) GremlinColors.Negative else MaterialTheme.colorScheme.primary,
            trackColor = GremlinColors.Raised
        )
        Spacer(Modifier.height(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            checks.forEach { BootCheckLine(it) }
        }
        if (error != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onRetry) {
                    Text(if (retryInSec != null) "Retry ($retryInSec)" else "Retry")
                }
                if (onContinue != null) {
                    OutlinedButton(onClick = onContinue) { Text("Continue anyway") }
                }
            }
        }
    }
}

@Composable
private fun BootCheckLine(check: BootCheck) {
    val dim = check.state == BootState.Waiting
    val labelColor by animateColorAsState(
        if (dim) GremlinColors.TextMuted.copy(alpha = 0.55f) else GremlinColors.Text, label = "bootLabel"
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        BootStateMark(check.state)
        Spacer(Modifier.width(12.dp))
        Text(check.label, style = MaterialTheme.typography.bodyLarge, color = labelColor, maxLines = 1)
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
                modifier = Modifier.widthIn(max = 220.dp)
            )
        }
    }
}

@Composable
private fun BootStateMark(state: BootState) {
    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
        when (state) {
            BootState.Working, BootState.Background -> CircularProgressIndicator(
                modifier = Modifier.size(17.dp),
                strokeWidth = 2.dp,
                color = if (state == BootState.Background) GremlinColors.TextMuted else MaterialTheme.colorScheme.primary
            )
            BootState.Waiting -> Box(Modifier.size(8.dp).background(GremlinColors.Raised, CircleShape))
            else -> {
                val (fill, icon) = when (state) {
                    BootState.Done -> GremlinColors.Positive to Icons.Filled.Check
                    BootState.Warn -> GremlinColors.Caution to Icons.Filled.PriorityHigh
                    BootState.Failed -> GremlinColors.Negative to Icons.Filled.Close
                    else -> GremlinColors.Raised to Icons.Filled.Remove
                }
                Box(Modifier.size(20.dp).background(fill, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(
                        icon, contentDescription = null, modifier = Modifier.size(14.dp),
                        tint = if (state == BootState.Skipped) GremlinColors.TextMuted else Color.Black
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusPanel(status: BootStatus, modifier: Modifier) {
    BootPanel(modifier) {
        PanelHeader("Status")
        Spacer(Modifier.height(12.dp))
        // VRChat platform status, as a small layer-2 card.
        Surface(shape = RoundedCornerShape(14.dp), color = GremlinColors.Card, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                val dot = when (status.vrchatOk) {
                    true -> GremlinColors.Positive
                    false -> GremlinColors.Caution
                    null -> GremlinColors.Inactive
                }
                Box(Modifier.size(10.dp).background(dot, CircleShape))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("VRChat", style = MaterialTheme.typography.titleSmall, color = GremlinColors.Text)
                    Text(
                        when (status.vrchatOk) {
                            true -> "All systems operational"
                            false -> status.vrchatDetail.ifBlank { "Having issues" }
                            null -> "Checking..."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status.vrchatOk == false) GremlinColors.Caution else GremlinColors.TextMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        if (status.updatedTo != null) {
            Text(
                "Updated to v${status.updatedTo.removePrefix("v")}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                status.updateNotes.take(4).forEach { note ->
                    Row {
                        Text("•", color = GremlinColors.TextMuted, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            note, style = MaterialTheme.typography.bodyMedium, color = GremlinColors.Text,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        // Hidden until it's a number worth showing, so a fresh install never sees "0".
        if (status.lifetimeSends >= 100) {
            Text(
                "${String.format(java.util.Locale.US, "%,d", status.lifetimeSends)} chatbox updates sent",
                style = MaterialTheme.typography.labelMedium,
                color = GremlinColors.TextMuted
            )
        }
    }
}
