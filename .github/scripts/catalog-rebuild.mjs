// VRC-A catalog rebuild — the heavy job the Worker can't do (128 MB / subrequest caps).
// Runs on a GitHub Actions runner (7 GB RAM), reads every R2 lookup shard over the public
// domain, and rebuilds — WRITING EVERYTHING TO R2 (no GitHub commit, no extra token):
//   - fragments/<3hex>.json  (avatarId -> search summary)
//   - index/<3hex>.json      (token -> [avatarId,...])
//   - db.json                (full master, backup + the admin bots' whole-catalog source)
//   - _manifest.json         (marks search ready + rebuild time)
//
// The Worker's flushR2 keeps the lookup shards fresh (clone path); this keeps SEARCH
// (index/fragments) + the master fresh from those shards. Schedule every ~15-30 min.
// Nothing here is on a user hot path.
//
// Bucket math MUST match the app (AvatarGlobalDb):
//   index token bucket = (token.hashCode() & 0xfff) as 3 hex   (Java String.hashCode)
//   fragment id bucket = the 3 hex after "avtr_"
//
// Env (GitHub Action secrets/vars):
//   CATALOG_DOMAIN        e.g. cdn.gremlininc.app        (public read of shards)
//   R2_ACCOUNT_ID         Cloudflare account id
//   R2_ACCESS_KEY_ID      R2 API token access key id     (read+write objects)
//   R2_SECRET_ACCESS_KEY  R2 API token secret
//   R2_BUCKET             vrca-avatar-catalog
import { AwsClient } from "aws4fetch";
import crypto from "crypto";

const {
  CATALOG_DOMAIN, R2_ACCOUNT_ID, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY, R2_BUCKET,
} = process.env;

for (const [k, v] of Object.entries({ CATALOG_DOMAIN, R2_ACCOUNT_ID, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY, R2_BUCKET })) {
  if (!v) { console.error(`Missing env ${k}`); process.exit(1); }
}

const R2_ENDPOINT = `https://${R2_ACCOUNT_ID}.r2.cloudflarestorage.com`;
const HOT_TOKEN_CAP = 800;        // cap postings per token (bounds a hot bucket)
const READ_CONCURRENCY = 48;      // parallel shard reads over the CDN
const WRITE_CONCURRENCY = 24;     // parallel R2 puts
const HEX = "0123456789abcdef";

const aws = new AwsClient({
  accessKeyId: R2_ACCESS_KEY_ID, secretAccessKey: R2_SECRET_ACCESS_KEY,
  service: "s3", region: "auto",
});

// Java String.hashCode, replicated so token buckets match the app exactly.
function hashCode(s) { let h = 0; for (let i = 0; i < s.length; i++) h = (Math.imul(31, h) + s.charCodeAt(i)) | 0; return h; }
function indexBucket(token) { return ((hashCode(token) & 0xfff) >>> 0).toString(16).padStart(3, "0"); }
function fragBucket(avatarId) { return avatarId.slice(5, 8).toLowerCase(); }
function platMask(platforms) {
  let m = 0; const p = platforms || [];
  if (p.includes("PC")) m |= 1; if (p.includes("Quest")) m |= 2; if (p.includes("iOS")) m |= 4; return m;
}
function tokenize(...fields) {
  const set = new Set();
  for (const f of fields) {
    if (!f) continue;
    for (const w of String(f).toLowerCase().split(/[^\p{L}\p{N}]+/u)) if (w.length >= 2) set.add(w);
  }
  return set;
}

async function mapLimit(items, limit, fn) {
  const out = new Array(items.length); let i = 0;
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (i < items.length) { const idx = i++; out[idx] = await fn(items[idx], idx); }
  }));
  return out;
}

const allPrefixes = [];
for (const a of HEX) for (const b of HEX) for (const c of HEX) allPrefixes.push(a + b + c);

async function fetchShard(prefix) {
  const url = `https://${CATALOG_DOMAIN}/shard/${prefix}.json`;
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      const res = await fetch(url, { headers: { "user-agent": "VRC-A-rebuild" } });
      if (res.status === 404) return {};
      if (res.status === 200) return (await res.json()).e || {};
    } catch (_) {}
    await new Promise((r) => setTimeout(r, 400 * (attempt + 1)));
  }
  console.warn(`shard ${prefix}: read failed after retries (skipped)`);
  return {};
}

async function r2Put(key, bodyStr) {
  const url = `${R2_ENDPOINT}/${R2_BUCKET}/${key}`;
  for (let attempt = 0; attempt < 4; attempt++) {
    const res = await aws.fetch(url, {
      method: "PUT", body: bodyStr,
      headers: { "content-type": "application/json", "cache-control": "public, max-age=3600" },
    });
    if (res.ok) return true;
    await new Promise((r) => setTimeout(r, 500 * (attempt + 1)));
  }
  throw new Error(`R2 put ${key} failed`);
}

// Read a single object via the S3 API (authoritative, uncached — unlike the public CDN).
async function r2Get(key) {
  const url = `${R2_ENDPOINT}/${R2_BUCKET}/${key}`;
  try {
    const res = await aws.fetch(url, { method: "GET" });
    if (res.status === 404) return null;
    if (res.ok) return await res.text();
  } catch (_) {}
  return null;
}

async function r2Delete(key) {
  try { await aws.fetch(`${R2_ENDPOINT}/${R2_BUCKET}/${key}`, { method: "DELETE" }); } catch (_) {}
}

function hashStr(s) { return crypto.createHash("sha1").update(s).digest("base64").slice(0, 20); }

async function main() {
  console.log(`Reading ${allPrefixes.length} shards from ${CATALOG_DOMAIN} ...`);
  // 1. Read every lookup shard -> the full catalog (fileId -> record).
  const avatars = {}; // fileId -> full record
  let readCount = 0;
  await mapLimit(allPrefixes, READ_CONCURRENCY, async (prefix) => {
    const e = await fetchShard(prefix);
    for (const fid of Object.keys(e)) avatars[fid] = e[fid];
    if (++readCount % 512 === 0) console.log(`  read ${readCount}/${allPrefixes.length} shards`);
  });
  const fileIds = Object.keys(avatars);
  console.log(`Loaded ${fileIds.length} avatars.`);
  if (fileIds.length === 0) { console.error("0 avatars read — aborting (won't wipe)"); process.exit(1); }

  // 2. Build fragments (id -> summary) and the token index (token -> ids), bucketed.
  const fragBuckets = {};   // "<3hex>" -> { id -> {f,n,au,ai,p,pf} }
  const idxBuckets = {};    // "<3hex>" -> { token -> Set(id) }
  const avtrBuckets = {};   // "<3hex>" -> Set(avatarId)  — presence index for the crawler dedup
  let unfilled = 0;         // entries the fill bot still needs to enrich (for the Bots-tab backlog)
  for (const fid of fileIds) {
    const a = avatars[fid];
    const id = a.id; if (!id || !id.startsWith("avtr_")) continue;
    if (a.filled !== true) unfilled++;
    const fb = fragBucket(id);
    (avtrBuckets[fb] ||= new Set()).add(id);   // avatar-id presence (same prefix as fragments)
    (fragBuckets[fb] ||= {})[id] = {
      f: fid, n: a.name || "", au: a.author || "", ai: a.authorId || "",
      p: platMask(a.platforms),
      pf: { pc: a.perfPc ?? 5, q: a.perfQuest ?? 5, i: a.perfIos ?? 5 },
    };
    const tokens = tokenize(a.name, a.author, a.desc);
    if (a.authorId) tokens.add(a.authorId.toLowerCase());
    for (const t of tokens) {
      const ib = indexBucket(t);
      const bucket = (idxBuckets[ib] ||= {});
      (bucket[t] ||= new Set()).add(id);
    }
  }

  // 3. Serialize all buckets (fragments + token index + avatar-id presence) + the master.
  const fragEntries = Object.entries(fragBuckets).map(([b, e]) => [`fragments/${b}.json`, JSON.stringify({ v: 1, e })]);
  const idxEntries = Object.entries(idxBuckets).map(([b, toks]) => {
    const t = {};
    for (const [tok, idset] of Object.entries(toks)) {
      let ids = Array.from(idset);
      if (ids.length > HOT_TOKEN_CAP) ids = ids.slice(0, HOT_TOKEN_CAP);
      t[tok] = ids;
    }
    return [`index/${b}.json`, JSON.stringify({ v: 1, t })];
  });
  const avtrEntries = Object.entries(avtrBuckets).map(([b, s]) => [`avtr/${b}.json`, JSON.stringify({ v: 1, ids: Array.from(s) })]);
  const lines = fileIds.map((k) => JSON.stringify(k) + ":" + JSON.stringify(avatars[k]));
  const master = `{"name":"VRC-A Avatar Store","version":1,"count":${fileIds.length},"avatars":{\n${lines.join(",\n")}\n}}`;
  const allObjects = [...fragEntries, ...idxEntries, ...avtrEntries, ["db.json", master]];

  // 4. Hash-DIFF write: only PUT objects whose content changed since the last run, tracked in
  //    `_hashes.json`. This is what keeps R2 writes cheap — a steady catalog writes a handful
  //    of buckets per run instead of all ~16k every time. Delete buckets that vanished.
  let prevHashes = {};
  try { const h = await r2Get("_hashes.json"); if (h) prevHashes = JSON.parse(h); } catch (_) {}
  const newHashes = {};
  const toWrite = [];
  for (const [key, body] of allObjects) {
    const h = hashStr(body);
    newHashes[key] = h;
    if (prevHashes[key] !== h) toWrite.push([key, body]);
  }
  const staleKeys = Object.keys(prevHashes).filter((k) => !(k in newHashes));
  console.log(`Changed: ${toWrite.length}/${allObjects.length} objects; stale to delete: ${staleKeys.length}.`);
  let wrote = 0;
  await mapLimit(toWrite, WRITE_CONCURRENCY, async ([key, body]) => {
    await r2Put(key, body);
    if (++wrote % 256 === 0) console.log(`  wrote ${wrote}/${toWrite.length}`);
  });
  await mapLimit(staleKeys, WRITE_CONCURRENCY, async (key) => { await r2Delete(key); });

  // 5. Manifest + hash-manifest (always written — tiny). Manifest carries the counts; hashes
  //    feed the next run's diff.
  await r2Put("_manifest.json", JSON.stringify({
    v: 1, shardScheme: "filehex3-full", shardCount: 4096, indexScheme: "hash3", entryCount: fileIds.length,
    unfilled, searchReady: true, lastFullRebuild: new Date().toISOString(),
  }));
  await r2Put("_hashes.json", JSON.stringify(newHashes));
  console.log(`Done. ${fileIds.length} avatars (${unfilled} unfilled); wrote ${toWrite.length} changed, deleted ${staleKeys.length}.`);
}

main().catch((e) => { console.error(e); process.exit(1); });
