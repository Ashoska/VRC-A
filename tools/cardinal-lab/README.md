# Cardinal Lab

Talk to the **real** Cardinal bot code — hundreds of messages at a time — without a phone,
Discord, or screenshots, and see exactly what it sends to the model, what it gets back, what it
remembers, and what it costs.

The lab runs the production `DiscordBotService` (routing, reply/director/learn prompts, every memory
store, the budget ladder) on the JVM under Robolectric, connected to:

- **FakeDiscord** — the v10 gateway (HELLO/IDENTIFY/READY/GUILD_CREATE, heartbeats, RESUME,
  MESSAGE_CREATE, reactions) and the four REST calls the bot makes, with real snowflake ids.
- **AiProxy** — stands in for `api.cloudflare.com/.../ai/run/<model>` and records every call in
  full. **DRY** (default) answers with synthetic text for free; **LIVE** forwards to real Workers AI.

The only production change is `BotEndpoints` (the four base URLs as vars instead of constants).
Nothing here ships in an APK: the harness lives in `app/src/test` and only runs with `-PcardinalLab`.

## Run it

```bash
# A scripted conversation → lab-runs/<run>/report.md
tools/cardinal-lab/lab.sh run tools/cardinal-lab/scripts/hangout.txt

# Several scripts in one bot process (memory carries over between them)
tools/cardinal-lab/lab.sh run tools/cardinal-lab/scripts/seeded-memory.txt tools/cardinal-lab/scripts/burst.txt

# Interactive: the bot stays up; drive it from another shell
tools/cardinal-lab/lab.sh interactive &
tools/cardinal-lab/labctl.py wait-ready
tools/cardinal-lab/labctl.py say alice "hey @Cardinal what do you think of pineapple pizza"
tools/cardinal-lab/labctl.py prompt           # the exact prompt behind the last reply
tools/cardinal-lab/labctl.py state            # everything every store holds / would inject
tools/cardinal-lab/labctl.py quit             # writes the report
```

First run downloads Robolectric's Android 14 jar (~200 MB, once). Each run takes ~40 s of Gradle
start-up plus the conversation itself.

## LIVE mode (real model calls)

Add these in the cloud environment's settings (never paste them in chat or commit them):

| variable | what |
|---|---|
| `CF_ACCOUNT_ID` | Cloudflare account id |
| `CF_API_TOKEN` | API token with **Workers AI: Read/Edit** (a dedicated lab token is best) |
| `CF_GATEWAY_ID` | optional — route lab calls through an AI Gateway; they're tagged `cf-aig-metadata: {"lab":"cardinal",...}` so they can be filtered out of production views |
| `CF_AIG_TOKEN` | optional — only if that gateway is *authenticated* |
| `CF_AIG_READ_TOKEN` | optional — token with **AI Gateway: Read** for `aig_logs.py` |

```bash
tools/cardinal-lab/lab.sh run tools/cardinal-lab/scripts/hangout.txt --live --cap 1500
```

**Budget warning:** the lab and the production bot share the account's 10,000 free neurons/day, and
if the bot's Analytics token is set its budget ladder sees lab spend too (it could push the live bot
into TRIM/CHEAP/SILENT for the rest of the UTC day). `--cap` is a hard per-run ceiling (default
1500). Best of all: use a separate free Cloudflare account for the lab — its own 10k/day.

## Options

| option | effect |
|---|---|
| `--live` | real Workers AI (needs the env vars above) |
| `--cap N` | hard LIVE neuron ceiling for the run (default 1500) |
| `--remap A=B,...` | swap a model at the proxy: `@cf/meta/llama-3.3-70b-instruct-fp8-fast=@cf/google/gemma-4-26b-a4b-it`, or by role: `observe=@cf/meta/llama-3.1-8b-instruct-fp8`, `director=...`, `reply=...` |
| `--state FILE` | start from a saved memory (`lab-runs/<run>/state-final.json`) |
| `--set ambient=N` / `cooldown=` / `context=` / `model=` / `shadow=1` / `spent=N` | bot config (same knobs as the admin tab) |
| `--set gap=MS` / `quiet=MS` / `idle=MIN` | pacing between non-waited lines / settle quiet window / interactive idle exit |
| `--set dry.director=reply\|react\|ignore\|mix` / `dry.latency=MS` | DRY-mode behaviour |
| `--set affinity=1` | LIVE: send `x-session-affinity` (prefix-cache experiment) |

## Script format (`scripts/*.txt`)

```
# comment
@set ambient=0 context=8          bot config (restarts the service like the admin tab would)
@gap 1200                         ms between lines that don't wait for the bot
@channel memes shitposts only     declare a channel / set its topic
[general]                         switch channel
alice: hey @Cardinal what's up    a message. @Cardinal = real mention, @bob and #media resolve too
alice ^bot: lol no                reply to Cardinal's last message here (^bob = bob's last)
alice +img: look at this          attach an image          (!wait / !nowait force waiting)
> wait                            let the bot finish (incl. background learning)
> sleep 3000 · > react carol bot 😂 · > checkpoint name · > note text
> drop-gateway [code] · > gateway-reject [code] · > gateway-accept · > gateway-stats
> gateway-heartbeat MS              HELLO heartbeat for NEW sessions (default 41250; shorten for fast gateway tests)
> log                               print the bot's activity-log lines + gateway events since the last print
> teach self|style|mood|pinned <text>        seed the personality (through the real store APIs)
> teach card|nick|prefer|rel|lang <name> <text>   seed a user card
> teach server <text> · > teach bit <channel> <text> · > teach summary <text>
> expect reply | no-reply | react | contains <regex> | not-contains <regex>
> expect card <name> contains|not-contains <regex> · server|self|summary contains <regex>
> expect status CONNECTED|RECONNECTING|FAILED|...
```

Addressed lines (mention or `^bot`) wait until the bot has decided and gone quiet; others pace by `@gap`.

## What a run leaves behind (`lab-runs/<run>/`, git-ignored)

- `report.md` — cost/latency per role and model, reply-prompt anatomy (tokens per section),
  heuristic quality flags (bot-meta, format leaks, repeats, language mismatch, unrendered emoji),
  expectation results, a turn-by-turn log, and the final memory state as the bot would inject it.
- `prompts.md` — every model call verbatim (system prompt, transcript, response).
- `calls.jsonl`, `events.jsonl` — raw machine-readable record.
- `state-final.json` — exact dump of every store; feed it back with `--state`.

## Scripts included

| script | what it exercises |
|---|---|
| `smoke.txt` | boot, mention reply, trivial-ping react, reply-to-bot, react request |
| `hangout.txt` | ~140 lines, 6 people, 3 channels, ambient on, Japanese, image, cross-channel, stop, recall |
| `seeded-memory.txt` | a lived-in memory (personality, cards, culture, channel bit, summary) → full prompts |
| `pileup.txt` | the 8B observer firing on unreplied chatter |
| `burst.txt` | several people addressing the bot within a second (mutex / covered / threading) |
| `ladder.txt` | the budget ladder: TRIM → CHEAP (8B) → REACT_ONLY → SILENT |
| `personality-cap.txt` | repro: the 25th trait never enters (personality freezes at 24) |
| `identity-filter.txt` | repro: short names/nicknames/relationship words silently delete unrelated card facts |
| `lifecycle.txt` | service restart + server-side drop: stale-socket callbacks vs RESUME |
| `gateway-codes.txt` | server-initiated close (deaf until a missed heartbeat) + a 4014 intents rejection loop |

## Production observability

- `aig_logs.py list|show|digest` — reads the **production** bot's calls back from AI Gateway logs
  (needs the bot's Config tab to have an AI Gateway id). A digest is a readable transcript of what
  Cardinal actually sent and received — no screenshots.
- `probe_models.py ping` — is each model alive, how fast, what does a call cost (~1 neuron each).
- `probe_models.py replay lab-runs/<run>/calls.jsonl --models A,B,C` — re-send the exact captured
  prompts to candidate models side by side (tokens, latency, neurons, text) to pick a cheaper model
  without guessing about naturalness.
