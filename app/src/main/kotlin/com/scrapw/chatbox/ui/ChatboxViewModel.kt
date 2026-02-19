// app/src/main/kotlin/com/scrapw/chatbox/ui/ChatboxViewModel.kt
package com.scrapw.chatbox.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.scrapw.chatbox.BuildConfig
import com.scrapw.chatbox.ChatboxApplication
import com.scrapw.chatbox.NowPlayingState
import com.scrapw.chatbox.data.UserPreferencesRepository
import com.scrapw.chatbox.osc.ChatboxOSC
import com.scrapw.chatbox.ui.mainScreen.ConversationUiState
import com.scrapw.chatbox.ui.mainScreen.Message
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import java.time.Instant
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * ChatboxViewModel (DEVICE-FIRST) — RESTORED + RULES-COMPAT
 *
 * ✅ Canonical doc:
 *   users/{deviceHash}
 *
 * ✅ UID mapping (matches YOUR RULES file):
 *   usersById/{authUid} -> { deviceHash, authUid, appId, adminBuild, updatedAt }
 *
 * Why this fixes the earlier public-branch error:
 * - Public clients are no longer trying to write users/{uid} or usersByUid/{uid},
 *   which your Firestore rules would deny.
 */
class ChatboxViewModel(
    private val app: ChatboxApplication,
    private val userPreferencesRepository: UserPreferencesRepository
) : ViewModel() {

    companion object {
        private lateinit var instance: ChatboxViewModel

        private const val CYCLE_INTERVAL_SECONDS_LOCKED = 10
        private const val MUSIC_REFRESH_SECONDS_LOCKED = 2

        private const val VRC_MAX_CHARS = 144
        private const val VRC_MAX_LINES = 9

        private const val SEND_FLOOR_MS = 2_000L

        private const val META_STABLE_MS = 1_100L
        private const val META_CONFIRM_MOVE_MS = 900L
        private const val META_GIVE_UP_MS = 2_400L
        private const val POS_RESET_CONFIRM_MS = 1_800L

        private const val NO_MOVE_PAUSE_MS = 5_000L
        private const val UI_TICK_MS = 500L

        // Firestore sync throttles (kept from your newer VM)
        private const val SELF_SYNC_MIN_INTERVAL_MS = 12_000L
        private const val SELF_SYNC_FORCE_INTERVAL_MS = 90_000L

        // SharedPrefs (must match AdminScreen + ChatboxApp/MainActivity)
        private const val REMOTE_PREFS_FILE = "vrca_remote"
        private const val PREF_DEVICE_ID_HASH = "device_id_hash"
        private const val PREF_AUTH_UID = "auth_uid"

        // Collections (MUST MATCH YOUR RULES)
        private const val COL_USERS = "users"          // users/{deviceHash}
        private const val COL_USERS_BY_ID = "usersById" // usersById/{uid}

        @MainThread
        fun isInstanceInitialized(): Boolean = ::instance.isInitialized

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as ChatboxApplication)
                instance = ChatboxViewModel(
                    app = application,
                    userPreferencesRepository = application.userPreferencesRepository
                )
                Log.d("ChatboxViewModel", "Init")
                instance
            }
        }

        @MainThread
        fun getInstance(): ChatboxViewModel {
            if (!::instance.isInitialized) throw Exception("ChatboxViewModel is not initialized!")
            return instance
        }
    }

    override fun onCleared() {
        uiTickJob?.cancel()
        selfSyncJob?.cancel()
        stopAll(clearFromChatbox = false)
        super.onCleared()
    }

    // =========================
    // Firebase
    // =========================
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private var selfSyncJob: Job? = null
    private var lastSelfSyncAtMs: Long = 0L
    private var lastSelfSyncFingerprint: String = ""
    private var lastSelfSyncError: String = ""

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
        val cycleClean = cycleLines.map { it.trim() }.take(10)
        val afkP = (1..3).joinToString("|") { getAfkPresetPreview(it) }
        val cycP = (1..5).joinToString("|") { getCyclePresetPreview(it) }

        val npTitle = lastNowPlayingTitle.trim()
        val npArtist = lastNowPlayingArtist.trim()

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
            "npDet=$nowPlayingDetected",
            "npPlay=$nowPlayingIsPlaying",
            "npT=$npTitle",
            "npA=$npArtist",
            "prev=${combinedPreviewText.trim()}",
            "afkP=$afkP",
            "cycP=$cycP"
        ).joinToString("||")
    }

    /**
     * ✅ Full snapshot for users/{deviceHash}
     * Must stay inside your Firestore rules' selfMutableKeys() list.
     */
    private fun buildUserSnapshot(authUid: String, deviceHash: String): Map<String, Any> {
        val cycleClean = cycleLines.map { it.trim() }.filter { it.isNotEmpty() }.take(10)

        val data = linkedMapOf<String, Any>(
            // identity/debug (rules require uid/authUid to match request.auth.uid)
            "docId" to deviceHash,
            "docIdType" to "deviceHash",
            "authUid" to authUid,
            "uid" to authUid,              // legacy alias (admin searches)
            "currentUid" to authUid,       // your rules mention this; keep it consistent
            "deviceHash" to deviceHash,

            // build
            "appId" to BuildConfig.APPLICATION_ID,
            "adminBuild" to BuildConfig.IS_ADMIN_BUILD,
            "versionName" to BuildConfig.VERSION_NAME,
            "versionCode" to BuildConfig.VERSION_CODE,

            // presence
            "lastSeenAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),

            // live state
            "afkEnabled" to afkEnabled,
            "afkMessage" to afkMessage.trim(),

            "cycleEnabled" to cycleEnabled,
            "cycleIntervalSeconds" to cycleIntervalSeconds,
            "cycleLines" to cycleClean,
            "cycleLinesText" to cycleClean.joinToString("\n"),

            "spotifyEnabled" to spotifyEnabled,
            "spotifyDemoEnabled" to spotifyDemoEnabled,
            "spotifyPreset" to spotifyPreset,

            "nowPlayingDetected" to nowPlayingDetected,
            "nowPlayingIsPlaying" to nowPlayingIsPlaying,
            "nowPlayingTitle" to lastNowPlayingTitle.takeIf { it != "(blank)" }?.trim().orEmpty(),
            "nowPlayingArtist" to lastNowPlayingArtist.takeIf { it != "(blank)" }?.trim().orEmpty(),
            "activePackage" to activePackage,

            // UI preview
            "combinedPreviewText" to combinedPreviewText.trim(),
            "cycleTrimWarning" to cycleTrimWarning.trim()
        )

        // presets (admin visibility)
        data["afkPreset1"] = getAfkPresetPreview(1)
        data["afkPreset2"] = getAfkPresetPreview(2)
        data["afkPreset3"] = getAfkPresetPreview(3)

        data["cyclePreset1"] = cyclePresetMessages.getOrNull(0)?.trim().orEmpty()
        data["cyclePreset2"] = cyclePresetMessages.getOrNull(1)?.trim().orEmpty()
        data["cyclePreset3"] = cyclePresetMessages.getOrNull(2)?.trim().orEmpty()
        data["cyclePreset4"] = cyclePresetMessages.getOrNull(3)?.trim().orEmpty()
        data["cyclePreset5"] = cyclePresetMessages.getOrNull(4)?.trim().orEmpty()

        return data
    }

    /**
     * ✅ UID mapping per YOUR RULES:
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

    private fun startSelfSyncLoopIfNeeded() {
        if (selfSyncJob != null) return

        selfSyncJob = viewModelScope.launch {
            while (true) {
                runCatching {
                    val authUid = ensureAnonAuth() ?: return@runCatching

                    val deviceHash = readDeviceHashFromPrefs()
                    if (!isValidDeviceHash(deviceHash)) {
                        // Do NOT fall back to authUid here, because your rules are deviceHash-canonical.
                        lastSelfSyncError = "deviceHash missing/invalid. Ensure MainActivity/ChatboxApp sets prefs key device_id_hash."
                        return@runCatching
                    }

                    val now = System.currentTimeMillis()
                    val fp = computeSelfFingerprint(authUid, deviceHash)

                    val changed = fp != lastSelfSyncFingerprint
                    val dueByForce = (now - lastSelfSyncAtMs) >= SELF_SYNC_FORCE_INTERVAL_MS
                    val dueByChange = changed && (now - lastSelfSyncAtMs) >= SELF_SYNC_MIN_INTERVAL_MS
                    if (!dueByForce && !dueByChange) return@runCatching

                    // ✅ Canonical write: users/{deviceHash}
                    db.collection(COL_USERS).document(deviceHash)
                        .set(buildUserSnapshot(authUid, deviceHash), SetOptions.merge())
                        .await()

                    // ✅ UID mapping: usersById/{uid}
                    runCatching {
                        db.collection(COL_USERS_BY_ID).document(authUid)
                            .set(buildUsersByIdLink(authUid, deviceHash), SetOptions.merge())
                            .await()
                    }

                    lastSelfSyncAtMs = now
                    lastSelfSyncFingerprint = fp
                    lastSelfSyncError = ""
                }.onFailure { e ->
                    lastSelfSyncError = (e.message ?: e.toString()).take(4000)
                }

                delay(2_000L)
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
        val osc = if (!local) remoteChatboxOSC else localChatboxOSC
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

    private val remoteChatboxOSC = ChatboxOSC(
        ipAddress = runBlocking { userPreferencesRepository.ipAddress.first() },
        port = 9000
    )

    private val localChatboxOSC = ChatboxOSC(
        ipAddress = "localhost",
        port = 9000
    )

    fun onIpAddressChange(ip: String) {
        userInputIpState.value = ip
    }

    fun ipAddressApply(address: String) {
        remoteChatboxOSC.ipAddress = address
        viewModelScope.launch { userPreferencesRepository.saveIpAddress(address) }
        startSelfSyncLoopIfNeeded()
    }

    fun portApply(port: Int) {
        remoteChatboxOSC.port = port
        viewModelScope.launch { userPreferencesRepository.savePort(port) }
        startSelfSyncLoopIfNeeded()
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
        val osc = if (!local) remoteChatboxOSC else localChatboxOSC
        messageText.value = message
        stashedMessage = message.text

        if (messengerUiState.value.isRealtimeMsg) {
            osc.sendRealtimeMessage(message.text)
        } else if (messengerUiState.value.isTypingIndicator) {
            osc.typing = message.text.isNotEmpty()
        }
    }

    fun sendMessage(local: Boolean = false) {
        val osc = if (!local) remoteChatboxOSC else localChatboxOSC

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

    // =========================
    // AFK
    // =========================
    var afkEnabled by mutableStateOf(false)
        private set

    var afkMessage by mutableStateOf("")
        private set

    private val afkForcedIntervalSeconds = 12
    private var afkJob: Job? = null

    // =========================
    // Cycle
    // =========================
    var cycleEnabled by mutableStateOf(false)
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
    var spotifyEnabled by mutableStateOf(false)
        private set
    var spotifyDemoEnabled by mutableStateOf(false)
        private set

    var spotifyPreset by mutableStateOf(1)
        private set

    var musicRefreshSeconds by mutableStateOf(MUSIC_REFRESH_SECONDS_LOCKED)
        private set

    private var nowPlayingJob: Job? = null

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
        private set

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
        // Start periodic self sync (rules-compatible).
        startSelfSyncLoopIfNeeded()

        viewModelScope.launch {
            userPreferencesRepository.afkMessage.collect {
                afkMessage = it
                rebuildCombinedPreviewOnly()
                startSelfSyncLoopIfNeeded()
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.cycleEnabled.collect {
                cycleEnabled = it
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
                cycleIntervalSeconds = CYCLE_INTERVAL_SECONDS_LOCKED
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

        uiTickJob?.cancel()
        uiTickJob = viewModelScope.launch {
            while (true) {
                tickNowPlayingMovement()
                tickCyclePreviewOnly()
                nowPlayingIsPlaying = computeDisplayedPlaying()
                rebuildCombinedPreviewOnly()
                startSelfSyncLoopIfNeeded()
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
                val trackChanged = key != lastTrackKeyForInference && (s.title.isNotBlank() || s.artist.isNotBlank())
                if (trackChanged) {
                    lastTrackKeyForInference = key
                    lastMovementAtMs = System.currentTimeMillis()
                    pauseCandidateSinceMs = 0L
                    inferredIsPlaying = true

                    lastEffectivePosForTick = nowPlayingPositionMs.coerceAtLeast(0L)
                    lastEffectiveTickAtMs = System.currentTimeMillis()
                }

                stabilizeNowPlayingMetadata(
                    rawTitle = s.title,
                    rawArtist = s.artist,
                    rawDurationMs = s.durationMs,
                    positionMs = s.positionMs,
                    reportedIsPlaying = s.isPlaying,
                    inferredIsPlaying = inferredIsPlaying
                )

                nowPlayingIsPlaying = computeDisplayedPlaying()
                rebuildCombinedPreviewOnly()
                startSelfSyncLoopIfNeeded()
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

        val intervalMs = CYCLE_INTERVAL_SECONDS_LOCKED.toLong() * 1000L
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

        if (moved) {
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

        val hardPause =
            !nowPlayingReportedIsPlaying &&
                pauseCandidateSinceMs > 0L &&
                (now - pauseCandidateSinceMs) >= 1_200L

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
        clearChatbox(local)
        rebuildCombinedPreviewOnly(forceClearIfAllOff = true)
        startSelfSyncLoopIfNeeded()
    }

    // =========================
    // Enable flags
    // =========================
    fun setAfkEnabledFlag(enabled: Boolean) {
        afkEnabled = enabled
        rebuildCombinedPreviewOnly()
        if (!enabled) stopAfkSender(clearFromChatbox = true)
        startSelfSyncLoopIfNeeded()
    }

    fun setCycleEnabledFlag(enabled: Boolean) {
        cycleEnabled = enabled
        viewModelScope.launch { userPreferencesRepository.saveCycleEnabled(enabled) }
        rebuildCombinedPreviewOnly()
        if (!enabled) stopCycle(clearFromChatbox = true)
        if (enabled) lastCyclePreviewAdvanceMs = 0L
        startSelfSyncLoopIfNeeded()
    }

    fun setSpotifyEnabledFlag(enabled: Boolean) {
        spotifyEnabled = enabled
        rebuildCombinedPreviewOnly()
        if (!enabled) stopNowPlayingSender(clearFromChatbox = true)
        startSelfSyncLoopIfNeeded()
    }

    fun setSpotifyDemoFlag(enabled: Boolean) {
        spotifyDemoEnabled = enabled
        rebuildCombinedPreviewOnly()
        startSelfSyncLoopIfNeeded()
    }

    fun updateSpotifyPreset(preset: Int) {
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
        if (cycleLines.size >= 10) return
        cycleLines.add("")
        persistCycleLinesPreserve()
        rebuildCombinedPreviewOnly()
    }

    fun removeCycleLine(index: Int) {
        if (index !in cycleLines.indices) return
        cycleLines.removeAt(index)
        persistCycleLinesPreserve()
        rebuildCombinedPreviewOnly()
    }

    fun updateCycleLine(index: Int, value: String) {
        if (index !in cycleLines.indices) return
        cycleLines[index] = value
        persistCycleLinesPreserve()
        rebuildCombinedPreviewOnly()
    }

    fun clearCycleLines() {
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
        val txt = when (slot.coerceIn(1, 3)) {
            1 -> userPreferencesRepository.afkPreset1.first()
            2 -> userPreferencesRepository.afkPreset2.first()
            else -> userPreferencesRepository.afkPreset3.first()
        }
        updateAfkText(txt)
        startSelfSyncLoopIfNeeded()
    }

    suspend fun saveCyclePreset(slot: Int, lines: List<String>) {
        val s = slot.coerceIn(1, 5)
        val idx = s - 1

        val messages = lines.map { it.trim() }.filter { it.isNotEmpty() }.take(10).joinToString("\n")
        val interval = CYCLE_INTERVAL_SECONDS_LOCKED

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
        val s = slot.coerceIn(1, 5)
        val (messages, _) = when (s) {
            1 -> userPreferencesRepository.cyclePreset1Messages.first() to userPreferencesRepository.cyclePreset1Interval.first()
            2 -> userPreferencesRepository.cyclePreset2Messages.first() to userPreferencesRepository.cyclePreset2Interval.first()
            3 -> userPreferencesRepository.cyclePreset3Messages.first() to userPreferencesRepository.cyclePreset3Interval.first()
            4 -> userPreferencesRepository.cyclePreset4Messages.first() to userPreferencesRepository.cyclePreset4Interval.first()
            else -> userPreferencesRepository.cyclePreset5Messages.first() to userPreferencesRepository.cyclePreset5Interval.first()
        }

        cycleIntervalSeconds = CYCLE_INTERVAL_SECONDS_LOCKED
        viewModelScope.launch { userPreferencesRepository.saveCycleInterval(cycleIntervalSeconds) }

        setCycleLinesFromTextPreserve(messages)
        persistCycleLinesPreserve()
        startSelfSyncLoopIfNeeded()
    }

    // =========================
    // AFK sender
    // =========================
    fun startAfkSender(local: Boolean = false) {
        if (!afkEnabled) return
        afkJob?.cancel()
        afkJob = viewModelScope.launch {
            while (afkEnabled) {
                rebuildAndMaybeSendCombined(forceSend = true, local = local)
                delay(afkForcedIntervalSeconds.toLong() * 1000L)
            }
        }
        startSelfSyncLoopIfNeeded()
    }

    fun stopAfkSender(clearFromChatbox: Boolean) {
        afkJob?.cancel()
        afkJob = null
        if (clearFromChatbox) rebuildAndMaybeSendCombined(forceSend = true, forceClearIfAllOff = true)
        startSelfSyncLoopIfNeeded()
    }

    fun sendAfkNow(local: Boolean = false) {
        rebuildAndMaybeSendCombined(forceSend = true, local = local)
        startSelfSyncLoopIfNeeded()
    }

    // =========================
    // Cycle sender
    // =========================
    fun startCycle(local: Boolean = false) {
        val msgs = cycleLines.map { it.trim() }.filter { it.isNotEmpty() }.take(10)
        if (!cycleEnabled || msgs.isEmpty()) return

        viewModelScope.launch { userPreferencesRepository.saveCycleEnabled(true) }
        persistCycleLinesPreserve()
        cycleIntervalSeconds = CYCLE_INTERVAL_SECONDS_LOCKED
        viewModelScope.launch { userPreferencesRepository.saveCycleInterval(cycleIntervalSeconds) }

        cycleJob?.cancel()
        cycleJob = viewModelScope.launch {
            cycleIndex = 0
            while (cycleEnabled) {
                rebuildAndMaybeSendCombined(
                    forceSend = true,
                    local = local,
                    cycleLineOverride = msgs[cycleIndex % msgs.size]
                )
                cycleIndex = (cycleIndex + 1) % msgs.size
                delay(CYCLE_INTERVAL_SECONDS_LOCKED.toLong() * 1000L)
            }
        }
        startSelfSyncLoopIfNeeded()
    }

    fun stopCycle(clearFromChatbox: Boolean) {
        cycleJob?.cancel()
        cycleJob = null
        if (clearFromChatbox) rebuildAndMaybeSendCombined(forceSend = true, forceClearIfAllOff = true)
        lastCyclePreviewAdvanceMs = 0L
        startSelfSyncLoopIfNeeded()
    }

    // =========================
    // Now Playing sender
    // =========================
    fun startNowPlayingSender(local: Boolean = false) {
        if (!spotifyEnabled) return
        nowPlayingJob?.cancel()
        nowPlayingJob = viewModelScope.launch {
            while (spotifyEnabled) {
                val now = System.currentTimeMillis()
                val nextAllowed = lastCombinedSendMs + SEND_FLOOR_MS
                val waitMs = max(0L, nextAllowed - now)
                if (waitMs > 0L) delay(waitMs + 25L)

                rebuildAndMaybeSendCombined(forceSend = true, local = local)
                delay(10L)
            }
        }
        startSelfSyncLoopIfNeeded()
    }

    fun stopNowPlayingSender(clearFromChatbox: Boolean) {
        nowPlayingJob?.cancel()
        nowPlayingJob = null
        if (clearFromChatbox) rebuildAndMaybeSendCombined(forceSend = true, forceClearIfAllOff = true)
        startSelfSyncLoopIfNeeded()
    }

    fun sendNowPlayingOnce(local: Boolean = false) {
        rebuildAndMaybeSendCombined(forceSend = true, local = local)
        startSelfSyncLoopIfNeeded()
    }

    fun stopAll(clearFromChatbox: Boolean) {
        stopCycle(clearFromChatbox = false)
        stopNowPlayingSender(clearFromChatbox = false)
        stopAfkSender(clearFromChatbox = false)
        if (clearFromChatbox) clearChatbox()
        startSelfSyncLoopIfNeeded()
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

        sendToVrchatRaw(combined, local, addToConversation = false)
        lastCombinedSendMs = nowMs
    }

    private fun buildCombinedText(cycleLineOverride: String?): String {
        cycleTrimWarning = ""

        val afkLine = if (afkEnabled && afkMessage.trim().isNotEmpty()) afkMessage.trim() else ""
        val cycleLine = if (cycleEnabled) (cycleLineOverride ?: currentCycleLinePreview()) else ""
        val musicLines = if (spotifyEnabled) buildNowPlayingLines() else emptyList()

        debugLastAfkOsc = afkLine
        debugLastCycleOsc = cycleLine
        debugLastMusicOsc = musicLines.joinToString("\n")

        val rawLines = mutableListOf<LineWithPriority>()
        if (afkLine.isNotBlank()) rawLines += LineWithPriority(text = afkLine, priority = Priority.AFK)
        if (cycleLine.isNotBlank()) rawLines += LineWithPriority(text = cycleLine, priority = Priority.CYCLE)
        for (m in musicLines) if (m.isNotBlank()) rawLines += LineWithPriority(text = m, priority = Priority.MUSIC)

        val limited = limitWithPriority(rawLines, VRC_MAX_CHARS, VRC_MAX_LINES)

        if (limited.cycleWasModifiedToPreserveMusic) {
            cycleTrimWarning = "Cycle was trimmed to preserve Now Playing (VRChat limits)."
        }

        val combined = limited.text
        debugLastCombinedOsc = combined
        return combined
    }

    private fun clearChatbox(local: Boolean = false) {
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

        val maxLine = 42
        val twoLineBudget = maxLine * 2

        val combinedName = if (safeArtist.isNotBlank()) "$safeArtist — $safeTitle" else safeTitle
        val preferNoArtist = safeArtist.isNotBlank() && combinedName.length > twoLineBudget

        val primary = if (preferNoArtist) safeTitle else combinedName
        val line1 = when {
            primary.length <= maxLine -> primary
            safeTitle.length <= maxLine -> safeTitle
            else -> safeTitle.take(maxLine - 1) + "…"
        }.trim()

        val dur = if (spotifyDemoEnabled && !nowPlayingDetected) 205_000L else nowPlayingDurationMs
        val posSnapshot = if (spotifyDemoEnabled && !nowPlayingDetected) 78_000L else nowPlayingPositionMs

        val pos = if (nowPlayingIsPlaying && dur > 0L) {
            val elapsed = SystemClock.elapsedRealtime() - nowPlayingPositionUpdateTimeMs
            val adj = (elapsed * nowPlayingSpeed).toLong()
            (posSnapshot + max(0L, adj)).coerceAtMost(dur)
        } else posSnapshot

        val bar = renderProgressBar(spotifyPreset, pos, max(1L, dur), nowPlayingIsPlaying)
        val time = "${fmtTime(pos)} / ${fmtTime(max(1L, dur))}"
        val status = if (!nowPlayingIsPlaying) "Paused" else ""

        val line2 = listOf(bar, time).joinToString(" ").trim()
        val line3 = status.takeIf { it.isNotBlank() }

        return listOfNotNull(line1.takeIf { it.isNotBlank() }, line2.takeIf { it.isNotBlank() }, line3)
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
            val trimmed = if (newLen >= 2) original.take(newLen - 1) + "…" else "…"
            if (cleaned[index].priority == Priority.CYCLE) cycleModifiedForMusic = true
            cleaned[index] = cleaned[index].copy(text = trimmed)
        }

        var len = totalLen(cleaned)
        while (len > maxChars && cleaned.isNotEmpty()) {
            val excess = len - maxChars

            val cycleIdx = cleaned.indexOfLast { it.priority == Priority.CYCLE }
            val musicIdx = cleaned.indexOfLast { it.priority == Priority.MUSIC }
            val afkIdx = cleaned.indexOfLast { it.priority == Priority.AFK }

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
    private fun renderProgressBar(preset: Int, posMs: Long, durMs: Long, isPlaying: Boolean): String {
        val duration = max(1L, durMs)
        val p = min(1f, max(0f, posMs.toFloat() / duration.toFloat()))

        return when (preset.coerceIn(1, 5)) {
            1 -> {
                val innerSlots = 8
                val idx = (p * (innerSlots - 1)).toInt()
                val inner = CharArray(innerSlots) { '━' }
                inner[idx] = '◉'
                "♡" + inner.concatToString() + "♡"
            }
            2 -> {
                val slots = 10
                val idx = (p * (slots - 1)).toInt()
                val bg = CharArray(slots) { '─' }
                bg[idx] = '◉'
                bg.concatToString()
            }
            3 -> {
                val slots = 10
                val idx = (p * (slots - 1)).toInt()
                val bg = CharArray(slots) { '⟡' }
                bg[idx] = '◉'
                bg.concatToString()
            }
            4 -> renderSoundwaveBar(p, posMs, isPlaying)
            else -> {
                val slots = 10
                val idx = (p * (slots - 1)).toInt()
                val out = CharArray(slots) { i ->
                    when {
                        i < idx -> '▣'
                        i == idx -> '◉'
                        else -> '▢'
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

    private fun renderSoundwaveBar(progress01: Float, posMs: Long, isPlaying: Boolean): String {
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
                out.append('[').append('▣').append(']')
            } else out.append(chars[i])
        }
        return out.toString()
    }

    private fun ampToChar(a: Int): Char = when (a.coerceIn(1, 8)) {
        1 -> '▁'
        2 -> '▂'
        3 -> '▃'
        4 -> '▄'
        5 -> '▅'
        6 -> '▆'
        7 -> '▇'
        else -> '█'
    }

    private fun fmtTime(ms: Long): String {
        val totalSec = max(0L, ms) / 1000L
        val m = totalSec / 60L
        val s = (totalSec % 60L).toInt()
        return "${m}:${s.toString().padStart(2, '0')}"
    }

    private fun sendToVrchatRaw(text: String, local: Boolean, addToConversation: Boolean) {
        val osc = if (!local) remoteChatboxOSC else localChatboxOSC
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
        inferredIsPlaying: Boolean
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
