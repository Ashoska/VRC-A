// app/src/main/kotlin/com/scrapw/chatbox/AdminScreen.kt
package com.scrapw.chatbox

import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await

/**
 * ✅ Device-gated Admin-only screen (NO LOGIN UI).
 *
 * Admin enable:
 * - Firestore: devices/{deviceHash} : { adminEnabled: true, ... }
 *
 * Owner enable (for in-app admin management):
 * - Firestore: config/app : { ownerUid: "<uid>" }
 *
 * Collections:
 * - announcements/{id} : { title, body, active, priority, createdAt, createdByDevice, createdByAppId }
 * - users/{uid} : { displayName, warned, warnReason, banned, banReason, updatedAt, lastSeenAt }
 * - config/app : { tosVersion, tosText, tosUrl, ownerUid, updatedAt }
 * - devices/{deviceHash} : { adminEnabled, note, lastSeenAt, ... }
 *
 * Added:
 * - moderationEvents/{id} : { uid, action, reason, createdAt, byDeviceHash, byUid, byAppId }
 *
 * Added (admin-only UI):
 * - User Directory: paged, filterable list of users/{uid}
 */
@Composable
fun AdminScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val db = remember { FirebaseFirestore.getInstance() }
    val auth = remember { FirebaseAuth.getInstance() }

    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun setErr(msg: String?) { error = msg?.takeIf { it.isNotBlank() }?.take(4000) }

    // Hard block: should never be reachable on public build, but keep it safe anyway.
    if (!BuildConfig.IS_ADMIN_BUILD) {
        Surface {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Admin", style = MaterialTheme.typography.titleLarge)
                ErrorCard("This page is only available in the Admin build.")
            }
        }
        return
    }

    val deviceHash = remember { readDeviceHash(ctx) }

    // ✅ UID: read cached UID first (written by ChatboxApp bootstrap)
    var myUid by remember { mutableStateOf(readCachedUid(ctx)) }

    // If cached UID is blank, try to auth and then cache it
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

    // ---------- Admin gate (device-based) ----------
    var adminChecked by remember { mutableStateOf(false) }
    var isAdmin by remember { mutableStateOf(false) }

    fun refreshAdminGate() {
        adminChecked = false
        isAdmin = false
        setErr(null)

        if (deviceHash.isBlank()) {
            setErr("DeviceHash is blank. (App couldn't read vrca_remote/device_id_hash.)")
            adminChecked = true
            return
        }

        db.collection("devices").document(deviceHash).get()
            .addOnSuccessListener { snap ->
                isAdmin = snap.getBoolean("adminEnabled") ?: false
                adminChecked = true
            }
            .addOnFailureListener { e ->
                setErr(e.message ?: "Failed to check admin status")
                adminChecked = true
            }
    }

    LaunchedEffect(deviceHash) { refreshAdminGate() }

    if (!adminChecked) {
        Surface {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Admin", style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator()
                    Text("Checking permissions…")
                }
                DeviceHashCard(deviceHash)
                UidCard(myUid)
                if (error != null) ErrorCard(error!!)
            }
        }
        return
    }

    if (!isAdmin) {
        Surface {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Admin", style = MaterialTheme.typography.titleLarge)

                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Access denied", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "This device is not enabled as admin.\n\n" +
                                "Enable in Firestore:\n" +
                                "devices/$deviceHash\n" +
                                "{ adminEnabled: true }",
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                DeviceHashCard(deviceHash)
                UidCard(myUid)

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { refreshAdminGate() }) { Text("Re-check") }
                    OutlinedButton(onClick = {
                        // force refresh UID display
                        myUid = readCachedUid(ctx)
                        if (myUid.isBlank()) {
                            setErr("UID not cached yet. Open app Home once (bootstrap), then return here.")
                        }
                    }) { Text("Re-read UID") }
                    if (error != null) OutlinedButton(onClick = { setErr(null) }) { Text("Clear error") }
                }

                if (error != null) ErrorCard(error!!)
            }
        }
        return
    }

    // ---------- Owner gate (config/app.ownerUid) ----------
    var ownerChecked by remember { mutableStateOf(false) }
    var ownerUid by remember { mutableStateOf("") }
    var computedIsOwner by remember { mutableStateOf(false) }

    fun refreshOwnerGate() {
        ownerChecked = false
        ownerUid = ""
        computedIsOwner = false

        db.collection("config").document("app").get()
            .addOnSuccessListener { snap ->
                ownerUid = snap.getString("ownerUid") ?: ""
                computedIsOwner = ownerUid.isNotBlank() && myUid.isNotBlank() && ownerUid == myUid
                ownerChecked = true
            }
            .addOnFailureListener { e ->
                setErr(e.message ?: "Failed to load config/app (ownerUid)")
                ownerChecked = true
            }
    }

    LaunchedEffect(myUid) { refreshOwnerGate() }

    // ---------- Main admin UI ----------
    Surface {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Admin", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Device: $deviceHash",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "UID: ${myUid.ifBlank { "(not available yet)" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (ownerChecked && ownerUid.isNotBlank()) {
                        Text(
                            "OwnerUID: $ownerUid",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                OutlinedButton(onClick = {
                    myUid = readCachedUid(ctx).ifBlank { myUid }
                    refreshAdminGate()
                    refreshOwnerGate()
                }) { Text("Refresh") }
            }

            if (error != null) {
                ErrorCard(error!!)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { setErr(null) }) { Text("Clear error") }
                }
            }

            AdminRulesCard()

            // ✅ Admin-only user directory (paged / lazy / filterable)
            Divider()
            AdminUserDirectorySection(
                db = db,
                setLoading = { loading = it },
                setError = ::setErr
            )

            // OWNER-ONLY: Admin Manager
            if (ownerChecked && computedIsOwner) {
                Divider()
                OwnerAdminManagerSection(
                    db = db,
                    setLoading = { loading = it },
                    setError = ::setErr
                )
            }

            Divider()

            // Announcements
            AdminAnnouncementsSection(
                db = db,
                createdByDevice = deviceHash,
                setLoading = { loading = it },
                setError = ::setErr
            )

            Divider()

            // Moderation + History
            AdminModerationSection(
                db = db,
                myUid = myUid,
                byDeviceHash = deviceHash,
                byAppId = BuildConfig.APPLICATION_ID,
                setLoading = { loading = it },
                setError = ::setErr
            )

            Divider()

            // ToS / Config
            AdminTosConfigSection(
                db = db,
                setLoading = { loading = it },
                setError = ::setErr
            )

            if (loading) {
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            }

            Spacer(Modifier.height(30.dp))
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
private fun DeviceHashCard(deviceHash: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("DeviceHash", style = MaterialTheme.typography.titleSmall)
            Text(
                deviceHash.ifBlank { "(blank — not found in vrca_remote/device_id_hash)" },
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun UidCard(uid: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("UID", style = MaterialTheme.typography.titleSmall)
            Text(uid.ifBlank { "(not available yet)" }, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Error", style = MaterialTheme.typography.titleSmall)
            Text(message, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun AdminRulesCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Rules (current plan)", style = MaterialTheme.typography.titleSmall)
            Text("• Public build: NO writes to devices/ collection.")
            Text("• Admin build: reads/writes announcements + moderation + config.")
            Text("• Admin access: devices/{deviceHash}.adminEnabled == true.")
            Text("• Owner access: config/app.ownerUid == your UID (enables in-app admin manager).")
            Text("• Moderation history: moderationEvents/ collection (append-only).")
            Text("• User directory: paged + lazy list (won’t render everything at once).")
        }
    }
}

/* =========================================================
   Admin User Directory (paged + filter + inline expand)
   ========================================================= */

private data class UserRow(
    val uid: String,
    val displayName: String,
    val warned: Boolean,
    val banned: Boolean,
    val lastSeenAt: Timestamp?
)

@Composable
private fun AdminUserDirectorySection(
    db: FirebaseFirestore,
    setLoading: (Boolean) -> Unit,
    setError: (String?) -> Unit
) {
    val users = remember { mutableStateListOf<UserRow>() }

    var search by rememberSaveable { mutableStateOf("") }
    var filterWarned by rememberSaveable { mutableStateOf(false) }
    var filterBanned by rememberSaveable { mutableStateOf(false) }

    // ✅ Remember expanded user + keep place (no navigation away)
    var expandedUid by rememberSaveable { mutableStateOf<String?>(null) }

    // ✅ Remember scroll position (and restore after expand/collapse)
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    var restoreIndex by rememberSaveable { mutableIntStateOf(0) }
    var restoreOffset by rememberSaveable { mutableIntStateOf(0) }
    var restorePending by rememberSaveable { mutableStateOf(false) }

    // Paging state
    var pagingLoading by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    var lastDoc by remember { mutableStateOf<DocumentSnapshot?>(null) }
    val pageSize = 75

    fun loadNextPage() {
        if (pagingLoading) return
        if (!hasMore) return

        pagingLoading = true
        setError(null)

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
                    val name = (d.getString("displayName") ?: "").trim()
                    val warned = d.getBoolean("warned") ?: false
                    val banned = d.getBoolean("banned") ?: false
                    val lastSeen = d.getTimestamp("lastSeenAt")

                    users.add(
                        UserRow(
                            uid = uid,
                            displayName = name,
                            warned = warned,
                            banned = banned,
                            lastSeenAt = lastSeen
                        )
                    )
                }

                lastDoc = docs.lastOrNull()
                if (docs.size < pageSize) hasMore = false

                pagingLoading = false
            }
            .addOnFailureListener { e ->
                pagingLoading = false
                setError(e.message ?: "Failed to load users list")
            }
    }

    fun resetAndLoadFirstPage() {
        users.clear()
        lastDoc = null
        hasMore = true
        expandedUid = null
        loadNextPage()
    }

    LaunchedEffect(Unit) { resetAndLoadFirstPage() }

    val filtered by remember(search, filterWarned, filterBanned, users.size) {
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
                        row.displayName.contains(q, ignoreCase = true)
                }
                .toList()
        }
    }

    // ✅ Restore scroll after expand/collapse so you keep your place (prevents “jump” feeling)
    LaunchedEffect(expandedUid) {
        if (restorePending) {
            restorePending = false
            runCatching { listState.scrollToItem(restoreIndex, restoreOffset) }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("User Directory (admin)", style = MaterialTheme.typography.titleMedium)

        ElevatedCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Scroll to auto-load more. Tap a user to expand (keeps your place).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Search UID / displayName") },
                    placeholder = { Text("type to filter…") }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Warned")
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = filterWarned, onCheckedChange = { filterWarned = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Banned")
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = filterBanned, onCheckedChange = { filterBanned = it })
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { resetAndLoadFirstPage() },
                        modifier = Modifier.weight(1f)
                    ) { Text("Refresh") }

                    Button(
                        onClick = { loadNextPage() },
                        enabled = hasMore && !pagingLoading,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (pagingLoading) "Loading…" else "Load more") }
                }

                Text(
                    "Loaded: ${users.size}   Showing: ${filtered.size}   More: ${if (hasMore) "yes" else "no"}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (filtered.isEmpty()) {
            Text("No users loaded/matching filters yet.", style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(filtered, key = { _, u -> u.uid }) { index, u ->
                    // ✅ auto prefetch near bottom (infinite scroll)
                    if (hasMore && !pagingLoading && index >= filtered.size - 12) {
                        loadNextPage()
                    }

                    val isExpanded = expandedUid == u.uid

                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize()
                            .clickable {
                                // Capture current scroll position BEFORE changing item height.
                                restoreIndex = listState.firstVisibleItemIndex
                                restoreOffset = listState.firstVisibleItemScrollOffset
                                restorePending = true

                                expandedUid = if (isExpanded) null else u.uid
                            }
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(u.uid, fontFamily = FontFamily.Monospace)
                                Text(
                                    if (isExpanded) "▾" else "▸",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }

                            Text(
                                "displayName=${u.displayName.ifBlank { "(blank)" }}",
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
                                    "Tip: copy UID and paste into Moderation section below to act on them.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedButton(onClick = { expandedUid = null }, modifier = Modifier.weight(1f)) {
                                        Text("Collapse")
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (pagingLoading) CircularProgressIndicator()
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    LaunchedEffect(pagingLoading) {
        setLoading(pagingLoading)
    }
}

/* =========================================================
   OWNER: Admin manager
   ========================================================= */

private data class DeviceRow(
    val deviceHash: String,
    val adminEnabled: Boolean,
    val note: String,
    val lastSeenAt: Timestamp?
)

@Composable
private fun OwnerAdminManagerSection(
    db: FirebaseFirestore,
    setLoading: (Boolean) -> Unit,
    setError: (String?) -> Unit
) {
    val devices = remember { mutableStateListOf<DeviceRow>() }
    var search by remember { mutableStateOf("") }

    fun refresh() {
        setLoading(true)
        setError(null)
        db.collection("devices")
            .orderBy("lastSeenAt", Query.Direction.DESCENDING)
            .limit(50)
            .get()
            .addOnSuccessListener { snap ->
                devices.clear()
                for (d in snap.documents) {
                    val hash = d.id
                    devices.add(
                        DeviceRow(
                            deviceHash = hash,
                            adminEnabled = d.getBoolean("adminEnabled") ?: false,
                            note = d.getString("note") ?: "",
                            lastSeenAt = d.getTimestamp("lastSeenAt")
                        )
                    )
                }
                setLoading(false)
            }
            .addOnFailureListener { e ->
                setLoading(false)
                setError(e.message ?: "Failed to load devices")
            }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Owner: Admin Manager", style = MaterialTheme.typography.titleMedium)

        ElevatedCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Promote/demote admins without opening the Firestore website.",
                    style = MaterialTheme.typography.bodyMedium
                )

                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Search device hash / note") },
                    placeholder = { Text("type to filter…") }
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { refresh() }, modifier = Modifier.weight(1f)) {
                        Text("Refresh devices")
                    }
                }
            }
        }

        val filtered = remember(search, devices.toList()) {
            val q = search.trim()
            if (q.isBlank()) devices.toList()
            else devices.filter {
                it.deviceHash.contains(q, ignoreCase = true) ||
                    it.note.contains(q, ignoreCase = true)
            }
        }

        if (filtered.isEmpty()) {
            Text("No devices found.", style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(filtered, key = { it.deviceHash }) { d ->
                    Card {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(d.deviceHash, fontFamily = FontFamily.Monospace)
                            Text(
                                "lastSeenAt=${d.lastSeenAt ?: "?"}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Admin")
                                    Spacer(Modifier.width(10.dp))
                                    Switch(
                                        checked = d.adminEnabled,
                                        onCheckedChange = { newValue ->
                                            setLoading(true)
                                            setError(null)
                                            db.collection("devices").document(d.deviceHash)
                                                .set(
                                                    mapOf(
                                                        "adminEnabled" to newValue,
                                                        "updatedAt" to FieldValue.serverTimestamp()
                                                    ),
                                                    com.google.firebase.firestore.SetOptions.merge()
                                                )
                                                .addOnSuccessListener { setLoading(false); refresh() }
                                                .addOnFailureListener { e ->
                                                    setLoading(false)
                                                    setError(e.message ?: "Failed to update adminEnabled")
                                                }
                                        }
                                    )
                                }
                            }

                            var noteText by remember(d.deviceHash) { mutableStateOf(d.note) }
                            OutlinedTextField(
                                value = noteText,
                                onValueChange = { noteText = it },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 1,
                                label = { Text("Note (optional)") },
                                placeholder = { Text("e.g. Ash's phone / tester / etc") }
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = {
                                        setLoading(true)
                                        setError(null)
                                        db.collection("devices").document(d.deviceHash)
                                            .set(
                                                mapOf(
                                                    "note" to noteText.trim(),
                                                    "updatedAt" to FieldValue.serverTimestamp()
                                                ),
                                                com.google.firebase.firestore.SetOptions.merge()
                                            )
                                            .addOnSuccessListener { setLoading(false); refresh() }
                                            .addOnFailureListener { e ->
                                                setLoading(false)
                                                setError(e.message ?: "Failed to save note")
                                            }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Save note") }

                                OutlinedButton(
                                    onClick = { noteText = d.note },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Reset") }
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}

/* =========================================================
   Announcements (unchanged)
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

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Announcements", style = MaterialTheme.typography.titleMedium)

        ElevatedCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Create / publish", style = MaterialTheme.typography.titleSmall)

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
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Active")
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = newActive, onCheckedChange = { newActive = it })
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                                    setError(e.message ?: "Failed to publish announcement")
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
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(announcements, key = { it.id }) { a ->
                    Card {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
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
                                                setError(e.message ?: "Failed to toggle active")
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
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}

/* =========================================================
   Moderation + History
   ========================================================= */

private data class ModerationEventRow(
    val id: String,
    val action: String,
    val reason: String,
    val createdAt: Timestamp?,
    val byDeviceHash: String,
    val byUid: String,
    val byAppId: String
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
    var lookupUid by remember { mutableStateOf("") }
    var loadedUid by remember { mutableStateOf<String?>(null) }

    var displayName by remember { mutableStateOf("") }
    var warned by remember { mutableStateOf(false) }
    var banned by remember { mutableStateOf(false) }
    var warnReason by remember { mutableStateOf("") }
    var banReason by remember { mutableStateOf("") }
    var updatedAt by remember { mutableStateOf<Timestamp?>(null) }

    val history = remember { mutableStateListOf<ModerationEventRow>() }

    fun clearLoaded() {
        loadedUid = null
        displayName = ""
        warned = false
        banned = false
        warnReason = ""
        banReason = ""
        updatedAt = null
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
            .limit(40)
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
                            byAppId = d.getString("byAppId") ?: ""
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
                warned = snap.getBoolean("warned") ?: false
                banned = snap.getBoolean("banned") ?: false
                warnReason = snap.getString("warnReason") ?: ""
                banReason = snap.getString("banReason") ?: ""
                updatedAt = snap.getTimestamp("updatedAt")
                setLoading(false)
                refreshHistory(u)
            }
            .addOnFailureListener { e ->
                setLoading(false)
                setError(e.message ?: "Failed to load user")
            }
    }

    fun writeEvent(uid: String, action: String, reason: String) {
        val data = hashMapOf(
            "uid" to uid.trim(),
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

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Moderation", style = MaterialTheme.typography.titleMedium)

        ElevatedCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("User: $loadedUid", style = MaterialTheme.typography.titleSmall)
                Text("displayName=$displayName", fontFamily = FontFamily.Monospace)
                Text("warned=$warned  banned=$banned", fontFamily = FontFamily.Monospace)
                Text("updatedAt=${updatedAt ?: "?"}", fontFamily = FontFamily.Monospace)

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { refreshHistory(loadedUid!!) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Refresh history") }

                    OutlinedButton(
                        onClick = { load(loadedUid!!) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Reload user") }
                }
            }
        }

        ElevatedCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Warn", style = MaterialTheme.typography.titleSmall)
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
                                    com.google.firebase.firestore.SetOptions.merge()
                                )
                                .addOnSuccessListener {
                                    setLoading(false)
                                    writeEvent(uid, "warn", warnReason)
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
                                    com.google.firebase.firestore.SetOptions.merge()
                                )
                                .addOnSuccessListener {
                                    setLoading(false)
                                    writeEvent(uid, "clear_warn", "")
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

        ElevatedCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Ban", style = MaterialTheme.typography.titleSmall)
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
                            setLoading(true); setError(null)
                            db.collection("users").document(uid)
                                .set(
                                    mapOf(
                                        "banned" to true,
                                        "banReason" to banReason.trim(),
                                        "updatedAt" to FieldValue.serverTimestamp()
                                    ),
                                    com.google.firebase.firestore.SetOptions.merge()
                                )
                                .addOnSuccessListener {
                                    setLoading(false)
                                    writeEvent(uid, "ban", banReason)
                                    load(uid)
                                }
                                .addOnFailureListener { e ->
                                    setLoading(false); setError(e.message ?: "Failed to ban")
                                }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Ban user") }

                    OutlinedButton(
                        onClick = {
                            val uid = loadedUid ?: return@OutlinedButton
                            setLoading(true); setError(null)
                            db.collection("users").document(uid)
                                .set(
                                    mapOf(
                                        "banned" to false,
                                        "banReason" to "",
                                        "updatedAt" to FieldValue.serverTimestamp()
                                    ),
                                    com.google.firebase.firestore.SetOptions.merge()
                                )
                                .addOnSuccessListener {
                                    setLoading(false)
                                    writeEvent(uid, "unban", "")
                                    load(uid)
                                }
                                .addOnFailureListener { e ->
                                    setLoading(false); setError(e.message ?: "Failed to unban")
                                }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Unban") }
                }
            }
        }

        ElevatedCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("History", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Latest moderation actions for this UID.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (history.isEmpty()) {
                    Text("No history found.", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(history, key = { it.id }) { e ->
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        "${e.action.ifBlank { "(no action)" }}  @  ${e.createdAt ?: "?"}",
                                        fontFamily = FontFamily.Monospace
                                    )
                                    if (e.reason.isNotBlank()) {
                                        Text("reason=${e.reason}", fontFamily = FontFamily.Monospace)
                                    }
                                    Text(
                                        "byUid=${e.byUid.ifBlank { "?" }}  byDevice=${e.byDeviceHash.take(12).ifBlank { "?" }}…",
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                        item { Spacer(Modifier.height(10.dp)) }
                    }
                }
            }
        }
    }
}

/* =========================================================
   ToS / Config (unchanged)
   ========================================================= */

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
                setError(e.message ?: "Failed to load ToS config")
            }
    }

    LaunchedEffect(Unit) { load() }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("ToS / App Config", style = MaterialTheme.typography.titleMedium)

        ElevatedCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Current config", style = MaterialTheme.typography.titleSmall)
                Text("loadedAt=${loadedAt ?: "?"}", fontFamily = FontFamily.Monospace)

                OutlinedTextField(
                    value = ownerUid,
                    onValueChange = { ownerUid = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Owner UID (controls in-app admin management)") },
                    placeholder = { Text("paste your UID here") }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("ToS version", style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                                .set(data, com.google.firebase.firestore.SetOptions.merge())
                                .addOnSuccessListener { setLoading(false); load() }
                                .addOnFailureListener { e ->
                                    setLoading(false)
                                    setError(e.message ?: "Failed to save config")
                                }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Save") }

                    OutlinedButton(onClick = { load() }, modifier = Modifier.weight(1f)) { Text("Reload") }
                }

                Text(
                    "Public build should re-prompt ToS when tosVersion increases.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
