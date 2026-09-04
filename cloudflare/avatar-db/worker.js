// VRC-A Avatar DB Worker (R2-only)
// -----------------------------------------------------------------------------
// A tiny, VRChat-free, crowdsourced avatar-id catalog merger — now 100% on
// Cloudflare R2. GitHub is OUT of the pipeline entirely: both the apps AND the
// admin bots read the SHARDS straight from the CDN (cdn.gremlininc.app), and the
// cron flush writes per-shard R2 objects. No GitHub token, no whole-file commit,
// no CPU/commit wall — it scales to any catalog size.
//
// Data model in R2:
//   shard/<3hex>.json      full records keyed by fileId (the clone-lookup source)
//   fragments/<3hex>.json  search summaries (built by the rebuild Action)
//   index/<3hex>.json      token -> ids (built by the rebuild Action)
//   avtr/<3hex>.json       avatar-id presence (crawler dedup, built by the Action)
//   db.json                full master snapshot (written by the Action = the backup)
//   _manifest.json         counts + searchReady + lastFullRebuild (+ live count here)
//   _worklist.json         shard prefixes with bot work (built by the Action)
//
// This Worker keeps the lookup shards fresh (contribute/report/admin -> flush);
// the GitHub Action rebuilds search + master + manifest + worklist from the shards
// every ~20 min. Reads happen straight off the CDN in the apps. Zero Firestore.
//
// Endpoints:
//   POST /contribute   { entries: [ { fileId, avatarId, name, author, platforms } ] }
//   POST /report       { fileId, avatarId, status: "dead"|"renamed", name? }
//   POST /admin        { key, upserts?, removes?, checked?, clearReports? }   (bot sweep)
//   GET  /admin/reports?key=...            pending dead/rename reports for the bot
//   GET  /admin/reshard?key=&lo=&hi=       RECOVERY: re-shard from the R2 db.json master
//   GET  /health                           counts + backend status
//   GET  /catalog/<key>                    edge-cached R2 read (fallback if no custom domain)
//   GET  /db                               the R2 master snapshot (download/backup)
//   GET  /flush                            trigger a flush on demand (diagnostics)
//
// Bindings/vars (Cloudflare dashboard / wrangler.toml):
//   R2 bucket binding:   CATALOG   (bucket vrca-avatar-catalog)
//   Variable:            CATALOG_BASE   e.g. "https://cdn.gremlininc.app"
//   Secret (admin ops):  ADMIN_KEY
//   Secrets (purge-on-write, optional): CF_PURGE_TOKEN, CF_ZONE_ID
//   Cron trigger:        * * * * *   (every minute; purge makes a new avatar live in ~1s)
// -----------------------------------------------------------------------------

const AVTR_RE = /^avtr_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const FILE_RE = /^file_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const REMOVE_QUORUM = 2; // independent "dead" reports needed before a hard remove
// Shard edge-cache TTL. Long ON PURPOSE: shards are PURGED on every write (flushR2 -> purgeShards),
// so a changed avatar still goes live in ~1s regardless of this value — the TTL only governs how
// long an UNCHANGED shard stays served free from the edge. A long TTL is what makes a full-instance
// join (avatars spread across ~40 different shards) cheap: each shard is read from R2 once, then
// served free to EVERYONE who encounters that avatar until it changes. 6h balances max cache warmth
// against the worst case if a purge ever fails (a stale shard = slightly-old name/author; the
// file->avatar id mapping is stable, so cloning is unaffected).
const SHARD_TTL = 21600; // 6h (was 5 min); purge-on-write keeps it fresh

// The AUTHORITATIVE catalog size lives in _manifest.json (the Action rewrites entryCount every
// ~20 min). meta.entries in KV only moves on a flush, so between rebuilds — and whenever
// contributions are idle — it drifts stale (e.g. 86k while the manifest says 105k). /health
// returns the manifest number so the app + admin card always match the manifest. One cheap R2
// GET, memoized per warm isolate for 60s so 15s polls don't each hit R2.
let _manCountCache = { v: -1, at: 0 };
async function manifestEntryCount(env) {
  const now = Date.now();
  if (_manCountCache.v >= 0 && now - _manCountCache.at < 60_000) return _manCountCache.v;
  try {
    const m = await env.CATALOG.get("_manifest.json");
    if (m) {
      const j = await m.json();
      if (typeof j.entryCount === "number") { _manCountCache = { v: j.entryCount, at: now }; return j.entryCount; }
    }
  } catch (_) {}
  return -1;
}

// file id = "file_" + UUID (8-4-4-4-12 hex). The 3 hex after "file_" (index 5..7) are the
// shard prefix. Guarded: fall back to "000" if the format ever differs. 4096 shards.
function shardPrefix(fileId) {
  const p = (fileId || "").slice(5, 8);
  return /^[0-9a-f]{3}$/i.test(p) ? p.toLowerCase() : "000";
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: {
      "content-type": "application/json",
      "access-control-allow-origin": "*",
      "access-control-allow-methods": "GET,POST,OPTIONS",
      "access-control-allow-headers": "content-type",
    },
  });
}

function validEntry(e) {
  return e && typeof e === "object" &&
    typeof e.fileId === "string" && FILE_RE.test(e.fileId) &&
    typeof e.avatarId === "string" && AVTR_RE.test(e.avatarId);
}

// VRChat avatar performance/optimisation rank per platform:
// 0=Excellent 1=Good 2=Medium 3=Poor 4=VeryPoor 5=None/unknown. Clamp + default 5.
function clampPerf(v) {
  const n = Number.isInteger(v) ? v : 5;
  return n >= 0 && n <= 5 ? n : 5;
}

function cleanEntry(e) {
  const now = Date.now();
  const desc = typeof e.description === "string" ? e.description
    : (typeof e.desc === "string" ? e.desc : "");
  const plats = Array.isArray(e.platforms)
    ? e.platforms.filter((p) => typeof p === "string").slice(0, 4)
    : [];
  const out = {
    id: e.avatarId,
    name: typeof e.name === "string" ? e.name.slice(0, 100) : "",
    author: typeof e.author === "string" ? e.author.slice(0, 100) : "",
    authorId: typeof e.authorId === "string" ? e.authorId : "",
    platforms: plats,
    desc: desc.slice(0, 400),          // avatar bio (device- or bot-filled)
    filled: e.filled === true,         // bot did a full first-fill (devices send false)
    added: now,
    checked: now,                      // last liveness verify (= added at first)
  };
  // Per-platform perf rank — only for platforms the avatar actually builds for.
  if (plats.includes("PC")) out.perfPc = clampPerf(e.perfPc);
  if (plats.includes("Quest")) out.perfQuest = clampPerf(e.perfQuest);
  if (plats.includes("iOS")) out.perfIos = clampPerf(e.perfIos);
  return out;
}

// ---- incremental search index (FULL: add / rename / remove) -----------------
// The WORKER now owns the entire search index — GitHub is no longer needed for search. The flush
// maintains fragments/ (id->summary), index/ (token->ids) and avtr/ (id presence) incrementally
// from the same shard reads it already does, so search is ~1 min fresh with no full rebuild.
// The bucket scheme MUST match the app's readers EXACTLY (hashCode/indexBucket/fragBucket/tokenize/
// platMask are copied verbatim). A perf/bio-only bot fill changes no search field, so it's skipped
// entirely (buildIndexOp returns null) — only real add/rename/remove touch the index (the big saving).
function hashCode(s) { let h = 0; for (let i = 0; i < s.length; i++) h = (Math.imul(31, h) + s.charCodeAt(i)) | 0; return h; }
function indexBucketFor(token) { return ((hashCode(token) & 0xfff) >>> 0).toString(16).padStart(3, "0"); }
function fragBucketFor(avatarId) { return avatarId.slice(5, 8).toLowerCase(); }
function platMask(platforms) {
  let m = 0; const p = platforms || [];
  if (p.includes("PC")) m |= 1; if (p.includes("Quest")) m |= 2; if (p.includes("iOS")) m |= 4; return m;
}
function tokenizeFields(...fields) {
  const set = new Set();
  for (const f of fields) {
    if (!f) continue;
    for (const w of String(f).toLowerCase().split(/[^\p{L}\p{N}]+/u)) if (w.length >= 2) set.add(w);
  }
  return set;
}
const INDEX_HOT_TOKEN_CAP = 5000;   // must match the app/rebuild HOT_TOKEN_CAP
// Bound the SEARCH-INDEX work per flush (fragments/index/avtr). The index-op backlog beyond this cap
// carries forward in an `iq:` queue and drains over the next flushes, so a big burst never drops and
// subrequests stay bounded.
// Raised 30 -> 150: index ops are GROUPED by bucket in applyIndexOps, so co-located ops collapse into
// ONE read+write per bucket only WITHIN a single drain. Draining a small backlog whole (instead of 30
// at a time across many flushes) stops a hot token/frag/avtr bucket from being read+rewritten once per
// flush — the hot-bucket write amplification. Grouped, 150 ops touch far fewer than 150 buckets, so the
// subrequest cost stays well under budget alongside MAX_SHARDS_PER_FLUSH (120) and purges.
const MAX_INDEX_OPS_PER_FLUSH = 150;
// Bound the CLONE-SHARD writes per flush too. Each distinct shard is 1 R2 read + 1 write, so a burst
// of big USER contribution batches (a 136-avatar batch spans ~136 shards) could push a single flush
// past Cloudflare's per-invocation subrequest limit → the invocation dies BEFORE clearing the `pend:`
// keys → the same batches retry and die every minute ("pending batches stuck at N"). The pend loop
// consumes batches only until this many distinct shards are queued, then leaves the rest for the next
// 1-min flush, so a backlog DRAINS over a few flushes. 120 shards ≈ 240 R2 ops, well under budget
// alongside the index work + purges. Admin/bot pushes are separately bounded (WALK_BATCH).
const MAX_SHARDS_PER_FLUSH = 120;
// Coalesce _manifest.json writes. The LIVE entry count already rides `meta.entries` (which /health
// max()es against the manifest), so the _manifest.json copy only needs periodic freshening, not a
// write+purge on every count-moving flush during steady growth. Rewrite it at most every N ms OR once
// the count has drifted by K from the last written manifest — whichever comes first.
const MANIFEST_MIN_INTERVAL_MS = 5 * 60_000;   // at most one manifest write per 5 min from drift alone
const MANIFEST_MIN_DELTA = 25;                 // ...unless the count moved by >=25, then write now

// The search summary stored in a fragment bucket.
function fragSummary(e, fileId) {
  return { f: fileId, n: e.name || "", au: e.author || "", ai: e.authorId || "",
    p: platMask(e.platforms), pf: { pc: e.perfPc ?? 5, q: e.perfQuest ?? 5, i: e.perfIos ?? 5 } };
}
function tokensOf(e) {
  const s = tokenizeFields(e.name, e.author, e.desc);
  if (e.authorId) s.add(String(e.authorId).toLowerCase());
  return s;
}
// Compute a search-index op from an avatar's OLD and NEW state (either may be null).
//   old=null  -> ADD (new avatar): write fragment + avtr + all tokens.
//   new=null  -> REMOVE: delete fragment + avtr + remove all old tokens.
//   both      -> only touch the index if a SEARCH-RELEVANT field changed (name/author/authorId/
//                platforms); a perf/bio/checked-only bot fill returns null (skipped — the big saving).
// True when an upsert would change NOTHING material about the stored entry — so the shard write
// (and index re-key) can be skipped. Compares only the persisted, admin-refreshable fields; the
// `checked` timestamp and immutable `added` are intentionally ignored (a `checked` bump is handled
// separately, and `added` never changes). This is what stops an admin/bot "refresh" that found the
// avatar unchanged from pointlessly rewriting its shard.
function entryEquivalent(a, b) {
  if (!a || !b) return false;
  if (a.id !== b.id) return false;
  if ((a.name || "") !== (b.name || "")) return false;
  if ((a.author || "") !== (b.author || "")) return false;
  if ((a.authorId || "") !== (b.authorId || "")) return false;
  if ((a.desc || "") !== (b.desc || "")) return false;
  if ((a.filled === true) !== (b.filled === true)) return false;
  if (platMask(a.platforms) !== platMask(b.platforms)) return false;
  // Perf rank can change on a re-upload (e.g. the creator optimised the avatar). It's shown as the
  // search badge (fragSummary.pf), so a perf-only change MUST persist + re-index, not be skipped.
  if ((a.perfPc ?? 5) !== (b.perfPc ?? 5)) return false;
  if ((a.perfQuest ?? 5) !== (b.perfQuest ?? 5)) return false;
  if ((a.perfIos ?? 5) !== (b.perfIos ?? 5)) return false;
  return true;
}

// Op: { id, del, frag|null, add:[tok], rem:[tok], avtr:'a'|'r'|null }.
function buildIndexOp(oldE, newE, fileId) {
  if (!newE) {
    if (!oldE || !oldE.id) return null;
    return { id: oldE.id, del: true, frag: null, add: [], rem: [...tokensOf(oldE)], avtr: "r" };
  }
  if (!newE.id) return null;
  if (!oldE) return { id: newE.id, del: false, frag: fragSummary(newE, fileId), add: [...tokensOf(newE)], rem: [], avtr: "a" };
  const relevant = (oldE.name || "") !== (newE.name || "") || (oldE.author || "") !== (newE.author || "") ||
    (oldE.authorId || "") !== (newE.authorId || "") || platMask(oldE.platforms) !== platMask(newE.platforms) ||
    // perf rank rides the fragment summary (search badge), so a perf-only change must refresh it too.
    (oldE.perfPc ?? 5) !== (newE.perfPc ?? 5) || (oldE.perfQuest ?? 5) !== (newE.perfQuest ?? 5) ||
    (oldE.perfIos ?? 5) !== (newE.perfIos ?? 5);
  if (!relevant) return null;
  const ot = tokensOf(oldE), nt = tokensOf(newE);
  return { id: newE.id, del: false, frag: fragSummary(newE, fileId),
    add: [...nt].filter((t) => !ot.has(t)), rem: [...ot].filter((t) => !nt.has(t)), avtr: null };
}

// Apply a batch of index ops to fragments/<p> + avtr/<p> + index/<b>, grouped so each object is
// read+written ONCE. Returns the changed URLs for edge-cache purge.
async function applyIndexOps(env, ops) {
  if (!ops.length) return [];
  const fragWork = {};   // prefix -> { set:{id:summary}, del:Set(id) }
  const avtrWork = {};   // prefix -> { add:Set, rem:Set }
  const idxWork = {};    // indexBucket -> { token -> {add:Set, rem:Set} }
  for (const o of ops) {
    const id = o.id; if (!id || !String(id).startsWith("avtr_")) continue;
    const fp = fragBucketFor(id);
    const fw = (fragWork[fp] ||= { set: {}, del: new Set() });
    if (o.del) { fw.del.add(id); delete fw.set[id]; }
    else if (o.frag) { fw.set[id] = o.frag; fw.del.delete(id); }
    if (o.avtr === "a") (avtrWork[fp] ||= { add: new Set(), rem: new Set() }).add.add(id);
    else if (o.avtr === "r") (avtrWork[fp] ||= { add: new Set(), rem: new Set() }).rem.add(id);
    for (const t of (o.add || [])) ((idxWork[indexBucketFor(t)] ||= {})[t] ||= { add: new Set(), rem: new Set() }).add.add(id);
    for (const t of (o.rem || [])) ((idxWork[indexBucketFor(t)] ||= {})[t] ||= { add: new Set(), rem: new Set() }).rem.add(id);
  }
  const changed = [];
  const base = env.CATALOG_BASE ? env.CATALOG_BASE.replace(/\/$/, "") : null;
  const ttl = { httpMetadata: { contentType: "application/json", cacheControl: "public, max-age=" + SHARD_TTL } };
  // Fragments + avtr share the avatarId prefix.
  for (const p of new Set([...Object.keys(fragWork), ...Object.keys(avtrWork)])) {
    const fw = fragWork[p], aw = avtrWork[p];
    if (fw && (Object.keys(fw.set).length || fw.del.size)) {
      let cur = { v: 1, e: {} };
      try { const o = await env.CATALOG.get(`fragments/${p}.json`); if (o) cur = await o.json(); } catch (_) {}
      if (!cur || typeof cur.e !== "object" || cur.e === null) cur = { v: 1, e: {} };
      // NO-OP GUARD: only rewrite when a summary actually CHANGES (re-adding an already-indexed
      // avatar whose summary is identical, or a delete of an absent id, must not rewrite the bucket).
      // This is the frag/avtr/index equivalent of the shard `dirty` flag — the biggest wasted Class A.
      let dirty = false;
      for (const [id, summary] of Object.entries(fw.set)) {
        if (JSON.stringify(cur.e[id]) !== JSON.stringify(summary)) { cur.e[id] = summary; dirty = true; }
      }
      for (const id of fw.del) { if (id in cur.e) { delete cur.e[id]; dirty = true; } }
      if (dirty) {
        try { await env.CATALOG.put(`fragments/${p}.json`, JSON.stringify({ v: 1, e: cur.e }), ttl);
          if (base) changed.push(base + `/fragments/${p}.json`); } catch (_) {}
      }
    }
    if (aw && (aw.add.size || aw.rem.size)) {
      let cur = { v: 1, ids: [] };
      try { const o = await env.CATALOG.get(`avtr/${p}.json`); if (o) cur = await o.json(); } catch (_) {}
      if (!cur || !Array.isArray(cur.ids)) cur = { v: 1, ids: [] };
      const s = new Set(cur.ids);
      // NO-OP GUARD: only dirty when a membership actually changes (add of an id already present /
      // remove of an absent id rewrote the bucket identically — the reconcile re-add + requeue waste).
      let dirty = false;
      for (const id of aw.add) { if (!s.has(id)) { s.add(id); dirty = true; } }
      for (const id of aw.rem) { if (s.has(id)) { s.delete(id); dirty = true; } }
      if (dirty) {
        try { await env.CATALOG.put(`avtr/${p}.json`, JSON.stringify({ v: 1, ids: Array.from(s) }), ttl);
          if (base) changed.push(base + `/avtr/${p}.json`); } catch (_) {}
      }
    }
  }
  // Index token lists.
  for (const [b, toks] of Object.entries(idxWork)) {
    let cur = { v: 1, t: {} };
    try { const o = await env.CATALOG.get(`index/${b}.json`); if (o) cur = await o.json(); } catch (_) {}
    if (!cur || typeof cur.t !== "object" || cur.t === null) cur = { v: 1, t: {} };
    // NO-OP GUARD per token: only rewrite the bucket when at least one token's posting list actually
    // changed (a re-emitted ADD for an already-listed id, common after reconcile/requeue, was
    // rewriting the whole bucket identically).
    let dirty = false;
    for (const [tok, ch] of Object.entries(toks)) {
      const s = new Set(Array.isArray(cur.t[tok]) ? cur.t[tok] : []);
      let tdirty = false;
      for (const id of ch.add) { if (!s.has(id)) { s.add(id); tdirty = true; } }
      for (const id of ch.rem) { if (s.has(id)) { s.delete(id); tdirty = true; } }
      if (!tdirty) continue;
      let ids = Array.from(s);
      if (ids.length > INDEX_HOT_TOKEN_CAP) ids = ids.slice(0, INDEX_HOT_TOKEN_CAP);
      if (ids.length) cur.t[tok] = ids; else delete cur.t[tok];
      dirty = true;
    }
    if (dirty) {
      try { await env.CATALOG.put(`index/${b}.json`, JSON.stringify({ v: 1, t: cur.t }), ttl);
        if (base) changed.push(base + `/index/${b}.json`); } catch (_) {}
    }
  }
  return changed;
}

export default {
  async fetch(req, env, ctx) {
    const url = new URL(req.url);
    if (req.method === "OPTIONS") return json({ ok: true });
    try {
      // ---- R2 catalog serving (edge-cached) --------------------------------
      // GET /catalog/<key> -> the R2 object at <key> (e.g. /catalog/shard/ab1.json).
      // Fallback path when no custom domain is attached; normally CATALOG_BASE points at
      // the bucket's own domain so reads bypass the Worker entirely.
      if (req.method === "GET" && url.pathname.startsWith("/catalog/")) {
        if (!env.CATALOG) return json({ ok: false, error: "R2 not configured" }, 503);
        const key = url.pathname.slice("/catalog/".length);
        if (!key || key.includes("..")) return json({ ok: false, error: "bad key" }, 400);
        const cache = caches.default;
        const hit = await cache.match(req);
        if (hit) return hit;
        const obj = await env.CATALOG.get(key);
        if (!obj) return new Response("", { status: 404, headers: { "access-control-allow-origin": "*" } });
        const res = new Response(obj.body, {
          headers: {
            "content-type": "application/json",
            "cache-control": "public, max-age=" + SHARD_TTL,
            "access-control-allow-origin": "*",
          },
        });
        if (ctx && ctx.waitUntil) ctx.waitUntil(cache.put(req, res.clone()));
        return res;
      }

      if (req.method === "POST" && url.pathname === "/contribute") {
        const body = await req.json().catch(() => null);
        const list = body && Array.isArray(body.entries)
          ? body.entries
          : (body && body.fileId ? [body] : []);
        const good = list.filter(validEntry).slice(0, 200);
        if (good.length === 0) return json({ ok: false, error: "no valid entries" }, 400);
        const payload = {};
        for (const e of good) payload[e.fileId] = cleanEntry(e);
        // Sender tag + time for the admin "recent contributions" view. Stored as reserved non-fileId
        // keys so the flush merge (which filters on FILE_RE) ignores them; surfaced free at flush time.
        if (typeof body.by === "string" && body.by) payload.__by = body.by.slice(0, 40);
        payload.__ts = Date.now();
        // One KV write per POST (batch). Dedup happens at merge time (keyed by file id).
        await env.AVATAR_KV.put("pend:" + crypto.randomUUID(), JSON.stringify(payload), {
          expirationTtl: 7 * 86400,
        });
        return json({ ok: true, accepted: good.length });
      }

      if (req.method === "POST" && url.pathname === "/report") {
        const body = await req.json().catch(() => null);
        if (!body || !FILE_RE.test(body.fileId || "")) return json({ ok: false }, 400);
        const key = "rep:" + body.fileId;
        const existing = await env.AVATAR_KV.get(key);
        const cur = JSON.parse(existing || "{}");
        const isNewReport = !existing;
        cur.status = body.status === "renamed" ? "renamed" : "dead";
        if (body.status === "renamed" && typeof body.name === "string") {
          cur.name = body.name.slice(0, 100);
        }
        if (typeof body.avatarId === "string" && body.avatarId.startsWith("avtr_")) {
          cur.avatarId = body.avatarId;
        }
        cur.count = (cur.count || 0) + 1;
        // 7-day backstop (was 30): a report that never reaches quorum AND the bot can never verify
        // (a persistently-unreachable avatar id) self-expires instead of sitting in the queue for a
        // month. The flush's moot-clear already drains reports whose avatar left the catalog; this
        // covers the rarer in-catalog-but-unverifiable case so the queue always fully drains.
        await env.AVATAR_KV.put(key, JSON.stringify(cur), { expirationTtl: 7 * 86400 });
        // Wake the bot promptly: bump the live report count so the sweep's /health poll sees
        // it immediately instead of waiting for the next flush. Self-corrects at flush.
        if (isNewReport) {
          const meta = JSON.parse((await env.AVATAR_KV.get("meta")) || "{}");
          meta.reports = (meta.reports || 0) + 1;
          await env.AVATAR_KV.put("meta", JSON.stringify(meta));
        }
        return json({ ok: true });
      }

      if (req.method === "POST" && url.pathname === "/admin") {
        // Authoritative admin ops from the recheck sweep (bot VRChat session).
        const body = await req.json().catch(() => null);
        if (!body || !env.ADMIN_KEY || body.key !== env.ADMIN_KEY) {
          return json({ ok: false, error: "unauthorized" }, 401);
        }
        const upserts = Array.isArray(body.upserts) ? body.upserts.filter(validEntry).slice(0, 200) : [];
        const removes = Array.isArray(body.removes)
          ? body.removes.filter((f) => typeof f === "string" && f.startsWith("file_")).slice(0, 200)
          : [];
        if (upserts.length) {
          const payload = {};
          for (const e of upserts) payload[e.fileId] = cleanEntry(e);
          await env.AVATAR_KV.put("admu:" + crypto.randomUUID(), JSON.stringify(payload), { expirationTtl: 7 * 86400 });
        }
        if (removes.length) {
          await env.AVATAR_KV.put("admr:" + crypto.randomUUID(), JSON.stringify(removes), { expirationTtl: 7 * 86400 });
        }
        const checked = Array.isArray(body.checked)
          ? body.checked.filter((f) => typeof f === "string" && f.startsWith("file_")).slice(0, 500)
          : [];
        if (checked.length) {
          await env.AVATAR_KV.put("admk:" + crypto.randomUUID(), JSON.stringify(checked), { expirationTtl: 7 * 86400 });
        }
        const clearReports = Array.isArray(body.clearReports)
          ? body.clearReports.filter((f) => typeof f === "string" && f.startsWith("file_")).slice(0, 200)
          : [];
        // Delete resolved report keys, counting only those that ACTUALLY existed — a walk-discovered
        // dead avatar in `removes` was never reported, so it must not decrement the report counter.
        let repsResolved = 0;
        const toClear = new Set([...clearReports, ...removes]);
        for (const fid of toClear) {
          if ((await env.AVATAR_KV.get("rep:" + fid)) !== null) { await env.AVATAR_KV.delete("rep:" + fid); repsResolved++; }
        }
        // Decrement the live report counter so /health (and the admin "queued" number) drops as
        // reports are resolved, instead of sticking high until the next flush recomputes it.
        if (repsResolved) {
          const meta = JSON.parse((await env.AVATAR_KV.get("meta")) || "{}");
          meta.reports = Math.max(0, (meta.reports || 0) - repsResolved);
          await env.AVATAR_KV.put("meta", JSON.stringify(meta));
        }
        // AUTHOR RENAMES (bot-verified): the bot's GET /avatars/{id} is authoritative VRChat data, so a
        // detected author display-name change (same authorId, new name) is queued here as `arn:<authorId>`.
        // The cron (propagateAuthorRenames) bulk-applies it to EVERY entry by that author over a bounded
        // shard walk — so all of a creator's avatars pick up the new name instead of waiting for the
        // liveness sweep to reach each one. Keyed by the IMMUTABLE authorId; avtrdb names never reach here.
        const authorRenames = Array.isArray(body.authorRenames)
          ? body.authorRenames.filter((x) => x && typeof x.authorId === "string" && x.authorId.startsWith("usr_") &&
              typeof x.name === "string" && x.name.trim().length > 0).slice(0, 100)
          : [];
        for (const rn of authorRenames) {
          // 30-day TTL so an unprocessed rename can't linger forever; the cron deletes it on completion.
          await env.AVATAR_KV.put("arn:" + rn.authorId, rn.name.slice(0, 100), { expirationTtl: 30 * 86400 });
        }
        return json({ ok: true, upserts: upserts.length, removes: removes.length, cleared: clearReports.length, authorRenames: authorRenames.length });
      }

      if (req.method === "GET" && url.pathname === "/admin/reconcile") {
        // Re-arm the ONE-TIME search-index reconciler (resets its cursor + done flag) so it walks all
        // 4096 clone shards again — only needed if a future audit suspects the search index drifted
        // from the clone shards. Normal operation never needs this (the incremental flush maintains it).
        if (!env.ADMIN_KEY || url.searchParams.get("key") !== env.ADMIN_KEY) {
          return json({ ok: false, error: "unauthorized" }, 401);
        }
        const meta = JSON.parse((await env.AVATAR_KV.get("meta")) || "{}");
        meta.rc = 0; meta.rcDone = false; meta.reconcileScanned = 0; meta.reconcileFixed = 0;
        // Reset the recount accumulators + the clean-pass guards so the re-armed pass computes a
        // fresh EXACT count (adopted only if the whole 4096-shard lap reads cleanly).
        meta.rcEntries = 0; meta.rcUnfilled = 0; meta.rcReadFail = 0; meta.rcAttempts = 0; meta.rcAdoptSkipped = 0;
        await env.AVATAR_KV.put("meta", JSON.stringify(meta));
        return json({ ok: true, reconcile: "re-armed (one full pass will run over ~a day)" });
      }

      if (req.method === "GET" && url.pathname === "/admin/reports") {
        // The bot fetches PENDING dead/rename reports to verify — so it only checks REPORTED
        // avatars, never the whole catalog (scales to millions).
        if (!env.ADMIN_KEY || url.searchParams.get("key") !== env.ADMIN_KEY) {
          return json({ ok: false, error: "unauthorized" }, 401);
        }
        const rep = await env.AVATAR_KV.list({ prefix: "rep:" });
        const out = [];
        for (const k of rep.keys.slice(0, 200)) {
          const val = await env.AVATAR_KV.get(k.name);
          if (!val) continue;
          try {
            const r = JSON.parse(val);
            out.push({ fileId: k.name.slice(4), avatarId: r.avatarId || "", status: r.status || "dead", count: r.count || 0 });
          } catch (_) {}
        }
        return json({ ok: true, reports: out });
      }

      if (req.method === "GET" && url.pathname === "/admin/recent") {
        // Full recent USER contribution batches (who + every avatar name) for the admin's expandable
        // view. Admin-key gated; ONE cheap KV read, only when the admin is looking (not polled by bots).
        if (!env.ADMIN_KEY || url.searchParams.get("key") !== env.ADMIN_KEY) {
          return json({ ok: false, error: "unauthorized" }, 401);
        }
        let recent = []; try { const r = await env.AVATAR_KV.get("recent"); if (r) recent = JSON.parse(r); } catch (_) {}
        return json({ ok: true, recent: Array.isArray(recent) ? recent : [] });
      }

      if (req.method === "GET" && url.pathname === "/admin/reshard") {
        // RECOVERY (replaces the old GitHub restore + migrate): re-shard from the R2 db.json
        // master snapshot (written by the rebuild Action). Guarded by ADMIN_KEY. Processes a
        // hex-prefix RANGE per call so a huge catalog re-shards in chunks:
        //   /admin/reshard?key=KEY&lo=000&hi=0ff   (then 100-1ff, ... up to fff)
        // Use this only if the live shards got corrupted; the db.json backup is the source.
        if (!env.ADMIN_KEY || url.searchParams.get("key") !== env.ADMIN_KEY) return json({ ok: false, error: "unauthorized" }, 401);
        if (!env.CATALOG) return json({ ok: false, error: "R2 not configured" }, 503);
        const lo = (url.searchParams.get("lo") || "000").toLowerCase();
        const hi = (url.searchParams.get("hi") || "fff").toLowerCase();
        const loN = parseInt(lo, 16), hiN = parseInt(hi, 16);
        if (isNaN(loN) || isNaN(hiN) || loN > hiN) return json({ ok: false, error: "bad lo/hi (3 hex each)" }, 400);
        const endN = Math.min(loN + 511, hiN);
        const res = await reshardFromMaster(env, loN, endN);
        if (!res) return json({ ok: false, error: "could not read db.json master from R2" }, 500);
        const nextN = endN + 1;
        const done = nextN > hiN;
        const nextLo = done ? null : nextN.toString(16).padStart(3, "0");
        const origin = `${url.protocol}//${url.host}`;
        const nextUrl = done ? null
          : `${origin}/admin/reshard?key=${encodeURIComponent(url.searchParams.get("key"))}&lo=${nextLo}&hi=${hi}`;
        return json({ ok: true, range: `${lo}-${endN.toString(16).padStart(3, "0")}`,
          shardsWritten: res.written, entriesInRange: res.entries, done, nextLo, nextUrl,
          note: done ? "reshard complete" : "open nextUrl to continue (tap it)" });
      }

      if (req.method === "GET" && url.pathname === "/db") {
        // The R2 master snapshot (written by the rebuild Action) — download/backup. Served
        // straight from R2, no-store so it's always the latest committed master.
        if (!env.CATALOG) return json({ ok: false, error: "R2 not configured" }, 503);
        const obj = await env.CATALOG.get("db.json");
        return new Response(obj ? obj.body : '{"avatars":{}}', {
          status: 200,
          headers: { "content-type": "application/json", "cache-control": "no-store",
            "access-control-allow-origin": "*" },
        });
      }

      if (req.method === "GET" && (url.pathname === "/health" || url.pathname === "/")) {
        // ONE cheap KV read (no list ops — those have a tight free-tier limit and this is
        // polled every 15s). Counts come from meta (set at flush).
        const meta = JSON.parse((await env.AVATAR_KV.get("meta")) || "{}");
        // Prefer the manifest's authoritative entryCount (Action-maintained); fall back to the
        // KV counter. max() so a fresh contribution that bumped KV past the last rebuild still shows.
        const manCount = await manifestEntryCount(env);
        const liveEntries = manCount >= 0 ? Math.max(manCount, meta.entries || 0) : (meta.entries || 0);
        return json({
          ok: true,
          entries: liveEntries,
          pendingBatches: meta.pendingBatches || 0,
          reports: meta.reports || 0,
          lastFlush: meta.lastFlush || null,
          lastAdded: meta.lastAdded || 0,
          lastRemoved: meta.lastRemoved || 0,
          totalAdded: meta.totalAdded || 0,
          totalRemoved: meta.totalRemoved || 0,
          lastCommit: meta.lastCommit || "none",
          adminKeySet: !!env.ADMIN_KEY,
          // Worker-side search-index reconcile (NO GitHub): how far the round-robin shard walk has got
          // (rc = next of 4096), when it last ran, and cumulative shards walked / entries re-indexed.
          reconcileCursor: meta.rc || 0,
          lastReconcile: meta.lastReconcile || null,
          reconcileScanned: meta.reconcileScanned || 0,
          reconcileFixed: meta.reconcileFixed || 0,
          reconcileDone: !!meta.rcDone,          // one-time heal finished → reconciler idle (flush maintains)
          reconcileDoneAt: meta.rcDoneAt || null,
          // Recount-integrity guards (v8+): shards a recount lap couldn't read, retry attempts, and whether
          // the last completed lap SKIPPED adopting its count because it was tainted (kept the incremental).
          rcReadFail: meta.rcReadFail || 0,
          rcAttempts: meta.rcAttempts || 0,
          rcAdoptSkipped: meta.rcAdoptSkipped || 0,
          // Author-rename propagation: the author currently being applied across the catalog (null =
          // idle), its progress this pass, and the last completed one (how many entries it renamed).
          authorRenameActive: meta.arnActive || null,
          authorRenameCursor: meta.arnCursor || 0,
          authorRenameFixed: meta.arnFixed || 0,
          authorRenameLast: meta.arnLast || null,
          authorRenameLastFixed: meta.arnLastFixed || 0,
          authorRenameLastAt: meta.arnLastAt || null,
          purgeConfigured: !!(env.CF_PURGE_TOKEN && env.CF_ZONE_ID),
          // R2 is the only backend now. `catalogBase` is what the app appends
          // /shard/<prefix>.json etc. to (learned from here, so a serving change needs no
          // app update). Defaults to this Worker's /catalog route if no custom domain.
          r2: !!env.CATALOG,
          backend: "r2",
          catalogBase: env.CATALOG_BASE || `https://${url.host}/catalog`,
          shardScheme: "filehex3-full",
          shardCount: 4096,
          version: 12,   // perf-rank changes now persist + re-index (were dropped by entryEquivalent/buildIndexOp)
        });
      }

      if (req.method === "GET" && url.pathname === "/flush") {
        await flushR2(env);
        const meta = JSON.parse((await env.AVATAR_KV.get("meta")) || "{}");
        return json({
          triggered: true,
          lastCommit: meta.lastCommit || "none",
          entries: meta.entries || 0,
          lastAdded: meta.lastAdded || 0,
          lastFlush: meta.lastFlush || null,
        });
      }

      return json({ ok: false, error: "not found" }, 404);
    } catch (e) {
      return json({ ok: false, error: String(e) }, 500);
    }
  },

  async scheduled(event, env, ctx) {
    // Search is fully incremental in flushR2 (no GitHub). reconcileIndex() is the WORKER-side
    // replacement for the Action's full reconcile: it walks the clone shards a few per minute and
    // re-indexes any entry MISSING from the search index (fragments/avtr/index), healing avatars
    // that were cloneable-but-unsearchable because their index op was dropped in the past. No GitHub.
    ctx.waitUntil((async () => { await flushR2(env); await reconcileIndex(env); await propagateAuthorRenames(env); })());
  },
};

// ---- Worker-side search-index reconciler (NO GitHub) ----------------------------------------------
// The incremental flush maintains the index for NEW changes, but nothing repaired avatars whose index
// op was dropped in the past (the GitHub rebuild used to be the only reconciler). This walks the clone
// shards round-robin (a few per cron), and for every entry NOT present in its avtr/ presence bucket,
// enqueues an ADD index op to the iq: queue (drained by the next flushes, TTL-free so never lost).
// This is a ONE-TIME heal: it walks all 4096 clone shards ONCE (~a day at 4/min), re-indexing the
// avatars whose index op was dropped in the past, then STOPS (meta.rcDone) — ongoing indexing of NEW
// avatars is handled by the incremental flush (with the now-durable, TTL-free iq: queue), so there's
// no reason to keep reading R2 forever. Re-run it any time with GET /admin/reconcile?key=… (resets the
// cursor) if a future audit ever suspects drift. Bounded per run: RECONCILE_SHARDS_PER_RUN shard reads
// + one avtr/ read per distinct id-bucket seen (cached within the run).
const RECONCILE_SHARDS_PER_RUN = 8;   // one-time pass → go a bit faster (~8.5h) then STOP; stays under
                                      // the subrequest budget alongside flushR2
const RC_MAX_ATTEMPTS = 3;            // retry a tainted (read-failed) count pass this many times, then
                                      // give up and keep the incremental count (never adopt a bad one)
// PERIODIC RE-ARM: the incremental `unfilled`/`entries` counts DRIFT over time (a fill that doesn't
// decrement, a contribution counted unfilled but filled elsewhere), and a drifted `unfilled` makes the
// FILL bots blind-walk the WHOLE catalog forever chasing a PHANTOM backlog they can never drain
// ("shards 7000+, filled 0, checked 0, queued 74 stuck"). The one-time heal corrected it once then set
// rcDone; after that the count could drift again with nothing to fix it. So re-arm the recount every
// RECONCILE_REARM_MS: it re-walks the catalog, recomputes the TRUE entries/unfilled, and adopts them on
// a clean lap — snapping "queued 74" back to reality so the bots stop chasing ghosts. Cheap: shard reads
// (Class B) + zero index ops when the index is already healthy.
// Cost note: a recount lap is ~4,100 R2 CLASS B reads (the near-free tier — the same reads a clone
// lookup uses) + ~1 manifest write; ZERO Class A writes on a healthy index (index ops are enqueued only
// for genuinely-broken entries, which is the repair you'd want). That's ~$0.002 per lap. At 30 days it's
// far under a cent a month — nowhere near the Class A write cost that caused the bill.
const RECONCILE_REARM_MS = 30 * 24 * 60 * 60 * 1000;   // 30 days (matches the recheck cadence)
async function reconcileIndex(env) {
  const meta = JSON.parse((await env.AVATAR_KV.get("meta")) || "{}");
  // Re-arm a completed heal once it's older than the interval, so the count is periodically re-truthed.
  if (meta.rcDone) {
    const doneMs = meta.rcDoneAt ? (Date.parse(meta.rcDoneAt) || 0) : 0;
    if (doneMs && Date.now() - doneMs > RECONCILE_REARM_MS) {
      meta.rcDone = false;
      meta.rc = 0; meta.reconcileScanned = 0; meta.rcEntries = 0; meta.rcUnfilled = 0;
      meta.rcReadFail = 0; meta.rcAttempts = 0;
      // fall through → run a fresh recount lap this run
    } else {
      return;   // heal current + within the window → the incremental flush maintains the index
    }
  }
  let cursor = (typeof meta.rc === "number" ? meta.rc : 0) & 0xfff;
  const avtrCache = {};        // id-bucket -> Set(ids present in the search index)
  const missing = [];          // ADD index ops for entries not yet indexed
  // Recount the AUTHORITATIVE entry + unfilled totals as we read every shard, to correct the running
  // incremental counts that drift with no full rebuild — a drifted `unfilled` makes the FILL bots
  // churn the whole catalog forever on a phantom backlog they can never drain ("queued 247, checked
  // 0, stuck at a random number"). Accumulated across the pass into meta, adopted authoritatively on
  // completion. (Slightly high in the rare real-backlog case since fills happen during the ~day pass,
  // but for a PHANTOM count it finds the true near-zero and fixes the churn.)
  let entriesSeen = 0, unfilledSeen = 0, stepped = 0, readFail = 0;
  for (let n = 0; n < RECONCILE_SHARDS_PER_RUN; n++) {
    const prefix = cursor.toString(16).padStart(3, "0");
    cursor = (cursor + 1) & 0xfff;   // 0..4095 wrap
    // Distinguish a genuinely-ABSENT/empty shard (0 entries, fine to count as 0) from a transient
    // READ FAILURE (the object exists but the GET/parse threw). A read failure means we'd UNDER-count
    // that shard's ~32 avatars — the recount must NOT be adopted as authoritative if any occurred, or
    // the admin sees a phantom "lost N avatars" drop (the count is display-only; the shards are intact).
    let shard = null, failed = false;
    try { const o = await env.CATALOG.get(`shard/${prefix}.json`); if (o) shard = await o.json(); }
    catch (_) { failed = true; }
    stepped++;                             // one shard VISITED (completion is a full 4096-step lap)
    if (failed) { readFail++; continue; }  // undercount risk — tallied, pass won't be adopted if >0
    if (!shard || !shard.e) continue;      // genuinely absent/empty → 0 entries (fine)
    for (const [fid, e] of Object.entries(shard.e)) {
      const id = e && e.id;
      if (!id || !id.startsWith("avtr_")) continue;
      entriesSeen++;
      if (e.filled !== true) unfilledSeen++;
      const b = fragBucketFor(id);
      if (!(b in avtrCache)) {
        let ids = new Set();
        try { const o = await env.CATALOG.get(`avtr/${b}.json`); if (o) { const j = await o.json(); if (Array.isArray(j.ids)) ids = new Set(j.ids); } } catch (_) {}
        avtrCache[b] = ids;
      }
      if (!avtrCache[b].has(id)) {
        const op = buildIndexOp(null, e, fid);   // ADD: writes fragment + avtr + tokens
        if (op) { missing.push(op); avtrCache[b].add(id); }   // add to cache so siblings this run aren't re-flagged
      }
    }
  }
  // Enqueue the repairs (TTL-free) — the normal flush drains them via applyIndexOps.
  for (let i = 0; i < missing.length; i += MAX_INDEX_OPS_PER_FLUSH)
    await env.AVATAR_KV.put("iq:" + crypto.randomUUID(), JSON.stringify(missing.slice(i, i + MAX_INDEX_OPS_PER_FLUSH)));
  meta.rc = cursor;
  meta.lastReconcile = new Date().toISOString();
  // reconcileScanned now counts STEPS (shards visited), so completion is exactly one 4096-shard lap —
  // no re-lapping / double-counting even if some reads failed (the old `scanned` skipped failed/empty
  // shards, so failures made the count fall short → the cursor re-lapped and re-counted, corrupting
  // the recount).
  meta.reconcileScanned = (meta.reconcileScanned || 0) + stepped;
  meta.reconcileFixed = (meta.reconcileFixed || 0) + missing.length; // entries re-indexed this pass
  meta.rcEntries = (meta.rcEntries || 0) + entriesSeen;             // authoritative recount (this pass)
  meta.rcUnfilled = (meta.rcUnfilled || 0) + unfilledSeen;
  meta.rcReadFail = (meta.rcReadFail || 0) + readFail;              // shards we couldn't read this pass
  // One full 4096-step lap → decide. ONLY adopt the recomputed counts when the whole lap read cleanly
  // (rcReadFail === 0) — a tainted pass would UNDER-count and show a phantom "lost N avatars" drop
  // (the shards themselves are never touched by reconcile; this is a display/backlog counter only).
  // On a tainted lap, retry a fresh clean pass up to RC_MAX_ATTEMPTS; if it still can't get a clean
  // read, STOP and keep the running incremental count (never adopt a bad number).
  if (meta.reconcileScanned >= 4096) {
    const clean = (meta.rcReadFail || 0) === 0;
    const attempts = (meta.rcAttempts || 0);
    if (clean) {
      meta.rcDone = true; meta.rcDoneAt = new Date().toISOString();
      meta.entries = meta.rcEntries || 0;
      meta.unfilled = meta.rcUnfilled || 0;
      meta.rcAdoptSkipped = 0;
      // Push the corrected counts straight to the manifest (what the admin/bots read) + purge.
      try {
        let man = {}; const m = await env.CATALOG.get("_manifest.json"); if (m) man = await m.json();
        man = { ...man, v: 1, shardScheme: "filehex3-full", shardCount: 4096, indexScheme: "hash3",
          entryCount: meta.entries, unfilled: meta.unfilled, searchReady: true, lastUpdate: new Date().toISOString() };
        await env.CATALOG.put("_manifest.json", JSON.stringify(man),
          { httpMetadata: { contentType: "application/json", cacheControl: "public, max-age=30" } });
        if (env.CATALOG_BASE) await purgeCatalogUrls(env, [env.CATALOG_BASE.replace(/\/$/, "") + "/_manifest.json"]);
      } catch (_) {}
      meta.rc = 0; meta.reconcileScanned = 0; meta.rcEntries = 0; meta.rcUnfilled = 0;
      meta.rcReadFail = 0; meta.rcAttempts = 0;   // reset accumulators for a future re-arm
    } else if (attempts < RC_MAX_ATTEMPTS) {
      // Tainted lap → retry a fresh full pass (index repairs already enqueued above are idempotent).
      meta.rcAttempts = attempts + 1;
      meta.rc = 0; meta.reconcileScanned = 0; meta.rcEntries = 0; meta.rcUnfilled = 0; meta.rcReadFail = 0;
      // rcDone stays false → the next cron re-walks the whole catalog for a clean count.
    } else {
      // Couldn't get a clean read after the retries → give up and KEEP the incremental count
      // (never overwrite it with an under-counted recount). Re-arm manually later if desired.
      meta.rcDone = true; meta.rcDoneAt = new Date().toISOString();
      meta.rcAdoptSkipped = (meta.rcReadFail || 0);
      meta.rc = 0; meta.reconcileScanned = 0; meta.rcEntries = 0; meta.rcUnfilled = 0;
      meta.rcReadFail = 0; meta.rcAttempts = 0;
    }
  }
  await env.AVATAR_KV.put("meta", JSON.stringify(meta));
}

// ---- AUTHOR-RENAME propagation ------------------------------------------------------------------
// When a creator changes their VRChat display name, every catalog entry by them still stores the OLD
// `author`. The bots detect it authoritatively (their GET /avatars/{id} is real VRChat data) and queue
// `arn:<authorId>` = newName. This walks the catalog (bounded per run, ONE author at a time, keyed on
// the immutable authorId) and rewrites `author` on every matching entry — reusing buildIndexOp so the
// search index (fragment `au` + author tokens) updates through the normal iq: pipeline. Dirty-only
// writes; runs ONLY while a rename is pending, so steady-state cost is zero. Rare event → slow is fine.
const RENAME_SHARDS_PER_RUN = 8;   // ~8.5h to sweep the whole catalog for one author's rename
async function propagateAuthorRenames(env) {
  const meta = JSON.parse((await env.AVATAR_KV.get("meta")) || "{}");
  // Continue the active rename, or pick the next pending one (nothing pending → zero cost, just a list).
  if (!meta.arnActive) {
    const list = await env.AVATAR_KV.list({ prefix: "arn:", limit: 1 });
    if (!list.keys || list.keys.length === 0) return;
    const authorId = list.keys[0].name.slice(4);
    const newName = await env.AVATAR_KV.get("arn:" + authorId);
    if (!newName) { await env.AVATAR_KV.delete("arn:" + authorId); return; }  // empty/stale → drop
    meta.arnActive = authorId; meta.arnName = newName;
    meta.arnCursor = 0; meta.arnStepped = 0; meta.arnFixed = 0;
  }
  const authorId = meta.arnActive, newName = meta.arnName;
  let cursor = (typeof meta.arnCursor === "number" ? meta.arnCursor : 0) & 0xfff;
  const indexOps = [], purge = [];
  for (let n = 0; n < RENAME_SHARDS_PER_RUN; n++) {
    const prefix = cursor.toString(16).padStart(3, "0");
    cursor = (cursor + 1) & 0xfff;
    meta.arnStepped = (meta.arnStepped || 0) + 1;
    let shard = null;
    try { const o = await env.CATALOG.get(`shard/${prefix}.json`); if (o) shard = await o.json(); } catch (_) { continue; }
    if (!shard || !shard.e) continue;
    let dirty = false;
    for (const [fid, e] of Object.entries(shard.e)) {
      if (e && e.authorId === authorId && e.author !== newName) {
        const before = { ...e };
        e.author = newName;
        dirty = true;
        meta.arnFixed = (meta.arnFixed || 0) + 1;
        const op = buildIndexOp(before, e, fid);   // author is search-relevant → updates fragment + tokens
        if (op) indexOps.push(op);
      }
    }
    if (dirty) {
      try {
        await env.CATALOG.put(`shard/${prefix}.json`, JSON.stringify({ v: 1, e: shard.e }), {
          httpMetadata: { contentType: "application/json", cacheControl: "public, max-age=" + SHARD_TTL },
        });
        purge.push(prefix);
      } catch (_) {}
    }
  }
  for (let i = 0; i < indexOps.length; i += MAX_INDEX_OPS_PER_FLUSH)
    await env.AVATAR_KV.put("iq:" + crypto.randomUUID(), JSON.stringify(indexOps.slice(i, i + MAX_INDEX_OPS_PER_FLUSH)));
  if (purge.length) await purgeShards(env, purge);
  meta.arnCursor = cursor;
  // One full 4096-step lap for this author → done: drop the pending key + clear the active slot so the
  // next run picks the next queued rename.
  if ((meta.arnStepped || 0) >= 4096) {
    await env.AVATAR_KV.delete("arn:" + authorId);
    meta.arnLast = authorId; meta.arnLastFixed = meta.arnFixed || 0; meta.arnLastAt = new Date().toISOString();
    meta.arnActive = null; meta.arnName = null; meta.arnCursor = 0; meta.arnStepped = 0; meta.arnFixed = 0;
  }
  await env.AVATAR_KV.put("meta", JSON.stringify(meta));
}

// GitHub's own `schedule:` cron is best-effort — it drops/delays most runs (observed 10–12h
// gaps), so the search index went stale. Cloudflare's cron IS reliable, so the Worker triggers
// the rebuild itself via workflow_dispatch every REBUILD_INTERVAL_MS. Needs a fine-grained GitHub
// token (Actions: write on the repo) as the GH_DISPATCH_TOKEN secret + GH_OWNER/GH_REPO/GH_REF
// vars. Unset → no-op (falls back to GitHub's flaky cron). Cost: 1 KV read/min + 1 KV write +
// 1 GitHub API call per interval.
const REBUILD_INTERVAL_MS = 20 * 60_000;
async function maybeDispatchRebuild(env) {
  if (!env.GH_DISPATCH_TOKEN || !env.GH_OWNER || !env.GH_REPO) return;
  let last = 0;
  try { last = parseInt((await env.AVATAR_KV.get("last_rebuild_ms")) || "0", 10) || 0; } catch (_) {}
  const now = Date.now();
  if (now - last < REBUILD_INTERVAL_MS) return;
  await env.AVATAR_KV.put("last_rebuild_ms", String(now));   // claim the slot so we don't double-fire
  const ref = env.GH_REF || "main";
  const stampMeta = async (patch) => {
    try { const m = JSON.parse((await env.AVATAR_KV.get("meta")) || "{}"); await env.AVATAR_KV.put("meta", JSON.stringify({ ...m, ...patch })); } catch (_) {}
  };
  try {
    const r = await fetch(
      `https://api.github.com/repos/${env.GH_OWNER}/${env.GH_REPO}/actions/workflows/catalog-rebuild.yml/dispatches`,
      {
        method: "POST",
        headers: {
          "Authorization": `Bearer ${env.GH_DISPATCH_TOKEN}`,
          "Accept": "application/vnd.github+json",
          "X-GitHub-Api-Version": "2022-11-28",
          "User-Agent": "vrca-worker",
        },
        body: JSON.stringify({ ref }),
      }
    );
    if (!r.ok) {
      const body = (await r.text().catch(() => "")).slice(0, 200);
      await stampMeta({ lastRebuildError: `${r.status} ${body}` });
      await env.AVATAR_KV.put("last_rebuild_ms", String(last));   // failed → retry next minute
      return;
    }
    await stampMeta({ lastRebuildAt: new Date(now).toISOString(), lastRebuildError: null });
  } catch (e) {
    await stampMeta({ lastRebuildError: `fetch: ${String(e).slice(0, 180)}` });
    await env.AVATAR_KV.put("last_rebuild_ms", String(last));
  }
}

// ---- R2 per-shard flush -----------------------------------------------------
// Read only the shards touched by pending ops, merge, write them back. The whole catalog is
// NEVER in memory at once, so there's no CPU/memory wall regardless of size. Contributions/
// admin-upserts arrive already cleanEntry'd (stamped by /contribute + /admin).
// List every KV key under a prefix, following the cursor. The OLD code did ONE un-paginated
// AVATAR_KV.list() (max 1000 keys, lexicographic) and filtered — so once the `iq:` index-queue grew
// past 1000 keys it filled the whole window and, because `iq:` sorts BEFORE `pend:`/`rep:`, the
// pending USER batches (and reports) fell outside it and were NEVER seen: the flush ran, added 0, and
// the batches sat forever ("pending batches stuck at N", while the `adm*` admin keys — which sort
// first — still processed, so the bots kept moving). Listing each prefix on its OWN cursor can never
// be crowded out. `cap` bounds pagination (we only need a few of some prefixes).
async function listPrefix(env, prefix, cap = 5000) {
  const out = []; let cursor;
  do {
    const r = await env.AVATAR_KV.list({ prefix, cursor });
    for (const k of r.keys) out.push(k.name);
    cursor = r.list_complete ? null : r.cursor;
  } while (cursor && out.length < cap);
  return out;
}

async function flushR2(env) {
  const prevMeta = JSON.parse((await env.AVATAR_KV.get("meta")) || "{}");
  const pendNames = await listPrefix(env, "pend:");
  const repNames  = await listPrefix(env, "rep:");
  const admuNames = await listPrefix(env, "admu:");
  const admrNames = await listPrefix(env, "admr:");
  const admkNames = await listPrefix(env, "admk:");
  const hasIndexQueue = (await env.AVATAR_KV.list({ prefix: "iq:", limit: 1 })).keys.length > 0;

  // Nothing to do → skip the whole flush (no shard reads, no meta write). This stops the
  // every-minute cron from writing `meta` ~1440×/day while the catalog is idle. Reports and the
  // index carry-forward queue keep the flush active only while any exist; a fully-idle catalog
  // costs just one KV list per minute.
  if (!pendNames.length && !admuNames.length && !admrNames.length && !admkNames.length &&
      !repNames.length && !hasIndexQueue) return;

  // Group every pending op by shard prefix so each shard is read + written ONCE.
  const shardOps = {};
  const S = (sp) => (shardOps[sp] ||= { adds: {}, upserts: {}, removes: new Set(), checked: new Set(), renames: {} });

  const pendKeys = [];
  const recentBatches = [];   // admin "recent contributions" view (free: built from data already read)
  // Consume pending USER batches only until MAX_SHARDS_PER_FLUSH distinct shards are queued, then STOP
  // (leave the rest for the next 1-min flush) so a big burst can't blow the subrequest limit and wedge
  // the queue forever. A deferred batch is NOT added to pendKeys, so it isn't deleted → it drains next
  // flush. Always take at least the first batch so a single huge batch can't get stuck.
  // SHARED shard budget across pending USER batches AND admin/bot pushes: each distinct shard is 1 R2
  // read + 1 write, so the TOTAL touched per flush must stay under Cloudflare's per-invocation limit or
  // the whole invocation dies before writing meta (the "lastFlush frozen for hours / batches stuck"
  // symptom — the observed dying flush touched 214 shards). reserve(fids) returns false when this key's
  // shards would exceed the budget; the caller then DEFERS it (leaves its key) for the next 1-min flush.
  const touchedShards = new Set();
  const reserve = (fids) => {
    const p = new Set(); for (const fid of fids) if (FILE_RE.test(fid)) p.add(shardPrefix(fid));
    let fresh = 0; for (const sp of p) if (!touchedShards.has(sp)) fresh++;
    if (touchedShards.size > 0 && touchedShards.size + fresh > MAX_SHARDS_PER_FLUSH) return false;
    for (const sp of p) touchedShards.add(sp);
    return true;
  };
  for (const kn of pendNames) {
    const val = await env.AVATAR_KV.get(kn);
    if (!val) { pendKeys.push(kn); continue; }                                 // empty → clear it
    let batch; try { batch = JSON.parse(val); } catch (_) { pendKeys.push(kn); continue; }  // garbage → clear it
    const fids = Object.keys(batch).filter((fid) => FILE_RE.test(fid));
    if (!reserve(fids)) break;                                                 // over budget → defer rest
    pendKeys.push(kn);
    for (const fid of fids) S(shardPrefix(fid)).adds[fid] = batch[fid];
    if (fids.length) recentBatches.push({
      ts: typeof batch.__ts === "number" ? batch.__ts : Date.now(),
      by: typeof batch.__by === "string" ? batch.__by : "",
      n: fids.length,
      // Keep ALL names in the batch (a batch is already capped at 200 by /contribute) so the admin
      // can EXPAND a row to see every avatar. Stored in a DEDICATED `recent` key, not meta, so the
      // frequently-polled /health stays tiny.
      names: fids.map((fid) => (batch[fid] && batch[fid].name) || "").filter(Boolean),
    });
  }
  const admuKeys = [];
  for (const kn of admuNames) {
    const val = await env.AVATAR_KV.get(kn);
    if (!val) { admuKeys.push(kn); continue; }
    let batch; try { batch = JSON.parse(val); } catch (_) { admuKeys.push(kn); continue; }
    const fids = Object.keys(batch).filter((fid) => FILE_RE.test(fid));
    if (!reserve(fids)) break;   // over budget → defer to next flush
    admuKeys.push(kn);
    for (const fid of fids) S(shardPrefix(fid)).upserts[fid] = batch[fid];
  }
  const admrKeys = [];
  for (const kn of admrNames) {
    const val = await env.AVATAR_KV.get(kn);
    if (!val) { admrKeys.push(kn); continue; }
    let arr; try { arr = JSON.parse(val); } catch (_) { admrKeys.push(kn); continue; }
    const fids = arr.filter((f) => typeof f === "string" && f.startsWith("file_"));
    if (!reserve(fids)) break;   // over budget → defer to next flush
    admrKeys.push(kn);
    for (const fid of fids) S(shardPrefix(fid)).removes.add(fid);
  }
  const admkKeys = [];
  for (const kn of admkNames) {
    const val = await env.AVATAR_KV.get(kn);
    if (!val) { admkKeys.push(kn); continue; }
    let arr; try { arr = JSON.parse(val); } catch (_) { admkKeys.push(kn); continue; }
    const fids = arr.filter((f) => typeof f === "string" && f.startsWith("file_"));
    if (!reserve(fids)) break;   // over budget → defer to next flush
    admkKeys.push(kn);
    for (const fid of fids) S(shardPrefix(fid)).checked.add(fid);
  }
  // Reports: rename immediately, remove on quorum; both clear their rep: key. A below-quorum "dead"
  // report whose avatar is ALREADY GONE from the catalog is MOOT — clear it so it doesn't sit pending
  // forever (the "a few reports that never go away" case: a 2nd report already caused the removal, or
  // the liveness bot culled it, or it was never in the catalog). A shard READ we can't do (over budget
  // / read failed) leaves the report untouched so nothing is dropped on uncertainty.
  const repClear = [];
  const repShardCache = {};   // prefix -> entries map (null = read failed/unknown → don't moot-clear)
  const repShardEntries = async (prefix) => {
    if (prefix in repShardCache) return repShardCache[prefix];
    let e = null;
    try { const o = await env.CATALOG.get(`shard/${prefix}.json`); e = o ? ((await o.json()).e || {}) : {}; } catch (_) { e = null; }
    repShardCache[prefix] = e; return e;
  };
  for (const kn of repNames) {
    const fid = kn.slice(4);
    if (!fid.startsWith("file_")) continue;
    const val = await env.AVATAR_KV.get(kn);
    if (!val) continue;
    let r; try { r = JSON.parse(val); } catch (_) { continue; }
    if (r.status === "renamed" && r.name) {
      if (!reserve([fid])) break;                                   // shard WRITE → budget
      S(shardPrefix(fid)).renames[fid] = String(r.name).slice(0, 100); repClear.push(kn);
    } else if (r.status === "dead" && (r.count || 0) >= REMOVE_QUORUM) {
      if (!reserve([fid])) break;                                   // shard WRITE → budget
      S(shardPrefix(fid)).removes.add(fid); repClear.push(kn);
    } else if (r.status === "dead" && (Object.keys(repShardCache).length < 30 || shardPrefix(fid) in repShardCache)) {
      // Below quorum: normally waits for a 2nd report or the bot. Drain it here ONLY if the avatar is
      // no longer in the catalog (nothing to remove) — a shard READ, no write, so no budget reserve.
      // Capped at ~30 distinct shard reads/flush so a big report backlog can't blow the subrequest
      // budget; the rest are checked over subsequent flushes.
      const ent = await repShardEntries(shardPrefix(fid));
      if (ent && !ent[fid]) repClear.push(kn);
    }
  }

  const nowChecked = Date.now();
  let added = 0, removed = 0, allShardsOk = true;
  let unfilledDelta = 0;      // incremental Fill-backlog delta (Worker owns the count now)
  const indexOps = [];        // search-index ops computed from the SAME shard read (no re-fetch)
  const fillHintAdd = new Set();   // touched shards that still have an unfilled avatar
  const fillHintDone = new Set();  // touched shards that are now fully filled
  const prefixes = Object.keys(shardOps);
  const dirtyPrefixes = [];        // shards we actually REWROTE
  const purgePrefixes = [];        // shards whose SERVED content changed (only these need a CDN purge —
                                   // a `checked`-only rewrite doesn't change what a clone/search read sees)
  for (const sp of prefixes) {
    const ops = shardOps[sp];
    let cur;
    try {
      const obj = await env.CATALOG.get(`shard/${sp}.json`);
      cur = obj ? await obj.json() : { v: 1, e: {} };
      if (!cur || typeof cur !== "object" || typeof cur.e !== "object" || cur.e === null) cur = { v: 1, e: {} };
    } catch (_) { allShardsOk = false; continue; } // read failed -> skip (never wipe), retry next flush
    const e = cur.e;
    // Track whether this shard's CONTENT actually changed. A flush where every op is a no-op
    // (harvest re-sending already-known avatars → all adds are dupes; a `checked` bump for an
    // avatar no longer in the shard) must NOT rewrite the shard — an unconditional put was the
    // single biggest wasted R2 Class A write, since the harvest re-contributes thousands of
    // already-present avatars. Only put when `dirty`.
    // Per-shard tallies. These fold into the global added/removed/unfilledDelta ONLY after this
    // shard's write SUCCEEDS — so a write that fails (KV not cleared → retried next flush) can never
    // bump `entries` for a shard that didn't persist and then get RE-counted on the retry. That
    // double-count on partial failures was a source of the running count drifting off exact.
    // `dirty` = the shard must be REWRITTEN; `contentDirty` = its SERVED content changed so the edge
    // cache must be PURGED. A `checked`-only bump sets `dirty` (the timestamp must persist for sweep
    // pacing) but NOT `contentDirty` (no clone/search reader consults `checked`), so it never wastes a
    // purge — the biggest recurring purge saving at the 30d recheck cadence.
    let dirty = false, contentDirty = false, sAdded = 0, sRemoved = 0, sUnfilled = 0;
    const sIndexOps = [];
    for (const fid of Object.keys(ops.adds)) if (!e[fid]) {
      const ne = ops.adds[fid]; e[fid] = ne; sAdded++; dirty = true; contentDirty = true;
      const op = buildIndexOp(null, ne, fid); if (op) sIndexOps.push(op);
      if (ne.filled !== true) sUnfilled++;
    }
    for (const fid of Object.keys(ops.upserts)) {
      const inc = ops.upserts[fid];
      const prev = e[fid];
      if (prev && typeof prev.added === "number") inc.added = prev.added; // `added` is immutable
      else if (!prev) sAdded++;
      if (!prev) { if (inc.filled !== true) sUnfilled++; }
      else { const wasUnfilled = prev.filled !== true, nowUnfilled = inc.filled !== true;
        if (wasUnfilled && !nowUnfilled) sUnfilled--; else if (!wasUnfilled && nowUnfilled) sUnfilled++; }
      // An upsert that changes nothing material (same name/author/authorId/platforms/bio/filled)
      // shouldn't rewrite the shard either. entryEquivalent compares the persisted fields.
      if (!prev || !entryEquivalent(prev, inc)) {
        const op = buildIndexOp(prev || null, inc, fid); if (op) sIndexOps.push(op);
        e[fid] = inc; dirty = true; contentDirty = true;
      }
    }
    for (const fid of Object.keys(ops.renames)) if (e[fid] && e[fid].name !== ops.renames[fid]) {
      const prev = { ...e[fid] }; e[fid].name = ops.renames[fid]; dirty = true; contentDirty = true;
      const op = buildIndexOp(prev, e[fid], fid); if (op) sIndexOps.push(op);
    }
    // `checked` bumps DO dirty the shard — the timestamp must persist so the liveness sweep can
    // pace itself (an un-persisted `checked` would leave the avatar perpetually stale → re-checked
    // every pass forever). The write volume is instead bounded by the RECHECK_INTERVAL_MS (widened
    // to 30d in the app): at 30d, re-verifying the whole catalog is ~1 shard write per avatar per
    // month — well under the R2 free tier — instead of the old 7d cadence's ~4x churn.
    for (const fid of ops.checked) if (e[fid]) { e[fid].checked = nowChecked; dirty = true; }
    for (const fid of ops.removes) if (e[fid]) {
      const prev = e[fid]; delete e[fid]; sRemoved++; dirty = true; contentDirty = true;
      const op = buildIndexOp(prev, null, fid); if (op) sIndexOps.push(op);
      if (prev.filled !== true) sUnfilled--;
    }
    // Bot FILL-hint (replaces the Action's worklist): does this shard, AFTER the ops, still hold
    // an unfilled avatar? Accurate + cheap (the shard is already in memory). Drives _worklist.json.
    if (Object.values(e).some((x) => x && x.filled !== true)) fillHintAdd.add(sp);
    else fillHintDone.add(sp);
    if (!dirty) continue;   // nothing changed → skip the R2 write (and the purge below)
    let wrote = false;
    try {
      await env.CATALOG.put(`shard/${sp}.json`, JSON.stringify({ v: 1, e }), {
        httpMetadata: { contentType: "application/json", cacheControl: "public, max-age=" + SHARD_TTL },
      });
      wrote = true;
    } catch (_) { allShardsOk = false; }
    if (wrote) {   // fold the count deltas + index ops ONLY now that the shard actually persisted
      dirtyPrefixes.push(sp);
      if (contentDirty) purgePrefixes.push(sp);   // checked-only writes persist but skip the purge
      added += sAdded; removed += sRemoved; unfilledDelta += sUnfilled;
      for (const op of sIndexOps) indexOps.push(op);
    }
  }

  // Purge just the shards whose SERVED content changed (adds/upserts/renames/removes) so a new avatar
  // goes live within ~seconds instead of the TTL. A `checked`-only rewrite is skipped — a stale cached
  // copy is functionally identical for clone/search (no reader consults `checked`). No-op unless a
  // purge token is configured.
  if (allShardsOk && purgePrefixes.length > 0) await purgeShards(env, purgePrefixes);

  // FULL incremental SEARCH INDEX (add / rename / remove + avtr presence) — computed above from the
  // SAME shard reads (no re-fetch). Drain any carried-over ops first, then this flush's, up to the
  // per-flush cap; the remainder carries forward in an `iq:` queue and drains over the next flushes,
  // so a big burst never drops and subrequests stay bounded. Only runs when shards wrote OK (so we
  // index exactly what persisted; a failed apply re-queues everything and re-applies idempotently).
  if (allShardsOk) {
    const iqNames = await listPrefix(env, "iq:", 1000);   // own cursor (never crowded out); we drain only a few
    let queued = []; const drained = [];
    for (const kn of iqNames) {
      if (queued.length >= MAX_INDEX_OPS_PER_FLUSH) break;
      const val = await env.AVATAR_KV.get(kn); drained.push(kn);
      if (val) try { const a = JSON.parse(val); if (Array.isArray(a)) queued.push(...a); } catch (_) {}
    }
    const all = [...queued, ...indexOps];
    const toApply = all.slice(0, MAX_INDEX_OPS_PER_FLUSH);
    let applied = true;
    if (toApply.length) {
      try { const urls = await applyIndexOps(env, toApply); if (urls.length) await purgeCatalogUrls(env, urls); }
      catch (_) { applied = false; }
    }
    for (const kn of drained) await env.AVATAR_KV.delete(kn);
    const requeue = applied ? all.slice(MAX_INDEX_OPS_PER_FLUSH) : all;   // on failure retry all (idempotent)
    for (let i = 0; i < requeue.length; i += MAX_INDEX_OPS_PER_FLUSH)
      // NO expiry: a search-index op is the ONLY thing that makes an avatar searchable, so it must
      // NEVER be dropped. The old 7-day TTL silently EXPIRED queued ops whenever the backlog outlived
      // it (heavy harvesting, or while the full rebuild was down), leaving avatars cloneable-but-
      // unsearchable forever. The queue self-drains as flushes catch up; the paginated listPrefix read
      // keeps it from crowding out pend:/rep:, and the full rebuild reconciles fragments/index from the
      // clone shards, so an un-drained op is at worst redundant, never lost.
      await env.AVATAR_KV.put("iq:" + crypto.randomUUID(),
        JSON.stringify(requeue.slice(i, i + MAX_INDEX_OPS_PER_FLUSH)));
  }

  // Clear KV only when every touched shard wrote OK (idempotent retry otherwise — nothing lost).
  if (allShardsOk) {
    for (const n of pendKeys) await env.AVATAR_KV.delete(n);
    for (const n of admuKeys) await env.AVATAR_KV.delete(n);
    for (const n of admrKeys) await env.AVATAR_KV.delete(n);
    for (const n of admkKeys) await env.AVATAR_KV.delete(n);
    for (const n of repClear) await env.AVATAR_KV.delete(n);
  }

  // Manifest — the WORKER now owns it (search + counts are fully incremental, no Action needed).
  // entryCount and unfilled move by the deltas computed above; searchReady is always true. Written
  // only when something moved; short TTL + purge so the fresh number is live in ~1s. If a full
  // rebuild is ever run as an optional backstop, its authoritative counts are adopted once.
  const countMoved = added > 0 || removed > 0 || unfilledDelta !== 0;
  let entries = Math.max(0, (prevMeta.entries || 0) + added - removed);
  let unfilled = typeof prevMeta.unfilled === "number" ? prevMeta.unfilled : 0;
  let adoptedRebuild = prevMeta.adoptedRebuild || null;
  let manifestWritten = false;
  if (countMoved) {
    // Decide whether to freshen _manifest.json THIS flush (coalesced — see MANIFEST_MIN_* above). The
    // running unfilled tracks in meta regardless; the manifest read + adopt only run when we actually
    // write, so a skipped flush also saves the manifest Class B read.
    const sinceManifest = nowChecked - (prevMeta.lastManifestMs || 0);
    const entryDelta = Math.abs(entries - (typeof prevMeta.lastManifestEntries === "number" ? prevMeta.lastManifestEntries : 0));
    const writeManifest = !prevMeta.lastManifestMs || sinceManifest >= MANIFEST_MIN_INTERVAL_MS || entryDelta >= MANIFEST_MIN_DELTA;
    unfilled = Math.max(0, unfilled + unfilledDelta);
    if (writeManifest) {
      let man = {};
      try { const m = await env.CATALOG.get("_manifest.json"); if (m) man = await m.json(); } catch (_) {}
      if (!man || typeof man !== "object") man = {};
      // A full rebuild (optional backstop) publishes authoritative counts once — adopt them.
      if (man.lastFullRebuild && man.lastFullRebuild !== adoptedRebuild && typeof man.entryCount === "number") {
        entries = Math.max(0, man.entryCount + added - removed);
        if (typeof man.unfilled === "number") unfilled = Math.max(0, man.unfilled + unfilledDelta);
        adoptedRebuild = man.lastFullRebuild;
      }
      man = { ...man, v: 1, shardScheme: "filehex3-full", shardCount: 4096, indexScheme: "hash3",
        entryCount: entries, unfilled, searchReady: true, lastUpdate: new Date().toISOString() };
      try {
        await env.CATALOG.put("_manifest.json", JSON.stringify(man), {
          httpMetadata: { contentType: "application/json", cacheControl: "public, max-age=30" },
        });
        manifestWritten = true;
      } catch (_) {}
      if (manifestWritten && allShardsOk && env.CATALOG_BASE) {
        await purgeCatalogUrls(env, [env.CATALOG_BASE.replace(/\/$/, "") + "/_manifest.json"]);
      }
    }
  }

  // Bot FILL worklist (replaces the Action's _worklist.json): the running set of shard prefixes that
  // still hold an unfilled avatar, maintained incrementally from the accurate per-shard check above.
  // The bots read this to fill NEW avatars promptly; liveness is left to their oldest-swept walk.
  let fillHint = Array.isArray(prevMeta.fillHint) ? prevMeta.fillHint : null;
  let seeded = false;
  if (fillHint === null) {   // first run after this change: seed from the existing worklist so the
    try { const w = await env.CATALOG.get("_worklist.json"); if (w) { const j = await w.json(); if (Array.isArray(j.fill)) fillHint = j.fill; } } catch (_) {}
    if (fillHint === null) fillHint = [];   // current ~12k-unfilled backlog is preserved, then maintained
    seeded = true;
  }
  let hintChanged = false;
  if (allShardsOk) {
    const s = new Set(fillHint);
    for (const p of fillHintAdd) if (!s.has(p)) { s.add(p); hintChanged = true; }
    for (const p of fillHintDone) if (s.has(p)) { s.delete(p); hintChanged = true; }
    if (hintChanged) {
      fillHint = Array.from(s).slice(0, 4096);
      try {
        await env.CATALOG.put("_worklist.json",
          JSON.stringify({ v: 1, ts: new Date().toISOString(), fill: fillHint, stale: [] }),
          { httpMetadata: { contentType: "application/json", cacheControl: "public, max-age=30" } });
        if (env.CATALOG_BASE) await purgeCatalogUrls(env, [env.CATALOG_BASE.replace(/\/$/, "") + "/_worklist.json"]);
      } catch (_) {}
    }
  }

  await env.AVATAR_KV.put("meta", JSON.stringify({
    ...prevMeta,
    lastFlush: new Date().toISOString(),
    lastAdded: added, lastRemoved: removed,
    totalAdded: (prevMeta.totalAdded || 0) + added,
    totalRemoved: (prevMeta.totalRemoved || 0) + removed,
    entries,
    ...(countMoved ? { unfilled } : {}),   // running Fill backlog (preserved when nothing moved)
    ...(manifestWritten ? { lastManifestMs: nowChecked, lastManifestEntries: entries } : {}),
    ...(hintChanged || seeded ? { fillHint } : {}),  // bot fill worklist (seeded once, then maintained)
    adoptedRebuild,
    lastCommit: allShardsOk
      ? `R2 +${added} -${removed} (${prefixes.length} shards)`
      : `R2 partial: some shard IO failed, kept pending (+${added} -${removed})`,
    pendingBatches: pendNames.length,
    reports: allShardsOk ? Math.max(0, repNames.length - repClear.length) : repNames.length,
    backend: "r2",
  }));

  // Rolling log of the most recent USER contribution batches (newest first) with FULL names, in a
  // DEDICATED key so it never bloats meta/health. Only rewritten when batches were processed this
  // flush → +1 small KV write per flush at most, nothing when idle. Capped at 20 batches.
  if (recentBatches.length) {
    let prev = []; try { const r = await env.AVATAR_KV.get("recent"); if (r) prev = JSON.parse(r); } catch (_) {}
    prev = Array.isArray(prev) ? prev : [];
    // DEDUP the display log by CONTENT (contributor + the exact avatar set), not timestamp: a
    // contribution POST that times out on the phone but actually landed gets retried, creating a 2nd
    // identical pend: batch — harmless for the catalog (contribute is idempotent, +0 added) but it
    // showed the same row twice in "Recent user contributions". The client sends no per-batch ts, so a
    // re-POST processed in a later flush has a different Date.now(); a content signature catches it
    // regardless. (Genuine repeats can't collide: the client dedups its queue by file id and contribute
    // dedups against R2, so an identical avatar set is only ever a re-POST.)
    const sigOf = (b) => `${b.by || ""}|${b.n || 0}|${(b.names || []).join("")}`;
    const seen = new Set(prev.map(sigOf));
    const fresh = [];
    for (const b of recentBatches) { const s = sigOf(b); if (!seen.has(s)) { seen.add(s); fresh.push(b); } }
    if (fresh.length) {
      const next = [...fresh.reverse(), ...prev].slice(0, 20);
      await env.AVATAR_KV.put("recent", JSON.stringify(next));
    }
  }
}

// Purge a list of absolute catalog URLs from Cloudflare's edge cache (batches of 30, the
// free-plan per-request file cap). Graceful no-op unless the purge secrets + CATALOG_BASE are set.
async function purgeCatalogUrls(env, urls) {
  if (!env.CF_PURGE_TOKEN || !env.CF_ZONE_ID || !env.CATALOG_BASE || !urls || urls.length === 0) return;
  const api = `https://api.cloudflare.com/client/v4/zones/${env.CF_ZONE_ID}/purge_cache`;
  for (let i = 0; i < urls.length; i += 30) {
    try {
      await fetch(api, {
        method: "POST",
        headers: { authorization: `Bearer ${env.CF_PURGE_TOKEN}`, "content-type": "application/json" },
        body: JSON.stringify({ files: urls.slice(i, i + 30) }),
      });
    } catch (_) { /* freshness falls back to the TTL */ }
  }
}

async function purgeShards(env, prefixes) {
  if (!env.CATALOG_BASE) return;
  const base = env.CATALOG_BASE.replace(/\/$/, "");
  await purgeCatalogUrls(env, prefixes.map((p) => `${base}/shard/${p}.json`));
}

// ---- recovery: re-shard from the R2 db.json master --------------------------
// Line-parses the master (never a whole-file JSON.parse) and writes only the [loN..endN] span's
// shards, so Worker memory + subrequests stay bounded. Returns { written, entries } or null.
async function reshardFromMaster(env, loN, endN) {
  const obj = await env.CATALOG.get("db.json");
  if (!obj) return null;
  const text = await obj.text();
  if (!text) return null;
  const byShard = {};
  let entries = 0;
  for (const raw of text.split("\n")) {
    const line = raw.trim().replace(/,$/, "");
    if (!line.startsWith('"file_')) continue;
    const idx = line.indexOf('":');
    if (idx < 0) continue;
    const fid = line.slice(1, idx);
    if (!FILE_RE.test(fid)) continue;
    const sp = shardPrefix(fid);
    const n = parseInt(sp, 16);
    if (n < loN || n > endN) continue;
    let val; try { val = JSON.parse(line.slice(idx + 2)); } catch (_) { continue; }
    (byShard[sp] ||= {})[fid] = val;
    entries++;
  }
  let written = 0;
  for (const sp of Object.keys(byShard)) {
    await env.CATALOG.put(`shard/${sp}.json`, JSON.stringify({ v: 1, e: byShard[sp] }), {
      httpMetadata: { contentType: "application/json", cacheControl: "public, max-age=" + SHARD_TTL },
    });
    written++;
  }
  return { written, entries };
}
