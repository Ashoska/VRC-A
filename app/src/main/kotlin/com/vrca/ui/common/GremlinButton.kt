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
import androidx.compose.foundation.layout.fillMaxWidth
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

/** The looks on the approval sheet (revamp 2). One is kept once the user picks. */
enum class GremlinButtonStyle(val label: String) {
    /** Built like the boot panels: a solid face with a light top lip on a flat drop layer; it sinks
     *  into the drop when pressed. */
    Layered("A  Layered"),
    /** A dark face with a coloured outline and coloured label; pressing fills it with its colour. */
    Outline("B  Outline"),
    /** A soft wash of its colour, no edge; pressing deepens the wash. */
    Tinted("C  Tinted"),
    /** B with the logo's cat ears on its top edge; they perk up when pressed. */
    Ears("D  Cat ears"),
    /** A dark face with a bar of its colour along the bottom that grows when pressed. */
    Underline("E  Underline"),
    /** B with angled corners (top-left, bottom-right), a techier edge. */
    Notched("F  Notched"),
    /** The icon in a block of its colour on the left, the label on a dark face. */
    IconBlock("G  Icon block")
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
    val secondary = kind == GremlinButtonKind.Secondary
    val drop = if (layered && !quiet) 4.dp else 0.dp
    // Room above the face for the cat ears.
    val top = if (style == GremlinButtonStyle.Ears && !quiet) 10.dp else 0.dp
    val block = style == GremlinButtonStyle.IconBlock && !quiet && (icon != null || loading)
    val labelColor = when {
        filled -> GremlinColors.Backdrop
        secondary -> GremlinColors.Text
        style == GremlinButtonStyle.IconBlock && !quiet -> GremlinColors.Text
        else -> toneA
    }
    Box(
        modifier
            .defaultMinSize(minHeight = size.height + drop + top)
            .graphicsLayer { alpha = dim }
            .clickable(source, indication = null, enabled = enabled && !loading, role = Role.Button, onClick = onClick)
            .drawBehind {
                if (quiet) {
                    // Just a soft tint while pressed.
                    if (press > 0f) drawRoundRect(toneA.copy(alpha = 0.14f * press), cornerRadius = CornerRadius(12.dp.toPx()))
                    return@drawBehind
                }
                val w = this.size.width
                val t = top.toPx()
                val d = drop.toPx()
                val sink = d * press
                val faceH = this.size.height - d - t
                val rr = 14.dp.toPx()
                val r = CornerRadius(rr)
                val stroke = 1.5.dp.toPx()
                val edge = if (secondary) GremlinColors.Edge else toneA
                when (style) {
                    GremlinButtonStyle.Layered -> {
                        // The drop layer: a flat darker copy under the face; the face sinks onto it when pressed.
                        if (d > 0f) drawRoundRect(Color.Black.copy(alpha = 0.38f), topLeft = Offset(0f, d), size = Size(w, faceH), cornerRadius = r)
                        val face = if (filled) lerp(toneA, Color.Black, 0.12f * press) else lerp(GremlinColors.Raised, GremlinColors.Card, press)
                        drawRoundRect(face, topLeft = Offset(0f, sink), size = Size(w, faceH), cornerRadius = r)
                        drawRoundRect(
                            if (filled) Color.Black.copy(alpha = 0.18f) else GremlinColors.Edge,
                            topLeft = Offset(0f, sink), size = Size(w, faceH), cornerRadius = r, style = Stroke(1.dp.toPx())
                        )
                        // The light top lip (the edge facing the light), gone while pressed in.
                        drawLine(
                            Color.White.copy(alpha = (if (filled) 0.28f else 0.08f) * (1f - press)),
                            Offset(rr, sink + 1.dp.toPx()), Offset(w - rr, sink + 1.dp.toPx()), 1.dp.toPx()
                        )
                    }
                    GremlinButtonStyle.Outline -> {
                        drawRoundRect(lerp(GremlinColors.Card, toneA, 0.22f * press), cornerRadius = r)
                        drawRoundRect(edge.copy(alpha = 0.85f + 0.15f * press), cornerRadius = r, style = Stroke((1.5f + press).dp.toPx()))
                    }
                    GremlinButtonStyle.Tinted -> {
                        val base = if (secondary) GremlinColors.Raised else toneA.copy(alpha = 0.16f + 0.14f * press)
                        drawRoundRect(if (secondary) lerp(base, GremlinColors.Card, press) else base, cornerRadius = r)
                    }
                    GremlinButtonStyle.Ears -> {
                        val face = lerp(GremlinColors.Card, toneA, 0.22f * press)
                        val earW = 17.dp.toPx(); val earH = (8f + 3f * press).dp.toPx()
                        val ears = listOf(rr + 4.dp.toPx(), w - rr - 4.dp.toPx() - earW)
                        // The ears: a filled triangle over the top edge, its two sides outlined.
                        for (x in ears) {
                            val p = androidx.compose.ui.graphics.Path().apply {
                                moveTo(x, t + stroke); lineTo(x + earW * 0.35f, t - earH + t * 0f); lineTo(x + earW, t + stroke); close()
                            }
                            drawPath(p, face)
                            val o = androidx.compose.ui.graphics.Path().apply { moveTo(x, t); lineTo(x + earW * 0.35f, t - earH); lineTo(x + earW, t) }
                            drawPath(o, edge, style = Stroke(stroke, join = androidx.compose.ui.graphics.StrokeJoin.Round, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                        }
                        drawRoundRect(face, topLeft = Offset(0f, t), size = Size(w, faceH), cornerRadius = r)
                        drawRoundRect(edge, topLeft = Offset(0f, t), size = Size(w, faceH), cornerRadius = r, style = Stroke(stroke))
                        // Re-fill the ear bases so the face's top edge doesn't cut across them.
                        for (x in ears) drawLine(face, Offset(x + stroke, t), Offset(x + earW - stroke, t), stroke * 1.4f)
                    }
                    GremlinButtonStyle.Underline -> {
                        drawRoundRect(lerp(GremlinColors.Card, toneA, 0.14f * press), cornerRadius = r)
                        drawRoundRect(GremlinColors.Edge, cornerRadius = r, style = Stroke(1.dp.toPx()))
                        val bar = (3f + 2.5f * press).dp.toPx()
                        val inset = rr * 0.6f
                        drawRoundRect(edge, topLeft = Offset(inset, faceH - bar - 3.dp.toPx()), size = Size(w - inset * 2, bar), cornerRadius = CornerRadius(bar / 2))
                    }
                    GremlinButtonStyle.Notched -> {
                        val c = 10.dp.toPx()
                        val p = androidx.compose.ui.graphics.Path().apply {
                            moveTo(c, 0f); lineTo(w, 0f); lineTo(w, faceH - c); lineTo(w - c, faceH); lineTo(0f, faceH); lineTo(0f, c); close()
                        }
                        drawPath(p, lerp(GremlinColors.Card, toneA, 0.22f * press))
                        drawPath(p, edge.copy(alpha = 0.85f + 0.15f * press), style = Stroke((1.5f + press).dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round))
                    }
                    GremlinButtonStyle.IconBlock -> {
                        drawRoundRect(lerp(GremlinColors.Card, GremlinColors.Raised, press), cornerRadius = r)
                        drawRoundRect(GremlinColors.Edge, cornerRadius = r, style = Stroke(1.dp.toPx()))
                        if (block) {
                            // The icon's block, rounded on the left like the button.
                            val bw = faceH
                            val bp = androidx.compose.ui.graphics.Path().apply {
                                addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, bw, faceH, r, CornerRadius.Zero, CornerRadius.Zero, r))
                            }
                            drawPath(bp, if (secondary) GremlinColors.Raised else lerp(toneA, Color.Black, 0.15f * press))
                        }
                    }
                }
            },
        contentAlignment = Alignment.TopCenter
    ) {
        Row(
            Modifier.graphicsLayer { translationY = drop.toPx() * press }
                .padding(top = top).height(size.height)
                // The icon block sits at the button's left edge, so that row spans the button.
                .then(if (block) Modifier.fillMaxWidth() else Modifier)
                .padding(start = if (block) 0.dp else if (quiet) 10.dp else size.padX, end = if (quiet) 10.dp else size.padX),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (block) Arrangement.Start else Arrangement.Center
        ) {
            val iconSize = if (size == GremlinButtonSize.Small) 16.dp else 20.dp
            Crossfade(loading, label = "busy") { busy ->
                when {
                    // Boot Cat thinking; on a filled face he sits on a dark dot so he still shows.
                    busy -> Row {
                        Box(
                            Modifier.size(if (block) size.height else iconSize + 6.dp).then(
                                if (filled || block) Modifier.drawBehind { drawCircle(GremlinColors.Backdrop.copy(alpha = 0.85f)) } else Modifier
                            ),
                            contentAlignment = Alignment.Center
                        ) { BootCat(Mark.Working, 0, Modifier.size(iconSize + 2.dp)) }
                        Spacer(Modifier.width(8.dp))
                    }
                    block -> Row {
                        Box(Modifier.size(size.height), contentAlignment = Alignment.Center) {
                            Icon(icon!!, null, tint = if (secondary) GremlinColors.Text else GremlinColors.Backdrop, modifier = Modifier.size(iconSize))
                        }
                        Spacer(Modifier.width(12.dp))
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
