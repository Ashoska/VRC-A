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
