package com.vrca.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await

@Composable
internal fun DashboardTab(
    db: FirebaseFirestore,
    users: List<UserRow>,
    usersLoading: Boolean,
    totalUsersCount: Int,
    warnedUsersCount: Int,
    bannedUsersCount: Int,
    onRefresh: () -> Unit,
    setError: (String?) -> Unit
) {
    val totalUsers  = totalUsersCount
    // Dashboard counter uses the raw isOnlineInApp flag (no staleness window)
    // so online users show immediately when the admin opens the app, before
    // their heartbeats arrive. The admin-side staleness sweep cleans up dead users.
    val onlineCount = users.count { it.isOnlineInApp }
    val bannedCount = bannedUsersCount
    val warnedCount = warnedUsersCount
    var evasionCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            try {
                val snap = db.collection("moderationEvents")
                    .whereEqualTo("action", "ban_evasion_detected")
                    .count()
                    .get(com.google.firebase.firestore.AggregateSource.SERVER)
                    .await()
                evasionCount = snap.count.toInt()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {}
            delay(60_000L)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Hero: live online count.
        item {
            AdminSectionCard(
                title = "Live overview",
                icon = Icons.Filled.Bolt,
                tone = AdminTone.Primary,
                trailing = {
                    if (usersLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                    }
                }
            ) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        onlineCount.toString(),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = if (onlineCount > 0) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "online now",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
                Text(
                    "of $totalUsers total users",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Stat grid (2x2).
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AdminStatTile(
                        modifier = Modifier.weight(1f),
                        label = "Users", value = totalUsers.toString(),
                        icon = Icons.Filled.Group, tone = AdminTone.Neutral
                    )
                    AdminStatTile(
                        modifier = Modifier.weight(1f),
                        label = "Online", value = onlineCount.toString(),
                        icon = Icons.Filled.Bolt,
                        tone = if (onlineCount > 0) AdminTone.Primary else AdminTone.Neutral
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AdminStatTile(
                        modifier = Modifier.weight(1f),
                        label = "Warned", value = warnedCount.toString(),
                        icon = Icons.Filled.Warning,
                        tone = if (warnedCount > 0) AdminTone.Warn else AdminTone.Neutral
                    )
                    AdminStatTile(
                        modifier = Modifier.weight(1f),
                        label = "Banned", value = bannedCount.toString(),
                        icon = Icons.Filled.Block,
                        tone = if (bannedCount > 0) AdminTone.Error else AdminTone.Neutral
                    )
                }
            }
        }

        // Ban-evasion alert.
        if (evasionCount > 0) {
            item {
                AdminSectionCard(
                    title = "Ban evasion detected",
                    icon = Icons.Filled.Shield,
                    tone = AdminTone.Error
                ) {
                    Text(
                        "$evasionCount evasion attempt${if (evasionCount == 1) "" else "s"} captured.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Open the Log tab for details (new device / new VRChat ID on a banned record).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
    }
}
