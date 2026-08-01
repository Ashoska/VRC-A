package com.vrca.app

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Best-effort current weather for the `{weather}` chatbox token. IP-based
 * geolocation (no location permission) → open-meteo current conditions, refreshed
 * every 15 min and cached. Both APIs are free + keyless. Blank on any failure, so
 * a bad network just leaves `{weather}` empty rather than erroring.
 */
object WeatherProvider {

    private const val TAG = "WeatherProvider"

    /** e.g. "12°C Cloudy". Read synchronously by the token resolver. */
    @Volatile var current: String = ""
        private set

    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            while (true) {
                runCatching { refresh() }.onFailure { Log.w(TAG, "weather refresh failed", it) }
                delay(15 * 60 * 1000L)
            }
        }
    }

    private fun refresh() {
        val loc = ipLocation() ?: return
        val (lat, lon) = loc
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code&temperature_unit=celsius"
        val body = httpGet(url) ?: return
        val cur = JSONObject(body).optJSONObject("current") ?: return
        val temp = cur.optDouble("temperature_2m", Double.NaN)
        if (temp.isNaN()) return
        val code = cur.optInt("weather_code", -1)
        current = "${Math.round(temp)}°C ${condition(code)}".trim()
    }

    /** ipwho.is — free, keyless, HTTPS. Returns (lat, lon). */
    private fun ipLocation(): Pair<Double, Double>? {
        val body = httpGet("https://ipwho.is/") ?: return null
        val j = JSONObject(body)
        if (!j.optBoolean("success", true)) return null
        val lat = j.optDouble("latitude", Double.NaN)
        val lon = j.optDouble("longitude", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return null
        return lat to lon
    }

    private fun httpGet(url: String): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "VRC-A/1.0")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 12_000; readTimeout = 12_000
        }
        if (conn.responseCode == 200) conn.inputStream.bufferedReader().readText() else null
    } catch (e: Exception) {
        Log.w(TAG, "GET $url failed", e); null
    }

    /** WMO weather-code → short condition. */
    private fun condition(code: Int): String = when (code) {
        0 -> "Clear"
        1, 2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Foggy"
        51, 53, 55, 56, 57 -> "Drizzle"
        61, 63, 65, 66, 67 -> "Rain"
        71, 73, 75, 77 -> "Snow"
        80, 81, 82 -> "Showers"
        85, 86 -> "Snow showers"
        95, 96, 99 -> "Thunderstorm"
        else -> ""
    }
}
