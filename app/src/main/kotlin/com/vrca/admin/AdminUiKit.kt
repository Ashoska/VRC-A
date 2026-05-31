package com.vrca.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/* =========================================================
   ADMIN UI KIT — shared visual language for the admin panel
   (Phase 6). Cohesive section cards, stat tiles, status
   pills, labeled rows, and identity avatars. All admin
   surfaces (Dashboard, Directory, Detail) compose from these
   so density + clarity stay consistent. Pure presentation —
   no behavior lives here.
   ========================================================= */

/** Semantic color tone used across tiles, pills, and section accents. */
internal enum class AdminTone { Neutral, Primary, Success, Warn, Error, Info }

internal data class ToneColors(val container: Color, val on: Color, val accent: Color)

@Composable
internal fun toneColors(tone: AdminTone): ToneColors {
    val cs = MaterialTheme.colorScheme
    return when (tone) {
        AdminTone.Primary -> ToneColors(cs.primaryContainer, cs.onPrimaryContainer, cs.primary)
        AdminTone.Success -> ToneColors(cs.primaryContainer, cs.onPrimaryContainer, cs.primary)
        AdminTone.Warn    -> ToneColors(cs.tertiaryContainer, cs.onTertiaryContainer, cs.tertiary)
        AdminTone.Error   -> ToneColors(cs.errorContainer, cs.onErrorContainer, cs.error)
        AdminTone.Info    -> ToneColors(cs.secondaryContainer, cs.onSecondaryContainer, cs.secondary)
        AdminTone.Neutral -> ToneColors(cs.surfaceVariant, cs.onSurfaceVariant, cs.onSurfaceVariant)
    }
}

/**
 * A consistently-styled content section: an ElevatedCard with an icon-in-a-tinted-circle
 * header, a title, and an optional trailing slot (e.g. a refresh button or chip).
 */
@Composable
internal fun AdminSectionCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tone: AdminTone = AdminTone.Primary,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val tc = toneColors(tone)
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(color = tc.accent.copy(alpha = 0.16f), shape = CircleShape) {
                    Icon(
                        icon, contentDescription = null,
                        tint = tc.accent,
                        modifier = Modifier.padding(6.dp).size(18.dp)
                    )
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (trailing != null) trailing()
            }
            content()
        }
    }
}

/**
 * A standalone iconed section header (icon-in-tinted-circle + title), for use
 * INSIDE an existing card/column where wrapping in [AdminSectionCard] isn't
 * convenient. Matches the [AdminSectionCard] header styling.
 */
@Composable
internal fun AdminCardHeader(
    title: String,
    icon: ImageVector,
    tone: AdminTone = AdminTone.Primary,
    trailing: (@Composable () -> Unit)? = null
) {
    val tc = toneColors(tone)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(color = tc.accent.copy(alpha = 0.16f), shape = CircleShape) {
            Icon(
                icon, contentDescription = null,
                tint = tc.accent,
                modifier = Modifier.padding(6.dp).size(18.dp)
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        if (trailing != null) trailing()
    }
}

/** A big-number stat tile for the Dashboard grid. */
@Composable
internal fun AdminStatTile(
    label: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tone: AdminTone = AdminTone.Neutral
) {
    val tc = toneColors(tone)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = tc.container
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = tc.on.copy(alpha = 0.8f)
                )
                Icon(icon, contentDescription = null, tint = tc.accent, modifier = Modifier.size(18.dp))
            }
            Text(
                value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = tc.on
            )
        }
    }
}

/** A compact rounded status pill. */
@Composable
internal fun StatusPill(text: String, tone: AdminTone) {
    val tc = toneColors(tone)
    Surface(shape = RoundedCornerShape(50), color = tc.accent.copy(alpha = 0.18f)) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = tc.accent,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/** A label → value row, optionally monospaced (for ids/hashes). */
@Composable
internal fun AdminLabeledRow(
    label: String,
    value: String,
    mono: Boolean = false,
    labelWidth: Int = 76
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(width = labelWidth.dp, height = 18.dp)
        )
        Text(
            value,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * An identity avatar: a colored circle with the first initial, with a small
 * online/offline status dot in the corner.
 */
@Composable
internal fun AdminAvatar(
    name: String,
    online: Boolean,
    size: Int = 40
) {
    val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val cs = MaterialTheme.colorScheme
    Box(contentAlignment = Alignment.Center) {
        Surface(
            shape = CircleShape,
            color = cs.primary.copy(alpha = 0.16f),
            modifier = Modifier.size(size.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    initial,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = cs.primary
                )
            }
        }
        Box(
            modifier = Modifier
                .size((size * 0.30f).dp)
                .align(Alignment.BottomEnd)
                .background(cs.surface, CircleShape)
                .padding(2.dp)
                .background(if (online) cs.primary else cs.outline, CircleShape)
        )
    }
}
