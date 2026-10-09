package com.vrca.discordbot

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Cloudflare Workers-AI neuron budget — the REAL number, not a fake estimate.
 *
 * Two layers:
 *  1. A **persisted per-UTC-day estimate** (`vrca_discord_budget`, keyed by the UTC date) that
 *     accumulates a small calibrated cost per model call and SURVIVES app restarts (the old bug:
 *     the counter reset to 0 on reopen). This is the immediate, offline-safe number.
 *  2. A **real usage sync** ([syncReal]) that queries Cloudflare's GraphQL analytics for today's
 *     actual neuron spend and adopts it as authoritative when available (needs a token with
 *     Account Analytics:Read — entered separately in the Bot tab so it can't run up billing).
 *
 * The absolute number the ladder reads is `max(persisted estimate, last real sync)` per day, so a
 * lagging analytics window never makes the budget look emptier than the estimate already knows.
 */
object NeuronBudget {
    private const val PREFS = "vrca_discord_budget"
    private const val KEY_DAY = "utc_day"
    private const val KEY_EST = "estimate"       // running estimate for KEY_DAY
    private const val KEY_REAL = "real"          // last real sync value for KEY_DAY

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS).build()
    }
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private fun utcDay(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(System.currentTimeMillis())

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    private fun rolloverIfNeeded(ctx: Context) {
        val p = prefs(ctx)
        val today = utcDay()
        if (p.getString(KEY_DAY, null) != today) {
            p.edit().putString(KEY_DAY, today).putLong(KEY_EST, 0L).putLong(KEY_REAL, -1L).apply()
        }
    }

    /** Accumulate an estimated cost and return the authoritative day total. */
    @Synchronized
    fun add(ctx: Context, est: Long): Long {
        rolloverIfNeeded(ctx)
        val p = prefs(ctx)
        val newEst = (p.getLong(KEY_EST, 0L) + est).coerceAtLeast(0L)
        p.edit().putLong(KEY_EST, newEst).apply()
        return current(ctx)
    }

    /** The authoritative day total = max(estimate, last real sync). */
    @Synchronized
    fun current(ctx: Context): Long {
        rolloverIfNeeded(ctx)
        val p = prefs(ctx)
        val est = p.getLong(KEY_EST, 0L)
        val real = p.getLong(KEY_REAL, -1L)
        return if (real >= 0) maxOf(est, real) else est
    }

    /** Adopt a real analytics value for today (keeps the running estimate as a floor). */
    @Synchronized
    fun setReal(ctx: Context, neurons: Long) {
        rolloverIfNeeded(ctx)
        prefs(ctx).edit().putLong(KEY_REAL, neurons.coerceAtLeast(0L)).apply()
    }

    /**
     * Query Cloudflare GraphQL analytics for today's real Workers-AI neuron spend. Returns the
     * absolute day total (already adopted via [setReal]) or null on any failure — the caller keeps
     * the estimate. Best-effort + tolerant of schema drift (parses several plausible field names).
     */
    suspend fun syncReal(ctx: Context, cfg: DiscordBotStore.Config): Long? = withContext(Dispatchers.IO) {
        val token = cfg.analyticsToken.ifBlank { return@withContext null }
        val account = cfg.cfAccountId.ifBlank { return@withContext null }
        try {
            val day = utcDay()
            val query = """
                query { viewer { accounts(filter:{accountTag:"$account"}) {
                  aiInferenceAdaptiveGroups(limit:1000, filter:{date_geq:"$day", date_leq:"$day"}) {
                    sum { totalNeurons }
                  } } } }
            """.trimIndent()
            val body = JSONObject().put("query", query).toString().toRequestBody(JSON)
            val req = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/graphql")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .post(body).build()
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext null
                val neurons = parseNeurons(raw) ?: return@withContext null
                setReal(ctx, neurons)
                current(ctx)
            }
        } catch (_: Exception) { null }
    }

    /** Walk the GraphQL response for the summed neuron count, tolerating field-name drift. */
    private fun parseNeurons(raw: String): Long? {
        return try {
            val root = JSONObject(raw)
            if ((root.optJSONArray("errors")?.length() ?: 0) > 0) return null
            val accounts = root.optJSONObject("data")?.optJSONObject("viewer")?.optJSONArray("accounts")
            val groups = accounts?.optJSONObject(0)?.optJSONArray("aiInferenceAdaptiveGroups")
            if (groups == null || groups.length() == 0) return 0L
            var total = 0.0
            for (i in 0 until groups.length()) {
                val sum = groups.optJSONObject(i)?.optJSONObject("sum") ?: continue
                total += sum.optDouble("totalNeurons",
                    sum.optDouble("neurons", sum.optDouble("neuronsTotal", 0.0)))
            }
            total.toLong()
        } catch (_: Exception) { null }
    }
}
