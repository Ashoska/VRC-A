// app/src/main/kotlin/com/scrapw/chatbox/AdminScreen.kt
package com.scrapw.chatbox

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Owner-only Admin screen.
 *
 * IMPORTANT (post deviceHash-doc change):
 * - users/{docId} is typically deviceHash now (reinstall-resistant).
 * - auth UID is stored in the document fields:
 *    - authUid (current session UID)
 *    - uid (legacy alias)
 *
 * So:
 * - "docId" != "authUid" in general
 * - moderation must load/update by docId
 */
@Composable
fun AdminScreen() {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val db = remember { FirebaseFirestore.getInstance() }
    val auth = remember { FirebaseAuth.getInstance() }

    var globalLoading by remember { mutableStateOf(false) }
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

    // UID: read cached first, then ensure anon auth
    var myUid by remember { mutableStateOf(readCachedUid(ctx)) }

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

    suspend fun refreshOwnerGate() {
        ownerChecked = false
        ownerUid = ""
        isOwner = false
        setErr(null)

        runCatching {
            val snap = db.collection("config").document("app").get().await()
            ownerUid = snap.getString("ownerUid") ?: ""
            isOwner = ownerUid.isNotBlank() && myUid.isNotBlank() && ownerUid == myUid
            ownerChecked = true
        }.onFailure { e ->
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

                item { DeviceHashCard(deviceHash, onCopy = { clipboard.setText(AnnotatedString(deviceHash)) }) }
                item { UidCard(myUid, onCopy = { clipboard.setText(AnnotatedString(myUid)) }) }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = {
                            myUid = readCachedUid(ctx).ifBlank { myUid }
                            scope.launch { refreshOwnerGate() }
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

    // ==========================================
    // MAIN UI (TABS)
    // ==========================================
    val tabs = remember { listOf("Users", "Moderation", "Announcements", "Config") }
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }

    // Shared “selected user” to jump from Users -> Moderation
    var moderationTarget by rememberSaveable { mutableStateOf<ModerationTarget?>(null) }

    Surface {
        androidx.compose.foundation.layout.Column(Modifier.padding(14.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                    Text("Admin", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "DeviceHash: ${deviceHash.ifBlank { "(blank)" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "My UID: ${myUid.ifBlank { "(not available yet)" }}",
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
                    scope.launch {
                        myUid = readCachedUid(ctx).ifBlank { myUid }
                        refreshOwnerGate()
                    }
                }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh gate")
                }
            }

            if (error != null) {
                Spacer(Modifier.height(10.dp))
                ErrorCard(error!!)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { setErr(null) }) { Text("Clear error") }
                }
            }

            Spacer(Modifier.height(12.dp))

            TabRow(selectedTabIndex = tabIndex) {
                tabs.forEachIndexed { i, label ->
                    Tab(
                        selected = tabIndex == i,
                        onClick = { tabIndex = i },
                        text = { Text(label) }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            when (tabIndex) {
                0 -> UsersTab(
                    db = db,
                    clipboardCopy = { clipboard.setText(AnnotatedString(it)) },
                    setGlobalLoading = { globalLoading = it },
                    setError = ::setErr,
                    onSendToModeration = { target ->
                        moderationTarget = target
                        tabIndex = 1
                    }
                )
                1 -> ModerationTab(
                    db = db,
                    myUid = myUid,
                    byDeviceHash = deviceHash,
                    byAppId = BuildConfig.APPLICATION_ID,
                    clipboardCopy = { clipboard.setText(AnnotatedString(it)) },
                    setGlobalLoading = { globalLoading = it },
                    setError = ::setErr,
                    initialTarget = moderationTarget,
                    onClearInitialTarget = { moderationTarget = null }
                )
                2 -> AnnouncementsTab(
                    db = db,
                    createdByDevice = deviceHash,
                    setGlobalLoading = { globalLoading = it },
                    setError = ::setErr
                )
                else -> ConfigTab(
                    db = db,
                    setGlobalLoading = { globalLoading = it },
                    setError = ::setErr
                )
            }

            if (globalLoading) {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/* =========================================================
   USERS TAB
   ========================================================= */

private data class UserRow(
    val docId: String,
    val authUid: String,
    val displayName: String,
    val deviceHash: String,
    val warned: Boolean,
    val banned: Boolean,
    val lastSeenAt: Timestamp?,
    val updatedAt: Timestamp?
)

private data class UserDetail(
    val afkEnabled: Boolean,
    val afkMessage: String,
    val cycleEnabled: Boolean,
    val cycleIntervalSeconds: Long,
    val cycleLinesText: String,
    val spotifyEnabled: Boolean,
    val spotifyDemoEnabled: Boolean,
    val spotifyPreset: Long,
    val nowPlayingDetected: Boolean,
    val nowPlayingIsPlaying: Boolean,
    val nowPlayingTitle: String,
    val nowPlayingArtist: String,
    val combinedPreviewText: String,
    val warnReason: String,
    val banReason: String,
    val afkPresets: List<String>,
    val cyclePresets: List<String>
)

private data class ModerationTarget(
    val docId: String,
    val authUid: String,
    val deviceHash: String,
    val displayName: String
)

@Composable
private fun UsersTab(
    db: FirebaseFirestore,
    clipboardCopy: (String) -> Unit,
    setGlobalLoading: (Boolean) -> Unit,
    setError: (String?) -> Unit,
    onSendToModeration: (ModerationTarget) -> Unit
) {
    val scope = rememberCoroutineScope()
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    val users = remember { mutableStateListOf<UserRow>() }
    var pagingLoading by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    var lastDoc by remember { mutableStateOf<DocumentSnapshot?>(null) }
    val pageSize = 75

    var search by rememberSaveable { mutableStateOf("") }
    var filterWarned by rememberSaveable { mutableStateOf(false) }
    var filterBanned by rememberSaveable { mutableStateOf(false) }

    var expandedDocId by rememberSaveable { mutableStateOf<String?>(null) }

    // Cache details per user docId (loaded only when expanded)
    val detailsCache = remember { mutableMapOf<String, UserDetail>() }
    var detailsLoadingFor by remember { mutableStateOf<String?>(null) }

    fun rowMatches(u: UserRow, q: String): Boolean {
        if (q.isBlank()) return true
        val t = q.trim()
        return u.docId.contains(t, true) ||
            u.authUid.contains(t, true) ||
            u.deviceHash.contains(t, true) ||
            u.displayName.contains(t, true)
    }

    val filteredUsers by remember(search, filterWarned, filterBanned, users.size) {
        derivedStateOf {
            val q = search.trim()
            users.asSequence()
                .filter { if (filterWarned) it.warned else true }
                .filter { if (filterBanned) it.banned else true }
                .filter { rowMatches(it, q) }
                .toList()
        }
    }

    suspend fun loadNextPage() {
        if (pagingLoading || !hasMore) return
        pagingLoading = true
        setError(null)

        runCatching {
            var q: Query = db.collection("users")
                .orderBy("lastSeenAt", Query.Direction.DESCENDING)
                .limit(pageSize.toLong())

            lastDoc?.let { q = q.startAfter(it) }

            val snap = q.get().await()
            val docs = snap.documents

            docs.forEach { d ->
                val docId = d.id
                val authUid = (d.getString("authUid") ?: d.getString("uid") ?: "").trim()
                users.add(
                    UserRow(
                        docId = docId,
                        authUid = authUid,
                        displayName = (d.getString("displayName") ?: "").trim(),
                        deviceHash = (d.getString("deviceHash") ?: "").trim(),
                        warned = d.getBoolean("warned") ?: false,
                        banned = d.getBoolean("banned") ?: false,
                        lastSeenAt = d.getTimestamp("lastSeenAt"),
                        updatedAt = d.getTimestamp("updatedAt")
                    )
                )
            }

            lastDoc = docs.lastOrNull()
            if (docs.size < pageSize) hasMore = false
            pagingLoading = false
        }.onFailure { e ->
            pagingLoading = false
            setError(e.message ?: "Failed to load users list")
        }
    }

    suspend fun resetAndLoad() {
        users.clear()
        hasMore = true
        lastDoc = null
        expandedDocId = null
        detailsCache.clear()
        loadNextPage()
    }

    suspend fun loadDetails(docId: String) {
        if (detailsCache.containsKey(docId)) return
        detailsLoadingFor = docId
        setError(null)

        runCatching {
            val snap = db.collection("users").document(docId).get().await()

            fun s(key: String) = (snap.getString(key) ?: "").trim()
            fun b(key: String) = snap.getBoolean(key) ?: false
            fun l(key: String) = snap.getLong(key) ?: 0L

            val afkPresets = listOf(s("afkPreset1"), s("afkPreset2"), s("afkPreset3"))
            val cyclePresets = listOf(s("cyclePreset1"), s("cyclePreset2"), s("cyclePreset3"), s("cyclePreset4"), s("cyclePreset5"))

            detailsCache[docId] = UserDetail(
                afkEnabled = b("afkEnabled"),
                afkMessage = s("afkMessage"),
                cycleEnabled = b("cycleEnabled"),
                cycleIntervalSeconds = l("cycleIntervalSeconds"),
                cycleLinesText = s("cycleLinesText"),
                spotifyEnabled = b("spotifyEnabled"),
                spotifyDemoEnabled = b("spotifyDemoEnabled"),
                spotifyPreset = l("spotifyPreset"),
                nowPlayingDetected = b("nowPlayingDetected"),
                nowPlayingIsPlaying = b("nowPlayingIsPlaying"),
                nowPlayingTitle = s("nowPlayingTitle"),
                nowPlayingArtist = s("nowPlayingArtist"),
                combinedPreviewText = s("combinedPreviewText"),
                warnReason = s("warnReason"),
                banReason = s("banReason"),
                afkPresets = afkPresets,
                cyclePresets = cyclePresets
            )
        }.onFailure { e ->
            setError(e.message ?: "Failed to load user details")
        }

        detailsLoadingFor = null
    }

    LaunchedEffect(Unit) { resetAndLoad() }

    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ElevatedCard {
            androidx.compose.foundation.layout.Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Users", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Search matches: docId (deviceHash), authUid, deviceHash field, displayName.\nTap a user to expand details.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Search") },
                    placeholder = { Text("docId / authUid / deviceHash / displayName") }
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
                        onClick = { scope.launch { resetAndLoad() } },
                        modifier = Modifier.weight(1f)
                    ) { Text("Refresh") }

                    Button(
                        onClick = { scope.launch { loadNextPage() } },
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

        Divider()

        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (filteredUsers.isEmpty()) {
                item {
                    Text("No users loaded/matching filters yet.", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                itemsIndexed(filteredUsers, key = { _, u -> u.docId }) { index, u ->
                    if (hasMore && !pagingLoading && index >= filteredUsers.size - 12) {
                        scope.launch { loadNextPage() }
                    }

                    val isExpanded = expandedDocId == u.docId
                    val detail = detailsCache[u.docId]
                    val isDetailLoading = detailsLoadingFor == u.docId

                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize()
                            .clickable {
                                expandedDocId = if (isExpanded) null else u.docId
                                if (!isExpanded) {
                                    scope.launch { loadDetails(u.docId) }
                                }
                            }
                    ) {
                        androidx.compose.foundation.layout.Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                                    Text(
                                        u.displayName.ifBlank { "(no displayName)" },
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        "docId=${u.docId}",
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        "authUid=${u.authUid.ifBlank { "(blank)" }}",
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
                                }

                                Icon(
                                    if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                    contentDescription = null
                                )
                            }

                            Text(
                                "lastSeenAt=${u.lastSeenAt ?: "?"}   updatedAt=${u.updatedAt ?: "?"}",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (isExpanded) {
                                Divider()

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { clipboardCopy(u.docId) }) {
                                        Icon(Icons.Filled.ContentCopy, contentDescription = null)
                                        Spacer(Modifier.width(6.dp))
                                        Text("Copy docId")
                                    }
                                    if (u.authUid.isNotBlank()) {
                                        OutlinedButton(onClick = { clipboardCopy(u.authUid) }) {
                                            Icon(Icons.Filled.ContentCopy, contentDescription = null)
                                            Spacer(Modifier.width(6.dp))
                                            Text("Copy authUid")
                                        }
                                    }
                                    if (u.deviceHash.isNotBlank()) {
                                        OutlinedButton(onClick = { clipboardCopy(u.deviceHash) }) {
                                            Icon(Icons.Filled.ContentCopy, contentDescription = null)
                                            Spacer(Modifier.width(6.dp))
                                            Text("Copy device")
                                        }
                                    }
                                }

                                Spacer(Modifier.height(6.dp))

                                Button(
                                    onClick = {
                                        onSendToModeration(
                                            ModerationTarget(
                                                docId = u.docId,
                                                authUid = u.authUid,
                                                deviceHash = u.deviceHash,
                                                displayName = u.displayName
                                            )
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Filled.ArrowForward, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Send to Moderation")
                                }

                                Spacer(Modifier.height(6.dp))

                                if (isDetailLoading) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        CircularProgressIndicator()
                                        Text("Loading details…")
                                    }
                                } else if (detail != null) {
                                    DetailBlock(detail)
                                } else {
                                    Text(
                                        "No detail loaded (tap again or refresh).",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
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
        }
    }
}

@Composable
private fun DetailBlock(d: UserDetail) {
    @Composable
    fun mono(label: String, value: String) {
        Text(
            text = "$label=$value",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
    }

    ElevatedCard {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Live State", style = MaterialTheme.typography.titleSmall)
            mono("afkEnabled", d.afkEnabled.toString())
            mono("afkMessage", d.afkMessage.ifBlank { "(blank)" })
            mono("cycleEnabled", d.cycleEnabled.toString())
            mono("cycleIntervalSeconds", d.cycleIntervalSeconds.toString())
            mono("spotifyEnabled", d.spotifyEnabled.toString())
            mono("spotifyDemoEnabled", d.spotifyDemoEnabled.toString())
            mono("spotifyPreset", d.spotifyPreset.toString())
            mono("nowPlayingDetected", d.nowPlayingDetected.toString())
            mono("nowPlayingIsPlaying", d.nowPlayingIsPlaying.toString())
            mono("nowPlayingTitle", d.nowPlayingTitle.ifBlank { "(blank)" })
            mono("nowPlayingArtist", d.nowPlayingArtist.ifBlank { "(blank)" })

            Spacer(Modifier.height(4.dp))
            Text("combinedPreviewText", style = MaterialTheme.typography.labelLarge)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Text(
                    text = d.combinedPreviewText.ifBlank { "(blank)" },
                    modifier = Modifier.padding(10.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
            if (d.warnReason.isNotBlank() || d.banReason.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("Moderation Flags", style = MaterialTheme.typography.titleSmall)
                if (d.warnReason.isNotBlank()) mono("warnReason", d.warnReason)
                if (d.banReason.isNotBlank()) mono("banReason", d.banReason)
            }

            Spacer(Modifier.height(4.dp))
            Text("AFK Presets", style = MaterialTheme.typography.titleSmall)
            d.afkPresets.forEachIndexed { i, p ->
                mono("afkPreset${i + 1}", p.ifBlank { "(blank)" })
            }

            Spacer(Modifier.height(4.dp))
            Text("Cycle Presets", style = MaterialTheme.typography.titleSmall)
            d.cyclePresets.forEachIndexed { i, p ->
                val oneLine = p.lines().firstOrNull()?.trim().orEmpty()
                mono("cyclePreset${i + 1}", oneLine.ifBlank { "(blank)" })
            }

            if (d.cycleLinesText.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("cycleLinesText", style = MaterialTheme.typography.labelLarge)
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Text(
                        d.cycleLinesText,
                        modifier = Modifier.padding(10.dp),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

/* =========================================================
   MODERATION TAB
   ========================================================= */

private data class ModerationEventRow(
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
private fun ModerationTab(
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
    var loaded by rememberSaveable { mutableStateOf<ModerationTarget?>(null) }

    var warned by remember { mutableStateOf(false) }
    var banned by remember { mutableStateOf(false) }
    var warnReason by remember { mutableStateOf("") }
    var banReason by remember { mutableStateOf("") }

    var deviceBanned by remember { mutableStateOf(false) }
    var deviceBanReason by remember { mutableStateOf("") }
    var applyUidBan by rememberSaveable { mutableStateOf(true) }
    var applyDeviceBan by rememberSaveable { mutableStateOf(true) }

    val history = remember { mutableStateListOf<ModerationEventRow>() }

    fun clearLoaded() {
        loaded = null
        warned = false
        banned = false
        warnReason = ""
        banReason = ""
        deviceBanned = false
        deviceBanReason = ""
        history.clear()
    }

    suspend fun resolveUser(input: String): ModerationTarget? {
        val t = input.trim()
        if (t.isBlank()) return null

        // 1) Try direct docId lookup
        runCatching {
            val doc = db.collection("users").document(t).get().await()
            if (doc.exists()) {
                val authUid = (doc.getString("authUid") ?: doc.getString("uid") ?: "").trim()
                val deviceHash = (doc.getString("deviceHash") ?: "").trim()
                val displayName = (doc.getString("displayName") ?: "").trim()
                return ModerationTarget(docId = t, authUid = authUid, deviceHash = deviceHash, displayName = displayName)
            }
        }

        // 2) Try authUid query
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

    suspend fun loadTarget(target: ModerationTarget) {
        setGlobalLoading(true)
        setError(null)

        runCatching {
            val snap = db.collection("users").document(target.docId).get().await()

            warned = snap.getBoolean("warned") ?: false
            banned = snap.getBoolean("banned") ?: false
            warnReason = (snap.getString("warnReason") ?: "").trim()
            banReason = (snap.getString("banReason") ?: "").trim()

            val dh = (snap.getString("deviceHash") ?: target.deviceHash).trim()

            if (dh.isNotBlank()) {
                val ds = db.collection("bannedDevices").document(dh).get().await()
                deviceBanned = ds.getBoolean("banned") ?: false
                deviceBanReason = (ds.getString("reason") ?: "").trim()
            } else {
                deviceBanned = false
                deviceBanReason = ""
            }

            // history: query by targetDocId first, fallback legacy uid field
            history.clear()

            val h1 = runCatching {
                db.collection("moderationEvents")
                    .whereEqualTo("targetDocId", target.docId)
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .limit(60)
                    .get()
                    .await()
            }.getOrNull()

            val snapToUse = if (h1 != null && !h1.isEmpty) {
                h1
            } else {
                // legacy fallback: older events written with uid field
                db.collection("moderationEvents")
                    .whereEqualTo("uid", target.docId)
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .limit(60)
                    .get()
                    .await()
            }

            snapToUse.documents.forEach { d ->
                history.add(
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
                )
            }

            loaded = target
            setGlobalLoading(false)
        }.onFailure { e ->
            setGlobalLoading(false)
            setError(e.message ?: "Failed to load moderation target")
        }
    }

    suspend fun writeEvent(
        target: ModerationTarget,
        action: String,
        reason: String
    ) {
        runCatching {
            val data = hashMapOf(
                // legacy query fields (keep for existing indexes/queries)
                "uid" to target.docId,
                "targetUid" to target.docId,

                // new structured fields
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

    // If coming from Users tab, auto-load once.
    LaunchedEffect(initialTarget?.docId) {
        val t = initialTarget ?: return@LaunchedEffect
        lookup = t.docId
        loadTarget(t)
        onClearInitialTarget()
    }

    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ElevatedCard {
            androidx.compose.foundation.layout.Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Moderation", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Lookup accepts docId (deviceHash doc id) OR authUid.\nLoads user doc by docId; bans update the correct doc.",
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
                                if (t == null) {
                                    setError("No matching user found for: ${lookup.trim()}")
                                } else {
                                    loadTarget(t)
                                }
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
            return
        }

        // Summary + copy
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            androidx.compose.foundation.layout.Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Target", style = MaterialTheme.typography.titleSmall)
                Text("displayName=${t.displayName.ifBlank { "(blank)" }}", fontFamily = FontFamily.Monospace)
                Text("docId=${t.docId}", fontFamily = FontFamily.Monospace)
                Text("authUid=${t.authUid.ifBlank { "(blank)" }}", fontFamily = FontFamily.Monospace)
                Text("deviceHash=${t.deviceHash.ifBlank { "(blank)" }}", fontFamily = FontFamily.Monospace)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { clipboardCopy(t.docId) }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Copy docId")
                    }
                    if (t.authUid.isNotBlank()) {
                        OutlinedButton(onClick = { clipboardCopy(t.authUid) }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Copy authUid")
                        }
                    }
                    if (t.deviceHash.isNotBlank()) {
                        OutlinedButton(onClick = { clipboardCopy(t.deviceHash) }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Copy device")
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { scope.launch { loadTarget(t) } },
                        modifier = Modifier.weight(1f)
                    ) { Text("Reload") }
                }

                Text("warned=$warned  banned=$banned  deviceBanned=$deviceBanned", fontFamily = FontFamily.Monospace)
            }
        }

        // WARN
        ElevatedCard {
            androidx.compose.foundation.layout.Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
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
                            scope.launch {
                                setGlobalLoading(true)
                                setError(null)

                                runCatching {
                                    db.collection("users").document(t.docId)
                                        .set(
                                            mapOf(
                                                "warned" to true,
                                                "warnReason" to warnReason.trim(),
                                                "updatedAt" to FieldValue.serverTimestamp()
                                            ),
                                            SetOptions.merge()
                                        )
                                        .await()
                                    writeEvent(t, "warn", warnReason)
                                    loadTarget(t)
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
                                    writeEvent(t, "clear_warn", "")
                                    loadTarget(t)
                                }.onFailure { e ->
                                    setGlobalLoading(false)
                                    setError(e.message ?: "Failed to clear warn")
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Clear warn") }
                }
            }
        }

        // BAN
        ElevatedCard {
            androidx.compose.foundation.layout.Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Ban", style = MaterialTheme.typography.titleSmall)

                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                            enabled = t.deviceHash.isNotBlank()
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
                            scope.launch {
                                val reason = banReason.trim()
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
                                    loadTarget(t)
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
                                    loadTarget(t)
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
            }
        }

        // HISTORY
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
                                    if (e.reason.isNotBlank()) {
                                        Text("reason=${e.reason}", fontFamily = FontFamily.Monospace)
                                    }
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
   ANNOUNCEMENTS TAB
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
private fun AnnouncementsTab(
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

    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ElevatedCard {
            androidx.compose.foundation.layout.Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Announcements", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Create, enable/disable, delete, and adjust priority here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

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
            return
        }

        announcements.forEach { a ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                androidx.compose.foundation.layout.Column(
                    Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                            Text(a.title.ifBlank { "(no title)" }, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "priority=${a.priority}  active=${a.active}  createdAt=${a.createdAt ?: "?"}",
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
}

/* =========================================================
   CONFIG TAB
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
private fun ConfigTab(
    db: FirebaseFirestore,
    setGlobalLoading: (Boolean) -> Unit,
    setError: (String?) -> Unit
) {
    val scope = rememberCoroutineScope()

    var tosVersion by rememberSaveable { mutableIntStateOf(1) }
    var tosText by rememberSaveable { mutableStateOf("") }
    var tosUrl by rememberSaveable { mutableStateOf("") }
    var ownerUid by rememberSaveable { mutableStateOf("") }
    var loadedAt by remember { mutableStateOf<Timestamp?>(null) }

    suspend fun load() {
        setGlobalLoading(true)
        setError(null)

        runCatching {
            val snap = db.collection("config").document("app").get().await()
            tosVersion = (snap.getLong("tosVersion") ?: 1L).toInt().coerceAtLeast(1)
            tosText = snap.getString("tosText") ?: ""
            tosUrl = snap.getString("tosUrl") ?: ""
            ownerUid = snap.getString("ownerUid") ?: ""
            loadedAt = snap.getTimestamp("updatedAt")
            setGlobalLoading(false)
        }.onFailure { e ->
            setGlobalLoading(false)
            setError(e.message ?: "Failed to load config")
        }
    }

    LaunchedEffect(Unit) { load() }

    ElevatedCard {
        androidx.compose.foundation.layout.Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("ToS / App Config", style = MaterialTheme.typography.titleMedium)
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { tosVersion = (tosVersion - 1).coerceAtLeast(1) }) { Text("−") }
                    Text(tosVersion.toString(), fontFamily = FontFamily.Monospace)
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
                    onClick = { if (tosText.trim().isEmpty()) tosText = DEFAULT_TOS_TEXT },
                    modifier = Modifier.weight(1f)
                ) { Text("Use template") }

                OutlinedButton(
                    onClick = { scope.launch { load() } },
                    modifier = Modifier.weight(1f)
                ) { Text("Reload") }
            }

            Button(
                onClick = {
                    scope.launch {
                        setGlobalLoading(true)
                        setError(null)

                        runCatching {
                            val data = hashMapOf(
                                "ownerUid" to ownerUid.trim(),
                                "tosVersion" to tosVersion,
                                "tosText" to tosText,
                                "tosUrl" to tosUrl,
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                            db.collection("config").document("app")
                                .set(data, SetOptions.merge())
                                .await()
                            load()
                        }.onFailure { e ->
                            setGlobalLoading(false)
                            setError(e.message ?: "Failed to save config")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save") }

            Text(
                "Increase ToS version to re-prompt users.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/* =========================================================
   COMMON UI
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
                IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, contentDescription = "Copy") }
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
                IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, contentDescription = "Copy") }
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
   PREFS HELPERS
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
