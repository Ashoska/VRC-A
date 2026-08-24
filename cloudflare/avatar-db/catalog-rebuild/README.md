# Catalog rebuild Action (search index + master backup)

The Worker keeps the **lookup shards** fresh (the clone path). This Action keeps **search**
(the token index + fragments) and the **master `db.json` backup** fresh, by reading all the
R2 lookup shards and rebuilding — on a runner with real RAM, so there's no Worker
memory/subrequest limit. It's the only thing GitHub does once cutover happens; it's never on
a user hot path.

## What it produces (from the R2 shards)
- `index/<3hex>.json` — token → `[avatarId,…]` (bucket = `token.hashCode() & 0xfff`) → R2
- `fragments/<3hex>.json` — avatarId → `{f,n,au,ai,p,pf}` summary → R2
- `avatars/db.json` — full master, one avatar per line → committed to the image-store repo

The app (`AvatarGlobalDb`) computes the exact same bucket keys, so its `searchSharded`
reads these directly.

## Setup (one time)
1. **Copy two files into the image-store repo** (`Ashoska/VRC-A-Image-store`):
   - `rebuild.mjs` → repo root
   - `catalog-rebuild.yml` → `.github/workflows/catalog-rebuild.yml`
2. **Create an R2 API token** (Cloudflare → R2 → *Manage R2 API Tokens* → *Create API Token*,
   Object Read & Write on the `vrca-avatar-catalog` bucket). It gives you an **Access Key ID**
   and **Secret Access Key**. Note your **Account ID** (R2 overview page).
3. **Add repo secrets** (image-store repo → Settings → Secrets and variables → Actions):
   - `R2_ACCOUNT_ID`
   - `R2_ACCESS_KEY_ID`
   - `R2_SECRET_ACCESS_KEY`
   (`GITHUB_TOKEN` is provided automatically; it commits the master to the same repo.)
4. If your domain isn't `cdn.gremlininc.app`, edit `CATALOG_DOMAIN` in the workflow.

## Running it
- Runs automatically every 20 min (the cron), and you can trigger it from the Actions tab
  (*Run workflow*). Search freshness = the cron cadence (20 min); clone freshness is separate
  (the shards, ~1–5 min via the Worker).
- **Enable it only at/after cutover** — before `R2_WRITE="1"` the shards are the frozen
  migration snapshot, so a rebuild would just reproduce that. After cutover the Worker keeps
  the shards live and this reflects real changes.

## Safety
- Aborts (non-zero exit, no writes) if it reads 0 avatars, so a transient CDN failure can't
  wipe the index or master.
- Hot tokens are capped at `HOT_TOKEN_CAP` (800) postings so a bucket can't balloon.
- Idempotent: every run fully rebuilds from the shards (renames/removals self-heal — no stale
  postings), so it's safe to re-run any time.

## Scale note
At millions of avatars, bump the shard read to 4-hex (matching a client `shardPrefix` change)
and consider sharding the index further; the runner RAM (7 GB) comfortably covers well into
the millions before that's needed.
