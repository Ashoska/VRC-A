package com.vrca.ui.screen

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.net.Uri
import android.provider.Settings.Secure
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.vrca.BuildConfig
import com.vrca.R
import com.vrca.admin.AdminScreen
import com.vrca.vrchat.VrchatAuthManager
import com.vrca.vrchat.VrchatPipelineState
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest

internal enum class AppPage(val title: String) {
    Home("Home"),
    Automations("Automations"),
    Music("Media"),
    VrchatStatus("VRChat"),
    Settings("Settings"),
    Admin("Admin")
}

private enum class ChatboxInfoTab(val title: String) {
    Overview("Overview"),
    Help("Help")
}

/** Simple persisted UI prefs (no VM changes required). */
internal object UiPrefs {
    private const val FILE = "vrca_ui_prefs"
    private const val KEY_SPOTIFY_ENABLED = "spotify_enabled"
    private const val KEY_SPOTIFY_DEMO = "spotify_demo"
    private const val KEY_SPOTIFY_PRESET = "spotify_preset"
    private const val KEY_TUTORIAL_EXPANDED = "tutorial_expanded"
    private const val KEY_PREVIEW_EXPANDED = "preview_expanded"
    private const val KEY_HOME_CARD_ORDER = "home_card_order"

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

    fun readPreviewExpanded(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).getBoolean(KEY_PREVIEW_EXPANDED, true)

    fun writePreviewExpanded(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).edit().putBoolean(KEY_PREVIEW_EXPANDED, v).apply()
    }

    /** Home PAGE card order (Preview / Connection / ManualSend) — distinct from
     *  the chatbox COMPONENT order (VrcaViewModel.cardOrder), which controls the
     *  top-to-bottom order of the OSC output itself. */
    val HOME_CARDS_DEFAULT = listOf("Preview", "Connection", "ManualSend")

    fun readHomeCardOrder(ctx: Context): List<String> {
        val raw = ctx.getSharedPreferences(FILE, MODE_PRIVATE)
            .getString(KEY_HOME_CARD_ORDER, null) ?: return HOME_CARDS_DEFAULT
        val saved = raw.split(",").map { it.trim() }.filter { it in HOME_CARDS_DEFAULT }
        // Append anything missing (new cards added in later versions).
        return saved + HOME_CARDS_DEFAULT.filter { it !in saved }
    }

    fun writeHomeCardOrder(ctx: Context, order: List<String>) {
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).edit()
            .putString(KEY_HOME_CARD_ORDER, order.joinToString(",")).apply()
    }
}

internal data class AnnouncementUi(
    val id: String,
    val title: String,
    val body: String,
    val active: Boolean,
    val priority: Int,
    val bodyDoc: String,
    val createdAt: Timestamp?
)

internal data class ModerationUi(
    val warned: Boolean = false,
    val warnReason: String = "",
    val banned: Boolean = false,
    val banReason: String = "",
    val deviceBanned: Boolean = false,
    val deviceBanReason: String = "",
    val updatedAt: Timestamp? = null
)

/* =========================
   Device hash (survives reinstall)
   ========================= */

private object DeviceId {
    private const val PREFS = "vrca_remote"
    private const val KEY_DEVICE_ID_HASH = "device_id_hash"

    fun read(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREFS, MODE_PRIVATE)
        return prefs.getString(KEY_DEVICE_ID_HASH, "")?.trim().orEmpty()
    }

    fun ensure(ctx: Context): String {
        val existing = read(ctx)
        if (existing.isNotBlank()) return existing

        // ANDROID_ID survives reinstall (same signing key + same Android user), changes on factory reset.
        val androidId = runCatching { Secure.getString(ctx.contentResolver, Secure.ANDROID_ID) }
            .getOrNull()
            ?.trim()
            .orEmpty()

        val seed = "v1:${androidId.ifBlank { "unknown" }}"
        val hash = sha256Hex(seed)

        ctx.getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_DEVICE_ID_HASH, hash)
            .apply()

        return hash
    }

    private fun sha256Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}

/**
 * Top-bar connection control (next to Settings). A Wi-Fi icon TINTED by the
 * current OSC target's reachability — green = reachable, red = no reply, neutral =
 * checking / not set (no text, per design). Tapping opens a small dropdown of the
 * device IPs (the manual slots for now; the account centre will add real synced
 * device IPs) — pick one to make it the OSC target, or "Edit / add IP…" for the
 * full field. On the headset it's automatic (localhost), so the dropdown just
 * shows that. Replaced the Home Connection card.
 */
@Composable
private fun ConnectionButton(vm: com.vrca.ui.viewmodel.VrcaViewModel) {
    val isHeadset = BuildConfig.IS_HEADSET_BUILD
    val repo = vm.userPreferencesRepository
    val scope = rememberCoroutineScope()

    val ip by repo.ipAddress.collectAsState(initial = "")
    var reachable by remember(ip) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(ip, isHeadset) {
        if (isHeadset) { reachable = true; return@LaunchedEffect }
        if (ip.isBlank() || ip == "127.0.0.1") { reachable = null; return@LaunchedEffect }
        while (true) {
            reachable = com.vrca.ui.onboarding.pingHost(ip)
            kotlinx.coroutines.delay(20_000L)
        }
    }

    var menuOpen by remember { mutableStateOf(false) }
    var editOpen by remember { mutableStateOf(false) }
    val tint = when {
        isHeadset || reachable == true -> Color(0xFF4CAF50)
        reachable == false -> MaterialTheme.colorScheme.error
        else -> LocalContentColor.current
    }

    Box {
        IconButton(onClick = { menuOpen = true }) {
            Icon(Icons.Filled.Wifi, contentDescription = "Connection", tint = tint)
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (isHeadset) {
                DropdownMenuItem(
                    text = { Text("This headset · 127.0.0.1") },
                    onClick = { menuOpen = false },
                    leadingIcon = { Icon(Icons.Filled.Wifi, null, tint = Color(0xFF4CAF50)) }
                )
            } else {
                val activeSlot by repo.activeIpSlot.collectAsState(initial = 1)
                val n1 by repo.ip1Name.collectAsState(initial = "Home")
                val n2 by repo.ip2Name.collectAsState(initial = "Hotspot")
                val n3 by repo.ip3Name.collectAsState(initial = "Other")
                val a1 by repo.ip1Address.collectAsState(initial = "")
                val a2 by repo.ip2Address.collectAsState(initial = "")
                val a3 by repo.ip3Address.collectAsState(initial = "")
                val slots = listOf(Triple(1, n1, a1), Triple(2, n2, a2), Triple(3, n3, a3))
                var anyShown = false
                slots.forEach { (slot, name, addr) ->
                    if (addr.isNotBlank()) {
                        anyShown = true
                        DropdownMenuItem(
                            text = { Text(if (slot == activeSlot) "$name · $addr  ✓" else "$name · $addr") },
                            onClick = {
                                scope.launch { repo.saveActiveIpSlot(slot) }
                                vm.ipAddressApply(addr)
                                menuOpen = false
                            }
                        )
                    }
                }
                if (anyShown) Divider()
                DropdownMenuItem(
                    text = { Text("Edit / add IP…") },
                    onClick = { menuOpen = false; editOpen = true }
                )
            }
        }
    }

    if (editOpen && !isHeadset) {
        com.vrca.ui.common.VrcaCardDialog(onDismiss = { editOpen = false }) {
            Column(
                Modifier.padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Connection", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Choose or enter the IP of the headset/PC running VRChat.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                com.vrca.ui.conversation.IpField(
                    chatboxViewModel = vm,
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(
                    onClick = { editOpen = false },
                    modifier = Modifier.align(Alignment.End)
                ) { Text("Done") }
            }
        }
    }
}

// Monitor-shape framing breakpoints (see the Scaffold content in VrcaScreen).
// A phone stays below the threshold (portrait ~360-420 dp), so it's never
// centered; Meta Quest's 1024 dp landscape panel is well above it, so the app
// reads as a centered monitor window there. MAX_CONTENT_WIDTH keeps the
// phone-designed cards at a comfortable reading width instead of stretching
// edge-to-edge across the wide panel.
private val HEADSET_WIDE_THRESHOLD = 640.dp
private val HEADSET_MAX_CONTENT_WIDTH = 720.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VrcaScreen(
    chatboxViewModel: com.vrca.ui.viewmodel.VrcaViewModel,
    // Rides VrcaApp's single config/app listener (no separate get here) — live.
    discordInvite: String = ""
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // --- Firebase (ToS + announcements only; moderation comes from ViewModel) ---
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }

    // Ensure device hash exists for this install/user (survives reinstall)
    val deviceHash = remember { DeviceId.ensure(ctx).trim() }

    var authedUid by remember { mutableStateOf(auth.currentUser?.uid?.trim()) }

    // Capture last Firebase issue for Debug ONLY (from ToS/announcements/auth)
    var lastFirebaseIssue by remember { mutableStateOf<String?>(null) }

    fun reportFirebase(tag: String, msg: String, t: Throwable? = null) {
        val full = "[$tag] $msg" + (t?.let { " :: ${it.message ?: it::class.java.simpleName}" } ?: "")
        lastFirebaseIssue = full.take(4000)
        if (t != null) Log.w("VRC-A/Firebase", full, t) else Log.w("VRC-A/Firebase", full)
    }

    fun safeUid(): String = authedUid?.trim().orEmpty()

    // Ensure anon auth ASAP (VM also does this, but we keep it for ToS/announcements listeners)
    LaunchedEffect(Unit) {
        if (auth.currentUser == null) {
            runCatching {
                auth.signInAnonymously().await()
                authedUid = auth.currentUser?.uid?.trim()
            }.onFailure { e ->
                reportFirebase("auth", "Anonymous auth failed", e)
            }
        } else {
            authedUid = auth.currentUser?.uid?.trim()
        }
    }

    var announcements by remember { mutableStateOf<List<AnnouncementUi>>(emptyList()) }
    // True once the announcements listener has actually delivered a snapshot, so the
    // media cull never runs on the initial empty list (which would wipe all cached
    // announcement media before the list loads, forcing a needless re-download).
    var announcementsLoaded by remember { mutableStateOf(false) }

    // Announcements (in-app display) — FOREGROUND-scoped snapshot listener: it
    // attaches while this screen is composed (app open) and detaches on
    // background/leave (onDispose), so the list updates LIVE while you're looking
    // and costs nothing while closed. The initial callback is one read (same as the
    // old one-shot get); a live push only bills when it actually lands while open.
    // The background-surviving service listener (attachAnnouncementsListener) is
    // still the source of the notification when the user isn't on this screen.
    DisposableEffect(Unit) {
        val reg = db.collection("announcements")
            .whereEqualTo("active", true)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(60)
            .addSnapshotListener { snap, err ->
                if (err != null) { reportFirebase("announcements", "Failed to load announcements", err); return@addSnapshotListener }
                if (snap == null) return@addSnapshotListener
                announcements = snap.documents.map { d ->
                    AnnouncementUi(
                        id = d.id,
                        title = d.getString("title") ?: "",
                        body = d.getString("body") ?: "",
                        active = d.getBoolean("active") ?: true,
                        priority = (d.getLong("priority") ?: 0L).toInt(),
                        bodyDoc = d.getString("bodyDoc") ?: "",
                        createdAt = d.getTimestamp("createdAt")
                    )
                }.sortedWith(
                    compareByDescending<AnnouncementUi> { it.priority }
                        .thenByDescending { it.createdAt }
                )
                announcementsLoaded = true
            }
        onDispose { reg.remove() }
    }

    // Announcement rich media: prefetch into the ann/ cache and cull anything no
    // longer referenced by an active announcement (an admin removing/swapping media
    // frees the user's storage on the next snapshot). Zero Firestore cost.
    LaunchedEffect(announcements, announcementsLoaded) {
        com.vrca.ui.common.AnnouncementSeenState.ensureLoaded(ctx)
        val urls = announcements.flatMap {
            com.vrca.richcontent.resolveRichDoc(it.bodyDoc, it.body)?.mediaUrls() ?: emptyList()
        }
        urls.forEach {
            com.vrca.richcontent.RichMediaStore.ensureCachedAsync(
                ctx, it, com.vrca.richcontent.RichMediaStore.Scope.ANNOUNCEMENT
            )
        }
        // Cull ONLY after the list has genuinely loaded — never on the initial empty.
        com.vrca.richcontent.RichMediaStore.gcAnnouncements(ctx, urls.toSet(), confirmed = announcementsLoaded)
    }

    // --- Moderation state comes from ViewModel ---
    val moderation = remember(
        chatboxViewModel.warned,
        chatboxViewModel.warnReason,
        chatboxViewModel.uidBanned,
        chatboxViewModel.banReason,
        chatboxViewModel.deviceBanned,
        chatboxViewModel.deviceBanReason
    ) {
        ModerationUi(
            warned = chatboxViewModel.warned,
            warnReason = chatboxViewModel.warnReason,
            banned = chatboxViewModel.uidBanned,
            banReason = chatboxViewModel.banReason,
            deviceBanned = chatboxViewModel.deviceBanned,
            deviceBanReason = chatboxViewModel.deviceBanReason,
            updatedAt = null
        )
    }

    // --- Ban gate (from VM) ---
    val isBannedEffective = chatboxViewModel.isBanned

    // Ensure a one-time stop/clear when the ban flips on
    var banStopRan by remember { mutableStateOf(false) }
    LaunchedEffect(isBannedEffective) {
        if (isBannedEffective && !banStopRan) {
            banStopRan = true
            runCatching { chatboxViewModel.killStopAndClear() }
        }
        if (!isBannedEffective) banStopRan = false
    }

    var page by rememberSaveable { mutableStateOf(AppPage.Home) }

    // Safety: if PUBLIC build, never allow landing on Admin
    LaunchedEffect(Unit) {
        if (!BuildConfig.IS_ADMIN_BUILD && page == AppPage.Admin) page = AppPage.Home
    }


    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }

    // Restore demo + preset (idempotent). NOTE: we intentionally do NOT reset the
    // Spotify/Time toggles here anymore. The runtime ViewModel is now scoped to the
    // process (Application ViewModelStore), so this composable re-enters on every
    // Activity recreation; resetting toggles here would wrongly kill a chatbox the
    // user left running when they backgrounded the app. Toggles already start OFF on
    // a fresh process (VM defaults) and must survive Activity recreation.
    LaunchedEffect(Unit) {
        val demo = UiPrefs.readSpotifyDemo(ctx)
        val preset = UiPrefs.readSpotifyPreset(ctx)
        if (chatboxViewModel.spotifyDemoEnabled != demo) chatboxViewModel.setSpotifyDemoFlag(demo)
        if (chatboxViewModel.spotifyPreset != preset) chatboxViewModel.updateSpotifyPreset(preset)
    }

    // If banned, always keep them on Home (so they see ban screen)
    LaunchedEffect(isBannedEffective) {
        if (isBannedEffective) page = AppPage.Home
    }

    // Setup health: VRChat linked + IP set. Reads vm.vrchatLoggedOut so a
    // mid-session sign-out (Settings → Accounts) flips this reactively. Feeds
    // the Home health checklist and the red dot on the Home nav icon — the
    // old every-tab "Setup incomplete" banner is gone (Home-only checklist).
    val vrcLinked = !chatboxViewModel.vrchatLoggedOut &&
        VrchatAuthManager.isLoggedIn(ctx) &&
        VrchatAuthManager.getStoredUserId(ctx)?.isNotBlank() == true
    val ipSet = remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        chatboxViewModel.userPreferencesRepository.ipAddress.collect { ip ->
            ipSet.value = ip.isNotBlank() && ip != "127.0.0.1"
        }
    }
    val setupNeedsAttention = ipSet.value != null && (!vrcLinked || ipSet.value == false)

    // Discord community invite (public build): the admin sets the link in
    // config/app.discordInvite; the top-bar button opens it. The value is passed in
    // from VrcaApp's single config/app listener (merged read + live). Blank → hidden.

    // (Single-session HARD-DENY removed — VRC-A is multi-device; several devices on
    // the same VRChat account are all allowed. See docs/account-system-plan.md §5.)

    // Tapping the top bar (e.g. the "VRC-A" title) clears any focused text field /
    // dismisses the keyboard — the page background isn't always reachable when cards
    // cover it. The icon buttons consume their own taps, so they still work.
    val topBarFocus = androidx.compose.ui.platform.LocalFocusManager.current
    Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    modifier = Modifier.pointerInput(Unit) {
                        detectTapGestures(onTap = { topBarFocus.clearFocus() })
                    },
                    title = { Text(if (BuildConfig.IS_ADMIN_BUILD) "VRC-A (ADMIN)" else "VRC-A") },
                    navigationIcon = {
                        if (BuildConfig.IS_ADMIN_BUILD) {
                            IconButton(onClick = { page = AppPage.Admin }) {
                                Icon(Icons.Filled.Gavel, contentDescription = "Admin")
                            }
                        } else if (discordInvite.isNotBlank()) {
                            IconButton(onClick = {
                                runCatching {
                                    ctx.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(discordInvite))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_discord),
                                    contentDescription = "Join our Discord"
                                )
                            }
                        }
                    },
                    actions = {
                        ConnectionButton(vm = chatboxViewModel)
                        IconButton(onClick = { page = AppPage.Settings }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    }
                )
            },
            // Bottom navigation bar -- always visible, labelled
            bottomBar = {
                if (!isBannedEffective) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = page == AppPage.Home,
                            onClick = { page = AppPage.Home },
                            icon = {
                                // Red dot = the Home setup-health checklist has
                                // unresolved items (the only cross-tab signal;
                                // the checklist itself lives on Home only).
                                BadgedBox(badge = { if (setupNeedsAttention) Badge() }) {
                                    Icon(Icons.Filled.Home, contentDescription = null)
                                }
                            },
                            label = { Text("Home") }
                        )
                        NavigationBarItem(
                            selected = page == AppPage.Automations,
                            onClick = { page = AppPage.Automations },
                            icon = { Icon(Icons.Filled.Sync, contentDescription = null) },
                            label = { Text("Automations") }
                        )
                        NavigationBarItem(
                            selected = page == AppPage.Music,
                            onClick = { page = AppPage.Music },
                            icon = { Icon(Icons.Filled.MusicNote, contentDescription = null) },
                            label = { Text("Media") }
                        )
                        NavigationBarItem(
                            selected = page == AppPage.VrchatStatus,
                            onClick = { page = AppPage.VrchatStatus },
                            icon = { Icon(Icons.Filled.AccountCircle, contentDescription = null) },
                            label = { Text("VRChat") }
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            contentWindowInsets = WindowInsets(0)
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
              // Monitor-shape framing for WIDE panels — Meta Quest's 16:10
              // 1024x640 dp default panel (also tablets/foldables). Center the
              // page content in a comfortable max-width column so a Quest build
              // reads as a monitor app instead of a stretched-wide phone. Keyed on
              // the ACTUAL available width (not IS_HEADSET_BUILD), so it's a
              // genuine responsive improvement AND never affects a normal phone
              // (its width stays below the threshold). The top/bottom bars keep the
              // full panel width as the window "chrome"; only the content centers.
              BoxWithConstraints(Modifier.fillMaxSize()) {
                val contentModifier = if (maxWidth >= HEADSET_WIDE_THRESHOLD) {
                    Modifier
                        .fillMaxHeight()
                        .widthIn(max = HEADSET_MAX_CONTENT_WIDTH)
                        .align(Alignment.TopCenter)
                } else {
                    Modifier.fillMaxSize()
                }
                Box(contentModifier) {
                Crossfade(targetState = page, label = "page_crossfade") { p ->
                    when (p) {
                        AppPage.Home -> {
                            if (isBannedEffective) {
                                BannedScreen(
                                    uid = safeUid(),
                                    deviceHash = deviceHash,
                                    banReason = moderation.banReason,
                                    deviceBanReason = moderation.deviceBanReason,
                                    onOpenInfo = { page = AppPage.Settings },
                                    onOpenSettings = { page = AppPage.Settings }
                                )
                            } else {
                                HomePage(
                                    vm = chatboxViewModel,
                                    onNavigate = { page = it },
                                    announcements = announcements,
                                    moderation = moderation,
                                    isBanned = false,
                                    vrcLinked = vrcLinked
                                )
                            }
                        }

                        AppPage.Automations -> AutomationsPage(chatboxViewModel, isBanned = isBannedEffective)

                        AppPage.Music -> NowPlayingPage(
                            vm = chatboxViewModel,
                            isBanned = isBannedEffective,
                            onPersistSpotifyPreset = { UiPrefs.writeSpotifyPreset(ctx, it) }
                        )

                        AppPage.VrchatStatus -> VrchatStatusPage(vm = chatboxViewModel)

                        AppPage.Settings -> SettingsPage(
                            vm = chatboxViewModel,
                            lastFirebaseIssue = lastFirebaseIssue,
                            moderationError = chatboxViewModel.moderationLastError
                        )

                        AppPage.Admin -> {
                            if (BuildConfig.IS_ADMIN_BUILD && !isBannedEffective) {
                                AdminScreen()
                            } else {
                                HomePage(
                                    vm = chatboxViewModel,
                                    onNavigate = { page = it },
                                    announcements = announcements,
                                    moderation = moderation,
                                    isBanned = isBannedEffective,
                                    vrcLinked = vrcLinked
                                )
                            }
                        }
                    }
                }
                } // Box(contentModifier)
              } // BoxWithConstraints

            }
        }
}

/* =========================
   Global banners (WARNINGS ONLY)
   ========================= */

@Composable
private fun GlobalStatusBanner(
    moderation: ModerationUi
) {
    if (!moderation.warned || (moderation.banned || moderation.deviceBanned)) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Warning", style = MaterialTheme.typography.labelLarge)
                Text(
                    moderation.warnReason.ifBlank { "You have been warned by moderators." },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/* =========================
   Ban screen
   ========================= */

@Composable
private fun BannedScreen(
    uid: String,
    deviceHash: String,
    banReason: String,
    deviceBanReason: String,
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

                    val reasons = buildList {
                        if (banReason.isNotBlank()) add("Ban reason: $banReason")
                        if (deviceBanReason.isNotBlank()) add("Device ban: $deviceBanReason")
                    }.ifEmpty { listOf("No reason provided.") }

                    reasons.forEach { r ->
                        Text(r, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("IDs (for support/admin)", style = MaterialTheme.typography.titleSmall)
                    Text("uid=${uid.ifBlank { "?" }}", fontFamily = FontFamily.Monospace)
                    Text(
                        "deviceHash=${deviceHash.take(16).ifBlank { "?" }}...",
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("What you can do", style = MaterialTheme.typography.titleSmall)
                    Text("- You can still open Settings and Info.")
                    Text("- If this is a mistake, contact the app moderators.")
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
   LEFT NAV DRAWER
   ========================= */

@Composable
private fun DrawerContent(
    current: AppPage,
    onSelect: (AppPage) -> Unit
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

            Divider()

            DrawerSectionHeader("Setup")
            DrawerItem(
                title = AppPage.Settings.title,
                icon = Icons.Filled.Settings,
                selected = current == AppPage.Settings,
                onClick = { onSelect(AppPage.Settings) }
            )

            if (BuildConfig.IS_ADMIN_BUILD) {
                DrawerItem(
                    title = AppPage.Admin.title,
                    icon = Icons.Filled.Gavel,
                    selected = current == AppPage.Admin,
                    onClick = { onSelect(AppPage.Admin) }
                )
            }

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
internal fun PageContainer(
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
    onViewport: ((topInWindowPx: Float, heightPx: Int) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    // Tapping empty space anywhere on a page clears text-field focus (and the
    // keyboard). detectTapGestures on a parent only sees taps the children
    // didn't claim, so buttons/fields keep working normally.
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (onViewport != null) Modifier.onGloballyPositioned {
                    onViewport(it.positionInWindow().y, it.size.height)
                } else Modifier
            )
            .verticalScroll(scrollState)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { focusManager.clearFocus() })
            }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
internal fun SectionCard(
    title: String,
    subtitle: String? = null,
    titleStyle: androidx.compose.ui.text.TextStyle? = null,
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
                    // The title must always render WHOLE on ONE line and never
                    // move ("VRChat Preview" was ellipsized when the wider
                    // "Sending" chip squeezed it). On width overflow the font
                    // shrinks a notch and relayouts until the full text fits.
                    val baseTitleStyle = titleStyle ?: MaterialTheme.typography.titleMedium
                    var fittedTitleStyle by remember(baseTitleStyle, title) {
                        mutableStateOf(baseTitleStyle)
                    }
                    Text(
                        text = title,
                        style = fittedTitleStyle,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { result ->
                            // With overflow=Ellipsis the truncated layout "fits",
                            // so didOverflowWidth stays false — isLineEllipsized
                            // is the signal that actually fires.
                            if ((result.isLineEllipsized(0) || result.didOverflowWidth) &&
                                fittedTitleStyle.fontSize.isSp &&
                                fittedTitleStyle.fontSize.value > 11f
                            ) {
                                fittedTitleStyle = fittedTitleStyle.copy(
                                    fontSize = fittedTitleStyle.fontSize * 0.92f
                                )
                            }
                        }
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
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions
                    )
                }
            }
            content()
        }
    }
}


/**
 * Makes preview behave nicer:
 * - Inserts zero-width breaks into long unbroken tokens so Text can wrap
 * - Keeps newlines intact
 */
internal fun vrChatSafePreview(input: String): String {
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

/* =========================
   VRChat status page
   Shows presence card + login/logout controls
   ========================= */

private object StatusBannerState {
    // Shared, observable so the banner on every tab (Home, VRChat) collapses
    // and expands together instead of tracking independent local state.
    var expanded by mutableStateOf(true)
}

@Composable
internal fun VrchatStatusBanner() {
    val statusData by VrchatPipelineState.statusPageFlow.collectAsState()
    val data = statusData ?: return

    if (data.indicator == "none") return

    val bannerColor = when (data.indicator) {
        "critical" -> MaterialTheme.colorScheme.error
        "major" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.tertiary
    }
    val containerColor = when (data.indicator) {
        "critical" -> MaterialTheme.colorScheme.errorContainer
        "major" -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val onContainerColor = when (data.indicator) {
        "critical" -> MaterialTheme.colorScheme.onErrorContainer
        "major" -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onTertiaryContainer
    }

    val ctx = LocalContext.current

    val title = data.description.ifBlank {
        when (data.indicator) {
            "critical" -> "Major System Outage"
            "major" -> "Significant System Issues"
            else -> "Service Degraded"
        }
    }
    val affected = data.components.filter { it.status != "operational" }

    val innerCardColor = lerp(containerColor, onContainerColor, 0.12f)

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { StatusBannerState.expanded = !StatusBannerState.expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Canvas(Modifier.size(10.dp)) {
                    drawCircle(color = bannerColor)
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = onContainerColor,
                    modifier = Modifier.weight(1f)
                )
                if (!StatusBannerState.expanded && affected.isNotEmpty()) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = onContainerColor.copy(alpha = 0.12f)
                    ) {
                        Text(
                            "${affected.size} affected",
                            style = MaterialTheme.typography.labelSmall,
                            color = onContainerColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                Icon(
                    imageVector = if (StatusBannerState.expanded) Icons.Filled.ExpandLess
                        else Icons.Filled.ExpandMore,
                    contentDescription = if (StatusBannerState.expanded) "Collapse" else "Expand",
                    tint = onContainerColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            AnimatedVisibility(visible = StatusBannerState.expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (affected.isNotEmpty()) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = innerCardColor,
                            shadowElevation = 1.dp
                        ) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                Text(
                                    "Affected systems",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = onContainerColor.copy(alpha = 0.6f),
                                    modifier = Modifier.padding(vertical = 6.dp)
                                )
                                for ((idx, c) in affected.withIndex()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Canvas(Modifier.size(6.dp)) {
                                            drawCircle(color = bannerColor)
                                        }
                                        Text(
                                            c.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = onContainerColor,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Surface(
                                            shape = MaterialTheme.shapes.small,
                                            color = bannerColor.copy(alpha = 0.15f)
                                        ) {
                                            Text(
                                                c.status.replace("_", " ")
                                                    .replaceFirstChar { it.uppercase() },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = bannerColor,
                                                modifier = Modifier.padding(
                                                    horizontal = 8.dp, vertical = 3.dp)
                                            )
                                        }
                                    }
                                    if (idx < affected.lastIndex) {
                                        Divider(
                                            color = onContainerColor.copy(alpha = 0.1f),
                                            thickness = 0.5.dp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (data.incidents.isNotEmpty()) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = innerCardColor,
                            shadowElevation = 1.dp
                        ) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                Text(
                                    "Latest updates",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = onContainerColor.copy(alpha = 0.6f),
                                    modifier = Modifier.padding(vertical = 6.dp)
                                )
                                val incidents = data.incidents.take(2)
                                for ((idx, inc) in incidents.withIndex()) {
                                    Column(
                                        Modifier.padding(vertical = 6.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            inc.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = onContainerColor
                                        )
                                        if (inc.latestUpdate.isNotBlank()) {
                                            Text(
                                                inc.latestUpdate,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = onContainerColor.copy(alpha = 0.7f)
                                            )
                                        }
                                    }
                                    if (idx < incidents.lastIndex) {
                                        Divider(
                                            color = onContainerColor.copy(alpha = 0.1f),
                                            thickness = 0.5.dp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = innerCardColor,
                        shadowElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth().clickable {
                            val intent = Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://status.vrchat.com"))
                            ctx.startActivity(intent)
                        }
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint = onContainerColor,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                "View VRChat Status Page",
                                style = MaterialTheme.typography.labelSmall,
                                color = onContainerColor
                            )
                        }
                    }
                }
            }
        }
    }
}

