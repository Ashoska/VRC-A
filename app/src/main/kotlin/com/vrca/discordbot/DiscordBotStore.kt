package com.vrca.discordbot

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted config store for the admin-only Discord AI bot.
 *
 * The Discord **bot token** is a full-control credential and the Cloudflare
 * Workers-AI token can run up billing, so both live in EncryptedSharedPreferences
 * (same MasterKey/AES-256-GCM scheme as [com.vrca.admin.BotVrchatSession] and the
 * VRChat cookies) rather than baked into the APK — the admin enters them ONCE in the
 * Discord Bot tab and can rotate either without a rebuild. Nothing here is synced to
 * Firestore and nothing is committed to source.
 *
 * Admin build only — the service refuses to start unless `BuildConfig.IS_ADMIN_BUILD`.
 */
object DiscordBotStore {
    private const val PREFS = "vrca_discord_bot"

    private const val KEY_BOT_TOKEN = "bot_token"
    private const val KEY_CF_ACCOUNT = "cf_account_id"
    private const val KEY_CF_TOKEN = "cf_api_token"
    private const val KEY_CF_GATEWAY = "cf_gateway_id"
    private const val KEY_MODEL = "cf_model"
    private const val KEY_SYSTEM_PROMPT = "system_prompt"
    private const val KEY_AMBIENT_PCT = "ambient_percent"
    private const val KEY_AMBIENT_COOLDOWN = "ambient_cooldown_sec"
    private const val KEY_HISTORY = "history_limit"
    private const val KEY_ENABLED = "enabled"

    /** Cloudflare's strongest broadly-available Workers-AI model; fp8-fast trims latency
     *  so the "typing…" wait stays short. Swappable in the tab or, later, behind AI Gateway. */
    const val DEFAULT_MODEL = "@cf/meta/llama-3.3-70b-instruct-fp8-fast"
    const val DEFAULT_SYSTEM_PROMPT =
        "You are a friendly, concise Discord chat companion. Keep replies short and " +
        "conversational (usually 1-3 sentences). Never use @everyone or role pings."
    const val DEFAULT_AMBIENT_PCT = 4          // % chance to chime into an unaddressed message
    const val DEFAULT_AMBIENT_COOLDOWN_SEC = 90 // min seconds between ambient replies per channel
    const val DEFAULT_HISTORY = 8               // recent channel messages fed as memory (0 = off)

    /** Immutable snapshot the service reads at start and per message (cheap read). */
    data class Config(
        val botToken: String,
        val cfAccountId: String,
        val cfApiToken: String,
        val cfGatewayId: String,
        val model: String,
        val systemPrompt: String,
        val ambientPercent: Int,
        val ambientCooldownSec: Int,
        val historyLimit: Int,
    ) {
        /** True when the bot has enough to actually run (gateway token + an AI backend). */
        val isComplete: Boolean
            get() = botToken.isNotBlank() && cfAccountId.isNotBlank() && cfApiToken.isNotBlank()
    }

    private fun prefs(context: Context) = try {
        val mk = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, PREFS, mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Exception) {
        // MasterKey corruption recovery: wipe the file and retry once (mirrors
        // VrchatAuthManager.getPrefs()). If it still fails, callers get blank config.
        try {
            context.deleteSharedPreferences(PREFS)
            val mk = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                context, PREFS, mk,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) { null }
    }

    fun load(context: Context): Config {
        val p = prefs(context)
        return Config(
            botToken = p?.getString(KEY_BOT_TOKEN, "").orEmpty().trim(),
            cfAccountId = p?.getString(KEY_CF_ACCOUNT, "").orEmpty().trim(),
            cfApiToken = p?.getString(KEY_CF_TOKEN, "").orEmpty().trim(),
            cfGatewayId = p?.getString(KEY_CF_GATEWAY, "").orEmpty().trim(),
            model = p?.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL },
            systemPrompt = p?.getString(KEY_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT).orEmpty()
                .ifBlank { DEFAULT_SYSTEM_PROMPT },
            ambientPercent = (p?.getInt(KEY_AMBIENT_PCT, DEFAULT_AMBIENT_PCT) ?: DEFAULT_AMBIENT_PCT)
                .coerceIn(0, 100),
            ambientCooldownSec = (p?.getInt(KEY_AMBIENT_COOLDOWN, DEFAULT_AMBIENT_COOLDOWN_SEC)
                ?: DEFAULT_AMBIENT_COOLDOWN_SEC).coerceAtLeast(0),
            historyLimit = (p?.getInt(KEY_HISTORY, DEFAULT_HISTORY) ?: DEFAULT_HISTORY)
                .coerceIn(0, 25),
        )
    }

    fun save(
        context: Context,
        botToken: String,
        cfAccountId: String,
        cfApiToken: String,
        cfGatewayId: String,
        model: String,
        systemPrompt: String,
        ambientPercent: Int,
        ambientCooldownSec: Int,
        historyLimit: Int,
    ) {
        prefs(context)?.edit()
            ?.putString(KEY_BOT_TOKEN, botToken.trim())
            ?.putString(KEY_CF_ACCOUNT, cfAccountId.trim())
            ?.putString(KEY_CF_TOKEN, cfApiToken.trim())
            ?.putString(KEY_CF_GATEWAY, cfGatewayId.trim())
            ?.putString(KEY_MODEL, model.trim().ifBlank { DEFAULT_MODEL })
            ?.putString(KEY_SYSTEM_PROMPT, systemPrompt.trim().ifBlank { DEFAULT_SYSTEM_PROMPT })
            ?.putInt(KEY_AMBIENT_PCT, ambientPercent.coerceIn(0, 100))
            ?.putInt(KEY_AMBIENT_COOLDOWN, ambientCooldownSec.coerceAtLeast(0))
            ?.putInt(KEY_HISTORY, historyLimit.coerceIn(0, 25))
            ?.apply()
    }

    /** Whether the admin has switched the bot ON (drives auto-start on app open / revival). */
    fun isEnabled(context: Context): Boolean = prefs(context)?.getBoolean(KEY_ENABLED, false) ?: false
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context)?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
    }
}
