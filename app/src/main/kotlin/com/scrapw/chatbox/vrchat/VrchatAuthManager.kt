package com.scrapw.chatbox.vrchat

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * VrchatAuthManager
 *
 * Handles VRChat web API authentication for the companion app.
 * This uses the unofficial VRChat API (same one the website uses) purely
 * for reading the user's own status, friends list, and notifications.
 *
 * Auth cookies are stored in EncryptedSharedPreferences and survive app restarts.
 * The pipeline service uses these cookies to maintain a persistent WebSocket connection.
 *
 * VRChat API usage policy: do not poll more than once per 60 seconds.
 * We use the WebSocket pipeline for real-time events to minimise REST calls.
 *
 * Auth flow:
 *  1. POST credentials → receive auth cookie
 *  2. If 2FA required → POST 2FA code → receive twoFactorAuth cookie
 *  3. Both cookies stored encrypted, used on all subsequent requests
 *  4. On cookie expiry (~2 weeks) → re-auth silently if credentials cached,
 *     otherwise show login screen notification
 */
object VrchatAuthManager {

    private const val TAG = "VrchatAuth"
    private const val BASE = "https://api.vrchat.cloud/api/1"
    // VRChat requires a descriptive User-Agent identifying your app.
    private const val USER_AGENT = "VRC-A-Companion/1.0 (Android; companion app)"

    private const val PREFS_FILE = "vrca_vrchat_auth"
    private const val KEY_AUTH_COOKIE = "auth_cookie"
    private const val KEY_2FA_COOKIE = "twofa_cookie"
    private const val KEY_USER_ID = "vrchat_user_id"
    private const val KEY_DISPLAY_NAME = "vrchat_display_name"
    private const val KEY_COOKIE_STORED_AT = "cookie_stored_at_ms"

    // Cookies are valid ~2 weeks. We proactively refresh after 12 days.
    private const val COOKIE_REFRESH_MS = 12L * 24 * 60 * 60 * 1000

    sealed class AuthResult {
        data class Success(val userId: String, val displayName: String) : AuthResult()
        object Requires2FA : AuthResult()
        object RequiresEmail2FA : AuthResult()
        data class Error(val message: String) : AuthResult()
    }

    sealed class TwoFaResult {
        data class Success(val userId: String, val displayName: String) : TwoFaResult()
        data class Error(val message: String) : TwoFaResult()
    }

    // ------------------------------------------------------------------
    // Encrypted prefs
    // ------------------------------------------------------------------

    private fun getPrefs(context: Context) = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.e(TAG, "EncryptedSharedPreferences init failed", e)
        null
    }

    fun isLoggedIn(context: Context): Boolean {
        val prefs = getPrefs(context) ?: return false
        return prefs.getString(KEY_AUTH_COOKIE, null)?.isNotBlank() == true &&
               prefs.getString(KEY_USER_ID, null)?.isNotBlank() == true
    }

    fun getStoredUserId(context: Context): String? =
        getPrefs(context)?.getString(KEY_USER_ID, null)

    fun getStoredDisplayName(context: Context): String? =
        getPrefs(context)?.getString(KEY_DISPLAY_NAME, null)

    fun getCookieHeader(context: Context): String? {
        val prefs = getPrefs(context) ?: return null
        val auth = prefs.getString(KEY_AUTH_COOKIE, null) ?: return null
        val twoFa = prefs.getString(KEY_2FA_COOKIE, null)
        return if (twoFa != null) "$auth; $twoFa" else auth
    }

    fun shouldRefreshCookies(context: Context): Boolean {
        val prefs = getPrefs(context) ?: return false
        val storedAt = prefs.getLong(KEY_COOKIE_STORED_AT, 0L)
        return System.currentTimeMillis() - storedAt > COOKIE_REFRESH_MS
    }

    fun logout(context: Context) {
        getPrefs(context)?.edit()?.clear()?.apply()
    }

    // ------------------------------------------------------------------
    // Login
    // ------------------------------------------------------------------

    suspend fun login(context: Context, username: String, password: String): AuthResult =
        withContext(Dispatchers.IO) {
            try {
                val credentials = Base64.getEncoder()
                    .encodeToString("$username:$password".toByteArray())

                val (responseCode, body, cookies) = get(
                    url = "$BASE/auth/user",
                    authHeader = "Basic $credentials",
                    cookieHeader = null
                )

                when (responseCode) {
                    200 -> {
                        // Logged in — may or may not have 2FA cookie yet
                        val json = JSONObject(body)
                        val userId = json.optString("id")
                        val displayName = json.optString("displayName")
                        val authCookie = cookies.firstOrNull { it.startsWith("auth=") }

                        if (authCookie != null && userId.isNotBlank()) {
                            saveSession(context, authCookie, null, userId, displayName)
                            AuthResult.Success(userId, displayName)
                        } else {
                            AuthResult.Error("No auth cookie in response")
                        }
                    }
                    401 -> {
                        // Need 2FA
                        val json = JSONObject(body)
                        val requires = json.optJSONArray("requiresTwoFactorAuth")
                        val authCookie = cookies.firstOrNull { it.startsWith("auth=") }

                        // Store partial auth cookie so 2FA POST can use it
                        if (authCookie != null) {
                            getPrefs(context)?.edit()
                                ?.putString(KEY_AUTH_COOKIE, authCookie)
                                ?.apply()
                        }

                        if (requires != null) {
                            val types = (0 until requires.length()).map { requires.getString(it) }
                            if (types.any { it.contains("email", ignoreCase = true) }) {
                                AuthResult.RequiresEmail2FA
                            } else {
                                AuthResult.Requires2FA
                            }
                        } else {
                            AuthResult.Error("Authentication failed")
                        }
                    }
                    else -> AuthResult.Error("HTTP $responseCode: ${body.take(200)}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Login failed", e)
                AuthResult.Error(e.message ?: "Network error")
            }
        }

    // ------------------------------------------------------------------
    // 2FA verification
    // ------------------------------------------------------------------

    suspend fun verify2FA(context: Context, code: String, isEmail: Boolean): TwoFaResult =
        withContext(Dispatchers.IO) {
            try {
                val partialCookie = getPrefs(context)?.getString(KEY_AUTH_COOKIE, null)
                    ?: return@withContext TwoFaResult.Error("No partial auth cookie")

                val endpoint = if (isEmail) "auth/twofactorauth/emailotp/verify"
                               else "auth/twofactorauth/totp/verify"

                val body = "{\"code\":\"${code.trim()}\"}"
                val (responseCode, responseBody, cookies) = post(
                    url = "$BASE/$endpoint",
                    body = body,
                    cookieHeader = partialCookie
                )

                if (responseCode == 200) {
                    val twoFaCookie = cookies.firstOrNull { it.startsWith("twoFactorAuth=") }
                    // Now fetch user info with both cookies
                    val cookieHeader = if (twoFaCookie != null) "$partialCookie; $twoFaCookie"
                                       else partialCookie

                    val (userCode, userBody, _) = get(
                        url = "$BASE/auth/user",
                        authHeader = null,
                        cookieHeader = cookieHeader
                    )

                    if (userCode == 200) {
                        val json = JSONObject(userBody)
                        val userId = json.optString("id")
                        val displayName = json.optString("displayName")
                        saveSession(context, partialCookie, twoFaCookie, userId, displayName)
                        TwoFaResult.Success(userId, displayName)
                    } else {
                        TwoFaResult.Error("Could not fetch user after 2FA: HTTP $userCode")
                    }
                } else {
                    TwoFaResult.Error("Invalid 2FA code (HTTP $responseCode)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "2FA verification failed", e)
                TwoFaResult.Error(e.message ?: "Network error")
            }
        }

    // ------------------------------------------------------------------
    // Cookie refresh / validate
    // ------------------------------------------------------------------

    /**
     * Silently validates the stored session. Returns true if still valid.
     * Call this on service start before opening the WebSocket.
     */
    suspend fun validateSession(context: Context): Boolean = withContext(Dispatchers.IO) {
        val cookieHeader = getCookieHeader(context) ?: return@withContext false
        try {
            val (code, _, _) = get("$BASE/auth", null, cookieHeader)
            code == 200
        } catch (e: Exception) {
            Log.e(TAG, "Session validation failed", e)
            false
        }
    }

    // ------------------------------------------------------------------
    // Fetch current user presence data (REST, called sparingly)
    // ------------------------------------------------------------------

    data class VrcUserPresence(
        val userId: String,
        val displayName: String,
        val status: String,          // "active", "join me", "ask me", "busy", "offline"
        val statusDescription: String,
        val location: String,        // instance location string or "private" / "offline"
        val platform: String,        // "standalonewindows", "android", "ios"
        val worldName: String,
        val instancePlayerCount: Int,
        val instanceCapacity: Int,
        val currentAvatarThumbnailUrl: String
    )

    suspend fun fetchPresence(context: Context): VrcUserPresence? = withContext(Dispatchers.IO) {
        val cookieHeader = getCookieHeader(context) ?: return@withContext null
        try {
            val (code, body, _) = get("$BASE/auth/user", null, cookieHeader)
            if (code != 200) return@withContext null
            val json = JSONObject(body)
            val location = json.optString("location", "offline")

            // Fetch instance details if in a world
            var worldName = ""
            var playerCount = 0
            var capacity = 0
            if (location.isNotBlank() && location != "offline" && location != "private" &&
                location != "traveling" && location.contains(":")) {
                val worldId = location.substringBefore(":")
                try {
                    val instanceId = location.substringAfter(":")
                    val (wCode, wBody, _) = get("$BASE/instances/$worldId:$instanceId", null, cookieHeader)
                    if (wCode == 200) {
                        val inst = JSONObject(wBody)
                        playerCount = inst.optInt("n_users", 0)
                        capacity = inst.optInt("capacity", 0)
                        val world = inst.optJSONObject("world")
                        worldName = world?.optString("name", "") ?: ""
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not fetch instance info", e)
                }
            }

            VrcUserPresence(
                userId = json.optString("id"),
                displayName = json.optString("displayName"),
                status = json.optString("status", "offline"),
                statusDescription = json.optString("statusDescription", ""),
                location = location,
                platform = json.optString("last_platform", ""),
                worldName = worldName,
                instancePlayerCount = playerCount,
                instanceCapacity = capacity,
                currentAvatarThumbnailUrl = json.optString("currentAvatarThumbnailImageUrl", "")
            )
        } catch (e: Exception) {
            Log.e(TAG, "fetchPresence failed", e)
            null
        }
    }

    // ------------------------------------------------------------------
    // Friends list (used for unfriend diffing)
    // ------------------------------------------------------------------

    data class VrcFriend(val userId: String, val displayName: String)

    suspend fun fetchFriends(context: Context): List<VrcFriend> = withContext(Dispatchers.IO) {
        val cookieHeader = getCookieHeader(context) ?: return@withContext emptyList()
        val friends = mutableListOf<VrcFriend>()
        var offset = 0
        val pageSize = 100

        try {
            while (true) {
                val (code, body, _) = get(
                    "$BASE/auth/user/friends?offset=$offset&n=$pageSize&offline=false",
                    null, cookieHeader
                )
                if (code != 200) break
                val arr = org.json.JSONArray(body)
                if (arr.length() == 0) break
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    friends += VrcFriend(
                        userId = obj.optString("id"),
                        displayName = obj.optString("displayName")
                    )
                }
                if (arr.length() < pageSize) break
                offset += pageSize
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchFriends failed", e)
        }
        friends
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    private fun saveSession(
        context: Context,
        authCookie: String,
        twoFaCookie: String?,
        userId: String,
        displayName: String
    ) {
        getPrefs(context)?.edit()
            ?.putString(KEY_AUTH_COOKIE, authCookie)
            ?.putString(KEY_2FA_COOKIE, twoFaCookie)
            ?.putString(KEY_USER_ID, userId)
            ?.putString(KEY_DISPLAY_NAME, displayName)
            ?.putLong(KEY_COOKIE_STORED_AT, System.currentTimeMillis())
            ?.apply()
    }

    /** Simple GET returning (responseCode, body, Set-Cookie list) */
    private fun get(
        url: String,
        authHeader: String?,
        cookieHeader: String?
    ): Triple<Int, String, List<String>> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
            if (authHeader != null) setRequestProperty("Authorization", authHeader)
            if (cookieHeader != null) setRequestProperty("Cookie", cookieHeader)
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        val code = conn.responseCode
        val body = try {
            (if (code < 400) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
        } catch (e: IOException) { "" }
        val cookies = conn.headerFields["Set-Cookie"] ?: emptyList()
        return Triple(code, body, cookies)
    }

    /** Simple POST returning (responseCode, body, Set-Cookie list) */
    private fun post(
        url: String,
        body: String,
        cookieHeader: String?
    ): Triple<Int, String, List<String>> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (cookieHeader != null) setRequestProperty("Cookie", cookieHeader)
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        val responseBody = try {
            (if (code < 400) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
        } catch (e: IOException) { "" }
        val cookies = conn.headerFields["Set-Cookie"] ?: emptyList()
        return Triple(code, responseBody, cookies)
    }
}
