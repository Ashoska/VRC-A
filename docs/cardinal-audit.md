# Cardinal audit — speed, cost, naturalness, memory (2026-09-24)

The findings below were first recorded as an investigation (no behaviour change); **every one has
since been fixed** — see [Results after the fixes](#results-after-the-fixes) for what changed and the
measured before/after. The original findings are kept unchanged underneath for reference. Evidence
markers: 🧪 reproduced by running the real bot in the **Cardinal Lab** (`tools/cardinal-lab/`, script
named in brackets); 📖 from reading the code; ❓ needed a LIVE run.

## Results after the fixes

Measured in the Cardinal Lab with real Workers AI calls (LIVE), same scripts before and after.
"Before" = commit `cf1bda9` (start of this work); "after" = the shipped code.

**Knowledge** — `recall.txt`: a lived-in memory (people, nicknames, server moments) then 10 memory
questions ("who runs the events here?", "what's alice's cat called again", "where does ember live?",
"what do i do again lol", …), each checked for the right fact. Two runs each (model answers vary):

| | before | after |
|---|---:|---:|
| correct answers | 6/10 + 8/10 = **14/20** | 10/10 + 10/10 = **20/20** |
| neurons per run (10 replies + learning) | 395.2 / 370.3 | 189.7 / 204.5 (**−49%**) |
| neurons per reply | 37.1 | 18.5 |
| reply prompt | ~1,190 tokens | ~510 tokens |
| reply latency p50 | 1.72 s / 0.96 s | 0.92 s / 1.20 s |

Before, the misses were confident wrong answers ("alice doesn't have a cat", "finn is learning
Japanese", "bob doesn't have time for games"). After, every answer used the stored fact.

**A full evening** — `seeded-memory.txt` + `hangout.txt`: 127 human messages across three channels,
29 addressed, ambient on at the production default, a Japanese speaker, a "stop", a react request:

| | before | after | change |
|---|---:|---:|---:|
| **total neurons** | **1,090.1** | **631.4** | **−42%** |
| replies (70B) | 28 × 37.5 = 1,051.4 | 28 × 19.6 = 548.8 | −48% per reply |
| learning (8B) | 3 passes = 33.9, read ~18 messages | 8 passes = 77.1, read all 191 lines | +43 neurons, ~10× coverage |
| director (8B) | 3 × 1.6 = 4.8 | 3 × 1.8 = 5.5 | same |
| reply prompt | 1,241 tokens | 568 tokens | −54% |
| reply latency p50 / p95 | 1.12 s / 4.51 s | 0.86 s / 1.81 s | faster |
| neurons per 1,000 messages (this mix) | 8,583 | 4,972 | −42% |
| scripted checks | 22/22 | 22/22 | |

Learning costs a little more because it now reads every message (and Cardinal's own replies) instead
of the last few before each reply; the reply savings are ~12× larger than that increase.

### What was fixed

| # | Finding | Fix | Verified |
|---|---|---|---|
| 1 | "Deprecated" 8B `CHEAP_MODEL` | **Correction:** the id still works — Cloudflare serves it as `llama-3.1-8b-fast-v2` (4,119 / 34,868 neurons per M, cheaper input than before). Kept. Director / learn failures now show in the activity log (deduplicated), and a learn batch that fails is retried with the next one. | LIVE: `result.model`, every run |
| 2 | Identity filter deleted real facts | Compares whole words minus filler ("goes by ali") instead of substrings. | `identity-filter.txt` 6/6 |
| 3 | Personality froze at 24 traits; digest cut mid-trait | A new trait always gets a slot (replaces the oldest never-established one); traits not shown for 3 days lose strength and fade out; reworded duplicates merge; "traits" that just restate the core ("sassy", "witty and playful") or an assistant persona are dropped; mood changes at most every 20 min; the digest is built from whole items, most-recent first among equals. | `personality-cap.txt` ✓, temporary unit checks |
| 4 | Learning saw ~9 messages per 150 s | Every message counts (replied or not, Cardinal's own included). A pass runs after 10 new messages (≥2 min apart), after 30 in a very busy channel (≥30 s apart), or when the channel goes quiet (45 s) — and reads **everything since the last pass** (up to 50). Only on the FULL/TRIM budget rungs. | `pileup.txt` 14/14 messages, hangout 191/191 lines |
| 5 | Gateway: deaf after server close, 4014 retried forever, stale-socket callbacks | `onClosing` answers the close and resumes immediately; fatal codes (4004, 4010–4014) stop with FAILED and a plain reason ("turn on MESSAGE CONTENT INTENT…"); callbacks from an old socket are ignored. | `gateway-codes.txt`: ~50 s deaf → 1.1 s; 4014 → FAILED with reason; `lifecycle.txt` ✓ |
| 6 | Reply prompt ~65% boilerplate | Short fixed core (identity + the voice rules) + only what this moment needs: who it's answering (always), the channel, the server's most-used emojis (always, so Cardinal can use one when it wants), and — only when relevant — people named or talking, server memories, the earlier summary, the names / memory-question rules. People named in a message always come with what Cardinal knows about them. | prompt 1,241 → 568 tokens, knowledge 14/20 → 20/20 |
| 7 | Budget estimates wrong both ways | Every call is charged from Workers AI's own `usage.neurons` (exact); the old flat estimates are gone. | report "neurons" = billed |
| 8 | Fake "just now" recency; core memories in every reply; topic archive never written; summary lost on restart | Age shows when a moment happened (first seen), not when last mentioned; no always-on core memories (retrieved only when the conversation touches them); mentions strengthen a memory at most hourly and unmentioned ones slowly rank lower; the first message after a 12-min lull archives the previous conversation (revivable later); the live summary is saved. | temporary unit checks |

Also fixed from the detailed sections below: "stop" only triggers on a real stop request aimed at
Cardinal ("don't stop" / "stop by the event" no longer mute it for 2 min) and leaves a trace; a react
request records one trace, not two; the director is asked at most every 20 s per channel and reacts
unprompted at most every 90 s, and it knows which channel it's in; typing dots fire immediately
(not after the history fetch) and a referenced channel is fetched in parallel; a reply to an older
message is marked inline ("(replying to bob: "…")") instead of inserted as a new turn; the bot's own
@mention is stripped consistently; most-used emojis instead of alphabetical; learned names like
"@alice" / "Alice (ali)" resolve; short or everyday nicknames ("ali", "boss") only pull a card in
when the message clearly points at a person; reactions no longer overwrite a learned vibe; channel
bits need two shared words to surface; a second language is recorded as "also speaks" instead of
replacing the main one.

**Learning quality guards** (found while testing the fixes — the cheap learner invented facts like
"from Brazil" / "game developer" and wrote "none" into fields): learned facts must mostly use words
actually said by or about that person in the batch (no extra model call); nicknames must appear in
the chat; "none"/"unknown" values count as empty; relationship and how-to-treat are filled once and
then kept (a learned mood no longer overwrites "server regular, basically runs events"); generic
relationships ("member") and chat-mood filler ("has a sense of humor…") aren't stored; facts tied to
today/tonight aren't stored; "alice plays X" is stored as "plays X" so duplicates merge. A low
temperature for the learner was tried and **reverted** — it made the 8B loop until its token limit.
Remaining limit: the 8B still occasionally mixes up who said what ("bob likes pineapple pizza" after
bob *asked* about it); those facts are grounded in real words, so they pass.

## Original findings (before the fixes)

### TL;DR — fix these first

| # | Finding | Impact | Evidence |
|---|---|---|---|
| 1 | `CHEAP_MODEL = @cf/meta/llama-3.1-8b-instruct` was **deprecated by Cloudflare on 2026-05-30**. It drives the ambient **director**, **all memory learning** (people facts, summary, self, server culture, channel bits) and the **CHEAP** budget rung. Its failures are swallowed without a log line. | If the endpoint is gone, Cardinal has not learned anything since memory moved to the 8B (commit 344c8b2), never joins conversations unprompted, and the CHEAP rung can't reply. Nothing in the admin tab would show it. | 📖 + Cloudflare changelog. ❓ one `probe_models.py ping` confirms. |
| 2 | **User-card facts are silently deleted** by the "don't restate identity" filter: it treats plain substring containment as "same", so nickname `ali` deletes "lives in Australia", relationship `friend` deletes "best friends with carol", a user named `al` loses every fact containing "al" (Valorant, metal…). It re-runs over **all** stored facts on every update. | Profiles lose real facts, worse for short names/nicknames and common relationship words. | 🧪 6/6 facts lost [`identity-filter.txt`] |
| 3 | **Personality freezes at 24 traits.** Traits only enter through `noteSelf` (the decaying `applyReflection` is never called), nothing ever decays, and a new trait (strength 2) sorts last and is cut by the 24 cap on insert. The digest is then hard-truncated at 600 chars **mid-trait** ("into lo-fi b"). `style` and `episodes` are never written. | Cardinal's self stops evolving once it has 24 traits; the ones he got first dominate forever. | 🧪 [`personality-cap.txt`] |
| 4 | **Learning covers only the last ~9 messages per 150 s per channel.** The post-reply learn pass reuses the reply's 8-turn context and shares a 150 s throttle with the pile-up observer; the observer's counter resets on every reply. | In a busy channel (>4 msgs/min) most of what people say is never seen by the learner. | 🧪 118-message hangout → 2 learn passes, ~18 messages seen [`hangout.txt`] |
| 5 | **Gateway: a Discord-side close leaves Cardinal deaf** until a heartbeat goes un-ACKed (the listener has no `onClosing`, so the close handshake never completes). Non-recoverable close codes (4004 bad token, 4013/4014 intents) are retried forever with no reason shown. Callbacks from an **old** socket still run `scheduleReconnect()`, flipping the shared status to "Reconnecting…" after a restart (and, after a zombie recovery, able to tear down the new socket ~60 s later). | Up to ~40–80 s of missed/late replies per server-side close; an intents misconfiguration shows as endless "Reconnecting…". | 🧪 ~50 s deaf, message answered ~51 s late (two runs); a 4014 rejection retried endlessly with only "Heartbeat not ACKed" in the log [`gateway-codes.txt`, `lifecycle.txt`] |
| 6 | **The reply prompt is ~1,000–1,250 tokens with a lived-in memory; ~65% is fixed boilerplate** (duplicated identity line, rule blocks that reference sections that aren't there, a stale instruction about a removed "tail"). Input is ~85% of the cost of a 70B reply. | ~25–35% of reply cost is removable without touching behaviour. | 🧪 prompt anatomy [`seeded-memory.txt`] |
| 7 | **Budget accounting is wrong in both directions**: flat estimates charge 60 per reply (real ≈ 30–40) and 8 per 8B call (real learn/observe ≈ 30–40 on the deprecated model), and the ladder uses `max(estimate, real)` — so the over-estimate always wins even with the Analytics token set. | TRIM/CHEAP/SILENT kick in early for replies and late for learning. | 🧪 app estimate 1,300 vs lab estimate 589 for the same 118 messages [`hangout.txt`] |
| 8 | Server-culture recency is fake: every keyword overlap re-stamps a memory's time, so months-old moments render as **"(just now)"**, and the two strongest ("core") memories are injected into **every** reply. The **topic archive is never written** (the "revive a dead conversation" feature is dead) and the rolling summary is in-memory only. | Repetitive references to the same old jokes; no conversation revival; context lost on every restart. | 🧪 + 📖 |

---

## How to see it yourself

```bash
tools/cardinal-lab/lab.sh run tools/cardinal-lab/scripts/identity-filter.txt   # finding 2
tools/cardinal-lab/lab.sh run tools/cardinal-lab/scripts/personality-cap.txt   # finding 3
tools/cardinal-lab/lab.sh run tools/cardinal-lab/scripts/gateway-codes.txt     # finding 5
tools/cardinal-lab/lab.sh run tools/cardinal-lab/scripts/seeded-memory.txt     # finding 6 (see prompts.md)
tools/cardinal-lab/lab.sh interactive & tools/cardinal-lab/labctl.py say alice "hey @Cardinal"
```

Every run writes `lab-runs/<run>/report.md` (cost/latency per role, prompt anatomy, quality flags,
turn log, final memory) and `prompts.md` (every model call verbatim). See `tools/cardinal-lab/README.md`.

---

## 1. Models and cost

**Current roles**

| role | model | when | prompt | output cap |
|---|---|---|---|---|
| reply | `llama-3.3-70b-instruct-fp8-fast` | every addressed message, ambient "reply" | ~750 tokens fresh, ~1,000–1,250 lived-in | 220 (90 short) |
| director | `llama-3.1-8b-instruct` ⚠ deprecated | ~22–33% of unaddressed messages that pass the free prefilter | ~160–250 | 80 |
| learn (after a reply) | same 8B | ≤1 per 150 s per channel | ~550 system + ≤9 turns | 420 |
| pile-up observer | same 8B | after 8 unreplied messages, same 150 s throttle | ~550 system + ≤30 turns | 420 |
| CHEAP rung reply | same 8B | budget ≥ 92% | same as reply | 90 |

**Prices (neurons per 1M tokens, in / out)** — 70B fp8-fast 26,668 / 204,805 · deprecated 8B
25,608 / 75,147 · live replacements: 8B-fp8 13,778 / 26,128, 8B-fp8-fast 4,119 / 34,868,
granite-4.0-h-micro 1,542 / 10,158, llama-3.2-3b 4,625 / 30,475, gemma-4-26b-a4b 9,091 / 27,273,
glm-4.7-flash 5,500 / 36,400, qwen3-30b-a3b 4,625 / 30,475, llama-4-scout 24,545 / 77,273.
10k neurons/day free, then $0.011 per 1k.

**Per-call cost (estimate, lived-in prompt ≈ 1,150 in / 25 out for a reply)**

| call | today | if moved to… |
|---|---|---|
| reply (70B) | ≈ 31 in + 5 out ≈ **36** | trimmed prompt (−350 tok) ≈ 27 · gemma-4-26b ≈ 11 · glm-4.7-flash ≈ 7 · qwen3-30b ≈ 6 (❓ quality) |
| learn (8B, ~800 in / 120 out) | ≈ **30** (budgeted as 8) | 8B-fp8-fast ≈ 7.5 · granite-micro ≈ 2.4 |
| director (8B, ~250 / 20) | ≈ 8 | 8B-fp8-fast ≈ 1.7 |

Observations:
- The deprecated 8B's **input price is almost the same as the 70B's**, so the "CHEAP" rung was never
  much cheaper for these input-heavy prompts (21 vs 22 neurons for the same prompt in the lab).
- Output is 7.7× the input price on the 70B, but replies are short — **input dominates (~85%)**,
  so prompt trimming is the biggest no-quality-risk lever.
- Workers AI prefix caching gives a discount only on models that list a cached-input price (kimi,
  glm-5.x, deepseek-v4). The 70B has none, so `x-session-affinity` would at most cut latency.
  The lab can measure it (`--set affinity=1 --live`).
- No `temperature` is sent (model default). The JSON roles (director/observe) would be steadier at
  a low temperature; replies may feel livelier a bit higher — ❓ A/B in the lab.

## 2. Speed

📖 Before the model starts, an addressed reply does, in sequence: the channel-history REST fetch,
an optional cross-channel fetch, then **awaits** the typing-indicator POST. That's 2–3 Discord
round-trips from a phone (~0.2–1 s) before any tokens are generated, and the typing dots only appear
after the history fetch. Firing typing immediately and not awaiting it, and fetching in parallel,
removes that without changing behaviour. Memory stores re-parse their whole JSON on every message
(server memories up to 3× per message) — fine now, O(n) as they grow.

The lab's LIVE mode records real model latency per role (p50/p95) — ❓ not measured yet.

## 3. Prompt structure and naturalness

The full reply prompt is in `lab-runs/seeded1/prompts.md`. In order: anchor → seed persona →
[Who you are] → [Where you are] → [culture] → [running bit] → [cross-channel] → [People] →
[What's going on] → [Replying to] → [Language] → [Emojis] → [Don't repeat] → [Names] →
[Answering about people] → output rule → transcript.

| issue | detail |
|---|---|
| duplicated identity | `ANCHOR` ("You're Cardinal. You've been a regular…") is immediately followed by the seed ("You're Cardinal, a regular in this Discord…"). |
| stale instruction | [Emojis] says "put the emoji names in the tail's "react" list" — the tail was removed; this also contradicts "Reply with ONLY your message — no JSON". |
| rules about absent data | [Names] ("the people list above says who's who") and [Answering about people] ("ANSWER from the memory above — it's real") are sent even when there is no people list or memory (~170 tokens, ~22% of a fresh prompt). The seed says "Vary your tone with your MOOD (given below)" when no mood exists. |
| unbalanced bracket | the people block opens `[People here you know (…):` and never closes it; every other section is `[Label] text`. |
| self-mention inconsistency | the message being answered has the bot mention stripped ("hey  you alive?", double space) but the same message in history shows "hey @Cardinal you alive?" — history resolves mentions before stripping. |
| emoji hint | always the first 12 custom emojis **alphabetically** (~92 tokens every call), not the server's popular ones. |
| order vs caching | the big static rule blocks sit *after* the dynamic sections, so nothing but the first ~1,400 chars is a stable prefix. |
| seed style | 9 rules, mostly "NEVER/Don't". LLM chat voices usually get more natural with fewer, positively-phrased rules plus two or three example lines — ❓ A/B with `probe_models.py replay`. |
| director | biased to "reply/react" and not rate-limited when it says react/ignore (only a reply starts the 60 s ambient cooldown) — in a busy room it can be asked every few messages and react often. It also doesn't know the channel or Cardinal's mood. |
| react requests | "react to my message with X" records **two** traces (react + react-req). |
| stop detection | `STOP_RE` matches "stop"/"not now" anywhere in an addressed message ("@Cardinal don't stop", "can you stop by the event") → 2-minute back-off, with no trace entry (only the activity log). |
| transcript order | the answered message is always appended last, even when newer messages exist; a quoted `referenced_message` is inserted as an ordinary turn with no marker that it's a quote. |

## 4. Personality

📖🧪 What actually happens today: the 8B learn/observe pass may propose one `self.trait` and a
`mood`; `noteSelf` reinforces or appends (start strength 2, +1 per repeat, max 10). No decay, no
merging of near-duplicates ("sassy" vs "sassy and playful"), `style` and `episodes` never written
(the service always passes `style = null`; `applyReflection` has no caller), `DIGEST_TRAITS_INJECT`
unused. The observer only sees chat (whose Cardinal lines were written with the seed), so proposed
traits drift toward restating the seed ("sassy", "playful") — they add tokens, not identity. Mood is
free text from an 8B on every pass, so tone can swing between consecutive replies.

Once 24 traits exist the set is frozen (finding 3), and the digest cuts mid-line at 600 chars.

A stronger design (suggestion, not implemented): keep a lean seed; replace the bullet list with a
short self-portrait paragraph rewritten by a **daily consolidation** pass (one 70B call/day ≈ 40
neurons) from the current portrait + the day's strongest traits/moments; wire `applyReflection`
into that pass for decay/merging; inject only the portrait + top N traits; make mood slow (e.g. at
most hourly, or a small fixed set).

## 5. User profiles

- **Fact deletion by substring** (finding 2). The filter should compare whole normalised strings
  (and ignore identity values shorter than ~4 characters).
- Fields that are **never written** any more: `bits`, `alsoSpeaks`, `talkStyle` (rendered and shown
  in admin, but nothing fills them).
- **Overwrites:** any reaction to Cardinal's message replaces `sentiment` with
  "warm (reacted 👍)" / "cool (reacted 🙄)", which is then injected as `vibe: warm (reacted 👍)`;
  the observer also writes `sentiment`/`relationship` whenever non-blank.
- **Low-value injection:** a card with only a sentiment or `language: English` still gets injected;
  "speaks: English" for every English speaker is noise.
- **No time on facts:** "moving in december" never ages out; there's no "learned on" date to prefer
  fresh facts or expire time-bound ones.
- `interactions` counts replies to the person, not their messages.
- **Absent-person injection** matches any name/nickname ≥3 chars as a word — short or common-word
  nicknames ("ali", "boss", "mom") pull cards in on unrelated messages.
- Attribution is exact-name only (good — no more cross-contamination); names the 8B formats
  differently ("@alice", "Alice (ali)") are silently dropped.
- Poisoning / ephemeral / meta guards are solid.

## 6. Server culture and channel bits

- `reinforceReferenced` runs on **every** message: ≥2 shared keywords (server, ≥4-letter words) or
  ≥2 (channel, ≥3-letter words) bumps strength and re-stamps the time. Generic memories inflate,
  and the rendered age ("just now") is really "last time someone used those words".
- No decay; `core` = the 2 strongest are injected into every reply regardless of relevance → the
  same inside jokes resurface constantly.
- Channel bits deploy on a single shared keyword (35% roll, 30 min cooldown) — common words like
  "pic" make them eligible often (seen in the lab: the #general "hair physics" bit fired on "did
  you see carol's new pic in #media").
- Suggestion: store created-time separately from last-referenced time and render the created age;
  decay strength weekly; require rarer/more keyword matches to reinforce; inject core only when
  relevant; dedupe server memories against channel bits.

## 7. Conversation summary and topic archive

- `ConversationStore.touch()` stamps "last message" on every message, and `updateSummary` archives
  only if the channel was quiet ≥12 min *at that moment* — but it's called right after a message,
  so quiet time is always ~0. `sealIfDormant()` has no caller. **Nothing is ever archived**, so
  `reviveFor()` always returns empty and the "Earlier here:" block never appears.
- The live summary is in memory only — every app/process restart starts blank.
- With the 8B possibly dead (finding 1), there may be no summary at all.

## 8. Gateway robustness

- **No `onClosing`** in the WebSocket listener → a server-initiated close isn't completed or acted on
  until the next heartbeat tick finds the previous one un-ACKed (up to ~82 s at Discord's 41.25 s
  interval; lab, two runs: 49.1 / 50.9 s deaf, the message sent meanwhile answered 50.7 / 52.6 s
  later). Real Discord replays missed events on RESUME, so it shows up as very late replies rather
  than lost ones.
- **Close codes never reach the admin**: because `onClosing` is missing, the server's close code
  only arrives in `onClosed` after the bot's own heartbeat timeout closes the socket, and
  `onClosed` treats every code except 1000 as "resume". So 4004 (bad token) and 4010–4014 — 4014 is
  "MESSAGE CONTENT intent not enabled in the dev portal", the most likely setup mistake — loop
  forever with exponential backoff (capped at 30 s). The activity log only ever says "Heartbeat not
  ACKed — reconnecting" and the status never reaches FAILED. Lab (`gateway-codes.txt`, 4 s test
  heartbeat): a 4014 rejection was retried 4 times in 60 s (still going, status RECONNECTING) with no
  mention of 4014 anywhere the admin can see; once the gateway accepted again it resumed on its own.
- **Stale-socket callbacks** aren't ignored (`ws !== webSocket`), so an old socket's close/failure
  runs `scheduleReconnect()` on shared state (see TL;DR 5).
- Heartbeat, RESUME, op 7 / op 9 handling otherwise behave correctly in the lab.

## 9. Budget accounting

- `charge()` uses flat per-call constants regardless of model or length, although every Workers AI
  response carries `usage` (prompt/completion tokens) that could be priced exactly.
- The ladder reads `max(estimate, analytics)`; with an over-estimating reply constant the estimate
  always wins, so the real-usage sync can never correct it downward.
- The learn pass still runs on the CHEAP rung (where the point is to save).

---

## Recommendations (as written before the fixes — see "What was fixed" above for what shipped)

In priority order; each can be checked in the lab before shipping.

1. **Confirm and replace the 8B** — `probe_models.py ping`. Candidates for director/learn:
   `llama-3.1-8b-instruct-fp8-fast` (closest drop-in, ~6× cheaper input than the deprecated model),
   `granite-4.0-h-micro` (~17× cheaper), `llama-3.2-3b-instruct`. Pick with
   `lab.sh run hangout.txt --live --remap observe=<model>,director=<model>` and compare stored memory.
   Also log director/observe failures to the activity log.
2. **Fix the identity-filter substring match** (whole-string similarity, min length) — then re-run
   `identity-filter.txt` (expect 6/6).
3. **Personality lifecycle** — decay/merge (wire `applyReflection` or a daily consolidation), stop
   the 24-cap freeze, cut the digest at a line boundary / top-N traits.
4. **Learning coverage** — learn from all messages since the last learned message id (bounded, e.g.
   ≤30), independent of the reply throttle; include Cardinal's own reply.
5. **Prompt trim** (same behaviour, ~25–35% cheaper replies): drop the duplicate anchor line, send
   [Names]/[Answering…] only when cards/memories are present, delete the stale "tail" clause, close
   the people bracket, consistent mention handling, most-used emojis instead of alphabetical, move
   static rules before dynamic sections.
6. **Gateway**: `onClosing` → close + reconnect/resume immediately; classify close codes (4004,
   4010–4014 → stop with FAILED and a plain reason such as "enable the Message Content intent");
   ignore stale-socket callbacks. Verify with `gateway-codes.txt` + `lifecycle.txt`.
7. **Accounting**: charge from `usage` × a price table; use the analytics value as authoritative
   when present; skip the learn pass on CHEAP/REACT_ONLY.
8. **Memory hygiene**: separate created/referenced times, decay, relevance-gated core memories, fix
   the topic archive (seal on the observer noticing a lull, or on the next message after a ≥12 min
   gap *before* stamping), persist the live summary.
9. **Latency**: fire typing immediately without awaiting; parallelise the history + cross-ref fetches.
10. **Reply model A/B** (only after 1–5): `probe_models.py replay <run>/calls.jsonl --models …` on
    real captured prompts, then a full `--live --remap reply=…` hangout. Keep the 70B unless a
    cheaper model is indistinguishable in tone.

## AI Gateway — what's worth turning on

- **Set a gateway id in the bot's Config tab** (already supported). Every call is then logged with
  prompt, response, tokens, cost and duration, and `tools/cardinal-lab/aig_logs.py digest` turns the
  logs into a readable transcript — the "no screenshots" view of production. Gateways created on or
  after 2026-09-24 use Workers Logs pricing/retention; older ones keep 100k free stored logs.
- **Spend limits** (dollar budget per window, blocks with 429 past it) as a hard backstop under
  the app's ladder — ❓ check it applies to Workers AI pricing on your account.
- **Custom metadata** (`cf-aig-metadata`, ≤5 keys) — tagging each call with its role
  (reply/director/learn) would make the logs filterable (app change; the lab already does this).
- Caching and fallback routing: not useful here (unique chat turns; no-fallback is a product rule).

## LIVE runs

Done (results above): the 8B is alive (served as `llama-3.1-8b-fast-v2`); reply quality, length,
latency and cost with a lived-in memory; what the learner stores from a real conversation; the
knowledge A/B. Still open: **what production is doing right now** — set a gateway id in the bot's
Config tab, then `aig_logs.py digest --since 24h` (needs an AI Gateway Read token); and a reply-model
A/B (`probe_models.py replay` + `--remap reply=…`) if an even cheaper reply model is wanted.

LIVE lab runs need `CF_ACCOUNT_ID` at runtime (and a Workers AI token, which the cloud environment's
credential proxy injects); nothing is committed. The lab shares the account's 10k free neurons/day
with the live bot — use `--cap`.
