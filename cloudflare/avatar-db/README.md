# VRC-A Avatar DB — Cloudflare Worker setup

This Worker is the single writer of the crowdsourced avatar catalog
`avatars/db.json` in the **VRC-A-Image-store** repo. The VRC-A apps only ever
READ that file (off the GitHub CDN) and POST new avatar mappings to this Worker.
No GitHub token ever ships in the app. **Nothing here touches Firestore.**

Everything below is done in the **Cloudflare dashboard in a browser** — no command
line, no installs.

---

## 1. Make a GitHub token (lets the Worker write the file)

1. GitHub → your avatar → **Settings** → **Developer settings** (bottom left) →
   **Personal access tokens** → **Fine-grained tokens** → **Generate new token**.
2. Name it `vrca-avatar-db`. Expiration: 1 year (or "no expiration").
3. **Resource owner:** the account that owns `VRC-A-Image-store`.
4. **Repository access:** *Only select repositories* → pick **VRC-A-Image-store**.
5. **Permissions** → **Repository permissions** → **Contents** → set to
   **Read and write**. (Leave everything else as No access.)
6. **Generate token** and COPY it. You'll paste it into Cloudflare in step 4 —
   you never send it to anyone.

## 2. Create the Worker

1. Go to <https://dash.cloudflare.com> → sign up / log in (free).
2. Left sidebar → **Workers & Pages** → **Create** → **Create Worker**.
3. Name it `vrca-avatar-db` → **Deploy** (deploys the default hello-world).
4. Click **Edit code**. Delete everything in the editor, then paste the entire
   contents of [`worker.js`](./worker.js). Click **Deploy**.

## 3. Add the KV storage (the pending queue)

1. Workers & Pages → **KV** → **Create a namespace** → name it
   `vrca-avatar-kv` → **Add**.
2. Back in the Worker: **Settings** → **Variables and Secrets** (or **Bindings**)
   → **KV Namespace Bindings** → **Add binding**.
   - **Variable name:** `AVATAR_KV`  (must be exactly this)
   - **KV namespace:** `vrca-avatar-kv`
   - **Save / Deploy.**

## 4. Add the variables + secret

In the Worker → **Settings** → **Variables and Secrets** → **Add**:

| Name        | Type                | Value                              |
|-------------|---------------------|------------------------------------|
| `GH_TOKEN`  | **Secret** (encrypt)| the GitHub token from step 1       |
| `GH_REPO`   | Text                | `Ashoska/VRC-A-Image-store`        |
| `DB_PATH`   | Text                | `avatars/db.json`                  |
| `GH_BRANCH` | Text (optional)     | `main`                             |

Save / Deploy after adding them.

## 5. Add the flush schedule (cron)

1. Worker → **Settings** → **Triggers** (or **Trigger Events**) → **Cron
   Triggers** → **Add Cron Trigger**.
2. Enter `*/10 * * * *` (every 10 minutes) → **Add**.

## 6. Get the URL and test

1. The Worker's URL is shown on its overview page, like
   `https://vrca-avatar-db.YOURNAME.workers.dev`.
2. Open `https://vrca-avatar-db.YOURNAME.workers.dev/health` in a browser. You
   should see JSON like:
   ```json
   { "ok": true, "entries": 0, "pendingBatches": 0, "reports": 0, "lastFlush": null }
   ```
   That means it's live. (`entries` is 0 until the first flush writes the file.)
3. **Send that base URL** (`https://vrca-avatar-db.YOURNAME.workers.dev`) to be
   baked into the app.

---

## 7. Cache Rule on the catalog domain (REQUIRED — load-bearing for cost)

**This is the single most important cost setting and NOTHING in the code can
enforce it — it lives only in the Cloudflare dashboard.** If the zone is ever
rebuilt or this rule is deleted, the catalog silently falls back to **0% edge
caching** and every clone/search read becomes a direct R2 Class B op again.

The apps + bots read the catalog off the R2 custom domain
`cdn.gremlininc.app` (see `CATALOG_BASE` in `wrangler.toml`), NOT through the
Worker. The Worker already stamps the right `cache-control` on every object
(shards/fragments/index/avtr `max-age=21600` = 6h; `_manifest.json` /
`_worklist.json` `max-age=30`) AND purges on write (`CF_PURGE_TOKEN` +
`CF_ZONE_ID`). But **Cloudflare does NOT cache `application/json` by default** —
without an explicit Cache Rule every response comes back `cf-cache-status:
DYNAMIC` (uncacheable), so reads scale linearly with user count.

The rule flips that to `MISS`/`HIT` so reads are absorbed at the edge and R2
reads decouple from user count (floor ≈ 4096 shards refreshing per TTL ≈
~0.5M reads/month, whether 20 or 20,000 users are reading).

**Create it:** dashboard → the `gremlininc.app` zone → **Caching → Cache Rules
→ Create rule**:

- **Rule name:** `catalog cache`
- **When incoming requests match** (Custom filter expression):
  `http.host eq "cdn.gremlininc.app"`
- **Then → Cache eligibility:** *Eligible for cache*
- **Then → Edge TTL:** *Use cache-control header if present, bypass cache if
  not* (= respect origin — honors the Worker's per-object `max-age`)
- Leave Browser TTL / Cache key / Vary / Serve-stale untouched. **Deploy.**

**Verify:** hit any shard twice and check the header — it must be `MISS` then
`HIT`, never `DYNAMIC`:
```
curl -sI https://cdn.gremlininc.app/shard/000.json | grep -i cf-cache-status
```
`DYNAMIC` = the rule is missing/broken (reads are NOT cached). `MISS`/`HIT` =
working. (Repeat hits can show `MISS` from a different edge PoP; that's fine —
it just means that PoP is cold, not that caching is off. `DYNAMIC` is the only
bad state.)

Freshness is safe with this on: the Worker purges a changed object within ~1s,
and the app re-checks each avatar is live against VRChat before cloning, so a
cached shard can never cause a wrong/dead clone.

---

## How it behaves

- Apps `POST /contribute` new avatar mappings → stashed in KV.
- Every 10 min the cron merges everything pending into `avatars/db.json` in ONE
  commit (you can watch the file grow on GitHub). The apps re-pull that file on
  open and every 30 minutes, so a newly-contributed avatar goes global within
  ~10-40 min.
- Apps `POST /report` a dead/renamed avatar → the file self-heals (rename applied
  immediately; a removal needs `REMOVE_QUORUM` = 2 independent reports).
- `GET /health` is cheap (KV only) and is what the admin panel polls live.

## First-file note

`avatars/db.json` is created automatically by the first flush that has something
to write. If you'd like it to exist immediately, add a file `avatars/db.json` in
the image-store repo with the contents `{"version":1,"avatars":{}}`.
