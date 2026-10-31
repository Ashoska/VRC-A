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
const SHARD_TTL = 7 * 24 * 3600; // v53: 7 days (was 6 h). Every content write queues a guaranteed purge (purge queue: retried
// until Cloudflare confirms AND the CDN serves the new ETag), so the TTL is only a safety net (user, 2026-10-30).
// The SEARCH-INDEX buckets (fragments/ avtr/ index/) change constantly (every contribution, fold ADD,
// rename) and are read for search freshness, so they get a SHORT TTL — NOT the 6h SHARD_TTL. With 6h,
// a CDN purge that missed an edge (or a stale re-cache between write and purge) served a stale bucket
// for up to SIX HOURS, so a freshly-indexed avatar appeared then "disappeared" from plain-text search
// for hours (the overnight→morning flicker). At 5 min a purge-miss self-heals fast and search stays
// consistent. Costs a few more R2 Class B (cheap) reads on the search path; clone LOOKUPS are untouched.
const INDEX_TTL = 300; // 5 min — search-index buckets (fragments/avtr/index)
const IDX_PURGE_CAP = 150; // v49: changed search files refreshed on the CDN per flush (5 purge calls max)

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
// NFKC-fold before tokenizing so "fancy" Unicode display names — modifier-letter/superscript caps
// (ᵂᴴᴵᵀᴱ ᵀᴵᴳᴱᴿ), Mathematical-Alphanumeric bold/script (𝗪𝗛𝗜𝗧𝗘), fullwidth (ＷＨＩＴＥ), all VERY common
// on VRChat — index under their PLAIN-ASCII tokens ("white","tiger") instead of the decorative
// codepoints. This makes a plain-text search find a fancy-named/authored avatar. The STORED display
// fields (fragSummary.n/au) stay RAW so the UI still shows the styled name; only the search tokens fold.
// Unicode "small capital" letters (a common VRChat font NFKC does NOT fold) -> plain ASCII, applied
// AFTER NFKC so smallcaps names index/search as plain. MUST stay byte-identical to the app maps
// (AvatarGlobalDb.SMALLCAPS / VrchatAuthManager.SMALLCAPS).
const SMALLCAPS = { "ᴀ":"a","ʙ":"b","ᴄ":"c","ᴅ":"d","ᴇ":"e","ꜰ":"f","ɢ":"g","ʜ":"h","ɪ":"i","ᴊ":"j","ᴋ":"k","ʟ":"l","ᴍ":"m","ɴ":"n","ᴏ":"o","ᴘ":"p","ꞯ":"q","ʀ":"r","ꜱ":"s","ᴛ":"t","ᴜ":"u","ᴠ":"v","ᴡ":"w","ʏ":"y","ᴢ":"z" };
const COMBINING_MARK = /\p{M}/u;   // Unicode General_Category Mark (Mn+Mc+Me)
// Cross-script LOOK-ALIKES of ASCII Latin letters (Cyrillic/Greek/IPA/Latin-extended) that NFKC does NOT
// fold, plus decorative letter-punctuation mapped to "." (a tokenizer separator, i.e. dropped). VRChat
// display names constantly spell Latin words in these look-alikes (ᴛᴜғғʟᴇ, νεκο, аԁміn), so a plain-text
// search can only find them if they fold to ASCII. MUST match the client AvatarGlobalDb.CONFUSABLES /
// VrchatAuthManager.CONFUSABLES byte-for-byte (else index tokens and query tokens disagree). Genuine
// non-Latin scripts (kana/CJK/Hangul/Georgian/…) are intentionally NOT here — they are real names,
// searchable in their own script; the "unconverted" registry surfaces anything still un-folded so the
// map can be extended incrementally.
const CONFUSABLES = {
  "а":"a","А":"a","е":"e","Е":"e","о":"o","О":"o","с":"c","С":"c","х":"x","Х":"x","р":"p","Р":"p","у":"y","У":"y",
  "і":"i","І":"i","ј":"j","Ј":"j","ѕ":"s","Ѕ":"s","к":"k","К":"k","м":"m","М":"m","т":"t","Т":"t","н":"h","Н":"h",
  "в":"b","В":"b","є":"e","Є":"e","ө":"o","Ө":"o","ғ":"f","Ғ":"f","ҽ":"e","Ҽ":"e","ѵ":"v","Ԁ":"d","ԁ":"d","һ":"h","Һ":"h","ԛ":"q","ԝ":"w",
  "α":"a","Α":"a","β":"b","Β":"b","ε":"e","Ε":"e","ι":"i","Ι":"i","κ":"k","Κ":"k","ν":"v","Ν":"n","ο":"o","Ο":"o",
  "ρ":"p","Ρ":"p","τ":"t","Τ":"t","υ":"u","Υ":"y","χ":"x","Χ":"x","η":"n","Η":"h","Ζ":"z","Μ":"m",
  "ɾ":"r","ɳ":"n","ɫ":"l","ɡ":"g","ɐ":"a","ɘ":"e","ɔ":"o","ǝ":"e","ɓ":"b","ø":"o","Ø":"o","đ":"d","Đ":"d","ħ":"h","ı":"i",
  "ǃ":".","ʚ":".","ɞ":".","ǀ":".","ǁ":".","ǂ":".","ˎ":".","ˊ":".","ˋ":".","˗":".",
  // v40: top of the "unconverted" registry (letters used as Latin look-alikes) + upside-down text.
  "я":"r","Я":"r","σ":"o","Σ":"e","Λ":"a","Δ":"a","ł":"l","Ł":"l","Ɇ":"e","ɇ":"e","Ð":"d","ð":"d","ɛ":"e","ƈ":"c","ɨ":"i","Ҝ":"k","ҝ":"k",
  "ʍ":"w","ɥ":"h","ʇ":"t","ƃ":"g","ɹ":"r","ʎ":"y","ɯ":"m","ʞ":"k","ɟ":"f","ʌ":"v"
};
function foldFancy(s) {
  let out = "";
  for (const ch of String(s).normalize("NFKC")) {
    // 1) Strip DECORATIVE combining marks NFKC couldn't compose (zalgo / stacked diacritics, e.g.
    //    sʟᴇᴇͥᴘͫʏᴍͣᴏʟʟɪᴇ). They're SEPARATORS in the /[^\p{L}\p{N}]+/ tokenizer, so left in they FRAGMENT a
    //    fancy name into junk (slee/ym/ollie); stripped it folds to one clean token (sleepymollie).
    if (COMBINING_MARK.test(ch)) continue;
    // 2) Small-caps + cross-script look-alikes -> plain ASCII.
    const m = SMALLCAPS[ch] || CONFUSABLES[ch];
    if (m) { out += m; continue; }
    // 3) Accent-fold ONLY accented LATIN (café -> cafe, Nöel -> Noel): if this single char NFKD-decomposes
    //    and its base is an ASCII letter, keep the base. Composed non-Latin (Hangul 가, CJK) decompose to a
    //    NON-ASCII base, so they're left composed + searchable in their own script (not broken into jamo).
    const d = ch.normalize("NFKD");
    out += (d.length > 1 && /[A-Za-z]/.test(d[0])) ? d[0] : ch;
  }
  return out;
}
// A folded string still carrying a non-ASCII LETTER did NOT fully convert to plain text (an unmapped
// look-alike font, an exotic decoration, or a genuine non-Latin script). Returns the distinct residual
// letter codepoints, which the reconcile lap tallies into the "unconverted" registry (GET /unconverted)
// so unfoldable fonts surface and CONFUSABLES can be extended over time.
function foldResidualCps(e) {
  const cps = new Set();
  for (const f of [e.name, e.author]) {
    if (!f) continue;
    for (const ch of foldFancy(f)) {
      const cp = ch.codePointAt(0);
      if (cp > 127 && /\p{L}/u.test(ch)) cps.add(cp);
    }
  }
  return cps;
}
function tokenizeFields(...fields) {
  const set = new Set();
  for (const f of fields) {
    if (!f) continue;
    // Emit BOTH the RAW-lowercased tokens (fancy codepoints as-is, e.g. "ᵂᴴᴵᵀᴱ") AND the NFKC+smallcaps
    // FOLDED tokens ("white") as ONE set. Emitting both from a SINGLE tokenizer call is what keeps them
    // CONSISTENT: buildIndexOp derives add/rem from tokensOf(old) vs tokensOf(new), so every rename /
    // re-key / remove now touches an entry's raw AND folded tokens TOGETHER. Previously tokenizeFields
    // folded-only while the reconcile had also left the original raw tokens in the buckets as a SEPARATE
    // set — so a rem computed from the folded view stripped "white"/"tiger" while orphaning the raw
    // "ᵂᴴᴵᵀᴱ"/"ᵀᴵᴳᴱᴿ" (the "searchable in fancy font but not plain text" desync). The client union query
    // (raw ∪ folded query tokens) matches either form, so both search modes work off this union index.
    for (const w of String(f).toLowerCase().split(/[^\p{L}\p{N}]+/u)) if (w.length >= 2) set.add(w);
    for (const w of foldFancy(f).toLowerCase().split(/[^\p{L}\p{N}]+/u)) if (w.length >= 2) set.add(w);
  }
  return set;
}
// True when an entry's name/author contain "fancy" Unicode that NFKC folds to different ASCII — i.e.
// it was indexed (before this fold shipped) under decorative tokens and needs a one-time re-index so
// its plain-ASCII tokens enter the search index. Cheap string compare, no allocation on the ASCII path.
// v48: the bio too. It was name + author only, so an avatar whose only fancy text was its bio
// (49k of 346k) never got its plain words indexed unless the bio itself changed: "Rainworldǃ"
// was findable only as "rainworldǃ", never "rainworld" (measured: 35 of 150 sampled bios).
function needsFold(e) {
  const s = (e.name || "") + " " + (e.author || "") + " " + (e.desc || "");
  return foldFancy(s) !== s;
}
// FOLD STAMP (v40). The plain-text (folded) tokens of a fancy entry are written to the index ONCE per
// distinct fold result, not on every liveness check: `e.fz` holds foldSig() of the fold last queued for
// it, so a recheck only emits when the entry's name/author/bio changed or the fold rules (CONFUSABLES)
// changed its result. The old every-check re-assert of the FULL token union flooded the index queue
// (iqDepth pinned at 1000) and, past the subrequest ceiling, was silently dropped (ᵂᴴᴵᵀᴱ ᵀᴵᴳᴱᴿ's 8
// avatars never got "white"/"tiger").
function foldSig(e) {
  return (hashCode(foldFancy(e.name || "") + "|" + foldFancy(e.author || "") + "|" + foldFancy(e.desc || "")) >>> 0).toString(36);
}
// Only the folded tokens the raw tokens lack (the raw ones are indexed on add), so a fold op touches
// a few index buckets instead of the whole union.
function foldOnlyTokens(e) {
  const raw = new Set(), out = new Set();
  for (const f of [e.name, e.author, e.desc]) {
    if (!f) continue;
    for (const w of String(f).toLowerCase().split(/[^\p{L}\p{N}]+/u)) if (w.length >= 2) raw.add(w);
  }
  for (const f of [e.name, e.author, e.desc]) {
    if (!f) continue;
    for (const w of foldFancy(f).toLowerCase().split(/[^\p{L}\p{N}]+/u)) if (w.length >= 2 && !raw.has(w)) out.add(w);
  }
  return out;
}
// A fold op (marked fz:1). These ride the PRIORITY queue (iqp:), drained before the general backlog.
function foldOp(e) {
  return { id: e.id, del: false, frag: null, add: [...foldOnlyTokens(e)], rem: [], avtr: null, fz: 1 };
}
// A pre-v40 every-check re-assert (full union, no marker). Safe to DROP from the backlog: the fold
// stamp re-emits whatever is still needed as a small marked op.
// Priority-lane keys carry their op count ("iqp:<n>:<uuid>") so the flush can report the exact
// number of queued fold ops from the key LIST alone, without reading any values.
function iqpKey(n) { return "iqp:" + n + ":" + crypto.randomUUID(); }
function iqpCount(k) { const n = parseInt(String(k).split(":")[1], 10); return Number.isFinite(n) ? n : 0; }
function isLegacyReassert(o) {
  return !!o && !o.fz && !o.del && !o.frag && !o.avtr && (!o.rem || !o.rem.length);
}
// Distinct R2 objects one op touches (fragment / avtr / index buckets). Each costs ~1 read + <=1 write,
// so the drain budgets BUCKETS, not ops: 142 ops x ~10 tokens needed ~1,400 reads, over the ~1000
// subrequest ceiling, and every read/write past it failed silently while the batch was cleared as done.
function opBuckets(o, into) {
  // v53: only avtr/ is written now (see applyIndexOps).
  const id = o && o.id; if (!id || !String(id).startsWith("avtr_")) return;
  if (o.avtr) into.add("a" + fragBucketFor(id));
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
const MAX_INDEX_OPS_PER_FLUSH = 300;   // raised 150->300: the FOLD_VER re-lap + missing-from-avtr ADDs
// emit faster than 150/flush drained, so the iq: queue ballooned past 1000 keys and never converged.
// The bulk of these ops are NO-OP re-adds (tokens already present → cheap R2 Class B reads, no write /
// no purge via the applyIndexOps guards), and MAX_SHARDS_PER_FLUSH is now 60 (was 180), so the flush
// has ample wall-time headroom for a bigger index drain. 300/flush out-drains the emission so the
// backlog shrinks + the search index converges instead of growing unbounded.
// Bound the CLONE-SHARD writes per flush too. Each distinct shard is 1 R2 read + 1 write, so a burst
// of big USER contribution batches (a 136-avatar batch spans ~136 shards) could push a single flush
// past Cloudflare's per-invocation subrequest limit → the invocation dies BEFORE clearing the `pend:`
// keys → the same batches retry and die every minute ("pending batches stuck at N"). The pend loop
// consumes batches only until this many distinct shards are queued, then leaves the rest for the next
// flush, so a backlog DRAINS over a few flushes.
// Raised 120 -> 180: a large pending backlog (thousands of queued avatars) was drip-fed into
// processable "unfilled" at only 120 shards/2-min cron, so the fill bots drained the ~400 that made it
// through and then sat IDLE waiting for the next flush while thousands of batches waited. This is NOT a
// cost increase — it's the SAME set of dirty shards written once each, just drained in fewer crons
// instead of starving the bots (a no-op dupe shard costs only a Class B read). 180 shards ≈ 360 R2 ops;
// with the grouped index work (≤150 ops), prune (40 reads), reconcile (8) and rename (8) in the same
// cron chain the worst case is ~700 subrequests, still comfortably under the ~1000/invocation budget.
// Admin/bot pushes are separately bounded (WALK_BATCH).
// LOWERED 180 -> 60: at 180 the flush was measured taking >60s (GET /flush timed out) under a heavy
// harvest backlog — each dirty shard is a PUT + a CDN purge (~100-300ms HTTP call), so 180 dirty shards
// alone is tens of seconds of WALL time (the ~1000 subrequest COUNT budget was fine; wall time was not).
// A flush that overruns the invocation is KILLED before the cron's later tasks run, so reconcileIndex
// (the fold re-index) NEVER executed and the iq: queue never drained — the whole pipeline jammed behind
// one overloaded flush. 60 shards keeps each flush well within budget so it COMPLETES and the downstream
// reconcile/drain actually run. Contribution intake is slower, which is fine (and desirable) while the
// passive-harvest inflow is the thing overloading the pipeline.
const MAX_SHARDS_PER_FLUSH = 120;   // raised 60->120: v24 dropped the index-bucket CDN purges that were the
// flush's WALL-time hog (the v21 jam), so there's headroom to place ~2x the shards (and thus ~2x the
// avatars, since small batches already fuse by shard within a flush) per tick. Budget stays safe: worst
// case ~120 shard reads + writes + a lean 300-op index drain + reconcile's bounded reads ≈ ~800
// subrequests, under Cloudflare's ~1000/invocation limit; shard purges are batched 30/call and only fire
// on genuine content change (dupe churn = +0 = no purge). Contribution intake ~doubles; no ingest change.

// ---- Self-chaining flush continuation ----------------------------------------------------------
// The per-invocation ~1000-subrequest ceiling used to cap the drain to ONE cron flush per 2 min, so a
// contribution backlog crept up faster than it cleared. Instead of one big (ceiling-risking) flush,
// each flush that still sees pend work fires a fresh self-invocation (`/flush?cont=<ADMIN_KEY>`), which
// gets a FRESH budget and drains the next chunk — chaining until the queue is empty. A single-flight KV
// lock keeps the 2-min cron from starting a second chain AND keeps reconcile from overlapping a chain's
// flush (which is what raced the shared `meta` record → the pendingBatches/lastFlush flicker). Cost: the
// chain only runs while there's a backlog and idles back to the plain 2-min cron once pend is empty, so
// steady state is unchanged; a drain burst adds a lock op + meta write per step (negligible KV).
const FLUSH_LOCK_TTL_MS = 90_000;      // a chain holds this; the cron skips its cycle while it's fresh
const MAX_CONTINUATION_DEPTH = 8;      // v47: 30 -> 8. Steps now nest (each awaits the next), all inside
                                       // the cron's 15-min limit: 8 x ~70 s ≈ 10 min.
const INDEX_CHAIN_DEPTH = 8;           // v40/v47: chained steps per cron for an INDEX-only backlog (iq:/iqp:)
const DEFAULT_SELF_URL = "https://vrca-avatar-db.shadowash321rulse.workers.dev";  // fallback if WORKER_SELF_URL unset
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

// v49 CALM LANE (user, 2026-10-30): bot REFRESHES of avatars that already exist (a changed name/bio/perf
// on a filled avatar) and font flags don't need to land within 2 minutes, so the bots send them with
// `calm: true` and they wait (as `admc:<ts>:<uuid>` keys, durable in KV) until the oldest is CALM_HOLD_MS
// old, then drain together: several changes to one shard become ONE write. New contributions, fills,
// removals and re-keys stay on the 2-min fast lane, so anything that becomes clonable is also searchable
// in the same flush. Safety: a calm op never CREATES an entry (an avatar removed meanwhile stays removed),
// and every write stamps the entry's `mt` (op time) so an older op can never overwrite a newer one.
const CALM_HOLD_MS = 30 * 60_000;
const CALM_MAX_KEYS = 300;   // drain early if this many calm batches pile up
function calmKey(ts) { return "admc:" + String(ts).padStart(13, "0") + ":" + crypto.randomUUID(); }
function calmKeyTs(k) { const n = parseInt(String(k).split(":")[1], 10); return Number.isFinite(n) ? n : 0; }
// The bots flag an avatar for a font check when its text has ANY non-ASCII character and its `fv` stamp
// isn't the current fold rules version (published as `foldV` in _manifest.json). The Worker re-folds it
// if needed and stamps `fv`, so it's flagged once per rules change, never again. Same test as the bot's.
function hasNonAscii(e) { return /[^\x00-\x7F]/.test((e.name || "") + (e.author || "") + (e.desc || "")); }
// Worker-owned per-entry fields a bot/device payload never carries: keep them across an upsert. When the
// text changed, the upsert's own index op re-indexes it under the CURRENT fold, so fz/fv are re-stamped.
function carryWorkerFields(prev, inc, textChanged) {
  if (!prev) return;
  if (textChanged) {
    if (needsFold(inc)) inc.fz = foldSig(inc);
    if (hasNonAscii(inc)) inc.fv = FOLD_STAMP_V;
  } else {
    if (prev.fz !== undefined) inc.fz = prev.fz;
    if (prev.fv !== undefined) inc.fv = prev.fv;
  }
}
function textChangedBetween(a, b) {
  return !a || !b || (a.name || "") !== (b.name || "") || (a.author || "") !== (b.author || "") || (a.desc || "") !== (b.desc || "");
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
    // desc is tokenized into the index (tokensOf reads e.desc), so a description edit MUST re-index — else
    // searching a word added to a bio wouldn't find it (and a removed word would still match) until the next
    // name/author change or fold re-lap. The shard already rewrites on a desc change (entryEquivalent
    // compares desc), so this only adds the changed-desc token diff (no-op-guarded per token) — bounded by
    // genuine bio edits, not harvest churn.
    (oldE.desc || "") !== (newE.desc || "") ||
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
const INDEX_IO_CONCURRENCY = 6;   // Workers allow 6 simultaneous open connections per invocation
// Run fn over items with at most n in flight.
async function runPool(items, n, fn) {
  let i = 0;
  const worker = async () => { while (i < items.length) { const it = items[i++]; await fn(it); } };
  await Promise.all(Array.from({ length: Math.min(n, items.length) }, worker));
}
async function applyIndexOps(env, ops) {
  // v53 cleanup: search is D1 + nm/ now, so the index pipeline only keeps avtr/ (the free "do we already have
  // this avatar id?" presence files apps and the crawler read). The old fragments/ + index/ writers are gone.
  if (!ops.length) return [];
  // A read that THROWS (vs an absent object) must never be treated as empty (writing back "empty + this batch"
  // would wipe the bucket); skip it and throw at the end so the caller requeues the batch (idempotent).
  let readFailed = false, firstErr = "";
  const fail = (what, e) => { readFailed = true; if (!firstErr) firstErr = what + ": " + String(e && e.message || e).slice(0, 100); };
  const avtrWork = {};   // prefix -> { add:Set, rem:Set }
  for (const o of ops) {
    const id = o.id; if (!id || !String(id).startsWith("avtr_")) continue;
    const fp = fragBucketFor(id);
    if (o.avtr === "a") (avtrWork[fp] ||= { add: new Set(), rem: new Set() }).add.add(id);
    else if (o.avtr === "r") (avtrWork[fp] ||= { add: new Set(), rem: new Set() }).rem.add(id);
  }
  const changed = [];
  const base = env.CATALOG_BASE ? env.CATALOG_BASE.replace(/\/$/, "") : null;
  const ttl = { httpMetadata: { contentType: "application/json", cacheControl: "public, max-age=" + INDEX_TTL } };
  await runPool(Object.keys(avtrWork), INDEX_IO_CONCURRENCY, async (p) => {
    const aw = avtrWork[p];
    let cur = { v: 1, ids: [] };
    try { const o = await env.CATALOG.get(`avtr/${p}.json`); if (o) cur = await o.json(); } catch (e) { fail(`get avtr/${p}`, e); return; }
    if (!cur || !Array.isArray(cur.ids)) cur = { v: 1, ids: [] };
    const s = new Set(cur.ids);
    // Only dirty when membership changes. REM before ADD so ADD wins a same-id conflict: a re-keyed avatar
    // (remove old fileId + add new fileId, same avatar id, one flush) stays present.
    let dirty = false;
    for (const id of aw.rem) { if (s.has(id)) { s.delete(id); dirty = true; } }
    for (const id of aw.add) { if (!s.has(id)) { s.add(id); dirty = true; } }
    if (dirty) {
      try { await env.CATALOG.put(`avtr/${p}.json`, JSON.stringify({ v: 1, ids: Array.from(s) }), ttl);
        if (base) changed.push(base + `/avtr/${p}.json`); } catch (e) { fail(`put avtr/${p}`, e); }
    }
  });
  if (readFailed) throw new Error(firstErr || "avtr bucket read/write failed");
  return changed;
}

export default {
  async fetch(req, env, ctx) {
    const url = new URL(req.url);
    if (req.method === "OPTIONS") return json({ ok: true });
    try {
      // ---- v52 live page (placeholder for the website's live stats) ----------
      // GET /live: one static page; it takes a /health snapshot and then listens on /live/ws, where the
      // hub (Durable Object) pushes each run's events. Public view only: no device ids, no contributor
      // names, no admin data.
      // On the main domain (gremlininc.app, a custom domain of this Worker) the root is the live page too.
      if (req.method === "GET" && (url.pathname === "/live" || (url.pathname === "/" && url.hostname.endsWith("gremlininc.app")))) {
        return new Response(LIVE_HTML, { headers: { "content-type": "text/html; charset=utf-8", "cache-control": "public, max-age=300" } });
      }
      if (req.method === "GET" && url.pathname === "/live/stats") {
        const h = hubStub(env);
        if (!h) return json({ hours: [], days: [] });
        const r = await h.fetch("https://hub/stats");
        return new Response(r.body, { headers: { "content-type": "application/json", "cache-control": "public, max-age=30", "access-control-allow-origin": "*" } });
      }
      if (req.method === "GET" && url.pathname === "/live/ws") {
        const h = hubStub(env);
        if (!h || req.headers.get("Upgrade") !== "websocket") return json({ ok: false, error: "websocket only" }, 426);
        return h.fetch("https://hub/ws", req);
      }
      // ---- R2 catalog serving (edge-cached) --------------------------------
      // GET /catalog/<key> -> the R2 object at <key> (e.g. /catalog/shard/ab1.json).
      // Fallback path when no custom domain is attached; normally CATALOG_BASE points at
      // the bucket's own domain so reads bypass the Worker entirely.
      if (req.method === "GET" && url.pathname.startsWith("/catalog/")) {
        if (!env.CATALOG) return json({ ok: false, error: "R2 not configured" }, 503);
        const key = url.pathname.slice("/catalog/".length);
        if (!key || key.includes("..")) return json({ ok: false, error: "bad key" }, 400);
        // Only what the apps read (never the full backup).
        if (!/^(shard|nm|avtr|index|fragments)\/[0-9a-f]{3}\.json$|^_(manifest|worklist)\.json$/.test(key)) return json({ ok: false, error: "not found" }, 404);
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
          const meta = JSON.parse((await hubGet(env, "meta")) || "{}");
          meta.reports = (meta.reports || 0) + 1;
          await hubPut(env, "meta", JSON.stringify(meta));
          await emit(env, { t: "report", status: cur.status, reports: meta.reports });
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
          // v49: every bot batch carries its time (`mt` newest-wins guard in the flush). `calm: true` =
          // refreshes of existing avatars → the calm lane (see CALM_HOLD_MS); everything else is fast.
          const ts = Date.now(); payload.__ts = ts;
          await env.AVATAR_KV.put(body.calm === true ? calmKey(ts) : "admu:" + crypto.randomUUID(),
            JSON.stringify(payload), { expirationTtl: 7 * 86400 });
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
          const meta = JSON.parse((await hubGet(env, "meta")) || "{}");
          meta.reports = Math.max(0, (meta.reports || 0) - repsResolved);
          await hubPut(env, "meta", JSON.stringify(meta));
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

      if (url.pathname === "/admin/media") {
        // Admin rich-content media (announcement / release pictures, GIFs, videos) on R2 under media/rich/,
        // served at <CATALOG_BASE>/media/rich/<name>. Replaces the GitHub rich-media release.
        // POST ?name=  body = the file  -> { url }   (names are unique per upload, never overwritten)
        // DELETE ?name=                 -> removes it (+ cache clear)
        // GET                           -> { names } (for the admin orphan sweep)
        if (!env.ADMIN_KEY || url.searchParams.get("key") !== env.ADMIN_KEY) return json({ ok: false, error: "unauthorized" }, 401);
        if (req.method === "GET") return json({ ok: true, names: await mediaRichNames(env) });
        const name = url.searchParams.get("name") || "";
        if (!MEDIA_NAME_RE.test(name)) return json({ ok: false, error: "bad name" }, 400);
        const k = "media/rich/" + name;
        if (req.method === "DELETE") {
          await env.CATALOG.delete(k);
          await hubQueuePush(env, "pq", [{ u: catalogUrl(env, k), etag: "", t0: Date.now() }]);
          return json({ ok: true });
        }
        if (req.method === "POST") {
          const len = Number(req.headers.get("content-length") || 0);
          if (!len || len > MEDIA_MAX_BYTES) return json({ ok: false, error: "size" }, 413);
          const fls = new FixedLengthStream(len);
          const pipe = req.body.pipeTo(fls.writable);
          await env.CATALOG.put(k, fls.readable, { httpMetadata: { contentType: mediaType(name), cacheControl: MEDIA_RICH_CC } });
          await pipe;
          return json({ ok: true, url: catalogUrl(env, k) });
        }
        return json({ ok: false }, 405);
      }

      if (req.method === "POST" && url.pathname === "/admin/mediapic") {
        // Replace or add an app picture (?pic=tos/platform_rules&ext=png, body = the file): stored under
        // media/images/, its manifest entry (path, SHA-256, size) updated, both cache-cleared.
        if (!env.ADMIN_KEY || url.searchParams.get("key") !== env.ADMIN_KEY) return json({ ok: false, error: "unauthorized" }, 401);
        const pic = url.searchParams.get("pic") || "", ext = url.searchParams.get("ext") || "png";
        if (!/^[a-z0-9_-]{1,40}\/[A-Za-z0-9_-]{1,60}$/.test(pic) || !/^(png|jpg|jpeg|webp|gif)$/.test(ext)) return json({ ok: false, error: "bad name" }, 400);
        const buf = await req.arrayBuffer();
        if (!buf.byteLength || buf.byteLength > 10 * 1024 * 1024) return json({ ok: false, error: "size" }, 413);
        const path = "images/" + pic + "." + ext;
        const put = await env.CATALOG.put("media/" + path, buf, { httpMetadata: { contentType: mediaType(path), cacheControl: MEDIA_PIC_CC } });
        const man = await mediaManifest(env) || { images: {} };
        man.images[pic] = { path, sha256: await sha256Hex(buf), bytes: buf.byteLength };
        const mput = await mediaManifestPut(env, man);
        await hubQueuePush(env, "pq", [{ u: catalogUrl(env, "media/" + path), etag: put.httpEtag, t0: Date.now() },
          { u: catalogUrl(env, "media/manifest.json"), etag: mput.httpEtag, t0: Date.now() }]);
        return json({ ok: true, entry: man.images[pic] });
      }

      if (req.method === "GET" && url.pathname === "/admin/backupstep") {
        // v45: one step of the daily db.json backup (fired by the cron as its OWN invocation, so it has
        // its own subrequest budget and never holds the flush lock). Token-gated like the chain.
        if (!env.ADMIN_KEY || url.searchParams.get("key") !== env.ADMIN_KEY) {
          return json({ ok: false, error: "unauthorized" }, 401);
        }
        let r = null;
        try { r = await backupStep(env); } catch (e) { r = { error: String(e && e.message || e).slice(0, 200) }; }
        return json({ ok: true, backup: r });
      }

      if (req.method === "GET" && url.pathname === "/admin/reconcile") {
        // Re-arm the ONE-TIME search-index reconciler (resets its cursor + done flag) so it walks all
        // 4096 clone shards again — only needed if a future audit suspects the search index drifted
        // from the clone shards. Normal operation never needs this (the incremental flush maintains it).
        if (!env.ADMIN_KEY || url.searchParams.get("key") !== env.ADMIN_KEY) {
          return json({ ok: false, error: "unauthorized" }, 401);
        }
        const meta = JSON.parse((await hubGet(env, "meta")) || "{}");
        meta.rc = 0; meta.rcDone = false; meta.reconcileScanned = 0; meta.reconcileFixed = 0;
        // v40: ?heal=1 also checks every entry's search presence (budgeted, ~a day); without it the
        // lap is the fast shard-only recount + fold catch-up (~70 min).
        meta.rcHeal = url.searchParams.get("heal") === "1";
        // Reset the recount accumulators + the clean-pass guards so the re-armed pass computes a
        // fresh EXACT count (adopted only if the whole 4096-shard lap reads cleanly).
        meta.rcEntries = 0; meta.rcUnfilled = 0; meta.rcReadFail = 0; meta.rcAttempts = 0; meta.rcAdoptSkipped = 0;
        await hubPut(env, "meta", JSON.stringify(meta));
        return json({ ok: true, reconcile: meta.rcHeal ? "re-armed WITH presence heal (budgeted, ~a day)" : "re-armed (shard-only pass, ~70 min)" });
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
        let recent = []; try { const r = await hubGet(env, "recent"); if (r) recent = JSON.parse(r); } catch (_) {}
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
        if (!env.ADMIN_KEY || url.searchParams.get("key") !== env.ADMIN_KEY) return json({ ok: false, error: "unauthorized" }, 401);
        const obj = (env.PRIVATE && await env.PRIVATE.get("db.json")) || await env.CATALOG.get("db.json");
        return new Response(obj ? obj.body : '{"avatars":{}}', {
          status: 200,
          headers: { "content-type": "application/json", "cache-control": "no-store",
            "access-control-allow-origin": "*" },
        });
      }

      // The "unconverted" registry: everything whose fancy name/author didn't fully fold to ASCII, as a
      // per-codepoint histogram (what to add to CONFUSABLES) + a capped avatar sample. Written by the
      // reconcile lap; this just serves the R2 file (edge-cached).
      if (req.method === "GET" && url.pathname === "/unconverted") {
        if (!env.CATALOG) return json({ ok: false, error: "R2 not configured" }, 503);
        if (!env.ADMIN_KEY || url.searchParams.get("key") !== env.ADMIN_KEY) return json({ ok: false, error: "unauthorized" }, 401);
        try {
          const o = (env.PRIVATE && await env.PRIVATE.get("unconverted.json")) || await env.CATALOG.get("unconverted.json");
          if (!o) return json({ ok: true, pending: true, note: "no lap has completed since the registry shipped" });
          return new Response(o.body, { headers: { "content-type": "application/json", "access-control-allow-origin": "*" } });
        } catch (e) { return json({ ok: false, error: String(e) }, 500); }
      }
      // v53 search (D1). Public; served on gremlininc.app so the edge cache works (and on workers.dev).
      // Only on gremlininc.app: workers.dev has no edge cache, so the speed limit can't work there.
      if (req.method === "GET" && url.pathname === "/search") {
        if (!url.hostname.endsWith("gremlininc.app") && !env.SEARCH_ANY_HOST) return json({ ok: false, error: "use gremlininc.app/search" }, 403);
        return handleSearch(req, env, ctx, url);
      }
      // v53 search step: internal (token), fired by the cron after each flush as its own invocation.
      if (req.method === "GET" && url.pathname === "/searchstep") {
        const cont = url.searchParams.get("cont");
        if (!cont || !env.ADMIN_KEY || cont !== env.ADMIN_KEY) return json({ ok: false }, 403);
        return json({ ok: true, ...(await searchStep(env)) });
      }
      // v53 backfill: copy the catalog into D1 then build every nm/ file (rides the search step, 1/min).
      if (req.method === "POST" && url.pathname === "/admin/searchfill") {
        let body = {}; try { body = await req.json(); } catch (_) {}
        if (!env.ADMIN_KEY || body.key !== env.ADMIN_KEY) return json({ ok: false, error: "unauthorized" }, 401);
        const st = body.phase === 2 ? { phase: 2, next: 0, startedAt: new Date().toISOString() } : { phase: 1, next: 0, startedAt: new Date().toISOString() };
        await hubPut(env, "searchFill", JSON.stringify(st));
        return json({ ok: true, ...st });
      }
      if (req.method === "GET" && url.pathname === "/status") {
        // v56: VRC-A servers status for the app's boot screen and the website: the admin's maintenance switch
        // plus the server checking itself. Cached at the edge for 30 s, so app opens barely reach the Worker.
        return serviceStatus(env, ctx);
      }

      if (req.method === "POST" && url.pathname === "/admin/status") {
        // The admin Config tab's maintenance switch (+ optional message), shown by /status everywhere.
        const body = await req.json().catch(() => null);
        if (!body || !env.ADMIN_KEY || body.key !== env.ADMIN_KEY) return json({ ok: false, error: "unauthorized" }, 401);
        const m = { on: !!body.maintenance, msg: String(body.message || "").slice(0, 200), at: Date.now() };
        await hubPut(env, "maint", JSON.stringify(m));
        try { await caches.default.delete(STATUS_CACHE_KEY); } catch (_) {}
        return json({ ok: true, maintenance: m });
      }

      if (req.method === "GET" && (url.pathname === "/health" || url.pathname === "/")) {
        // ONE cheap KV read (no list ops — those have a tight free-tier limit and this is
        // polled every 15s). Counts come from meta (set at flush).
        const meta = JSON.parse((await hubGet(env, "meta")) || "{}");
        let backupSt = null; try { backupSt = JSON.parse((await env.AVATAR_KV.get("backup")) || "null"); } catch (_) {}
        // Prefer the manifest's authoritative entryCount (Action-maintained); fall back to the
        // KV counter. max() so a fresh contribution that bumped KV past the last rebuild still shows.
        const manCount = await manifestEntryCount(env);
        const liveEntries = manCount >= 0 ? Math.max(manCount, meta.entries || 0) : (meta.entries || 0);
        return json({
          ok: true,
          entries: liveEntries,
          pendingBatches: meta.pendingBatches || 0,
          reports: meta.reports || 0,
          iqDepth: meta.iqDepth || 0,   // search-index op queue depth (keys, ~150 ops each); drains to 0 as fold/rebuild ops apply
          iqpDepth: meta.iqpDepth || 0, // v40 priority lane (fold ops), drained before iqDepth
          foldPending: meta.foldPending || 0, // v40 fancy-font avatars whose plain-text tokens are still queued
          backup: backupLine(backupSt),       // v45 daily db.json backup status
          lastDrain: meta.lastDrain || "",    // v43 last index-drain report (applied/ops/buckets/keys/err)
          iqDropped: meta.iqDropped || 0, // v40 pre-v40 every-check re-asserts dropped from the backlog
          lastFlush: meta.lastFlush || null,
          // v53 search step (D1 + nm/ + purge queue) and backfill progress. purgeStuck = a cache purge has
          // been failing for over 30 min (a stale CDN file is possible until it clears).
          search: await (async () => { try {
            const s = JSON.parse((await hubGet(env, "searchStat")) || "null"), f = JSON.parse((await hubGet(env, "searchFill")) || "null");
            return { d1: !!env.SEARCH && !!(f && (f.done || f.phase === 2)), nmReady: !!(f && f.done), last: s, fill: f, purgeStuck: !!(s && s.purgeOldestMs > PURGE_STUCK_MS) };
          } catch (_) { return null; } })(),
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
          reconcileFoldQueued: meta.reconcileFoldQueued || 0,   // v40 fold ops queued to the priority lane by reconcile
          reconcileHeal: meta.rcHeal === true,                  // v40 presence heal armed (on-demand)
          reconcileDone: !!meta.rcDone,          // one-time heal finished → reconciler idle (flush maintains)
          reconcileDoneAt: meta.rcDoneAt || null,
          // Recount-integrity guards (v8+): shards a recount lap couldn't read, retry attempts, and whether
          // the last completed lap SKIPPED adopting its count because it was tainted (kept the incremental).
          rcReadFail: meta.rcReadFail || 0,
          rcAttempts: meta.rcAttempts || 0,
          rcAdoptSkipped: meta.rcAdoptSkipped || 0,
          // v49: no per-avatar "last checked" any more (the bots walk every shard continuously), so the
          // liveness backlog is always 0; kept for older admin apps that read it.
          stale: 0,
          unfilled: typeof meta.unfilled === "number" ? meta.unfilled : 0,   // live Fill backlog (not just the coalesced manifest)
          fillShards: Array.isArray(meta.fillHint) ? meta.fillHint.length : 0, // shards in _worklist.json (should track unfilled, not balloon)
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
          nmBuckets: 4096,   // v53: clone name files nm/<bucket>.json (the app reads the count here)
          foldVer: meta.foldVer || 0,   // fancy-Unicode fold re-index version (FOLD_VER when the one-time lap is done)
          foldLapV: meta.foldLapV || 0, // FOLD_VER the CURRENT fresh fold lap is dedicated to (== FOLD_VER while it runs)
          unconverted: (backupSt && typeof backupSt.unconverted === "number") ? backupSt.unconverted : (meta.unconverted || 0),   // distinct residual codepoints that don't fold to ASCII (GET /unconverted)
          lastDiag: meta.lastDiag || null,   // last flush's consumed/cleared/shards/failed/ok (cheap: rides the meta read)
          calmWaiting: meta.calmDepth || 0,   // v49 calm-lane batches waiting (bot refreshes + font flags)
          foldV: FOLD_STAMP_V,                // v49 font rules version the bots compare each avatar's fv to
          writesToday: meta.wr || null,       // v49 R2 writes today (UTC): sh = shards, ix = search files, ot = manifest/worklist
          writesYesterday: meta.wrPrev || null,
          version: 56,   // v56: /status (maintenance switch + self-checks) for the boot screen and website.
         //   // v55: /search filters pl + rk (applied on the Search press).
         //   // v54: app pictures + rich media on R2 (media/, copied from GitHub), admin media upload.
         //   // v49: calm lane for bot refreshes (one write per shard), no "last checked" writes (font checks only), fast-lane search purge, no automatic re-index (fold/recount ride the bots + daily backup), adaptive backup steps, write counters.    // v48: the count adopts each finished daily backup's exact count (a one-off +~7,000 miscount had stuck). v47: chain steps await the next step (waitUntil cut them off after ~30 s); depth caps 8. v46: fill-list pruner always runs + recounts unfilled each pass (phantom 1,176 vs 7 real). v45: daily db.json backup built in R2 (multipart, /admin/backupstep, own invocation). v44: record a throwing drain in lastDrain. v43: /health lastDrain report; requeue before deleting drained keys. v42: index bucket IO 6-way parallel (runPool); chain steps run reconcile/prune/rename when due (cronTasksIfDue) so a long drain can't starve them. v41: fold-rule fingerprint re-arms the fold catch-up lap on any CONFUSABLES change. v40: bucket-budgeted index drain (no silent loss past the subrequest ceiling), iqp: priority lane for fold ops, fold stamp (fz) instead of every-check re-asserts, legacy re-asserts dropped, index-backlog chaining, shard-only reconcile (120/run) + on-demand budgeted presence heal, new CONFUSABLES. v39: prototype-free index token maps (a "constructor" token was a poison op). v38: continuation chain confirmed working (backlog drained to 0); removed the temporary
                         // deep-probe/contHits/selfBinding diagnostics. v37: wrap meta-put + recentBatches so flushR2 always RETURNS (chain cascades, counter accurate); v36: CLEAR pend keys BEFORE the index drain (+ cost-cap the drain) so an index-drain
                         // ceiling-throw can't strand batches (the "nothing drains" root cause); v35: deep pend probe; v34: diagnostics for the continuation chain (selfBinding/selfUrl/contHits). v33: SELF-CHAINING flush continuation — a flush that still sees pend work fires
                         // a fresh self-invocation (`/flush?cont=<ADMIN_KEY>`) with a fresh ~1000-subrequest
                         // budget and chains until the queue drains, so the per-invocation ceiling is no
                         // longer a throughput cap (a backlog clears in one chain, not one chunk per 2-min
                         // cron). A single-flight KV lock (`flushlock`) makes the cron yield while a chain
                         // runs AND stops reconcile overlapping a chain's flush → the `meta` read-modify-
                         // write race (pendingBatches/lastFlush flicker) is gone. Idles to the plain cron
                         // when pend is empty. Prior (v32): PER-KEY batch drain — a flush that partial-fails on the ~1000-subrequest
                         // ceiling now clears the batches whose shards persisted and KEEPS only the ones
                         // touching a genuinely-failed shard (was all-or-nothing, which stranded EVERY
                         // batch on a single failed shard → "batches stuck at N, flickering"). No cap
                         // change, so iq/shard throughput is unchanged. `pendingBatches` now reports the
                         // post-drain remaining count (drops each flush). Prior (v31): FOLD is now
                         // maintained SOLELY by the LIVENESS bots' continuous walk — the
                         // reconcile's fold re-emit pass is REMOVED (no more full fold re-laps). A
                         // checked-only bump (bot re-verified an UNCHANGED fancy avatar) re-asserts the
                         // entry's union (raw ∪ folded) tokens with the CURRENT foldFancy, no-op-guarded
                         // by applyIndexOps — so both token sets stay indexed AND a new CONFUSABLES entry
                         // is picked up as the walk re-checks each avatar (~one continuous lap, ~15h),
                         // with NO FOLD_VER bump. Fill bots maintain it via buildIndexOp on the material
                         // `filled` change; name/author changes already re-index. Prior (v30 = the same
                         // liveness fold-ensure but still alongside the reconcile fold lap). And (v29):
                         // applyIndexOps REMS-before-ADDS / SET-before-DEL so a same-flush same-avatarId
                         // conflict resolves ADD/SET-wins, fixing the RE-KEY strip (an avatar whose image
                         // changed is remove(old fileId)+add(new fileId) in one flush; the old add-then-
                         // rem order let the stale rem delete the id the add just inserted → the live
                         // avatar vanished from search, esp. fancy authors the bots re-key). No FOLD_VER
                         // bump for either.
        });
      }

      if (req.method === "GET" && url.pathname === "/flush") {
        // Internal continuation step (fired by fireContinuation with the ADMIN_KEY token). ONLY a
        // token-bearing request may CHAIN — a plain public /flush stays a single flush exactly as
        // before, so /flush being public gains no new blast radius (the lock + depth cap bound a chain
        // to one drain regardless of how it's triggered).
        const cont = url.searchParams.get("cont");
        const isChain = !!cont && !!env.ADMIN_KEY && cont === env.ADMIN_KEY;
        if (isChain) {
          await chainLockPut(env);   // refresh the single-flight lock so the cron keeps yielding
          // v41: while a chain runs, the 2-min cron skips its whole cycle (chainBusy), so a long index
          // drain starved reconcile/prune/author-rename. A chain step runs them itself when due
          // (same lock, so no meta race).
          await cronTasksIfDue(env);
          let res = null;
          try { res = await flushR2(env); } catch (e) { console.log("flush chain err", e); }
          const depth = parseInt(url.searchParams.get("n") || "1", 10) || 1;
          const more = res && ((res.pendRemaining > 0 && res.progressed && depth < MAX_CONTINUATION_DEPTH) ||
            (res.iqRemaining && res.iqProgressed && depth < INDEX_CHAIN_DEPTH));
          if (more) {
            // Hand off a fresh budget AND wait for it, so this request stays open and the next step
            // can't be cut off (v47). The whole chain stays inside the cron's 15-min window via the caps.
            await fireContinuation(env, ctx, depth + 1, true);
          } else {
            await chainLockDel(env);   // drained / capped / no progress → release the lock
          }
          return json({ chained: true, depth, pendRemaining: res ? res.pendRemaining : -1 });
        }
        await flushR2(env);
        const meta = JSON.parse((await hubGet(env, "meta")) || "{}");
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
    // Each task is independent + isolated: one throwing (e.g. a recount KV hiccup) must NOT skip the
    // others. ORDER MATTERS FOR STARVATION: the CHEAP, critical tasks run FIRST so an overloaded flush
    // (measured >60s under a harvest backlog) can't consume the whole invocation and skip them — which
    // is exactly why the fold re-index (reconcileIndex) stopped running and the iq: queue jammed. So:
    // reconcile (re-arm + advance the fold lap, enqueues iq: ops) → prune → author-rename, THEN flushR2
    // LAST (now bounded to 60 shards so it completes and drains the iq: ops the earlier tasks queued).
    ctx.waitUntil((async () => {
      // A continuation chain from a previous cron may still be draining a large backlog. If so, skip
      // this whole cycle — running reconcile/flush now would overlap the chain's flush and race the
      // shared `meta` record (the pendingBatches/lastFlush flicker). The chain finishes fast; the cron
      // just waits one cycle. (A crashed chain self-heals in ≤90s when the lock ages out.)
      // v45 daily db.json backup: one step per cron as its OWN invocation (own budget, no lock).
      // v52: the cron runs every minute, but backup steps stay 2 min apart (their progress record and
      // carry buffer live in KV, which another machine can read ~60 s stale).
      if (new Date(event.scheduledTime || Date.now()).getUTCMinutes() % 2 === 0) {
        try { await maybeFireBackupStep(env, ctx); } catch (_) {}
      }
      if (await chainBusy(env)) { await fireSearchStep(env); return; }
      // Maintenance only when ~2 min have passed (it's every cron at the 2-min cadence; kept in case the
      // cadence ever speeds up, see wrangler.toml for why 1 min needs a Durable Object first).
      await cronTasksIfDue(env);
      // Claim the single-flight lock, run one flush, and if pend work remains hand off to a
      // self-chaining drain (each step a FRESH ~1000-subrequest budget) instead of waiting 2 min for
      // the next cron. Releases the lock when there's nothing left to chain.
      await chainLockPut(env);
      let res = null;
      try { res = await flushR2(env); } catch (e) { console.log("flushR2 err", e); }
      if (res && ((res.pendRemaining > 0 && res.progressed) || (res.iqRemaining && res.iqProgressed))) {
        fireContinuation(env, ctx, 1);
      } else {
        await chainLockDel(env);
      }
      await fireSearchStep(env);   // v53: D1 + nm/ + purge queue, its own invocation (own subrequest budget)
    })());
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
// v40: 24 -> 120. The lap is now SHARD READS ONLY (the per-entry avtr presence check moved to the
// on-demand heal mode below), so a run costs ~120 Class B reads and a full lap ~4,100 reads in ~70 min
// (was 6h+, and each run tried ~1,600 avtr reads, past the ~1000-subrequest ceiling: the failed reads
// counted as "missing" and queued full re-adds, which is most of reconcileFixed's 105k).
const RECONCILE_SHARDS_PER_RUN = 120; // previously: one-time pass → ~5h for a full lap then STOP. Bumped 8→24 to
                                      // roll the fancy-Unicode fold re-index out ~3x faster (plain-text
                                      // search for fancy-named/authored avatars needs the folded tokens
                                      // in the index). COST-NEUTRAL vs the bill emergency: this only adds
                                      // Class B shard READS per run; it does NOT touch MAX_INDEX_OPS_PER_FLUSH
                                      // (the write-rate cap), and the flush drain (75 ops/min) still has
                                      // spare capacity over the raised emission (~97/min), so the TTL-free
                                      // iq: queue absorbs any transient backlog. Stays far under the
                                      // subrequest budget alongside flushR2 (~40 extra reads/run).
// v40 presence HEAL mode (armed only by GET /admin/reconcile?heal=1): also checks every entry against
// its avtr/ presence bucket and re-adds the genuinely missing. Budgeted per run so it never crosses the
// ceiling, and a failed avtr read means "unknown, skip" (never "missing").
const HEAL_AVTR_READS_PER_RUN = 300;
// v40 re-arm: a fresh lap that queues the plain-text (folded) tokens of every fancy entry without a
// current fold stamp into the PRIORITY lane (iqp:), so they don't wait for a 20-day checked push.
// v41: a FINGERPRINT of the fold rules (CONFUSABLES/SMALLCAPS). v49: it no longer re-arms a lap; it is
// published as `foldV` in _manifest.json and stamped on each re-folded avatar as `fv`, and the bots flag
// non-ASCII avatars whose `fv` is behind it as they walk (see hasNonAscii / the flush's font check).
const FOLD_STAMP_V = "r" + (hashCode(JSON.stringify(CONFUSABLES) + JSON.stringify(SMALLCAPS) + "+bio") >>> 0).toString(36);
const RC_MAX_ATTEMPTS = 3;            // retry a tainted (read-failed) count pass this many times, then
                                      // give up and keep the incremental count (never adopt a bad one)
// HISTORICAL (v49: no longer starts anything; only reported in /health). It used to force a one-time
// full fold re-index lap when bumped. Fold changes now reach every avatar via the bots' font flags.
const FOLD_VER = 6;   // bumped 5->6: foldFancy overhaul (strip zalgo marks + CONFUSABLES cross-script
                      // look-alikes + per-char Latin accent-fold), so re-arm ONE fresh full lap to re-fold
                      // the whole catalog under the corrected fold + populate the "unconverted" registry.
                      // Prior note (v4->5, still in force via the foldLapV guard below): the v24 fold lap
                      // could stamp foldVer WITHOUT covering all 4096 shards (bump landing mid-lap).
                      // The reset-to-cursor-0 that starts a fresh fold lap was gated on `&& meta.rcDone`, so a
                      // FOLD_VER bump landing MID-LAP (rcDone=false — the normal case on a deploy during active
                      // churn) left the carried-over cursor + reconcileScanned untouched; foldPass then rode on
                      // top of a partial lap that "completed" the instant reconcileScanned crossed 4096 (from
                      // steps already accumulated) and stamped foldVer having run the fold gate over only a TAIL
                      // slice of shards. Fancy-authored entries outside that slice (e.g. ᵂᴴᴵᵀᴱ ᵀᴵᴳᴱᴿ's 8 avatars)
                      // kept their raw tokens but never got the plain folded ones -> unsearchable by plain text,
                      // and foldPass went dormant so it never retried. The `meta.foldLapV` guard below now forces
                      // exactly ONE dedicated full 0->4096 lap per FOLD_VER (regardless of rcDone) before foldVer
                      // is stamped; this bump re-arms that corrected lap. tokenizeFields still emits raw ∪ folded
                      // so every fancy entry is indexed under BOTH forms as one consistent set.
async function reconcileIndex(env) {
  const meta = JSON.parse((await hubGet(env, "meta")) || "{}");
  // ONE-TIME migration: zero the frozen legacy staleCount NOW (kills the phantom "queued" immediately)
  // and re-arm the recount so the new stale tally computes the true value on its next clean lap.
  if (meta.staleModel !== 1) {
    meta.staleModel = 1; meta.stale = 0;
    meta.rcDone = false; meta.rc = 0; meta.reconcileScanned = 0;
    meta.rcEntries = 0; meta.rcUnfilled = 0; meta.rcStale = 0; meta.rcReadFail = 0; meta.rcAttempts = 0; meta.rcFill = [];
    try {
      let man = {}; const m = await env.CATALOG.get("_manifest.json"); if (m) man = await m.json();
      man = { ...man, staleCount: 0, lastUpdate: new Date().toISOString() };
      await env.CATALOG.put("_manifest.json", JSON.stringify(man),
        { httpMetadata: { contentType: "application/json", cacheControl: "public, max-age=30" } });
      if (env.CATALOG_BASE) await purgeCatalogUrls(env, [env.CATALOG_BASE.replace(/\/$/, "") + "/_manifest.json"]);
    } catch (_) {}
    // fall through → run the recount this run (rcDone is now false)
  }
  // v49 (user, 2026-10-30): NO AUTOMATIC LAPS. A full lap used to start by itself on a FOLD_VER bump, on
  // ANY CONFUSABLES/SMALLCAPS edit (FOLD_STAMP_V) and every 30 days. The fold laps were the costly ones
  // (the Sep/Oct bills): the bots now flag fancy avatars whose `fv` is behind the current rules as they
  // walk, so a rules change reaches every avatar within about a day, a shard at a time. The 30-day
  // recount's jobs (exact unfilled count, fill worklist, "unconverted" registry) moved into the daily
  // backup, which reads every shard anyway. So this lap now runs ONLY when armed by hand
  // (GET /admin/reconcile, optionally ?heal=1), e.g. to repair a damaged index.
  if (meta.rcDone) return;
  let cursor = (typeof meta.rc === "number" ? meta.rc : 0) & 0xfff;
  const avtrCache = {};        // id-bucket -> Set(ids present in the search index)
  const idxCache = {};         // index-bucket -> { token -> Set(ids) } (per-run cache for the fold gate)
  const missing = [];          // ADD index ops for entries not yet indexed (heal mode only)
  const foldOps = [];          // v40 fold ops for fancy entries without a current fold stamp → iqp:
  const heal = meta.rcHeal === true;
  let avtrReads = 0;
  // Accumulate (across the multi-run lap) EVERY shard prefix that still holds an unfilled avatar. On
  // lap completion this becomes the AUTHORITATIVE fill worklist — the fix for the endless whole-catalog
  // "shard walk". The incremental flush only ever added a shard to the worklist when a fresh CONTRIBUTION
  // touched it (fillHintAdd), so PRE-EXISTING unfilled avatars sitting in un-touched shards were never
  // listed. The client, seeing manifestUnfilled > 0 with those shards absent from the worklist, fell back
  // to blind-walking all 4096 shards to FIND them — and re-armed that walk every time the recount grew
  // the count, so it looped forever. With the recount (which reads every shard anyway) writing them into
  // the worklist, the bots walk ONLY the worklist and the walk terminates when it drains.
  const fillSeen = new Set(Array.isArray(meta.rcFill) ? meta.rcFill : []);
  // "UNCONVERTED" registry — accumulate, across the lap, every entry whose folded name/author STILL holds
  // a non-ASCII letter (an unmapped look-alike font, exotic decoration, or genuine non-Latin script). On
  // clean lap completion this is written to `unconverted.json` (GET /unconverted): a per-codepoint
  // histogram (what to add to CONFUSABLES next) + a capped sample of {id,name,author} to eyeball. This is
  // the "fetch everything that didn't convert" tool — extend CONFUSABLES from the histogram, re-lap, repeat.
  const unconvHist = Object.assign({}, meta.rcUnconvHist || {});
  const unconvSample = Array.isArray(meta.rcUnconvSample) ? meta.rcUnconvSample.slice() : [];
  const UNCONV_SAMPLE_CAP = 400;
  // Recount the AUTHORITATIVE entry + unfilled totals as we read every shard, to correct the running
  // incremental counts that drift with no full rebuild — a drifted `unfilled` makes the FILL bots
  // churn the whole catalog forever on a phantom backlog they can never drain ("queued 247, checked
  // 0, stuck at a random number"). Accumulated across the pass into meta, adopted authoritatively on
  // completion. (Slightly high in the rare real-backlog case since fills happen during the ~day pass,
  // but for a PHANTOM count it finds the true near-zero and fixes the churn.)
  let entriesSeen = 0, unfilledSeen = 0, stepped = 0, readFail = 0;
  for (let n = 0; n < RECONCILE_SHARDS_PER_RUN; n++) {
    if (heal && avtrReads >= HEAL_AVTR_READS_PER_RUN) break;   // heal budget spent → resume next run
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
    let shardHasUnfilled = false;
    for (const [fid, e] of Object.entries(shard.e)) {
      const id = e && e.id;
      if (!id || !id.startsWith("avtr_")) continue;
      entriesSeen++;
      if (e.filled !== true) { unfilledSeen++; shardHasUnfilled = true; }
      // Tally anything that didn't fully fold to ASCII (see foldResidualCps / the "unconverted" registry).
      const resid = foldResidualCps(e);
      if (resid.size) {
        for (const cp of resid) unconvHist[cp] = (unconvHist[cp] || 0) + 1;
        if (unconvSample.length < UNCONV_SAMPLE_CAP)
          unconvSample.push({ id, n: (e.name || "").slice(0, 48), au: (e.author || "").slice(0, 48),
            r: Array.from(resid) });
      }
      // v40: fancy entry without a current fold stamp → its plain-text tokens go to the priority lane.
      if (needsFold(e) && e.fz !== foldSig(e)) {
        const fop = foldOp(e);
        if (fop.add.length) foldOps.push(fop);
      }
      if (!heal) continue;   // presence check only in the on-demand heal mode
      const b = fragBucketFor(id);
      if (!(b in avtrCache)) {
        let ids = new Set();
        avtrReads++;
        try { const o = await env.CATALOG.get(`avtr/${b}.json`); if (o) { const j = await o.json(); if (Array.isArray(j.ids)) ids = new Set(j.ids); } }
        catch (_) { ids = null; }   // read failed → UNKNOWN (skip), never "missing"
        avtrCache[b] = ids;
      }
      if (avtrCache[b] && !avtrCache[b].has(id)) {
        const op = buildIndexOp(null, e, fid);   // ADD: writes fragment + avtr + tokens
        if (op) { missing.push(op); avtrCache[b].add(id); }   // add to cache so siblings this run aren't re-flagged
      }
      // NO FOLD RE-EMIT PASS ANYMORE. Fold maintenance (re-asserting a fancy entry's plain/folded
      // tokens) is now owned entirely by the LIVENESS bots' continuous walk (v30): their `checked` bump
      // on a fancy avatar re-asserts its union (raw ∪ folded) tokens, no-op-guarded by applyIndexOps. So
      // a CONFUSABLES addition needs NO FOLD_VER bump and NO full re-lap — liveness picks up the new fold
      // as it re-checks each avatar (~one continuous liveness lap, ~15h at 4 bots). This reconcile still
      // does the recount / unconverted registry / fill-worklist + the genuinely-absent index heal above,
      // just not folding. `foldPass`/`FOLD_VER`/`foldLapV`/`idxCache` are vestigial (kept only so an
      // in-flight lap settles cleanly). DO NOT bump FOLD_VER to "start a new fold" — liveness handles it.
    }
    if (shardHasUnfilled) fillSeen.add(prefix);   // this shard belongs in the fill worklist
  }
  // Enqueue the repairs (TTL-free) — the normal flush drains them via applyIndexOps.
  for (let i = 0; i < missing.length; i += MAX_INDEX_OPS_PER_FLUSH)
    await env.AVATAR_KV.put("iq:" + crypto.randomUUID(), JSON.stringify(missing.slice(i, i + MAX_INDEX_OPS_PER_FLUSH)));
  for (let i = 0; i < foldOps.length; i += MAX_INDEX_OPS_PER_FLUSH) {
    const part = foldOps.slice(i, i + MAX_INDEX_OPS_PER_FLUSH);
    await env.AVATAR_KV.put(iqpKey(part.length), JSON.stringify(part));
  }
  // Keep "Fonts waiting" current between flushes (the next flush recomputes it exactly).
  meta.foldPending = (meta.foldPending || 0) + foldOps.length;
  meta.reconcileFoldQueued = (meta.reconcileFoldQueued || 0) + foldOps.length;
  meta.rcFill = Array.from(fillSeen);   // accumulate the fill-shard set across the lap (adopted on completion)
  meta.rcUnconvHist = unconvHist;       // accumulate the unconverted histogram + sample across the lap
  meta.rcUnconvSample = unconvSample;
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
    meta.rcHeal = false;   // v40: a heal lap is one-shot
    if (clean) {
      meta.rcDone = true; meta.rcDoneAt = new Date().toISOString();
      // Stamp the fold version ONLY when the lap that just completed was the DEDICATED fresh fold lap for
      // this FOLD_VER (foldLapV set at its start, above). A partial/carried-over lap that happens to cross
      // 4096 must NOT mark the fold done — that was the v24 bug that left fancy entries un-folded.
      if (meta.foldLapV === FOLD_VER) meta.foldVer = FOLD_VER;   // fancy-entry fold re-index complete
      // v48: the avatar COUNT is no longer adopted here (the daily backup's exact count is; see flushR2).
      // A lap counts over ~70 min while flushes keep adding, and one lap left a +~7,000 phantom.
      meta.unfilled = meta.rcUnfilled || 0;
      meta.stale = 0;
      meta.rcAdoptSkipped = 0;
      // Push the corrected counts straight to the manifest (what the admin/bots read) + purge.
      try {
        let man = {}; const m = await env.CATALOG.get("_manifest.json"); if (m) man = await m.json();
        man = { ...man, v: 1, shardScheme: "filehex3-full", shardCount: 4096, indexScheme: "hash3",
          entryCount: meta.entries, unfilled: meta.unfilled, staleCount: 0, foldV: FOLD_STAMP_V, searchReady: true, nmBuckets: 4096, lastUpdate: new Date().toISOString() };
        await env.CATALOG.put("_manifest.json", JSON.stringify(man),
          { httpMetadata: { contentType: "application/json", cacheControl: "public, max-age=30" } });
        if (env.CATALOG_BASE) await purgeCatalogUrls(env, [env.CATALOG_BASE.replace(/\/$/, "") + "/_manifest.json"]);
      } catch (_) {}
      // AUTHORITATIVE fill worklist: union every shard the (clean) lap saw holding an unfilled avatar
      // into fillHint and publish _worklist.json. This lists the PRE-EXISTING unfilled shards the
      // incremental flush never added, so the bots stop blind-walking the whole catalog to find them.
      // Union (not replace) preserves shards a mid-lap contribution added; the prune trims any that got
      // filled during the lap → converges to the exact set.
      try {
        const fh = new Set(Array.isArray(meta.fillHint) ? meta.fillHint : []);
        for (const p of (Array.isArray(meta.rcFill) ? meta.rcFill : [])) fh.add(p);
        meta.fillHint = Array.from(fh).slice(0, 4096);
        await env.CATALOG.put("_worklist.json",
          JSON.stringify({ v: 1, ts: new Date().toISOString(), fill: meta.fillHint, stale: [] }),
          { httpMetadata: { contentType: "application/json", cacheControl: "public, max-age=30" } });
        if (env.CATALOG_BASE) await purgeCatalogUrls(env, [env.CATALOG_BASE.replace(/\/$/, "") + "/_worklist.json"]);
      } catch (_) {}
      // Publish the "unconverted" registry (see foldResidualCps): a per-codepoint histogram of every
      // non-ASCII letter that survived folding + a capped sample of the avatars. Fetch GET /unconverted
      // (or /catalog/unconverted.json) to see exactly which fonts still don't convert, extend CONFUSABLES
      // from the histogram, bump FOLD_VER, and they clear on the next lap.
      try {
        const hist = meta.rcUnconvHist || {};
        let total = 0; for (const k in hist) total += hist[k];
        const top = Object.entries(hist).map(([cp, n]) => ({ cp: +cp, ch: String.fromCodePoint(+cp), n }))
          .sort((a, b) => b.n - a.n);
        meta.unconverted = top.length;   // distinct residual codepoints (surfaced in /health)
        await env.CATALOG.put("unconverted.json",
          JSON.stringify({ v: 1, ts: new Date().toISOString(), distinctCodepoints: top.length,
            totalHits: total, codepoints: top, sample: (meta.rcUnconvSample || []) }),
          { httpMetadata: { contentType: "application/json", cacheControl: "public, max-age=60" } });
        if (env.CATALOG_BASE) await purgeCatalogUrls(env, [env.CATALOG_BASE.replace(/\/$/, "") + "/unconverted.json"]);
      } catch (_) {}
      meta.rc = 0; meta.reconcileScanned = 0; meta.rcEntries = 0; meta.rcUnfilled = 0; meta.rcStale = 0;
      meta.rcReadFail = 0; meta.rcAttempts = 0; meta.rcFill = [];   // reset accumulators for a future re-arm
      meta.rcUnconvHist = {}; meta.rcUnconvSample = [];
    } else if (attempts < RC_MAX_ATTEMPTS) {
      // Tainted lap → retry a fresh full pass (index repairs already enqueued above are idempotent).
      meta.rcAttempts = attempts + 1;
      meta.rc = 0; meta.reconcileScanned = 0; meta.rcEntries = 0; meta.rcUnfilled = 0; meta.rcStale = 0; meta.rcReadFail = 0; meta.rcFill = [];
      meta.rcUnconvHist = {}; meta.rcUnconvSample = [];
      // rcDone stays false → the next cron re-walks the whole catalog for a clean count.
    } else {
      // Couldn't get a clean read after the retries → give up and KEEP the incremental count
      // (never overwrite it with an under-counted recount). Re-arm manually later if desired.
      meta.rcDone = true; meta.rcDoneAt = new Date().toISOString();
      meta.rcAdoptSkipped = (meta.rcReadFail || 0);
      meta.rc = 0; meta.reconcileScanned = 0; meta.rcEntries = 0; meta.rcUnfilled = 0; meta.rcStale = 0;
      meta.rcReadFail = 0; meta.rcAttempts = 0; meta.rcFill = [];
      meta.rcUnconvHist = {}; meta.rcUnconvSample = [];
    }
  }
  await hubPut(env, "meta", JSON.stringify(meta));
}

// ---- AUTHOR-RENAME propagation ------------------------------------------------------------------
// When a creator changes their VRChat display name, every catalog entry by them still stores the OLD
// ---- FILL worklist prune (fix the bloated _worklist.json) ---------------------------------------
// _worklist.json lists the shards the FILL bots should walk (shards holding an unfilled avatar). It's
// maintained incrementally in flushR2 (fillHintAdd/fillHintDone) — but fillHintDone only fires for a
// shard the bots PUSH ops for, so a shard that becomes fully-filled AND all-fresh (no work → no ops)
// never gets removed. Over time the worklist LEAKED (observed: 761 listed shards, ALL fully filled,
// vs ~47 real unfilled avatars). The bots then sweep hundreds of already-done shards for nothing, so
// the "queued" number sits flat (nothing is being filled to subtract). This checks the FRONT batch of
// the worklist each cron and drops any shard with zero unfilled avatars, rotating checked-and-kept
// shards to the back — so the whole worklist self-corrects to the true set in a few minutes. It runs
// ONLY while the worklist is implausibly larger than the unfilled count (bloat present), so once clean
// it costs nothing; it re-arms automatically if the leak recurs.
// v46: it ALWAYS runs while the list is non-empty. The old gate (skip while length <= unfilled + 20)
// compared the list against the `unfilled` COUNTER, which had drifted too (2026-10-26: 1,176 "unfilled"
// and 1,012 listed shards vs 7 real unfilled avatars), so the pruner never ran and the bots kept reading
// empty fill shards. It also RE-COUNTS: unfilled avatars can only live in listed shards (new ones are
// added by the flush), so after each full pass over the list `unfilled` is set to what the pass found.
const PRUNE_BATCH = 100;           // shards checked per cron (Class B reads; ~list size once it's clean)
async function pruneFillWorklist(env) {
  const meta = JSON.parse((await hubGet(env, "meta")) || "{}");
  let fill = Array.isArray(meta.fillHint) ? meta.fillHint : null;
  if (!fill || fill.length === 0) {
    if (meta.unfilled) { meta.unfilled = 0; await hubPut(env, "meta", JSON.stringify(meta)); }
    return;
  }
  // Pass bookkeeping: a pass = as many shards as the list held when it started.
  if (!(meta.prunePassLeft > 0)) { meta.prunePassLeft = fill.length; meta.prunePassUnfilled = 0; }
  const batch = fill.slice(0, Math.min(PRUNE_BATCH, meta.prunePassLeft));
  const kept = [];
  let changed = false;
  for (const sp of batch) {
    let shard = null, failed = false;
    try { const o = await env.CATALOG.get(`shard/${sp}.json`); if (o) shard = await o.json(); }
    catch (_) { failed = true; }
    if (failed) { kept.push(sp); continue; }                            // transient read fail → keep, re-check later
    const e = (shard && shard.e) || {};
    const n = Object.values(e).filter((x) => x && x.id && String(x.id).startsWith("avtr_") && x.filled !== true).length;
    meta.prunePassUnfilled = (meta.prunePassUnfilled || 0) + n;
    if (n > 0) kept.push(sp);                                           // still has work → keep
    else changed = true;                                               // fully filled → drop it
  }
  const rest = fill.slice(batch.length);
  fill = [...rest, ...kept];   // rotate: checked-kept go to the back so the next run checks fresh ones
  meta.fillHint = fill;
  meta.prunePassLeft = Math.max(0, meta.prunePassLeft - batch.length);
  if (meta.prunePassLeft === 0) {
    meta.unfilled = meta.prunePassUnfilled || 0;   // exact "Fills waiting" for the pass just finished
    meta.lastFillRecount = new Date().toISOString();
  }
  if (changed) {
    try {
      await env.CATALOG.put("_worklist.json",
        JSON.stringify({ v: 1, ts: new Date().toISOString(), fill, stale: [] }),
        { httpMetadata: { contentType: "application/json", cacheControl: "public, max-age=30" } });
      if (env.CATALOG_BASE) await purgeCatalogUrls(env, [env.CATALOG_BASE.replace(/\/$/, "") + "/_worklist.json"]);
    } catch (_) {}
  }
  await hubPut(env, "meta", JSON.stringify(meta));
}

// `author`. The bots detect it authoritatively (their GET /avatars/{id} is real VRChat data) and queue
// `arn:<authorId>` = newName. This walks the catalog (bounded per run, ONE author at a time, keyed on
// the immutable authorId) and rewrites `author` on every matching entry — reusing buildIndexOp so the
// search index (fragment `au` + author tokens) updates through the normal iq: pipeline. Dirty-only
// writes; runs ONLY while a rename is pending, so steady-state cost is zero. Rare event → slow is fine.
const RENAME_SHARDS_PER_RUN = 8;   // ~8.5h to sweep the whole catalog for one author's rename
async function propagateAuthorRenames(env) {
  const meta = JSON.parse((await hubGet(env, "meta")) || "{}");
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
  await hubPut(env, "meta", JSON.stringify(meta));
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
    try { const m = JSON.parse((await hubGet(env, "meta")) || "{}"); await hubPut(env, "meta", JSON.stringify({ ...m, ...patch })); } catch (_) {}
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

// ---- continuation-chain helpers (see the FLUSH_LOCK_TTL_MS block above) --------------------------
function selfBase(env) { return (env.WORKER_SELF_URL || DEFAULT_SELF_URL).replace(/\/$/, ""); }
async function chainBusy(env) {
  try {
    const v = await hubGet(env, "flushlock");
    if (!v) return false;
    return (Date.now() - (parseInt(v, 10) || 0)) < FLUSH_LOCK_TTL_MS;
  } catch (_) { return false; }
}
async function chainLockPut(env) { try { await hubPut(env, "flushlock", String(Date.now())); } catch (_) {} }
// The cron's maintenance steps (each isolated: one throwing must not skip the others). Stamped in
// KV so a chain step can tell whether they're due (CRON_TASKS_EVERY_MS).
const CRON_TASKS_EVERY_MS = 110_000;
async function cronTasks(env) {
  try { await env.AVATAR_KV.put("crontasks", String(Date.now()), { expirationTtl: 3600 }); } catch (_) {}
  try { await reconcileIndex(env); } catch (e) { console.log("reconcile err", e); }
  try { await pruneFillWorklist(env); } catch (e) { console.log("prune err", e); }
  try { await propagateAuthorRenames(env); } catch (e) { console.log("arn err", e); }
}
// ---- v45 daily db.json backup, built inside R2 (replaces the removed GitHub catalog-rebuild) ----
// The catalog is too big for one Worker invocation's memory/subrequests, so the backup is a MULTIPART
// upload built across ~20 cron steps: each step reads BACKUP_SHARDS_PER_STEP shards, appends their
// entries in the master's line format ("file_…":{…} one per line, which reshardFromMaster parses),
// uploads every full BACKUP_PART_BYTES chunk, and carries the remainder in KV. The last step adds
// the footer and completes the upload, which swaps the new db.json in atomically; until then the
// previous backup stays intact. Cost/day: ~4,100 Class B shard reads + ~35 Class A (parts + create/
// complete) + ~45 KV writes, i.e. effectively free; it never touches the index, meta or flush lock.
const BACKUP_EVERY_MS = 24 * 60 * 60 * 1000;
const BACKUP_SHARDS_PER_STEP = 200;
// v49: a step also stops once it holds this much JSON, so the Worker's 128 MB memory stays safe as the
// catalog grows (at 5M avatars 200 shards would be ~90 MB; this keeps a step's peak well under the limit).
const BACKUP_STEP_CHARS = 16 * 1024 * 1024;
const BACKUP_UNCONV_SAMPLE_CAP = 400;
const BACKUP_PART_BYTES = 5 * 1024 * 1024;        // R2: every part but the last must be this size
const BACKUP_STALE_MS = 6 * 60 * 60 * 1000;       // an upload stuck this long is aborted + restarted
async function maybeFireBackupStep(env, ctx) {
  if (!env.ADMIN_KEY || !env.CATALOG) return;
  const st = JSON.parse((await env.AVATAR_KV.get("backup")) || "{}");
  const due = st.uploadId || !st.doneAt || Date.now() - st.doneAt >= BACKUP_EVERY_MS;
  if (!due) return;
  const path = `/admin/backupstep?key=${encodeURIComponent(env.ADMIN_KEY)}`;
  if (env.SELF && typeof env.SELF.fetch === "function") ctx.waitUntil(env.SELF.fetch("https://self" + path).catch(() => {}));
  else ctx.waitUntil(fetch(selfBase(env) + path).catch(() => {}));
}
// v53 (user, 2026-10-30: "we don't want people ripping our stuff"): the full backup lives in the PRIVATE
// bucket (vrca-avatar-private, no public domain), never on cdn.gremlininc.app. The first private backup
// deletes the old public db.json + unconverted.json. /db needs the admin key.
function backupBucket(env) { return env.PRIVATE || env.CATALOG; }
async function backupStep(env) {
  const KV = env.AVATAR_KV;
  let st = JSON.parse((await KV.get("backup")) || "{}");
  const now = Date.now();
  const where = env.PRIVATE ? "private" : "catalog";
  if (st.uploadId && (now - (st.startedAt || 0) > BACKUP_STALE_MS || (st.bucket || "catalog") !== where)) {
    try { await (st.bucket === "private" && env.PRIVATE ? env.PRIVATE : env.CATALOG).resumeMultipartUpload("db.json", st.uploadId).abort(); } catch (_) {}
    st = { doneAt: (st.bucket || "catalog") !== where ? 0 : st.doneAt, lastCount: st.lastCount, lastBytes: st.lastBytes, lastErr: "upload restarted" };
  }
  const enc = new TextEncoder();
  let carry;
  if (!st.uploadId) {
    if (st.doneAt && now - st.doneAt < BACKUP_EVERY_MS) return { skipped: "not due" };
    const up = await backupBucket(env).createMultipartUpload("db.json", {
      httpMetadata: { contentType: "application/json", cacheControl: "private, no-store" },
    });
    // v49: uf/fill/hist/sample = the old monthly recount's jobs, done here (the backup reads every shard):
    // exact unfilled count, shards holding unfilled avatars (fill worklist), "unconverted" font registry.
    st = { ...st, uploadId: up.uploadId, bucket: where, next: 0, parts: [], count: 0, bytes: 0, startedAt: now,
      uf: 0, fill: [], hist: {}, sample: [] };
    carry = enc.encode('{"name":"VRC-A Avatar Store","version":1,"avatars":{\n');
  } else {
    const b = await KV.get("backup:buf", "arrayBuffer");
    carry = b ? new Uint8Array(b) : new Uint8Array(0);
  }
  const upload = backupBucket(env).resumeMultipartUpload("db.json", st.uploadId);
  // Read this step's shards (a failed read aborts the step WITHOUT saving, so it's retried).
  const chunks = [];
  let count = st.count;
  let uf = st.uf || 0;
  const fill = new Set(Array.isArray(st.fill) ? st.fill : []);
  const hist = Object.assign({}, st.hist || {});
  const sample = Array.isArray(st.sample) ? st.sample.slice() : [];
  const stepEnd = Math.min(4096, st.next + BACKUP_SHARDS_PER_STEP);
  let chars = 0, n = st.next;
  for (; n < stepEnd; n++) {
    if (n > st.next && chars >= BACKUP_STEP_CHARS) break;   // memory cap: the rest go in the next step
    const prefix = n.toString(16).padStart(3, "0");
    const o = await env.CATALOG.get(`shard/${prefix}.json`);   // throws on a real failure → step retried
    if (!o) continue;
    const shard = await o.json();
    let shardUnfilled = false;
    for (const [fid, e] of Object.entries((shard && shard.e) || {})) {
      const line = (count > 0 ? ",\n" : "") + JSON.stringify(fid) + ":" + JSON.stringify(e);
      chunks.push(line); chars += line.length;
      count++;
      if (!e || typeof e !== "object") continue;
      if (e.filled !== true) { uf++; shardUnfilled = true; }
      const resid = foldResidualCps(e);
      if (resid.size) {
        for (const cp of resid) hist[cp] = (hist[cp] || 0) + 1;
        if (sample.length < BACKUP_UNCONV_SAMPLE_CAP)
          sample.push({ id: e.id, n: (e.name || "").slice(0, 48), au: (e.author || "").slice(0, 48), r: Array.from(resid) });
      }
    }
    if (shardUnfilled) fill.add(prefix);
  }
  const end = n;
  const last = end >= 4096;
  const startBytes = st.next === 0 ? carry.length : 0;   // the header is counted once
  if (last) chunks.push(`\n},"count":${count}}`);
  const add = enc.encode(chunks.join(""));
  let buf = new Uint8Array(carry.length + add.length);
  buf.set(carry, 0); buf.set(add, carry.length);
  const parts = st.parts.slice();
  let off = 0;
  while (buf.length - off >= BACKUP_PART_BYTES) {
    const part = await upload.uploadPart(parts.length + 1, buf.slice(off, off + BACKUP_PART_BYTES));
    parts.push({ partNumber: part.partNumber, etag: part.etag });
    off += BACKUP_PART_BYTES;
  }
  buf = buf.slice(off);
  if (last) {
    if (buf.length || !parts.length) {
      const part = await upload.uploadPart(parts.length + 1, buf);
      parts.push({ partNumber: part.partNumber, etag: part.etag });
    }
    await upload.complete(parts);
    const bytes = (st.bytes || 0) + startBytes + add.length;
    const done = { doneAt: Date.now(), lastCount: count, lastBytes: bytes, lastMs: Date.now() - st.startedAt,
      lastUnfilled: uf, fill: Array.from(fill) };
    // v48: snapshot the flush totals so the flush can adopt this exact count (+ what moved since).
    // v49: + the running unfilled-delta total, so the flush adopts the exact unfilled count the same way.
    try { const m = JSON.parse((await hubGet(env, "meta")) || "{}"); done.addedAt = m.totalAdded || 0; done.removedAt = m.totalRemoved || 0; done.ufAt = m.ufSum || 0; } catch (_) {}
    // v49: publish the "unconverted" registry (was the recount's): every non-ASCII letter that survives
    // folding (histogram) + a capped sample. GET /unconverted. Extend CONFUSABLES from it; the bots then
    // flag and re-fold the affected avatars as they walk.
    try {
      let total = 0; for (const k in hist) total += hist[k];
      const top = Object.entries(hist).map(([cp, k]) => ({ cp: +cp, ch: String.fromCodePoint(+cp), n: k })).sort((a, b) => b.n - a.n);
      done.unconverted = top.length;
      await backupBucket(env).put("unconverted.json",
        JSON.stringify({ v: 1, ts: new Date().toISOString(), distinctCodepoints: top.length, totalHits: total, codepoints: top, sample }),
        { httpMetadata: { contentType: "application/json", cacheControl: "private, no-store" } });
    } catch (_) {}
    // The first private backup removes the old public copies (and clears them from the CDN).
    if (env.PRIVATE) {
      try { await env.CATALOG.delete(["db.json", "unconverted.json"]); } catch (_) {}
      if (env.CATALOG_BASE) await hubQueuePush(env, "pq", ["db.json", "unconverted.json"].map((k) => ({ u: catalogUrl(env, k), etag: "", t0: Date.now() })));
    }
    await KV.put("backup", JSON.stringify(done));
    try { await KV.delete("backup:buf"); } catch (_) {}
    return { done: true, count, parts: parts.length, bytes };
  }
  await KV.put("backup:buf", buf);
  await KV.put("backup", JSON.stringify({ ...st, next: end, parts, count,
    bytes: (st.bytes || 0) + startBytes + add.length, uf, fill: Array.from(fill), hist, sample }));
  return { next: end, count, parts: parts.length };
}
/** One-line backup status for /health ("Backup" row in the admin Bots tab). */
function backupLine(st) {
  if (!st) return "";
  if (st.uploadId) return `running ${st.next || 0}/4096 shards, ${st.count || 0} avatars`;
  if (st.doneAt) return `${new Date(st.doneAt).toISOString().slice(0, 16).replace("T", " ")} UTC, ${st.lastCount} avatars, ` +
    `${((st.lastBytes || 0) / 1e6).toFixed(0)} MB` + (st.lastErr ? ` (${st.lastErr})` : "");
  return "";
}

async function fireSearchStep(env) {
  try {
    if (env.SELF && typeof env.SELF.fetch === "function" && env.ADMIN_KEY) await env.SELF.fetch("https://self/searchstep?cont=" + encodeURIComponent(env.ADMIN_KEY));
    else await searchStep(env);
  } catch (e) { console.log("searchstep err", e); }
}
async function cronTasksIfDue(env) {
  let last = 0;
  try { last = parseInt((await env.AVATAR_KV.get("crontasks")) || "0", 10) || 0; } catch (_) {}
  if (Date.now() - last >= CRON_TASKS_EVERY_MS) await cronTasks(env);
}
async function chainLockDel(env) { try { await hubDel(env, "flushlock"); } catch (_) {} }
// v47: returns the step's promise. A CHAIN STEP must AWAIT it (see the /flush chain handler): a fetch
// handler's ctx.waitUntil only lives ~30 s after it responds, but a step takes ~60-70 s, so a step
// fired via waitUntil from another step was cut off mid-flush every time (meta never saved, lock left
// held, work redone later). The cron's scheduled handler may use waitUntil (it lives up to 15 min).
function fireContinuation(env, ctx, depth, awaitIt = false) {
  if (!env.ADMIN_KEY) return Promise.resolve();   // chaining is gated on the internal token
  const path = `/flush?cont=${encodeURIComponent(env.ADMIN_KEY)}&n=${depth}`;
  const go = (p) => { const q = p.catch(() => {}); if (!awaitIt) ctx.waitUntil(q); return q; };
  // PREFER the self service-binding (env.SELF) — a plain fetch() to our own workers.dev hostname is an
  // unreliable way to self-invoke (Cloudflare loop-guard / edge-cache can drop it, which is why the
  // chain didn't fire on first deploy). The service binding routes straight to a fresh invocation of
  // THIS worker (fresh subrequest budget) with no DNS/cache in the path. Global fetch is the fallback.
  try {
    if (env.SELF && typeof env.SELF.fetch === "function") return go(env.SELF.fetch("https://self" + path));
  } catch (_) {}
  return go(fetch(selfBase(env) + path));
}

async function flushR2(env) {
  const runStart = Date.now();
  const prevMeta = JSON.parse((await hubGet(env, "meta")) || "{}");
  // iq: index-op queue depth (in KEYS, each ≈ up to MAX_INDEX_OPS_PER_FLUSH ops), stamped from the
  // drain below so /health can surface it WITHOUT its own list op (that endpoint is polled every 15s
  // and must stay list-free). Carried forward on a failed flush (drain skipped). Saturates at the
  // listPrefix cap (1000 keys); at ~150 ops/key that's ~150k queued ops before it stops being exact.
  let iqDepthNow = prevMeta.iqDepth || 0;
  let iqpDepthNow = prevMeta.iqpDepth || 0;   // v40 priority lane (fold ops)
  let iqDroppedNow = 0;                       // v40 legacy re-asserts dropped from the backlog this flush
  let iqLeft = false, iqMoved = false;        // v40 index-backlog chaining signals
  let foldPendingNow = prevMeta.foldPending || 0;   // v40 "Fonts waiting" (fold ops queued)
  let lastDrainNow = prevMeta.lastDrain || "";      // v43 drain report
  let idxWrites = 0;                                // v49 search-file writes this flush (write counters)
  const pendNames = await listPrefix(env, "pend:");
  const repNames  = await listPrefix(env, "rep:");
  const admuNames = await listPrefix(env, "admu:");
  const admrNames = await listPrefix(env, "admr:");
  const admkNames = await listPrefix(env, "admk:");
  // v49 calm lane: drained only once its OLDEST batch has waited CALM_HOLD_MS (or too many piled up).
  const admcNames = await listPrefix(env, "admc:");
  const nowMs = Date.now();
  const calmDue = admcNames.length > 0 &&
    (nowMs - calmKeyTs(admcNames[0]) >= CALM_HOLD_MS || admcNames.length >= CALM_MAX_KEYS);
  const hasIndexQueue = (await env.AVATAR_KV.list({ prefix: "iq:", limit: 1 })).keys.length > 0 ||
    (await env.AVATAR_KV.list({ prefix: "iqp:", limit: 1 })).keys.length > 0;

  // Nothing to do → skip the whole flush (no shard reads, no meta write). This stops the
  // every-minute cron from writing `meta` ~1440×/day while the catalog is idle. Reports and the
  // index carry-forward queue keep the flush active only while any exist; a fully-idle catalog
  // costs just one KV list per minute.
  if (!pendNames.length && !admuNames.length && !admrNames.length && !admkNames.length &&
      !repNames.length && !hasIndexQueue && !calmDue) {
    // Keep /health's "calm waiting" count current without a list op there (cheap: only when it moved).
    if ((prevMeta.calmDepth || 0) !== admcNames.length)
      try { await hubPut(env, "meta", JSON.stringify({ ...prevMeta, calmDepth: admcNames.length })); } catch (_) {}
    return;
  }

  // ONE-TIME iq: COMPACTION. The pre-gating fold laps flooded iq: with ~1400 keys of no-op re-adds
  // (already-present tokens). They drain only ~1 key/flush (~300 cheap-but-full ops), so ~47h to clear,
  // and genuine repairs (e.g. a desynced fancy author's avatars) are buried in random UUID order behind
  // them. They are SAFE to drop: applyIndexOps already no-op-guarded them (nothing pending changes a
  // bucket), and the re-armed GATED fold lap (FOLD_VER bump) + the reconcile presence-heal re-emit
  // anything genuinely missing into the now-empty queue, where it drains in a flush or two. Bounded to
  // IQ_COMPACT_DELETE_BUDGET deletes/flush to stay under the subrequest limit; a meta version flag makes
  // it run once. While compacting we dedicate the flush to it (skip the normal shard/index work) and
  // write meta so /health reflects progress — it completes in a handful of 1-min flushes.
  const IQ_COMPACT_V = 1, IQ_COMPACT_DELETE_BUDGET = 500;
  if ((prevMeta.iqCompactV || 0) < IQ_COMPACT_V) {
    const stale = await listPrefix(env, "iq:", IQ_COMPACT_DELETE_BUDGET);
    for (const kn of stale) await env.AVATAR_KV.delete(kn);
    const done = (await env.AVATAR_KV.list({ prefix: "iq:", limit: 1 })).keys.length === 0;
    await hubPut(env, "meta", JSON.stringify({
      ...prevMeta, iqDepth: done ? 0 : (prevMeta.iqDepth || 0),
      ...(done ? { iqCompactV: IQ_COMPACT_V } : {}),
      lastFlush: new Date().toISOString(),
      lastCommit: done ? "iq: compaction done" : `iq: compacting (-${stale.length} keys)`,
    }));
    return;   // resume normal flushing next tick (queue now empty; gated lap re-emits only broken)
  }

  // Group every pending op by shard prefix so each shard is read + written ONCE.
  const shardOps = {};
  // upTs = each upsert's op time (newest wins across both lanes); calmOnly = fids whose winning op is a
  // calm refresh (may never CREATE an entry).
  const S = (sp) => (shardOps[sp] ||= { adds: {}, upserts: {}, upTs: {}, calmOnly: new Set(), removes: new Set(), checked: new Set(), renames: {} });

  const pendKeys = [];
  const recentBatches = [];   // admin "recent contributions" view (free: built from data already read)
  // PER-KEY shard sets + the set of shards whose R2 IO FAILED this flush. The KV-clear below deletes a
  // key ONLY when none of the shards it touched failed — REPLACING the old all-or-nothing "clear only
  // if EVERY shard wrote OK". A single failed shard write (e.g. brushing the ~1000 subrequest ceiling)
  // used to strand EVERY batch in the flush → the queue "stuck at N, flickering" churning forever
  // (re-reading + partially re-writing the same batches every 2 min, thrown away). Now a partial
  // failure keeps ONLY the batches touching the genuinely-failed shards; the rest drain, so the queue
  // makes real progress every flush. No cap change → no throughput/iq slowdown.
  const failedShards = new Set();
  const pendKeyShards = new Map(), admuKeyShards = new Map(), admrKeyShards = new Map(),
        admkKeyShards = new Map(), repKeyShards = new Map(), admcKeyShards = new Map();
  const shardsOf = (fids) => { const s = new Set(); for (const fid of fids) if (FILE_RE.test(fid)) s.add(shardPrefix(fid)); return s; };
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
    pendKeyShards.set(kn, shardsOf(fids));
    const bts = typeof batch.__ts === "number" ? batch.__ts : nowMs;
    for (const fid of fids) { const ne = batch[fid]; if (ne && typeof ne === "object") ne.mt = bts; S(shardPrefix(fid)).adds[fid] = ne; }
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
    admuKeyShards.set(kn, shardsOf(fids));
    // Pre-v49 batches carry no __ts: treat them as "now" (newest), which is what they were before.
    const bts = typeof batch.__ts === "number" ? batch.__ts : nowMs;
    for (const fid of fids) {
      const o = S(shardPrefix(fid));
      if (o.upTs[fid] !== undefined && o.upTs[fid] > bts) continue;   // a newer op already won
      o.upserts[fid] = batch[fid]; o.upTs[fid] = bts; o.calmOnly.delete(fid);
    }
  }
  // v49 calm lane (only when due). Same newest-wins merge; the winner remembers it was calm.
  const admcKeys = [];
  if (calmDue) for (const kn of admcNames) {
    const val = await env.AVATAR_KV.get(kn);
    if (!val) { admcKeys.push(kn); continue; }
    let batch; try { batch = JSON.parse(val); } catch (_) { admcKeys.push(kn); continue; }
    const fids = Object.keys(batch).filter((fid) => FILE_RE.test(fid));
    if (!reserve(fids)) break;   // over budget → the rest wait for the next flush (still due)
    admcKeys.push(kn);
    admcKeyShards.set(kn, shardsOf(fids));
    const bts = typeof batch.__ts === "number" ? batch.__ts : calmKeyTs(kn);
    for (const fid of fids) {
      const o = S(shardPrefix(fid));
      if (o.upTs[fid] !== undefined && o.upTs[fid] >= bts) continue;  // fast/newer op wins
      o.upserts[fid] = batch[fid]; o.upTs[fid] = bts; o.calmOnly.add(fid);
    }
  }
  const admrKeys = [];
  for (const kn of admrNames) {
    const val = await env.AVATAR_KV.get(kn);
    if (!val) { admrKeys.push(kn); continue; }
    let arr; try { arr = JSON.parse(val); } catch (_) { admrKeys.push(kn); continue; }
    const fids = arr.filter((f) => typeof f === "string" && f.startsWith("file_"));
    if (!reserve(fids)) break;   // over budget → defer to next flush
    admrKeys.push(kn);
    admrKeyShards.set(kn, shardsOf(fids));
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
    admkKeyShards.set(kn, shardsOf(fids));
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
      S(shardPrefix(fid)).renames[fid] = String(r.name).slice(0, 100); repClear.push(kn); repKeyShards.set(kn, shardsOf([fid]));
    } else if (r.status === "dead" && (r.count || 0) >= REMOVE_QUORUM) {
      if (!reserve([fid])) break;                                   // shard WRITE → budget
      S(shardPrefix(fid)).removes.add(fid); repClear.push(kn); repKeyShards.set(kn, shardsOf([fid]));
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
  const addedItems = [], removedItems = [];   // v52 live feed: the avatars this run really added / removed
  // A re-key (the bot saw a new image: remove the old fileId + upsert the new one, SAME avatar id) is not an
  // avatar leaving or joining the catalog. Every avatar id this run adds or updates, in any shard, so the
  // live feed can tell a re-key from a real removal (seen live: Moontail avatars shown as removed).
  const runUpIds = new Set();
  for (const o of Object.values(shardOps)) for (const x of [...Object.values(o.adds), ...Object.values(o.upserts)]) if (x && x.id) runUpIds.add(x.id);
  const allAdd = [], allRem = [];
  const purgeJobs = [], searchRecs = [];   // v53: guaranteed purge queue + D1/nm search changes
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
    } catch (_) { allShardsOk = false; failedShards.add(sp); continue; } // read failed -> skip (never wipe); mark shard so its batches are KEPT, not cleared
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
    const sAddItems = [], sRemItems = [];
    const item = (x) => ({ n: (x && x.name) || "", a: (x && typeof x.id === "string" && /^avtr_[0-9a-f-]{36}$/.test(x.id)) ? x.id : "" });
    const sIndexOps = [];
    // v53: before/after of every avatar this shard's ops touch, for the D1 search + nm/ name files (search step).
    const sTouched = new Set([...Object.keys(ops.adds), ...Object.keys(ops.upserts), ...Object.keys(ops.renames), ...ops.removes]);
    const sBefore = new Map(); for (const fid of sTouched) sBefore.set(fid, e[fid] ? { ...e[fid] } : null);
    // v52: a remove in the same run wins over an add/update of the same avatar (e.g. a fill update queued
    // before the liveness bot found it gone). Without this the avatar was re-created and then deleted in
    // one flush: the catalog ended right, but the live feed and today's totals counted it +1 and -1.
    for (const fid of Object.keys(ops.adds)) if (!e[fid] && !ops.removes.has(fid)) {
      const ne = ops.adds[fid]; e[fid] = ne; sAdded++; sAddItems.push(item(ne)); dirty = true; contentDirty = true;
      const op = buildIndexOp(null, ne, fid); if (op) sIndexOps.push(op);
      if (needsFold(ne)) ne.fz = foldSig(ne);   // the ADD already carries the folded tokens
      if (hasNonAscii(ne)) ne.fv = FOLD_STAMP_V; // indexed under the current fold rules (bots won't flag it)
      if (ne.filled !== true) sUnfilled++;
    }
    for (const fid of Object.keys(ops.upserts)) {
      if (ops.removes.has(fid)) continue;
      const inc = ops.upserts[fid];
      const prev = e[fid];
      const ts = ops.upTs[fid] !== undefined ? ops.upTs[fid] : nowMs;
      // v49 safety: a calm refresh never re-creates an avatar removed while it waited, and no op older
      // than what's stored (its `mt`) may overwrite it.
      if (!prev && ops.calmOnly.has(fid)) continue;
      if (prev && typeof prev.mt === "number" && ts < prev.mt) continue;
      if (prev && typeof prev.added === "number") inc.added = prev.added; // `added` is immutable
      else if (!prev) { sAdded++; sAddItems.push(item(inc)); }
      if (!prev) { if (inc.filled !== true) sUnfilled++; }
      else { const wasUnfilled = prev.filled !== true, nowUnfilled = inc.filled !== true;
        if (wasUnfilled && !nowUnfilled) sUnfilled--; else if (!wasUnfilled && nowUnfilled) sUnfilled++; }
      // An upsert that changes nothing material (same name/author/authorId/platforms/bio/filled)
      // shouldn't rewrite the shard either. entryEquivalent compares the persisted fields.
      if (!prev || !entryEquivalent(prev, inc)) {
        const op = buildIndexOp(prev || null, inc, fid); if (op) sIndexOps.push(op);
        // fz/fv: kept when the text didn't change; re-stamped (current fold) when it did or it's new.
        if (prev) carryWorkerFields(prev, inc, textChangedBetween(prev, inc));
        else { if (needsFold(inc)) inc.fz = foldSig(inc); if (hasNonAscii(inc)) inc.fv = FOLD_STAMP_V; }
        inc.mt = ts;
        e[fid] = inc; dirty = true; contentDirty = true;
      }
    }
    for (const fid of Object.keys(ops.renames)) if (e[fid] && !ops.removes.has(fid) && e[fid].name !== ops.renames[fid]) {
      const prev = { ...e[fid] }; e[fid].name = ops.renames[fid]; dirty = true; contentDirty = true;
      const op = buildIndexOp(prev, e[fid], fid); if (op) sIndexOps.push(op);
    }
    // `checked` bumps DO dirty the shard — the timestamp must persist so the liveness sweep can
    // pace itself (an un-persisted `checked` would leave the avatar perpetually stale → re-checked
    // every pass forever). The write volume is instead bounded by the RECHECK_INTERVAL_MS (widened
    // to 30d in the app): at 30d, re-verifying the whole catalog is ~1 shard write per avatar per
    // month — well under the R2 free tier — instead of the old 7d cadence's ~4x churn.
    // v49: a bot `checked` id is now a FONT CHECK, never a timestamp write (the bots walk every shard
    // continuously, so a "last checked" date bought nothing but a shard rewrite per visit). The bots
    // flag only avatars with non-ASCII text whose `fv` isn't the current fold rules version; this
    // re-folds the entry if its plain-text tokens are out of date and stamps `fv`, so it's flagged
    // once per rules change. Nothing to fix → no write. (Older admin apps still send every unchanged
    // avatar here: ASCII ones are a no-op, fancy ones get the same one-time stamp.)
    // v53: bot font flags (`checked` ids) are ignored: they only re-folded the old search files' tokens.
    for (const fid of ops.removes) if (e[fid]) {
      const prev = e[fid]; delete e[fid]; sRemoved++; sRemItems.push(item(prev)); dirty = true; contentDirty = true;
      const op = buildIndexOp(prev, null, fid); if (op) sIndexOps.push(op);
      if (prev.filled !== true) sUnfilled--;
    }
    // Bot FILL-hint (replaces the Action's worklist): does this shard, AFTER the ops, still hold
    // an unfilled avatar? Accurate + cheap (the shard is already in memory). Drives _worklist.json.
    if (Object.values(e).some((x) => x && x.filled !== true)) fillHintAdd.add(sp);
    else fillHintDone.add(sp);
    if (!dirty) continue;   // nothing changed → skip the R2 write (and the purge below)
    let wrote = false, shardEtag = "";
    try {
      const put = await env.CATALOG.put(`shard/${sp}.json`, JSON.stringify({ v: 1, e }), {
        httpMetadata: { contentType: "application/json", cacheControl: "public, max-age=" + SHARD_TTL },
      });
      shardEtag = (put && put.etag) || "";
      wrote = true;
    } catch (_) { allShardsOk = false; failedShards.add(sp); }  // write failed → KEEP this shard's batches (per-key clear below)
    if (wrote) {   // fold the count deltas + index ops ONLY now that the shard actually persisted
      dirtyPrefixes.push(sp);
      if (contentDirty) purgePrefixes.push(sp);   // checked-only writes persist but skip the purge
      added += sAdded; removed += sRemoved; unfilledDelta += sUnfilled;
      allAdd.push(...sAddItems); allRem.push(...sRemItems);
      for (const op of sIndexOps) indexOps.push(op);
      if (contentDirty) purgeJobs.push({ u: catalogUrl(env, `shard/${sp}.json`), etag: shardEtag });
      for (const fid of sTouched) { const r = searchChange(fid, sBefore.get(fid), e[fid] || null); if (r) searchRecs.push(...r); }
    }
  }

  // Purge just the shards whose SERVED content changed (adds/upserts/renames/removes) so a new avatar
  // goes live within ~seconds instead of the TTL. A `checked`-only rewrite is skipped — a stale cached
  // copy is functionally identical for clone/search (no reader consults `checked`). No-op unless a
  // purge token is configured.
  // v53: purges go through the guaranteed purge queue (retried until confirmed); search changes go to the
  // search step (its own invocation, fired by the cron right after this flush). Both durable in the hub.
  if (purgeJobs.length) await hubQueuePush(env, "pq", purgeJobs.filter((j) => j.u).map((j) => ({ ...j, t0: Date.now() })));
  if (searchRecs.length) await hubQueuePush(env, "srq", searchRecs);

  // Clear the drained KV keys FIRST — BEFORE the (expensive) index drain. Clearing depends ONLY on the
  // shard-loop result (allShardsOk / failedShards), so doing it first guarantees a batch whose shards
  // persisted is deleted even if the index drain below later blows the ~1000-subrequest ceiling and
  // throws. THIS ORDER IS THE FIX for "nothing drains": the index drain used to run FIRST, and its ~300
  // index ops (each new-avatar op touches fragment+avtr+index buckets) + the shard ops exceeded the
  // ceiling, killing the invocation BEFORE this clear ever ran → pend keys never deleted → the whole
  // queue stuck (verified live: pendCleared=0 every flush). PER-KEY (not all-or-nothing): only keys
  // touching a genuinely-failed shard are kept for retry; a key with no recorded shards (empty/garbage
  // batch, moot report) always clears.
  const keyClears = (kn, map) => { const s = map.get(kn); if (!s) return true; for (const sp of s) if (failedShards.has(sp)) return false; return true; };
  let pendCleared = 0, repCleared = 0;
  for (const n of pendKeys) if (keyClears(n, pendKeyShards)) { await env.AVATAR_KV.delete(n); pendCleared++; }
  for (const n of admuKeys) if (keyClears(n, admuKeyShards)) await env.AVATAR_KV.delete(n);
  for (const n of admrKeys) if (keyClears(n, admrKeyShards)) await env.AVATAR_KV.delete(n);
  for (const n of admkKeys) if (keyClears(n, admkKeyShards)) await env.AVATAR_KV.delete(n);
  let calmCleared = 0;
  for (const n of admcKeys) if (keyClears(n, admcKeyShards)) { await env.AVATAR_KV.delete(n); calmCleared++; }
  for (const n of repClear) if (keyClears(n, repKeyShards)) { await env.AVATAR_KV.delete(n); repCleared++; }

  // FULL incremental SEARCH INDEX (add / rename / remove + avtr presence) — computed above from the SAME
  // shard reads (no re-fetch). Runs AFTER the clear (above) and is COST-CAPPED + fully WRAPPED so a
  // subrequest-ceiling throw can never abort the flush: the shards persisted + the batch already
  // cleared, and any un-applied op stays queued in iq: to drain on a later/lighter flush or chain step.
  if (allShardsOk) {
   try {
    // Dynamic budget: the shard read+write loop already spent ~2*prefixes.length subrequests, and each
    // index op can touch fragment+avtr+index buckets (read+write). Cap the drain so the TOTAL stays well
    // under the ~1000 ceiling — a heavy new-avatar batch yields most index work to the iq: queue (drained
    // by the next chain step) instead of blowing the invocation. Falls to 0 for a very shard-heavy flush.
    // v40 BUCKET BUDGET. applyIndexOps costs ~1 read + <=1 write per DISTINCT bucket (fragment/avtr/
    // index), not per op, so the drain is capped by buckets: the old op cap (142 ops x ~10 tokens =
    // ~1,400 reads) ran past the ~1000-subrequest ceiling, every R2 call after it failed silently, and
    // the batch was still cleared as applied — lost index updates. Now an op is applied only when its
    // buckets fit; everything else stays queued, so nothing is dropped.
    // 600 (not the old 720) leaves room for the same cron invocation's reconcile (<=120 shard reads),
    // prune and author-rename steps, so the whole invocation stays under the ~1000 ceiling.
    const bucketCap = Math.max(0, Math.floor((600 - 2 * prefixes.length) / 2));
    const KEY_READ_CAP = 40;   // backlog keys read per flush (KV gets); legacy re-asserts drop for free
    const used = new Set();
    const toApply = [], leftover = [];
    let full = false;
    const take = (op) => {
      if (full) { leftover.push(op); return; }
      const b = new Set(); opBuckets(op, b);
      let extra = 0; for (const x of b) if (!used.has(x)) extra++;
      // Always admit the first op (one oversized op must not wedge the queue forever).
      if (toApply.length && used.size + extra > bucketCap) { full = true; leftover.push(op); return; }
      for (const x of b) used.add(x);
      toApply.push(op);
    };
    // Order: this flush's contribution ops (new avatars searchable fast), this flush's fold ops, then
    // the PRIORITY lane (iqp: fold ops), then the general backlog (iq:).
    for (const op of indexOps) if (!op.fz) take(op);
    for (const op of indexOps) if (op.fz) take(op);
    const iqpNames = await listPrefix(env, "iqp:", 3000);
    const iqNames = await listPrefix(env, "iq:", 3000);
    iqDepthNow = iqNames.length; iqpDepthNow = iqpNames.length;
    const drained = [];
    let keysRead = 0;
    for (const kn of [...iqpNames, ...iqNames]) {
      if (full || keysRead >= KEY_READ_CAP) break;
      const val = await env.AVATAR_KV.get(kn); drained.push(kn); keysRead++;
      if (!val) continue;
      let a = null; try { a = JSON.parse(val); } catch (_) {}
      if (!Array.isArray(a)) continue;
      for (const op of a) {
        // Pre-v40 every-check re-asserts: dropped (the fold stamp re-emits what's still needed).
        if (isLegacyReassert(op)) { iqDroppedNow++; continue; }
        take(op);
      }
    }
    let applied = true, drainErr = "", idxChanged = [];
    const tApply = Date.now();
    if (toApply.length) {
      // No edge-cache purge on index buckets (they carry the 5-min INDEX_TTL, self-heal).
      try { idxChanged = await applyIndexOps(env, toApply) || []; } catch (e) { applied = false; drainErr = String(e && e.message || e).slice(0, 140); }
    }
    // Leftover / on-failure-all → requeue by lane (idempotent). NO expiry: an index op is the only
    // thing that makes an avatar searchable, so it must never be dropped. v43: requeue FIRST, then
    // delete the drained keys, so hitting the subrequest ceiling between the two can only duplicate
    // ops (harmless), never lose them.
    // v49: refresh the CHANGED search files on the CDN right away (as shards already are), so an avatar
    // that just became clonable is also searchable in the same flush instead of after the 5-min TTL.
    // Purges are free (no R2 op); capped at IDX_PURGE_CAP URLs (30 per call) to stay inside the
    // subrequest budget — anything past the cap still self-heals via the TTL.
    idxWrites = idxChanged.length;
    if (idxChanged.length) try { await purgeCatalogUrls(env, idxChanged.slice(0, IDX_PURGE_CAP)); } catch (_) {}
    const requeue = applied ? leftover : [...toApply, ...leftover];
    const fold = requeue.filter((o) => o.fz), rest = requeue.filter((o) => !o.fz);
    let requeueOk = true;
    try {
      for (let i = 0; i < fold.length; i += MAX_INDEX_OPS_PER_FLUSH) {
        const part = fold.slice(i, i + MAX_INDEX_OPS_PER_FLUSH);
        await env.AVATAR_KV.put(iqpKey(part.length), JSON.stringify(part));
      }
      for (let i = 0; i < rest.length; i += MAX_INDEX_OPS_PER_FLUSH)
        await env.AVATAR_KV.put("iq:" + crypto.randomUUID(), JSON.stringify(rest.slice(i, i + MAX_INDEX_OPS_PER_FLUSH)));
    } catch (e) { requeueOk = false; drainErr = drainErr || ("requeue: " + String(e && e.message || e).slice(0, 120)); }
    if (requeueOk) for (const kn of drained) await env.AVATAR_KV.delete(kn);
    // v43 drain report for /health (no logs available otherwise).
    lastDrainNow = `applied=${applied} ops=${toApply.length} buckets=${used.size}/${bucketCap} keys=${drained.length}` +
      ` dropped=${iqDroppedNow} left=${requeue.length} shards=${prefixes.length} applyMs=${Date.now() - tApply}` +
      (drainErr ? ` err=${drainErr}` : "");
    iqMoved = applied && (toApply.length > 0 || iqDroppedNow > 0);
    // Fonts waiting = fold ops still queued: the op counts carried in the undrained iqp: key names
    // plus what was just requeued (exact, no value reads).
    const drainedSet = new Set(drained);
    foldPendingNow = iqpNames.filter((k) => !drainedSet.has(k)).reduce((n, k) => n + iqpCount(k), 0) + fold.length;
    iqLeft = requeue.length > 0 || (iqpNames.length + iqNames.length) > drained.length;
   } catch (e) { /* index drain hit the ceiling — batches ALREADY cleared above; iq: self-heals next flush */
     lastDrainNow = "drain threw: " + String(e && e.stack || e).slice(0, 300); }
  }

  // Manifest — the WORKER now owns it (search + counts are fully incremental, no Action needed).
  // entryCount and unfilled move by the deltas computed above; searchReady is always true. Written
  // only when something moved; short TTL + purge so the fresh number is live in ~1s. If a full
  // rebuild is ever run as an optional backstop, its authoritative counts are adopted once.
  let entries = Math.max(0, (prevMeta.entries || 0) + added - removed);
  // v48: EXACT COUNT from the daily backup. The running count only moves by deltas, so any one-off
  // miscount stays forever (seen: +~7,000 phantom avatars after a recount lap, flat for days). The
  // backup reads every shard, so its count is the truth (give or take avatars added while it ran);
  // each finished backup is adopted once, and the manifest is rewritten so /health's max() drops too.
  let countFromBackup = prevMeta.countFromBackup || 0;
  try {
    const b = JSON.parse((await env.AVATAR_KV.get("backup")) || "null");
    if (b && b.doneAt && b.doneAt !== countFromBackup && typeof b.lastCount === "number" && b.lastCount > 0 &&
        typeof b.addedAt === "number" && typeof b.removedAt === "number") {
      // + whatever the flushes added/removed since the backup finished (totals snapshotted at the end).
      const since = ((prevMeta.totalAdded || 0) - b.addedAt) - ((prevMeta.totalRemoved || 0) - b.removedAt);
      entries = Math.max(0, b.lastCount + since + added - removed);
      countFromBackup = b.doneAt;
    }
  } catch (_) {}
  const countAdopted = countFromBackup !== (prevMeta.countFromBackup || 0);
  // v49: the daily backup (which reads every shard) also publishes the exact UNFILLED count + the shards
  // holding unfilled avatars (it replaced the monthly recount). Adopt both once per finished backup:
  // unfilled = the backup's count + the running unfilled deltas since it finished; the fill worklist
  // gains every shard it saw (union; the prune trims any filled since).
  let backupFill = null, unfilledFromBackup = null;
  const ufSum = (prevMeta.ufSum || 0) + unfilledDelta;   // running total of unfilled deltas
  try {
    const b = JSON.parse((await env.AVATAR_KV.get("backup")) || "null");
    if (b && b.doneAt && b.doneAt !== (prevMeta.fillFromBackup || 0) && typeof b.lastUnfilled === "number") {
      unfilledFromBackup = Math.max(0, b.lastUnfilled + (ufSum - (b.ufAt || 0)));
      backupFill = { at: b.doneAt, fill: Array.isArray(b.fill) ? b.fill : [] };
    }
  } catch (_) {}
  const needFoldV = prevMeta.manifestFoldV !== FOLD_STAMP_V;   // publish the font rules version once per change
  const countMoved = added > 0 || removed > 0 || unfilledDelta !== 0 || countAdopted || unfilledFromBackup !== null || needFoldV;
  let unfilled = typeof prevMeta.unfilled === "number" ? prevMeta.unfilled : 0;
  let adoptedRebuild = prevMeta.adoptedRebuild || null;
  let manifestWritten = false;
  if (countMoved) {
    // Decide whether to freshen _manifest.json THIS flush (coalesced — see MANIFEST_MIN_* above). The
    // running unfilled tracks in meta regardless; the manifest read + adopt only run when we actually
    // write, so a skipped flush also saves the manifest Class B read.
    const sinceManifest = nowChecked - (prevMeta.lastManifestMs || 0);
    const entryDelta = Math.abs(entries - (typeof prevMeta.lastManifestEntries === "number" ? prevMeta.lastManifestEntries : 0));
    const writeManifest = countAdopted || unfilledFromBackup !== null || needFoldV || !prevMeta.lastManifestMs || sinceManifest >= MANIFEST_MIN_INTERVAL_MS || entryDelta >= MANIFEST_MIN_DELTA;
    unfilled = Math.max(0, unfilled + unfilledDelta);
    if (unfilledFromBackup !== null) unfilled = unfilledFromBackup;
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
        // staleCount stays 0 (v49: no per-avatar "last checked" any more; older admin apps read it).
        // foldV = the font rules version the bots compare each avatar's `fv` stamp against.
        entryCount: entries, unfilled, staleCount: 0, foldV: FOLD_STAMP_V, searchReady: true, nmBuckets: 4096, lastUpdate: new Date().toISOString() };
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
    if (backupFill) for (const p of backupFill.fill) if (!s.has(p)) { s.add(p); hintChanged = true; }   // v49 backup adoption
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

  try { await hubPut(env, "meta", JSON.stringify({
    ...prevMeta,
    lastFlush: new Date().toISOString(),
    lastAdded: added, lastRemoved: removed,
    totalAdded: (prevMeta.totalAdded || 0) + added,
    totalRemoved: (prevMeta.totalRemoved || 0) + removed,
    entries,
    ...(countMoved ? { unfilled, stale: 0 } : {}),   // running Fill backlog (preserved when nothing moved)
    ...(manifestWritten ? { lastManifestMs: nowChecked, lastManifestEntries: entries, manifestFoldV: FOLD_STAMP_V } : {}),
    ufSum,
    ...(backupFill && allShardsOk ? { fillFromBackup: backupFill.at } : {}),
    ...(hintChanged || seeded ? { fillHint } : {}),  // bot fill worklist (seeded once, then maintained)
    adoptedRebuild,
    countFromBackup,
    lastCommit: allShardsOk
      ? `R2 +${added} -${removed} (${prefixes.length} shards)`
      : `R2 partial: ${failedShards.size} shard(s) failed, drained the rest (+${added} -${removed})`,
    pendingBatches: Math.max(0, pendNames.length - pendCleared),   // remaining after THIS flush's per-key drain (drops each flush now)
    lastDiag: `consumed=${pendKeys.length} cleared=${pendCleared} shards=${prefixes.length} dirty=${dirtyPrefixes.length} failed=${failedShards.size} ok=${allShardsOk}`,   // TEMP flush instrumentation
    reports: Math.max(0, repNames.length - repCleared),
    iqDepth: iqDepthNow,   // search-index op queue depth (keys) — watch it drain to 0 after a fold/rebuild
    iqpDepth: iqpDepthNow, // v40 priority lane (fold ops) depth in keys
    foldPending: foldPendingNow, // v40 fold ops queued (admin "Fonts waiting")
    lastDrain: lastDrainNow,     // v43 last index-drain report
    iqDropped: (prevMeta.iqDropped || 0) + iqDroppedNow,   // v40 legacy re-asserts dropped (running total)
    backend: "r2",
    // v49 calm lane depth (batches waiting) + R2 WRITE COUNTERS for today (UTC): shard / search / other
    // (manifest + worklist). /health shows them so every cost change can be measured, not guessed.
    calmDepth: Math.max(0, admcNames.length - calmCleared),
    wr: (() => {
      const d = new Date().toISOString().slice(0, 10);
      const w = prevMeta.wr && prevMeta.wr.d === d ? prevMeta.wr : { d, sh: 0, ix: 0, ot: 0 };
      return { d, sh: w.sh + dirtyPrefixes.length, ix: w.ix + idxWrites,
        ot: w.ot + (manifestWritten ? 1 : 0) + (hintChanged ? 1 : 0) };
    })(),
    wrPrev: prevMeta.wr && prevMeta.wr.d !== new Date().toISOString().slice(0, 10) ? prevMeta.wr : (prevMeta.wrPrev || null),
  })); } catch (_) { /* ceiling: meta stale ONE cycle (self-corrects next flush); the return below is computed from in-memory state so the chain still gets the right pendRemaining */ }

  // v52 live event for this run (public view: counts + avatar names, never who sent them). Re-keys are
  // left out of the feed and its totals (`rekeyed` counts them); `added`/`removed` above stay exact for meta.
  const remIds = new Set(allRem.map((x) => x.a).filter(Boolean));
  const feedRem = allRem.filter((x) => !(x.a && runUpIds.has(x.a)));
  const feedAdd = allAdd.filter((x) => !(x.a && remIds.has(x.a)));
  for (const x of feedAdd) if (addedItems.length < 300) addedItems.push(x);
  for (const x of feedRem) if (removedItems.length < 300) removedItems.push(x);
  try {
    await emit(env, { t: "run", ok: allShardsOk, added: feedAdd.length, removed: feedRem.length, rekeyed: allRem.length - feedRem.length, entries, ms: Date.now() - runStart,
      shards: dirtyPrefixes.length, idx: idxWrites, calm: calmCleared,
      // The avatars this run really added and removed (up to 300 each; `added`/`removed` are the full counts).
      addedItems, removedItems,
      batchCount: recentBatches.length,
      pending: Math.max(0, pendNames.length - pendCleared), reports: Math.max(0, repNames.length - repCleared),
      calmWaiting: Math.max(0, admcNames.length - calmCleared) });
  } catch (_) {}
  // Rolling log of the most recent USER contribution batches (newest first) with FULL names, in a
  // DEDICATED key so it never bloats meta/health. Only rewritten when batches were processed this
  // flush → +1 small KV write per flush at most, nothing when idle. Capped at 20 batches.
  if (recentBatches.length) try {
    let prev = []; try { const r = await hubGet(env, "recent"); if (r) prev = JSON.parse(r); } catch (_) {}
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
      await hubPut(env, "recent", JSON.stringify(next));
    }
  } catch (_) { /* best-effort display log; never let it abort the flush's return below */ }
  // For the self-chaining continuation: pend batches left after THIS flush's per-key drain, and
  // whether this step made progress (cleared ≥1). The caller chains another fresh-budget flush while
  // pend work remains AND progress is being made (so a step that can only re-hit failed shards stops).
  return { pendRemaining: Math.max(0, pendNames.length - pendCleared), progressed: pendCleared > 0,
    // v40: the index backlog also chains (bounded by INDEX_CHAIN_DEPTH) so it clears in about an hour
    // instead of days at one drain per 2-min cron. Same total ops/cost, just compressed in time.
    iqRemaining: iqLeft, iqProgressed: iqMoved };
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

// ---- v53: search in D1 + clone name files (nm/) + guaranteed purge queue ---------------------------------
// Search (the app's Search button) is D1: one row per avatar (`av`, keyed by a 60-bit number taken from the
// avatar id, so no extra index rows), authors in `au` (keyed by user id, so a rename is one row), and a lean
// contentless FTS5 index (`avx`: name + author + bio, raw AND font-folded words, prefix search). Clone resolve
// (roster, constant and automatic) must stay free, so it reads CDN name files instead: nm/<bucket>.json maps a
// folded exact name to every avatar with that name (the app keeps the existing unique-exact-name and author
// rules on top). Both are fed by the search step (its own invocation after each flush) from the durable `srq`
// queue in the hub, and every CDN file write is followed by a queued purge that is retried until Cloudflare
// confirms it AND the CDN serves the new ETag (alert on /health after 30 min). index/ + fragments/ keep being
// written for older apps until they are switched off.
const NAME_TTL = 7 * 24 * 3600;           // nm/ files: purge-on-write, the TTL is only a safety net
const SEARCH_RECS_PER_STEP = 1500;        // queued change records applied per search step
const NM_BUCKETS_PER_STEP = 150;          // nm/ read+write pairs per step (the rest stays queued)
const PURGE_PER_STEP = 150;               // purge jobs (+1 verify fetch each) per step
const PURGE_STUCK_MS = 30 * 60_000;       // a purge still failing after this is flagged on /health
const SEARCH_PAGE = 500;                  // results per /search batch (the app shows 50 a page)
const FILL_SHARDS_PER_STEP = 24;          // backfill: shards read per step (~2k avatars)
const NMFILL_BUCKETS_PER_STEP = 256;      // backfill: nm/ files written per step (one D1 scan each step)

function catalogUrl(env, key) { return env.CATALOG_BASE ? env.CATALOG_BASE.replace(/\/$/, "") + "/" + key : ""; }
function nameKey(s) { return foldFancy(s || "").trim().toLowerCase(); }   // == the app's fancyFold()
function nameBucketOf(k) { return ((hashCode(k) & 0xfff) >>> 0).toString(16).padStart(3, "0"); }
// 60 bits of the avatar uuid (hex), as a decimal string: SQLite stores it as an INTEGER PRIMARY KEY.
function avKey(id) { return BigInt("0x" + String(id).slice(5).replace(/-/g, "").slice(0, 15)).toString(); }
function perfPack(e) { return (e.perfPc ?? 5) | ((e.perfQuest ?? 5) << 4) | ((e.perfIos ?? 5) << 8); }
function searchText(s) {
  const raw = [s.n, s.au, s.d].filter(Boolean).join(" ");
  const f = foldFancy(raw);
  return f === raw ? raw : raw + " " + f;
}
// A searchable avatar: has an id and a name, and has been filled (an unfilled stub has no name yet).
function searchable(x) { return !!(x && typeof x.id === "string" && x.id.startsWith("avtr_") && x.filled === true && x.name); }
function slim(x, fid) {
  return { id: x.id, f: fid, n: x.name || "", au: x.author || "", ai: x.authorId || "", d: String(x.desc || "").slice(0, 1000),
    p: platMask(x.platforms), pf: perfPack(x), ad: x.added || 0 };
}
function searchFieldsDiffer(a, b) {
  return a.id !== b.id || (a.name || "") !== (b.name || "") || (a.author || "") !== (b.author || "") ||
    (a.authorId || "") !== (b.authorId || "") || (a.desc || "") !== (b.desc || "") ||
    platMask(a.platforms) !== platMask(b.platforms) || perfPack(a) !== perfPack(b);
}
// Change records for one fileId: { id, f, nw: slim | null, old: { n, f } | null }. A record with nw = null
// removes the avatar from search ONLY where search still points at this fileId (a re-filed avatar whose
// search row already moved to its new image is never deleted by the old image's removal).
function searchChange(fid, before, after) {
  const b = searchable(before) ? before : null, a = searchable(after) ? after : null;
  if (!b && !a) return null;
  if (b && a && b.id === a.id && !searchFieldsDiffer(b, a)) return null;
  const out = [];
  if (b && (!a || a.id !== b.id)) out.push({ id: b.id, f: fid, nw: null, old: { n: b.name, f: fid } });
  if (a) out.push({ id: a.id, f: fid, nw: slim(a, fid), old: b && b.id === a.id ? { n: b.name, f: fid } : null });
  return out;
}

// ---- hub queues (durable, strongly consistent) ----
async function hubQueuePush(env, name, items) {
  if (!items || !items.length) return true;
  const h = hubStub(env); if (!h) return false;
  try { const r = await h.fetch("https://hub/q/push", { method: "POST", body: JSON.stringify({ name, items }) }); return r.ok; }
  catch (_) { return false; }
}
async function hubQueueTake(env, name, max) {
  const h = hubStub(env); if (!h) return { keys: [], items: [], depth: 0 };
  try { const r = await h.fetch("https://hub/q/take", { method: "POST", body: JSON.stringify({ name, max }) }); return await r.json(); }
  catch (_) { return { keys: [], items: [], depth: 0 }; }
}
async function hubQueueAck(env, keys) {
  if (!keys || !keys.length) return;
  const h = hubStub(env); if (!h) return;
  try { await h.fetch("https://hub/q/ack", { method: "POST", body: JSON.stringify({ keys }) }); } catch (_) {}
}
async function hubLock(env, name, ttlMs) {
  const h = hubStub(env); if (!h) return true;
  try { const r = await h.fetch("https://hub/lock", { method: "POST", body: JSON.stringify({ name, ttlMs }) }); return (await r.json()).ok === true; }
  catch (_) { return false; }
}
async function hubUnlock(env, name) {
  const h = hubStub(env); if (!h) return;
  try { await h.fetch("https://hub/unlock", { method: "POST", body: JSON.stringify({ name }) }); } catch (_) {}
}

// ---- D1 ----
let _schemaOk = false;
async function ensureSearchSchema(db) {
  if (_schemaOk) return;
  await db.batch([
    db.prepare("CREATE TABLE IF NOT EXISTS av (k INTEGER PRIMARY KEY, id TEXT NOT NULL, f TEXT NOT NULL, n TEXT NOT NULL, nk TEXT NOT NULL, nb INTEGER NOT NULL, ai TEXT NOT NULL, p INTEGER NOT NULL, pf INTEGER NOT NULL, ad INTEGER NOT NULL)"),
    db.prepare("CREATE TABLE IF NOT EXISTS au (id TEXT PRIMARY KEY, n TEXT NOT NULL, k TEXT NOT NULL) WITHOUT ROWID"),
    db.prepare("CREATE VIRTUAL TABLE IF NOT EXISTS avx USING fts5(t, content='', contentless_delete=1, tokenize='unicode61 remove_diacritics 2', prefix='2 3')"),
  ]);
  _schemaOk = true;
}
const qs = (n, g) => Array.from({ length: n }, () => g).join(",");
// Apply resolved puts/dels to D1 in ONE batch (one transaction): dels first (conditional on fileId), then puts.
async function d1Apply(db, puts, dels) {
  const st = [];
  for (let i = 0; i < dels.length; i += 45) {
    const c = dels.slice(i, i + 45), b = c.flatMap((r) => [avKey(r.id), r.f]);
    st.push(db.prepare(`DELETE FROM avx WHERE rowid IN (SELECT k FROM av WHERE (k,f) IN (VALUES ${qs(c.length, "(?,?)")}))`).bind(...b));
    st.push(db.prepare(`DELETE FROM av WHERE (k,f) IN (VALUES ${qs(c.length, "(?,?)")})`).bind(...b));
  }
  for (let i = 0; i < puts.length; i += 90) {
    const c = puts.slice(i, i + 90);
    st.push(db.prepare(`DELETE FROM avx WHERE rowid IN (${qs(c.length, "?")})`).bind(...c.map((s) => avKey(s.id))));
  }
  for (let i = 0; i < puts.length; i += 9) {
    const c = puts.slice(i, i + 9);
    st.push(db.prepare(`INSERT INTO av(k,id,f,n,nk,nb,ai,p,pf,ad) VALUES ${qs(c.length, "(?,?,?,?,?,?,?,?,?,?)")} ON CONFLICT(k) DO UPDATE SET id=excluded.id,f=excluded.f,n=excluded.n,nk=excluded.nk,nb=excluded.nb,ai=excluded.ai,p=excluded.p,pf=excluded.pf`)
      .bind(...c.flatMap((s) => { const k = nameKey(s.n); return [avKey(s.id), s.id, s.f, s.n, k, parseInt(nameBucketOf(k), 16), s.ai, s.p, s.pf, s.ad]; })));
  }
  for (let i = 0; i < puts.length; i += 45) {
    const c = puts.slice(i, i + 45);
    st.push(db.prepare(`INSERT INTO avx(rowid,t) VALUES ${qs(c.length, "(?,?)")}`).bind(...c.flatMap((s) => [avKey(s.id), searchText(s)])));
  }
  const authors = new Map(); for (const s of puts) if (s.ai) authors.set(s.ai, s.au);
  const al = [...authors];
  for (let i = 0; i < al.length; i += 30) {
    const c = al.slice(i, i + 30);
    st.push(db.prepare(`INSERT INTO au(id,n,k) VALUES ${qs(c.length, "(?,?,?)")} ON CONFLICT(id) DO UPDATE SET n=excluded.n,k=excluded.k WHERE au.n<>excluded.n`)
      .bind(...c.flatMap(([id, n]) => [id, n, nameKey(n)])));
  }
  if (!st.length) return { written: 0 };
  let written = 0;
  for (const r of await db.batch(st)) written += (r.meta && r.meta.rows_written) || 0;
  return { written };
}

// ---- nm/ name files: { v, e: { "<folded name>": [ {a,f,n,au,ai,p,pf}, … ] } } ----
async function nmApply(env, puts, removes, maxBuckets) {
  // removes: [{ k, id, f }] (old names), puts: slim entries (new state). Grouped per bucket: one read + one write.
  const byB = new Map();
  const touch = (b) => { if (!byB.has(b)) byB.set(b, { rem: [], add: [] }); return byB.get(b); };
  for (const r of removes) touch(nameBucketOf(r.k)).rem.push(r);
  for (const s of puts) { const k = nameKey(s.n); touch(nameBucketOf(k)).add.push({ k, s }); }
  const done = new Set(), failed = new Set(), jobs = [];
  const buckets = [...byB.keys()];
  await runPool(buckets.slice(0, maxBuckets), 6, async (b) => {
    const w = byB.get(b);
    let cur;
    try { const o = await env.CATALOG.get(`nm/${b}.json`); cur = o ? await o.json() : { v: 1, e: {} }; if (!cur || typeof cur.e !== "object") cur = { v: 1, e: {} }; }
    catch (_) { failed.add(b); return; }   // a failed READ never writes (would wipe the bucket)
    const e = cur.e;
    for (const r of w.rem) { const l = e[r.k]; if (!l) continue; const n = l.filter((x) => !(x.a === r.id && x.f === r.f)); if (n.length) e[r.k] = n; else delete e[r.k]; }
    for (const { k, s } of w.add) {
      const l = (e[k] || []).filter((x) => x.a !== s.id);
      l.push({ a: s.id, f: s.f, n: s.n, au: s.au, ai: s.ai, p: s.p, pf: s.pf });
      e[k] = l;
    }
    try {
      const put = await env.CATALOG.put(`nm/${b}.json`, JSON.stringify(cur), { httpMetadata: { contentType: "application/json", cacheControl: "public, max-age=" + NAME_TTL } });
      done.add(b); jobs.push({ u: catalogUrl(env, `nm/${b}.json`), etag: (put && put.etag) || "", t0: Date.now() });
    } catch (_) { failed.add(b); }
  });
  const pending = new Set(buckets.filter((b) => !done.has(b)));   // failed + over budget
  return { done: done.size, pending, jobs };
}

// One search step: apply queued change records to D1 + nm/, then work the purge queue. Single-flight.
async function searchStep(env) {
  const out = { applied: 0, d1Written: 0, nm: 0, requeued: 0, purged: 0, purgeFailed: 0 };
  if (!(await hubLock(env, "search", 120_000))) return { ...out, busy: true };
  try {
    const got = await hubQueueTake(env, "srq", SEARCH_RECS_PER_STEP);
    if (got.items.length) {
      // Resolve per avatar: any put wins (latest); a del only where no put for that avatar in this step.
      const puts = new Map(), dels = new Map(), rem = [];
      for (const r of got.items) {
        if (r.old && r.old.n) rem.push({ k: nameKey(r.old.n), id: r.id, f: r.old.f });
        if (r.nw) { puts.set(r.id, r.nw); dels.delete(r.id); } else if (!puts.has(r.id)) dels.set(r.id, r);
      }
      const putList = [...puts.values()], delList = [...dels.values()].map((r) => ({ id: r.id, f: r.f }));
      let d1ok = true;
      if (env.SEARCH) {
        try { await ensureSearchSchema(env.SEARCH); const w = await d1Apply(env.SEARCH, putList, delList); out.d1Written = w.written; }
        catch (e) { d1ok = false; out.d1Err = String(e && e.message || e).slice(0, 200); }
      }
      // nm/ for dels: drop (id, f) under the old name; rem covers renames and removals.
      const nm = await nmApply(env, putList, rem, NM_BUCKETS_PER_STEP);
      out.nm = nm.done;
      if (nm.jobs.length) await hubQueuePush(env, "pq", nm.jobs);
      // Requeue: everything if D1 failed, else only records whose nm/ bucket wasn't written (idempotent).
      const again = got.items.filter((r) => !d1ok ||
        (r.nw && nm.pending.has(nameBucketOf(nameKey(r.nw.n)))) || (r.old && r.old.n && nm.pending.has(nameBucketOf(nameKey(r.old.n)))));
      if (again.length) { await hubQueuePush(env, "srq", again); out.requeued = again.length; }
      await hubQueueAck(env, got.keys);
      out.applied = got.items.length;
    }
    Object.assign(out, await purgeStep(env));
    Object.assign(out, await legacyFilesCleanup(env));
    try { Object.assign(out, await mediaImportStep(env)); } catch (e) { out.mediaErr = String(e && e.message || e).slice(0, 200); }
    if (env.SEARCH && got.items.length < 100) Object.assign(out, await searchFillStep(env));
  } finally { await hubUnlock(env, "search"); }
  try { await hubPut(env, "searchStat", JSON.stringify({ at: Date.now(), ...out })); } catch (_) {}
  return out;
}

// One-time cleanup (v53): delete the old search files (index/ + fragments/, ~8,200 objects) in bulk, 1,000 per
// step, until none are left; then it's a no-op (a hub flag). R2 deletes are free; each list is one Class A op.
async function legacyFilesCleanup(env) {
  if ((await hubGet(env, "legacyGone")) === "1") return {};
  let deleted = 0;
  for (const prefix of ["index/", "fragments/"]) {
    const l = await env.CATALOG.list({ prefix, limit: 1000 });
    const keys = (l.objects || []).map((o) => o.key);
    if (keys.length) { await env.CATALOG.delete(keys); deleted += keys.length; break; }
  }
  if (!deleted) await hubPut(env, "legacyGone", "1");
  return { legacyDeleted: deleted };
}

// v54: app pictures + rich media on R2 (media/), copied once from the GitHub image store. The copy runs a few
// files per search step until done (hub flag); GitHub keeps the originals so old apps still work. The app reads
// media/manifest.json, whose "base" says where the pictures live.
const MEDIA_GH = "https://raw.githubusercontent.com/Ashoska/VRC-A-Image-store/main";
const MEDIA_GH_RELEASE = "https://api.github.com/repos/Ashoska/VRC-A-Image-store/releases/tags/rich-media";
const MEDIA_NAME_RE = /^[A-Za-z0-9._-]{1,100}$/;
const MEDIA_MAX_BYTES = 95 * 1024 * 1024;
const MEDIA_PIC_CC = "public, max-age=86400";
const MEDIA_RICH_CC = "public, max-age=31536000, immutable";   // unique name per upload
function mediaType(name) {
  const e = name.split(".").pop().toLowerCase();
  return { png: "image/png", jpg: "image/jpeg", jpeg: "image/jpeg", webp: "image/webp", gif: "image/gif", mp4: "video/mp4", webm: "video/webm", json: "application/json" }[e] || "application/octet-stream";
}
async function sha256Hex(buf) {
  return [...new Uint8Array(await crypto.subtle.digest("SHA-256", buf))].map((b) => b.toString(16).padStart(2, "0")).join("");
}
async function mediaManifest(env) {
  const o = await env.CATALOG.get("media/manifest.json");
  return o ? JSON.parse(await o.text()) : null;
}
async function mediaManifestPut(env, man) {
  man.base = catalogUrl(env, "media");
  return env.CATALOG.put("media/manifest.json", JSON.stringify(man), { httpMetadata: { contentType: "application/json", cacheControl: "public, max-age=300" } });
}
async function mediaRichNames(env) {
  const names = []; let cursor;
  do { const l = await env.CATALOG.list({ prefix: "media/rich/", cursor, limit: 1000 }); for (const o of l.objects) names.push(o.key.slice(11)); cursor = l.truncated ? l.cursor : null; } while (cursor);
  return names;
}
async function mediaImportStep(env) {
  if (!env.CATALOG_BASE || (await hubGet(env, "mediaDone")) === "1") return {};
  const ua = { "user-agent": "vrca-avatar-db" };
  if (!(await mediaManifest(env))) {
    const r = await fetch(MEDIA_GH + "/manifest.json?v=" + Date.now(), { headers: ua });
    if (!r.ok) return { media: "manifest " + r.status };
    const man = await r.json();
    for (const e of Object.values(man.images || {})) {
      const f = await fetch(MEDIA_GH + "/" + e.path, { headers: ua });
      if (!f.ok) return { media: "pic " + f.status + " " + e.path };
      await env.CATALOG.put("media/" + e.path, await f.arrayBuffer(), { httpMetadata: { contentType: mediaType(e.path), cacheControl: MEDIA_PIC_CC } });
    }
    await mediaManifestPut(env, man);
    return { media: "pictures " + Object.keys(man.images || {}).length };
  }
  const rel = await fetch(MEDIA_GH_RELEASE, { headers: { ...ua, accept: "application/vnd.github+json" } });
  if (rel.status === 404) { await hubPut(env, "mediaDone", "1"); return { media: "done" }; }
  if (!rel.ok) return { media: "release " + rel.status };
  const have = new Set(await mediaRichNames(env));
  const todo = ((await rel.json()).assets || []).filter((a) => MEDIA_NAME_RE.test(a.name) && !have.has(a.name));
  let copied = 0;
  for (const a of todo.slice(0, 3)) {
    const f = await fetch(a.browser_download_url, { headers: ua });
    if (!f.ok || !a.size) continue;
    const fls = new FixedLengthStream(a.size);
    const pipe = f.body.pipeTo(fls.writable);
    await env.CATALOG.put("media/rich/" + a.name, fls.readable, { httpMetadata: { contentType: mediaType(a.name), cacheControl: MEDIA_RICH_CC } });
    await pipe; copied++;
  }
  if (todo.length <= copied) { await hubPut(env, "mediaDone", "1"); return { media: "done", copied }; }
  return { media: "rich " + (todo.length - copied) + " left", copied };
}

// Guaranteed purge: a job leaves the queue only when Cloudflare confirmed the purge AND the CDN serves the
// ETag we wrote (or a newer write superseded it). Anything else is retried every step; /health flags a job
// older than 30 min.
async function purgeStep(env) {
  const res = { purged: 0, purgeFailed: 0 };
  if (!env.CF_PURGE_TOKEN || !env.CF_ZONE_ID) return res;
  const got = await hubQueueTake(env, "pq", PURGE_PER_STEP);
  if (!got.items.length) return res;
  const by = new Map();   // newest etag per URL, oldest t0
  for (const j of got.items) { const c = by.get(j.u); by.set(j.u, { u: j.u, etag: j.etag, t0: c ? Math.min(c.t0, j.t0) : j.t0 }); }
  const jobs = [...by.values()], bad = new Set();
  const api = `https://api.cloudflare.com/client/v4/zones/${env.CF_ZONE_ID}/purge_cache`;
  for (let i = 0; i < jobs.length; i += 30) {
    const c = jobs.slice(i, i + 30);
    try {
      const r = await fetch(api, { method: "POST", headers: { authorization: `Bearer ${env.CF_PURGE_TOKEN}`, "content-type": "application/json" }, body: JSON.stringify({ files: c.map((j) => j.u) }) });
      const j = await r.json().catch(() => ({}));
      if (!r.ok || !j.success) for (const x of c) bad.add(x.u);
    } catch (_) { for (const x of c) bad.add(x.u); }
  }
  // Verify: the CDN must now serve our write (the ETag R2 returned on put). A newer write elsewhere changes
  // the ETag too; that write queued its own job, so a mismatch here just retries once more.
  const norm = (s) => String(s || "").replace(/^W\//, "").replace(/"/g, "");
  await runPool(jobs.filter((j) => !bad.has(j.u) && j.etag), 6, async (j) => {
    try {
      const r = await fetch(j.u, { headers: { "user-agent": "VRC-A purge-verify", ...(env.CDN_APP_MARK ? { "x-vrca-app": env.CDN_APP_MARK } : {}) } }); await r.body?.cancel();
      const served = norm(r.headers.get("etag"));
      if (served !== norm(j.etag)) {
        // A newer write of the same file supersedes this job: the CDN serving the file's CURRENT version means the
        // purge did its work (an older job would otherwise retry forever, seen live with nm/4bd.json).
        const cur = await env.CATALOG.head(new URL(j.u).pathname.slice(1)).catch(() => null);
        if (!cur || norm(cur.etag) !== served) bad.add(j.u);
      }
    }
    catch (_) { bad.add(j.u); }
  });
  const retry = jobs.filter((j) => bad.has(j.u));
  if (retry.length) await hubQueuePush(env, "pq", retry);
  await hubQueueAck(env, got.keys);
  res.purged = jobs.length - retry.length; res.purgeFailed = retry.length;
  res.purgeOldestMs = retry.length ? Date.now() - Math.min(...retry.map((j) => j.t0)) : 0;
  res.purgeRetry = retry.slice(0, 3).map((j) => j.u.replace(/^https?:\/\/[^/]+\//, ''));   // which files keep failing (for /health)
  return res;
}

// Backfill (admin: POST /admin/searchfill {key}): phase 1 copies every shard into D1 (FILL_SHARDS_PER_STEP a
// step), phase 2 builds every nm/ file from D1 (one D1 scan per NMFILL_BUCKETS_PER_STEP buckets). Steps ride
// the search step, so they run once a minute and never overlap. Progress in the hub (`searchFill`).
async function searchFillStep(env) {
  let st = JSON.parse((await hubGet(env, "searchFill")) || "null");
  // First time D1 is attached: start the backfill by itself (no admin call needed). Runs once.
  if (!st) { st = { phase: 1, next: 0, startedAt: new Date().toISOString(), auto: true }; await hubPut(env, "searchFill", JSON.stringify(st)); }
  if (st.done) return {};
  const db = env.SEARCH; await ensureSearchSchema(db);
  if (st.phase === 1) {
    const from = st.next || 0, to = Math.min(4096, from + FILL_SHARDS_PER_STEP);
    const puts = [];
    for (let n = from; n < to; n++) {
      const sp = n.toString(16).padStart(3, "0");
      try { const o = await env.CATALOG.get(`shard/${sp}.json`); if (!o) continue; const j = await o.json();
        for (const [fid, x] of Object.entries(j.e || {})) if (searchable(x)) puts.push(slim(x, fid)); }
      catch (e) { return { fillErr: "read " + sp }; }   // retry this step next minute
    }
    const seen = new Map(); for (const s of puts) seen.set(s.id, s);   // one row per avatar id
    const w = await d1Apply(db, [...seen.values()], []);
    st.next = to; st.rows = (st.rows || 0) + w.written; st.avatars = (st.avatars || 0) + seen.size;
    if (to >= 4096) { st.phase = 2; st.next = 0; }
  } else if (st.phase === 2) {
    const from = st.next || 0, to = Math.min(4096, from + NMFILL_BUCKETS_PER_STEP);
    const r = await db.prepare("SELECT av.id a, av.f f, av.n n, av.nk k, av.nb b, av.ai ai, av.p p, av.pf pf, au.n au FROM av LEFT JOIN au ON au.id = av.ai WHERE av.nb >= ? AND av.nb < ?").bind(from, to).all();
    const files = new Map();
    for (const x of r.results) { const b = x.b; if (!files.has(b)) files.set(b, {}); const e = files.get(b); (e[x.k] ||= []).push({ a: x.a, f: x.f, n: x.n, au: x.au || "", ai: x.ai, p: x.p, pf: x.pf }); }
    const jobs = [];
    await runPool([...files.keys()], 6, async (b) => {
      const key = `nm/${b.toString(16).padStart(3, "0")}.json`;
      const put = await env.CATALOG.put(key, JSON.stringify({ v: 1, e: files.get(b) }), { httpMetadata: { contentType: "application/json", cacheControl: "public, max-age=" + NAME_TTL } });
      jobs.push({ u: catalogUrl(env, key), etag: (put && put.etag) || "", t0: Date.now() });
    });
    if (jobs.length) await hubQueuePush(env, "pq", jobs);
    st.next = to; st.nmFiles = (st.nmFiles || 0) + files.size; st.readRows = (st.readRows || 0) + ((r.meta && r.meta.rows_read) || 0);
    if (to >= 4096) { st.done = true; st.doneAt = new Date().toISOString(); }
  }
  await hubPut(env, "searchFill", JSON.stringify(st));
  return { fill: st };
}

// GET /search?q=<text>&o=<offset>[&au=<author>] → { r: [ {a,f,n,au,ai,p,pf} ], more }. Every query word must
// match (raw or font-folded form, across name, author and bio); the last word also matches as a prefix.
// Exact folded-name matches first, then FTS rank. With `au`, only that author's avatars (folded name).
// Results are edge-cached for 60 s (saves D1 reads for repeat searches; the request itself still counts).
function ftsQuery(q) {
  const words = foldFancy(q).toLowerCase().split(/[^\p{L}\p{N}]+/u).filter(Boolean).slice(0, 8);
  if (!words.length) return "";
  return words.map((w, i) => `"${w.replace(/"/g, "")}"${i === words.length - 1 ? "*" : ""}`).join(" ");
}
// Search speed limit (user, 2026-10-30: one search per 3 s, enforced by the server so a modified app can't
// skip it). Remembered in Cloudflare's free per-datacenter cache (no D1/hub request per search):
//   - a NEW search (o = 0): one per 3 s per IP + device (the app sends X-VRCA-Device, a random install id),
//   - a further batch of the same search (o > 0, the app's prefetch): one per 1.5 s per IP + device,
//   - per IP overall: 12 per 10 s, so people sharing one Wi-Fi are fine but faking device ids doesn't help.
// Over the limit → 429 { retryAfterMs }; the app waits and retries by itself.
const SEARCH_GAP_MS = 3000, SEARCH_PAGE_GAP_MS = 1500, SEARCH_IP_WINDOW_MS = 10_000, SEARCH_IP_MAX = 12;
async function searchRateLimit(req, url) {
  const ip = req.headers.get("cf-connecting-ip") || "0";
  const dev = (req.headers.get("x-vrca-device") || "none").slice(0, 64).replace(/[^A-Za-z0-9_-]/g, "");
  const o = parseInt(url.searchParams.get("o") || "0", 10) || 0;
  const cache = caches.default, now = Date.now();
  // Cache keys must live on this zone's own host (a foreign host is silently not stored).
  const k = (s) => new Request(`https://${url.host}/__rl/` + encodeURIComponent(s));
  const gap = o > 0 ? SEARCH_PAGE_GAP_MS : SEARCH_GAP_MS;
  const devKey = k(`d:${o > 0 ? "p" : "n"}:${ip}:${dev}`), ipKey = k(`i:${ip}`);
  let last = 0, win = { s: now, n: 0 };
  try { const r = await cache.match(devKey); if (r) last = parseInt(await r.text(), 10) || 0; } catch (_) {}
  try { const r = await cache.match(ipKey); if (r) { const w = JSON.parse(await r.text()); if (now - w.s < SEARCH_IP_WINDOW_MS) win = w; } } catch (_) {}
  if (now - last < gap) return gap - (now - last);
  if (win.n >= SEARCH_IP_MAX) return SEARCH_IP_WINDOW_MS - (now - win.s);
  win.n++;
  const put = (key, body, ms) => cache.put(key, new Response(body, { headers: { "cache-control": "max-age=" + Math.ceil(ms / 1000) } })).catch(() => {});
  await Promise.all([put(devKey, String(now), gap + 1000), put(ipKey, JSON.stringify(win), SEARCH_IP_WINDOW_MS + 1000)]);
  return 0;
}
async function handleSearch(req, env, ctx, url) {
  const cors = { "access-control-allow-origin": "*" };
  if (!env.SEARCH) return json({ ok: false, error: "search not configured" }, 503);
  const wait = await searchRateLimit(req, url);
  if (wait > 0) return new Response(JSON.stringify({ ok: false, error: "slow down", retryAfterMs: wait }),
    { status: 429, headers: { "content-type": "application/json", "retry-after": String(Math.ceil(wait / 1000)), ...cors } });
  const q = (url.searchParams.get("q") || "").slice(0, 120), au = (url.searchParams.get("au") || "").slice(0, 120);
  const o = Math.max(0, Math.min(100000, parseInt(url.searchParams.get("o") || "0", 10) || 0));
  const fq = ftsQuery(au ? q + " " + au : q);
  if (!fq) return json({ ok: true, r: [], more: false });
  // v55 filters, applied when the user presses Search (tapping a filter in the app does nothing on its own):
  // pl = pc | quest | ios (the avatar has that platform), rk = 0..3 (rank Excellent..Poor or better; on `pl` if
  // given, else on EVERY platform the avatar has). p = platform bits (1 PC, 2 Quest, 4 iOS), pf = packed ranks
  // (pc | quest << 4 | ios << 8, 5 = unknown, which never passes).
  const pl = { pc: 0, quest: 1, ios: 2 }[(url.searchParams.get("pl") || "").toLowerCase()];
  const rkRaw = parseInt(url.searchParams.get("rk") || "", 10);
  const rk = rkRaw >= 0 && rkRaw <= 3 ? rkRaw : undefined;
  let filt = "";
  if (pl !== undefined) {
    filt += ` AND (av.p & ${1 << pl}) != 0`;
    if (rk !== undefined) filt += ` AND ((av.pf >> ${pl * 4}) & 15) <= ${rk}`;
  } else if (rk !== undefined) {
    filt += " AND av.p != 0" + [0, 1, 2].map((i) => ` AND ((av.p & ${1 << i}) = 0 OR ((av.pf >> ${i * 4}) & 15) <= ${rk})`).join("");
  }
  const cacheKey = new Request(`https://gremlininc.app/search?q=${encodeURIComponent(q.trim().toLowerCase())}&o=${o}&au=${encodeURIComponent(au.trim().toLowerCase())}&pl=${pl ?? ""}&rk=${rk ?? ""}`);
  const cache = caches.default;
  try { const hit = await cache.match(cacheKey); if (hit) return hit; } catch (_) {}
  await ensureSearchSchema(env.SEARCH);
  const nk = nameKey(q);
  const sql = "SELECT av.id a, av.f f, av.n n, au.n au, av.ai ai, av.p p, av.pf pf FROM avx JOIN av ON av.k = avx.rowid LEFT JOIN au ON au.id = av.ai " +
    "WHERE avx MATCH ?" + (au ? " AND au.k = ?" : "") + filt + " ORDER BY (av.nk = ?) DESC, rank LIMIT ? OFFSET ?";
  const b = au ? [fq, nameKey(au), nk, SEARCH_PAGE + 1, o] : [fq, nk, SEARCH_PAGE + 1, o];
  let rows;
  try { rows = (await env.SEARCH.prepare(sql).bind(...b).all()).results || []; }
  catch (e) { return json({ ok: false, error: "search failed" }, 500); }
  const more = rows.length > SEARCH_PAGE;
  const res = new Response(JSON.stringify({ ok: true, r: rows.slice(0, SEARCH_PAGE).map((x) => ({ ...x, au: x.au || "" })), more }), {
    headers: { "content-type": "application/json", "cache-control": "public, max-age=60", ...cors },
  });
  try { ctx.waitUntil(cache.put(cacheKey, res.clone())); } catch (_) {}
  return res;
}

// ---- v56 service status (/status) -------------------------------------------------------------------------
// state: online | issues | maintenance. "issues" comes from the server's own checks, read from records it already
// keeps (no extra storage reads beyond the hub): catalog updates stuck behind, the search step not running or
// failing, a cache clear stuck for over 30 min. The app's own database session is checked on the device.
const STATUS_CACHE_KEY = new Request("https://gremlininc.app/__status");
const STATUS_STALE_MS = 15 * 60_000;
async function serviceStatus(env, ctx) {
  try { const hit = await caches.default.match(STATUS_CACHE_KEY); if (hit) return hit; } catch (_) {}
  const now = Date.now();
  let maint = null, meta = {}, ss = null;
  try { maint = JSON.parse((await hubGet(env, "maint")) || "null"); } catch (_) {}
  try { meta = JSON.parse((await hubGet(env, "meta")) || "{}"); } catch (_) {}
  try { ss = JSON.parse((await hubGet(env, "searchStat")) || "null"); } catch (_) {}
  const problems = [];
  const lastFlush = meta.lastFlush ? Date.parse(meta.lastFlush) : 0;
  if ((meta.pendingBatches || 0) > 0 && lastFlush && now - lastFlush > STATUS_STALE_MS) problems.push("catalog updates delayed");
  if (env.SEARCH && (!ss || now - (ss.at || 0) > STATUS_STALE_MS || ss.d1Err)) problems.push("avatar search delayed");
  if (ss && ss.purgeOldestMs > PURGE_STUCK_MS) problems.push("some avatar files may be out of date");
  const state = maint && maint.on ? "maintenance" : problems.length ? "issues" : "online";
  const body = { ok: true, state, message: maint && maint.on ? (maint.msg || "") : problems.join(", "), at: now };
  const res = new Response(JSON.stringify(body), { headers: { "content-type": "application/json", "cache-control": "public, max-age=30", "access-control-allow-origin": "*" } });
  try { ctx.waitUntil(caches.default.put(STATUS_CACHE_KEY, res.clone())); } catch (_) {}
  return res;
}

// ---- recovery: re-shard from the R2 db.json master --------------------------
// Line-parses the master (never a whole-file JSON.parse) and writes only the [loN..endN] span's
// shards, so Worker memory + subrequests stay bounded. Returns { written, entries } or null.
async function reshardFromMaster(env, loN, endN) {
  const obj = (env.PRIVATE && await env.PRIVATE.get("db.json")) || await env.CATALOG.get("db.json");
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


// ---- v52 hub: one Durable Object for the run lock, the records and the live feed --------------------
// KV can show another machine a value up to ~60 s old, which made 1-min runs skip each other (stale lock)
// and could lose counts (a run reading `meta` from before the previous run). A Durable Object is one
// instance, so every read sees the last write. Kept in the hub: `meta` (all counters / totals / writes
// today), `recent` (the admin's recent contributions) and `flushlock`. `meta` and `recent` are mirrored to
// KV on every write, so rolling back to a KV-only Worker loses nothing. Without the HUB binding every
// helper falls back to KV (local tests, or a rollback). Also the live feed: each run sends one event,
// broadcast to /live viewers over hibernating WebSockets (no duration billed while idle) with a short
// history so a new viewer isn't empty. Cost: a few hub requests per run (~130k/month at 1 min), inside
// the Workers Paid included Durable Object requests.
const HUB_MIRROR = new Set(["meta", "recent"]);
const HUB_HISTORY = 60;
function hubStub(env) { return env.HUB ? env.HUB.get(env.HUB.idFromName("main")) : null; }
async function hubGet(env, key) {
  const h = hubStub(env);
  if (!h) return env.AVATAR_KV.get(key);
  const r = await h.fetch("https://hub/kv/" + encodeURIComponent(key));
  return r.status === 200 ? r.text() : null;
}
async function hubPut(env, key, val) {
  const h = hubStub(env);
  if (!h) return env.AVATAR_KV.put(key, val);
  const r = await h.fetch("https://hub/kv/" + encodeURIComponent(key), { method: "PUT", body: val });
  if (!r.ok) throw new Error("hub put " + key + " " + r.status);
}
async function hubDel(env, key) {
  const h = hubStub(env);
  if (!h) return env.AVATAR_KV.delete(key);
  await h.fetch("https://hub/kv/" + encodeURIComponent(key), { method: "DELETE" });
}
async function emit(env, ev) {
  const h = hubStub(env);
  if (!h) return;
  try { await h.fetch("https://hub/event", { method: "POST", body: JSON.stringify({ ...ev, at: Date.now() }) }); } catch (_) {}
}

export class Hub {
  constructor(ctx, env) {
    this.ctx = ctx; this.env = env;
    // Viewers' keep-alive pings are answered by Cloudflare without waking the hub (no request, no duration).
    try { ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", '{"t":"pong"}')); } catch (_) {}
  }
  async fetch(req) {
    const url = new URL(req.url);
    const p = url.pathname;
    if (p.startsWith("/kv/")) {
      const key = decodeURIComponent(p.slice(4));
      const sk = "k:" + key;
      if (req.method === "GET") {
        let v = await this.ctx.storage.get(sk);
        if (v === undefined) {
          // First use of a key: take over KV's copy once (continuity from the KV-only Worker).
          v = HUB_MIRROR.has(key) ? await this.env.AVATAR_KV.get(key) : null;
          await this.ctx.storage.put(sk, v);
        }
        return v == null ? new Response("", { status: 404 }) : new Response(v);
      }
      if (req.method === "PUT") {
        const v = await req.text();
        await this.ctx.storage.put(sk, v);
        if (HUB_MIRROR.has(key)) { try { await this.env.AVATAR_KV.put(key, v); } catch (_) {} }
        return new Response("ok");
      }
      if (req.method === "DELETE") { await this.ctx.storage.put(sk, null); return new Response("ok"); }
    }
    if (p === "/event" && req.method === "POST") {
      const ev = await req.text();
      let out = ev;
      const hist = (await this.ctx.storage.get("history")) || [];
      hist.push(ev);
      while (hist.length > HUB_HISTORY) hist.shift();
      await this.ctx.storage.put("history", hist);
      // Totals for the live page's graphs: per hour (48 kept) and per day (60 kept). One extra put per run.
      try {
        const e = JSON.parse(ev);
        if (e.t === "run") {
          const iso = new Date(e.at).toISOString();
          const w = (e.shards || 0) + (e.idx || 0);
          const hours = (await this.ctx.storage.get("hours")) || [];
          let h = hours[hours.length - 1];
          if (!h || h.h !== iso.slice(0, 13)) { h = { h: iso.slice(0, 13), a: 0, r: 0, w: 0, n: 0 }; hours.push(h); }
          h.a += e.added || 0; h.r += e.removed || 0; h.w += w; h.n += 1;
          while (hours.length > 48) hours.shift();
          const days = (await this.ctx.storage.get("days")) || [];
          let d = days[days.length - 1];
          if (!d || d.d !== iso.slice(0, 10)) { d = { d: iso.slice(0, 10), a: 0, r: 0, w: 0, e: 0, c: 0 }; days.push(d); }
          d.a += e.added || 0; d.r += e.removed || 0; d.w += w; d.c = (d.c || 0) + (e.batchCount != null ? e.batchCount : (e.batches || []).length); if (e.entries) d.e = e.entries;
          while (days.length > 60) days.shift();
          await this.ctx.storage.put({ hours, days });
          // Viewers get this hour's and today's totals pushed with the run, so the page never polls.
          out = JSON.stringify({ ...e, hour: h, day: d });
        }
      } catch (_) {}
      for (const ws of this.ctx.getWebSockets()) { try { ws.send(out); } catch (_) {} }
      return new Response("ok");
    }
    // v53 durable queues (search changes `srq`, purge jobs `pq`): items stored in chunks of <100 KB under
    // q:<name>:<seq>, taken oldest first and deleted only on ack (so a crashed step just retakes them).
    if (p === "/q/push" && req.method === "POST") {
      const { name, items } = await req.json();
      let seq = (await this.ctx.storage.get("qseq")) || 0, chunk = [], size = 0;
      const flush = async () => { if (!chunk.length) return; seq++; await this.ctx.storage.put(`q:${name}:${String(seq).padStart(12, "0")}`, chunk); chunk = []; size = 0; };
      for (const it of items || []) { const s = JSON.stringify(it).length; if (size + s > 90_000) await flush(); chunk.push(it); size += s; }
      await flush(); await this.ctx.storage.put("qseq", seq);
      return new Response("ok");
    }
    if (p === "/q/take" && req.method === "POST") {
      const { name, max } = await req.json();
      const m = await this.ctx.storage.list({ prefix: `q:${name}:`, limit: 200 });
      const keys = [], items = [];
      for (const [k, v] of m) { if (items.length && items.length + v.length > max) break; keys.push(k); items.push(...v); }
      return new Response(JSON.stringify({ keys, items, depth: m.size }), { headers: { "content-type": "application/json" } });
    }
    if (p === "/q/ack" && req.method === "POST") {
      const { keys } = await req.json();
      for (let i = 0; i < (keys || []).length; i += 100) await this.ctx.storage.delete(keys.slice(i, i + 100));
      return new Response("ok");
    }
    if (p === "/lock" && req.method === "POST") {
      const { name, ttlMs } = await req.json();
      const until = (await this.ctx.storage.get("lock:" + name)) || 0;
      if (until > Date.now()) return new Response(JSON.stringify({ ok: false }));
      await this.ctx.storage.put("lock:" + name, Date.now() + (ttlMs || 60_000));
      return new Response(JSON.stringify({ ok: true }));
    }
    if (p === "/unlock" && req.method === "POST") {
      const { name } = await req.json(); await this.ctx.storage.delete("lock:" + name);
      return new Response("ok");
    }
    if (p === "/stats") {
      const [hours, days] = [(await this.ctx.storage.get("hours")) || [], (await this.ctx.storage.get("days")) || []];
      return new Response(JSON.stringify({ hours, days }), { headers: { "content-type": "application/json" } });
    }
    if (p === "/ws") {
      if (req.headers.get("Upgrade") !== "websocket") return new Response("websocket only", { status: 426 });
      const pair = new WebSocketPair();
      this.ctx.acceptWebSocket(pair[1]);
      // The viewer asks for the history once it's connected ("hello", below): a message sent before the 101
      // reaches it can be dropped.
      return new Response(null, { status: 101, webSocket: pair[0] });
    }
    return new Response("not found", { status: 404 });
  }
  async webSocketMessage(ws, msg) {
    if (msg === "ping") { try { ws.send('{"t":"pong"}'); } catch (_) {} return; }
    if (msg === "hello") {
      const got = await this.ctx.storage.get(["history", "hours", "days"]);
      const hist = got.get("history") || [];
      try { ws.send(JSON.stringify({ t: "history", events: hist.map((e) => JSON.parse(e)), hours: got.get("hours") || [], days: got.get("days") || [] })); } catch (_) {}
    }
  }
  async webSocketClose(ws, code) { try { ws.close(code, "bye"); } catch (_) {} }
  async webSocketError(ws) { try { ws.close(1011, "error"); } catch (_) {} }
}

// The live page (v52): for testers and users (no budget/cost data). VRC-A colours (GremlinColors) and Roboto, all
// times UTC; laid out for the UI Lab's two sizes (headset 1024x640: one screen, no scrolling; phone 411x891: one
// column). Stat tiles, graphs from the hub's hourly/daily totals (/live/stats), queues, the live activity feed.
const LIVE_HTML = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>VRC-A Live</title>
<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Roboto:wght@400;500;700&display=swap" rel="stylesheet">
<style>
:root{--backdrop:#06101C;--panel:#0A1828;--card:#0F2236;--raised:#142B44;--edge:#223B57;--text:#EAF0FF;--muted:#B3C0DE;--faint:#7D8BA8;
--accent:#8F7CFF;--accent-soft:#B7A9FF;--ok:#53E39F;--warn:#FFD166;--err:#FF6B6B}
*{box-sizing:border-box}html,body{margin:0;background:var(--backdrop);color:var(--text);font:400 14px/1.45 Roboto,system-ui,sans-serif;-webkit-font-smoothing:antialiased}
.top{background:var(--panel);border-bottom:1px solid var(--edge)}
.top .in{max-width:1240px;margin:0 auto;padding:10px 16px;display:flex;align-items:center;gap:12px}
.logo{height:36px;width:auto;display:block;color:var(--text)}
.crumb{color:var(--muted);font-weight:500}.sp{flex:1}
.pill{display:flex;align-items:center;gap:8px;background:var(--raised);border:1px solid var(--edge);border-radius:999px;padding:6px 12px;font-weight:500;font-size:13px}
.dot{width:8px;height:8px;border-radius:50%;background:var(--faint)}.dot.on{background:var(--ok);box-shadow:0 0 0 0 rgba(83,227,159,.5);animation:pulse 2s infinite}.dot.off{background:var(--err)}
@keyframes pulse{0%{box-shadow:0 0 0 0 rgba(83,227,159,.45)}70%{box-shadow:0 0 0 7px rgba(83,227,159,0)}100%{box-shadow:0 0 0 0 rgba(83,227,159,0)}}
.next{color:var(--muted);font-variant-numeric:tabular-nums}
main{max-width:1240px;margin:0 auto;padding:12px 16px 20px}
.kpis{display:grid;grid-template-columns:repeat(4,1fr);gap:10px}
.card{background:var(--card);border:1px solid var(--edge);border-radius:14px;padding:12px 14px}
.lab{color:var(--muted);font-size:12px;font-weight:500;letter-spacing:.3px}
.val{font-size:26px;font-weight:700;font-variant-numeric:tabular-nums;margin-top:4px;line-height:1.15}
.sub{color:var(--muted);font-size:12.5px;margin-top:4px;font-variant-numeric:tabular-nums}
.up{color:var(--ok)}.dn{color:var(--err)}
.meter{height:6px;background:var(--raised);border-radius:4px;overflow:hidden;margin-top:10px}.meter i{display:block;height:100%;width:0;background:var(--accent);border-radius:4px;transition:width .8s}
.meter.warn i{background:var(--warn)}
.cols{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr) minmax(0,.9fr);grid-template-areas:"g n f" "r q f";gap:10px;margin-top:10px}
#aG{grid-area:g}#aN{grid-area:n}#aR{grid-area:r}#aQ{grid-area:q}#aF{grid-area:f;display:flex;flex-direction:column;min-height:0}
.ch-h{display:flex;align-items:baseline;gap:8px;margin-bottom:8px}.ch-t{font-weight:500;font-size:14.5px}.ch-s{color:var(--muted);font-size:12.5px}
.key{display:flex;gap:14px;color:var(--muted);font-size:12px;margin-left:auto}.key span{display:flex;align-items:center;gap:6px}.key i{display:inline-block;width:14px;height:0;border-top:2px dashed var(--warn)}
.chart{position:relative;height:160px;-webkit-user-select:none;user-select:none;-webkit-touch-callout:none;-webkit-tap-highlight-color:transparent}.chart svg{width:100%;height:100%;display:block;overflow:visible}
.empty{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;color:var(--faint);font-size:13px;text-align:center;padding:0 20px}
.q{display:grid;grid-template-columns:1fr auto;row-gap:7px;margin-top:8px}.q div:nth-child(even){font-weight:500;font-variant-numeric:tabular-nums;text-align:right}
.q .n{color:var(--muted)}
.feed{list-style:none;margin:4px 0 0;padding:0;flex:1 1 0;height:0;min-height:0;overflow:auto;scrollbar-width:none}.feed::-webkit-scrollbar{display:none}
.feed li{border-top:1px solid var(--edge);padding:8px 0;animation:in .45s ease-out}.feed li:first-child{border-top:0}
.feed .h{display:flex;gap:8px;align-items:center}.feed .h b{font-weight:500;display:flex;gap:6px;flex-wrap:nowrap;min-width:0}.feed .h .tag{white-space:nowrap}
.feed li.add{box-shadow:inset 3px 0 0 var(--ok);padding-left:12px}.feed li.rem{box-shadow:inset 3px 0 0 var(--err);padding-left:12px}
.tag{font-size:12.5px;font-weight:500;padding:2px 9px;border-radius:999px}.tag.add{color:var(--ok);background:rgba(83,227,159,.12)}.tag.rem{color:var(--err);background:rgba(255,107,107,.12)}.feed .tm{color:var(--faint);font-size:12px;margin-left:auto;white-space:nowrap}
.feed .m{color:var(--muted);font-size:12.5px;margin-top:2px}.feed .nm{display:flex;flex-wrap:wrap;gap:6px;align-items:center;margin-top:4px}
.cols>*,.feed li{min-width:0}.grp{margin-top:5px}.grp .nm{margin-top:0;gap:5px}.feed .m.row{display:flex;flex-wrap:wrap;align-items:center;gap:4px 10px}.feed .m.row:empty{display:none}.feed .m .more{padding:0}.gl{flex:none;font-size:11.5px;font-weight:500;letter-spacing:.3px;margin-right:2px}.gl.add{color:var(--ok)}.gl.rem{color:var(--err)}
.badge.add{border-color:rgba(83,227,159,.35)}.badge.rem{border-color:rgba(255,107,107,.35);color:var(--muted);text-decoration:line-through;text-decoration-color:rgba(255,107,107,.7)}
a.badge:hover{border-color:var(--ok);color:var(--ok)}
.more{background:none;border:0;color:var(--accent-soft);font:500 12px Roboto,system-ui,sans-serif;cursor:pointer;padding:2px 4px}.more:hover{text-decoration:underline}
.badge{text-decoration:none;display:inline-block;font-size:12.5px;line-height:1.35;font-weight:500;padding:2px 9px;border-radius:999px;background:var(--raised);border:1px solid var(--edge);color:var(--text);max-width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
@keyframes in{from{opacity:0;transform:translateY(-4px)}to{opacity:1;transform:none}}
#tip{position:fixed;pointer-events:none;background:var(--raised);border:1px solid var(--edge);border-radius:10px;padding:7px 10px;font-size:12.5px;opacity:0;transition:opacity .12s;z-index:5;white-space:nowrap}
#tip b{font-size:15px;font-variant-numeric:tabular-nums}#tip .tl{color:var(--muted)}
.grid line{stroke:var(--edge);stroke-width:1}.axis text{fill:var(--faint);font-size:11px;font-family:Roboto,system-ui,sans-serif}
@media (max-width:860px){.kpis{grid-template-columns:repeat(2,1fr)}.cols{grid-template-columns:1fr;grid-template-areas:"g" "n" "r" "q" "f"}.feed{flex:none;height:auto;max-height:420px}}
@media (max-width:480px){.crumb{display:none}.val{font-size:22px}.chart{height:150px}}
</style></head><body>
<div class="top"><div class="in"><svg class="logo" viewBox="0 0 428 362" role="img" aria-label="VRC-A"><path fill="currentColor" fill-rule="evenodd" d="M418.4,357C417.8,356.8 416.6,355.7 415.5,354.3C415.1,353.8 414.5,353.2 414.2,353C414,352.8 411.8,350.6 409.3,348.2C406.9,345.7 404.4,343.1 403.7,342.4C403,341.7 401.2,339.9 399.7,338.4C398.2,336.9 393.5,332.2 389.4,328.1C385.3,324 381.4,320 380.7,319.2C380,318.5 378.2,316.7 376.7,315.2C375.2,313.7 371.3,309.8 368,306.5C364.7,303.2 358.9,297.3 355.1,293.4C341.8,280.1 337.9,276.1 337.1,275.1C336.6,274.6 335.9,273.9 335.5,273.7L334.8,273.2L284.5,273.2C130.7,273.1 25.1,272.9 24.5,272.7C24.2,272.6 23.3,272.3 22.5,272.1C21.7,271.9 20.7,271.5 20.2,271.3C19.7,271.1 18.7,270.7 18.1,270.5C16.4,269.8 14.7,268.9 13.8,268.2C12.7,267.4 8.8,263.4 7.9,262.2C7.2,261.2 6.1,259.1 5.9,258.3C5.9,258 5.7,257.4 5.5,257.1C5.3,256.7 5,255.5 4.7,254.4L4.2,252.4L4.3,163.1L4.3,73.8L4.7,71.8C5,70.7 5.4,69.3 5.6,68.8C5.8,68.3 6,67.7 6,67.6C6,67.5 6.2,67 6.5,66.4C6.8,65.9 7,65.3 7,65.3C7,65.1 8.1,63.4 8.6,62.8C9.4,62 11.6,58.5 12.2,57.3C12.9,55.7 13.7,54.6 14.8,53.2C15.5,52.4 16.5,51.2 17,50.5C17.6,49.8 18.4,48.9 18.9,48.4C19.8,47.6 20.7,46.5 21.6,45.3C21.9,45 22.6,44 23.3,43.3C24,42.5 24.7,41.7 25,41.3C26.5,39.4 31.9,32.8 32.7,31.9C33,31.7 33.8,30.7 34.6,29.7C38,25.6 39.2,24.2 39.5,24C39.6,23.9 40.3,23 41,22.1C41.7,21.2 42.9,19.7 43.7,18.9C45.6,16.9 45.5,17 46.2,16C46.6,15.5 47.5,14.5 48.2,13.7C48.9,13 49.9,11.8 50.4,11.1C51,10.3 51.5,9.7 51.6,9.5C51.9,9.2 53.7,8.2 54.4,8C54.8,7.9 55.3,7.6 55.7,7.4C56,7.2 56.3,7 56.5,7C56.6,7 57,6.8 57.4,6.6C58,6.3 59.6,5.8 61.5,5.3C62.6,5.1 65.9,4.8 67.7,4.9C70.7,5.1 73.6,5.7 75.5,6.6C76,6.8 76.5,7 76.6,7C76.7,7 77.8,7.5 78.9,8C80.8,8.9 81.1,9.2 82.3,10.4C83,11.2 84.4,12.7 85.3,13.9C86.3,15.1 87.6,16.6 88.2,17.4C88.9,18.1 90,19.5 90.8,20.5C91.5,21.4 92.7,22.9 93.5,23.7C94.2,24.4 95,25.4 95.2,25.8C95.5,26.1 96.4,27.1 97.3,28C98.2,28.9 99,29.8 99.1,30C99.3,30.3 99.7,30.9 100,31.4C100.3,31.9 101.2,32.9 101.9,33.6C102.6,34.3 103.7,35.6 104.3,36.4C104.9,37.2 105.7,38.2 106.1,38.6C107.1,39.7 108.6,41.4 109.1,42.2C109.4,42.6 110.2,43.5 110.9,44.3C112.1,45.6 114.3,48.2 114.9,49C116.2,50.8 116.7,51.4 117.5,52.1C118,52.5 118.6,53 118.9,53.1C119.3,53.2 145,53.3 213.8,53.4C305.6,53.4 308.1,53.4 308.8,53.1C309.5,52.8 311.5,50.8 311.9,49.9C312.2,49.4 313.9,47.4 314.5,46.8C314.7,46.7 315.5,45.7 316.4,44.6C317.3,43.5 318.9,41.6 320,40.3C321.1,39.1 322.3,37.6 322.9,36.9C323.4,36.3 324.4,35.1 325.2,34.3C325.9,33.6 326.7,32.7 326.8,32.5C327.5,31.5 328.6,30.1 329.8,28.8C330.5,28 331.4,27 331.8,26.4C332.2,25.9 333.4,24.6 334.3,23.6C335.3,22.6 336.3,21.4 336.6,21C336.9,20.6 337.6,19.8 338.1,19.1C339.9,17.1 341.1,15.7 341.6,15C343.6,12.5 346.6,9.5 347.7,8.9C349.6,7.9 354.6,6.3 356.4,6C358.2,5.7 363.6,5.7 365.4,6C367.1,6.3 371,7.6 373.8,8.9C374.8,9.3 376,10.4 376.8,11.5C377,11.8 377.9,12.8 378.8,13.7C379.6,14.5 380.6,15.7 381,16.2C381.3,16.7 382.3,17.8 383,18.5C383.8,19.3 384.6,20.3 384.9,20.7C385.2,21.2 385.6,21.8 385.8,22C385.9,22.2 386.8,23.2 387.7,24.2C388.7,25.1 389.8,26.4 390.2,27C390.7,27.6 392,29.1 393,30.4C394.1,31.6 395.9,33.7 397,35C398.1,36.3 399.4,37.8 399.8,38.3C400.2,38.8 400.7,39.3 400.8,39.5C400.9,39.7 401.8,40.8 402.7,41.8C403.7,42.8 404.6,43.9 404.8,44.2C405.6,45.3 406.9,46.8 408.2,48.2C409,48.9 409.7,49.9 410,50.2C410.2,50.6 411.1,51.7 412,52.6C413.9,54.5 414.2,54.9 415.4,57.1C416.2,58.8 417.8,61.3 418.4,62C418.9,62.5 419.4,63.3 420.4,65.5C420.9,66.5 421.4,67.4 421.5,67.4C421.6,67.5 421.8,67.9 421.9,68.4C422.1,68.9 422.3,69.5 422.5,69.8C422.7,70.2 422.9,71.1 423.1,72C423.4,73.5 423.4,75.3 423.4,101.8C423.3,117.2 423.3,158.8 423.3,194C423.1,307.9 423,354.5 422.8,355.1C422.6,355.6 421.2,356.7 420.4,357C419.7,357.2 419.1,357.2 418.4,357ZM413.8,338.4C414.2,338.2 414.5,337.9 414.6,337.8C414.7,337.7 414.7,336.9 414.7,336.1C415.1,297.8 415.2,276.7 415.1,180.9C415.1,84 415.1,76 414.8,75.4C414.2,73.9 412.9,71.3 412.5,70.9C412.3,70.7 411.8,69.8 411.3,69C410.1,66.7 409.6,66 409.3,65.4C408.5,64.3 407.5,63 406.8,62.3C406.4,61.9 405.8,61.2 405.5,60.8C405.2,60.3 404.1,59 403.1,57.9C402.1,56.7 401,55.3 400.7,54.8C400.4,54.3 399.5,53.2 398.9,52.4C398.2,51.6 397.1,50.2 396.5,49.3C395.9,48.5 395,47.3 394.5,46.7C394.1,46.2 393.5,45.4 393.2,45.1C393,44.7 392.4,44 391.9,43.4C391.5,42.9 390.8,42 390.4,41.4C390.1,40.9 389,39.6 388.2,38.6C387.3,37.6 386.4,36.3 386,35.9C385,34.4 384.8,34.1 383.6,32.6C382.9,31.9 381.9,30.6 381.3,29.8C380.8,28.9 380,27.8 379.6,27.3C376.8,23.9 375.8,22.6 374.8,21.7C373.7,20.6 371.8,19 371.6,19C371.5,19 371.1,18.8 370.8,18.6C370.5,18.4 369.9,18.1 369.4,18C369,17.8 368.2,17.5 367.7,17.4C364.4,16.2 364.1,16.1 360.9,16.1C358.5,16.1 357.8,16.2 357.1,16.4C356.6,16.6 355.7,16.9 354.9,17.1C351.6,18 350.4,18.7 348.1,20.7C347.2,21.4 344.6,24.2 344.1,25C343.8,25.3 343.4,25.9 343.1,26.3C342.8,26.7 342.3,27.4 342.1,27.8C341.3,28.8 338.8,32.2 337.9,33.3C337.4,33.8 336.7,34.8 336.3,35.4C335.9,36.1 334.9,37.3 334.1,38.2C332.8,39.6 332.1,40.5 331.1,42.1C330.9,42.3 330.2,43.3 329.5,44.1C328.8,44.9 327.9,46 327.6,46.6C327.2,47.1 326.7,47.8 326.5,48.1C326.3,48.3 326,48.7 325.8,48.9C325.1,49.9 324.4,50.9 323.3,52C322,53.5 321.4,54.2 321,55.1C320.9,55.4 320.1,56.4 319.4,57.2C317.9,58.8 317.2,59.6 317,60.2C316.9,60.4 316.6,60.8 316.3,61.1L315.7,61.5L287.8,61.5C272.4,61.5 259.7,61.4 259.6,61.4C259.5,61.3 226.1,61.2 185.4,61.2C113.9,61.2 111.4,61.2 110.9,60.9C110.7,60.8 110.3,60.4 110.2,60.1C109.7,59.2 108.8,57.9 107.6,56.5C106.9,55.7 106.2,54.8 105.9,54.4C105.5,53.7 104.1,51.9 103,50.5C102.7,50 102,49.1 101.5,48.5C101,47.9 100.4,47 100,46.5C99.7,46 98.6,44.7 97.6,43.6C96.7,42.4 95.7,41.1 95.3,40.6C95,40.1 94.1,38.9 93.4,38C92.7,37.1 91.6,35.8 91.1,35.1C89.9,33.4 88.9,32.1 87.4,30.2C85.6,27.8 85.4,27.5 84.6,26.2C84.2,25.5 83.4,24.4 82.8,23.8C82.2,23.1 81.3,22.1 80.8,21.4C79.2,19.5 78.7,19.1 75.2,17.5C72.6,16.3 71.3,16 67.9,15.3C67,15.1 65.4,15.2 64,15.6C63.5,15.7 62.7,15.9 62.2,15.9C61.8,16 61,16.2 60.6,16.4C60.1,16.6 59.4,16.9 59,17.1C58.6,17.2 58.2,17.4 58.1,17.5C58,17.6 57.6,17.8 57.2,17.9C55.7,18.4 52.1,21.3 51.1,22.8C50.8,23.2 50.4,23.8 50.1,24.2C49.8,24.6 49.2,25.3 48.9,25.8C48.6,26.2 48,27 47.5,27.6C47,28.1 46.2,29.3 45.6,30.2C45,31 43.9,32.5 43.1,33.4C42.3,34.3 41.3,35.6 40.9,36.2C40.5,36.8 39.8,37.7 39.3,38.3C38.5,39.3 36.7,41.7 35.4,43.6C35.1,43.9 34.5,44.7 34,45.4C32.2,47.5 32,47.7 31.6,48.2C31.4,48.5 30.9,49.3 30.5,49.9C30,50.6 29.2,51.6 28.6,52.2C27.4,53.7 26.5,54.9 26.1,55.6C25.8,56.3 24.7,57.7 24.4,57.9C24.2,58.1 22.1,60.6 21.3,61.6C21,62 20.2,63.1 19.4,64C18.7,64.9 17.9,65.9 17.8,66.2C17.6,66.5 17.1,67.2 16.8,67.7C16.5,68.3 16.1,69 16,69.3C15.9,69.6 15.5,70.4 15.1,71.1C13.3,74.3 13,75 12.3,77.7C12.2,78 12.2,111.4 12.2,161.4L12.2,244.6L12.7,246.8C13.1,248.7 13.7,250.3 14.4,251.5C14.6,251.7 14.9,252.3 15.1,252.8C16.7,256 19.8,259.1 23.2,261.1C23.6,261.3 24.3,261.7 24.8,262C25.3,262.3 25.9,262.6 26.2,262.7C29.1,264.1 30.7,264.4 35.2,264.7C37.4,264.8 78,264.8 182.2,264.8C261.4,264.7 328.3,264.6 330.7,264.6C336,264.4 336.1,264.5 337.4,265.9C338.7,267.2 341.9,270.6 346.6,275.2C348.9,277.5 353.8,282.4 357.4,286.1C361,289.7 368,296.8 373,301.8C399.9,328.8 402.4,331.4 407.4,336.7C408.6,337.9 409.7,338.7 410.7,338.8C411.6,339 413,338.8 413.8,338.4ZM322.1,219.9C320.8,219.6 319.4,218.7 318.2,217.5C316.8,216.1 316.4,215.5 315,213C314.8,212.5 314.5,211.9 314.4,211.8C313.7,210.6 312.3,207 311.9,205.4C311.5,203.7 310.9,202.4 310.4,202.2C310.2,202.1 308.5,202 304.7,201.9C298.9,201.8 298.6,201.8 298.2,202.8C297.6,204.5 297.2,205.7 296.9,206.6C296.7,207.1 296.3,208.2 296,209C295.7,209.8 295.3,210.9 295.1,211.4C294.4,213.4 292.7,215.3 290.8,216.3C288,217.7 283.4,217.3 281,215.5C279,213.9 278.2,212.7 277.8,210.3C277.5,208.5 277.5,208.3 277.8,206.7C277.9,205.6 278.2,204.6 278.4,204.2C278.6,203.7 278.9,203.1 279,202.8C279.1,202.4 279.4,201.8 279.6,201.4C279.8,201.1 280,200.6 280,200.5C280,200.4 280.2,199.9 280.4,199.4C281.2,197.8 281.8,196 282.1,194.5C282.9,190.1 283.1,189.6 285.1,186.7C285.5,186.2 286.3,184.7 286.9,183.4C287.5,182.1 288.1,180.9 288.3,180.6C288.4,180.3 288.7,179.8 288.9,179.4C289.1,179 289.3,178.6 289.4,178.3C289.6,178.1 289.8,177.7 289.9,177.3C290.1,177 290.2,176.6 290.3,176.5C290.7,175.9 290.8,175.6 291.7,173.8C292.1,172.9 292.6,172 292.7,171.8C292.8,171.7 293,171.3 293.1,170.9C293.3,170.5 293.6,170 293.7,169.7C293.9,169.3 294.1,168.9 294.3,168.6C294.4,168.3 294.7,167.8 294.9,167.4C296.2,165 298.4,162.6 300.4,161.6C302.7,160.4 305.8,160.4 308.1,161.6C310.2,162.6 312.7,165.5 315.2,169.7C316.2,171.4 318.2,175.3 319.8,178.8C320.2,179.6 320.9,180.9 321.3,181.7C321.7,182.6 322,183.3 322,183.4C322,183.8 323.1,185.6 323.9,186.6C325.5,188.4 325.9,189.2 326.4,190.8C326.6,191.6 326.9,192.5 326.9,192.9C327,193.3 327.2,194.3 327.5,195.2C328,196.8 328.2,197.3 328.8,199.2C329.3,200.3 330.2,202.4 330.5,202.8C330.6,202.9 331.1,203.6 331.5,204.3C331.9,205 332.3,205.8 332.5,206C333.5,207.5 334.2,210.6 333.9,213C333.7,215.7 331.9,218 329.2,219.4L327.8,220.1L325.3,220.1C323.8,220.1 322.6,220.1 322.1,219.9ZM199.9,216.1C196.9,215.8 193.5,215.1 191.6,214.2C190,213.6 188.3,212.7 187.7,212.2C187.3,212 186.7,211.6 186.2,211.2C185,210.3 182.6,207.6 181.9,206.3C181.8,206 181.5,205.6 181.4,205.5C181.3,205.4 181.1,205 181,204.6C180.9,204.2 180.7,203.8 180.6,203.6C180.4,203.4 180.1,202.5 179.8,201.6C179.4,200.1 179.3,199.5 179.1,196.5C178.8,190.6 179.1,188.4 180.5,183.2C180.7,182.5 181,181.4 181.1,180.8C181.2,180.2 181.5,179.3 181.8,178.7C182,178.2 182.2,177.6 182.2,177.4C182.2,177.2 182.4,176.7 182.6,176.2C182.8,175.8 183,175.3 183,175.2C183,174.9 184.2,172.7 184.6,172.1C184.8,171.8 185,171.5 185,171.4C185,171.1 187.7,168 189,166.7C192.3,163.5 196.5,161.4 200.4,161C203.4,160.7 207.4,160.9 208.9,161.6C209.2,161.7 209.9,161.9 210.4,162C211.8,162.3 216.1,164.6 216.8,165.5C216.9,165.6 217.3,166 217.8,166.4C219.9,168.3 221.6,171.3 222.1,173.8C222.5,176.3 222.6,177.5 222.3,179.2C222,180.5 221.9,180.9 221.3,181.7C219.7,184.3 217.2,185.2 214.9,184.2C213.4,183.6 212.5,182.7 211.8,181.2C211.3,180.2 211.2,179.8 211,178C210.8,175.4 210.4,174.8 208.6,173.8C207.4,173.1 206.4,172.9 204.1,172.7C200.9,172.5 199.5,173 197.4,175.1C195.1,177.3 192.8,182.1 191.7,186.8C191.6,187.5 191.4,188.5 191.2,189.1C190.9,190.5 190.7,194.7 191,196.1C191.6,199.8 194.6,202.8 199.4,204.3C200.6,204.6 201.7,204.9 202.7,204.9C205.9,205.2 213,204.2 215.2,203.3C216.1,202.9 217.9,201.8 218.2,201.5C218.2,201.4 218.7,201.1 219.3,200.8C220.2,200.4 220.7,200.3 221.7,200.2C222.8,200.1 223.1,200.2 224,200.6C225.4,201.2 226.5,202.3 227.2,203.7C227.8,204.7 227.8,204.9 227.8,206.4C227.8,207.8 227.8,208.1 227.3,209.2C226.9,209.9 226.4,210.8 226.1,211.1C225.5,211.7 224,212.7 223.2,213C222.8,213.1 222.4,213.3 222.1,213.5C221.3,213.9 218.8,214.6 215.6,215.2C213.2,215.7 211.7,215.9 208.2,216.1C203.2,216.4 203.7,216.4 199.9,216.1ZM77.2,215.6C76.5,215.2 75.2,213.9 75,213.3C74.9,213 74.6,212.5 74.4,212.2C74.2,211.9 74,211.5 74,211.3C74,211.2 73.8,210.7 73.6,210.3C73.4,209.9 73.1,209.3 73,208.9C72.9,208.5 72.6,207.9 72.4,207.5C72.2,207 72,206.6 72,206.4C72,206.3 71.8,205.8 71.6,205.3C71.4,204.8 71.1,204.1 71,203.7C70.9,203.3 70.7,202.7 70.6,202.5C70.3,202.2 69.9,201.1 69.3,199.1C69.1,198.6 68.8,197.9 68.6,197.4C68.5,197 68.2,196.1 68,195.6C67.8,195 67.6,194.2 67.4,193.7C66.9,192.2 66.5,191.1 66.2,190.3C66.1,189.9 65.8,189.1 65.6,188.5C65.2,187.5 64.7,185.8 63.6,182.2C63.4,181.4 63.1,180.3 63,179.8C62.9,179.3 62.7,178.7 62.6,178.5C62.4,178.2 62.1,176.9 61.8,175.5C61.4,173.6 61.3,172.7 61.3,171.1C61.3,169.2 61.3,169 61.7,168.1C62.3,166.9 62.8,166.3 64,165.6C64.9,165.1 65,165.1 66.7,165.1C68.4,165.1 68.5,165.1 69.4,165.7C71.5,166.8 72.1,167.8 73,171C73.3,172 73.7,173.5 74,174.4C74.3,175.3 74.7,176.9 75,178C75.3,179 75.5,179.9 75.6,180C75.7,180.1 75.9,180.8 76,181.4C76.2,182.1 76.5,183 76.6,183.6C76.8,184.1 77.2,185.3 77.5,186.2C77.7,187.2 78.1,188.3 78.3,188.8C78.4,189.2 78.7,190.1 78.9,190.8C79.1,191.5 79.5,192.3 79.6,192.7C79.8,193.1 80.1,193.8 80.2,194.3C80.6,196.1 81.6,196.5 82,195.1C82.1,194.7 82.3,194.3 82.4,194.2C82.5,194 82.8,193.5 83.1,193C83.4,192.5 83.8,191.8 84,191.4C84.2,191 84.7,190.2 85,189.7C85.3,189.1 85.8,188.3 86,187.9C86.2,187.5 86.7,186.8 87,186.2C87.3,185.7 87.8,184.8 88.1,184.3C88.4,183.8 88.8,183.1 89,182.8C89.2,182.4 89.6,181.6 90,181C90.7,179.9 91.1,179.2 91.9,177.7C92.2,177.3 92.5,176.8 92.6,176.7C92.7,176.5 93,176.1 93.2,175.8C93.4,175.4 93.8,174.7 94.1,174.2C94.4,173.7 94.8,173 94.9,172.8C95,172.5 95.3,172 95.6,171.7C95.8,171.4 96,171 96,170.9C96,170.8 96.2,170.4 96.5,170.1C96.8,169.7 97,169.4 97,169.3C97,169.2 97.6,168.3 98.3,167.2C99,166.2 99.7,165.1 99.8,164.9C100.3,164.2 101.3,163.2 102,162.7C103.3,161.8 105.5,161.5 107.1,161.9C109.1,162.4 110.8,165 110.8,167.5C110.8,168.9 110.1,171.1 109.4,172.1C109.2,172.4 109,172.7 109,172.8C109,172.8 108.7,173.4 108.2,174.1C107.8,174.7 107.3,175.5 107.1,175.9C106.9,176.3 106.6,176.8 106.4,177.1C106.2,177.4 106,177.7 106,177.8C106,177.9 105.8,178.2 105.5,178.6C105.2,179 105,179.3 105,179.4C105,179.5 104.8,179.9 104.5,180.2C104.2,180.6 104,181 104,181.1C104,181.2 103.8,181.5 103.6,181.8C103.4,182 103,182.5 102.8,182.9C102,184.5 101.6,185.1 101.3,185.5C101.1,185.7 101,186 101,186.1C101,186.2 100.8,186.5 100.6,186.8C100.4,187 100.1,187.5 99.9,187.9C99.7,188.3 99.2,189.1 98.8,189.8C98.3,190.4 98,191 98,191C98,191.1 97.7,191.6 97.4,192C97.1,192.5 96.8,192.9 96.8,193C96.8,193.1 96.6,193.4 96.4,193.7C96.2,194 96,194.3 96,194.4C96,194.5 95.8,194.9 95.5,195.2C95.2,195.6 95,196 95,196.1C95,196.2 94.8,196.6 94.5,196.9C94.2,197.3 94,197.7 94,197.8C94,197.8 93.7,198.4 93.2,199.1C92.8,199.7 92.3,200.7 92,201.2C91.7,201.7 91.3,202.5 91,202.9C90.7,203.4 90.3,204.2 90,204.7C89.7,205.3 89.2,206.1 89,206.5C88.7,206.9 88.3,207.7 88,208.2C87.7,208.8 87.4,209.4 87.3,209.5C87.3,209.6 87,210 86.9,210.4C85.8,212.6 85.4,213.3 84.6,214.1C83.4,215.2 82.3,215.6 79.8,215.7C78,215.8 77.7,215.8 77.2,215.6ZM326.6,215.3C327.8,214.9 328.8,214 329,213.1C329.1,212.7 329.2,211.8 329.1,211C329,209.8 328.9,209.4 328.4,208.3C327.7,207.1 327.2,206.3 326.5,205.3C326.1,204.7 325,202.5 325,202.3C325,202.2 324.8,201.8 324.7,201.4C324.5,201 324.2,200.4 324.1,199.9C324,199.4 323.7,198.7 323.5,198.2C323.3,197.8 323.1,197.1 323,196.7C322.9,196.3 322.7,195.7 322.6,195.5C322.4,195.2 322.2,194.3 322.1,193.4C321.8,191.6 321.4,190.8 320,189.3C319.3,188.6 318.7,187.6 317.4,184.8C317.2,184.3 316.8,183.4 316.5,182.8C316.2,182.2 316,181.6 316,181.6C316,181.4 313.3,176 312.2,174.1C311.8,173.3 311.3,172.4 311.1,172.2C310.8,171.6 310.4,170.9 309.1,169C307.7,166.9 305.7,165.3 304.4,165.3C302.6,165.3 300.3,167.3 298.9,169.9C298.2,171.3 295.3,177.1 292.5,183.2C290.6,187.1 289.7,188.8 289,189.7C287.8,191.1 287.3,192.3 287.2,194.3C287.1,195.2 286.9,196.3 286.7,196.7C286.6,197.1 286.3,197.8 286.2,198.3C286,198.8 285.7,199.7 285.4,200.3C285.2,200.8 284.8,201.6 284.6,202.1C284.4,202.5 283.9,203.5 283.6,204.2C282.2,207 282,208.7 282.7,210.2C283.3,211.4 283.9,211.9 285.1,212.2C286.4,212.6 287,212.6 288.1,212.2C289.5,211.8 290.5,210.6 291.1,208.5C291.6,206.9 292.3,204.8 292.5,204.4C292.6,204.2 292.9,203.5 293.1,202.8C294.1,199.8 294.3,199.3 294.7,198.5C295.5,197.1 294.8,197.2 304.7,197.2L313.4,197.2L314.1,197.7C314.7,198.1 314.9,198.3 315.3,199.7C315.6,200.6 316,201.8 316.2,202.6C317.3,207.4 318.7,210.5 320.7,213.2C322.3,215.5 324.3,216.2 326.6,215.3ZM128.3,214.4C128.1,214.3 127.7,214.1 127.3,214C125.8,213.5 124.3,211.7 123.8,210C123.6,209.1 123.5,208.4 123.5,205.8C123.5,204.1 123.5,202.6 123.4,202.5C123.4,202.4 123.2,200.8 123.1,199.1C123,197.3 122.7,194.9 122.5,193.6C121.6,187.5 121.5,183.1 121.9,177.6C122.1,174.9 122.3,174.2 122.7,172.9C123.3,171.3 123.5,170.9 124.7,169.4C125.6,168.4 126.4,167.7 129.1,165.9C130.2,165.2 130.7,164.9 132.9,163.8C135.1,162.7 136.5,162.2 138.2,161.9C139.1,161.8 140.3,161.6 140.9,161.4C141.6,161.3 143,161.2 144.4,161.2C146.4,161.2 147,161.2 148.7,161.7C149.8,161.9 151.4,162.4 152.1,162.8C153.5,163.4 155.6,164.7 156.4,165.5C157.4,166.4 158.8,168.3 159.3,169.4C160,170.8 160.5,173.3 160.5,174.9C160.5,176.3 160,178.8 159.5,179.5C159.4,179.7 159.1,180.2 159,180.5C158.8,180.8 158.2,181.6 157.6,182.3C156.2,183.8 156.2,184.2 157.5,184.8C157.9,185 158.5,185.4 158.8,185.6C159,185.8 159.3,186 159.4,186C159.7,186 161.4,187.5 162,188.3C164.1,191.1 165.6,195.6 166.1,200.5C166.2,202 166.4,203.9 166.5,204.7C166.9,207.6 166.4,209.4 164.9,210.9C163.1,212.7 160.3,213.3 158,212.2C157.3,211.9 156.5,211.3 156,210.8C154.8,209.7 154.5,208.6 154.4,205.3C154.4,203.9 154.2,201.8 154.1,200.7C153.6,196.6 152.3,195 148.4,193.6L146.8,193L143.1,192.8C140.3,192.7 139,192.8 137.8,192.9C134.7,193.4 134.6,193.6 135,197.2C135.2,198.6 135.4,200.4 135.6,201.2C136,203.7 136.7,207.7 136.8,208.7C136.9,210.3 135.8,212.1 133.9,213.4C132.3,214.5 129.4,215 128.3,214.4ZM209.2,213.5C209.6,213.4 210.8,213.3 211.8,213.2C212.9,213.1 214.2,212.9 214.8,212.8C217.7,212.1 219.6,211.7 219.7,211.5C219.8,211.4 220.3,211.2 220.8,211.1C222.6,210.5 224.3,209.4 224.8,208.3C225.1,207.8 225.2,206.3 225,205.5C224.8,204.7 223.9,203.6 223.1,203.2C222.5,202.9 221.2,202.9 220.6,203.2C220.4,203.3 219.8,203.7 219.3,204.1C218.8,204.6 218,205.1 217.6,205.4C216.7,206 213.9,206.8 212.1,207C211.3,207.1 210.2,207.3 209.8,207.4C208.7,207.7 202.8,207.9 201.5,207.7C197,206.9 193.4,205.1 191.1,202.5C190.6,201.9 190,201.1 189.9,200.8C189.7,200.4 189.4,200 189.3,199.8C189.2,199.7 188.9,199 188.6,198.2L188.1,196.9L188.1,194C188.1,192.4 188.2,190.6 188.3,190.1C188.8,187.1 189.4,184.6 189.6,184.2C189.7,184 189.9,183.3 190,182.8C190.1,182.3 190.4,181.5 190.6,181.1C190.8,180.6 191,180.1 191,180C191,179.9 191.3,179.2 191.6,178.5C193.9,174 197.5,170.6 200.6,170C203.3,169.5 206.2,169.7 208.2,170.6C211.3,172 212.8,173.1 213.2,174.5C213.4,174.9 213.6,176.3 213.7,177.6C213.9,178.9 214.1,180.1 214.1,180.3C214.2,180.6 214.6,181 215,181.4C215.6,181.9 215.8,182 216.6,182C217.4,182 217.6,181.9 218.2,181.4C218.6,181 219.1,180.3 219.3,179.8C219.7,179 219.7,178.7 219.7,177.1C219.7,172.2 217.1,168.2 212.1,165.6C210,164.5 208.2,164.1 205.4,163.8C201.9,163.5 198.8,163.8 197,164.6C196.1,165 195.9,165.1 194.4,165.9C192.2,167.1 189.2,170 187.7,172.2C187.3,172.9 186.8,173.6 186.7,173.8C186.2,174.5 185,177.1 184.9,177.7C184.8,178 184.7,178.5 184.5,178.8C184.3,179.2 184.1,179.9 184,180.4C183.9,181 183.7,181.7 183.6,182C183.4,182.3 183.1,183.4 182.9,184.3C182.7,185.3 182.4,186.6 182.3,187.2C181.8,189 181.6,193.7 181.8,196.8C182,199 182.1,199.8 182.5,201C182.8,201.9 183,202.6 183,202.7C183,202.9 184.2,205 184.9,206.1C185.5,206.9 187.5,209 187.7,209C187.7,209 188.1,209.3 188.5,209.6C189.3,210.2 192.2,211.7 193.3,212C193.7,212.1 194.5,212.3 195,212.5C196.8,213.2 200.9,213.6 204.9,213.6C206.9,213.6 208.9,213.6 209.2,213.5ZM82.2,212.5C82.9,212 83.7,210.9 83.9,210.1C84,209.8 84.2,209.4 84.3,209.2C84.4,208.9 85,208 85.4,207.1C85.9,206.1 86.5,205.1 86.7,204.8C87.6,203.3 88,202.7 88,202.5C88,202.5 88.2,202.1 88.5,201.8C88.8,201.4 89,201.1 89,201C89,200.8 90.8,197.7 91.6,196.5C91.8,196.2 92,195.8 92,195.7C92,195.6 92.2,195.3 92.4,195.1C92.6,194.8 92.9,194.3 93.1,194C93.2,193.7 93.6,193 93.9,192.5C94.2,192 94.6,191.3 94.8,190.9C95,190.6 95.3,190.1 95.5,189.8C95.7,189.6 96.3,188.5 97,187.3C97.7,186.2 98.4,185 98.6,184.7C98.8,184.3 99.1,183.8 99.3,183.5C100.2,181.9 101.3,180.2 101.6,179.7C101.8,179.3 102.2,178.6 102.6,178C103,177.4 103.4,176.7 103.6,176.4C103.8,176.2 104,175.9 104,175.8C104,175.7 104.7,174.6 105.5,173.5C107.6,170.5 108.5,167.8 107.7,166.3C107.3,165.4 106.6,164.7 105.9,164.5C105.4,164.3 105.2,164.3 104.2,164.7C103,165.2 102.4,165.7 101.5,167C100.4,168.8 100,169.3 100,169.5C100,169.5 99.9,169.7 99.7,169.9C99.5,170.1 99.2,170.5 99,170.9C98.8,171.3 98.4,172 98,172.6C97.6,173.1 97.2,173.8 97.1,174.2C96.9,174.5 96.6,175 96.4,175.2C96.2,175.5 96,175.8 96,175.9C96,176 95.9,176.3 95.7,176.5C95.5,176.8 95.2,177.3 94.9,177.7C93.9,179.6 93.4,180.4 93,181.1C92.6,181.8 92.2,182.4 91.1,184.4C90.8,185 90.4,185.7 90.2,186.1C90,186.5 89.7,187 89.5,187.2C89.3,187.4 89.1,187.9 88.9,188.2C88.8,188.5 88.4,189.2 88,189.8C87.6,190.4 87.2,191.2 87,191.6C86.8,192 86.4,192.7 86,193.3C85.6,193.9 85.1,194.8 84.9,195.2C84.7,195.6 84.2,196.3 83.9,196.8C83.2,198.1 82.1,200.1 81.5,201.7C80.9,203.1 80.6,203.4 80.1,203C79.9,202.9 79.8,202.5 79.7,202.2C79.7,201.9 79.5,201.1 79.3,200.6C79.1,200 79,199.5 79,199.3C79,199.2 78.8,198.7 78.6,198.3C78.4,197.9 78.1,197.1 77.9,196.7C77.8,196.2 77.6,195.7 77.5,195.5C77.4,195.3 77.1,194.6 77,194C76.7,192.9 76.3,191.6 75.6,189.7C75.4,189.1 75.1,188.1 74.9,187.6C74.8,187 74.5,186.1 74.3,185.5C73.2,182 73,181.3 73,181.1C73,181 72.8,180.2 72.5,179.3C71.8,176.9 71.3,175.2 71,173.7C70.3,170.5 69.9,169.3 69.3,168.8C68.5,168.1 67.8,167.8 66.6,167.8C65.5,167.8 65.4,167.8 64.7,168.5C64.1,169.2 64,169.3 63.9,170.6C63.8,172.1 64,174 64.5,175.6C64.7,176.1 64.9,177 65,177.6C65.1,178.1 65.3,178.7 65.4,178.9C65.4,179 65.7,179.8 65.9,180.6C66.1,181.4 66.5,182.5 66.6,183.1C66.8,183.6 67.2,184.9 67.5,185.8C67.8,186.8 68.2,188.1 68.5,188.8C68.8,189.5 69,190.3 69.1,190.6C69.1,190.8 69.3,191.4 69.5,191.8C69.7,192.1 69.9,192.8 70,193.2C70.1,193.7 70.3,194.3 70.4,194.5C70.6,194.8 70.8,195.5 71,196.1C71.2,196.6 71.5,197.5 71.6,198C72.5,200.2 72.7,200.6 72.9,201.3C73,201.7 73.3,202.4 73.5,202.8C73.7,203.3 73.9,203.8 73.9,204.1C74,204.4 74.2,204.9 74.4,205.4C74.7,205.8 74.9,206.4 75.1,206.8C76,209.4 77.3,211.9 77.8,212.6C78.2,213 78.3,213 79.8,213C81.2,212.9 81.5,212.8 82.2,212.5ZM132.1,211.3C132.5,211.1 133.2,210.5 133.5,210.2C134,209.5 134,209.4 134,208.1C133.9,207.4 133.7,206.2 133.5,205.6C133.3,204.9 133.1,203.7 133,202.9C132.9,202.1 132.7,200.9 132.6,200.2C132.2,198.3 131.9,194.5 132,192.6C132,192.1 133.4,191.2 134.5,191C135,190.9 135.9,190.7 136.4,190.5C139.5,189.5 145.3,189.6 148.7,190.8C151.2,191.6 152.4,192.2 154,193.6C155,194.5 155.7,195.7 156.3,197.6C157,199.8 157,200.2 157.1,204.6L157.2,208.4L157.9,209.2C158.3,209.8 158.7,210 159.4,210.3C160.3,210.5 160.3,210.5 161.4,210.3C162.3,210 162.6,209.8 163.2,209.2L163.9,208.5L163.8,205C163.7,203.1 163.6,201.2 163.5,200.7C163.4,200.2 163.2,199 163.1,198.1C162.8,196.4 161.9,193.5 161.3,192.4C161.2,192 161,191.7 161,191.6C161,191.2 159.5,189.3 158.9,188.8C157.9,188.1 156.4,187.2 155.6,186.9C155.2,186.8 154.8,186.5 154.5,186.4C154.3,186.3 153.7,186.1 153,185.9C150.7,185.4 149.8,185.1 149.7,184.8C149.6,184.3 150,184 150.6,183.9C151.3,183.8 152.3,183.3 153.5,182.4C155.4,181.1 156.7,179.6 157.3,177.9C157.7,177 157.8,176.7 157.8,174.8C157.8,173 157.7,172.6 157.3,171.6C156.8,170.2 155.8,168.7 154.9,167.8C154.1,167.1 152.5,165.8 152.3,165.8C152.2,165.8 151.9,165.7 151.7,165.5C151.4,165.3 150.3,165 149.2,164.7C147.5,164.2 146.9,164.1 144.6,164C141.7,164 140.8,164.1 138,164.8C135.5,165.5 133.7,166.3 130.9,168.1C128.3,169.6 126.6,171.1 125.8,172.5C125.1,173.8 125.1,173.8 124.8,176.7C124.5,179.3 124.4,185.1 124.7,187.4C124.8,188.2 124.9,189.9 125,191.2C125.1,192.4 125.3,194 125.4,194.8C126.1,198.9 126.3,202.7 126.2,207.3L126.1,209.1L126.7,210C127.5,211.2 128.6,211.8 130.1,211.8C131,211.8 131.3,211.8 132.1,211.3ZM261.1,197.7C260.5,197.6 259.6,197.4 259.2,197.3C257.4,196.9 255.2,196.8 249.5,196.9L243.4,196.9L242.4,196.4C240.9,195.6 240.1,194.7 239.8,193.2C239.4,191.6 239.4,190.8 239.8,189.4C240.1,188.4 240.4,188 241,187.3C242.3,186 242.7,185.9 247.8,185.5C250.9,185.3 254.2,185.1 258.8,185.1L265.4,185.1L266.5,185.6C268,186.5 268.7,187.3 269.2,188.9C270,191.3 269.7,192.7 268.2,194.7C266.4,197.1 264.1,198 261.1,197.7ZM264.7,194.4C266,193.6 266.9,192.1 266.9,190.9C266.9,190.2 266.8,189.9 266.4,189.3C265.4,187.9 265.4,187.9 261.4,187.9C259.4,187.8 254.8,187.9 251.2,188L244.8,188.2L243.9,188.6C242.6,189.2 242.2,190.1 242.2,191.5C242.2,192.5 242.2,192.7 242.6,193.2C243.4,194.3 243.4,194.3 250.1,194.2C255.5,194.2 256.2,194.2 257.8,194.5C260,194.9 260.4,194.9 262.3,195C263.7,195 263.8,195 264.7,194.4ZM302.4,189C300.7,189 299.2,188.9 299,188.8C298.7,188.6 298.8,188 299.3,186.9C299.6,186.3 299.8,185.7 299.9,185.5C300,185.3 300.3,184.5 300.6,183.8C300.9,183.1 301.3,182.3 301.5,181.9C301.7,181.5 302.1,180.6 302.5,179.8C302.8,179.1 303.2,178.2 303.3,177.9C303.7,176.8 304.6,176.5 304.9,177.3C305,177.5 305.4,178.3 305.8,179.1C306.2,179.8 306.9,181.2 307.4,182.2C307.9,183.3 308.4,184.3 308.6,184.5C309.3,185.8 310.5,188.4 310.5,188.6C310.5,189.2 310,189.2 302.4,189ZM131.5,184.8C131.1,184.6 131.1,184.5 130.9,181.2C130.7,177 130.9,175.9 131.7,174.7C132.1,174.3 132.6,173.7 133,173.5C133.3,173.2 133.9,172.8 134.2,172.5C134.6,172.2 135,172 135.1,172C135.2,172 135.6,171.8 136.1,171.6C136.5,171.4 137.3,171.1 137.9,170.9C138.4,170.8 139.1,170.6 139.2,170.5C139.7,170.2 142.8,169.8 144.3,169.8C146,169.8 147.3,170.1 148.5,170.8C149.7,171.5 150.8,172.8 151,173.8C151.2,174.8 151.2,176.3 150.9,176.9C150.5,177.8 148.6,179.3 147,180.1C146.6,180.3 146.1,180.5 145.9,180.6C145.6,180.8 144.3,181.4 142.3,182.1C141.7,182.3 140.6,182.7 139.9,182.9C139.1,183.1 138.2,183.4 137.8,183.6C137.4,183.7 136.6,183.9 136.1,184C135.5,184.1 134.7,184.3 134.3,184.4C133,184.8 131.9,185 131.5,184.8ZM135,181.4C135.2,181.3 135.9,181.1 136.4,181C137,180.9 137.8,180.7 138.2,180.5C138.7,180.3 139.6,180 140.2,179.8C142.4,179.1 143.6,178.7 143.7,178.5C143.9,178.4 144.3,178.2 144.7,178.1C146.4,177.5 148.3,175.9 148.3,175.1C148.3,174.3 147.7,173.5 146.9,173.1C146.3,172.9 145.8,172.8 143.7,172.8C141.1,172.8 139.9,173 138.8,173.5C138.4,173.7 137.8,173.8 137.5,173.9C137.3,174 136.8,174.2 136.4,174.4C136.1,174.6 135.7,174.8 135.6,174.9C135,175.1 134,176.3 133.8,176.9C133.7,177.3 133.7,178.4 133.7,179.3C133.7,180.8 133.7,181 134,181.3C134.4,181.7 134.4,181.7 135,181.4ZM246.2,134.6C245.4,134.5 243.7,134.2 242.5,134.1C240.3,133.9 237.9,133.3 235.2,132.6C234.5,132.4 233.3,132.1 232.7,131.9C230.1,131.4 227.5,130.2 223.9,128C223,127.4 222.1,126.9 220.8,126.2C220,125.8 219,125 217.6,123.7C216.9,123.1 215.9,122.2 215.3,121.7C214.7,121.1 213.9,120.2 213.5,119.5C213,118.9 212.6,118.3 212.5,118.3C212.2,118.3 211.7,118.8 210.3,120.3C209.1,121.5 207.2,123.2 205.2,124.7C203.8,125.7 203.1,126.1 200.7,127.2C200.2,127.5 199.2,128 198.6,128.3C198,128.7 197.4,129 197.3,129C197.2,129 196.8,129.2 196.4,129.4C196.1,129.6 195.5,129.9 195.1,130C194.7,130.1 194.1,130.4 193.7,130.6C192.7,131.1 189.2,131.8 186.2,132.1C184.8,132.2 183.2,132.5 182.6,132.6C180.9,133 173.6,133 171.9,132.6C171.3,132.5 169.3,132.2 167.6,132C165.8,131.8 163.6,131.5 162.7,131.3C161.1,130.9 160.4,130.6 157.6,129.3C157,129.1 156.5,128.8 156.3,128.8C156.2,128.7 155.6,128.4 155.1,128.1C154.5,127.8 153.3,127.1 152.4,126.7C151.4,126.2 150.3,125.5 149.9,125.2C146.2,122.4 144.7,121.1 142.4,118.3C140.4,116 139,113.9 139,113.2C139,113.1 138.8,112.6 138.5,112C137.8,110.6 137,108.9 137,108.7C137,108.6 136.8,108 136.6,107.3C136.2,106.3 135.8,103.5 135.8,102.5C135.8,102.3 135.9,102 136.1,101.7L136.3,101.3L141.1,101.3L145.8,101.3L146.3,101.8C146.8,102.2 146.9,102.4 147,103.9C147.1,104.8 147.3,105.9 147.5,106.3C147.7,106.7 148,107.6 148.2,108.2C149.4,111.8 149.5,111.9 151.5,114.3C152.9,115.9 154.5,117.4 155.6,118.1C155.8,118.3 156.4,118.6 156.8,118.9C159.6,120.9 161,121.7 162,122C162.4,122.1 163.1,122.3 163.6,122.5C166,123.4 170.5,124.5 173,124.8C174.9,125.1 179.4,125.1 181.2,124.8C183.7,124.5 186.6,123.8 189,123C190.3,122.6 191.7,122.2 192,122.1C192.6,122 194,121.2 195.8,120.1C197.1,119.3 197.5,119 198.8,117.9C201.9,115.4 202.4,114.9 203.3,113.6C203.8,112.8 204.3,112.1 204.5,111.8C204.8,111.4 205.7,109.5 206.4,108C206.9,106.9 207.4,104.4 207.3,104.1C207.1,103.6 207.5,102.5 207.9,102.3C208.2,102 209,102 212.8,102C215.5,102 217.5,102.1 217.7,102.2C218.1,102.4 218.3,103.1 218.4,104.7C218.5,106.4 219.1,108.7 219.5,109.6C219.7,110 219.9,110.5 220.1,110.8C220.2,111.2 220.6,111.8 221,112.3C221.4,112.8 222.1,113.9 222.6,114.6C223.5,115.9 225.2,117.6 228.7,120.5C229.8,121.4 232.9,122.9 236.1,124C236.6,124.2 237.1,124.4 237.4,124.5C237.6,124.6 238.2,124.9 238.8,125C239.4,125.2 240.3,125.4 240.8,125.6C244.5,126.6 251.1,126.9 255.8,126.2C257.3,126 258.8,125.7 259.2,125.6C259.6,125.4 260.4,125.2 261.1,125C262.2,124.7 263.7,124.1 265.1,123.5C265.7,123.2 267.3,122.5 268.5,122.1C270,121.5 273.7,118.7 276.2,116.2C277.7,114.6 281,109 281,108C281,107.8 281.2,107.1 281.4,106.3C281.6,105.6 281.8,104.6 281.8,104.2C281.8,102.8 281.7,102.8 287.2,102.8C293,102.8 292.8,102.8 292.7,104.7C292.7,105.4 292.6,106.5 292.4,107.2C292.3,108 292.1,109.1 292,109.8C291.9,110.4 291.6,111.3 291.4,111.8C291.2,112.3 291,112.7 291,112.8C291,113 289.3,115.7 288,117.3C287.6,117.8 287.2,118.5 287.1,118.8C286.7,119.7 285.7,120.8 284.8,121.6C284.3,121.9 283.3,122.9 282.5,123.6C281.7,124.3 280.8,125.1 280.5,125.3C279.7,125.9 278.2,126.8 277.6,127.1C277.3,127.2 276.6,127.6 276.1,128C275.5,128.4 274.9,128.8 274.6,128.9C274.3,129 273.9,129.3 273.7,129.4C272.5,130.3 269.1,131.6 267.2,131.9C266.9,132 265.8,132.2 264.9,132.5C264,132.8 262.4,133.2 261.4,133.4C259.7,133.8 258.8,133.9 253.8,134.6C251.6,134.9 248.1,134.9 246.2,134.6ZM116.6,106.7C115.4,106.5 113.8,106 113.1,105.5C112.9,105.3 112.6,105.2 112.5,105.2C112.2,105.2 109.9,103 109.1,102C108.1,100.7 107,99.1 107,98.9C107,98.9 106.8,98.3 106.5,97.7C105.9,96.6 105.9,96.5 105.9,94.3C105.8,92.9 105.9,91.6 106,90.9C106.2,89.8 107.1,87.9 107.8,86.8C108.1,86.4 108.6,85.8 108.8,85.4C110,83.6 111.1,82.6 112.8,82C113.2,81.8 113.6,81.6 113.7,81.5C113.9,81.4 114.7,81.1 115.6,80.7C117.2,80.2 117.5,80.2 119.5,80.2C121.5,80.2 121.8,80.2 123.1,80.7C123.8,80.9 124.8,81.3 125.2,81.6C125.6,81.8 126.1,82 126.3,82.1C127.1,82.3 128.4,83.3 129.1,84.1C130.9,86.3 132.3,88.9 132.8,90.8C133,91.6 133,95.7 132.8,96.5C132.2,99 129.8,102.6 127.7,104.2C126,105.5 125.6,105.7 124.3,106.2C123.2,106.7 122.9,106.7 120.3,106.8C118.8,106.8 117.1,106.7 116.6,106.7ZM305.5,106.7C304.1,106.5 302.4,106 301.9,105.5C301.7,105.3 301.4,105.1 301.2,105C300.6,104.7 299.6,103.9 298.4,102.7C297.6,101.9 297.2,101.3 296.6,100.1C296.1,99.2 295.6,98 295.4,97.5C295,96.7 294.9,96.4 294.9,94.3C294.8,93 294.9,91.5 295,91C295.2,90 296.1,87.6 296.5,87.1C296.6,87 296.8,86.6 297,86.2C297.4,85.2 298.2,84.2 299,83.5C299.8,82.9 301.2,82.1 301.7,81.9C301.9,81.8 302.3,81.6 302.5,81.5C302.8,81.3 303.7,80.9 304.6,80.7C306,80.2 306.4,80.2 308.4,80.2C310.4,80.2 310.7,80.2 311.9,80.7C315.1,81.8 316.8,82.9 318.4,84.8C319.6,86.2 320,86.7 320.7,88C321.4,89.2 321.7,93.8 321.3,96.8C320.9,99.3 320.3,100.3 317.6,103C315.1,105.3 314.7,105.7 312.8,106.3C311.8,106.6 311.2,106.7 309.2,106.7C307.8,106.8 306.2,106.7 305.5,106.7Z"/></svg><div class="crumb">Avatar catalog</div><div class="sp"></div>
<div class="pill"><span id="dot" class="dot"></span><span id="conn">Connecting</span></div><div class="next"><span id="clock">–</span> UTC</div></div></div>
<main>
<section class="kpis">
 <div class="card"><div class="lab">Avatars</div><div class="val" data-n="entries">–</div><div class="sub"><span class="up" id="todayNet">–</span> today</div></div>
 <div class="card"><div class="lab">New today</div><div class="val" data-n="addedToday">–</div><div class="sub"><span data-n="addedHour">–</span> this hour</div></div>
 <div class="card"><div class="lab">Removed today</div><div class="val" data-n="removedToday">–</div><div class="sub"><span data-n="removedHour">–</span> this hour</div></div>
 <div class="card"><div class="lab">Contributions today</div><div class="val" data-n="contribToday">–</div><div class="sub">sent by VRC-A users</div></div>
</section>
<section class="cols">
  <div class="card" id="aG"><div class="ch-h"><span class="ch-t">Catalog size</span><span class="ch-s">per day, last 30 days (UTC)</span></div><div class="chart" id="cGrowth"></div></div>
  <div class="card" id="aN"><div class="ch-h"><span class="ch-t">New avatars</span><span class="ch-s">per hour, last 48 h (UTC)</span></div><div class="chart" id="cNew"></div></div>
  <div class="card" id="aR"><div class="ch-h"><span class="ch-t">Removed avatars</span><span class="ch-s">per hour, last 48 h (UTC)</span></div><div class="chart" id="cRemoved"></div></div>
  <div class="card" id="aQ"><div class="ch-t">Waiting to be applied</div><div class="q">
   <div class="n">Contribution batches</div><div data-n="pendingBatches">–</div>
   <div class="n">Reports</div><div data-n="reports">–</div>
   <div class="n">Updates to existing avatars</div><div data-n="calmWaiting">–</div>
   <div class="n">Last update</div><div id="last">–</div>
   <div class="n">Next update</div><div id="next">–</div></div></div>
  <div class="card" id="aF"><div class="ch-h"><span class="ch-t">Live activity</span><span class="ch-s">UTC+0</span></div><ul class="feed" id="feed"><li class="m">Waiting for avatars to be added or removed…</li></ul></div>
</section>
</main>
<div id="tip"></div>
<script>
var H=location.origin,shown={},snapH=null,stats={hours:[],days:[]},lastAt=0,DAYS=['Sun','Mon','Tue','Wed','Thu','Fri','Sat'],MON=['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
function p2(n){return (n<10?'0':'')+n}
function utcTime(t,sec){var d=new Date(t);return p2(d.getUTCHours())+':'+p2(d.getUTCMinutes())+(sec?':'+p2(d.getUTCSeconds()):'')}
function fmt(n){return Math.round(n).toLocaleString()}
function setN(k,v){if(v==null||isNaN(v))return;var from=shown[k]==null?v:shown[k],t0=performance.now();
 function st(t){var u=Math.min(1,(t-t0)/1100),e=1-Math.pow(1-u,3),c=from+(v-from)*e;shown[k]=c;
  var els=document.querySelectorAll('[data-n="'+k+'"]');for(var i=0;i<els.length;i++)els[i].textContent=fmt(c);if(u<1)requestAnimationFrame(st)}
 requestAnimationFrame(st)}
var tip=document.getElementById('tip');
function showTip(x,y,val,label){tip.textContent='';var b=document.createElement('b');b.textContent=val;var l=document.createElement('div');l.className='tl';l.textContent=label;
 tip.appendChild(b);tip.appendChild(l);tip.style.opacity=1;var w=tip.offsetWidth;tip.style.left=Math.min(innerWidth-w-8,Math.max(8,x-w/2))+'px';tip.style.top=(y-tip.offsetHeight-12)+'px'}
function hideTip(){tip.style.opacity=0}
var NS='http://www.w3.org/2000/svg';function el(n,a){var e=document.createElementNS(NS,n);for(var k in a)e.setAttribute(k,a[k]);return e}
function niceStep(m){if(m<=0)return 1;var p=Math.pow(10,Math.floor(Math.log10(m))),f=m/p;return (f<=1?1:f<=2?2:f<=5?5:10)*p}
function niceScale(max){var st=Math.max(1,niceStep(max/4)),top=Math.max(st,Math.ceil(max/st)*st);return {max:top,ticks:Math.round(top/st)}}
function frame(box,ymin,ymax,ticks){box.classList.remove('isEmpty');var W=box.clientWidth,Hh=box.clientHeight,L=(ymax>=10000&&(ymax-ymin)<4000)?62:46,R=8,T=8,B=22;box.textContent='';var svg=el('svg',{viewBox:'0 0 '+W+' '+Hh});box.appendChild(svg);
 var g=el('g',{'class':'grid'}),ax=el('g',{'class':'axis'});svg.appendChild(g);svg.appendChild(ax);
 for(var i=0;i<=ticks;i++){var v=ymin+(ymax-ymin)*i/ticks,y=T+(Hh-T-B)*(1-i/ticks);g.appendChild(el('line',{x1:L,x2:W-R,y1:y,y2:y}));
  var t=el('text',{x:L-8,y:y+4,'text-anchor':'end'});t.textContent=(v>=10000&&(ymax-ymin)>=4000)?(Math.round(v/100)/10).toLocaleString()+'k':fmt(v);ax.appendChild(t)}
 return {svg:svg,ax:ax,W:W,H:Hh,L:L,R:R,T:T,B:B,y:function(v){return T+(Hh-T-B)*(1-(v-ymin)/(ymax-ymin))}}}
function emptyMsg(box,msg){box.textContent='';box.classList.add('isEmpty');var d=document.createElement('div');d.className='empty';d.textContent=msg;box.appendChild(d)}
function hourLabel(h){var d=new Date(h+':00:00Z');return DAYS[d.getUTCDay()]+' '+p2(d.getUTCHours())+':00 UTC'}
function bars(box,rows,opts){ // rows: [{k,label,v}]
 if(!rows.length||rows.every(function(r){return !r.v})){emptyMsg(box,opts.empty);return}
 var sc=niceScale(Math.max.apply(null,rows.map(function(r){return r.v}).concat([opts.line||0]))),max=sc.max,f=frame(box,0,max,sc.ticks),n=48,slot=(f.W-f.L-f.R)/n,bw=Math.max(2,slot-2);
 var first=rows.length-n;
 rows.forEach(function(r,i){var x=f.L+(i-first)*slot+1,y=f.y(r.v),h=f.y(0)-y;if(x<f.L-0.5)return;
  var over=opts.line&&r.v>opts.line,rect=el('rect',{x:x,y:h>0?y:f.y(0)-1,width:bw,height:Math.max(1,h),rx:Math.min(2,bw/2),fill:over?'var(--warn)':'var(--accent)'});f.svg.appendChild(rect);
  var hit=el('rect',{x:x-1,y:f.T,width:slot,height:f.H-f.T-f.B,fill:'transparent'});
  hit.addEventListener('pointermove',function(e){rect.setAttribute('opacity','.75');showTip(e.clientX,e.clientY,fmt(r.v)+(opts.unit||''),r.label+(over?' · over the free pace':''))});
  hit.addEventListener('pointerdown',function(e){rect.setAttribute('opacity','.75');showTip(e.clientX,e.clientY,fmt(r.v)+(opts.unit||''),r.label+(over?' · over the free pace':''))});
  hit.addEventListener('pointerleave',function(e){if(e.pointerType!=='mouse')return;rect.removeAttribute('opacity');hideTip()});f.svg.appendChild(hit)});
 if(opts.line){var ly=f.y(opts.line);f.svg.appendChild(el('line',{x1:f.L,x2:f.W-f.R,y1:ly,y2:ly,stroke:'var(--warn)','stroke-width':1.5,'stroke-dasharray':'5 4'}))}
 [0,24].forEach(function(o){var i=first+o;if(i>=0&&rows[i]){var t=el('text',{x:f.L+o*slot,y:f.H-6});t.textContent=hourLabel(rows[i].k);f.ax.appendChild(t)}});
 var t=el('text',{x:f.W-f.R,y:f.H-6,'text-anchor':'end'});t.textContent='now';f.ax.appendChild(t)}
function line(box,rows,opts){
 if(rows.length<2){emptyMsg(box,opts.empty);return}
 var vs=rows.map(function(r){return r.v}),lo=Math.min.apply(null,vs),hi=Math.max.apply(null,vs),st=niceStep(Math.max(40,(hi-lo)*1.3)/4),y0=Math.floor(lo/st)*st,y1=Math.max(y0+st,Math.ceil(hi/st)*st);
 var f=frame(box,y0,y1,Math.round((y1-y0)/st)),n=rows.length,X=function(i){return f.L+(f.W-f.L-f.R)*(n==1?0:i/(n-1))};
 var d=rows.map(function(r,i){return (i?'L':'M')+X(i)+' '+f.y(r.v)}).join(' ');
 f.svg.appendChild(el('path',{d:d,fill:'none',stroke:'var(--accent)','stroke-width':2,'stroke-linejoin':'round','stroke-linecap':'round'}));
 var dot=el('circle',{r:5,fill:'var(--accent)',stroke:'var(--card)','stroke-width':2,opacity:0}),hair=el('line',{y1:f.T,y2:f.H-f.B,stroke:'var(--edge)','stroke-width':1,opacity:0});
 f.svg.appendChild(hair);f.svg.appendChild(dot);
 var last=el('circle',{cx:X(n-1),cy:f.y(rows[n-1].v),r:4,fill:'var(--accent)',stroke:'var(--card)','stroke-width':2});f.svg.appendChild(last);
 var hit=el('rect',{x:f.L,y:f.T,width:f.W-f.L-f.R,height:f.H-f.T-f.B,fill:'transparent'});f.svg.appendChild(hit);
 hit.addEventListener('pointermove',function(e){var b=f.svg.getBoundingClientRect(),px=(e.clientX-b.left)*(f.W/b.width),i=Math.round((px-f.L)/((f.W-f.L-f.R)/Math.max(1,n-1)));i=Math.max(0,Math.min(n-1,i));
  var x=X(i),y=f.y(rows[i].v);hair.setAttribute('x1',x);hair.setAttribute('x2',x);hair.setAttribute('opacity',1);dot.setAttribute('cx',x);dot.setAttribute('cy',y);dot.setAttribute('opacity',1);
  showTip(b.left+x*(b.width/f.W),b.top+y*(b.height/f.H),fmt(rows[i].v)+' avatars',rows[i].label)});
 hit.addEventListener('pointerdown',function(e){hit.dispatchEvent(new PointerEvent('pointermove',{clientX:e.clientX,clientY:e.clientY}))});
 hit.addEventListener('pointerleave',function(e){if(e.pointerType!=='mouse')return;hair.setAttribute('opacity',0);dot.setAttribute('opacity',0);hideTip()});
 [0,n-1].forEach(function(i){var t=el('text',{x:X(i),y:f.H-6,'text-anchor':i?'end':'start'});t.textContent=rows[i].label;f.ax.appendChild(t)})}
function hourRows(key){var map={};stats.hours.forEach(function(h){map[h.h]=h});var out=[],now=new Date();now.setUTCMinutes(0,0,0);
 for(var i=47;i>=0;i--){var d=new Date(now.getTime()-i*3600e3),k=d.toISOString().slice(0,13);out.push({k:k,label:hourLabel(k),v:map[k]?map[k][key]:0})}return out}
function draw(){var sy=scrollY;drawAll();if(scrollY!==sy)scrollTo(0,sy)}
function drawAll(){
 var started=stats.hours.length?' (since '+hourLabel(stats.hours[0].h)+')':'';
 bars(document.getElementById('cNew'),hourRows('a'),{empty:'Fills in as avatars are added'+started,unit:' new'});
 bars(document.getElementById('cRemoved'),hourRows('r'),{empty:'Fills in as avatars are removed'+started,unit:' removed'});
 var days=stats.days.slice(-30).filter(function(d){return d.e}).map(function(d){return {label:MON[+d.d.slice(5,7)-1]+' '+(+d.d.slice(8,10)),v:d.e}});
  line(document.getElementById('cGrowth'),days,{empty:'The line starts once there are two days of data (UTC)'})}
function today(){var d=new Date().toISOString().slice(0,10),t=stats.days.filter(function(x){return x.d===d})[0]||{a:0,r:0,c:0};
 var hk=new Date().toISOString().slice(0,13),hh=stats.hours.filter(function(x){return x.h===hk})[0]||{a:0,r:0};
 setN('addedToday',t.a);setN('removedToday',t.r);setN('contribToday',t.c||0);setN('addedHour',hh.a);setN('removedHour',hh.r);var net=t.a-t.r,e=document.getElementById('todayNet');e.textContent=(net>=0?'+':'−')+fmt(Math.abs(net));e.className=net>=0?'up':'dn'}
function snap(h){snapH=h;setN('entries',h.entries);setN('pendingBatches',h.pendingBatches);setN('reports',h.reports);setN('calmWaiting',h.calmWaiting);if(h.lastFlush)lastAt=Date.parse(h.lastFlush)}
function refresh(){fetch(H+'/health',{cache:'no-store'}).then(function(r){return r.json()}).then(snap).catch(function(){})}
function bucket(arr,b,key,cap){if(!b)return;var l=arr[arr.length-1];if(l&&l[key]===b[key])arr[arr.length-1]=b;else arr.push(b);while(arr.length>cap)arr.shift()}
function ago(ms){var s=Math.max(0,Math.round(ms/1000));return s<60?s+' s ago':Math.round(s/60)+' min ago'}
setInterval(function(){if(lastAt)document.getElementById('last').textContent=ago(Date.now()-lastAt);document.getElementById('next').textContent='in '+(60-new Date().getUTCSeconds())+' s';document.getElementById('clock').textContent=utcTime(Date.now(),true)},1000);
var feed=document.getElementById('feed'),empty=true;
function item(ev){var li=document.createElement('li'),h=document.createElement('div');h.className='h';var b=document.createElement('b'),tm=document.createElement('span');tm.className='tm';
 tm.textContent=utcTime(ev.at,true)+' UTC';
 if(ev.t==='run'){if(!ev.added&&!ev.removed)return null;
  li.className=ev.added?'add':'rem';
  if(ev.added){var ba=document.createElement('span');ba.className='tag add';ba.textContent='+'+fmt(ev.added)+' added';b.appendChild(ba)}
  if(ev.removed){var br=document.createElement('span');br.className='tag rem';br.textContent='−'+fmt(ev.removed)+' removed';br.title='No longer on VRChat';b.appendChild(br)}
  h.appendChild(b);h.appendChild(tm);li.appendChild(h);
  var m=document.createElement('div');m.className='m row';var nb=ev.batchCount!=null?ev.batchCount:(ev.batches||[]).length;
  if(ev.added&&nb>0){var src=document.createElement('span');src.textContent='from '+nb+' contribution batch'+(nb===1?'':'es');m.appendChild(src)}li.appendChild(m);
  var lists=[[ev.addedItems,ev.added,'add','Added'],[ev.removedItems,ev.removed,'rem','Removed']].filter(function(x){return x[0]&&x[0].length});
  if(lists.length){var box=null,more=document.createElement('button');more.className='more';more.textContent='Show names';m.appendChild(more);
   more.onclick=function(){if(!box){box=document.createElement('div');lists.forEach(function(x){var list=x[0],total=x[1],kind=x[2];
      var g=document.createElement('div');g.className='grp';var nm=document.createElement('div');nm.className='nm';g.appendChild(nm);
      var hd=document.createElement('span');hd.className='gl '+kind;hd.textContent=x[3];nm.appendChild(hd);
      list.forEach(function(it){var nmTxt=it.n||'(unnamed)',lnk=kind==='add'&&it.a;var s=document.createElement(lnk?'a':'span');s.className='badge '+kind;s.textContent=nmTxt;
       if(lnk){s.href='https://vrchat.com/home/avatar/'+it.a;s.target='_blank';s.rel='noopener';s.title=nmTxt+' (opens on VRChat)'}else s.title=nmTxt+(kind==='rem'?' (no longer on VRChat)':'');nm.appendChild(s)});
      if(total>list.length){var xx=document.createElement('span');xx.className='tm';xx.textContent='+'+fmt(total-list.length)+' more (list shows the first 300)';nm.appendChild(xx)}
      box.appendChild(g)});li.appendChild(box);more.textContent='Hide names'}
     else{box.hidden=!box.hidden;more.textContent=box.hidden?'Show names':'Hide names'}}}
  return li}
  return null}
function add(ev,live){
 if(ev.at>newest)newest=ev.at;
 if(live&&ev.t==='run'){lastAt=ev.at;if(ev.entries)setN('entries',ev.entries);setN('pendingBatches',ev.pending);setN('reports',ev.reports);setN('calmWaiting',ev.calmWaiting);
  if(ev.hour||ev.day){bucket(stats.hours,ev.hour,'h',48);bucket(stats.days,ev.day,'d',60);today();draw()}}
 var li=item(ev);if(!li)return;if(empty){feed.textContent='';empty=false}feed.insertBefore(li,feed.firstChild);while(feed.children.length>60)feed.removeChild(feed.lastChild)}
var tries=0,heard=0,newest=0,sock=null,retryT=0,checkT=0;
function setConn(on,t){document.getElementById('dot').className='dot '+(on?'on':'off');document.getElementById('conn').textContent=t}
function retry(){clearTimeout(retryT);if(document.hidden)return;var w=tries?Math.min(60000,3000*Math.pow(2,tries-1)):400;tries++;retryT=setTimeout(connect,w/2+Math.random()*w/2)}
function drop(ws){clearInterval(ws.p);ws.onclose=ws.onmessage=null;try{ws.close()}catch(e){}if(sock===ws)sock=null}
function connect(){clearTimeout(retryT);var ws=new WebSocket(H.replace(/^http/,'ws')+'/live/ws'),ping;sock=ws;
 ws.onopen=function(){tries=0;ws.send('hello');refresh();setConn(true,'Live');heard=Date.now();ping=ws.p=setInterval(function(){if(Date.now()-heard>80000)try{ws.send('ping');heard=Date.now()}catch(e){}},20000)};
 ws.onmessage=function(m){heard=Date.now();clearTimeout(checkT);var ev=JSON.parse(m.data);if(ev.t==='history'){var sy=scrollY;if(ev.hours){stats={hours:ev.hours,days:ev.days||[]};today();drawAll()}
  if(empty||!newest){feed.textContent='';empty=true;(ev.events||[]).forEach(function(e){add(e,false)})}else{var since=newest;(ev.events||[]).forEach(function(e){if(e.at>since)add(e,false)})}
  if(scrollY!==sy)scrollTo(0,sy)}else if(ev.t!=='pong')add(ev,true)};
 ws.onclose=function(){clearInterval(ping);if(sock!==ws)return;sock=null;if(!document.hidden)setConn(false,'Reconnecting');retry()}}
document.addEventListener('visibilitychange',function(){if(document.hidden)return;
 if(!sock||sock.readyState>1){if(sock)drop(sock);tries=0;connect();return}
 if(sock.readyState===1){try{sock.send('ping')}catch(e){}clearTimeout(checkT);var s0=sock;checkT=setTimeout(function(){if(sock===s0){drop(s0);tries=0;connect()}},3000)}});
var rz,lastW=innerWidth;addEventListener('resize',function(){if(innerWidth===lastW)return;lastW=innerWidth;clearTimeout(rz);rz=setTimeout(draw,150)});addEventListener('scroll',hideTip,{passive:true});addEventListener('pointerdown',function(e){if(!(e.target.closest&&e.target.closest('.chart')))hideTip()});
connect();
</script></body></html>
`;
