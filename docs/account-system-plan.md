# VRC-A account system + headset-source plan (DRAFT — under discussion)

Living design note for the next major evolution of VRC-A. Companion to
`docs/vrc-nexus-teardown.md` (the technique source) and the sync/background
sections of `CLAUDE.md`.

**Progress:** §2 headset flavor **M1 scaffold is SHIPPED** (the `headsetApp`
build variant + 16:10 landscape monitor framing — see `CLAUDE.md`). §5 account
centre is **design-locked** (this doc) and next to build, on Firestore. The log
reader (§9), account-centre code, and features (§8) are not implemented yet.

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

## 5. Account centre (✅ design locked — backend-agnostic)

The account centre replaces the **single-session HARD-DENY** with a
**multi-device membership** model: one account = one VRChat identity, with
several *member devices* (a headset + phone(s)) that are all authorized, sync
content, and can command each other. This is the direct answer to "logging in
on the headset with my other device should be allowed, not blocked" — the
hard-deny was built for a one-device world we're leaving.

**Identity, for now:** keyed on `vrchatUserId` (the account) + the per-device
`deviceHash` (the member), exactly the keys we already have — **no new auth
provider is introduced in v1** (email/OAuth stays deferred, §7). The headset is
the *primary/source* device; phones/others are *added* members.

**Backend-agnostic:** the data model below maps 1:1 onto **Firestore
collections (build on this NOW)** or **Cloudflare D1 tables (migrate later,
free tier is fine at our scale — see `backend-migration-plan.md`)**. Every
operation is a point read / small upsert, so the layer is a clean infra swap,
not a redesign.

### 5.1 Data model

Two records. **Each device only ever writes its OWN device record** (never the
whole account), so concurrent multi-device writes never clobber each other — a
Firestore **subcollection** (not a map field) gives that for free and maps
cleanly to a D1 child table.

```
account            (rarely written — created once, primary changes rarely)
  id            = vrchatUserId ("usr_…")        // doc id / PK
  primaryDeviceHash                              // the headset if one exists, else earliest device
  createdAt, updatedAt

account_device     (one per member device; each device writes only its own)
  deviceHash                                     // doc id / PK
  vrchatUserId                                   // parent / FK
  role          = "headset" | "phone"            // from BuildConfig at join (admin never joins)
  appId                                          // com.gremlin.inc.headset | com.scrapw.chatbox
  addedAt, lastSeenAt                            // lastSeenAt piggybacks existing liveness → ~0 new cost
  versionName, versionCode
```

| Concern | Firestore (now) | Cloudflare D1 (later) |
|---|---|---|
| account | `accounts/{vrchatUserId}` | `accounts(vrchat_user_id PK, primary_device_hash, …)` |
| device | `accounts/{vrchatUserId}/devices/{deviceHash}` | `account_devices(device_hash PK, vrchat_user_id FK, role, …)` |
| list members | subcollection query | `SELECT … WHERE vrchat_user_id = ?` |
| join / heartbeat | dotted upsert of own device doc | `INSERT … ON CONFLICT UPDATE` |

### 5.2 Lifecycle (no deny anywhere)

1. **Join** — on VRChat login (vrchatUserId known) + valid deviceHash: upsert
   `devices/{myHash}` (`role` from `IS_HEADSET_BUILD`, `addedAt` if new,
   `lastSeenAt = now`); create the `accounts/{vid}` doc if absent; set
   `primaryDeviceHash` (a **headset always wins**; otherwise keep the earliest).
   **No claim, no deny** — additional same-account devices simply appear as
   members. (Replaces `claimAccount`.)
2. **Heartbeat** — `lastSeenAt` rides the **existing** liveness write (hourly,
   or 10 s while watched), so membership freshness costs **no extra writes**.
3. **Leave** — on a deliberate VRChat sign-out, delete `devices/{myHash}`; if it
   was the last device, delete `accounts/{vid}`. (Replaces `releaseAccountLock`
   / the admin remote-logout lock delete.)
4. **Remove a device** (later increment) — a member (or admin) deletes another
   `devices/{hash}` to kick a lost/old device off the account.

### 5.3 What comes OUT (hard-deny removal)

- `accountDenied` state, its OSC block, its `disconnectDiscordLocally()`, and
  the `AccountDeniedScreen` — all removed. `refreshOscBlockGate()` drops
  `accountDenied` from its OR (keeps `forceUpdatePending || vrchatLoggedOut ||
  vrchatAuthDead`).
- `startAccountLockWatcher()` becomes `startAccountMembershipWatcher()`: it
  still re-attaches per login and still watches the account, but instead of
  claim-or-deny it **registers/refreshes this device's membership** and exposes
  the member list (for a future "your devices" view + peer commands). It never
  blocks OSC or Discord.
- `claimAccount` → `joinAccount`, `releaseAccountLock` → `leaveAccount`.

### 5.4 Content sync — account-scoped

Already effectively account-scoped: `applyCrossDeviceSync()` queries `users
where vrchatUserId == mine`, picks the freshest `updatedAt`, and pulls
presets/messages/intervals into local DataStore (read-only against siblings,
freshest-wins). v1 **keeps this as-is** (it's the membership set by another
name) — no migration needed. *Optional later:* promote content to a single
`accounts/{vid}/content` doc so there's one source instead of per-device docs;
not required and deferred.

### 5.5 Peer commands (member ↔ member) — design, deferred to a follow-on

Generalize today's admin→device channel (`oscCommand`/`killSignal`/
`logoutVrchatAt`, read by each device's moderation listener) to **member →
member**: a phone can tell the headset to Start/Stop OSC, or wake it. Transport
is the same pattern — write a command to the **target device's** record (or
`accounts/{vid}/commands/{deviceHash}`), delivered by the target's existing
listener, plus **FCM** for wake (§6, with the FLAG_STOPPED caveat). v1 only
needs membership + roles recorded; the command channel lands next.

### 5.6 Migration from the existing lock docs

Existing docs are `accounts/{vid} = {activeDevice, activeSince}`. The new join
logic just writes `devices/{myHash}` + the new account fields; the vestigial
`activeDevice` field is harmless (nothing reads it once the deny is gone) and
can be cleaned opportunistically. **Rollout note:** an un-updated OLD client
still runs the hard-deny and could deny a second device until it updates —
**forced-update resolves this** (all releases are forced). New devices coexist
immediately.

### 5.7 Firestore rules (v1)

Add the `devices` subcollection under the existing `accounts` rules:
`accounts/{vid}/devices/{deviceHash}` — **create/update/delete** by `signedIn()`
constrained to the schema keys + a valid `deviceHash` (same bounded threat as
today's lock: it needs the high-entropy `usr_` id, which only the real owner
has). **read** by `isOwner()` or a signed-in member of that account. The parent
`accounts/{vid}` rules are unchanged.

### 5.8 First shippable increment (v1 scope)

1. Write `accounts/{vid}/devices/{myHash}` on login + upsert the account doc
   (`joinAccount`, role from build flag, headset→primary).
2. **Remove the hard-deny** (§5.3) so headset + phone coexist.
3. `lastSeenAt` piggybacks the existing liveness write (no new cost).
4. Firestore rules for the `devices` subcollection (§5.7).
5. `leaveAccount` on sign-out (+ admin remote-logout frees membership, not a
   lock).

**Deferred:** peer commands (§5.5), account-level content doc (§5.4), a
"your devices" management UI, and cross-wake FCM (§6). Auth provider (§7).

### 5.9 Cost & portability

Membership is **low-write**: one `join` per login + `lastSeenAt` folded into the
liveness write that already fires → **~0 marginal Firestore cost**. That's why
Firestore-now is fine and Cloudflare's **free tier** (100k Worker/DO req/day,
100k D1 row-writes/day) comfortably covers this at our scale — the account
centre is not where backend cost lives (presence was, and that's already
optimized / headed to log-derived). Build on Firestore now; the D1 mapping in
§5.1 makes the eventual Cloudflare move an infra swap.

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
- ❓ **Auth provider** (email/OAuth vs VRChat-login-derived) — deferred; v1 keeps
  the `vrchatUserId` + `deviceHash` keying (§5), so nothing is blocked on it.
  Revisit if/when we want account recovery independent of a VRChat login.
- ❓ Device **invite/authorization UX** — v1 auto-joins any device that logs in
  with the same VRChat account (possession of the login = authorization). A
  stricter "approve this new device from an existing one" flow is a later option.
- ❓ Phone→Quest OSC-in viability (OSCQuery advertise over LAN) vs headset-only.
- ❓ Private-vs-removed labeling: which avatar DB(s), and their reliability/ToS.
- ❓ Whether the headset build also runs Discord RPC or delegates it to the phone.
- ❓ FCM: self-hosted send (Cloud Function) vs client-triggered; ack field shape.

**Resolved this pass:** account doc schema (§5.1), headset "claim" → multi-device
**join** with no deny (§5.2–5.3), backend = Firestore now / Cloudflare-free later
(§5.9).

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
  - **Per-player platform** (PC / Quest-Android / iOS) → **API, but a cheap
    ONE-OFF per join** (not a poll). The log has name+id+avatar, NOT platform;
    it comes from `GET /users/{id}` → `last_platform` (`standalonewindows`→PC,
    `android`→Quest, `ios`→iOS), the same way NEXUS's roster does it. This is a
    fundamentally cheaper cost class than the continuous presence poll we're
    killing — it's **event-driven** (fires once on `OnPlayerJoined`, never again
    for that person) and the SAME call returns the whole row (platform + trust
    rank + bio + avatar image + friend status + age-verified), so it's "one call
    to fully populate a roster row," clearly worth it. Make it cheaper still:
    (a) **cache per userId** — `last_platform`/rank/bio don't change mid-session,
    so a re-seen player costs 0; (b) the only concentrated cost is the **join
    burst** on entering a populated instance (~40 `OnPlayerJoined` at once →
    ~40 one-off calls) — **rate-gap/queue them** (like NEXUS's "gap between
    invites") and/or enrich lazily (on-screen rows first); (c) **`android` = Quest
    OR Android phone** (VRChat can't distinguish them) — label it "Android", not
    "Quest". **The aggregate breakdown is also free**: the instance object's
    `platforms` {standalonewindows, android, ios} counts ride the single
    `/instances/{id}` call we already make (`extractInstanceUserCount`), so
    "18 PC / 12 Quest / 2 iOS" for the whole instance costs nothing — only the
    per-person label needs the one-off call. Bottom line: one-off on-join
    enrichment is fine to keep; the reduction target was the *continuous* polling.
  - **Works for NON-FRIENDS too (the whole point).** The log supplies every
    instance occupant's userId — friends *and* strangers — and `GET /users/{id}`
    is a **public** endpoint, so platform + rank + bio + avatar resolve for anyone
    in the room, not just friends. This is strictly more than the friend-presence
    WebSocket (friends-only). Scope: limited to people currently sharing your
    instance (that's where the IDs come from); their *location* stays privacy-gated
    for non-friends, but platform/rank/bio/avatar are public.
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

1. **Quest build first** — `headsetApp` flavor / `IS_HEADSET_BUILD`:
   - ✅ **M1 SHIPPED:** the flavor (id `com.gremlin.inc.headset`), its Firebase
     app, and **16:10 landscape "monitor" framing** (Quest's 1024×640 dp panel;
     width-responsive so phones are untouched). Builds + installs + runs.
   - 🟡 **M1.5 (polish):** monitor-frame the boot/onboarding screens too; tune
     content width from real in-headset feedback.
   - 🟡 **M2:** the **log reader** + its **Quest permissions** — All-files/SAF on
     the VRChat log path (confirm the real Quest path first), notification
     listener, and the **"Allow restricted settings"** sideload gateway; a
     Quest-specific onboarding path (its permission UI differs). OSC-in lives here.
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
