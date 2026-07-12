package com.vrca.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.Image
import com.vrca.ui.theme.SlimeError
import com.vrca.ui.theme.SlimeSuccess
import com.vrca.ui.theme.SlimeWarning

/* =========================================================================
   PublicUiKit — shared building blocks for the public-build UI revamp.

   Design rules (docs/ui-revamp.md):
   - "Collapsed = status, expanded = control": collapsed cards must answer
     their own question via a live [summary], so nearly everything can
     default collapsed.
   - Visual north star is the VRChat login screens (NOT the admin panel):
     clean spacing, quiet trust copy, distinct state colors.
   - Consistent metrics everywhere: 12dp card padding, 8dp gaps.
   ========================================================================= */

/** Semantic state colors used by [StatusDot] / [KitStatusChip]. */
enum class KitTone { Neutral, Success, Warning, Error }

@Composable
fun KitTone.color(): Color = when (this) {
    KitTone.Neutral -> MaterialTheme.colorScheme.outline
    KitTone.Success -> SlimeSuccess
    KitTone.Warning -> SlimeWarning
    KitTone.Error   -> SlimeError
}

/**
 * Small colored state dot. The at-a-glance primitive behind every
 * "is it alive?" indicator (connection reachability, RPC state, sending).
 */
@Composable
fun StatusDot(tone: KitTone, size: Int = 10) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(tone.color())
    )
}

/**
 * Dot + label chip (e.g. "Sending", "Connected", "Unreachable").
 * Mirrors HomePage's SendStatusChip styling so existing and new chips match.
 */
@Composable
fun KitStatusChip(label: String, tone: KitTone) {
    val container =
        if (tone == KitTone.Neutral) MaterialTheme.colorScheme.surfaceVariant
        else tone.color().copy(alpha = 0.16f)
    Surface(shape = MaterialTheme.shapes.large, color = container) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            StatusDot(tone)
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Collapsible section card whose COLLAPSED header carries a live [summary]
 * ("Cycle · 5 lines · 30s") so the user never has to expand just to learn
 * state. Configure-once content defaults collapsed; daily-glance content can
 * pass [initiallyExpanded] = true or [collapsible] = false for a plain card.
 *
 * Expansion state survives recomposition AND process death via
 * [rememberSaveable] keyed on the title — a new card prepended to a list must
 * not shift another card's remembered state (same lesson as the in-app alert
 * cards' key(group.groupId) fix).
 *
 * [persistExpansion] = false opts out of that: plain [remember], so leaving
 * the tab (which disposes the composable) snaps the card back to
 * [initiallyExpanded] on return — used by the Media tab's Now Playing /
 * Progress-bar-style cards, which must always re-open collapsed.
 */
@Composable
fun CompactSectionCard(
    title: String,
    icon: ImageVector? = null,
    summary: String? = null,
    collapsible: Boolean = true,
    initiallyExpanded: Boolean = false,
    persistExpansion: Boolean = true,
    expandedState: androidx.compose.runtime.MutableState<Boolean>? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    // Caller can hoist the expand state (e.g. to auto-expand on cross-section drag
    // hover); otherwise it's owned internally as before.
    val ownState = if (persistExpansion)
        rememberSaveable(title) { mutableStateOf(!collapsible || initiallyExpanded) }
    else
        remember(title) { mutableStateOf(!collapsible || initiallyExpanded) }
    var expanded by (expandedState ?: ownState)

    ElevatedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .let { if (collapsible) it.clickable { expanded = !expanded } else it },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (icon != null) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        modifier = Modifier.size(32.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!summary.isNullOrBlank()) {
                        Text(
                            summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (trailing != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = trailing
                    )
                }
                if (collapsible) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
            }
        }
    }
}

/**
 * Compact icon + label + state toggle pill for the Home Quick Toggles list.
 * [onLongPress] is the jump-to-edit affordance (long-press Cycle →
 * Automations).
 *
 * State indicator is a single dot pinned to the RIGHT edge (labels vary in
 * width, so a dot trailing the text looked ragged). No ON/OFF text — it was
 * tried and rejected as UI clutter; the pill's color shift is the signal.
 *
 * Input uses [combinedClickable], NOT a raw pointerInput keyed on [checked]:
 * the keyed detector was cancelled+relaunched on every state flip, so taps
 * landing mid-restart were dropped or replayed a stale `!checked` (a no-op
 * write → no recomposition → the pill looked frozen until something else
 * changed). combinedClickable keeps its lambdas fresh across recomposition
 * and adds the ripple feedback the pill was missing.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TogglePill(
    label: String,
    icon: ImageVector,
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongPress: (() -> Unit)? = null,
    onToggle: (Boolean) -> Unit
) {
    val container =
        if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        else MaterialTheme.colorScheme.surfaceVariant
    val contentColor =
        if (checked) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        shape = MaterialTheme.shapes.large,
        color = container,
        modifier = modifier
    ) {
        Row(
            Modifier
                .combinedClickable(
                    enabled = enabled,
                    onClick = { onToggle(!checked) },
                    onLongClick = onLongPress
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            StatusDot(if (checked) KitTone.Success else KitTone.Neutral, size = 8)
        }
    }
}

/**
 * Dense label → value row ("Version" → "v1.7.10"). [mono] for ids/addresses.
 * The label column width is fixed so stacked rows align.
 */
@Composable
fun LabeledRow(
    label: String,
    value: String,
    mono: Boolean = false,
    labelWidth: Int = 110,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(labelWidth.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (mono) FontFamily.Monospace else null,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/**
 * Onboarding instruction image: fixed rounded frame + optional caption,
 * tap to expand full-screen with pinch zoom (Quest settings text is small
 * in a phone-width screenshot). The images are downloaded on-demand into
 * [com.vrca.ui.onboarding.TutorialImageStore] (NOT bundled in the APK), so this
 * resolves the local file by [index] (1-based), shows a spinner while it's still
 * downloading, and triggers a download itself as a fallback if a boot/replay
 * prefetch didn't cover it.
 */
@Composable
fun TutorialImage(
    index: Int,
    contentDescription: String,
    caption: String? = null
) {
    val ctx = LocalContext.current
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var file by remember(index) {
        mutableStateOf(com.vrca.ui.onboarding.TutorialImageStore.cachedFileFor(ctx, index))
    }
    LaunchedEffect(index) {
        if (file == null) {
            com.vrca.ui.onboarding.TutorialImageStore.ensureDownloaded(ctx)
            file = com.vrca.ui.onboarding.TutorialImageStore.cachedFileFor(ctx, index)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = file != null) { fullscreen = true }
        ) {
            val f = file
            if (f != null) {
                coil.compose.AsyncImage(
                    model = f,
                    contentDescription = contentDescription,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }
            }
        }
        if (!caption.isNullOrBlank()) {
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    val zoomFile = file
    if (fullscreen && zoomFile != null) {
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            var scale by rememberSaveable { mutableStateOf(1f) }
            var offsetX by rememberSaveable { mutableStateOf(0f) }
            var offsetY by rememberSaveable { mutableStateOf(0f) }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.92f))
                    .clickable { fullscreen = false }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offsetX += pan.x
                            offsetY += pan.y
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = coil.compose.rememberAsyncImagePainter(model = zoomFile),
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY
                        )
                )
            }
        }
    }
}

/**
 * Muted "why we need this" one-liner under permission/login forms — the
 * quiet trust copy that is part of the login-screen design language.
 */
@Composable
fun TrustNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * Section header row with a bold title and an optional trailing summary
 * value ("Friends activity · 6/10 on") so users can skip expanding at all.
 */
@Composable
fun KitSectionHeader(
    title: String,
    trailingValue: String? = null,
    fontWeight: FontWeight = FontWeight.SemiBold
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = fontWeight
        )
        if (!trailingValue.isNullOrBlank()) {
            Text(
                trailingValue,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * In-app banner shown when the VRChat session is confirmed dead-and-unrecoverable
 * (drives off `VrcaViewModel.vrchatAuthDead`). OSC is gated while it shows, so the
 * copy explains WHY the chatbox stopped and the likely causes, then offers a
 * one-tap sign-in. Deliberately no notification — in-app only.
 */
@Composable
fun VrchatSessionExpiredBanner(onSignIn: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = cs.errorContainer)
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = cs.onErrorContainer,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "VRChat session expired",
                    style = MaterialTheme.typography.titleSmall,
                    color = cs.onErrorContainer
                )
            }
            Text(
                "Your VRChat login expired, so the chatbox is paused. This usually " +
                    "happens after a password change or if VRC-A hasn't been opened in " +
                    "about 30 days. Sign in again to fix it.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onErrorContainer
            )
            Button(
                onClick = onSignIn,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = cs.error,
                    contentColor = cs.onError
                )
            ) { Text("Sign in to VRChat") }
        }
    }
}
