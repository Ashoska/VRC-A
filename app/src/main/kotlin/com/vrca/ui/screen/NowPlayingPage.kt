package com.vrca.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vrca.ui.settings.ToggleRow
import com.vrca.ui.viewmodel.VrcaViewModel
import kotlinx.coroutines.delay

@Composable
internal fun NowPlayingPage(
    vm: VrcaViewModel,
    isBanned: Boolean,
    onPersistSpotifyEnabled: (Boolean) -> Unit,
    onPersistSpotifyDemo: (Boolean) -> Unit,
    onPersistSpotifyPreset: (Int) -> Unit
) {
    val ctx = LocalContext.current

    var previewT by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            previewT += 0.02f
            if (previewT > 1f) previewT -= 1f
            delay(60L)
        }
    }

    PageContainer {
        SectionCard(
            title = "Now Playing",
            subtitle = "Uses Notification Access. Stop clears instantly."
        ) {
            ToggleRow("Enable Now Playing block", vm.spotifyEnabled, enabled = !isBanned) {
                vm.setSpotifyEnabledFlag(it)
                onPersistSpotifyEnabled(it)
            }
            ToggleRow("Demo mode (testing)", vm.spotifyDemoEnabled, enabled = !isBanned) {
                vm.setSpotifyDemoFlag(it)
                onPersistSpotifyDemo(it)
            }

            OutlinedButton(
                onClick = { ctx.startActivity(vm.notificationAccessIntent()) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Open Notification Access settings") }

            Text(
                text = "Music refresh: 0.5 seconds (fixed)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text("Progress bar preset:", style = MaterialTheme.typography.labelLarge)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..5).forEach { p ->
                    val selected = (vm.spotifyPreset == p)
                    val name = vm.getMusicPresetName(p)

                    ElevatedCard(
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(10.dp)
                                .clickable(enabled = !isBanned) {
                                    vm.updateSpotifyPreset(p)
                                    onPersistSpotifyPreset(p)
                                },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(text = name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = vm.renderMusicPresetPreview(p, previewT),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            if (selected) Text("Selected", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

        }

        SectionCard(
            title = "Detected / Preview",
            subtitle = "If blank: enable access, restart app, then play music."
        ) {
            Text("Detected: ${vm.nowPlayingDetected}")
            Text("Artist: ${vm.lastNowPlayingArtist}")
            Text("Title: ${vm.lastNowPlayingTitle}")
            Text("App: ${vm.activePackage}")
            Text("Status: ${if (vm.nowPlayingIsPlaying) "Playing" else "Paused"}")
        }
    }
}
