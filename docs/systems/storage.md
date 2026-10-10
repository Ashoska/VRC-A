# Storage caps + cleanup

_Scope: Every on-disk cache and its cap or cleanup rule._

### Storage Management

- **Firestore offline cache**: explicitly capped at 25 MB in `VrcaApplication.onCreate()` via `FirebaseFirestoreSettings.Builder().setCacheSizeBytes(25MB)` (default was 100 MB — far more than needed for the few collections the public build reads).

- **Discord WebView footprint**: `cacheDir/WebView` (HTTP cache + Code Cache) capped at 64 MB, first check 3 min after start, then every 30 min. Over-cap clear: `clearCache(true)` + direct sweep of `cacheDir/WebView`. **`dataDir/app_webview` (profile data incl. `localStorage.token`) is NEVER cleared** — wiping it kills the Discord session.

- **Coil image cache** (`VrchatImageLoader`): DISK cache capped at 10 MB (`cacheDir/image_cache`) — Coil's default is 2% of free disk space. **MEMORY cache capped at 12 MB** — Coil's default in-memory bitmap cache is **25% of app RAM**, and full-resolution event banners + world/instance thumbnails accumulating there (on top of the foreground services + Discord WebView) was a real **OEM low-memory KILL trigger** (users reported more frequent background kills after the event UI shipped); the hard 12 MB cap evicts old bitmaps instead of ballooning RAM.

- **Rich-content media (`RichMediaStore`, `filesDir/rich_media/{ann,upd}`)**: lives in DATA (not cache). `ann/` (announcement media) is culled live by `VrcaScreen`'s `gcAnnouncements` when an announcement is removed/swapped. **`upd/` (patch-note media) is wiped on every cold start** (`VrcaApplication.onCreate`) — it's view-scoped (re-downloads when the Update/What's New dialog shows), and a FORCED dialog killed mid-show never runs its `onDispose` `clearUpdateMedia`, so its cached video/images would otherwise linger forever. Culling is PURELY REFERENCE-BASED with NO size cap / LRU eviction (the `enforceTotalCap` backstop was removed): `RichMediaStore.reconcile(scope, referencedUrls)` deletes exactly the files whose URL isn't in the active set and nothing else, so ACTIVE media is NEVER evicted. This is REQUIRED because **video is download-first / local-only** (see below) — there's no URL-streaming fallback for video, so evicting an active clip would break it. `gcAnnouncements(urls, confirmed)` reconciles `ann/`;
  **`confirmed` MUST be true** (VrcaScreen passes `announcementsLoaded`, set once the listener delivers a snapshot) so the cull NEVER runs on the initial empty list (which would wipe all cached media before the list loads). Storage = exactly the media of your active announcements/updates, cached FULLY however big; removed/swapped content is freed on the next reconcile. **Video is DOWNLOAD-FIRST (`RichVideo`)**: the clip plays ONLY from the fully-downloaded local file (`Uri.fromFile`), NEVER streamed from the URL — the play button is replaced by a spinner + "Downloading video…" until `localFile` is set, then becomes tappable (a failed download shows a Retry). Streaming a not-yet-complete/remote clip caused playback issues, so the URL fallback was removed for video (images/gifs keep `cachedFile ?: url` — a URL load is a harmless loading state for them, and reference-culling keeps them local anyway). **`richImageLoader` (rich-content Coil loader) has its OWN capped disk cache** (`cacheDir/rich_image_cache`, 15 MB) + 12 MB memory cache — without it Coil defaults to 2% of free disk (a silent cache grower); separate dir from `VrchatImageLoader`'s `image_cache` to avoid Coil's shared-dir conflict.

- **Stale update APKs**: `VrcaApplication.cleanStaleUpdateApks()` deletes any `.apk` files from `getExternalFilesDir(DIRECTORY_DOWNLOADS)` on every app start. The update download (`vrc-a-update.apk`) was never cleaned up after the system installer ran — a ~20 MB file sitting forever until the next update.

- **Friends cache** (`vrca_friends_cache`): unbounded JSON blob keyed by userId, grows with friend count (~150-300 bytes/friend). No cap but bounded by VRChat's friend limit.

- **In-app alerts** (`vrca_in_app_alerts`): capped at 20 groups × 50 events (FIFO).

- **Seen notification IDs** (`vrca_seen_notifs`): capped at 500 (FIFO).

- **Crash log** (`vrca_crash`): capped at 80,000 chars, overwritten on next crash.
- **Voice packs** (`filesDir/stt/<packId>/`, headset only): offline speech models, 0.6 MB (voice detector) to ~670 MB per pack, downloaded on demand from the Manual Send language picker, verified by SHA-256. No cap — removed per pack from Settings → App (the voice detector goes with the last pack). `SpeechPacks.cleanupLegacy` deletes the old Vosk model dirs (`filesDir/vosk-model*`) and any `stt/<id>` pack the catalog no longer lists (e.g. GigaAM v2, replaced by v3). See `voice-to-text.md`.
