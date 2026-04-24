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
- **VrchatAuthManager**: Singleton handling VRChat API auth (Basic auth + 2FA), cookie storage via EncryptedSharedPreferences, presence fetching, and friends list retrieval
- **VrchatPipelineService**: Foreground service with OkHttp WebSocket to `wss://pipeline.vrchat.cloud`. Handles real-time events (friend online/offline, unfriend, invites, group events), syncs VRChat presence to Firestore, and manages friends cache
- **Friends cache**: Persisted to Firestore (`users/{deviceHash}` fields: `savedFriendIds`, `savedFriendNames`) for cross-session unfriend detection. Includes half-list guard to prevent mass false notifications on API pagination errors or first install
- **VrchatPipelineState**: Shared in-memory singleton using `MutableStateFlow` for reactive cross-component state (connection status, presence data). Compose UI observes via `collectAsState()`.
- **DiscordRpcService**: Foreground service connecting to Discord Gateway WebSocket (`wss://gateway.discord.gg`). Sends VRChat Rich Presence (world name, player count, elapsed time) mimicking VRChat desktop's Discord integration. Auto-starts when VRChat pipeline connects if enabled. Uses external URLs for images: world thumbnail when in a world (unless DND/AskMe), VRChat logo (Discord CDN app icon) for all other states. Sends empty activities on disconnect to clear presence from profile.
- **VrchatAuthManager**: Also saves credentials to EncryptedSharedPreferences for auto-relogin when sessions expire.
- **DiscordLoginWebView**: WebView-based Discord login flow that extracts the user token from localStorage after login — no manual token paste needed.
- **IpField**: Multi-slot IP field component with 3 named slots (Home/Hotspot/Other). Uses local state tracking (not async DataStore) for immediate slot switching without cross-contamination. Supports per-slot editing and auto-migration from legacy single IP key.

### Firestore Schema (users/{deviceHash})
Key fields written by the app:
- `isOnlineInApp` (bool): Set to `true` by dedicated heartbeat write, `false` on ViewModel cleanup
- `lastSeenAt` (timestamp): Updated every ~8s by heartbeat (separate from data sync)
- `savedFriendIds` / `savedFriendNames` (string arrays): Friends cache for unfriend detection
- `afkEnabled`, `cycleEnabled`, `spotifyEnabled`, `timeEnabled`: Feature toggles (admin-editable)
- `warned`, `banned`, `warnReason`, `banReason`: Moderation flags (read by public app via snapshot listener)
- `targetedUpdateUrl` / `targetedUpdateNotes` (strings): Admin-pushed targeted APK update for specific user
- VRChat presence fields: `vrchatUserId`, `vrchatDisplayName`, `vrchatState`, `vrchatStatus`, `vrchatLocation`, etc.

**Firestore rules must include** `savedFriendIds`, `savedFriendNames`, `isOnlineInApp` in the `selfMutableKeys()` allowlist.

### Navigation
- Bottom nav: Home, Automations, Music, VRChat (4 items)
- Settings: Full page accessed via gear icon in top app bar. Contains Permissions, About, Help, and collapsible Debug section
- Admin: Full page accessed via gavel icon in top app bar (admin build only). Includes targeted APK push per user and release retraction.

### Firestore Sync Architecture
- **Event-driven sync**: Data changes trigger an immediate debounced sync (500ms) to Firestore
- **Heartbeat**: Separate lightweight write of `isOnlineInApp` + `lastSeenAt` every 8s, fires immediately on startup then repeats. Runs on BOTH admin and public builds so admin shows online in dashboard. Offline write uses `GlobalScope` to survive ViewModel teardown (only in `onTaskRemoved`, NOT `onDestroy` to avoid race with heartbeat).
- **Admin online detection**: Uses `lastSeenAt` within 30s + `isOnlineInApp` flag (auto-expires stale entries if offline write fails)
- **Admin reads**: Snapshot listeners provide real-time updates from Firestore (no polling)

### NowPlaying
- Ad/DJ detection restricted to Spotify only (`com.spotify.music`) to prevent false positives on regular songs
- Special window reduced to 10s (from 30s) for faster recovery after ads
- Motion-based play detection with YouTube-specific stall tracking
- Crystal progress bar (preset 3) uses filled diamonds (U+25C6) before the marker position

### Remote Config (Admin Edits)
The public app's moderation snapshot listener on `users/{deviceHash}` also picks up admin-editable fields (feature toggles, messages, intervals) and applies them in real-time via DataStore flow collectors. Uses `metadata.hasPendingWrites()` guard to skip processing own writes.

### Removed Features (do not re-add)
- **Divider system**: Previously allowed inserting text dividers between chatbox components. Fully removed from UI, ViewModel, DataStore, and message construction.
- **Preset naming**: Custom names for pinned/cycle presets. Removed from ViewModel, AdminScreen, DataStore, and Firestore sync. Presets now use generic "Preset 1/2/3..." labels.
- **Redundant action buttons**: Start/stop buttons in Cycle, pin/unpin + send once in Pinned, start/stop + test in Music — all removed since toggles handle activation.

### Toggle Persistence
All feature toggles (`afkEnabled`, `spotifyEnabled`, `cycleEnabled`, `timeEnabled`) are persisted to DataStore and restored on app restart. No cold-start reset — toggles survive process death.

## Coding Conventions
- Use Jetpack Compose for all new UI — no XML layouts
- Follow existing file/package structure when adding new screens or components
- Admin-only features must be gated behind `BuildConfig.IS_ADMIN_BUILD`
- Firestore security rules must be updated alongside any schema changes
- Keep admin APK and public APK builds fully separated

## Git & PR Conventions
- Branch naming: `fix/description`, `feature/description`, `chore/description`
- Commit messages: short, imperative ("Fix NowPlaying pause detection", "Add Releases tab filter")
- Always open a PR for non-trivial changes rather than pushing directly to main
- Include a brief description in the PR body of what changed and why

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
- Push directly to `main` for large feature additions — always use a PR
- Remove or weaken `bannedDevices` Firestore security rules
- Mix admin-only UI into the public build
- Break the existing GitHub Actions CI pipeline without replacing it with something better
- Leave this CLAUDE.md file out of date after making significant changes
