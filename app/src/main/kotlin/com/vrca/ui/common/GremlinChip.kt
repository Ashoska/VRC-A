package com.vrca.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vrca.ui.theme.GremlinColors

/**
 * Gremlin filter / shortcut chip (revamp 2, matches [GremlinSearchField]): a `Raised` pill with a 1dp `Edge`
 * outline; selected = a soft accent fill, 1.5dp `Accent` outline and accent text. Colours are animated and read
 * while drawing, so selecting only repaints. [leading] is an optional small symbol (e.g. a platform mark).
 */
@Composable
fun GremlinChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable RowScope.() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(10.dp)
    val fill = animateColorAsState(if (selected) GremlinColors.Accent.copy(alpha = 0.16f) else GremlinColors.Raised, label = "chipFill")
    val edge = animateColorAsState(if (selected) GremlinColors.Accent else GremlinColors.Edge, label = "chipEdge")
    Row(
        modifier
            .height(34.dp)
            .widthIn(max = 200.dp)
            .clip(shape)
            .drawBehind {
                val w = (if (selected) 1.5.dp else 1.dp).toPx()
                val r = 10.dp.toPx()
                drawRoundRect(fill.value, cornerRadius = CornerRadius(r))
                drawRoundRect(edge.value, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w),
                    cornerRadius = CornerRadius(r - w / 2), style = Stroke(w))
            }
            .semantics { this.selected = selected }
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        leading?.invoke(this)
        Text(
            label, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) GremlinColors.AccentSoft else GremlinColors.TextMuted,
        )
    }
}
