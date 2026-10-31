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
import com.vrca.app.BootCat
import com.vrca.app.BootCatCountdown
import com.vrca.app.Mark
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.vrca.app.BootIcons
import com.vrca.ui.theme.GremlinColors

/**
 * Gremlin search box (revamp 2, user pick "C" of the search-box sheet, 2026-10-31): a custom box (dark `Raised`
 * layer, 14dp corners, 1.5dp `Edge` outline, 2dp `Accent` while typing) whose only button is VRC-A's eared
 * magnifier in a round accent ring on the right. The ring shows Boot Cat thinking while [searching] and the
 * countdown cat while [cooldown] (1 → 0) runs; it can't be pressed then. Enter does the same as the ring.
 * Colours are read while drawing, so focusing only repaints.
 */
@Composable
fun GremlinSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    searching: Boolean = false,
    cooldown: () -> Float = { 0f },
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val edge = animateColorAsState(if (focused) GremlinColors.Accent else GremlinColors.Edge, label = "searchEdge")
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
            Row(Modifier.padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = GremlinColors.TextMuted.copy(alpha = 0.7f), maxLines = 1)
                    inner()
                }
                SearchRing(enabled = value.isNotBlank(), searching = searching, cooldown = cooldown, onSearch = onSearch)
            }
        }
    )
}

@Composable
private fun SearchRing(enabled: Boolean, searching: Boolean, cooldown: () -> Float, onSearch: () -> Unit) {
    val cooling by remember { androidx.compose.runtime.derivedStateOf { cooldown() > 0f } }
    val active = enabled && !searching && !cooling
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(GremlinColors.Card)
            .border(1.5.dp, GremlinColors.Accent.copy(alpha = if (active) 1f else 0.4f), CircleShape)
            .clickable(enabled = active, onClickLabel = "Search", role = androidx.compose.ui.semantics.Role.Button) { onSearch() }
            .semantics { contentDescription = "Search" },
        contentAlignment = Alignment.Center
    ) {
        when {
            searching -> BootCat(Mark.Working, 0, Modifier.size(26.dp))
            cooling -> BootCatCountdown(cooldown(), Modifier.size(24.dp), smooth = false)
            else -> BootIconView(BootIcons.Search, { GremlinColors.TextMuted }, { if (active) GremlinColors.Accent else GremlinColors.TextMuted }, Modifier.size(22.dp))
        }
    }
}
