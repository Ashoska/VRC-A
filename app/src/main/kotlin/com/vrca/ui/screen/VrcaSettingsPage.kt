package com.vrca.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Power
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vrca.ui.viewmodel.VrcaViewModel

@Composable
internal fun SettingsPage(
    vm: VrcaViewModel,
    lastFirebaseIssue: String?,
    moderationError: String?
) {
    val ctx = LocalContext.current
    var debugExpanded by rememberSaveable { mutableStateOf(false) }

    PageContainer {
        // -- Permissions --
        SectionCard(title = "Permissions") {
            SettingsRow(
                icon = Icons.Filled.MusicNote,
                title = "Notification Access",
                subtitle = "Required for Now Playing detection.",
                primary = "Open"
            ) { ctx.startActivity(vm.notificationAccessIntent()) }

            SettingsRow(
                icon = Icons.Filled.Bolt,
                title = "Overlay Permission",
                subtitle = "Only needed if you use overlay.",
                primary = "Open"
            ) { ctx.startActivity(vm.overlayPermissionIntent()) }

            SettingsRow(
                icon = Icons.Filled.Power,
                title = "Battery Optimization",
                subtitle = "Stops Android pausing when screen is off.",
                primary = "Request"
            ) { ctx.startActivity(vm.batteryOptimizationIntent()) }
        }

        // -- About --
        SectionCard(title = "About") {
            Text(
                "VRC-A (made by Ashoska Mitsu Sisko)\n\n" +
                "- Sends OSC chatbox text to your Quest/PC target\n" +
                "- Includes: Pinned, Cycle, Now Playing, Manual Send\n" +
                "- Use KILL to stop all senders and clear the VRChat chatbox",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        // -- Help --
        SectionCard(title = "Help") {
            Text(
                "Nothing appears in VRChat:\n" +
                "- VRChat -> Settings -> OSC -> Enable OSC\n" +
                "- Phone + headset on the same Wi-Fi\n" +
                "- Set the correct headset IP (Home -> Connection)\n" +
                "- Try Manual Send\n\n" +
                "Now Playing blank:\n" +
                "- Enable Notification Access\n" +
                "- Reopen the app\n" +
                "- Start music so a notification exists\n\n" +
                "Stops sending with screen off:\n" +
                "- Disable Battery Optimization for VRC-A",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        // -- Debug (collapsible) --
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(
                    Modifier.fillMaxWidth().clickable { debugExpanded = !debugExpanded },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Debug", style = MaterialTheme.typography.titleMedium)
                    Icon(
                        if (debugExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (debugExpanded) "Collapse" else "Expand"
                    )
                }
                AnimatedVisibility(visible = debugExpanded) {
                    Column(
                        Modifier.padding(top = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Firebase (last issue)", style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = lastFirebaseIssue ?: "(none captured)",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )

                        Text("Moderation listener (last error)", style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = moderationError?.ifBlank { "(none)" } ?: "(none)",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )

                        Text("Listener", style = MaterialTheme.typography.labelMedium)
                        Text("Connected: ${vm.listenerConnected}", style = MaterialTheme.typography.bodySmall)
                        Text("Active package: ${vm.activePackage}", style = MaterialTheme.typography.bodySmall)
                        Text("Detected: ${vm.nowPlayingDetected}", style = MaterialTheme.typography.bodySmall)
                        Text("Playing: ${vm.nowPlayingIsPlaying}", style = MaterialTheme.typography.bodySmall)

                        Text("OSC Output Preview", style = MaterialTheme.typography.labelMedium)
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Pinned: ${vm.debugLastAfkOsc}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                Text("Cycle: ${vm.debugLastCycleOsc}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                Text("Music: ${vm.debugLastMusicOsc}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                Text("Combined: ${vm.debugLastCombinedOsc}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            }
                        }

                        Text("Last sent to VRChat (ms): ${vm.lastSentToVrchatAtMs}",
                            style = MaterialTheme.typography.bodySmall)

                        // TEMPORARY: YouTube ad-detection signal capture. Play a video
                        // that triggers a pre-roll ad, then a genuine short video, and
                        // read this log to see which signal (seek=, cust=[], over=, mid=)
                        // separates the ad from the real video.
                        val adSignals by com.vrca.nowplaying.NowPlayingDebug.lines.collectAsState()
                        Text("YouTube ad signals (debug)", style = MaterialTheme.typography.labelMedium)
                        Text(
                            "seek=ACTION_SEEK_TO  ff=fast-fwd  over=pos>dur  cust=customActions  mid=mediaId",
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = { com.vrca.nowplaying.NowPlayingDebug.clear() }) {
                            Text("Clear log")
                        }
                        if (adSignals.isEmpty()) {
                            Text(
                                "No samples yet. Play YouTube / YouTube Music to populate.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            SelectionContainer {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    // Newest first.
                                    for (line in adSignals.asReversed()) {
                                        Text(
                                            line,
                                            fontFamily = FontFamily.Monospace,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    primary: String,
    onPrimary: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onPrimary() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null)
            }
        }

        Spacer(Modifier.width(10.dp))

        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        TextButton(onClick = onPrimary) {
            Text(primary)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Filled.ChevronRight, contentDescription = null)
        }
    }
}
