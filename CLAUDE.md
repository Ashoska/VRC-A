# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

## Build Commands

```bash
# Public build (user-facing)
./gradlew :app:assemblePublicAppDebug
./gradlew :app:assemblePublicAppRelease

# Admin build (owner-only)
./gradlew :app:assembleAdminAppDebug
./gradlew :app:assembleAdminAppRelease

# All variants at once
./gradlew :app:assemble

# Clean
./gradlew clean
```

APK output paths:
- `app/build/outputs/apk/publicApp/debug/*.apk`
- `app/build/outputs/apk/publicApp/release/*.apk`
- `app/build/outputs/apk/adminApp/debug/*.apk`
- `app/build/outputs/apk/adminApp/release/*.apk`

## Tests

```bash
# All unit tests
./gradlew :app:test

# Single variant
./gradlew :app:testPublicAppDebugUnitTest
./gradlew :app:testAdminAppDebugUnitTest

# Single test class
./gradlew :app:testPublicAppDebugUnitTest --tests "com.scrapw.chatbox.ExampleUnitTest"
```

## Local Signing Setup

Copy `keystore.properties.TEMPLATE` → `keystore.properties` (never commit this file) and fill in your keystore path and passwords. Without it, release builds are unsigned but still compile. CI generates this file from GitHub Secrets (`KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, `RELEASE_PAT`, `RELEASE_OWNER`, `RELEASE_REPO`).

---

## Architecture Overview

### Dual-Build System

One codebase, two APK outputs controlled by a Gradle product flavor dimension `"app"`:

| Flavor | `applicationId` | `IS_ADMIN_BUILD` | Writes to Firestore users/ |
|--------|-----------------|-----------------|---------------------------|
| `publicApp` | `com.scrapw.chatbox` | `false` | Yes |
| `adminApp` | `com.scrapw.chatbox.admin` | `true` | No (avoids auth UID collision) |

Each flavor has its own `google-services.json` in `app/src/adminApp/` and `app/src/publicApp/` (separate Firebase projects). **Every admin-only feature must be gated on `BuildConfig.IS_ADMIN_BUILD`.**

### Boot Sequence (ChatboxApp.kt)

`MainActivity` → `ChatboxApp()` executes a strict sequential gate system. If any gate fails, it shows a blocking screen and waits:

1. **Crash gate** – reads `vrca_crash` SharedPrefs; shows last crash log if present
2. **Bootstrap gate** – Firebase anonymous sign-in + writes `users/{deviceHash}` and `usersById/{uid}` (public build only)
3. **Phase 1 ban check** – queries `bannedIdentifiers/{deviceHash}` and `bannedIdentifiers/{authUid}` before VRChat login
4. **ToS gate** – checks local `vrca_tos` SharedPrefs (version 1 baseline)
5. **VRChat login gate** – shows `VrchatLoginScreen` if not authenticated; on success runs Phase 2 ban check
6. **Phase 2 ban check** – queries `bannedIdentifiers/{vrchatUserId}`; appends new identifiers to ban records for evasion detection
7. **Update check** – public build only; reads Firestore `releases/latest` via `checkFirestoreRelease()` in `InAppUpdate.kt`
8. **Main app** – `ChatboxScreen(ChatboxViewModel)`

### Device Identity

Device hash = `SHA-256("v2:<ANDROID_ID>:<SIGNING_CERT_SHA256>")`. Stable across reinstall on the same device + signing key. Falls back to a stored random UUID if `ANDROID_ID` is blank. Stored in `vrca_remote` SharedPrefs under key `device_id_hash`. Logic is duplicated in both `MainActivity.kt` and `ChatboxApp.kt` — keep them in sync.

### Firestore Collections

| Collection | Key | Purpose |
|---|---|---|
| `users/{deviceHash}` | deviceHash | Canonical public user doc; all selfMutableKeys |
| `usersById/{uid}` | authUid | uid → deviceHash mapping |
| `bannedIdentifiers/{id}` | deviceHash / authUid / vrchatId | Flat ban index `{banId, type, active}` |
| `bannedRecords/{banId}` | banId | Full ban record with all linked identifiers + evasionAttempts[] |
| `bannedDevices/{deviceHash}` | deviceHash | **Legacy** — kept for migration only, no new writes |
| `announcements/{id}` | autoId | Public-readable announcements |
| `releases/latest` | fixed | Update manifest for public build |
| `config/app` | fixed | `ownerUid`, `tosVersion`, `tosText`, `tosUrl` |
| `moderationEvents/{id}` | autoId | Ban evasion audit trail |
| `actions/{id}` | autoId | Admin audit trail |

### OSC / Chatbox Output

`ChatboxOSC.kt` sends UDP OSC packets to VRChat:
- `/chatbox/input` – message text
- `/chatbox/typing` – typing indicator

Target IP/port is user-configured (up to 3 named IP slots in Firestore). `ChatboxViewModel` drives all OSC sends and enforces a ban block — if the user doc has `banned=true`, all OSC sends are silenced.

### Now Playing

`NowPlayingListenerService` (a `NotificationListenerService`) attaches `MediaController.Callback` listeners to active media sessions from an allowlist of packages (Spotify, YouTube Music, Apple Music, etc.). It pushes `NowPlayingSnapshot` updates to `NowPlayingState` (a singleton `StateFlow`).

`NowPlayingState.update()` performs motion-based pause inference: it compares consecutive position timestamps to decide whether playback is actually moving. Special handling for YouTube stall detection and Spotify DJ/Ad segments. `ChatboxViewModel` observes this StateFlow and only sends OSC when `isPlaying == true`.

### VRChat Pipeline Service

`VrchatPipelineService` is a persistent foreground service that maintains a WebSocket to `wss://pipeline.vrchat.cloud`. It handles VRChat notifications (friend requests, invites, online/offline) and syncs user presence fields (world, status, avatar, etc.) to `users/{deviceHash}` in Firestore. Reconnects with exponential backoff. Started via `ContextCompat.startForegroundService` after Phase 2 ban check passes. Restarts on device boot via `BootReceiver`.

### Admin Features

`AdminScreen.kt` / `AdminViewModel.kt` are visible only when `IS_ADMIN_BUILD == true`. The ViewModel loads data from Firestore with a one-shot fetch (no snapshot listeners on the user list). Admin actions (ban, warn, clear, announcements, ToS config) write back to Firestore and refresh the local state. The admin signs in with email/password (`signInEmailPassword`) to obtain elevated Firestore rules access — the shared anonymous auth UID alone is not the owner UID.

### Update System

Public build: `checkFirestoreRelease()` in `InAppUpdate.kt` reads `releases/latest` from Firestore. The admin manually writes this document to trigger updates. Version comparison is by `versionCode` (integer). `requiredMinCode` forces the update dialog to be non-dismissible. Download uses Android `DownloadManager` → APK install intent.

The legacy `CheckUpdate.kt` (GitHub Releases API) is separate from the Firestore-based system and is wired up in `ChatboxViewModel` for the admin build's own update check path.

### Version Numbering

Computed entirely from `GITHUB_RUN_NUMBER` in `app/build.gradle`:
- `versionCode` = run number (local fallback: 279)
- `versionName` = `v1.(2 + run÷100).(run%100)` relative to `baseRunOffset=200`
No manual version bumps needed.

### CI

`.github/workflows/android.yml` builds all four variants (`publicAppDebug`, `publicAppRelease`, `adminAppDebug`, `adminAppRelease`) on every push to `main` and uploads them as artifacts. Release publishing to GitHub Releases is not automated — must be done manually or added to the workflow.

---

## Key Invariants to Preserve

- Admin build must never write to `users/` or `usersById/` (would collide with public build's anon UID for the same device).
- All OSC sends in `ChatboxViewModel` must check the ban state before executing.
- `NowPlayingState.isPlaying` — not `PlaybackState.STATE_PLAYING` alone — is the authoritative signal for whether to send chatbox music updates.
- The `bannedIdentifiers` + `bannedRecords` pair is the active ban system. The `bannedDevices` collection is legacy-only; do not add new ban writes there.
- Firestore `config/app.ownerUid` is the single source of truth for all `isOwner()` rule checks. The admin email/password auth must resolve to that same UID.
