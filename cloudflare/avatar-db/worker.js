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

function cleanEntry(e) {
  return {
    id: e.avatarId,
    name: typeof e.name === "string" ? e.name.slice(0, 100) : "",
    author: typeof e.author === "string" ? e.author.slice(0, 100) : "",
    authorId: typeof e.authorId === "string" ? e.authorId : "",
    platforms: Array.isArray(e.platforms)
      ? e.platforms.filter((p) => typeof p === "string").slice(0, 4)
      : [],
    added: Date.now(),
  };
}

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (req.method === "OPTIONS") return json({ ok: true });
    try {
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
        const cur = JSON.parse((await env.AVATAR_KV.get(key)) || "{}");
        cur.status = body.status === "renamed" ? "renamed" : "dead";
        if (body.status === "renamed" && typeof body.name === "string") {
          cur.name = body.name.slice(0, 100);
        }
        cur.count = (cur.count || 0) + 1;
        await env.AVATAR_KV.put(key, JSON.stringify(cur), { expirationTtl: 30 * 86400 });
        return json({ ok: true });
      }

      if (req.method === "GET" && (url.pathname === "/health" || url.pathname === "/")) {
        // Cheap: KV only, no GitHub call — safe to poll from the admin panel.
        const meta = JSON.parse((await env.AVATAR_KV.get("meta")) || "{}");
        const pend = await env.AVATAR_KV.list({ prefix: "pend:" });
        const rep = await env.AVATAR_KV.list({ prefix: "rep:" });
        const branch = env.GH_BRANCH || "main";
        return json({
          ok: true,
          entries: meta.entries || 0,
          pendingBatches: pend.keys.length,
          reports: rep.keys.length,
          lastFlush: meta.lastFlush || null,
          lastAdded: meta.lastAdded || 0,
          lastRemoved: meta.lastRemoved || 0,
          // Where this Worker WRITES (so a repo/branch/path mismatch is visible):
          repo: env.GH_REPO || "(unset)",
          path: env.DB_PATH || "(unset)",
          branch: branch,
          rawUrl: `https://raw.githubusercontent.com/${env.GH_REPO || "?"}/${branch}/${env.DB_PATH || "?"}`,
          lastCommit: meta.lastCommit || "none",
          adminKeySet: !!env.ADMIN_KEY,
          version: 4,
        });
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
        return json({ ok: true, upserts: upserts.length, removes: removes.length });
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
    ctx.waitUntil(flush(env));
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
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
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

async function flush(env) {
  const repo = env.GH_REPO;
  const path = env.DB_PATH;
  const branch = env.GH_BRANCH || "main";
  const headers = ghHeaders(env);

  // GUARD: never write to an unset/undefined path (that created the "undefined"
  // file). Abort and record the reason so /health shows it.
  if (!path || path === "undefined" || !repo || repo === "undefined") {
    await env.AVATAR_KV.put("meta", JSON.stringify({
      lastFlush: new Date().toISOString(), lastAdded: 0, lastRemoved: 0,
      entries: 0, lastCommit: "ERROR: GH_REPO/DB_PATH unset (repo=" + repo + " path=" + path + ")",
    }));
    return;
  }
  const apiUrl = `https://api.github.com/repos/${repo}/contents/${path}`;

  // 1. Load the current db.json (+ sha for the update).
  let db = { version: 1, avatars: {} };
  let sha;
  let legacySha; // sha of a stray "undefined" file to migrate + delete
  const getRes = await fetch(apiUrl + "?ref=" + encodeURIComponent(branch), { headers });
  if (getRes.status === 200) {
    const j = await getRes.json();
    sha = j.sha;
    try {
      db = JSON.parse(b64decode(j.content));
    } catch (_) {
      db = { version: 1, avatars: {} };
    }
    if (!db.avatars) db.avatars = {};
  } else if (getRes.status !== 404) {
    return; // transient GitHub error — leave KV untouched, retry next cron
  }

  // 2. Merge pending adds (deduped by file id).
  let added = 0;
  const pendKeys = [];
  const pend = await env.AVATAR_KV.list({ prefix: "pend:" });
  for (const k of pend.keys) {
    const val = await env.AVATAR_KV.get(k.name);
    pendKeys.push(k.name);
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
  const rep = await env.AVATAR_KV.list({ prefix: "rep:" });
  for (const k of rep.keys) {
    const fileId = k.name.slice(4);
    const val = await env.AVATAR_KV.get(k.name);
    if (!val) continue;
    let r;
    try {
      r = JSON.parse(val);
    } catch (_) {
      continue;
    }
    if (r.status === "renamed" && db.avatars[fileId] && r.name) {
      db.avatars[fileId].name = r.name;
      repClear.push(k.name);
    } else if (r.status === "dead" && (r.count || 0) >= REMOVE_QUORUM) {
      if (db.avatars[fileId]) {
        delete db.avatars[fileId];
        removed++;
      }
      repClear.push(k.name);
    }
    // Not enough "dead" reports yet -> leave the report in place.
  }

  // 3b. Apply authoritative admin ops (overwrite refreshes + hard removes).
  const admuKeys = [], admrKeys = [];
  let adminChanged = false;
  const admu = await env.AVATAR_KV.list({ prefix: "admu:" });
  for (const k of admu.keys) {
    admuKeys.push(k.name);
    const val = await env.AVATAR_KV.get(k.name);
    if (!val) continue;
    try {
      const batch = JSON.parse(val);
      for (const fid of Object.keys(batch)) { db.avatars[fid] = batch[fid]; adminChanged = true; }
    } catch (_) {}
  }
  const admr = await env.AVATAR_KV.list({ prefix: "admr:" });
  for (const k of admr.keys) {
    admrKeys.push(k.name);
    const val = await env.AVATAR_KV.get(k.name);
    if (!val) continue;
    try {
      const arr = JSON.parse(val);
      for (const fid of arr) { if (db.avatars[fid]) { delete db.avatars[fid]; removed++; adminChanged = true; } }
    } catch (_) {}
  }

  const entries = Object.keys(db.avatars).length;

  // 4. Commit when something changed OR we're migrating the legacy "undefined" file.
  let lastCommit = "no change";
  if (added > 0 || removed > 0 || repClear.length > 0 || legacySha || adminChanged) {
    const putBody = {
      message: `avatar-db: +${added} -${removed} (${entries} total)`,
      content: b64encode(serializeDb(db)),
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
        lastAdded: 0, lastRemoved: 0, entries,
        lastCommit: lastCommit + " FAILED: " + errText.slice(0, 200),
      }));
      return;
    }
    // Only clear KV after a successful commit, so nothing is lost on failure.
    for (const name of pendKeys) await env.AVATAR_KV.delete(name);
    for (const name of repClear) await env.AVATAR_KV.delete(name);
    for (const name of admuKeys) await env.AVATAR_KV.delete(name);
    for (const name of admrKeys) await env.AVATAR_KV.delete(name);
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

  await env.AVATAR_KV.put(
    "meta",
    JSON.stringify({
      lastFlush: new Date().toISOString(),
      lastAdded: added,
      lastRemoved: removed,
      entries,
      lastCommit,
    })
  );
}
