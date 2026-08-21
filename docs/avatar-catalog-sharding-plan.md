# Avatar Catalog — Scaling & Sharding Master Plan

> Audience: a future Claude Code session working on VRC-A. This is a design/engineering
> document, NOT user-facing. Assume full context of the codebase. Be precise, reference
> exact files/functions/constants. Nothing here is implemented yet except where noted
> "(shipped)". Do not implement any of this until the trigger thresholds in §2 are hit —
> at current scale (~3k avatars) the in-RAM design is strictly better.

---

## 0. TL;DR / decision record

- The crowdsourced avatar catalog (`AvatarGlobalDb`) currently ships the **entire** catalog
  as one `avatars/db.json` file, pulled from the GitHub CDN into an in-RAM
  `ConcurrentHashMap<String, Entry>` keyed by image file id. Clone-resolve = O(1) RAM
  lookup; search = O(n) RAM token scan.
- This is optimal up to ~30k avatars and **fatal past ~150k** (RAM-at-parse wall — see §1).
- Migration is **staged** (§2): streaming parse → slim resident model → full CDN sharding →
  Action-driven rebuild pipeline. Each stage is independently shippable and backward
  compatible (dual-source reads, fallbacks).
- **Hard constraint from the user (load-bearing):** do NOT increase Cloudflare Worker usage.
  Regular-user reads must stay on the **free GitHub CDN** (static files). The Worker keeps
  only its current write-side jobs (`/contribute`, `/report`, `/admin`, `/health`, `/db`,
  cron flush). No per-user `/lookup` or `/search` Worker traffic — ever.
- **Second user constraint:** aggressive presence-scoped eviction. When the last wearer of
  an avatar leaves the instance, its cached entry is dropped. No cross-encounter "cached =
  instant"; every new encounter is a fresh ~200 ms shard fetch. The pending-contribution
  queue is a SEPARATE store, never evicted (see §7.4).
- Cloudflare's own limits also forbid the Worker ever loading the whole catalog (128 MB
  Worker memory) — heavy rebuilds move to **GitHub Actions** (real RAM, free for public
  repos). See §5.

---

## 1. Current architecture (baseline to migrate from)

### 1.1 Files & responsibilities
- `app/src/main/kotlin/com/vrca/vrchat/AvatarGlobalDb.kt` — the client catalog. In-RAM
  `map: ConcurrentHashMap<String, Entry>`. Reads `avatars/db.json` via CDN (ETag, 30-min
  `REFRESH_MS`, + `forceRefresh(cacheBust)` reading the Worker `/db` for freshness).
  `contribute()` queues to `vrca_avatar_db` prefs + inserts into `map` immediately;
  `flushQueue()` drains every `FLUSH_MS` (2 min) in `CONTRIBUTE_CHUNK`=200 POSTs.
  `parseInto()` → `mergeWithLocal()` (monotonic `filled`/`checked`, empty-response wipe
  guard). `lookup(fileId)`, `searchByName(query)`, `adminPush()`, `applyAdminLocal()`,
  `markCheckedLocally()`, `snapshot()`, `workerLastFlush()`, `pendingReportCount()`,
  `fetchReports()`.
- `app/src/main/kotlin/com/vrca/vrchat/VrchatAuthManager.kt` — `resolveWornAvatarId()`
  (the clone resolver). Consult order: `AvatarGlobalDb.lookup(wornFileId)` FIRST (instant),
  then the DB stack: `AvatarSearch.searchCandidatesByImageFileId`, `resolveViaAuthorAvatars`
  (`GET /file/{id}`→ownerId→`GET /avatars?userId=`), name search + thumbnail-file-id confirm.
  Returns `WornAvatarResult(avatarId, platforms)`. `avatarCatalogEntry()`,
  `currentAvatarCatalogEntry()`, `ownAvatarLibrary()`, `favouriteAvatarIds()` feed
  contributions. `fetchUserInfo()`/`prettyPlatform()`.
- `app/src/main/kotlin/com/vrca/vrchat/AvatarSearch.kt` — `searchAll()` = our catalog +
  avtrdb + 2 VRCX mirrors, merged, deduped; `AVTRDB_PAGE_GUARD`=1000 (full pagination).
- `app/src/main/kotlin/com/vrca/vrchat/InstanceRosterManager.kt` — headset roster; resolves
  each member's clone id in the background (single-flight, 1s-paced, rides the `/users/{id}`
  call that also gets platform/pfp). `gateCloneId`/`selfIsQuest` PC-only grey-out. Caches
  (`avatarIdCache`, `platformCache`, `pfpCache`, `avatarIdResolvedFor`) cleared on instance
  leave. THIS is the presence-scoped eviction the user referenced.
- `app/src/main/kotlin/com/vrca/admin/AvatarCatalogSweep.kt` — the 4-bot maintenance sweep.
  Roles REPORTS/FILL/LIVENESS_A/LIVENESS_B. `needsFill(e) = !e.filled` (shipped fix).
  Shared atomic `inFlight` claim set + `claimBatch`/`release` (shipped). `helpPass` loaning
  (shipped). Per-cycle try/catch + `ensureRunning` dead-job watchdog (shipped). `PACE_MS`
  1200, `FILL_BATCH`/`LIVENESS_BATCH` 40, `RECHECK_INTERVAL_MS` 7d, partition by
  `fileId.hashCode() & 0x7fffffff % 2`.
- `app/src/main/kotlin/com/vrca/admin/BotVrchatSession.kt` — 4 encrypted bot slots. `login`
  (global `loginGate` Mutex + `MIN_LOGIN_GAP_MS` 30s + `RATE_LIMIT_WAIT_MS` 75s),
  `verify2FA`, `validate`, `autoRelogin`. `checkAvatar()` → `AvatarCheck(alive, fileId,
  name, author, authorId, platforms, description)`.
- `app/src/main/kotlin/com/vrca/admin/BotController.kt` — process-lifetime driver. Loop 1
  (2s: `applySweepConfig` + off-main `roleViews`/`blitzViews`). Loop 2 (validate/autoRelogin,
  staggered). `beginLogin/endLogin/chilling/silenced` pause + login-serialization.
- `cloudflare/avatar-db/worker.js` — single writer of `avatars/db.json`. KV `AVATAR_KV`
  pending queue. Endpoints `/contribute`, `/report`, `/admin`, `/admin/reports`,
  `/admin/restore`, `/health`, `/db` (fresh from KV `dbcache`). Cron `*/2` flush. Reads
  >1 MB via Blob API; never-wipe guard (`hadExistingFile`, 0.7 drop-ratio abort);
  `serializeDb()` one-avatar-per-line. `GH_TOKEN` server-side secret; `ADMIN_KEY` secret.
- `cloudflare/avatar-db/wrangler.toml` — `crons=["*/2 * * * *"]`, KV binding, `GH_REPO`
  `Ashoska/VRC-A-Image-store`, `DB_PATH` `avatars/db.json`, `GH_BRANCH` `main`.

### 1.2 Entry schema (current)
```
Entry(fileId: String,        // "file_..." — the KEY. Unique per avatar upload.
      avatarId: String,      // "avtr_..." — what you PUT /avatars/{id}/select to clone.
      name, author, authorId,
      platforms: List<String>,   // subset of {PC, Quest, iOS}
      checked: Long,             // epoch ms bot last verified alive (0=never)
      description: String,
      filled: Boolean)           // bot has done first-fill
```
Serialized JSON per entry ≈ 250–350 bytes; ~310 avg. Worker writes one avatar per line.

### 1.3 The scaling walls (measured/derived)
- **Parse transient (the real wall):** `JSONObject(text)` holds raw text + full boxed tree.
  ~2–4× file size in transient heap. 150k entries ≈ 50 MB file ≈ 150–250 MB transient →
  OOM on typical Android heaps (128–512 MB, shared with Discord WebView + FGS).
- **Resident map:** ~500–900 bytes/entry live (String object overhead + UTF-16). 100k ≈
  50–90 MB resident — sustained pressure that raises OEM low-memory kill odds.
- **CDN/git:** a 50 MB+ file committed every 2 min bloats git history (git stores a fresh
  blob per change; JSON deltas poorly); GitHub warns/blocks past ~1–5 GB repo. Also the
  Contents API 1 MB inline-content limit (the past catastrophic-wipe root cause — already
  mitigated in worker.js via Blob API, but a growing single file keeps flirting with it).
- **Worker memory:** 128 MB hard cap. Cannot load/rewrite a large whole-catalog file.

---

## 2. Staged migration & trigger thresholds

Do the CHEAP stages first; they buy large headroom and defer the complex sharding. Each
stage is shippable alone.

| Stage | Trigger | What | Buys headroom to |
|---|---|---|---|
| 0 (now) | ≤ 30k | In-RAM full catalog (current). Do nothing. | ~30k |
| A | ~30k, or first OOM report | **Streaming NDJSON parse** (kill the parse spike) | ~120–150k |
| B | ~50k | **Slim resident model** (RAM holds clone-path fields only; heavy fields lazy) | ~300–500k for clone path |
| C | ~100k | **Full CDN sharding** (lookup shards + index buckets) | millions |
| D | ~100k (with C) | **GitHub Action rebuild pipeline** (master + index + GC) | millions |
| E | ~hundreds of k | **Master file → release asset** (stop committing it to git history) | unbounded |

**Headline rule:** start at ~50k, be fully sharded (C+D) before ~150k. Never wait for 1M.

### Stage A — streaming parse (cheapest, highest ROI)
- Replace `parseInto(text)`'s `JSONObject(text)` full-tree with `android.util.JsonReader`
  over the file **streamed one avatar per line** (worker already writes NDJSON-ish: the
  `"avatars": { ... }` object with one `"file_x": {...}` per line). Parse line-by-line,
  build the `fresh` map incrementally, never hold the whole tree. Keep `mergeWithLocal`.
- Also stream the disk-cache write (`File.writeText` is fine; the READ is the OOM risk).
- Removes the transient spike; the remaining wall becomes resident size → Stage B.
- **Invariant to preserve:** empty-response wipe guard (`if (avatars.length()==0 &&
  map.isNotEmpty()) return`) must survive the rewrite — check the stream produced ≥1 entry
  before `map.clear()`; on a truncated/short read, ABORT (keep old map).

### Stage B — slim resident model
- Split `Entry` storage: keep a **resident `ConcurrentHashMap<String, Lite>`** where
  `Lite(avatarId, platformMask: Int, perf: Int)` ≈ 90 bytes/entry (fileId is the key).
  100k ≈ 9 MB, 500k ≈ 45 MB resident — acceptable.
- `name`/`author`/`authorId`/`description` (the search/display heavy fields) are NOT held
  resident. Search moves to the token index (Stage C's `index/` buckets, or an interim
  on-disk index). Clone-resolve (`lookup`) only needs `Lite`.
- `contribute()`'s immediate-local-insert now writes to a small `localOverlay` +
  the resident `Lite` map (so the contributor sees their own instantly).
- This is the fork point: at Stage B, `resolveWornAvatarId` step-0 reads the resident `Lite`
  map (still O(1), no network) — so cloneability stays instant even without sharding, up to
  ~300–500k. Sharding (C) is only strictly needed when even the `Lite` map is too big to
  hold/download.

### Stage C — full sharding (the main event; §3–§7)

### Stage D — Action rebuild pipeline (§5)

### Stage E — master as release asset (§6.3)

---

## 3. Sharded data model (Stage C)

All files live in the image-store repo (`Ashoska/VRC-A-Image-store`) under `avatars/`, served
by the GitHub CDN (raw / jsDelivr). Compute every path client-side; never list a directory.

### 3.1 File-id shard key
- File id = `file_` + UUID (`8-4-4-4-12` hex). Use the **first 3 hex chars of the UUID**
  (index 5,6,7 of the string, i.e. right after `file_`) → **4096 shards**.
- `shardPrefix(fileId) = fileId.substring(5, 8)` (guard length; fall back to a hash if the
  format ever differs).
- Path: `avatars/lookup/<prefix>.json` (flat; 4096 files in one dir is fine for git/CDN).
- At 1M avatars: 4096 shards × ~244 entries × ~90 bytes ≈ **~22 KB/shard**. At 100k ≈ 2.4 KB.
- Tunable: if shards get too big, reshard to 4 hex (65,536 shards) — but that's a lot of
  files; prefer sub-2-level dirs (`lookup/ab/c.json`) if you go there. 3 hex is the sweet
  spot; document any reshard in the Action + client `shardPrefix` together (must match).

### 3.2 Lookup shard record (the clone path — tiny, no heavy fields)
`avatars/lookup/<prefix>.json`:
```json
{ "v": 1,
  "e": {
    "file_ab1...": { "a": "avtr_...", "p": 3, "pf": {"pc":1,"q":3} },
    ...
  } }
```
- `a` = avatarId. `p` = platform bitmask (PC=1, Quest=2, iOS=4). `pf` = perf ranks per
  platform (see §4). Omit `pf` when unknown.
- ~80–95 bytes/entry. This is ALL the clone button + PC-only gate needs. No name/desc here.

### 3.3 Search index buckets (our-DB search; unioned with mirrors)
- Tokenize name + author + description (lowercase, split on `\W+`, drop len<2, dedupe).
  Author `usr_` id is also a token (search-by-author).
- Bucket by the token's **first 2 chars** → `avatars/index/<cc>.json` (≈ up to a few
  thousand buckets; hot buckets like `th`, `ca` may be large → sub-shard by 3rd char when a
  bucket exceeds, say, 256 KB: `index/th/x.json`; record the split in a manifest `index/
  _meta.json` the client reads once).
- Bucket format (posting lists with **denormalized display summaries** so search needs no
  second fetch):
```json
{ "v": 1,
  "t": {
    "fox": [ {"a":"avtr_..","f":"file_..","n":"Foxxo","au":"Maker","p":3,"pf":{"q":2},"r":1234}, ... ],
    "cute": [ ... ]
  } }
```
- `r` = ranking/popularity (see §4.3). Cap postings per token (e.g. top 500 by `r`) to bound
  bucket size; the Action owns this cull.
- Search = fetch bucket(s) for each query token, AND-intersect by `a`, rank by summed token
  weight × `r`, union with mirror results, dedupe by `a`. Platform/perf filters applied
  client-side over the summaries.

### 3.4 Master file (backup / canonical)
- `avatars/db.json` stays as the FULL record (all fields), one avatar per line, rebuilt by
  the Action (§5) — NOT the live lookup path anymore. Disaster-recovery + human-browsable +
  bulk export. At Stage E it becomes a release asset.

### 3.5 Manifest
- `avatars/_manifest.json`: `{ shardScheme:"filehex3", shardCount:4096, indexScheme:"tok2",
  indexSubSharded:[...], lastFullRebuild, entryCount, version }`. Client fetches once per
  session (cheap, CDN) to learn scheme + detect resharding without an app update.

---

## 4. New fields & features to fold in during the migration

### 4.1 `perf` — VRChat optimisation/performance rank (user-requested)
- Source: `GET /avatars/{id}` → `unityPackages[].performanceRating` ∈ {Excellent, Good,
  Medium, Poor, VeryPoor, None}. The FILL bot already makes this exact call — zero extra cost.
- Store **per-platform** (PC perf ≠ Quest perf). Encode rank int: Excellent=0, Good=1,
  Medium=2, Poor=3, VeryPoor=4, None/unknown=5. `pf: {"pc":1,"q":3}` in lookup + index.
- End-to-end change list: `AvatarGlobalDb.Entry` (+`perfPc`/`perfQuest`/`perfIos` or a
  `perf: Map<String,Int>`), `BotVrchatSession.AvatarCheck` (+parse performanceRating per
  unityPackage), `fillRefresh`/`liveRefresh` (record/refresh — a re-upload changes it, so
  liveness must refresh it like it does name/platforms), `adminPush` serialization, worker
  `cleanEntry`, lookup record `pf`, index summary `pf`, search UI filter+sort.
- **Search UX:** filter "Quest-Good-or-better", sort worst-first/best-first. This is a real
  ranking signal straight from VRChat (unlike popularity, which is third-party-derived).

### 4.2 Author as a first-class search axis
- Index the author's `usr_` id AND display name as tokens (already in §3.3). "search by
  author" = query the author name/id token. Free.

### 4.3 `r` — popularity/quality ranking for search ordering
- VRChat exposes NO public avatar popularity rank. Only source is the mirrors: avtrdb
  returns favorite/usage-ish metrics. Have the FILL bot (and the avtrdb search harvest in
  `AvatarGlobalDb.harvestSearchResults`) record a popularity number when available; store as
  `r`. Absent ⇒ 0. Used only to rank/cull search postings, never for correctness.
- Honest caveat to preserve in code comments: `r` is approximate, mirror-coverage-limited.

### 4.4 Platform filter
- Already implicit: `p` bitmask on every record. Search/roster filter client-side. The
  PC-only clone gate (`InstanceRosterManager.gateCloneId`) reads `p` from the lookup shard.

### 4.5 "Retry not-found" roster loop (user-approved)
- Problem: today if the roster resolves a present member as "no match" (`avatarId==""`), and
  they don't switch avatars, it never retries — so a just-contributed/propagated avatar
  won't light up until they switch or you rejoin.
- Fix: in `InstanceRosterManager`, add a slow re-check (every ~3–5 min) of still-present
  members whose `avatarId==""`, re-running the sharded lookup (their shard may have updated).
  Bounded (only unresolved present members), rides existing pacing.

### 4.6 Refresh-on-use (user-flagged; cuts propagation 30 min → ~5–9 min)
- Today regular users only pull the catalog every `REFRESH_MS` (30 min). In the sharded
  world, lookups fetch the live shard on demand from the CDN, so this partly self-solves —
  BUT the shard's CDN cache is ~5 min. On instance-join and search-open, issue a
  conditional GET of the needed shards/buckets so they're at CDN-freshness (~5 min) rather
  than up to 30 min stale. No Worker traffic (CDN only). Keep it to the shards actually
  needed (roster members / query tokens), not a bulk pull.

---

## 5. Worker changes (Stage C/D) — stays thin, respects 128 MB & KV budget

### 5.1 Incremental per-shard writes on flush (never load the whole catalog)
- The cron flush currently rebuilds `db.json`. Change: group pending contributions/reports
  by `shardPrefix`, then for each affected shard: read `avatars/lookup/<prefix>.json` (small,
  <1 MB → Contents API fine, no Blob API needed at shard granularity), merge, and stage the
  new blob. Update the affected `index/` buckets likewise (tokenize each new entry, append to
  its buckets, dedupe by `a`).
- **Commit all changed files in ONE commit** via the Git Data API: create blobs → create a
  tree (base = current `main` tree) → create commit → update ref. One commit per flush
  regardless of how many shards/buckets changed. This keeps git history sane and stays well
  under Worker memory (only the touched shards are in memory, each tiny).
- The Worker does NOT rebuild the master `db.json` or the full index (the Action does — §5.3).
  Incremental index appends can leave dead postings (removed avatars) and unbounded hot
  buckets; the Action's periodic full rebuild GCs both.
- **KV budget invariant (past blowout):** keep the queue-then-batch-flush model
  (`AvatarGlobalDb.flushQueue` + Worker cron). Do NOT do a KV write per avatar. The Worker
  merges pending into shards in the single scheduled flush.

### 5.2 Endpoints (unchanged surface; regular users still never hit these)
- `/contribute`, `/report`, `/admin`, `/admin/reports`, `/admin/restore`, `/health`, `/db`
  remain. `/health` gains `shardScheme`/`entryCount`/`lastFullRebuild` echoes for the client
  manifest + admin panel. Still NO `/lookup` or `/search` (would add per-user Worker cost —
  forbidden by the user constraint).

### 5.3 GitHub Action rebuild pipeline (the heavy lifting — free, real RAM)
- New workflow `.github/workflows/catalog-rebuild.yml` in the IMAGE-STORE repo (not the app
  repo), `schedule` every 15–30 min + `workflow_dispatch`.
- Node script: read all `lookup/` shards (or the master), regenerate:
  1. `avatars/db.json` master (full records, one/line) — canonical backup.
  2. The FULL `index/` bucket set from scratch (purges dead postings, re-culls hot buckets to
     top-N by `r`, applies sub-sharding + writes `index/_meta.json`).
  3. `_manifest.json` (entryCount, lastFullRebuild, scheme).
- Real GH-hosted runner RAM (7 GB) handles millions of entries a Worker never could. Commit
  results (one commit). Public repo ⇒ Actions minutes are free.
- **Coordination:** the Worker's incremental shard writes and the Action's full rebuild both
  target `main`. Use the same "merge, don't overwrite blindly" discipline: the Action rebuilds
  master+index FROM the shards (shards are the source of truth for lookup), so it never fights
  the Worker's shard writes. If a race commit conflicts, retry with a fresh base tree.

---

## 6. Client changes (Stage C)

### 6.1 Resolver
- `AvatarGlobalDb`: add `suspend fun lookupSharded(fileId): Lite?`:
  1. Check `localOverlay` (contributor's own new avatars) — instant.
  2. Check a small in-memory LRU of recently-fetched shards (bounded, e.g. 32 shards;
     evicted on instance leave with the roster caches to honor the no-storage-buildup rule —
     memory-only, NOT persisted to disk, mirroring the pfp disk-cache-disabled decision).
  3. Else fetch `avatars/lookup/<prefix>.json` from CDN (conditional GET), parse, cache in the
     LRU, return the entry (or null).
- `resolveWornAvatarId` step-0 calls `lookupSharded` instead of `lookup`. Everything else
  (author-listing, name-confirm, image-file-id) unchanged — still the fallback for cache
  misses.
- **Eviction (user requirement):** on instance leave, clear the shard LRU + `localOverlay`
  presence entries, exactly like `InstanceRosterManager` clears its caches today. The
  pending-contribution queue (`vrca_avatar_db` prefs) is untouched (separate store). Net:
  every new encounter = ~200 ms shard fetch, memory stays flat.

### 6.2 Search
- `searchByName` → `searchSharded(query)`: tokenize; fetch `index/<cc>` bucket(s) for the
  tokens (respect `_meta.json` sub-sharding); AND-intersect postings by `a`; rank by
  Σ(token weight) × `r`; apply platform/perf filters. Then `AvatarSearch.searchAll` unions
  this with avtrdb + VRCX mirrors exactly as today. Contribute-back unchanged.
- The index buckets are CDN static files (no Worker). First query on a token ≈ 200–500 ms,
  then within-session cached; mirrors stream alongside.

### 6.3 Contribute / eviction / master
- `contribute()`: keep queue→POST→Worker + immediate `localOverlay` insert (so the
  contributing device sees its own avatar instantly, pre-flush).
- Master file: only the ADMIN/backfill paths or a "bulk import" would read `db.json`; the app
  no longer pulls the whole master on the clone/search path.

### 6.4 Roster (`InstanceRosterManager`)
- Clone-id resolve rides the existing paced `/users/{id}` loop; per member, call
  `lookupSharded(wornFileId)` (one shard fetch, ~200 ms, cached for the member's stay).
  No separate prefetch (the resolve loop already fetches every needed shard — confirmed with
  the user). Add the §4.5 retry-not-found loop. PC-only gate reads `p` from the `Lite`.

---

## 7. Invariants, failure modes, edge cases (carry forward — these were bought with pain)

1. **Never wipe.** worker.js: read >1 MB via Blob API; on any read/parse failure ABORT (keep
   file); safety guard aborts a flush that drops entry count > 30% beyond explicit removes.
   Client `parseInto`: empty-response guard. Preserve all of these through the shard rewrite
   (apply the guards PER SHARD: never overwrite a populated shard with an empty/short read).
2. **Monotonic `filled`/`checked`** (`mergeWithLocal`): a stale file re-pull must never reset
   a locally-filled/checked entry — else the bots re-do work forever. In the sharded world,
   the per-shard merge must keep this monotonicity (local ahead of file wins until the file
   catches up).
3. **Claim set** (`AvatarCatalogSweep.inFlight`): multiple bots (dedicated + loaning) never
   process the same avatar. Cleared on `stop()`.
4. **Sweep self-heal** (shipped): per-cycle try/catch + `ensureRunning` dead-job watchdog. A
   coroutine death must never permanently freeze a role's queue.
5. **Rate-limit discipline:** `PACE_MS` 1200/bot; 3-consecutive-null backoff; login
   serialization via `loginGate` (`MIN_LOGIN_GAP_MS`, `RATE_LIMIT_WAIT_MS`) — only the
   Basic-auth password login is IP-rate-limited, cookie GETs and 2FA verify are not.
6. **KV write budget:** queue+batch; no per-avatar KV write. One flush POST per cron tick.
7. **CDN staleness ~5 min:** the price of keeping reads on the free CDN (no Worker /lookup).
   Freshness floor is Worker-flush(2 min)+CDN(~5 min) ≈ 5–9 min for regular users; bots read
   `/db` (fresh KV) for ~2–4 min. Accept it; do not "fix" it by routing reads through the
   Worker (violates the Cloudflare-flat constraint).
8. **`needsFill = !filled` only** (shipped): NOT `|| platforms.isEmpty() || ...` — VRChat
   always has a platform, and OR-ing in field-emptiness re-queued un-satisfiable entries
   forever (the "Fill queue stuck" bug). Later owner edits are liveness's job.
9. **Resolver correctness:** when the worn image file id IS known, only return an
   image-file-id-CONFIRMED match; never a name-only guess (the "cloned a same-named different
   avatar" bug). Name-only best-effort ONLY when no worn image exists (impostor/hidden thumb).
   This must remain true in `lookupSharded` (the lookup shard is keyed by file id, so it IS
   image-confirmed by construction — good; the fallback stack keeps the confirm logic).
10. **Presence-scoped eviction** (user requirement): shard LRU + overlay drop on instance
    leave; contribution queue is a separate, never-evicted store. No cross-encounter caching
    promises.
11. **PC-only gate:** save PC-only avatars to the catalog regardless, but grey the clone
    button when local user is Quest and the avatar lacks a Quest package (`p & 2 == 0`).
12. **Privacy:** public-only (`releaseStatus=="public"`); never contribute private avatars.

---

## 8. Cost model (must stay ~flat)
- **Cloudflare:** Worker write-side only (contribute/report/admin/health/db + cron). No
  per-user reads. Unchanged by sharding. KV: batched writes only.
- **GitHub CDN (raw/jsDelivr):** all regular-user reads (shards + index + manifest). Free,
  scales with users at zero cost to us. More, smaller requests than today's single file —
  still free.
- **GitHub Actions:** rebuild pipeline. Free for public repos. Minutes scale with catalog
  size / cadence, not user count.
- **GitHub storage:** repo holds shards + index + master. Past ~hundreds of k, move master to
  a release asset (Stage E) to cap git-history growth; shards/index are small per-file and
  churn slowly (one avatar touches one shard + a few buckets), so their history is fine.

---

## 9. Suggested implementation order (when triggered)
1. Stage A streaming parse (do early — removes the scariest failure mode; low risk).
2. `perf` field end-to-end (§4.1) — small, independently useful now, and gets it into the
   schema before shards freeze the format.
3. Stage B slim resident + move search to an index (interim on-disk index acceptable).
4. Worker: incremental per-shard writes + one-commit Git Data API (§5.1). Dual-write: keep
   producing `db.json` (master) AND the shards so old app versions still work.
5. Client: `lookupSharded` + shard LRU + eviction + resolver step-0 swap (fallback to old
   whole-file read if a shard 404s during rollout).
6. Client: `searchSharded` + index buckets, unioned with mirrors.
7. Action rebuild pipeline (§5.3) + `_manifest.json`.
8. §4.5 retry-not-found + §4.6 refresh-on-use.
9. Stage E master → release asset (only once git history growth is a real problem).
10. Retire the whole-file client read path once telemetry shows all clients updated.

## 10. Testing strategy
- Pure-logic (throwaway unit tests per the repo's no-permanent-tests rule): `shardPrefix`
  distribution (even spread over 4096), tokenizer, bucket AND-intersect + ranking,
  platform/perf bitmask encode/decode, `mergeWithLocal` monotonicity per-shard, never-wipe
  guards per-shard. Run via `./gradlew testPublicAppDebugUnitTest`, delete before commit.
- Worker: `node -c worker.js`; a local harness POSTing synthetic contributions and asserting
  one-commit-per-flush + shard membership + never-wipe on a simulated empty/short read.
- Device-only (can't unit test): CDN staleness timing, roster resolve latency, OEM memory
  pressure at the Stage-B resident size, Action rebuild wall-clock at scale.

## 11b. Shard size optimization (near-instant downloads)

Goal: minimize on-the-wire shard/bucket size while keeping all info. Ranked by ROI:

1. **Transport gzip/brotli is FREE and AUTOMATIC — rely on it, don't hand-roll.** GitHub raw
   and jsDelivr serve text with `Content-Encoding: gzip` (jsDelivr also brotli) automatically,
   and `HttpURLConnection`/OkHttp auto-decompress. JSON compresses ~85–90%, so a ~22 KB lookup
   shard is **~3–5 KB on the wire** already → effectively near-instant. **Do NOT pre-gzip the
   files** (double-compression = wasted bytes + you lose the CDN's transparent handling and
   human-readability). This single fact means the user's "near-instant" is mostly already true
   for lookup shards; the work below is for the heavier INDEX buckets.
2. **Short keys** (already in §3.2/§3.3: `a`,`p`,`pf`,`f`,`n`,`au`,`r`). gzip dedupes repeated
   keys anyway, but short keys shrink the pre-compression tree and speed parse.
3. **Strip constant id prefixes.** Store the 36-char UUID only, re-add `file_`/`avtr_`/`usr_`
   on read. In lookup shards the KEY is the fileId → store bare hex; `a` stores the bare avtr
   UUID. ~5 bytes/id × up to 3 ids/record. gzip loves the resulting uniformity.
4. **Index: fragment-store split (the real structural win — Pagefind model).** The naive index
   duplicates each avatar's summary under every token it matches (name words + author + every
   description word) → massive redundancy in the heavy part. Instead:
   - `index/<cc>.json`: token → **list of avatar ids only** (bare avtr UUIDs).
   - `fragments/<prefix>.json`: avatarId → summary `{f,n,au,p,pf,r}` stored **once**.
   Search = fetch bucket(s) (ids), AND-intersect, then fetch the fragment(s) for the top-N to
   display. One summary per avatar instead of one per (avatar×token). At scale this can shrink
   the index by 5–20×. Cost: one extra fetch (ids → fragments), CDN-cached. Adopt when the
   inline-summary buckets get large; below ~50k the inline form (§3.3) is simpler.
5. **Cull postings per token** (top-N by `r`) so a hot token's bucket stays bounded — the
   Action owns this. Combined with the fragment split, buckets become tiny id-lists.
6. **Binary (MessagePack/CBOR) — LAST resort, probably skip.** Smaller pre-gzip + faster parse,
   but kills repo human-readability and adds a codec dependency. Given gzip already does ~85%
   and shards parse in <1 ms, not worth it. If ever needed, apply ONLY to shards/buckets (keep
   the master `db.json` as JSON for browsability). Raw-16-byte UUIDs (vs 36 hex) only make sense
   inside a binary format.

**Net:** lookup shards are already near-instant thanks to CDN gzip + their tiny size; short
keys + prefix-strip are a cheap extra ~15–20%. The index is where structural work (fragment
split + posting cull) pays off — do it when buckets grow, not before.

## 11. Open decisions (resolve at implementation time)
- 3-hex (4096) vs 4-hex (65536) shard count — start 3-hex, reshard via `_manifest.json` +
  matched `shardPrefix` bump if shards exceed ~64 KB.
- Hot-bucket sub-shard threshold (256 KB?) and top-N postings cap per token (500?).
- Whether the shard LRU persists to a small bounded disk cache (faster re-encounters) vs
  strictly memory-only (honors no-storage-buildup) — user leans memory-only; default to that.
- `perf` storage shape: flat `perfPc/perfQuest/perfIos` ints vs a `Map<String,Int>` — flat is
  cheaper to (de)serialize at scale; prefer flat.
