package com.vrca.ui.screen

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vrca.BuildConfig
import com.vrca.vrchat.InstanceRosterManager

/**
 * "Who's in your instance" roster — the headline headset feature, fed by the
 * VRChat log reader ([InstanceRosterManager]). Lives in the far-right column of
 * the headset Home. Renders one of four states: needs file access, waiting for a
 * log, not in a world, or the live member list (name + platform).
 *
 * On non-headset builds the manager is never started, so this shows the "coming
 * soon" placeholder (it isn't placed on phone Home anyway).
 */
@Composable
fun InstanceRosterPanel(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        if (BuildConfig.IS_HEADSET_BUILD) InstanceRosterManager.start(ctx)
    }
    val ui by InstanceRosterManager.flow.collectAsState()

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Header: title + live count.
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Filled.Group, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("In your instance", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                if (ui.status == InstanceRosterManager.Status.LIVE) {
                    Text(
                        "${ui.members.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (ui.worldName != null && ui.status == InstanceRosterManager.Status.LIVE) {
                Text(
                    ui.worldName!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            when (ui.status) {
                InstanceRosterManager.Status.NEEDS_PERMISSION -> PermissionState(ctx)
                InstanceRosterManager.Status.NO_LOG -> HintState(
                    "Waiting for VRChat's log. Open VRChat and this fills in automatically."
                )
                InstanceRosterManager.Status.IDLE -> HintState(
                    "Not in a world right now. Join an instance to see who's in it."
                )
                InstanceRosterManager.Status.LIVE -> {
                    if (ui.members.isEmpty()) {
                        HintState("You're the only one here so far.")
                    } else {
                        ui.members.forEach { m -> MemberRow(m) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionState(ctx: android.content.Context) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Give VRC-A file access so it can read VRChat's log and show who's in your instance.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = {
                runCatching {
                    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + ctx.packageName)
                        )
                    } else {
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + ctx.packageName))
                    }
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Grant file access") }
    }
}

@Composable
private fun HintState(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun MemberRow(m: InstanceRosterManager.Member) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Initial-circle avatar (no pfp fetch — same choice as the admin UI).
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.size(30.dp)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    m.displayName.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
        Text(
            m.displayName,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (m.platform.isNotBlank()) {
            PlatformChip(m.platform)
        } else if (m.userId == null) {
            // Older name-only log format: no id to resolve a platform from.
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

@Composable
private fun PlatformChip(platform: String) {
    val color = when (platform) {
        "PC" -> MaterialTheme.colorScheme.primary
        "Quest" -> androidx.compose.ui.graphics.Color(0xFF2BCF5C)
        "iOS" -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.20f)
    ) {
        Text(
            platform,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontFamily = FontFamily.SansSerif,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}
