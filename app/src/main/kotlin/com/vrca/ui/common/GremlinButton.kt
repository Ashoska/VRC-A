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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
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

/** Button heights: big actions (Start sending, Retry), normal, and small inline ones. */
enum class GremlinButtonSize(val height: Dp, val padX: Dp) { Large(52.dp, 22.dp), Medium(44.dp, 18.dp), Small(34.dp, 14.dp) }

/**
 * VRC-A's button (revamp 2; the user picked look "B" of the button sheet, 2026-10-23, over
 * layered, tinted, cat-ear, underline, notched, icon-block, speech-bubble, boot-line, bracket, echo,
 * doodle and subtler variants): a dark face with an outline and label in its colour (accent for the
 * main action, red to stop, the edge colour for side actions); pressing fills it with its colour and
 * thickens the outline. A Quiet button is just coloured text that tints when pressed.
 *
 * [loading]: Boot Cat thinking takes the icon's place and taps are ignored (pass a label like
 * "Starting..." meanwhile). [leading] replaces the icon with any small content (the Retry
 * countdown ring). Every animation (press, colours) is read while drawing, so pressing only
 * repaints the button. [pressedPreview] forces the pressed look (UI Lab sheets only).
 */
@Composable
fun GremlinButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: GremlinButtonKind = GremlinButtonKind.Primary,
    icon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    size: GremlinButtonSize = GremlinButtonSize.Medium,
    leading: (@Composable () -> Unit)? = null,
    pressedPreview: Boolean = false
) {
    GremlinButtonFrame(onClick, modifier, kind, enabled, loading, size, pressedPreview) { labelColor ->
        val iconSize = if (size == GremlinButtonSize.Small) 16.dp else 20.dp
        Crossfade(loading, label = "busy") { busy ->
            when {
                busy -> Row { BootCat(Mark.Working, 0, Modifier.size(iconSize + 4.dp)); Spacer(Modifier.width(8.dp)) }
                leading != null -> Row(verticalAlignment = Alignment.CenterVertically) { leading(); Spacer(Modifier.width(10.dp)) }
                icon != null -> Row { Icon(icon, null, tint = labelColor, modifier = Modifier.size(iconSize)); Spacer(Modifier.width(8.dp)) }
            }
        }
        Text(text, maxLines = 1)
        if (trailingIcon != null) {
            Spacer(Modifier.width(8.dp))
            Icon(trailingIcon, null, tint = labelColor, modifier = Modifier.size(iconSize - 2.dp))
        }
    }
}

/**
 * The same button with free content, laid out in a row (the stock Material Button's shape, so the
 * app's old buttons swap over by name). Text and icons inside take the button's colour unless they
 * set their own.
 */
@Composable
fun GremlinButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    kind: GremlinButtonKind = GremlinButtonKind.Primary,
    size: GremlinButtonSize = GremlinButtonSize.Medium,
    loading: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    GremlinButtonFrame(onClick, modifier, kind, enabled, loading, size, false) {
        // Loading: Boot Cat thinking in front of the content (and taps are ignored).
        if (loading) {
            BootCat(Mark.Working, 0, Modifier.size(if (size == GremlinButtonSize.Small) 20.dp else 24.dp))
            Spacer(Modifier.width(8.dp))
        }
        content()
    }
}

/** A side action (the old OutlinedButton). */
@Composable
fun GremlinSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: GremlinButtonSize = GremlinButtonSize.Medium,
    content: @Composable RowScope.() -> Unit
) = GremlinButton(onClick, modifier, enabled, GremlinButtonKind.Secondary, size, content = content)

/** A quiet text action (the old TextButton). */
@Composable
fun GremlinQuietButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: GremlinButtonSize = GremlinButtonSize.Small,
    content: @Composable RowScope.() -> Unit
) = GremlinButton(onClick, modifier, enabled, GremlinButtonKind.Quiet, size, content = content)

@Composable
private fun GremlinButtonFrame(
    onClick: () -> Unit,
    modifier: Modifier,
    kind: GremlinButtonKind,
    enabled: Boolean,
    loading: Boolean,
    size: GremlinButtonSize,
    pressedPreview: Boolean,
    content: @Composable RowScope.(labelColor: androidx.compose.ui.graphics.Color) -> Unit
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
    val quiet = kind == GremlinButtonKind.Quiet
    val secondary = kind == GremlinButtonKind.Secondary
    val labelColor = if (secondary) GremlinColors.Text else toneA
    Box(
        modifier
            .defaultMinSize(minHeight = size.height)
            .graphicsLayer { alpha = dim }
            .clickable(source, indication = null, enabled = enabled && !loading, role = Role.Button, onClick = onClick)
            .drawBehind {
                if (quiet) {
                    if (press > 0f) drawRoundRect(toneA.copy(alpha = 0.14f * press), cornerRadius = CornerRadius(12.dp.toPx()))
                    return@drawBehind
                }
                val r = CornerRadius(14.dp.toPx())
                drawRoundRect(lerp(GremlinColors.Card, toneA, 0.22f * press), cornerRadius = r)
                val edge = if (secondary) GremlinColors.Edge else toneA
                drawRoundRect(edge.copy(alpha = 0.85f + 0.15f * press), cornerRadius = r, style = Stroke((1.5f + press).dp.toPx()))
            },
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(
            LocalContentColor provides labelColor,
            LocalTextStyle provides (if (size == GremlinButtonSize.Small) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleSmall)
                .copy(fontWeight = FontWeight.SemiBold)
        ) {
            Row(
                Modifier.defaultMinSize(minHeight = size.height).padding(horizontal = if (quiet) 10.dp else size.padX),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) { content(labelColor) }
        }
    }
}
