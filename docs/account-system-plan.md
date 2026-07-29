# VRC-A account system + headset-source plan (DRAFT — under discussion)

Living design note for the next major evolution of VRC-A. **Not implemented
yet** — this records decisions and constraints as we discuss them so the eventual
build has a clear spec. Companion to `docs/vrc-nexus-teardown.md` (the technique
source) and the sync/background sections of `CLAUDE.md`.

Status legend: ✅ decided · 🟡 leaning · ❓ open · ⛔ ruled out / not possible.

---

## 1. Goal

Move from "per-device docs loosely tied by `vrchatUserId`" to a real **account**
model where a user's devices are members of one account, sync content, can
control each other, and — crucially — a **headset device acts as the
authoritative *source*** of the data only it can see (instance roster, precise
in-world location), feeding the phone's Discord RPC and any admin view with
accurate, instant info.

---

## 2. Device roles & build variants

**✅ The headset gets its own APK flavor**, like admin — a third product flavor
`headsetApp` with `BuildConfig.IS_HEADSET_BUILD`, alongside the existing
`publicApp` / `adminApp`. Rationale: differentiate device type at the account
level, and stop each device carrying subsystems it doesn't need.

| Subsystem | Headset build | Phone (public) build | Admin build |
|---|---|---|---|
| Chatbox OSC send | ✅ (local `127.0.0.1:9000`) | ✅ (LAN → headset IP) | — |
| **Log reader** (instance roster, location) | ✅ **only here** | ⛔ (can't read another device's logs) | — |
| **OSC-in :9001 + OSCQuery** (avatar params) | ✅ local | 🟡 experimental (needs OSCQuery advertise over LAN) | — |
| Roster/location **writer** to account | ✅ (sole writer) | ❌ reader only | reader |
| Discord RPC | 🟡 (usually the phone) | ✅ | — |
| NowPlaying / lyrics | ✅ | ✅ | — |
| Moderation / directed releases | — | subject | ✅ author |

**Source-of-truth rule (🟡):** the headset is the **only writer** of
instance-roster + live-location fields (it's the only device that can observe
them). Phones are readers + controllers. This avoids multi-writer conflicts and
matches "headset is the authoritative source."

---

## 3. Data flow: headset as source → account → other devices

```
[Headset build]
  VRChat output log  ── log reader ──►  instance roster {name,usr_id,avatarName,author,avtr_id}
  VRChat OSC out :9001 + OSCQuery ───►  live avatar params (mute/AFK/movement)
  self location (log Joining wrld_…) ─►  world+instance+accessType+nonce
        │  (writes, sole writer)
        ▼
   account doc / account/{uid}/live
        │  (reads)
        ├──► Phone build → Discord RPC (accurate instant location + player count)
        ├──► Phone build → in-app "who's in my instance" view
        └──► Admin build → directory/detail
```

The headset porting **precise location (incl. instance access type + player
count) + roster** is exactly what makes the RPC accurate without the phone ever
hitting a VRChat API rate limit — the log gives same-instance data the API can't.

This slots onto the **existing** Firestore sync + single-session-lock
infrastructure; the account layer generalizes today's per-device docs.

---

## 4. Avatar data — what we can see, and what we can do with it

### 4.1 Where avatar IDs come from (the key asymmetry)
| Source | Reach | Gives avatar **name** | Gives **`avtr_` ID** | Gives author |
|---|---|---|---|---|
| **Logs** (headset, same instance only) | only people in your instance | ✅ (`Switching … to avatar …`) | ✅ (client downloads the avatar to render it → `avtr_` in log; correlate `usr_`↔`avtr_`) | ✅ (`Unpacking Avatar (… by …)`) |
| **VRChat API / friend-update WS** (phone, anywhere) | any friend, even not with you | ❌ (image only) | ⛔ **no** — VRChat scrubbed avatar IDs from the user object *specifically to stop cloning* | ❌ |

So: **API = remote, image only, no ID. Logs = same-instance only, but full ID +
author.** The **only** way to get another person's actual `avtr_` ID is the
headset log reader while sharing their instance — which is why the headset-source
model is the enabler for any avatar feature.

### 4.2 What we can DO with an ID (scope: ✅ public only, ⛔ no ripping)
Having an ID ≠ being able to wear it.
- **Public avatar** (`releaseStatus: public`): legitimately equippable —
  `PUT /avatars/{id}/select`, `avatars/favorites`. ✅ We build this: scan →
  look up → favorite/equip the public ones.
- **Private avatar** (most custom/"ripped" ones): API blocks it; actually wearing
  it needs a **modified client that decrypts the asset bundle** = ToS violation.
  ⛔ **We do not build ripping.** We only *identify and label* it for the user.

### 4.3 Public vs private/removed (✅ decided: all 404 → "Private")
VRChat returns 404 for **both** "private (someone else's)" **and** "deleted," and
the raw API can't split them. **Decision: don't try — treat every 404 as
`Private` (a single, not-usable label).** Simpler, and the private/removed
distinction doesn't matter to the user (both are "you can't wear this").

One call `GET /avatars/{id}`, classified by status:
- **200** → **Public / usable** (read `releaseStatus`). Show "Use / Favorite."
- **real 404** → **Private** (not usable; hide equip).
- **429 / 5xx / timeout / network** → **Unknown — retry later. Do NOT cache as
  Private.** VRChat rate-limits hard (we'd hit it once per scanned ID), and a
  throttle blip must never permanently mislabel a *public* avatar as private.

Guardrails:
- **The user's own private avatars return 200 for them** (owner has access), so
  the "404 = Private" rule never wrongly blocks avatars they own — only avatars
  they genuinely can't access hit the 404 path.
- Copy note: a *deleted* avatar also reads "Private" here (slightly inaccurate but
  harmless — both are not-usable). If we ever want it honest without a DB lookup,
  a neutral `Private / unavailable` label covers both; plain `Private` is the
  current pick.
- Optional later: an **avatar DB** (avtrdb, vrcdb, …) could upgrade a 404 to a
  precise `Removed`, but this is explicitly **not** required for v1.

---

## 5. Account / auth model

- 🟡 Real account (auth) on top of today's Firestore + anon-device model; headset
  is the **primary/required** device, phones/others are **added** to that account.
- Reuse the existing **single-session lock** concept, generalized: instead of
  hard-deny across devices, account members are *known peers* that sync + can
  command each other (the existing oscCommand / kill / toggle channel, extended
  peer-to-peer).
- Content sync: today's `vrchatUserId`-keyed cross-device content pull becomes
  account-scoped.
- ❓ Auth provider (Firebase Auth email/OAuth? VRChat-login-derived?) — open.

---

## 6. Cross-device wake

**Goal:** headset boots → phone's VRC-A revives, as an extra defense against
background kills.

**✅ Mechanism: FCM high-priority *data* message.** No LAN "launch app" primitive
exists; the standard path is headset → backend/Firestore flag → **FCM
high-priority push** → phone's `FirebaseMessagingService` wakes (even from
Doze/background) and can start work. High-priority FCM grants a temporary
allowlist window that **permits a background foreground-service start** on
Android 12+.

**⛔ Hard limit — you cannot detect FLAG_STOPPED before sending, and a
force-stopped/swiped app won't receive FCM at all.** Android sets `FLAG_STOPPED`
after a user swipe/force-stop **and** after an aggressive OEM force-stop; FCM is
**not delivered** to a stopped app. There is **no API for device A to query
device B's process state.** So the "only wake if not flag-stopped" filter must be
**inferred**, not checked:

**🟡 Wake protocol (inferred, ack-based):**
1. Each device writes a **heartbeat** (reuse `lastActiveAt`/presence). Fresh →
   alive, no wake needed.
2. Stale heartbeat → send **one** high-priority FCM wake, then **wait for an ack**
   the woken app writes back.
3. **Ack within N s** → it was Doze/soft-killed and recovered ✅.
   **No ack** → it was force-stopped/swiped (FCM couldn't land) → mark
   `unreachable — open manually`, stop retrying (no spam).
4. **Respect deliberate stops:** if the target's last shutdown was a **swipe**
   (existing `swiped_away` flag), don't wake it at all.
5. Self-heals: the moment the user manually opens a stopped app, `FLAG_STOPPED`
   clears and heartbeats resume.

**Honest framing:** cross-wake reliably beats **Doze / memory / soft** kills, is
**off for swipes**, and **cannot** beat a true **OEM force-stop** (same wall as
every app — the OEM allow-list / `OemPowerGuidance` stays the primary defense).
The ack means we never falsely believe we woke a device we didn't.

---

## 7. Open questions
- ❓ Auth provider + how a headset "claims" the account and invites other devices.
- ❓ Exact account doc schema (roster/location live subdoc vs main doc; cost).
- ❓ Phone→Quest OSC-in viability (OSCQuery advertise over LAN) vs headset-only.
- ❓ Private-vs-removed labeling: which avatar DB(s), and their reliability/ToS.
- ❓ Whether the headset build also runs Discord RPC or delegates it to the phone.
- ❓ FCM: self-hosted send (Cloud Function) vs client-triggered; ack field shape.

---

## 8. What we can start on independently (no account system needed)
These are additive and don't block on the account work:
1. **Synced lyrics** (LRCLIB) — Tier-1 win, phone or headset.
2. **Extra chatbox tokens** — `{weather}`/`{date}`/`{uptime}`/`{battery}`.
3. **OSC-in + OSCQuery prototype** on the (future) headset build → `{mute}`/
   `{afk}`/`{movement}`/`{param:Name}` lines + a real "VRChat OSC live" signal.
The log reader + roster + avatar features naturally land **with** the headset
flavor, since that's where they run.

---

## 9. Log-as-event-stream → API offload map (✅ the point of the headset)

The VRChat output log is an **event stream**. On the headset (same device as
VRChat) it replaces the heaviest, most-throttled REST calls in the app entirely.

| Log event line | Real-time data | API load it removes |
|---|---|---|
| `Joining wrld_…:<instance>~<tags>~nonce(…)` | world + instance + access type + nonce | location half of `/auth/user` + `/users/{id}` |
| `Joining or Creating Room: <Name>` / `Entering Room:` | world **name** | `/worlds/{id}` |
| `OnPlayerJoined <n> (usr_id)` / `OnPlayerLeft …` | count players yourself; full roster (incl. non-friends) | **`/instances/{location}` player-count polling** |
| `Switching <n> to avatar <a>` / `Unpacking Avatar (<a> by <author>)` + `avtr_` | who's on what avatar + `avtr_` id | (no API equivalent) |
| `Received Notification: …` | invites / friend-requests / joins **while VRChat runs** | *part* of notification backfill |
| `[Video Playback] … URL '…'` / `[String Download]` | what's playing in-world | (no API equivalent) |
| `OnLeftRoom` / `Successfully left room` | you left the instance | world-hop detection without polling |

**Headline:** the **entire self-presence chain — location, world name, instance
type, player count — becomes 100% log-derived on the headset, zero API calls.**
That kills the exact calls the codebase fights hardest (`fetchPresence` 3-call
chain + `fetchInstanceCount`, the cookie-IP-invalidation 429s, the
`userCount`/`n_users` count-lag saga). The log count is *more* accurate than the
API — it's the same joins/leaves the in-game panel counts.

**Still NOT offloaded (kept honest):**
- **Friend presence when they're not in your instance** — already cheap: VRC-A
  gets it via the pipeline **WebSocket (push, not REST)**.
- **Offline friends' bio/name/rank edits** — still the REST profile-refresh loop.
- **Group announcements / calendar events** — app-level, not in the client log;
  still REST.

Architecture payoff: headset writes log-derived presence to the account; the
**phone's RPC + admin read it from the account and make ZERO VRChat API calls**
for presence.

### 9.1 How location + count are derived (and what's still NOT from the log)
- **Location / world / instance type / nonce** — direct from `Joining wrld_…`
  (+ `Joining or Creating Room: <Name>` for the human name). Instant.
- **Player count** — *tally* `OnPlayerJoined` − `OnPlayerLeft` since the last
  `Joining wrld_…` reset. On join, VRChat logs an `OnPlayerJoined` for everyone
  already present, so the tally is complete and matches the in-game panel exactly
  (more accurate than the API's `userCount`).
- **"Instant" = ~1–2 s**, not literally 0 (VRChat flush ~1 s + our tail-read /
  `FileObserver`). Still far better than a 10 s REST poll that can 429.
- **NOT in the log → stays on WS/API (already free or cheap):**
  - Your VRChat **status** (join-me/ask-me/busy) + status text → WebSocket
    `user-update` (push, free).
  - Instance **capacity** (the "/N") → one **cached** `/worlds/{id}` per unique
    world (cache forever), or omit the cap and show only the count.
  - **Friend** presence (friends not in your instance) → WebSocket, free.
  - **Per-player platform** (PC / Quest-Android / iOS) → **API only, 1 call per
    player**. The log has name+id+avatar, NOT platform; it comes from
    `GET /users/{id}` → `last_platform` (`standalonewindows`→PC, `android`→Quest,
    `ios`→iOS) — this is exactly how NEXUS's roster does it (per-user enrichment
    call). Two caveats: (a) it scales with roster size (40-person instance = up to
    40 calls, rate-gap them); (b) **`android` = Quest OR Android phone** — VRChat
    can't distinguish them, so label it "Android", not "Quest". **The aggregate
    breakdown is free**, though: the instance object's `platforms`
    {standalonewindows, android, ios} counts come in the single `/instances/{id}`
    call we already make (`extractInstanceUserCount`), so "18 PC / 12 Quest / 2 iOS"
    for the whole instance costs nothing — only the *per-person* label needs a call.
- **Reader robustness:** VRChat rotates to a **new log file per launch**; the
  reader must tail back far enough to anchor on the current `Joining wrld_…` line
  + all joins/leaves since, or the count drifts when reading starts mid-session.

---

## 10. Discord RPC — what triggers it, rate limits, and the 10s (✅ findings)

Clears up "we aren't sure what triggers Discord":
- **The trigger IS you sending an OP 3 (presence update) with a changed activity.**
  No server-side trigger beyond that; Discord broadcasts your latest OP 3 to
  friends. The current 10s push is a **self-imposed keepalive**, not a Discord
  requirement.
- **Rate limit = 5 presence updates / 20 s** per session (~1 every 4 s). Faster
  risks throttle/disconnect. So ~4 s is the safe floor; 10 s is very conservative.
- **The elapsed timer is client-rendered from `timestamps.start`.** Set `start`
  once → Discord ticks it every second on its own. Frequent pushes are NOT needed
  to keep the timer smooth (which is why 10 s never hurt the timer).

**Conclusion:** the 10 s only limited *how fast a location/state change shows*.
Fix is **event-driven pushes, not a faster poll**: push the instant the **log**
reports a world-hop (or the WS self-location event fires), then let the ~10 s
keepalive cover the rest → location updates land in ~1 s while staying far under
the limit. VRC-A already does a WS-driven version (`applySelfLocationEvent` → RPC
`collectLatest`); the **log is a more reliable trigger** (no REST 429 dependency).
Keep the ~10 s keepalive, add on-change pushes, never go below 4 s.

---

## 11. Build sequence (✅ confirmed order)

Everything above is wanted, but in this order:

1. **Quest build first** — `headsetApp` flavor / `IS_HEADSET_BUILD`, with:
   - **Scaled UI for the Quest 2D panel** (fixed-size floating window; density +
     touch-target tuning for laser-pointer input; test at panel size).
   - **Correct Quest permissions** — All-files/SAF grant on `Documents/Logs`,
     notification listener, and the **"Allow restricted settings"** sideload
     gateway; a Quest-specific onboarding path (its permission UI differs).
   - The log reader + OSC-in live here.
2. **Account system** (§2–§5) — headset-as-source, multi-device, sync/control.
3. **Feature buffet** on top, roughly: log-derived RPC + roster (§9) → synced
   lyrics + tokens + heart-rate (§8 / roadmap) → OSC-in avatar lines + avatar DB →
   OSC macros → cross-wake (§6).

---

## 12. VRChat API budget & savings (modeled from code cadences)

Numbers are **modeled from the actual loop intervals in the source** (not
measured), for one logged-in user with the pipeline foreground service alive
24/7. Representative profile: ~100 friends, ~10 groups, ~8 h/day in a world +
~16 h/day app-alive-but-idle. All figures scale with friends / groups / in-game
time.

### Passive (continuous timer loops)
| Loop | Cadence | Calls/fire | Calls/hr |
|---|---|---|---|
| **Self-presence** `fetchPresence` = `/auth/user`→`/users/{id}`→`/instances/{loc}` | 10 s | 3 in-world · 2 idle | **1,080** / **720** |
| Friends profile refresh `fetchFriends` ×2 passes | 30 s fg / 60 s bg | ~2 (+1 per 100 friends/pass) | 120 (bg) / 240 (fg) |
| Group poll (`groups` + posts + events per group) | 5 min | 2 + 2×groups | 264 (10 groups) |
| Hourly instance-count | 1 h | 1 | 1 |
| Auth revalidate | 6 h | 1 | ~0.2 |
| *(VRChat status page — `status.vrchat.com`, NOT the API)* | 2 min | 1 | *(30, excluded)* |

**Passive ≈ 1,465/hr in-world · ≈ 1,104/hr idle.**

### Triggered (user/event)
- **Backfill on every pipeline connect:** `3 + 2×groups` (~23 for 10 groups, up to
  ~103 at the 50-group cap) — per connect.
- World-hop instance count, `verifyStillFriend`, invites, avatar select,
  login/2FA — rare. **Triggered ≈ 150–300/day.**

### Received (VRChat → us, **0 REST cost**)
One pipeline **WebSocket** carries everything VRChat pushes:
`friend-online/offline/active/location`, **`friend-update` (bio/name/rank/avatar)**,
`friend-add/delete`, `notification`, `notification-v2`, `group-*`, self
`user-location`/`user-update`. Thousands of events/day for a social user — all
free.

### Daily total per user ≈ 29,500 calls
| Bucket | Calls/day | Share |
|---|---|---|
| **Self-presence + instance count** | **~20,200** | **~68%** |
| Group poll | ~6,300 | ~21% |
| Friends refresh | ~2,900 | ~10% |
| Triggered | ~150 | <1% |
| Received (WebSocket) | thousands | **$0** |

Fleet scale: 1,000 concurrent in-world users ⇒ self-presence alone ≈ **1.08M
calls/hr ≈ 300/sec**, 24/7.

### Savings
- **Log (headset) removes the self-presence chain + instance count entirely** →
  **~20,200/day/user, ~68% cut.** Remaining ~9,300/day (group + friends + triggered)
  stays REST (offline friends + app-level group content the log can't see).
- With the account model a **phone reads presence from the account (Firestore) →
  0 VRChat presence calls**; an account with a headset drops the *whole account's*
  presence load to ~0 VRChat calls.
- **Available even without the headset:** the 10 s self-presence poll is largely
  redundant with the WebSocket (which already pushes self `user-location`/
  `user-update`, patched in `applySelfLocationEvent`). Drive location/state from
  the WS (free) and fetch only the instance **count** sparingly (~30–60 s / on-hop)
  → self-presence drops ~1,080/hr → ~60–120/hr, a **~90% cut of the dominant cost
  with no headset required.** The log then makes the count free too.
- **Stacked ceiling:** WS/log presence + slower/conditional group poll + WS-first
  friend-edit detection ⇒ toward **~80–85% total reduction**, received pushes free
  throughout.

*(Modeled estimate — validate against real telemetry before quoting externally.)*
