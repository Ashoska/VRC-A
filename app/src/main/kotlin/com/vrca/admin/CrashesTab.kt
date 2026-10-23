package com.vrca.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await

/**
 * Crash reports the app sent (CrashUpload, `crashes` collection). One read when the tab opens and on
 * Refresh (no listener: crashes are rare and a live listener would bill every change).
 */
@Composable
internal fun CrashesTab(db: FirebaseFirestore, setError: (String?) -> Unit) {
    class CrashRow(
        val id: String, val where: String, val what: String, val version: String, val build: String,
        val device: String, val deviceHash: String, val createdAt: Timestamp?, val text: String
    )

    var rows by remember { mutableStateOf<List<CrashRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var tick by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<String?>(null) }
    val nowMs = remember(tick) { System.currentTimeMillis() }

    LaunchedEffect(tick) {
        setError(null); loading = true
        runCatching {
            db.collection("crashes").orderBy("createdAt", Query.Direction.DESCENDING).limit(100).get().await()
        }.onSuccess { snap ->
            rows = snap.documents.map { d ->
                fun s(k: String) = (d.getString(k) ?: "").trim()
                CrashRow(d.id, s("where"), s("what"), s("version"), s("build"), s("device"), s("deviceHash"), d.getTimestamp("createdAt"), s("text"))
            }
        }.onFailure { setError(it.message) }
        loading = false
    }

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            AdminCardHeader("Crashes", Icons.Filled.BugReport, AdminTone.Error, trailing = {
                if (loading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else IconButton(onClick = { tick++ }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
            })
        }
        if (rows.isEmpty() && !loading) item {
            Text("No crashes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(rows, key = { it.id }) { row ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(
                    Modifier.fillMaxWidth().clickable { open = if (open == row.id) null else row.id }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(row.where, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                        Text(relativeTime(row.createdAt, nowMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        IconButton(onClick = {
                            db.collection("crashes").document(row.id).delete()
                                .addOnSuccessListener { rows = rows.filter { it.id != row.id } }
                                .addOnFailureListener { setError(it.message) }
                        }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Delete, contentDescription = "Delete", modifier = Modifier.size(18.dp)) }
                    }
                    Text(row.what, style = MaterialTheme.typography.bodySmall)
                    Text(
                        listOf(row.version, row.build, row.device).filter { it.isNotBlank() }.joinToString("  ·  "),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (row.deviceHash.isNotBlank()) Text(
                        row.deviceHash.take(16), fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (open == row.id) Box(
                        Modifier.padding(top = 6.dp).fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState()).padding(8.dp)
                    ) {
                        Text(row.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, softWrap = false)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}
