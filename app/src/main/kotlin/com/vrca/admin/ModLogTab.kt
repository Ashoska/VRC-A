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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query

@Composable
internal fun ModLogTab(db: FirebaseFirestore, setError: (String?) -> Unit) {
    data class LogRow(
        val id: String,
        val action: String,
        val displayName: String,
        val vrchatId: String,
        val deviceHash: String,
        val reason: String,
        val method: String,
        val createdAt: Timestamp?
    )

    val rows = remember { mutableStateListOf<LogRow>() }
    var loading by remember { mutableStateOf(true) }
    val nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    DisposableEffect(Unit) {
        setError(null); loading = true
        val reg = db.collection("moderationEvents")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(200)
            .addSnapshotListener { snap, e ->
                if (e != null) { setError(e.message); loading = false; return@addSnapshotListener }
                if (snap == null) { loading = false; return@addSnapshotListener }
                val next = snap.documents.map { d ->
                    fun s(k: String) = (d.getString(k) ?: "").trim()
                    LogRow(
                        id = d.id,
                        action = s("action"),
                        displayName = s("newDisplayName").ifBlank { s("targetUid") },
                        vrchatId = s("newVrchatId").ifBlank { s("targetAuthUid") },
                        deviceHash = s("newDeviceHash").ifBlank { s("targetDeviceHash") },
                        reason = s("reason"),
                        method = s("method"),
                        createdAt = d.getTimestamp("createdAt")
                    )
                }
                rows.clear(); rows.addAll(next); loading = false
            }
        onDispose { reg.remove() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item {
            AdminCardHeader("Moderation Log", Icons.Filled.History, AdminTone.Neutral,
                trailing = {
                    if (loading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                })
        }

        if (rows.isEmpty() && !loading) {
            item {
                Text("No moderation events yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        items(rows, key = { it.id }) { row ->
            val isEvasion = row.action == "ban_evasion_detected"
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isEvasion)
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            row.action.replace("_", " ").replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isEvasion) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurface
                        )
                        Text(relativeTime(row.createdAt, nowMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (row.displayName.isNotBlank())
                        Text(row.displayName, style = MaterialTheme.typography.bodySmall)
                    if (row.vrchatId.isNotBlank())
                        Text(row.vrchatId, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (row.method.isNotBlank())
                        Text("Method: ${row.method}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (row.reason.isNotBlank())
                        Text("Reason: ${row.reason}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}
