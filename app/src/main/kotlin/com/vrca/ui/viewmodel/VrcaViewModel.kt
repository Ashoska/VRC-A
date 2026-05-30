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
import com.vrca.data.UserPreferencesRepository
import com.vrca.osc.VrcaOsc
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

        private const val SEND_FLOOR_MS = 500L

        private const val META_STABLE_MS = 1_100L
        private const val META_CONFIRM_MOVE_MS = 900L
        private const val META_GIVE_UP_MS = 2_400L
        private const val POS_RESET_CONFIRM_MS = 1_800L

        private const val NO_MOVE_PAUSE_MS = 5_000L
        private const val UI_TICK_MS = 500L

        // Firestore sync throttles
        private const val SELF_SYNC_DEBOUNCE_MS = 500L
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

        // Moderation attach retry
        private const val MOD_ATTACH_RETRY_MS = 1_250L

        // SharedPrefs (must match AdminScreen + VrcaApp/MainActivity)
        private const val REMOTE_PREFS_FILE = "vrca_remote"
        private const val PREF_DEVICE_ID_HASH = "device_id_hash"
        private const val PREF_AUTH_UID = "auth_uid"

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
        // Best-effort close-write fallback: VrchatPipelineService.onTaskRemoved
        // is the primary path, but if that service isn't running we still want
        // the user to be marked offline. GlobalScope is intentional — we need
        // the write to outlive ViewModel teardown.
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch {
            runCatching {
                val deviceHash = readDeviceHashFromPrefs()
                if (isValidDeviceHash(deviceHash)) {
                    db.collection(COL_USERS).document(deviceHash)
                        .set(mapOf(
                            "isOnlineInApp" to false,
                            "lastSeenAt" to FieldValue.serverTimestamp()
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
    private var lastSelfSyncFingerprint: String = ""
    private var usersByIdLinkWritten: Boolean = false
    private var lastSelfSyncError: String = ""

    // Per-field snapshot of what we last successfully wrote to Firestore.
    // applyRemoteConfig compares incoming snapshot values against these to
    // distinguish our own echoes (match → skip) from genuine admin edits
    // (differ → apply). This replaces fingerprint-based echo suppression
    // which was fragile due to empty-line filtering, volatile fields, and
    // race conditions between heartbeat and self-sync writes.
    private val lastSyncedValues = mutableMapOf<String, Any?>()

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

    private suspend fun ensureAnonAuth(): String? {
        return runCatching {
            if (auth.currentUser == null) auth.signInAnonymously().await()
            val uid = auth.currentUser?.uid
            if (!uid.isNullOrBlank()) writeCachedUid(uid)
            uid
        }.getOrNull()
    }

    private fun computeSelfFingerprint(authUid: String, deviceHash: String): String {
        val cycleClean = cycleLines.map { it.trim() }.filter { it.isNotEmpty() }.take(10)
        val afkP = (1..3).joinToString("|") { getAfkPresetPreview(it) }
        val cycP = (1..5).joinToString("|") { getCyclePresetPreview(it) }

        return listOf(
            "doc=$deviceHash",
            "dev=$deviceHash",
            "auth=$authUid",
            "afkE=$afkEnabled",
            "afkM=${afkMessage.trim()}",
            "cycE=$cycleEnabled",
            "cycI=$cycleIntervalSeconds",
            "cycL=${cycleClean.joinToString("\\n")}",
            "spE=$spotifyEnabled",
            "spD=$spotifyDemoEnabled",
            "spP=$spotifyPreset",
            "afkP=$afkP",
            "cycP=$cycP",
            "timeE=$timeEnabled",
            "timeM=$timeMode"
        ).joinToString("||")
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
        val presence = com.vrca.vrchat.VrchatPipelineState.presence
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
        "lastReportedTime" to if (timeEnabled) currentTimeString() else "",
        "lastTimeUpdateAt" to FieldValue.serverTimestamp(),
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
     * No-op under the hourly model. Edits and toggles used to trigger a
     * debounced Firestore write here; that produced a write per keystroke/
     * toggle. Now content is persisted ONLY to local DataStore on edit (which
     * already happens at every call site) and pushed to Firestore on the next
     * app-open write or the hourly heartbeat ([startHourlyHeartbeat]) — and
     * only if it actually changed (fingerprint guard in [performSelfSync]).
     *
     * The 47 call sites are intentionally left in place so the edit→DataStore
     * paths and preview rebuilds at those sites are untouched; this function
     * simply no longer schedules a network write. While an admin is actively
     * watching this user, the 10s live-sync loop still streams volatile output.
     */
    private fun startSelfSyncLoopIfNeeded() {
        // Intentionally empty — see KDoc above. (SELF_SYNC_DEBOUNCE_MS retained
        // for reference; no debounced write is scheduled anymore.)
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

    private fun captureStateForSync(): Map<String, Any?> = mapOf(
        "afkEnabled" to afkEnabled,
        "afkMessage" to afkMessage.trim(),
        "cycleEnabled" to cycleEnabled,
        "cycleIntervalSeconds" to cycleIntervalSeconds,
        "cycleLinesText" to cycleLines.joinToString("\n").trim(),
        "spotifyEnabled" to spotifyEnabled,
        "spotifyPreset" to spotifyPreset,
        "timeEnabled" to timeEnabled,
        "timeMode" to timeMode,
        "afkPreset1" to getAfkPresetPreview(1),
        "afkPreset2" to getAfkPresetPreview(2),
        "afkPreset3" to getAfkPresetPreview(3),
        "cyclePreset1" to (cyclePresetMessages.getOrNull(0)?.trim().orEmpty()),
        "cyclePreset2" to (cyclePresetMessages.getOrNull(1)?.trim().orEmpty()),
        "cyclePreset3" to (cyclePresetMessages.getOrNull(2)?.trim().orEmpty()),
        "cyclePreset4" to (cyclePresetMessages.getOrNull(3)?.trim().orEmpty()),
        "cyclePreset5" to (cyclePresetMessages.getOrNull(4)?.trim().orEmpty()),
    )

    private suspend fun applyRemoteContentBeforeSync() {
        // The admin build keeps its chatbox content purely in local DataStore.
        // It must NOT read its own users/{adminHash} doc back into DataStore:
        // a stale snapshot (from an earlier sync, including the pre-distinct-hash
        // era when admin shared the public doc) would overwrite the admin's
        // fresh local presets on every reopen.
        if (BuildConfig.IS_ADMIN_BUILD) return
        runCatching {
            val deviceHash = readDeviceHashFromPrefs()
            if (!isValidDeviceHash(deviceHash)) return@runCatching
            val snap = db.collection(COL_USERS).document(deviceHash).get().await()
            if (snap == null || !snap.exists()) return@runCatching

            snap.getString("afkMessage")?.trim()?.let { remote ->
                if (remote != afkMessage.trim()) {
                    afkMessage = remote
                    userPreferencesRepository.saveAfkMessage(remote)
                }
            }
            snap.getLong("cycleIntervalSeconds")?.toInt()?.coerceAtLeast(2)?.let { remote ->
                if (remote != cycleIntervalSeconds) {
                    cycleIntervalSeconds = remote
                    userPreferencesRepository.saveCycleInterval(remote)
                }
            }
            snap.getString("cycleLinesText")?.trim()?.let { remote ->
                val local = cycleLines.joinToString("\n").trim()
                if (remote != local) {
                    setCycleLinesFromTextPreserve(remote)
                    userPreferencesRepository.saveCycleMessages(remote)
                }
            }
            val afkPresetSavers = listOf<suspend (String) -> Unit>(
                { v -> userPreferencesRepository.saveAfkPreset1(v) },
                { v -> userPreferencesRepository.saveAfkPreset2(v) },
                { v -> userPreferencesRepository.saveAfkPreset3(v) }
            )
            for (i in 1..3) {
                snap.getString("afkPreset$i")?.trim()?.let { remote ->
                    if (remote != afkPresetTexts[i - 1].trim()) {
                        afkPresetTexts[i - 1] = remote
                        afkPresetSavers[i - 1](remote)
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
                    if (remote != (cyclePresetMessages.getOrNull(i - 1)?.trim().orEmpty())) {
                        cyclePresetMessages[i - 1] = remote
                        val interval = cyclePresetIntervals.getOrElse(i - 1) { 10 }
                        presetSavers[i - 1](remote, interval, null)
                    }
                }
            }
            snap.getLong("spotifyPreset")?.toInt()?.coerceIn(1, 5)?.let { remote ->
                if (remote != spotifyPreset) {
                    spotifyPreset = remote
                    userPreferencesRepository.saveSpotifyPreset(remote)
                }
            }
        }
    }

    private suspend fun performSelfSync() {
        // Admin build never writes its own user doc — content lives in DataStore
        // only. Writing it would create an orphan doc AND feed the round-trip
        // (write → snapshot echo → applyRemoteConfig) that wipes local presets.
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

            val fp = computeSelfFingerprint(authUid, deviceHash)
            if (fp == lastSelfSyncFingerprint) return@runCatching

            val stateSnapshot = captureStateForSync()
            lastSelfSyncFingerprint = fp

            try {
                db.collection(COL_USERS).document(deviceHash)
                    .set(buildUserSnapshot(authUid, deviceHash), SetOptions.merge())
                    .await()

                // usersById link is static (deviceHash, authUid, appId, adminBuild
                // never change per device). Write it once per session, not on
                // every debounced content sync.
                if (!usersByIdLinkWritten) {
                    runCatching {
                        db.collection(COL_USERS_BY_ID).document(authUid)
                            .set(buildUsersByIdLink(authUid, deviceHash), SetOptions.merge())
                            .await()
                        usersByIdLinkWritten = true
                    }
                }

                lastSyncedValues.clear()
                lastSyncedValues.putAll(stateSnapshot)
                lastSelfSyncAtMs = System.currentTimeMillis()
                lastSelfSyncError = ""
            } catch (e: Throwable) {
                lastSelfSyncFingerprint = ""
                throw e
            }
        }.onFailure { e ->
            lastSelfSyncError = (e.message ?: e.toString()).take(4000)
        }
    }

    /**
     * Starts the once-per-hour liveness loop. Idempotent; started once from
     * init after the cold-open write. Each tick:
     *   1. Always writes lastActiveAt (+ isOnlineInApp=true) so the admin's
     *      online/offline determination stays fresh — this is the one write
     *      that must happen even when nothing changed.
     *   2. Calls performSelfSync(), which pushes content ONLY if it changed
     *      since the last write (fingerprint guard), so unchanged presets/
     *      chatbox aren't rewritten.
     * In-process only — dies with the process, which is exactly how a
     * swiped/killed app correctly stops reporting online.
     */
    private fun startHourlyHeartbeat() {
        if (BuildConfig.IS_ADMIN_BUILD) return
        if (hourlyHeartbeatJob != null) return
        hourlyHeartbeatJob = viewModelScope.launch {
            while (true) {
                delay(HOURLY_HEARTBEAT_MS)
                performHourlyHeartbeat()
                performSelfSync()
            }
        }
    }

    private suspend fun performHourlyHeartbeat() {
        if (BuildConfig.IS_ADMIN_BUILD) return
        if (!initialDataLoaded) return
        runCatching {
            val deviceHash = readDeviceHashFromPrefs()
            if (!isValidDeviceHash(deviceHash)) return@runCatching
            db.collection(COL_USERS).document(deviceHash)
                .set(
                    mapOf(
                        "isOnlineInApp" to true,
                        "lastActiveAt" to FieldValue.serverTimestamp(),
                        "lastSeenAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
                .await()
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

                // Attach once.
                if (moderationUserReg == null) {
                    moderationLastError = ""
                    moderationConnected = true

                    moderationUserReg = db.collection(COL_USERS).document(deviceHash)
                        .addSnapshotListener { snap, e ->
                            if (e != null) {
                                moderationLastError = (e.message ?: "Moderation listen failed").take(4000)
                                moderationConnected = false
                                return@addSnapshotListener
                            }

                            if (snap == null || !snap.exists()) {
                                warned = false
                                warnReason = ""
                                uidBanned = false
                                banReason = ""
                                moderationConnected = true
                                moderationLastError = ""
                                enforceIfBannedChanged()
                                return@addSnapshotListener
                            }

                            warned = snap.getBoolean("warned") ?: false
                            warnReason = (snap.getString("warnReason") ?: "").trim()

                            uidBanned = snap.getBoolean("banned") ?: false
                            banReason = (snap.getString("banReason") ?: "").trim()

                            moderationConnected = true
                            moderationLastError = ""
                            enforceIfBannedChanged()

                            val killSignal = snap.getTimestamp("killSignal")
                            if (killSignal != null) {
                                val killMs = killSignal.seconds * 1000L + (killSignal.nanoseconds / 1_000_000L)
                                val ageMs = System.currentTimeMillis() - killMs
                                if (ageMs in 0L..60_000L) {
                                    handleAdminKill()
                                }
                            }

                            targetedUpdateUrl = (snap.getString("targetedUpdateUrl") ?: "").trim()
                            targetedUpdateNotes = (snap.getString("targetedUpdateNotes") ?: "").trim()

                            // Apply remote config on every non-echo snapshot.
                            // Echo suppression is handled inside applyRemoteConfig
                            // via the per-field lastSyncedValues map — no need to
                            // gate on hasPendingWrites() which blocks admin edits
                            // during live-mode (writes every 500ms keep pending
                            // state almost perpetually true).
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
    private var initialSnapshotProcessed = false

    /**
     * Seed [lastSyncedValues] from a Firestore snapshot. Called on the very
     * first snapshot AND after [applyRemoteContentBeforeSync] so the snapshot
     * listener has a baseline to compare admin edits against — without this,
     * admin toggle/preset writes would be silently dropped during the window
     * between listener-attach and the first successful [performSelfSync].
     */
    private fun seedLastSyncedFromSnapshot(snap: com.google.firebase.firestore.DocumentSnapshot) {
        snap.getBoolean("afkEnabled")?.let { lastSyncedValues["afkEnabled"] = it }
        snap.getBoolean("cycleEnabled")?.let { lastSyncedValues["cycleEnabled"] = it }
        snap.getBoolean("spotifyEnabled")?.let { lastSyncedValues["spotifyEnabled"] = it }
        snap.getBoolean("timeEnabled")?.let { lastSyncedValues["timeEnabled"] = it }
        snap.getString("afkMessage")?.let { lastSyncedValues["afkMessage"] = it.trim() }
        snap.getLong("cycleIntervalSeconds")?.let { lastSyncedValues["cycleIntervalSeconds"] = it.toInt().coerceAtLeast(2) }
        snap.getString("cycleLinesText")?.let { lastSyncedValues["cycleLinesText"] = it.trim() }
        for (i in 1..3) snap.getString("afkPreset$i")?.let { lastSyncedValues["afkPreset$i"] = it.trim() }
        for (i in 1..5) snap.getString("cyclePreset$i")?.let { lastSyncedValues["cyclePreset$i"] = it.trim() }
        snap.getLong("spotifyPreset")?.let { lastSyncedValues["spotifyPreset"] = it.toInt().coerceIn(1, 5) }
        snap.getString("timeMode")?.let { lastSyncedValues["timeMode"] = it }
    }

    private fun applyRemoteConfig(snap: com.google.firebase.firestore.DocumentSnapshot) {
        // Admin build does not apply remote config to itself. It is never the
        // target of admin edits, and applying a stale own-doc snapshot would
        // overwrite its local DataStore presets. (Watcher detection is also
        // meaningless here since the admin is filtered out of its own list.)
        if (BuildConfig.IS_ADMIN_BUILD) return
        // Watcher detection runs on EVERY snapshot (even the first). Admins
        // refresh `watcherActiveAt` from the admin panel; if recent enough
        // we flip [AdminWatchState.isWatched] to true and the live-sync
        // loop starts streaming volatile fields. No traffic when nobody
        // is watching.
        val watcherActiveAtMs = runCatching {
            snap.getTimestamp("watcherActiveAt")?.toDate()?.time
        }.getOrNull()
        com.vrca.sync.AdminWatchState.updateFromTimestampMs(watcherActiveAtMs)

        viewModelScope.launch {
            if (!initialSnapshotProcessed) {
                initialSnapshotProcessed = true
                // Seed baseline from the first snapshot so admin edits arriving
                // before the first performSelfSync write can still be detected.
                // We drop the first snapshot's content (DataStore wins cold start)
                // but record what's on the doc so subsequent changes are caught.
                seedLastSyncedFromSnapshot(snap)
                return@launch
            }

            // For each field, compare remote value against what we LAST WROTE
            // to Firestore (lastSyncedValues). If it matches our last write,
            // the snapshot is just an echo (from self-sync, watcher heartbeat,
            // or live-mode write) — skip. If it differs, an admin actually
            // changed the field, so apply.
            //
            // Comparing against current local state would break here: when an
            // admin starts watching and the heartbeat fires, the snapshot
            // contains the OLD field values (Firestore hasn't received the
            // user's pending local toggle yet), so `remote != local` would
            // revert whatever the user just toggled. lastSyncedValues stays
            // in lock-step with what's actually on the doc, so it's the right
            // reference for distinguishing echoes from real admin edits.
            //
            // Both branches update lastSyncedValues to the snapshot value so
            // the subsequent self-sync echo is correctly suppressed.
            snap.getBoolean("afkEnabled")?.let { remote ->
                if (remote != lastSyncedValues["afkEnabled"]) {
                    afkEnabled = remote
                    savedState["afkEnabled"] = remote
                    lastSyncedValues["afkEnabled"] = remote
                    rebuildCombinedPreviewOnly()
                    if (!remote) stopAfkSender(clearFromChatbox = true)
                    startSelfSyncLoopIfNeeded()
                } else {
                    lastSyncedValues["afkEnabled"] = remote
                }
            }
            snap.getBoolean("cycleEnabled")?.let { remote ->
                if (remote != lastSyncedValues["cycleEnabled"]) {
                    cycleEnabled = remote
                    savedState["cycleEnabled"] = remote
                    lastSyncedValues["cycleEnabled"] = remote
                    rebuildCombinedPreviewOnly()
                    if (!remote) stopCycle(clearFromChatbox = true)
                    if (remote) lastCyclePreviewAdvanceMs = 0L
                    startSelfSyncLoopIfNeeded()
                } else {
                    lastSyncedValues["cycleEnabled"] = remote
                }
            }
            snap.getBoolean("spotifyEnabled")?.let { remote ->
                if (remote != lastSyncedValues["spotifyEnabled"]) {
                    spotifyEnabled = remote
                    savedState["spotifyEnabled"] = remote
                    lastSyncedValues["spotifyEnabled"] = remote
                    rebuildCombinedPreviewOnly()
                    if (!remote) stopNowPlayingSender(clearFromChatbox = true)
                    startSelfSyncLoopIfNeeded()
                } else {
                    lastSyncedValues["spotifyEnabled"] = remote
                }
            }
            snap.getBoolean("timeEnabled")?.let { remote ->
                if (remote != lastSyncedValues["timeEnabled"]) {
                    timeEnabled = remote
                    savedState["timeEnabled"] = remote
                    lastSyncedValues["timeEnabled"] = remote
                    rebuildCombinedPreviewOnly()
                    startSelfSyncLoopIfNeeded()
                } else {
                    lastSyncedValues["timeEnabled"] = remote
                }
            }

            // For string/int fields we fall back to the current local state when
            // there's no baseline yet — without this, admin edits that arrive
            // before performSelfSync's first write are silently dropped.
            snap.getString("afkMessage")?.let { remote ->
                val baseline = (lastSyncedValues["afkMessage"] as? String) ?: afkMessage.trim()
                if (remote.trim() != baseline) {
                    userPreferencesRepository.saveAfkMessage(remote.trim())
                    lastSyncedValues["afkMessage"] = remote.trim()
                } else {
                    lastSyncedValues["afkMessage"] = remote.trim()
                }
            }
            snap.getLong("cycleIntervalSeconds")?.let { remote ->
                val intVal = remote.toInt().coerceAtLeast(2)
                val baseline = (lastSyncedValues["cycleIntervalSeconds"] as? Int) ?: cycleIntervalSeconds
                if (intVal != baseline) {
                    userPreferencesRepository.saveCycleInterval(intVal)
                    lastSyncedValues["cycleIntervalSeconds"] = intVal
                } else {
                    lastSyncedValues["cycleIntervalSeconds"] = intVal
                }
            }
            snap.getString("cycleLinesText")?.let { remote ->
                val baseline = (lastSyncedValues["cycleLinesText"] as? String)
                    ?: cycleLines.joinToString("\n").trim()
                if (remote.trim() != baseline) {
                    userPreferencesRepository.saveCycleMessages(remote.trim())
                    lastSyncedValues["cycleLinesText"] = remote.trim()
                } else {
                    lastSyncedValues["cycleLinesText"] = remote.trim()
                }
            }
            val afkPresetSavers = listOf<suspend (String) -> Unit>(
                { v -> userPreferencesRepository.saveAfkPreset1(v) },
                { v -> userPreferencesRepository.saveAfkPreset2(v) },
                { v -> userPreferencesRepository.saveAfkPreset3(v) }
            )
            for (i in 1..3) {
                val remoteMsg = snap.getString("afkPreset$i") ?: continue
                val baseline = (lastSyncedValues["afkPreset$i"] as? String)
                    ?: afkPresetTexts.getOrNull(i - 1)?.trim().orEmpty()
                if (remoteMsg.trim() != baseline) {
                    afkPresetSavers[i - 1](remoteMsg.trim())
                }
                lastSyncedValues["afkPreset$i"] = remoteMsg.trim()
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
                val baseline = (lastSyncedValues["cyclePreset$i"] as? String)
                    ?: cyclePresetMessages.getOrNull(i - 1)?.trim().orEmpty()
                if (remoteMsg.trim() != baseline) {
                    val interval = cyclePresetIntervals.getOrElse(i - 1) { 10 }
                    presetSavers[i - 1](remoteMsg.trim(), interval, null)
                }
                lastSyncedValues["cyclePreset$i"] = remoteMsg.trim()
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
        rebuildAndMaybeSendCombined(forceSend = true)
        startSelfSyncLoopIfNeeded()
        manageKeepaliveLoop()
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
        // Public build: attach moderation listeners (also drives watcher detection
        // and remote-config snapshots). Admin build skips self-sync entirely.
        attachModerationListenersLoopOnce()

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
            // Timeout after 5s — DataStore already has the latest local
            // state, so this is best-effort optimization, not a gate.
            kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                applyRemoteContentBeforeSync()
            }
            // Cold-open write: this is the "user got online" write, anchored to
            // app open. The hourly heartbeat then fires every hour after this.
            performSelfSync()
            startHourlyHeartbeat()
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

                // Track ad segment count: increment only when transitioning INTO an ad,
                // not on every tick. Reset when ad ends so next ad gets a fresh count.
                val isAdNow = s.specialActive && s.title.trim().lowercase().let { t ->
                    t.contains("advert") || t == "ad" || t.contains("advertisement") || t.contains("sponsored")
                } || (s.specialActive && s.activePackage == "com.spotify.music" && s.title.trim() == "AD")
                if (isAdNow && !lastSpecialWasAd) adSegmentCount++
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

        // YouTube-specific: NowPlayingState already ran stall detection and forced
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
    // KILL switch
    // =========================
    fun killStopAndClear(local: Boolean = false) {
        stopAll(clearFromChatbox = false)
        afkEnabled = false; savedState["afkEnabled"] = false
        cycleEnabled = false; savedState["cycleEnabled"] = false
        spotifyEnabled = false; savedState["spotifyEnabled"] = false
        timeEnabled = false; savedState["timeEnabled"] = false
        persistFeatureSession()
        keepaliveJob?.cancel(); keepaliveJob = null
        if (!isBanned) clearChatbox(local)
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
            time = timeEnabled
        )
    }

    /** Re-enable whatever toggles were active before an unexpected kill and start
     *  their sender loops. No-op on a fresh/intentional launch (restore not armed). */
    private fun restoreFeatureSession() {
        val pending = FeatureSessionStore.pendingRestore(app.applicationContext) ?: return
        if (!pending.anyEnabled) return
        if (isBanned) return
        if (pending.afk) setAfkEnabledFlag(true)
        if (pending.cycle) setCycleEnabledFlag(true)
        if (pending.spotify) setSpotifyEnabledFlag(true)
        if (pending.time) updateTimeEnabled(true)
    }

    // =========================
    // Enable flags
    // =========================
    fun setAfkEnabledFlag(enabled: Boolean) {
        if (isBanned) return
        afkEnabled = enabled
        savedState["afkEnabled"] = enabled
        if (!enabled) stopAfkSender(clearFromChatbox = true)
        else startAfkSender()
        persistFeatureSession()
        rebuildAndMaybeSendCombined(forceSend = true)
        startSelfSyncLoopIfNeeded()
        manageKeepaliveLoop()
    }

    fun setCycleEnabledFlag(enabled: Boolean) {
        if (isBanned) return
        cycleEnabled = enabled
        savedState["cycleEnabled"] = enabled
        if (!enabled) stopCycle(clearFromChatbox = true)
        else { lastCyclePreviewAdvanceMs = 0L; startCycle() }
        persistFeatureSession()
        rebuildAndMaybeSendCombined(forceSend = true)
        startSelfSyncLoopIfNeeded()
        manageKeepaliveLoop()
    }

    fun setSpotifyEnabledFlag(enabled: Boolean) {
        if (isBanned) return
        spotifyEnabled = enabled
        savedState["spotifyEnabled"] = enabled
        if (!enabled) stopNowPlayingSender(clearFromChatbox = true)
        else startNowPlayingSender()
        persistFeatureSession()
        rebuildAndMaybeSendCombined(forceSend = true)
        startSelfSyncLoopIfNeeded()
        manageKeepaliveLoop()
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
        if (!afkEnabled) return
        // AFK sender has its own 12s loop — cancel keepalive to avoid duplication.
        keepaliveJob?.cancel()
        keepaliveJob = null
        afkJob?.cancel()
        afkJob = viewModelScope.launch {
            while (afkEnabled && !isBanned) {
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
        if (!cycleEnabled || msgs.isEmpty()) return

        persistCycleLinesPreserve()
        viewModelScope.launch { userPreferencesRepository.saveCycleInterval(cycleIntervalSeconds) }

        // Cycle has its own interval-based send loop — cancel keepalive to avoid duplication.
        keepaliveJob?.cancel()
        keepaliveJob = null
        cycleJob?.cancel()
        cycleJob = viewModelScope.launch {
            cycleIndex = 0
            while (cycleEnabled && !isBanned) {
                rebuildAndMaybeSendCombined(
                    forceSend = true,
                    local = local,
                    cycleLineOverride = msgs[cycleIndex % msgs.size]
                )
                cycleIndex = (cycleIndex + 1) % msgs.size
                delay(cycleIntervalSeconds.toLong() * 1000L)
            }
        }
        startSelfSyncLoopIfNeeded()
    }

    fun stopCycle(clearFromChatbox: Boolean) {
        cycleJob?.cancel()
        cycleJob = null
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
        if (!spotifyEnabled) return
        // NowPlaying has its own 500ms send loop — cancel keepalive to avoid duplication.
        keepaliveJob?.cancel()
        keepaliveJob = null
        nowPlayingJob?.cancel()
        nowPlayingJob = viewModelScope.launch {
            while (spotifyEnabled && !isBanned) {
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

        val limited = limitWithPriority(rawLines, VRC_MAX_CHARS, VRC_MAX_LINES)

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
            // Prefer the player's real ad index ("Ad 1 of 1"); else fall back to the
            // session counter, coerced to at least 1 so it never shows "Ad 0".
            val label = if (nowPlayingAdInfo.isNotBlank()) {
                "Ad $nowPlayingAdInfo"
            } else {
                "Ad ${adSegmentCount.coerceAtLeast(1)}"
            }
            return listOf(label)
        }

        val isSpotifyDj = activePackage == "com.spotify.music" &&
            nowPlayingDetected &&
            (safeTitle.isBlank() || safeArtist.isBlank())

        val effectiveIsPlaying = if (nowPlayingSpecialActive || isSpotifyDj) true else nowPlayingIsPlaying

        val maxLine = 42
        val twoLineBudget = maxLine * 2

        val combinedName = if (safeArtist.isNotBlank()) "$safeArtist \u2014 $safeTitle" else safeTitle
        val preferNoArtist = safeArtist.isNotBlank() && combinedName.length > twoLineBudget

        val primary = if (preferNoArtist) safeTitle else combinedName
        val line1 = when {
            primary.length <= maxLine -> primary
            safeTitle.length <= maxLine -> safeTitle
            else -> safeTitle.take(maxLine - 1) + "…"
        }.trim()

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

    // \u25C9 = playing (circled dot). \u23F8 = paused (classic double-bar pause symbol).
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

        return when (preset.coerceIn(1, 5)) {
            1 -> {
                val innerSlots = 8
                val idx = (p * (innerSlots - 1)).toInt()
                val inner = CharArray(innerSlots) { '\u2501' }
                inner[idx] = dot
                "\u2661" + inner.concatToString() + "\u2661"
            }
            2 -> {
                val slots = 10
                val idx = (p * (slots - 1)).toInt()
                val bg = CharArray(slots) { '\u2500' }
                bg[idx] = dot
                bg.concatToString()
            }
            3 -> {
                val slots = 10
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
                val slots = 10
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
        val slots = 8
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
