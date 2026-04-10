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
 * VRChat API auth flow (GET /api/1/auth/user with Basic auth):
 *  - HTTP 200 + body has "requiresTwoFactorAuth" array -> 2FA needed
 *    (auth cookie is still in Set-Cookie at this point)
 *  - HTTP 200 + body has "id" field -> fully logged in
 *  - HTTP 401 -> wrong credentials
 *
 * Cookie handling:
 *  Set-Cookie headers look like: "auth=authcookie_xxx; Path=/; HttpOnly; Secure"
 *  We extract only the "name=value" part (before first semicolon) for storage
 *  and for sending in Cookie headers.
 */
object VrchatAuthManager {

    private const val TAG = "VrchatAuth"
    private const val BASE = "https://api.vrchat.cloud/api/1"
    private const val USER_AGENT = "VRC-A-Companion/1.0 (Android; companion app)"

    private const val PREFS_FILE = "vrca_vrchat_auth"
    private const val KEY_AUTH_COOKIE  = "auth_cookie"
    private const val KEY_2FA_COOKIE   = "twofa_cookie"
    private const val KEY_USER_ID      = "vrchat_user_id"
    private const val KEY_DISPLAY_NAME = "vrchat_display_name"
    private const val KEY_COOKIE_STORED_AT = "cookie_stored_at_ms"

    private const val COOKIE_REFRESH_MS = 12L * 24 * 60 * 60 * 1000

    sealed class AuthResult {
        data class Success(val userId: String, val displayName: String) : AuthResult()
        object Requires2FA : AuthResult()          // authenticator app TOTP
        object RequiresEmail2FA : AuthResult()     // email OTP (expires 15 min)
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
        val auth  = prefs.getString(KEY_AUTH_COOKIE, null) ?: return null
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

                val (responseCode, body, rawCookies) = get(
                    url = "$BASE/auth/user",
                    authHeader = "Basic $credentials",
                    cookieHeader = null
                )

                Log.d(TAG, "login response=$responseCode cookies=${rawCookies.size}")

                // Extract clean "name=value" part from each Set-Cookie header
                val authCookieValue = rawCookies
                    .mapNotNull { extractCookieValue(it, "auth") }
                    .firstOrNull()

                when (responseCode) {
                    200 -> {
                        val json = JSONObject(body)

                        // 200 + requiresTwoFactorAuth = 2FA needed (auth cookie still present)
                        val requires2FA = json.optJSONArray("requiresTwoFactorAuth")
                        if (requires2FA != null && requires2FA.length() > 0) {
                            // Save partial auth cookie for the 2FA verify step
                            if (authCookieValue != null) {
                                getPrefs(context)?.edit()
                                    ?.putString(KEY_AUTH_COOKIE, authCookieValue)
                                    ?.apply()
                            }
                            val types = (0 until requires2FA.length())
                                .map { requires2FA.getString(it) }
                            Log.d(TAG, "2FA required: $types")
                            return@withContext if (types.any { it.contains("email", ignoreCase = true) })
                                AuthResult.RequiresEmail2FA
                            else
                                AuthResult.Requires2FA
                        }

                        // 200 + "id" field = fully logged in
                        val userId = json.optString("id")
                        val displayName = json.optString("displayName")

                        if (authCookieValue != null && userId.isNotBlank()) {
                            saveSession(context, authCookieValue, null, userId, displayName)
                            AuthResult.Success(userId, displayName)
                        } else {
                            // Unusual: 200 but no id or cookie - log the body for diagnosis
                            Log.w(TAG, "200 but no id/cookie. body=${body.take(300)}")
                            AuthResult.Error("Login response missing user data. Try again.")
                        }
                    }

                    401 -> {
                        // Wrong credentials
                        AuthResult.Error("Incorrect username or password.")
                    }

                    else -> {
                        Log.w(TAG, "Unexpected response $responseCode: ${body.take(200)}")
                        AuthResult.Error("HTTP $responseCode - please try again.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Login failed", e)
                AuthResult.Error(e.message ?: "Network error - check your connection.")
            }
        }

    // ------------------------------------------------------------------
    // 2FA verification
    // ------------------------------------------------------------------

    suspend fun verify2FA(context: Context, code: String, isEmail: Boolean): TwoFaResult =
        withContext(Dispatchers.IO) {
            try {
                val partialCookie = getPrefs(context)?.getString(KEY_AUTH_COOKIE, null)
                    ?: return@withContext TwoFaResult.Error("Session expired - please sign in again.")

                val endpoint = if (isEmail) "auth/twofactorauth/emailotp/verify"
                               else "auth/twofactorauth/totp/verify"

                val body = "{\"code\":\"${code.trim()}\"}"
                val (responseCode, responseBody, rawCookies) = post(
                    url = "$BASE/$endpoint",
                    body = body,
                    cookieHeader = partialCookie
                )

                Log.d(TAG, "verify2FA response=$responseCode")

                if (responseCode == 200) {
                    val twoFaCookieValue = rawCookies
                        .mapNotNull { extractCookieValue(it, "twoFactorAuth") }
                        .firstOrNull()

                    val cookieHeader = if (twoFaCookieValue != null)
                        "$partialCookie; twoFactorAuth=$twoFaCookieValue"
                    else partialCookie

                    // Fetch user info with full cookie set
                    val (userCode, userBody, _) = get(
                        url = "$BASE/auth/user",
                        authHeader = null,
                        cookieHeader = cookieHeader
                    )

                    if (userCode == 200) {
                        val json = JSONObject(userBody)
                        val userId = json.optString("id")
                        val displayName = json.optString("displayName")

                        if (userId.isNotBlank()) {
                            saveSession(context, partialCookie,
                                twoFaCookieValue?.let { "twoFactorAuth=$it" },
                                userId, displayName)
                            TwoFaResult.Success(userId, displayName)
                        } else {
                            TwoFaResult.Error("Could not retrieve user after verification.")
                        }
                    } else {
                        TwoFaResult.Error("Session error after verification (HTTP $userCode).")
                    }
                } else {
                    val msg = try {
                        JSONObject(responseBody).optString("error", "")
                    } catch (_: Exception) { "" }
                    TwoFaResult.Error(
                        if (msg.isNotBlank()) "Invalid code: $msg"
                        else "Invalid code. Please check and try again."
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "2FA verification failed", e)
                TwoFaResult.Error(e.message ?: "Network error.")
            }
        }

    // ------------------------------------------------------------------
    // Session validation
    // ------------------------------------------------------------------

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
    // Presence fetch
    // ------------------------------------------------------------------

    data class VrcUserPresence(
        val userId: String,
        val displayName: String,
        val status: String,
        val statusDescription: String,
        val location: String,
        val platform: String,
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

            var worldName = ""
            var playerCount = 0
            var capacity = 0
            if (location.isNotBlank() && location != "offline" && location != "private" &&
                location != "traveling" && location.contains(":")) {
                try {
                    val (wCode, wBody, _) = get("$BASE/instances/$location", null, cookieHeader)
                    if (wCode == 200) {
                        val inst = JSONObject(wBody)
                        playerCount = inst.optInt("n_users", 0)
                        capacity = inst.optInt("capacity", 0)
                        worldName = inst.optJSONObject("world")?.optString("name", "") ?: ""
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
    // Friends list
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
                    friends += VrcFriend(obj.optString("id"), obj.optString("displayName"))
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

    /**
     * Extracts the value of a named cookie from a raw Set-Cookie header string.
     * e.g. extractCookieValue("auth=authcookie_xxx; Path=/; HttpOnly", "auth")
     *      returns "authcookie_xxx"
     * Returns the full "name=value" string ready for a Cookie header,
     * e.g. "auth=authcookie_xxx"
     */
    private fun extractCookieValue(setCookieHeader: String, name: String): String? {
        // Split on ";" to get individual attributes, first segment is "name=value"
        val nameValue = setCookieHeader.split(";").firstOrNull()?.trim() ?: return null
        if (!nameValue.startsWith("$name=", ignoreCase = true)) return null
        return nameValue // returns "auth=authcookie_xxx" or "twoFactorAuth=xxx"
    }

    private fun saveSession(
        context: Context,
        authCookie: String,  // full "auth=authcookie_xxx"
        twoFaCookie: String?, // full "twoFactorAuth=xxx" or null
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
