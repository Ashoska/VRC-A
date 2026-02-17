// app/src/main/kotlin/com/scrapw/chatbox/data/UserPreferencesRepository.kt
package com.scrapw.chatbox.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import com.scrapw.chatbox.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant

/**
 * IMPORTANT FIX:
 * Do NOT create another preferencesDataStore() here.
 * This repo must use the ONE global Context.dataStore defined in com.scrapw.chatbox (your dataStore extension),
 * otherwise Android will crash with:
 * "multiple DataStores active for the same file ... vrca_prefs.preferences_pb"
 */
class UserPreferencesRepository(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // =========================
    // Keys
    // =========================
    private object Keys {
        val IP = stringPreferencesKey("ip_address")
        val PORT = intPreferencesKey("port")

        val REALTIME = booleanPreferencesKey("is_realtime_msg")
        val TRIGGER_SFX = booleanPreferencesKey("is_trigger_sfx")
        val TYPING = booleanPreferencesKey("is_typing_indicator")
        val SEND_IMMEDIATELY = booleanPreferencesKey("is_send_immediately")

        val AFK_MESSAGE = stringPreferencesKey("afk_message")

        val CYCLE_ENABLED = booleanPreferencesKey("cycle_enabled")
        val CYCLE_MESSAGES = stringPreferencesKey("cycle_messages")
        val CYCLE_INTERVAL = intPreferencesKey("cycle_interval")

        val AFK_PRESET_1 = stringPreferencesKey("afk_preset_1")
        val AFK_PRESET_2 = stringPreferencesKey("afk_preset_2")
        val AFK_PRESET_3 = stringPreferencesKey("afk_preset_3")

        val CYCLE_P1_MSG = stringPreferencesKey("cycle_p1_messages")
        val CYCLE_P1_INT = intPreferencesKey("cycle_p1_interval")
        val CYCLE_P2_MSG = stringPreferencesKey("cycle_p2_messages")
        val CYCLE_P2_INT = intPreferencesKey("cycle_p2_interval")
        val CYCLE_P3_MSG = stringPreferencesKey("cycle_p3_messages")
        val CYCLE_P3_INT = intPreferencesKey("cycle_p3_interval")
        val CYCLE_P4_MSG = stringPreferencesKey("cycle_p4_messages")
        val CYCLE_P4_INT = intPreferencesKey("cycle_p4_interval")
        val CYCLE_P5_MSG = stringPreferencesKey("cycle_p5_messages")
        val CYCLE_P5_INT = intPreferencesKey("cycle_p5_interval")

        val SPOTIFY_PRESET = intPreferencesKey("spotify_preset")

        val AFK_PRESETS_COLLAPSED = booleanPreferencesKey("afk_presets_collapsed")
        val CYCLE_PRESETS_COLLAPSED = booleanPreferencesKey("cycle_presets_collapsed")

        // ✅ ToS
        val TOS_ACCEPTED_VERSION = intPreferencesKey("tos_accepted_version")
        val TOS_ACCEPTED_AT_EPOCH = longPreferencesKey("tos_accepted_at_epoch")
    }

    // =========================
    // Public flows used by ChatboxViewModel
    // =========================
    val ipAddress: Flow<String> = context.dataStore.data
        .map { it[Keys.IP] ?: "127.0.0.1" }

    val port: Flow<Int> = context.dataStore.data
        .map { it[Keys.PORT] ?: 9000 }

    val isRealtimeMsg: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.REALTIME] ?: false }

    val isTriggerSfx: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.TRIGGER_SFX] ?: true }

    val isTypingIndicator: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.TYPING] ?: true }

    val isSendImmediately: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.SEND_IMMEDIATELY] ?: true }

    val afkMessage: Flow<String> = context.dataStore.data
        .map { it[Keys.AFK_MESSAGE] ?: "" }

    val cycleEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.CYCLE_ENABLED] ?: false }

    val cycleMessages: Flow<String> = context.dataStore.data
        .map { it[Keys.CYCLE_MESSAGES] ?: "" }

    val cycleInterval: Flow<Int> = context.dataStore.data
        .map { it[Keys.CYCLE_INTERVAL] ?: 10 }

    val afkPreset1: Flow<String> = context.dataStore.data.map { it[Keys.AFK_PRESET_1] ?: "" }
    val afkPreset2: Flow<String> = context.dataStore.data.map { it[Keys.AFK_PRESET_2] ?: "" }
    val afkPreset3: Flow<String> = context.dataStore.data.map { it[Keys.AFK_PRESET_3] ?: "" }

    val cyclePreset1Messages: Flow<String> = context.dataStore.data.map { it[Keys.CYCLE_P1_MSG] ?: "" }
    val cyclePreset1Interval: Flow<Int> = context.dataStore.data.map { it[Keys.CYCLE_P1_INT] ?: 10 }
    val cyclePreset2Messages: Flow<String> = context.dataStore.data.map { it[Keys.CYCLE_P2_MSG] ?: "" }
    val cyclePreset2Interval: Flow<Int> = context.dataStore.data.map { it[Keys.CYCLE_P2_INT] ?: 10 }
    val cyclePreset3Messages: Flow<String> = context.dataStore.data.map { it[Keys.CYCLE_P3_MSG] ?: "" }
    val cyclePreset3Interval: Flow<Int> = context.dataStore.data.map { it[Keys.CYCLE_P3_INT] ?: 10 }
    val cyclePreset4Messages: Flow<String> = context.dataStore.data.map { it[Keys.CYCLE_P4_MSG] ?: "" }
    val cyclePreset4Interval: Flow<Int> = context.dataStore.data.map { it[Keys.CYCLE_P4_INT] ?: 10 }
    val cyclePreset5Messages: Flow<String> = context.dataStore.data.map { it[Keys.CYCLE_P5_MSG] ?: "" }
    val cyclePreset5Interval: Flow<Int> = context.dataStore.data.map { it[Keys.CYCLE_P5_INT] ?: 10 }

    val spotifyPreset: Flow<Int> = context.dataStore.data
        .map { (it[Keys.SPOTIFY_PRESET] ?: 1).coerceIn(1, 5) }

    val afkPresetsCollapsed: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.AFK_PRESETS_COLLAPSED] ?: true }

    val cyclePresetsCollapsed: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.CYCLE_PRESETS_COLLAPSED] ?: true }

    // =========================
    // Save functions used by ChatboxViewModel
    // =========================
    suspend fun saveIpAddress(value: String) {
        context.dataStore.edit { it[Keys.IP] = value.trim() }
    }

    suspend fun savePort(value: Int) {
        context.dataStore.edit { it[Keys.PORT] = value.coerceIn(1, 65535) }
    }

    suspend fun saveIsRealtimeMsg(value: Boolean) {
        context.dataStore.edit { it[Keys.REALTIME] = value }
    }

    suspend fun saveIsTriggerSFX(value: Boolean) {
        context.dataStore.edit { it[Keys.TRIGGER_SFX] = value }
    }

    suspend fun saveTypingIndicator(value: Boolean) {
        context.dataStore.edit { it[Keys.TYPING] = value }
    }

    suspend fun saveIsSendImmediately(value: Boolean) {
        context.dataStore.edit { it[Keys.SEND_IMMEDIATELY] = value }
    }

    suspend fun saveAfkMessage(value: String) {
        context.dataStore.edit { it[Keys.AFK_MESSAGE] = value }
    }

    suspend fun saveCycleEnabled(value: Boolean) {
        context.dataStore.edit { it[Keys.CYCLE_ENABLED] = value }
    }

    suspend fun saveCycleMessages(value: String) {
        context.dataStore.edit { it[Keys.CYCLE_MESSAGES] = value }
    }

    suspend fun saveCycleInterval(value: Int) {
        context.dataStore.edit { it[Keys.CYCLE_INTERVAL] = value }
    }

    suspend fun saveAfkPreset1(value: String) { context.dataStore.edit { it[Keys.AFK_PRESET_1] = value } }
    suspend fun saveAfkPreset2(value: String) { context.dataStore.edit { it[Keys.AFK_PRESET_2] = value } }
    suspend fun saveAfkPreset3(value: String) { context.dataStore.edit { it[Keys.AFK_PRESET_3] = value } }

    suspend fun saveCyclePreset1(messages: String, interval: Int) {
        context.dataStore.edit {
            it[Keys.CYCLE_P1_MSG] = messages
            it[Keys.CYCLE_P1_INT] = interval
        }
    }

    suspend fun saveCyclePreset2(messages: String, interval: Int) {
        context.dataStore.edit {
            it[Keys.CYCLE_P2_MSG] = messages
            it[Keys.CYCLE_P2_INT] = interval
        }
    }

    suspend fun saveCyclePreset3(messages: String, interval: Int) {
        context.dataStore.edit {
            it[Keys.CYCLE_P3_MSG] = messages
            it[Keys.CYCLE_P3_INT] = interval
        }
    }

    suspend fun saveCyclePreset4(messages: String, interval: Int) {
        context.dataStore.edit {
            it[Keys.CYCLE_P4_MSG] = messages
            it[Keys.CYCLE_P4_INT] = interval
        }
    }

    suspend fun saveCyclePreset5(messages: String, interval: Int) {
        context.dataStore.edit {
            it[Keys.CYCLE_P5_MSG] = messages
            it[Keys.CYCLE_P5_INT] = interval
        }
    }

    suspend fun saveSpotifyPreset(value: Int) {
        context.dataStore.edit { it[Keys.SPOTIFY_PRESET] = value.coerceIn(1, 5) }
    }

    suspend fun saveAfkPresetsCollapsed(value: Boolean) {
        context.dataStore.edit { it[Keys.AFK_PRESETS_COLLAPSED] = value }
    }

    suspend fun saveCyclePresetsCollapsed(value: Boolean) {
        context.dataStore.edit { it[Keys.CYCLE_PRESETS_COLLAPSED] = value }
    }

    // =========================
    // ✅ ToS
    // =========================
    fun tosAcceptedStateFlow(currentVersion: Int): StateFlow<Boolean> {
        return context.dataStore.data
            .map { prefs ->
                val acceptedVer = prefs[Keys.TOS_ACCEPTED_VERSION] ?: 0
                acceptedVer >= currentVersion
            }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = false
            )
    }

    suspend fun saveTosAcceptedVersion(version: Int) {
        context.dataStore.edit {
            it[Keys.TOS_ACCEPTED_VERSION] = version
            it[Keys.TOS_ACCEPTED_AT_EPOCH] = Instant.now().epochSecond
        }
    }
}
