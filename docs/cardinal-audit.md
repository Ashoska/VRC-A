# Cardinal audit — speed, cost, naturalness, memory (2026-09-24)

The findings below were first recorded as an investigation (no behaviour change); **every one has
since been fixed** — see [Results after the fixes](#results-after-the-fixes) for what changed and the
measured before/after. The original findings are kept unchanged underneath for reference. Evidence
markers: 🧪 reproduced by running the real bot in the **Cardinal Lab** (`tools/cardinal-lab/`, script
named in brackets); 📖 from reading the code; ❓ needed a LIVE run.

## Round 7 — getting to know people: tentative/confirmed memory, curiosity (LIVE lab)

Everything new here is free code: no extra model calls.
- **Tentative for 3 days.** A new note on a person, or a new trait of Cardinal's, is tentative. It disappears after 3 days unless it's confirmed.
- **What confirms it (people).** Two different people back it. Or the person says it again in a later conversation (10+ min after it was first seen). Or they answer "yeah" when Cardinal checks it; "nope" drops it.
- **What confirms it (Cardinal).** He shows the trait again in a later conversation, in his own words (not just repeating the line before).
- **Confirmed entries are protected.** They're never pushed out for newer ones: a full slot only swaps out a tentative entry. The old 3-day fade no longer deletes Cardinal's confirmed traits; they only lose rank.
- **Shown to him.** Unsure notes appear as "value (?)", with one line telling him to check before stating them. The admin tab shows "?".
- **Shorter entries.** Notes are 1-4 words; traits are 2-6.
- **Curiosity.** At most once a day per person, only in a casual moment and only when a basic slot is empty, the reply gets "If it fits naturally, ask them what they do / play / where they're from / what they're into". On the evening run it fired on 6 of 32 replies and he used it once, naturally ("so what's your poison, dave? games or just observing the madness?").
- **Opinions.** Asked his opinion, he gets "Say plainly if you like it or not", which fills his likes/dislikes. An earlier "take a clear side" was echoed word for word.

Lab: `knowing.txt` 8/8 three times in a row (unsure marker, two-person confirm, a full confirmed games slot keeps its entries, "yeah" confirms). The fixes it drove:
- The two-person check needed half the note per person, not two-thirds.
- A "play X" note filed under hobbies now goes to games.
- The yes/no check runs on filler lines ("yeah" is filler).
- New free rule: "i work at/in/for X".

All other suites pass: evening, people, recall, device, titles, traits, edge-learning, corrections, regressions, structure, basics, edge-replies, follow. Evening cost: 267.9 → 273.8 neurons (+2%).

## Round 6 — compact reply layout, Cardinal slots, device fixes (LIVE lab)

**Reply prompt, new layout** (`DiscordBotAi.reply`): a 4-rule core ("Do fun asks (politicians too). Answer what you know. Play
along with jokes about you. Don't invent real-world facts."), then one item per line: `Channel:` (no server name unless asked),
`Date:` (+ a separate `Time now:` line only on a time question), `Mood:`, `You (when it fits):` (every trait, grouped by slot),
`Talking to:` (name, nicknames, pronouns, live UTC offset; one known thing per line), `Others:` (up to 6: whole card for anyone
named, replied to, matched by a "who…?" search or one of the last 2 speakers; everyone else talking gets their name line + only
notes that match the conversation), then the conditional lines (`Earlier:`, `Server memories:`, `Bit here:`, `From #…`, day notes,
`Emojis:` only when neither of his last two replies used one, `Said recently:`) and short situation hints last. Transcript: 10
lines (was 8; a stored 8 migrates once), laugh-only lines folded onto the line they laugh at, his own older lines capped at 100
chars, no per-line pronouns, his line isn't re-quoted when it's the turn right above. The director's follow-up check no longer
repeats the exchange that's already in RECENT CHAT.

Measured on the 127-message evening (avg of 3 Round 5 runs → Round 6):

| | Round 5 | Round 6 |
|---|---|---|
| reply prompt | 559 tokens | 438 tokens (−22%) |
| per reply | 5.71 neurons | 4.52 neurons |
| evening total | 297.1 neurons | 267.9 neurons (−10%) |

What the lab caught while cutting (all put back or fixed): without "Reply with just your message, no name prefix" the model
sometimes echoed the person's message back → restored; without "politicians" it refused the joke-poem case → "(politicians
too)"; without "answer what you know" it dodged a known fact ("ask erin yourself") → restored; the drones bit leaked into
"12 times 12" → `You (when it fits):`; full cards leaked "plays JJ's" into a food answer → `(bring notes up only if they fit)`.
It also copies a "(replying to X)" tag or writes extra "cardinal:" turns → stripped in code.

**Cardinal slots** (`PersonalityStore.KINDS`): title (3), likes (5), dislikes (5), speech = how he types (6), bit (4), habit (4);
a full slot swaps out its weakest new entry. Proof per slot (`selfProven`): likes/dislikes need his own line with a like/hate
word; habit needs 2 of his lines; speech needs 2 of his lines and, for caps/lowercase/emoji claims, lines that actually show it;
titles need 2 people or his own "i'm the X"; his line doesn't count when it only repeats the line before it (the device's
"gay homo" habit). "annoyed at X"-style moods are never traits. The learner is asked for these slots and no longer told
"usually []"; the old "notes about Cardinal → habit" conversion (source of "has a cat named miso" as HIS habit) is gone.
Admin: `kind: trait` adds to a slot.

**People fixes from device chats**: a person note the learner filed under "Cardinal" but citing someone else's line goes to that
person; the learner is no longer told "most lines have none" (max 6 notes); "A.I" no longer counts as "I" ("rename cardinal to
A.I" became Cornelius's "about"); a note whose words sit in the part of a line about Cardinal is dropped; languages: said in
words ("i speak czech", "add Japanese to my languages") go straight onto the card, a language filed as likes/work is moved or
dropped, "i dont speak french" never adds French, `speaks:` shows for a non-English speaker or a language question (and for anyone
asked about); pets must be animals ("reached gremlin incarnate"); from/lives need a real place phrase (a VRChat world isn't a
hometown); vague work ("plans") dropped; "Straftat's game" → "Straftat"; free rules for "I'm the notorious X" (their notes),
"everyone calls me X" (nickname), "@Cardinal im Ash" (the name they go by), "mine are she/her" and "me too" under someone's
pronouns; "friend"/"rival" is how they get on with Cardinal (`with them`), not a server role; "what's our relationship?" and
"add it as my role" get a hint (answer from the card / say it's noted, never "i can't"). Time questions show the exact minute. A taunt at "you" ("i bet you cant even play gmod in vr") is no longer the speaker's dislike; the he-means-you hint needs the line to follow one of Cardinal's lines or name him (not "my voldemort … post him"); `Talking to:` keeps every note but moves the ones that don't match the message to a "background" line (fixed "plays JJ's" in a food answer).
**Cost tab**: "Account today" (the whole Cloudflare account, which includes lab runs) vs "Cardinal today" (his own exact spend);
"Per reply" now uses his own spend.

## Round 5 — typed memory, evidence-checked learning, timezones, compact prompt (LIVE lab)

**Why.** Cards were a free-text fact pile guarded by a growing wall of regex filters (joke/right-now/bot-talk/
transient/…); each new junk case needed another filter, and a real "bot developer" fact could be cut by one.
The learner also re-read everyone's known facts every pass, and the day log recorded a topic line every pass.

**What changed** (details in CLAUDE.md "v7")
- **Typed slots**: work, from, lives, game, hobby, likes, dislikes, pet, about (+ timezone). A note is a
  slot + a short value, shown as "work: bank teller · plays: Valorant". The old fact filters are gone.
- **Learner**: numbered lines, filler dropped, laughs folded onto the line they laugh at, and a batch of
  small talk is skipped without a model call. The answer is `{sum, notes, me, mood, moment?, joke?}` with
  at most 4 notes; the known-people block is gone (the 8B copied it back into its answer).
- **Evidence check** (free): each note must be backed by the line it cites — the person's own line (with
  I/me/my) or one naming them — and is rejected when that line is a question, joke, what-if, plan,
  right-now, about a relative, negated, denied, said *to* them as banter, or a place without a place word.
  A regex backstop adds plain "i work as / i live in / moved to / i play / i have a cat named" statements
  the 8B skipped, through the same checks.
- **Timezone**: stated ("i'm on EST", "utc+2"), revealed ("it's 3am here"), or from where they live;
  stored as a zone and always shown as the live UTC offset (daylight saving included), only when time is
  asked about.
- **Last seen** is their last message anywhere (was: their last talk with Cardinal).
- **Day log**: one topic per finished conversation; moments only when line-proven with 2+ people, a laugh,
  or a running joke forming. Server memories need 2+ people.
- **Reply prompt**: [You] shows titles/pinned + the 4 strongest traits + ones the message touches; shorter
  core, emoji, names and don't-repeat lines.
- **Admin Bot tab** rebuilt with no explanatory text: People / Cardinal / Server / Days / Traces / Cost /
  Settings; every note, trait, nickname and language is individually removable.

**Measured (LIVE, same scripts, baseline = commit 539d57d)**

Evening = seeded memory + a 127-message hangout (31-32 replies); device = the 61-message device-bug script
(36-38 replies). New numbers are the average of the 3 sweep runs.

| | before | after | change |
|---|---:|---:|---:|
| **Evening total** | 327.6 n | 297.1 n (292.6 / 297.5 / 301.3) | **−9%** |
| Evening reply prompt | ≈639 tokens | ≈561 tokens | −78 tokens (−12%) |
| Evening reply neurons per call | 6.4 | 5.7 | −10% |
| Evening learner input (8B, per pass) | ≈1,297 tokens | ≈725 tokens | −44% |
| Evening learner neurons (all passes) | 109.3 | 94.7 | −13% |
| **Device total** | 293.4 n | 280.8 n (278.8 / 293.2 / 270.5) | **−4%** |
| Device learner input (8B, per pass) | ≈1,067 tokens | ≈656 tokens | −39% |
| Per 1,000 messages (evening mix) | ≈2,579 n | ≈2,339 n | −9% |

Reply prompt sections (evening): [You] 96 → 65, [Emojis] 44 → 29, [Earlier] 28 → 17, [Names] 18 → 13,
[Don't repeat] 53 → 43, core 128 → 119 tokens. Token savings are bigger than neuron savings because
input tokens are the cheap part of a Workers AI call; reply output and the per-call base dominate.

Every suite (26: identity-filter, personality-cap, smoke, burst, pileup, gateway-codes, lifecycle, ladder,
device, people, tz, basics, recall, routes, corrections, traits, edge-learning, edge-replies, structure,
regressions, follow, days, pile, thread, silent, evening) passed 3 LIVE runs in a row on the final code.

## Round 3 — cheaper reply model, conversation following, safer learning (LIVE lab)

**What changed** (details in CLAUDE.md "v5" + "Round 3b")
- Reply model moved from the 70B to `gemma-4-26b-a4b-it` with thinking off (≈6.5 neurons per reply call
  instead of ≈19.5). The learner and director stay on the 8B; gemma handles only fact-pile merges and
  learn passes where one of his bits is changing.
- Conversation following: he answers follow-ups from someone he's talking with, even without an @. A free
  rule handles the easy cases, and one cheap 8B check handles the unclear ones. He is called by name
  ("cardinal, …"); being talked *about* only goes to the director.
- Stale context: transcript lines from an older conversation (more than 12 minutes before the current
  run) are cut, so a question asked hours later isn't answered from the old thread. Time/date questions
  get a `[Clock]` line (UTC).
- Learning hygiene: jokes, right-now states ("rn", "been busy", "hasn't been on for a bit"), what-ifs,
  relatives, reworded verbs, negated facts and joke ages are not stored as facts. Hedged self-statements
  ("apparently a nurse") are kept as plain facts. A trait needs its words in at least 2 batch messages.
  Nicknames must come from someone else and can't be slang. There are no content restrictions on traits.
- The summary keeps the conversation's main topic when a short tail pass only summarises the last joke
  (`ConversationStore.keepThread`).
- A request written in English to answer in another language ("can you answer in spanish?") now gets a
  reply in that language (`askedLang`).
- Lab additions: `> time-skip N`, `regressions.txt` (every case that failed before), plus 22 suites in
  rotation.

**Pass rates.** Every suite passed 3 LIVE runs in a row after the last change that touched it. The
"original" column is code at `cf1bda9` running the same script.

| suite | checks | original | now (3 runs) | neurons/run now |
|---|---|---|---|---|
| regressions | 16 | — | 16/16 ×3 | 78–80 |
| basics | 12 | 11/12 | 12/12 ×3 | 46–54 |
| recall | 10 | 7/9 | 10/10 ×3 | 66–75 |
| traits | 13 | 10/13 | 13/13 ×3 | 75–84 |
| routes | 8 | 5/8 | 8/8 ×3 | 84–85 |
| corrections | 12 | 8/12 | 12/12 ×3 | 31–41 |
| days | 6 | 2/6 | 6/6 ×3 | 38–58 |
| silent | 5 | 1/5 | 5/5 ×3 | 26–35 |
| pile | 8 | 8/8 | 8/8 ×3 | 10–11 |
| follow | 18 | 13/18 | 18/18 ×3 | 77–81 |
| edge-replies | 24 | 18/22 | 24/24 ×3 | 96–126 (original 325) |
| edge-learning | 14 | 9/14 | 14/14 ×3 | 19–22 |
| structure | 22 | 22/22 | 22/22 ×3 | 50–56 (original 214) |
| smoke / burst / pileup | 5 / 2 / 2 | — | all ×3 | 11–28 |
| gateway-codes / lifecycle / ladder | 3 / 6 / 4 | — | all ×3 | 4–9 |
| identity-filter / personality-cap | 6 / 1 | — | all ×3 | 0 (DRY) |
| evening (seeded memory + 127-message hangout) | 22 | — | 22/22 ×3 | 340 / 368 / 341 |

**Cost and speed, evening script** (original → round 1 → round 2 → now)

| metric | original | round 1 | round 2 | now |
|---|---|---|---|---|
| total neurons | 1,090 | 631 | 588 | **340–368** |
| per reply call | 37.5 | 19.6 | 19.4 | **6.4–6.7** |
| all calls ÷ replies | — | — | — | 10.3–10.8 |
| reply prompt (tokens) | 1,241 | 568 | 582 | ≈625–676 |
| learning per evening | 34 (≈18 msgs read) | 77 | 81 | ≈100–108 (8B ≈70 + gemma bit passes ≈33) |
| director per call | 4.8 | 5.5 | — | ≈1.8 |
| per 1,000 messages | 8,583 | 4,972 | ≈4,630 | **≈2,680–2,900** |
| reply time p50 / p95 | 1.12 s / 4.51 s | 0.86 s / 1.81 s | — | 0.81–1.23 s / 1.85–3.0 s |

The reply prompt grew slightly from round 2 because of the conversation-following cues and the
language/clock/verdict hints. Each hint is only added when it applies. Reply p95 moves with Cloudflare
latency from run to run. The three runs spread from 1.85 s to 3.0 s on identical code.

## Round 2 — day log, revisable memory, emergent traits (LIVE lab)

**New behaviour**
- **Day log** (`DayLogStore`): every learn pass (which reads every message, replied to or not) adds its
  summary line + 0-2 funny/notable "moments" to that day's log, server-wide, keyed by the phone's date.
  A finished day with ≥6 notes gets one cheap 8B recap (~5 neurons, once per day). A day question
  ("what happened yesterday", "anything funny today", "what went on 3 days ago", "last weekend", "the 21st",
  "what did I miss") adds that day's notes to the reply prompt; every other message gets nothing extra.
- **Revisable memory**: only when a learn batch contains a correction/complaint cue does the learner see
  a numbered list of what's stored (the speakers' facts + nicknames, Cardinal's traits) and return the
  numbers that are wrong. Backed by free rules because the 8B misses these often: a stored fact whose key
  word is negated within 3 words ("left toronto", "never been a chef") by the person or two others is
  dropped; "stop calling me X" retires the nickname and records it as something to avoid; two or more
  people complaining about a trait's subject tones the trait down (a new trait goes, an established one
  loses 4 strength). A correction is only honoured if the humans actually mentioned the item.
- **Emergent traits**: roles/bits the room gives Cardinal that he goes along with become traits; his known
  traits are shown to the learner so it doesn't save reworded duplicates; emoji-aware duplicate matching.
  The reply prompt adds "your quirks come out when they fit the moment, not in every message".
- **Robustness**: one retry on transient 5xx/network errors (unbilled), tolerant JSON repair for a learner
  answer missing its closing brackets, speculative "facts" (possibly/maybe/member of the server) and
  facts about playing along with Cardinal are rejected, one-off events go to the day log instead of facts.

**Results (LIVE)**

| test | result |
|---|---|
| `days.txt` (6 checks) | 6/6 — today's untouched chat recorded; yesterday recapped; 3-days-ago answered |
| `corrections.txt` (12 checks) | 12/12 — move, disputed job, nickname retired; unrelated fact survived |
| `traits.txt` (12 checks) | 12/12 — pizza-critic bit adopted; Shrek/drones/💀 used; bird trait dropped after complaints |
| `recall.txt` ×2 | 20/20 (unchanged) at 184/189 neurons (was 190/205) |
| evening (seeded-memory + hangout, 127 msgs) | 22/22, **587.6 neurons** (round 1: 631.4; original: 1,090) |

Evening breakdown: replies 26 × 19.4 = 504.6 (was 28 × 19.6); learning 8 passes = 80.7 (round 1: 77.1 —
the richer learner first cost 126.9, then a compact template that stops the 8B writing every optional key
empty cut its output from 337 to 181 tokens/pass); director 2.3. Reply prompt ≈ 582 tokens (568 before; +14
for the "quirks when they fit" line). Normal replies carry no day log or stored-memory view.

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
