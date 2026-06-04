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
    private const val KEY_PROFILE_PIC  = "vrchat_profile_pic"
    private const val KEY_COOKIE_STORED_AT = "cookie_stored_at_ms"
    private const val KEY_USERNAME = "vrchat_username"
    private const val KEY_PASSWORD = "vrchat_password"

    private const val COOKIE_REFRESH_MS = 12L * 24 * 60 * 60 * 1000
    // Timestamp of when the trusted-device 2FA cookie was last (re)issued. Kept
    // for diagnostics only — the cookie is ALWAYS sent regardless of age (VRChat
    // is the authority on trusted-device validity; see getCookieHeader).
    private const val KEY_2FA_COOKIE_STORED_AT = "twofa_cookie_stored_at_ms"

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

    /** The user's VRChat+ custom profile picture URL (blank without VRChat+). */
    fun getStoredProfilePic(context: Context): String =
        getPrefs(context)?.getString(KEY_PROFILE_PIC, "")?.trim().orEmpty()

    /**
     * Cheap one-shot refresh of the stored VRChat+ profile picture via /auth/user.
     * Lets the admin directory show a logged-in user's pfp WITHOUT needing to
     * actively watch them (the watched presence sync is otherwise the only writer).
     * No-op if not logged in or the call fails.
     */
    suspend fun refreshProfilePic(context: Context): String = withContext(Dispatchers.IO) {
        val cookieHeader = getCookieHeader(context) ?: return@withContext ""
        try {
            val (code, body, rawCookies) = get("$BASE/auth/user", null, cookieHeader)
            if (code != 200) return@withContext ""
            captureRolledCookies(context, rawCookies)
            val json = JSONObject(body)
            val pic = json.optString("profilePicOverride", "")
                .ifBlank { json.optString("userIcon", "") }
            // Store whatever we got (including blank → no VRChat+, so we don't
            // keep re-fetching a value that will never appear).
            getPrefs(context)?.edit()?.putString(KEY_PROFILE_PIC, pic)?.apply()
            pic
        } catch (e: Exception) {
            Log.w(TAG, "refreshProfilePic failed", e)
            ""
        }
    }

    /**
     * On-demand profile-pic resolution by VRChat userId, using THIS device's
     * session (used by the admin directory so it can show any user's VRChat+
     * picture without that picture ever being stored in Firestore). Returns the
     * `profilePicOverride` (falling back to `userIcon`) URL, or "" if the user
     * has no VRChat+ pic / we're not logged in / the fetch failed.
     *
     * Results (including blank) are cached per-userId for the process lifetime so
     * scrolling the directory doesn't re-hit `GET /users/{id}` and risk rate
     * limits — the caller is expected to only request VISIBLE rows.
     */
    private val profilePicUrlCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    suspend fun fetchProfilePicUrl(context: Context, userId: String): String =
        withContext(Dispatchers.IO) {
            if (userId.isBlank()) return@withContext ""
            profilePicUrlCache[userId]?.let { return@withContext it }
            val cookieHeader = getCookieHeader(context) ?: return@withContext ""
            try {
                val (code, body, rawCookies) = get("$BASE/users/$userId", null, cookieHeader)
                if (code != 200) return@withContext ""
                captureRolledCookies(context, rawCookies)
                val json = JSONObject(body)
                val pic = json.optString("profilePicOverride", "")
                    .ifBlank { json.optString("userIcon", "") }
                profilePicUrlCache[userId] = pic // cache blank too — no refetch storm
                pic
            } catch (e: Exception) {
                Log.w(TAG, "fetchProfilePicUrl failed for $userId", e)
                ""
            }
        }

    /** Live instance occupancy from a single `GET /instances/{location}` call. */
    data class InstanceCount(val players: Int, val capacity: Int)

    /**
     * Derives the in-instance headcount from a `/instances/{loc}` JSON body.
     *
     * VRChat exposes TWO different counts on this object and they routinely
     * disagree by a few users:
     *  - `n_users` is what the **in-game client's** instance panel shows (the
     *    number the player and everyone in the instance actually see in-headset).
     *  - the per-platform breakdown (`platforms`: standalonewindows / android /
     *    ios) sums to what the **website / VRCX** show — it counts users who are
     *    mid-join / in-transit that `n_users` doesn't yet, so it skews a few HIGH.
     *
     * No single field matches both surfaces. We match the **in-game client**
     * (the truest source for the user) by preferring `n_users`, and only fall
     * back to the platforms sum / `userCount` when `n_users` is absent.
     */
    private fun extractInstanceUserCount(inst: JSONObject): Int {
        // --- DIAGNOSTIC (temporary): log EVERY candidate count so we can see
        // which field matches the in-game instance panel. Capture with:
        //   adb logcat -s VrchatAuth | grep INSTANCE_COUNT_DEBUG
        // then compare against the number VRChat shows in-game at that moment.
        val platformsObj = inst.optJSONObject("platforms")
        var platformsSum = 0
        val platformParts = StringBuilder()
        if (platformsObj != null) {
            val k = platformsObj.keys()
            while (k.hasNext()) {
                val key = k.next()
                val v = platformsObj.optInt(key, 0)
                platformsSum += v
                platformParts.append(key).append('=').append(v).append(' ')
            }
        }
        Log.w(
            TAG,
            "INSTANCE_COUNT_DEBUG n_users=${inst.optInt("n_users", -1)} " +
                "userCount=${inst.optInt("userCount", -1)} " +
                "platformsSum=$platformsSum [${platformParts.toString().trim()}] " +
                "queueSize=${inst.optInt("queueSize", -1)} " +
                "capacity=${inst.optInt("capacity", -1)} " +
                "recommendedCapacity=${inst.optInt("recommendedCapacity", -1)}"
        )
        // --- END DIAGNOSTIC

        val nUsers = inst.optInt("n_users", -1)
        if (nUsers >= 0) return nUsers

        if (platformsObj != null) return platformsSum
        return inst.optInt("userCount", 0)
    }

    /**
     * Lightweight single-call instance occupancy fetch — hits ONLY
     * `GET /instances/{location}` and reads the live player count (see
     * [extractInstanceUserCount]) / `capacity`. This is what
     * the VRChat website does on a tab refresh (one request, instant), unlike the
     * full 3-call [fetchPresence] chain (`/auth/user` -> `/users/{id}` ->
     * `/instances`) whose count only refreshes when the WHOLE chain lands —
     * minutes apart on mobile under cookie IP-invalidation / rate-limit churn.
     * Returns null when not in a joinable world instance or on any failure (the
     * caller keeps the previously-known count). [location] is the raw
     * `{worldId}:{instanceId}` string.
     */
    suspend fun fetchInstanceCount(context: Context, location: String): InstanceCount? =
        withContext(Dispatchers.IO) {
            val loc = location.trim()
            if (loc.isBlank() || !loc.startsWith("wrld_") ||
                loc == "offline" || loc == "private" || loc == "traveling"
            ) return@withContext null
            val cookieHeader = getCookieHeader(context) ?: return@withContext null
            try {
                val (code, body, rawCookies) = get("$BASE/instances/$loc", null, cookieHeader)
                if (code != 200) return@withContext null
                captureRolledCookies(context, rawCookies)
                val inst = JSONObject(body)
                InstanceCount(extractInstanceUserCount(inst), inst.optInt("capacity", 0))
            } catch (e: Exception) {
                Log.w(TAG, "fetchInstanceCount failed", e)
                null
            }
        }

    /**
     * Sends an invite to the caller's OWN logged-in VRChat account for [location]
     * (the raw `{worldId}:{instanceId}` string). Mirrors the website's "Invite Me"
     * button (`POST /invite/myself/to/{location}`) — works for invite-only /
     * friends+ / group instances. The instance's occupant is NOT notified; the
     * invite lands only on the caller's account. Admin-only use. Returns true on
     * HTTP 200.
     */
    suspend fun inviteSelfToInstance(context: Context, location: String): Boolean =
        withContext(Dispatchers.IO) {
            val loc = location.trim()
            if (loc.isBlank() || !loc.startsWith("wrld_") ||
                loc == "offline" || loc == "private" || loc == "traveling"
            ) return@withContext false
            val cookieHeader = getCookieHeader(context) ?: return@withContext false
            try {
                val (code, _, rawCookies) = post("$BASE/invite/myself/to/$loc", "", cookieHeader)
                if (code == 200) {
                    captureRolledCookies(context, rawCookies)
                    true
                } else {
                    Log.w(TAG, "inviteSelfToInstance returned $code for $loc")
                    false
                }
            } catch (e: Exception) {
                Log.w(TAG, "inviteSelfToInstance failed", e)
                false
            }
        }

    /**
     * Headers required to LOAD an auth-gated VRChat image (`api.vrchat.cloud`
     * file/image URLs require the session cookie + a User-Agent). Returned to the
     * Coil image loader so the admin's session can render other users' VRChat+
     * pictures. Null when not logged in.
     */
    fun vrchatImageHeaders(context: Context): Map<String, String>? {
        val cookie = getCookieHeader(context) ?: return null
        return mapOf("Cookie" to cookie, "User-Agent" to USER_AGENT)
    }

    /**
     * Collapse an accidental double name-prefix in a stored cookie, e.g. a
     * historically mis-stored `"twoFactorAuth=twoFactorAuth=VALUE"` becomes
     * `"twoFactorAuth=VALUE"`. This is migration self-healing for the old
     * verify2FA bug that re-wrapped extractCookieValue's already-prefixed
     * return — VRChat rejected the malformed trusted-device cookie and forced a
     * fresh 2FA prompt after every global logout. Returns the cleaned value (or
     * null if input was null). Safe/idempotent on already-correct cookies.
     */
    private fun normalizeCookie(raw: String?, name: String): String? {
        if (raw == null) return null
        var v = raw.trim()
        val prefix = "$name="
        while (v.startsWith(prefix, ignoreCase = true) &&
               v.substring(prefix.length).startsWith(prefix, ignoreCase = true)) {
            v = v.substring(prefix.length)
        }
        return v
    }

    /**
     * Read a stored cookie, self-heal any double-prefix in place (persist the
     * corrected value so the fix is permanent across all read paths, including
     * the WebSocket), and return the clean `name=value` pair.
     */
    private fun readCookie(prefs: android.content.SharedPreferences, key: String, name: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        val clean = normalizeCookie(stored, name) ?: return null
        if (clean != stored) {
            prefs.edit().putString(key, clean).apply()
            Log.i(TAG, "Self-healed malformed $name cookie (collapsed double prefix)")
        }
        return clean
    }

    fun getCookieHeader(context: Context): String? {
        val prefs = getPrefs(context) ?: return null
        val auth  = readCookie(prefs, KEY_AUTH_COOKIE, "auth")
        // ALWAYS send the trusted-device 2FA cookie if we have one — never drop
        // it on a client-side age guess. VRChat's web client keeps a remembered
        // device trusted indefinitely (and rolls it forward on use); a hard
        // client-side cutoff only ever HURTS — it forces a 2FA prompt VRChat
        // itself would not have asked for. Sending a stale cookie is harmless:
        // worst case VRChat ignores it and returns requiresTwoFactorAuth, which
        // is exactly what dropping it would have produced. Let the SERVER decide.
        val twoFa = readCookie(prefs, KEY_2FA_COOKIE, "twoFactorAuth")
        return when {
            auth != null && twoFa != null -> "$auth; $twoFa"
            auth != null                  -> auth
            twoFa != null                 -> twoFa
            else                          -> null
        }
    }

    /**
     * Cookie header for the Basic-auth re-login path. Sends ONLY the trusted-
     * device 2FA cookie — never the saved auth cookie. An expired auth cookie
     * sent alongside Basic credentials can cause VRChat to reject the request
     * as a session conflict, even though the twoFactorAuth cookie alone lets
     * the new login skip the 2FA prompt. The cookie is sent regardless of age:
     * VRChat is the authority on whether the trusted device is still valid, so
     * we never proactively drop it (which would force an avoidable 2FA prompt).
     */
    private fun getTwoFaOnlyCookieHeader(context: Context): String? {
        val prefs = getPrefs(context) ?: return null
        return readCookie(prefs, KEY_2FA_COOKIE, "twoFactorAuth")
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

                // VRChat requires the username and password to each be
                // URI-encoded (encodeURIComponent-style) BEFORE base64 for Basic
                // auth — its backend URL-decodes them. Passing raw bytes breaks
                // any credential containing @ + # : & % etc., which VRChat then
                // rejects with 401 → auto-relogin dies → forced manual 2FA.
                val credentials = Base64.getEncoder()
                    .encodeToString("${encodeUriComponent(username)}:${encodeUriComponent(password)}".toByteArray(Charsets.UTF_8))

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
                        // VRChat+ custom profile picture (blank without VRChat+).
                        val profilePic = json.optString("profilePicOverride", "")
                            .ifBlank { json.optString("userIcon", "") }

                        if (authCookieValue != null && userId.isNotBlank()) {
                            // Update the auth cookie + user info. If VRChat re-issued
                            // a twoFactorAuth (trusted-device) cookie on this login,
                            // capture it and RESET its 30-day clock so the trusted
                            // window keeps extending. If it did NOT (the common case
                            // for a bypass login), leave the existing 2FA cookie and
                            // its original stored-at untouched so future auto-relogins
                            // still work.
                            val now = System.currentTimeMillis()
                            val newTwoFa = rawCookies
                                .mapNotNull { extractCookieValue(it, "twoFactorAuth") }
                                .firstOrNull()
                            val editor = getPrefs(context)?.edit()
                                ?.putString(KEY_AUTH_COOKIE, authCookieValue)
                                ?.putString(KEY_USER_ID, userId)
                                ?.putString(KEY_DISPLAY_NAME, displayName)
                                ?.putLong(KEY_COOKIE_STORED_AT, now)
                            if (profilePic.isNotBlank()) editor?.putString(KEY_PROFILE_PIC, profilePic)
                            if (newTwoFa != null) {
                                editor?.putString(KEY_2FA_COOKIE, newTwoFa)
                                    ?.putLong(KEY_2FA_COOKIE_STORED_AT, now)
                            }
                            editor?.apply()
                            AuthResult.Success(userId, displayName)
                        } else {
                            // Unusual: 200 but no id or cookie - log the body for diagnosis
                            Log.w(TAG, "200 but no id/cookie. body=${body.take(300)}")
                            AuthResult.Error("Login response missing user data. Try again.")
                        }
                    }

                    401 -> {
                        // A 401 is NOT always wrong credentials. VRChat also returns
                        // 401 for an IP-invalidated / conflicting session ("authToken
                        // doesn't correspond with an active session"). Only wipe the
                        // saved credentials when the error clearly says the credentials
                        // are bad — otherwise keep them so auto-relogin can recover the
                        // session instead of forcing a full manual login (with 2FA).
                        val msg = try {
                            JSONObject(body).optJSONObject("error")?.optString("message") ?: body
                        } catch (_: Exception) { body }
                        val badCreds = msg.contains("invalid", true) ||
                            msg.contains("incorrect", true) ||
                            msg.contains("credential", true) ||
                            msg.contains("password", true) ||
                            msg.contains("username", true)
                        if (badCreds) {
                            clearCredentials(context)
                            AuthResult.Error("Incorrect username or password.")
                        } else {
                            Log.w(TAG, "401 (non-credential, keeping creds): ${msg.take(140)}")
                            AuthResult.Error("Session expired — retrying.")
                        }
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
                    // extractCookieValue already returns the full "twoFactorAuth=VALUE"
                    // pair, ready to drop straight into a Cookie header / storage.
                    // Do NOT re-wrap it as "twoFactorAuth=$it" — that produces the
                    // malformed "twoFactorAuth=twoFactorAuth=VALUE" the trusted-device
                    // check rejects, which forced a fresh 2FA prompt after every
                    // VRChat global logout even though the website would not have asked.
                    val twoFaCookieValue = rawCookies
                        .mapNotNull { extractCookieValue(it, "twoFactorAuth") }
                        .firstOrNull()

                    val cookieHeader = if (twoFaCookieValue != null)
                        "$partialCookie; $twoFaCookieValue"
                    else partialCookie

                    // Fetch user info with full cookie set
                    val (userCode, userBody, userCookies) = get(
                        url = "$BASE/auth/user",
                        authHeader = null,
                        cookieHeader = cookieHeader
                    )
                    if (userCode == 200) captureRolledCookies(context, userCookies)

                    if (userCode == 200) {
                        val json = JSONObject(userBody)
                        val userId = json.optString("id")
                        val displayName = json.optString("displayName")

                        if (userId.isNotBlank()) {
                            saveSession(context, partialCookie,
                                twoFaCookieValue,
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
            val (code, _, rawCookies) = get("$BASE/auth", null, cookieHeader)
            if (code == 200) captureRolledCookies(context, rawCookies)
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
        val worldImageUrl: String = "",
        // VRChat+ custom profile picture (profilePicOverride, falling back to
        // userIcon). Blank when the user has no VRChat+ custom picture — in that
        // case VRChat itself just renders their name letters.
        val profilePicUrl: String = ""
    )

    suspend fun fetchPresence(context: Context): VrcUserPresence? = withContext(Dispatchers.IO) {
        val cookieHeader = getCookieHeader(context) ?: run {
            Log.w(TAG, "fetchPresence: no cookie header available")
            return@withContext null
        }
        try {
            val (code, body, rawCookies) = get("$BASE/auth/user", null, cookieHeader)
            if (code != 200) {
                Log.w(TAG, "fetchPresence: API returned $code, body=${body.take(200)}")
                return@withContext null
            }
            // Roll the trusted-device / auth cookies forward if VRChat re-issued
            // them — keeps the session "remembered" so it never forces a fresh 2FA.
            captureRolledCookies(context, rawCookies)
            val json = JSONObject(body)
            val userId = json.optString("id")
            var state = json.optString("state", "offline")
            var location = json.optString("location", "offline")
            var status = json.optString("status", "offline")
            var statusDescription = json.optString("statusDescription", "")
            var platform = json.optString("last_platform", "")
            var displayName = json.optString("displayName")
            var avatarThumb = json.optString("currentAvatarThumbnailImageUrl", "")
            // VRChat+ profile picture: profilePicOverride wins, then userIcon.
            var profilePic = json.optString("profilePicOverride", "")
                .ifBlank { json.optString("userIcon", "") }

            Log.d(TAG, "fetchPresence /auth/user: state=$state status=$status location=$location")

            // /auth/user can return stale presence when session was created by a
            // companion app rather than the VRChat game client. Fetch the specific
            // user endpoint which reflects the true live state.
            if (userId.isNotBlank()) {
                try {
                    val (uCode, uBody, uCookies) = get("$BASE/users/$userId", null, cookieHeader)
                    if (uCode == 200) {
                        captureRolledCookies(context, uCookies)
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
                        // The /users/{id} endpoint is the authoritative source for
                        // the VRChat+ profile picture fields.
                        uj.optString("profilePicOverride", "").let { if (it.isNotBlank()) profilePic = it }
                        if (profilePic.isBlank())
                            uj.optString("userIcon", "").let { if (it.isNotBlank()) profilePic = it }
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
                    val (wCode, wBody, wCookies) = get("$BASE/instances/$location", null, cookieHeader)
                    if (wCode == 200) {
                        captureRolledCookies(context, wCookies)
                        val inst = JSONObject(wBody)
                        playerCount = extractInstanceUserCount(inst)
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

            // Persist the profile pic so self-sync can include it even when the
            // user isn't currently being watched (the directory needs it).
            if (profilePic.isNotBlank()) {
                getPrefs(context)?.edit()?.putString(KEY_PROFILE_PIC, profilePic)?.apply()
            }

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
                worldImageUrl = worldImageUrl,
                profilePicUrl = profilePic
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
                    val (code, body, rawCookies) = get(
                        "$BASE/auth/user/friends?offset=$offset&n=$pageSize&offline=$offline",
                        null, cookieHeader
                    )
                    if (code == 200) captureRolledCookies(context, rawCookies)
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
            val (code, body, rawCookies) = get(
                "$BASE/auth/user/notifications?type=all&hidden=false&n=100",
                null, cookieHeader
            )
            if (code == 200) { captureRolledCookies(context, rawCookies); org.json.JSONArray(body) } else null
        } catch (e: Exception) {
            Log.w(TAG, "fetchPendingNotifications failed", e)
            null
        }
    }

    suspend fun fetchPendingNotificationsV2(context: Context): org.json.JSONArray? = withContext(Dispatchers.IO) {
        val cookieHeader = getCookieHeader(context) ?: return@withContext null
        try {
            val (code, body, rawCookies) = get(
                "$BASE/auth/user/notifications/v2?n=50",
                null, cookieHeader
            )
            if (code == 200) { captureRolledCookies(context, rawCookies); org.json.JSONArray(body) } else null
        } catch (e: Exception) {
            Log.w(TAG, "fetchPendingNotificationsV2 failed", e)
            null
        }
    }

    suspend fun fetchUserGroups(context: Context): org.json.JSONArray? = withContext(Dispatchers.IO) {
        val userId = getStoredUserId(context) ?: return@withContext null
        val cookieHeader = getCookieHeader(context) ?: return@withContext null
        try {
            val (code, body, rawCookies) = get(
                "$BASE/users/$userId/groups?n=50",
                null, cookieHeader
            )
            if (code == 200) { captureRolledCookies(context, rawCookies); org.json.JSONArray(body) } else null
        } catch (e: Exception) {
            Log.w(TAG, "fetchUserGroups failed", e)
            null
        }
    }

    suspend fun fetchGroupName(context: Context, groupId: String): String? = withContext(Dispatchers.IO) {
        if (groupId.isBlank()) return@withContext null
        val cookieHeader = getCookieHeader(context) ?: return@withContext null
        try {
            val (code, body, rawCookies) = get("$BASE/groups/$groupId", null, cookieHeader)
            if (code == 200) captureRolledCookies(context, rawCookies)
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
            val (code, body, rawCookies) = get(
                "$BASE/groups/$groupId/announcement",
                null, cookieHeader
            )
            if (code == 200) captureRolledCookies(context, rawCookies)
            if (code == 200 && body.startsWith("{")) org.json.JSONObject(body) else null
        } catch (e: Exception) {
            Log.w(TAG, "fetchGroupAnnouncement($groupId) failed", e)
            null
        }
    }

    suspend fun fetchGroupPosts(context: Context, groupId: String, n: Int = 10): org.json.JSONArray? = withContext(Dispatchers.IO) {
        val cookieHeader = getCookieHeader(context) ?: return@withContext null
        try {
            val (code, body, rawCookies) = get(
                "$BASE/groups/$groupId/posts?n=$n",
                null, cookieHeader
            )
            if (code != 200) return@withContext null
            captureRolledCookies(context, rawCookies)
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
        // Correct VRChat group-calendar endpoint is GET /calendar/{groupId}
        // (returns {"results":[...]}). The older /groups/{id}/events paths 404,
        // which is why events created while the app was closed never surfaced.
        val endpoints = arrayOf(
            "$BASE/calendar/$groupId?n=$n",
            "$BASE/groups/$groupId/events?n=$n",
            "$BASE/groups/$groupId/calendar?n=$n"
        )
        for (url in endpoints) {
            try {
                val (code, body, rawCookies) = get(url, null, cookieHeader)
                Log.i(TAG, "fetchGroupCalendarEvents($groupId) url=${url.substringAfter("groups/")} http=$code bodyHead=${body.take(80)}")
                if (code == 404) continue
                if (code != 200) continue
                captureRolledCookies(context, rawCookies)
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

    /**
     * JS encodeURIComponent-equivalent. Java's URLEncoder uses
     * application/x-www-form-urlencoded (space → "+", and it escapes
     * !'()~ while leaving *-._ alone), so we fix those up to match what
     * VRChat's backend expects when it URI-decodes the Basic-auth credentials.
     */
    private fun encodeUriComponent(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")
            .replace("+", "%20")
            .replace("%21", "!")
            .replace("%27", "'")
            .replace("%28", "(")
            .replace("%29", ")")
            .replace("%7E", "~")

    /**
     * Roll the stored `auth` and `twoFactorAuth` cookies forward from ANY
     * authenticated response that re-issues them.
     *
     * VRChat's web client stays "trusted" (no repeat 2FA) indefinitely because
     * the server rolls the `twoFactorAuth` cookie forward every time the client
     * uses it — the website never lets it reach its ~30-day Max-Age. The app used
     * to capture that rotation ONLY on the explicit login / verify paths and threw
     * away the Set-Cookie on every other authenticated call (`fetchPresence`,
     * `validateSession`, `refreshProfilePic`, …). So the app's stored trusted-device
     * cookie simply aged out, and ~30 days after the last login VRChat demanded a
     * fresh 2FA code even though the website would not. Calling this after every
     * authenticated success keeps the app's cookie as fresh as the browser's.
     *
     * It is strictly additive: it only overwrites when a NON-blank refreshed cookie
     * is actually present in the response, and resets that cookie's stored-at clock.
     */
    private fun captureRolledCookies(context: Context, rawCookies: List<String>) {
        if (rawCookies.isEmpty()) return
        val auth = rawCookies.mapNotNull { extractCookieValue(it, "auth") }.firstOrNull()
        val twoFa = rawCookies.mapNotNull { extractCookieValue(it, "twoFactorAuth") }.firstOrNull()
        if (auth == null && twoFa == null) return
        val editor = getPrefs(context)?.edit() ?: return
        val now = System.currentTimeMillis()
        if (auth != null) {
            editor.putString(KEY_AUTH_COOKIE, auth).putLong(KEY_COOKIE_STORED_AT, now)
        }
        if (twoFa != null) {
            editor.putString(KEY_2FA_COOKIE, twoFa).putLong(KEY_2FA_COOKIE_STORED_AT, now)
            Log.d(TAG, "Rolled twoFactorAuth cookie forward (trusted-device window extended)")
        }
        editor.apply()
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
            val (code, body, rawCookies) = get("$BASE/users/$userId", null, cookieHeader)
            when {
                code == 200 -> {
                    captureRolledCookies(context, rawCookies)
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
