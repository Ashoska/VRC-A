package com.vrca.vrchat

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

    private val _loggedOutSignal = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val loggedOutSignal: kotlinx.coroutines.flow.SharedFlow<Unit> = _loggedOutSignal

    private const val TAG = "VrchatAuth"
    private const val BASE = "https://api.vrchat.cloud/api/1"
    private const val USER_AGENT = "VRC-A-Companion/1.0 (Android; companion app)"

    private const val PREFS_FILE = "vrca_vrchat_auth"
    private const val KEY_AUTH_COOKIE  = "auth_cookie"
    private const val KEY_2FA_COOKIE   = "twofa_cookie"
    private const val KEY_USER_ID      = "vrchat_user_id"
    private const val KEY_DISPLAY_NAME = "vrchat_display_name"
    private const val KEY_COOKIE_STORED_AT = "cookie_stored_at_ms"
    private const val KEY_USERNAME = "vrchat_username"
    private const val KEY_PASSWORD = "vrchat_password"

    private const val COOKIE_REFRESH_MS = 12L * 24 * 60 * 60 * 1000
    private const val KEY_2FA_COOKIE_STORED_AT = "twofa_cookie_stored_at_ms"
    private const val TWO_FA_COOKIE_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

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
        Log.e(TAG, "EncryptedSharedPreferences init failed — attempting recovery", e)
        try {
            // Delete corrupted prefs file and its encrypted key file, then retry
            val prefsFile = java.io.File(context.applicationInfo.dataDir + "/shared_prefs/" + PREFS_FILE + ".xml")
            val keyFile = java.io.File(context.applicationInfo.dataDir + "/shared_prefs/" + PREFS_FILE + ".xml.__androidx_security_crypto_encrypted_prefs__")
            if (prefsFile.exists()) prefsFile.delete()
            if (keyFile.exists()) keyFile.delete()
            Log.i(TAG, "Deleted corrupted prefs files, re-creating EncryptedSharedPreferences")
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
        } catch (e2: Exception) {
            Log.e(TAG, "EncryptedSharedPreferences recovery also failed", e2)
            null
        }
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
        val auth  = prefs.getString(KEY_AUTH_COOKIE, null)
        val twoFa = prefs.getString(KEY_2FA_COOKIE, null)
        // Drop 2FA cookie if older than 30 days (VRChat's server expiry)
        val twoFaValid = if (twoFa != null) {
            val storedAt = prefs.getLong(KEY_2FA_COOKIE_STORED_AT, 0L)
            storedAt == 0L || System.currentTimeMillis() - storedAt < TWO_FA_COOKIE_MAX_AGE_MS
        } else false
        val effectiveTwoFa = if (twoFaValid) twoFa else null
        return when {
            auth != null && effectiveTwoFa != null -> "$auth; $effectiveTwoFa"
            auth != null                           -> auth
            effectiveTwoFa != null                 -> effectiveTwoFa
            else                                   -> null
        }
    }

    /**
     * Cookie header for the Basic-auth re-login path. Sends ONLY the trusted-
     * device 2FA cookie (if still valid) — never the saved auth cookie. An
     * expired auth cookie sent alongside Basic credentials can cause VRChat
     * to reject the request as a session conflict, even though the
     * twoFactorAuth cookie alone would let the new login skip the 2FA prompt.
     */
    private fun getTwoFaOnlyCookieHeader(context: Context): String? {
        val prefs = getPrefs(context) ?: return null
        val twoFa = prefs.getString(KEY_2FA_COOKIE, null) ?: return null
        val storedAt = prefs.getLong(KEY_2FA_COOKIE_STORED_AT, 0L)
        if (storedAt == 0L) {
            // Migration case: cookie exists but was never timestamped.
            // Give it a fresh 30-day window from now.
            Log.i(TAG, "getTwoFaOnlyCookieHeader: migrating 2FA cookie with missing timestamp")
            prefs.edit().putLong(KEY_2FA_COOKIE_STORED_AT, System.currentTimeMillis()).apply()
            return twoFa
        }
        val valid = System.currentTimeMillis() - storedAt < TWO_FA_COOKIE_MAX_AGE_MS
        return if (valid) twoFa else null
    }

    fun shouldRefreshCookies(context: Context): Boolean {
        val prefs = getPrefs(context) ?: return false
        val storedAt = prefs.getLong(KEY_COOKIE_STORED_AT, 0L)
        return System.currentTimeMillis() - storedAt > COOKIE_REFRESH_MS
    }

    fun logout(context: Context) {
        getPrefs(context)?.edit()?.clear()?.apply()
        _loggedOutSignal.tryEmit(Unit)
    }

    fun hasSavedCredentials(context: Context): Boolean {
        val prefs = getPrefs(context) ?: return false
        return prefs.getString(KEY_USERNAME, null)?.isNotBlank() == true &&
               prefs.getString(KEY_PASSWORD, null)?.isNotBlank() == true
    }

    suspend fun autoRelogin(context: Context): Boolean = withContext(Dispatchers.IO) {
        val prefs = getPrefs(context) ?: return@withContext false
        val username = prefs.getString(KEY_USERNAME, null)
        val password = prefs.getString(KEY_PASSWORD, null)
        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            Log.w(TAG, "autoRelogin: no saved credentials")
            return@withContext false
        }
        Log.i(TAG, "Attempting auto re-login for $username")
        when (val result = login(context, username, password)) {
            is AuthResult.Success -> {
                Log.i(TAG, "Auto re-login succeeded: ${result.displayName}")
                true
            }
            is AuthResult.Requires2FA, is AuthResult.RequiresEmail2FA -> {
                val twoFa = prefs.getString(KEY_2FA_COOKIE, null)
                val storedAt = prefs.getLong(KEY_2FA_COOKIE_STORED_AT, 0L)
                val ageDays = if (storedAt > 0) (System.currentTimeMillis() - storedAt) / (24L * 60 * 60 * 1000) else -1
                Log.w(TAG, "Auto re-login needs 2FA — twoFaCookie present=${twoFa != null}, ageDays=$ageDays")
                false
            }
            is AuthResult.Error -> {
                Log.e(TAG, "Auto re-login failed: ${result.message}")
                false
            }
        }
    }

    private fun saveCredentials(context: Context, username: String, password: String) {
        getPrefs(context)?.edit()
            ?.putString(KEY_USERNAME, username)
            ?.putString(KEY_PASSWORD, password)
            ?.apply()
    }

    private fun clearCredentials(context: Context) {
        getPrefs(context)?.edit()
            ?.remove(KEY_USERNAME)
            ?.remove(KEY_PASSWORD)
            ?.apply()
    }

    // ------------------------------------------------------------------
    // Login
    // ------------------------------------------------------------------

    suspend fun login(context: Context, username: String, password: String): AuthResult =
        withContext(Dispatchers.IO) {
            try {
                // Save credentials BEFORE the HTTP request so they survive
                // if the app is killed between a successful response and
                // the post-response processing.
                saveCredentials(context, username, password)

                val credentials = Base64.getEncoder()
                    .encodeToString("$username:$password".toByteArray())

                val (responseCode, body, rawCookies) = get(
                    url = "$BASE/auth/user",
                    authHeader = "Basic $credentials",
                    cookieHeader = getTwoFaOnlyCookieHeader(context)
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
                            // Update only the auth cookie + user info — preserve the
                            // existing 2FA trusted-device cookie and its original
                            // stored-at timestamp so future auto-relogins after
                            // subsequent auth-cookie expiries still work.
                            val now = System.currentTimeMillis()
                            getPrefs(context)?.edit()
                                ?.putString(KEY_AUTH_COOKIE, authCookieValue)
                                ?.putString(KEY_USER_ID, userId)
                                ?.putString(KEY_DISPLAY_NAME, displayName)
                                ?.putLong(KEY_COOKIE_STORED_AT, now)
                                ?.apply()
                            AuthResult.Success(userId, displayName)
                        } else {
                            // Unusual: 200 but no id or cookie - log the body for diagnosis
                            Log.w(TAG, "200 but no id/cookie. body=${body.take(300)}")
                            AuthResult.Error("Login response missing user data. Try again.")
                        }
                    }

                    401 -> {
                        // Wrong credentials — clear saved credentials so auto-relogin
                        // doesn't keep retrying with invalid creds
                        clearCredentials(context)
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
        val state: String,
        val status: String,
        val statusDescription: String,
        val location: String,
        val platform: String,
        val worldName: String,
        val instancePlayerCount: Int,
        val instanceCapacity: Int,
        val currentAvatarThumbnailUrl: String,
        val isOnlineInVRChat: Boolean,
        val worldImageUrl: String = ""
    )

    suspend fun fetchPresence(context: Context): VrcUserPresence? = withContext(Dispatchers.IO) {
        val cookieHeader = getCookieHeader(context) ?: run {
            Log.w(TAG, "fetchPresence: no cookie header available")
            return@withContext null
        }
        try {
            val (code, body, _) = get("$BASE/auth/user", null, cookieHeader)
            if (code != 200) {
                Log.w(TAG, "fetchPresence: API returned $code, body=${body.take(200)}")
                return@withContext null
            }
            val json = JSONObject(body)
            val userId = json.optString("id")
            var state = json.optString("state", "offline")
            var location = json.optString("location", "offline")
            var status = json.optString("status", "offline")
            var statusDescription = json.optString("statusDescription", "")
            var platform = json.optString("last_platform", "")
            var displayName = json.optString("displayName")
            var avatarThumb = json.optString("currentAvatarThumbnailImageUrl", "")

            Log.d(TAG, "fetchPresence /auth/user: state=$state status=$status location=$location")

            // /auth/user can return stale presence when session was created by a
            // companion app rather than the VRChat game client. Fetch the specific
            // user endpoint which reflects the true live state.
            if (userId.isNotBlank()) {
                try {
                    val (uCode, uBody, _) = get("$BASE/users/$userId", null, cookieHeader)
                    if (uCode == 200) {
                        val uj = JSONObject(uBody)
                        val uState = uj.optString("state", "")
                        val uLocation = uj.optString("location", "")
                        val uStatus = uj.optString("status", "")
                        Log.d(TAG, "fetchPresence /users/$userId: state=$uState status=$uStatus location=$uLocation")
                        if (uState.isNotBlank()) state = uState
                        if (uLocation.isNotBlank()) location = uLocation
                        if (uStatus.isNotBlank()) status = uStatus
                        uj.optString("statusDescription", "").let { if (it.isNotBlank()) statusDescription = it }
                        uj.optString("last_platform", "").let { if (it.isNotBlank()) platform = it }
                        uj.optString("displayName", "").let { if (it.isNotBlank()) displayName = it }
                        uj.optString("currentAvatarThumbnailImageUrl", "").let { if (it.isNotBlank()) avatarThumb = it }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not fetch /users/$userId", e)
                }
            }

            var worldName = ""
            var worldImageUrl = ""
            var playerCount = 0
            var capacity = 0
            val hasWorldLocation = location.isNotBlank() &&
                location != "offline" && location != "private" &&
                location != "traveling" && location.startsWith("wrld_")
            if (hasWorldLocation) {
                try {
                    val (wCode, wBody, _) = get("$BASE/instances/$location", null, cookieHeader)
                    if (wCode == 200) {
                        val inst = JSONObject(wBody)
                        playerCount = inst.optInt("n_users", 0)
                        capacity = inst.optInt("capacity", 0)
                        val worldObj = inst.optJSONObject("world")
                        worldName = worldObj?.optString("name", "") ?: ""
                        worldImageUrl = worldObj?.optString("thumbnailImageUrl", "") ?: ""
                        if (worldImageUrl.isBlank()) {
                            worldImageUrl = worldObj?.optString("imageUrl", "") ?: ""
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not fetch instance info", e)
                }
            }

            val isOnline = state == "online" ||
                location.startsWith("wrld_") ||
                location == "private" ||
                location == "traveling"

            VrcUserPresence(
                userId = userId,
                displayName = displayName,
                state = state,
                status = status,
                statusDescription = statusDescription,
                location = location,
                platform = platform,
                worldName = worldName,
                instancePlayerCount = playerCount,
                instanceCapacity = capacity,
                currentAvatarThumbnailUrl = avatarThumb,
                isOnlineInVRChat = isOnline,
                worldImageUrl = worldImageUrl
            )
        } catch (e: Exception) {
            Log.e(TAG, "fetchPresence failed", e)
            null
        }
    }

    // ------------------------------------------------------------------
    // Friends list
    // ------------------------------------------------------------------

    data class VrcFriend(
        val userId: String,
        val displayName: String,
        val status: String = "",
        val statusDescription: String = "",
        val location: String = "",
        val avatarThumb: String = "",
        val bio: String = "",
        val trustRank: String = ""
    )

    suspend fun fetchFriends(context: Context): List<VrcFriend> = withContext(Dispatchers.IO) {
        val cookieHeader = getCookieHeader(context) ?: return@withContext emptyList()
        val seen = mutableMapOf<String, VrcFriend>()
        val pageSize = 100
        for (offline in listOf(false, true)) {
            var offset = 0
            try {
                while (true) {
                    val (code, body, _) = get(
                        "$BASE/auth/user/friends?offset=$offset&n=$pageSize&offline=$offline",
                        null, cookieHeader
                    )
                    if (code == 429) {
                        Log.w(TAG, "fetchFriends rate limited, waiting 5s")
                        kotlinx.coroutines.delay(5000)
                        continue
                    }
                    if (code != 200) {
                        Log.w(TAG, "fetchFriends(offline=$offline) page offset=$offset returned $code")
                        break
                    }
                    val arr = org.json.JSONArray(body)
                    if (arr.length() == 0) break
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val id = obj.optString("id")
                        val name = obj.optString("displayName")
                        if (id.isNotBlank()) {
                            seen[id] = VrcFriend(
                                userId = id,
                                displayName = name,
                                status = obj.optString("status", ""),
                                statusDescription = obj.optString("statusDescription", ""),
                                location = obj.optString("location", ""),
                                avatarThumb = obj.optString("currentAvatarThumbnailImageUrl", ""),
                                bio = obj.optString("bio", ""),
                                trustRank = extractTrustRankFromTags(obj.optJSONArray("tags"))
                            )
                        }
                    }
                    if (arr.length() < pageSize) break
                    offset += pageSize
                    kotlinx.coroutines.delay(500)
                }
            } catch (e: Exception) {
                Log.e(TAG, "fetchFriends(offline=$offline) failed", e)
            }
        }
        Log.i(TAG, "fetchFriends total: ${seen.size}")
        seen.values.toList()
    }

    // ------------------------------------------------------------------
    // REST helpers for offline notification backfill
    // ------------------------------------------------------------------

    suspend fun fetchPendingNotifications(context: Context): org.json.JSONArray? = withContext(Dispatchers.IO) {
        val cookieHeader = getCookieHeader(context) ?: return@withContext null
        try {
            val (code, body, _) = get(
                "$BASE/auth/user/notifications?type=all&hidden=false&n=100",
                null, cookieHeader
            )
            if (code == 200) org.json.JSONArray(body) else null
        } catch (e: Exception) {
            Log.w(TAG, "fetchPendingNotifications failed", e)
            null
        }
    }

    suspend fun fetchPendingNotificationsV2(context: Context): org.json.JSONArray? = withContext(Dispatchers.IO) {
        val cookieHeader = getCookieHeader(context) ?: return@withContext null
        try {
            val (code, body, _) = get(
                "$BASE/auth/user/notifications/v2?n=50",
                null, cookieHeader
            )
            if (code == 200) org.json.JSONArray(body) else null
        } catch (e: Exception) {
            Log.w(TAG, "fetchPendingNotificationsV2 failed", e)
            null
        }
    }

    suspend fun fetchUserGroups(context: Context): org.json.JSONArray? = withContext(Dispatchers.IO) {
        val userId = getStoredUserId(context) ?: return@withContext null
        val cookieHeader = getCookieHeader(context) ?: return@withContext null
        try {
            val (code, body, _) = get(
                "$BASE/users/$userId/groups?n=50",
                null, cookieHeader
            )
            if (code == 200) org.json.JSONArray(body) else null
        } catch (e: Exception) {
            Log.w(TAG, "fetchUserGroups failed", e)
            null
        }
    }

    suspend fun fetchGroupName(context: Context, groupId: String): String? = withContext(Dispatchers.IO) {
        if (groupId.isBlank()) return@withContext null
        val cookieHeader = getCookieHeader(context) ?: return@withContext null
        try {
            val (code, body, _) = get("$BASE/groups/$groupId", null, cookieHeader)
            if (code == 200 && body.startsWith("{")) {
                org.json.JSONObject(body).optString("name", "").takeIf { it.isNotBlank() }
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "fetchGroupName($groupId) failed", e)
            null
        }
    }

    suspend fun fetchGroupAnnouncement(context: Context, groupId: String): org.json.JSONObject? = withContext(Dispatchers.IO) {
        val cookieHeader = getCookieHeader(context) ?: return@withContext null
        try {
            val (code, body, _) = get(
                "$BASE/groups/$groupId/announcement",
                null, cookieHeader
            )
            if (code == 200 && body.startsWith("{")) org.json.JSONObject(body) else null
        } catch (e: Exception) {
            Log.w(TAG, "fetchGroupAnnouncement($groupId) failed", e)
            null
        }
    }

    suspend fun fetchGroupPosts(context: Context, groupId: String, n: Int = 10): org.json.JSONArray? = withContext(Dispatchers.IO) {
        val cookieHeader = getCookieHeader(context) ?: return@withContext null
        try {
            val (code, body, _) = get(
                "$BASE/groups/$groupId/posts?n=$n",
                null, cookieHeader
            )
            if (code != 200) return@withContext null
            // VRChat wraps posts in an object: {"posts":[...],"total":N}.
            // Older/edge responses may return a bare array — handle both.
            when {
                body.startsWith("[") -> org.json.JSONArray(body)
                body.startsWith("{") -> org.json.JSONObject(body).optJSONArray("posts")
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchGroupPosts($groupId) failed", e)
            null
        }
    }

    // Group calendar events. VRChat exposes group events at
    // GET /groups/{groupId}/calendar — used to backfill events created while
    // the app was closed (they don't reliably appear in the per-user
    // notifications-v2 feed). Response may be a bare array or an object
    // wrapping the list under "results"/"events".
    suspend fun fetchGroupCalendarEvents(context: Context, groupId: String, n: Int = 20): org.json.JSONArray? = withContext(Dispatchers.IO) {
        val cookieHeader = getCookieHeader(context) ?: return@withContext null
        val endpoints = arrayOf(
            "$BASE/groups/$groupId/events?n=$n",
            "$BASE/groups/$groupId/scheduledEvents?n=$n",
            "$BASE/groups/$groupId/calendar?n=$n"
        )
        for (url in endpoints) {
            try {
                val (code, body, _) = get(url, null, cookieHeader)
                Log.i(TAG, "fetchGroupCalendarEvents($groupId) url=${url.substringAfter("groups/")} http=$code bodyHead=${body.take(80)}")
                if (code == 404) continue
                if (code != 200) continue
                val result = when {
                    body.startsWith("[") -> org.json.JSONArray(body)
                    body.startsWith("{") -> {
                        val obj = org.json.JSONObject(body)
                        obj.optJSONArray("results")
                            ?: obj.optJSONArray("events")
                            ?: obj.optJSONArray("calendarEvents")
                            ?: obj.optJSONArray("scheduledEvents")
                    }
                    else -> null
                }
                if (result != null && result.length() > 0) return@withContext result
            } catch (e: Exception) {
                Log.w(TAG, "fetchGroupCalendarEvents($groupId) ${url.substringAfterLast("/")} failed", e)
            }
        }
        null
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    private fun extractTrustRankFromTags(tags: org.json.JSONArray?): String {
        if (tags == null) return ""
        val ranks = listOf(
            "system_trust_legend",
            "system_trust_veteran",
            "system_trust_trusted",
            "system_trust_known",
            "system_trust_basic"
        )
        for (rank in ranks) {
            for (i in 0 until tags.length()) {
                if (tags.optString(i) == rank) return rank
            }
        }
        return ""
    }

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
        val now = System.currentTimeMillis()
        val editor = getPrefs(context)?.edit() ?: return
        editor.putString(KEY_AUTH_COOKIE, authCookie)
        editor.putString(KEY_USER_ID, userId)
        editor.putString(KEY_DISPLAY_NAME, displayName)
        editor.putLong(KEY_COOKIE_STORED_AT, now)
        if (twoFaCookie != null) {
            editor.putString(KEY_2FA_COOKIE, twoFaCookie)
            editor.putLong(KEY_2FA_COOKIE_STORED_AT, now)
        }
        editor.apply()
    }

    fun diagnoseAuthState(context: Context) {
        val prefs = getPrefs(context)
        if (prefs == null) {
            Log.e(TAG, "diagnoseAuthState: getPrefs returned null — EncryptedSharedPreferences broken")
            return
        }
        val hasAuth = prefs.getString(KEY_AUTH_COOKIE, null) != null
        val has2fa = prefs.getString(KEY_2FA_COOKIE, null) != null
        val hasUser = prefs.getString(KEY_USER_ID, null) != null
        val hasCreds = prefs.getString(KEY_USERNAME, null) != null && prefs.getString(KEY_PASSWORD, null) != null
        val cookieAge = System.currentTimeMillis() - prefs.getLong(KEY_COOKIE_STORED_AT, 0L)
        val twoFaAge = System.currentTimeMillis() - prefs.getLong(KEY_2FA_COOKIE_STORED_AT, 0L)
        Log.i(TAG, "Auth state: authCookie=$hasAuth, 2faCookie=$has2fa, userId=$hasUser, credentials=$hasCreds, cookieAgeHrs=${cookieAge/3600000}, 2faAgeHrs=${twoFaAge/3600000}")
    }

    /**
     * Verifies whether the current user is still friends with [userId] by
     * directly fetching `/users/{userId}` and reading the `isFriend` field.
     *
     * Returns:
     *   true  — confirmed still friends (suppress unfriend notification)
     *   false — confirmed not friends (fire unfriend notification)
     *   null  — couldn't verify (network error, rate limit, expired session, etc.)
     *
     * Callers should treat null as "fall back to existing heuristic" rather
     * than silently dropping the notification — we don't want a transient
     * network blip to mask real unfriends.
     */
    suspend fun verifyStillFriend(context: Context, userId: String): Boolean? = withContext(Dispatchers.IO) {
        if (userId.isBlank()) return@withContext null
        val cookieHeader = getCookieHeader(context) ?: return@withContext null
        try {
            val (code, body, _) = get("$BASE/users/$userId", null, cookieHeader)
            when {
                code == 200 -> {
                    val json = JSONObject(body)
                    if (json.has("isFriend")) json.optBoolean("isFriend", false) else null
                }
                code == 404 -> false  // user blocked us / deleted account → effectively unfriended
                else -> null  // 401, 429, 5xx, etc. — can't tell
            }
        } catch (e: Exception) {
            Log.w(TAG, "verifyStillFriend $userId failed", e)
            null
        }
    }

    private fun get(
        url: String,
        authHeader: String?,
        cookieHeader: String?
    ): Triple<Int, String, List<String>> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            useCaches = false
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-cache")
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
