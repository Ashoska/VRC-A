package com.vrca.spotify

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.vrca.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Spotify OAuth (Authorization Code + PKCE) + Web API client.
 *
 * Replaces the notification-listener media scraping with Spotify's OFFICIAL API:
 * the user logs in once, we store a refresh token, and poll
 * `GET /me/player/currently-playing` for the live track. PKCE = no client secret
 * (correct for a mobile public client — the Client ID is not confidential).
 *
 * Setup (one-time, by the app owner): create an app at
 * developer.spotify.com/dashboard, register the redirect URI [REDIRECT_URI], and
 * put the Client ID in `keystore.properties` as `spotifyClientId=...`
 * (→ `BuildConfig.SPOTIFY_CLIENT_ID`).
 */
object SpotifyAuthManager {

    private const val TAG = "SpotifyAuth"
    private const val PREFS_FILE = "vrca_spotify"

    // Public client id (not a secret). Redirect must match the dashboard exactly.
    val CLIENT_ID: String get() = BuildConfig.SPOTIFY_CLIENT_ID
    const val REDIRECT_URI = "vrca://spotify-callback"
    private const val SCOPES = "user-read-currently-playing user-read-playback-state"
    private const val AUTH_ENDPOINT = "https://accounts.spotify.com/authorize"
    private const val TOKEN_ENDPOINT = "https://accounts.spotify.com/api/token"
    private const val API_BASE = "https://api.spotify.com/v1"

    private const val KEY_ACCESS = "access_token"
    private const val KEY_REFRESH = "refresh_token"
    private const val KEY_EXPIRES_AT = "expires_at"
    private const val KEY_VERIFIER = "code_verifier"

    // Observable connection state for the UI.
    private val _connected = MutableStateFlow(false)
    val connectedFlow: StateFlow<Boolean> = _connected.asStateFlow()

    fun isConfigured(): Boolean = CLIENT_ID.isNotBlank()

    fun isConnected(context: Context): Boolean =
        !readPrefs(context)?.getString(KEY_REFRESH, null).isNullOrBlank()

    fun refreshConnectedState(context: Context) {
        _connected.value = isConnected(context)
    }

    // ---- auth (PKCE) ---------------------------------------------------------

    /** Build the authorize URL + persist the PKCE verifier. Open this in a Custom
     *  Tab / browser; Spotify redirects to [REDIRECT_URI] with `?code=`. */
    fun buildAuthUrl(context: Context): String {
        val verifier = randomCodeVerifier()
        writePrefs(context) { putString(KEY_VERIFIER, verifier) }
        val challenge = codeChallenge(verifier)
        val state = randomCodeVerifier().take(16)
        return AUTH_ENDPOINT +
            "?client_id=" + enc(CLIENT_ID) +
            "&response_type=code" +
            "&redirect_uri=" + enc(REDIRECT_URI) +
            "&code_challenge_method=S256" +
            "&code_challenge=" + enc(challenge) +
            "&scope=" + enc(SCOPES) +
            "&state=" + enc(state)
    }

    /** Handle the `vrca://spotify-callback?code=...` redirect: exchange the code
     *  for tokens. Returns true on success. */
    suspend fun handleRedirect(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val code = uri.getQueryParameter("code")
        val error = uri.getQueryParameter("error")
        if (!error.isNullOrBlank()) { Log.w(TAG, "auth redirect error=$error"); return@withContext false }
        if (code.isNullOrBlank()) return@withContext false
        val verifier = readPrefs(context)?.getString(KEY_VERIFIER, null)
        if (verifier.isNullOrBlank()) { Log.w(TAG, "no code_verifier stored"); return@withContext false }
        val ok = exchangeCodeForToken(context, code, verifier)
        if (ok) { writePrefs(context) { remove(KEY_VERIFIER) }; _connected.value = true }
        ok
    }

    private fun exchangeCodeForToken(context: Context, code: String, verifier: String): Boolean {
        val body = mapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to REDIRECT_URI,
            "client_id" to CLIENT_ID,
            "code_verifier" to verifier
        )
        val (respCode, resp) = postForm(TOKEN_ENDPOINT, body)
        if (respCode != 200) { Log.w(TAG, "token exchange failed: $respCode $resp"); return false }
        return storeTokenResponse(context, resp)
    }

    private fun storeTokenResponse(context: Context, resp: String): Boolean {
        return try {
            val j = JSONObject(resp)
            val access = j.optString("access_token", "")
            val refresh = j.optString("refresh_token", "") // may be absent on refresh
            val expiresIn = j.optLong("expires_in", 3600L)
            if (access.isBlank()) return false
            writePrefs(context) {
                putString(KEY_ACCESS, access)
                if (refresh.isNotBlank()) putString(KEY_REFRESH, refresh)
                putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + (expiresIn - 60) * 1000L)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "storeTokenResponse failed", e); false
        }
    }

    /** A valid access token, refreshing if expired. Null if not connected / refresh failed. */
    suspend fun getAccessToken(context: Context): String? = withContext(Dispatchers.IO) {
        val prefs = readPrefs(context) ?: return@withContext null
        val access = prefs.getString(KEY_ACCESS, null)
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        if (!access.isNullOrBlank() && System.currentTimeMillis() < expiresAt) return@withContext access
        // Expired -> refresh.
        val refresh = prefs.getString(KEY_REFRESH, null) ?: return@withContext null
        val body = mapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refresh,
            "client_id" to CLIENT_ID
        )
        val (respCode, resp) = postForm(TOKEN_ENDPOINT, body)
        if (respCode != 200) {
            Log.w(TAG, "token refresh failed: $respCode $resp")
            // 400 invalid_grant = the refresh token is dead -> force re-login.
            if (respCode == 400) { logout(context) }
            return@withContext null
        }
        if (!storeTokenResponse(context, resp)) return@withContext null
        readPrefs(context)?.getString(KEY_ACCESS, null)
    }

    fun logout(context: Context) {
        writePrefs(context) { clear() }
        _connected.value = false
    }

    // ---- Web API: currently playing -----------------------------------------

    data class SpotifyTrack(
        val title: String,
        val artist: String,
        val albumArtUrl: String,
        val durationMs: Long,
        val progressMs: Long,
        val isPlaying: Boolean,
        val isAd: Boolean
    )

    /** `GET /me/player/currently-playing`. Null = nothing playing / no active
     *  device / not connected. */
    suspend fun fetchCurrentlyPlaying(context: Context): SpotifyTrack? = withContext(Dispatchers.IO) {
        val token = getAccessToken(context) ?: return@withContext null
        try {
            val (code, body) = get("$API_BASE/me/player/currently-playing", token)
            if (code == 204 || code == 202) return@withContext null // nothing playing
            if (code == 401) { // token rejected — drop cached access so next call refreshes
                writePrefs(context) { putLong(KEY_EXPIRES_AT, 0L) }
                return@withContext null
            }
            if (code != 200 || body.isBlank()) return@withContext null
            val j = JSONObject(body)
            val isPlaying = j.optBoolean("is_playing", false)
            val progressMs = j.optLong("progress_ms", 0L)
            val type = j.optString("currently_playing_type", "track")
            if (type == "ad") {
                return@withContext SpotifyTrack("", "", "", 0L, progressMs, isPlaying, isAd = true)
            }
            val item = j.optJSONObject("item") ?: return@withContext null
            val title = item.optString("name", "")
            val artists = item.optJSONArray("artists")
            val artist = buildString {
                if (artists != null) for (i in 0 until artists.length()) {
                    val n = artists.optJSONObject(i)?.optString("name", "").orEmpty()
                    if (n.isNotBlank()) { if (isNotEmpty()) append(", "); append(n) }
                }
            }
            val albumArt = item.optJSONObject("album")?.optJSONArray("images")
                ?.optJSONObject(0)?.optString("url", "").orEmpty()
            val durationMs = item.optLong("duration_ms", 0L)
            SpotifyTrack(title, artist, albumArt, durationMs, progressMs, isPlaying, isAd = false)
        } catch (e: Exception) {
            Log.w(TAG, "fetchCurrentlyPlaying failed", e); null
        }
    }

    // ---- http helpers --------------------------------------------------------

    private fun postForm(url: String, params: Map<String, String>): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
            doOutput = true
            connectTimeout = 15_000; readTimeout = 15_000
        }
        val bodyStr = params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        conn.outputStream.use { it.write(bodyStr.toByteArray()) }
        val code = conn.responseCode
        val resp = try {
            (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
        } catch (e: Exception) { "" }
        return code to resp
    }

    private fun get(url: String, token: String): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 15_000; readTimeout = 15_000
        }
        val code = conn.responseCode
        val resp = try {
            (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
        } catch (e: Exception) { "" }
        return code to resp
    }

    // ---- PKCE + prefs --------------------------------------------------------

    private fun randomCodeVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun readPrefs(context: Context) = try {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, PREFS_FILE, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.e(TAG, "prefs init failed", e); null
    }

    private inline fun writePrefs(context: Context, block: android.content.SharedPreferences.Editor.() -> Unit) {
        readPrefs(context)?.edit()?.apply { block(); apply() }
    }
}
