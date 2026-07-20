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

    // Emitted on every successful manual login (Basic-auth success or 2FA verify
    // success). Used by VrcaViewModel to lift the VRChat-logout OSC gate and by
    // VrcaApp to re-run the Phase-2 ban check after a Settings re-login.
    private val _loggedInSignal = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val loggedInSignal: kotlinx.coroutines.flow.SharedFlow<Unit> = _loggedInSignal

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
            // userIcon is the round profile picture; profilePicOverride is the
            // wide BANNER — only fall back to it when no icon is set.
            val pic = json.optString("userIcon", "")
                .ifBlank { json.optString("profilePicOverride", "") }
            // Store whatever we got (including blank → no VRChat+, so we don't
            // keep re-fetching a value that will never appear).
            getPrefs(context)?.edit()?.putString(KEY_PROFILE_PIC, pic)?.apply()
            pic
        } catch (e: Exception) {
            Log.w(TAG, "refreshProfilePic failed", e)
            ""
        }
    }

    /** Live instance occupancy from a single `GET /instances/{location}` call. */
    data class InstanceCount(val players: Int, val capacity: Int)

    /**
     * Derives the in-instance headcount from a `/instances/{loc}` JSON body.
     *
     * VRChat exposes THREE candidate counts and they disagree by a few users:
     *  - `userCount` is what the **in-game client's** instance panel shows (the
     *    number the player and everyone in the instance actually see in-headset).
     *  - `n_users` and the per-platform breakdown (`platforms`: standalonewindows
     *    / android / ios, which sums to the **website / VRCX** number) AGREE with
     *    each other but run a few HIGH — they count users mid-join / in-transit /
     *    timing-out that the in-game client has already dropped.
     *
     * Confirmed empirically (debug overlay vs the in-game panel: userCount=36 ==
     * in-game, while n_users=47 and platformsSum=47 both over-counted). We match
     * the in-game client by preferring `userCount`, falling back to `n_users`
     * then the platforms sum only when it is absent.
     */
    private fun extractInstanceUserCount(inst: JSONObject): Int {
        val userCount = inst.optInt("userCount", -1)
        if (userCount >= 0) return userCount

        val nUsers = inst.optInt("n_users", -1)
        if (nUsers >= 0) return nUsers

        val platforms = inst.optJSONObject("platforms")
        if (platforms != null) {
            var sum = 0
            val keys = platforms.keys()
            while (keys.hasNext()) {
                sum += platforms.optInt(keys.next(), 0)
            }
            return sum
        }
        return 0
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
     * Richer instance snapshot for the invite/history instance-list UI: world name +
     * thumbnail, live occupancy, and a joinability [status] derived from VRChat's HTTP
     * response (open / closed-dead / not accessible). Fetched once when a menu opens.
     */
    data class InstanceInfo(
        val location: String,
        val worldName: String,
        val worldImageUrl: String,
        val players: Int,
        val capacity: Int,
        val status: InstanceStatus,
        val instanceType: String = "",
        val ownerId: String = "",
        val groupId: String = ""
    )

    enum class InstanceStatus { OPEN, CLOSED, INACCESSIBLE, UNKNOWN }

    /**
     * Single `GET /instances/{location}` that returns world name/image + occupancy +
     * a joinability status. 200 = OPEN, 404 = CLOSED (dead), 403 = INACCESSIBLE
     * (invite/invite+ you can't self-serve). Uses the caller's own VRChat session.
     */
    suspend fun fetchInstanceInfo(context: Context, location: String): InstanceInfo =
        withContext(Dispatchers.IO) {
            val loc = location.trim()
            val unknown = InstanceInfo(loc, "", "", 0, 0, InstanceStatus.UNKNOWN)
            if (loc.isBlank() || !loc.startsWith("wrld_") ||
                loc == "offline" || loc == "private" || loc == "traveling"
            ) return@withContext unknown.copy(status = InstanceStatus.CLOSED)
            val cookieHeader = getCookieHeader(context) ?: return@withContext unknown
            try {
                val (code, body, rawCookies) = get("$BASE/instances/$loc", null, cookieHeader)
                when (code) {
                    200 -> {
                        captureRolledCookies(context, rawCookies)
                        val inst = JSONObject(body)
                        val w = inst.optJSONObject("world")
                        val img = (w?.optString("thumbnailImageUrl", "").orEmpty())
                            .ifBlank { w?.optString("imageUrl", "").orEmpty() }
                        InstanceInfo(
                            location = loc,
                            worldName = w?.optString("name", "").orEmpty(),
                            worldImageUrl = img,
                            players = extractInstanceUserCount(inst),
                            capacity = inst.optInt("capacity", 0),
                            status = InstanceStatus.OPEN,
                            instanceType = inst.optString("type", "").lowercase(),
                            ownerId = inst.optString("ownerId", ""),
                            groupId = inst.optString("groupId", "")
                                .ifBlank { inst.optString("shortName", "").let { sn ->
                                    if (sn.startsWith("grp_")) sn else ""
                                } }
                        )
                    }
                    404 -> unknown.copy(status = InstanceStatus.CLOSED)
                    403 -> unknown.copy(status = InstanceStatus.INACCESSIBLE)
                    else -> unknown
                }
            } catch (e: Exception) {
                Log.w(TAG, "fetchInstanceInfo failed", e)
                unknown
            }
        }

    /**
     * Result of an invite call. [ok] is HTTP 200; [error] carries VRChat's own
     * human-readable reason on failure (e.g. "That instance is not accessible",
     * "instance is full"), parsed from the response body, so the UI can tell the
     * user WHY a re-invite failed (instance closed / full / not accessible)
     * instead of a generic failure.
     */
    data class InviteResult(val ok: Boolean, val error: String? = null)

    /** Extracts VRChat's `error.message` (or a bare `message`) from a response body. */
    private fun parseVrcError(body: String, code: Int): String? {
        val parsed = try {
            val obj = JSONObject(body)
            obj.optJSONObject("error")?.optString("message")?.ifBlank { null }
                ?: obj.optString("message").ifBlank { null }
        } catch (_: Exception) { null }
        // Trim VRChat's frequent wrapping quotes/whitespace.
        val cleaned = parsed?.trim()?.trim('"')?.trim()?.ifBlank { null }
        return cleaned ?: when (code) {
            404 -> "That instance has closed or no longer exists"
            403 -> "That instance is not accessible"
            else -> null
        }
    }

    /**
     * Sends an invite to the caller's OWN logged-in VRChat account for [location]
     * (the raw `{worldId}:{instanceId}` string). Mirrors the website's "Invite Me"
     * button (`POST /invite/myself/to/{location}`) — works for invite-only /
     * friends+ / group instances. The instance's occupant is NOT notified; the
     * invite lands only on the caller's account. Returns [InviteResult].
     */
    suspend fun inviteSelfToInstance(context: Context, location: String): InviteResult =
        withContext(Dispatchers.IO) {
            val loc = location.trim()
            if (loc.isBlank() || !loc.startsWith("wrld_") ||
                loc == "offline" || loc == "private" || loc == "traveling"
            ) return@withContext InviteResult(false, "That instance can't be joined")
            val cookieHeader = getCookieHeader(context)
                ?: return@withContext InviteResult(false, "Not signed in to VRChat")
            try {
                val (code, respBody, rawCookies) = post("$BASE/invite/myself/to/$loc", "", cookieHeader)
                if (code == 200) {
                    captureRolledCookies(context, rawCookies)
                    InviteResult(true)
                } else {
                    Log.w(TAG, "inviteSelfToInstance returned $code for $loc body=${respBody.take(200)}")
                    InviteResult(false, parseVrcError(respBody, code))
                }
            } catch (e: Exception) {
                Log.w(TAG, "inviteSelfToInstance failed", e)
                InviteResult(false, "Network error")
            }
        }

    /**
     * Invites [userId] to [location] (the raw `{worldId}:{instanceId}` string) —
     * used to fulfil an incoming "invite request" by inviting the requester to the
     * user's CURRENT instance. `POST /invite/{userId}` with `{instanceId}`. Returns
     * [InviteResult].
     */
    suspend fun inviteUserToInstance(context: Context, userId: String, location: String): InviteResult =
        withContext(Dispatchers.IO) {
            val uid = userId.trim()
            val loc = location.trim()
            if (uid.isBlank() || loc.isBlank() || !loc.startsWith("wrld_") ||
                loc == "offline" || loc == "private" || loc == "traveling"
            ) return@withContext InviteResult(false, "You're not in a joinable instance")
            val cookieHeader = getCookieHeader(context)
                ?: return@withContext InviteResult(false, "Not signed in to VRChat")
            try {
                val body = JSONObject().put("instanceId", loc).toString()
                val (code, respBody, rawCookies) = post("$BASE/invite/$uid", body, cookieHeader)
                if (code == 200) {
                    captureRolledCookies(context, rawCookies)
                    InviteResult(true)
                } else {
                    Log.w(TAG, "inviteUserToInstance returned $code for $uid -> $loc body=${respBody.take(200)}")
                    InviteResult(false, parseVrcError(respBody, code))
                }
            } catch (e: Exception) {
                Log.w(TAG, "inviteUserToInstance failed", e)
                InviteResult(false, "Network error")
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
        // Close out the current instance's "left" time now. A VRChat logout doesn't
        // fire a user-location:offline pipeline event (the socket just disconnects),
        // so without this the last instance stayed marked "Still here" and kept
        // counting until the user reopened VRChat and a new location event closed it.
        InstanceHistoryStore.markCurrentLeft(context)
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
                        // userIcon is the round pfp; profilePicOverride is the banner.
                        val profilePic = json.optString("userIcon", "")
                            .ifBlank { json.optString("profilePicOverride", "") }

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
                            _loggedInSignal.tryEmit(Unit)
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
                            _loggedInSignal.tryEmit(Unit)
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

    /** VALID = auth cookie accepted; UNAUTHORIZED = auth cookie definitively dead
     *  (401); UNKNOWN = inconclusive (no cookie / network error / 5xx). The
     *  distinction matters for the OSC auth-dead gate: "couldn't reach VRChat"
     *  must NEVER be treated as "session dead". */
    enum class SessionValidity { VALID, UNAUTHORIZED, UNKNOWN }

    suspend fun validateSessionDetailed(context: Context): SessionValidity = withContext(Dispatchers.IO) {
        val cookieHeader = getCookieHeader(context) ?: return@withContext SessionValidity.UNKNOWN
        try {
            val (code, _, rawCookies) = get("$BASE/auth", null, cookieHeader)
            when {
                code == 200 -> { captureRolledCookies(context, rawCookies); SessionValidity.VALID }
                code == 401 -> SessionValidity.UNAUTHORIZED
                else -> SessionValidity.UNKNOWN // 5xx / rate-limit / other = inconclusive
            }
        } catch (e: Exception) {
            Log.e(TAG, "Session validation failed", e)
            SessionValidity.UNKNOWN
        }
    }

    suspend fun validateSession(context: Context): Boolean =
        validateSessionDetailed(context) == SessionValidity.VALID

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
        // VRChat+ custom profile picture — the round `userIcon`. NOT the wide
        // profile banner (`profilePicOverride`); cropping the banner into the
        // avatar circle was the "banner used as pfp" bug. Blank when the user
        // has no VRChat+ icon — the UI renders their name initial instead.
        val profilePicUrl: String = "",
        // VRChat+ profile BANNER (`profilePicOverride`) — the wide image shown
        // at the top of the website profile. Used as the identity-card
        // background on the VRChat tab; blank for non-VRChat+ users.
        val bannerUrl: String = "",
        // Raw system_trust_* tag (highest), shown as a chip on the VRChat tab
        // identity header. Blank when tags were unavailable.
        val trustRank: String = ""
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
            // VRChat+ images: userIcon is the round profile picture; the
            // profilePicOverride is the wide BANNER — keep them separate.
            var profilePic = json.optString("userIcon", "")
            var bannerPic = json.optString("profilePicOverride", "")
            var trustRank = extractTrustRankFromTags(json.optJSONArray("tags"))

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
                        // /users/{id} redacts invite/invite+ instances to "private"
                        // even for your OWN account, dropping the ~nonce(...) access
                        // token the admin self-invite needs. When it comes back
                        // "private" but /auth/user already gave us a full joinable
                        // location, KEEP the full one. Any other value (a real wrld_
                        // location, or "offline"/"traveling" when you've actually left)
                        // still overrides, so stale-presence correction is unaffected.
                        if (uLocation.isNotBlank() &&
                            !(uLocation == "private" && location.startsWith("wrld_"))
                        ) {
                            location = uLocation
                        }
                        if (uStatus.isNotBlank()) status = uStatus
                        uj.optString("statusDescription", "").let { if (it.isNotBlank()) statusDescription = it }
                        uj.optString("last_platform", "").let { if (it.isNotBlank()) platform = it }
                        uj.optString("displayName", "").let { if (it.isNotBlank()) displayName = it }
                        uj.optString("currentAvatarThumbnailImageUrl", "").let { if (it.isNotBlank()) avatarThumb = it }
                        // The /users/{id} endpoint is the authoritative source for
                        // the VRChat+ profile picture / banner fields.
                        uj.optString("userIcon", "").let { if (it.isNotBlank()) profilePic = it }
                        uj.optString("profilePicOverride", "").let { if (it.isNotBlank()) bannerPic = it }
                        extractTrustRankFromTags(uj.optJSONArray("tags")).let { if (it.isNotBlank()) trustRank = it }
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
                profilePicUrl = profilePic,
                bannerUrl = bannerPic,
                trustRank = trustRank
            )
        } catch (e: Exception) {
            Log.e(TAG, "fetchPresence failed", e)
            null
        }
    }

    /**
     * Lightweight single-call self presence — `GET /users/{id}` only, no
     * `/auth/user` and no `/instances/{loc}` follow-ups. Used as a FALLBACK when
     * the heavy [fetchPresence] 3-call chain returns null because one of its calls
     * timed out / 429'd (a partial failure) while the session is still alive. One
     * request is far less likely to be throttled than three, so the user's
     * location/state keep tracking reality instead of freezing — which is what made
     * a genuinely in-game user show "not in a world" on the admin panel and "not in
     * VRChat" on their Discord RPC. Returns null when even this single call fails
     * (e.g. the cookie is fully IP-invalidated — that's the WS/session-recovery
     * path). worldName/instance counts are left blank here; the caller merges them
     * from the last-known presence when the location is unchanged, and the next
     * successful heavy chain refills them on a world hop.
     */
    suspend fun fetchSelfPresenceLight(context: Context): VrcUserPresence? = withContext(Dispatchers.IO) {
        val userId = getStoredUserId(context) ?: return@withContext null
        val cookieHeader = getCookieHeader(context) ?: return@withContext null
        try {
            val (code, body, rawCookies) = get("$BASE/users/$userId", null, cookieHeader)
            if (code != 200) {
                Log.w(TAG, "fetchSelfPresenceLight: /users/$userId returned $code")
                return@withContext null
            }
            captureRolledCookies(context, rawCookies)
            val uj = JSONObject(body)
            val location = uj.optString("location", "offline")
            val state = uj.optString("state", "offline")
            val status = uj.optString("status", "offline")
            val isOnline = state == "online" ||
                location.startsWith("wrld_") || location == "private" || location == "traveling"
            VrcUserPresence(
                userId = userId,
                displayName = uj.optString("displayName"),
                state = state,
                status = status,
                statusDescription = uj.optString("statusDescription", ""),
                location = location,
                platform = uj.optString("last_platform", ""),
                worldName = "",
                instancePlayerCount = 0,
                instanceCapacity = 0,
                currentAvatarThumbnailUrl = uj.optString("currentAvatarThumbnailImageUrl", ""),
                isOnlineInVRChat = isOnline,
                worldImageUrl = "",
                profilePicUrl = uj.optString("userIcon", ""),
                bannerUrl = uj.optString("profilePicOverride", ""),
                trustRank = extractTrustRankFromTags(uj.optJSONArray("tags"))
            )
        } catch (e: Exception) {
            Log.w(TAG, "fetchSelfPresenceLight failed", e)
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

    /**
     * Fetch friends. [onlineOnly] requests ONLY VRChat's "Online" friends group
     * (the `offline=false` list). That group is NOT just in-game players — it is
     * everyone whose status isn't offline, i.e. it ALSO includes website- and
     * mobile-active friends (status active / join me / ask me / busy). Only
     * truly-offline friends (the `offline=true` list) are skipped. So a frequent
     * foreground refresh stays a single light call for most users while still
     * catching bio/name/rank edits from friends on the website or phone. The full
     * sweep (default, both passes) additionally covers offline friends.
     */
    /**
     * Fetches the friends list. **Returns null on a HARD failure** (no cookie, or not a
     * single page ever returned 200 — session dead / rate-limited-out / all-errored) so
     * callers can tell "the fetch failed" apart from "you genuinely have 0 friends".
     * That distinction is what lets the unfriend-diff fire when your LAST friend is
     * removed (empty list == real) without false-flagging every friend as removed when a
     * fetch just failed (null == unknown, skip the diff / preserve the cache).
     */
    suspend fun fetchFriends(context: Context, onlineOnly: Boolean = false): List<VrcFriend>? = withContext(Dispatchers.IO) {
        val cookieHeader = getCookieHeader(context) ?: return@withContext null
        val seen = mutableMapOf<String, VrcFriend>()
        var gotAny200 = false
        val pageSize = 100
        val passes = if (onlineOnly) listOf(false) else listOf(false, true)
        for (offline in passes) {
            var offset = 0
            try {
                while (true) {
                    val (code, body, rawCookies) = get(
                        "$BASE/auth/user/friends?offset=$offset&n=$pageSize&offline=$offline",
                        null, cookieHeader
                    )
                    if (code == 200) { captureRolledCookies(context, rawCookies); gotAny200 = true }
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
        Log.i(TAG, "fetchFriends total: ${seen.size} (gotAny200=$gotAny200)")
        // Never received a valid page → the fetch FAILED (don't let callers read this as
        // "0 friends"). A valid 200 with an empty array is a genuine zero and returns [].
        if (!gotAny200) null else seen.values.toList()
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

    /** A user's current display name (`GET /users/{id}` → `displayName`) — used to
     *  resolve an event's organizer name for the alert card. Best-effort. */
    suspend fun fetchUserDisplayName(context: Context, userId: String): String? = withContext(Dispatchers.IO) {
        if (userId.isBlank() || !userId.startsWith("usr_")) return@withContext null
        val cookieHeader = getCookieHeader(context) ?: return@withContext null
        try {
            val (code, body, rawCookies) = get("$BASE/users/$userId", null, cookieHeader)
            if (code == 200) captureRolledCookies(context, rawCookies)
            if (code == 200 && body.startsWith("{")) {
                org.json.JSONObject(body).optString("displayName", "").takeIf { it.isNotBlank() }
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "fetchUserDisplayName($userId) failed", e)
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

    suspend fun fetchGroupPosts(context: Context, groupId: String, n: Int = 50): org.json.JSONArray? = withContext(Dispatchers.IO) {
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
            val arr = when {
                body.startsWith("[") -> org.json.JSONArray(body)
                body.startsWith("{") -> org.json.JSONObject(body).optJSONArray("posts")
                else -> null
            }
            arr
        } catch (e: Exception) {
            Log.w(TAG, "fetchGroupPosts($groupId) failed", e)
            null
        }
    }

    /**
     * A single calendar event (`GET /calendar/{groupId}/{eventId}`). The single-
     * event object carries the authenticated user's per-event state (whether they
     * follow/are-signed-up) that the group calendar LIST omits — so this is how we
     * detect an event the user added to their calendar IN-GAME. Returns the full
     * object (also richer for organizer/recurrence).
     */
    suspend fun fetchCalendarEvent(context: Context, groupId: String, eventId: String): org.json.JSONObject? =
        fetchCalendarEventResult(context, groupId, eventId).event

    /**
     * Tri-state single calendar-event fetch. [CalendarEventResult.status] lets the
     * caller distinguish a definitive **404 (deleted)** from a transient failure
     * (network / 429 / no cookie), so a bad connection never false-flags a live
     * event as "Removed". FOUND carries the object; DELETED means VRChat returned
     * 404; UNKNOWN is any other non-200 / error.
     */
    enum class CalendarEventStatus { FOUND, DELETED, UNKNOWN }
    data class CalendarEventResult(val status: CalendarEventStatus, val event: org.json.JSONObject?)

    suspend fun fetchCalendarEventResult(context: Context, groupId: String, eventId: String): CalendarEventResult =
        withContext(Dispatchers.IO) {
            if (groupId.isBlank() || eventId.isBlank())
                return@withContext CalendarEventResult(CalendarEventStatus.UNKNOWN, null)
            val cookieHeader = getCookieHeader(context)
                ?: return@withContext CalendarEventResult(CalendarEventStatus.UNKNOWN, null)
            try {
                val (code, body, rawCookies) = get("$BASE/calendar/$groupId/$eventId", null, cookieHeader)
                when {
                    code == 200 && body.startsWith("{") -> {
                        captureRolledCookies(context, rawCookies)
                        CalendarEventResult(CalendarEventStatus.FOUND, org.json.JSONObject(body))
                    }
                    // 404 Not Found / 410 Gone = deleted. (403 is deliberately NOT
                    // treated as deleted — it can be a transient auth/permission
                    // state, and a false "deleted" is worse than a slightly slower one.)
                    code == 404 || code == 410 -> CalendarEventResult(CalendarEventStatus.DELETED, null)
                    else -> CalendarEventResult(CalendarEventStatus.UNKNOWN, null)
                }
            } catch (e: Exception) {
                Log.w(TAG, "fetchCalendarEvent($groupId,$eventId) failed", e)
                CalendarEventResult(CalendarEventStatus.UNKNOWN, null)
            }
        }

    // Group calendar events. VRChat exposes group events at
    // GET /groups/{groupId}/calendar — used to backfill events created while
    // the app was closed (they don't reliably appear in the per-user
    // notifications-v2 feed). Response may be a bare array or an object
    // wrapping the list under "results"/"events".
    suspend fun fetchGroupCalendarEvents(context: Context, groupId: String, n: Int = 100): org.json.JSONArray? = withContext(Dispatchers.IO) {
        val cookieHeader = getCookieHeader(context) ?: return@withContext null
        // Correct VRChat group-calendar endpoint is GET /calendar/{groupId}
        // (returns {"results":[...]}). The older /groups/{id}/events paths 404,
        // which is why events created while the app was closed never surfaced.
        val endpoints = arrayOf(
            "$BASE/calendar/$groupId?n=$n",
            "$BASE/groups/$groupId/events?n=$n",
            "$BASE/groups/$groupId/calendar?n=$n"
        )
        for ((idx, url) in endpoints.withIndex()) {
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
                // The canonical endpoint (idx 0, /calendar/{groupId}) is
                // AUTHORITATIVE: return its parsed list even when EMPTY. An emptied
                // group is a valid 200 with results:[] — NOT a fetch failure — and
                // deletion detection MUST tell those apart (previously an empty list
                // fell through to the legacy 404 endpoints and returned null, so an
                // all-events-deleted group looked like a network error and its
                // deleted events could never be confirmed gone). Legacy fallbacks
                // (idx 1/2) only "count" when non-empty.
                if (result != null && (idx == 0 || result.length() > 0)) return@withContext result
            } catch (e: Exception) {
                Log.w(TAG, "fetchGroupCalendarEvents($groupId) ${url.substringAfterLast("/")} failed", e)
            }
        }
        null
    }

    /**
     * The group's currently-OPEN instances (`GET /groups/{id}/instances` — what the
     * website's group page lists). Returns joinable `wrld_x:instance` location
     * strings, newest-activity first as VRChat returns them. Used by the in-app
     * "Join event" action: VRChat doesn't reliably link an event to a specific
     * instance, so we surface every instance the hosting group has up.
     */
    suspend fun fetchGroupInstances(context: Context, groupId: String): List<String> =
        withContext(Dispatchers.IO) {
            if (groupId.isBlank()) return@withContext emptyList()
            val cookieHeader = getCookieHeader(context) ?: return@withContext emptyList()
            try {
                val (code, body, rawCookies) = get("$BASE/groups/$groupId/instances", null, cookieHeader)
                if (code != 200) {
                    Log.w(TAG, "fetchGroupInstances($groupId) http=$code")
                    return@withContext emptyList()
                }
                captureRolledCookies(context, rawCookies)
                val arr = when {
                    body.startsWith("[") -> org.json.JSONArray(body)
                    body.startsWith("{") -> org.json.JSONObject(body).optJSONArray("instances")
                    else -> null
                } ?: return@withContext emptyList()
                val out = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val inst = arr.optJSONObject(i) ?: continue
                    // Prefer the full `location`; fall back to world.id + instanceId.
                    val loc = inst.optString("location", "").ifBlank {
                        val worldId = inst.optJSONObject("world")?.optString("id", "").orEmpty()
                        val instanceId = inst.optString("instanceId", "")
                        if (worldId.startsWith("wrld_") && instanceId.isNotBlank())
                            "$worldId:$instanceId" else ""
                    }
                    if (loc.startsWith("wrld_")) out.add(loc)
                }
                out
            } catch (e: Exception) {
                Log.w(TAG, "fetchGroupInstances($groupId) failed", e)
                emptyList()
            }
        }

    /**
     * Adds/removes a group calendar event on the USER's VRChat calendar — the
     * website's "Add to Calendar" / "Remove from Calendar" buttons
     * (`POST /calendar/{groupId}/{eventId}/follow` with `{"isFollowing":bool}`,
     * the community-documented follow endpoint). On failure the [InviteResult]
     * carries VRChat's own error message so the UI can show WHY.
     */
    suspend fun setCalendarEventFollowing(
        context: Context,
        groupId: String,
        eventId: String,
        following: Boolean
    ): InviteResult = withContext(Dispatchers.IO) {
        if (groupId.isBlank() || eventId.isBlank())
            return@withContext InviteResult(false, "Event info is missing")
        val cookieHeader = getCookieHeader(context)
            ?: return@withContext InviteResult(false, "Not signed in to VRChat")
        try {
            val url = "$BASE/calendar/$groupId/$eventId/follow"
            val reqBody = "{\"isFollowing\":$following}"
            val (code, respBody, rawCookies) = post(url, reqBody, cookieHeader)
            if (code in 200..299) {
                captureRolledCookies(context, rawCookies)
                InviteResult(true)
            } else {
                Log.w(TAG, "setCalendarEventFollowing($groupId,$eventId,$following) $code body=${respBody.take(200)}")
                InviteResult(false, parseVrcError(respBody, code))
            }
        } catch (e: Exception) {
            Log.w(TAG, "setCalendarEventFollowing failed", e)
            InviteResult(false, "Network error")
        }
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
    // Serializes cookie roll-forward writes. Every authenticated REST response now
    // routes through captureRolledCookies, and the heavy fetchPresence chain + the
    // friends sweep fire several of them CONCURRENTLY on Dispatchers.IO. Without a
    // lock, two responses doing edit()/apply() at once race on the shared
    // `auth`/`twoFactorAuth` store the pipeline WebSocket also reads — a stale-IP
    // cookie could win nondeterministically. The lock makes each read-decide-write
    // atomic and ordered. (Timestamp/roll-forward semantics are unchanged — every
    // present cookie still refreshes its stored-at clock, which shouldRefreshCookies
    // and the trusted-device window depend on.)
    private val cookieWriteLock = Any()

    private fun captureRolledCookies(context: Context, rawCookies: List<String>) {
        if (rawCookies.isEmpty()) return
        val auth = rawCookies.mapNotNull { extractCookieValue(it, "auth") }.firstOrNull()
        val twoFa = rawCookies.mapNotNull { extractCookieValue(it, "twoFactorAuth") }.firstOrNull()
        if (auth == null && twoFa == null) return
        synchronized(cookieWriteLock) {
            val editor = getPrefs(context)?.edit() ?: return
            val now = System.currentTimeMillis()
            if (auth != null) {
                editor.putString(KEY_AUTH_COOKIE, auth).putLong(KEY_COOKIE_STORED_AT, now)
            }
            if (twoFa != null) {
                editor.putString(KEY_2FA_COOKIE, twoFa).putLong(KEY_2FA_COOKIE_STORED_AT, now)
                Log.d(TAG, "Rolled twoFactorAuth cookie forward (trusted-device window extended)")
            }
            editor.commit()
        }
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

    /* =========================================================
       Friend graph helpers (used by the admin self-invite flow —
       see SelfInviteCoordinator). All are REST, session-authed.
       ========================================================= */

    data class FriendStatus(
        val isFriend: Boolean,
        val incomingRequest: Boolean,
        val outgoingRequest: Boolean
    )

    /** GET /user/{userId}/friendStatus → {isFriend, incomingRequest, outgoingRequest}.
     *  Null on a network/permission error so callers can tell "unknown" from "not friends". */
    suspend fun getFriendStatus(context: Context, userId: String): FriendStatus? =
        withContext(Dispatchers.IO) {
            val uid = userId.trim()
            if (uid.isBlank()) return@withContext null
            val cookieHeader = getCookieHeader(context) ?: return@withContext null
            try {
                val (code, body, rawCookies) = get("$BASE/user/$uid/friendStatus", null, cookieHeader)
                if (code == 200) {
                    captureRolledCookies(context, rawCookies)
                    val o = JSONObject(body)
                    FriendStatus(
                        isFriend = o.optBoolean("isFriend", false),
                        incomingRequest = o.optBoolean("incomingRequest", false),
                        outgoingRequest = o.optBoolean("outgoingRequest", false)
                    )
                } else null
            } catch (e: Exception) {
                Log.w(TAG, "getFriendStatus $uid failed", e)
                null
            }
        }

    /** POST /user/{userId}/friendRequest — sends a friend request. If the other party
     *  already has an outgoing request to us, VRChat auto-befriends (mutual request). */
    suspend fun sendFriendRequest(context: Context, userId: String): InviteResult =
        withContext(Dispatchers.IO) {
            val uid = userId.trim()
            if (uid.isBlank()) return@withContext InviteResult(false, "Missing user id")
            val cookieHeader = getCookieHeader(context)
                ?: return@withContext InviteResult(false, "Not signed in to VRChat")
            try {
                val (code, respBody, rawCookies) = post("$BASE/user/$uid/friendRequest", "", cookieHeader)
                if (code == 200) {
                    captureRolledCookies(context, rawCookies)
                    InviteResult(true)
                } else {
                    Log.w(TAG, "sendFriendRequest $uid returned $code body=${respBody.take(200)}")
                    InviteResult(false, parseVrcError(respBody, code))
                }
            } catch (e: Exception) {
                Log.w(TAG, "sendFriendRequest $uid failed", e)
                InviteResult(false, "Network error")
            }
        }

    /** DELETE /user/{userId}/friendRequest — cancels an outgoing (or rejects an
     *  incoming) friend request. Used in dance cleanup so no request lingers if the
     *  pair never auto-befriended. Best-effort; a 404 (no request) is treated as ok. */
    suspend fun cancelFriendRequest(context: Context, userId: String): InviteResult =
        withContext(Dispatchers.IO) {
            val uid = userId.trim()
            if (uid.isBlank()) return@withContext InviteResult(false, "Missing user id")
            val cookieHeader = getCookieHeader(context)
                ?: return@withContext InviteResult(false, "Not signed in to VRChat")
            try {
                val (code, respBody, rawCookies) = delete("$BASE/user/$uid/friendRequest", cookieHeader)
                if (code == 200 || code == 404) {
                    captureRolledCookies(context, rawCookies)
                    InviteResult(true)
                } else InviteResult(false, parseVrcError(respBody, code))
            } catch (e: Exception) {
                Log.w(TAG, "cancelFriendRequest $uid failed", e)
                InviteResult(false, "Network error")
            }
        }

    /** DELETE /auth/user/friends/{userId} — unfriends. A 404/200 both mean "not a
     *  friend anymore" (success). Used by the dance cleanup + the pending-unfriend sweep. */
    suspend fun unfriendUser(context: Context, userId: String): InviteResult =
        withContext(Dispatchers.IO) {
            val uid = userId.trim()
            if (uid.isBlank()) return@withContext InviteResult(false, "Missing user id")
            val cookieHeader = getCookieHeader(context)
                ?: return@withContext InviteResult(false, "Not signed in to VRChat")
            try {
                val (code, respBody, rawCookies) = delete("$BASE/auth/user/friends/$uid", cookieHeader)
                if (code == 200 || code == 404) {
                    captureRolledCookies(context, rawCookies)
                    InviteResult(true)
                } else InviteResult(false, parseVrcError(respBody, code))
            } catch (e: Exception) {
                Log.w(TAG, "unfriendUser $uid failed", e)
                InviteResult(false, "Network error")
            }
        }

    private fun delete(
        url: String,
        cookieHeader: String?
    ): Triple<Int, String, List<String>> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            useCaches = false
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-cache")
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
