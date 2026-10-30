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
const SHARD_TTL = 21600; // 6h (was 5 min); purge-on-write keeps it fresh — for the rarely-changing CLONE shards only
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
  const id = o && o.id; if (!id || !String(id).startsWith("avtr_")) return;
  const fp = fragBucketFor(id);
  if (o.del || o.frag) into.add("f" + fp);
  if (o.avtr) into.add("a" + fp);
  for (const t of (o.add || [])) into.add("i" + indexBucketFor(t));
  for (const t of (o.rem || [])) into.add("i" + indexBucketFor(t));
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
  if (!ops.length) return [];
  // v40: a read that THROWS (vs an absent object) must never be treated as empty — writing back
  // "empty + this batch" would wipe the bucket's other entries. Skip that bucket and throw at the end,
  // so the caller requeues the whole batch (idempotent).
  let readFailed = false, firstErr = "";
  const fail = (what, e) => { readFailed = true; if (!firstErr) firstErr = what + ": " + String(e && e.message || e).slice(0, 100); };
  const fragWork = {};   // prefix -> { set:{id:summary}, del:Set(id) }
  const avtrWork = {};   // prefix -> { add:Set, rem:Set }
  const idxWork = {};    // indexBucket -> { token -> {add:Set, rem:Set} }
  for (const o of ops) {
    const id = o.id; if (!id || !String(id).startsWith("avtr_")) continue;
    const fp = fragBucketFor(id);
    const fw = (fragWork[fp] ||= { set: {}, del: new Set() });
    // Collect del + set INDEPENDENTLY (do NOT let one cancel the other here); the apply step below
    // resolves a same-id conflict as SET-wins, so a re-key (remove old fileId + add new fileId, SAME
    // avatarId, one flush) keeps the fragment instead of dropping it on op order.
    if (o.del) fw.del.add(id);
    if (o.frag) fw.set[id] = o.frag;
    if (o.avtr === "a") (avtrWork[fp] ||= { add: new Set(), rem: new Set() }).add.add(id);
    else if (o.avtr === "r") (avtrWork[fp] ||= { add: new Set(), rem: new Set() }).rem.add(id);
    // Token maps have NO prototype: a plain {} hands the token "constructor" Object's inherited
    // function, so `.add.add` threw, the whole drain failed and requeued, every flush (poison op).
    for (const t of (o.add || [])) ((idxWork[indexBucketFor(t)] ||= Object.create(null))[t] ||= { add: new Set(), rem: new Set() }).add.add(id);
    for (const t of (o.rem || [])) ((idxWork[indexBucketFor(t)] ||= Object.create(null))[t] ||= { add: new Set(), rem: new Set() }).rem.add(id);
  }
  const changed = [];
  const base = env.CATALOG_BASE ? env.CATALOG_BASE.replace(/\/$/, "") : null;
  const ttl = { httpMetadata: { contentType: "application/json", cacheControl: "public, max-age=" + INDEX_TTL } };
  // v41: buckets are independent objects, so they're processed INDEX_IO_CONCURRENCY at a time
  // instead of one by one (same subrequest count; a ~300-bucket drain took ~100 s of wall time
  // sequentially, which also held the chain lock and starved the cron's reconcile).
  // Fragments + avtr share the avatarId prefix.
  const fragAvtr = async (p) => {
    const fw = fragWork[p], aw = avtrWork[p];
    if (fw && (Object.keys(fw.set).length || fw.del.size)) await (async () => {
      let cur = { v: 1, e: {} }, rf = false;
      try { const o = await env.CATALOG.get(`fragments/${p}.json`); if (o) cur = await o.json(); } catch (e) { rf = true; fail(`get fragments/${p}`, e); }
      if (rf) return;
      if (!cur || typeof cur.e !== "object" || cur.e === null) cur = { v: 1, e: {} };
      // NO-OP GUARD: only rewrite when a summary actually CHANGES (re-adding an already-indexed
      // avatar whose summary is identical, or a delete of an absent id, must not rewrite the bucket).
      // This is the frag/avtr/index equivalent of the shard `dirty` flag — the biggest wasted Class A.
      let dirty = false;
      // DEL before SET so SET wins a same-id conflict: a re-key's remove(old fileId) + add(new fileId)
      // for the SAME avatarId both land here; deleting first then setting keeps the fragment. A del whose
      // id is also being set is skipped entirely (the avatar is alive under the new key).
      for (const id of fw.del) { if (!(id in fw.set) && id in cur.e) { delete cur.e[id]; dirty = true; } }
      for (const [id, summary] of Object.entries(fw.set)) {
        if (JSON.stringify(cur.e[id]) !== JSON.stringify(summary)) { cur.e[id] = summary; dirty = true; }
      }
      if (dirty) {
        try { await env.CATALOG.put(`fragments/${p}.json`, JSON.stringify({ v: 1, e: cur.e }), ttl);
          if (base) changed.push(base + `/fragments/${p}.json`); } catch (e) { fail(`put fragments/${p}`, e); }
      }
    })();
    if (aw && (aw.add.size || aw.rem.size)) await (async () => {
      let cur = { v: 1, ids: [] }, rf = false;
      try { const o = await env.CATALOG.get(`avtr/${p}.json`); if (o) cur = await o.json(); } catch (e) { rf = true; fail(`get avtr/${p}`, e); }
      if (rf) return;
      if (!cur || !Array.isArray(cur.ids)) cur = { v: 1, ids: [] };
      const s = new Set(cur.ids);
      // NO-OP GUARD: only dirty when a membership actually changes (add of an id already present /
      // remove of an absent id rewrote the bucket identically — the reconcile re-add + requeue waste).
      // REM before ADD so ADD wins a same-id conflict (see the index-token loop below for the full
      // re-key rationale): a re-keyed avatar stays in the avtr/ presence set.
      let dirty = false;
      for (const id of aw.rem) { if (s.has(id)) { s.delete(id); dirty = true; } }
      for (const id of aw.add) { if (!s.has(id)) { s.add(id); dirty = true; } }
      if (dirty) {
        try { await env.CATALOG.put(`avtr/${p}.json`, JSON.stringify({ v: 1, ids: Array.from(s) }), ttl);
          if (base) changed.push(base + `/avtr/${p}.json`); } catch (e) { fail(`put avtr/${p}`, e); }
      }
    })();
  };
  await runPool([...new Set([...Object.keys(fragWork), ...Object.keys(avtrWork)])], INDEX_IO_CONCURRENCY, fragAvtr);
  // Index token lists.
  await runPool(Object.entries(idxWork), INDEX_IO_CONCURRENCY, async ([b, toks]) => {
    let cur = { v: 1, t: {} }, rf = false;
    try { const o = await env.CATALOG.get(`index/${b}.json`); if (o) cur = await o.json(); } catch (e) { rf = true; fail(`get index/${b}`, e); }
    if (rf) return;
    if (!cur || typeof cur.t !== "object" || cur.t === null) cur = { v: 1, t: {} };
    // NO-OP GUARD per token: only rewrite the bucket when at least one token's posting list actually
    // changed (a re-emitted ADD for an already-listed id, common after reconcile/requeue, was
    // rewriting the whole bucket identically).
    let dirty = false;
    for (const [tok, ch] of Object.entries(toks)) {
      const s = new Set(Array.isArray(cur.t[tok]) ? cur.t[tok] : []);
      // REM before ADD so ADD wins a same-id conflict. On a RE-KEY (an avatar whose image changed:
      // remove under the OLD fileId + add under the NEW fileId, SAME avatarId, ONE flush) both a rem and
      // an add for this avatarId land in this token's posting list. The old add-then-rem order let the
      // stale rem delete the id the add just inserted, so the (still-alive) avatar VANISHED from search
      // until the next fold lap re-added it — then the next re-key stripped it again ("appeared then
      // disappeared"). Applying rem first then add keeps a re-keyed avatar searchable; a genuine delete
      // (rem only, no add) still removes it.
      let tdirty = false;
      for (const id of ch.rem) { if (s.has(id)) { s.delete(id); tdirty = true; } }
      for (const id of ch.add) { if (!s.has(id)) { s.add(id); tdirty = true; } }
      if (!tdirty) continue;
      let ids = Array.from(s);
      if (ids.length > INDEX_HOT_TOKEN_CAP) ids = ids.slice(0, INDEX_HOT_TOKEN_CAP);
      if (ids.length) cur.t[tok] = ids; else delete cur.t[tok];
      dirty = true;
    }
    if (dirty) {
      try { await env.CATALOG.put(`index/${b}.json`, JSON.stringify({ v: 1, t: cur.t }), ttl);
        if (base) changed.push(base + `/index/${b}.json`); } catch (e) { fail(`put index/${b}`, e); }
    }
  });
  // Any failed read or write → the caller requeues the whole batch (re-applying is idempotent).
  if (readFailed) throw new Error(firstErr || "index bucket read/write failed");
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
        const meta = JSON.parse((await env.AVATAR_KV.get("meta")) || "{}");
        meta.rc = 0; meta.rcDone = false; meta.reconcileScanned = 0; meta.reconcileFixed = 0;
        // v40: ?heal=1 also checks every entry's search presence (budgeted, ~a day); without it the
        // lap is the fast shard-only recount + fold catch-up (~70 min).
        meta.rcHeal = url.searchParams.get("heal") === "1";
        // Reset the recount accumulators + the clean-pass guards so the re-armed pass computes a
        // fresh EXACT count (adopted only if the whole 4096-shard lap reads cleanly).
        meta.rcEntries = 0; meta.rcUnfilled = 0; meta.rcReadFail = 0; meta.rcAttempts = 0; meta.rcAdoptSkipped = 0;
        await env.AVATAR_KV.put("meta", JSON.stringify(meta));
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

      // The "unconverted" registry: everything whose fancy name/author didn't fully fold to ASCII, as a
      // per-codepoint histogram (what to add to CONFUSABLES) + a capped avatar sample. Written by the
      // reconcile lap; this just serves the R2 file (edge-cached).
      if (req.method === "GET" && url.pathname === "/unconverted") {
        if (!env.CATALOG) return json({ ok: false, error: "R2 not configured" }, 503);
        try {
          const o = await env.CATALOG.get("unconverted.json");
          if (!o) return json({ ok: true, pending: true, note: "no lap has completed since the registry shipped" });
          return new Response(o.body, { headers: { "content-type": "application/json", "access-control-allow-origin": "*" } });
        } catch (e) { return json({ ok: false, error: String(e) }, 500); }
      }
      if (req.method === "GET" && (url.pathname === "/health" || url.pathname === "/")) {
        // ONE cheap KV read (no list ops — those have a tight free-tier limit and this is
        // polled every 15s). Counts come from meta (set at flush).
        const meta = JSON.parse((await env.AVATAR_KV.get("meta")) || "{}");
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
          foldVer: meta.foldVer || 0,   // fancy-Unicode fold re-index version (FOLD_VER when the one-time lap is done)
          foldLapV: meta.foldLapV || 0, // FOLD_VER the CURRENT fresh fold lap is dedicated to (== FOLD_VER while it runs)
          unconverted: (backupSt && typeof backupSt.unconverted === "number") ? backupSt.unconverted : (meta.unconverted || 0),   // distinct residual codepoints that don't fold to ASCII (GET /unconverted)
          lastDiag: meta.lastDiag || null,   // last flush's consumed/cleared/shards/failed/ok (cheap: rides the meta read)
          calmWaiting: meta.calmDepth || 0,   // v49 calm-lane batches waiting (bot refreshes + font flags)
          foldV: FOLD_STAMP_V,                // v49 font rules version the bots compare each avatar's fv to
          writesToday: meta.wr || null,       // v49 R2 writes today (UTC): sh = shards, ix = search files, ot = manifest/worklist
          writesYesterday: meta.wrPrev || null,
          version: 49,   // v49: calm lane for bot refreshes (one write per shard), no "last checked" writes (font checks only), fast-lane search purge, no automatic re-index (fold/recount ride the bots + daily backup), adaptive backup steps, write counters.    // v48: the count adopts each finished daily backup's exact count (a one-off +~7,000 miscount had stuck). v47: chain steps await the next step (waitUntil cut them off after ~30 s); depth caps 8. v46: fill-list pruner always runs + recounts unfilled each pass (phantom 1,176 vs 7 real). v45: daily db.json backup built in R2 (multipart, /admin/backupstep, own invocation). v44: record a throwing drain in lastDrain. v43: /health lastDrain report; requeue before deleting drained keys. v42: index bucket IO 6-way parallel (runPool); chain steps run reconcile/prune/rename when due (cronTasksIfDue) so a long drain can't starve them. v41: fold-rule fingerprint re-arms the fold catch-up lap on any CONFUSABLES change. v40: bucket-budgeted index drain (no silent loss past the subrequest ceiling), iqp: priority lane for fold ops, fold stamp (fz) instead of every-check re-asserts, legacy re-asserts dropped, index-backlog chaining, shard-only reconcile (120/run) + on-demand budgeted presence heal, new CONFUSABLES. v39: prototype-free index token maps (a "constructor" token was a poison op). v38: continuation chain confirmed working (backlog drained to 0); removed the temporary
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
      try { await maybeFireBackupStep(env, ctx); } catch (_) {}
      if (await chainBusy(env)) return;
      await cronTasks(env);
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
  const meta = JSON.parse((await env.AVATAR_KV.get("meta")) || "{}");
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
          entryCount: meta.entries, unfilled: meta.unfilled, staleCount: 0, foldV: FOLD_STAMP_V, searchReady: true, lastUpdate: new Date().toISOString() };
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
  await env.AVATAR_KV.put("meta", JSON.stringify(meta));
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
  const meta = JSON.parse((await env.AVATAR_KV.get("meta")) || "{}");
  let fill = Array.isArray(meta.fillHint) ? meta.fillHint : null;
  if (!fill || fill.length === 0) {
    if (meta.unfilled) { meta.unfilled = 0; await env.AVATAR_KV.put("meta", JSON.stringify(meta)); }
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
  await env.AVATAR_KV.put("meta", JSON.stringify(meta));
}

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

// ---- continuation-chain helpers (see the FLUSH_LOCK_TTL_MS block above) --------------------------
function selfBase(env) { return (env.WORKER_SELF_URL || DEFAULT_SELF_URL).replace(/\/$/, ""); }
async function chainBusy(env) {
  try {
    const v = await env.AVATAR_KV.get("flushlock");
    if (!v) return false;
    return (Date.now() - (parseInt(v, 10) || 0)) < FLUSH_LOCK_TTL_MS;
  } catch (_) { return false; }
}
async function chainLockPut(env) { try { await env.AVATAR_KV.put("flushlock", String(Date.now()), { expirationTtl: 120 }); } catch (_) {} }
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
async function backupStep(env) {
  const KV = env.AVATAR_KV;
  let st = JSON.parse((await KV.get("backup")) || "{}");
  const now = Date.now();
  if (st.uploadId && now - (st.startedAt || 0) > BACKUP_STALE_MS) {
    try { await env.CATALOG.resumeMultipartUpload("db.json", st.uploadId).abort(); } catch (_) {}
    st = { doneAt: st.doneAt, lastCount: st.lastCount, lastBytes: st.lastBytes, lastErr: "stale upload restarted" };
  }
  const enc = new TextEncoder();
  let carry;
  if (!st.uploadId) {
    if (st.doneAt && now - st.doneAt < BACKUP_EVERY_MS) return { skipped: "not due" };
    const up = await env.CATALOG.createMultipartUpload("db.json", {
      httpMetadata: { contentType: "application/json", cacheControl: "public, max-age=3600" },
    });
    // v49: uf/fill/hist/sample = the old monthly recount's jobs, done here (the backup reads every shard):
    // exact unfilled count, shards holding unfilled avatars (fill worklist), "unconverted" font registry.
    st = { ...st, uploadId: up.uploadId, next: 0, parts: [], count: 0, bytes: 0, startedAt: now,
      uf: 0, fill: [], hist: {}, sample: [] };
    carry = enc.encode('{"name":"VRC-A Avatar Store","version":1,"avatars":{\n');
  } else {
    const b = await KV.get("backup:buf", "arrayBuffer");
    carry = b ? new Uint8Array(b) : new Uint8Array(0);
  }
  const upload = env.CATALOG.resumeMultipartUpload("db.json", st.uploadId);
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
    try { const m = JSON.parse((await KV.get("meta")) || "{}"); done.addedAt = m.totalAdded || 0; done.removedAt = m.totalRemoved || 0; done.ufAt = m.ufSum || 0; } catch (_) {}
    // v49: publish the "unconverted" registry (was the recount's): every non-ASCII letter that survives
    // folding (histogram) + a capped sample. GET /unconverted. Extend CONFUSABLES from it; the bots then
    // flag and re-fold the affected avatars as they walk.
    try {
      let total = 0; for (const k in hist) total += hist[k];
      const top = Object.entries(hist).map(([cp, k]) => ({ cp: +cp, ch: String.fromCodePoint(+cp), n: k })).sort((a, b) => b.n - a.n);
      done.unconverted = top.length;
      await env.CATALOG.put("unconverted.json",
        JSON.stringify({ v: 1, ts: new Date().toISOString(), distinctCodepoints: top.length, totalHits: total, codepoints: top, sample }),
        { httpMetadata: { contentType: "application/json", cacheControl: "public, max-age=60" } });
      if (env.CATALOG_BASE) await purgeCatalogUrls(env, [env.CATALOG_BASE.replace(/\/$/, "") + "/unconverted.json"]);
    } catch (_) {}
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

async function cronTasksIfDue(env) {
  let last = 0;
  try { last = parseInt((await env.AVATAR_KV.get("crontasks")) || "0", 10) || 0; } catch (_) {}
  if (Date.now() - last >= CRON_TASKS_EVERY_MS) await cronTasks(env);
}
async function chainLockDel(env) { try { await env.AVATAR_KV.delete("flushlock"); } catch (_) {} }
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
  const prevMeta = JSON.parse((await env.AVATAR_KV.get("meta")) || "{}");
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
      try { await env.AVATAR_KV.put("meta", JSON.stringify({ ...prevMeta, calmDepth: admcNames.length })); } catch (_) {}
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
    await env.AVATAR_KV.put("meta", JSON.stringify({
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
    const sIndexOps = [];
    for (const fid of Object.keys(ops.adds)) if (!e[fid]) {
      const ne = ops.adds[fid]; e[fid] = ne; sAdded++; dirty = true; contentDirty = true;
      const op = buildIndexOp(null, ne, fid); if (op) sIndexOps.push(op);
      if (needsFold(ne)) ne.fz = foldSig(ne);   // the ADD already carries the folded tokens
      if (hasNonAscii(ne)) ne.fv = FOLD_STAMP_V; // indexed under the current fold rules (bots won't flag it)
      if (ne.filled !== true) sUnfilled++;
    }
    for (const fid of Object.keys(ops.upserts)) {
      const inc = ops.upserts[fid];
      const prev = e[fid];
      const ts = ops.upTs[fid] !== undefined ? ops.upTs[fid] : nowMs;
      // v49 safety: a calm refresh never re-creates an avatar removed while it waited, and no op older
      // than what's stored (its `mt`) may overwrite it.
      if (!prev && ops.calmOnly.has(fid)) continue;
      if (prev && typeof prev.mt === "number" && ts < prev.mt) continue;
      if (prev && typeof prev.added === "number") inc.added = prev.added; // `added` is immutable
      else if (!prev) sAdded++;
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
    for (const fid of Object.keys(ops.renames)) if (e[fid] && e[fid].name !== ops.renames[fid]) {
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
    for (const fid of ops.checked) if (e[fid]) {
      const ent = e[fid];
      if (needsFold(ent)) {
        const sig = foldSig(ent);
        if (ent.fz !== sig) {
          const op = foldOp(ent);
          if (op.add.length) sIndexOps.push(op);
          ent.fz = sig; dirty = true;
        }
      }
      if (hasNonAscii(ent) && ent.fv !== FOLD_STAMP_V) { ent.fv = FOLD_STAMP_V; dirty = true; }
    }
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
    } catch (_) { allShardsOk = false; failedShards.add(sp); }  // write failed → KEEP this shard's batches (per-key clear below)
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
        entryCount: entries, unfilled, staleCount: 0, foldV: FOLD_STAMP_V, searchReady: true, lastUpdate: new Date().toISOString() };
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

  try { await env.AVATAR_KV.put("meta", JSON.stringify({
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

  // Rolling log of the most recent USER contribution batches (newest first) with FULL names, in a
  // DEDICATED key so it never bloats meta/health. Only rewritten when batches were processed this
  // flush → +1 small KV write per flush at most, nothing when idle. Capped at 20 batches.
  if (recentBatches.length) try {
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
