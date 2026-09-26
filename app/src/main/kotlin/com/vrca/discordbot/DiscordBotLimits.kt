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
    /** Replies. Gemma 4 26B (MoE, 4B active): matched the 70B on the memory, day, correction and route lab tests at ~40% of the cost and ~2x the
     *  speed. Its "thinking" is switched off per call (see DiscordBotAi.THINKING_KWARG_MODELS). */
    const val REPLY_MODEL = "@cf/google/gemma-4-26b-a4b-it"
    /** The previous default. Installs still on it are moved to [REPLY_MODEL] once (DiscordBotStore). */
    const val LEGACY_REPLY_MODEL = "@cf/meta/llama-3.3-70b-instruct-fp8-fast"
    /** Learner + director: the small model is still the cheapest per learn pass (long input, short output). */
    const val CHEAP_MODEL = "@cf/meta/llama-3.1-8b-instruct"
    /** Paraphrase-pile merge: the 8B only picks a subset; Gemma actually combines, at the same ~2.4 neurons. */
    const val MERGE_MODEL = "@cf/google/gemma-4-26b-a4b-it"
    /** The learn pass (reads each batch of chat, writes memory). */
    @Volatile @JvmStatic var LEARN_MODEL = CHEAP_MODEL   // the lab can swap it (--set learnModel=…)

    // ── Context assembly (clarity + bounded prompt) ───────────────────────
    /** Raw transcript turns fed alongside the rolling summary. */
    const val CONTEXT_RAW_TURNS = 8
    const val MAX_MSG_CHARS = 240
    /** Normal reply cap; short banter uses the smaller cap (adaptive length). */
    const val REPLY_MAX_TOKENS = 220
    const val SHORT_REPLY_MAX_TOKENS = 90
    const val SUMMARY_MAX_CHARS = 500
    /** Learn-pass answer cap (it lists only people with something new, so this is rarely reached). */
    const val LEARN_MAX_TOKENS = 320
    /** The director's reply/react/ignore call runs cooler for a steadier decision. (Not the learn pass:
     *  at 0.2 the small model looped until max_tokens; invented facts are caught by grounding instead.) */
    const val DIRECTOR_TEMPERATURE = 0.2
    /** Top-N relevant facts injected per active user card (pinned always included). */
    const val USER_FACTS_INJECT = 4
    /** Recent turns whose words decide what's "relevant now" (memories, facts) — also gives continuity:
     *  something pulled in stays while its words are still in the recent messages. */
    const val RELEVANCE_WINDOW_TURNS = 6
    /** Distinct words kept from that window (newest message first). */
    const val RELEVANCE_KEYWORDS_MAX = 32
    /** Other people's lines in one prompt (named/asked-about people first). */
    const val OTHER_PEOPLE_MAX = 3
    /** Cards found by searching everyone's facts for a "who …?" question with nobody named. */
    const val RECALL_SEARCH_MAX = 2
    /** Custom server emojis offered in the prompt (most-used first). */
    const val EMOJI_HINT_MAX = 6
    /** Extra custom emojis whose NAME matches the conversation, added on top of the most-used ones. */
    const val EMOJI_TOPICAL_MAX = 4
    /** Revived topics / relevant server memories injected for a given message. */
    const val TOPIC_RETRIEVE_MAX = 2
    const val EVENT_RETRIEVE_MAX = 3
    /** Cap for the admin-taught extras in the personality digest (every trait is always shown). */
    const val SELF_DIGEST_MAX_CHARS = 600
    /** Strongest traits always in a reply's [You] (plus titles, pinned, and ones the message touches). */
    const val SELF_TRAITS_IN_PROMPT = 4
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
    /** After asking the director about a channel, don't ask again for this long (it's asked on a
     *  random share of messages; without a gap a busy room paid for a call every few messages). */
    const val DIRECTOR_MIN_GAP_MS = 20_000L
    /** After Cardinal replies to someone, their next messages (no @, no reply) may still be to him for
     *  this long — each exchange renews it. Only people inside this window are ever checked. */
    @Volatile @JvmStatic var FOLLOW_WINDOW_MS = 3 * 60_000L
    /** A follow-up check that says "not to Cardinal" this many times in a row ends that conversation. */
    const val FOLLOW_MISSES_TO_END = 2
    /** Their next line counts as to him for free only if it comes this soon after his reply, with nothing between. */
    @Volatile @JvmStatic var FOLLOW_FREE_MS = 90_000L
    /** An unprompted (ambient) emoji reaction at most this often per channel. */
    const val AMBIENT_REACT_COOLDOWN_MS = 90_000L
    /** When told to stop, back off in that channel for this long. */
    const val BACKOFF_MS = 120_000L

    // ── Learning (the cheap 8B pass that writes memory/summary/culture/self) ─────
    // Every message counts toward the next pass (replied or not, Cardinal's own included), and a
    // pass reads EVERYTHING since the last one (up to LEARN_FETCH) — so nothing said in a busy
    // channel slips past the learner. Kept batched so each 8B call covers many messages.
    /** A pass runs once this many messages are unlearned (and LEARN_MIN_INTERVAL_MS has passed). */
    const val LEARN_TRIGGER_MSGS = 8
    const val LEARN_MIN_INTERVAL_MS = 60_000L
    /** A very busy channel learns sooner, so messages never fall out of the LEARN_FETCH window. */
    const val LEARN_FORCE_MSGS = 30
    const val LEARN_FORCE_MIN_GAP_MS = 30_000L
    /** When a channel goes quiet this long with a few unlearned messages, learn the tail too. */
    @Volatile @JvmStatic var LEARN_LULL_MS = 45_000L
    const val LEARN_LULL_MIN_MSGS = 3
    @Volatile @JvmStatic var LEARN_LULL_MIN_GAP_MS = 60_000L
    /** Messages a learn pass reads (only the ones it hasn't seen are kept). */
    const val LEARN_FETCH = 50
    /** Each numbered line in the learn transcript is cut to this (a wall of text needs no more to be learned from). */
    const val LEARN_LINE_MAX_CHARS = 220
    /** Quiet gap that ends the active conversation segment → archived as a topic. */
    const val CONVO_GAP_MS = 12 * 60 * 1000L

    // ── Neuron budget + degradation ladder ────────────────────────────────
    const val DAILY_NEURON_BUDGET = 9_000L
    const val LADDER_FULL_FRAC = 0.80
    const val LADDER_TRIM_FRAC = 0.92
    const val LADDER_CHEAP_FRAC = 0.99
    const val USAGE_SYNC_INTERVAL_MS = 3 * 60 * 1000L

    // ── Self (personality) ────────────────────────────────────────────────
    /** Weighted traits kept in the store (the learn pass adds/reinforces them). */
    const val MAX_TRAITS = 24
    /** Memorable personal episodes Cardinal keeps (server EVENTS live in ServerMemoryStore). */
    const val MAX_EPISODES = 6
    /** A trait not shown again within this window loses 1 strength (dropped at 0). */
    const val TRAIT_DECAY_INTERVAL_MS = 3 * 24 * 3_600_000L
    /** The mood line changes at most this often, so the tone doesn't swing reply to reply. */
    const val MOOD_MIN_INTERVAL_MS = 20 * 60_000L

    // ── Conversation / topic archive ──────────────────────────────────────
    /** Archived dormant-conversation topics kept per channel (revivable hours later). */
    const val TOPIC_STORE_MAX = 60
    /** Shared server event-memories kept (unbounded by design; a sane FIFO ceiling). */
    const val SERVER_MEMORY_STORE_MAX = 400
    /** A server memory / channel bit is strengthened by chat mentioning it at most this often. */
    const val MEMORY_REINFORCE_COOLDOWN_MS = 3_600_000L
    /** A memory nobody has mentioned for this long ranks one strength point lower (per period). */
    const val MEMORY_FADE_MS = 14 * 24 * 3_600_000L

    // ── Day log ("what happened yesterday?") ──────────────────────────────
    /** Entries kept per day (moments are kept first; the oldest topic lines are thinned). */
    const val DAY_ENTRIES_MAX = 80
    /** A finished day with at least this many entries is condensed into a digest (one cheap call). */
    const val DAY_DIGEST_MIN_ENTRIES = 6
    const val DAY_DIGEST_MAX_CHARS = 700
    const val DAY_DIGEST_MAX_TOKENS = 220
    /** Raw entries are dropped (digest kept) once a day is this old. */
    const val DAY_RAW_KEEP_DAYS = 14
    /** Whole days are forgotten after this. */
    const val DAY_KEEP_DAYS = 120
    /** The day block in a reply prompt is capped at this. */
    const val DAY_BLOCK_MAX_CHARS = 900
    /** A card topic with more facts than this ("AI" ×7) gets one cheap merge pass for paraphrases. */
    const val FACTS_PER_TOPIC = 2
    /** A card is merged at most this often. */
    const val FACT_MERGE_COOLDOWN_MS = 6 * 3_600_000L
    /** A dispute/complaint against one of Cardinal's traits knocks this much strength off it. */
    const val TRAIT_DISPUTE_PENALTY = 4

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
