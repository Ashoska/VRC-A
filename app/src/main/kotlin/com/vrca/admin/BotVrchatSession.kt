package com.vrca.admin

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * SEPARATE, isolated VRChat sessions for the avatar-catalog bots — dedicated **bot
 * accounts** so the admin's REAL VRChat account isn't rate-limited by the continuous
 * `/avatars/{id}` checks. There are up to [SLOTS] independent slots so several bots
 * can run at once (reports / fill / two liveness sweepers), each with its OWN
 * encrypted cookie store, fully independent of VrchatAuthManager.
 *
 * Slot 0 keeps the legacy store name (`vrca_bot_vrchat`) so an already-logged-in bot
 * survives the upgrade. Deliberately minimal: login (+2FA) and `GET /avatars/{id}`.
 * Admin build only.
 */
object BotVrchatSession {
    const val SLOTS = 4
    private const val BASE = "https://api.vrchat.cloud/api/1"
    private const val UA = "VRC-A/1.0 (VRChat companion)"
    private const val KEY_AUTH = "auth_cookie"
    private const val KEY_2FA = "twofa_cookie"
    private const val KEY_NAME = "bot_name"

    sealed class LoginResult {
        object Success : LoginResult()
        data class Needs2FA(val email: Boolean) : LoginResult()
        data class Error(val message: String) : LoginResult()
    }

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

    fun logout(context: Context, slot: Int = 0) { prefs(context, slot)?.edit()?.clear()?.apply() }

    /** How many bot slots are currently logged in (for the UI + sweep gating). */
    fun loggedInCount(context: Context): Int = (0 until SLOTS).count { isLoggedIn(context, it) }

    // VRChat URL-decodes the basic-auth creds, so URI-encode before base64.
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun cookieHeader(context: Context, slot: Int): String? {
        val p = prefs(context, slot) ?: return null
        val auth = p.getString(KEY_AUTH, null)
        val twofa = p.getString(KEY_2FA, null)
        return listOfNotNull(auth, twofa).takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private fun extractCookie(setCookies: List<String>, name: String): String? {
        for (c in setCookies) {
            val m = Regex("$name=([^;]+)").find(c) ?: continue
            return "$name=${m.groupValues[1]}"
        }
        return null
    }

    suspend fun login(context: Context, slot: Int, username: String, password: String): LoginResult =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val basic = Base64.encodeToString(
                    "${enc(username)}:${enc(password)}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP
                )
                conn = (URL("$BASE/auth/user").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Authorization", "Basic $basic")
                    setRequestProperty("User-Agent", UA)
                    connectTimeout = 15000; readTimeout = 15000
                }
                val code = conn.responseCode
                val body = (if (code < 400) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText() ?: ""
                val setCookies = conn.headerFields["Set-Cookie"] ?: emptyList()
                extractCookie(setCookies, "auth")?.let {
                    prefs(context, slot)?.edit()?.putString(KEY_AUTH, it)?.apply()
                }
                if (code != 200) return@withContext LoginResult.Error("HTTP $code")
                val json = JSONObject(body)
                json.optString("displayName", "").takeIf { it.isNotBlank() }?.let {
                    prefs(context, slot)?.edit()?.putString(KEY_NAME, it)?.apply()
                }
                val requires = json.optJSONArray("requiresTwoFactorAuth")
                if (requires != null && requires.length() > 0) {
                    val email = (0 until requires.length()).any {
                        requires.optString(it).contains("email", true)
                    }
                    return@withContext LoginResult.Needs2FA(email)
                }
                LoginResult.Success
            } catch (e: Exception) {
                LoginResult.Error(e.javaClass.simpleName)
            } finally { runCatching { conn?.disconnect() } }
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
                val setCookies = conn.headerFields["Set-Cookie"] ?: emptyList()
                extractCookie(setCookies, "twoFactorAuth")?.let {
                    prefs(context, slot)?.edit()?.putString(KEY_2FA, it)?.apply()
                }
                extractCookie(setCookies, "auth")?.let {
                    prefs(context, slot)?.edit()?.putString(KEY_AUTH, it)?.apply()
                }
                if (respCode == 200) LoginResult.Success else LoginResult.Error("2FA HTTP $respCode")
            } catch (e: Exception) {
                LoginResult.Error(e.javaClass.simpleName)
            } finally { runCatching { conn?.disconnect() } }
        }

    /** Session validity for a slot's stored cookie. */
    enum class Auth { AUTHED, EXPIRED, UNKNOWN }

    /** Cheap `GET /auth` check so the UI can show whether a bot is still authed:
     *  200 = AUTHED, 401 = EXPIRED (needs re-login), anything else / no cookie =
     *  UNKNOWN (network/transient — don't claim expired). */
    suspend fun validate(context: Context, slot: Int): Auth = withContext(Dispatchers.IO) {
        val cookie = cookieHeader(context, slot) ?: return@withContext Auth.UNKNOWN
        var conn: HttpURLConnection? = null
        try {
            conn = (URL("$BASE/auth").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Cookie", cookie)
                connectTimeout = 12000; readTimeout = 12000
            }
            when (conn.responseCode) {
                200 -> Auth.AUTHED
                401 -> Auth.EXPIRED
                else -> Auth.UNKNOWN
            }
        } catch (e: Exception) { Auth.UNKNOWN }
        finally { runCatching { conn?.disconnect() } }
    }

    /** One avatar's check result: alive + fresh fields (incl. bio), or dead (404/410/403). */
    data class AvatarCheck(
        val alive: Boolean, val fileId: String?, val name: String,
        val author: String, val authorId: String, val platforms: List<String>,
        val description: String = ""
    )

    /** GET /avatars/{id} with a BOT cookie. 404/410/403 = not publicly accessible
     *  (deleted/gone/private) → remove from the PUBLIC catalog; a non-200 that isn't
     *  those (rate limit / network) returns null so the sweep leaves it untouched. */
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
                if (code == 404 || code == 410 || code == 403)
                    return@withContext AvatarCheck(false, null, "", "", "", emptyList())
                if (code != 200) return@withContext null   // transient/auth → skip
                val j = JSONObject(conn.inputStream.bufferedReader().readText())
                // PRIVACY: a genuinely public avatar returns 200; a non-public one the
                // bot can somehow read (its own etc.) must NOT enter/stay in the shared
                // catalog — treat non-public as "remove".
                if (j.optString("releaseStatus", "public") != "public")
                    return@withContext AvatarCheck(false, null, "", "", "", emptyList())
                val fileId = Regex("file_[0-9a-fA-F-]{36}").find(
                    j.optString("thumbnailImageUrl", "").ifBlank { j.optString("imageUrl", "") }
                )?.value
                val plats = j.optJSONArray("unityPackages")?.let { ups ->
                    (0 until ups.length()).mapNotNull {
                        ups.optJSONObject(it)?.optString("platform", "")?.takeIf { s -> s.isNotBlank() }
                    }.map {
                        when (it.lowercase()) {
                            "standalonewindows" -> "PC"; "android" -> "Quest"; "ios" -> "iOS"; else -> ""
                        }
                    }.filter { it.isNotBlank() }.distinct()
                } ?: emptyList()
                AvatarCheck(
                    true, fileId, j.optString("name", ""),
                    j.optString("authorName", ""), j.optString("authorId", ""), plats,
                    j.optString("description", "")
                )
            } catch (e: Exception) { null }
            finally { runCatching { conn?.disconnect() } }
        }
}
