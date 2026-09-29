# VRC-A — Claude Code Instructions

VRC-A (VRChat Assistant) is an Android companion app for VRChat: Kotlin, Jetpack Compose, Firebase Firestore, GitHub Actions CI.
Three builds: **public** (`com.scrapw.chatbox`, kept for install continuity), **admin** (`BuildConfig.IS_ADMIN_BUILD`) and **headset** / Meta Quest (`com.gremlin.inc.headset`, `BuildConfig.IS_HEADSET_BUILD`).
Source: `app/src/main/kotlin/com/vrca/` with packages `admin/ app/ data/ discord/ discordbot/ keepalive/ nowplaying/ osc/ overlay/ richcontent/ sync/ ui/ update/ vrchat/`. Worker: `cloudflare/avatar-db/`. CI: `.github/workflows/android.yml`. Rules: `firestore.rules`.
Display name embedded in UI strings: "Ashoska Mitsu Sisko".

## How this file works (read first)
This file is only the rules plus a **map**. The details of every feature and system live in `docs/systems/*.md`.
- **Before working on a system, read its doc(s) from the map below.** A task often touches more than one (e.g. anything that writes Firestore also needs `firestore-sync.md`). If unsure which, grep `docs/systems/` for the class or feature name.
- The docs hold hard-won "don't do this again" notes (why a `key()` wrapper exists, why a cap was removed, …). Check them before changing behaviour they describe.

### Keeping the docs current (same commit as the code, always)
- **Changed a system** → update its doc so it describes what the code does now.
- **Added a system/feature** → add it to the fitting doc, or create a new `docs/systems/<name>.md` and add a map row.
- **Removed a system** → delete its text (or the whole doc and its map row).
- **A doc grows too big** (over ~60 KB) or mixes unrelated things → split it and update the map.
- **Changed a rule, build command or convention** → update this file.
- Keep every map row's "covers" column specific (class names, feature words), because it is how the right doc gets found.
- Keep doc lines under ~1,500 characters (long single lines get cut off when read).
- There is only one `CLAUDE.md`. Never add another one anywhere else.

## Map
| Doc (`docs/systems/`) | Covers |
|---|---|
| `chatbox.md` | OSC Start/Stop gate (`oscSending`), feature toggles + OS-kill auto-restore (`FeatureSessionStore`), Pinned/Cycle content, sub-lines (`SubLineCodec`), cycle lines/shuffle/tokens `{time}{song}…`, preset auto-save, Manual Send (Instant/Live/Scroll), cycle sender, Invisible Chatbox Border, rate limit, "Pinned" naming, removed features |
| `automations-editor.md` | `AutomationsPage` drag-and-drop: reorder, promote/demote, Pinned↔Cycle moves, drag ghost overlay, hover-to-arm |
| `app-tabs.md` | Bottom nav, Home (preview card, Quick Toggles, Connection card, SetupHealthCard), Automations page layout, VRChat tab header, Settings page, `IpField` slots |
| `ui-kit.md` | `PublicUiKit` (CompactSectionCard, TogglePill…), `VrcaDialogs` (card dialogs, timezone picker), `TimeZones` |
| `onboarding-startup.md` | First-open tutorial (`OnboardingFlow`), `TutorialImageStore`, ToS gate, boot screen (`BootstrapScreen`, warm resume), `sanitizeBackendRefs` |
| `now-playing.md` | Media detection (Spotify, YouTube, YT Music, Quest browser), ad/live detection, pause detection, `TitleCleaner`, chatbox width calibration, progress bars, Media tab UI |
| `headset.md` | Quest flavor, monitor-shape framing, 3-column Home, OSCQuery/OSC-in tokens (`VrcaOscQuery`, `{mute}` etc.), headset OSC send target |
| `roster.md` | Headset instance roster: VRChat log reader (`VrcLogParser`, `InstanceRosterManager`), log-derived presence, member rows (platform/trust/status badges), friend button, roster to admin |
| `avatar-catalog.md` | Roster clone button + `resolveWornAvatarId` (log name+author resolve now primary: VRChat removed the worn image from `/users`), crowdsourced catalog (`AvatarGlobalDb`), contributions, avatar search (`AvatarSearch`), avatar size tool, increments 1-9 |
| `avatar-catalog-worker.md` | Cloudflare Worker by version (flush, reconcile, fancy-Unicode fold, search index, `iq:` queue), fill worklist, liveness shard-walk bots, author renames, contribution dedup |
| `vrchat-connection.md` | `VrchatAuthManager` (login, 2FA cookie roll-forward, relogin, REST helpers, instance counts), `VrchatPipelineService` (WebSocket, presence), sign-out + auth-dead OSC gates, login screen |
| `friends.md` | Friends cache, live bio/name/rank change detection, `pipelineDispatcher`, friends refresh loop, unfriend/friend-add/friend-request dedup, activity suppression |
| `notifications.md` | Notification channels + toggles, offline backfill, group posts/calendar, baselines + install-time cutoff, `seenNotifIds` (uncapped), tap actions, app-update/announcement/status-page notifications, persistent notification icon |
| `vrchat-tab-alerts.md` | In-app alert cards, rich event/announcement cards, Signed up + Pinned sections, series/Repeats dialog, `GroupAlertEnricher`, `AlertImageStore`, `InstanceListDialog` + 24h instance history |
| `discord-rpc.md` | `DiscordRpcService` WebView gateway, JS shim/OP 3 injection, gateway health + recovery, offline parking, RPC timer, Discord login WebView, WebView keep-alive + cache cap |
| `cardinal.md` | Admin Discord AI bot (`com.vrca.discordbot`) v1-v10: memory, learner, prompts, admin Bot tab. Measurements: `docs/cardinal-audit.md` |
| `firestore-sync.md` | `users/{deviceHash}` schema, liveness (`lastActiveAt`/`offlineAt`), cold-open/hourly/debounced writes + cost rules, watcher live mode, echo suppression, admin offline edits, cross-device sync, online detection, remote config, device hash, rules |
| `moderation-accounts.md` | Bans + ban-evasion (`VrchatBanChecker`), kill switch, single-session account lock, admin remote logout, move-login handoff, account-wide moderation |
| `admin.md` | Admin UI kit, Dashboard, directory + detail (read model, polls, remote Start/Stop, invite-me/self-invite), `AdminRuntime`, public Discord button, foreground-scoped listener rule |
| `releases.md` | GitHub release upload, directed/global/headset releases, forced updates + OSC block, update dialog + browser fallback |
| `rich-content.md` | `RichDoc` engine, renderer, `RichMediaStore`, admin `RichDocEditor` + uploads, announcements surface, What's New, video/GIF playback |
| `background-survival.md` | App-scoped ViewModel, headless revival, swipe vs OEM kill (`AppShutdown`), watchdog, safe foreground start, OEM guidance, wakelock + WiFi lock |
| `storage.md` | Every cache/on-disk store and its cap or cleanup |
| `labs.md` | UI Lab (`tools/ui-lab/`) and Cardinal Lab (`tools/cardinal-lab/`) |

Other reference docs in `docs/`: `ui-revamp.md` (public UI + onboarding design spec; read before public UI work), `cardinal-audit.md`, `account-system-plan.md`, `avatar-catalog-sharding-plan.md`, `backend-migration-plan.md`, `vrc-nexus-teardown.md`.

## Build Commands
```bash
./gradlew assembleDebug              # public debug
./gradlew assembleRelease            # release
./gradlew assembleAdminDebug         # admin
./gradlew assembleHeadsetAppDebug    # headset (Meta Quest)
./gradlew test                       # unit tests (labs excluded)
./gradlew connectedAndroidTest       # instrumented tests
./gradlew build                      # full build + test

# UI Lab: the real UI on the JVM, driven + screenshotted (docs/systems/labs.md)
tools/ui-lab/ui.sh shot admin                          # phone 411x891dp → ui-shots/admin-home.png
tools/ui-lab/ui.sh run headset "preset roster; shot r" # Quest panel 1024x640dp
tools/ui-lab/ui.sh serve headset & tools/ui-lab/uictl.sh "tap Settings" "shot s"   # live

# Cardinal Lab (admin Discord bot harness; JVM only, never in an APK)
tools/cardinal-lab/lab.sh run tools/cardinal-lab/scripts/hangout.txt
```
- **Web sessions:** the container starts without the Android SDK; the committed SessionStart hook (`.claude/hooks/session-start.sh`) installs it to `$HOME/android-sdk` and writes `local.properties`. `.gitignore` tracks only `.claude/settings.json` + `.claude/hooks/`.
- **Always compile public, admin and headset before pushing** (`./gradlew compilePublicAppDebugKotlin compileAdminAppDebugKotlin compileHeadsetAppDebugKotlin`). This catches API/signature nits (e.g. `HorizontalDivider` vs `Divider`) that would otherwise only fail in CI.
- **No permanent unit tests (user preference).** For pure logic, write a throwaway test under `app/src/test`, run `./gradlew testPublicAppDebugUnitTest`, then delete it before committing. UI and the VRChat/Discord/OSC/Firestore integrations can only be verified on the user's device (or visually in the UI Lab). The two labs are deliberate, gated exceptions; don't delete them.
- Use the UI Lab to check layout/fit after UI changes before asking the user to build.

## Coding Conventions
- Jetpack Compose for all new UI, no XML layouts; follow the existing package structure.
- Admin-only features behind `BuildConfig.IS_ADMIN_BUILD`; never mix admin UI into the public build.
- Admin UI (especially the Discord Bot tab) carries NO explanatory paragraphs or helper captions: labels, values and controls only.
- Firestore costs money: every new read, listener or write needs a reason (see `firestore-sync.md`). Update `firestore.rules` alongside any schema change and note it in the PR.
- Never hardcode secrets, API keys or credentials.
- Never show backend names ("Firebase"/"Firestore") in public-build UI text.
- No em dashes in onboarding/login UI copy.

## Git & PR Conventions
- **CRITICAL: push ALL work to `claude/vrc-a-android-app-NUpaN`.** Commit on the local `VRC-A-Official` working branch, then push to the remote `claude/vrc-a-android-app-NUpaN` branch (e.g. `git push -u origin VRC-A-Official:claude/vrc-a-android-app-NUpaN`). **Do NOT push to `VRC-A-Official` directly: the remote consistently returns HTTP 503 on that branch.** Do NOT push to `main` or any other branch. Only deviate if the user explicitly names a different target branch in this turn.
- Commit messages: short, imperative ("Fix NowPlaying pause detection").
- Include a brief description in the PR body of what changed and why.
- Do NOT open pull requests automatically; the user merges the work themselves.

## Autonomous Permissions
Claude may do all of the following without asking: read/create/edit/delete repository files; run build, test and lint commands; commit and push; open PRs with descriptions (when asked) and merge self-contained PRs whose checks pass; create GitHub releases; update Firestore rules when the schema needs it; add dependencies / edit `build.gradle`; fix build errors, lint warnings and test failures; refactor for clarity or performance; add or update comments and docs; update this file and `docs/systems/`.

## Never
- Push to `main`, `VRC-A-Official` (it 503s), or any branch other than `claude/vrc-a-android-app-NUpaN` unless the user names one this turn.
- Remove or weaken the `bannedDevices` Firestore rules.
- Mix admin-only UI into the public build.
- Break the GitHub Actions CI pipeline without replacing it with something better.
- Leave `docs/systems/` or this file out of date after changing a system.
