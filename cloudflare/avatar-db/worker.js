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
        return json({
          ok: true,
          entries: meta.entries || 0,
          pendingBatches: pend.keys.length,
          reports: rep.keys.length,
          lastFlush: meta.lastFlush || null,
          lastAdded: meta.lastAdded || 0,
          lastRemoved: meta.lastRemoved || 0,
          version: 1,
        });
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

async function flush(env) {
  const repo = env.GH_REPO;
  const path = env.DB_PATH;
  const branch = env.GH_BRANCH || "main";
  const apiUrl = `https://api.github.com/repos/${repo}/contents/${path}`;
  const headers = ghHeaders(env);

  // 1. Load the current db.json (+ sha for the update).
  let db = { version: 1, avatars: {} };
  let sha;
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

  const entries = Object.keys(db.avatars).length;

  // 4. Commit only when something actually changed.
  if (added > 0 || removed > 0 || repClear.length > 0) {
    const putBody = {
      message: `avatar-db: +${added} -${removed} (${entries} total)`,
      content: b64encode(JSON.stringify(db)),
      branch,
    };
    if (sha) putBody.sha = sha;
    const putRes = await fetch(apiUrl, {
      method: "PUT",
      headers,
      body: JSON.stringify(putBody),
    });
    if (putRes.status !== 200 && putRes.status !== 201) {
      return; // sha conflict / error -> keep KV, retry next cron
    }
    // Only clear KV after a successful commit, so nothing is lost on failure.
    for (const name of pendKeys) await env.AVATAR_KV.delete(name);
    for (const name of repClear) await env.AVATAR_KV.delete(name);
  }

  await env.AVATAR_KV.put(
    "meta",
    JSON.stringify({
      lastFlush: new Date().toISOString(),
      lastAdded: added,
      lastRemoved: removed,
      entries,
    })
  );
}
