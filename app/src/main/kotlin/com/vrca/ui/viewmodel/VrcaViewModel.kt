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
import com.vrca.app.ChatboxSubLine
import com.vrca.app.FeatureSessionStore
import com.vrca.app.SubLineCodec
import com.vrca.app.VrcaApplication
import com.vrca.nowplaying.NowPlayingState
import com.vrca.nowplaying.TitleCleaner
import com.vrca.data.UserPreferencesRepository
import com.vrca.osc.VrcaOsc
import com.vrca.ui.common.resolveTimeZone
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
        // Max cycle lines (raised from the original v1 cap of 10).
        const val MAX_CYCLE_LINES = 20

        private const val VRC_MAX_CHARS = 144
        private const val VRC_MAX_LINES = 9
        // Chars reserved from the 144 budget for the minimal-background control
        // suffix (U+0003+U+001F, appended in VrcaOsc) while that toggle is ON.
        private const val MINIMAL_BG_RESERVED_CHARS = 2

        // Known media-source packages (per-source Now Playing enables).
        const val PKG_SPOTIFY = "com.spotify.music"
        const val PKG_YOUTUBE = "com.google.android.youtube"
        const val PKG_YTMUSIC = "com.google.android.apps.youtube.music"

        private const val SEND_FLOOR_MS = 500L

        // Manual Send takeover: a manual message pauses the automated chatbox
        // (Pinned/Cycle/Music/Time) for this long so people can read it. Extended
        // on every manual send / live keystroke; in Live mode it counts from the
        // last change so the message stays up 10s after the final line.
        private const val MANUAL_HOLD_MS = 10_000L
        // Live-typing push cadence (matches the Music 0.5s refresh).
        private const val MANUAL_LIVE_TICK_MS = 500L
        // Live scroll window: newest N lines stay visible, older ones scroll off.
        private const val MANUAL_SCROLL_LINES = 4
        // Estimated proportional width to wrap each scroll line at — kept a touch
        // narrower than VRChat's ~30-unit chatbox wrap so it never re-wraps our
        // computed lines into a 5th, which would blow the 4-line window.
        private const val MANUAL_SCROLL_WIDTH = 26f

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
        // The sibling query that ALSO drives the single-session claim runs more often
        // than the 30-min content pull (so a freshly-opened device takes over quickly),
        // but is still throttled so an OS-kill relaunch storm doesn't re-read every time.
        private const val PREF_LAST_ACCOUNT_QUERY_MS = "last_account_query_ms"
        private const val ACCOUNT_QUERY_THROTTLE_MS = 60L * 1000L
        private const val PREF_LAST_SYNCED_JSON = "last_synced_values_json"

        // Collections (MUST MATCH YOUR RULES)
        private const val COL_USERS = "users"             // users/{deviceHash}
        private const val COL_USERS_BY_ID = "usersById"   // usersById/{uid}
        private const val COL_BANNED_DEVICES = "bannedDevices"
        private const val COL_ACCOUNTS = "accounts"        // accounts/{vrchatUserId} session lock

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
        val cycleClean = cycleLines.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_CYCLE_LINES)

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

        // Write the RAW (encoded) preset value so sub-line structure round-trips
        // and the admin sees it — getAfkPresetPreview returns a rendered preview.
        data["afkPreset1"] = afkPresetTexts.getOrElse(0) { "" }
        data["afkPreset2"] = afkPresetTexts.getOrElse(1) { "" }
        data["afkPreset3"] = afkPresetTexts.getOrElse(2) { "" }

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

        // Profile pictures are intentionally NOT written to Firestore (cost) and
        // NOT shown in the admin panel (AdminAvatar renders name initials).

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
        put("afkPreset1", afkPresetTexts.getOrElse(0) { "" })
        put("afkPreset2", afkPresetTexts.getOrElse(1) { "" })
        put("afkPreset3", afkPresetTexts.getOrElse(2) { "" })
        put("cyclePreset1", cyclePresetMessages.getOrNull(0)?.trim().orEmpty())
        put("cyclePreset2", cyclePresetMessages.getOrNull(1)?.trim().orEmpty())
        put("cyclePreset3", cyclePresetMessages.getOrNull(2)?.trim().orEmpty())
        put("cyclePreset4", cyclePresetMessages.getOrNull(3)?.trim().orEmpty())
        put("cyclePreset5", cyclePresetMessages.getOrNull(4)?.trim().orEmpty())
        // Profile pictures are deliberately NOT synced to Firestore (cost) and
        // NOT shown in the admin panel (AdminAvatar renders name initials).

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
        m["cycleLines"] = cycleLines.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_CYCLE_LINES)
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
        val presetSavers = listOf<suspend (String, Int, Boolean, String?) -> Unit>(
            { m, iv, sh, n -> userPreferencesRepository.saveCyclePreset1(m, iv, sh, n) },
            { m, iv, sh, n -> userPreferencesRepository.saveCyclePreset2(m, iv, sh, n) },
            { m, iv, sh, n -> userPreferencesRepository.saveCyclePreset3(m, iv, sh, n) },
            { m, iv, sh, n -> userPreferencesRepository.saveCyclePreset4(m, iv, sh, n) },
            { m, iv, sh, n -> userPreferencesRepository.saveCyclePreset5(m, iv, sh, n) }
        )
        for (i in 1..5) {
            snap.getString("cyclePreset$i")?.trim()?.let { remote ->
                val local = cyclePresetMessages.getOrNull(i - 1)?.trim().orEmpty()
                val baseline = (lastSyncedValues["cyclePreset$i"] as? String) ?: local
                if (remote != baseline && remote != local) {
                    cyclePresetMessages[i - 1] = remote
                    val interval = cyclePresetIntervals.getOrElse(i - 1) { 10 }
                    val shuffle = cyclePresetShuffle.getOrElse(i - 1) { false }
                    presetSavers[i - 1](remote, interval, shuffle, null)
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
        // Light relaunch-storm guard for the sibling QUERY (separate from the 30-min
        // content-pull window): this query also drives the single-session claim, so it
        // must run on a genuine reopen — not be suppressed for 30 min — but still be
        // bounded so an OS-kill relaunch storm doesn't re-read every few seconds.
        val lastQuery = prefs().getLong(PREF_LAST_ACCOUNT_QUERY_MS, 0L)
        if (System.currentTimeMillis() - lastQuery < ACCOUNT_QUERY_THROTTLE_MS) return

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
            prefs().edit().putLong(PREF_LAST_ACCOUNT_QUERY_MS, System.currentTimeMillis()).apply()
            if (siblings == null || siblings.isEmpty) return@runCatching

            // (The single-session claim/deny is owned entirely by the account-lock
            // watcher now — see startAccountLockWatcher. This path only does the
            // cross-device CONTENT pull below.)

            // Content pull keeps its own 30-min throttle (the expensive part) — pull the
            // freshest sibling's presets/messages so a switched-to device has the latest.
            val lastContent = prefs().getLong(PREF_LAST_CROSS_DEVICE_SYNC_MS, 0L)
            if (System.currentTimeMillis() - lastContent < CROSS_DEVICE_SYNC_THROTTLE_MS) return@runCatching

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
            prefs().edit().putLong(PREF_LAST_CROSS_DEVICE_SYNC_MS, System.currentTimeMillis()).apply()
            if (bestSnap == null || bestMs <= myUpdatedAt) return@runCatching

            applyContentFromSnapshot(bestSnap)
            Log.d("VrcaViewModel", "Cross-device sync: pulled content from ${bestSnap.id}")
            rebuildCombinedPreviewOnly()
        }
    }

    /**
     * Single-session lock helpers (multi-device, same VRChat account).
     *
     * Lock doc: accounts/{vrchatUserId} = { activeDevice, activeSince }. A device is
     * "active" when the doc is absent OR names this device. The newest claimer wins
     * (take-over); the displaced device learns instantly via [startAccountLockWatcher]
     * and stops sending (OSC blocked at the same chokepoint as the logged-out gate).
     * Only ever written when a second device on the same account is detected, so a
     * single-device user never touches this collection.
     */
    private suspend fun claimAccount(vrchatUserId: String, deviceHash: String) {
        if (vrchatUserId.isBlank() || !isValidDeviceHash(deviceHash)) return
        runCatching {
            db.collection(COL_ACCOUNTS).document(vrchatUserId)
                .set(mapOf(
                    "activeDevice" to deviceHash,
                    "activeSince" to FieldValue.serverTimestamp()
                ))
                .await()
        }.onFailure { Log.w("VrcaViewModel", "claimAccount failed: ${it.message}") }
    }

    private var accountLockReg: ListenerRegistration? = null
    @Volatile private var watchedAccountId: String = ""

    /**
     * Watches accounts/{vrchatUserId} for the current VRChat login (re-attaches on
     * login / account switch / logout) and enforces the HARD-DENY single-session model:
     * claim the lock when it's free/ours, or raise [accountDenied] (block OSC, drop
     * Discord, show the deny screen) when another device holds it. Recovery is
     * automatic when the lock is freed (holder signs out / admin remote-logout).
     */
    private fun startAccountLockWatcher() {
        if (BuildConfig.IS_ADMIN_BUILD) return
        viewModelScope.launch {
            VrchatPipelineState.presenceFlow.collect { presence ->
                val vid = presence?.userId?.trim().orEmpty()
                if (vid == watchedAccountId) return@collect
                accountLockReg?.remove(); accountLockReg = null
                watchedAccountId = vid
                if (vid.isBlank()) {
                    // Signed out of VRChat → no account lock applies; clear deny.
                    if (accountDenied) { accountDenied = false; refreshOscBlockGate() }
                    return@collect
                }
                val myHash = readDeviceHashFromPrefs()
                if (!isValidDeviceHash(myHash)) return@collect
                // HARD-DENY model: the lock names exactly ONE device. On every lock
                // change, claim it when it's free/ours, or DENY when another device
                // holds it. Recovery is automatic — when the holder signs out or an
                // admin frees the lock (deletes it), the next snapshot is absent and
                // this device claims + un-denies itself with no reopen.
                accountLockReg = db.collection(COL_ACCOUNTS).document(vid)
                    .addSnapshotListener { snap, e ->
                        if (e != null) return@addSnapshotListener
                        val exists = snap?.exists() == true
                        val activeDevice = snap?.getString("activeDevice")?.trim().orEmpty()
                        val claimedByOther = exists && activeDevice.isNotBlank() && activeDevice != myHash
                        if (claimedByOther) {
                            if (!accountDenied) {
                                accountDenied = true
                                if (oscSending) stopSending()
                                disconnectDiscordLocally()
                                refreshOscBlockGate()
                            }
                        } else {
                            // Free or ours → claim it (if not already ours) and clear deny.
                            if (activeDevice != myHash) {
                                viewModelScope.launch { claimAccount(vid, myHash) }
                            }
                            if (accountDenied) { accountDenied = false; refreshOscBlockGate() }
                        }
                    }
            }
        }
    }

    /** Releases this account's single-session lock (deletes accounts/{vrchatUserId}) so
     *  another device can claim it. Called on a deliberate VRChat sign-out. */
    fun releaseAccountLock() {
        if (BuildConfig.IS_ADMIN_BUILD) return
        val vid = watchedAccountId
        if (vid.isBlank()) return
        viewModelScope.launch {
            runCatching { db.collection(COL_ACCOUNTS).document(vid).delete().await() }
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
                    .filter { it.isNotEmpty() }.take(MAX_CYCLE_LINES)
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
            refreshInstanceCountBeforeSync()
            performSelfSync()
            while (true) {
                delay(HOURLY_HEARTBEAT_MS)
                refreshInstanceCountBeforeSync()
                performSelfSync()
            }
        }
    }

    private suspend fun refreshInstanceCountBeforeSync() {
        try {
            val loc = VrchatPipelineState.presence?.location
            if (!loc.isNullOrBlank() && loc.startsWith("wrld_")) {
                com.vrca.vrchat.VrchatAuthManager.fetchInstanceCount(app, loc)?.let { ic ->
                    VrchatPipelineState.presence?.let { p ->
                        if (p.location == loc &&
                            (p.instancePlayerCount != ic.players || p.instanceCapacity != ic.capacity)
                        ) {
                            VrchatPipelineState.presence = p.copy(
                                instancePlayerCount = ic.players,
                                instanceCapacity = ic.capacity
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {}
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
    private var lastVrchatLogoutMs: Long = 0L
    private var lastDiscordLogoutMs: Long = 0L

    /** Admin remote-logout of VRChat: clears the session (incl. saved credentials, so
     *  auto-relogin can't revive it), which emits loggedOutSignal → OSC gate +
     *  releaseAccountLock (frees the single-session lock), then stops the pipeline. */
    private fun handleAdminVrchatLogout() {
        runCatching { com.vrca.vrchat.VrchatAuthManager.logout(app) }
        runCatching {
            val i = android.content.Intent(app, com.vrca.vrchat.VrchatPipelineService::class.java)
            i.action = com.vrca.vrchat.VrchatPipelineService.ACTION_STOP
            app.startService(i)
        }
    }

    private fun handleAdminKill() {
        if (killSignalHandled) return
        killSignalHandled = true
        try {
            // Fully stop the process AND keep it dead (set the swipe/manual-kill guards
            // + cancel the watchdog so START_STICKY and the watchdog don't bounce it
            // back), costing zero Firestore writes. A plain killProcess just bounced.
            com.vrca.app.AppShutdown.killNowNoWrite(app)
        } catch (_: Throwable) {
            try { android.os.Process.killProcess(android.os.Process.myPid()) } catch (_: Throwable) {}
        }
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

                            // Admin remote-logout signals (fresh + once per timestamp).
                            snap.getTimestamp("logoutVrchatAt")?.let { ts ->
                                val ms = ts.seconds * 1000L + (ts.nanoseconds / 1_000_000L)
                                if (System.currentTimeMillis() - ms in 0L..60_000L && ms != lastVrchatLogoutMs) {
                                    lastVrchatLogoutMs = ms
                                    handleAdminVrchatLogout()
                                }
                            }
                            snap.getTimestamp("logoutDiscordAt")?.let { ts ->
                                val ms = ts.seconds * 1000L + (ts.nanoseconds / 1_000_000L)
                                if (System.currentTimeMillis() - ms in 0L..60_000L && ms != lastDiscordLogoutMs) {
                                    lastDiscordLogoutMs = ms
                                    disconnectDiscordLocally()
                                }
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
            val presetSavers = listOf<suspend (String, Int, Boolean, String?) -> Unit>(
                { m, iv, sh, n -> userPreferencesRepository.saveCyclePreset1(m, iv, sh, n) },
                { m, iv, sh, n -> userPreferencesRepository.saveCyclePreset2(m, iv, sh, n) },
                { m, iv, sh, n -> userPreferencesRepository.saveCyclePreset3(m, iv, sh, n) },
                { m, iv, sh, n -> userPreferencesRepository.saveCyclePreset4(m, iv, sh, n) },
                { m, iv, sh, n -> userPreferencesRepository.saveCyclePreset5(m, iv, sh, n) }
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
                        val shuffle = cyclePresetShuffle.getOrElse(i - 1) { false }
                        presetSavers[i - 1](trimmed, interval, shuffle, null)
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

    // ---- Manual Send takeover state ----
    // Instant (false, default): type + press Send, message shows above your head.
    // Live (true): the chatbox updates every 0.5s as you type (like Music).
    var manualLiveMode by mutableStateOf(false)
        private set
    // Live-only: newest 4 lines stay visible, older lines scroll off the top.
    var manualScroll by mutableStateOf(false)
        private set

    // While now < manualHoldUntilMs the automated senders skip their tick so the
    // manual message holds the VRChat chatbox. Any manual send / live keystroke
    // pushes it forward; a manual send is NEVER gated by this (only the automated
    // combined output is), so the user can always send again mid-window.
    @Volatile private var manualHoldUntilMs: Long = 0L
    // What the manual takeover is currently showing (drives the Home preview while
    // the automated output is paused).
    private var lastManualHoldText: String = ""
    private var manualLiveJob: Job? = null
    private var manualRevertJob: Job? = null
    private var lastManualLiveSent: String? = null
    // Which OSC target the active live loop is driving (remote vs local), so a
    // mid-hold exit from Live mode can hand the revert to the right sender.
    private var manualLiveLocal: Boolean = false

    private fun manualHoldActive(): Boolean = System.currentTimeMillis() < manualHoldUntilMs

    /** VRChat's chatbox budget for a manual message (142 with the invisible
     *  border on so the 2 control chars survive, else the full 144). */
    fun manualCharBudget(): Int =
        if (minimalChatboxBg) VRC_MAX_CHARS - MINIMAL_BG_RESERVED_CHARS else VRC_MAX_CHARS

    fun setManualLiveModeFlag(enabled: Boolean) {
        if (manualLiveMode == enabled) return
        manualLiveMode = enabled
        viewModelScope.launch { userPreferencesRepository.saveManualLiveMode(enabled) }
        // Leaving Live mode stops the live loop + typing indicator; the current
        // hold still expires normally and reverts the chatbox.
        if (!enabled) {
            val local = manualLiveLocal
            manualLiveJob?.cancel(); manualLiveJob = null
            lastManualLiveSent = null
            remoteVrcaOsc.typing = false
            localVrcaOsc.typing = false
            // The live loop OWNED hold expiry while running; now that it's gone,
            // hand the current hold back to the revert job so the chatbox still
            // reverts to automation once it elapses (otherwise it'd stick on the
            // manual text). No active hold → revert to the normal chatbox now.
            if (manualHoldActive()) {
                scheduleManualRevert(local)
            } else if (!isBanned) {
                lastManualHoldText = ""
                rebuildAndMaybeSendCombined(forceSend = true, local = local, forceClearIfAllOff = true)
            }
        }
    }

    fun setManualScrollFlag(enabled: Boolean) {
        if (manualScroll == enabled) return
        manualScroll = enabled
        viewModelScope.launch { userPreferencesRepository.saveManualScroll(enabled) }
    }

    /**
     * Live scroll formatter: show the NEWEST [MANUAL_SCROLL_LINES] VRChat chatbox
     * lines, older lines scrolling off the top. VRChat wraps a long message into
     * multiple visual lines, so we must wrap it OURSELVES and keep the newest few
     * — splitting only on '\n' meant one long unbroken line was treated as a
     * single line and we trimmed CHARACTERS off the front instead of dropping a
     * whole line (the reported bug). We wrap by estimated proportional width (a
     * touch narrower than VRChat so it never re-wraps our lines into a 5th),
     * insert explicit newlines to lock those breaks, keep the last N, and cap at
     * [budget] so nothing is lost at a wrap point ("drop the 4th line" backstop).
     */
    private fun formatManualScroll(text: String, budget: Int): String {
        if (text.isEmpty()) return ""
        val lines = wrapToVisualLines(text, MANUAL_SCROLL_WIDTH)
        val kept = ArrayDeque<String>()
        var total = 0
        // Walk from the newest wrapped line backwards, keeping what fits.
        for (i in lines.indices.reversed()) {
            if (kept.size >= MANUAL_SCROLL_LINES) break
            val line = lines[i]
            val add = line.length + if (kept.isEmpty()) 0 else 1 // +1 for the join '\n'
            if (total + add > budget) {
                if (kept.isEmpty()) return line.takeLast(budget) // newest line alone over budget
                break
            }
            kept.addFirst(line)
            total += add
        }
        return kept.joinToString("\n")
    }

    /** Estimated proportional glyph width (VRChat's chatbox is centered
     *  proportional text, so caps/wide glyphs wrap sooner than a char count). */
    private fun manualCharWidth(c: Char): Float = when {
        c == ' ' -> 0.5f
        c in "iIlj|.,:;'!`" -> 0.5f
        c in "mwMW" -> 1.5f
        c.isUpperCase() || c.isDigit() -> 1.15f
        else -> 1.0f
    }

    private fun manualStrWidth(s: String): Float {
        var t = 0f; for (c in s) t += manualCharWidth(c); return t
    }

    /** Greedy word-wrap into visual lines of ≤ [maxW] estimated width, honoring
     *  explicit newlines and hard-splitting a single word longer than a line. */
    private fun wrapToVisualLines(text: String, maxW: Float): List<String> {
        val out = mutableListOf<String>()
        for (para in text.split("\n")) {
            val cur = StringBuilder()
            var curW = 0f
            for (word in para.split(" ")) {
                var wd = word
                // Hard-split a word that alone exceeds a full line.
                while (manualStrWidth(wd) > maxW) {
                    if (cur.isNotEmpty()) { out.add(cur.toString()); cur.setLength(0); curW = 0f }
                    val sb = StringBuilder(); var acc = 0f; var i = 0
                    while (i < wd.length) {
                        val cw = manualCharWidth(wd[i])
                        if (acc + cw > maxW && sb.isNotEmpty()) break
                        sb.append(wd[i]); acc += cw; i++
                    }
                    out.add(sb.toString())
                    wd = wd.substring(i.coerceAtLeast(1))
                }
                val sepW = if (cur.isEmpty()) 0f else manualCharWidth(' ')
                val wW = manualStrWidth(wd)
                if (cur.isNotEmpty() && curW + sepW + wW > maxW) {
                    out.add(cur.toString()); cur.setLength(0); curW = 0f
                    cur.append(wd); curW = wW
                } else {
                    if (cur.isNotEmpty()) { cur.append(' '); curW += sepW }
                    cur.append(wd); curW += wW
                }
            }
            out.add(cur.toString())
        }
        return out
    }

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
    // Seeded from the REAL auth state, not false: with the forced login screen
    // removed, a signed-out cold start lands straight in the app — the OSC
    // block must hold from the first frame, not wait for a loggedOutSignal
    // that never fires on cold start.
    var vrchatLoggedOut by mutableStateOf(
        !com.vrca.vrchat.VrchatAuthManager.isLoggedIn(app.applicationContext)
    )
        private set

    /**
     * True when this VRChat account is already claimed by a DIFFERENT device — a HARD
     * DENY (no take-over): OSC is blocked, Discord RPC is disconnected, and the UI
     * shows a reassuring "account already registered, contact support" screen with the
     * support-server link. The VRChat session + the account-lock listener are KEPT
     * attached so recovery is seamless: when the old device signs out or an admin
     * remote-logs-it-out (freeing the lock), this device auto-recovers and claims it.
     * Single-device users never see it (their own claim names them).
     */
    var accountDenied by mutableStateOf(false)
        private set

    /** True when the VRChat session is CONFIRMED dead-and-unrecoverable (password
     *  change / 30-day trusted-device expiry / cleared creds), not merely a
     *  transient drop — determined by the pipeline over a 5-min window with the
     *  device online + VRChat not in a major outage. Blocks OSC and drives the
     *  in-app "session expired" banner; clears the instant the user signs in. */
    var vrchatAuthDead by mutableStateOf(false)
        private set

    /** OSC is blocked when ANY gate reason is active. */
    private fun refreshOscBlockGate() {
        val blocked = forceUpdatePending || vrchatLoggedOut || accountDenied || vrchatAuthDead
        remoteVrcaOsc.blocked = blocked
        localVrcaOsc.blocked = blocked
    }

    /** Disconnects Discord RPC on this device (used by deny + admin remote-logout):
     *  clears the session prefs so it can't auto-restart, wipes the WebView cookies,
     *  and stops the service. */
    private fun disconnectDiscordLocally() {
        viewModelScope.launch {
            runCatching {
                userPreferencesRepository.saveDiscordRpcEnabled(false)
                userPreferencesRepository.saveDiscordSessionSeeded(false)
            }
            runCatching { android.webkit.CookieManager.getInstance().removeAllCookies(null) }
            runCatching {
                val i = android.content.Intent(app, com.vrca.discord.DiscordRpcService::class.java)
                i.action = com.vrca.discord.DiscordRpcService.ACTION_STOP
                app.startService(i)
            }
        }
    }

    private fun startVrchatAuthGateWatcher() {
        // Apply the seeded state immediately (cold start while signed out).
        refreshOscBlockGate()
        viewModelScope.launch {
            com.vrca.vrchat.VrchatAuthManager.loggedOutSignal.collect {
                // Order matters: stopSending()'s chatbox-clearing send must go
                // out BEFORE the gate blocks the chokepoint.
                if (oscSending) stopSending()
                vrchatLoggedOut = true
                refreshOscBlockGate()
                // A deliberate VRChat sign-out releases this account's single-session
                // lock so another device can claim it (the "properly sign out on the
                // old device" escape from the hard-deny).
                releaseAccountLock()
            }
        }
        viewModelScope.launch {
            com.vrca.vrchat.VrchatAuthManager.loggedInSignal.collect {
                vrchatLoggedOut = false
                // A fresh sign-in resolves a confirmed-dead session too.
                vrchatAuthDead = false
                com.vrca.vrchat.VrchatPipelineState.authDead = false
                refreshOscBlockGate()
            }
        }
        // Confirmed-dead session (present-but-invalid cookie the pipeline can't
        // silently recover): gate OSC and raise the in-app banner. When sending,
        // stop first so the chatbox-clearing send escapes before the block.
        viewModelScope.launch {
            com.vrca.vrchat.VrchatPipelineState.authDeadFlow.collect { dead ->
                if (dead == vrchatAuthDead) return@collect
                if (dead && oscSending) stopSending()
                vrchatAuthDead = dead
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

    /**
     * Per-source Now Playing enables (Media tab). When the currently-detected
     * media app's source is OFF, [buildNowPlayingLines] renders nothing — the
     * chatbox/preview drop the music block while detection itself keeps
     * running (so re-enabling the source picks the track straight back up).
     * An unlisted media app is always allowed. DataStore-persisted, local-only
     * (not synced to Firestore, not part of the feature-session restore).
     */
    var mediaSourceSpotify by mutableStateOf(true)
        private set
    var mediaSourceYoutube by mutableStateOf(true)
        private set
    var mediaSourceYtMusic by mutableStateOf(true)
        private set

    fun setMediaSourceFlag(pkg: String, enabled: Boolean) {
        when (pkg) {
            PKG_SPOTIFY -> {
                mediaSourceSpotify = enabled
                viewModelScope.launch { userPreferencesRepository.saveMediaSourceSpotify(enabled) }
            }
            PKG_YOUTUBE -> {
                mediaSourceYoutube = enabled
                viewModelScope.launch { userPreferencesRepository.saveMediaSourceYoutube(enabled) }
            }
            PKG_YTMUSIC -> {
                mediaSourceYtMusic = enabled
                viewModelScope.launch { userPreferencesRepository.saveMediaSourceYtMusic(enabled) }
            }
            else -> return
        }
        // React in-game immediately, same as the minimal-background toggle.
        if (oscSending) rebuildAndMaybeSendCombined(forceSend = true)
        else rebuildCombinedPreviewOnly()
    }

    /** Is the source of the currently-active media app enabled? */
    fun isActiveMediaSourceEnabled(): Boolean = when (activePackage) {
        PKG_SPOTIFY -> mediaSourceSpotify
        PKG_YOUTUBE -> mediaSourceYoutube
        PKG_YTMUSIC -> mediaSourceYtMusic
        else -> true
    }

    /** Now Playing progress bar+time line. OFF = title only (Media tab toggle).
     *  DataStore-persisted, local-only. */
    var musicShowProgress by mutableStateOf(true)
        private set

    fun setMusicShowProgressFlag(enabled: Boolean) {
        musicShowProgress = enabled
        viewModelScope.launch { userPreferencesRepository.saveMusicShowProgress(enabled) }
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

        // Live mode: the live loop drives the chatbox every 0.5s from the field's
        // current text; just make sure it's running while there's content.
        if (manualLiveMode) {
            // Scroll: render the FIELD exactly as VRChat will — collapse to flowing
            // text, wrap at VRChat's line width, then keep the newest 4 lines within
            // the char budget (older lines scroll off the top). The field then shows
            // the SAME lines as the chatbox instead of fewer (the in-app box is
            // wider, so a message that wrapped to 3 lines in VRChat used to show as 2
            // here — which made the scroll-off trim jump and typing feel off). The
            // wrapped display IS what we send, so field == chatbox. Cursor forced to
            // the end (the user types at the end, so the view never jumps). Newlines
            // collapse to spaces — a scrolling ticker is one flowing line in VRChat,
            // so manual line breaks aren't kept while Scroll is on.
            if (manualScroll && message.text.isNotEmpty()) {
                val display = formatManualScroll(message.text.replace("\n", " "), manualCharBudget())
                if (display != message.text) {
                    messageText.value = TextFieldValue(display, TextRange(display.length))
                    stashedMessage = display
                }
            }
            if (messageText.value.text.isNotEmpty()) startManualLiveLoop(local)
            return
        }
        // Instant mode: a keystroke doesn't touch the chatbox (Send does); keep
        // the legacy typing-indicator behaviour if the user enabled it.
        if (messengerUiState.value.isTypingIndicator) {
            osc.typing = message.text.isNotEmpty()
        }
    }

    /**
     * Instant Send: push the manual message once, then HOLD the chatbox for 10s
     * (automated senders skip their tick) so it stays readable. Sending again —
     * even inside the 10s — always works and re-arms the hold. Over the char
     * budget is a no-op (the UI also disables the button).
     */
    fun sendMessage(local: Boolean = false) {
        if (isBanned) return
        val text = messageText.value.text
        if (text.isBlank()) return
        if (text.length > manualCharBudget()) return // UI blocks this too

        val osc = if (!local) remoteVrcaOsc else localVrcaOsc
        osc.sendMessage(text, messengerUiState.value.isSendImmediately, triggerSFX = false)
        osc.typing = false

        beginManualHold(text, local)

        conversationUiState.addMessage(Message(text, false, Instant.now()))
        messageText.value = TextFieldValue("", TextRange.Zero)
        stashedMessage = ""
    }

    /** Clear the manual message from the chatbox + the field, drop the hold, and
     *  restore the normal automated chatbox (or a clear if nothing is enabled). */
    fun clearManual(local: Boolean = false) {
        manualLiveJob?.cancel(); manualLiveJob = null
        manualRevertJob?.cancel(); manualRevertJob = null
        lastManualLiveSent = null
        manualHoldUntilMs = 0L
        lastManualHoldText = ""
        messageText.value = TextFieldValue("", TextRange.Zero)
        stashedMessage = ""
        remoteVrcaOsc.typing = false
        localVrcaOsc.typing = false
        if (!isBanned) rebuildAndMaybeSendCombined(forceSend = true, local = local, forceClearIfAllOff = true)
    }

    /** Arm/extend the 10s takeover for the message [shown] and schedule the
     *  revert back to the normal chatbox once it expires. */
    private fun beginManualHold(shown: String, local: Boolean) {
        manualHoldUntilMs = System.currentTimeMillis() + MANUAL_HOLD_MS
        lastManualHoldText = shown
        combinedPreviewText = shown
        scheduleManualRevert(local)
    }

    private fun scheduleManualRevert(local: Boolean) {
        manualRevertJob?.cancel()
        manualRevertJob = viewModelScope.launch {
            while (true) {
                val wait = manualHoldUntilMs - System.currentTimeMillis()
                if (wait <= 0) break
                delay(wait)
            }
            // Hold expired with no fresh manual activity: drop back to normal.
            lastManualHoldText = ""
            lastManualLiveSent = null
            remoteVrcaOsc.typing = false
            localVrcaOsc.typing = false
            if (!isBanned) rebuildAndMaybeSendCombined(forceSend = true, local = local, forceClearIfAllOff = true)
        }
    }

    /**
     * Live-typing loop: every 0.5s, if the field text changed, push it (scroll-
     * formatted when Scroll is on) to the chatbox with a typing indicator and
     * re-arm the 10s hold. When the field goes blank it restores the normal
     * chatbox and stops; onMessageTextChange restarts it on the next keystroke.
     */
    private fun startManualLiveLoop(local: Boolean = false) {
        if (manualLiveJob?.isActive == true) return
        val osc = if (!local) remoteVrcaOsc else localVrcaOsc
        manualLiveLocal = local
        // The live loop is the SOLE owner of hold expiry while it runs — cancel
        // any Instant-mode revert job so the two can't race. The old design ran
        // scheduleManualRevert() alongside the loop: at expiry the revert job
        // reverted to automation AND nulled lastManualLiveSent, so the loop's next
        // 0.5s tick saw the (unchanged) field as a NEW edit (text != null) and
        // instantly re-armed the hold + re-pushed the manual text — flickering
        // back to manual and, depending on tick ordering, sometimes never settling
        // on automation at all. Now the loop detects expiry itself and reverts once.
        manualRevertJob?.cancel(); manualRevertJob = null
        manualLiveJob = viewModelScope.launch {
            var typingOn = false
            while (manualLiveMode && !isBanned) {
                val text = messageText.value.text
                if (text.isEmpty()) {
                    // Field cleared: revert immediately and stop the loop.
                    if (typingOn) { osc.typing = false; typingOn = false }
                    manualHoldUntilMs = 0L
                    lastManualHoldText = ""
                    lastManualLiveSent = null
                    rebuildAndMaybeSendCombined(forceSend = true, local = local, forceClearIfAllOff = true)
                    break
                }
                val changed = text != lastManualLiveSent
                if (changed) {
                    // A genuine edit (re)arms the 10s hold + shows the typing dots.
                    lastManualLiveSent = text
                    manualHoldUntilMs = System.currentTimeMillis() + MANUAL_HOLD_MS
                    if (!typingOn) { osc.typing = true; typingOn = true }
                } else if (!manualHoldActive()) {
                    // Unchanged AND the 10s window elapsed: revert to the normal
                    // chatbox HERE (the loop owns expiry in live mode) and stop.
                    // A keystroke starts a fresh loop via onMessageTextChange.
                    if (typingOn) { osc.typing = false; typingOn = false }
                    manualHoldUntilMs = 0L
                    lastManualHoldText = ""
                    lastManualLiveSent = null
                    rebuildAndMaybeSendCombined(forceSend = true, local = local, forceClearIfAllOff = true)
                    break
                } else if (typingOn) {
                    // Paused but still inside the 10s window: drop the typing dots,
                    // but KEEP pushing the text below so it stays up until revert.
                    osc.typing = false; typingOn = false
                }
                // Push the CURRENT field text EVERY 0.5s tick for the whole hold
                // window — even when the user pauses typing — so a keystroke that
                // landed between ticks (fast typing) is always reflected within
                // 0.5s and the final bit is never stranded. Identical re-sends are
                // cheap and keep the chatbox message fresh until the revert.
                val budget = manualCharBudget()
                val shown = if (manualScroll) formatManualScroll(text, budget) else text.take(budget)
                osc.sendMessage(shown, sendImmediately = true, triggerSFX = false)
                lastManualHoldText = shown
                combinedPreviewText = shown
                delay(MANUAL_LIVE_TICK_MS)
            }
        }
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
    // Per-line mute, kept in lockstep with [cycleLines] (index-aligned). Local-only
    // (NOT synced to Firestore) — a muted line stays in the editor + the synced
    // cycleLinesText but is skipped by the sender. Defaults to enabled.
    val cycleLineEnabled = mutableStateListOf<Boolean>()
    // Legacy mute-repaint counter. Reading a version Int INSIDE the Cycle card's
    // content lambda still didn't repaint on-device (the content lambda lives in a
    // separate AnimatedVisibility sub-composition that a SnapshotStateList index-set
    // didn't invalidate). The repaint is now driven where it actually works: the
    // Cycle CompactSectionCard's `summary` reads `cycleLineEnabled.count { !it }`
    // EAGERLY in the PARENT composable's scope, so a mute toggle invalidates the
    // parent -> the whole card (header + content) recomposes with fresh per-line
    // state. This counter is kept (bumped, unread) as harmless belt-and-suspenders.
    var cycleMuteRev by mutableStateOf(0)
        private set
    // Random/shuffle rotation instead of sequential. Local-only.
    var cycleShuffle by mutableStateOf(false)
        private set
    // Recently-played positions (into the active-lines list) for the shuffle
    // no-repeat window: avoid the last 2 when there are >5 active lines, else
    // the last 1 (never an immediate repeat). Reset when the active set changes.
    private val recentCyclePicks = ArrayDeque<Int>()
    private var lastCyclePreviewAdvanceMs: Long = 0L

    // Auto-save preset model: the selected slot is the auto-save target — editing
    // the Pinned message / cycle lines writes straight into it, so switching slots
    // is the only "save" (no manual save button). Persisted (local DataStore).
    // 0 = NOTHING selected: editing does NOT touch any preset (so an existing user's
    // saved slots are never clobbered until they deliberately tap/switch to one).
    var selectedAfkPreset by mutableStateOf(0)
        private set
    var selectedCyclePreset by mutableStateOf(0)
        private set

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
    // Read by the Media tab's now-playing card (state chip).
    var nowPlayingIsAd by mutableStateOf(false)
        private set

    // True when the active YouTube session is a live stream (non-seekable, no
    // finite duration). The builder shows a LIVE marker instead of a progress bar.
    // Read by the Media tab's now-playing card (state chip).
    var nowPlayingIsLive by mutableStateOf(false)
        private set

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

    // 12-hour (default, with AM/PM) vs 24-hour clock for the Time line.
    // Settings → Chatbox display. (`Flag` suffix avoids the JVM setter clash.)
    var time24h by mutableStateOf(false)
        private set

    fun setTime24hFlag(enabled: Boolean) {
        time24h = enabled
        viewModelScope.launch { userPreferencesRepository.saveTimeFormat24h(enabled) }
        if (oscSending) rebuildAndMaybeSendCombined(forceSend = true)
        else rebuildCombinedPreviewOnly()
    }

    private fun currentTimeString(): String {
        val zone: java.time.ZoneId = resolveTimeZone(timeMode)
        val now = java.time.LocalDateTime.now(zone)
        // 12-hour with AM/PM by default; 24-hour when the Settings toggle is on.
        // Locale.US keeps the marker a stable uppercase "AM"/"PM".
        return if (time24h) DateTimeFormatter.ofPattern("HH:mm").format(now)
        else DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US).format(now)
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
    // Per-preset shuffle, mirrored from DataStore so each slot restores its own.
    private val cyclePresetShuffle = mutableStateListOf(false, false, false, false, false)
    // Per-preset per-line mute CSV (aligned to the preset's non-empty saved lines),
    // mirrored from DataStore so each slot restores which lines were hidden.
    private val cyclePresetEnabled = mutableStateListOf("", "", "", "", "")

    init {
        // Restore the last-synced baseline from the previous session so the
        // delta writer knows what Firestore already has. Enables cold-open
        // delta writes (only changed content + liveness) instead of full
        // 40-field snapshots on every app restart.
        loadLastSyncedValues()

        // Keep the OSC send target in sync with the saved IP (active slot) from
        // DataStore. remoteVrcaOsc was constructed once with whatever was stored
        // at VM-creation time; nothing else re-reads it, so an IP saved AFTER the
        // VM was created — most importantly the address entered during the
        // onboarding tutorial (the VM is created during onboarding) — never became
        // the live target until the user manually tapped Apply in Home. This
        // collector makes any saved/equipped/synced IP the runtime target
        // automatically. Manual Apply also writes the same value to DataStore, so
        // this re-emits the identical value (no conflict).
        viewModelScope.launch {
            userPreferencesRepository.ipAddress.collect { ip ->
                if (ip.isNotBlank()) remoteVrcaOsc.ipAddress = ip
            }
        }

        // Public build: attach moderation listeners (also drives watcher detection
        // and remote-config snapshots). Admin build skips self-sync entirely.
        attachModerationListenersLoopOnce()

        // VRChat sign-out hard-blocks OSC until re-login (Settings Accounts).
        startVrchatAuthGateWatcher()

        // Single-session lock: when the same VRChat account is open on another
        // device, this one stands down (OSC blocked) until it's re-claimed.
        startAccountLockWatcher()

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
                cycleShuffle = userPreferencesRepository.cycleShuffle.first()
                applyCycleLineEnabledCsv(userPreferencesRepository.cycleLineEnabled.first())
                selectedAfkPreset = userPreferencesRepository.selectedAfkPreset.first().coerceIn(0, 3)
                selectedCyclePreset = userPreferencesRepository.selectedCyclePreset.first().coerceIn(0, 5)
                afkPresetTexts[0] = userPreferencesRepository.afkPreset1.first()
                afkPresetTexts[1] = userPreferencesRepository.afkPreset2.first()
                afkPresetTexts[2] = userPreferencesRepository.afkPreset3.first()
                cyclePresetMessages[0] = userPreferencesRepository.cyclePreset1Messages.first()
                cyclePresetMessages[1] = userPreferencesRepository.cyclePreset2Messages.first()
                cyclePresetMessages[2] = userPreferencesRepository.cyclePreset3Messages.first()
                cyclePresetMessages[3] = userPreferencesRepository.cyclePreset4Messages.first()
                cyclePresetMessages[4] = userPreferencesRepository.cyclePreset5Messages.first()
                cyclePresetIntervals[0] = userPreferencesRepository.cyclePreset1Interval.first().coerceAtLeast(2)
                cyclePresetIntervals[1] = userPreferencesRepository.cyclePreset2Interval.first().coerceAtLeast(2)
                cyclePresetIntervals[2] = userPreferencesRepository.cyclePreset3Interval.first().coerceAtLeast(2)
                cyclePresetIntervals[3] = userPreferencesRepository.cyclePreset4Interval.first().coerceAtLeast(2)
                cyclePresetIntervals[4] = userPreferencesRepository.cyclePreset5Interval.first().coerceAtLeast(2)
                cyclePresetShuffle[0] = userPreferencesRepository.cyclePreset1Shuffle.first()
                cyclePresetShuffle[1] = userPreferencesRepository.cyclePreset2Shuffle.first()
                cyclePresetShuffle[2] = userPreferencesRepository.cyclePreset3Shuffle.first()
                cyclePresetShuffle[3] = userPreferencesRepository.cyclePreset4Shuffle.first()
                cyclePresetShuffle[4] = userPreferencesRepository.cyclePreset5Shuffle.first()
                cyclePresetEnabled[0] = userPreferencesRepository.cyclePreset1Enabled.first()
                cyclePresetEnabled[1] = userPreferencesRepository.cyclePreset2Enabled.first()
                cyclePresetEnabled[2] = userPreferencesRepository.cyclePreset3Enabled.first()
                cyclePresetEnabled[3] = userPreferencesRepository.cyclePreset4Enabled.first()
                cyclePresetEnabled[4] = userPreferencesRepository.cyclePreset5Enabled.first()
                spotifyPreset = userPreferencesRepository.spotifyPreset.first().coerceIn(1, 5)
                timeMode = userPreferencesRepository.timeMode.first()
            }
            // One-shot: park a pre-auto-save user's live content into a preset
            // slot so tapping a chip can't load over (destroy) it. Must run
            // AFTER the loads above (needs live content + slot contents) and
            // BEFORE any UI interaction can select a preset.
            runCatching { migrateLiveContentIntoPresetSlot() }
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

        // Manual Send mode + scroll style (local-only preferences).
        viewModelScope.launch {
            userPreferencesRepository.manualLiveMode.collect { manualLiveMode = it }
        }
        viewModelScope.launch {
            userPreferencesRepository.manualScroll.collect { manualScroll = it }
        }

        // Per-source Now Playing enables: seed + follow DataStore (local-only).
        viewModelScope.launch {
            userPreferencesRepository.mediaSourceSpotify.collect {
                mediaSourceSpotify = it; rebuildCombinedPreviewOnly()
            }
        }
        viewModelScope.launch {
            userPreferencesRepository.mediaSourceYoutube.collect {
                mediaSourceYoutube = it; rebuildCombinedPreviewOnly()
            }
        }
        viewModelScope.launch {
            userPreferencesRepository.mediaSourceYtMusic.collect {
                mediaSourceYtMusic = it; rebuildCombinedPreviewOnly()
            }
        }

        // 12/24-hour clock + Now Playing progress-bar visibility (local-only).
        viewModelScope.launch {
            userPreferencesRepository.timeFormat24h.collect {
                time24h = it; rebuildCombinedPreviewOnly()
            }
        }
        viewModelScope.launch {
            userPreferencesRepository.musicShowProgress.collect {
                musicShowProgress = it; rebuildCombinedPreviewOnly()
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
        viewModelScope.launch { userPreferencesRepository.cyclePreset1Interval.collect { cyclePresetIntervals[0] = it.coerceAtLeast(2) } }
        viewModelScope.launch { userPreferencesRepository.cyclePreset1Shuffle.collect { cyclePresetShuffle[0] = it } }
        viewModelScope.launch { userPreferencesRepository.cyclePreset2Messages.collect { cyclePresetMessages[1] = it; startSelfSyncLoopIfNeeded() } }
        viewModelScope.launch { userPreferencesRepository.cyclePreset2Interval.collect { cyclePresetIntervals[1] = it.coerceAtLeast(2) } }
        viewModelScope.launch { userPreferencesRepository.cyclePreset2Shuffle.collect { cyclePresetShuffle[1] = it } }
        viewModelScope.launch { userPreferencesRepository.cyclePreset3Messages.collect { cyclePresetMessages[2] = it; startSelfSyncLoopIfNeeded() } }
        viewModelScope.launch { userPreferencesRepository.cyclePreset3Interval.collect { cyclePresetIntervals[2] = it.coerceAtLeast(2) } }
        viewModelScope.launch { userPreferencesRepository.cyclePreset3Shuffle.collect { cyclePresetShuffle[2] = it } }
        viewModelScope.launch { userPreferencesRepository.cyclePreset4Messages.collect { cyclePresetMessages[3] = it; startSelfSyncLoopIfNeeded() } }
        viewModelScope.launch { userPreferencesRepository.cyclePreset4Interval.collect { cyclePresetIntervals[3] = it.coerceAtLeast(2) } }
        viewModelScope.launch { userPreferencesRepository.cyclePreset4Shuffle.collect { cyclePresetShuffle[3] = it } }
        viewModelScope.launch { userPreferencesRepository.cyclePreset5Messages.collect { cyclePresetMessages[4] = it; startSelfSyncLoopIfNeeded() } }
        viewModelScope.launch { userPreferencesRepository.cyclePreset5Interval.collect { cyclePresetIntervals[4] = it.coerceAtLeast(2) } }
        viewModelScope.launch { userPreferencesRepository.cyclePreset5Shuffle.collect { cyclePresetShuffle[4] = it } }

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
        val msgs = activeCycleLines()
        if (msgs.isEmpty()) return

        val now = System.currentTimeMillis()
        if (lastCyclePreviewAdvanceMs == 0L) {
            lastCyclePreviewAdvanceMs = now
            return
        }

        val intervalMs = cycleIntervalSeconds.toLong() * 1000L
        if (now - lastCyclePreviewAdvanceMs >= intervalMs) {
            cycleIndex = nextCyclePos(msgs.size, cycleIndex)
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
        // Speed is part of a preset — write it into the selected slot (no-op at slot 0).
        autoSaveSelectedCyclePreset()
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
        autoSaveSelectedAfkPreset(text)
        rebuildCombinedPreviewOnly()
        startSelfSyncLoopIfNeeded()
    }

    /** Auto-save the live Pinned text into the currently-selected preset slot —
     *  the selected slot mirrors the editor, so switching slots is the only save.
     *  No-op when nothing is selected (slot 0) so existing presets aren't clobbered. */
    private fun autoSaveSelectedAfkPreset(text: String) {
        if (selectedAfkPreset !in 1..3) return
        val idx = selectedAfkPreset - 1
        afkPresetTexts[idx] = text
        viewModelScope.launch {
            when (idx + 1) {
                1 -> userPreferencesRepository.saveAfkPreset1(text)
                2 -> userPreferencesRepository.saveAfkPreset2(text)
                else -> userPreferencesRepository.saveAfkPreset3(text)
            }
        }
    }

    /** Switch the active Pinned preset: the old slot was already auto-saved (the
     *  editor mirrors it), so this just records the new selection and loads it. */
    fun selectAfkPreset(slot: Int) {
        if (isBanned) return
        val s = slot.coerceIn(1, 3)
        selectedAfkPreset = s
        viewModelScope.launch { userPreferencesRepository.saveSelectedAfkPreset(s) }
        val txt = afkPresetTexts.getOrElse(s - 1) { "" }
        // Set the live editor to the slot's content WITHOUT re-saving (it already
        // holds this content); updateAfkText would auto-save back into the slot.
        afkMessage = txt
        viewModelScope.launch { userPreferencesRepository.saveAfkMessage(txt) }
        rebuildCombinedPreviewOnly()
        startSelfSyncLoopIfNeeded()
    }

    // ---- Pinned sub-lines (up to 3 chatbox rows, encoded into afkMessage) ----
    // The hide/order/hidden-text state rides the existing afkMessage field (and
    // therefore the preset slots + all sync) — no new Firestore fields. The SENT
    // output is only the visible, non-blank rows (SubLineCodec.renderVisible).
    fun pinnedSubLines(): List<ChatboxSubLine> = SubLineCodec.decode(afkMessage)

    private fun setPinnedSubLines(subs: List<ChatboxSubLine>) {
        // updateAfkText persists + auto-saves the selected preset + rebuilds preview.
        updateAfkText(SubLineCodec.encode(subs.take(SubLineCodec.MAX_SUB_LINES)))
    }

    fun setPinnedSubLineText(index: Int, text: String) {
        val subs = pinnedSubLines().toMutableList()
        if (index !in subs.indices) return
        subs[index] = subs[index].copy(text = text)
        setPinnedSubLines(subs)
    }

    fun setPinnedSubLineHidden(index: Int, hidden: Boolean) {
        val subs = pinnedSubLines().toMutableList()
        if (index !in subs.indices) return
        subs[index] = subs[index].copy(hidden = hidden)
        setPinnedSubLines(subs)
    }

    fun addPinnedSubLine() {
        val subs = pinnedSubLines().toMutableList()
        if (subs.size >= SubLineCodec.MAX_SUB_LINES) return
        subs.add(ChatboxSubLine("", false))
        setPinnedSubLines(subs)
    }

    fun removePinnedSubLine(index: Int) {
        val subs = pinnedSubLines().toMutableList()
        if (index !in subs.indices) return
        subs.removeAt(index)
        if (subs.isEmpty()) subs.add(ChatboxSubLine("", false))
        setPinnedSubLines(subs)
    }

    fun movePinnedSubLine(from: Int, to: Int) {
        val subs = pinnedSubLines().toMutableList()
        if (from !in subs.indices || to !in subs.indices || from == to) return
        subs.add(to, subs.removeAt(from))
        setPinnedSubLines(subs)
    }

    // =========================
    // Cycle lines management
    // =========================
    /** Keep [cycleLineEnabled] index-aligned with [cycleLines] (default new = enabled). */
    private fun syncCycleEnabledSize() {
        while (cycleLineEnabled.size < cycleLines.size) cycleLineEnabled.add(true)
        while (cycleLineEnabled.size > cycleLines.size && cycleLineEnabled.isNotEmpty())
            cycleLineEnabled.removeAt(cycleLineEnabled.size - 1)
    }

    private fun setCycleLinesFromTextPreserve(text: String) {
        val lines = text.split("\n").take(MAX_CYCLE_LINES)
        // Echo of our own save (saveCycleMessages re-fires this collector) or an
        // unchanged remote push: keep the local mute/shuffle state untouched. Only
        // a genuine line-set change resets mute (we can't remap old mute to new lines).
        if (lines.size == cycleLines.size && lines.indices.all { lines[it] == cycleLines[it] }) {
            syncCycleEnabledSize()
            return
        }
        cycleLines.clear()
        cycleLines.addAll(lines)
        cycleLineEnabled.clear()
        syncCycleEnabledSize()
        // Heal any historically-corrupted line whose MAIN sub-line is hidden (a
        // pinned<->cycle move used to carry the hidden flag onto sub 0, so the line
        // decoded as blank → uncounted + non-sending while still showing its text).
        // Re-persist the cleaned form so the fix sticks across restarts.
        if (repairCycleMainVisibility()) persistCycleLinesPreserve()
        recentCyclePicks.clear()
        rebuildCombinedPreviewOnly()
    }

    /** The MAIN line (sub 0) of a cycle slide must never be sub-level HIDDEN — its
     *  visibility is governed by the line's mute (cycleLineEnabled), not the sub
     *  hidden flag (which the editor never exposes for sub 0). Force sub 0 visible on
     *  every slide; returns true if anything was repaired. Cheap no-op normally. */
    private fun repairCycleMainVisibility(): Boolean {
        var changed = false
        for (i in cycleLines.indices) {
            val subs = SubLineCodec.decode(cycleLines[i])
            if (subs.isNotEmpty() && subs[0].hidden) {
                val fixed = subs.toMutableList()
                fixed[0] = fixed[0].copy(hidden = false)
                cycleLines[i] = SubLineCodec.encode(fixed)
                changed = true
            }
        }
        return changed
    }

    /** Apply a persisted CSV of per-line "1"/"0" mute flags (local restore). */
    private fun applyCycleLineEnabledCsv(csv: String) {
        if (csv.isBlank()) { syncCycleEnabledSize(); return }
        val flags = csv.split(",").map { it.trim() == "1" }
        cycleLineEnabled.clear()
        cycleLineEnabled.addAll(flags.take(cycleLines.size))
        syncCycleEnabledSize()
        rebuildCombinedPreviewOnly()
    }

    private fun persistCycleLinesPreserve() {
        // Self-heal sub-0 visibility on every mutation's output so no drag op can ever
        // persist a hidden main line (see repairCycleMainVisibility).
        repairCycleMainVisibility()
        val joined = cycleLines.take(MAX_CYCLE_LINES).joinToString("\n")
        viewModelScope.launch { userPreferencesRepository.saveCycleMessages(joined) }
        persistCycleLineEnabled()
        autoSaveSelectedCyclePreset()
        startSelfSyncLoopIfNeeded()
    }

    /** Auto-save the live cycle lines into the selected cycle preset slot — the
     *  selected slot mirrors the editor, so switching slots is the only save.
     *  No-op when nothing is selected (slot 0) so existing presets aren't clobbered. */
    private fun autoSaveSelectedCyclePreset() {
        if (selectedCyclePreset !in 1..5) return
        val s = selectedCyclePreset
        val idx = s - 1
        val messages = cycleLines.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_CYCLE_LINES).joinToString("\n")
        // Mute CSV aligned to the SAME non-empty lines the preset saves (empty
        // lines are dropped from `messages`, so the flags must skip them too) —
        // otherwise the indices wouldn't line up when the preset reloads.
        val enabledCsv = cycleLines.indices
            .filter { cycleLines[it].trim().isNotEmpty() }
            .take(MAX_CYCLE_LINES)
            .joinToString(",") { if (cycleLineEnabled.getOrElse(it) { true }) "1" else "0" }
        val interval = cycleIntervalSeconds
        val shuffle = cycleShuffle
        cyclePresetMessages[idx] = messages
        cyclePresetIntervals[idx] = interval
        cyclePresetShuffle[idx] = shuffle
        cyclePresetEnabled[idx] = enabledCsv
        viewModelScope.launch {
            when (s) {
                1 -> userPreferencesRepository.saveCyclePreset1(messages, interval, shuffle, enabledCsv = enabledCsv)
                2 -> userPreferencesRepository.saveCyclePreset2(messages, interval, shuffle, enabledCsv = enabledCsv)
                3 -> userPreferencesRepository.saveCyclePreset3(messages, interval, shuffle, enabledCsv = enabledCsv)
                4 -> userPreferencesRepository.saveCyclePreset4(messages, interval, shuffle, enabledCsv = enabledCsv)
                else -> userPreferencesRepository.saveCyclePreset5(messages, interval, shuffle, enabledCsv = enabledCsv)
            }
        }
    }

    /** Switch the active cycle preset: the old slot was already auto-saved, so this
     *  records the new selection and loads it into the editor. */
    fun selectCyclePreset(slot: Int) {
        if (isBanned) return
        val s = slot.coerceIn(1, 5)
        selectedCyclePreset = s
        viewModelScope.launch { userPreferencesRepository.saveSelectedCyclePreset(s) }
        val messages = cyclePresetMessages.getOrElse(s - 1) { "" }
        val storedInterval = cyclePresetIntervals.getOrElse(s - 1) { cycleIntervalSeconds }
        cycleIntervalSeconds = storedInterval.coerceAtLeast(2)
        viewModelScope.launch { userPreferencesRepository.saveCycleInterval(cycleIntervalSeconds) }
        // Restore this preset's own shuffle mode too (speed + shuffle are per-preset).
        cycleShuffle = cyclePresetShuffle.getOrElse(s - 1) { false }
        recentCyclePicks.clear()
        viewModelScope.launch { userPreferencesRepository.saveCycleShuffle(cycleShuffle) }
        setCycleLinesFromTextPreserve(messages)
        // Restore THIS preset's saved hide state. A blank CSV (legacy presets
        // saved before this feature) means all-visible — reset explicitly so a
        // switch between identical-line presets can't inherit the prior slot's
        // mute (setCycleLinesFromTextPreserve early-returns when lines match and
        // applyCycleLineEnabledCsv keeps current flags on a blank CSV).
        val presetEnabledCsv = cyclePresetEnabled.getOrElse(s - 1) { "" }
        if (presetEnabledCsv.isBlank()) {
            cycleLineEnabled.clear()
            syncCycleEnabledSize()
            rebuildCombinedPreviewOnly()
        } else {
            applyCycleLineEnabledCsv(presetEnabledCsv)
        }
        // Persist the loaded lines as the live cycle (NOT via the auto-save path,
        // which would re-save into the same slot redundantly — fine either way).
        viewModelScope.launch { userPreferencesRepository.saveCycleMessages(cycleLines.take(MAX_CYCLE_LINES).joinToString("\n")) }
        persistCycleLineEnabled()
        rebuildCombinedPreviewOnly()
        startSelfSyncLoopIfNeeded()
    }

    /** Persist the per-line mute flags as a CSV of 1/0 (local-only). */
    private fun persistCycleLineEnabled() {
        syncCycleEnabledSize()
        val csv = cycleLineEnabled.joinToString(",") { if (it) "1" else "0" }
        viewModelScope.launch { userPreferencesRepository.saveCycleLineEnabled(csv) }
    }

    fun addCycleLine() {
        if (isBanned) return
        if (cycleLines.size >= MAX_CYCLE_LINES) return
        cycleLines.add("")
        cycleLineEnabled.add(true)
        persistCycleLinesPreserve()
        rebuildCombinedPreviewOnly()
    }

    fun removeCycleLine(index: Int) {
        if (isBanned) return
        if (index !in cycleLines.indices) return
        cycleLines.removeAt(index)
        if (index in cycleLineEnabled.indices) cycleLineEnabled.removeAt(index)
        recentCyclePicks.clear()
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

    // ---- Cycle sub-lines (each slide up to 3 rows, encoded into cycleLines[slide]) ----
    // Mirrors the Pinned sub-line ops; rides the existing cycleLines/cycleLinesText
    // sync + presets (no new Firestore fields). The main editor line is sub-line 0.
    fun cycleSlideSubLines(slide: Int): List<ChatboxSubLine> =
        SubLineCodec.decode(cycleLines.getOrElse(slide) { "" })

    private fun setCycleSlideSubLines(slide: Int, subs: List<ChatboxSubLine>) {
        if (slide !in cycleLines.indices) return
        cycleLines[slide] = SubLineCodec.encode(subs.take(SubLineCodec.MAX_SUB_LINES))
        persistCycleLinesPreserve()
        rebuildCombinedPreviewOnly()
    }

    fun setCycleSubLineText(slide: Int, sub: Int, text: String) {
        val subs = cycleSlideSubLines(slide).toMutableList()
        if (sub !in subs.indices) return
        subs[sub] = subs[sub].copy(text = text)
        setCycleSlideSubLines(slide, subs)
    }

    fun setCycleSubLineHidden(slide: Int, sub: Int, hidden: Boolean) {
        val subs = cycleSlideSubLines(slide).toMutableList()
        if (sub !in subs.indices) return
        subs[sub] = subs[sub].copy(hidden = hidden)
        setCycleSlideSubLines(slide, subs)
    }

    fun addCycleSubLine(slide: Int) {
        val subs = cycleSlideSubLines(slide).toMutableList()
        if (subs.size >= SubLineCodec.MAX_SUB_LINES) return
        subs.add(ChatboxSubLine("", false))
        setCycleSlideSubLines(slide, subs)
    }

    fun removeCycleSubLine(slide: Int, sub: Int) {
        val subs = cycleSlideSubLines(slide).toMutableList()
        if (sub !in subs.indices) return
        subs.removeAt(sub)
        if (subs.isEmpty()) subs.add(ChatboxSubLine("", false))
        setCycleSlideSubLines(slide, subs)
    }

    fun moveCycleSubLine(slide: Int, from: Int, to: Int) {
        val subs = cycleSlideSubLines(slide).toMutableList()
        if (from !in subs.indices || to !in subs.indices || from == to) return
        subs.add(to, subs.removeAt(from))
        setCycleSlideSubLines(slide, subs)
    }

    // ---- Promote / demote (drag between the slide level and the sub-line level) ----
    /** True if slide [from]'s non-blank sub-lines can be appended to slide [into]
     *  without exceeding the 3-row cap (used to gray-out an invalid demote drop). */
    fun canDemoteCycleInto(from: Int, into: Int): Boolean {
        if (from == into || from !in cycleLines.indices || into !in cycleLines.indices) return false
        val fromRows = SubLineCodec.decode(cycleLines[from]).count { it.text.isNotBlank() }
        val intoRows = SubLineCodec.decode(cycleLines[into]).count { it.text.isNotBlank() }
        return fromRows in 1..SubLineCodec.MAX_SUB_LINES &&
            intoRows + fromRows <= SubLineCodec.MAX_SUB_LINES
    }

    /** Demote: slide [from] becomes appended sub-line(s) of slide [into]; [from] is
     *  removed. No-op (returns false) if it would exceed the 3-row cap. */
    fun demoteCycleLineInto(from: Int, into: Int): Boolean {
        if (isBanned) return false
        if (!canDemoteCycleInto(from, into)) return false
        val fromSubs = SubLineCodec.decode(cycleLines[from]).filter { it.text.isNotBlank() }
        val intoClean = SubLineCodec.decode(cycleLines[into]).filter { it.text.isNotBlank() }.toMutableList()
        intoClean.addAll(fromSubs)
        cycleLines[into] = SubLineCodec.encode(intoClean) // set BEFORE removeAt (into valid pre-removal)
        cycleLines.removeAt(from)
        if (from in cycleLineEnabled.indices) cycleLineEnabled.removeAt(from)
        recentCyclePicks.clear()
        persistCycleLinesPreserve()
        rebuildCombinedPreviewOnly()
        return true
    }

    // ---- Cross-section moves (Pinned <-> Cycle) ----
    /** True if a cycle slide's non-blank rows fit into Pinned (3-row cap). */
    fun canMoveCycleLineToPinned(slide: Int): Boolean {
        if (slide !in cycleLines.indices) return false
        val moving = SubLineCodec.decode(cycleLines[slide]).count { it.text.isNotBlank() }
        val pinned = pinnedSubLines().count { it.text.isNotBlank() }
        return moving in 1..SubLineCodec.MAX_SUB_LINES && pinned + moving <= SubLineCodec.MAX_SUB_LINES
    }

    /** Move a whole cycle slide into Pinned at [at] (append when [at] < 0); removes the slide. */
    fun moveCycleLineToPinned(slide: Int, at: Int = -1): Boolean {
        if (isBanned) return false
        if (!canMoveCycleLineToPinned(slide)) return false
        val moving = SubLineCodec.decode(cycleLines[slide]).filter { it.text.isNotBlank() }
        val pinned = pinnedSubLines().filter { it.text.isNotBlank() }.toMutableList()
        val insertAt = if (at < 0) pinned.size else at.coerceIn(0, pinned.size)
        pinned.addAll(insertAt, moving)
        // Pinned's main line (row 0) has no eye — it must never be sub-level hidden.
        if (pinned.isNotEmpty()) pinned[0] = pinned[0].copy(hidden = false)
        updateAfkText(SubLineCodec.encode(pinned.take(SubLineCodec.MAX_SUB_LINES)))
        cycleLines.removeAt(slide)
        if (slide in cycleLineEnabled.indices) cycleLineEnabled.removeAt(slide)
        recentCyclePicks.clear()
        persistCycleLinesPreserve()
        rebuildCombinedPreviewOnly()
        return true
    }

    /** True if a single cycle sub-line fits into Pinned. */
    fun canMoveCycleSubToPinned(): Boolean =
        pinnedSubLines().count { it.text.isNotBlank() } < SubLineCodec.MAX_SUB_LINES

    /** Move one cycle sub-line into Pinned at [at] (append when [at] < 0); removes it from its slide. */
    fun moveCycleSubToPinned(slide: Int, sub: Int, at: Int = -1): Boolean {
        if (isBanned) return false
        val subs = cycleSlideSubLines(slide).toMutableList()
        if (sub !in subs.indices) return false
        if (subs[sub].text.isBlank() || !canMoveCycleSubToPinned()) return false
        val moved = subs.removeAt(sub)
        if (subs.isEmpty()) subs.add(ChatboxSubLine("", false))
        cycleLines[slide] = SubLineCodec.encode(subs)
        val pinned = pinnedSubLines().filter { it.text.isNotBlank() }.toMutableList()
        val insertAt = if (at < 0) pinned.size else at.coerceIn(0, pinned.size)
        pinned.add(insertAt, moved)
        // Pinned's main line (row 0) has no eye — it must never be sub-level hidden.
        if (pinned.isNotEmpty()) pinned[0] = pinned[0].copy(hidden = false)
        updateAfkText(SubLineCodec.encode(pinned.take(SubLineCodec.MAX_SUB_LINES)))
        recentCyclePicks.clear()
        persistCycleLinesPreserve()
        rebuildCombinedPreviewOnly()
        return true
    }

    /** Move one Pinned row out to become its own new cycle line at [at] (append when [at] < 0). */
    fun movePinnedRowToCycle(subIndex: Int, at: Int = -1): Boolean {
        if (isBanned) return false
        if (cycleLines.size >= MAX_CYCLE_LINES) return false
        val psubs = pinnedSubLines().toMutableList()
        if (subIndex !in psubs.indices) return false
        val moved = psubs.removeAt(subIndex)
        if (moved.text.isBlank()) return false
        if (psubs.isEmpty()) psubs.add(ChatboxSubLine("", false))
        updateAfkText(SubLineCodec.encode(psubs))
        val insertAt = if (at < 0) cycleLines.size else at.coerceIn(0, cycleLines.size)
        // Hidden pinned row → visible-but-MUTED cycle line (sub 0 never hidden).
        cycleLines.add(insertAt, SubLineCodec.encode(listOf(moved.copy(hidden = false))))
        if (insertAt <= cycleLineEnabled.size) cycleLineEnabled.add(insertAt, !moved.hidden)
        syncCycleEnabledSize()
        recentCyclePicks.clear()
        persistCycleLinesPreserve()
        rebuildCombinedPreviewOnly()
        return true
    }

    /** True if cycle line [target] is a real line (≥1 content row) with room for one
     *  more sub-line. Used to gray-out an invalid nest drop for sub/pinned sources. */
    fun canNestIntoCycleLine(target: Int): Boolean {
        if (target !in cycleLines.indices) return false
        val n = SubLineCodec.decode(cycleLines[target]).count { it.text.isNotBlank() }
        return n in 1 until SubLineCodec.MAX_SUB_LINES
    }

    /** Nest cycle sub-line [sub] of slide [fromSlide] as a sub-line of cycle line
     *  [target] (append). No-op if target is full or it's the same slide. */
    fun moveCycleSubIntoLine(fromSlide: Int, sub: Int, target: Int): Boolean {
        if (isBanned) return false
        if (fromSlide == target) return false
        if (fromSlide !in cycleLines.indices || target !in cycleLines.indices) return false
        if (!canNestIntoCycleLine(target)) return false
        val fromSubs = cycleSlideSubLines(fromSlide).toMutableList()
        if (sub !in fromSubs.indices || fromSubs[sub].text.isBlank()) return false
        val moved = fromSubs.removeAt(sub)
        if (fromSubs.isEmpty()) fromSubs.add(ChatboxSubLine("", false))
        val targetSubs = SubLineCodec.decode(cycleLines[target]).filter { it.text.isNotBlank() }.toMutableList()
        targetSubs.add(moved) // appended (index ≥1) → hidden flag preserved, sub 0 untouched
        cycleLines[fromSlide] = SubLineCodec.encode(fromSubs)
        cycleLines[target] = SubLineCodec.encode(targetSubs.take(SubLineCodec.MAX_SUB_LINES))
        recentCyclePicks.clear()
        persistCycleLinesPreserve() // self-heals main-line visibility
        rebuildCombinedPreviewOnly()
        return true
    }

    /** Nest a Pinned row ([pinnedIndex]) as a sub-line of cycle line [target] (append).
     *  No-op if target is full. */
    fun movePinnedRowIntoCycleLine(pinnedIndex: Int, target: Int): Boolean {
        if (isBanned) return false
        if (target !in cycleLines.indices) return false
        if (!canNestIntoCycleLine(target)) return false
        val psubs = pinnedSubLines().toMutableList()
        if (pinnedIndex !in psubs.indices) return false
        val moved = psubs.removeAt(pinnedIndex)
        if (moved.text.isBlank()) return false
        if (psubs.isEmpty()) psubs.add(ChatboxSubLine("", false))
        if (psubs.isNotEmpty()) psubs[0] = psubs[0].copy(hidden = false) // pinned main has no eye
        updateAfkText(SubLineCodec.encode(psubs))
        val targetSubs = SubLineCodec.decode(cycleLines[target]).filter { it.text.isNotBlank() }.toMutableList()
        targetSubs.add(moved)
        cycleLines[target] = SubLineCodec.encode(targetSubs.take(SubLineCodec.MAX_SUB_LINES))
        recentCyclePicks.clear()
        persistCycleLinesPreserve()
        rebuildCombinedPreviewOnly()
        return true
    }

    /** Promote: sub-line [sub] of slide [slide] leaves the slide and becomes its own
     *  new cycle line inserted at [at] (right after its slide when [at] < 0). No-op if
     *  at the 20-line cap. */
    fun promoteCycleSubLine(slide: Int, sub: Int, at: Int = -1) {
        if (isBanned) return
        if (cycleLines.size >= MAX_CYCLE_LINES) return
        val subs = cycleSlideSubLines(slide).toMutableList()
        if (sub !in subs.indices) return
        val moved = subs.removeAt(sub)
        if (subs.isEmpty()) subs.add(ChatboxSubLine("", false))
        cycleLines[slide] = SubLineCodec.encode(subs)
        syncCycleEnabledSize()
        val insertAt = if (at < 0) slide + 1 else at.coerceIn(0, cycleLines.size)
        // A standalone cycle line's visibility is its MUTE, not the sub hidden flag —
        // so a hidden sub promotes to a visible-but-MUTED line (intent preserved,
        // sub 0 never hidden → the line still decodes/counts correctly).
        cycleLines.add(insertAt, SubLineCodec.encode(listOf(moved.copy(hidden = false))))
        cycleLineEnabled.add(insertAt, !moved.hidden)
        recentCyclePicks.clear()
        persistCycleLinesPreserve()
        rebuildCombinedPreviewOnly()
    }

    /** Duplicate a line directly below it (carries its mute state). */
    fun duplicateCycleLine(index: Int) {
        if (isBanned) return
        if (index !in cycleLines.indices) return
        if (cycleLines.size >= MAX_CYCLE_LINES) return
        syncCycleEnabledSize()
        cycleLines.add(index + 1, cycleLines[index])
        cycleLineEnabled.add(index + 1, cycleLineEnabled.getOrElse(index) { true })
        recentCyclePicks.clear()
        persistCycleLinesPreserve()
        rebuildCombinedPreviewOnly()
    }

    /** Move a line up/down by one (reorder). */
    fun moveCycleLine(from: Int, to: Int) {
        if (isBanned) return
        if (from !in cycleLines.indices || to !in cycleLines.indices || from == to) return
        syncCycleEnabledSize()
        cycleLines.add(to, cycleLines.removeAt(from))
        cycleLineEnabled.add(to, cycleLineEnabled.removeAt(from))
        recentCyclePicks.clear()
        persistCycleLinesPreserve()
        rebuildCombinedPreviewOnly()
    }

    /** Toggle a single line's mute (skipped by the sender, kept in the editor). */
    fun setCycleLineEnabled(index: Int, enabled: Boolean) {
        if (isBanned) return
        syncCycleEnabledSize()
        if (index !in cycleLineEnabled.indices) return
        cycleLineEnabled[index] = enabled
        cycleMuteRev++
        recentCyclePicks.clear()
        persistCycleLineEnabled()
        // Persist the hide state INTO the selected preset too — otherwise a
        // preset never remembers which lines were hidden ("presets don't save
        // hidden lines"). No-op at slot 0 (autoSave early-returns).
        autoSaveSelectedCyclePreset()
        rebuildCombinedPreviewOnly()
    }

    fun setCycleShuffleFlag(enabled: Boolean) {
        if (isBanned) return
        cycleShuffle = enabled
        recentCyclePicks.clear()
        viewModelScope.launch { userPreferencesRepository.saveCycleShuffle(enabled) }
        // Shuffle is part of a preset — write it into the selected slot (no-op at slot 0).
        autoSaveSelectedCyclePreset()
    }

    fun clearCycleLines() {
        if (isBanned) return
        cycleLines.clear()
        cycleLineEnabled.clear()
        recentCyclePicks.clear()
        persistCycleLinesPreserve()
        rebuildCombinedPreviewOnly()
    }

    /**
     * The non-blank, non-muted cycle lines in editor order (capped). This is the
     * rotation set the sender + preview walk. Raw text (tokens unresolved) so the
     * caller resolves [resolveCycleTokens] at send/preview time.
     */
    private fun activeCycleLines(): List<String> {
        val out = ArrayList<String>(cycleLines.size)
        for (i in cycleLines.indices) {
            if (out.size >= MAX_CYCLE_LINES) break
            // A slide may hold up to 3 sub-lines encoded in cycleLines[i]; render
            // only its visible rows (joined by real newlines = separate chatbox
            // rows). Muted slides + slides with no visible content are skipped.
            val rendered = SubLineCodec.renderVisible(cycleLines[i])
            if (rendered.isNotEmpty() && cycleLineEnabled.getOrElse(i) { true }) out.add(rendered)
        }
        return out
    }

    /**
     * Substitute dynamic tokens with live values just before sending/previewing:
     *   {time}    current Time-line string  {song}    "Artist - Title" now playing
     *   {world}   current VRChat world name  {players} instance "n/cap"
     * Unknown tokens are left as-is; a token with no live value renders empty.
     * Public so Pinned + Cycle editors can resolve tokens for their char meter
     * (the meter must count the RESOLVED length, not the literal "{world}").
     */
    fun resolveTokens(text: String): String {
        if (text.indexOf('{') < 0) return text
        var out = text
        if (out.contains("{time}", ignoreCase = true))
            out = out.replace(Regex("\\{time\\}", RegexOption.IGNORE_CASE), currentTimeString())
        if (out.contains("{song}", ignoreCase = true)) {
            val title = lastNowPlayingTitle.takeIf { it.isNotBlank() && it != "(blank)" }.orEmpty()
            val artist = lastNowPlayingArtist.takeIf { it.isNotBlank() && it != "(blank)" }.orEmpty()
            val song = when {
                title.isNotEmpty() && artist.isNotEmpty() -> "$artist - $title"
                title.isNotEmpty() -> title
                else -> ""
            }
            out = out.replace(Regex("\\{song\\}", RegexOption.IGNORE_CASE), song)
        }
        if (out.contains("{world}", ignoreCase = true)) {
            val w = VrchatPipelineState.presence?.worldName?.takeIf {
                it.isNotBlank() && !it.equals("offline", true)
            }.orEmpty()
            out = out.replace(Regex("\\{world\\}", RegexOption.IGNORE_CASE), w)
        }
        if (out.contains("{players}", ignoreCase = true)) {
            val p = VrchatPipelineState.presence
            val players = if (p != null && p.instancePlayerCount > 0) {
                if (p.instanceCapacity > 0) "${p.instancePlayerCount}/${p.instanceCapacity}"
                else p.instancePlayerCount.toString()
            } else ""
            out = out.replace(Regex("\\{players\\}", RegexOption.IGNORE_CASE), players)
        }
        return out
    }

    /**
     * No-repeat window for shuffle: how many recently-played positions to avoid,
     * scaled by the number of active lines. Small counts stay at 1 (just no
     * immediate repeat); from 5 lines up it ramps via these anchors (activeSize to
     * window): 5→3, 10→5, 15→8, 20→14, interpolating linearly in between and
     * clamping above the top anchor. More lines = enforce more variety before a
     * line can come back around.
     */
    private fun shuffleWindow(activeSize: Int): Int {
        if (activeSize < 5) return 1
        val anchors = listOf(5 to 3, 10 to 5, 15 to 8, 20 to 14)
        if (activeSize >= anchors.last().first) return anchors.last().second
        for (i in 0 until anchors.size - 1) {
            val (s0, w0) = anchors[i]
            val (s1, w1) = anchors[i + 1]
            if (activeSize in s0 until s1) {
                val t = (activeSize - s0).toFloat() / (s1 - s0)
                return Math.round(w0 + t * (w1 - w0)).toInt()
            }
        }
        return anchors.last().second
    }

    /**
     * Pick the next active-line position. Sequential = previous+1. Shuffle = a random
     * position avoiding the recent window (see [shuffleWindow]) so it stays random
     * without repeating too much / never an immediate repeat.
     */
    private fun nextCyclePos(activeSize: Int, prevPos: Int): Int {
        if (activeSize <= 1) return 0
        if (!cycleShuffle) return (prevPos + 1) % activeSize
        val window = shuffleWindow(activeSize).coerceIn(1, activeSize - 1)
        val recent = recentCyclePicks.toSet()
        val candidates = (0 until activeSize).filter { it !in recent }
        val pick = (candidates.ifEmpty { (0 until activeSize).filter { it != prevPos } })
            .randomOrNull() ?: ((prevPos + 1) % activeSize)
        recentCyclePicks.addLast(pick)
        while (recentCyclePicks.size > window) recentCyclePicks.removeFirst()
        return pick
    }

    // =========================
    // Preset previews
    // =========================
    fun getAfkPresetPreview(slot: Int): String {
        val i = slot.coerceIn(1, 3) - 1
        // Presets store the encoded pinned value; show the visible rows readably.
        return SubLineCodec.renderVisible(afkPresetTexts[i]).replace("\n", "  /  ").trim()
    }

    fun getCyclePresetPreview(slot: Int): String {
        val i = slot.coerceIn(1, 5) - 1
        // Slides may be sub-line encoded; show the first slide's visible rows.
        val firstSlide = cyclePresetMessages[i].split("\n").firstOrNull { SubLineCodec.hasVisible(it) }.orEmpty()
        return SubLineCodec.renderVisible(firstSlide).replace("\n", "  /  ").trim()
    }

    /** Full multi-line cycle preset content — the Automations preset-chip
     *  long-press peek needs more than the first line. */
    fun getCyclePresetFull(slot: Int): String {
        val i = slot.coerceIn(1, 5) - 1
        return cyclePresetMessages[i].split("\n")
            .map { SubLineCodec.renderVisible(it).replace("\n", "  /  ") }
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }

    /** Short metadata line for a cycle preset ("5 lines · 10s · shuffle"). */
    fun getCyclePresetSubtitle(slot: Int): String {
        val i = slot.coerceIn(1, 5) - 1
        val lines = cyclePresetMessages.getOrElse(i) { "" }.split("\n").count { SubLineCodec.hasVisible(it) }
        val interval = cyclePresetIntervals.getOrElse(i) { 10 }
        val shuf = cyclePresetShuffle.getOrElse(i) { false }
        return buildString {
            append("$lines line").append(if (lines == 1) "" else "s")
            append(" · ${interval}s")
            if (shuf) append(" · shuffle")
        }
    }

    /** The cycle line currently on screen (or first line when idle) — drives
     *  the Automations collapsed-card "now: '…'" summary. */
    fun cycleCurrentLine(): String = currentCycleLinePreview()

    /** Raw editor index of the line currently being sent (for the live "now
     *  sending" highlight), or -1 when nothing is active. */
    fun cycleActiveRawIndex(): Int {
        if (!cycleEnabled) return -1
        val raw = ArrayList<Int>(cycleLines.size)
        for (i in cycleLines.indices) {
            // Match activeCycleLines exactly (visible rows, not raw markers).
            if (SubLineCodec.hasVisible(cycleLines[i]) && cycleLineEnabled.getOrElse(i) { true }) raw.add(i)
        }
        if (raw.isEmpty()) return -1
        return raw[cycleIndex % raw.size]
    }

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
    /**
     * One-shot migration for users updating from the pre-auto-save preset
     * system. They arrive at slot 0 (nothing selected) with their live Pinned
     * message / cycle lines saved in NO preset slot — tapping any preset chip
     * would load that slot over the live editor and silently destroy their
     * content. Park the live content in a slot ONCE: prefer a slot already
     * holding identical content (just select it — no write needed), else the
     * first EMPTY slot (save + select). If every slot is full with distinct
     * content, leave slot 0 selected and change nothing (never clobber a
     * deliberately saved preset). Selecting here does NOT reload the editor —
     * the editor already holds the content by construction. Guarded by the
     * PRESET_SEED_MIGRATED DataStore flag so it runs at most once.
     */
    private suspend fun migrateLiveContentIntoPresetSlot() {
        if (userPreferencesRepository.presetSeedMigrated.first()) return
        // Pinned: live message → matching or first empty slot.
        if (selectedAfkPreset == 0) {
            val live = afkMessage.trim()
            if (live.isNotEmpty()) {
                val match = (1..3).firstOrNull { afkPresetTexts[it - 1].trim() == live }
                val target = match ?: (1..3).firstOrNull { afkPresetTexts[it - 1].isBlank() }
                if (target != null) {
                    if (match == null) {
                        afkPresetTexts[target - 1] = afkMessage
                        when (target) {
                            1 -> userPreferencesRepository.saveAfkPreset1(afkMessage)
                            2 -> userPreferencesRepository.saveAfkPreset2(afkMessage)
                            else -> userPreferencesRepository.saveAfkPreset3(afkMessage)
                        }
                    }
                    selectedAfkPreset = target
                    userPreferencesRepository.saveSelectedAfkPreset(target)
                }
            }
        }
        // Cycle: live lines (normalized like the auto-save) → matching or empty slot.
        if (selectedCyclePreset == 0) {
            val liveLines = cycleLines.map { it.trim() }.filter { it.isNotEmpty() }
                .take(MAX_CYCLE_LINES).joinToString("\n")
            if (liveLines.isNotEmpty()) {
                val match = (1..5).firstOrNull { cyclePresetMessages[it - 1].trim() == liveLines }
                val target = match ?: (1..5).firstOrNull { cyclePresetMessages[it - 1].isBlank() }
                if (target != null) {
                    if (match == null) {
                        // Mute CSV aligned to the non-empty lines (same as the auto-save)
                        // so the parked preset keeps the user's live hide state.
                        val liveEnabledCsv = cycleLines.indices
                            .filter { cycleLines[it].trim().isNotEmpty() }
                            .take(MAX_CYCLE_LINES)
                            .joinToString(",") { if (cycleLineEnabled.getOrElse(it) { true }) "1" else "0" }
                        cyclePresetMessages[target - 1] = liveLines
                        cyclePresetIntervals[target - 1] = cycleIntervalSeconds
                        cyclePresetShuffle[target - 1] = cycleShuffle
                        cyclePresetEnabled[target - 1] = liveEnabledCsv
                        when (target) {
                            1 -> userPreferencesRepository.saveCyclePreset1(liveLines, cycleIntervalSeconds, cycleShuffle, enabledCsv = liveEnabledCsv)
                            2 -> userPreferencesRepository.saveCyclePreset2(liveLines, cycleIntervalSeconds, cycleShuffle, enabledCsv = liveEnabledCsv)
                            3 -> userPreferencesRepository.saveCyclePreset3(liveLines, cycleIntervalSeconds, cycleShuffle, enabledCsv = liveEnabledCsv)
                            4 -> userPreferencesRepository.saveCyclePreset4(liveLines, cycleIntervalSeconds, cycleShuffle, enabledCsv = liveEnabledCsv)
                            else -> userPreferencesRepository.saveCyclePreset5(liveLines, cycleIntervalSeconds, cycleShuffle, enabledCsv = liveEnabledCsv)
                        }
                    }
                    selectedCyclePreset = target
                    userPreferencesRepository.saveSelectedCyclePreset(target)
                }
            }
        }
        userPreferencesRepository.savePresetSeedMigrated(true)
    }

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

        val messages = lines.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_CYCLE_LINES).joinToString("\n")
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
        val msgs = activeCycleLines()
        if (!cycleEnabled || !oscSending || msgs.isEmpty()) return

        persistCycleLinesPreserve()
        viewModelScope.launch { userPreferencesRepository.saveCycleInterval(cycleIntervalSeconds) }

        // Cycle has its own interval-based send loop — cancel keepalive to avoid duplication.
        keepaliveJob?.cancel()
        keepaliveJob = null
        cycleJob?.cancel()
        recentCyclePicks.clear()
        cycleJob = viewModelScope.launch {
            // Resume from wherever the cycle left off instead of snapping back to
            // line 1 on every Start. cycleIndex is preserved by stopCycle (and it
            // keeps advancing in the background during a manual-send takeover),
            // so pick it up here; coerced into range in case lines changed while
            // stopped. This is the "Start/Stop resets the cycle to line 1" fix.
            val resumePos = cycleIndex
            var prevPos = -1
            var first = true
            while (cycleEnabled && oscSending && !isBanned) {
                // Re-read the LIVE active lines every tick (mid-send edits / mutes /
                // reorders apply at the next rotation boundary). The loop used to
                // iterate a list captured ONCE at start, which flashed pre-edit text.
                val live = activeCycleLines()
                if (live.isEmpty()) {
                    // All lines deleted/muted mid-send: render without a cycle line
                    // (clears the chatbox if nothing else is enabled) and keep
                    // looping so re-adding a line resumes automatically.
                    rebuildAndMaybeSendCombined(forceSend = true, local = local, forceClearIfAllOff = true)
                    prevPos = -1
                    nextCycleAtMs = System.currentTimeMillis() + cycleIntervalSeconds.toLong() * 1000L
                    delay(cycleIntervalSeconds.toLong() * 1000L)
                    continue
                }
                // First tick shows the RESUME position (where it left off), not a
                // hard 0; subsequent ticks pick the next position (sequential or
                // shuffle with the no-repeat window).
                val pos = if (first) resumePos.coerceIn(0, live.size - 1) else nextCyclePos(live.size, prevPos)
                first = false
                prevPos = pos
                cycleIndex = pos.coerceIn(0, live.size - 1)
                // Resolve {time}/{song}/{world}/{players} just before sending so the
                // values are live.
                rebuildAndMaybeSendCombined(
                    forceSend = true,
                    local = local,
                    cycleLineOverride = resolveTokens(live[cycleIndex])
                )
                nextCycleAtMs = System.currentTimeMillis() + cycleIntervalSeconds.toLong() * 1000L
                delay(cycleIntervalSeconds.toLong() * 1000L)
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

        // Manual Send takeover: while a manual message holds the chatbox, the
        // automated senders must NOT overwrite or clear it. Keep the Home preview
        // showing the manual text and skip the send. The revert/clear paths drop
        // the hold (set it to 0) BEFORE calling here, so restoring the normal
        // chatbox still goes through.
        if (manualHoldActive()) {
            if (lastManualHoldText.isNotEmpty()) combinedPreviewText = lastManualHoldText
            return
        }

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
        // Pinned may hold up to 3 sub-lines encoded in afkMessage; render only the
        // visible, non-blank rows (joined by real newlines → separate chatbox rows).
        val afkVisible = SubLineCodec.renderVisible(afkMessage)
        val afkLine = if (afkEnabled && afkVisible.isNotEmpty()) resolveTokens(afkVisible) else ""
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
        val msgs = activeCycleLines()
        if (msgs.isEmpty()) return ""
        return resolveTokens(msgs.getOrNull(cycleIndex % msgs.size).orEmpty())
    }

    /** The exact music lines as they would render into the chatbox right now —
     *  shown verbatim (monospace) in the Media tab's now-playing card. */
    fun currentMusicChatboxLines(): List<String> = buildNowPlayingLines()

    private fun buildNowPlayingLines(): List<String> {
        val title = if (spotifyDemoEnabled && !nowPlayingDetected) "Pretty Girl" else lastNowPlayingTitle
        val artist = if (spotifyDemoEnabled && !nowPlayingDetected) "Clairo" else lastNowPlayingArtist
        if (!spotifyDemoEnabled && !nowPlayingDetected) return emptyList()
        // Per-source enable (Media tab): a disabled source renders nothing —
        // detection keeps running so re-enabling resumes instantly.
        if (nowPlayingDetected && !isActiveMediaSourceEnabled()) return emptyList()

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
            if (!musicShowProgress) return listOf(label)
            // Keep the progress bar during ads — it must NEVER vanish. Ads always
            // play, so force playing and render the ad's OWN position/duration
            // countdown. When the player reports NO duration (some audio ads),
            // render a static zero-position bar with no time instead of dropping
            // the bar entirely (the old fallback). No brand/title is shown — only
            // the neutral "Ad" label + the bar — so nothing leaks.
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
            val bar = renderProgressBar(spotifyPreset, 0L, 1L, true, true)
            return listOfNotNull(label, bar.takeIf { it.isNotBlank() })
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

        // Media tab "Show progress bar" off → title only, no bar/time line.
        if (!musicShowProgress) return listOfNotNull(line1.takeIf { it.isNotBlank() })

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

        // A Pinned block / Cycle slide can now be multiple chatbox ROWS (embedded
        // '\n'), so the 9-line budget counts ROWS, not blocks. Over-budget removes
        // whole lowest-priority blocks (Cycle first, then Music, then Pinned).
        fun rowCount(list: List<LineWithPriority>): Int =
            list.sumOf { it.text.count { c -> c == '\n' } + 1 }

        while (rowCount(cleaned) > maxLines && cleaned.isNotEmpty()) {
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
