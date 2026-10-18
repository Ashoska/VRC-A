package com.vrca.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

/**
 * The app's slider: a thin 4 dp track and a round 20 dp thumb in the accent colour. Material 3
 * 1.3+ restyled its default slider (thick split track + bar handle); this keeps VRC-A's look and
 * stays put across library updates. Same parameters as Material's Slider for what we use.
 */
@Composable
fun GremlinSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null
) {
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.surfaceVariant
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        valueRange = valueRange,
        onValueChangeFinished = onValueChangeFinished,
        thumb = { Box(Modifier.size(20.dp).background(active, CircleShape)) },
        track = { state ->
            Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                val span = state.valueRange.endInclusive - state.valueRange.start
                val f = if (span > 0f) ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f) else 0f
                val y = size.height / 2f
                drawLine(inactive, Offset(0f, y), Offset(size.width, y), size.height, StrokeCap.Round)
                drawLine(active, Offset(0f, y), Offset(size.width * f, y), size.height, StrokeCap.Round)
            }
        }
    )
}
