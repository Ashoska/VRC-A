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

### 4.3 Telling public / private / removed apart (❓ partial)
One call `GET /avatars/{id}`:
- **200** → accessible → **public/usable** (read `releaseStatus`). Show "Use / Favorite."
- **404** → not accessible — but VRChat returns 404 for **both** "private
  (someone else's)" **and** "deleted," so the raw API **cannot reliably split
  private from removed**.
- To upgrade the label we lean on an **avatar DB** (avtrdb, vrcdb, etc.) that
  cached the avatar while it was public: "was public, now 404" → likely private;
  DB marks known-deleted → removed.
- **User-facing labels:** `Public — usable` vs `Not available (private or
  removed)`, upgraded to `Removed` only when a DB confirms it.

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
