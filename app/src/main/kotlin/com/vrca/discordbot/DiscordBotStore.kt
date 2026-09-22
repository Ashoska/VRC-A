package com.vrca.discordbot

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted config store for the admin-only Discord AI bot.
 *
 * The Discord **bot token** is a full-control credential and the Cloudflare Workers-AI token
 * can run up billing, so both live in EncryptedSharedPreferences (same MasterKey/AES-256-GCM
 * scheme as [com.vrca.admin.BotVrchatSession]) — entered ONCE in the Bot tab, rotatable without
 * a rebuild, never in source, never synced to Firestore.
 *
 * **No persona field** — Cardinal self-develops his identity ([PersonalityStore]); the admin
 * never types a system prompt. The tunables here are operational only.
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
    private const val KEY_AMBIENT_PCT = "ambient_percent"
    private const val KEY_AMBIENT_COOLDOWN = "ambient_cooldown_sec"
    private const val KEY_CONTEXT_TURNS = "context_turns"
    private const val KEY_SHADOW = "shadow_mode"
    private const val KEY_MUTED = "muted_channels"
    private const val KEY_ENABLED = "enabled"

    const val DEFAULT_MODEL = DiscordBotLimits.REPLY_MODEL
    const val DEFAULT_AMBIENT_PCT = DiscordBotLimits.DEFAULT_AMBIENT_PCT
    const val DEFAULT_AMBIENT_COOLDOWN_SEC = DiscordBotLimits.DEFAULT_AMBIENT_COOLDOWN_SEC
    const val DEFAULT_CONTEXT_TURNS = DiscordBotLimits.CONTEXT_RAW_TURNS

    /** Immutable snapshot the service reads at start and per message (cheap read). */
    data class Config(
        val botToken: String,
        val cfAccountId: String,
        val cfApiToken: String,
        val cfGatewayId: String,
        val model: String,
        val ambientPercent: Int,
        val ambientCooldownSec: Int,
        val contextTurns: Int,
        val shadowMode: Boolean,
        val mutedChannelsCsv: String,
    ) {
        val isComplete: Boolean
            get() = botToken.isNotBlank() && cfAccountId.isNotBlank() && cfApiToken.isNotBlank()

        val mutedChannels: Set<String>
            get() = mutedChannelsCsv.split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()
    }

    private fun prefs(context: Context) = try {
        val mk = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, PREFS, mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Exception) {
        try {
            context.deleteSharedPreferences(PREFS)
            val mk = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
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
            ambientPercent = (p?.getInt(KEY_AMBIENT_PCT, DEFAULT_AMBIENT_PCT) ?: DEFAULT_AMBIENT_PCT)
                .coerceIn(0, 100),
            ambientCooldownSec = (p?.getInt(KEY_AMBIENT_COOLDOWN, DEFAULT_AMBIENT_COOLDOWN_SEC)
                ?: DEFAULT_AMBIENT_COOLDOWN_SEC).coerceAtLeast(0),
            contextTurns = (p?.getInt(KEY_CONTEXT_TURNS, DEFAULT_CONTEXT_TURNS) ?: DEFAULT_CONTEXT_TURNS)
                .coerceIn(0, 20),
            shadowMode = p?.getBoolean(KEY_SHADOW, false) ?: false,
            mutedChannelsCsv = p?.getString(KEY_MUTED, "").orEmpty(),
        )
    }

    fun save(
        context: Context,
        botToken: String,
        cfAccountId: String,
        cfApiToken: String,
        cfGatewayId: String,
        model: String,
        ambientPercent: Int,
        ambientCooldownSec: Int,
        contextTurns: Int,
    ) {
        prefs(context)?.edit()
            ?.putString(KEY_BOT_TOKEN, botToken.trim())
            ?.putString(KEY_CF_ACCOUNT, cfAccountId.trim())
            ?.putString(KEY_CF_TOKEN, cfApiToken.trim())
            ?.putString(KEY_CF_GATEWAY, cfGatewayId.trim())
            ?.putString(KEY_MODEL, model.trim().ifBlank { DEFAULT_MODEL })
            ?.putInt(KEY_AMBIENT_PCT, ambientPercent.coerceIn(0, 100))
            ?.putInt(KEY_AMBIENT_COOLDOWN, ambientCooldownSec.coerceAtLeast(0))
            ?.putInt(KEY_CONTEXT_TURNS, contextTurns.coerceIn(0, 20))
            ?.apply()
    }

    fun setShadowMode(context: Context, on: Boolean) {
        prefs(context)?.edit()?.putBoolean(KEY_SHADOW, on)?.apply()
    }

    /** Toggle a channel in the mute set; returns the new set. */
    fun toggleMutedChannel(context: Context, channelId: String): Set<String> {
        val id = channelId.trim()
        val cur = load(context).mutedChannels.toMutableSet()
        if (!cur.remove(id) && id.isNotBlank()) cur.add(id)
        prefs(context)?.edit()?.putString(KEY_MUTED, cur.joinToString(","))?.apply()
        return cur
    }

    fun isEnabled(context: Context): Boolean = prefs(context)?.getBoolean(KEY_ENABLED, false) ?: false
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context)?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
    }
}
