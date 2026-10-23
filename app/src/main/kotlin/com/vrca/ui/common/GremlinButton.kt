package com.vrca.ui.common

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vrca.app.BootCat
import com.vrca.app.Mark
import com.vrca.ui.theme.GremlinColors

/** What a button does: the main action, a side action, a stop / destructive one, or a quiet link. */
enum class GremlinButtonKind { Primary, Secondary, Danger, Quiet }

/** The two looks on the approval sheet (revamp 2). One is kept once the user picks. */
enum class GremlinButtonStyle {
    /** Built like the boot panels: a solid face with a light top lip on a flat drop layer; it sinks
     *  into the drop when pressed. */
    Layered,
    /** A dark face with a coloured outline and coloured label; pressing fills it with its colour. */
    Outline
}

/** Button heights: big actions (Start sending), normal, and small inline ones. */
enum class GremlinButtonSize(val height: Dp, val padX: Dp) { Large(52.dp, 22.dp), Medium(44.dp, 18.dp), Small(34.dp, 14.dp) }

/**
 * VRC-A's own button (revamp 2). [loading] swaps the icon for Boot Cat thinking and ignores taps.
 * Every animation (press, colours) is read while drawing, so pressing only repaints the button.
 * [pressedPreview] forces the pressed look (UI Lab sheets only).
 */
@Composable
fun GremlinButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: GremlinButtonKind = GremlinButtonKind.Primary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    size: GremlinButtonSize = GremlinButtonSize.Medium,
    style: GremlinButtonStyle = GremlinButtonStyle.Layered,
    pressedPreview: Boolean = false
) {
    val accent = MaterialTheme.colorScheme.primary
    val tone = when (kind) {
        GremlinButtonKind.Primary -> accent
        GremlinButtonKind.Danger -> GremlinColors.Negative
        GremlinButtonKind.Secondary, GremlinButtonKind.Quiet -> GremlinColors.TextMuted
    }
    val source = remember { MutableInteractionSource() }
    val pressedNow by source.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressedNow || pressedPreview) 1f else 0f, tween(90), label = "press")
    val dim by animateFloatAsState(if (enabled) 1f else 0.4f, tween(200), label = "dim")
    val toneA by animateColorAsState(tone, tween(250), label = "tone")
    val layered = style == GremlinButtonStyle.Layered
    val filled = layered && (kind == GremlinButtonKind.Primary || kind == GremlinButtonKind.Danger)
    val quiet = kind == GremlinButtonKind.Quiet
    val drop = if (layered && !quiet) 4.dp else 0.dp
    val labelColor = when {
        filled -> GremlinColors.Backdrop
        kind == GremlinButtonKind.Secondary -> GremlinColors.Text
        else -> toneA
    }
    Box(
        modifier
            .defaultMinSize(minHeight = size.height + drop)
            .graphicsLayer { alpha = dim }
            .clickable(source, indication = null, enabled = enabled && !loading, role = Role.Button, onClick = onClick)
            .drawBehind {
                if (quiet) {
                    // Just a soft tint while pressed.
                    if (press > 0f) drawRoundRect(toneA.copy(alpha = 0.14f * press), cornerRadius = CornerRadius(12.dp.toPx()))
                    return@drawBehind
                }
                val d = drop.toPx()
                val sink = d * press
                val faceH = this.size.height - d
                val r = CornerRadius(14.dp.toPx())
                if (layered) {
                    // The drop layer: a flat darker copy under the face; the face sinks onto it when pressed.
                    if (d > 0f) drawRoundRect(Color.Black.copy(alpha = 0.38f), topLeft = Offset(0f, d), size = Size(this.size.width, faceH), cornerRadius = r)
                    val face = when {
                        filled -> lerp(toneA, Color.Black, 0.12f * press)
                        else -> lerp(GremlinColors.Raised, GremlinColors.Card, press)
                    }
                    drawRoundRect(face, topLeft = Offset(0f, sink), size = Size(this.size.width, faceH), cornerRadius = r)
                    drawRoundRect(
                        if (filled) Color.Black.copy(alpha = 0.18f) else GremlinColors.Edge,
                        topLeft = Offset(0f, sink), size = Size(this.size.width, faceH), cornerRadius = r, style = Stroke(1.dp.toPx())
                    )
                    // The light top lip (the edge facing the light), gone while pressed in.
                    drawLine(
                        Color.White.copy(alpha = (if (filled) 0.28f else 0.08f) * (1f - press)),
                        Offset(r.x, sink + 1.dp.toPx()), Offset(this.size.width - r.x, sink + 1.dp.toPx()), 1.dp.toPx()
                    )
                } else {
                    // Outline look: dark face, coloured edge; pressing fills it with its colour.
                    val face = lerp(GremlinColors.Card, toneA.copy(alpha = 1f), 0.22f * press)
                    drawRoundRect(face, cornerRadius = r)
                    val edgeColor = if (kind == GremlinButtonKind.Secondary) GremlinColors.Edge else toneA
                    drawRoundRect(edgeColor.copy(alpha = 0.85f + 0.15f * press), cornerRadius = r, style = Stroke((1.5f + press).dp.toPx()))
                }
            },
        contentAlignment = Alignment.TopCenter
    ) {
        Row(
            Modifier.graphicsLayer { translationY = drop.toPx() * press }
                .height(size.height).padding(horizontal = if (quiet) 10.dp else size.padX),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            val iconSize = if (size == GremlinButtonSize.Small) 16.dp else 20.dp
            Crossfade(loading, label = "busy") { busy ->
                when {
                    // Boot Cat thinking; on a filled face he sits on a dark dot so he still shows.
                    busy -> Row {
                        Box(
                            Modifier.size(iconSize + 6.dp).then(
                                if (filled) Modifier.drawBehind { drawCircle(GremlinColors.Backdrop.copy(alpha = 0.85f)) } else Modifier
                            ),
                            contentAlignment = Alignment.Center
                        ) { BootCat(Mark.Working, 0, Modifier.size(iconSize + 2.dp)) }
                        Spacer(Modifier.width(8.dp))
                    }
                    icon != null -> Row { Icon(icon, null, tint = labelColor, modifier = Modifier.size(iconSize)); Spacer(Modifier.width(8.dp)) }
                }
            }
            Text(
                text, color = labelColor, maxLines = 1, fontWeight = FontWeight.SemiBold,
                style = if (size == GremlinButtonSize.Small) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleSmall
            )
        }
    }
}
