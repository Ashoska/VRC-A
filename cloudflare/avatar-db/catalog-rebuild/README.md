# Catalog rebuild Action (search index + master backup)

The Worker keeps the **lookup shards** fresh (the clone path). This Action keeps **search**
(the token index + fragments) and the **master `db.json`** fresh, by reading all the R2 lookup
shards and rebuilding — on a runner with real RAM, so there's no Worker memory/subrequest
limit. Everything is written back to **R2** (no GitHub commit, no extra token). It's never on a
user hot path.

## Where it lives
It's already in **this** repo (no file-copying):
- `.github/workflows/catalog-rebuild.yml` — the workflow (cron every 20 min + manual button)
- `.github/scripts/catalog-rebuild.mjs` — the rebuild script

## What it produces (to R2, from the R2 shards)
- `fragments/<3hex>.json` — avatarId → `{f,n,au,ai,p,pf}` summary
- `index/<3hex>.json` — token → `[avatarId,…]` (bucket = `token.hashCode() & 0xfff`)
- `db.json` — full master (the admin bots' whole-catalog source post-cutover) + backup
- `_manifest.json` — search-ready marker + rebuild time

The app (`AvatarGlobalDb`) computes the same bucket keys, so `searchSharded` reads these
directly, and the admin reads `${CATALOG_DOMAIN}/db.json`.

## Setup (one time)
1. **Create an R2 API token** — Cloudflare → R2 → *Manage R2 API Tokens* → *Create API Token*,
   **Object Read & Write** on the `vrca-avatar-catalog` bucket. Note the **Access Key ID**,
   **Secret Access Key**, and your **Account ID** (R2 overview page).
2. **Add three repo secrets** (this repo → Settings → Secrets and variables → Actions):
   `R2_ACCOUNT_ID`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`.
3. **Merge to the default branch (main).** Scheduled + manual runs only work once the workflow
   is on `main`. After merging, the Actions tab shows `catalog-rebuild` with a *Run workflow*
   button.
4. If your domain isn't `cdn.gremlininc.app`, edit `CATALOG_DOMAIN` in the workflow.

## Running it
- Runs automatically every 20 min once on `main`; trigger manually from the Actions tab.
- Search freshness = the cron cadence (20 min); clone freshness is separate (the shards,
  ~1–5 min via the Worker).
- **Enable it at/after cutover** — before `R2_WRITE="1"` the shards are the frozen migration
  snapshot, so a rebuild just reproduces that (harmless, and it does build the initial index so
  you can verify search serves). After cutover it reflects real changes.

## Safety
- Aborts (non-zero exit, no writes) if it reads 0 avatars, so a transient CDN failure can't
  wipe the index/master.
- Hot tokens capped at 800 postings so a bucket can't balloon.
- Idempotent — every run fully rebuilds from the shards, so renames/removals self-heal (no
  stale postings). Safe to re-run any time.

## Scale note
At millions, bump the shard read to 4-hex (matching a client `shardPrefix` change) and consider
sharding the index further; 7 GB runner RAM comfortably covers well into the millions first.
