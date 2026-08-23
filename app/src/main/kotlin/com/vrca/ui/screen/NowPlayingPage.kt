package com.vrca.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.vrca.ui.common.CompactSectionCard
import com.vrca.ui.common.KitSectionHeader
import com.vrca.ui.common.KitStatusChip
import com.vrca.ui.common.KitTone
import com.vrca.ui.settings.ToggleRow
import com.vrca.ui.viewmodel.VrcaViewModel
import kotlinx.coroutines.delay

/**
 * Media tab (UI revamp Phase 6, docs/ui-revamp.md):
 * - Notification Access shrinks to a one-line warning row shown ONLY while
 *   access is missing (onboarding + Settings own the permission otherwise).
 * - A proper now-playing card: source app icon, track + artist, the EXACT
 *   rendered chatbox lines, ad/live state chip, per-source enable rows.
 * - Progress-bar preset rows at half height, no "Selected" label (the
 *   highlight IS the selection). Raw detection booleans moved to
 *   Settings → Debug.
 */
@Composable
internal fun NowPlayingPage(
    vm: VrcaViewModel,
    isBanned: Boolean,
    onOpenPermissions: () -> Unit,
    onPersistSpotifyPreset: (Int) -> Unit
) {
    val ctx = LocalContext.current

    // Notification Access status — gated on the REAL granted permission
    // (NotificationManagerCompat), NOT vm.listenerConnected. listenerConnected
    // only flips true once the OS (re)binds the listener service and pushes a
    // state update; after a process restart it sits false even though the
    // permission IS granted, which made this warning falsely persist while music
    // was clearly being detected. Re-checked on ON_RESUME (return from Settings).
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var permRefreshTick by remember { mutableStateOf(0) }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) permRefreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val listenerGranted = remember(permRefreshTick) {
        androidx.core.app.NotificationManagerCompat
            .getEnabledListenerPackages(ctx)
            .contains(ctx.packageName)
    }

    // Drives the animated preset glyphs AND the live re-read of the rendered
    // chatbox lines (position counters advance via clock extrapolation, so
    // without a tick the time/bar would freeze between media-state pushes).
    var previewT by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            previewT += 0.02f
            if (previewT > 1f) previewT -= 1f
            delay(60L)
        }
    }

    PageContainer {
        // -- Notification Access: warning row only when the permission is missing --
        if (!listenerGranted) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenPermissions() }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        Icons.Filled.NotificationsOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        "Notification Access is missing — tap to grant it in Settings so music can be detected.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        // -- Now Playing card --
        NowPlayingCard(vm = vm, isBanned = isBanned, previewT = previewT)

        // -- Progress bar style --
        // Defaults CLOSED and deliberately does NOT persist expansion: leaving
        // the tab snaps it shut again (per user request).
        CompactSectionCard(
            title = "Progress bar style",
            icon = Icons.Filled.GraphicEq,
            summary = if (vm.musicShowProgress) vm.getMusicPresetName(vm.spotifyPreset)
                      else "Hidden — title only",
            persistExpansion = false
        ) {
            ToggleRow(
                label = "Show progress bar",
                checked = vm.musicShowProgress,
                enabled = !isBanned,
                description = "Off = only the track title shows in the chatbox — no bar or time."
            ) { vm.setMusicShowProgressFlag(it) }
            (1..5).forEach { p ->
                val selected = (vm.spotifyPreset == p)
                PresetRow(
                    name = vm.getMusicPresetName(p),
                    glyph = vm.renderMusicPresetPreview(p, previewT),
                    selected = selected,
                    enabled = !isBanned
                ) {
                    vm.updateSpotifyPreset(p)
                    onPersistSpotifyPreset(p)
                }
            }
        }
    }
}

/* =========================
   Now Playing card
   ========================= */

@Composable
private fun NowPlayingCard(vm: VrcaViewModel, isBanned: Boolean, previewT: Float) {
    val safeTitle = vm.lastNowPlayingTitle.takeIf { it != "(blank)" }?.trim().orEmpty()
    val safeArtist = vm.lastNowPlayingArtist.takeIf { it != "(blank)" }?.trim().orEmpty()
    val pkg = vm.activePackage
    val detected = vm.nowPlayingDetected
    val sourceEnabled = vm.isActiveMediaSourceEnabled()

    val summary = when {
        !detected -> "Nothing playing"
        vm.nowPlayingIsAd -> "Ad playing · ${sourceLabel(pkg)}"
        safeTitle.isBlank() && safeArtist.isBlank() -> sourceLabel(pkg)
        else -> listOf(safeTitle, safeArtist).filter { it.isNotBlank() }
            .joinToString(" — ") + " · " + sourceLabel(pkg)
    }

    // Defaults CLOSED and resets to closed when the user navigates away and
    // back (persistExpansion = false) — per user request.
    CompactSectionCard(
        title = "Now Playing",
        icon = Icons.Filled.MusicNote,
        summary = summary,
        persistExpansion = false,
        trailing = {
            when {
                !detected -> KitStatusChip("Idle", KitTone.Neutral)
                !sourceEnabled -> KitStatusChip("Source off", KitTone.Warning)
                vm.nowPlayingIsAd -> KitStatusChip("Ad", KitTone.Warning)
                vm.nowPlayingIsLive -> KitStatusChip("LIVE", KitTone.Error)
                vm.nowPlayingIsPlaying -> KitStatusChip("Playing", KitTone.Success)
                else -> KitStatusChip("Paused", KitTone.Neutral)
            }
        }
    ) {
        // Track row: source app icon + title/artist + app name
        if (detected) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val icon = rememberAppIcon(pkg)
                if (icon != null) {
                    Image(
                        bitmap = icon,
                        contentDescription = sourceLabel(pkg),
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                } else {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.MusicNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            vm.nowPlayingIsAd -> "Advertisement"
                            safeTitle.isNotBlank() -> safeTitle
                            else -> "Unknown track"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        listOfNotNull(
                            safeArtist.takeIf { it.isNotBlank() && !vm.nowPlayingIsAd },
                            sourceLabel(pkg)
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        } else {
            Text(
                "Play something in a media app — the track appears here and in your chatbox while Now Playing is toggled on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // The exact chatbox lines, verbatim. previewT keys the re-read so the
        // bar/time keep moving between media-state pushes.
        if (detected) {
            KitSectionHeader(title = "Chatbox output")
            val lines = remember(previewT) { vm.currentMusicChatboxLines() }
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = when {
                        lines.isNotEmpty() -> lines.joinToString("\n")
                        !sourceEnabled -> "(hidden — ${sourceLabel(pkg)} is disabled below)"
                        else -> "(nothing to show)"
                    },
                    modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Title formatting
        KitSectionHeader(title = "Formatting")
        ToggleRow(
            label = "Filter Titles",
            checked = vm.musicCleanTitles,
            enabled = !isBanned
        ) { vm.setMusicCleanTitlesFlag(it) }

        // Per-source enables
        KitSectionHeader(title = "Sources")
        ToggleRow(
            label = "Spotify",
            checked = vm.mediaSourceSpotify,
            enabled = !isBanned
        ) { vm.setMediaSourceFlag(VrcaViewModel.PKG_SPOTIFY, it) }
        ToggleRow(
            label = "YouTube",
            checked = vm.mediaSourceYoutube,
            enabled = !isBanned
        ) { vm.setMediaSourceFlag(VrcaViewModel.PKG_YOUTUBE, it) }
        ToggleRow(
            label = "YouTube Music",
            checked = vm.mediaSourceYtMusic,
            enabled = !isBanned
        ) { vm.setMediaSourceFlag(VrcaViewModel.PKG_YTMUSIC, it) }
        Text(
            "A source that's off is never shown in the chatbox. Other media apps are always allowed.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/* =========================
   Half-height preset row
   ========================= */

@Composable
private fun PresetRow(
    name: String,
    glyph: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onSelect() }
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                name,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                glyph,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/* =========================
   Helpers
   ========================= */

private fun sourceLabel(pkg: String): String = when (pkg) {
    VrcaViewModel.PKG_SPOTIFY -> "Spotify"
    VrcaViewModel.PKG_YOUTUBE -> "YouTube"
    VrcaViewModel.PKG_YTMUSIC -> "YouTube Music"
    "(none)", "" -> "No app"
    else -> pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
}

/** The detected media app's launcher icon via PackageManager (no network, no
 *  new deps). Null when the package isn't resolvable — caller shows a glyph. */
@Composable
private fun rememberAppIcon(pkg: String): ImageBitmap? {
    val ctx = LocalContext.current
    return remember(pkg) {
        runCatching {
            ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()
        }.getOrNull()
    }
}
