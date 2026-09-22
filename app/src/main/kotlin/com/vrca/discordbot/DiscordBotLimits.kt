package com.vrca.discordbot

/**
 * The bot's HARDCODED, bumpable limits — one place for every ceiling so the permanent
 * design can be tuned by editing a number, not hunting through the service.
 *
 * The philosophy: idle chatter is free (a heuristic decides), a normal reply is ONE model
 * call that also updates memory (the deltas tail), only genuinely-ambiguous multi-person
 * moments pay a cheap 8B "director" call, and a daily neuron budget degrades gracefully
 * instead of blowing the free tier. Everything here can be raised later.
 */
object DiscordBotLimits {

    // ── Models ────────────────────────────────────────────────────────────
    /** The strong reply model (fp8-fast for low latency). */
    const val REPLY_MODEL = "@cf/meta/llama-3.3-70b-instruct-fp8-fast"
    /** The cheap model for the director, memory updates, and reflection (~1/10th the neurons). */
    const val CHEAP_MODEL = "@cf/meta/llama-3.1-8b-instruct"

    // ── Context assembly (token efficiency) ───────────────────────────────
    /** Raw recent turns fed alongside the rolling thread summary (kept tight on purpose). */
    const val CONTEXT_RAW_TURNS = 6
    /** Per-message character cap in the fed transcript (a long paste can't blow the prompt). */
    const val MAX_MSG_CHARS = 240
    /** Reply generation cap — short replies read human AND cost far fewer output neurons. */
    const val REPLY_MAX_TOKENS = 200
    /** Rolling per-channel thread summary cap (chars). */
    const val SUMMARY_MAX_CHARS = 400

    // ── Fluid concurrency (the A→B fix) ───────────────────────────────────
    /** Quiet-period per PERSON before their burst is answered (so B never cancels A). */
    const val PER_USER_DEBOUNCE_MS = 1400L
    /** Window in which multiple people addressing the bot are considered "simultaneous". */
    const val COALESCE_WINDOW_MS = 2500L
    /** Max reply generations in flight per channel; extra people queue (finish-the-thought). */
    const val PER_CHANNEL_INFLIGHT = 2
    /** Per-person reply cooldown so one user can't monopolise the bot. */
    const val PER_USER_REPLY_COOLDOWN_MS = 4000L

    // ── Ambient (unaddressed) chatter ─────────────────────────────────────
    /** Default % chance the heuristic even considers an unaddressed message (admin-tunable). */
    const val DEFAULT_AMBIENT_PCT = 4
    /** Min seconds between ambient replies per channel. */
    const val DEFAULT_AMBIENT_COOLDOWN_SEC = 90
    /** After the bot has posted recently it backs off ambient chatter (don't dominate). */
    const val SELF_RECENT_QUIET_MS = 20_000L

    // ── Learning cadences (async, off the reply path) ─────────────────────
    /** Slow personality reflection (traits, weeks-scale reinforce/decay). */
    const val REFLECT_INTERVAL_MS = 20 * 60 * 1000L
    /** Fast mood nudge cadence (days-scale drift). */
    const val MOOD_INTERVAL_MS = 6 * 60 * 1000L
    /** Recent chat sampled for a reflection pass. */
    const val REFLECT_SAMPLE_MSGS = 24

    // ── Neuron budget + degradation ladder ────────────────────────────────
    /** Cloudflare free tier is 10,000 neurons/day; leave headroom so we never hard-fail. */
    const val DAILY_NEURON_BUDGET = 9_000L
    /** Rough neuron estimate per reply (70B) — used to project spend, not billed. */
    const val EST_NEURONS_REPLY = 3_800L
    /** Rough neuron estimate per cheap 8B call (director / memory / reflect). */
    const val EST_NEURONS_CHEAP = 350L
    /** Below this fraction of the budget → full 70B replies. */
    const val LADDER_FULL_FRAC = 0.75
    /** Between FULL and this → 70B on trimmed context. */
    const val LADDER_TRIM_FRAC = 0.90
    /** Between TRIM and this → cheap-model replies only. Above → reactions only, then silent. */
    const val LADDER_CHEAP_FRAC = 0.98

    // ── Memory ────────────────────────────────────────────────────────────
    /** Per-user card size cap (chars of the rendered card) so the prompt stays lean. */
    const val USER_CARD_MAX_CHARS = 340
    /** Facts kept per user card (pinned/admin-taught facts sort to the front, model facts trim). */
    const val USER_CARD_MAX_FACTS = 8
    /** Running bits kept per user card. */
    const val USER_CARD_MAX_BITS = 4
    /** Personality digest cap (chars) injected into every reply prompt. */
    const val SELF_DIGEST_MAX_CHARS = 500
    /** Trait store cap. */
    const val MAX_TRAITS = 16
    /** Episodic memory cap (memorable server moments). */
    const val MAX_EPISODES = 8

    // ── Traces / observability ────────────────────────────────────────────
    /** Decision-trace ring buffer size for the admin "Why" view. */
    const val TRACE_RING = 60
    /** Activity log lines kept. */
    const val ACTIVITY_LOG_CAP = 60
}
