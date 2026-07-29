# VRC-A backend migration plan — off Firestore (DRAFT — under discussion)

Companion to `docs/account-system-plan.md`. Records the decision to move off
Firestore for cost reasons, the target architecture, the phased path, and the
legacy-user migration — locked in **before** the account-system build starts,
because it shapes the schema.

Status legend: ✅ decided · 🟡 leaning · ❓ open · ⛔ ruled out.

---

## 1. Why move

Firestore **bills per operation**, and VRC-A's dominant cost is **high-frequency
presence writes/reads** — the wrong shape for per-op pricing. At the target scale
(**100k+ users**) per-op billing is untenable. The fix is two-fold and stacks:
1. **Fewer ops** — the headset-source + WS-driven presence work already cuts
   writes ~68–90% (see account plan §9–§12).
2. **A backend that doesn't bill per op** — flat-cost (self-host) or per-resource
   (Supabase/Cloudflare), plus routing ephemeral traffic off the database entirely.

**Hard truth up front:** **$5–25/mo and a true 100k+ users don't coexist on a
managed service.** Managed at 100k done naively = low four figures/month; done
with the architecture below = ~$25–150; truly flat $5–25 at 100k = **self-hosted
only**. The plan below starts managed for speed and migrates to self-host at the
cost-crossover, using ONE portable architecture so it's not a rewrite.

---

## 2. Core architectural principles (✅ — apply on ANY backend)

These aren't optimizations; at 100k they're required. They're also **portable** —
identical on Supabase or a self-hosted VPS, so migrating later is infra-only.

1. **No persistent realtime connection per user.** Use **FCM push** for the rare
   must-deliver commands (kill / moderation / directed release / cross-device
   wake). Open a realtime channel **only for active admin-watching** (already the
   watcher-gated model). → concurrent realtime drops from ~tens-of-thousands to a
   few hundred.
2. **Never broadcast presence as realtime messages.** Presence is **log-derived on
   the headset**, written to the DB **sparsely**, and read **on-demand**. This
   kills the per-message cliff (naive presence broadcast at 100k = billions of
   messages/mo).
3. **Keep our own identity** (device hash / account id), **do NOT use managed
   Auth** → **$0 MAU billing**, even at 500k users. (VRC-A already uses anon
   device identity.)
4. **Durable vs ephemeral split** — durable state (accounts, content, moderation
   flags, releases, config) lives in the DB and is rarely written; ephemeral
   state (live location, watcher, commands) rides pub/sub + FCM and mostly never
   touches the DB.

---

## 3. Supabase tiers (fetched 2026-07-29 — verify before committing)

| | **Free $0** | **Pro $25** | **Team $599** |
|---|---|---|---|
| Database | 500 MB | 8 GB, +$0.125/GB | 8 GB |
| Auth MAU | 50k | 100k, +$0.00325/MAU | 100k |
| Egress | 5 GB | 250 GB, +$0.09/GB | 250 GB |
| File storage | 1 GB | 100 GB, +$0.0213/GB | 100 GB |
| **Realtime concurrent conns** | **200** | **500, +$10/1,000** | 500 |
| **Realtime messages/mo** | **2 M** | **5 M, +$2.50/M** | 5 M |
| Compute | Micro | Micro (covered by $10 credit) | — |
| Notes | pauses after 1 wk idle | no pause, daily backups | SOC2/support |

Compute add-ons (DB instance): Micro $10 → 16XL $3,730/mo (those "connections" are
**Postgres** connections; realtime WebSocket clients are the separate
"500 + $10/1,000" line).

**The three cliffs at 100k if done naively:** realtime **connections**
(10–20k concurrent ≈ $95–195/mo + bigger compute), realtime **messages**
(presence broadcast = billions × $2.50/M = thousands), **MAU** (200k active ≈
$325/mo if using Supabase Auth). §2 removes all three.

---

## 4. Recommended phased path (🟡)

The architecture is portable, so we don't choose infra today — we choose it by cost.

1. **Build the scale-ready architecture now** (§2): FCM commands, sparse
   log-derived presence, on-demand realtime, own identity. Portable between
   Supabase and self-host.
2. **Start on Supabase (Free → Pro).** Launch fast, zero server ops, cheap while
   small (first several thousand users fit $0–25). Skip Supabase Auth.
3. **Migrate to self-hosted flat-cost** when the Supabase bill crosses the comfort
   line (~$50–100/mo, likely 10k–50k users). Same code pattern, cheaper infra.
4. **At 100k+ you're self-hosted, flat-cost**, architecture already scale-ready.

**Alternative (commit to self-host from day one):** cheapest long-run, but you
operate it from the start. Viable phone-only (rent + SSH from Termux), just more
ops earlier. Pick this only if the $25 managed step isn't worth the saved ops.

---

## 5. Self-host stack (the flat-cost target)

Phone-only is fine — you **rent** a cloud box and manage it from **Termux over
SSH**; you do NOT host on the phone (CGNAT + uptime + connection limits make the
phone unusable as a server).

- **Compute:** a VPS (Hetzner ~$5–20, or Oracle Cloud "Always Free" ARM to start).
  100k *concurrent* WebSockets is real engineering — a single $5 box won't do it;
  budget a **$40–100/mo small cluster** at true 100k (still far under managed).
- **Durable store:** **Postgres** (accounts, users, content, moderation, releases,
  config, bans).
- **Ephemeral / pub-sub:** **Redis** (presence, watcher state, command fan-out).
- **Realtime layer:** a **Go or Elixir WebSocket** server (efficient at many
  concurrent connections) subscribing to Redis pub/sub — used ONLY for active
  watching, not per-user.
- **Push:** **FCM** for commands + wake (free).
- **Files/APKs:** stay on **GitHub Releases** (already used) — not the DB.

⛔ Not Termux-hosted. ⛔ Not SQLite-single-node for the realtime path at 100k
(fine for a PocketBase *start*, but Postgres+Redis for the scaled target).

---

## 6. Schema mapping (Firestore → new backend)

### Durable (Postgres tables, rarely written)
| Firestore today | New table | Notes |
|---|---|---|
| `users/{deviceHash}` | `devices` (+ `accounts`) | split: device row + account row (see account plan) |
| `accounts/{vrchatUserId}` (session lock) | `accounts` / `account_locks` | single-session lock generalized |
| `announcements` | `announcements` | admin content |
| `releases/latest`, `releases/{deviceHash}` | `releases` (global + targeted) | pointer to GitHub asset |
| `config/app` | `config` | ToS, discordInvite, owner ids |
| `bannedDevices`, `bannedIdentifiers`, `bannedRecords` | `bans`, `ban_identifiers`, `ban_records` | tri-directional evasion index |
| `moderationEvents` | `moderation_events` | audit log |

### Ephemeral (Redis + FCM, mostly NOT in the DB)
| Concern | Transport | DB touch |
|---|---|---|
| Live location / instance / player count | headset → sparse DB write (hourly-ish) + Redis channel for active watching | rare |
| Watcher state (admin viewing user X) | Redis pub/sub | none |
| Commands: kill / logout / osc start-stop / directed release / wake | **FCM data message** to target device(s) + a durable `pending_command` row (so an offline device catches up on reconnect) | 1 row per command |
| Liveness (`lastActiveAt`) | sparse column update (hourly) or presence-channel heartbeat | rare |

### Command channel (replaces the moderation snapshot listener)
- Admin action → write `pending_command` row + send **FCM high-priority data
  message** to the account's device(s).
- Device receives FCM (even backgrounded) → applies (kill/logout/toggle) → acks by
  clearing/observing the row. An offline device reads pending commands on next
  connect (durable backstop). Mirrors today's `killSignal`/`logoutVrchatAt`
  semantics but push-based instead of a standing listener.
- **FLAG_STOPPED caveat** (account plan §6): FCM won't wake a force-stopped/swiped
  app; the durable `pending_command` row + reconnect catch-up covers that case.

---

## 7. Legacy migration (old app versions still on Firestore)

- **Dual-write bridge:** a small server/worker mirrors the handful of essential
  fields **both ways** between Firestore and the new backend during the
  transition, so old (Firestore) and new (new-backend) clients still see each
  other (moderation, releases, presence-in-directory).
- **Forced-update cutover:** VRC-A's forced-update system pulls users onto the new
  version quickly; as adoption climbs, shrink the bridge, then **sunset
  Firestore**.
- **Legacy stragglers:** versions predating the force-update system can't be
  compelled (documented limit) — they stay on the Firestore bridge or are
  eventually stranded. Acceptable; the ban screen remains the only lever for them.
- **New installs** go straight to the new backend.

---

## 8. Open decisions
- ❓ **Managed-start vs self-host-from-day-one** (§4 path 2 vs the alternative).
- ❓ **Which self-host provider** (Hetzner vs Oracle-free vs other) + when to
  migrate (the cost-crossover threshold).
- ❓ **Realtime layer language** (Go vs Elixir/Phoenix Channels — Phoenix is
  purpose-built for millions of sockets).
- ❓ Exact `devices`/`accounts` split + indexes (ties into account plan §2–§5).
- ❓ FCM send path: Cloud Function / tiny worker vs the self-host server itself.
- ❓ Whether to keep a thin Supabase tier permanently for auth/storage even after
  self-hosting the realtime path (hybrid), or go fully self-host.

## 9. Honest caveats
- No managed service is "free unlimited" at scale; **only flat-cost self-host** is
  cheap at 100k, and even that needs a **cluster** (~$40–100/mo), not a $5 box.
- The whole thing is affordable at 100k **only because of §2** (FCM commands +
  sparse presence + no per-user socket + no managed Auth). Without it, every
  backend is expensive.
- Numbers here are modeled + pricing is dated (2026-07-29) — re-verify before
  committing spend.
