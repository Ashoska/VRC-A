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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
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
import com.google.firebase.firestore.ListenerRegistration
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
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * ChatboxViewModel (DEVICE-FIRST) \u2014 RESTORED + RULES-COMPAT
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
class ChatboxViewModel(
    private val app: ChatboxApplication,
    val userPreferencesRepository: UserPreferencesRepository
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

        // Firestore sync throttles
        private const val SELF_SYNC_MIN_INTERVAL_MS = 12_000L
        private const val SELF_SYNC_FORCE_INTERVAL_MS = 10_000L

        // Moderation attach retry
        private const val MOD_ATTACH_RETRY_MS = 1_250L

        // SharedPrefs (must match AdminScreen + ChatboxApp/MainActivity)
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
        moderationAttachJob?.cancel()
        moderationUserReg?.remove()
        moderationDeviceReg?.remove()
        // Do NOT reset cycle/time enabled here.
        // onCleared is called on backgrounding too, not just app death,
        // which caused the state to be wiped before the next DataStore read.
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
            "cycP=$cycP",
            "timeE=$timeEnabled",
            "timeM=$timeMode"
        ).joinToString("||")
    }

    /**
     * \u2705 Full snapshot for users/{deviceHash}
     * Must stay inside your Firestore rules' selfMutableKeys() list.
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

            "nowPlayingDetected" to nowPlayingDetected,
            "nowPlayingIsPlaying" to nowPlayingIsPlaying,
            "nowPlayingTitle" to lastNowPlayingTitle.takeIf { it != "(blank)" }?.trim().orEmpty(),
            "nowPlayingArtist" to lastNowPlayingArtist.takeIf { it != "(blank)" }?.trim().orEmpty(),
            "activePackage" to activePackage,

            "combinedPreviewText" to combinedPreviewText.trim(),
            "cycleTrimWarning" to cycleTrimWarning.trim(),

            "timeEnabled" to timeEnabled,
            "timeMode" to timeMode,
            "lastReportedTime" to if (timeEnabled) currentTimeString() else "",
            "lastTimeUpdateAt" to FieldValue.serverTimestamp()
        )

        data["afkPreset1"] = getAfkPresetPreview(1)
        data["afkPreset2"] = getAfkPresetPreview(2)
        data["afkPreset3"] = getAfkPresetPreview(3)
        data["afkPreset1Name"] = pinnedPresetNames.getOrElse(0) { "Preset 1" }
        data["afkPreset2Name"] = pinnedPresetNames.getOrElse(1) { "Preset 2" }
        data["afkPreset3Name"] = pinnedPresetNames.getOrElse(2) { "Preset 3" }

        data["cyclePreset1"] = cyclePresetMessages.getOrNull(0)?.trim().orEmpty()
        data["cyclePreset2"] = cyclePresetMessages.getOrNull(1)?.trim().orEmpty()
        data["cyclePreset3"] = cyclePresetMessages.getOrNull(2)?.trim().orEmpty()
        data["cyclePreset4"] = cyclePresetMessages.getOrNull(3)?.trim().orEmpty()
        data["cyclePreset5"] = cyclePresetMessages.getOrNull(4)?.trim().orEmpty()
        data["cyclePreset1Name"] = cyclePresetNames.getOrElse(0) { "Preset 1" }
        data["cyclePreset2Name"] = cyclePresetNames.getOrElse(1) { "Preset 2" }
        data["cyclePreset3Name"] = cyclePresetNames.getOrElse(2) { "Preset 3" }
        data["cyclePreset4Name"] = cyclePresetNames.getOrElse(3) { "Preset 4" }
        data["cyclePreset5Name"] = cyclePresetNames.getOrElse(4) { "Preset 5" }

        // Multi-IP slots
        val activeSlot = runCatching {
            kotlinx.coroutines.runBlocking { userPreferencesRepository.activeIpSlot.first() }
        }.getOrDefault(1)
        data["activeIpSlot"] = activeSlot

        return data
    }

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
     * IMPORTANT: Admin build does NOT self-sync to avoid UID tug-of-war with Public build.
     */
    private fun startSelfSyncLoopIfNeeded() {
        if (BuildConfig.IS_ADMIN_BUILD) return
        if (selfSyncJob != null) return

        selfSyncJob = viewModelScope.launch {
            while (true) {
                runCatching {
                    val authUid = ensureAnonAuth() ?: return@runCatching

                    val deviceHash = readDeviceHashFromPrefs()
                    if (!isValidDeviceHash(deviceHash)) {
                        lastSelfSyncError =
                            "deviceHash missing/invalid. Ensure app sets prefs key device_id_hash."
                        return@runCatching
                    }

                    val now = System.currentTimeMillis()
                    val fp = computeSelfFingerprint(authUid, deviceHash)

                    val changed = fp != lastSelfSyncFingerprint
                    val dueByForce = (now - lastSelfSyncAtMs) >= SELF_SYNC_FORCE_INTERVAL_MS
                    val dueByChange = changed && (now - lastSelfSyncAtMs) >= SELF_SYNC_MIN_INTERVAL_MS
                    if (!dueByForce && !dueByChange) return@runCatching

                    db.collection(COL_USERS).document(deviceHash)
                        .set(buildUserSnapshot(authUid, deviceHash), SetOptions.merge())
                        .await()

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

    val isBanned: Boolean
        get() = uidBanned || deviceBanned

    private var lastBanEffective: Boolean = false

    private fun enforceIfBannedChanged() {
        val nowBanned = isBanned
        if (nowBanned == lastBanEffective) return
        lastBanEffective = nowBanned

        if (nowBanned) {
            // Hard stop all running jobs immediately. Do NOT clear chatbox while banned (no OSC allowed).
            stopAll(clearFromChatbox = false)

            // Ensure typing indicator is off locally (no OSC send; just state safety).
            remoteChatboxOSC.typing = false
            localChatboxOSC.typing = false
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
        attachModerationListenersLoopOnce()
    }

    fun portApply(port: Int) {
        remoteChatboxOSC.port = port
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
        val osc = if (!local) remoteChatboxOSC else localChatboxOSC
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

    // True when the active media package is in a DJ/Ad/special-window state.
    // When true we suppress metadata updates and force effectiveIsPlaying=true.
    var nowPlayingSpecialActive by mutableStateOf(false)

    // Tracks how many ad segments have been detected this session so the
    // chatbox can show "Ad 1", "Ad 2" etc. without leaking ad brand names.
    private var adSegmentCount by mutableIntStateOf(0)
    private var lastSpecialWasAd = false
        private set

    // =========================
    // Time feature
    // =========================
    var timeEnabled by mutableStateOf(false)
        private set

    // Stored as: "Device", "UTC", "UTC+1".."UTC+14", "UTC-1".."UTC-12"
    var timeMode by mutableStateOf("Device")
        private set

    fun updateTimeEnabled(enabled: Boolean) {
        if (isBanned) return
        timeEnabled = enabled
        viewModelScope.launch { userPreferencesRepository.saveTimeEnabled(enabled) }
        rebuildCombinedPreviewOnly()
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
    // Valid component names: "NowPlaying", "AFK", "Cycle"
    // Order = top-to-bottom in the chatbox output. Cut-off priority is unchanged.
    // =========================
    val DEFAULT_CARD_ORDER = listOf("Time", "AFK", "Cycle", "NowPlaying")

    var cardOrder by mutableStateOf(listOf("Time", "AFK", "Cycle", "NowPlaying"))
        private set

    fun updateCardOrder(order: List<String>) {
        if (isBanned) return
        val valid = order.filter { it in DEFAULT_CARD_ORDER || it.startsWith("Divider_") }.distinct()
        val full = valid + DEFAULT_CARD_ORDER.filter { it !in valid }
        cardOrder = full
        viewModelScope.launch { userPreferencesRepository.saveCardOrder(full) }
        rebuildCombinedPreviewOnly()
        startSelfSyncLoopIfNeeded()
    }

    fun resetCardOrder() {
        updateCardOrder(DEFAULT_CARD_ORDER)
    }

    /** Add a new divider at the given position in cardOrder (after the component at insertAfterIdx) */
    fun addDivider(insertAfterIdx: Int, text: String = "Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬") {
        if (isBanned) return
        // Find a unique divider ID
        val existingIds = cardOrder.filter { it.startsWith("Divider_") }.map {
            it.removePrefix("Divider_").toIntOrNull() ?: 0
        }.toSet()
        val newId = (1..99).first { it !in existingIds }
        val divId = "Divider_$newId"

        // Save text config
        dividerTexts[divId] = text
        saveDividerConfig()

        // Insert into order
        val newOrder = cardOrder.toMutableList()
        val insertAt = (insertAfterIdx + 1).coerceAtMost(newOrder.size)
        newOrder.add(insertAt, divId)
        updateCardOrder(newOrder)
    }

    fun removeDivider(divId: String) {
        if (isBanned) return
        dividerTexts.remove(divId)
        saveDividerConfig()
        updateCardOrder(cardOrder.filter { it != divId })
    }

    fun updateDividerText(divId: String, text: String) {
        if (isBanned) return
        dividerTexts[divId] = text
        saveDividerConfig()
        rebuildCombinedPreviewOnly()
    }

    private fun saveDividerConfig() {
        val arr = org.json.JSONArray()
        dividerTexts.entries.forEach { entry ->
            arr.put(org.json.JSONObject()
                .put("id", entry.key as String)
                .put("text", entry.value as String))
        }
        viewModelScope.launch { userPreferencesRepository.saveDividerConfig(arr.toString()) }
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
    /** Set when a divider line was auto-removed to fit the char limit */
    var dividerRemovedWarning by mutableStateOf(false)

    // Divider configs: map of "Divider_N" -> display text, loaded from DataStore
    // e.g. {"Divider_1": "Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬", "Divider_2": "Ã¢â‚¬Â¢ Ã¢â‚¬Â¢ Ã¢â‚¬Â¢"}
    val dividerTexts = mutableStateMapOf<String, String>()
    private val afkPresetTexts = mutableStateListOf("", "", "")
    val pinnedPresetNames = mutableStateListOf("Preset 1", "Preset 2", "Preset 3")
    val cyclePresetNames  = mutableStateListOf("Preset 1", "Preset 2", "Preset 3", "Preset 4", "Preset 5")
    private val cyclePresetMessages = mutableStateListOf("", "", "", "", "")
    private val cyclePresetIntervals = mutableStateListOf(
        CYCLE_INTERVAL_SECONDS_LOCKED,
        CYCLE_INTERVAL_SECONDS_LOCKED,
        CYCLE_INTERVAL_SECONDS_LOCKED,
        CYCLE_INTERVAL_SECONDS_LOCKED,
        CYCLE_INTERVAL_SECONDS_LOCKED
    )

    init {
        // Always reset cycle to off on app start.
        // Done in init (not onCleared) because onCleared fires on backgrounding too,
        // causing a race where false is written mid-session. init runs exactly once
        // per ViewModel creation = exactly once per app start.
        viewModelScope.launch { userPreferencesRepository.saveCycleEnabled(false) }

        // Public build: start periodic self sync (rules-compatible).
        // Admin build: startSelfSyncLoopIfNeeded() is a no-op (prevents UID tug-of-war).
        startSelfSyncLoopIfNeeded()

        // Public build: attach moderation listeners once deviceHash + auth are available.
        attachModerationListenersLoopOnce()

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
                cycleIntervalSeconds = it.coerceAtLeast(2)
                rebuildCombinedPreviewOnly()
                startSelfSyncLoopIfNeeded()
            }
        }

        viewModelScope.launch { userPreferencesRepository.afkPreset1.collect { afkPresetTexts[0] = it; startSelfSyncLoopIfNeeded() } }
        viewModelScope.launch { userPreferencesRepository.afkPreset2.collect { afkPresetTexts[1] = it; startSelfSyncLoopIfNeeded() } }
        viewModelScope.launch { userPreferencesRepository.afkPreset3.collect { afkPresetTexts[2] = it; startSelfSyncLoopIfNeeded() } }
        viewModelScope.launch { userPreferencesRepository.pinnedPreset1Name.collect { pinnedPresetNames[0] = it } }
        viewModelScope.launch { userPreferencesRepository.pinnedPreset2Name.collect { pinnedPresetNames[1] = it } }
        viewModelScope.launch { userPreferencesRepository.pinnedPreset3Name.collect { pinnedPresetNames[2] = it } }
        viewModelScope.launch { userPreferencesRepository.cyclePreset1Name.collect { cyclePresetNames[0] = it } }
        viewModelScope.launch { userPreferencesRepository.cyclePreset2Name.collect { cyclePresetNames[1] = it } }
        viewModelScope.launch { userPreferencesRepository.cyclePreset3Name.collect { cyclePresetNames[2] = it } }
        viewModelScope.launch { userPreferencesRepository.cyclePreset4Name.collect { cyclePresetNames[3] = it } }
        viewModelScope.launch { userPreferencesRepository.cyclePreset5Name.collect { cyclePresetNames[4] = it } }

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
                // Allow Divider_N entries alongside standard components
                val validComponents = DEFAULT_CARD_ORDER.toSet()
                val valid = saved.filter { it in validComponents || it.startsWith("Divider_") }.distinct()
                val full = valid + DEFAULT_CARD_ORDER.filter { it !in valid }
                cardOrder = full
                rebuildCombinedPreviewOnly()
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.dividerConfig.collect { json ->
                // Parse JSON array: [{"id":"Divider_1","text":"Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬"}, ...]
                try {
                    val arr = org.json.JSONArray(json)
                    dividerTexts.clear()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        dividerTexts[obj.getString("id")] = obj.optString("text", "Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬")
                    }
                } catch (_: Exception) { /* malformed Ã¢â‚¬â€ keep empty */ }
                rebuildCombinedPreviewOnly()
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.timeEnabled.collect {
                timeEnabled = it
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

                // Track ad segment count: increment only when transitioning INTO an ad,
                // not on every tick. Reset when ad ends so next ad gets a fresh count.
                val isAdNow = s.specialActive && s.title.trim().lowercase().let { t ->
                    t.contains("advert") || t == "ad" || t.contains("advertisement") || t.contains("sponsored")
                } || (s.specialActive && s.activePackage == "com.spotify.music" && s.title.trim() == "AD")
                if (isAdNow && !lastSpecialWasAd) adSegmentCount++
                lastSpecialWasAd = isAdNow

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
        if (!isBanned) clearChatbox(local)
        rebuildCombinedPreviewOnly(forceClearIfAllOff = true)
        startSelfSyncLoopIfNeeded()
    }

    // =========================
    // Enable flags
    // =========================
    fun setAfkEnabledFlag(enabled: Boolean) {
        if (isBanned) return
        afkEnabled = enabled
        rebuildCombinedPreviewOnly()
        if (!enabled) stopAfkSender(clearFromChatbox = true)
        startSelfSyncLoopIfNeeded()
    }

    fun setCycleEnabledFlag(enabled: Boolean) {
        if (isBanned) return
        cycleEnabled = enabled
        viewModelScope.launch { userPreferencesRepository.saveCycleEnabled(enabled) }
        rebuildCombinedPreviewOnly()
        if (!enabled) stopCycle(clearFromChatbox = true)
        if (enabled) lastCyclePreviewAdvanceMs = 0L
        startSelfSyncLoopIfNeeded()
    }

    fun setSpotifyEnabledFlag(enabled: Boolean) {
        if (isBanned) return
        spotifyEnabled = enabled
        rebuildCombinedPreviewOnly()
        if (!enabled) stopNowPlayingSender(clearFromChatbox = true)
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

    suspend fun saveAfkPresetName(slot: Int, name: String) {
        if (isBanned) return
        when (slot.coerceIn(1, 3)) {
            1 -> userPreferencesRepository.savePinnedPreset1Name(name)
            2 -> userPreferencesRepository.savePinnedPreset2Name(name)
            else -> userPreferencesRepository.savePinnedPreset3Name(name)
        }
    }

    suspend fun saveCyclePresetName(slot: Int, name: String) {
        if (isBanned) return
        when (slot.coerceIn(1, 5)) {
            1 -> userPreferencesRepository.saveCyclePreset1(
                userPreferencesRepository.cyclePreset1Messages.first(),
                userPreferencesRepository.cyclePreset1Interval.first(), name)
            2 -> userPreferencesRepository.saveCyclePreset2(
                userPreferencesRepository.cyclePreset2Messages.first(),
                userPreferencesRepository.cyclePreset2Interval.first(), name)
            3 -> userPreferencesRepository.saveCyclePreset3(
                userPreferencesRepository.cyclePreset3Messages.first(),
                userPreferencesRepository.cyclePreset3Interval.first(), name)
            4 -> userPreferencesRepository.saveCyclePreset4(
                userPreferencesRepository.cyclePreset4Messages.first(),
                userPreferencesRepository.cyclePreset4Interval.first(), name)
            else -> userPreferencesRepository.saveCyclePreset5(
                userPreferencesRepository.cyclePreset5Messages.first(),
                userPreferencesRepository.cyclePreset5Interval.first(), name)
        }
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

        viewModelScope.launch { userPreferencesRepository.saveCycleEnabled(true) }
        persistCycleLinesPreserve()
        viewModelScope.launch { userPreferencesRepository.saveCycleInterval(cycleIntervalSeconds) }

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
    }

    // =========================
    // Now Playing sender
    // =========================
    fun startNowPlayingSender(local: Boolean = false) {
        if (isBanned) return
        if (!spotifyEnabled) return
        nowPlayingJob?.cancel()
        nowPlayingJob = viewModelScope.launch {
            while (spotifyEnabled && !isBanned) {
                // \u2705 ONLY CHANGE: run on a 2s cadence so OSC updates match public behavior
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
        if (clearFromChatbox && !isBanned) clearChatbox()
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

        sendToVrchatRaw(combined, local, addToConversation = false)
        lastCombinedSendMs = nowMs
    }

    private fun buildCombinedText(cycleLineOverride: String?): String {
        cycleTrimWarning = ""
        dividerRemovedWarning = false

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
                component == "AFK" -> if (afkLine.isNotBlank()) rawLines += LineWithPriority(text = afkLine, priority = Priority.AFK)
                component == "Cycle" -> if (cycleLine.isNotBlank()) rawLines += LineWithPriority(text = cycleLine, priority = Priority.CYCLE)
                component == "NowPlaying" -> for (m in musicLines) if (m.isNotBlank()) rawLines += LineWithPriority(text = m, priority = Priority.MUSIC)
                component == "Time" -> if (standalonTimeLine.isNotBlank()) rawLines += LineWithPriority(text = standalonTimeLine, priority = Priority.MUSIC)
                component.startsWith("Divider_") -> {
                    val divText = dividerTexts[component]?.trim() ?: "Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬"
                    // Only insert divider if there's content on both sides (don't add leading/trailing dividers)
                    if (rawLines.isNotEmpty()) {
                        rawLines += LineWithPriority(text = divText, priority = Priority.DIVIDER, isDivider = true)
                    }
                }
            }
        }
        // Remove trailing divider if it ended up last
        while (rawLines.isNotEmpty() && rawLines.last().isDivider) rawLines.removeAt(rawLines.lastIndex)

        val limited = limitWithPriority(rawLines, VRC_MAX_CHARS, VRC_MAX_LINES)

        if (limited.cycleWasModifiedToPreserveMusic) {
            cycleTrimWarning = "Cycle was trimmed to preserve Now Playing (VRChat limits)."
        }
        if (limited.dividerWasRemoved || dividerRemovedWarning) {
            dividerRemovedWarning = true
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
        val isSpotifyDj = activePackage == "com.spotify.music" &&
            nowPlayingDetected &&
            (safeTitle.isBlank() || safeArtist.isBlank())

        // Ad suppression: never show brand name, title, progress bar, or timestamps.
        // Only show "Ad N" where N is how many ad segments have played this session.
        // This prevents leaking brand/location info from targeted ads.
        val isAdSegment = nowPlayingSpecialActive && !isSpotifyDj &&
            (safeTitle.lowercase().let { t ->
                t == "ad" || t.contains("advert") || t.contains("advertisement") || t.contains("sponsored")
            } || (activePackage == "com.spotify.music" && safeTitle == "AD"))
        if (isAdSegment) {
            return listOf("Ad $adSegmentCount")
        }

        val effectiveIsPlaying = if (nowPlayingSpecialActive || isSpotifyDj) true else nowPlayingIsPlaying

        val maxLine = 42
        val twoLineBudget = maxLine * 2

        val combinedName = if (safeArtist.isNotBlank()) "$safeArtist \u2014 $safeTitle" else safeTitle
        val preferNoArtist = safeArtist.isNotBlank() && combinedName.length > twoLineBudget

        val primary = if (preferNoArtist) safeTitle else combinedName
        val line1 = when {
            primary.length <= maxLine -> primary
            safeTitle.length <= maxLine -> safeTitle
            else -> safeTitle.take(maxLine)  // hard cut
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

    private enum class Priority { AFK, MUSIC, CYCLE, DIVIDER }
    private data class LineWithPriority(val text: String, val priority: Priority, val isDivider: Boolean = false)
    private data class LimitedResult(val text: String, val cycleWasModifiedToPreserveMusic: Boolean, val dividerWasRemoved: Boolean = false)

    private fun limitWithPriority(lines: List<LineWithPriority>, maxChars: Int, maxLines: Int): LimitedResult {
        if (lines.isEmpty()) return LimitedResult("", false)

        val cleaned = lines.map { it.copy(text = it.text.trim()) }.filter { it.text.isNotEmpty() }.toMutableList()
        if (cleaned.isEmpty()) return LimitedResult("", false)

        var cycleModifiedForMusic = false

        while (cleaned.size > maxLines) {
            // Dividers are always dropped first Ã¢â‚¬â€ they are cosmetic
            val divIdx = cleaned.indexOfLast { it.isDivider }
            if (divIdx >= 0) {
                cleaned.removeAt(divIdx)
                dividerRemovedWarning = true
                continue
            }
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

        var len = totalLen(cleaned)
        while (len > maxChars && cleaned.isNotEmpty()) {
            val excess = len - maxChars

            // Drop dividers first Ã¢â‚¬â€ cosmetic, never truncate
            val divIdx = cleaned.indexOfLast { it.isDivider }
            if (divIdx >= 0) {
                cleaned.removeAt(divIdx)
                dividerRemovedWarning = true
                len = totalLen(cleaned)
                continue
            }

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
                // U+25C7 (ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬ÂÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¡ White Diamond) ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â in basic geometric shapes block,
                // renders correctly in VRChat. U+27E1 (ÃƒÆ’Ã‚Â¢Ãƒâ€¦Ã‚Â¸Ãƒâ€šÃ‚Â¡) is not in VRChat's font.
                val bg = CharArray(slots) { '\u25C7' }
                bg[idx] = dot
                bg.concatToString()
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
