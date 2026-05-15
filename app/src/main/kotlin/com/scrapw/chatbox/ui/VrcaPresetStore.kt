package com.scrapw.chatbox.ui

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.vrcaPresetDataStore by preferencesDataStore(name = "vrca_presets")

data class VrcaPresets(
    val afk1: String,
    val afk2: String,
    val afk3: String,
    val cycle1: String,
    val cycle2: String,
    val cycle3: String,
    val cycle4: String,
    val cycle5: String,
)

object VrcaPresetStore {

    private val AFK_1 = stringPreferencesKey("afk_1")
    private val AFK_2 = stringPreferencesKey("afk_2")
    private val AFK_3 = stringPreferencesKey("afk_3")

    private val CYCLE_1 = stringPreferencesKey("cycle_1")
    private val CYCLE_2 = stringPreferencesKey("cycle_2")
    private val CYCLE_3 = stringPreferencesKey("cycle_3")
    private val CYCLE_4 = stringPreferencesKey("cycle_4")
    private val CYCLE_5 = stringPreferencesKey("cycle_5")

    fun flow(ctx: Context): Flow<VrcaPresets> {
        return ctx.vrcaPresetDataStore.data.map { p ->
            VrcaPresets(
                afk1 = p[AFK_1] ?: "Away",
                afk2 = p[AFK_2] ?: "Away - grabbing water",
                afk3 = p[AFK_3] ?: "Away - brb",
                cycle1 = p[CYCLE_1] ?: "",
                cycle2 = p[CYCLE_2] ?: "",
                cycle3 = p[CYCLE_3] ?: "",
                cycle4 = p[CYCLE_4] ?: "",
                cycle5 = p[CYCLE_5] ?: "",
            )
        }
    }

    suspend fun saveAfk(ctx: Context, slot: Int, value: String) {
        val key = when (slot) {
            1 -> AFK_1
            2 -> AFK_2
            else -> AFK_3
        }
        ctx.vrcaPresetDataStore.edit { it[key] = value }
    }

    suspend fun saveCycle(ctx: Context, slot: Int, value: String) {
        val key = when (slot) {
            1 -> CYCLE_1
            2 -> CYCLE_2
            3 -> CYCLE_3
            4 -> CYCLE_4
            else -> CYCLE_5
        }
        ctx.vrcaPresetDataStore.edit { it[key] = value }
    }
}
