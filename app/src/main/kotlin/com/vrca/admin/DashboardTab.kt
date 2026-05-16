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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
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
import androidx.compose.ui.Modifier
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
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            if (usersLoading) {
                Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Loading...")
                }
            }
        }

        item {
            ElevatedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Overview", style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatChip(Modifier.weight(1f), "Users", totalUsers.toString())
                        StatChip(Modifier.weight(1f), "Online", onlineCount.toString(),
                            highlight = onlineCount > 0)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatChip(Modifier.weight(1f), "Warned", warnedCount.toString(),
                            warn = warnedCount > 0)
                        StatChip(Modifier.weight(1f), "Banned", bannedCount.toString(),
                            error = bannedCount > 0)
                    }
                    if (evasionCount > 0) {
                        Card(colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Row(Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("(!) ", style = MaterialTheme.typography.bodyMedium)
                                Column {
                                    Text("Ban evasion attempts detected: $evasionCount",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer)
                                    Text("Check the Log tab for details.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer)
                                }
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
internal fun StatChip(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    highlight: Boolean = false,
    warn: Boolean = false,
    error: Boolean = false
) {
    val containerColor = when {
        error     -> MaterialTheme.colorScheme.errorContainer
        warn      -> MaterialTheme.colorScheme.tertiaryContainer
        highlight -> MaterialTheme.colorScheme.primaryContainer
        else      -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = containerColor)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
