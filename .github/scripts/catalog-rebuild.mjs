// VRC-A catalog BACKUP — once a day, reads every R2 lookup shard over the public domain and
// writes the full master `db.json` (backup) to R2. That is ALL it writes. The search index
// (fragments/, index/, avtr/), _manifest.json and _worklist.json are owned by the Worker
// (cloudflare/avatar-db/worker.js) and must not be written here (see main(), step 2).
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
const HOT_TOKEN_CAP = 5000;       // cap postings per token — high enough for deep paging
                                  // (~250 pages of 20) while keeping an index bucket small
                                  // (~5000 bare ids ≈ 30 KB gzipped). Ordered-by-rank later.
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

// Returns the shard's entries object {} (200), an EMPTY object for a real 404 (shard never
// created — legitimately empty), or NULL when the read FAILED after all retries. The null case
// is critical: a failed read must NOT be treated as "empty" (that silently drops the shard's
// avatars from the rebuilt master/search/count and deletes their buckets — the "manifest
// randomly regressed to a lower number" bug). The caller aborts the whole rebuild on any null.
async function fetchShard(prefix) {
  const url = `https://${CATALOG_DOMAIN}/shard/${prefix}.json`;
  for (let attempt = 0; attempt < 5; attempt++) {
    try {
      const res = await fetch(url, { headers: { "user-agent": "VRC-A-rebuild" } });
      if (res.status === 404) return {};                      // legitimately empty
      if (res.status === 200) return (await res.json()).e || {};
    } catch (_) {}
    await new Promise((r) => setTimeout(r, 500 * (attempt + 1)));
  }
  console.warn(`shard ${prefix}: read FAILED after retries`);
  return null;                                                 // unknown contents — do NOT drop
}

async function r2Put(key, bodyStr, ttl = 300) {
  const url = `${R2_ENDPOINT}/${R2_BUCKET}/${key}`;
  for (let attempt = 0; attempt < 4; attempt++) {
    const res = await aws.fetch(url, {
      method: "PUT", body: bodyStr,
      // The Action rewrites these every run and can't purge the edge, so keep the TTL short
      // enough that a rebuild propagates: ~5 min for buckets, tiny for the manifest counts.
      headers: { "content-type": "application/json", "cache-control": `public, max-age=${ttl}` },
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
  // 1. Read every lookup shard -> the full catalog (fileId -> record). While reading, record
  //    WHICH shard prefixes contain bot work (unfilled entries, or entries due a liveness
  //    recheck) so the bots can walk ONLY those shards instead of blindly stepping through all
  //    4096 (most of which have no work — that blind scan is why the bot counters "don't move").
  const avatars = {}; // fileId -> full record
  const fillShards = new Set();   // prefixes with >=1 unfilled entry
  const staleShards = new Set();  // prefixes with >=1 entry due a recheck
  // Must match the bot's RECHECK_INTERVAL_MS (7 days) so `stale` == what the bot would recheck.
  const STALE_CUTOFF = Date.now() - 7 * 24 * 60 * 60 * 1000;
  let readCount = 0;
  let failedReads = 0;
  await mapLimit(allPrefixes, READ_CONCURRENCY, async (prefix) => {
    const e = await fetchShard(prefix);
    if (e === null) { failedReads++; return; }   // read failed — counted, guarded below
    for (const fid of Object.keys(e)) {
      const a = e[fid];
      avatars[fid] = a;
      if (a && a.filled !== true) fillShards.add(prefix);
      if (a && typeof a.checked === "number" && a.checked < STALE_CUTOFF) staleShards.add(prefix);
    }
    if (++readCount % 512 === 0) console.log(`  read ${readCount}/${allPrefixes.length} shards`);
  });
  const fileIds = Object.keys(avatars);
  console.log(`Loaded ${fileIds.length} avatars.` + (failedReads ? `  (${failedReads} shard reads FAILED)` : ""));
  if (fileIds.length === 0) { console.error("0 avatars read — aborting (won't wipe)"); process.exit(1); }
  // SAFETY: never publish a SHRUNKEN catalog from an incomplete read. A failed shard read means
  // we don't know that shard's contents, so writing the rebuilt master/search/count + deleting
  // "missing" buckets would drop real avatars (the manifest-regression bug). Read the previous
  // authoritative count and abort (keep everything, retry next run) if any read failed OR the
  // count dropped more than 10% — genuine growth and small bot culls pass through fine.
  let prevCount = 0;
  try { const m = await r2Get("_manifest.json"); if (m) prevCount = JSON.parse(m).entryCount || 0; } catch (_) {}
  if (failedReads > 0) {
    console.error(`ABORT: ${failedReads} shard read(s) failed — not publishing an incomplete catalog (prev ${prevCount}, this run ${fileIds.length}). Retrying next run.`);
    process.exit(1);
  }
  if (prevCount > 500 && fileIds.length < prevCount * 0.9) {
    console.error(`ABORT: count would drop ${prevCount} -> ${fileIds.length} (>10%). Refusing to shrink the catalog; retrying next run.`);
    process.exit(1);
  }

  // 2. BACKUP ONLY (2026-10-26). This job used to rebuild the WHOLE search index (fragments/,
  //    index/, avtr/) plus _manifest/_worklist every 20 min. The Worker has owned all of those
  //    incrementally since the R2 cutover, so the rebuild was pure cost (~144k R2 writes + ~290k
  //    reads a day, most of the bill) and actively harmful: its tokenizer has no fancy-font fold,
  //    so every index bucket it rewrote ERASED the Worker's plain-text font tokens (why plain
  //    "white tiger" never found ᵂᴴᴵᵀᴱ ᵀᴵᴳᴱᴿ). It now only writes the db.json master backup,
  //    once a day, and never touches anything the Worker owns.
  const lines = fileIds.map((k) => JSON.stringify(k) + ":" + JSON.stringify(avatars[k]));
  const master = `{"name":"VRC-A Avatar Store","version":1,"count":${fileIds.length},"avatars":{\n${lines.join(",\n")}\n}}`;
  await r2Put("db.json", master, 3600);
  console.log(`Done. Backed up ${fileIds.length} avatars to db.json (${(master.length / 1e6).toFixed(1)} MB). Search index untouched (Worker-owned).`);
}

main().catch((e) => { console.error(e); process.exit(1); });
