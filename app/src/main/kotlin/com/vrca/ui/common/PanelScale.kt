package com.vrca.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Headset: VRC-A has ONE layout, the default Quest panel ([WIDTH_DP] x [HEIGHT_DP]), scaled to
 * whatever window Horizon OS gives it. User request: the window shouldn't be resizable. Horizon
 * lets users resize any Android panel (Meta: the resize handles are the system's), and
 * `resizeableActivity="false"` only letterboxed the app in grey (device-tested), so the headset
 * manifest asks Horizon not to free-resize and to keep the 16:10 shape (unofficial
 * `com.oculus.vrshell.*` flags), and if a resize still happens it zooms the whole app instead of
 * reflowing it. A window of another shape gets the app's background around the panel.
 *
 * Every dialog is its own window, whose composition sets LocalDensity from its own view again, so
 * the app's dialogs use [PanelDialog] (content re-scaled with [PanelScaled]).
 */
object PanelScale {
    const val WIDTH_DP = 1024f
    const val HEIGHT_DP = 640f
}

/** The scaled density every window of the app uses (null = not scaled: phone builds). */
val LocalPanelDensity = staticCompositionLocalOf<Density?> { null }

/** The app's content at the default panel size, scaled to fit the window. */
@Composable
fun FixedPanel(content: @Composable () -> Unit) {
    BoxWithConstraints(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        val real = LocalDensity.current
        val s = if (maxWidth.value > 0f && maxHeight.value > 0f) {
            minOf(maxWidth.value / PanelScale.WIDTH_DP, maxHeight.value / PanelScale.HEIGHT_DP)
        } else 1f
        val scaled = remember(real, s) { Density(real.density * s, real.fontScale) }
        // Same structure at every size (scale 1 included), so nothing is recreated mid-resize.
        CompositionLocalProvider(LocalDensity provides scaled, LocalPanelDensity provides scaled) {
            Box(Modifier.size(PanelScale.WIDTH_DP.dp, PanelScale.HEIGHT_DP.dp)) { content() }
        }
    }
}

/** A dialog's content at the panel's scale (unchanged outside [FixedPanel]). */
@Composable
fun PanelScaled(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDensity provides (LocalPanelDensity.current ?: LocalDensity.current), content = content)
}

/**
 * [Dialog] with its content at the panel's scale: use it instead of Dialog for the app's own
 * dialogs. Material menus (DropdownMenu) stay at the window's real size: they lay out their own
 * frame inside their popup window, where only the items could be scaled, not the frame.
 */
@Composable
fun PanelDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    // Held while the app is being built behind the boot screen: a dialog is its own window, so it
    // would show OVER the boot screen. It appears as soon as the hold lifts (it reads the state).
    if (PanelDialogHold.held.value) return
    Dialog(onDismissRequest = onDismissRequest, properties = properties) { PanelScaled(content) }
}

/** While [held] is true every [PanelDialog] waits (AppBoot builds the app behind its screen). */
object PanelDialogHold {
    val held = androidx.compose.runtime.mutableStateOf(false)
}
