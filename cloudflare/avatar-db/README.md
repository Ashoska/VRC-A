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
2. Enter `*/15 * * * *` (every 15 minutes) → **Add**.

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

## How it behaves

- Apps `POST /contribute` new avatar mappings → stashed in KV.
- Every 15 min the cron merges everything pending into `avatars/db.json` in ONE
  commit (you can watch the file grow on GitHub).
- Apps `POST /report` a dead/renamed avatar → the file self-heals (rename applied
  immediately; a removal needs `REMOVE_QUORUM` = 2 independent reports).
- `GET /health` is cheap (KV only) and is what the admin panel polls live.

## First-file note

`avatars/db.json` is created automatically by the first flush that has something
to write. If you'd like it to exist immediately, add a file `avatars/db.json` in
the image-store repo with the contents `{"version":1,"avatars":{}}`.
