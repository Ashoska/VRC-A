// app/src/main/kotlin/com/scrapw/chatbox/AdminScreen.kt
package com.scrapw.chatbox

import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CopyAll
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

/**
 * Owner-only Admin screen.
 *
 * - No device-admin system.
 * - Gate is config/app.ownerUid == your auth uid.
 */
@Composable
fun AdminScreen() {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current

    val db = remember { FirebaseFirestore.getInstance() }
    val auth = remember { FirebaseAuth.getInstance() }

    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun setErr(msg: String?) { error = msg?.trim()?.takeIf { it.isNotBlank() }?.take(4000) }

    // Hard block: should never be reachable on public build.
    if (!BuildConfig.IS_ADMIN_BUILD) {
        Surface {
            LazyColumn(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { Text("Admin", style = MaterialTheme.typography.titleLarge) }
                item { ErrorCard("This page is only available in the Admin build.") }
            }
        }
        return
    }

    val deviceHash = remember { readDeviceHash(ctx) }

    // UID: read cached first
    var myUid by remember { mutableStateOf(readCachedUid(ctx)) }

    // Ensure auth + cache uid if missing
    LaunchedEffect(Unit) {
        if (myUid.isNotBlank()) return@LaunchedEffect
        runCatching {
            if (auth.currentUser == null) auth.signInAnonymously().await()
            val uid = auth.currentUser?.uid.orEmpty()
            if (uid.isNotBlank()) {
                writeCachedUid(ctx, uid)
                myUid = uid
            }
        }.onFailure { e ->
            setErr(e.message ?: "Auth failed while trying to get UID")
        }
    }

    // Owner gate (config/app.ownerUid)
    var ownerChecked by remember { mutableStateOf(false) }
    var ownerUid by remember { mutableStateOf("") }
    var isOwner by remember { mutableStateOf(false) }

    fun refreshOwnerGate() {
        ownerChecked = false
        ownerUid = ""
        isOwner = false

        db.collection("config").document("app").get()
            .addOnSuccessListener { snap ->
                ownerUid = snap.getString("ownerUid") ?: ""
                isOwner = ownerUid.isNotBlank() && myUid.isNotBlank() && ownerUid == myUid
                ownerChecked = true
            }
            .addOnFailureListener { e ->
                setErr(e.message ?: "Failed to load app config")
                ownerChecked = true
            }
    }

    LaunchedEffect(myUid) { refreshOwnerGate() }

    // Gate loading screen
    if (!ownerChecked) {
        Surface {
            LazyColumn(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { Text("Admin", style = MaterialTheme.typography.titleLarge) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator()
                        Text("Checking access…")
                    }
                }
                item {
                    DeviceHashCard(deviceHash, onCopy = {
                        clipboard.setText(AnnotatedString(deviceHash))
                    })
                }
                item {
                    UidCard(myUid, onCopy = {
                        clipboard.setText(AnnotatedString(myUid))
                    })
                }
                if (error != null) item { ErrorCard(error!!) }
            }
        }
        return
    }

    // Access denied
    if (!isOwner) {
        Surface {
            LazyColumn(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { Text("Admin", style = MaterialTheme.typography.titleLarge) }

                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        androidx.compose.foundation.layout.Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("Access denied", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "This account is not the owner.\n\n" +
                                    "UID: ${myUid.ifBlank { "(not available yet)" }}",
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                item {
                    DeviceHashCard(deviceHash, onCopy = { clipboard.setText(AnnotatedString(deviceHash)) })
                }
                item {
                    UidCard(myUid, onCopy = { clipboard.setText(AnnotatedString(myUid)) })
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = {
                            myUid = readCachedUid(ctx).ifBlank { myUid }
                            refreshOwnerGate()
                            setErr(null)
                        }) { Text("Re-check") }

                        if (error != null) OutlinedButton(onClick = { setErr(null) }) { Text("Clear error") }
                    }
                }

                if (error != null) item { ErrorCard(error!!) }
            }
        }
        return
    }

    // =========================
    // MAIN OWNER UI (single scroll)
    // =========================
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    // User directory state (kept at top-level so it can render as items in THIS list)
    val users = remember { mutableStateListOf<UserRow>() }
    var search by rememberSaveable { mutableStateOf("") }
    var filterWarned by rememberSaveable { mutableStateOf(false) }
    var filterBanned by rememberSaveable { mutableStateOf(false) }
    var expandedUid by rememberSaveable { mutableStateOf<String?>(null) }
    var pagingLoading by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    var lastDoc by remember { mutableStateOf<DocumentSnapshot?>(null) }
    val pageSize = 75

    fun loadNextUserPage() {
        if (pagingLoading) return
        if (!hasMore) return

        pagingLoading = true
        setErr(null)

        var q: Query = db.collection("users")
            .orderBy("lastSeenAt", Query.Direction.DESCENDING)
            .limit(pageSize.toLong())

        val ld = lastDoc
        if (ld != null) q = q.startAfter(ld)

        q.get()
            .addOnSuccessListener { snap ->
                val docs = snap.documents
                for (d in docs) {
                    val uid = d.id
                    users.add(
                        UserRow(
                            uid = uid,
                            displayName = (d.getString("displayName") ?: "").trim(),
                            deviceHash = (d.getString("deviceHash") ?: "").trim(),
                            warned = d.getBoolean("warned") ?: false,
                            banned = d.getBoolean("banned") ?: false,
                            lastSeenAt = d.getTimestamp("lastSeenAt")
                        )
                    )
                }

                lastDoc = docs.lastOrNull()
                if (docs.size < pageSize) hasMore = false
                pagingLoading = false
            }
            .addOnFailureListener { e ->
                pagingLoading = false
                setErr(e.message ?: "Failed to load users list")
            }
    }

    fun resetUsersAndLoad() {
        users.clear()
        lastDoc = null
        hasMore = true
        expandedUid = null
        loadNextUserPage()
    }

    LaunchedEffect(Unit) { resetUsersAndLoad() }

    val filteredUsers by remember(search, filterWarned, filterBanned, users.size) {
        derivedStateOf {
            val q = search.trim()
            users.asSequence()
                .filter { row ->
                    if (filterWarned && !row.warned) return@filter false
                    if (filterBanned && !row.banned) return@filter false
                    true
                }
                .filter { row ->
                    if (q.isBlank()) true
                    else row.uid.contains(q, ignoreCase = true) ||
                        row.displayName.contains(q, ignoreCase = true) ||
                        row.deviceHash.contains(q, ignoreCase = true)
                }
                .toList()
        }
    }

    Surface {
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                        Text("Admin", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Device: ${deviceHash.ifBlank { "(blank)" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "UID: ${myUid.ifBlank { "(not available yet)" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (ownerUid.isNotBlank()) {
                            Text(
                                "OwnerUID: $ownerUid",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    IconButton(onClick = {
                        myUid = readCachedUid(ctx).ifBlank { myUid }
                        refreshOwnerGate()
                        resetUsersAndLoad()
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                }
            }

            // Error
            if (error != null) {
                item { ErrorCard(error!!) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { setErr(null) }) { Text("Clear error") }
                    }
                }
            }

            item { Divider() }

            // User directory controls
            item {
                androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("User Directory", style = MaterialTheme.typography.titleMedium)

                    ElevatedCard {
                        androidx.compose.foundation.layout.Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                "Scroll to auto-load more. Tap a user to expand.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            OutlinedTextField(
                                value = search,
                                onValueChange = { search = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("Search UID / displayName / deviceHash") },
                                placeholder = { Text("type to filter…") }
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row {
                                    Text("Warned")
                                    Spacer(Modifier.width(8.dp))
                                    Switch(checked = filterWarned, onCheckedChange = { filterWarned = it })
                                }
                                Row {
                                    Text("Banned")
                                    Spacer(Modifier.width(8.dp))
                                    Switch(checked = filterBanned, onCheckedChange = { filterBanned = it })
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(
                                    onClick = { resetUsersAndLoad() },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Refresh") }

                                Button(
                                    onClick = { loadNextUserPage() },
                                    enabled = hasMore && !pagingLoading,
                                    modifier = Modifier.weight(1f)
                                ) { Text(if (pagingLoading) "Loading…" else "Load more") }
                            }

                            Text(
                                "Loaded: ${users.size}   Showing: ${filteredUsers.size}   More: ${if (hasMore) "yes" else "no"}",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            // User rows (as items in THIS list)
            if (filteredUsers.isEmpty()) {
                item {
                    Text("No users loaded/matching filters yet.", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                itemsIndexed(filteredUsers, key = { _, u -> u.uid }) { index, u ->
                    if (hasMore && !pagingLoading && index >= filteredUsers.size - 12) loadNextUserPage()

                    val isExpanded = expandedUid == u.uid
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize()
                            .clickable { expandedUid = if (isExpanded) null else u.uid }
                    ) {
                        androidx.compose.foundation.layout.Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(u.uid, fontFamily = FontFamily.Monospace)
                                Text(if (isExpanded) "▾" else "▸", style = MaterialTheme.typography.titleMedium)
                            }

                            Text(
                                "displayName=${u.displayName.ifBlank { "(blank)" }}",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )

                            Text(
                                "deviceHash=${u.deviceHash.take(16).ifBlank { "(blank)" }}…",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )

                            Text(
                                "warned=${u.warned}  banned=${u.banned}",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )

                            if (isExpanded) {
                                Divider()
                                Text(
                                    "lastSeenAt=${u.lastSeenAt ?: "?"}",
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "Tip: copy UID/deviceHash and paste into Moderation.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                item {
                    if (pagingLoading) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }

            item { Divider() }

            // Moderation
            item {
                AdminModerationSection(
                    db = db,
                    myUid = myUid,
                    byDeviceHash = deviceHash,
                    byAppId = BuildConfig.APPLICATION_ID,
                    setLoading = { loading = it },
                    setError = ::setErr
                )
            }

            item { Divider() }

            // Announcements
            item {
                AdminAnnouncementsSection(
                    db = db,
                    createdByDevice = deviceHash,
                    setLoading = { loading = it },
                    setError = ::setErr
                )
            }

            item { Divider() }

            // ToS / Config
            item {
                AdminTosConfigSection(
                    db = db,
                    setLoading = { loading = it },
                    setError = ::setErr
                )
            }

            // Global loading spinner
            if (loading) {
                item {
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                    }
                }
            }

            item { Spacer(Modifier.height(30.dp)) }
        }
    }
}

/* =========================================================
   Prefs helpers
   ========================================================= */

private fun readDeviceHash(ctx: Context): String {
    val prefs = ctx.getSharedPreferences("vrca_remote", Context.MODE_PRIVATE)
    return prefs.getString("device_id_hash", "")?.trim().orEmpty()
}

private fun readCachedUid(ctx: Context): String {
    val prefs = ctx.getSharedPreferences("vrca_remote", Context.MODE_PRIVATE)
    return prefs.getString("auth_uid", "")?.trim().orEmpty()
}

private fun writeCachedUid(ctx: Context, uid: String) {
    ctx.getSharedPreferences("vrca_remote", Context.MODE_PRIVATE)
        .edit()
        .putString("auth_uid", uid.trim())
        .apply()
}

/* =========================================================
   Common UI
   ========================================================= */

@Composable
private fun DeviceHashCard(deviceHash: String, onCopy: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        androidx.compose.foundation.layout.Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Device", style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = onCopy) { Icon(Icons.Filled.CopyAll, contentDescription = "Copy") }
            }
            Text(
                deviceHash.ifBlank { "(blank)" },
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun UidCard(uid: String, onCopy: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        androidx.compose.foundation.layout.Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("UID", style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = onCopy) { Icon(Icons.Filled.CopyAll, contentDescription = "Copy") }
            }
            Text(uid.ifBlank { "(not available yet)" }, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        androidx.compose.foundation.layout.Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Error", style = MaterialTheme.typography.titleSmall)
            Text(message, fontFamily = FontFamily.Monospace)
        }
    }
}

/* =========================================================
   User Directory models
   ========================================================= */

private data class UserRow(
    val uid: String,
    val displayName: String,
    val deviceHash: String,
    val warned: Boolean,
    val banned: Boolean,
    val lastSeenAt: Timestamp?
)

/* =========================================================
   Moderation + History (unchanged logic, but safe in single scroll)
   ========================================================= */

private data class ModerationEventRow(
    val id: String,
    val action: String,
    val reason: String,
    val createdAt: Timestamp?,
    val byDeviceHash: String,
    val byUid: String,
    val byAppId: String,
    val targetUid: String,
    val targetDeviceHash: String
)

@Composable
private fun AdminModerationSection(
    db: FirebaseFirestore,
    myUid: String,
    byDeviceHash: String,
    byAppId: String,
    setLoading: (Boolean) -> Unit,
    setError: (String?) -> Unit
) {
    val clipboard = LocalClipboardManager.current

    var lookupUid by remember { mutableStateOf("") }
    var loadedUid by remember { mutableStateOf<String?>(null) }

    // user doc fields
    var displayName by remember { mutableStateOf("") }
    var userDeviceHash by remember { mutableStateOf("") }
    var warned by remember { mutableStateOf(false) }
    var banned by remember { mutableStateOf(false) }
    var warnReason by remember { mutableStateOf("") }
    var banReason by remember { mutableStateOf("") }
    var updatedAt by remember { mutableStateOf<Timestamp?>(null) }
    var lastSeenAt by remember { mutableStateOf<Timestamp?>(null) }

    // device ban doc
    var deviceBanned by remember { mutableStateOf(false) }
    var deviceBanReason by remember { mutableStateOf("") }
    var deviceBanUpdatedAt by remember { mutableStateOf<Timestamp?>(null) }

    // UI toggles
    var applyUidBan by rememberSaveable { mutableStateOf(true) }
    var applyDeviceBan by rememberSaveable { mutableStateOf(true) }

    val history = remember { mutableStateListOf<ModerationEventRow>() }

    fun clearLoaded() {
        loadedUid = null
        displayName = ""
        userDeviceHash = ""
        warned = false
        banned = false
        warnReason = ""
        banReason = ""
        updatedAt = null
        lastSeenAt = null
        deviceBanned = false
        deviceBanReason = ""
        deviceBanUpdatedAt = null
        history.clear()
    }

    fun refreshHistory(uid: String) {
        val u = uid.trim()
        if (u.isBlank()) return
        setLoading(true)
        setError(null)

        db.collection("moderationEvents")
            .whereEqualTo("uid", u)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(60)
            .get()
            .addOnSuccessListener { snap ->
                history.clear()
                for (d in snap.documents) {
                    history.add(
                        ModerationEventRow(
                            id = d.id,
                            action = d.getString("action") ?: "",
                            reason = d.getString("reason") ?: "",
                            createdAt = d.getTimestamp("createdAt"),
                            byDeviceHash = d.getString("byDeviceHash") ?: "",
                            byUid = d.getString("byUid") ?: "",
                            byAppId = d.getString("byAppId") ?: "",
                            targetUid = d.getString("targetUid") ?: (d.getString("uid") ?: ""),
                            targetDeviceHash = d.getString("targetDeviceHash") ?: ""
                        )
                    )
                }
                setLoading(false)
            }
            .addOnFailureListener { e ->
                setLoading(false)
                setError(e.message ?: "Failed to load moderation history")
            }
    }

    fun load(uid: String) {
        val u = uid.trim()
        if (u.isBlank()) return
        setLoading(true)
        setError(null)

        db.collection("users").document(u).get()
            .addOnSuccessListener { snap ->
                loadedUid = u
                displayName = snap.getString("displayName") ?: ""
                userDeviceHash = (snap.getString("deviceHash") ?: "").trim()

                warned = snap.getBoolean("warned") ?: false
                banned = snap.getBoolean("banned") ?: false
                warnReason = snap.getString("warnReason") ?: ""
                banReason = snap.getString("banReason") ?: ""
                updatedAt = snap.getTimestamp("updatedAt")
                lastSeenAt = snap.getTimestamp("lastSeenAt")

                if (userDeviceHash.isNotBlank()) {
                    db.collection("bannedDevices").document(userDeviceHash).get()
                        .addOnSuccessListener { ds ->
                            deviceBanned = ds.getBoolean("banned") ?: false
                            deviceBanReason = ds.getString("reason") ?: ""
                            deviceBanUpdatedAt = ds.getTimestamp("updatedAt")
                            setLoading(false)
                            refreshHistory(u)
                        }
                        .addOnFailureListener { e ->
                            setLoading(false)
                            setError(e.message ?: "Failed to load device ban")
                            refreshHistory(u)
                        }
                } else {
                    setLoading(false)
                    refreshHistory(u)
                }
            }
            .addOnFailureListener { e ->
                setLoading(false)
                setError(e.message ?: "Failed to load user")
            }
    }

    fun writeEvent(
        targetUid: String,
        targetDeviceHash: String,
        action: String,
        reason: String
    ) {
        val data = hashMapOf(
            "uid" to targetUid.trim(), // legacy query field
            "targetUid" to targetUid.trim(),
            "targetDeviceHash" to targetDeviceHash.trim(),
            "action" to action,
            "reason" to reason.trim(),
            "createdAt" to FieldValue.serverTimestamp(),
            "byDeviceHash" to byDeviceHash,
            "byUid" to myUid,
            "byAppId" to byAppId
        )
        db.collection("moderationEvents")
            .add(data)
            .addOnFailureListener { e ->
                setError("Event write failed: ${e.message ?: "unknown"}")
            }
    }

    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Moderation", style = MaterialTheme.typography.titleMedium)

        ElevatedCard {
            androidx.compose.foundation.layout.Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Lookup user (by UID)", style = MaterialTheme.typography.titleSmall)

                OutlinedTextField(
                    value = lookupUid,
                    onValueChange = { lookupUid = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("User UID") },
                    placeholder = { Text("paste uid here") }
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { load(lookupUid) },
                        enabled = lookupUid.trim().isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) { Text("Load") }

                    OutlinedButton(onClick = { clearLoaded() }, modifier = Modifier.weight(1f)) { Text("Clear") }
                }
            }
        }

        if (loadedUid == null) {
            Text("No user loaded.", style = MaterialTheme.typography.bodySmall)
            return
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            androidx.compose.foundation.layout.Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("User: $loadedUid", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(loadedUid!!)) }) { Text("Copy UID") }
                        if (userDeviceHash.isNotBlank()) {
                            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(userDeviceHash)) }) { Text("Copy device") }
                        }
                    }
                }

                Text("displayName=$displayName", fontFamily = FontFamily.Monospace)
                Text("deviceHash=${userDeviceHash.ifBlank { "(blank)" }}", fontFamily = FontFamily.Monospace)
                Text("warned=$warned  uidBanned=$banned", fontFamily = FontFamily.Monospace)
                Text("deviceBanned=$deviceBanned", fontFamily = FontFamily.Monospace)
                Text("lastSeenAt=${lastSeenAt ?: "?"}", fontFamily = FontFamily.Monospace)
                Text("updatedAt=${updatedAt ?: "?"}", fontFamily = FontFamily.Monospace)
                if (userDeviceHash.isNotBlank()) {
                    Text("deviceBanUpdatedAt=${deviceBanUpdatedAt ?: "?"}", fontFamily = FontFamily.Monospace)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { load(loadedUid!!) }, modifier = Modifier.weight(1f)) { Text("Reload") }
                    OutlinedButton(onClick = { refreshHistory(loadedUid!!) }, modifier = Modifier.weight(1f)) { Text("Refresh history") }
                }
            }
        }

        // WARN (UID only)
        ElevatedCard {
            androidx.compose.foundation.layout.Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Warn (UID)", style = MaterialTheme.typography.titleSmall)

                OutlinedTextField(
                    value = warnReason,
                    onValueChange = { warnReason = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    label = { Text("Warn reason (shown to user)") }
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            val uid = loadedUid ?: return@Button
                            setLoading(true); setError(null)

                            db.collection("users").document(uid)
                                .set(
                                    mapOf(
                                        "warned" to true,
                                        "warnReason" to warnReason.trim(),
                                        "updatedAt" to FieldValue.serverTimestamp()
                                    ),
                                    SetOptions.merge()
                                )
                                .addOnSuccessListener {
                                    setLoading(false)
                                    writeEvent(uid, userDeviceHash, "warn_uid", warnReason)
                                    load(uid)
                                }
                                .addOnFailureListener { e ->
                                    setLoading(false); setError(e.message ?: "Failed to warn")
                                }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Apply warn") }

                    OutlinedButton(
                        onClick = {
                            val uid = loadedUid ?: return@OutlinedButton
                            setLoading(true); setError(null)

                            db.collection("users").document(uid)
                                .set(
                                    mapOf(
                                        "warned" to false,
                                        "warnReason" to "",
                                        "updatedAt" to FieldValue.serverTimestamp()
                                    ),
                                    SetOptions.merge()
                                )
                                .addOnSuccessListener {
                                    setLoading(false)
                                    writeEvent(uid, userDeviceHash, "clear_warn_uid", "")
                                    load(uid)
                                }
                                .addOnFailureListener { e ->
                                    setLoading(false); setError(e.message ?: "Failed to clear warn")
                                }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Clear warn") }
                }
            }
        }

        // BAN (UID and/or device)
        ElevatedCard {
            androidx.compose.foundation.layout.Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Ban", style = MaterialTheme.typography.titleSmall)

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row {
                        Text("Apply UID ban")
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = applyUidBan, onCheckedChange = { applyUidBan = it })
                    }
                    Row {
                        Text("Apply Device ban")
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = applyDeviceBan,
                            onCheckedChange = { applyDeviceBan = it },
                            enabled = userDeviceHash.isNotBlank()
                        )
                    }
                }

                OutlinedTextField(
                    value = banReason,
                    onValueChange = { banReason = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    label = { Text("Ban reason (shown to user)") }
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            val uid = loadedUid ?: return@Button
                            val reason = banReason.trim()
                            val dh = userDeviceHash.trim()

                            setLoading(true); setError(null)

                            fun doUidBan(next: () -> Unit) {
                                if (!applyUidBan) return next()
                                db.collection("users").document(uid)
                                    .set(
                                        mapOf(
                                            "banned" to true,
                                            "banReason" to reason,
                                            "updatedAt" to FieldValue.serverTimestamp()
                                        ),
                                        SetOptions.merge()
                                    )
                                    .addOnSuccessListener { next() }
                                    .addOnFailureListener { e ->
                                        setLoading(false); setError(e.message ?: "Failed to UID-ban")
                                    }
                            }

                            fun doDeviceBan(done: () -> Unit) {
                                if (!applyDeviceBan) return done()
                                if (dh.isBlank()) {
                                    setLoading(false)
                                    setError("Cannot device-ban: deviceHash missing.")
                                    return
                                }
                                db.collection("bannedDevices").document(dh)
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
                                    .addOnSuccessListener { done() }
                                    .addOnFailureListener { e ->
                                        setLoading(false); setError(e.message ?: "Failed to device-ban")
                                    }
                            }

                            doUidBan {
                                doDeviceBan {
                                    setLoading(false)
                                    writeEvent(uid, dh, "ban", reason)
                                    load(uid)
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = (applyUidBan || (applyDeviceBan && userDeviceHash.isNotBlank()))
                    ) { Text("Ban") }

                    OutlinedButton(
                        onClick = {
                            val uid = loadedUid ?: return@OutlinedButton
                            val dh = userDeviceHash.trim()

                            setLoading(true); setError(null)

                            fun doUidUnban(next: () -> Unit) {
                                if (!applyUidBan) return next()
                                db.collection("users").document(uid)
                                    .set(
                                        mapOf(
                                            "banned" to false,
                                            "banReason" to "",
                                            "updatedAt" to FieldValue.serverTimestamp()
                                        ),
                                        SetOptions.merge()
                                    )
                                    .addOnSuccessListener { next() }
                                    .addOnFailureListener { e ->
                                        setLoading(false); setError(e.message ?: "Failed to UID-unban")
                                    }
                            }

                            fun doDeviceUnban(done: () -> Unit) {
                                if (!applyDeviceBan) return done()
                                if (dh.isBlank()) {
                                    setLoading(false)
                                    setError("Cannot device-unban: deviceHash missing.")
                                    return
                                }
                                db.collection("bannedDevices").document(dh)
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
                                    .addOnSuccessListener { done() }
                                    .addOnFailureListener { e ->
                                        setLoading(false); setError(e.message ?: "Failed to device-unban")
                                    }
                            }

                            doUidUnban {
                                doDeviceUnban {
                                    setLoading(false)
                                    writeEvent(uid, dh, "unban", "")
                                    load(uid)
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = (applyUidBan || (applyDeviceBan && userDeviceHash.isNotBlank()))
                    ) { Text("Unban") }
                }
            }
        }

        // History (rendered as a simple column to avoid nested scroll fights)
        ElevatedCard {
            androidx.compose.foundation.layout.Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("History", style = MaterialTheme.typography.titleSmall)

                if (history.isEmpty()) {
                    Text("No history found.", style = MaterialTheme.typography.bodySmall)
                } else {
                    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        history.forEach { e ->
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                androidx.compose.foundation.layout.Column(
                                    Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        "${e.action.ifBlank { "(no action)" }}  @  ${e.createdAt ?: "?"}",
                                        fontFamily = FontFamily.Monospace
                                    )
                                    if (e.reason.isNotBlank()) Text("reason=${e.reason}", fontFamily = FontFamily.Monospace)
                                    if (e.targetDeviceHash.isNotBlank()) {
                                        Text(
                                            "targetDevice=${e.targetDeviceHash.take(16)}…",
                                            fontFamily = FontFamily.Monospace,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    Text(
                                        "byUid=${e.byUid.ifBlank { "?" }}  byDevice=${e.byDeviceHash.take(12).ifBlank { "?" }}…",
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

/* =========================================================
   Announcements (kept, single-scroll friendly)
   ========================================================= */

private data class AnnouncementRow(
    val id: String,
    val title: String,
    val body: String,
    val active: Boolean,
    val priority: Int,
    val createdAt: Timestamp?
)

@Composable
private fun AdminAnnouncementsSection(
    db: FirebaseFirestore,
    createdByDevice: String,
    setLoading: (Boolean) -> Unit,
    setError: (String?) -> Unit
) {
    val announcements = remember { mutableStateListOf<AnnouncementRow>() }

    fun refresh() {
        setLoading(true)
        setError(null)
        db.collection("announcements")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(30)
            .get()
            .addOnSuccessListener { snap ->
                announcements.clear()
                for (d in snap.documents) {
                    announcements.add(
                        AnnouncementRow(
                            id = d.id,
                            title = d.getString("title") ?: "",
                            body = d.getString("body") ?: "",
                            active = d.getBoolean("active") ?: true,
                            priority = (d.getLong("priority") ?: 0L).toInt(),
                            createdAt = d.getTimestamp("createdAt")
                        )
                    )
                }
                setLoading(false)
            }
            .addOnFailureListener { e ->
                setLoading(false)
                setError(e.message ?: "Failed to load announcements")
            }
    }

    var newTitle by remember { mutableStateOf("") }
    var newBody by remember { mutableStateOf("") }
    var newActive by remember { mutableStateOf(true) }
    var newPriority by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { refresh() }

    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Announcements", style = MaterialTheme.typography.titleMedium)

        ElevatedCard {
            androidx.compose.foundation.layout.Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Create", style = MaterialTheme.typography.titleSmall)

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
                    Row {
                        Text("Active")
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = newActive, onCheckedChange = { newActive = it })
                    }

                    Row {
                        Text("Priority")
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { newPriority = (newPriority - 1).coerceIn(-5, 5) }) { Text("−") }
                        Spacer(Modifier.width(8.dp))
                        Text(newPriority.toString(), fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { newPriority = (newPriority + 1).coerceIn(-5, 5) }) { Text("+") }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            setLoading(true)
                            setError(null)
                            val data = hashMapOf(
                                "title" to newTitle.trim(),
                                "body" to newBody.trim(),
                                "active" to newActive,
                                "priority" to newPriority,
                                "createdAt" to FieldValue.serverTimestamp(),
                                "createdByDevice" to createdByDevice,
                                "createdByAppId" to BuildConfig.APPLICATION_ID
                            )
                            db.collection("announcements").add(data)
                                .addOnSuccessListener {
                                    setLoading(false)
                                    newTitle = ""
                                    newBody = ""
                                    newActive = true
                                    newPriority = 0
                                    refresh()
                                }
                                .addOnFailureListener { e ->
                                    setLoading(false)
                                    setError(e.message ?: "Failed to publish")
                                }
                        },
                        enabled = newTitle.isNotBlank() && newBody.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) { Text("Publish") }

                    OutlinedButton(onClick = { refresh() }, modifier = Modifier.weight(1f)) { Text("Refresh list") }
                }
            }
        }

        if (announcements.isEmpty()) {
            Text("No announcements yet.", style = MaterialTheme.typography.bodySmall)
        } else {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                announcements.forEach { a ->
                    Card {
                        androidx.compose.foundation.layout.Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(a.title.ifBlank { "(no title)" }, style = MaterialTheme.typography.titleSmall)
                                Text(if (a.active) "ACTIVE" else "OFF", style = MaterialTheme.typography.labelLarge)
                            }
                            Text(
                                "priority=${a.priority}  createdAt=${a.createdAt ?: "?"}",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(a.body, style = MaterialTheme.typography.bodyMedium)

                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        setLoading(true)
                                        setError(null)
                                        db.collection("announcements").document(a.id)
                                            .update(
                                                "active", !(a.active),
                                                "updatedAt", FieldValue.serverTimestamp()
                                            )
                                            .addOnSuccessListener { setLoading(false); refresh() }
                                            .addOnFailureListener { e ->
                                                setLoading(false)
                                                setError(e.message ?: "Failed to toggle")
                                            }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text(if (a.active) "Disable" else "Enable") }

                                OutlinedButton(
                                    onClick = {
                                        setLoading(true)
                                        setError(null)
                                        db.collection("announcements").document(a.id)
                                            .delete()
                                            .addOnSuccessListener { setLoading(false); refresh() }
                                            .addOnFailureListener { e ->
                                                setLoading(false)
                                                setError(e.message ?: "Failed to delete")
                                            }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        }
    }
}

/* =========================================================
   ToS / Config (adds safe template button so it "prefills")
   ========================================================= */

private const val DEFAULT_TOS_TEXT: String =
    "Terms of Service (Summary)\n" +
        "\n" +
        "By using this app, you agree to the following:\n" +
        "1) You are responsible for how you use the app.\n" +
        "2) Do not use the app to harass, threaten, or abuse others.\n" +
        "3) Do not attempt to bypass restrictions or access areas you are not permitted to.\n" +
        "4) The app may store basic app state for functionality and moderation.\n" +
        "5) Access may be restricted for misuse.\n" +
        "\n" +
        "This text is a short in-app notice. If you publish a full policy page later, set the URL field and keep this short summary."

@Composable
private fun AdminTosConfigSection(
    db: FirebaseFirestore,
    setLoading: (Boolean) -> Unit,
    setError: (String?) -> Unit
) {
    var tosVersion by remember { mutableIntStateOf(1) }
    var tosText by remember { mutableStateOf("") }
    var tosUrl by remember { mutableStateOf("") }
    var ownerUid by remember { mutableStateOf("") }
    var loadedAt by remember { mutableStateOf<Timestamp?>(null) }

    fun load() {
        setLoading(true)
        setError(null)
        db.collection("config").document("app").get()
            .addOnSuccessListener { snap ->
                tosVersion = (snap.getLong("tosVersion") ?: 1L).toInt().coerceAtLeast(1)
                tosText = snap.getString("tosText") ?: ""
                tosUrl = snap.getString("tosUrl") ?: ""
                ownerUid = snap.getString("ownerUid") ?: ""
                loadedAt = snap.getTimestamp("updatedAt")
                setLoading(false)
            }
            .addOnFailureListener { e ->
                setLoading(false)
                setError(e.message ?: "Failed to load config")
            }
    }

    LaunchedEffect(Unit) { load() }

    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("ToS / App Config", style = MaterialTheme.typography.titleMedium)

        ElevatedCard {
            androidx.compose.foundation.layout.Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Current config", style = MaterialTheme.typography.titleSmall)
                Text("loadedAt=${loadedAt ?: "?"}", fontFamily = FontFamily.Monospace)

                OutlinedTextField(
                    value = ownerUid,
                    onValueChange = { ownerUid = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Owner UID") },
                    placeholder = { Text("paste your UID here") }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("ToS version", style = MaterialTheme.typography.labelLarge)
                    Row {
                        OutlinedButton(onClick = { tosVersion = (tosVersion - 1).coerceAtLeast(1) }) { Text("−") }
                        Spacer(Modifier.width(10.dp))
                        Text(tosVersion.toString(), fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.width(10.dp))
                        OutlinedButton(onClick = { tosVersion += 1 }) { Text("+") }
                    }
                }

                OutlinedTextField(
                    value = tosUrl,
                    onValueChange = { tosUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("ToS URL (optional)") },
                    placeholder = { Text("https://…") }
                )

                OutlinedTextField(
                    value = tosText,
                    onValueChange = { tosText = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    label = { Text("ToS text (shown in-app)") }
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (tosText.trim().isEmpty()) tosText = DEFAULT_TOS_TEXT
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Use template") }

                    OutlinedButton(
                        onClick = { load() },
                        modifier = Modifier.weight(1f)
                    ) { Text("Reload") }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            setLoading(true)
                            setError(null)
                            val data = hashMapOf(
                                "ownerUid" to ownerUid.trim(),
                                "tosVersion" to tosVersion,
                                "tosText" to tosText,
                                "tosUrl" to tosUrl,
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                            db.collection("config").document("app")
                                .set(data, SetOptions.merge())
                                .addOnSuccessListener { setLoading(false); load() }
                                .addOnFailureListener { e ->
                                    setLoading(false)
                                    setError(e.message ?: "Failed to save config")
                                }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Save") }
                }

                Text(
                    "Increase ToS version to re-prompt users.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
