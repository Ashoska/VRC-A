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
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import com.vrca.richcontent.RichBlock
import com.vrca.richcontent.RichDoc
import com.vrca.richcontent.resolveRichDoc
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

internal data class AnnouncementRow(
    val id: String,
    val title: String,
    val body: String,
    val active: Boolean,
    val priority: Int,
    val bodyDoc: String,
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
    val githubPat = BuildConfig.GITHUB_PAT

    var newTitle by rememberSaveable { mutableStateOf("") }
    var newActive by rememberSaveable { mutableStateOf(true) }
    var newPriority by rememberSaveable { mutableIntStateOf(0) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    // The rich body being authored. One write; media on GitHub; zero Firestore reads.
    val blocks = remember { mutableStateListOf<RichBlock>() }

    fun resetForm() {
        newTitle = ""; newActive = true; newPriority = 0
        editingId = null; blocks.clear()
    }

    fun loadIntoEditor(a: AnnouncementRow, asCopy: Boolean) {
        newTitle = a.title
        newActive = a.active
        newPriority = a.priority
        blocks.clear()
        resolveRichDoc(a.bodyDoc, a.body)?.let { blocks.addAll(it.blocks) }
        editingId = if (asCopy) null else a.id
    }

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
                        bodyDoc = d.getString("bodyDoc") ?: "",
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
                AdminCardHeader(
                    if (editingId == null) "Announcements" else "Editing announcement",
                    Icons.Filled.Campaign, AdminTone.Info
                )

                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Title") }
                )

                RichDocEditor(blocks = blocks, githubPat = githubPat)

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
                                    val doc = RichDoc(blocks = blocks.toList())
                                    val bodyDocJson = if (doc.blocks.isEmpty()) "" else doc.toJson()
                                    val plain = doc.toPlainText()
                                    val data = hashMapOf<String, Any>(
                                        "title" to newTitle.trim(),
                                        "body" to plain,
                                        "bodyDoc" to bodyDocJson,
                                        "active" to newActive,
                                        "priority" to newPriority,
                                        "updatedAt" to FieldValue.serverTimestamp(),
                                        "createdByDevice" to createdByDevice,
                                        "createdByAppId" to BuildConfig.APPLICATION_ID
                                    )
                                    val target = editingId
                                    if (target == null) {
                                        // ONE write. No re-read — optimistically prepend the row.
                                        data["createdAt"] = FieldValue.serverTimestamp()
                                        val ref = db.collection("announcements").add(data).await()
                                        announcements.add(
                                            0,
                                            AnnouncementRow(
                                                ref.id, newTitle.trim(), plain, newActive,
                                                newPriority, bodyDocJson, Timestamp.now()
                                            )
                                        )
                                    } else {
                                        val oldMedia = announcements.firstOrNull { it.id == target }
                                            ?.let { resolveRichDoc(it.bodyDoc, it.body)?.mediaUrls() } ?: emptyList()
                                        db.collection("announcements").document(target)
                                            .set(data, SetOptions.merge()).await()
                                        val idx = announcements.indexOfFirst { it.id == target }
                                        if (idx >= 0) announcements[idx] = announcements[idx].copy(
                                            title = newTitle.trim(), body = plain, bodyDoc = bodyDocJson,
                                            active = newActive, priority = newPriority
                                        )
                                        // Delete media that was in the old version but not the new one.
                                        val newMedia = doc.mediaUrls()
                                        githubDeleteMedia(oldMedia.filter { it !in newMedia }, githubPat)
                                    }
                                    resetForm()
                                    setGlobalLoading(false)
                                }.onFailure { e ->
                                    setGlobalLoading(false)
                                    setError(e.message ?: "Failed to publish")
                                }
                            }
                        },
                        enabled = newTitle.trim().isNotBlank() && blocks.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) { Text(if (editingId == null) "Publish" else "Update") }

                    if (editingId != null) {
                        OutlinedButton(
                            onClick = { resetForm() },
                            modifier = Modifier.weight(1f)
                        ) { Text("Cancel") }
                    } else {
                        OutlinedButton(
                            onClick = { scope.launch { refresh() } },
                            modifier = Modifier.weight(1f)
                        ) { Text("Refresh") }
                    }
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
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(a.title.ifBlank { "(no title)" }, style = MaterialTheme.typography.titleSmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    StatusPill(if (a.active) "LIVE" else "DRAFT",
                                        if (a.active) AdminTone.Success else AdminTone.Neutral)
                                    StatusPill("P${a.priority}", AdminTone.Info)
                                    if (a.bodyDoc.isNotBlank()) StatusPill("RICH", AdminTone.Primary)
                                }
                                Text(
                                    formatTimestamp(a.createdAt),
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            IconButton(
                                onClick = {
                                    scope.launch {
                                        setGlobalLoading(true)
                                        setError(null)
                                        runCatching {
                                            val media = resolveRichDoc(a.bodyDoc, a.body)?.mediaUrls() ?: emptyList()
                                            db.collection("announcements").document(a.id).delete().await()
                                            announcements.removeAll { it.id == a.id }
                                            if (editingId == a.id) resetForm()
                                            githubDeleteMedia(media, githubPat)  // free the orphaned files
                                            setGlobalLoading(false)
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
                                onClick = { loadIntoEditor(a, asCopy = false) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.Edit, null, modifier = Modifier.height(16.dp))
                                Spacer(Modifier.height(0.dp)); Text(" Edit")
                            }
                            OutlinedButton(
                                onClick = { loadIntoEditor(a, asCopy = true) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.height(16.dp))
                                Text(" Copy")
                            }
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
                                            val idx = announcements.indexOfFirst { it.id == a.id }
                                            if (idx >= 0) announcements[idx] = announcements[idx].copy(active = !a.active)
                                            setGlobalLoading(false)
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
                                            val next = a.priority - 1
                                            db.collection("announcements").document(a.id)
                                                .set(
                                                    mapOf(
                                                        "priority" to next,
                                                        "updatedAt" to FieldValue.serverTimestamp()
                                                    ),
                                                    SetOptions.merge()
                                                )
                                                .await()
                                            val idx = announcements.indexOfFirst { it.id == a.id }
                                            if (idx >= 0) announcements[idx] = announcements[idx].copy(priority = next)
                                            setGlobalLoading(false)
                                        }.onFailure { e ->
                                            setGlobalLoading(false)
                                            setError(e.message ?: "Failed to change priority")
                                        }
                                    }
                                }
                            ) { Text("P-") }

                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        setGlobalLoading(true)
                                        setError(null)
                                        runCatching {
                                            val next = a.priority + 1
                                            db.collection("announcements").document(a.id)
                                                .set(
                                                    mapOf(
                                                        "priority" to next,
                                                        "updatedAt" to FieldValue.serverTimestamp()
                                                    ),
                                                    SetOptions.merge()
                                                )
                                                .await()
                                            val idx = announcements.indexOfFirst { it.id == a.id }
                                            if (idx >= 0) announcements[idx] = announcements[idx].copy(priority = next)
                                            setGlobalLoading(false)
                                        }.onFailure { e ->
                                            setGlobalLoading(false)
                                            setError(e.message ?: "Failed to change priority")
                                        }
                                    }
                                }
                            ) { Text("P+") }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
    }
}
