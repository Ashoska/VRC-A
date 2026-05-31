package com.vrca.admin

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
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

internal data class ModerationEventRow(
    val id: String,
    val action: String,
    val reason: String,
    val createdAt: Timestamp?,
    val byDeviceHash: String,
    val byUid: String,
    val byAppId: String,
    val targetDocId: String,
    val targetAuthUid: String,
    val targetDeviceHash: String
)

@Composable
internal fun ModerationTab(
    db: FirebaseFirestore,
    myUid: String,
    byDeviceHash: String,
    byAppId: String,
    clipboardCopy: (String) -> Unit,
    setGlobalLoading: (Boolean) -> Unit,
    setError: (String?) -> Unit,
    initialTarget: ModerationTarget?,
    onClearInitialTarget: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var lookup by rememberSaveable { mutableStateOf("") } // can be docId or authUid
    var loaded by remember { mutableStateOf<ModerationTarget?>(null) }

    // LIVE state from Firestore (never bind these directly to TextFields)
    var liveWarned by remember { mutableStateOf(false) }
    var liveBanned by remember { mutableStateOf(false) }
    var liveWarnReason by remember { mutableStateOf("") }
    var liveBanReason by remember { mutableStateOf("") }

    // EDITOR state (TextFields bind to these)
    var editWarnReason by rememberSaveable { mutableStateOf("") }
    var editBanReason by rememberSaveable { mutableStateOf("") }
    var editingWarn by rememberSaveable { mutableStateOf(false) }
    var editingBan by rememberSaveable { mutableStateOf(false) }

    var deviceBanned by remember { mutableStateOf(false) }
    var deviceBanReason by remember { mutableStateOf("") }
    var applyUidBan by rememberSaveable { mutableStateOf(true) }
    var applyDeviceBan by rememberSaveable { mutableStateOf(true) }

    val history = remember { mutableStateListOf<ModerationEventRow>() }

    var userDocReg by remember { mutableStateOf<ListenerRegistration?>(null) }
    var deviceDocReg by remember { mutableStateOf<ListenerRegistration?>(null) }
    // Tracks which deviceHash the device listener is bound to, so we only
    // re-attach (and re-read the bannedDevices doc) when it actually changes —
    // not on every user-doc snapshot (which fires every ~10s while watching).
    var deviceDocHash by remember { mutableStateOf("") }

    fun clearLoaded() {
        userDocReg?.remove(); userDocReg = null
        deviceDocReg?.remove(); deviceDocReg = null
        deviceDocHash = ""
        loaded = null

        liveWarned = false
        liveBanned = false
        liveWarnReason = ""
        liveBanReason = ""

        editWarnReason = ""
        editBanReason = ""
        editingWarn = false
        editingBan = false

        deviceBanned = false
        deviceBanReason = ""
        history.clear()
    }

    suspend fun resolveUser(input: String): ModerationTarget? {
        val t = input.trim()
        if (t.isBlank()) return null

        // 1) Try docId directly (users/{deviceHash})
        runCatching {
            val doc = db.collection("users").document(t).get().await()
            if (doc.exists()) {
                val authUid = (doc.getString("authUid") ?: doc.getString("uid") ?: "").trim()
                val deviceHash = (doc.getString("deviceHash") ?: "").trim()
                val displayName = (doc.getString("displayName") ?: "").trim()
                return ModerationTarget(docId = t, authUid = authUid, deviceHash = deviceHash, displayName = displayName)
            }
        }

        // 2) Try usersById/{uid} mapping
        runCatching {
            val map = db.collection("usersById").document(t).get().await()
            if (map.exists()) {
                val deviceHash = (map.getString("deviceHash") ?: "").trim()
                val authUid = (map.getString("authUid") ?: t).trim()
                if (deviceHash.isNotBlank()) {
                    val doc = db.collection("users").document(deviceHash).get().await()
                    if (doc.exists()) {
                        val displayName = (doc.getString("displayName") ?: "").trim()
                        return ModerationTarget(docId = deviceHash, authUid = authUid, deviceHash = deviceHash, displayName = displayName)
                    }
                    // mapping exists but user doc missing
                    return ModerationTarget(docId = deviceHash, authUid = authUid, deviceHash = deviceHash, displayName = "")
                }
            }
        }

        // 3) Fallback: lookup by authUid in users collection
        return runCatching {
            val q = db.collection("users")
                .whereEqualTo("authUid", t)
                .limit(1)
                .get()
                .await()

            val doc = q.documents.firstOrNull() ?: return@runCatching null
            val docId = doc.id
            val authUid = (doc.getString("authUid") ?: doc.getString("uid") ?: "").trim()
            val deviceHash = (doc.getString("deviceHash") ?: "").trim()
            val displayName = (doc.getString("displayName") ?: "").trim()
            ModerationTarget(docId = docId, authUid = authUid, deviceHash = deviceHash, displayName = displayName)
        }.getOrNull()
    }

    suspend fun loadHistoryNoIndex(target: ModerationTarget) {
        // No orderBy here -> no composite index required
        val snap = db.collection("moderationEvents")
            .whereEqualTo("targetDocId", target.docId)
            .limit(200)
            .get()
            .await()

        val rows = snap.documents.map { d ->
            ModerationEventRow(
                id = d.id,
                action = d.getString("action") ?: "",
                reason = d.getString("reason") ?: "",
                createdAt = d.getTimestamp("createdAt"),
                byDeviceHash = d.getString("byDeviceHash") ?: "",
                byUid = d.getString("byUid") ?: "",
                byAppId = d.getString("byAppId") ?: "",
                targetDocId = d.getString("targetDocId") ?: (d.getString("targetUid") ?: ""),
                targetAuthUid = d.getString("targetAuthUid") ?: "",
                targetDeviceHash = d.getString("targetDeviceHash") ?: ""
            )
        }.sortedByDescending { it.createdAt?.seconds ?: 0L }

        history.clear()
        history.addAll(rows)
    }

    fun attachLiveTarget(target: ModerationTarget) {
        userDocReg?.remove(); userDocReg = null
        deviceDocReg?.remove(); deviceDocReg = null
        deviceDocHash = ""

        userDocReg = db.collection("users").document(target.docId)
            .addSnapshotListener { snap, e ->
                if (e != null) {
                    setError(e.message ?: "Moderation live user update failed")
                    return@addSnapshotListener
                }
                if (snap == null || !snap.exists()) return@addSnapshotListener

                liveWarned = snap.getBoolean("warned") ?: false
                liveBanned = snap.getBoolean("banned") ?: false
                liveWarnReason = (snap.getString("warnReason") ?: "").trim()
                liveBanReason = (snap.getString("banReason") ?: "").trim()

                // Only refresh the editor if user is NOT actively editing
                if (!editingWarn) editWarnReason = liveWarnReason
                if (!editingBan) editBanReason = liveBanReason

                val dh = (snap.getString("deviceHash") ?: target.deviceHash).trim()
                if (dh.isNotBlank() && dh != deviceDocHash) {
                    deviceDocReg?.remove()
                    deviceDocHash = dh
                    deviceDocReg = db.collection("bannedDevices").document(dh)
                        .addSnapshotListener { ds, de ->
                            if (de != null) {
                                setError(de.message ?: "Moderation live device update failed")
                                return@addSnapshotListener
                            }
                            if (ds == null || !ds.exists()) {
                                deviceBanned = false
                                deviceBanReason = ""
                                return@addSnapshotListener
                            }
                            deviceBanned = ds.getBoolean("banned") ?: false
                            deviceBanReason = (ds.getString("reason") ?: "").trim()
                        }
                } else {
                    deviceBanned = false
                    deviceBanReason = ""
                }
            }
    }

    suspend fun loadTarget(target: ModerationTarget) {
        setGlobalLoading(true)
        setError(null)
        runCatching {
            loaded = target
            // reset editing flags for a new target so fields behave
            editingWarn = false
            editingBan = false
            attachLiveTarget(target)
            loadHistoryNoIndex(target)
            setGlobalLoading(false)
        }.onFailure { e ->
            setGlobalLoading(false)
            setError(e.message ?: "Failed to load moderation target")
        }
    }

    suspend fun writeEvent(target: ModerationTarget, action: String, reason: String) {
        runCatching {
            val data = hashMapOf(
                "uid" to target.docId,
                "targetUid" to target.docId,
                "targetDocId" to target.docId,
                "targetAuthUid" to target.authUid,
                "targetDeviceHash" to target.deviceHash,
                "action" to action,
                "reason" to reason.trim(),
                "createdAt" to FieldValue.serverTimestamp(),
                "byDeviceHash" to byDeviceHash,
                "byUid" to myUid,
                "byAppId" to byAppId
            )
            db.collection("moderationEvents").add(data).await()
        }.onFailure { e ->
            setError("Event write failed: ${e.message ?: "unknown"}")
        }
    }

    LaunchedEffect(initialTarget?.docId) {
        val t = initialTarget ?: return@LaunchedEffect
        lookup = t.docId
        loadTarget(t)
        onClearInitialTarget()
    }

    DisposableEffect(Unit) {
        onDispose {
            userDocReg?.remove()
            deviceDocReg?.remove()
        }
    }

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
                AdminCardHeader("Moderation", Icons.Filled.Gavel, AdminTone.Error)
                Text(
                    "Lookup accepts docId OR authUid.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = lookup,
                    onValueChange = { lookup = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("docId or authUid") },
                    placeholder = { Text("paste here") }
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                setGlobalLoading(true)
                                setError(null)
                                val t = resolveUser(lookup)
                                setGlobalLoading(false)
                                if (t == null) setError("No matching user found for: ${lookup.trim()}")
                                else loadTarget(t)
                            }
                        },
                        enabled = lookup.trim().isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) { Text("Load") }

                    OutlinedButton(
                        onClick = { clearLoaded() },
                        modifier = Modifier.weight(1f)
                    ) { Text("Clear") }
                }
            }
        }

        val t = loaded
        if (t == null) {
            Text("No user loaded.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            return
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AdminAvatar(name = t.displayName.ifBlank { t.docId }, online = false, size = 40)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(t.displayName.ifBlank { "(no name)" },
                            style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (liveBanned) StatusPill("BANNED", AdminTone.Error)
                            if (liveWarned) StatusPill("WARNED", AdminTone.Warn)
                            if (deviceBanned) StatusPill("DEVICE BAN", AdminTone.Error)
                            if (!liveBanned && !liveWarned && !deviceBanned)
                                StatusPill("CLEAN", AdminTone.Success)
                        }
                    }
                }

                AdminLabeledRow("docId", t.docId, mono = true, labelWidth = 72)
                AdminLabeledRow("authUid", t.authUid.ifBlank { "(blank)" }, mono = true, labelWidth = 72)
                AdminLabeledRow("device", t.deviceHash.ifBlank { "(blank)" }, mono = true, labelWidth = 72)
                if (deviceBanned && deviceBanReason.isNotBlank())
                    AdminLabeledRow("dev. reason", deviceBanReason, labelWidth = 72)

                OutlinedButton(
                    onClick = { scope.launch { loadTarget(t) } },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Reload (history + live)") }
            }
        }

        // WARN
        ElevatedCard {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AdminCardHeader("Warn", Icons.Filled.Warning, AdminTone.Warn)

                OutlinedTextField(
                    value = editWarnReason,
                    onValueChange = {
                        editingWarn = true
                        editWarnReason = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    label = { Text("Warn reason (shown to user)") }
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                setGlobalLoading(true)
                                setError(null)

                                runCatching {
                                    val reason = editWarnReason.trim()
                                    db.collection("users").document(t.docId)
                                        .set(
                                            mapOf(
                                                "warned" to true,
                                                "warnReason" to reason,
                                                "updatedAt" to FieldValue.serverTimestamp()
                                            ),
                                            SetOptions.merge()
                                        )
                                        .await()
                                    writeEvent(t, "warn", reason)
                                    loadHistoryNoIndex(t)

                                    editingWarn = false
                                    setGlobalLoading(false)
                                }.onFailure { e ->
                                    setGlobalLoading(false)
                                    setError(e.message ?: "Failed to warn")
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Apply warn") }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                setGlobalLoading(true)
                                setError(null)

                                runCatching {
                                    db.collection("users").document(t.docId)
                                        .set(
                                            mapOf(
                                                "warned" to false,
                                                "warnReason" to "",
                                                "updatedAt" to FieldValue.serverTimestamp()
                                            ),
                                            SetOptions.merge()
                                        )
                                        .await()
                                    writeEvent(t, "remove_warn", "")
                                    loadHistoryNoIndex(t)

                                    // reset editor immediately too
                                    editWarnReason = ""
                                    editingWarn = false

                                    setGlobalLoading(false)
                                }.onFailure { e ->
                                    setGlobalLoading(false)
                                    setError(e.message ?: "Failed to remove warn")
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Remove warn") }
                }

                // quick "use live" in case you're mid-edit and want to discard
                if (editingWarn) {
                    OutlinedButton(
                        onClick = {
                            editWarnReason = liveWarnReason
                            editingWarn = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Discard typing (use live)") }
                }
            }
        }

        // BAN
        ElevatedCard {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AdminCardHeader("Ban", Icons.Filled.Block, AdminTone.Error)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Apply UID")
                        Switch(checked = applyUidBan, onCheckedChange = { applyUidBan = it })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Apply Device")
                        Switch(
                            checked = applyDeviceBan,
                            onCheckedChange = { applyDeviceBan = it },
                            enabled = t.deviceHash.isNotBlank()
                        )
                    }
                }

                OutlinedTextField(
                    value = editBanReason,
                    onValueChange = {
                        editingBan = true
                        editBanReason = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    label = { Text("Ban reason (shown to user)") }
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                val reason = editBanReason.trim()
                                setGlobalLoading(true)
                                setError(null)

                                runCatching {
                                    if (applyUidBan) {
                                        db.collection("users").document(t.docId)
                                            .set(
                                                mapOf(
                                                    "banned" to true,
                                                    "banReason" to reason,
                                                    "updatedAt" to FieldValue.serverTimestamp()
                                                ),
                                                SetOptions.merge()
                                            )
                                            .await()
                                    }

                                    if (applyDeviceBan) {
                                        if (t.deviceHash.isBlank()) {
                                            throw IllegalStateException("Cannot device-ban: deviceHash missing.")
                                        }
                                        db.collection("bannedDevices").document(t.deviceHash)
                                            .set(
                                                mapOf(
                                                    "banned" to true,
                                                    "reason" to reason,
                                                    "updatedAt" to FieldValue.serverTimestamp(),
                                                    "updatedByUid" to myUid,
                                                    "updatedByDeviceHash" to byDeviceHash,
                                                    "updatedByAppId" to byAppId
                                                ),
                                                SetOptions.merge()
                                            )
                                            .await()
                                    }

                                    writeEvent(t, "ban", reason)
                                    loadHistoryNoIndex(t)

                                    editingBan = false
                                    setGlobalLoading(false)
                                }.onFailure { e ->
                                    setGlobalLoading(false)
                                    setError(e.message ?: "Failed to ban")
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = (applyUidBan || (applyDeviceBan && t.deviceHash.isNotBlank()))
                    ) { Text("Ban") }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                setGlobalLoading(true)
                                setError(null)

                                runCatching {
                                    if (applyUidBan) {
                                        db.collection("users").document(t.docId)
                                            .set(
                                                mapOf(
                                                    "banned" to false,
                                                    "banReason" to "",
                                                    "updatedAt" to FieldValue.serverTimestamp()
                                                ),
                                                SetOptions.merge()
                                            )
                                            .await()
                                    }

                                    if (applyDeviceBan) {
                                        if (t.deviceHash.isBlank()) {
                                            throw IllegalStateException("Cannot device-unban: deviceHash missing.")
                                        }
                                        db.collection("bannedDevices").document(t.deviceHash)
                                            .set(
                                                mapOf(
                                                    "banned" to false,
                                                    "reason" to "",
                                                    "updatedAt" to FieldValue.serverTimestamp(),
                                                    "updatedByUid" to myUid,
                                                    "updatedByDeviceHash" to byDeviceHash,
                                                    "updatedByAppId" to byAppId
                                                ),
                                                SetOptions.merge()
                                            )
                                            .await()
                                    }

                                    writeEvent(t, "unban", "")
                                    loadHistoryNoIndex(t)

                                    editBanReason = ""
                                    editingBan = false

                                    setGlobalLoading(false)
                                }.onFailure { e ->
                                    setGlobalLoading(false)
                                    setError(e.message ?: "Failed to unban")
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = (applyUidBan || (applyDeviceBan && t.deviceHash.isNotBlank()))
                    ) { Text("Unban") }
                }

                if (editingBan) {
                    OutlinedButton(
                        onClick = {
                            editBanReason = liveBanReason
                            editingBan = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Discard typing (use live)") }
                }
            }
        }

        // HISTORY
        ElevatedCard {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AdminCardHeader("History", Icons.Filled.History, AdminTone.Neutral,
                    trailing = {
                        OutlinedButton(onClick = { scope.launch { loadHistoryNoIndex(t) } }) { Text("Reload") }
                    })

                if (history.isEmpty()) {
                    Text("No history found.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        history.forEach { e ->
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                Column(
                                    Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        "${e.action.ifBlank { "(no action)" }}  @  ${formatTimestamp(e.createdAt)}",
                                        fontFamily = FontFamily.Monospace
                                    )
                                    if (e.reason.isNotBlank()) {
                                        Text("reason=${e.reason}", fontFamily = FontFamily.Monospace)
                                    }
                                    if (e.targetDeviceHash.isNotBlank()) {
                                        Text(
                                            "targetDevice=${e.targetDeviceHash.take(16)}...",
                                            fontFamily = FontFamily.Monospace,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    Text(
                                        "byUid=${e.byUid.ifBlank { "?" }}  byDevice=${e.byDeviceHash.take(12).ifBlank { "?" }}...",
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

        Spacer(Modifier.height(10.dp))
    }
}
