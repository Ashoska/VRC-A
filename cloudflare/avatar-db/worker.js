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
        await env.AVATAR_KV.put(key, JSON.stringify(cur), { expirationTtl: 30 * 86400 });
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
        return json({ ok: true, upserts: upserts.length, removes: removes.length, cleared: clearReports.length });
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
          recent: Array.isArray(meta.recent) ? meta.recent.slice(0, 40) : [],
          adminKeySet: !!env.ADMIN_KEY,
          purgeConfigured: !!(env.CF_PURGE_TOKEN && env.CF_ZONE_ID),
          // R2 is the only backend now. `catalogBase` is what the app appends
          // /shard/<prefix>.json etc. to (learned from here, so a serving change needs no
          // app update). Defaults to this Worker's /catalog route if no custom domain.
          r2: !!env.CATALOG,
          backend: "r2",
          catalogBase: env.CATALOG_BASE || `https://${url.host}/catalog`,
          shardScheme: "filehex3-full",
          shardCount: 4096,
          version: 7,
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
    ctx.waitUntil(flushR2(env));
  },
};

// ---- R2 per-shard flush -----------------------------------------------------
// Read only the shards touched by pending ops, merge, write them back. The whole catalog is
// NEVER in memory at once, so there's no CPU/memory wall regardless of size. Contributions/
// admin-upserts arrive already cleanEntry'd (stamped by /contribute + /admin).
async function flushR2(env) {
  const prevMeta = JSON.parse((await env.AVATAR_KV.get("meta")) || "{}");
  const allNames = (await env.AVATAR_KV.list()).keys.map((k) => k.name);
  const pendNames = allNames.filter((n) => n.startsWith("pend:"));
  const repNames  = allNames.filter((n) => n.startsWith("rep:"));
  const admuNames = allNames.filter((n) => n.startsWith("admu:"));
  const admrNames = allNames.filter((n) => n.startsWith("admr:"));
  const admkNames = allNames.filter((n) => n.startsWith("admk:"));

  // Group every pending op by shard prefix so each shard is read + written ONCE.
  const shardOps = {};
  const S = (sp) => (shardOps[sp] ||= { adds: {}, upserts: {}, removes: new Set(), checked: new Set(), renames: {} });

  const pendKeys = [];
  const recentBatches = [];   // admin "recent contributions" view (free: built from data already read)
  for (const kn of pendNames) {
    pendKeys.push(kn);
    const val = await env.AVATAR_KV.get(kn);
    if (!val) continue;
    let batch; try { batch = JSON.parse(val); } catch (_) { continue; }
    const fids = Object.keys(batch).filter((fid) => FILE_RE.test(fid));
    for (const fid of fids) S(shardPrefix(fid)).adds[fid] = batch[fid];
    if (fids.length) recentBatches.push({
      ts: typeof batch.__ts === "number" ? batch.__ts : Date.now(),
      by: typeof batch.__by === "string" ? batch.__by : "",
      n: fids.length,
      names: fids.slice(0, 3).map((fid) => (batch[fid] && batch[fid].name) || "").filter(Boolean),
    });
  }
  const admuKeys = [];
  for (const kn of admuNames) {
    admuKeys.push(kn);
    const val = await env.AVATAR_KV.get(kn);
    if (!val) continue;
    let batch; try { batch = JSON.parse(val); } catch (_) { continue; }
    for (const fid of Object.keys(batch)) if (FILE_RE.test(fid)) S(shardPrefix(fid)).upserts[fid] = batch[fid];
  }
  const admrKeys = [];
  for (const kn of admrNames) {
    admrKeys.push(kn);
    const val = await env.AVATAR_KV.get(kn);
    if (!val) continue;
    let arr; try { arr = JSON.parse(val); } catch (_) { continue; }
    for (const fid of arr) if (typeof fid === "string" && fid.startsWith("file_")) S(shardPrefix(fid)).removes.add(fid);
  }
  const admkKeys = [];
  for (const kn of admkNames) {
    admkKeys.push(kn);
    const val = await env.AVATAR_KV.get(kn);
    if (!val) continue;
    let arr; try { arr = JSON.parse(val); } catch (_) { continue; }
    for (const fid of arr) if (typeof fid === "string" && fid.startsWith("file_")) S(shardPrefix(fid)).checked.add(fid);
  }
  // Reports: rename immediately, remove on quorum; both clear their rep: key.
  const repClear = [];
  for (const kn of repNames) {
    const fid = kn.slice(4);
    if (!fid.startsWith("file_")) continue;
    const val = await env.AVATAR_KV.get(kn);
    if (!val) continue;
    let r; try { r = JSON.parse(val); } catch (_) { continue; }
    if (r.status === "renamed" && r.name) { S(shardPrefix(fid)).renames[fid] = String(r.name).slice(0, 100); repClear.push(kn); }
    else if (r.status === "dead" && (r.count || 0) >= REMOVE_QUORUM) { S(shardPrefix(fid)).removes.add(fid); repClear.push(kn); }
  }

  const nowChecked = Date.now();
  let added = 0, removed = 0, allShardsOk = true;
  const prefixes = Object.keys(shardOps);
  for (const sp of prefixes) {
    const ops = shardOps[sp];
    let cur;
    try {
      const obj = await env.CATALOG.get(`shard/${sp}.json`);
      cur = obj ? await obj.json() : { v: 1, e: {} };
      if (!cur || typeof cur !== "object" || typeof cur.e !== "object" || cur.e === null) cur = { v: 1, e: {} };
    } catch (_) { allShardsOk = false; continue; } // read failed -> skip (never wipe), retry next flush
    const e = cur.e;
    for (const fid of Object.keys(ops.adds)) if (!e[fid]) { e[fid] = ops.adds[fid]; added++; }
    for (const fid of Object.keys(ops.upserts)) {
      const inc = ops.upserts[fid];
      const prev = e[fid];
      if (prev && typeof prev.added === "number") inc.added = prev.added; // `added` is immutable
      else if (!prev) added++;
      e[fid] = inc;
    }
    for (const fid of Object.keys(ops.renames)) if (e[fid]) e[fid].name = ops.renames[fid];
    for (const fid of ops.checked) if (e[fid]) e[fid].checked = nowChecked;
    for (const fid of ops.removes) if (e[fid]) { delete e[fid]; removed++; }
    try {
      await env.CATALOG.put(`shard/${sp}.json`, JSON.stringify({ v: 1, e }), {
        httpMetadata: { contentType: "application/json", cacheControl: "public, max-age=" + SHARD_TTL },
      });
    } catch (_) { allShardsOk = false; }
  }

  // Purge just the changed shards from the edge cache so a new avatar goes live within
  // ~seconds instead of the TTL. No-op unless a purge token is configured.
  if (allShardsOk && prefixes.length > 0) await purgeShards(env, prefixes);

  // Clear KV only when every touched shard wrote OK (idempotent retry otherwise — nothing lost).
  if (allShardsOk) {
    for (const n of pendKeys) await env.AVATAR_KV.delete(n);
    for (const n of admuKeys) await env.AVATAR_KV.delete(n);
    for (const n of admrKeys) await env.AVATAR_KV.delete(n);
    for (const n of admkKeys) await env.AVATAR_KV.delete(n);
    for (const n of repClear) await env.AVATAR_KV.delete(n);
  }

  // Manifest: write it ONLY when the count actually moved, and MERGE so the fields the 20-min
  // rebuild Action owns (unfilled/searchReady/indexScheme/lastFullRebuild) are preserved
  // instead of being clobbered to undefined every minute. Short TTL + an explicit purge so the
  // fresh count/`lastUpdate` is live in ~1s.
  const countMoved = added > 0 || removed > 0;
  let entries = Math.max(0, (prevMeta.entries || 0) + added - removed);
  let adoptedRebuild = prevMeta.adoptedRebuild || null;
  if (countMoved) {
    let man = {};
    try { const m = await env.CATALOG.get("_manifest.json"); if (m) man = await m.json(); } catch (_) {}
    if (!man || typeof man !== "object") man = {};
    // Adopt the Action's AUTHORITATIVE entryCount on each fresh full rebuild — self-heals the
    // running counter's drift without a whole-catalog scan.
    if (man.lastFullRebuild && man.lastFullRebuild !== adoptedRebuild && typeof man.entryCount === "number") {
      entries = Math.max(0, man.entryCount + added - removed);
      adoptedRebuild = man.lastFullRebuild;
    }
    man = { ...man, v: 1, shardScheme: "filehex3-full", shardCount: 4096, entryCount: entries, lastUpdate: new Date().toISOString() };
    try {
      await env.CATALOG.put("_manifest.json", JSON.stringify(man), {
        httpMetadata: { contentType: "application/json", cacheControl: "public, max-age=30" },
      });
    } catch (_) {}
    if (allShardsOk && env.CATALOG_BASE) {
      await purgeCatalogUrls(env, [env.CATALOG_BASE.replace(/\/$/, "") + "/_manifest.json"]);
    }
  }

  await env.AVATAR_KV.put("meta", JSON.stringify({
    ...prevMeta,
    lastFlush: new Date().toISOString(),
    lastAdded: added, lastRemoved: removed,
    totalAdded: (prevMeta.totalAdded || 0) + added,
    totalRemoved: (prevMeta.totalRemoved || 0) + removed,
    entries,
    adoptedRebuild,
    lastCommit: allShardsOk
      ? `R2 +${added} -${removed} (${prefixes.length} shards)`
      : `R2 partial: some shard IO failed, kept pending (+${added} -${removed})`,
    pendingBatches: pendNames.length,
    reports: repNames.length,
    // Rolling log of the most recent USER contribution batches (newest first, capped) — who sent
    // it + a few avatar names. Only updated when batches were actually processed this flush.
    recent: recentBatches.length
      ? [...recentBatches.reverse(), ...(Array.isArray(prevMeta.recent) ? prevMeta.recent : [])].slice(0, 40)
      : (Array.isArray(prevMeta.recent) ? prevMeta.recent : []),
    backend: "r2",
  }));
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
