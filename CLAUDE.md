# VRC-A (Chatbox-VRC-A) — Claude Code Instructions

## Project Overview
Android app called VRC-A (VRChat Assistant), also referred to as Chatbox-VRC-A.
Built with Kotlin, Jetpack Compose, Firebase Firestore, and GitHub Actions CI.
There are two build variants: a public user-facing APK and a separate admin APK.
Display name embedded in UI strings: "Ashoska Mitsu Sisko".

## Repository Structure
- Main app source: `app/src/main/`
- Admin vs public build separation: `BuildConfig.IS_ADMIN_BUILD`
- CI workflow: `.github/workflows/android.yml`
- Firebase config: `google-services.json`
- Firestore rules: `firestore.rules` (uses `isOwner()` via `config/app.ownerUid`, NOT `sign_in_provider`)

## Build Commands
```bash
# Debug build (public)
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Admin build
./gradlew assembleAdminDebug

# Run tests
./gradlew test

# Run instrumented tests
./gradlew connectedAndroidTest

# Full build + test
./gradlew build
```

## Architecture & Stack
- Language: Kotlin
- UI: Jetpack Compose
- Backend: Firebase Firestore
- Auth/device management: Firestore `bannedDevices` collection
- NowPlaying: pause detection logic (check for edge cases when modifying)
- Admin UI: Users tab, Releases tab, tab bar navigation
- APK distribution: GitHub Releases

### VRChat Integration
- **VrchatAuthManager**: Singleton handling VRChat API auth (Basic auth + 2FA), cookie storage via EncryptedSharedPreferences, presence fetching, and friends list retrieval. Also saves credentials to EncryptedSharedPreferences for auto-relogin when sessions expire.
- **VrchatPipelineService**: Foreground service with OkHttp WebSocket to `wss://pipeline.vrchat.cloud`. Handles real-time events (friend online/offline/active/location, friend-update, unfriend, invites, group events) and manages friends cache. Writes VRChat presence to Firestore **only while an admin is watching** this user (gated by `AdminWatchState.isWatched`); when unwatched, in-app presence still updates locally via WebSocket events but no traffic hits Firestore. When watched, presence polls every 500ms. Also runs a 30s `lastSeenAt` heartbeat gated by `AdminBrowsingState.isBrowsing` (admin on Dashboard/Users tab). Connect/disconnect transitions fire connection-status notifications (gated by `notif_connection` pref).
- **Friends cache**: Persisted **locally** in SharedPreferences (`vrca_friends_cache` / `friends_json`) via `FriendsCacheStore`. No Firestore involvement — friends data is only used by the user's own app for unfriend notifications and is meaningless to admins. Old `savedFriendIds`/`savedFriendNames` fields in user docs are removed via `FieldValue.delete()` on every offline write so legacy data doesn't linger.
- **Unfriend notification logic**: Two sources cooperate via a `notifiedUnfriendIds` dedup set. (1) Real-time `friend-delete` from the WebSocket fires immediately for unfriends during an active session — but is **skipped if the userId isn't in the cache** (queued event for an offline unfriend that loadFriendsCache already removed). (2) Offline diff uses **two-fetch confirmation** to eliminate API pagination flaps: after the first API fetch on connect, removed IDs are captured into `candidateRemovals` but NOT notified. After the second fetch (60s later), only IDs still missing in BOTH fetches (`candidateRemovals ∩ stillMissing`) trigger notifications. The dedup set ensures at most one notification per userId per session. The notification text is neutral — "Friend removed" / "$name is no longer on your friends list" — since the app can't know who initiated the removal.

- **Notification system**: Six Android notification channels — `vrca_pipeline` (background service, MIN), `vrca_friends_activity` (online/offline/world/status/avatar), `vrca_friend_requests` (incoming requests + new friends), `vrca_friend_removals` (unfriends), `vrca_invites` (world + group invites), `vrca_groups` (announcements/queue/role/etc.), `vrca_connection` (pipeline up/down + sign-in alerts). Legacy `vrca_vrchat_events` is deleted on upgrade. Notifications use `setGroup()` keys (`vrca_group_friends`, `vrca_group_social`, `vrca_group_invites`, `vrca_group_groups`) so multiple events stack in the shade with summary notifications. Per-friend cooldowns (60s) on chatty events (location, status, avatar). All toggles live in `VrchatNotificationPrefs` keys + `UserPreferencesRepository` flows. The UI lives in `LegacyPagesAndSettingsPage.kt`'s `NotificationToggleSection`, organized into Friend list / Friends activity / Invites / Groups / App and connection sections. `friendsCache` is now `Map<String, FriendCacheEntry>` (was `Map<String, String>`) carrying display name, status, location, world name, avatar thumb, bio — used to detect change events from `friend-update` and fire targeted notifications (renamed, changed status, changed avatar, updated bio).
- **VrchatPipelineState**: Shared in-memory singleton using `MutableStateFlow` for reactive cross-component state (connection status, presence data). Compose UI observes via `collectAsState()`.
- **DiscordRpcService**: Foreground service connecting to Discord Gateway WebSocket (`wss://gateway.discord.gg`). Sends VRChat Rich Presence (world name, player count, elapsed time) mimicking VRChat desktop's Discord integration. Auto-starts when VRChat pipeline connects if enabled. Default image is a hosted VRChat logo (`https://raw.githubusercontent.com/shadowash321rulse-lab/VRChat-rpc-display/main/vrchat-1102x620.jpg`) resolved through Discord's external-assets endpoint. World thumbnails are routed through `images.weserv.nl` with `fit=fill` to squash them into a square so they don't clip in Discord's activity-card frame; the default logo is left unproxied since it's already correctly sized. **Updates are event-driven**: a `collectLatest` on `VrchatPipelineState.presenceFlow` fires an Op-3 within ~1.5s of any VRChat presence change, with a 1.5s minimum debounce between sends. A 10s backup timer also fires Op-3 for keep-alive. Image resolution is now atomic — `resolveOrTimeout(url, 1500ms)` awaits the cache miss up to 1.5s before sending, so the activity card never displays the wrong image. On disconnect, sends an empty-activities op-3 payload then `Thread.sleep(1500)` before closing the WebSocket so the clear flushes before Android kills the process.
- **DiscordExternalAssetResolver**: Posts URLs to `https://discord.com/api/v10/applications/{app_id}/external-assets` to mint stable `mp:external/...` references that work in `activity.assets.large_image`. Bounded in-memory `LruCache` (64 entries), 60s negative-cache TTL for failed URLs, mutex-guarded `inFlight` set to dedup concurrent resolutions. `resolveOrTimeout(url, timeoutMs, fallback)` wraps `withTimeoutOrNull` for atomic-update cases. The default-image reference is also persisted to DataStore (`discord_default_image_ref`) and pre-populated into the resolver cache on service start, so the proper logo renders immediately on every launch after the first resolve.
- **DiscordLoginWebView**: WebView-based Discord login flow that extracts the user token from localStorage after login — no manual token paste needed.
- **IpField**: Multi-slot IP field component with 3 named slots (Home/Hotspot/Other). Uses local state tracking (not async DataStore) for immediate slot switching without cross-contamination. Supports per-slot editing and auto-migration from legacy single IP key.

### Firestore Schema (users/{deviceHash})
Key fields written by the app:
- `isOnlineInApp` (bool): Set to `true` by the app-open write, `false` by `VrchatPipelineService.onTaskRemoved` and `ChatboxViewModel.onCleared` (GlobalScope fallback)
- `lastSeenAt` (timestamp): Refreshed on app-open, app-close, content edits, and live-mode writes (when watched)
- `afkEnabled`, `cycleEnabled`, `spotifyEnabled`, `timeEnabled`: Feature toggles (admin-editable)
- `warned`, `banned`, `warnReason`, `banReason`: Moderation flags (read by public app via snapshot listener)
- `targetedUpdateUrl` / `targetedUpdateNotes` (strings): Admin-pushed targeted APK update for specific user
- `watcherActiveAt` (timestamp): **Admin-only write.** Refreshed every ~30s while an admin has this user's detail page open. User app reads it from snapshots and feeds into `AdminWatchState.updateFromTimestampMs` — if within 60s, live-mode loops start.
- VRChat presence fields (`vrchatUserId`, `vrchatDisplayName`, `vrchatState`, `vrchatLocation`, etc.): Only written while watched.
- NowPlaying / preview fields (`nowPlayingTitle`, `nowPlayingArtist`, `combinedPreviewText`, `activePackage`): Now included in **every** self-sync write (debounced) so admins can see the user's current chatbox output without needing to actively watch them. Live-mode writes a higher-frequency version every 500ms.
- `lastReportedTime`, `cycleTrimWarning`, `lastTimeUpdateAt`: Only written while watched (live-mode loop).

**Friends are no longer in Firestore** — `savedFriendIds`/`savedFriendNames` are deleted via `FieldValue.delete()` on offline write. They live in local SharedPreferences only.

### Navigation
- Bottom nav: Home, Automations, Music, VRChat (4 items)
- Settings: Full page accessed via gear icon in top app bar. Contains Permissions, About, Help, and collapsible Debug section
- Admin: Full page accessed via gavel icon in top app bar (admin build only). Includes targeted APK push per user and release retraction.

### Firestore Sync Architecture
The sync model is intentionally minimal — Firestore costs money and we only push to it when we have to. Three classes of writes:

1. **App-open write (one)**: After `initialDataLoaded` flips, `applyRemoteContentBeforeSync()` reads the Firestore doc to pick up any admin edits made while the user was offline (messages, presets, intervals — not toggles, which always start OFF), then `performSelfSync` runs once with the merged state. This is the only write tied to startup.
2. **App-close write (one)**: `VrchatPipelineService.onTaskRemoved` writes `isOnlineInApp=false, lastSeenAt`. `ChatboxViewModel.onCleared` does the same via `GlobalScope` as a fallback for cases where the foreground service isn't running.
3. **Event-driven content writes (debounced 500ms)**: Whenever the user changes a toggle, types in a message, edits a preset, etc., a debounced trigger writes the current content snapshot. No safety net periodic loop — if a write fails, the next user edit picks it up.

**Browse-gated heartbeat (NEW)**: When an admin is on the Dashboard or Users tab in the admin panel, the admin app writes `config/adminPresence.browsingAt = serverTimestamp()` every 30s. Public user apps subscribe to that single doc via a snapshot listener inside `VrchatPipelineService`. If `browsingAt` is fresh (within 75s — `AdminBrowsingState.FRESHNESS_WINDOW_MS`), `AdminBrowsingState.isBrowsing` flips true and the service runs a 30s `lastSeenAt` heartbeat on the user's own doc. When admin closes both tabs, the doc goes stale and heartbeats stop — zero idle Firestore traffic. Force-killed users can't heartbeat, so their `lastSeenAt` immediately starts going stale, which the admin's 75s staleness filter detects (see Admin online detection below).

**Live-mode (watcher-gated)**: When an admin opens a specific user's detail page in the admin panel, the admin app refreshes `watcherActiveAt` every ~30s on that user's doc. The user app's snapshot listener feeds the timestamp into `AdminWatchState`; if fresh (within 60s), `isWatched` flips to true and two side-effects start:
- `ChatboxViewModel.startLiveSyncWatcher` writes `buildLivePayload` (nowPlaying, preview, `lastReportedTime`, `lastSeenAt`) every 500ms
- `VrchatPipelineService.startPresenceRefreshLoop` writes VRChat presence every 500ms

Both stop instantly when `isWatched` flips back to false (`collectLatest` cancels the inner loop). When nobody is watching, neither loop touches Firestore.

**Force-kill detection**: If the user's app is force-killed, neither `onTaskRemoved` nor `onCleared` fires, so `isOnlineInApp` stays `true` in Firestore. The browse-gated 30s heartbeat (above) keeps `lastSeenAt` fresh while the user app is alive AND an admin is browsing; if the user app is dead, the heartbeat doesn't fire and `lastSeenAt` goes stale within ~75s. The admin's online check combines both signals: `isOnlineInApp == true && lastSeenAt within 75s` — see Admin online detection below.

**Admin watcher heartbeat**: When an admin selects a user in the admin panel's detail view, a `LaunchedEffect` writes `watcherActiveAt = serverTimestamp()` to that user's doc every 30 seconds. When the admin navigates away or selects a different user, the coroutine is cancelled automatically. The user app reads `watcherActiveAt` from its snapshot listener and feeds it into `AdminWatchState.updateFromTimestampMs()` — if within 60s, live-mode starts.

**Echo suppression** (per-field `lastSyncedValues` map): Every field (toggle or content) compares its incoming snapshot value against `lastSyncedValues` — what we last knew was on the Firestore doc. If `remote == lastSyncedValues`, the snapshot is an echo (from self-sync, watcher heartbeat, or live-mode write) and we skip. If `remote != lastSyncedValues`, an admin actually changed it and we apply. Comparing against current local state would break this: during admin watch, the heartbeat fires snapshots with the OLD field values (Firestore hasn't received the user's pending local toggle yet), so a `remote != local` test would revert whatever the user just toggled. Content fields fall back to current local state when no baseline exists yet. Both branches update `lastSyncedValues` immediately on every snapshot (whether applied or skipped), so subsequent self-sync echoes are correctly suppressed. The map is also bulk-updated after each successful `performSelfSync` write via `captureStateForSync()`. The first snapshot seeds the baseline via `seedLastSyncedFromSnapshot`.

**Admin polling (admin-side only)**: Dashboard stats poll every 30s via `get(Source.SERVER)`. User directory polls every 30s. Selected user detail polls every 500ms. All reads use `Source.SERVER` to bypass Firestore cache. The `watcherActiveAt` heartbeat writes every 30s while a user is selected. No periodic writes from the user app — only the admin reads.

**Initial-data gate** (`initialDataLoaded`): `performSelfSync` returns early until a loader coroutine has read every user-content DataStore field into ViewModel state. Prevents cold-start sync from writing empty defaults.

**First-snapshot skip** (`initialSnapshotProcessed`): The very first Firestore snapshot after listener attach is dropped — DataStore is the source of truth on cold start.

**Offline editing**: User-content edits land in DataStore immediately and survive process death. On reconnect, the next debounced write or app-close write pushes them to Firestore. Admin-content edits are preserved across user restarts via `applyRemoteContentBeforeSync()` (read-before-write on app-open). If both the user and admin edited the same field while the user was offline, the Firestore version (admin's) wins because it's applied before the app-open write.

**Admin online detection**: A user counts as online when `isOnlineInApp == true && (now - lastSeenAt) < 75_000ms` — encapsulated in `isUserOnline(u, nowMs)` in `AdminScreen.kt`. The Dashboard counter wraps this in a `derivedStateOf(users, nowMs)` block with `nowMs` ticking every 5s, so users flip to offline without needing a fresh Firestore poll. The Users-tab row badge uses the same helper. The shared user-list state is stored as `mutableStateOf<List<UserRow>>` (whole-value assignment) rather than `SnapshotStateList` so downstream consumers reliably recompose on update.

### NowPlaying
- Ad/DJ detection restricted to Spotify only (`com.spotify.music`) to prevent false positives on regular songs
- Special window reduced to 10s (from 30s) for faster recovery after ads
- Motion-based play detection with YouTube-specific stall tracking
- Crystal progress bar (preset 3) uses filled diamonds (U+25C6) before the marker position

### Remote Config (Admin Edits)
The public app's moderation snapshot listener on `users/{deviceHash}` also picks up admin-editable fields (feature toggles, messages, intervals, presets) and applies them in real-time. Each incoming snapshot is filtered by:
1. `metadata.hasPendingWrites()` — skip if it reflects our own pending local write
2. `initialSnapshotProcessed` — drop the very first snapshot (DataStore wins cold start)
3. Fingerprint match against `lastSelfSyncFingerprint` — skip our own confirmed echo

After those filters, the snapshot's content fields (afkMessage, cycleLines, presets) are written to DataStore so existing flow collectors propagate them into ViewModel state. Toggles are set directly on the ViewModel (they don't persist — see below).

### Removed Features (do not re-add)
- **Divider system**: Previously allowed inserting text dividers between chatbox components. Fully removed from UI, ViewModel, DataStore, and message construction.
- **Preset naming**: Custom names for pinned/cycle presets. Removed from ViewModel, AdminScreen, DataStore, and Firestore sync. Presets now use generic "Preset 1/2/3..." labels.
- **Redundant action buttons**: Start/stop buttons in Cycle, pin/unpin + send once in Pinned, start/stop + test in Music — all removed since toggles handle activation.

### Toggle Persistence
Feature toggles (`afkEnabled`, `spotifyEnabled`, `cycleEnabled`, `timeEnabled`) **do not** persist across app restarts — they always start OFF on app open. The DataStore keys, collectors, and `saveX` calls were intentionally removed from the toggle setters. Content (messages, intervals, presets) DOES persist via DataStore as normal. Admin can still flip toggles in real-time via the Firestore snapshot listener, which writes directly to ViewModel state without touching DataStore.

## Coding Conventions
- Use Jetpack Compose for all new UI — no XML layouts
- Follow existing file/package structure when adding new screens or components
- Admin-only features must be gated behind `BuildConfig.IS_ADMIN_BUILD`
- Firestore security rules must be updated alongside any schema changes
- Keep admin APK and public APK builds fully separated

## Git & PR Conventions
- **CRITICAL — Default branch for ALL work is `VRC-A-Official`.** Every commit and push must land on `VRC-A-Official`. Do NOT push to `main`, `claude/*`, or any other auto-generated branch. If you find yourself on a different branch (including the `claude/...` branch the harness defaults to), checkout `VRC-A-Official` first, cherry-pick or rebase your work onto it, and push there. Only deviate if the user explicitly names a different target branch in this turn.
- Commit messages: short, imperative ("Fix NowPlaying pause detection", "Add Releases tab filter")
- Include a brief description in the PR body of what changed and why
- Do NOT open pull requests automatically — the user merges `VRC-A-Official` themselves on their own schedule

## Autonomous Permissions
Claude is fully authorised to do all of the following without asking for confirmation:

- Read, create, edit, and delete any files in the repository
- Run any build, test, or lint commands
- Commit changes with descriptive commit messages
- Push branches to the remote repository
- Open pull requests with descriptions of what was changed
- Merge pull requests if all checks pass and the task is self-contained
- Create and push new releases to GitHub Releases
- Update Firestore security rules when schema changes require it
- Install dependencies or update `build.gradle` files as needed
- Fix any build errors, lint warnings, or test failures encountered along the way
- Refactor code for clarity or performance without being asked
- Add or update comments and documentation inline
- **Update this CLAUDE.md file** whenever new features, architecture changes, or conventions are added

## Keeping CLAUDE.md Up To Date
Claude must treat this file as a living document and update it as part of normal development:

- **When adding a new feature or screen:** Add a brief entry under Architecture & Stack describing what it does and any key implementation notes
- **When adding a new dependency or library:** Note it under Architecture & Stack with its purpose
- **When changing build commands or adding new Gradle tasks:** Update the Build Commands section
- **When changing Firestore schema or collections:** Update the relevant notes under Architecture & Stack
- **When establishing a new pattern or convention:** Add it to Coding Conventions so it applies to future work
- **When completing a significant PR:** Review this file and add anything that would help future sessions understand the codebase better

Claude should update CLAUDE.md in the same commit as the feature it describes, not as a separate task. The goal is that any new Claude Code session can read this file and immediately understand the full current state of the project without needing to explore the codebase from scratch.

## What Claude Should Always Do
- Run `./gradlew build` after making changes to verify nothing is broken before pushing
- Check that both admin and public build variants still compile after any change
- Never hardcode secrets, API keys, or credentials — use environment variables or Firebase config
- Keep `BuildConfig.IS_ADMIN_BUILD` gates intact on all admin-only features
- If a Firestore rule change is needed, update both the rules file and note it in the PR
- Update this CLAUDE.md file whenever the architecture, conventions, or features change

## What Claude Should Never Do
- Push to `main`, `claude/*`, or any branch other than `VRC-A-Official` (always use `VRC-A-Official` unless the user explicitly names a different target this turn)
- Remove or weaken `bannedDevices` Firestore security rules
- Mix admin-only UI into the public build
- Break the existing GitHub Actions CI pipeline without replacing it with something better
- Leave this CLAUDE.md file out of date after making significant changes
