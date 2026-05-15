package com.vrca.admin

import com.vrca.BuildConfig
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

internal data class AnnouncementRow(
    val id: String,
    val title: String,
    val body: String,
    val active: Boolean,
    val priority: Int,
    val createdAt: Timestamp?
)

@Composable
internal fun AnnouncementsTab(
    db: FirebaseFirestore,
    createdByDevice: String,
    setGlobalLoading: (Boolean) -> Unit,
    setError: (String?) -> Unit
) {
    val scope = rememberCoroutineScope()
    val announcements = remember { mutableStateListOf<AnnouncementRow>() }

    var newTitle by rememberSaveable { mutableStateOf("") }
    var newBody by rememberSaveable { mutableStateOf("") }
    var newActive by rememberSaveable { mutableStateOf(true) }
    var newPriority by rememberSaveable { mutableIntStateOf(0) }

    suspend fun refresh() {
        setGlobalLoading(true)
        setError(null)

        runCatching {
            val snap = db.collection("announcements")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(50)
                .get()
                .await()

            announcements.clear()
            snap.documents.forEach { d ->
                announcements.add(
                    AnnouncementRow(
                        id = d.id,
                        title = (d.getString("title") ?: "").trim(),
                        body = (d.getString("body") ?: "").trim(),
                        active = d.getBoolean("active") ?: true,
                        priority = (d.getLong("priority") ?: 0L).toInt(),
                        createdAt = d.getTimestamp("createdAt")
                    )
                )
            }

            setGlobalLoading(false)
        }.onFailure { e ->
            setGlobalLoading(false)
            setError(e.message ?: "Failed to load announcements")
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ElevatedCard {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Announcements", style = MaterialTheme.typography.titleMedium)

                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Title") }
                )

                OutlinedTextField(
                    value = newBody,
                    onValueChange = { newBody = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    label = { Text("Body") }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Active")
                        Switch(checked = newActive, onCheckedChange = { newActive = it })
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Priority", style = MaterialTheme.typography.labelLarge)
                        IconButton(onClick = { newPriority = (newPriority - 1).coerceIn(-10, 10) }) {
                            Icon(Icons.Filled.Remove, contentDescription = "Dec")
                        }
                        Text(newPriority.toString(), fontFamily = FontFamily.Monospace)
                        IconButton(onClick = { newPriority = (newPriority + 1).coerceIn(-10, 10) }) {
                            Icon(Icons.Filled.Add, contentDescription = "Inc")
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                setGlobalLoading(true)
                                setError(null)

                                runCatching {
                                    val data = hashMapOf(
                                        "title" to newTitle.trim(),
                                        "body" to newBody.trim(),
                                        "active" to newActive,
                                        "priority" to newPriority,
                                        "createdAt" to FieldValue.serverTimestamp(),
                                        "updatedAt" to FieldValue.serverTimestamp(),
                                        "createdByDevice" to createdByDevice,
                                        "createdByAppId" to BuildConfig.APPLICATION_ID
                                    )
                                    db.collection("announcements").add(data).await()

                                    newTitle = ""
                                    newBody = ""
                                    newActive = true
                                    newPriority = 0
                                    refresh()
                                }.onFailure { e ->
                                    setGlobalLoading(false)
                                    setError(e.message ?: "Failed to publish")
                                }
                            }
                        },
                        enabled = newTitle.trim().isNotBlank() && newBody.trim().isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) { Text("Publish") }

                    OutlinedButton(
                        onClick = { scope.launch { refresh() } },
                        modifier = Modifier.weight(1f)
                    ) { Text("Refresh") }
                }
            }
        }

        if (announcements.isEmpty()) {
            Text("No announcements.", style = MaterialTheme.typography.bodySmall)
        } else {
            announcements.forEach { a ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(a.title.ifBlank { "(no title)" }, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "priority=${a.priority}  active=${a.active}  createdAt=${formatTimestamp(a.createdAt)}",
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            IconButton(
                                onClick = {
                                    scope.launch {
                                        setGlobalLoading(true)
                                        setError(null)
                                        runCatching {
                                            db.collection("announcements").document(a.id).delete().await()
                                            refresh()
                                        }.onFailure { e ->
                                            setGlobalLoading(false)
                                            setError(e.message ?: "Failed to delete announcement")
                                        }
                                    }
                                }
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete")
                            }
                        }

                        if (a.body.isNotBlank()) {
                            Text(a.body, style = MaterialTheme.typography.bodyMedium)
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        setGlobalLoading(true)
                                        setError(null)
                                        runCatching {
                                            db.collection("announcements").document(a.id)
                                                .set(
                                                    mapOf(
                                                        "active" to !a.active,
                                                        "updatedAt" to FieldValue.serverTimestamp()
                                                    ),
                                                    SetOptions.merge()
                                                )
                                                .await()
                                            refresh()
                                        }.onFailure { e ->
                                            setGlobalLoading(false)
                                            setError(e.message ?: "Failed to toggle active")
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text(if (a.active) "Disable" else "Enable") }

                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        setGlobalLoading(true)
                                        setError(null)
                                        runCatching {
                                            db.collection("announcements").document(a.id)
                                                .set(
                                                    mapOf(
                                                        "priority" to (a.priority - 1),
                                                        "updatedAt" to FieldValue.serverTimestamp()
                                                    ),
                                                    SetOptions.merge()
                                                )
                                                .await()
                                            refresh()
                                        }.onFailure { e ->
                                            setGlobalLoading(false)
                                            setError(e.message ?: "Failed to change priority")
                                        }
                                    }
                                }
                            ) { Text("Priority -") }

                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        setGlobalLoading(true)
                                        setError(null)
                                        runCatching {
                                            db.collection("announcements").document(a.id)
                                                .set(
                                                    mapOf(
                                                        "priority" to (a.priority + 1),
                                                        "updatedAt" to FieldValue.serverTimestamp()
                                                    ),
                                                    SetOptions.merge()
                                                )
                                                .await()
                                            refresh()
                                        }.onFailure { e ->
                                            setGlobalLoading(false)
                                            setError(e.message ?: "Failed to change priority")
                                        }
                                    }
                                }
                            ) { Text("Priority +") }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
    }
}
