package com.vrca.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.vrca.BuildConfig
import com.vrca.app.FeatureSessionStore
import com.vrca.app.VrcaApplication
import com.vrca.nowplaying.NowPlayingState
import com.vrca.nowplaying.TitleCleaner
import com.vrca.data.UserPreferencesRepository
import com.vrca.osc.VrcaOsc
import com.vrca.vrchat.VrchatPipelineState
import com.vrca.ui.conversation.ConversationUiState
import com.vrca.ui.conversation.Message
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * VrcaViewModel (DEVICE-FIRST) \u2014 RESTORED + RULES-COMPAT
 *
 * \u2705 Canonical doc:
 *   users/{deviceHash}
 *
 * \u2705 UID mapping (matches YOUR RULES file):
 *   usersById/{authUid} -> { deviceHash, authUid, appId, adminBuild, updatedAt }
 *
 * NOTE (important):
 * - Admin build and Public build can have DIFFERENT anon-auth UIDs.
 * - If both write to the SAME users/{deviceHash}, your rules will deny one of them.
 * - So: Admin build does NOT self-sync (no background writes) to avoid UID tug-of-war.
 *
 * MODERATION ENFORCEMENT:
 * - Public build listens to users/{deviceHash} for warned/banned flags (+ reasons)
 * - Optional: listens to bannedDevices/{deviceHash} (legacy device ban doc)
 * - When banned, ALL OSC sends are blocked (including typing, realtime, AFK/Cycle/Music, manual send).
 */
class VrcaViewModel(
    private val app: VrcaApplication,
    val userPreferencesRepository: UserPreferencesRepository,
    private val savedState: SavedStateHandle = SavedStateHandle()
) : ViewModel() {

    companion object {
        private lateinit var instance: VrcaViewModel

        private const val CYCLE_INTERVAL_SECONDS_LOCKED = 10
        private const val MUSIC_REFRESH_SECONDS_LOCKED = 1

        private const val VRC_MAX_CHARS = 144
        private const val VRC_MAX_LINES = 9
        // Chars reserved from the 144 budget for the minimal-background control
        // suffix (U+0003+U+001F, appended in VrcaOsc) while that toggle is ON.
        private const val MINIMAL_BG_RESERVED_CHARS = 2

        private const val SEND_FLOOR_MS = 500L

        private const val META_STABLE_MS = 1_100L
        private const val META_CONFIRM_MOVE_MS = 900L
        private const val META_GIVE_UP_MS = 2_400L
        private const val POS_RESET_CONFIRM_MS = 1_800L

        private const val NO_MOVE_PAUSE_MS = 5_000L
        private const val UI_TICK_MS = 500L

        // Firestore sync throttles
        // 30s idle debounce: after the user STOPS editing presets/messages/intervals
        // for this long, ONE delta write flushes all changed content to Firestore.
        // Any new edit before it fires cancels and reschedules, so a burst of edits
        // costs exactly one write. This is what stops "edit then close → reverts on
        // reopen" (the app-open read no longer overwrites a not-yet-synced edit).
        private const val SELF_SYNC_DEBOUNCE_MS = 30_000L

        // Uptime-counter restore grace: >15 min so it spans the watchdog's
        // ~15-min recovery cycle after an OEM kill (same reasoning as the
        // Discord RPC ONLINE_GRACE_MS).
        private const val UPTIME_RESTORE_GRACE_MS = 20 * 60_000L
        // Live-mode write interval — only used when an admin is watching.
        private const val LIVE_SYNC_INTERVAL_MS = 10_000L
        // When an admin is browsing (dashboard/users list) but not actively
        // watching this user's detail, push volatile preview/nowPlaying at a
        // slower cadence so the directory shows current output cheaply.
        private const val BROWSE_VOLATILE_SYNC_INTERVAL_MS = 30_000L

        // Hourly liveness heartbeat. This is the SOLE periodic write in the
        // steady state: one write per hour, anchored to when the user opened
        // the app (the cold-open write at init), then +1h, +2h, … It refreshes
        // lastActiveAt so admins can determine online/offline (online =
        // lastActiveAt within ~65 min) and pushes any content that changed
        // since the last write. Critically this is an in-PROCESS coroutine
        // loop (NOT an AlarmManager wakeup): a swiped/OS-killed app simply
        // stops firing it, so it goes stale and is correctly counted offline.
        // An AlarmManager wakeup would resurrect a dead app to write a
        // heartbeat, making it report "online" forever and breaking offline
        // detection entirely.
        private const val HOURLY_HEARTBEAT_MS = 60L * 60L * 1000L

        // Cold-open liveness throttle. The cold-open performSelfSync() is the "got
        // online" write, but the background-survival stack (START_STICKY, the ~15-min
        // watchdog, BootReceiver, OEM killers) resurrects the process repeatedly — and
        // each resurrection re-ran the cold-open delta write (4 liveness fields) plus a
        // self-listener echo read. That per-restart cost, multiplied by frequent kills,
        // is the dominant "steady Firestore traffic even with no admin browsing" source.
        // If the last ACTUAL liveness write was within this window, a liveness-ONLY
        // cold-open write is skipped — lastActiveAt is still fresh, so online status is
        // unaffected, and the hourly heartbeat is anchored to the last real write
        // (below) so a write still lands within the 65-min staleness window. Content
        // deltas and the first-ever sync are NEVER throttled.
        private const val COLD_OPEN_LIVENESS_THROTTLE_MS = 20L * 60L * 1000L
        // Persisted wall-clock of the last successful self-sync write, so the throttle
        // and the hourly anchor survive process death (the in-memory field resets).
        private const val PREF_LAST_SELF_SYNC_MS = "last_self_sync_ms"

        // Moderation attach retry
        private const val MOD_ATTACH_RETRY_MS = 1_250L

        // SharedPrefs (must match AdminScreen + VrcaApp/MainActivity)
        private const val REMOTE_PREFS_FILE = "vrca_remote"
        private const val PREF_DEVICE_ID_HASH = "device_id_hash"
        private const val PREF_AUTH_UID = "auth_uid"
        private const val PREF_LAST_CROSS_DEVICE_SYNC_MS = "last_cross_device_sync_ms"
        // Cross-device sync runs on VM creation; throttle so OS-kill relaunches don't
        // repeat the collection read every few minutes.
        private const val CROSS_DEVICE_SYNC_THROTTLE_MS = 30L * 60L * 1000L
        private const val PREF_LAST_SYNCED_JSON = "last_synced_values_json"

        // Collections (MUST MATCH YOUR RULES)
        private const val COL_USERS = "users"             // users/{deviceHash}
        private const val COL_USERS_BY_ID = "usersById"   // usersById/{uid}
        private const val COL_BANNED_DEVICES = "bannedDevices"

        @MainThread
        fun isInstanceInitialized(): Boolean = ::instance.isInitialized

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                // Resolve the Application from the process-wide handle rather than
                // CreationExtras[APPLICATION_KEY]: this VM is created against the
                // Application's own ViewModelStore, whose CreationExtras do NOT carry
                // APPLICATION_KEY, so reading it here would NPE.
                val application = VrcaApplication.instance
                // NOTE: a plain SavedStateHandle() is used (not createSavedStateHandle()).
                // This VM is owned by the Application's process-lifetime ViewModelStore,
                // which has no SavedStateRegistry, so createSavedStateHandle() would throw.
                // Toggles intentionally start OFF on a fresh process anyway; the in-memory
                // singleton preserves them across Activity recreation.
                instance = VrcaViewModel(
                    app = application,
                    userPreferencesRepository = application.userPreferencesRepository,
                    savedState = SavedStateHandle()
                )
                Log.d("VrcaViewModel", "Init")
                instance
            }
        }

        @MainThread
        fun getInstance(): VrcaViewModel {
            if (!::instance.isInitialized) throw Exception("VrcaViewModel is not initialized!")
            return instance
        }
    }

    override fun onCleared() {
        uiTickJob?.cancel()
        syncTriggerJob?.cancel()
        hourlyHeartbeatJob?.cancel()
        liveSyncJob?.cancel()
        keepaliveJob?.cancel()
        moderationAttachJob?.cancel()
        moderationUserReg?.remove()
        moderationDeviceReg?.remove()
        stopAll(clearFromChatbox = false)
        // Swipe is the only path that clears the app-scoped ViewModelStore, so when
        // AppShutdown is already shutting down it owns the offline write (offline
        // marker + content snapshot, awaited before the process kill) — skip here so
        // the swipe costs ONE write, not two. This GlobalScope write remains only as a
        // defensive fallback for any future path that clears the VM without AppShutdown.
        if (com.vrca.app.AppShutdown.isShuttingDown()) {
            super.onCleared()
            return
        }
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch {
            runCatching {
                val deviceHash = readDeviceHashFromPrefs()
                if (isValidDeviceHash(deviceHash)) {
                    db.collection(COL_USERS).document(deviceHash)
                        .set(mapOf(
                            "isOnlineInApp" to false,
                            // offlineAt is the authoritative clean-shutdown marker the
                            // admin reads: when it's newer than lastActiveAt the user
                            // shows offline INSTANTLY (the admin no longer trusts the
                            // isOnlineInApp flag alone — see isUserOnline). onCleared
                            // only runs on a real swipe now (app-scoped ViewModel).
                            // NOTE: deliberately do NOT bump lastSeenAt here — a shutdown
                            // must not refresh the liveness mirror, or isUserOnline's
                            // `offlineAt > (lastActiveAt ?? lastSeenAt)` test could tie
                            // (same serverTimestamp) and keep the user falsely online.
                            "offlineAt" to FieldValue.serverTimestamp()
                        ), SetOptions.merge())
                        .await()
                }
            }
        }
        super.onCleared()
    }

    // =========================
    // Firebase
    // =========================
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private var syncTriggerJob: Job? = null
    private var hourlyHeartbeatJob: Job? = null
    private var lastSelfSyncAtMs: Long = 0L
    private var usersByIdLinkWritten: Boolean = false
    private var lastSelfSyncError: String = ""

    // Per-field snapshot of what we last successfully wrote to Firestore.
    // applyRemoteConfig compares incoming snapshot values against these to
    // distinguish our own echoes (match → skip) from genuine admin edits
    // (differ → apply). This replaces fingerprint-based echo suppression
    // which was fragile due to empty-line filtering, volatile fields, and
    // race conditions between heartbeat and self-sync writes.
    private val lastSyncedValues = mutableMapOf<String, Any?>()

    // Set by applyRemoteContentBeforeSync when the SERVER read confirms the user's
    // doc already exists. The cold-open performSelfSync uses this to write LIVENESS
    // ONLY (never content/toggles) for an existing doc — so a cold-open write can
    // physically never overwrite an admin's offline edit (the edit lives on the
    // server; the apply path + moderation listener pull it into local; the later
    // debounced/hourly write pushes any genuine user edit up). A true first install
    // (doc absent) still does the full creating write.
    @Volatile private var remoteDocConfirmedExists = false

    // True when restoreFeatureSession() resumed an ACTIVE OSC session after an OS
    // kill. Used by applyOfflineToggleEdits to avoid letting a stale server `false`
    // (no baseline) stop a just-revived user's OSC. A deliberate admin disable
    // (remote != baseline) still applies.
    @Volatile private var restoredActiveSending = false

    // Gate self-sync until DataStore has provided its initial values. Without this
    // gate, an immediate sync on cold start writes the empty default ViewModel
    // state to Firestore, which then echoes back via the snapshot listener and
    // wipes the user's saved content from DataStore.
    @Volatile private var initialDataLoaded = false

    private fun prefs() = app.getSharedPreferences(REMOTE_PREFS_FILE, Context.MODE_PRIVATE)

    private fun readDeviceHashFromPrefs(): String =
        prefs().getString(PREF_DEVICE_ID_HASH, "")?.trim().orEmpty()

    private fun readCachedUid(): String =
        prefs().getString(PREF_AUTH_UID, "")?.trim().orEmpty()

    private fun writeCachedUid(uid: String) {
        prefs().edit().putString(PREF_AUTH_UID, uid.trim()).apply()
    }

    private fun isValidDeviceHash(h: String): Boolean {
        val s = h.trim()
        return s.length in 16..128
    }

    // The liveness fields written on every delta sync. A delta containing only these
    // (no content change) is what the cold-open throttle skips on a rapid restart.
    private val LIVENESS_KEYS = setOf("isOnlineInApp", "lastActiveAt", "lastSeenAt", "updatedAt")
    private val TOGGLE_KEYS = setOf("afkEnabled", "cycleEnabled", "spotifyEnabled", "spotifyDemoEnabled", "timeEnabled")
    // Volatile preview/nowPlaying/presence keys carried by captureStateForSync so the
    // hourly delta refreshes an unwatched user's directory row. Excluded from the
    // swipe-time offline write (captureContentForOfflineWrite) — that write is
    // documented to omit volatile preview, and the pipeline service's own offline
    // write already carries final presence.
    private val VOLATILE_SYNC_KEYS = setOf(
        "combinedPreviewText", "nowPlayingDetected", "nowPlayingIsPlaying",
        "nowPlayingTitle", "nowPlayingArtist", "activePackage",
        "vrchatUserId", "vrchatDisplayName", "vrchatState", "vrchatStatus",
        "vrchatStatusDescription", "vrchatWorld", "vrchatLocation",
        "vrchatInstancePlayerCount", "vrchatInstanceCapacity",
        "vrchatPlatform", "vrchatIsOnline"
    )

    private fun readLastSelfSyncMs(): Long = prefs().getLong(PREF_LAST_SELF_SYNC_MS, 0L)

    /** Record a successful self-sync write time, in memory and persisted. */
    private fun markSelfSyncWritten() {
        val now = System.currentTimeMillis()
        lastSelfSyncAtMs = now
        runCatching { prefs().edit().putLong(PREF_LAST_SELF_SYNC_MS, now).apply() }
    }

    private fun persistLastSyncedValues() {
        runCatching {
            val obj = JSONObject()
            for ((key, value) in lastSyncedValues) {
                when (value) {
                    null -> obj.put(key, JSONObject.NULL)
                    is Boolean -> obj.put(key, value)
                    is Int -> obj.put(key, value)
                    is Long -> obj.put(key, value)
                    is String -> obj.put(key, value)
                    else -> obj.put(key, value.toString())
                }
            }
            prefs().edit().putString(PREF_LAST_SYNCED_JSON, obj.toString()).apply()
        }
    }

    private fun loadLastSyncedValues() {
        runCatching {
            val json = prefs().getString(PREF_LAST_SYNCED_JSON, null) ?: return
            val obj = JSONObject(json)
            lastSyncedValues.clear()
            for (key in obj.keys()) {
                if (obj.isNull(key)) {
                    lastSyncedValues[key] = null
                    continue
                }
                val v = obj.get(key)
                lastSyncedValues[key] = when (v) {
                    is Boolean -> v
                    is Number -> v.toInt()
                    is String -> v
                    else -> v.toString()
                }
            }
        }
    }

    private suspend fun ensureAnonAuth(): String? {
        return runCatching {
            if (auth.currentUser == null) auth.signInAnonymously().await()
            val uid = auth.currentUser?.uid
            if (!uid.isNullOrBlank()) writeCachedUid(uid)
            uid
        }.getOrNull()
    }

    /**
     * Content snapshot for users/{deviceHash} \u2014 written on app open and on
     * debounced user edits. Excludes live/volatile fields (nowPlaying, preview,
     * lastReportedTime) which are only synced when an admin is watching this
     * user via the live-sync loop. See [buildLivePayload].
     */
    private fun buildUserSnapshot(authUid: String, deviceHash: String): Map<String, Any> {
        val cycleClean = cycleLines.map { it.trim() }.filter { it.isNotEmpty() }.take(10)

        val data = linkedMapOf<String, Any>(
            "docId" to deviceHash,
            "docIdType" to "deviceHash",
            "authUid" to authUid,
            "uid" to authUid,
            "currentUid" to authUid,
            "deviceHash" to deviceHash,

            "appId" to BuildConfig.APPLICATION_ID,
            "adminBuild" to BuildConfig.IS_ADMIN_BUILD,
            "versionName" to BuildConfig.VERSION_NAME,
            "versionCode" to BuildConfig.VERSION_CODE,

            "isOnlineInApp" to true,
            // lastActiveAt is the canonical liveness field for the hourly
            // model. lastSeenAt/updatedAt are still written (same timestamp,
            // zero extra write cost) so older admin builds that read them keep
            // working — additive migration, nothing removed.
            "lastActiveAt" to FieldValue.serverTimestamp(),
            "lastSeenAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),

            "afkEnabled" to afkEnabled,
            "afkMessage" to afkMessage.trim(),

            "cycleEnabled" to cycleEnabled,
            "cycleIntervalSeconds" to cycleIntervalSeconds,
            "cycleLines" to cycleClean,
            "cycleLinesText" to cycleClean.joinToString("\n"),

            "spotifyEnabled" to spotifyEnabled,
            "spotifyDemoEnabled" to spotifyDemoEnabled,
            "spotifyPreset" to spotifyPreset,

            "timeEnabled" to timeEnabled,
            "timeMode" to timeMode
        )

        data["afkPreset1"] = getAfkPresetPreview(1)
        data["afkPreset2"] = getAfkPresetPreview(2)
        data["afkPreset3"] = getAfkPresetPreview(3)

        data["cyclePreset1"] = cyclePresetMessages.getOrNull(0)?.trim().orEmpty()
        data["cyclePreset2"] = cyclePresetMessages.getOrNull(1)?.trim().orEmpty()
        data["cyclePreset3"] = cyclePresetMessages.getOrNull(2)?.trim().orEmpty()
        data["cyclePreset4"] = cyclePresetMessages.getOrNull(3)?.trim().orEmpty()
        data["cyclePreset5"] = cyclePresetMessages.getOrNull(4)?.trim().orEmpty()

        // Live output preview — included in every self-sync write (debounced
        // on edits) so admins can see the user's current chatbox output
        // without needing to actively "watch" them. Live-mode sync writes a
        // higher-frequency version (every 500ms) for real-time playback.
        data["combinedPreviewText"] = combinedPreviewText.trim()
        data["nowPlayingDetected"] = nowPlayingDetected
        data["nowPlayingIsPlaying"] = nowPlayingIsPlaying
        data["nowPlayingTitle"] = lastNowPlayingTitle.takeIf { it != "(blank)" }?.trim().orEmpty()
        data["nowPlayingArtist"] = lastNowPlayingArtist.takeIf { it != "(blank)" }?.trim().orEmpty()
        data["activePackage"] = activePackage

        // Profile pictures are intentionally NOT written to Firestore (cost): the
        // admin panel resolves VRChat+ pictures on demand by vrchatUserId using the
        // admin's own VRChat session (see AdminAvatar / VrchatImageLoader).

        // Multi-IP slots
        val activeSlot = runCatching {
            kotlinx.coroutines.runBlocking { userPreferencesRepository.activeIpSlot.first() }
        }.getOrDefault(1)
        data["activeIpSlot"] = activeSlot

        // Only set displayName when we actually have a VRChat name to write —
        // otherwise we'd push an empty string on every app-open and clobber
        // any value already on the doc.
        com.vrca.vrchat.VrchatAuthManager.getStoredDisplayName(app)
            ?.takeIf { it.isNotBlank() }
            ?.let { data["displayName"] = it }

        // Include VRChat presence if available — piggybacks on self-sync writes
        // so the admin can see location/status without active watching.
        val presence = VrchatPipelineState.presence
        if (presence != null) {
            data["vrchatUserId"] = presence.userId
            data["vrchatDisplayName"] = presence.displayName
            data["vrchatState"] = presence.state
            data["vrchatStatus"] = presence.status
            data["vrchatStatusDescription"] = presence.statusDescription
            data["vrchatWorld"] = presence.worldName
            data["vrchatLocation"] = presence.location
            data["vrchatInstancePlayerCount"] = presence.instancePlayerCount
            data["vrchatInstanceCapacity"] = presence.instanceCapacity
            data["vrchatPlatform"] = presence.platform
            data["vrchatAvatarThumb"] = presence.currentAvatarThumbnailUrl
            data["vrchatIsOnline"] = presence.isOnlineInVRChat
        }

        return data
    }

    /**
     * Live/volatile payload \u2014 only written when an admin is watching this user
     * (see [AdminWatchState]). These fields change too frequently to write on
     * every edit; gating them behind the watch flag keeps Firestore traffic
     * near zero when nobody is looking.
     */
    private fun buildLivePayload(): Map<String, Any> = mapOf(
        "nowPlayingDetected" to nowPlayingDetected,
        "nowPlayingIsPlaying" to nowPlayingIsPlaying,
        "nowPlayingTitle" to lastNowPlayingTitle.takeIf { it != "(blank)" }?.trim().orEmpty(),
        "nowPlayingArtist" to lastNowPlayingArtist.takeIf { it != "(blank)" }?.trim().orEmpty(),
        "activePackage" to activePackage,
        "combinedPreviewText" to combinedPreviewText.trim(),
        "cycleTrimWarning" to cycleTrimWarning.trim(),
        // Feature toggles stream with the watched 10s loop so the admin's
        // Pinned/Cycle/Music/Time button states reflect a user's toggle change in
        // real time while watching. (They also persist via the hourly delta write —
        // captureStateForSync carries them — so an unwatched toggle still surfaces.)
        "afkEnabled" to afkEnabled,
        "cycleEnabled" to cycleEnabled,
        "spotifyEnabled" to spotifyEnabled,
        "timeEnabled" to timeEnabled,
        "lastReportedTime" to if (timeEnabled) currentTimeString() else "",
        "lastTimeUpdateAt" to FieldValue.serverTimestamp(),
        "lastActiveAt" to FieldValue.serverTimestamp(),
        "lastSeenAt" to FieldValue.serverTimestamp()
    )

    /**
     * \u2705 UID mapping per YOUR RULES:
     * usersById/{uid} keys must be ONLY:
     *   deviceHash, authUid, appId, adminBuild, updatedAt
     */
    private fun buildUsersByIdLink(authUid: String, deviceHash: String): Map<String, Any> {
        return linkedMapOf(
            "deviceHash" to deviceHash,
            "authUid" to authUid,
            "appId" to BuildConfig.APPLICATION_ID,
            "adminBuild" to BuildConfig.IS_ADMIN_BUILD,
            "updatedAt" to FieldValue.serverTimestamp()
        )
    }

    /**
     * Schedules ONE debounced delta write [SELF_SYNC_DEBOUNCE_MS] (30s) after the
     * last edit/toggle. Each call cancels and reschedules the pending write, so a
     * burst of edits flushes exactly once. [performSelfSync] writes only changed
     * content + the liveness fields (delta vs [lastSyncedValues]), so an idle user
     * who isn't editing schedules nothing extra here — the call sites only fire on
     * genuine content/toggle changes (NOT on NowPlaying ticks or preview rebuilds).
     *
     * This exists because removing the per-edit write made edits sync only on the
     * hourly heartbeat, so "edit a preset then close the app" reverted on reopen
     * (the app-open read overwrote the not-yet-synced edit). Runs in the app-scoped
     * viewModelScope so the flush still fires if the app is backgrounded after
     * editing; a swipe is handled separately (AppShutdown writes content + offline
     * synchronously). While an admin watches, the 10s live loop also streams output.
     */
    private fun startSelfSyncLoopIfNeeded() {
        if (BuildConfig.IS_ADMIN_BUILD) return
        // Don't schedule during cold-start content load — the DataStore collectors
        // fire on first emission before the baseline is ready; performSelfSync also
        // guards on initialDataLoaded, but skipping here avoids a pointless job.
        if (!initialDataLoaded) return
        // 30s idle debounce. Each edit cancels the pending write and reschedules, so
        // a burst of edits flushes ONCE (one delta write — performSelfSync writes
        // only changed content + liveness). Runs in viewModelScope, which is
        // app-scoped, so the flush still fires if the user backgrounds the app after
        // editing. A swipe is handled separately (AppShutdown writes content + offline
        // synchronously before the process is killed).
        syncTriggerJob?.cancel()
        syncTriggerJob = viewModelScope.launch {
            delay(SELF_SYNC_DEBOUNCE_MS)
            performSelfSync()
        }
    }

    /**
     * Live-mode loop: writes volatile fields (nowPlaying, preview, etc.)
     * every few seconds, but ONLY while [AdminWatchState.isWatched] is true.
     * Started once from init and self-gates internally — flipping the watch
     * flag toggles the loop on/off without any extra Firestore reads.
     */
    private var liveSyncJob: Job? = null

    private fun startLiveSyncWatcher() {
        if (BuildConfig.IS_ADMIN_BUILD) return
        if (liveSyncJob != null) return
        liveSyncJob = viewModelScope.launch {
            // collectLatest auto-cancels the previous block when isWatched changes,
            // so flipping the flag to false stops the inner write loop instantly.
            com.vrca.sync.AdminWatchState.isWatched.collectLatest { watched ->
                if (watched) {
                    while (true) {
                        performLiveSync()
                        delay(LIVE_SYNC_INTERVAL_MS)
                    }
                }
            }
        }
    }

    private suspend fun performLiveSync() {
        if (!initialDataLoaded) return
        val deviceHash = readDeviceHashFromPrefs()
        if (!isValidDeviceHash(deviceHash)) return
        runCatching {
            db.collection(COL_USERS).document(deviceHash)
                .set(buildLivePayload(), SetOptions.merge())
                .await()
        }
    }

    /**
     * Browse-gated volatile loop: when an admin is on the dashboard/users list
     * (AdminBrowsingState.isBrowsing) but NOT actively watching this user, push
     * preview/nowPlaying every 30s so the directory shows current output without
     * the user spamming writes. Skips entirely when watched (the faster
     * live-sync loop covers it) and when no admin is present (no writes at all).
     */
    private var browseVolatileJob: Job? = null

    /**
     * Disabled under the hourly model. An admin merely browsing the directory
     * must NOT cause this user to emit writes — only opening this specific
     * user's detail (which flips [AdminWatchState.isWatched]) starts the 10s
     * live-sync loop. Directory rows are populated from the admin's one-shot
     * fetch, not from a per-user browse heartbeat.
     */
    private fun startBrowseVolatileSyncWatcher() {
        // Intentionally empty — directory browsing no longer triggers writes.
    }

    private fun captureStateForSync(): Map<String, Any?> = buildMap {
        put("afkEnabled", afkEnabled)
        put("afkMessage", afkMessage.trim())
        put("cycleEnabled", cycleEnabled)
        put("cycleIntervalSeconds", cycleIntervalSeconds)
        put("cycleLinesText", cycleLines.joinToString("\n").trim())
        put("spotifyEnabled", spotifyEnabled)
        put("spotifyDemoEnabled", spotifyDemoEnabled)
        put("spotifyPreset", spotifyPreset)
        put("timeEnabled", timeEnabled)
        put("timeMode", timeMode)
        put("afkPreset1", getAfkPresetPreview(1))
        put("afkPreset2", getAfkPresetPreview(2))
        put("afkPreset3", getAfkPresetPreview(3))
        put("cyclePreset1", cyclePresetMessages.getOrNull(0)?.trim().orEmpty())
        put("cyclePreset2", cyclePresetMessages.getOrNull(1)?.trim().orEmpty())
        put("cyclePreset3", cyclePresetMessages.getOrNull(2)?.trim().orEmpty())
        put("cyclePreset4", cyclePresetMessages.getOrNull(3)?.trim().orEmpty())
        put("cyclePreset5", cyclePresetMessages.getOrNull(4)?.trim().orEmpty())
        // Profile pictures are deliberately NOT synced to Firestore (cost). The
        // admin panel resolves VRChat+ pictures on demand by vrchatUserId via the
        // admin's own VRChat session — see AdminAvatar / VrchatImageLoader.

        // Volatile preview/nowPlaying + VRChat presence ride the hourly delta so
        // an UNWATCHED user's directory row stays roughly current (Firestore bills
        // per document write, not per field — these piggyback on the write that
        // happens anyway). The 30s edit debounce is still only scheduled by
        // genuine content edits, so these fields never CAUSE a write on their own;
        // they're swept up whenever one fires. While watched, the 10s live loop
        // remains the real-time source. (These were silently dropped from the
        // delta path when captureStateForSync was introduced — buildUserSnapshot
        // carries them but only fires on first install / uid change — leaving
        // unwatched previews frozen at the last watch session.)
        put("combinedPreviewText", combinedPreviewText.trim())
        put("nowPlayingDetected", nowPlayingDetected)
        put("nowPlayingIsPlaying", nowPlayingIsPlaying)
        put("nowPlayingTitle", lastNowPlayingTitle.takeIf { it != "(blank)" }?.trim().orEmpty())
        put("nowPlayingArtist", lastNowPlayingArtist.takeIf { it != "(blank)" }?.trim().orEmpty())
        put("activePackage", activePackage)
        VrchatPipelineState.presence?.let { p ->
            put("vrchatUserId", p.userId)
            put("vrchatDisplayName", p.displayName)
            put("vrchatState", p.state)
            put("vrchatStatus", p.status)
            put("vrchatStatusDescription", p.statusDescription)
            put("vrchatWorld", p.worldName)
            put("vrchatLocation", p.location)
            put("vrchatInstancePlayerCount", p.instancePlayerCount)
            put("vrchatInstanceCapacity", p.instanceCapacity)
            put("vrchatPlatform", p.platform)
            put("vrchatIsOnline", p.isOnlineInVRChat)
        }
    }

    /**
     * Content snapshot for the swipe-time offline write (called by [com.vrca.app.AppShutdown]
     * before the process is killed). Carries presets / messages / intervals / toggles
     * so the admin sees the user's LAST state even if they swiped before the 30s
     * debounce flushed — but deliberately NOT the volatile preview text. This is the
     * backup for the case where the process is hard-killed before the debounce fires.
     * Public build only (admin content lives purely in local DataStore).
     */
    fun captureContentForOfflineWrite(): Map<String, Any> {
        if (BuildConfig.IS_ADMIN_BUILD) return emptyMap()
        val m = mutableMapOf<String, Any>()
        captureStateForSync().forEach { (k, v) ->
            if (v != null && k !in VOLATILE_SYNC_KEYS) m[k] = v
        }
        m["cycleLines"] = cycleLines.map { it.trim() }.filter { it.isNotEmpty() }.take(10)
        return m
    }

    private suspend fun applyRemoteContentBeforeSync() {
        if (BuildConfig.IS_ADMIN_BUILD) return
        runCatching {
            ensureAnonAuth()
            val deviceHash = readDeviceHashFromPrefs()
            if (!isValidDeviceHash(deviceHash)) {
                Log.w("VrcaViewModel", "applyRemoteContentBeforeSync: invalid deviceHash, skipping")
                return@runCatching
            }
            // Read from the SERVER, not Firestore's local cache. The default get()
            // returns the cached doc on a cold start — which holds this device's
            // OLD pre-edit values, so an admin edit made while the app was closed
            // would never be seen (old-vs-old comparison applies nothing). Source.SERVER
            // forces the fresh doc with the admin's edits. If it times out (the caller
            // wraps this in withTimeoutOrNull), the moderation snapshot listener is the
            // backstop — it also delivers server data and applies admin edits.
            val snap = db.collection(COL_USERS).document(deviceHash)
                .get(com.google.firebase.firestore.Source.SERVER).await()
            if (snap == null || !snap.exists()) {
                Log.w("VrcaSync", "applyRemoteContentBeforeSync: no remote doc yet (server read)")
                return@runCatching
            }
            Log.i("VrcaSync", "applyRemoteContentBeforeSync: server read OK — " +
                "afkMsg='${snap.getString("afkMessage")}' afkEnabled=${snap.getBoolean("afkEnabled")} " +
                "baselineKeys=${lastSyncedValues.keys}")
            // The doc exists → the cold-open write must NOT write content/toggles
            // (it would risk clobbering admin offline edits). Liveness-only from here.
            remoteDocConfirmedExists = true
            // Apply admin content/toggle edits. Each apply updates lastSyncedValues for
            // the field it changed (so the moderation listener suppresses the duplicate);
            // un-applied keys keep their prefs baseline so the listener can still apply a
            // real admin edit and the delta can still push a genuine user offline edit.
            // We deliberately do NOT blanket-seed the baseline from the snapshot — that
            // poisoned the listener (it saw remote==baseline and suppressed un-applied
            // admin edits) AND, combined with a stale local value, made the cold-open
            // delta write the local value back, OVERWRITING the admin's edit.
            applyContentFromSnapshot(snap)
            applyOfflineToggleEdits(snap)
            Log.d("VrcaViewModel", "applyRemoteContentBeforeSync: applied remote content/toggles")
        }.onFailure { e ->
            Log.w("VrcaSync", "applyRemoteContentBeforeSync FAILED (listener will backstop): ${e.message}")
        }
    }

    /**
     * Apply admin toggle edits made while this user was offline. **Toggles are special
     * because of the OS-kill revival path**: [restoreFeatureSession] runs first and may
     * have just resumed OSC sending with the toggles ON. So we apply a toggle change
     * ONLY when there is a REAL persisted baseline ([lastSyncedValues]) that the admin
     * genuinely changed (`remote != baseline`). We deliberately SKIP on a null baseline
     * (return) instead of falling back to local — falling back to local could flip a
     * just-restored toggle on a stale/missing baseline and STOP a revived user's OSC.
     * With a real baseline, `remote == baseline` means the admin didn't touch it → we
     * leave the restored toggle (and its OSC) alone; `remote != baseline` means the
     * admin edited it (e.g. DISABLED afk while the app was OEM-killed) → we apply via
     * the same setter the UI uses (which starts/stops the sender, gated on oscSending).
     * The cold-open [performSelfSync] also SKIPS toggle keys (and writes liveness-only
     * for an existing doc), so it never writes a toggle value back either way.
     *
     * We update [lastSyncedValues] for any toggle we apply so the moderation listener
     * (which also processes the same snapshot) sees `remote == baseline` and suppresses
     * the duplicate — without poisoning un-applied keys.
     */
    private fun applyOfflineToggleEdits(
        snap: com.google.firebase.firestore.DocumentSnapshot
    ) {
        fun applyToggle(key: String, current: Boolean, setter: (Boolean) -> Unit) {
            val remote = snap.getBoolean(key) ?: return
            val baseline = lastSyncedValues[key] as? Boolean
            // If we have a baseline and the server matches it, the admin didn't
            // change this toggle since our last sync — leave it (and don't fight a
            // restored OSC session).
            if (baseline != null && remote == baseline) {
                Log.d("VrcaSync", "toggle $key: remote=$remote == baseline → skip")
                return
            }
            // Revival protection: if we just restored an ACTIVE OSC session and have
            // no baseline proof the admin turned this OFF, a stale server `false`
            // must not stop the revived user's OSC. Skip only that exact case.
            if (restoredActiveSending && baseline == null && !remote) {
                Log.d("VrcaSync", "toggle $key: stale false during active restore → skip")
                return
            }
            // Either a real admin edit (remote != baseline) OR no baseline at all
            // (empty/partial prefs) — cold open is server-authoritative, so apply.
            if (remote != current) {
                Log.i("VrcaSync", "toggle $key: APPLY remote=$remote (was $current, baseline=$baseline)")
                setter(remote)
            }
            lastSyncedValues[key] = remote
        }
        applyToggle("afkEnabled", afkEnabled) { setAfkEnabledFlag(it) }
        applyToggle("cycleEnabled", cycleEnabled) { setCycleEnabledFlag(it) }
        applyToggle("spotifyEnabled", spotifyEnabled) { setSpotifyEnabledFlag(it) }
        applyToggle("timeEnabled", timeEnabled) { updateTimeEnabled(it) }
    }

    private suspend fun applyContentFromSnapshot(
        snap: com.google.firebase.firestore.DocumentSnapshot
    ) {
        // Apply admin-edited content when the remote value differs from the persisted
        // baseline ([lastSyncedValues]) — i.e. the admin changed it since our last sync.
        // With a real baseline, remote==baseline means "admin didn't change it" so we
        // keep the user's local edit (a later debounced/hourly write pushes it up); both
        // edited → admin wins. When there's NO baseline for a key (null), we fall back to
        // the current LOCAL value so the server/admin value still wins on first sync.
        //
        // For every field we APPLY, we set lastSyncedValues[key] = remote so the
        // moderation listener (processing the same snapshot) suppresses the duplicate.
        // We do NOT seed un-applied keys — leaving their baseline as the prefs value lets
        // the listener correctly distinguish a real admin edit from an echo, and lets the
        // cold-open/debounced delta push a genuine user offline edit.
        snap.getString("afkMessage")?.trim()?.let { remote ->
            val baseline = (lastSyncedValues["afkMessage"] as? String) ?: afkMessage.trim()
            if (remote != baseline && remote != afkMessage.trim()) {
                afkMessage = remote
                userPreferencesRepository.saveAfkMessage(remote)
                lastSyncedValues["afkMessage"] = remote
            }
        }
        snap.getLong("cycleIntervalSeconds")?.toInt()?.coerceAtLeast(2)?.let { remote ->
            val baseline = (lastSyncedValues["cycleIntervalSeconds"] as? Int) ?: cycleIntervalSeconds
            if (remote != baseline && remote != cycleIntervalSeconds) {
                cycleIntervalSeconds = remote
                userPreferencesRepository.saveCycleInterval(remote)
                lastSyncedValues["cycleIntervalSeconds"] = remote
            }
        }
        snap.getString("cycleLinesText")?.trim()?.let { remote ->
            val local = cycleLines.joinToString("\n").trim()
            val baseline = (lastSyncedValues["cycleLinesText"] as? String) ?: local
            if (remote != baseline && remote != local) {
                setCycleLinesFromTextPreserve(remote)
                userPreferencesRepository.saveCycleMessages(remote)
                lastSyncedValues["cycleLinesText"] = remote
            }
        }
        val afkPresetSavers = listOf<suspend (String) -> Unit>(
            { v -> userPreferencesRepository.saveAfkPreset1(v) },
            { v -> userPreferencesRepository.saveAfkPreset2(v) },
            { v -> userPreferencesRepository.saveAfkPreset3(v) }
        )
        for (i in 1..3) {
            snap.getString("afkPreset$i")?.trim()?.let { remote ->
                val local = afkPresetTexts[i - 1].trim()
                val baseline = (lastSyncedValues["afkPreset$i"] as? String) ?: local
                if (remote != baseline && remote != local) {
                    afkPresetTexts[i - 1] = remote
                    afkPresetSavers[i - 1](remote)
                    lastSyncedValues["afkPreset$i"] = remote
                }
            }
        }
        val presetSavers = listOf<suspend (String, Int, String?) -> Unit>(
            userPreferencesRepository::saveCyclePreset1,
            userPreferencesRepository::saveCyclePreset2,
            userPreferencesRepository::saveCyclePreset3,
            userPreferencesRepository::saveCyclePreset4,
            userPreferencesRepository::saveCyclePreset5
        )
        for (i in 1..5) {
            snap.getString("cyclePreset$i")?.trim()?.let { remote ->
                val local = cyclePresetMessages.getOrNull(i - 1)?.trim().orEmpty()
                val baseline = (lastSyncedValues["cyclePreset$i"] as? String) ?: local
                if (remote != baseline && remote != local) {
                    cyclePresetMessages[i - 1] = remote
                    val interval = cyclePresetIntervals.getOrElse(i - 1) { 10 }
                    presetSavers[i - 1](remote, interval, null)
                    lastSyncedValues["cyclePreset$i"] = remote
                }
            }
        }
        snap.getLong("spotifyPreset")?.toInt()?.coerceIn(1, 5)?.let { remote ->
            val baseline = (lastSyncedValues["spotifyPreset"] as? Int) ?: spotifyPreset
            if (remote != baseline && remote != spotifyPreset) {
                spotifyPreset = remote
                userPreferencesRepository.saveSpotifyPreset(remote)
                lastSyncedValues["spotifyPreset"] = remote
            }
        }
    }

    /**
     * Cross-device content sync. Waits for VRChat userId to become known (pipeline
     * connect), then queries Firestore for sibling device docs with the SAME
     * `vrchatUserId` but a different `deviceHash`. If a sibling has fresher content
     * (`updatedAt`), pulls its presets/messages/intervals into local DataStore —
     * so editing on phone A auto-appears on phone B's next open.
     *
     * THROTTLED to at most once per [CROSS_DEVICE_SYNC_THROTTLE_MS] (persisted
     * timestamp): this runs on every VM creation, and the background-survival
     * machinery recreates the VM after each OS kill, so without the throttle a
     * device that's being killed+restarted repeatedly would re-run this collection
     * read every few minutes. The throttle bounds it to one query per 30 min/device
     * regardless of relaunch frequency. A genuine reopen after editing on the other
     * phone is almost always >30 min from the last sync, so the feature is intact.
     */
    private suspend fun applyCrossDeviceSync() {
        // Throttle: skip if we synced within the window. Cheap relaunch-storm guard.
        val lastSync = prefs().getLong(PREF_LAST_CROSS_DEVICE_SYNC_MS, 0L)
        if (System.currentTimeMillis() - lastSync < CROSS_DEVICE_SYNC_THROTTLE_MS) return

        val myPresence = kotlinx.coroutines.withTimeoutOrNull(120_000L) {
            VrchatPipelineState.presenceFlow.first { it != null }
        } ?: return
        val myVrchatId = myPresence.userId
        if (myVrchatId.isBlank()) return

        val deviceHash = readDeviceHashFromPrefs()
        if (!isValidDeviceHash(deviceHash)) return

        runCatching {
            val siblings = db.collection(COL_USERS)
                .whereEqualTo("vrchatUserId", myVrchatId)
                .get()
                .await()
            // Stamp the throttle on a successful query (whether or not we pull), so
            // relaunches within the window don't re-read.
            prefs().edit().putLong(PREF_LAST_CROSS_DEVICE_SYNC_MS, System.currentTimeMillis()).apply()
            if (siblings == null || siblings.isEmpty) return@runCatching

            // The query returns our OWN doc too (same vrchatUserId), so read our
            // updatedAt straight from the results — no extra get() needed.
            var bestSnap: com.google.firebase.firestore.DocumentSnapshot? = null
            var bestMs = 0L
            var myUpdatedAt = 0L
            for (doc in siblings.documents) {
                val ts = doc.getTimestamp("updatedAt")?.toDate()?.time ?: 0L
                if (doc.id == deviceHash) { myUpdatedAt = ts; continue }
                if (ts > bestMs) { bestMs = ts; bestSnap = doc }
            }
            if (bestSnap == null || bestMs <= myUpdatedAt) return@runCatching

            applyContentFromSnapshot(bestSnap)
            Log.d("VrcaViewModel", "Cross-device sync: pulled content from ${bestSnap.id}")
            rebuildCombinedPreviewOnly()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun performSelfSync(coldOpen: Boolean = false) {
        if (BuildConfig.IS_ADMIN_BUILD) return
        if (!initialDataLoaded) return
        runCatching {
            val authUid = ensureAnonAuth() ?: return@runCatching
            val deviceHash = readDeviceHashFromPrefs()
            if (!isValidDeviceHash(deviceHash)) {
                lastSelfSyncError =
                    "deviceHash missing/invalid. Ensure app sets prefs key device_id_hash."
                return@runCatching
            }

            val currentState = captureStateForSync()
            val uidChanged = lastSyncedValues["_authUid"]?.let { it != authUid } ?: false
            val isFirstSync = lastSyncedValues.isEmpty()
            // If Source.SERVER failed in applyRemoteContentBeforeSync and the prefs
            // baseline is empty, check the LOCAL Firestore cache before deciding on a
            // full write — a returning user with corrupted/empty prefs still has the
            // doc in cache, and a full write would clobber admin offline edits.
            if (coldOpen && !remoteDocConfirmedExists && isFirstSync) {
                remoteDocConfirmedExists = try {
                    db.collection(COL_USERS).document(deviceHash)
                        .get(com.google.firebase.firestore.Source.CACHE).await()
                        .exists()
                } catch (_: Throwable) { false }
            }
            val livenessOnlyExistingDoc = coldOpen && remoteDocConfirmedExists

            Log.i("VrcaSync", "performSelfSync coldOpen=$coldOpen isFirstSync=$isFirstSync " +
                "docExists=$remoteDocConfirmedExists uidChanged=$uidChanged " +
                "→ ${if ((isFirstSync && !remoteDocConfirmedExists) || uidChanged) "FULL-WRITE" else if (livenessOnlyExistingDoc) "LIVENESS-ONLY" else "DELTA"}")

            if ((isFirstSync && !remoteDocConfirmedExists) || uidChanged) {
                // Full write: first ever install (doc absent), or auth UID changed
                // (rules require uid/authUid to match the new auth, so a delta that
                // omits them would be denied — must rewrite the whole doc). Handles doc
                // creation and writes all fields for backward compat.
                try {
                    db.collection(COL_USERS).document(deviceHash)
                        .set(buildUserSnapshot(authUid, deviceHash), SetOptions.merge())
                        .await()

                    if (!usersByIdLinkWritten || uidChanged) {
                        runCatching {
                            db.collection(COL_USERS_BY_ID).document(authUid)
                                .set(buildUsersByIdLink(authUid, deviceHash), SetOptions.merge())
                                .await()
                            usersByIdLinkWritten = true
                        }
                    }

                    lastSyncedValues.clear()
                    lastSyncedValues.putAll(currentState)
                    lastSyncedValues["_authUid"] = authUid
                    persistLastSyncedValues()
                    markSelfSyncWritten()
                    lastSelfSyncError = ""
                } catch (e: Throwable) {
                    throw e
                }
                return@runCatching
            }

            // Delta write: liveness fields always, content only if changed.
            val delta = mutableMapOf<String, Any>(
                "isOnlineInApp" to true,
                "lastActiveAt" to FieldValue.serverTimestamp(),
                "lastSeenAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            for ((key, value) in currentState) {
                // Existing-doc cold open → liveness only (never write content/toggles).
                if (livenessOnlyExistingDoc) continue
                // First-install cold open still skips toggles (they start OFF; writing
                // OFF back would race an admin's enabled toggle before it's applied).
                if (coldOpen && key in TOGGLE_KEYS) continue
                if (value != lastSyncedValues[key] && value != null) {
                    delta[key] = value
                }
            }
            if (delta.containsKey("cycleLinesText")) {
                delta["cycleLines"] = cycleLines.map { it.trim() }
                    .filter { it.isNotEmpty() }.take(10)
            }

            // Liveness throttle: if the delta is liveness-ONLY (no content changed)
            // and the last real write was recent, skip it. The fresh lastActiveAt
            // already proves online; the hourly heartbeat (anchored to the last real
            // write) backstops the 65-min window. This applies to BOTH the cold-open
            // write (process resurrection churn) AND the debounced write (spurious
            // DataStore re-emissions) — but never the hourly heartbeat (coldOpen=false
            // and the debounce job is a separate path from the heartbeat).
            val livenessOnly = delta.keys.all { it in LIVENESS_KEYS }
            if (livenessOnly &&
                System.currentTimeMillis() - readLastSelfSyncMs() < COLD_OPEN_LIVENESS_THROTTLE_MS
            ) {
                lastSelfSyncError = ""
                return@runCatching
            }

            try {
                db.collection(COL_USERS).document(deviceHash)
                    .set(delta, SetOptions.merge())
                    .await()
                if (livenessOnlyExistingDoc) {
                    // We wrote ONLY liveness — we did NOT write content/toggles, so we
                    // must NOT rebaseline them to local. Overwriting the baseline with
                    // local here would (a) drop the prefs baseline that lets the listener
                    // apply admin offline edits, and (b) make a later write/listener
                    // clobber a user's own offline edit. Keep the existing baseline
                    // (prefs + any per-field updates the apply path made) and just
                    // record _authUid + the write timestamp.
                    lastSyncedValues["_authUid"] = authUid
                    persistLastSyncedValues()
                    markSelfSyncWritten()
                    lastSelfSyncError = ""
                    return@runCatching
                }
                lastSyncedValues.clear()
                lastSyncedValues.putAll(currentState)
                lastSyncedValues["_authUid"] = authUid
                persistLastSyncedValues()
                markSelfSyncWritten()
                lastSelfSyncError = ""
            } catch (e: Throwable) {
                throw e
            }
        }.onFailure { e ->
            lastSelfSyncError = (e.message ?: e.toString()).take(4000)
        }
    }

    /**
     * Once-per-hour liveness + delta sync. Each tick calls [performSelfSync]
     * which always writes liveness (lastActiveAt, isOnlineInApp) and includes
     * any content fields that changed since the last write. This merges the
     * old separate heartbeat + sync into a single Firestore write per hour.
     * In-process only — dies with the process, so a swiped/killed app
     * correctly stops reporting online.
     */
    private fun startHourlyHeartbeat() {
        if (BuildConfig.IS_ADMIN_BUILD) return
        if (hourlyHeartbeatJob != null) return
        hourlyHeartbeatJob = viewModelScope.launch {
            // Anchor the first tick to the last REAL write (persisted), not to this
            // process's start. If the cold-open write was throttled (a recent restart),
            // this still fires a liveness write ~60 min after the last actual write —
            // keeping lastActiveAt inside the 65-min staleness window without writing
            // once per restart. Floored so a stale anchor doesn't fire a burst.
            val sinceLast = System.currentTimeMillis() - readLastSelfSyncMs()
            val firstDelay = (HOURLY_HEARTBEAT_MS - sinceLast).coerceIn(60_000L, HOURLY_HEARTBEAT_MS)
            delay(firstDelay)
            performSelfSync()
            while (true) {
                delay(HOURLY_HEARTBEAT_MS)
                performSelfSync()
            }
        }
    }

    // =========================
    // Moderation / punishments
    // =========================
    private var moderationUserReg: ListenerRegistration? = null
    private var moderationDeviceReg: ListenerRegistration? = null
    private var moderationAttachJob: Job? = null

    var warned by mutableStateOf(false)
        private set
    var warnReason by mutableStateOf("")
        private set

    var uidBanned by mutableStateOf(false)
        private set
    var banReason by mutableStateOf("")
        private set

    var deviceBanned by mutableStateOf(false)
        private set
    var deviceBanReason by mutableStateOf("")
        private set

    var moderationConnected by mutableStateOf(false)
        private set
    var moderationLastError by mutableStateOf("")
        private set

    var targetedUpdateUrl by mutableStateOf("")
        private set
    var targetedUpdateNotes by mutableStateOf("")
        private set

    val isBanned: Boolean
        get() = uidBanned || deviceBanned

    private var lastBanEffective: Boolean = false

    private var killSignalHandled: Boolean = false

    private fun handleAdminKill() {
        if (killSignalHandled) return
        killSignalHandled = true
        try {
            android.os.Process.killProcess(android.os.Process.myPid())
        } catch (_: Throwable) { /* nothing to do */ }
    }

    private fun enforceIfBannedChanged() {
        val nowBanned = isBanned
        if (nowBanned == lastBanEffective) return
        lastBanEffective = nowBanned

        if (nowBanned) {
            // Hard stop all running jobs immediately. Do NOT clear chatbox while banned (no OSC allowed).
            stopAll(clearFromChatbox = false)

            // Ensure typing indicator is off locally (no OSC send; just state safety).
            remoteVrcaOsc.typing = false
            localVrcaOsc.typing = false
        }
    }

    private fun attachModerationListenersLoopOnce() {
        if (BuildConfig.IS_ADMIN_BUILD) return
        if (moderationAttachJob != null) return

        moderationAttachJob = viewModelScope.launch {
            while (true) {
                val uid = ensureAnonAuth().orEmpty().trim()
                val deviceHash = readDeviceHashFromPrefs().trim()

                if (uid.isBlank()) {
                    moderationConnected = false
                    moderationLastError = "Auth unavailable (cannot read moderation flags)."
                    delay(MOD_ATTACH_RETRY_MS)
                    continue
                }
                if (!isValidDeviceHash(deviceHash)) {
                    moderationConnected = false
                    moderationLastError = "deviceHash missing/invalid (cannot read moderation flags)."
                    delay(MOD_ATTACH_RETRY_MS)
                    continue
                }

                if (moderationUserReg == null) {
                    moderationUserReg = db.collection(COL_USERS).document(deviceHash)
                        .addSnapshotListener { snap, e ->
                            if (e != null) {
                                moderationLastError = (e.message ?: "User-doc listen failed").take(4000)
                                moderationConnected = false
                                enforceIfBannedChanged()
                                return@addSnapshotListener
                            }

                            if (snap == null || !snap.exists()) {
                                warned = false; warnReason = ""
                                uidBanned = false; banReason = ""
                                moderationConnected = true; moderationLastError = ""
                                enforceIfBannedChanged()
                                return@addSnapshotListener
                            }

                            warned = snap.getBoolean("warned") ?: false
                            warnReason = (snap.getString("warnReason") ?: "").trim()
                            uidBanned = snap.getBoolean("banned") ?: false
                            banReason = (snap.getString("banReason") ?: "").trim()
                            moderationConnected = true; moderationLastError = ""
                            enforceIfBannedChanged()

                            val killSignal = snap.getTimestamp("killSignal")
                            if (killSignal != null) {
                                val killMs = killSignal.seconds * 1000L + (killSignal.nanoseconds / 1_000_000L)
                                val ageMs = System.currentTimeMillis() - killMs
                                if (ageMs in 0L..60_000L) handleAdminKill()
                            }

                            targetedUpdateUrl = (snap.getString("targetedUpdateUrl") ?: "").trim()
                            targetedUpdateNotes = (snap.getString("targetedUpdateNotes") ?: "").trim()
                            applyRemoteConfig(snap)
                        }
                }

                if (moderationDeviceReg == null) {
                    moderationDeviceReg = db.collection(COL_BANNED_DEVICES).document(deviceHash)
                        .addSnapshotListener { snap, e ->
                            if (e != null) {
                                // Legacy doc may be unreadable for some users; don't hard-fail connected.
                                moderationLastError = (e.message ?: "Device-ban listen failed").take(4000)
                                enforceIfBannedChanged()
                                return@addSnapshotListener
                            }

                            if (snap == null || !snap.exists()) {
                                deviceBanned = false
                                deviceBanReason = ""
                                enforceIfBannedChanged()
                                return@addSnapshotListener
                            }

                            deviceBanned = snap.getBoolean("banned") ?: false
                            deviceBanReason = (snap.getString("reason") ?: "").trim()
                            enforceIfBannedChanged()
                        }
                }

                // Once both are attached, stop retry loop.
                moderationAttachJob = null
                return@launch
            }
        }
    }

    // =========================
    // Remote config (admin edits applied in real-time)
    // =========================

    /**
     * Called from the moderation snapshot listener whenever the user document changes.
     * Picks up admin-editable fields and writes them to DataStore (which triggers
     * existing flow collectors to update ViewModel state). Fields without DataStore
     * backing are set directly on the ViewModel.
     */

    private fun applyRemoteConfig(snap: com.google.firebase.firestore.DocumentSnapshot) {
        if (BuildConfig.IS_ADMIN_BUILD) return
        val watcherActiveAtMs = runCatching {
            snap.getTimestamp("watcherActiveAt")?.toDate()?.time
        }.getOrNull()
        com.vrca.sync.AdminWatchState.updateFromTimestampMs(watcherActiveAtMs)

        viewModelScope.launch {
            // Two-layer comparison for each field:
            //  1. remote != local  — is the value actually different from what we have?
            //     If same, skip entirely (no change needed, prevents echo loops).
            //  2. baseline == null || remote != baseline — is this a REAL admin edit, or
            //     just an echo of our pending local write (heartbeat during active watching)?
            //     When the user toggled locally but the write hasn't landed on Firestore yet,
            //     the heartbeat snapshot echoes the OLD server value. baseline matches that
            //     old value (we wrote it), so remote == baseline → skip (don't revert the
            //     user's local toggle). When an admin ACTUALLY changed the field, remote !=
            //     baseline → apply. Null baseline (first snapshot / empty prefs) always
            //     passes through to the local comparison, so admin edits on cold-open are
            //     never blocked by missing baseline data.
            //
            // Always update lastSyncedValues to remote so subsequent echoes are suppressed.

            snap.getBoolean("afkEnabled")?.let { remote ->
                if (remote != afkEnabled) {
                    val baseline = lastSyncedValues["afkEnabled"]
                    if (baseline == null || remote != baseline) {
                        afkEnabled = remote
                        savedState["afkEnabled"] = remote
                        rebuildCombinedPreviewOnly()
                        if (!remote) stopAfkSender(clearFromChatbox = true)
                        startSelfSyncLoopIfNeeded()
                    }
                }
                lastSyncedValues["afkEnabled"] = remote
            }
            snap.getBoolean("cycleEnabled")?.let { remote ->
                if (remote != cycleEnabled) {
                    val baseline = lastSyncedValues["cycleEnabled"]
                    if (baseline == null || remote != baseline) {
                        cycleEnabled = remote
                        savedState["cycleEnabled"] = remote
                        rebuildCombinedPreviewOnly()
                        if (!remote) stopCycle(clearFromChatbox = true)
                        if (remote) lastCyclePreviewAdvanceMs = 0L
                        startSelfSyncLoopIfNeeded()
                    }
                }
                lastSyncedValues["cycleEnabled"] = remote
            }
            snap.getBoolean("spotifyEnabled")?.let { remote ->
                if (remote != spotifyEnabled) {
                    val baseline = lastSyncedValues["spotifyEnabled"]
                    if (baseline == null || remote != baseline) {
                        spotifyEnabled = remote
                        savedState["spotifyEnabled"] = remote
                        rebuildCombinedPreviewOnly()
                        if (!remote) stopNowPlayingSender(clearFromChatbox = true)
                        startSelfSyncLoopIfNeeded()
                    }
                }
                lastSyncedValues["spotifyEnabled"] = remote
            }
            snap.getBoolean("timeEnabled")?.let { remote ->
                if (remote != timeEnabled) {
                    val baseline = lastSyncedValues["timeEnabled"]
                    if (baseline == null || remote != baseline) {
                        timeEnabled = remote
                        savedState["timeEnabled"] = remote
                        rebuildCombinedPreviewOnly()
                        startSelfSyncLoopIfNeeded()
                    }
                }
                lastSyncedValues["timeEnabled"] = remote
            }

            // Content fields: compare against LOCAL value first (does the ViewModel
            // actually need updating?), then baseline for echo suppression. Directly
            // set ViewModel fields so the change takes effect immediately — relying
            // solely on async DataStore collector propagation was a source of races
            // where the cold-open DataStore load overwrote the applied value.
            snap.getString("afkMessage")?.let { remote ->
                val trimmed = remote.trim()
                if (trimmed != afkMessage.trim()) {
                    val baseline = lastSyncedValues["afkMessage"] as? String
                    if (baseline == null || trimmed != baseline) {
                        afkMessage = trimmed
                        userPreferencesRepository.saveAfkMessage(trimmed)
                    }
                }
                lastSyncedValues["afkMessage"] = trimmed
            }
            snap.getLong("cycleIntervalSeconds")?.let { remote ->
                val intVal = remote.toInt().coerceAtLeast(2)
                if (intVal != cycleIntervalSeconds) {
                    val baseline = lastSyncedValues["cycleIntervalSeconds"] as? Int
                    if (baseline == null || intVal != baseline) {
                        cycleIntervalSeconds = intVal
                        userPreferencesRepository.saveCycleInterval(intVal)
                    }
                }
                lastSyncedValues["cycleIntervalSeconds"] = intVal
            }
            snap.getString("cycleLinesText")?.let { remote ->
                val trimmed = remote.trim()
                val local = cycleLines.joinToString("\n").trim()
                if (trimmed != local) {
                    val baseline = lastSyncedValues["cycleLinesText"] as? String
                    if (baseline == null || trimmed != baseline) {
                        setCycleLinesFromTextPreserve(trimmed)
                        userPreferencesRepository.saveCycleMessages(trimmed)
                    }
                }
                lastSyncedValues["cycleLinesText"] = trimmed
            }
            val afkPresetSavers = listOf<suspend (String) -> Unit>(
                { v -> userPreferencesRepository.saveAfkPreset1(v) },
                { v -> userPreferencesRepository.saveAfkPreset2(v) },
                { v -> userPreferencesRepository.saveAfkPreset3(v) }
            )
            for (i in 1..3) {
                val remoteMsg = snap.getString("afkPreset$i") ?: continue
                val trimmed = remoteMsg.trim()
                val local = afkPresetTexts.getOrNull(i - 1)?.trim().orEmpty()
                if (trimmed != local) {
                    val baseline = lastSyncedValues["afkPreset$i"] as? String
                    if (baseline == null || trimmed != baseline) {
                        afkPresetTexts[i - 1] = trimmed
                        afkPresetSavers[i - 1](trimmed)
                    }
                }
                lastSyncedValues["afkPreset$i"] = trimmed
            }
            val presetSavers = listOf<suspend (String, Int, String?) -> Unit>(
                userPreferencesRepository::saveCyclePreset1,
                userPreferencesRepository::saveCyclePreset2,
                userPreferencesRepository::saveCyclePreset3,
                userPreferencesRepository::saveCyclePreset4,
                userPreferencesRepository::saveCyclePreset5
            )
            for (i in 1..5) {
                val remoteMsg = snap.getString("cyclePreset$i") ?: continue
                val trimmed = remoteMsg.trim()
                val local = cyclePresetMessages.getOrNull(i - 1)?.trim().orEmpty()
                if (trimmed != local) {
                    val baseline = lastSyncedValues["cyclePreset$i"] as? String
                    if (baseline == null || trimmed != baseline) {
                        cyclePresetMessages[i - 1] = trimmed
                        val interval = cyclePresetIntervals.getOrElse(i - 1) { 10 }
                        presetSavers[i - 1](trimmed, interval, null)
                    }
                }
                lastSyncedValues["cyclePreset$i"] = trimmed
            }
            snap.getLong("spotifyPreset")?.let { remote ->
                val intVal = remote.toInt().coerceIn(1, 5)
                if (intVal != spotifyPreset) {
                    val baseline = lastSyncedValues["spotifyPreset"] as? Int
                    if (baseline == null || intVal != baseline) {
                        spotifyPreset = intVal
                        userPreferencesRepository.saveSpotifyPreset(intVal)
                    }
                }
                lastSyncedValues["spotifyPreset"] = intVal
            }
        }
    }

    // =========================
    // Conversation / manual messages
    // =========================
    val conversationUiState = ConversationUiState()
    val messageText = mutableStateOf(TextFieldValue(""))

    var stashedMessage by mutableStateOf("")
        private set

    fun stashMessage(local: Boolean = false) {
        val osc = if (!local) remoteVrcaOsc else localVrcaOsc
        osc.typing = false

        val txt = messageText.value.text
        conversationUiState.addMessage(
            Message(txt, true, Instant.now())
        )

        messageText.value = TextFieldValue("", TextRange.Zero)
        stashedMessage = ""
    }

    fun stashMessage(text: String) {
        stashedMessage = text
        messageText.value = TextFieldValue(text, TextRange(text.length))
    }

    // =========================
    // Stored + live messenger state
    // =========================
    private val storedIpState: StateFlow<String> =
        userPreferencesRepository.ipAddress.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = "127.0.0.1"
        )

    private val userInputIpState = kotlinx.coroutines.flow.MutableStateFlow("")

    private val ipFlow = combine(storedIpState, userInputIpState) { stored, typed ->
        if (typed.isNotBlank()) typed else stored
    }

    val messengerUiState: StateFlow<MessengerUiState> = combine(
        ipFlow,
        userPreferencesRepository.isRealtimeMsg,
        userPreferencesRepository.isTriggerSfx,
        userPreferencesRepository.isTypingIndicator,
        userPreferencesRepository.isSendImmediately
    ) { ipAddress, isRealtimeMsg, isTriggerSfx, isTypingIndicator, isSendImmediately ->
        MessengerUiState(
            ipAddress = ipAddress,
            isRealtimeMsg = isRealtimeMsg,
            isTriggerSFX = isTriggerSfx,
            isTypingIndicator = isTypingIndicator,
            isSendImmediately = isSendImmediately
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MessengerUiState()
    )

    private val remoteVrcaOsc = VrcaOsc(
        ipAddress = runBlocking { userPreferencesRepository.ipAddress.first() },
        port = 9000
    )

    private val localVrcaOsc = VrcaOsc(
        ipAddress = "localhost",
        port = 9000
    )

    /**
     * True while a REQUIRED app update is pending. Set from [VrcaApp] when the
     * release check resolves to a forced update. It hard-blocks all OSC output
     * at the [VrcaOsc] chokepoint so the user cannot keep driving the VRChat
     * chatbox (including in the background — this lives on the app-scoped VM, so
     * the gate persists after the Activity is destroyed) until they update.
     */
    var forceUpdatePending by mutableStateOf(false)
        private set

    fun applyForceUpdateGate(pending: Boolean) {
        if (forceUpdatePending == pending) return
        forceUpdatePending = pending
        refreshOscBlockGate()
        if (pending) {
            // Stop any in-flight typing indicator immediately (state safety; the
            // OSC send is already blocked). Sender loops keep their config but
            // every transmission no-ops at the chokepoint above.
            remoteVrcaOsc.typing = false
            localVrcaOsc.typing = false
        }
    }

    /**
     * True while the user is signed OUT of VRChat. Per docs/ui-revamp.md
     * (Settings Accounts): signing out force-stops all OSC output until a
     * VRChat account is signed in again — same chokepoint as the force-update
     * gate. Driven by VrchatAuthManager's loggedOut/loggedIn signals (collected
     * in init on the app-scoped VM, so the gate works even with no Activity).
     * Re-login only UNBLOCKS — it never auto-starts sending; toggle config is
     * preserved and the user presses Start again.
     */
    var vrchatLoggedOut by mutableStateOf(false)
        private set

    /** OSC is blocked when ANY gate reason is active. */
    private fun refreshOscBlockGate() {
        val blocked = forceUpdatePending || vrchatLoggedOut
        remoteVrcaOsc.blocked = blocked
        localVrcaOsc.blocked = blocked
    }

    private fun startVrchatAuthGateWatcher() {
        viewModelScope.launch {
            com.vrca.vrchat.VrchatAuthManager.loggedOutSignal.collect {
                // Order matters: stopSending()'s chatbox-clearing send must go
                // out BEFORE the gate blocks the chokepoint.
                if (oscSending) stopSending()
                vrchatLoggedOut = true
                refreshOscBlockGate()
            }
        }
        viewModelScope.launch {
            com.vrca.vrchat.VrchatAuthManager.loggedInSignal.collect {
                vrchatLoggedOut = false
                refreshOscBlockGate()
            }
        }
    }

    /**
     * Minimal chatbox background (the VRCOSC/MagicChatbox "skinny bubble" trick).
     * When ON, [VrcaOsc] appends U+0003+U+001F to every outgoing chatbox message
     * (collapsing the in-game bubble background) and the combined-text builder
     * reserves 2 chars of the 144 budget so the suffix can never be trimmed off.
     * Persisted to DataStore like the notification toggles — survives close/swipe
     * (it is a display preference, NOT a sender toggle: it never starts OSC and
     * is not part of the feature-session restore or Firestore sync).
     */
    var minimalChatboxBg by mutableStateOf(false)
        private set

    fun setMinimalChatboxBgFlag(enabled: Boolean) {
        minimalChatboxBg = enabled
        remoteVrcaOsc.minimalBackground = enabled
        localVrcaOsc.minimalBackground = enabled
        viewModelScope.launch { userPreferencesRepository.saveMinimalChatboxBg(enabled) }
        // Re-send immediately while sending so the bubble reacts in-game the
        // moment the user flips the eye toggle (instead of on the next tick).
        if (oscSending) rebuildAndMaybeSendCombined(forceSend = true)
        else rebuildCombinedPreviewOnly()
    }

    fun onIpAddressChange(ip: String) {
        userInputIpState.value = ip
    }

    fun ipAddressApply(address: String) {
        remoteVrcaOsc.ipAddress = address
        viewModelScope.launch { userPreferencesRepository.saveIpAddress(address) }
        startSelfSyncLoopIfNeeded()
        attachModerationListenersLoopOnce()
    }

    fun ipAddressApplyRuntimeOnly(address: String) {
        remoteVrcaOsc.ipAddress = address
        startSelfSyncLoopIfNeeded()
        attachModerationListenersLoopOnce()
    }

    fun portApply(port: Int) {
        remoteVrcaOsc.port = port
        viewModelScope.launch { userPreferencesRepository.savePort(port) }
        startSelfSyncLoopIfNeeded()
        attachModerationListenersLoopOnce()
    }

    fun onRealtimeMsgChanged(value: Boolean) {
        viewModelScope.launch { userPreferencesRepository.saveIsRealtimeMsg(value) }
    }

    fun onTriggerSfxChanged(value: Boolean) {
        viewModelScope.launch { userPreferencesRepository.saveIsTriggerSFX(value) }
    }

    fun onTypingIndicatorChanged(value: Boolean) {
        viewModelScope.launch { userPreferencesRepository.saveTypingIndicator(value) }
    }

    fun onSendImmediatelyChanged(value: Boolean) {
        viewModelScope.launch { userPreferencesRepository.saveIsSendImmediately(value) }
    }

    fun onMessageTextChange(message: TextFieldValue, local: Boolean = false) {
        val osc = if (!local) remoteVrcaOsc else localVrcaOsc
        messageText.value = message
        stashedMessage = message.text

        if (isBanned) {
            osc.typing = false
            return
        }

        if (messengerUiState.value.isRealtimeMsg) {
            osc.sendRealtimeMessage(message.text)
        } else if (messengerUiState.value.isTypingIndicator) {
            osc.typing = message.text.isNotEmpty()
        }
    }

    fun sendMessage(local: Boolean = false) {
        if (isBanned) return

        val osc = if (!local) remoteVrcaOsc else localVrcaOsc

        osc.sendMessage(
            messageText.value.text,
            messengerUiState.value.isSendImmediately,
            triggerSFX = false
        )
        osc.typing = false

        conversationUiState.addMessage(
            Message(messageText.value.text, false, Instant.now())
        )

        messageText.value = TextFieldValue("", TextRange.Zero)
        stashedMessage = ""
    }

    // =========================
    // Throttling (hard floor)
    // =========================
    var minSendIntervalSeconds by mutableStateOf(2)
        private set

    private var lastCombinedSendMs = 0L

    // Content-change dedup: skip redundant OSC sends when the text hasn't changed.
    // Re-sends every 10s even if unchanged so VRChat doesn't clear the chatbox.
    private var lastSentCombinedText = ""
    private var lastSentMs = 0L

    // =========================
    // OSC send gate (master switch)
    // =========================
    // Toggles (afk/cycle/spotify/time) configure WHAT will be sent; this gate
    // decides WHEN it actually goes out over OSC. When false, enabling a toggle
    // only updates the local preview/config — nothing is transmitted and the
    // VRChat chatbox is untouched. START (`startSending`) flips this true and
    // launches the sender loops for whatever toggles are on; STOP (`stopSending`)
    // flips it false, stops the loops, and clears the VRChat chatbox — WITHOUT
    // untoggling any feature. Persisted (SavedStateHandle + FeatureSessionStore)
    // so an OS-kill restore only resumes sending if the user was actually sending.
    var oscSending by mutableStateOf(savedState["oscSending"] ?: false)
        private set

    // Epoch ms of when the current sending session started — drives the Home
    // "Sending · 2h 14m" uptime label. 0 while idle. Persisted through
    // FeatureSessionStore using the RPC-counter pattern: an OS-kill revival
    // inside the 20-min grace window CONTINUES the counter (the watchdog gap is
    // not subtracted — the label is framed as "since you pressed Start"); a
    // deliberate swipe disarms restore so the next session starts fresh.
    var sendingSinceMs by mutableStateOf(savedState["sendingSinceMs"] ?: 0L)
        private set
    private var uptimeHeartbeatJob: Job? = null

    // Epoch ms of the cycle sender's next line advance — drives the Home
    // "Next cycle in 12s" ticker. 0 when the cycle loop isn't running.
    var nextCycleAtMs by mutableStateOf(0L)
        private set

    // =========================
    // AFK
    // =========================
    var afkEnabled by mutableStateOf(savedState["afkEnabled"] ?: false)
        private set

    var afkMessage by mutableStateOf("")
        private set

    private val afkForcedIntervalSeconds = 12
    private var afkJob: Job? = null

    // =========================
    // Cycle
    // =========================
    var cycleEnabled by mutableStateOf(savedState["cycleEnabled"] ?: false)
        private set

    var cycleIntervalSeconds by mutableStateOf(CYCLE_INTERVAL_SECONDS_LOCKED)
        private set

    private var cycleJob: Job? = null
    private var cycleIndex = 0
    val cycleLines = mutableStateListOf<String>()
    private var lastCyclePreviewAdvanceMs: Long = 0L

    // =========================
    // Now Playing
    // =========================
    var spotifyEnabled by mutableStateOf(savedState["spotifyEnabled"] ?: false)
        private set
    var spotifyDemoEnabled by mutableStateOf(false)
        private set

    var spotifyPreset by mutableStateOf(1)
        private set

    // True when the active media package is in a DJ/Ad/special-window state.
    // When true we suppress metadata updates and force effectiveIsPlaying=true.
    var nowPlayingSpecialActive by mutableStateOf(false)

    // Tracks how many ad segments have been detected this session so the
    // chatbox can show "Ad 1", "Ad 2" etc. without leaking ad brand names.
    private var adSegmentCount by mutableIntStateOf(0)
    private var lastSpecialWasAd = false
        private set
    // The player's own ad index ("1 of 1", "2 of 3") parsed from the ad metadata,
    // preferred over the session counter when available.
    private var nowPlayingAdInfo by mutableStateOf("")
    // True only while the current special segment is an actual AD (not a DJ
    // segment). The builder checks this BEFORE isSpotifyDj — an ad blanks the
    // artist, which would otherwise make isSpotifyDj true and swallow the label.
    private var nowPlayingIsAd by mutableStateOf(false)

    // True when the active YouTube session is a live stream (non-seekable, no
    // finite duration). The builder shows a LIVE marker instead of a progress bar.
    private var nowPlayingIsLive by mutableStateOf(false)

    // =========================
    // Time feature
    // =========================
    var timeEnabled by mutableStateOf(savedState["timeEnabled"] ?: false)
        private set

    // Stored as: "Device", "UTC", "UTC+1".."UTC+14", "UTC-1".."UTC-12"
    var timeMode by mutableStateOf("Device")
        private set

    fun updateTimeEnabled(enabled: Boolean) {
        if (isBanned) return
        timeEnabled = enabled
        savedState["timeEnabled"] = enabled
        persistFeatureSession()
        if (oscSending) {
            rebuildAndMaybeSendCombined(forceSend = true)
            manageKeepaliveLoop()
        } else {
            rebuildCombinedPreviewOnly()
        }
        startSelfSyncLoopIfNeeded()
    }

    fun updateTimeMode(mode: String) {
        if (isBanned) return
        timeMode = mode
        viewModelScope.launch { userPreferencesRepository.saveTimeMode(mode) }
        rebuildCombinedPreviewOnly()
        startSelfSyncLoopIfNeeded()
    }

    private fun currentTimeString(): String {
        val zone: java.time.ZoneId = when {
            timeMode == "Device" || timeMode == "LOCAL" ->
                java.time.ZoneId.systemDefault()
            timeMode == "UTC" ->
                ZoneOffset.UTC
            timeMode.startsWith("UTC+") -> {
                val h = timeMode.removePrefix("UTC+").toIntOrNull() ?: 0
                ZoneOffset.ofHours(h)
            }
            timeMode.startsWith("UTC-") -> {
                val h = timeMode.removePrefix("UTC-").toIntOrNull() ?: 0
                ZoneOffset.ofHours(-h)
            }
            else -> java.time.ZoneId.systemDefault()
        }
        val now = java.time.LocalDateTime.now(zone)
        return DateTimeFormatter.ofPattern("HH:mm").format(now)
    }

    var musicRefreshSeconds by mutableStateOf(MUSIC_REFRESH_SECONDS_LOCKED)
        private set

    private var nowPlayingJob: Job? = null
    private var keepaliveJob: Job? = null

    private var inferredIsPlaying = false
    private var lastTrackKeyForInference: String = ""
    private var lastMovementAtMs: Long = 0L
    private var pauseCandidateSinceMs: Long = 0L

    private var lastEffectivePosForTick: Long = 0L
    private var lastEffectiveTickAtMs: Long = 0L
    private var uiTickJob: Job? = null

    private var confirmedTrackKey: String = ""
    private var confirmedTitle: String = ""
    private var confirmedArtist: String = ""
    private var confirmedDurationMs: Long = 0L

    private var pendingTrackKey: String = ""
    private var pendingTitle: String = ""
    private var pendingArtist: String = ""
    private var pendingDurationMs: Long = 0L
    private var pendingSinceMs: Long = 0L
    private var pendingStartPosMs: Long = 0L

    // =========================
    // Card output order (persisted)
    // Valid component names: "NowPlaying", "Pinned", "Cycle"
    // Order = top-to-bottom in the chatbox output. Cut-off priority is unchanged.
    // =========================
    val DEFAULT_CARD_ORDER = listOf("Time", "Pinned", "Cycle", "NowPlaying")

    var cardOrder by mutableStateOf(listOf("Time", "Pinned", "Cycle", "NowPlaying"))
        private set

    fun updateCardOrder(order: List<String>) {
        if (isBanned) return
        val valid = order.filter { it in DEFAULT_CARD_ORDER }.distinct()
        val full = valid + DEFAULT_CARD_ORDER.filter { it !in valid }
        cardOrder = full
        viewModelScope.launch { userPreferencesRepository.saveCardOrder(full) }
        rebuildCombinedPreviewOnly()
        startSelfSyncLoopIfNeeded()
    }

    fun resetCardOrder() {
        updateCardOrder(DEFAULT_CARD_ORDER)
    }

    // =========================
    // UI clutter controls (persisted)
    // =========================
    var afkPresetsCollapsed by mutableStateOf(true)
        private set
    var cyclePresetsCollapsed by mutableStateOf(true)
        private set

    fun updateAfkPresetsCollapsed(value: Boolean) {
        afkPresetsCollapsed = value
        viewModelScope.launch { userPreferencesRepository.saveAfkPresetsCollapsed(value) }
        startSelfSyncLoopIfNeeded()
    }

    fun updateCyclePresetsCollapsed(value: Boolean) {
        cyclePresetsCollapsed = value
        viewModelScope.launch { userPreferencesRepository.saveCyclePresetsCollapsed(value) }
        startSelfSyncLoopIfNeeded()
    }

    // =========================
    // Debug fields shown in UI
    // =========================
    var listenerConnected by mutableStateOf(false)
    var activePackage by mutableStateOf("(none)")
    var nowPlayingDetected by mutableStateOf(false)
    var lastNowPlayingTitle by mutableStateOf("(blank)")
    var lastNowPlayingArtist by mutableStateOf("(blank)")
    var lastSentToVrchatAtMs by mutableStateOf(0L)

    var nowPlayingIsPlaying by mutableStateOf(false)
        private set

    private var nowPlayingDurationMs: Long = 0L
    private var nowPlayingPositionMs: Long = 0L
    private var nowPlayingPositionUpdateTimeMs: Long = 0L
    private var nowPlayingSpeed: Float = 1f
    private var nowPlayingReportedIsPlaying: Boolean = false

    var debugLastAfkOsc by mutableStateOf("")
        private set
    var debugLastCycleOsc by mutableStateOf("")
        private set
    var debugLastMusicOsc by mutableStateOf("")
        private set
    var debugLastCombinedOsc by mutableStateOf("")
        private set

    var combinedPreviewText by mutableStateOf("")
        private set

    var cycleTrimWarning by mutableStateOf("")
    private val afkPresetTexts = mutableStateListOf("", "", "")
    private val cyclePresetMessages = mutableStateListOf("", "", "", "", "")
    private val cyclePresetIntervals = mutableStateListOf(
        CYCLE_INTERVAL_SECONDS_LOCKED,
        CYCLE_INTERVAL_SECONDS_LOCKED,
        CYCLE_INTERVAL_SECONDS_LOCKED,
        CYCLE_INTERVAL_SECONDS_LOCKED,
        CYCLE_INTERVAL_SECONDS_LOCKED
    )

    init {
        // Restore the last-synced baseline from the previous session so the
        // delta writer knows what Firestore already has. Enables cold-open
        // delta writes (only changed content + liveness) instead of full
        // 40-field snapshots on every app restart.
        loadLastSyncedValues()

        // Public build: attach moderation listeners (also drives watcher detection
        // and remote-config snapshots). Admin build skips self-sync entirely.
        attachModerationListenersLoopOnce()

        // VRChat sign-out hard-blocks OSC until re-login (Settings Accounts).
        startVrchatAuthGateWatcher()

        // Live-mode loop: idle until an admin starts watching.
        startLiveSyncWatcher()
        // Browse-mode volatile loop: idle until an admin browses the directory.
        startBrowseVolatileSyncWatcher()

        // Block self-sync until DataStore has provided initial values for all
        // user-content fields AND those values have been assigned into ViewModel
        // state. Otherwise the cold-start sync writes empty ViewModel state to
        // Firestore, the snapshot listener echoes it back, and the user's saved
        // presets/messages get wiped from DataStore. We assign directly here
        // (rather than relying on the collectors below) to remove any race
        // between collector dispatch and the flag flip.
        viewModelScope.launch {
            runCatching {
                afkMessage = userPreferencesRepository.afkMessage.first()
                cycleIntervalSeconds = userPreferencesRepository.cycleInterval.first().coerceAtLeast(2)
                setCycleLinesFromTextPreserve(userPreferencesRepository.cycleMessages.first())
                afkPresetTexts[0] = userPreferencesRepository.afkPreset1.first()
                afkPresetTexts[1] = userPreferencesRepository.afkPreset2.first()
                afkPresetTexts[2] = userPreferencesRepository.afkPreset3.first()
                cyclePresetMessages[0] = userPreferencesRepository.cyclePreset1Messages.first()
                cyclePresetMessages[1] = userPreferencesRepository.cyclePreset2Messages.first()
                cyclePresetMessages[2] = userPreferencesRepository.cyclePreset3Messages.first()
                cyclePresetMessages[3] = userPreferencesRepository.cyclePreset4Messages.first()
                cyclePresetMessages[4] = userPreferencesRepository.cyclePreset5Messages.first()
                spotifyPreset = userPreferencesRepository.spotifyPreset.first().coerceIn(1, 5)
                timeMode = userPreferencesRepository.timeMode.first()
            }
            initialDataLoaded = true
            // Auto-restore feature toggles after an unexpected process death (OS
            // memory pressure / Doze / OEM killer). A deliberate swipe disarms this
            // (AppShutdown), so an intentional close still starts clean. This is what
            // makes the chatbox resume on its own — including headlessly, when a
            // service (KeepAliveService) recreated this ViewModel without any UI.
            restoreFeatureSession()
            // Read-before-write: fetch the Firestore doc so admin edits
            // made while the user was offline aren't clobbered by our
            // app-open write. Toggles always start OFF per design, so we
            // only merge content fields (messages, presets, intervals).
            // Timeout after 12s — the read is now Source.SERVER (a network
            // round-trip after anon-auth), which can exceed 5s on a cold start over
            // cellular. DataStore already has the latest local state and the
            // moderation snapshot listener is the backstop (it also delivers server
            // data and applies admin edits), so this is best-effort, not a gate.
            kotlinx.coroutines.withTimeoutOrNull(12_000L) {
                applyRemoteContentBeforeSync()
            }
            // Cold-open write: this is the "user got online" write, anchored to
            // app open. Throttled when it would be liveness-only and a real write
            // landed recently (background-survival relaunches don't re-pay it). The
            // hourly heartbeat is anchored to the last real write so a write still
            // lands within the staleness window even when the cold-open is skipped.
            performSelfSync(coldOpen = true)
            startHourlyHeartbeat()
        }

        // Cross-device content sync: when the same VRChat account is used on
        // multiple phones, the devices have different deviceHashes (separate docs).
        // On app open, once we know our VRChat userId, check for a sibling device
        // doc with fresher content and pull from it — so editing presets/messages
        // on one phone auto-syncs to the other on its next open.
        viewModelScope.launch {
            if (BuildConfig.IS_ADMIN_BUILD) return@launch
            applyCrossDeviceSync()
        }

        // Minimal chatbox background: seed from DataStore on every VM creation
        // (incl. headless revival) and keep the OSC chokepoint flags in sync.
        // Deliberately no startSelfSyncLoopIfNeeded — local-only preference.
        viewModelScope.launch {
            userPreferencesRepository.minimalChatboxBg.collect {
                minimalChatboxBg = it
                remoteVrcaOsc.minimalBackground = it
                localVrcaOsc.minimalBackground = it
                rebuildCombinedPreviewOnly()
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.afkMessage.collect {
                afkMessage = it
                rebuildCombinedPreviewOnly()
                startSelfSyncLoopIfNeeded()
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.cycleMessages.collect { text ->
                setCycleLinesFromTextPreserve(text)
                startSelfSyncLoopIfNeeded()
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.cycleInterval.collect {
                cycleIntervalSeconds = it.coerceAtLeast(2)
                rebuildCombinedPreviewOnly()
                startSelfSyncLoopIfNeeded()
            }
        }

        viewModelScope.launch { userPreferencesRepository.afkPreset1.collect { afkPresetTexts[0] = it; startSelfSyncLoopIfNeeded() } }
        viewModelScope.launch { userPreferencesRepository.afkPreset2.collect { afkPresetTexts[1] = it; startSelfSyncLoopIfNeeded() } }
        viewModelScope.launch { userPreferencesRepository.afkPreset3.collect { afkPresetTexts[2] = it; startSelfSyncLoopIfNeeded() } }

        viewModelScope.launch { userPreferencesRepository.cyclePreset1Messages.collect { cyclePresetMessages[0] = it; startSelfSyncLoopIfNeeded() } }
        viewModelScope.launch { userPreferencesRepository.cyclePreset1Interval.collect { cyclePresetIntervals[0] = CYCLE_INTERVAL_SECONDS_LOCKED } }
        viewModelScope.launch { userPreferencesRepository.cyclePreset2Messages.collect { cyclePresetMessages[1] = it; startSelfSyncLoopIfNeeded() } }
        viewModelScope.launch { userPreferencesRepository.cyclePreset2Interval.collect { cyclePresetIntervals[1] = CYCLE_INTERVAL_SECONDS_LOCKED } }
        viewModelScope.launch { userPreferencesRepository.cyclePreset3Messages.collect { cyclePresetMessages[2] = it; startSelfSyncLoopIfNeeded() } }
        viewModelScope.launch { userPreferencesRepository.cyclePreset3Interval.collect { cyclePresetIntervals[2] = CYCLE_INTERVAL_SECONDS_LOCKED } }
        viewModelScope.launch { userPreferencesRepository.cyclePreset4Messages.collect { cyclePresetMessages[3] = it; startSelfSyncLoopIfNeeded() } }
        viewModelScope.launch { userPreferencesRepository.cyclePreset4Interval.collect { cyclePresetIntervals[3] = CYCLE_INTERVAL_SECONDS_LOCKED } }
        viewModelScope.launch { userPreferencesRepository.cyclePreset5Messages.collect { cyclePresetMessages[4] = it; startSelfSyncLoopIfNeeded() } }
        viewModelScope.launch { userPreferencesRepository.cyclePreset5Interval.collect { cyclePresetIntervals[4] = CYCLE_INTERVAL_SECONDS_LOCKED } }

        viewModelScope.launch {
            userPreferencesRepository.spotifyPreset.collect { saved ->
                spotifyPreset = saved.coerceIn(1, 5)
                rebuildCombinedPreviewOnly()
                startSelfSyncLoopIfNeeded()
            }
        }

        viewModelScope.launch { userPreferencesRepository.afkPresetsCollapsed.collect { afkPresetsCollapsed = it } }
        viewModelScope.launch { userPreferencesRepository.cyclePresetsCollapsed.collect { cyclePresetsCollapsed = it } }

        viewModelScope.launch {
            userPreferencesRepository.cardOrder.collect { saved ->
                // Migrate legacy "AFK" key to "Pinned"
                val migrated = saved.map { if (it == "AFK") "Pinned" else it }
                val validComponents = DEFAULT_CARD_ORDER.toSet()
                val valid = migrated.filter { it in validComponents }.distinct()
                val full = valid + DEFAULT_CARD_ORDER.filter { it !in valid }
                cardOrder = full
                rebuildCombinedPreviewOnly()
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.timeMode.collect {
                timeMode = it
                rebuildCombinedPreviewOnly()
            }
        }

        uiTickJob?.cancel()
        uiTickJob = viewModelScope.launch {
            while (true) {
                tickNowPlayingMovement()
                tickCyclePreviewOnly()
                nowPlayingIsPlaying = computeDisplayedPlaying()
                rebuildCombinedPreviewOnly()
                delay(UI_TICK_MS)
            }
        }

        viewModelScope.launch {
            NowPlayingState.state.collect { s ->
                listenerConnected = s.listenerConnected
                activePackage = if (s.activePackage.isBlank()) "(none)" else s.activePackage
                nowPlayingDetected = s.detected

                nowPlayingDurationMs = s.durationMs
                nowPlayingPositionMs = s.positionMs
                nowPlayingPositionUpdateTimeMs = s.positionUpdateTimeMs
                nowPlayingSpeed = s.playbackSpeed
                nowPlayingReportedIsPlaying = s.isPlaying

                val key = "${s.title.trim()}|${s.artist.trim()}|${s.durationMs}"
                val trackChanged =
                    key != lastTrackKeyForInference && (s.title.isNotBlank() || s.artist.isNotBlank())
                if (trackChanged) {
                    lastTrackKeyForInference = key
                    lastMovementAtMs = System.currentTimeMillis()
                    pauseCandidateSinceMs = 0L
                    inferredIsPlaying = true

                    lastEffectivePosForTick = nowPlayingPositionMs.coerceAtLeast(0L)
                    lastEffectiveTickAtMs = System.currentTimeMillis()
                }

                nowPlayingSpecialActive = s.specialActive
                nowPlayingAdInfo = s.adInfo
                nowPlayingIsLive = s.isLive

                // Track ad segment count: increment only when transitioning INTO an ad,
                // not on every tick. Reset when ad ends so next ad gets a fresh count.
                val isAdNow = s.specialActive && s.title.trim().lowercase().let { t ->
                    t.contains("advert") || t == "ad" || t.contains("advertisement") || t.contains("sponsored")
                } || (s.specialActive && s.activePackage == "com.spotify.music" && s.title.trim() == "AD")
                if (isAdNow && !lastSpecialWasAd) {
                    adSegmentCount++
                } else if (!isAdNow && lastSpecialWasAd) {
                    // Ad break ended — reset so the NEXT break starts at "Ad 1" instead of
                    // climbing 1→2→3 across the whole session. (Combined with the
                    // service-side window lock that keeps isAdNow continuously true through
                    // a single ad, this stops the count incrementing randomly mid-ad.)
                    adSegmentCount = 0
                }
                lastSpecialWasAd = isAdNow
                nowPlayingIsAd = isAdNow

                // Special window only gates playing-state (prevents Paused flicker during DJ/ads).
                // Title updates always go through stabilize so real track shows immediately
                // when the DJ/ad segment ends, even if the 30s special window is still active.
                if (s.specialActive) {
                    nowPlayingReportedIsPlaying = true
                }
                stabilizeNowPlayingMetadata(
                    rawTitle = s.title,
                    rawArtist = s.artist,
                    rawDurationMs = s.durationMs,
                    positionMs = s.positionMs,
                    reportedIsPlaying = s.isPlaying,
                    inferredIsPlaying = inferredIsPlaying,
                    forceConfirm = trackChanged  // skips pending, shows title immediately on skip
                )

                nowPlayingIsPlaying = computeDisplayedPlaying()
                rebuildCombinedPreviewOnly()
                // NowPlaying changes are volatile and fire constantly during
                // playback. They are NOT written to Firestore here — doing so
                // produced a write storm even when no admin was present. The
                // watcher-gated live-sync loop (when watched) and the
                // browse-gated volatile loop (when an admin is on the
                // dashboard/users tab) handle pushing preview/nowPlaying to
                // Firestore. With no admin present, nothing is written.
            }
        }
    }

    private fun tickCyclePreviewOnly() {
        if (!cycleEnabled) return
        if (cycleJob != null) return
        val msgs = cycleLines.map { it.trim() }.filter { it.isNotEmpty() }.take(10)
        if (msgs.isEmpty()) return

        val now = System.currentTimeMillis()
        if (lastCyclePreviewAdvanceMs == 0L) {
            lastCyclePreviewAdvanceMs = now
            return
        }

        val intervalMs = cycleIntervalSeconds.toLong() * 1000L
        if (now - lastCyclePreviewAdvanceMs >= intervalMs) {
            cycleIndex = (cycleIndex + 1) % msgs.size
            lastCyclePreviewAdvanceMs = now
        }
    }

    private fun effectivePosNowMs(): Long {
        val snap = nowPlayingPositionMs.coerceAtLeast(0L)
        val dur = nowPlayingDurationMs.coerceAtLeast(0L)
        val elapsed = SystemClock.elapsedRealtime() - nowPlayingPositionUpdateTimeMs
        val speed = nowPlayingSpeed
        val adv = if (elapsed > 0L) (elapsed.toFloat() * speed).toLong() else 0L
        val eff = snap + max(0L, adv)
        return if (dur > 0L) eff.coerceAtMost(dur) else eff
    }

    private fun tickNowPlayingMovement() {
        if (!spotifyEnabled) return
        if (!nowPlayingDetected && !spotifyDemoEnabled) return

        val nowMs = System.currentTimeMillis()
        val eff = effectivePosNowMs()

        if (lastEffectiveTickAtMs == 0L) {
            lastEffectiveTickAtMs = nowMs
            lastEffectivePosForTick = eff
            lastMovementAtMs = nowMs
            return
        }

        val dp = eff - lastEffectivePosForTick
        lastEffectivePosForTick = eff
        lastEffectiveTickAtMs = nowMs

        val moved = dp >= 150L || abs(dp) >= 1_000L

        // Only treat position advance as "playing evidence" when the service reports playing.
        // If reported paused, extrapolated pos still advances (speed=1f jitter) - don't let that
        // reset pauseCandidateSinceMs and prevent the pause dot from appearing.
        if (moved && nowPlayingReportedIsPlaying) {
            lastMovementAtMs = nowMs
            pauseCandidateSinceMs = 0L
            inferredIsPlaying = true
            return
        }

        if (!nowPlayingReportedIsPlaying || abs(nowPlayingSpeed) < 0.01f) {
            if (pauseCandidateSinceMs == 0L) pauseCandidateSinceMs = nowMs
        } else {
            pauseCandidateSinceMs = 0L
        }
    }

    private fun computeDisplayedPlaying(): Boolean {
        val now = System.currentTimeMillis()
        val noMoveForMs = now - lastMovementAtMs

        // If the service reported NOT playing AND speed is 0, the player has
        // explicitly signalled a user pause. Trust it immediately for all apps.
        // speed == 0f is set by NowPlayingState when playbackSpeed == 0f
        // (reliable on most players including Spotify, Apple Music, Deezer, etc.)
        if (!nowPlayingReportedIsPlaying && nowPlayingSpeed == 0f) {
            return false
        }

        // YouTube MUSIC: its raw position is unreliable (advances while paused / freezes
        // while playing) so any motion heuristic INVERTS it. Its reported state is honest
        // — trust it directly in BOTH directions and skip all motion logic below.
        if (activePackage == "com.google.android.apps.youtube.music") {
            return nowPlayingReportedIsPlaying
        }

        // YouTube VIDEO app: NowPlayingState already ran stall detection and forced
        // isPlaying=false. Trust it directly -- skip motion heuristics for YouTube
        // because YouTube keeps reporting speed=1f and position advances via extrapolation
        // even when truly paused, which fools the motion ticker.
        val youtubePackages = setOf(
            "com.google.android.youtube",
            "com.google.android.apps.youtube.music"
        )
        if (activePackage in youtubePackages && !nowPlayingReportedIsPlaying) {
            return false
        }

        // Generic hard-pause: reported not playing + no motion candidate for 300ms.
        // 300ms (down from 1200ms) because STATE_PAUSED is already unambiguous.
        val hardPause =
            !nowPlayingReportedIsPlaying &&
                pauseCandidateSinceMs > 0L &&
                (now - pauseCandidateSinceMs) >= 300L

        if (hardPause) return false
        if (noMoveForMs >= NO_MOVE_PAUSE_MS) return false
        return true
    }

    // =========================
    // System intents
    // =========================
    fun notificationAccessIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun overlayPermissionIntent(): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${BuildConfig.APPLICATION_ID}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun batteryOptimizationIntent(): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${BuildConfig.APPLICATION_ID}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    // =========================
    // START / STOP (OSC send gate)
    // =========================
    /** START: begin transmitting over OSC. Flips the [oscSending] gate on and
     *  launches the sender loops for whatever toggles are currently enabled.
     *  Toggling features without pressing START only updates the preview. */
    fun startSending(local: Boolean = false) {
        if (isBanned) return
        // Cancel any in-flight robust-clear from a just-pressed Stop so its trailing
        // empty sends can't blank the content we're about to transmit.
        clearJob?.cancel(); clearJob = null
        oscSending = true
        savedState["oscSending"] = true
        // Fresh Start stamps the uptime epoch; a restore that pre-seeded a
        // surviving epoch (OS-kill revival inside the grace window) keeps it.
        if (sendingSinceMs <= 0L) sendingSinceMs = System.currentTimeMillis()
        savedState["sendingSinceMs"] = sendingSinceMs
        startUptimeHeartbeat()
        persistFeatureSession()
        // Launch whatever is configured. Each starter no-ops if its toggle is off.
        startAfkSender(local)
        startCycle(local)
        startNowPlayingSender(local)
        manageKeepaliveLoop(local)
        rebuildAndMaybeSendCombined(forceSend = true, local = local)
        startSelfSyncLoopIfNeeded()
    }

    /** STOP: stop transmitting over OSC and clear the VRChat chatbox. Does NOT
     *  untoggle any feature — the toggle configuration is preserved so the user
     *  can press START again to resume exactly what they had set up. */
    fun stopSending(local: Boolean = false) {
        oscSending = false
        savedState["oscSending"] = false
        sendingSinceMs = 0L
        savedState["sendingSinceMs"] = 0L
        uptimeHeartbeatJob?.cancel(); uptimeHeartbeatJob = null
        persistFeatureSession()
        stopAll(clearFromChatbox = false)
        keepaliveJob?.cancel(); keepaliveJob = null
        if (!isBanned) clearChatboxRobust(local)
        // Keep the preview reflecting the still-enabled toggles (we did NOT untoggle).
        rebuildCombinedPreviewOnly()
        startSelfSyncLoopIfNeeded()
    }

    // =========================
    // KILL switch (ban path) — stops sending AND untoggles everything.
    // Used only when the account is banned; the user-facing button is STOP.
    // =========================
    fun killStopAndClear(local: Boolean = false) {
        stopAll(clearFromChatbox = false)
        oscSending = false; savedState["oscSending"] = false
        sendingSinceMs = 0L; savedState["sendingSinceMs"] = 0L
        uptimeHeartbeatJob?.cancel(); uptimeHeartbeatJob = null
        afkEnabled = false; savedState["afkEnabled"] = false
        cycleEnabled = false; savedState["cycleEnabled"] = false
        spotifyEnabled = false; savedState["spotifyEnabled"] = false
        timeEnabled = false; savedState["timeEnabled"] = false
        persistFeatureSession()
        keepaliveJob?.cancel(); keepaliveJob = null
        if (!isBanned) clearChatboxRobust(local)
        rebuildCombinedPreviewOnly(forceClearIfAllOff = true)
        startSelfSyncLoopIfNeeded()
    }

    /** Persist the current toggle set so it survives an unexpected process death.
     *  A deliberate swipe disarms restore via AppShutdown. */
    private fun persistFeatureSession() {
        FeatureSessionStore.save(
            app.applicationContext,
            afk = afkEnabled,
            cycle = cycleEnabled,
            spotify = spotifyEnabled,
            time = timeEnabled,
            sending = oscSending,
            sendingSinceMs = sendingSinceMs
        )
    }

    /** Slow (~60s) heartbeat while OSC is transmitting. The persisted timestamp
     *  is what lets [restoreFeatureSession] distinguish a short OS-kill→watchdog
     *  gap (uptime counter continues) from a long dead window (counter resets,
     *  sending still resumes). */
    private fun startUptimeHeartbeat() {
        uptimeHeartbeatJob?.cancel()
        uptimeHeartbeatJob = viewModelScope.launch {
            while (oscSending) {
                FeatureSessionStore.heartbeatSending(app.applicationContext)
                delay(60_000L)
            }
        }
    }

    /** Restore the toggle CONFIG that was set up before an unexpected kill, and
     *  resume OSC sending ONLY if the user was actively sending (pressed START).
     *  No-op on a fresh/intentional launch (restore not armed). This is what makes
     *  an OS kill invisible — if the user was sending, the chatbox resumes on its
     *  own "like nothing happened"; if they had toggles configured but hadn't
     *  pressed START, the config comes back but nothing transmits. */
    private fun restoreFeatureSession() {
        val pending = FeatureSessionStore.pendingRestore(app.applicationContext) ?: return
        if (isBanned) return
        // Re-apply the toggle configuration directly (no sender start — the gate
        // below decides whether anything actually transmits).
        afkEnabled = pending.afk; savedState["afkEnabled"] = pending.afk
        cycleEnabled = pending.cycle; savedState["cycleEnabled"] = pending.cycle
        spotifyEnabled = pending.spotify; savedState["spotifyEnabled"] = pending.spotify
        timeEnabled = pending.time; savedState["timeEnabled"] = pending.time
        oscSending = false; savedState["oscSending"] = false
        if (pending.sending && pending.anyEnabled) {
            // Was actively sending → resume seamlessly. Continue the uptime
            // counter only when the dead window is short (the kill→watchdog
            // gap); after a long gap the counter starts fresh while sending
            // still resumes (same grace-window pattern as the RPC counter).
            val sinceSeen = System.currentTimeMillis() - pending.lastSendingSeenMs
            sendingSinceMs =
                if (pending.sendingSinceMs > 0L && sinceSeen in 0..UPTIME_RESTORE_GRACE_MS) pending.sendingSinceMs
                else 0L
            savedState["sendingSinceMs"] = sendingSinceMs
            restoredActiveSending = true
            startSending()
        } else {
            // Configured but idle → just reflect the toggles in the preview.
            rebuildCombinedPreviewOnly()
        }
    }

    // =========================
    // Enable flags
    // =========================
    fun setAfkEnabledFlag(enabled: Boolean) {
        if (isBanned) return
        afkEnabled = enabled
        savedState["afkEnabled"] = enabled
        persistFeatureSession()
        if (oscSending) {
            // Live: start/stop this feature's loop immediately.
            if (!enabled) stopAfkSender(clearFromChatbox = true)
            else startAfkSender()
            rebuildAndMaybeSendCombined(forceSend = true)
            manageKeepaliveLoop()
        } else {
            // Idle (not sending): just update config + preview, no OSC.
            afkJob?.cancel(); afkJob = null
            rebuildCombinedPreviewOnly()
        }
        startSelfSyncLoopIfNeeded()
    }

    fun setCycleEnabledFlag(enabled: Boolean) {
        if (isBanned) return
        cycleEnabled = enabled
        savedState["cycleEnabled"] = enabled
        lastCyclePreviewAdvanceMs = 0L
        persistFeatureSession()
        if (oscSending) {
            if (!enabled) stopCycle(clearFromChatbox = true)
            else startCycle()
            rebuildAndMaybeSendCombined(forceSend = true)
            manageKeepaliveLoop()
        } else {
            cycleJob?.cancel(); cycleJob = null
            rebuildCombinedPreviewOnly()
        }
        startSelfSyncLoopIfNeeded()
    }

    fun setSpotifyEnabledFlag(enabled: Boolean) {
        if (isBanned) return
        spotifyEnabled = enabled
        savedState["spotifyEnabled"] = enabled
        persistFeatureSession()
        if (oscSending) {
            if (!enabled) stopNowPlayingSender(clearFromChatbox = true)
            else startNowPlayingSender()
            rebuildAndMaybeSendCombined(forceSend = true)
            manageKeepaliveLoop()
        } else {
            nowPlayingJob?.cancel(); nowPlayingJob = null
            rebuildCombinedPreviewOnly()
        }
        startSelfSyncLoopIfNeeded()
    }

    fun setSpotifyDemoFlag(enabled: Boolean) {
        if (isBanned) return
        spotifyDemoEnabled = enabled
        rebuildCombinedPreviewOnly()
        startSelfSyncLoopIfNeeded()
    }

    fun updateCycleIntervalSeconds(seconds: Int) {
        if (isBanned) return
        val allowed = listOf(2, 5, 10, 20, 40)
        val safe = allowed.minByOrNull { kotlin.math.abs(it - seconds) } ?: 10
        cycleIntervalSeconds = safe
        viewModelScope.launch { userPreferencesRepository.saveCycleInterval(safe) }
        startSelfSyncLoopIfNeeded()
    }

    fun updateSpotifyPreset(preset: Int) {
        if (isBanned) return
        val v = preset.coerceIn(1, 5)
        spotifyPreset = v
        viewModelScope.launch { userPreferencesRepository.saveSpotifyPreset(v) }
        rebuildCombinedPreviewOnly()
        startSelfSyncLoopIfNeeded()
    }

    // =========================
    // AFK text update
    // =========================
    fun updateAfkText(text: String) {
        if (isBanned) return
        afkMessage = text
        viewModelScope.launch { userPreferencesRepository.saveAfkMessage(text) }
        rebuildCombinedPreviewOnly()
        startSelfSyncLoopIfNeeded()
    }

    // =========================
    // Cycle lines management
    // =========================
    private fun setCycleLinesFromTextPreserve(text: String) {
        val raw = text.split("\n")
        val lines = raw.take(10)
        cycleLines.clear()
        cycleLines.addAll(lines)
        rebuildCombinedPreviewOnly()
    }

    private fun persistCycleLinesPreserve() {
        val joined = cycleLines.take(10).joinToString("\n")
        viewModelScope.launch { userPreferencesRepository.saveCycleMessages(joined) }
        startSelfSyncLoopIfNeeded()
    }

    fun addCycleLine() {
        if (isBanned) return
        if (cycleLines.size >= 10) return
        cycleLines.add("")
        persistCycleLinesPreserve()
        rebuildCombinedPreviewOnly()
    }

    fun removeCycleLine(index: Int) {
        if (isBanned) return
        if (index !in cycleLines.indices) return
        cycleLines.removeAt(index)
        persistCycleLinesPreserve()
        rebuildCombinedPreviewOnly()
    }

    fun updateCycleLine(index: Int, value: String) {
        if (isBanned) return
        if (index !in cycleLines.indices) return
        cycleLines[index] = value
        persistCycleLinesPreserve()
        rebuildCombinedPreviewOnly()
    }

    fun clearCycleLines() {
        if (isBanned) return
        cycleLines.clear()
        persistCycleLinesPreserve()
        rebuildCombinedPreviewOnly()
    }

    // =========================
    // Preset previews
    // =========================
    fun getAfkPresetPreview(slot: Int): String {
        val i = slot.coerceIn(1, 3) - 1
        return afkPresetTexts[i].trim()
    }

    fun getCyclePresetPreview(slot: Int): String {
        val i = slot.coerceIn(1, 5) - 1
        val firstLine = cyclePresetMessages[i].lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return firstLine
    }

    /** Full multi-line cycle preset content — the Automations preset-chip
     *  long-press peek needs more than the first line. */
    fun getCyclePresetFull(slot: Int): String {
        val i = slot.coerceIn(1, 5) - 1
        return cyclePresetMessages[i]
    }

    /** The cycle line currently on screen (or first line when idle) — drives
     *  the Automations collapsed-card "now: '…'" summary. */
    fun cycleCurrentLine(): String = currentCycleLinePreview()

    fun getMusicPresetName(preset: Int): String = when (preset.coerceIn(1, 5)) {
        1 -> "Love"
        2 -> "Minimal"
        3 -> "Crystal"
        4 -> "Soundwave"
        else -> "Geometry"
    }

    fun renderMusicPresetPreview(preset: Int, t: Float): String {
        val p = t.coerceIn(0f, 1f)
        val pos = (p * 1000f).toLong()
        return renderProgressBar(preset, pos, 1000L, isPlaying = true)
    }

    // =========================
    // Preset save/load
    // =========================
    suspend fun saveAfkPreset(slot: Int, text: String) {
        if (isBanned) return
        val s = slot.coerceIn(1, 3)
        val idx = s - 1
        afkPresetTexts[idx] = text
        when (s) {
            1 -> userPreferencesRepository.saveAfkPreset1(text)
            2 -> userPreferencesRepository.saveAfkPreset2(text)
            else -> userPreferencesRepository.saveAfkPreset3(text)
        }
        startSelfSyncLoopIfNeeded()
    }


    suspend fun loadAfkPreset(slot: Int) {
        if (isBanned) return
        val txt = when (slot.coerceIn(1, 3)) {
            1 -> userPreferencesRepository.afkPreset1.first()
            2 -> userPreferencesRepository.afkPreset2.first()
            else -> userPreferencesRepository.afkPreset3.first()
        }
        updateAfkText(txt)
        startSelfSyncLoopIfNeeded()
    }

    suspend fun saveCyclePreset(slot: Int, lines: List<String>) {
        if (isBanned) return
        val s = slot.coerceIn(1, 5)
        val idx = s - 1

        val messages = lines.map { it.trim() }.filter { it.isNotEmpty() }.take(10).joinToString("\n")
        val interval = cycleIntervalSeconds

        cyclePresetMessages[idx] = messages
        cyclePresetIntervals[idx] = interval

        when (s) {
            1 -> userPreferencesRepository.saveCyclePreset1(messages, interval)
            2 -> userPreferencesRepository.saveCyclePreset2(messages, interval)
            3 -> userPreferencesRepository.saveCyclePreset3(messages, interval)
            4 -> userPreferencesRepository.saveCyclePreset4(messages, interval)
            else -> userPreferencesRepository.saveCyclePreset5(messages, interval)
        }
        startSelfSyncLoopIfNeeded()
    }

    suspend fun loadCyclePreset(slot: Int) {
        if (isBanned) return
        val s = slot.coerceIn(1, 5)
        val (messages, storedInterval) = when (s) {
            1 -> userPreferencesRepository.cyclePreset1Messages.first() to userPreferencesRepository.cyclePreset1Interval.first()
            2 -> userPreferencesRepository.cyclePreset2Messages.first() to userPreferencesRepository.cyclePreset2Interval.first()
            3 -> userPreferencesRepository.cyclePreset3Messages.first() to userPreferencesRepository.cyclePreset3Interval.first()
            4 -> userPreferencesRepository.cyclePreset4Messages.first() to userPreferencesRepository.cyclePreset4Interval.first()
            else -> userPreferencesRepository.cyclePreset5Messages.first() to userPreferencesRepository.cyclePreset5Interval.first()
        }

        cycleIntervalSeconds = storedInterval.coerceAtLeast(2)
        viewModelScope.launch { userPreferencesRepository.saveCycleInterval(cycleIntervalSeconds) }

        setCycleLinesFromTextPreserve(messages)
        persistCycleLinesPreserve()
        startSelfSyncLoopIfNeeded()
    }

    // =========================
    // AFK sender
    // =========================
    fun startAfkSender(local: Boolean = false) {
        if (isBanned) return
        if (!afkEnabled || !oscSending) return
        // AFK sender has its own 12s loop — cancel keepalive to avoid duplication.
        keepaliveJob?.cancel()
        keepaliveJob = null
        afkJob?.cancel()
        afkJob = viewModelScope.launch {
            while (afkEnabled && oscSending && !isBanned) {
                rebuildAndMaybeSendCombined(forceSend = true, local = local)
                delay(afkForcedIntervalSeconds.toLong() * 1000L)
            }
        }
        startSelfSyncLoopIfNeeded()
    }

    fun stopAfkSender(clearFromChatbox: Boolean) {
        afkJob?.cancel()
        afkJob = null
        if (clearFromChatbox && !isBanned) rebuildAndMaybeSendCombined(forceSend = true, forceClearIfAllOff = true)
        startSelfSyncLoopIfNeeded()
        manageKeepaliveLoop()
    }

    fun sendAfkNow(local: Boolean = false) {
        if (isBanned) return
        rebuildAndMaybeSendCombined(forceSend = true, local = local)
        startSelfSyncLoopIfNeeded()
    }

    // =========================
    // Cycle sender
    // =========================
    fun startCycle(local: Boolean = false) {
        if (isBanned) return
        val msgs = cycleLines.map { it.trim() }.filter { it.isNotEmpty() }.take(10)
        if (!cycleEnabled || !oscSending || msgs.isEmpty()) return

        persistCycleLinesPreserve()
        viewModelScope.launch { userPreferencesRepository.saveCycleInterval(cycleIntervalSeconds) }

        // Cycle has its own interval-based send loop — cancel keepalive to avoid duplication.
        keepaliveJob?.cancel()
        keepaliveJob = null
        cycleJob?.cancel()
        cycleJob = viewModelScope.launch {
            cycleIndex = 0
            while (cycleEnabled && oscSending && !isBanned) {
                // Re-read the LIVE lines every tick. The loop used to iterate a
                // list captured ONCE at start, so a mid-send edit kept flashing
                // the pre-edit text at each rotation boundary (every other path
                // reads live cycleLines, so only this loop was stale) until a
                // Stop/Start re-captured it.
                val live = cycleLines.map { it.trim() }.filter { it.isNotEmpty() }.take(10)
                if (live.isEmpty()) {
                    // All lines deleted mid-send: render without a cycle line
                    // (clears the chatbox if nothing else is enabled) and keep
                    // looping so re-adding a line resumes automatically.
                    rebuildAndMaybeSendCombined(forceSend = true, local = local, forceClearIfAllOff = true)
                    nextCycleAtMs = System.currentTimeMillis() + cycleIntervalSeconds.toLong() * 1000L
                    delay(cycleIntervalSeconds.toLong() * 1000L)
                    continue
                }
                if (cycleIndex >= live.size) cycleIndex = 0
                rebuildAndMaybeSendCombined(
                    forceSend = true,
                    local = local,
                    cycleLineOverride = live[cycleIndex]
                )
                // Advance AFTER the interval, not right after the send — during
                // the wait cycleIndex must still point at the line on screen so
                // currentCycleLinePreview() (preview + the other sender loops'
                // null-override rebuilds) agrees with what this tick sent.
                nextCycleAtMs = System.currentTimeMillis() + cycleIntervalSeconds.toLong() * 1000L
                delay(cycleIntervalSeconds.toLong() * 1000L)
                cycleIndex = (cycleIndex + 1) % live.size
            }
            nextCycleAtMs = 0L
        }
        startSelfSyncLoopIfNeeded()
    }

    fun stopCycle(clearFromChatbox: Boolean) {
        cycleJob?.cancel()
        cycleJob = null
        nextCycleAtMs = 0L
        if (clearFromChatbox && !isBanned) rebuildAndMaybeSendCombined(forceSend = true, forceClearIfAllOff = true)
        lastCyclePreviewAdvanceMs = 0L
        startSelfSyncLoopIfNeeded()
        manageKeepaliveLoop()
    }

    // =========================
    // Now Playing sender
    // =========================
    fun startNowPlayingSender(local: Boolean = false) {
        if (isBanned) return
        if (!spotifyEnabled || !oscSending) return
        // NowPlaying has its own 500ms send loop — cancel keepalive to avoid duplication.
        keepaliveJob?.cancel()
        keepaliveJob = null
        nowPlayingJob?.cancel()
        nowPlayingJob = viewModelScope.launch {
            while (spotifyEnabled && oscSending && !isBanned) {
                // run on a 0.5s cadence so OSC updates match VRChat's chatbox rate limit
                rebuildAndMaybeSendCombined(forceSend = true, local = local)
                delay(SEND_FLOOR_MS)
            }
        }
        startSelfSyncLoopIfNeeded()
    }

    fun stopNowPlayingSender(clearFromChatbox: Boolean) {
        nowPlayingJob?.cancel()
        nowPlayingJob = null
        if (clearFromChatbox && !isBanned) rebuildAndMaybeSendCombined(forceSend = true, forceClearIfAllOff = true)
        startSelfSyncLoopIfNeeded()
        manageKeepaliveLoop()
    }

    fun sendNowPlayingOnce(local: Boolean = false) {
        if (isBanned) return
        rebuildAndMaybeSendCombined(forceSend = true, local = local)
        startSelfSyncLoopIfNeeded()
    }

    fun stopAll(clearFromChatbox: Boolean) {
        stopCycle(clearFromChatbox = false)
        stopNowPlayingSender(clearFromChatbox = false)
        stopAfkSender(clearFromChatbox = false)
        keepaliveJob?.cancel()
        keepaliveJob = null
        if (clearFromChatbox && !isBanned) clearChatbox()
        startSelfSyncLoopIfNeeded()
    }

    // =========================
    // Keepalive loop — sends chatbox text periodically when no other
    // sender loop (NowPlaying 500ms, Cycle interval, AFK 12s) is active.
    // Without this, Time-only or Pinned-only modes never push text to
    // VRChat, and VRChat clears the chatbox after ~15s of silence.
    // =========================
    private fun manageKeepaliveLoop(local: Boolean = false) {
        // Not sending → no keepalive (the master gate is off).
        if (!oscSending) {
            keepaliveJob?.cancel()
            keepaliveJob = null
            return
        }
        // NowPlaying and Cycle have their own send loops — don't duplicate.
        if (spotifyEnabled || (cycleEnabled && cycleJob != null)) {
            keepaliveJob?.cancel()
            keepaliveJob = null
            return
        }
        // AFK sender also has its own 12s loop — don't duplicate.
        if (afkEnabled && afkJob != null) {
            keepaliveJob?.cancel()
            keepaliveJob = null
            return
        }
        val anyActive = afkEnabled || cycleEnabled || timeEnabled
        if (anyActive && keepaliveJob == null) {
            keepaliveJob = viewModelScope.launch {
                // Force immediate send on start so text appears right away.
                rebuildAndMaybeSendCombined(forceSend = true, local = local)
                while (true) {
                    delay(3_000L)
                    rebuildAndMaybeSendCombined(forceSend = true, local = local)
                }
            }
        } else if (!anyActive) {
            keepaliveJob?.cancel()
            keepaliveJob = null
        }
    }

    // =========================
    // Combined builder + sender
    // =========================
    private fun rebuildCombinedPreviewOnly(forceClearIfAllOff: Boolean = false) {
        val built = buildCombinedText(cycleLineOverride = null)
        combinedPreviewText = built
        if (forceClearIfAllOff && built.isBlank()) combinedPreviewText = ""
    }

    private fun rebuildAndMaybeSendCombined(
        forceSend: Boolean,
        local: Boolean = false,
        cycleLineOverride: String? = null,
        forceClearIfAllOff: Boolean = false
    ) {
        if (isBanned) return

        val combined = buildCombinedText(cycleLineOverride)

        if (forceClearIfAllOff && combined.isBlank()) {
            clearChatbox(local)
            combinedPreviewText = ""
            return
        }

        combinedPreviewText = combined
        if (!forceSend) return
        if (combined.isBlank()) return

        val nowMs = System.currentTimeMillis()
        if (nowMs - lastCombinedSendMs < SEND_FLOOR_MS) return

        // Content-change dedup: skip the OSC send if the text is identical to
        // what we last sent AND it's been less than 10s. This avoids wasteful
        // repeated sends from the NowPlaying 500ms loop and keepalive loop
        // when nothing has actually changed. The 10s ceiling ensures VRChat
        // doesn't clear the chatbox (~15s inactivity timeout).
        if (combined == lastSentCombinedText && nowMs - lastSentMs < 3_000L) return
        lastSentCombinedText = combined
        lastSentMs = nowMs

        sendToVrchatRaw(combined, local, addToConversation = false)
        lastCombinedSendMs = nowMs
    }

    private fun buildCombinedText(cycleLineOverride: String?): String {
        cycleTrimWarning = ""

        // If banned, preview can still show what WOULD be sent, but nothing will send.
        val afkLine = if (afkEnabled && afkMessage.trim().isNotEmpty()) afkMessage.trim() else ""
        val cycleLine = if (cycleEnabled) (cycleLineOverride ?: currentCycleLinePreview()) else ""
        val musicLines = if (spotifyEnabled) buildNowPlayingLines() else emptyList()

        // Time is a fully independent card: always emit it when enabled.
        // Its position in the output is set by where "Time" sits in cardOrder.
        val standalonTimeLine = if (timeEnabled) currentTimeString() else ""

        debugLastAfkOsc = afkLine
        debugLastCycleOsc = cycleLine
        debugLastMusicOsc = musicLines.joinToString("\n")

        // Assemble lines in user-defined card order (top-to-bottom in chatbox output).
        // Cut-off priority is separate and unchanged (Cycle drops first, then Music, then AFK).
        val rawLines = mutableListOf<LineWithPriority>()
        for (component in cardOrder) {
            when {
                component == "Pinned" || component == "AFK" -> if (afkLine.isNotBlank()) rawLines += LineWithPriority(text = afkLine, priority = Priority.AFK)
                component == "Cycle" -> if (cycleLine.isNotBlank()) rawLines += LineWithPriority(text = cycleLine, priority = Priority.CYCLE)
                component == "NowPlaying" -> for (m in musicLines) if (m.isNotBlank()) rawLines += LineWithPriority(text = m, priority = Priority.MUSIC)
                component == "Time" -> if (standalonTimeLine.isNotBlank()) rawLines += LineWithPriority(text = standalonTimeLine, priority = Priority.MUSIC)
            }
        }

        // With minimal background ON, give the control suffix its 2 chars back
        // out of the content budget so it can never be cut by the 144 limit;
        // OFF returns the full budget to content.
        val charBudget = if (minimalChatboxBg) VRC_MAX_CHARS - MINIMAL_BG_RESERVED_CHARS else VRC_MAX_CHARS
        val limited = limitWithPriority(rawLines, charBudget, VRC_MAX_LINES)

        if (limited.cycleWasModifiedToPreserveMusic) {
            cycleTrimWarning = "Cycle was trimmed to preserve Now Playing (VRChat limits)."
        }

        val combined = limited.text
        debugLastCombinedOsc = combined
        return combined
    }

    private fun clearChatbox(local: Boolean = false) {
        if (isBanned) return
        sendToVrchatRaw("", local, addToConversation = false)
    }

    private var clearJob: Job? = null

    /**
     * Reliably clear the VRChat chatbox on STOP. A SINGLE empty send is flaky:
     *  (a) VRChat enforces a 0.5s chatbox rate limit, so a clear landing <0.5s
     *      after the final content send is silently dropped;
     *  (b) OSC is UDP — a lone packet can just be lost;
     *  (c) a sender loop already mid-`rebuildAndMaybeSendCombined` when STOP was
     *      pressed can land content AFTER the clear, repopulating the box.
     * So we send the empty payload a few times, spaced just past the rate limit,
     * which beats all three races. Reset the send-dedup state so nothing suppresses
     * the empty sends and a later Start re-sends fresh content.
     */
    private fun clearChatboxRobust(local: Boolean = false) {
        if (isBanned) return
        clearJob?.cancel()
        lastSentCombinedText = ""
        lastCombinedSendMs = 0L
        clearJob = viewModelScope.launch {
            repeat(3) {
                clearChatbox(local)
                delay(SEND_FLOOR_MS + 100L)
            }
        }
    }

    private fun currentCycleLinePreview(): String {
        val msgs = cycleLines.map { it.trim() }.filter { it.isNotEmpty() }.take(10)
        if (msgs.isEmpty()) return ""
        return msgs.getOrNull(cycleIndex % msgs.size).orEmpty()
    }

    private fun buildNowPlayingLines(): List<String> {
        val title = if (spotifyDemoEnabled && !nowPlayingDetected) "Pretty Girl" else lastNowPlayingTitle
        val artist = if (spotifyDemoEnabled && !nowPlayingDetected) "Clairo" else lastNowPlayingArtist
        if (!spotifyDemoEnabled && !nowPlayingDetected) return emptyList()

        val safeTitle = title.takeIf { it != "(blank)" }?.trim().orEmpty()
        val safeArtist = artist.takeIf { it != "(blank)" }?.trim().orEmpty()

        // DJ/Ads flicker fix: Spotify (and Spotify-like) players can show blank metadata
        // during DJ segments or ads. During these transitions we force isPlaying=true
        // so the chatbox does not flicker "Paused". Once real track metadata appears,
        // normal logic resumes.
        // nowPlayingSpecialActive covers DJ/ads for any supported player.
        // isSpotifyDj is a secondary check for blank-metadata edge cases.
        // Ad suppression takes PRIORITY over the DJ check below. An ad blanks the
        // artist (title="AD", artist=""), which makes isSpotifyDj true — so gating
        // the ad label on `!isSpotifyDj` swallowed it and rendered a bare "AD"
        // title with a progress bar instead of "Ad 1 of 1". Check the explicit ad
        // flag FIRST and return early so ads always show their index.
        if (nowPlayingIsAd) {
            // The ad index ("Ad 1 of 1") was unreliable, so just show a bare "Ad".
            val label = "Ad"
            // Keep the progress bar during ads (the bar used to vanish, leaving a
            // bare "Ad"). Ads always play, so force playing and render the ad's
            // OWN position/duration countdown. No brand/title is shown — only the
            // neutral "Ad" label + a timer — so nothing leaks.
            val adDur = nowPlayingDurationMs
            if (adDur > 0L) {
                val spd = if (nowPlayingSpeed > 0f) nowPlayingSpeed else 1f
                val elapsed = SystemClock.elapsedRealtime() - nowPlayingPositionUpdateTimeMs
                val adj = (elapsed * spd).toLong()
                val pos = (nowPlayingPositionMs + max(0L, adj)).coerceAtMost(adDur)
                val bar = renderProgressBar(spotifyPreset, pos, max(1L, adDur), true, true)
                val time = "${fmtTime(pos)}/${fmtTime(adDur)}"
                return listOfNotNull(label, (bar + time).takeIf { it.isNotBlank() })
            }
            return listOf(label)
        }

        if (nowPlayingIsLive) {
            val maxLine = 42
            val line1 = TitleCleaner.fitOneLine(safeTitle, safeArtist, maxLine)
            return listOfNotNull(line1.takeIf { it.isNotBlank() }, "● LIVE")
        }

        val isSpotifyDj = activePackage == "com.spotify.music" &&
            nowPlayingDetected &&
            (safeTitle.isBlank() || safeArtist.isBlank())

        val effectiveIsPlaying = if (nowPlayingSpecialActive || isSpotifyDj) true else nowPlayingIsPlaying

        val maxLine = 42
        val line1 = TitleCleaner.fitOneLine(safeTitle, safeArtist, maxLine)

        val dur = if (spotifyDemoEnabled && !nowPlayingDetected) 205_000L else nowPlayingDurationMs
        val posSnapshot = if (spotifyDemoEnabled && !nowPlayingDetected) 78_000L else nowPlayingPositionMs

        val pos = if (effectiveIsPlaying && dur > 0L) {
            val elapsed = SystemClock.elapsedRealtime() - nowPlayingPositionUpdateTimeMs
            val adj = (elapsed * nowPlayingSpeed).toLong()
            (posSnapshot + max(0L, adj)).coerceAtMost(dur)
        } else posSnapshot

        // dotIsPlaying: instant - uses reported state so dot flips immediately on pause/play.
        // DJ/special window forces it true so dot never flickers during ads/transitions.
        val dotIsPlaying = if (nowPlayingSpecialActive || isSpotifyDj) true else nowPlayingReportedIsPlaying
        val bar = renderProgressBar(spotifyPreset, pos, max(1L, dur), effectiveIsPlaying, dotIsPlaying)
        val time = "${fmtTime(pos)}/${fmtTime(max(1L, dur))}"

        // No space between bar and time - saves 1 char.
        // Time is a separate independent card; it is NOT embedded here.
        val line2 = bar + time

        return listOfNotNull(line1.takeIf { it.isNotBlank() }, line2.takeIf { it.isNotBlank() })
    }

    private enum class Priority { AFK, MUSIC, CYCLE }
    private data class LineWithPriority(val text: String, val priority: Priority)
    private data class LimitedResult(val text: String, val cycleWasModifiedToPreserveMusic: Boolean)

    private fun limitWithPriority(lines: List<LineWithPriority>, maxChars: Int, maxLines: Int): LimitedResult {
        if (lines.isEmpty()) return LimitedResult("", false)

        val cleaned = lines.map { it.copy(text = it.text.trim()) }.filter { it.text.isNotEmpty() }.toMutableList()
        if (cleaned.isEmpty()) return LimitedResult("", false)

        var cycleModifiedForMusic = false

        while (cleaned.size > maxLines) {
            val idxToRemove = cleaned.indexOfLast { it.priority == Priority.CYCLE }
                .takeIf { it >= 0 }
                ?: cleaned.indexOfLast { it.priority == Priority.MUSIC }.takeIf { it >= 0 }
                ?: cleaned.indexOfLast { it.priority == Priority.AFK }

            if (idxToRemove >= 0) {
                if (cleaned[idxToRemove].priority == Priority.CYCLE) cycleModifiedForMusic = true
                cleaned.removeAt(idxToRemove)
            } else break
        }

        fun totalLen(list: List<LineWithPriority>): Int {
            var sum = 0
            list.forEachIndexed { idx, l ->
                sum += l.text.length
                if (idx != list.lastIndex) sum += 1
            }
            return sum
        }

        fun trimLineAt(index: Int, needToRemove: Int) {
            val original = cleaned[index].text
            if (original.isEmpty()) return
            val newLen = (original.length - needToRemove).coerceAtLeast(1)
            val trimmed = original.take(newLen)  // hard cut - no ellipsis wastes chars
            if (cleaned[index].priority == Priority.CYCLE) cycleModifiedForMusic = true
            cleaned[index] = cleaned[index].copy(text = trimmed)
        }

        // First pass: try stripping artist name from music lines before hard-trimming.
        // Music lines containing "Artist — Title" can be shortened to just "Title".
        var len = totalLen(cleaned)
        if (len > maxChars) {
            for (i in cleaned.indices) {
                if (cleaned[i].priority == Priority.MUSIC) {
                    val dashIdx = cleaned[i].text.indexOf(" — ")
                    if (dashIdx > 0) {
                        val titleOnly = cleaned[i].text.substring(dashIdx + 3)
                        cleaned[i] = cleaned[i].copy(text = titleOnly)
                        len = totalLen(cleaned)
                        if (len <= maxChars) break
                    }
                }
            }
        }

        while (len > maxChars && cleaned.isNotEmpty()) {
            val excess = len - maxChars

            val cycleIdx = cleaned.indexOfLast { it.priority == Priority.CYCLE }
            val musicIdx = cleaned.indexOfLast { it.priority == Priority.MUSIC }
            val afkIdx   = cleaned.indexOfLast { it.priority == Priority.AFK }

            when {
                cycleIdx >= 0 -> trimLineAt(cycleIdx, excess + 1)
                musicIdx >= 0 -> trimLineAt(musicIdx, excess + 1)
                afkIdx >= 0 -> trimLineAt(afkIdx, excess + 1)
                else -> break
            }

            len = totalLen(cleaned)
            if (len > maxChars) {
                val dropCycle = cleaned.indexOfLast { it.priority == Priority.CYCLE }
                if (dropCycle >= 0 && cleaned.size > 1) {
                    cycleModifiedForMusic = true
                    cleaned.removeAt(dropCycle)
                    len = totalLen(cleaned)
                } else break
            }
        }

        val out = cleaned.joinToString("\n") { it.text }
        return LimitedResult(out, cycleModifiedForMusic)
    }

    // =========================
    // Progress bars
    // =========================

    private fun posDot(isPlaying: Boolean): Char = if (isPlaying) '\u25C9' else '\u23F8'

    // isPlaying   = animation state (smoothed - suppress stall/DJ flicker on position advance)
    // dotIsPlaying = dot char state (instant - reflects actual reported playing state)
    private fun renderProgressBar(
        preset: Int, posMs: Long, durMs: Long,
        isPlaying: Boolean,
        dotIsPlaying: Boolean = isPlaying
    ): String {
        val duration = max(1L, durMs)
        val p = min(1f, max(0f, posMs.toFloat() / duration.toFloat()))
        val dot = posDot(dotIsPlaying)
        // The paused marker (\u23F8) renders as a wide emoji in VRChat's chatbox font
        // (the playing \u25C9 is a normal text glyph), pushing the bar+time line past
        // the box width \u2014 "the time wraps when paused but not when playing".
        // Compensate by dropping ONE filler slot while paused: every slot is the
        // same glyph, so the bar just reads one char shorter; the marker index
        // rescales automatically and the slot returns on resume.
        val pausedTrim = if (dotIsPlaying) 0 else 1

        return when (preset.coerceIn(1, 5)) {
            1 -> {
                val innerSlots = 8 - pausedTrim
                val idx = (p * (innerSlots - 1)).toInt()
                val inner = CharArray(innerSlots) { '\u2501' }
                inner[idx] = dot
                "\u2661" + inner.concatToString() + "\u2661"
            }
            2 -> {
                val slots = 10 - pausedTrim
                val idx = (p * (slots - 1)).toInt()
                val bg = CharArray(slots) { '\u2500' }
                bg[idx] = dot
                bg.concatToString()
            }
            3 -> {
                val slots = 10 - pausedTrim
                val idx = (p * (slots - 1)).toInt()
                val out = CharArray(slots) { i ->
                    when {
                        i < idx  -> '\u25C6'
                        i == idx -> dot
                        else     -> '\u25C7'
                    }
                }
                out.concatToString()
            }
            4 -> renderSoundwaveBar(p, posMs, isPlaying, dotIsPlaying)
            else -> {
                val slots = 10 - pausedTrim
                val idx = (p * (slots - 1)).toInt()
                val out = CharArray(slots) { i ->
                    when {
                        i < idx  -> '\u25A3'
                        i == idx -> dot
                        else     -> '\u25A2'
                    }
                }
                out.concatToString()
            }
        }
    }

    private val soundwavePatterns: List<IntArray> = listOf(
        intArrayOf(6, 3, 6, 4, 7, 3, 6, 4, 7, 4, 6, 3),
        intArrayOf(7, 4, 6, 3, 7, 5, 6, 3, 7, 4, 6, 5),
        intArrayOf(5, 7, 4, 6, 3, 7, 4, 6, 3, 7, 4, 6),
        intArrayOf(6, 4, 7, 5, 3, 6, 4, 7, 5, 3, 6, 4),
        intArrayOf(7, 5, 3, 6, 4, 7, 5, 3, 6, 4, 7, 5),
        intArrayOf(6, 3, 5, 7, 4, 6, 3, 5, 7, 4, 6, 3),
        intArrayOf(5, 6, 7, 4, 3, 7, 6, 5, 4, 7, 6, 5),
        intArrayOf(7, 6, 4, 7, 5, 3, 6, 7, 4, 6, 7, 5),
        intArrayOf(6, 7, 5, 3, 6, 7, 5, 4, 7, 6, 4, 5),
        intArrayOf(7, 4, 6, 7, 3, 5, 7, 4, 6, 7, 3, 5)
    )

    private val soundwavePaused: IntArray = intArrayOf(4, 5, 4, 6, 4, 5, 4, 6, 4, 5, 4, 6)

    private fun renderSoundwaveBar(progress01: Float, posMs: Long, isPlaying: Boolean, dotIsPlaying: Boolean = isPlaying): String {
        // Same paused-trim as renderProgressBar: the [⏸] marker is emoji-wide.
        val slots = if (dotIsPlaying) 8 else 7
        val idx = (progress01 * (slots - 1)).toInt().coerceIn(0, slots - 1)

        val patternIndex = if (isPlaying) ((posMs / 1400L) % soundwavePatterns.size).toInt() else -1
        val base = if (patternIndex >= 0) soundwavePatterns[patternIndex] else soundwavePaused
        val phase = if (isPlaying) ((posMs / 180L) % base.size).toInt() else ((posMs / 900L) % base.size).toInt()

        val chars = CharArray(slots) { i ->
            val amp = base[(i + phase) % base.size].coerceIn(1, 8)
            ampToChar(amp)
        }

        val out = StringBuilder(10)
        for (i in 0 until slots) {
            if (i == idx) {
                out.append('[').append(posDot(dotIsPlaying)).append(']')
            } else out.append(chars[i])
        }
        return out.toString()
    }

    private fun ampToChar(a: Int): Char = when (a.coerceIn(1, 8)) {
        1 -> '\u2581'
        2 -> '\u2582'
        3 -> '\u2583'
        4 -> '\u2584'
        5 -> '\u2585'
        6 -> '\u2586'
        7 -> '\u2587'
        else -> '\u2588'
    }

    private fun fmtTime(ms: Long): String {
        val totalSec = max(0L, ms) / 1000L
        val m = totalSec / 60L
        val s = (totalSec % 60L).toInt()
        return "${m}:${s.toString().padStart(2, '0')}"
    }

    private fun sendToVrchatRaw(text: String, local: Boolean, addToConversation: Boolean) {
        if (isBanned) return
        val osc = if (!local) remoteVrcaOsc else localVrcaOsc
        osc.sendMessage(text, messengerUiState.value.isSendImmediately, triggerSFX = false)
        lastSentToVrchatAtMs = System.currentTimeMillis()
        if (addToConversation) conversationUiState.addMessage(Message(text, false, Instant.now()))
    }

    // =========================
    // metadata stabilization
    // =========================
    private fun stabilizeNowPlayingMetadata(
        rawTitle: String,
        rawArtist: String,
        rawDurationMs: Long,
        positionMs: Long,
        reportedIsPlaying: Boolean,
        inferredIsPlaying: Boolean,
        forceConfirm: Boolean = false
    ) {
        val now = System.currentTimeMillis()

        val t = rawTitle.trim()
        val a = rawArtist.trim()
        val hasMeta = t.isNotBlank() || a.isNotBlank()

        if (!hasMeta) {
            confirmedTrackKey = ""
            confirmedTitle = ""
            confirmedArtist = ""
            confirmedDurationMs = 0L

            pendingTrackKey = ""
            pendingTitle = ""
            pendingArtist = ""
            pendingDurationMs = 0L
            pendingSinceMs = 0L
            pendingStartPosMs = 0L

            lastNowPlayingTitle = "(blank)"
            lastNowPlayingArtist = "(blank)"
            return
        }

        val rawKey = "${t}|${a}|$rawDurationMs"

        // Force-confirm: track changed signal from collect block - skip pending, show title immediately.
        if (forceConfirm && rawKey != confirmedTrackKey) {
            confirmedTrackKey = rawKey
            confirmedTitle = t
            confirmedArtist = a
            confirmedDurationMs = rawDurationMs
            pendingTrackKey = ""
            pendingSinceMs = 0L
            pendingStartPosMs = 0L
            lastNowPlayingTitle = if (t.isBlank()) "(blank)" else t
            lastNowPlayingArtist = if (a.isBlank()) "(blank)" else a
            return
        }

        if (confirmedTrackKey.isBlank()) {
            confirmedTrackKey = rawKey
            confirmedTitle = t
            confirmedArtist = a
            confirmedDurationMs = rawDurationMs
            lastNowPlayingTitle = if (t.isBlank()) "(blank)" else t
            lastNowPlayingArtist = if (a.isBlank()) "(blank)" else a
            pendingTrackKey = ""
            pendingSinceMs = 0L
            return
        }

        if (rawKey == confirmedTrackKey && pendingTrackKey.isBlank()) {
            lastNowPlayingTitle = if (confirmedTitle.isBlank()) "(blank)" else confirmedTitle
            lastNowPlayingArtist = if (confirmedArtist.isBlank()) "(blank)" else confirmedArtist
            return
        }

        val posLooksLikeNewTrack = positionMs in 0..POS_RESET_CONFIRM_MS
        val durationChanged = (rawDurationMs > 0L && confirmedDurationMs > 0L && rawDurationMs != confirmedDurationMs)

        if (rawKey != confirmedTrackKey && (posLooksLikeNewTrack || durationChanged)) {
            confirmedTrackKey = rawKey
            confirmedTitle = t
            confirmedArtist = a
            confirmedDurationMs = rawDurationMs

            pendingTrackKey = ""
            pendingSinceMs = 0L
            pendingStartPosMs = 0L

            lastNowPlayingTitle = if (confirmedTitle.isBlank()) "(blank)" else confirmedTitle
            lastNowPlayingArtist = if (confirmedArtist.isBlank()) "(blank)" else confirmedArtist
            return
        }

        if (pendingTrackKey.isBlank() || rawKey != pendingTrackKey) {
            pendingTrackKey = rawKey
            pendingTitle = t
            pendingArtist = a
            pendingDurationMs = rawDurationMs
            pendingSinceMs = now
            pendingStartPosMs = positionMs.coerceAtLeast(0L)

            lastNowPlayingTitle = if (confirmedTitle.isBlank()) "(blank)" else confirmedTitle
            lastNowPlayingArtist = if (confirmedArtist.isBlank()) "(blank)" else confirmedArtist
            return
        }

        val stableFor = now - pendingSinceMs
        val movedSincePending = (positionMs - pendingStartPosMs)

        val canUsePlayingHint = reportedIsPlaying || inferredIsPlaying
        val confirmByMovement = movedSincePending >= META_CONFIRM_MOVE_MS
        val confirmByStability = stableFor >= META_STABLE_MS && canUsePlayingHint
        val confirmByGiveUp = stableFor >= META_GIVE_UP_MS && canUsePlayingHint

        if (confirmByMovement || confirmByStability || confirmByGiveUp) {
            confirmedTrackKey = pendingTrackKey
            confirmedTitle = pendingTitle
            confirmedArtist = pendingArtist
            confirmedDurationMs = pendingDurationMs

            pendingTrackKey = ""
            pendingSinceMs = 0L
            pendingStartPosMs = 0L

            lastNowPlayingTitle = if (confirmedTitle.isBlank()) "(blank)" else confirmedTitle
            lastNowPlayingArtist = if (confirmedArtist.isBlank()) "(blank)" else confirmedArtist
            return
        }

        lastNowPlayingTitle = if (confirmedTitle.isBlank()) "(blank)" else confirmedTitle
        lastNowPlayingArtist = if (confirmedArtist.isBlank()) "(blank)" else confirmedArtist
    }
}

data class MessengerUiState(
    val ipAddress: String = "127.0.0.1",
    val isRealtimeMsg: Boolean = false,
    val isTriggerSFX: Boolean = true,
    val isTypingIndicator: Boolean = true,
    val isSendImmediately: Boolean = true
)
