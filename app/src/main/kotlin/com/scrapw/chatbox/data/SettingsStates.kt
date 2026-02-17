// app/src/main/kotlin/com/scrapw/chatbox/data/SettingsStates.kt
package com.scrapw.chatbox.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.alorma.compose.settings.storage.datastore.GenericPreferenceDataStoreSettingValueState
import com.scrapw.chatbox.dataStore

object SettingsStates {

    @Composable
    fun ipAddress(): GenericPreferenceDataStoreSettingValueState<String> =
        rememberPreferenceDataStoreStringSettingState(
            key = "ip_address",
            defaultValue = "127.0.0.1",
            dataStore = LocalContext.current.dataStore
        )

    @Composable
    fun port(): GenericPreferenceDataStoreSettingValueState<Int> =
        rememberPreferenceDataStoreIntSettingState(
            key = "port",
            defaultValue = 9000,
            dataStore = LocalContext.current.dataStore
        )

    @Composable
    fun overlayState(): GenericPreferenceDataStoreSettingValueState<Boolean> =
        rememberPreferenceDataStoreBooleanSettingState(
            key = "overlay",
            defaultValue = false,
            dataStore = LocalContext.current.dataStore
        )

    @Composable
    fun overlayKeepOpen(): GenericPreferenceDataStoreSettingValueState<Boolean> =
        rememberPreferenceDataStoreBooleanSettingState(
            key = "overlay_keep_open",
            defaultValue = true,
            dataStore = LocalContext.current.dataStore
        )

    @Composable
    fun overlayLocalhost(): GenericPreferenceDataStoreSettingValueState<Boolean> =
        rememberPreferenceDataStoreBooleanSettingState(
            key = "overlay_localhost",
            defaultValue = true,
            dataStore = LocalContext.current.dataStore
        )

    @Composable
    fun messageRealtime(): GenericPreferenceDataStoreSettingValueState<Boolean> =
        rememberPreferenceDataStoreBooleanSettingState(
            key = "msg_realtime",
            defaultValue = false,
            dataStore = LocalContext.current.dataStore
        )

    @Composable
    fun messageTriggerSfx(): GenericPreferenceDataStoreSettingValueState<Boolean> =
        rememberPreferenceDataStoreBooleanSettingState(
            key = "msg_trigger_sfx",
            defaultValue = true,
            dataStore = LocalContext.current.dataStore
        )

    @Composable
    fun messageTypingIndicator(): GenericPreferenceDataStoreSettingValueState<Boolean> =
        rememberPreferenceDataStoreBooleanSettingState(
            key = "msg_typing_indicator",
            defaultValue = true,
            dataStore = LocalContext.current.dataStore
        )

    @Composable
    fun messageSendDirectly(): GenericPreferenceDataStoreSettingValueState<Boolean> =
        rememberPreferenceDataStoreBooleanSettingState(
            key = "msg_send_directly",
            defaultValue = true,
            dataStore = LocalContext.current.dataStore
        )

    @Composable
    fun displayIpState(): GenericPreferenceDataStoreSettingValueState<Boolean> =
        rememberPreferenceDataStoreBooleanSettingState(
            key = "display_ip",
            defaultValue = true,
            dataStore = LocalContext.current.dataStore
        )

    @Composable
    fun displayMessageOptionsState(): GenericPreferenceDataStoreSettingValueState<Boolean> =
        rememberPreferenceDataStoreBooleanSettingState(
            key = "display_msg_options",
            defaultValue = true,
            dataStore = LocalContext.current.dataStore
        )

    @Composable
    fun fullscreenState(): GenericPreferenceDataStoreSettingValueState<Boolean> =
        rememberPreferenceDataStoreBooleanSettingState(
            key = "fullscreen",
            defaultValue = false,
            dataStore = LocalContext.current.dataStore
        )

    @Composable
    fun alwaysShowKeyboardState(): GenericPreferenceDataStoreSettingValueState<Boolean> =
        rememberPreferenceDataStoreBooleanSettingState(
            key = "always_show_keyboard",
            defaultValue = false,
            dataStore = LocalContext.current.dataStore
        )

    @Composable
    fun buttonHapticState(): GenericPreferenceDataStoreSettingValueState<Boolean> =
        rememberPreferenceDataStoreBooleanSettingState(
            key = "button_haptic",
            defaultValue = true,
            dataStore = LocalContext.current.dataStore
        )
}

/**
 * ✅ COMPAT HELPERS:
 * Your current Alorma datastore API expects (coroutineScope + dataStoreKey),
 * so we build the states directly using GenericPreferenceDataStoreSettingValueState.
 */

@Composable
private fun rememberPreferenceDataStoreStringSettingState(
    key: String,
    defaultValue: String,
    dataStore: DataStore<Preferences>
): GenericPreferenceDataStoreSettingValueState<String> {
    val scope = rememberCoroutineScope()
    val prefKey = remember(key) { stringPreferencesKey(key) }
    return remember(key, defaultValue, dataStore) {
        GenericPreferenceDataStoreSettingValueState(
            coroutineScope = scope,
            dataStore = dataStore,
            dataStoreKey = prefKey,
            defaultValue = defaultValue
        )
    }
}

@Composable
private fun rememberPreferenceDataStoreIntSettingState(
    key: String,
    defaultValue: Int,
    dataStore: DataStore<Preferences>
): GenericPreferenceDataStoreSettingValueState<Int> {
    val scope = rememberCoroutineScope()
    val prefKey = remember(key) { intPreferencesKey(key) }
    return remember(key, defaultValue, dataStore) {
        GenericPreferenceDataStoreSettingValueState(
            coroutineScope = scope,
            dataStore = dataStore,
            dataStoreKey = prefKey,
            defaultValue = defaultValue
        )
    }
}

@Composable
private fun rememberPreferenceDataStoreBooleanSettingState(
    key: String,
    defaultValue: Boolean,
    dataStore: DataStore<Preferences>
): GenericPreferenceDataStoreSettingValueState<Boolean> {
    val scope = rememberCoroutineScope()
    val prefKey = remember(key) { booleanPreferencesKey(key) }
    return remember(key, defaultValue, dataStore) {
        GenericPreferenceDataStoreSettingValueState(
            coroutineScope = scope,
            dataStore = dataStore,
            dataStoreKey = prefKey,
            defaultValue = defaultValue
        )
    }
}
