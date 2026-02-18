// app/src/main/kotlin/com/scrapw/chatbox/ChatboxScreen.kt
package com.scrapw.chatbox

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Divider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.scrapw.chatbox.ui.ChatboxViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class AppPage(val title: String) {
    Home("Home"),
    Automations("Automations"),
    Music("Music"),
    Debug("Debug"),
    Admin("Admin")
}

private enum class ChatboxAutomationsTab(val title: String) {
    AFK("AFK"),
    Cycle("Cycle")
}

private enum class ChatboxInfoTab(val title: String) {
    Overview("Overview"),
    Help("Help")
}

/** Simple persisted UI prefs (no VM changes required). */
private object UiPrefs {
    private const val FILE = "vrca_ui_prefs"
    private const val KEY_SPOTIFY_ENABLED = "spotify_enabled"
    private const val KEY_SPOTIFY_DEMO = "spotify_demo"
    private const val KEY_SPOTIFY_PRESET = "spotify_preset"
    private const val KEY_TUTORIAL_EXPANDED = "tutorial_expanded"

    fun readSpotifyEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).getBoolean(KEY_SPOTIFY_ENABLED, false)

    fun writeSpotifyEnabled(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).edit().putBoolean(KEY_SPOTIFY_ENABLED, v).apply()
    }

    fun readSpotifyDemo(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).getBoolean(KEY_SPOTIFY_DEMO, false)

    fun writeSpotifyDemo(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).edit().putBoolean(KEY_SPOTIFY_DEMO, v).apply()
    }

    fun readSpotifyPreset(ctx: Context): Int =
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).getInt(KEY_SPOTIFY_PRESET, 1).coerceIn(1, 5)

    fun writeSpotifyPreset(ctx: Context, v: Int) {
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).edit().putInt(KEY_SPOTIFY_PRESET, v.coerceIn(1, 5)).apply()
    }

    fun readTutorialExpanded(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).getBoolean(KEY_TUTORIAL_EXPANDED, true)

    fun writeTutorialExpanded(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).edit().putBoolean(KEY_TUTORIAL_EXPANDED, v).apply()
    }
}

/**
 * ✅ ToS acceptance storage.
 * We store an "accepted_version" integer locally.
 * Now that ToS is remote-configurable, "version" is dynamic.
 */
private object TosPrefs {
    private const val FILE = "vrca_tos"
    private const val KEY_ACCEPTED_VERSION = "accepted_version"
    private const val KEY_ACCEPTED_AT_MS = "accepted_at_ms"

    fun acceptedVersion(ctx: Context): Int =
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).getInt(KEY_ACCEPTED_VERSION, 0)

    fun accept(ctx: Context, version: Int) {
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).edit()
            .putInt(KEY_ACCEPTED_VERSION, version.coerceAtLeast(1))
            .putLong(KEY_ACCEPTED_AT_MS, System.currentTimeMillis())
            .apply()
    }
}

/**
 * ✅ Local profile storage (alias + optional displayName).
 * Alias is the important one. displayName defaults to alias.
 */
private object ProfilePrefs {
    private const val FILE = "vrca_profile"
    private const val KEY_ALIAS = "alias"
    private const val KEY_DISPLAY_NAME = "display_name"

    fun readAlias(ctx: Context): String =
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).getString(KEY_ALIAS, "")?.trim().orEmpty()

    fun writeAlias(ctx: Context, alias: String) {
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).edit().putString(KEY_ALIAS, alias.trim()).apply()
    }

    fun readDisplayName(ctx: Context): String =
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).getString(KEY_DISPLAY_NAME, "")?.trim().orEmpty()

    fun writeDisplayName(ctx: Context, name: String) {
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).edit().putString(KEY_DISPLAY_NAME, name.trim()).apply()
    }
}

/* =========================
   Remote UI models
   ========================= */

private data class RemoteTosUi(
    val tosVersion: Int = 1,
    val tosText: String = "",
    val tosUrl: String = "",
    val updatedAt: Timestamp? = null
)

private data class AnnouncementUi(
    val id: String,
    val title: String,
    val body: String,
    val active: Boolean,
    val priority: Int,
    val createdAt: Timestamp?
)

private data class ModerationUi(
    val warned: Boolean = false,
    val warnReason: String = "",
    val banned: Boolean = false,
    val banReason: String = "",
    val updatedAt: Timestamp? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatboxScreen(
    chatboxViewModel: ChatboxViewModel
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // --- Firebase (public + admin) ---
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }

    // Ensure users have a UID (anonymous auth).
    var authedUid by remember { mutableStateOf(auth.currentUser?.uid) }

    // ✅ Capture last Firebase issue for Debug ONLY
    var lastFirebaseIssue by remember { mutableStateOf<String?>(null) }

    fun reportFirebase(tag: String, msg: String, t: Throwable? = null) {
        val full = "[$tag] $msg" + (t?.let { " :: ${it.message ?: it::class.java.simpleName}" } ?: "")
        lastFirebaseIssue = full.take(4000)
        if (t != null) Log.w("VRC-A/Firebase", full, t) else Log.w("VRC-A/Firebase", full)
    }

    // --- Local profile (alias) ---
    var localAlias by rememberSaveable { mutableStateOf(ProfilePrefs.readAlias(ctx)) }
    var localDisplayName by rememberSaveable { mutableStateOf(ProfilePrefs.readDisplayName(ctx).ifBlank { localAlias }) }

    LaunchedEffect(Unit) {
        if (auth.currentUser == null) {
            runCatching {
                auth.signInAnonymously()
                    .addOnSuccessListener { res -> authedUid = res.user?.uid }
                    .addOnFailureListener { e -> reportFirebase("auth", "Anonymous auth failed", e) }
            }.onFailure { e ->
                reportFirebase("auth", "Anonymous auth failed", e)
            }
        } else {
            authedUid = auth.currentUser?.uid
        }
    }

    // ✅ Admin-build-only heartbeat for devices/{deviceHash}
    // Public build does NOT write devices/ at all.
    if (BuildConfig.IS_ADMIN_BUILD) {
        val deviceHash = remember { readDeviceHashFromPrefs(ctx) }

        LaunchedEffect(deviceHash) {
            if (deviceHash.isBlank()) return@LaunchedEffect

            // Write once immediately
            runCatching {
                db.collection("devices").document(deviceHash)
                    .set(
                        mapOf(
                            "lastSeenAt" to FieldValue.serverTimestamp(),
                            "appId" to BuildConfig.APPLICATION_ID,
                            "adminBuild" to true,
                            "updatedAt" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    )
            }.onFailure { e ->
                reportFirebase("devices", "Failed writing admin heartbeat", e)
            }

            // Then keep it fresh (every 2 minutes)
            while (true) {
                delay(120_000L)
                runCatching {
                    db.collection("devices").document(deviceHash)
                        .set(
                            mapOf(
                                "lastSeenAt" to FieldValue.serverTimestamp(),
                                "updatedAt" to FieldValue.serverTimestamp()
                            ),
                            SetOptions.merge()
                        )
                }.onFailure { e ->
                    reportFirebase("devices", "Failed updating admin heartbeat", e)
                }
            }
        }
    }

    // --- Remote config state ---
    var remoteTos by remember { mutableStateOf(RemoteTosUi()) }
    var announcements by remember { mutableStateOf<List<AnnouncementUi>>(emptyList()) }
    var moderation by remember { mutableStateOf(ModerationUi()) }

    // Listen: config/app (ToS)
    DisposableEffect(Unit) {
        var reg: ListenerRegistration? = null
        reg = db.collection("config").document("app")
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    reportFirebase("config/app", "Snapshot listener error", err)
                    return@addSnapshotListener
                }
                if (snap != null && snap.exists()) {
                    val v = (snap.getLong("tosVersion") ?: 1L).toInt().coerceAtLeast(1)
                    remoteTos = RemoteTosUi(
                        tosVersion = v,
                        tosText = snap.getString("tosText") ?: "",
                        tosUrl = snap.getString("tosUrl") ?: "",
                        updatedAt = snap.getTimestamp("updatedAt")
                    )
                }
            }
        onDispose { reg?.remove() }
    }

    // Listen: announcements (active only)
    DisposableEffect(Unit) {
        var reg: ListenerRegistration? = null
        reg = db.collection("announcements")
            .whereEqualTo("active", true)
            .orderBy("priority", Query.Direction.DESCENDING)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(30)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    reportFirebase("announcements", "Failed to load announcements", err)
                    return@addSnapshotListener
                }
                if (snap != null) {
                    val list = snap.documents.map { d ->
                        AnnouncementUi(
                            id = d.id,
                            title = d.getString("title") ?: "",
                            body = d.getString("body") ?: "",
                            active = d.getBoolean("active") ?: true,
                            priority = (d.getLong("priority") ?: 0L).toInt(),
                            createdAt = d.getTimestamp("createdAt")
                        )
                    }
                    announcements = list
                }
            }
        onDispose { reg?.remove() }
    }

    // Listen: moderation status for THIS user (public).
    DisposableEffect(authedUid) {
        var reg: ListenerRegistration? = null
        val uid = authedUid
        if (!uid.isNullOrBlank()) {
            reg = db.collection("users").document(uid)
                .addSnapshotListener { snap, err ->
                    if (err != null) {
                        reportFirebase("users/$uid", "Failed to load moderation", err)
                        return@addSnapshotListener
                    }
                    if (snap != null && snap.exists()) {
                        moderation = ModerationUi(
                            warned = snap.getBoolean("warned") ?: false,
                            warnReason = snap.getString("warnReason") ?: "",
                            banned = snap.getBoolean("banned") ?: false,
                            banReason = snap.getString("banReason") ?: "",
                            updatedAt = snap.getTimestamp("updatedAt")
                        )

                        // ✅ Also pull alias/displayName if present (nice for multi-device)
                        val remoteAlias = (snap.getString("alias") ?: "").trim()
                        val remoteName = (snap.getString("displayName") ?: "").trim()

                        if (remoteAlias.isNotBlank() && remoteAlias != localAlias) {
                            localAlias = remoteAlias
                            ProfilePrefs.writeAlias(ctx, remoteAlias)
                        }
                        val resolvedName = remoteName.ifBlank { remoteAlias }
                        if (resolvedName.isNotBlank() && resolvedName != localDisplayName) {
                            localDisplayName = resolvedName
                            ProfilePrefs.writeDisplayName(ctx, resolvedName)
                        }
                    } else {
                        moderation = ModerationUi()
                    }
                }
        }
        onDispose { reg?.remove() }
    }

    // --- ToS gate (remote) ---
    val requiredTosVersion = remoteTos.tosVersion.coerceAtLeast(1)
    var tosAccepted by rememberSaveable { mutableStateOf(TosPrefs.acceptedVersion(ctx) >= requiredTosVersion) }

    // If remote ToS version changes upward while app is open, re-gate.
    LaunchedEffect(requiredTosVersion) {
        tosAccepted = TosPrefs.acceptedVersion(ctx) >= requiredTosVersion
    }

    // ✅ Require alias before allowing ToS acceptance
    val aliasOk = localAlias.trim().length >= 2

    if (!tosAccepted || !aliasOk) {
        TosGate(
            tosVersion = requiredTosVersion,
            tosText = remoteTos.tosText,
            tosUrl = remoteTos.tosUrl,
            aliasValue = localAlias,
            onAliasChange = { new ->
                localAlias = new
                // also keep displayName in sync by default
                if (localDisplayName.isBlank() || localDisplayName == ProfilePrefs.readDisplayName(ctx).ifBlank { localAlias }) {
                    localDisplayName = new
                }
            },
            onOpenUrl = { url ->
                runCatching {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            },
            onAccept = {
                val finalAlias = localAlias.trim()
                if (finalAlias.length < 2) return@TosGate

                ProfilePrefs.writeAlias(ctx, finalAlias)
                if (localDisplayName.isBlank()) {
                    localDisplayName = finalAlias
                }
                ProfilePrefs.writeDisplayName(ctx, localDisplayName.trim().ifBlank { finalAlias })

                TosPrefs.accept(ctx, requiredTosVersion)
                tosAccepted = true
            }
        )
        return
    }

    // ✅ Self-profile heartbeat: keep users/{uid} updated (alias + lastSeenAt)
    // This is "best effort" and will silently fail if rules deny (captured in Debug).
    val deviceHashForUser = remember { readDeviceHashFromPrefs(ctx) }
    LaunchedEffect(authedUid, localAlias) {
        val uid = authedUid ?: return@LaunchedEffect
        if (uid.isBlank()) return@LaunchedEffect

        // Write immediately, then every 2 minutes
        suspend fun writeProfileOnce() {
            val alias = localAlias.trim()
            if (alias.isBlank()) return

            val displayName = localDisplayName.trim().ifBlank { alias }
            val data = hashMapOf<String, Any>(
                "alias" to alias,
                "displayName" to displayName,
                "lastSeenAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
                "appId" to BuildConfig.APPLICATION_ID,
                "versionName" to BuildConfig.VERSION_NAME,
                "versionCode" to BuildConfig.VERSION_CODE,
                "adminBuild" to BuildConfig.IS_ADMIN_BUILD
            )
            if (deviceHashForUser.isNotBlank()) {
                data["deviceHash"] = deviceHashForUser
            }

            runCatching {
                db.collection("users").document(uid).set(data, SetOptions.merge())
            }.onFailure { e ->
                reportFirebase("users/$uid", "Self-profile write blocked/failed", e)
            }
        }

        writeProfileOnce()
        while (true) {
            delay(120_000L)
            writeProfileOnce()
        }
    }

    // --- Ban gate (public + admin) ---
    var banStopRan by remember { mutableStateOf(false) }
    LaunchedEffect(moderation.banned) {
        if (moderation.banned && !banStopRan) {
            banStopRan = true
            runCatching { chatboxViewModel.killStopAndClear() }
        }
        if (!moderation.banned) banStopRan = false
    }

    var page by rememberSaveable { mutableStateOf(AppPage.Home) }

    // ✅ SAFETY: if PUBLIC build, never allow landing on Admin
    LaunchedEffect(Unit) {
        if (!BuildConfig.IS_ADMIN_BUILD && page == AppPage.Admin) page = AppPage.Home
    }

    var showSettingsSheet by remember { mutableStateOf(false) }
    var showInfoSheet by remember { mutableStateOf(false) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }

    // Apply persisted music UI settings once
    LaunchedEffect(Unit) {
        chatboxViewModel.setSpotifyEnabledFlag(UiPrefs.readSpotifyEnabled(ctx))
        chatboxViewModel.setSpotifyDemoFlag(UiPrefs.readSpotifyDemo(ctx))
        chatboxViewModel.updateSpotifyPreset(UiPrefs.readSpotifyPreset(ctx))
    }

    // If banned, always keep them on Home (so they see ban screen)
    LaunchedEffect(moderation.banned) {
        if (moderation.banned) page = AppPage.Home
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            DrawerContent(
                current = page,
                onSelect = { chosen ->
                    val safeChosen =
                        if (moderation.banned) AppPage.Home
                        else if (!BuildConfig.IS_ADMIN_BUILD && chosen == AppPage.Admin) AppPage.Home
                        else chosen

                    page = safeChosen
                    scope.launch { drawerState.close() }
                },
                onOpenSettings = {
                    showSettingsSheet = true
                    scope.launch { drawerState.close() }
                },
                onOpenInfo = {
                    showInfoSheet = true
                    scope.launch { drawerState.close() }
                }
            )
        }
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(if (BuildConfig.IS_ADMIN_BUILD) "VRC-A (ADMIN)" else "VRC-A") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Menu")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSettingsSheet = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    }
                )
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            contentWindowInsets = WindowInsets(0)
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // Global banners (warn + announcements) ONLY
                GlobalStatusBanner(
                    moderation = moderation,
                    announcements = announcements
                )

                Crossfade(targetState = page, label = "page_crossfade") { p ->
                    when (p) {
                        AppPage.Home -> {
                            if (moderation.banned) {
                                BannedScreen(
                                    banReason = moderation.banReason,
                                    onOpenInfo = { showInfoSheet = true },
                                    onOpenSettings = { showSettingsSheet = true }
                                )
                            } else {
                                HomePage(
                                    vm = chatboxViewModel,
                                    snackbarHostState = snackbarHostState,
                                    onOpenSettings = { showSettingsSheet = true },
                                    announcements = announcements,
                                    moderation = moderation,
                                    alias = localAlias,
                                    onAliasSaved = { newAlias ->
                                        localAlias = newAlias.trim()
                                        ProfilePrefs.writeAlias(ctx, localAlias)
                                        // keep displayName aligned unless user overrides later
                                        localDisplayName = localAlias
                                        ProfilePrefs.writeDisplayName(ctx, localDisplayName)
                                    }
                                )
                            }
                        }

                        AppPage.Automations -> AutomationsPage(chatboxViewModel)

                        AppPage.Music -> NowPlayingPage(
                            vm = chatboxViewModel,
                            onPersistSpotifyEnabled = { UiPrefs.writeSpotifyEnabled(ctx, it) },
                            onPersistSpotifyDemo = { UiPrefs.writeSpotifyDemo(ctx, it) },
                            onPersistSpotifyPreset = { UiPrefs.writeSpotifyPreset(ctx, it) }
                        )

                        AppPage.Debug -> DebugPage(
                            vm = chatboxViewModel,
                            lastFirebaseIssue = lastFirebaseIssue
                        )

                        AppPage.Admin -> {
                            if (BuildConfig.IS_ADMIN_BUILD && !moderation.banned) {
                                AdminScreen()
                            } else {
                                HomePage(
                                    vm = chatboxViewModel,
                                    snackbarHostState = snackbarHostState,
                                    onOpenSettings = { showSettingsSheet = true },
                                    announcements = announcements,
                                    moderation = moderation,
                                    alias = localAlias,
                                    onAliasSaved = { newAlias ->
                                        localAlias = newAlias.trim()
                                        ProfilePrefs.writeAlias(ctx, localAlias)
                                        localDisplayName = localAlias
                                        ProfilePrefs.writeDisplayName(ctx, localDisplayName)
                                    }
                                )
                            }
                        }
                    }
                }

                if (showSettingsSheet) {
                    SettingsSheet(
                        vm = chatboxViewModel,
                        onDismiss = { showSettingsSheet = false }
                    )
                }

                if (showInfoSheet) {
                    InfoSheet(onDismiss = { showInfoSheet = false })
                }
            }
        }
    }
}

/* =========================
   Global banners
   ========================= */

@Composable
private fun GlobalStatusBanner(
    moderation: ModerationUi,
    announcements: List<AnnouncementUi>
) {
    val topAnn = announcements.maxByOrNull { it.priority }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (moderation.warned && !moderation.banned) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("⚠️ Warning", style = MaterialTheme.typography.labelLarge)
                    Text(
                        moderation.warnReason.ifBlank { "You have been warned by moderators." },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (topAnn != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        topAnn.title.ifBlank { "Announcement" },
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        topAnn.body.ifBlank { "" },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/* =========================
   Ban screen
   ========================= */

@Composable
private fun BannedScreen(
    banReason: String,
    onOpenInfo: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Surface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Access restricted", style = MaterialTheme.typography.headlineSmall)

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("You are banned from using this app.", style = MaterialTheme.typography.titleSmall)
                    Text(
                        banReason.ifBlank { "No reason provided." },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("What you can do", style = MaterialTheme.typography.titleSmall)
                    Text("• You can still open Settings and Info.")
                    Text("• If this is a mistake, contact the app moderators.")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) { Text("Settings") }
                OutlinedButton(onClick = onOpenInfo, modifier = Modifier.weight(1f)) { Text("Info") }
            }
        }
    }
}

/* =========================
   ToS Gate UI (WITH ALIAS)
   ========================= */

@Composable
private fun TosGate(
    tosVersion: Int,
    tosText: String,
    tosUrl: String,
    aliasValue: String,
    onAliasChange: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onAccept: () -> Unit
) {
    var checked by rememberSaveable { mutableStateOf(false) }
    val aliasTrim = aliasValue.trim()
    val aliasOk = aliasTrim.length >= 2 && aliasTrim.length <= 24

    val fallbackText = remember {
        """
By using this app, you agree to:
• Use it responsibly and legally
• Not use it to harass, spam, or impersonate others
• Understand VRChat chatbox limits apply and messages may be trimmed
• Accept that settings/history are stored locally on your device

If you do not agree, close the app.
        """.trimIndent()
    }

    Surface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Terms of Service", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Version $tosVersion",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            ElevatedCard {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = (tosText.ifBlank { fallbackText }),
                        style = MaterialTheme.typography.bodyMedium
                    )

                    if (tosUrl.isNotBlank()) {
                        OutlinedButton(
                            onClick = { onOpenUrl(tosUrl.trim()) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Open full ToS link")
                        }
                    }
                }
            }

            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Pick your Alias", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "This is what admins will see in the user directory. You can change it later in Home.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = aliasValue,
                        onValueChange = { onAliasChange(it) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Alias") },
                        placeholder = { Text("e.g. Ash") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                    )

                    Text(
                        if (aliasOk) "OK"
                        else "Alias must be 2–24 characters.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (aliasOk) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("I agree to the Terms of Service")
                Switch(checked = checked, onCheckedChange = { checked = it })
            }

            Button(
                onClick = onAccept,
                enabled = checked && aliasOk,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Accept & Continue")
            }
        }
    }
}

/* =========================
   LEFT NAV DRAWER
   ========================= */

@Composable
private fun DrawerContent(
    current: AppPage,
    onSelect: (AppPage) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenInfo: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(min = 290.dp, max = 360.dp),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Navigation", style = MaterialTheme.typography.titleLarge)

            DrawerSectionHeader("Main")
            DrawerItem(
                title = AppPage.Home.title,
                icon = Icons.Filled.Home,
                selected = current == AppPage.Home,
                onClick = { onSelect(AppPage.Home) }
            )
            DrawerItem(
                title = AppPage.Automations.title,
                icon = Icons.Filled.Sync,
                selected = current == AppPage.Automations,
                onClick = { onSelect(AppPage.Automations) }
            )
            DrawerItem(
                title = AppPage.Music.title,
                icon = Icons.Filled.MusicNote,
                selected = current == AppPage.Music,
                onClick = { onSelect(AppPage.Music) }
            )

            DrawerSectionHeader("Tools")
            DrawerItem(
                title = AppPage.Debug.title,
                icon = Icons.Filled.BugReport,
                selected = current == AppPage.Debug,
                onClick = { onSelect(AppPage.Debug) }
            )

            if (BuildConfig.IS_ADMIN_BUILD) {
                DrawerItem(
                    title = AppPage.Admin.title,
                    icon = Icons.Filled.Gavel,
                    selected = current == AppPage.Admin,
                    onClick = { onSelect(AppPage.Admin) }
                )
            }

            Divider()

            DrawerSectionHeader("Setup")
            DrawerItem(
                title = "Settings & Permissions",
                icon = Icons.Filled.Settings,
                selected = false,
                onClick = onOpenSettings
            )
            DrawerItem(
                title = "Info",
                icon = Icons.Filled.Info,
                selected = false,
                onClick = onOpenInfo
            )

            Spacer(Modifier.weight(1f))

            Text(
                text = if (BuildConfig.IS_ADMIN_BUILD) "VRC-A (Admin build)" else "VRC-A",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DrawerSectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp)
    )
}

@Composable
private fun DrawerItem(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    NavigationDrawerItem(
        label = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = null) },
        modifier = Modifier.fillMaxWidth(),
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            unselectedContainerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f),
            selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}

/* =========================
   COMMON LAYOUT
   ========================= */

@Composable
private fun PageContainer(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    ElevatedCard {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .padding(end = 10.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 4,
                        overflow = TextOverflow.Clip
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 6,
                            overflow = TextOverflow.Clip
                        )
                    }
                }
                if (actions != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions
                    )
                }
            }
            content()
        }
    }
}

/* =========================
   HOME
   ========================= */

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun HomePage(
    vm: ChatboxViewModel,
    snackbarHostState: SnackbarHostState,
    onOpenSettings: () -> Unit,
    announcements: List<AnnouncementUi>,
    moderation: ModerationUi,
    alias: String,
    onAliasSaved: (String) -> Unit
) {
    val uiState by vm.messengerUiState.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val connectionBring = remember { BringIntoViewRequester() }
    val manualSendBring = remember { BringIntoViewRequester() }

    var ipInputText by rememberSaveable { mutableStateOf(uiState.ipAddress) }
    LaunchedEffect(uiState.ipAddress) {
        if (ipInputText.isBlank()) ipInputText = uiState.ipAddress
    }

    var tutorialExpanded by remember { mutableStateOf(UiPrefs.readTutorialExpanded(ctx)) }

    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    LaunchedEffect(Unit) { overlayGranted = Settings.canDrawOverlays(ctx) }

    val pm = remember(ctx) { ctx.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var batteryOk by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(ctx.packageName)) }
    LaunchedEffect(Unit) { batteryOk = pm.isIgnoringBatteryOptimizations(ctx.packageName) }

    val notifOk = vm.listenerConnected
    val ipOk = uiState.ipAddress.isNotBlank() && uiState.ipAddress != "127.0.0.1"

    val topAnnouncements = remember(announcements) {
        announcements
            .sortedWith(compareByDescending<AnnouncementUi> { it.priority }.thenByDescending { it.createdAt })
            .take(3)
    }

    // Alias editor
    var aliasDraft by rememberSaveable { mutableStateOf(alias) }
    LaunchedEffect(alias) { if (aliasDraft.isBlank()) aliasDraft = alias }

    PageContainer {
        // ✅ Profile card (alias edit later)
        SectionCard(
            title = "Profile",
            subtitle = "Your Alias is shown to admins (user directory)."
        ) {
            OutlinedTextField(
                value = aliasDraft,
                onValueChange = { aliasDraft = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Alias") },
                placeholder = { Text("e.g. Ash") }
            )

            val a = aliasDraft.trim()
            val ok = a.length in 2..24

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        if (!ok) return@Button
                        onAliasSaved(a)
                        scope.launch { snackbarHostState.showSnackbar("Alias saved") }
                    },
                    enabled = ok,
                    modifier = Modifier.weight(1f)
                ) { Text("Save Alias") }

                OutlinedButton(
                    onClick = { aliasDraft = alias },
                    modifier = Modifier.weight(1f)
                ) { Text("Reset") }
            }

            Text(
                text = "Current: ${alias.trim().ifBlank { "(not set)" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (topAnnouncements.isNotEmpty()) {
            SectionCard(
                title = "Announcements",
                subtitle = "Latest updates from the app team."
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    topAnnouncements.forEach { a ->
                        ElevatedCard(
                            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(a.title.ifBlank { "Announcement" }, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    a.body.ifBlank { "" },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }

        if (moderation.warned && !moderation.banned) {
            SectionCard(
                title = "Account warning",
                subtitle = "This warning is shown to you only."
            ) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("⚠️ You have been warned.", style = MaterialTheme.typography.titleSmall)
                        Text(
                            moderation.warnReason.ifBlank { "No reason provided." },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        SectionCard(
            title = "VRChat Preview",
            subtitle = "Exactly what will appear in your chatbox.",
            actions = {
                Button(
                    onClick = {
                        vm.startAfkSender()
                        vm.startCycle()
                        vm.startNowPlayingSender()
                    }
                ) { Text("Start") }

                Button(
                    onClick = { vm.killStopAndClear() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("KILL", color = MaterialTheme.colorScheme.onError)
                }
            }
        ) {
            val previewTextRaw = vm.debugLastCombinedOsc.ifBlank { "(nothing active)" }
            val previewText = remember(previewTextRaw) { vrChatSafePreview(previewTextRaw) }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(280.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = 420.dp)
                        .fillMaxWidth(0.92f),
                    tonalElevation = 3.dp,
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Box(
                        Modifier
                            .heightIn(min = 96.dp)
                            .padding(12.dp)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        SelectionContainer {
                            Text(
                                text = previewText,
                                modifier = Modifier.fillMaxWidth(),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                softWrap = true,
                                maxLines = 9,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Canvas(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                        .height(200.dp)
                        .width(170.dp)
                ) {
                    val w = size.width
                    val h = size.height

                    drawCircle(
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.08f),
                        radius = w * 0.18f,
                        center = Offset(w * 0.5f, h * 0.20f)
                    )

                    val path = Path().apply {
                        moveTo(w * 0.50f, h * 0.36f)
                        cubicTo(w * 0.18f, h * 0.40f, w * 0.18f, h * 0.96f, w * 0.50f, h * 0.98f)
                        cubicTo(w * 0.82f, h * 0.96f, w * 0.82f, h * 0.40f, w * 0.50f, h * 0.36f)
                        close()
                    }
                    drawPath(path, color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.06f))
                }
            }

            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Quick Toggles", style = MaterialTheme.typography.titleSmall)
                    ToggleRow("AFK", vm.afkEnabled) { vm.setAfkEnabledFlag(it) }
                    ToggleRow("Cycle", vm.cycleEnabled) { vm.setCycleEnabledFlag(it) }
                    ToggleRow("Now Playing", vm.spotifyEnabled) { vm.setSpotifyEnabledFlag(it) }
                }
            }
        }

        SectionCard(
            title = "Setup Tutorial",
            subtitle = "Tap each step to open settings or jump to the right place.",
            actions = {
                IconButton(onClick = {
                    tutorialExpanded = !tutorialExpanded
                    UiPrefs.writeTutorialExpanded(ctx, tutorialExpanded)
                }) {
                    Icon(
                        imageVector = if (tutorialExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null
                    )
                }
            }
        ) {
            AnimatedVisibility(
                visible = tutorialExpanded,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it / 3 }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 3 })
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TutorialStep(
                        number = 0,
                        title = "Enable OSC in VRChat",
                        subtitle = "VRChat → Settings → OSC → Enable OSC.",
                        icon = Icons.Filled.Bolt,
                        primary = "How"
                    ) { scope.launch { snackbarHostState.showSnackbar("Open VRChat → Settings → OSC → Enable OSC") } }

                    TutorialStep(
                        number = 1,
                        title = "Enable Notification Access",
                        subtitle = if (notifOk) "Enabled." else "Required for Now Playing detection.",
                        icon = Icons.Filled.MusicNote,
                        primary = "Open"
                    ) { ctx.startActivity(vm.notificationAccessIntent()) }

                    TutorialStep(
                        number = 2,
                        title = "Allow Overlay permission",
                        subtitle = if (overlayGranted) "Enabled." else "Only needed if you use overlay.",
                        icon = Icons.Filled.Bolt,
                        primary = "Open"
                    ) {
                        ctx.startActivity(vm.overlayPermissionIntent())
                        overlayGranted = Settings.canDrawOverlays(ctx)
                    }

                    TutorialStep(
                        number = 3,
                        title = "Disable Battery Optimization",
                        subtitle = if (batteryOk) "Disabled (good)." else "Stops Android pausing the app when screen is off.",
                        icon = Icons.Filled.Power,
                        primary = "Request"
                    ) {
                        ctx.startActivity(vm.batteryOptimizationIntent())
                        batteryOk = pm.isIgnoringBatteryOptimizations(ctx.packageName)
                    }

                    TutorialStep(
                        number = 4,
                        title = "Set Headset IP",
                        subtitle = if (ipOk) "Looks set." else "Quest/PC IP on the same Wi-Fi.",
                        icon = Icons.Filled.Wifi,
                        primary = "Go"
                    ) { scope.launch { connectionBring.bringIntoView() } }

                    TutorialStep(
                        number = 5,
                        title = "Manual Test Send",
                        subtitle = "Type a message and hit Send.",
                        icon = Icons.Filled.ChevronRight,
                        primary = "Go"
                    ) { scope.launch { manualSendBring.bringIntoView() } }
                }
            }
        }

        SectionCard(
            title = "Connection",
            subtitle = "Headset IP (Quest / PC)."
        ) {
            Column(Modifier.bringIntoViewRequester(connectionBring)) {
                OutlinedTextField(
                    value = ipInputText,
                    onValueChange = { s: String -> ipInputText = s },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Headset IP address") },
                    placeholder = { Text("Example: 192.168.1.23") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            val ip = ipInputText.trim()
                            runCatching { vm.ipAddressApply(ip) }
                                .onFailure {
                                    scope.launch {
                                        snackbarHostState.showSnackbar("IP apply failed. Check format (e.g. 192.168.1.23)")
                                    }
                                }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Apply") }

                    OutlinedButton(
                        onClick = { ipInputText = uiState.ipAddress },
                        modifier = Modifier.weight(1f)
                    ) { Text("Reset") }
                }

                Text(
                    text = "Current target: ${uiState.ipAddress}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        SectionCard(
            title = "Manual Send",
            subtitle = "One-off message (doesn’t affect AFK/Cycle/Now Playing)."
        ) {
            Column(Modifier.bringIntoViewRequester(manualSendBring)) {
                OutlinedTextField(
                    value = vm.messageText.value,
                    onValueChange = { v: TextFieldValue -> vm.onMessageTextChange(v) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    label = { Text("Message") }
                )
                Button(onClick = { vm.sendMessage() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Send")
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun TutorialStep(
    number: Int,
    title: String,
    subtitle: String,
    icon: ImageVector,
    primary: String,
    onPrimary: () -> Unit
) {
    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onPrimary() }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null)
                }
            }

            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = "$number. $title",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            TextButton(onClick = onPrimary, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                Text(primary)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Filled.ChevronRight, contentDescription = null)
            }
        }
    }
}

/* =========================
   AUTOMATIONS
   ========================= */

@Composable
private fun AutomationsPage(vm: ChatboxViewModel) {
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(ChatboxAutomationsTab.AFK) }

    val cycleLineFields = remember { mutableStateMapOf<Int, TextFieldValue>() }

    fun syncCycleLineFieldsFromVm() {
        val valid = vm.cycleLines.indices.toSet()
        cycleLineFields.keys.toList().forEach { if (it !in valid) cycleLineFields.remove(it) }
        vm.cycleLines.forEachIndexed { idx, text ->
            val existing = cycleLineFields[idx]
            if (existing == null || existing.text != text) cycleLineFields[idx] = TextFieldValue(text)
        }
    }

    LaunchedEffect(vm.cycleLines.size) { syncCycleLineFieldsFromVm() }
    LaunchedEffect(vm.cycleLines.toList()) { syncCycleLineFieldsFromVm() }

    fun afkPresetsPreview(): String {
        val parts = (1..3).map { slot ->
            val p = vm.getAfkPresetPreview(slot).ifBlank { "empty" }
            "${slot}:${p}"
        }
        return parts.joinToString("  •  ").let { if (it.length > 80) it.take(79) + "…" else it }
    }

    fun cyclePresetsPreview(): String {
        val parts = (1..5).map { slot ->
            val p = vm.getCyclePresetPreview(slot).ifBlank { "empty" }
            "${slot}:${p}"
        }
        return parts.joinToString("  •  ").let { if (it.length > 80) it.take(79) + "…" else it }
    }

    PageContainer {
        ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(10.dp)) {
                TabRow(selectedTabIndex = tab.ordinal) {
                    ChatboxAutomationsTab.entries.forEachIndexed { idx, t ->
                        Tab(
                            selected = (tab.ordinal == idx),
                            onClick = { tab = t },
                            text = { Text(t.title) }
                        )
                    }
                }
            }
        }

        when (tab) {
            ChatboxAutomationsTab.AFK -> {
                SectionCard(
                    title = "AFK",
                    subtitle = "AFK always appears above Cycle + Music."
                ) {
                    ToggleRow("AFK enabled", vm.afkEnabled) { vm.setAfkEnabledFlag(it) }

                    OutlinedTextField(
                        value = vm.afkMessage,
                        onValueChange = { s: String -> vm.updateAfkText(s) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("AFK text") }
                    )

                    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { vm.updateAfkPresetsCollapsed(!vm.afkPresetsCollapsed) },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("AFK Presets (3)", style = MaterialTheme.typography.titleSmall)
                                    if (vm.afkPresetsCollapsed) {
                                        Text(
                                            afkPresetsPreview(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Icon(
                                    imageVector = if (vm.afkPresetsCollapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                                    contentDescription = null
                                )
                            }

                            AnimatedVisibility(
                                visible = !vm.afkPresetsCollapsed,
                                enter = fadeIn() + slideInVertically(initialOffsetY = { it / 4 }),
                                exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 4 })
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    (1..3).forEach { slot ->
                                        ElevatedCard {
                                            Column(
                                                Modifier.padding(10.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                val preview = vm.getAfkPresetPreview(slot).ifBlank { "(empty)" }
                                                Text("Preset $slot — $preview")

                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    OutlinedButton(
                                                        onClick = { scope.launch { vm.loadAfkPreset(slot) } },
                                                        modifier = Modifier.weight(1f)
                                                    ) { Text("Load") }

                                                    Button(
                                                        onClick = { scope.launch { vm.saveAfkPreset(slot, vm.afkMessage) } },
                                                        modifier = Modifier.weight(1f)
                                                    ) { Text("Save") }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { vm.startAfkSender() },
                            modifier = Modifier.weight(1f),
                            enabled = vm.afkEnabled
                        ) { Text("Start") }

                        OutlinedButton(
                            onClick = { vm.stopAfkSender(clearFromChatbox = true) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Stop") }
                    }

                    OutlinedButton(
                        onClick = { vm.sendAfkNow() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = vm.afkEnabled
                    ) { Text("Send once") }
                }
            }

            ChatboxAutomationsTab.Cycle -> {
                SectionCard(
                    title = "Cycle",
                    subtitle = "Up to 10 lines. Stop clears instantly."
                ) {
                    ToggleRow("Cycle enabled", vm.cycleEnabled) { vm.setCycleEnabledFlag(it) }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (vm.cycleLines.isEmpty()) {
                            Text("No lines yet. Tap Add Line.", style = MaterialTheme.typography.bodySmall)
                        }

                        vm.cycleLines.forEachIndexed { idx, _ ->
                            val fieldValue = cycleLineFields[idx] ?: TextFieldValue(vm.cycleLines.getOrNull(idx).orEmpty())

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = fieldValue,
                                    onValueChange = { v: TextFieldValue ->
                                        cycleLineFields[idx] = v
                                        vm.updateCycleLine(idx, v.text)
                                    },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    label = { Text("Line ${idx + 1}") }
                                )
                                Spacer(Modifier.width(8.dp))
                                IconButton(onClick = { vm.removeCycleLine(idx) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Remove line")
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { vm.addCycleLine() },
                                enabled = vm.cycleLines.size < 10,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Add line (${vm.cycleLines.size}/10)")
                            }

                            OutlinedButton(
                                onClick = { vm.clearCycleLines() },
                                enabled = vm.cycleLines.isNotEmpty(),
                                modifier = Modifier.weight(1f)
                            ) { Text("Clear") }
                        }
                    }

                    Text(
                        text = "Cycle speed: fixed at 10 seconds",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { vm.updateCyclePresetsCollapsed(!vm.cyclePresetsCollapsed) },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Cycle Presets (5)", style = MaterialTheme.typography.titleSmall)
                                    if (vm.cyclePresetsCollapsed) {
                                        Text(
                                            cyclePresetsPreview(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Icon(
                                    imageVector = if (vm.cyclePresetsCollapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                                    contentDescription = null
                                )
                            }

                            AnimatedVisibility(
                                visible = !vm.cyclePresetsCollapsed,
                                enter = fadeIn() + slideInVertically(initialOffsetY = { it / 4 }),
                                exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 4 })
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    (1..5).forEach { slot ->
                                        ElevatedCard {
                                            Column(
                                                Modifier.padding(10.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                val preview = vm.getCyclePresetPreview(slot).ifBlank { "(empty)" }
                                                Text("Preset $slot — $preview")

                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    OutlinedButton(
                                                        onClick = { scope.launch { vm.loadCyclePreset(slot) } },
                                                        modifier = Modifier.weight(1f)
                                                    ) { Text("Load") }

                                                    Button(
                                                        onClick = { scope.launch { vm.saveCyclePreset(slot, vm.cycleLines.toList()) } },
                                                        modifier = Modifier.weight(1f)
                                                    ) { Text("Save") }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { vm.startCycle() },
                            modifier = Modifier.weight(1f),
                            enabled = vm.cycleEnabled && vm.cycleLines.any { it.trim().isNotEmpty() }
                        ) { Text("Start") }

                        OutlinedButton(
                            onClick = { vm.stopCycle(clearFromChatbox = true) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Stop") }
                    }
                }
            }
        }
    }
}

/* =========================
   MUSIC
   ========================= */

@Composable
private fun NowPlayingPage(
    vm: ChatboxViewModel,
    onPersistSpotifyEnabled: (Boolean) -> Unit,
    onPersistSpotifyDemo: (Boolean) -> Unit,
    onPersistSpotifyPreset: (Int) -> Unit
) {
    val ctx = LocalContext.current

    var previewT by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            previewT += 0.02f
            if (previewT > 1f) previewT -= 1f
            delay(60L)
        }
    }

    PageContainer {
        SectionCard(
            title = "Now Playing",
            subtitle = "Uses Notification Access. Stop clears instantly."
        ) {
            ToggleRow("Enable Now Playing block", vm.spotifyEnabled) {
                vm.setSpotifyEnabledFlag(it)
                onPersistSpotifyEnabled(it)
            }
            ToggleRow("Demo mode (testing)", vm.spotifyDemoEnabled) {
                vm.setSpotifyDemoFlag(it)
                onPersistSpotifyDemo(it)
            }

            OutlinedButton(
                onClick = { ctx.startActivity(vm.notificationAccessIntent()) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Open Notification Access settings") }

            Text(
                text = "Music refresh speed: fixed at 2 seconds",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text("Progress bar preset:", style = MaterialTheme.typography.labelLarge)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..5).forEach { p ->
                    val selected = (vm.spotifyPreset == p)
                    val name = vm.getMusicPresetName(p)

                    ElevatedCard(
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(10.dp)
                                .clickable {
                                    vm.updateSpotifyPreset(p)
                                    onPersistSpotifyPreset(p)
                                },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(text = name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = vm.renderMusicPresetPreview(p, previewT),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            if (selected) Text("Selected", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { vm.startNowPlayingSender() },
                    modifier = Modifier.weight(1f),
                    enabled = vm.spotifyEnabled
                ) { Text("Start") }

                OutlinedButton(
                    onClick = { vm.stopNowPlayingSender(clearFromChatbox = true) },
                    modifier = Modifier.weight(1f)
                ) { Text("Stop") }
            }

            OutlinedButton(
                onClick = { vm.sendNowPlayingOnce() },
                modifier = Modifier.fillMaxWidth(),
                enabled = vm.spotifyEnabled
            ) { Text("Send once now (test)") }
        }

        SectionCard(
            title = "Detected / Preview",
            subtitle = "If blank: enable access, restart app, then play music."
        ) {
            Text("Detected: ${vm.nowPlayingDetected}")
            Text("Artist: ${vm.lastNowPlayingArtist}")
            Text("Title: ${vm.lastNowPlayingTitle}")
            Text("App: ${vm.activePackage}")
            Text("Status: ${if (vm.nowPlayingIsPlaying) "Playing" else "Paused"}")
        }
    }
}

/* =========================
   DEBUG
   ========================= */

@Composable
private fun DebugPage(vm: ChatboxViewModel, lastFirebaseIssue: String?) {
    PageContainer {
        SectionCard(
            title = "Firebase (last issue)",
            subtitle = "Only shown here. Not shown as a global banner."
        ) {
            Text(
                text = lastFirebaseIssue ?: "(none captured)",
                fontFamily = FontFamily.Monospace
            )
        }

        SectionCard(
            title = "Listener",
            subtitle = "Confirms Notification Access + media detection."
        ) {
            Text("Listener connected: ${vm.listenerConnected}")
            Text("Active package: ${vm.activePackage}")
            Text("Detected: ${vm.nowPlayingDetected}")
            Text("Playing: ${vm.nowPlayingIsPlaying}")
        }

        SectionCard(
            title = "OSC Output Preview",
            subtitle = "Raw lines + combined output."
        ) {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("AFK:", style = MaterialTheme.typography.labelLarge)
                    Text(vm.debugLastAfkOsc, fontFamily = FontFamily.Monospace)

                    Text("Cycle:", style = MaterialTheme.typography.labelLarge)
                    Text(vm.debugLastCycleOsc, fontFamily = FontFamily.Monospace)

                    Text("Music:", style = MaterialTheme.typography.labelLarge)
                    Text(vm.debugLastMusicOsc, fontFamily = FontFamily.Monospace)

                    Text("Combined:", style = MaterialTheme.typography.labelLarge)
                    Text(vm.debugLastCombinedOsc, fontFamily = FontFamily.Monospace)
                }
            }
        }

        SectionCard(title = "VRChat send status") {
            Text("Last sent to VRChat (ms): ${vm.lastSentToVrchatAtMs}")
        }
    }
}

/* =========================
   SETTINGS SHEET
   ========================= */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    vm: ChatboxViewModel,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Settings, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Settings & Setup", style = MaterialTheme.typography.titleMedium)
            }

            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingsGroupHeader("Permissions")

                    SettingsRow(
                        icon = Icons.Filled.MusicNote,
                        title = "Notification Access",
                        subtitle = "Required for Now Playing detection.",
                        primary = "Open"
                    ) { ctx.startActivity(vm.notificationAccessIntent()) }

                    SettingsRow(
                        icon = Icons.Filled.Bolt,
                        title = "Overlay Permission",
                        subtitle = "Only needed if you use overlay.",
                        primary = "Open"
                    ) { ctx.startActivity(vm.overlayPermissionIntent()) }

                    SettingsRow(
                        icon = Icons.Filled.Power,
                        title = "Battery Optimization",
                        subtitle = "Stops Android pausing when screen is off.",
                        primary = "Request"
                    ) { ctx.startActivity(vm.batteryOptimizationIntent()) }
                }
            }

            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Close")
            }
        }
    }
}

@Composable
private fun SettingsGroupHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    primary: String,
    onPrimary: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onPrimary() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null)
            }
        }

        Spacer(Modifier.width(10.dp))

        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        TextButton(onClick = onPrimary) {
            Text(primary)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Filled.ChevronRight, contentDescription = null)
        }
    }
}

/* =========================
   INFO SHEET
   ========================= */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfoSheet(onDismiss: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(ChatboxInfoTab.Overview) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Info, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Info", style = MaterialTheme.typography.titleMedium)
            }

            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TabRow(selectedTabIndex = tab.ordinal) {
                        ChatboxInfoTab.entries.forEachIndexed { idx, t ->
                            Tab(
                                selected = (tab.ordinal == idx),
                                onClick = { tab = t },
                                text = { Text(t.title) }
                            )
                        }
                    }

                    val overview = remember {
                        """
VRC-A (VRChat Assistant)
by Ashoska Mitsu Sisko

• Sends OSC chatbox text to your Quest/PC target
• Includes: AFK, Cycle, Now Playing, Manual Send
• Use KILL to stop all senders and clear the VRChat chatbox
                        """.trimIndent()
                    }

                    val help = remember {
                        """
HELP

Nothing appears in VRChat:
• VRChat → Settings → OSC → Enable OSC
• Phone + headset on the same Wi-Fi
• Set the correct headset IP (Home → Connection)
• Try Manual Send

Now Playing blank:
• Enable Notification Access
• Reopen the app
• Start music so a notification exists

Stops sending with screen off:
• Disable Battery Optimization for VRC-A
                        """.trimIndent()
                    }

                    val text = when (tab) {
                        ChatboxInfoTab.Overview -> overview
                        ChatboxInfoTab.Help -> help
                    }

                    SelectionContainer {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Close")
            }
        }
    }
}

/**
 * Makes preview behave nicer:
 * - Inserts zero-width breaks into long unbroken tokens so Text can wrap
 * - Keeps newlines intact
 */
private fun vrChatSafePreview(input: String): String {
    val zwsp = '\u200B'
    val maxToken = 18

    fun breakLongToken(token: String): String {
        if (token.length <= maxToken) return token
        val sb = StringBuilder(token.length + token.length / maxToken)
        var i = 0
        while (i < token.length) {
            val end = (i + maxToken).coerceAtMost(token.length)
            sb.append(token.substring(i, end))
            if (end < token.length) sb.append(zwsp)
            i = end
        }
        return sb.toString()
    }

    return input.lines().joinToString("\n") { line ->
        line.split(" ").joinToString(" ") { breakLongToken(it) }
    }
}

/**
 * Reads the device hash that ChatboxApp caches into SharedPreferences "vrca_remote".
 * Key must match ChatboxApp.kt (RemoteKeys.DEVICE_ID_HASH).
 */
private fun readDeviceHashFromPrefs(ctx: Context): String {
    val prefs = ctx.getSharedPreferences("vrca_remote", MODE_PRIVATE)
    return prefs.getString("device_id_hash", "")?.trim().orEmpty()
}
