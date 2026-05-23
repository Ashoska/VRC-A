package com.vrca.vrchat

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

data class InAppAlert(
    val id: String,
    val title: String,
    val body: String,
    val url: String?,
    val timestampMs: Long,
    val beforeText: String? = null,
    val afterText: String? = null
)

object InAppAlertState {
    private const val PREFS_NAME = "vrca_in_app_alerts"
    private const val KEY_ALERTS = "alerts_json"
    private const val MAX_ALERTS = 20

    private val _alerts = MutableStateFlow<List<InAppAlert>>(emptyList())
    val alerts: StateFlow<List<InAppAlert>> = _alerts

    fun load(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ALERTS, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Throwable) { JSONArray() }
        val list = mutableListOf<InAppAlert>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            list += InAppAlert(
                id = obj.optString("id"),
                title = obj.optString("title"),
                body = obj.optString("body"),
                url = obj.optString("url").ifBlank { null },
                timestampMs = obj.optLong("ts"),
                beforeText = obj.optString("before").ifBlank { null },
                afterText = obj.optString("after").ifBlank { null }
            )
        }
        _alerts.value = list
    }

    fun addAlert(ctx: Context, alert: InAppAlert) {
        val current = _alerts.value.toMutableList()
        if (current.any { it.id == alert.id }) return
        current.add(0, alert)
        while (current.size > MAX_ALERTS) current.removeLast()
        _alerts.value = current
        persist(ctx)
    }

    fun dismiss(ctx: Context, alertId: String) {
        val current = _alerts.value.toMutableList()
        current.removeAll { it.id == alertId }
        _alerts.value = current
        persist(ctx)
    }

    private fun persist(ctx: Context) {
        val arr = JSONArray()
        for (a in _alerts.value) {
            arr.put(JSONObject().apply {
                put("id", a.id)
                put("title", a.title)
                put("body", a.body)
                put("url", a.url ?: "")
                put("ts", a.timestampMs)
                put("before", a.beforeText ?: "")
                put("after", a.afterText ?: "")
            })
        }
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ALERTS, arr.toString()).apply()
    }
}
