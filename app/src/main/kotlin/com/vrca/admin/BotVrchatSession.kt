package com.vrca.admin

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * SEPARATE, isolated VRChat sessions for the avatar-catalog bots — dedicated **bot
 * accounts** so the admin's REAL account isn't rate-limited by the continuous
 * `/avatars/{id}` checks. Up to [SLOTS] independent slots run at once, each with its
 * OWN encrypted store, fully independent of VrchatAuthManager.
 *
 * Robust like the main session: it SAVES credentials + rolls the `auth`/`twoFactorAuth`
 * cookies forward on every authenticated call, and AUTO-RE-LOGINS (sending the stored
 * trusted-device 2FA cookie so VRChat skips the 2FA prompt) when a cookie expires — so
 * a bot stays logged in for weeks instead of getting wiped when the IP-bound auth
 * cookie lapses. Admin build only.
 */
object BotVrchatSession {
    const val SLOTS = 4
    private const val BASE = "https://api.vrchat.cloud/api/1"
    private const val UA = "VRC-A/1.0 (VRChat companion)"
    private const val KEY_AUTH = "auth_cookie"
    private const val KEY_2FA = "twofa_cookie"
    private const val KEY_NAME = "bot_name"
    private const val KEY_USER = "bot_user"
    private const val KEY_PASS = "bot_pass"

    sealed class LoginResult {
        object Success : LoginResult()
        data class Needs2FA(val email: Boolean) : LoginResult()
        data class Error(val message: String) : LoginResult()
    }

    /** Session validity for a slot's stored cookie. */
    enum class Auth { AUTHED, EXPIRED, UNKNOWN }

    // Wall-clock of the last API call that PROVED this slot's session is authed (any
    // authenticated response: 200, or an avatar-level 403/404/410 — all require a valid
    // cookie; only a 401 means expired). The Bots tab uses this to flip a bot from
    // "Checking…" to "Authed" the moment it does real work, instead of waiting on the
    // periodic validate (which can sit UNKNOWN on a transient 429/network blip).
    private val authOkAt = java.util.concurrent.atomic.AtomicLongArray(SLOTS)
    fun lastAuthOkMs(slot: Int): Long = if (slot in 0 until SLOTS) authOkAt.get(slot) else 0L
    private fun noteAuthOk(slot: Int) { if (slot in 0 until SLOTS) authOkAt.set(slot, System.currentTimeMillis()) }

    private fun prefsName(slot: Int) = if (slot <= 0) "vrca_bot_vrchat" else "vrca_bot_vrchat_$slot"

    private fun prefs(context: Context, slot: Int): android.content.SharedPreferences? = try {
        val mk = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, prefsName(slot), mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) { null }

    fun isLoggedIn(context: Context, slot: Int = 0): Boolean =
        !(prefs(context, slot)?.getString(KEY_AUTH, null)).isNullOrBlank()

    fun botName(context: Context, slot: Int = 0): String = prefs(context, slot)?.getString(KEY_NAME, "") ?: ""

    /** A human label for a slot: the VRChat display name once known, else the login
     *  email/username (so you can tell accounts apart before the name resolves), else "". */
    fun accountLabel(context: Context, slot: Int): String {
        val p = prefs(context, slot) ?: return ""
        return p.getString(KEY_NAME, "")?.takeIf { it.isNotBlank() }
            ?: p.getString(KEY_USER, "")?.takeIf { it.isNotBlank() }
            ?: ""
    }

    fun logout(context: Context, slot: Int = 0) { prefs(context, slot)?.edit()?.clear()?.apply() }

    fun loggedInCount(context: Context): Int = (0 until SLOTS).count { isLoggedIn(context, it) }

    // VRChat URL-decodes the basic-auth creds, so URI-encode before base64.
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Cookie header for authenticated calls (auth + trusted-device 2FA). */
    private fun cookieHeader(context: Context, slot: Int): String? {
        val p = prefs(context, slot) ?: return null
        return listOfNotNull(p.getString(KEY_AUTH, null), p.getString(KEY_2FA, null))
            .takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private fun extractCookie(setCookies: List<String>, name: String): String? {
        for (c in setCookies) {
            val m = Regex("$name=([^;]+)").find(c) ?: continue
            return "$name=${m.groupValues[1]}"
        }
        return null
    }

    /** Roll any refreshed auth/twoFactorAuth cookie forward (keeps the session as fresh
     *  as the browser's, so the trusted-device window keeps extending). */
    private fun captureRolled(context: Context, slot: Int, setCookies: List<String>) {
        val p = prefs(context, slot) ?: return
        val e = p.edit()
        extractCookie(setCookies, "auth")?.let { e.putString(KEY_AUTH, it) }
        extractCookie(setCookies, "twoFactorAuth")?.let { e.putString(KEY_2FA, it) }
        e.apply()
    }

    // ONE global login gate for the whole app: only one Basic-auth login runs at a time
    // (across manual logins AND background auto-relogins), and no two attempts fire within
    // MIN_LOGIN_GAP_MS. VRChat's login rate limit is a cooldown that EVERY attempt resets,
    // so hammering it keeps you locked out — spacing attempts is what actually gets you in.
    private val loginGate = Mutex()
    @Volatile private var lastLoginAttemptMs = 0L
    private const val MIN_LOGIN_GAP_MS = 30_000L      // never two logins closer than this
    private const val RATE_LIMIT_WAIT_MS = 75_000L    // quiet wait after a rate-limit hit (lets it clear)
    private const val MAX_RATE_RETRIES = 4

    /**
     * Log in. Saves the credentials FIRST (so auto-relogin can recover later) and sends
     * the stored trusted-device 2FA cookie if we have one (so a re-login skips the 2FA
     * prompt). SERIALIZED + SPACED via [loginGate] so concurrent/rapid logins can't
     * trip VRChat's per-IP rate limit. Returns Needs2FA when VRChat wants a code.
     */
    suspend fun login(
        context: Context, slot: Int, username: String, password: String,
        onProgress: ((String) -> Unit)? = null
    ): LoginResult =
        withContext(Dispatchers.IO) {
            prefs(context, slot)?.edit()
                ?.putString(KEY_USER, username)?.putString(KEY_PASS, password)?.apply()
            loginGate.withLock {
                var rateRetries = 0
                while (true) {
                    // Space this attempt out from the previous login (any bot).
                    val since = System.currentTimeMillis() - lastLoginAttemptMs
                    if (since < MIN_LOGIN_GAP_MS) {
                        val wait = MIN_LOGIN_GAP_MS - since
                        onProgress?.invoke("Spacing logins to dodge VRChat's limit — ${wait / 1000}s…")
                        delay(wait)
                    }
                    lastLoginAttemptMs = System.currentTimeMillis()
                    var conn: HttpURLConnection? = null
                    try {
                        val basic = Base64.encodeToString(
                            "${enc(username)}:${enc(password)}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP
                        )
                        val twofa = prefs(context, slot)?.getString(KEY_2FA, null)
                        conn = (URL("$BASE/auth/user").openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            setRequestProperty("Authorization", "Basic $basic")
                            setRequestProperty("User-Agent", UA)
                            if (twofa != null) setRequestProperty("Cookie", twofa)
                            connectTimeout = 15000; readTimeout = 15000
                        }
                        val code = conn.responseCode
                        val body = (if (code < 400) conn.inputStream else conn.errorStream)
                            ?.bufferedReader()?.readText() ?: ""
                        captureRolled(context, slot, conn.headerFields["Set-Cookie"] ?: emptyList())

                        // VRChat's error message tells us WHICH failure this is.
                        val vrcMsg = runCatching {
                            JSONObject(body).optJSONObject("error")?.optString("message", "") ?: ""
                        }.getOrDefault("").ifBlank { body }.take(160)
                        val lower = vrcMsg.lowercase()
                        val rateLimited = code == 429 || lower.contains("rate") || lower.contains("too many")
                        val badCreds = code == 401 && !rateLimited &&
                            (lower.contains("invalid") || lower.contains("incorrect") ||
                             lower.contains("password") || lower.contains("credential"))

                        if (badCreds) return@withLock LoginResult.Error("VRChat rejected the login: $vrcMsg")
                        if (rateLimited || (code == 401)) {
                            rateRetries++
                            if (rateRetries > MAX_RATE_RETRIES)
                                return@withLock LoginResult.Error("Still rate-limited. Wait ~2 min, then tap Log in again.")
                            onProgress?.invoke("VRChat rate-limited — waiting ${RATE_LIMIT_WAIT_MS / 1000}s (it clears if we stop knocking)…")
                            delay(RATE_LIMIT_WAIT_MS)
                            continue
                        }
                        if (code != 200) return@withLock LoginResult.Error("HTTP $code $vrcMsg")

                        val json = JSONObject(body)
                        json.optString("displayName", "").takeIf { it.isNotBlank() }?.let {
                            prefs(context, slot)?.edit()?.putString(KEY_NAME, it)?.apply()
                        }
                        val requires = json.optJSONArray("requiresTwoFactorAuth")
                        if (requires != null && requires.length() > 0) {
                            val email = (0 until requires.length()).any { requires.optString(it).contains("email", true) }
                            return@withLock LoginResult.Needs2FA(email)
                        }
                        return@withLock LoginResult.Success
                    } catch (e: Exception) {
                        return@withLock LoginResult.Error(e.javaClass.simpleName)
                    } finally { runCatching { conn?.disconnect() } }
                }
                @Suppress("UNREACHABLE_CODE") LoginResult.Error("unreachable")
            }
        }

    suspend fun verify2FA(context: Context, slot: Int, code: String, email: Boolean): LoginResult =
        withContext(Dispatchers.IO) {
            val auth = prefs(context, slot)?.getString(KEY_AUTH, null)
                ?: return@withContext LoginResult.Error("no auth cookie")
            var conn: HttpURLConnection? = null
            try {
                val ep = if (email) "auth/twofactorauth/emailotp/verify"
                         else "auth/twofactorauth/totp/verify"
                conn = (URL("$BASE/$ep").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Cookie", auth)
                    doOutput = true
                    connectTimeout = 15000; readTimeout = 15000
                }
                conn.outputStream.use { it.write(JSONObject().put("code", code).toString().toByteArray()) }
                val respCode = conn.responseCode
                captureRolled(context, slot, conn.headerFields["Set-Cookie"] ?: emptyList())
                if (respCode != 200) return@withContext LoginResult.Error("2FA HTTP $respCode")
                // Fetch + store the name now (the login response had none).
                validate(context, slot)
                LoginResult.Success
            } catch (e: Exception) {
                LoginResult.Error(e.javaClass.simpleName)
            } finally { runCatching { conn?.disconnect() } }
        }

    /** `GET /auth/user` — reports auth status, captures the account name, and rolls the
     *  cookies forward. 200 with a displayName = AUTHED; 200-still-needs-2FA / 401 =
     *  EXPIRED; anything else = UNKNOWN (transient — don't claim expired). */
    suspend fun validate(context: Context, slot: Int): Auth = withContext(Dispatchers.IO) {
        val cookie = cookieHeader(context, slot) ?: return@withContext Auth.UNKNOWN
        var conn: HttpURLConnection? = null
        try {
            conn = (URL("$BASE/auth/user").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Cookie", cookie)
                connectTimeout = 12000; readTimeout = 12000
            }
            val code = conn.responseCode
            if (code == 401) return@withContext Auth.EXPIRED
            if (code != 200) return@withContext Auth.UNKNOWN
            captureRolled(context, slot, conn.headerFields["Set-Cookie"] ?: emptyList())
            val j = JSONObject(conn.inputStream.bufferedReader().readText())
            if (j.optJSONArray("requiresTwoFactorAuth")?.let { it.length() > 0 } == true)
                return@withContext Auth.EXPIRED
            j.optString("displayName", "").takeIf { it.isNotBlank() }?.let {
                prefs(context, slot)?.edit()?.putString(KEY_NAME, it)?.apply()
            }
            noteAuthOk(slot)
            Auth.AUTHED
        } catch (e: Exception) { Auth.UNKNOWN }
        finally { runCatching { conn?.disconnect() } }
    }

    /** Recover an EXPIRED session from saved credentials (sends the trusted-device 2FA
     *  cookie so VRChat skips the prompt). Returns AUTHED on success, EXPIRED if it
     *  still needs a fresh 2FA code / creds are gone, UNKNOWN on a network blip. */
    suspend fun autoRelogin(context: Context, slot: Int): Auth = withContext(Dispatchers.IO) {
        val p = prefs(context, slot) ?: return@withContext Auth.UNKNOWN
        val u = p.getString(KEY_USER, null); val pw = p.getString(KEY_PASS, null)
        if (u.isNullOrBlank() || pw.isNullOrBlank()) return@withContext Auth.EXPIRED
        when (login(context, slot, u, pw)) {
            is LoginResult.Success -> Auth.AUTHED
            is LoginResult.Needs2FA -> Auth.EXPIRED
            is LoginResult.Error -> Auth.UNKNOWN
        }
    }

    /** One avatar's check result: alive + fresh fields (incl. bio + per-platform perf), or
     *  dead (404/410/403). Perf rank: 0=Excellent 1=Good 2=Medium 3=Poor 4=VeryPoor 5=unknown. */
    data class AvatarCheck(
        val alive: Boolean, val fileId: String?, val name: String,
        val author: String, val authorId: String, val platforms: List<String>,
        val description: String = "",
        val perfPc: Int = 5, val perfQuest: Int = 5, val perfIos: Int = 5
    )

    /** VRChat `performanceRating` string → rank int (worst-known wins if seen twice). */
    private fun perfRank(s: String): Int = when (s.trim().lowercase()) {
        "excellent" -> 0; "good" -> 1; "medium" -> 2; "poor" -> 3; "verypoor", "very poor" -> 4
        else -> 5
    }

    /** GET /avatars/{id} with a BOT cookie. 404/410/403 = not publicly accessible → remove;
     *  a non-200 that isn't those (rate limit / network) returns null so the sweep skips it. */
    suspend fun checkAvatar(context: Context, slot: Int, avatarId: String): AvatarCheck? =
        withContext(Dispatchers.IO) {
            val cookie = cookieHeader(context, slot) ?: return@withContext null
            var conn: HttpURLConnection? = null
            try {
                conn = (URL("$BASE/avatars/$avatarId").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Cookie", cookie)
                    connectTimeout = 15000; readTimeout = 15000
                }
                val code = conn.responseCode
                // Any of these proves the cookie is valid (a bad cookie 401s), so mark the slot
                // authed — this is what flips "Checking…" to "Authed" as soon as the bot works.
                if (code == 200 || code == 403 || code == 404 || code == 410) noteAuthOk(slot)
                if (code == 404 || code == 410 || code == 403)
                    return@withContext AvatarCheck(false, null, "", "", "", emptyList())
                if (code != 200) return@withContext null
                captureRolled(context, slot, conn.headerFields["Set-Cookie"] ?: emptyList())
                val j = JSONObject(conn.inputStream.bufferedReader().readText())
                if (j.optString("releaseStatus", "public") != "public")
                    return@withContext AvatarCheck(false, null, "", "", "", emptyList())
                val fileId = Regex("file_[0-9a-fA-F-]{36}").find(
                    j.optString("thumbnailImageUrl", "").ifBlank { j.optString("imageUrl", "") }
                )?.value
                // Walk unityPackages ONCE for both platforms and per-platform perf rank
                // (VRChat exposes performanceRating per package). Best-known rank wins if a
                // platform somehow appears twice (lower int = better; 5 = unknown).
                var perfPc = 5; var perfQuest = 5; var perfIos = 5
                val platSet = LinkedHashSet<String>()
                j.optJSONArray("unityPackages")?.let { ups ->
                    for (i in 0 until ups.length()) {
                        val up = ups.optJSONObject(i) ?: continue
                        val rawPlat = up.optString("platform", "")
                        val rank = perfRank(up.optString("performanceRating", ""))
                        when (rawPlat.lowercase()) {
                            "standalonewindows" -> { platSet.add("PC"); if (rank < perfPc) perfPc = rank }
                            "android" -> { platSet.add("Quest"); if (rank < perfQuest) perfQuest = rank }
                            "ios" -> { platSet.add("iOS"); if (rank < perfIos) perfIos = rank }
                        }
                    }
                }
                AvatarCheck(
                    true, fileId, j.optString("name", ""),
                    j.optString("authorName", ""), j.optString("authorId", ""), platSet.toList(),
                    j.optString("description", ""), perfPc, perfQuest, perfIos
                )
            } catch (e: Exception) { null }
            finally { runCatching { conn?.disconnect() } }
        }
}
