package com.vrca.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.vrca.app.BootIconView
import com.vrca.app.BootIcons
import com.vrca.ui.theme.GremlinColors

/**
 * Gremlin search box (revamp 2): the GremlinButton face (dark `Raised` layer, 14dp corners, 1.5dp outline)
 * with our eared magnifier. Focus turns the outline and the icon's glint accent and thickens the line;
 * the colours are read while drawing, so focusing only repaints. Enter = [onSearch].
 */
@Composable
fun GremlinSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val edge = animateColorAsState(if (focused) GremlinColors.Accent else GremlinColors.Edge, label = "searchEdge")
    val glint = animateColorAsState(if (focused) GremlinColors.Accent else GremlinColors.TextMuted, label = "searchGlint")
    val shape = RoundedCornerShape(14.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        interactionSource = interaction,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = GremlinColors.Text),
        cursorBrush = SolidColor(GremlinColors.Accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = modifier
            .height(52.dp)
            .background(GremlinColors.Raised, shape)
            .drawBehind {
                val w = (if (focused) 2.dp else 1.5.dp).toPx()
                drawRoundRect(edge.value, topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                    cornerRadius = CornerRadius(14.dp.toPx() - w / 2), style = Stroke(w))
            },
        decorationBox = { inner ->
            Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                BootIconView(BootIcons.Search, { GremlinColors.TextMuted }, { glint.value }, Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = GremlinColors.TextMuted.copy(alpha = 0.7f))
                    inner()
                }
            }
        }
    )
}
