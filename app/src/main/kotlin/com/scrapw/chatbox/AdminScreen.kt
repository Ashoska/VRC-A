// app/src/main/kotlin/com/scrapw/chatbox/AdminScreen.kt
package com.scrapw.chatbox

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
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
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Owner-only Admin screen.
 *
 * Canonical users doc is:
 * - users/{docId}  (docId usually == deviceHash)
 *
 * Mapping doc is:
 * - usersById/{uid} -> { deviceHash, authUid, appId, adminBuild, updatedAt }
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
    fun setErr(msg: String?) {
        error = msg?.trim()?.takeIf { it.isNotBlank() }?.take(4000)
    }

    // Hard block: should never be reachable on public build.
    if (!BuildConfig.IS_ADMIN_BUILD) {
        Surface {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Admin", style = MaterialTheme.typography.titleLarge)
                ErrorCard("This page is only available in the Admin build.")
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Admin", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator()
                    Text("Checking access…")
                }
                if (error != null) ErrorCard(error!!)
            }
        }
        return
    }

    // Access denied
    if (!isOwner) {
        Surface {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Admin", style = MaterialTheme.typography.titleLarge)

                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Access denied", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "This account is not the owner.\n\nUID: ${myUid.ifBlank { "(not available yet)" }}",
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                ElevatedCard {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("IDs", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(deviceHash)) }) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Copy device")
                            }
                            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(myUid)) }) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Copy UID")
                            }
                        }
                        Text("deviceHash=${deviceHash.ifBlank { "(blank)" }}", fontFamily = FontFamily.Monospace)
                        Text("uid=${myUid.ifBlank { "(blank)" }}", fontFamily = FontFamily.Monospace)
                        Text("ownerUid=${ownerUid.ifBlank { "(blank)" }}", fontFamily = FontFamily.Monospace)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = {
                        myUid = readCachedUid(ctx).ifBlank { myUid }
                        scope.launch { refreshOwnerGate() }
                        setErr(null)
                    }) { Text("Re-check") }

                    if (error != null) OutlinedButton(onClick = { setErr(null) }) { Text("Clear error") }
                }

                if (error != null) ErrorCard(error!!)
            }
        }
        return
    }

    // ==========================================
    // MAIN UI
    // ==========================================
    val tabs = remember { listOf("Users", "Mod", "Announce", "Config") }
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }

    // ModerationTarget is NOT saveable
    var moderationTarget by remember { mutableStateOf<ModerationTarget?>(null) }

    // Compact IDs drawer
    var idsExpanded by rememberSaveable { mutableStateOf(false) }

    Surface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header (clean)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Admin", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Owner build • ${BuildConfig.APPLICATION_ID}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(onClick = { idsExpanded = !idsExpanded }) {
                        Icon(Icons.Filled.Info, contentDescription = "IDs")
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
            }

            // IDs card
            AnimatedVisibility(visible = idsExpanded) {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize()
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("IDs", style = MaterialTheme.typography.titleSmall)
                            IconButton(onClick = { idsExpanded = false }) {
                                Icon(Icons.Filled.ExpandLess, contentDescription = "Close")
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(deviceHash)) }) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Copy device")
                            }
                            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(myUid)) }) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Copy UID")
                            }
                            if (ownerUid.isNotBlank()) {
                                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(ownerUid)) }) {
                                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Copy owner")
                                }
                            }
                        }

                        Text("deviceHash=${deviceHash.ifBlank { "(blank)" }}", fontFamily = FontFamily.Monospace)
                        Text("uid=${myUid.ifBlank { "(blank)" }}", fontFamily = FontFamily.Monospace)
                        Text("ownerUid=${ownerUid.ifBlank { "(blank)" }}", fontFamily = FontFamily.Monospace)
                    }
                }
            }

            if (error != null) {
                ErrorCard(error!!)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { setErr(null) }) { Text("Clear error") }
                }
            }

            // Tabs
            ScrollableTabRow(
                selectedTabIndex = tabIndex,
                edgePadding = 0.dp
            ) {
                tabs.forEachIndexed { i, label ->
                    Tab(
                        selected = tabIndex == i,
                        onClick = { tabIndex = i },
                        text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }

            // Content gets remaining height
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
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
            }

            if (globalLoading) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

/* =========================================================
   USERS TAB (LIVE / REACTIVE)
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
    val users = remember { mutableStateListOf<UserRow>() }

    var search by rememberSaveable { mutableStateOf("") }
    var filterWarned by rememberSaveable { mutableStateOf(false) }
    var filterBanned by rememberSaveable { mutableStateOf(false) }

    // Selected user takes over whole Users tab until unselected
    var selectedDocId by rememberSaveable { mutableStateOf<String?>(null) }

    // "More" just increases live query limit (still realtime)
    var liveLimit by rememberSaveable { mutableIntStateOf(75) }

    // Selected details live doc listener
    var selectedDetail by remember { mutableStateOf<UserDetail?>(null) }
    var selectedDetailLoading by remember { mutableStateOf(false) }

    // ticker for relative times
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000L)
        }
    }

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

    // Live users list
    DisposableEffect(liveLimit) {
        setError(null)
        setGlobalLoading(true)

        val reg: ListenerRegistration = db.collection("users")
            .orderBy("lastSeenAt", Query.Direction.DESCENDING)
            .limit(liveLimit.toLong())
            .addSnapshotListener { snap, e ->
                if (e != null) {
                    setGlobalLoading(false)
                    setError(e.message ?: "Users live update failed")
                    return@addSnapshotListener
                }
                if (snap == null) {
                    setGlobalLoading(false)
                    return@addSnapshotListener
                }

                val next = snap.documents.map { d ->
                    val docId = d.id
                    val authUid = (d.getString("authUid") ?: d.getString("uid") ?: "").trim()
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
                }

                users.clear()
                users.addAll(next)
                setGlobalLoading(false)
            }

        onDispose { reg.remove() }
    }

    // Live selected doc details
    DisposableEffect(selectedDocId) {
        val docId = selectedDocId
        if (docId.isNullOrBlank()) {
            selectedDetail = null
            selectedDetailLoading = false
            return@DisposableEffect onDispose { }
        }

        selectedDetailLoading = true
        setError(null)

        val reg = db.collection("users")
            .document(docId)
            .addSnapshotListener { snap, e ->
                if (e != null) {
                    selectedDetailLoading = false
                    setError(e.message ?: "User detail live update failed")
                    return@addSnapshotListener
                }
                if (snap == null || !snap.exists()) {
                    selectedDetailLoading = false
                    selectedDetail = null
                    return@addSnapshotListener
                }

                fun s(key: String) = (snap.getString(key) ?: "").trim()
                fun b(key: String) = snap.getBoolean(key) ?: false
                fun l(key: String) = snap.getLong(key) ?: 0L

                val afkPresets = listOf(s("afkPreset1"), s("afkPreset2"), s("afkPreset3"))
                val cyclePresets = listOf(
                    s("cyclePreset1"),
                    s("cyclePreset2"),
                    s("cyclePreset3"),
                    s("cyclePreset4"),
                    s("cyclePreset5")
                )

                selectedDetail = UserDetail(
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
                selectedDetailLoading = false
            }

        onDispose { reg.remove() }
    }

    val selectedRow = remember(selectedDocId, users.size) {
        selectedDocId?.let { id -> users.firstOrNull { it.docId == id } }
    }

    // Detail view
    if (selectedRow != null) {
        val d = selectedDetail

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ElevatedCard {
                Column(
                    Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconButton(onClick = { selectedDocId = null }) {
                                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                            }
                            Column {
                                // 요구사항: name line should be "User"
                                Text("User", style = MaterialTheme.typography.titleMedium)
                                if (selectedRow.displayName.isNotBlank()) {
                                    Text(
                                        selectedRow.displayName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    "docId=${shortId(selectedRow.docId)}",
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        IconButton(onClick = { /* live already */ }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Live")
                        }
                    }

                    Text(
                        "authUid=${shortId(selectedRow.authUid.ifBlank { "(blank)" })}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "device=${shortId(selectedRow.deviceHash.ifBlank { "(blank)" })}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "warned=${selectedRow.warned}  banned=${selectedRow.banned}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )

                    val lastSeenRel = relativeTime(selectedRow.lastSeenAt, nowMs)
                    val updatedRel = relativeTime(selectedRow.updatedAt, nowMs)
                    Text(
                        "lastSeen=$lastSeenRel   updated=$updatedRel",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Divider()

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { clipboardCopy(selectedRow.docId) }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Copy docId")
                        }
                        if (selectedRow.authUid.isNotBlank()) {
                            OutlinedButton(onClick = { clipboardCopy(selectedRow.authUid) }) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Copy authUid")
                            }
                        }
                        if (selectedRow.deviceHash.isNotBlank()) {
                            OutlinedButton(onClick = { clipboardCopy(selectedRow.deviceHash) }) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Copy device")
                            }
                        }
                    }

                    Button(
                        onClick = {
                            onSendToModeration(
                                ModerationTarget(
                                    docId = selectedRow.docId,
                                    authUid = selectedRow.authUid,
                                    deviceHash = selectedRow.deviceHash,
                                    displayName = selectedRow.displayName
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.ArrowForward, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Send to Moderation")
                    }
                }
            }

            if (selectedDetailLoading) {
                ElevatedCard {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator()
                        Text("Loading details…")
                    }
                }
            } else if (d != null) {
                DetailBlock(d)
            } else {
                ElevatedCard {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Details", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "No detail loaded (doc missing or not yet written).",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
        }
        return
    }

    // Normal Users list view
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ElevatedCard {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Users", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${filteredUsers.size}/${users.size}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Warned")
                        Switch(checked = filterWarned, onCheckedChange = { filterWarned = it })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Banned")
                        Switch(checked = filterBanned, onCheckedChange = { filterBanned = it })
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            liveLimit = liveLimit.coerceAtLeast(1)
                            setError(null)
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Live") }

                    Button(
                        onClick = { liveLimit = (liveLimit + 75).coerceAtMost(1000) },
                        modifier = Modifier.weight(1f)
                    ) { Text("More") }
                }

                Text(
                    "Live limit: $liveLimit",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Divider()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (filteredUsers.isEmpty()) {
                Text("No users loaded/matching filters yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                filteredUsers.forEach { u ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedDocId = u.docId }
                    ) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(Modifier.weight(1f)) {
                                    // 요구사항: name shows "User" (not displayName)
                                    Text("User", style = MaterialTheme.typography.titleSmall)
                                    if (u.displayName.isNotBlank()) {
                                        Text(
                                            u.displayName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Text(
                                        "docId=${shortId(u.docId)}",
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        "authUid=${shortId(u.authUid.ifBlank { "(blank)" })}",
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        "device=${shortId(u.deviceHash.ifBlank { "(blank)" })}",
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        "warned=${u.warned}  banned=${u.banned}",
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Icon(Icons.Filled.ExpandMore, contentDescription = null)
                            }

                            val lastSeenRel = relativeTime(u.lastSeenAt, nowMs)
                            val updatedRel = relativeTime(u.updatedAt, nowMs)
                            Text(
                                "lastSeen=$lastSeenRel   updated=$updatedRel",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
    fun Mono(label: String, value: String) {
        Text(
            "$label=$value",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
    }

    ElevatedCard {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Live State", style = MaterialTheme.typography.titleSmall)
            Mono("afkEnabled", d.afkEnabled.toString())
            Mono("afkMessage", d.afkMessage.ifBlank { "(blank)" })
            Mono("cycleEnabled", d.cycleEnabled.toString())
            Mono("cycleIntervalSeconds", d.cycleIntervalSeconds.toString())
            Mono("spotifyEnabled", d.spotifyEnabled.toString())
            Mono("spotifyDemoEnabled", d.spotifyDemoEnabled.toString())
            Mono("spotifyPreset", d.spotifyPreset.toString())
            Mono("nowPlayingDetected", d.nowPlayingDetected.toString())
            Mono("nowPlayingIsPlaying", d.nowPlayingIsPlaying.toString())
            Mono("nowPlayingTitle", d.nowPlayingTitle.ifBlank { "(blank)" })
            Mono("nowPlayingArtist", d.nowPlayingArtist.ifBlank { "(blank)" })

            Spacer(Modifier.height(4.dp))
            Text("combinedPreviewText", style = MaterialTheme.typography.labelLarge)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Text(
                    d.combinedPreviewText.ifBlank { "(blank)" },
                    modifier = Modifier.padding(10.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (d.warnReason.isNotBlank() || d.banReason.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("Moderation Flags", style = MaterialTheme.typography.titleSmall)
                if (d.warnReason.isNotBlank()) Mono("warnReason", d.warnReason)
                if (d.banReason.isNotBlank()) Mono("banReason", d.banReason)
            }

            Spacer(Modifier.height(4.dp))
            Text("AFK Presets", style = MaterialTheme.typography.titleSmall)
            d.afkPresets.forEachIndexed { i, p ->
                Mono("afkPreset${i + 1}", p.ifBlank { "(blank)" })
            }

            Spacer(Modifier.height(4.dp))
            Text("Cycle Presets", style = MaterialTheme.typography.titleSmall)
            d.cyclePresets.forEachIndexed { i, p ->
                val oneLine = p.lines().firstOrNull()?.trim().orEmpty()
                Mono("cyclePreset${i + 1}", oneLine.ifBlank { "(blank)" })
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
   MODERATION TAB (LIVE + NO INDEX REQUIRED)
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

    fun clearLoaded() {
        userDocReg?.remove(); userDocReg = null
        deviceDocReg?.remove(); deviceDocReg = null
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
                if (dh.isNotBlank()) {
                    deviceDocReg?.remove()
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
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ElevatedCard {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Moderation", style = MaterialTheme.typography.titleMedium)
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

                OutlinedButton(
                    onClick = { scope.launch { loadTarget(t) } },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Reload (history + live)") }

                Text(
                    "warned=$liveWarned  banned=$liveBanned  deviceBanned=$deviceBanned",
                    fontFamily = FontFamily.Monospace
                )
                if (deviceBanned && deviceBanReason.isNotBlank()) {
                    Text("deviceBanReason=$deviceBanReason", fontFamily = FontFamily.Monospace)
                }
            }
        }

        // WARN
        ElevatedCard {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Warn", style = MaterialTheme.typography.titleSmall)

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
                Text("Ban", style = MaterialTheme.typography.titleSmall)

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
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("History", style = MaterialTheme.typography.titleSmall)
                    OutlinedButton(onClick = { scope.launch { loadHistoryNoIndex(t) } }) { Text("Reload") }
                }

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

        Spacer(Modifier.height(10.dp))
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

    Column(
        modifier = Modifier
            .fillMaxSize()
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

        Spacer(Modifier.height(10.dp))
    }
}

/* =========================================================
   CONFIG TAB (unchanged)
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
        Column(
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
private fun ErrorCard(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Error", style = MaterialTheme.typography.titleSmall)
            Text(message, fontFamily = FontFamily.Monospace)
        }
    }
}

/* =========================================================
   SMALL HELPERS
   ========================================================= */

private fun shortId(s: String, head: Int = 10, tail: Int = 6): String {
    val t = s.trim()
    if (t.isBlank()) return "(blank)"
    if (t.length <= head + tail + 1) return t
    return t.take(head) + "…" + t.takeLast(tail)
}

private fun relativeTime(ts: Timestamp?, nowMs: Long): String {
    if (ts == null) return "?"
    val then = ts.toDate().time
    val diff = nowMs - then
    if (diff < 0) return "0s ago"
    val s = diff / 1000L
    if (s < 60L) return "${s}s ago"
    val m = s / 60L
    if (m < 60L) return "${m}m ago"
    val h = m / 60L
    if (h < 24L) return "${h}h ago"
    val d = h / 24L
    return "${d}d ago"
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
