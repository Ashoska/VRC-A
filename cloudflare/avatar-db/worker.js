// VRC-A Avatar DB Worker
// -----------------------------------------------------------------------------
// A tiny, VRChat-free, crowdsourced avatar-id catalog merger.
//
// It is the ONLY writer of `avatars/db.json` in the image-store repo. Apps never
// hold a GitHub token: they POST new avatar mappings here, this Worker validates
// their FORMAT, stashes them in KV, and a cron flush merges them into the file in
// ONE commit every ~10 minutes. Verification/culling is crowdsourced: apps that
// find a dead/renamed avatar POST a report, and removals need a small quorum.
//
// Zero Firestore. Reads happen straight off the GitHub CDN in the apps.
//
// Endpoints:
//   POST /contribute  { entries: [ { fileId, avatarId, name, author, platforms } ] }
//   POST /report      { fileId, status: "dead" | "renamed", name? }
//   GET  /health      -> { entries, pendingBatches, reports, lastFlush, ... }
//
// Bindings/vars this Worker expects (set in the Cloudflare dashboard):
//   KV namespace binding:  AVATAR_KV
//   Secret:                GH_TOKEN   (GitHub fine-grained token, contents:write)
//   Variable:              GH_REPO    e.g. "Ashoska/VRC-A-Image-store"
//   Variable:              DB_PATH    e.g. "avatars/db.json"
//   Variable:              GH_BRANCH  e.g. "main"   (optional, defaults to main)
//   Cron trigger:          */10 * * * *
// -----------------------------------------------------------------------------

const AVTR_RE = /^avtr_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const FILE_RE = /^file_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const REMOVE_QUORUM = 2; // independent "dead" reports needed before a hard remove

// ---- R2 sharding -----------------------------------------------------------
// When the R2 bucket binding `CATALOG` is present, the flush writes per-shard objects
// (`shard/<3hex>.json`) instead of committing the whole ~18 MB db.json to GitHub every
// cron — which is what hit the Worker CPU/memory/large-commit wall past ~50k entries.
// Each shard holds the FULL records for the file ids whose UUID starts with those 3 hex,
// so the whole catalog is NEVER loaded into the Worker at once. 4096 shards.
const SHARD_TTL = 300; // seconds; R2 cacheControl so edge caches expire in ~5 min

// file id = "file_" + UUID (8-4-4-4-12 hex). The 3 hex after "file_" (string index 5..7)
// are the shard prefix. Guarded: fall back to "000" if the format ever differs.
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
    // Avatar description/bio (device- or bot-filled). Kept short.
    desc: desc.slice(0, 400),
    // The bot has done a full first-fill of this entry (name/author/platforms/bio).
    // Devices contribute filled=false; the fill bot sets it true.
    filled: e.filled === true,
    added: now,
    checked: now, // last time the bot verified this avatar is alive (= added at first)
  };
  // Per-platform performance/optimisation rank — ONLY store it for platforms the avatar
  // actually has a build for (it's in `platforms`). A PC-only avatar shouldn't carry a
  // Quest/iOS rating; perf for an unsupported platform is meaningless noise.
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
      // A cache HIT skips R2 entirely (free); a MISS is one Class B read, then cached.
      // This is serving option 5b.2 — works on *.workers.dev TODAY, no domain needed.
      // Once a custom domain is attached to the bucket, CATALOG_BASE (in /health) flips
      // to it and reads bypass the Worker at the pure CDN layer (no app update needed).
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
        // One KV write per POST (batch), so a device flushing its whole queue is
        // a single write. Dedup happens at merge time (keyed by file id).
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
        // Store the avatar id so the bot can VERIFY without a catalog lookup.
        if (typeof body.avatarId === "string" && body.avatarId.startsWith("avtr_")) {
          cur.avatarId = body.avatarId;
        }
        cur.count = (cur.count || 0) + 1;
        await env.AVATAR_KV.put(key, JSON.stringify(cur), { expirationTtl: 30 * 86400 });
        // Wake the bot promptly: bump the live report count in meta so the sweep's
        // cheap /health poll sees it immediately instead of waiting up to ~10 min for
        // the next flush to stamp meta.reports. Self-corrects at the next flush.
        if (isNewReport) {
          const meta = JSON.parse((await env.AVATAR_KV.get("meta")) || "{}");
          meta.reports = (meta.reports || 0) + 1;
          await env.AVATAR_KV.put("meta", JSON.stringify(meta));
        }
        return json({ ok: true });
      }

      if (req.method === "GET" && url.pathname === "/admin/restore") {
        // ONE-TIME recovery: restore db.json from a known-good commit sha. Guarded by
        // ADMIN_KEY. Usage: /admin/restore?key=ADMIN_KEY&sha=<goodCommitSha>
        const key = url.searchParams.get("key");
        if (!env.ADMIN_KEY || key !== env.ADMIN_KEY) return json({ ok: false, error: "bad admin key" }, 403);
        const ref = url.searchParams.get("sha");
        if (!ref) return json({ ok: false, error: "need ?sha=<good commit>" }, 400);
        const repo = env.GH_REPO, path = env.DB_PATH, branch = env.GH_BRANCH || "main";
        const gh = ghHeaders(env);
        const apiUrl = `https://api.github.com/repos/${repo}/contents/${path}`;
        // Read the file AT the good commit (blob API handles the >1MB file).
        const oldRes = await fetch(apiUrl + "?ref=" + encodeURIComponent(ref), { headers: gh });
        if (oldRes.status !== 200) return json({ ok: false, error: "read old http " + oldRes.status }, 500);
        const oldJson = await oldRes.json();
        let b64 = oldJson.content || "";
        if (b64.replace(/\s/g, "").length === 0 && oldJson.sha) {
          const blobRes = await fetch(`https://api.github.com/repos/${repo}/git/blobs/${oldJson.sha}`, { headers: gh });
          if (blobRes.status !== 200) return json({ ok: false, error: "blob http " + blobRes.status }, 500);
          b64 = ((await blobRes.json()).content) || "";
        }
        b64 = b64.replace(/\s/g, "");
        let restored;
        try { restored = JSON.parse(b64decode(b64)); } catch (e) { return json({ ok: false, error: "old file unparseable" }, 500); }
        const count = Object.keys(restored.avatars || {}).length;
        if (count < 1) return json({ ok: false, error: "old file has 0 avatars" }, 500);
        // SAFETY (anti-footgun): a restore that would SHRINK the catalog is almost always an
        // accident (e.g. reverting the grown catalog back to an old snapshot). Recovering MORE
        // avatars than we currently have is always allowed; a REDUCING restore is refused
        // unless &force=1 is explicitly added. This is what stops an accidental re-click of an
        // old restore link from wiping the current data.
        const force = url.searchParams.get("force") === "1";
        const metaNow = JSON.parse((await env.AVATAR_KV.get("meta")) || "{}");
        const curCount = metaNow.entries || 0;
        if (!force && curCount > 200 && count < curCount * 0.7) {
          return json({
            ok: false,
            error: `REFUSED: restore from ${ref} has ${count} avatars but the catalog currently has ${curCount}. This would DELETE ${curCount - count} avatars. If you REALLY mean to shrink it, add &force=1.`,
            current: curCount, restore: count,
          }, 409);
        }
        // Overwrite current main with it. RETRY on a 409: the cron flush commits every ~2
        // min and can move the file's sha BETWEEN our read-sha and our write, which GitHub
        // rejects as a conflict ("is at X but expected Y"). Re-read the fresh sha and retry a
        // few times so a concurrent flush can't make the recovery fail.
        let putOk = false, putErr = "";
        for (let attempt = 0; attempt < 6; attempt++) {
          const curRes = await fetch(apiUrl + "?ref=" + encodeURIComponent(branch), { headers: gh });
          const curSha = curRes.status === 200 ? (await curRes.json()).sha : undefined;
          const putBody = { message: `avatar-db: RESTORE ${count} avatars from ${ref}`, content: b64, branch };
          if (curSha) putBody.sha = curSha;
          const putRes = await fetch(apiUrl, { method: "PUT", headers: gh, body: JSON.stringify(putBody) });
          if (putRes.status === 200 || putRes.status === 201) { putOk = true; break; }
          putErr = "http " + putRes.status + " " + (await putRes.text()).slice(0, 160);
          if (putRes.status !== 409) break;                  // non-conflict = real failure, stop
          await new Promise((r) => setTimeout(r, 700));       // brief backoff, then re-read sha
        }
        if (!putOk) return json({ ok: false, error: "put failed after retries: " + putErr }, 500);
        await env.AVATAR_KV.put("dbcache", serializeDb(restored));
        const meta = JSON.parse((await env.AVATAR_KV.get("meta")) || "{}");
        await env.AVATAR_KV.put("meta", JSON.stringify({
          ...meta, entries: count, lastFlush: new Date().toISOString(), lastCommit: `RESTORED ${count} from ${ref}`,
        }));
        return json({ ok: true, restored: count, from: ref });
      }

      if (req.method === "GET" && url.pathname === "/admin/migrate-r2") {
        // ONE-TIME, RESUMABLE seed of the R2 shards from the current GitHub master.
        // Guarded by ADMIN_KEY. Processes a hex-prefix RANGE per call so a huge catalog
        // migrates in chunks without one over-long invocation:
        //   /admin/migrate-r2?key=KEY&lo=000&hi=0ff   (then 100-1ff, ... up to fff)
        // Safe to re-run; each shard is overwritten from the master (source of truth here).
        if (!env.ADMIN_KEY || url.searchParams.get("key") !== env.ADMIN_KEY) return json({ ok: false, error: "unauthorized" }, 401);
        if (!env.CATALOG) return json({ ok: false, error: "R2 not configured" }, 503);
        const lo = (url.searchParams.get("lo") || "000").toLowerCase();
        const hi = (url.searchParams.get("hi") || "fff").toLowerCase();
        const loN = parseInt(lo, 16), hiN = parseInt(hi, 16);
        if (isNaN(loN) || isNaN(hiN) || loN > hiN) return json({ ok: false, error: "bad lo/hi (3 hex each)" }, 400);
        // AUTO (recommended): schedule a background migration. The cron migrates one span
        // (~512 shards) every 2 min until done — no browser holding a long request open (no
        // page hang / mobile-tab crash). Kick it once, close the page, watch /health .migrate.
        if (url.searchParams.get("auto") === "1") {
          await env.AVATAR_KV.put("migcur", lo);
          await env.AVATAR_KV.put("mighi", hi);
          await env.AVATAR_KV.put("migactive", "1");
          return json({ ok: true, scheduled: true, from: lo, to: hi,
            note: "Migration scheduled. The cron writes ~512 shards every 2 min until done; the GitHub flush pauses meanwhile (contributions just queue). Close this page — watch progress at /health under `migrate`." });
        }
        // MANUAL (one-off): process a single fixed span synchronously and hand back nextUrl.
        const endN = Math.min(loN + 511, hiN);
        const res = await migrateSpan(env, loN, endN);
        if (!res) return json({ ok: false, error: "could not read master db.json" }, 500);
        const nextN = endN + 1;
        const done = nextN > hiN;
        const nextLo = done ? null : nextN.toString(16).padStart(3, "0");
        const origin = `${url.protocol}//${url.host}`;
        const nextUrl = done ? null
          : `${origin}/admin/migrate-r2?key=${encodeURIComponent(url.searchParams.get("key"))}&lo=${nextLo}&hi=${hi}`;
        return json({ ok: true, range: `${lo}-${endN.toString(16).padStart(3, "0")}`,
          shardsWritten: res.written, entriesInRange: res.entries, done, nextLo, nextUrl,
          note: done ? "migration complete" : "open nextUrl to continue (tap it)" });
      }

      if (req.method === "GET" && url.pathname === "/db") {
        // The current serialized catalog, served FRESH from KV (no GitHub CDN lag) so
        // the admin bots notice new avatars immediately. Empty until the first flush.
        const cached = await env.AVATAR_KV.get("dbcache");
        return new Response(cached || '{"avatars":{}}', {
          status: 200,
          headers: { "content-type": "application/json", "cache-control": "no-store",
            "access-control-allow-origin": "*" },
        });
      }

      if (req.method === "GET" && (url.pathname === "/health" || url.pathname === "/")) {
        // ONE cheap KV read (no list ops — those have a tight 1k/day free limit and
        // this is polled every 15s). Pending counts come from meta (set at flush).
        const meta = JSON.parse((await env.AVATAR_KV.get("meta")) || "{}");
        const branch = env.GH_BRANCH || "main";
        return json({
          ok: true,
          entries: meta.entries || 0,
          pendingBatches: meta.pendingBatches || 0,
          reports: meta.reports || 0,
          lastFlush: meta.lastFlush || null,
          lastAdded: meta.lastAdded || 0,
          lastRemoved: meta.lastRemoved || 0,
          // Cumulative totals (climb over the Worker's lifetime, so bot activity is
          // visible even between flushes that changed nothing).
          totalAdded: meta.totalAdded || 0,
          totalRemoved: meta.totalRemoved || 0,
          // Where this Worker WRITES (so a repo/branch/path mismatch is visible):
          repo: env.GH_REPO || "(unset)",
          path: env.DB_PATH || "(unset)",
          branch: branch,
          rawUrl: `https://raw.githubusercontent.com/${env.GH_REPO || "?"}/${branch}/${env.DB_PATH || "?"}`,
          lastCommit: meta.lastCommit || "none",
          adminKeySet: !!env.ADMIN_KEY,
          // R2 sharding: which backend the flush is writing + where clients should read
          // the sharded catalog. `catalogBase` is what the app appends `/shard/<prefix>.json`
          // (and later `/index/...`) to — learned from here so a serving change (workers.dev
          // -> custom domain) needs no app update. Defaults to this Worker's /catalog route
          // (edge-cached); set the CATALOG_BASE var to a custom domain to bypass the Worker.
          r2: !!env.CATALOG,
          r2WriteActive: r2WriteActive(env),
          backend: r2WriteActive(env) ? "r2" : "github",
          catalogBase: env.CATALOG_BASE || `https://${url.host}/catalog`,
          shardScheme: "filehex3-full",
          shardCount: 4096,
          // Background R2 migration progress (present while/after an ?auto=1 migration runs).
          migrate: meta.migrate || null,
          version: 6,
        });
      }

      if (req.method === "GET" && url.pathname === "/admin/reports") {
        // The bot fetches the PENDING dead/rename reports to verify — so it only
        // ever checks REPORTED avatars, not the whole catalog (scales to millions).
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

      if (req.method === "POST" && url.pathname === "/admin") {
        // Authoritative admin ops from the recheck sweep (bot VRChat session).
        // Requires the ADMIN_KEY secret. upserts OVERWRITE entries (refresh
        // name/author/authorId/platforms); removes delete dead avatars outright.
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
        // Batched "last checked" bumps from the bot's passive oldest-first sweep —
        // applied on the next flush (rides the same commit, so it's ~free).
        const checked = Array.isArray(body.checked)
          ? body.checked.filter((f) => typeof f === "string" && f.startsWith("file_")).slice(0, 500)
          : [];
        if (checked.length) {
          await env.AVATAR_KV.put("admk:" + crypto.randomUUID(), JSON.stringify(checked), { expirationTtl: 7 * 86400 });
        }
        // Verified-ALIVE reports: clear them (the bot confirmed a false positive).
        const clearReports = Array.isArray(body.clearReports)
          ? body.clearReports.filter((f) => typeof f === "string" && f.startsWith("file_")).slice(0, 200)
          : [];
        for (const fid of clearReports) await env.AVATAR_KV.delete("rep:" + fid);
        // A confirmed-dead report's rep: key is also cleared once removed.
        for (const fid of removes) await env.AVATAR_KV.delete("rep:" + fid);
        return json({ ok: true, upserts: upserts.length, removes: removes.length, cleared: clearReports.length });
      }

      if (req.method === "GET" && url.pathname === "/flush") {
        // Trigger a flush on demand and report the commit result (for diagnosis).
        await flush(env);
        const meta = JSON.parse((await env.AVATAR_KV.get("meta")) || "{}");
        return json({
          triggered: true,
          lastCommit: meta.lastCommit || "none",
          entries: meta.entries || 0,
          lastAdded: meta.lastAdded || 0,
          lastFlush: meta.lastFlush || null,
        });
      }

      if (req.method === "GET" && url.pathname === "/where") {
        // Authenticated live GET of the file -> GitHub's OWN download_url / html_url,
        // so we can see EXACTLY where the Worker's commits land.
        const repo = env.GH_REPO, path = env.DB_PATH, branch = env.GH_BRANCH || "main";
        const apiUrl = `https://api.github.com/repos/${repo}/contents/${path}?ref=${encodeURIComponent(branch)}`;
        const res = await fetch(apiUrl, { headers: ghHeaders(env) });
        const out = { repo, path, branch, apiUrl, getStatus: res.status };
        if (res.status === 200) {
          const j = await res.json();
          out.download_url = j.download_url;
          out.html_url = j.html_url;
          out.sha = j.sha;
          out.size = j.size;
        } else {
          out.body = (await res.text().catch(() => "")).slice(0, 300);
        }
        return json(out);
      }

      return json({ ok: false, error: "not found" }, 404);
    } catch (e) {
      return json({ ok: false, error: String(e) }, 500);
    }
  },

  async scheduled(event, env, ctx) {
    // While a background R2 migration is active, the cron does a migration STEP instead of
    // the flush (so the two don't stack subrequests in one invocation). Contributions just
    // queue in KV during the ~16 min migration and flush normally once it finishes.
    ctx.waitUntil((async () => {
      if (await migrationActive(env)) await migrateStep(env);
      else await flush(env);
    })());
  },
};

function ghHeaders(env) {
  return {
    authorization: "Bearer " + env.GH_TOKEN,
    "user-agent": "VRC-A-Avatar-DB-Worker",
    accept: "application/vnd.github+json",
  };
}

function b64encode(str) {
  const bytes = new TextEncoder().encode(str);
  // Build the binary string in 32KB chunks. The old byte-by-byte concat did ~13
  // MILLION single-char appends on the full catalog and blew the Worker CPU limit
  // (Cloudflare error 1102 -> flush stopped committing to GitHub). fromCharCode.apply
  // over a subarray does ~one call per 32KB (a few hundred total) instead.
  let bin = "";
  const CHUNK = 0x8000;
  for (let i = 0; i < bytes.length; i += CHUNK) {
    bin += String.fromCharCode.apply(null, bytes.subarray(i, i + CHUNK));
  }
  return btoa(bin);
}

function b64decode(b64) {
  const bin = atob((b64 || "").replace(/\n/g, ""));
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return new TextDecoder().decode(bytes);
}

// One avatar per line, so the file is readable/diffable on GitHub as it grows.
// Labeled + version stamped at the top; each avatar on its own line.
function serializeDb(db) {
  const keys = Object.keys(db.avatars);
  const lines = keys.map((k) => JSON.stringify(k) + ":" + JSON.stringify(db.avatars[k]));
  return '{"name":"VRC-A Avatar Store","version":' + (db.version || 1) +
    ',"count":' + keys.length + ',"avatars":{\n' + lines.join(",\n") + "\n}}";
}

// Flush dispatcher. The R2 per-shard path (never loads the whole catalog → no CPU/memory/
// commit wall) activates ONLY when BOTH the R2 bucket binding is present AND the explicit
// `R2_WRITE = "1"` var is set. The two-key gate is deliberate: creating the bucket + binding
// lets you seed shards (/admin/migrate-r2) and serve them (/catalog) for testing WITHOUT
// cutting over the flush — because flushR2 stops feeding the whole-file sources (GitHub
// master + KV dbcache) that the admin sweep and un-updated clients still read, so cutover
// must wait until the sharded-read side is ready. Flip R2_WRITE last.
function r2WriteActive(env) { return !!(env.CATALOG && env.R2_WRITE === "1"); }
async function flush(env) {
  if (r2WriteActive(env)) return flushR2(env);
  return flushGithub(env);
}

// Migrate ONE prefix span [loN..endN] from the master into R2 shards. Line-parses the
// master (never a whole-file JSON.parse) and holds only the span's entries, so memory +
// subrequests stay bounded. Returns { written, entries } or null (master read failed).
async function migrateSpan(env, loN, endN) {
  const text = await readMasterText(env);
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

async function migrationActive(env) {
  return (await env.AVATAR_KV.get("migactive")) === "1";
}

// One background migration step, run by the cron while a migration is active. Advances a
// KV cursor by one 512-prefix span each tick until it passes `mighi`, then clears active.
async function migrateStep(env) {
  const lo = await env.AVATAR_KV.get("migcur");
  if (!lo) { await env.AVATAR_KV.put("migactive", "0"); return; }
  const hi = (await env.AVATAR_KV.get("mighi")) || "fff";
  const loN = parseInt(lo, 16), hiN = parseInt(hi, 16);
  if (isNaN(loN) || isNaN(hiN)) { await env.AVATAR_KV.delete("migcur"); await env.AVATAR_KV.put("migactive", "0"); return; }
  const endN = Math.min(loN + 511, hiN);
  const res = await migrateSpan(env, loN, endN);
  if (!res) return; // master read failed — leave the cursor, retry next tick
  const spanLabel = `${lo}-${endN.toString(16).padStart(3, "0")}`;
  const nextN = endN + 1;
  const meta = JSON.parse((await env.AVATAR_KV.get("meta")) || "{}");
  if (nextN > hiN) {
    await env.AVATAR_KV.delete("migcur");
    await env.AVATAR_KV.put("migactive", "0");
    meta.migrate = { active: false, done: new Date().toISOString(), lastSpan: spanLabel, lastWritten: res.written };
  } else {
    const nextLo = nextN.toString(16).padStart(3, "0");
    await env.AVATAR_KV.put("migcur", nextLo);
    meta.migrate = { active: true, cursor: nextLo, hi, lastSpan: spanLabel, lastWritten: res.written };
  }
  await env.AVATAR_KV.put("meta", JSON.stringify(meta));
}

// Read the GitHub master db.json as RAW TEXT (handles the >1 MB blob case). Returns the
// decoded string or null. The migration line-parses this instead of JSON.parse-ing the
// whole file into one object graph, so Worker memory stays bounded at any catalog size.
async function readMasterText(env) {
  const repo = env.GH_REPO, path = env.DB_PATH, branch = env.GH_BRANCH || "main";
  if (!repo || !path || repo === "undefined" || path === "undefined") return null;
  const headers = ghHeaders(env);
  const apiUrl = `https://api.github.com/repos/${repo}/contents/${path}`;
  const getRes = await fetch(apiUrl + "?ref=" + encodeURIComponent(branch), { headers });
  if (getRes.status !== 200) return null;
  const j = await getRes.json();
  let b64 = j.content || "";
  if (b64.replace(/\s/g, "").length === 0 && j.sha) {
    const blobRes = await fetch(`https://api.github.com/repos/${repo}/git/blobs/${j.sha}`, { headers });
    if (blobRes.status !== 200) return null;
    b64 = ((await blobRes.json()).content) || "";
  }
  try { return b64decode(b64.replace(/\s/g, "")); } catch (_) { return null; }
}

// Read + parse the whole GitHub master db.json (handles the >1 MB blob case). Returns
// the parsed { version, avatars } or null on any read/parse failure. (Kept for possible
// reuse; the migration endpoint uses readMasterText to stay memory-bounded.)
async function readMasterDb(env) {
  const repo = env.GH_REPO, path = env.DB_PATH, branch = env.GH_BRANCH || "main";
  if (!repo || !path || repo === "undefined" || path === "undefined") return null;
  const headers = ghHeaders(env);
  const apiUrl = `https://api.github.com/repos/${repo}/contents/${path}`;
  const getRes = await fetch(apiUrl + "?ref=" + encodeURIComponent(branch), { headers });
  if (getRes.status !== 200) return null;
  const j = await getRes.json();
  let b64 = j.content || "";
  if (b64.replace(/\s/g, "").length === 0 && j.sha) {
    const blobRes = await fetch(`https://api.github.com/repos/${repo}/git/blobs/${j.sha}`, { headers });
    if (blobRes.status !== 200) return null;
    b64 = ((await blobRes.json()).content) || "";
  }
  try {
    const db = JSON.parse(b64decode(b64.replace(/\s/g, "")));
    if (!db.avatars) db.avatars = {};
    return db;
  } catch (_) { return null; }
}

// The R2 per-shard flush: read only the shards touched by pending ops, merge, write them
// back. The whole catalog is NEVER in memory at once, so there is no CPU/memory/commit
// wall regardless of catalog size. Contributions/admin-upserts arrive already cleanEntry'd
// (stamped by /contribute + /admin), so this path just merges + writes.
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
  for (const kn of pendNames) {
    pendKeys.push(kn);
    const val = await env.AVATAR_KV.get(kn);
    if (!val) continue;
    let batch; try { batch = JSON.parse(val); } catch (_) { continue; }
    for (const fid of Object.keys(batch)) if (FILE_RE.test(fid)) S(shardPrefix(fid)).adds[fid] = batch[fid];
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

  // Clear KV only when every touched shard wrote OK (idempotent retry otherwise — nothing lost).
  if (allShardsOk) {
    for (const n of pendKeys) await env.AVATAR_KV.delete(n);
    for (const n of admuKeys) await env.AVATAR_KV.delete(n);
    for (const n of admrKeys) await env.AVATAR_KV.delete(n);
    for (const n of admkKeys) await env.AVATAR_KV.delete(n);
    for (const n of repClear) await env.AVATAR_KV.delete(n);
  }

  const entries = Math.max(0, (prevMeta.entries || 0) + added - removed);
  try {
    await env.CATALOG.put("_manifest.json", JSON.stringify({
      v: 1, shardScheme: "filehex3-full", shardCount: 4096, entryCount: entries,
      lastUpdate: new Date().toISOString(),
    }), { httpMetadata: { contentType: "application/json", cacheControl: "public, max-age=" + SHARD_TTL } });
  } catch (_) {}

  await env.AVATAR_KV.put("meta", JSON.stringify({
    ...prevMeta,
    lastFlush: new Date().toISOString(),
    lastAdded: added, lastRemoved: removed,
    totalAdded: (prevMeta.totalAdded || 0) + added,
    totalRemoved: (prevMeta.totalRemoved || 0) + removed,
    entries,
    lastCommit: allShardsOk
      ? `R2 +${added} -${removed} (${prefixes.length} shards)`
      : `R2 partial: some shard IO failed, kept pending (+${added} -${removed})`,
    pendingBatches: pendNames.length,
    reports: repNames.length,
    backend: "r2",
  }));
}

async function flushGithub(env) {
  const repo = env.GH_REPO;
  const path = env.DB_PATH;
  const branch = env.GH_BRANCH || "main";
  const headers = ghHeaders(env);
  // Prior meta — so we can carry the CUMULATIVE added/removed totals across flushes
  // (the per-flush lastAdded/lastRemoved reset each time, which read as "nothing
  // happened"; the running totals are what make bot activity visible).
  const prevMeta = JSON.parse((await env.AVATAR_KV.get("meta")) || "{}");

  // GUARD: never write to an unset/undefined path (that created the "undefined"
  // file). Abort and record the reason so /health shows it.
  if (!path || path === "undefined" || !repo || repo === "undefined") {
    await env.AVATAR_KV.put("meta", JSON.stringify({
      lastFlush: new Date().toISOString(), lastAdded: 0, lastRemoved: 0,
      totalAdded: prevMeta.totalAdded || 0, totalRemoved: prevMeta.totalRemoved || 0,
      entries: 0, lastCommit: "ERROR: GH_REPO/DB_PATH unset (repo=" + repo + " path=" + path + ")",
    }));
    return;
  }
  const apiUrl = `https://api.github.com/repos/${repo}/contents/${path}`;

  // 1. Load the current db.json (+ sha for the update).
  let db = { version: 1, avatars: {} };
  let sha;
  let legacySha; // sha of a stray "undefined" file to migrate + delete
  let hadExistingFile = false;
  const getRes = await fetch(apiUrl + "?ref=" + encodeURIComponent(branch), { headers });
  if (getRes.status === 200) {
    const j = await getRes.json();
    sha = j.sha;
    hadExistingFile = true;
    // The Contents API returns EMPTY content for files >1MB — fetch the blob instead
    // (handles up to 100MB). THIS 1MB LIMIT, plus the old reset-to-empty on failure, is
    // what wiped the catalog once it grew past ~1MB.
    let contentB64 = j.content || "";
    if (contentB64.replace(/\s/g, "").length === 0 && j.sha) {
      const blobRes = await fetch(`https://api.github.com/repos/${repo}/git/blobs/${j.sha}`, { headers });
      if (blobRes.status === 200) {
        contentB64 = ((await blobRes.json()).content) || "";
      } else {
        // Couldn't read the big file — ABORT, do NOT wipe. Retry next cron.
        await env.AVATAR_KV.put("meta", JSON.stringify({
          ...prevMeta, lastFlush: new Date().toISOString(),
          lastCommit: "ABORTED: blob read http " + blobRes.status + " (kept everything)",
        }));
        return;
      }
    }
    try {
      db = JSON.parse(b64decode(contentB64.replace(/\s/g, "")));
    } catch (e) {
      // CRITICAL: never reset to empty. If we can't parse the current file we must NOT
      // write a smaller one — abort and keep all pending for the next cron.
      await env.AVATAR_KV.put("meta", JSON.stringify({
        ...prevMeta, lastFlush: new Date().toISOString(),
        lastCommit: "ABORTED: could not parse current db.json (kept everything)",
      }));
      return;
    }
    if (!db.avatars) db.avatars = {};
  } else if (getRes.status !== 404) {
    return; // transient GitHub error — leave KV untouched, retry next cron
  }

  // ONE KV list for the whole flush (list ops have a tight 1k/day free limit), then
  // partition by prefix in code instead of 5 separate list() calls.
  const allNames = (await env.AVATAR_KV.list()).keys.map((k) => k.name);
  const pendNames = allNames.filter((n) => n.startsWith("pend:"));
  const repNames = allNames.filter((n) => n.startsWith("rep:"));
  const admuNames = allNames.filter((n) => n.startsWith("admu:"));
  const admrNames = allNames.filter((n) => n.startsWith("admr:"));
  const admkNames = allNames.filter((n) => n.startsWith("admk:"));

  // 2. Merge pending adds (deduped by file id).
  let added = 0;
  const pendKeys = [];
  for (const kname of pendNames) {
    const val = await env.AVATAR_KV.get(kname);
    pendKeys.push(kname);
    if (!val) continue;
    let batch;
    try {
      batch = JSON.parse(val);
    } catch (_) {
      continue;
    }
    for (const fileId of Object.keys(batch)) {
      if (!db.avatars[fileId]) {
        db.avatars[fileId] = batch[fileId];
        added++;
      }
    }
  }

  // 2b. MIGRATION: fold in + delete any stray root "undefined" file (from before
  //     DB_PATH was applied). Runs whether or not the real file already exists;
  //     once "undefined" is gone this is a cheap no-op.
  {
    const legacy = await fetch(
      `https://api.github.com/repos/${repo}/contents/undefined?ref=${encodeURIComponent(branch)}`,
      { headers }
    );
    if (legacy.status === 200) {
      const lj = await legacy.json();
      try {
        const ld = JSON.parse(b64decode(lj.content));
        if (ld && ld.avatars) {
          for (const fid of Object.keys(ld.avatars)) {
            if (!db.avatars[fid]) { db.avatars[fid] = ld.avatars[fid]; added++; }
          }
        }
      } catch (_) {}
      legacySha = lj.sha;
    }
  }

  // 3. Apply reports: rename immediately, remove on quorum.
  let removed = 0;
  const repClear = [];
  for (const kname of repNames) {
    const fileId = kname.slice(4);
    const val = await env.AVATAR_KV.get(kname);
    if (!val) continue;
    let r;
    try {
      r = JSON.parse(val);
    } catch (_) {
      continue;
    }
    if (r.status === "renamed" && db.avatars[fileId] && r.name) {
      db.avatars[fileId].name = r.name;
      repClear.push(kname);
    } else if (r.status === "dead" && (r.count || 0) >= REMOVE_QUORUM) {
      if (db.avatars[fileId]) {
        delete db.avatars[fileId];
        removed++;
      }
      repClear.push(kname);
    }
    // Not enough "dead" reports yet -> leave the report in place.
  }

  // 3b. Apply authoritative admin ops (overwrite refreshes + hard removes).
  const admuKeys = [], admrKeys = [];
  let adminChanged = false;
  for (const kname of admuNames) {
    admuKeys.push(kname);
    const val = await env.AVATAR_KV.get(kname);
    if (!val) continue;
    try {
      const batch = JSON.parse(val);
      for (const fid of Object.keys(batch)) {
        const incoming = batch[fid];
        const prev = db.avatars[fid];
        // `added` is IMMUTABLE — the moment the avatar first entered the catalog. cleanEntry
        // stamps added=now on every upsert, so without this a routine bot REFRESH would reset
        // added to now (both added+checked moved together — the reported bug). Preserve the
        // original added for an existing entry; only a genuinely NEW entry keeps added=now.
        // `checked` correctly takes the fresh value (that's what a re-check updates).
        if (prev && typeof prev.added === "number") incoming.added = prev.added;
        db.avatars[fid] = incoming;
        adminChanged = true;
      }
    } catch (_) {}
  }
  for (const kname of admrNames) {
    admrKeys.push(kname);
    const val = await env.AVATAR_KV.get(kname);
    if (!val) continue;
    try {
      const arr = JSON.parse(val);
      for (const fid of arr) { if (db.avatars[fid]) { delete db.avatars[fid]; removed++; adminChanged = true; } }
    } catch (_) {}
  }
  // Last-checked bumps (passive oldest-first sweep) — rides this same commit.
  const admkKeys = [];
  const nowChecked = Date.now();
  for (const kname of admkNames) {
    admkKeys.push(kname);
    const val = await env.AVATAR_KV.get(kname);
    if (!val) continue;
    try {
      const arr = JSON.parse(val);
      for (const fid of arr) { if (db.avatars[fid]) { db.avatars[fid].checked = nowChecked; adminChanged = true; } }
    } catch (_) {}
  }

  const entries = Object.keys(db.avatars).length;

  // SAFETY GUARD: never commit a catalog that suddenly lost most of its entries — a
  // drop far bigger than our explicit removes means the base read was corrupt. Abort
  // and keep everything (pending is preserved), so a read glitch can't wipe the DB.
  const prevEntries = prevMeta.entries || 0;
  if (hadExistingFile && prevEntries > 200 && entries < (prevEntries - removed) * 0.7) {
    await env.AVATAR_KV.put("meta", JSON.stringify({
      ...prevMeta, lastFlush: new Date().toISOString(),
      lastCommit: `ABORTED: entries ${prevEntries}->${entries} (safety guard, kept everything)`,
    }));
    return;
  }

  // 4. Commit when something changed OR we're migrating the legacy "undefined" file.
  // serializeDb() is the single heaviest step on a big catalog (builds a ~13MB
  // string), so compute it AT MOST ONCE per flush and reuse it for both the PUT
  // body and the dbcache. A no-op flush serializes nothing.
  let lastCommit = "no change";
  const changed = added > 0 || removed > 0 || repClear.length > 0 || legacySha || adminChanged;
  let serialized = null;
  if (changed) {
    serialized = serializeDb(db);
    const putBody = {
      message: `avatar-db: +${added} -${removed} (${entries} total)`,
      content: b64encode(serialized),
      branch,
    };
    if (sha) putBody.sha = sha;
    const putRes = await fetch(apiUrl, {
      method: "PUT",
      headers,
      body: JSON.stringify(putBody),
    });
    lastCommit = "PUT http " + putRes.status;
    if (putRes.status !== 200 && putRes.status !== 201) {
      // sha conflict / error -> keep KV, retry next cron. Record why for /health.
      const errText = await putRes.text().catch(() => "");
      await env.AVATAR_KV.put("meta", JSON.stringify({
        lastFlush: new Date().toISOString(),
        lastAdded: 0, lastRemoved: 0,
        totalAdded: prevMeta.totalAdded || 0, totalRemoved: prevMeta.totalRemoved || 0,
        entries,
        lastCommit: lastCommit + " FAILED: " + errText.slice(0, 200),
      }));
      return;
    }
    // Only clear KV after a successful commit, so nothing is lost on failure.
    for (const name of pendKeys) await env.AVATAR_KV.delete(name);
    for (const name of repClear) await env.AVATAR_KV.delete(name);
    for (const name of admuKeys) await env.AVATAR_KV.delete(name);
    for (const name of admrKeys) await env.AVATAR_KV.delete(name);
    for (const name of admkKeys) await env.AVATAR_KV.delete(name);
    // Migration done -> delete the stray "undefined" file.
    if (legacySha) {
      await fetch(`https://api.github.com/repos/${repo}/contents/undefined`, {
        method: "DELETE",
        headers,
        body: JSON.stringify({ message: "avatar-db: migrate undefined -> " + path, sha: legacySha, branch }),
      }).catch(() => {});
      lastCommit += " (migrated from undefined)";
    }
  }

  // Cache the CURRENT serialized DB in KV so the app can read it FRESH from the Worker
  // (GET /db) with zero GitHub CDN lag — the CDN caches raw files ~5 min, which delayed
  // the admin bots noticing new avatars. Only rewrite it when the file actually changed
  // (otherwise the existing cache is already current) — reuses the SAME serialized
  // string built for the commit, so a flush serializes the catalog at most once. This
  // (plus the chunked b64encode) is what got flush back under the Worker CPU limit.
  if (changed && serialized) {
    await env.AVATAR_KV.put("dbcache", serialized);
  }

  await env.AVATAR_KV.put(
    "meta",
    JSON.stringify({
      lastFlush: new Date().toISOString(),
      lastAdded: added,
      lastRemoved: removed,
      // Running totals since the Worker started keeping them (so the admin can SEE
      // the numbers climb even when a given flush changed nothing).
      totalAdded: (prevMeta.totalAdded || 0) + added,
      totalRemoved: (prevMeta.totalRemoved || 0) + removed,
      entries,
      lastCommit,
      // For /health (so it needn't list KV): activity seen at this flush.
      pendingBatches: pendNames.length,
      reports: repNames.length,
      // Preserve the migration status marker across flushes (this fresh meta object
      // would otherwise drop it, so a completed migration's `done` stamp vanished).
      migrate: prevMeta.migrate || null,
    })
  );
}
