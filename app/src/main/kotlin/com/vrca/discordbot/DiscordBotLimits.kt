package com.vrca.discordbot

/**
 * Cardinal's HARDCODED, bumpable limits — one place for every ceiling.
 *
 * Design: memory STORES are unbounded (text is tiny, and grab-only entries that are never
 * retrieved cost nothing) — the only real lever is how much gets INJECTED into a single prompt,
 * so the caps here are on retrieval/injection and pacing, not storage. The weak 8B model is
 * event-driven (only when something needs updating), never on a timer.
 */
object DiscordBotLimits {

    // ── Models ────────────────────────────────────────────────────────────
    const val REPLY_MODEL = "@cf/meta/llama-3.3-70b-instruct-fp8-fast"
    const val CHEAP_MODEL = "@cf/meta/llama-3.1-8b-instruct"

    // ── Context assembly (clarity + bounded prompt) ───────────────────────
    /** Raw transcript turns fed alongside the rolling summary. */
    const val CONTEXT_RAW_TURNS = 8
    /** Messages pulled for a cold-start / observer catch-up summary. */
    const val HISTORY_FETCH = 30
    const val MAX_MSG_CHARS = 240
    /** Normal reply cap; short banter uses the smaller cap (adaptive length). */
    const val REPLY_MAX_TOKENS = 220
    const val SHORT_REPLY_MAX_TOKENS = 90
    const val SUMMARY_MAX_CHARS = 500
    /** Top-N relevant facts injected per active user card (pinned always included). */
    const val USER_FACTS_INJECT = 4
    /** Strongest server memories always folded into the digest. */
    const val CORE_MEMORIES_INJECT = 2
    /** Revived topics / relevant server memories injected for a given message. */
    const val TOPIC_RETRIEVE_MAX = 2
    const val EVENT_RETRIEVE_MAX = 3
    /** Traits folded into the always-injected personality digest (store holds more). */
    const val DIGEST_TRAITS_INJECT = 10
    const val SELF_DIGEST_MAX_CHARS = 600
    /** Admin-side full render cap (prompt uses the retrieval-limited render). */
    const val USER_CARD_MAX_CHARS = 600
    /** The bot's own recent replies fed back so it doesn't repeat itself. */
    const val ANTI_REPEAT_REPLIES = 4

    // ── Fluid concurrency + pacing ────────────────────────────────────────
    // No upfront debounce/settle: a reply fires the moment its trigger lands. Ordering is the
    // per-channel reply mutex (finish-the-thought), and buildContext runs INSIDE that lock so a
    // queued message always sees the freshest state — anything typed while it waited is folded in
    // for free, and a same-person follow-up that arrived AFTER the last reply is caught by the free
    // "already covered" snowflake check instead of an upfront wait.
    const val PER_CHANNEL_INFLIGHT = 2
    const val PER_USER_REPLY_COOLDOWN_MS = 3500L

    // ── Ambient / chattiness ──────────────────────────────────────────────
    const val DEFAULT_AMBIENT_PCT = 22
    const val DEFAULT_AMBIENT_COOLDOWN_SEC = 60
    const val SELF_RECENT_QUIET_MS = 15_000L
    /** When told to stop, back off in that channel for this long. */
    const val BACKOFF_MS = 120_000L

    // ── Observer (event-driven memory/summary catch-up when it stays silent) ─
    // Fires only after this many unreplied messages pile up, and at most this often — kept
    // conservative so the cheap catch-up costs little in a busy channel.
    const val OBSERVER_MIN_NEW_MSGS = 8
    const val OBSERVER_MIN_INTERVAL_MS = 150_000L
    /** Quiet gap that ends the active conversation segment → archived as a topic. */
    const val CONVO_GAP_MS = 12 * 60 * 1000L

    // ── Neuron budget + degradation ladder ────────────────────────────────
    const val DAILY_NEURON_BUDGET = 9_000L
    /** Realistic per-call estimates (fp8-fast 70B ~tens of neurons; 8B ~single digits).
     *  Used only as a FALLBACK; the real number comes from the Cloudflare usage sync. */
    const val EST_NEURONS_REPLY = 60L
    const val EST_NEURONS_CHEAP = 8L
    const val LADDER_FULL_FRAC = 0.80
    const val LADDER_TRIM_FRAC = 0.92
    const val LADDER_CHEAP_FRAC = 0.99
    const val USAGE_SYNC_INTERVAL_MS = 3 * 60 * 1000L

    // ── Self (personality) ────────────────────────────────────────────────
    /** Weighted traits kept in the store (the reply tail + observer nudge these). */
    const val MAX_TRAITS = 24
    /** Memorable personal episodes Cardinal keeps (server EVENTS live in ServerMemoryStore). */
    const val MAX_EPISODES = 6

    // ── Conversation / topic archive ──────────────────────────────────────
    /** Archived dormant-conversation topics kept per channel (revivable hours later). */
    const val TOPIC_STORE_MAX = 60
    /** Shared server event-memories kept (unbounded by design; a sane FIFO ceiling). */
    const val SERVER_MEMORY_STORE_MAX = 400

    // ── Channel awareness (identity + per-channel running bits) ───────────
    /** Per-channel running bits kept (unbounded by design; a sane ceiling). */
    const val CHANNEL_MEMORY_STORE_MAX = 60
    /** A bit won't redeploy for this long, so a running joke stays occasional (30 min). */
    const val CHANNEL_BIT_DEPLOY_COOLDOWN_MS = 30 * 60 * 1000L
    /** % chance an eligible bit actually surfaces — most triggers pass with no joke. */
    const val CHANNEL_BIT_DEPLOY_CHANCE = 35
    /** Messages pulled from a channel someone cross-references ("did you see that in #media"). */
    const val CROSSREF_FETCH = 10

    // ── Traces / observability ────────────────────────────────────────────
    const val TRACE_RING = 60
    const val ACTIVITY_LOG_CAP = 60
}
