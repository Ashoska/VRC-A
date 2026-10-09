# VRC-NEXUS teardown v2 + VRC-A gap analysis & plan

A complete, from-scratch reverse-engineering of the competitor APK
(`com.deize.vrcnexus.quest`, "Nexus VRC", **version 1.49.105 / code 454**),
followed by a gap analysis of what VRC-A is missing, what is worth borrowing,
and what helps **RAM / request-endpoint efficiency** — then a concrete plan.

> **This document fully supersedes the earlier 0.4.8/code-53 teardown.** NEXUS
> was rewritten from the ground up between the two versions: the old build was a
> Capacitor/Vue hybrid with a few dumb native plugins; this one is a **native
> Kotlin app** (Gradle 8.9, Kotlin 2.0.21, Java 17) whose UI is still web but is
> now driven by a large hand-written native bridge, with a real cloud backend,
> WebRTC, offline speech, and Meta-Store monetization. Treat nothing in the old
> doc as current except the VRChat log grammar (unchanged) and the OSC wire
> format (unchanged).

This is a facts/techniques document (architecture, endpoints, permissions,
log/OSC formats, caching/TTL/RAM techniques). **No third-party source is copied
into the repo** — the APK was decompiled to a scratch dir and analysed; we learn
the *technique* and write our own code. Ethics/risk notes are called out in §9.

---

## 1. What Nexus VRC is now (architecture)

A **native Kotlin Android app that hosts a web UI in a WebView** and exposes a
single fat native bridge object to it. Concretely:

- **`MainActivity`** builds a `FrameLayout` root, puts a main `WebView` in it
  that loads the **bundled** web app `file:///android_asset/index.html` (a single
  ~1.4 MB HTML/CSS/JS file — the entire product UI, "Nexus VRC"), and calls
  `addJavascriptInterface(new VrcBridge(...), ...)`.
- **`VrcBridge`** (~2700 lines) is the one bridge: ~110 `@JavascriptInterface`
  methods the web UI calls to do everything a WebView can't — authenticated
  VRChat API calls, OSC send/receive, log reading, speech, device telemetry,
  Meta billing, and launching the services below. The web UI owns product logic
  and *which* endpoints to hit; the native side is a thin, authenticated
  do-er. (This is the inverse of VRC-A, which is native Compose end-to-end.)
- **Native "child window" panels**: Movies, Discord, and Spotify each run in
  their **own Activity** (`MovieActivity`/`DiscordActivity`/`SpotifyActivity`,
  each with its own `taskAffinity` so Horizon OS gives it a **separate Quest
  panel**). In-place, the JS can also **mount/position/unmount** extra native
  WebViews and a WatchParty `SurfaceView` as children of the main FrameLayout
  (`mountMovies/mountDiscord/mountSpotify/partyPosition` take x/y/w/h), i.e. the
  web UI lays out native surfaces by pixel rect over itself and hides them with
  `setOverlaysHidden`.
- **A real cloud backend**: `https://api.vrc-nexus.online` (crash upload, Last.fm
  + Discord-presence relays, avatar-DB proxy fallback), plus a **LiveKit**
  deployment at `livekit.vrc-nexus.online` (TURN/ICE for the watch party). The
  old Cloudflare Worker (`vrc-nexus-community-proxy.deizeljkite.workers.dev`)
  survives only as a fallback host.
- **Runs ON the Quest** (Quest-side APK) — so OSC, OSCQuery, and VRChat logs are
  all local (`127.0.0.1` / on-device files). This is still the single most
  important scoping fact: NEXUS's "free" local-device tricks are free only
  because it shares a device with VRChat.

Quality is much higher than the 0.4.8 build but still single-author; lots of
copy-pasted try/catch, but the ideas and the breadth are serious.

---

## 2. Build facts & manifest

- `package` **`com.deize.vrcnexus.quest`**, internal code package **`com.nexus.vrc`**,
  label "Nexus VRC". `versionName` **1.49.105** / `versionCode` **454**.
- `minSdk` **24**, `targetSdk` **34**. Kotlin 2.0.21 / Gradle 8.9 / Java 17.
- `usesCleartextTraffic="true"` + an `@xml/network_security_config` (for plain
  `http://` OSCQuery on the LAN). `allowBackup=true`, fullBackup + dataExtraction
  rules. `extractNativeLibs=true`.
- **Meta/Oculus Platform AppID** meta-data `1114160378456702` (native entitlement
  + billing).
- **Permissions**: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`,
  **`CHANGE_WIFI_MULTICAST_STATE`** (OSCQuery mDNS + multicast lock),
  **`MANAGE_EXTERNAL_STORAGE`** (read VRChat logs), `READ_EXTERNAL_STORAGE`
  (maxSdk 32), `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` +
  `FOREGROUND_SERVICE_MICROPHONE` + `FOREGROUND_SERVICE_MEDIA_PROJECTION` +
  `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `RECORD_AUDIO`, `CAMERA` (optional
  feature), `WAKE_LOCK`, `POST_NOTIFICATIONS`,
  **`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`** (new vs 0.4.8 — they now ask for the
  battery exemption). Note it does NOT request `PACKAGE_USAGE_STATS` in the
  manifest (the foreground-app feature checks for it and sends the user to
  Settings; see §4.14).
- **Activities** (each a separate Quest panel via `taskAffinity`): `MainActivity`
  (launcher; default 980×800 dp panel), `MovieActivity` (900×560), `DiscordActivity`
  (1000×640), `SpotifyActivity` (1000×640).
- **Services (6)** — notable that each feature owns a **purpose-typed** FGS
  rather than one mega keep-alive:
  | Service | FGS type | Purpose |
  |---|---|---|
  | `NexusNotificationListener` | (notification listener) | MediaSession/now-playing source |
  | `ChatboxService` | `specialUse` | the OSC chatbox compositor + sender (survives backgrounding) |
  | `ScriptService` | `specialUse` | avatar-OSC automation/macros |
  | `SpeechService` | `microphone` | offline speech-to-text capture |
  | `MediaProjectionService` | `microphone\|mediaProjection` | screen + internal-audio capture for the watch party |
  | `MusicPlayerService` | `mediaPlayback` | in-app music player |

---

## 3. Libraries (native + Java)

**Native `.so` (arm64-v8a only):**
- `libovrplatformloader.so` + `libpsdk_jni.so` — **Meta/Oculus Platform SDK**
  (entitlement + in-app purchases).
- `libjingle_peerconnection_so.so` — **WebRTC** (the watch party).
- `libvosk.so`, `libonnxruntime.so`, `libsherpa-onnx-{jni,cxx-api,c-api}.so` —
  **offline speech** (Vosk + sherpa-onnx/ONNX Runtime).
- `libjnidispatch.so` — **JNA**. `libc++_shared.so` — C++ runtime.

**Java libraries (from package dirs):** `okhttp3`; **`org.schabi.newpipe`
(NewPipeExtractor)** for YouTube/stream resolution; **`org.webrtc`**;
**`com.k2fsa.sherpa.onnx`** + `org.vosk`; **`org.mozilla.javascript` (Rhino)** —
NewPipe needs it to run YouTube's `base.js` for signature/throttling deciphering;
**`org.jsoup`** (HTML parsing, NewPipe + scraping); **`com.meta`** (Platform SDK);
`google.protobuf`. The bundled **Vencord** mod (`assets/vencord.user.{js,css}` +
`vencord-themes.js`) is injected into the Discord panel, not a Java lib.

---

## 4. Subsystem teardown

### 4.1 `VrcBridge` — the native capability surface
One class, ~110 `@JavascriptInterface` methods, a `newCachedThreadPool` executor,
and the session store `SharedPreferences("nexus_vrc_session")` (plain, **not**
encrypted — VRC-A's EncryptedSharedPreferences is better). Groups:

- **Session/API**: `login` (Basic auth — URI-encodes user:pass then base64),
  `webLogin` (web cookie harvest, §4.2), `verifyTwoFactor` (`POST
  auth/twofactorauth/{totp|otp|emailotp}/verify`), `logout` (`PUT logout` + wipe),
  `hasSession`, and the key one: **`apiRequest(method, path, body)`** — a generic
  authenticated passthrough. `open()` prepends `https://api.vrchat.cloud/api/1/`
  when `path` isn't absolute, attaches `Cookie` + the stored `User-Agent`
  (`"NexusVRC/1.0.0 nexus.vrc.app@gmail.com"`), and `captureCookies()` **rolls any
  `Set-Cookie` forward** on every response (browser-style session keep-alive).
  So the JS makes *all* VRChat calls through this one method; the endpoint list
  lives in the web app, not native.
- **Allow-listed extra fetches**: `netGet`/`netGet(bearer)`/`netPostForm` only
  permit a fixed host set (`open-meteo`, `nominatim.openstreetmap.org`,
  `lrclib.net`, `translate.googleapis.com`, `accounts.spotify.com`,
  `api.spotify.com`) — a clean SSRF/abuse guard for a JS-driven bridge.
- **OSC**: `oscSend` (one-shot UDP), `startBgChatbox`/`stopBgChatbox`/
  `updateBgChatbox`/`bgChatboxStatus` (drive ChatboxService), `startBgScript`/
  `stopBgScript` (ScriptService), `oscListenStart/Stop/Status` (bind UDP 9001),
  `discoverParams`/`oscParamsSnapshot` (OSCQuery), `pushPresenceContext` (hand the
  VRChat session token + name/id + world name to ChatboxService so it keeps
  sending **while the web UI is gone/backgrounded**).
- **Native panels**: `moviesMount/Position/Unmount/…`, `discordMount/…`,
  `spotifyMount/…`, `partyStartHost/Join/Position/Leave/SetMuted/ForceMute/Kick/
  Ban/Unban`, `setOverlaysHidden`, `openBrowser`, `padSetEnabled` (gamepad).
- **Music**: `resolveMusic`/`prefetchMusic`/`warmMusic` + `musicPlayQueue` and
  `musicPause/Resume/Next/Prev/Seek/SetVolume/Stop/State`.
- **Speech / mic**: `speechStatus`, `speechDownloadModel` (progress→JS),
  `speechStart/Stop/Arm`, `micTest`.
- **Meta IAP**: `metaIapStatus`, `metaPurchase`, `metaAgeCategory`,
  `metaPendingPurchases`.
- **Log cache**: `cacheStatus`, `cacheScan(files, MB)`, `cacheInstanceUsers(MB)`,
  `cacheDebugInfo`, `cacheRequestAccess`, `cachePickFolder` (SAF), `cacheClearFolder`.
- **Now-playing**: `nowPlaying`, `nowPlayingRequestAccess`.
- **Device / perms**: **`getDeviceStatus`** — ONE call returns battery %/charging/
  temp, storage free/total, thermal status, media volume + muted, Wi-Fi RSSI/bars/
  Mbps, RAM total/avail/lowMemory, display refresh Hz, device uptime, charge-time,
  and **`worn`** (headset on-head via the `sys.hmt.mounted` system property).
  `getForegroundApp` (UsageStats), `openUsageAccessSettings`, `isBatteryUnrestricted`
  / `requestBatteryUnrestricted`.
- **OSC-in internals**: `oscListenStart` binds UDP 9001 (SO_REUSEADDR, 5 retries),
  reads packets into `oscParams` (push) AND batches them to JS via
  `window.__nexusOscBatch` on a **66 ms coalescing flush** (`OSC_FLUSH_MS`), with
  the pending map **capped at 400** and keyed by address so repeated updates to one
  param collapse. It also starts OscQuery's poller + advertise server (§4.3).

### 4.2 Login — `VrcWebLogin` + `AuthWindow`  *(the headline change)*
- **`VrcWebLogin`** (the Google/Steam/Meta-capable VRChat login): a full-screen
  `Dialog` + `WebView` loading **`https://vrchat.com/home/login`** with
  `setAcceptThirdPartyCookies(true)` (the SSO-redirect enabler), clearing any old
  `auth`/`twoFactorAuth` first. **It never drives or parses the login** — VRChat's
  own page does username/password, 2FA, AND "Sign in with Steam/Meta/…". Success
  is detected by **polling**: every ~1.2 s (and on `onPageFinished`) it reads the
  `auth` cookie from the WebView's `CookieManager` (must start with `authcookie_`),
  then does a background `GET auth/user` with that cookie + the WebView UA, and
  completes only when the body has `"id":"usr_"` **and not** `requiresTwoFactorAuth`.
  It returns `(authCookie, twoFactorAuthCookie, userAgent)`; `VrcBridge.webLogin`
  stores them and the UA, then every API call rides those cookies with roll-forward.
  **No password is ever captured**, and there is no auto-relogin — a truly dead
  cookie means re-opening the web login (cookie roll-forward is the only cushion).
- **`AuthWindow`** is a *separate, generic* OAuth redirect-catcher (loads a
  provider authorize URL, watches `shouldOverrideUrlLoading` for a `redirect_uri`
  prefix, returns the redirect URL with the `code`). Used for Spotify/third-party,
  **not** VRChat.

### 4.3 OSC + OSCQuery — `OscQuery` + the `VrcBridge` listener
- **OSC-in**: UDP 9001 listener (§4.1) → `oscParams` live map; `/avatar/change`
  triggers an OSCQuery rescan; `/avatar/parameters/<p>` stores the value.
- **OSCQuery PULL (`OscQuery`)**: `startPoller` refreshes every **15 s** —
  mDNS-discovers VRChat's `_oscjson._tcp`, resolves, GETs the param tree (1.8 s
  timeout), walks `/avatar/parameters/*` with a `TYPE` into `oscParams`. Avatar-id
  change clears the map. `onAvatarChanged` does a backoff rescan at 0/3/6/10 s,
  generation-guarded so a newer swap cancels an older rescan.
- **OSCQuery ADVERTISE (`startServer`)**: binds an ephemeral `ServerSocket`,
  registers its own `_oscjson._tcp` service named "VRC-NEXUS" via `NsdManager`
  with a **MulticastLock**, and serves `HOST_INFO` → `OSC_IP:127.0.0.1,
  OSC_PORT:<our listen port>`. This is how it *tells VRChat where to send OSC* —
  but `OSC_IP` is `127.0.0.1`, confirming the whole OSC-in path is **same-device
  (on-Quest) only**; it does not solve a phone receiving a Quest's OSC.

### 4.4 `ChatboxService` — the chatbox compositor (biggest class, ~3500 lines)
A `specialUse` FGS holding a partial wake lock, a 250 ms fast tick
(`FAST_TICK_MS`), min-change gate `CHANGE_MIN_MS=600`, default send cadence
`sendMs=1500` (floored to 400), sending `/chatbox/input` `,sTF` (144 cap) to all
`hosts:port`, with the **identical invisible-background egg** VRC-A uses:
`EGG_SUFFIX = "\u0003\u001f"` (U+0003 + U+001F).

- **Dynamic feed line types (14)**: `time`, `date`, `uptime`, `devuptime`,
  `battery`, `volume`, `worn`, `weather`, `instance`, `media`, `lyrics`,
  `personal` (cycling free text), `vrcparam` (any OSC avatar param), `app`
  (foreground app). The network-backed values (weather/instance/alerts/lyrics/
  media) come from `LiveFeeds` (§4.5), TTL-cached so the tick never hammers an API.
- **Chatbox mini-games** (rendered as OSC text, playable from in-game input or the
  app): Flappy Bird, Snake, 2048, Minesweeper, Wordle, Hangman, Tic-Tac-Toe,
  Rock-Paper-Scissors, Slots, Magic-8-ball. Word lists for Wordle/Hangman are
  fetched from GitHub and cached up to 7 days.
- **Background survival**: `pushPresenceContext` persists the VRChat token + self
  name/id + world name into the service so it keeps composing + sending with the
  web UI gone; a 10 s `PRESENCE_TICK` loop maintains it. Config + presence persist
  to `SharedPreferences("nexus_chatbox_bg")`.

### 4.5 `LiveFeeds` — native data providers for the chatbox (all TTL-cached)
Supplies the network-backed chatbox lines, each with a cache window so the 250 ms
tick costs ~nothing:
- **`weatherLine`** — open-meteo forecast + `nominatim` geocode (geocode cached
  24 h, weather **10 min** TTL); WMO code→emoji+label, temp, ↑high/↓low, place.
- **`instanceLine`** — `GET auth/user` → location → `GET instances/{loc}` → world
  name + count (`n_users`/`userCount`) + type + region + 18+, **25 s TTL**.
- **`alertsLine`** — polls `GET auth/user/friends?offline=false&n=100` every **30 s**,
  diffs against the previous online set → "X is online" / "N friends came online"
  (optional watch-list filter + hold window).
- **`lyricsLine`** — LRC parse + binary-search by position (synced lyrics).
- **`remoteNowPlaying`** — a **friend's** Last.fm (`<backend>/vrc/lastfm?user=`) or
  Discord presence (`<backend>/discord-presence`) now-playing, via their backend,
  5 s TTL; local sources are MediaSession (`NowPlaying`) or the in-app music tab.
- **Backend host selection**: health-check `api.vrc-nexus.online` then the
  Cloudflare proxy (`/healthz`), cached 5 min.

### 4.6 `ScriptService` — avatar-OSC automation / macros
A `specialUse` FGS that runs a JSON "program" against avatar OSC params — a small
scripting language, now richer than 0.4.8: opcodes `set`, `wait`, `chatbox`,
`random`, `input` (pulse a `/input/<Action>`), `height` (`/avatar/eyeheight`),
`hue`/`emission` (auto-detect hue/glow-ish params by name and sweep), `ramp`
(linear sweep), `pulse`, `loop`, **`if`** (+ comparators `eq/gt/lt/ne`), and
**`parallel`** (concurrent blocks). Avatar *control*, not just chatbox.

### 4.7 `VrcCache` — VRChat log reader + instance roster
The reference log reader (VRC-A already has its own in `InstanceRosterManager`;
this confirms the grammar + bounds):
- **Access order**: direct `File` read of `Android/data/<pkg>/files` (VRChat pkgs
  `com.vrchat.oculus.quest`, `com.vrchat.VRChatAndroid`) + shared `Documents/Logs`,
  `Documents/VRChat`, `VRChat/Logs` (needs All-files); then a **SAF tree URI**
  (`ACTION_OPEN_DOCUMENT_TREE`, persisted + re-resolved from
  `getPersistedUriPermissions`, walked via `DocumentsContract`); ADB `appops`
  hint as last resort. VRChat Logging must be FULL (the status message spells this
  out).
- **Regex grammar (verbatim)**: `OnPlayerJoined\s+(.+?)(?:\s+\((usr_…{36})\))?\s*$`,
  `OnPlayerLeft…`, `Joining\s+(wrld_…{36}):(\S+)`,
  `Switching\s+(.+?)\s+to avatar\s+(.+?)\s*$`,
  `Unpacking Avatar\s+\((.+?)\s+by\s+(.+?)\)\s*$`, plus bare `usr_`/`avtr_`/`wrld_`
  scans. Roster assembles `{name, usr_id, avatarName, avatarCreator, avatarId}`;
  `Joining` resets it, `OnPlayerLeft` removes. It *tries* to capture `avatarId`
  only from a line carrying **both** a `usr_` and an `avtr_` (PC-style; Quest logs
  rarely have it — matches our finding that remote avatar IDs aren't in Quest logs).
- **Efficiency**: `listLogs` cached 4 s; `readTail(file, maxBytes)` seeks to
  `length-maxBytes` via `RandomAccessFile` (never reads the whole file), cached in
  an **8-entry LRU keyed by (size,modified)** so an unchanged file re-scans free;
  roster = newest 6 files × ~4 MB tail. **Pull-based** (JS polls `cacheInstanceUsers`)
  — no FileObserver, so VRC-A's FileObserver-driven instant roster is actually better.

### 4.8 Speech — `SpeechToText` + `SpeechService`  *(novel, fully offline)*
On-device dictation → chatbox, zero API cost after a one-time model download:
- Two engines: **Vosk** ("small" model) and **sherpa-onnx** (online transducer:
  `encoder/decoder/joiner.onnx` + `tokens.txt`, `modified_beam_search`, 2 CPU
  threads, 16 kHz, featureDim 80). Model readiness = file presence.
- **Models downloaded on demand** (`downloadModel`: URL → zip → flatten into
  `filesDir/vosk-model` or `sherpa-model`, progress streamed to JS) — not bundled,
  so the APK stays small and RAM is only used while armed; released on `stop()`.
- **Wake-word gating** ("nexus" default) with a 7 s window + fuzzy Levenshtein
  match, or push-to-talk ("button mode"). 16 kHz mono, RMS auto-gain. Partial +
  final transcripts pushed to `window.__nexusSpeech` → the JS drops them in the
  chatbox. Runs under `SpeechService` (FGS microphone).

### 4.9 Music — `MusicPlayerService` + `MusicResolver`/`MusicCache`/`MusicDownloader` + `NowPlaying`
An in-app music player: **NewPipeExtractor** resolves a track's audio stream
(`MusicResolver` → `StreamInfo.audioStreams`; Rhino deciphers YouTube's base.js),
playback via **`MediaPlayer`** (lighter than ExoPlayer) in a `mediaPlayback` FGS,
with `MusicCache` a **bounded disk cache that evicts** (resolve checks cache first,
`prefetchMusic`/`warmMusic` pre-resolve). `NowPlaying` reads the active MediaSession
via `MediaSessionManager.getActiveSessions` (bound to the notification listener) +
`PlaybackState` (+ Spotify). Lyrics come from `LiveFeeds` (LRCLIB).

### 4.10 Watch party + movies + screen share  *(entirely new category vs VRC-A)*
- **`WatchParty` + `PartySignaling` + `PartyAudioPlayer`**: a **raw WebRTC mesh**
  (`PeerConnection` + `DataChannel`, 48 kHz audio via `AudioTrack` with echo
  cancellation), NOT the LiveKit client SDK — `PartySignaling` talks to the
  `api.vrc-nexus.online` backend for room create/join, peer lists
  (`PeerInfo`), ICE servers (`IceServerInfo`; `livekit.vrc-nexus.online` is the
  TURN/ICE), and moderation (`BannedInfo`, force-mute/kick/ban). DataChannel
  carries watch-together sync + control.
- **Screen share**: `MediaProjectionService` (`mediaProjection`) + `ScreenAudioCapturer`
  (`AudioPlaybackCaptureConfiguration` to grab internal audio at 48 kHz +
  `AudioRecord`) feed the party, so you can share a screen + its audio to others.
- **Movies**: `MovieActivity`/`MoviesWindow` embed **`nepu.io`** (a free movie/TV
  streaming site) in a WebView, poppable into an overlay and sync-able via the
  party. (Piracy-adjacent — see §9.)

### 4.11 Discord — embedded Vencord client
`DiscordActivity` loads `discord.com` in a WebView and **`DiscordSupport` injects
the bundled Vencord mod** (`assets/vencord.user.js` + `.css`) via
`evaluateJavascript`; `DiscordUpdater` can pull newer Vencord bundles from
`raw.githubusercontent.com`; `DiscordNet`/`DiscordFiles` handle fetch + file
upload. This is a full **modded Discord client in a panel**, not an RPC presence
pusher (VRC-A's Discord RPC is a different, lighter thing).

### 4.12 HypeRate — heart rate
The web UI opens `wss://app.hyperate.io/socket/websocket` for a live heart-rate
feed (HypeRate device/ID) → a `{hr}`/`{bpm}` chatbox value + an in-app display
(~280 `hr` refs in the JS). Pure additive chatbox content.

### 4.13 Meta IAP — `MetaIap`
Oculus Platform billing (`getLoggedInUser`, products by SKU, `Purchase`,
age-category, pending purchases) — the app is monetized through the **Meta Horizon
Store** (consumables/entitlements). Relevant only to a Horizon-Store build.

### 4.14 Infra — telemetry, foreground-app, mic, ad-block, crash
- **`NexusApp`** (Application): tracks foreground via an `ActivityLifecycleCallbacks`
  resumed-counter + window focus → `canStartForegroundService()` gates every FGS
  start on `isForeground() && isWindowFocused()` (Android 12+ background-start
  compliance; VRC-A's `startForegroundSafely` mirrors this). Also an
  uncaught-exception handler that writes crash JSON to `filesDir/crashes/` and
  uploads up to 25 on next launch to `api.vrc-nexus.online/crash`.
- **`ForegroundApp`**: `UsageStatsManager` `queryEvents`/`queryUsageStats`
  (`MOVE_TO_FOREGROUND`) to detect which app is in front (is VRChat foregrounded)
  — drives the `app` chatbox line and feature pause/resume. Needs the user to grant
  Usage Access in Settings.
- **`MicProbe`**: `AudioRecord` RMS level test for the mic UI.
- **`AdBlocker`**: a large ad/tracker **hostname blocklist** (hundreds of domains,
  incl. Spotify ad hosts like `ads.spotify.com`) used in the embedded WebViews'
  `shouldInterceptRequest` to return empty responses — ad-free embedded Discord/
  Spotify/Movies AND fewer network requests.

---

## 5. VRChat API surface (relative paths the JS feeds `apiRequest`)
`auth/user`, `auth/twofactorauth/{type}/verify`, `auth/user/friends?offline=&n=`,
`auth/user/notifications`, `users/{id}`, `users?search=`, `avatars/{id}`,
`avatars?user=me&releaseStatus=all&n=&offset=`, `avatars?userId=`,
`avatars/favorites`, `worlds/{id}`, `worlds?search=`, `worlds?sort=popularity`,
`instances/{loc}`, `instances/recent?n=48`, `groups/…`, `favorites?type=avatar|world|friend`,
`invite/{id}`, `invite/myself/to/{loc}`, `file/…`. (The group-moderation +
auto-invite endpoints from the 0.4.8 teardown are still reachable via the same
generic passthrough.) It remains a **full VRChat companion client**, not just a
chatbox tool.

---

## 6. Feature matrix — Nexus VRC vs VRC-A

| Capability | NEXUS | VRC-A |
|---|---|---|
| OSC chatbox (`,sTF`, 144 cap, egg) | ✅ | ✅ |
| Pinned / cycling text | ✅ (`personal`) | ✅ (richer: sub-lines, presets, drag editor) |
| NowPlaying in chatbox | ✅ | ✅ (richer: ad detection, title cleaning, progress presets) |
| **VRChat WEB login (Steam/Meta/SSO)** | ✅ | ❌ (username/password + 2FA only) |
| **Synced lyrics (LRCLIB)** | ✅ | ❌ |
| **Weather / date / uptime / battery / worn lines** | ✅ | time only |
| **HypeRate heart-rate line** | ✅ | ❌ |
| **Live avatar-param lines (mute/AFK/movement/any)** | ✅ | ✅ (headset: `{mute}/{afk}/{movement}/{scale}/{param}`) |
| OSC-in (:9001) + OSCQuery | ✅ | ✅ (headset `VrcaOscQuery`) |
| **Avatar-OSC automation/macros** | ✅ (`set/ramp/hue/pulse/if/parallel/loop`) | ❌ |
| **Offline speech-to-text → chatbox** | ✅ (Vosk + sherpa-onnx) | ❌ |
| **Chatbox mini-games** | ✅ (10) | ❌ |
| Log-based instance roster | ✅ (pull) | ✅ (FileObserver, instant) |
| Avatar clone / catalog | basic (DB search) | ✅ (image-verified crowd catalog, resolver) |
| **In-app music player (NewPipe)** | ✅ | ❌ |
| **Watch party (WebRTC) + screen share + movies** | ✅ | ❌ |
| **Embedded Discord (Vencord)** | ✅ | RPC only (lighter, different) |
| Discord Rich Presence | ❌ | ✅ |
| **Device telemetry (battery/thermal/RAM/wifi/worn)** | ✅ (one call) | partial |
| **Foreground-app detection** | ✅ | ❌ |
| **Ad-blocking (embedded webviews)** | ✅ | n/a |
| **Meta Horizon Store billing** | ✅ | ❌ |
| Admin/moderation, Firestore, directed releases, forced updates | ❌ | ✅ |
| Friend-activity notifications, group events, announcements | ❌ | ✅ |
| Background survival (OEM killers, watchdog, restore) | moderate (+battery exemption now) | ✅ (extensive) |

VRC-A still wins decisively on the social/admin/RPC/notifications/background and
avatar-cloning axes. NEXUS's net-new surface is **web login, a pile of chatbox
content sources (lyrics/weather/HR/speech), avatar scripting, and three big new
product categories (music, watch-party, embedded Discord)**.

---

## 7. Gap analysis — what would improve VRC-A / what's missing

Ordered by value ÷ effort. ⚠️ marks features whose full value needs VRC-A running
**on the Quest** (headset build) or that carry real risk.

### Tier 1 — high value, low effort, additive, phone + Quest
1. **VRChat web login (Steam/Meta/Viveport/Pico)** — the headline. Lets the large
   password-less SSO audience (especially Meta accounts on the headset build) use
   VRC-A at all. Mechanism (§4.2): a `WebView` on `vrchat.com/home/login` +
   third-party cookies + poll `auth/user` until `usr_` without
   `requiresTwoFactorAuth` → seed `VrchatAuthManager`'s existing cookie store
   (auth + twoFactorAuth + captured UA). **Additive**: keep the password flow as
   primary (it alone gives silent `autoRelogin`); add web login as a second door;
   add a headless-reauth-via-provider-session so SSO users approach "log in once"
   (see the separate login discussion already in this session).
2. **New chatbox dynamic tokens** — VRC-A already has a token resolver
   (`{time}/{song}/{world}/{players}/{mute}/{afk}/{movement}/{scale}/{param}`).
   Slot in `{weather}` (open-meteo + Nominatim, geocode-cached 24 h / weather
   10 min), `{date}`, `{uptime}`, `{battery}`, `{worn}` (headset), and
   `{hr}`/`{bpm}` (HypeRate `wss://app.hyperate.io/socket/websocket`). Each must be
   TTL-cached (see §8).
3. **Synced lyrics (LRCLIB)** as a NowPlaying option — free, no key, pure additive;
   fetch `syncedLyrics`, parse LRC, binary-search by `positionMs`, feed one line at
   the current music cadence. (This was Tier-1 in the old doc and still isn't shipped.)

### Tier 2 — high value, medium effort (Quest-leaning ⚠️)
4. **Offline speech-to-text dictation → chatbox** (⚠️ mic; best on headset): speak
   → text in the VRChat chatbox, fully offline. sherpa-onnx (preferred) or Vosk,
   **model downloaded on demand** into `filesDir`, recognizer loaded only while
   armed and released on stop (RAM-bounded), wake-word or push-to-talk gate. A
   genuinely differentiated feature with zero ongoing API cost.
5. **Avatar-OSC automation / macros** — a small block engine (`set/wait/ramp/
   random/pulse/input/height/hue/emission/loop/if/parallel`) over
   `/avatar/parameters/*`, `/input/*`, `/avatar/eyeheight`. VRC-A already has the
   OSC send path (chatbox) + OSCQuery param discovery (`VrcaOscQuery`) for the
   hue/emission auto-detect; the SEND side works from a phone too. Distinct power-
   user surface.
6. **Chatbox mini-games** (optional crowd-pleaser) — pure OSC-text rendering, no
   permissions, low risk; good retention/marketing feature.

### Tier 3 — big new categories, high effort (evaluate as products)
7. **Watch party (WebRTC) + screen share + movies** — a whole surface needing a
   **signaling backend + TURN** (NEXUS runs its own `api.vrc-nexus.online` +
   LiveKit/TURN). VRC-A has no backend of this kind (Firestore only). Big bet;
   only if it's a product direction. Movies via `nepu.io` is piracy-adjacent —
   skip that part (§9).
8. **In-app music player (NewPipe)** — popular but carries YouTube-ToS +
   maintenance risk (NewPipe breaks when YouTube changes); weigh carefully (§9).
9. **Embedded Discord (Vencord)** — VRC-A already has Discord RPC; a full modded
   client in a panel is a different, heavier thing with Discord-ToS risk. Likely
   **not** worth it.

### Infra / telemetry borrows (cheap, useful)
10. **Device telemetry** — a single `getDeviceStatus`-style call (headset battery,
    thermal, RAM, Wi-Fi, `worn` via `sys.hmt.mounted`) for a headset status panel
    + a low-battery / on-head gate for features.
11. **Foreground-app detection** (UsageStats) — pause/resume VRC-A features based
    on whether VRChat is actually foregrounded.
12. **Opt-in crash upload** — VRC-A has a local crash screen; a tiny opt-in upload
    (like NEXUS → their backend) would surface field crashes. (Firestore or a
    minimal endpoint.)
13. **Meta Horizon Store build + IAP** — only if VRC-A's headset variant ships to
    the Horizon Store; NEXUS proves the Platform SDK path.

---

## 8. RAM & request-endpoint efficiency findings (what to adopt)

The user specifically asked what helps **less RAM / fewer request endpoints**.
NEXUS's patterns worth adopting (several VRC-A already does — noted):

**Fewer / cheaper requests:**
- **TTL-cache every VRChat-derived chatbox value.** NEXUS caches the instance line
  25 s, friend-alerts 30 s, weather 10 min, geocode 24 h, remote now-playing 5 s,
  backend-host health 5 min. Any new VRC-A dynamic token MUST do the same so a
  per-tick chatbox never drives per-tick API calls. (VRC-A already fetches the
  instance count on a lightweight single call; extend that discipline to every new
  token.)
- **One generic authenticated passthrough + cookie roll-forward** (`apiRequest` +
  `captureCookies`) keeps the native layer tiny and keeps the session alive
  browser-style (fewer re-logins). VRC-A's `captureRolledCookies` is the same idea;
  keep leaning on it (it's the thing that makes the no-password web-login viable).
- **OSCQuery PULL (15 s) + OSC-in PUSH for avatar state** = **zero** VRChat API
  calls for mute/AFK/movement/params. VRC-A's `VrcaOscQuery` already does this on
  the headset — use it as the source for any `{mute}/{afk}/{movement}` tokens
  rather than REST.
- **Byte-offset log tailing + tiny LRU** (`readTail` seeks to `len-maxBytes`, 8-entry
  cache keyed by size+modified, 4 s list cache) — re-scans of an unchanged log are
  free. VRC-A's roster is FileObserver-driven (even better — event, not poll);
  adopt the (size,modified) tail cache + bounded tail size if not already bounded.
- **On-device processing instead of network** — offline speech = no STT API; local
  MediaSession now-playing = no polling a service. Prefer on-device wherever a
  feature could otherwise call out.
- **Ad/tracker host blocklist** in any embedded WebView (if VRC-A ever embeds one)
  cuts a large fraction of requests + data.
- **Batch/coalesce bridge→UI traffic** — the OSC→JS bridge flushes at 66 ms and
  caps the pending map at 400 keyed by address (dupes collapse). Analogous: debounce
  any high-rate flow into the UI.

**Less RAM:**
- **Lazy-load heavy engines; release on stop.** The speech recognizer + model load
  only while armed and are released on `stop()`; the music player uses `MediaPlayer`
  (lighter) not ExoPlayer. VRC-A already caps Coil (10 MB disk / 12 MB mem),
  Firestore cache (25 MB), and the Discord WebView cache (64 MB) — keep that
  discipline for any new heavy component (models, players, WebRTC).
- **Download models/assets on demand, don't bundle** (speech models, word lists).
  Keeps both APK size and resident RAM down; VRC-A already does on-demand tutorial
  images + rich-media.
- **Bounded caches with eviction everywhere** (music disk cache evicts; tail LRU 8;
  OSC pending 400; crash upload capped at 25/run).
- **Purpose-typed foreground services started only when foreground-allowed**
  (`canStartForegroundService`), rather than one always-on mega service, so RAM/
  wakelocks are scoped to the active feature. VRC-A's model is heavier here by
  design (background survival is a VRC-A selling point); no change needed, but the
  FGS-start gate pattern is already mirrored.

**Net:** the single biggest efficiency lesson is **TTL-cache every chatbox data
source and prefer OSCQuery/on-device over REST** — adding NEXUS's content tokens
to VRC-A costs almost no extra requests if done that way.

---

## 9. Don't-copy / risk list
- **`nepu.io` movie streaming** — piracy-adjacent; do not embed.
- **NewPipe YouTube extraction** — YouTube ToS + constant breakage/maintenance;
  adopt only with eyes open (LRCLIB lyrics are fine and separate).
- **Vencord-modded Discord client** — Discord ToS risk (client modding); VRC-A's
  RPC is the safer, lighter path. Skip the full client.
- **Scraping the VRChat password out of the web-login page** — never; it's covertly
  harvesting a credential on a page we don't own, fragile, and impossible for SSO
  users anyway. The cookie-harvest (§4.2) is the clean approach.
- **Plain SharedPreferences for the session** — VRC-A's EncryptedSharedPreferences
  is strictly better; keep it.

---

## 10. VRC-A master plan — missing / changing / improving

The full backlog, in three buckets: **A. Missing** (net-new to add), **B.
Changing** (fix/correct/decide on existing behaviour), **C. Improving** (polish
or extend what already works). Items are tagged `[both]`/`[quest]`/`[phone]`,
effort **S/M/L**, and mapped to our classes. Draws from both the NEXUS gap
analysis (§7) and VRC-A's own documented limitations/deferred items in CLAUDE.md.
Complements the existing `docs/account-system-plan.md` and `docs/ui-revamp.md`.

### A. MISSING — features to add

**A1 — Tier 1 (cheap, additive, do first):**
1. **VRChat web login (Steam/Meta/Viveport/Pico/SSO)** `[both, M]` → new
   `VrchatWebLoginScreen` mirroring `DiscordLoginWebView`: load
   `vrchat.com/home/login`, `setAcceptThirdPartyCookies(true)`, poll `auth/user`
   until `usr_` without `requiresTwoFactorAuth`, seed `VrchatAuthManager`'s cookie
   store (auth + twoFactorAuth + captured UA). Additive beside the password flow
   (see B1). Unlocks the password-less SSO audience — biggest win for the headset
   build. *(This is the item you flagged first; it anchors the plan.)*
2. **Chatbox content tokens** `[both, S–M]` → extend `VrcaViewModel.resolveTokens`
   with `{weather}` (new `WeatherFeed`: open-meteo + Nominatim geocode),
   `{date}`, `{uptime}`, `{battery}`, `{worn}` `[quest]`, and `{hr}`/`{bpm}`
   (new `HypeRateClient`, `wss://app.hyperate.io`). Every one TTL-cached per §8.
   Update `TokensHint`.
3. **Synced lyrics (LRCLIB)** `[both, S]` → `LrcLibLyrics` helper
   (fetch/parse/binary-search by `positionMs`) wired into `buildNowPlayingLines()`
   behind a "Show lyrics" toggle; throwaway-test the LRC parser.

**A2 — Tier 2 (medium effort, headset-leaning):**
4. **Offline speech-to-text dictation → chatbox** `[quest, M–L]` →
   `SpeechToTextManager` (sherpa-onnx preferred, Vosk fallback), model **downloaded
   on demand** into `filesDir`, FGS `microphone`, wake-word / push-to-talk gate,
   transcript → chatbox via the existing OSC path. Recognizer loaded only while
   armed, released on stop (RAM-bounded). Zero ongoing API cost.
5. **Avatar-OSC macros** `[both send / quest param-detect, M]` → `OscScriptEngine`
   + an Automations sub-tab (`set/wait/ramp/random/pulse/input/height/hue/emission/
   loop/if/parallel`). Reuse the chatbox OSC send path + `VrcaOscQuery` params for
   hue/emission auto-detect.
6. **Translate a chatbox line** `[both, S]` → prefer **LibreTranslate** (clean
   dependency) over the unofficial Google endpoint NEXUS uses. *(Was Tier-4 in the
   old teardown; still missing.)*
7. **Alternate now-playing sources** `[both, S–M]` → Spotify Web API OAuth +
   Last.fm, for users whose Notification Access is denied. *(Old teardown Tier-4;
   still missing.)*
8. **Chatbox mini-games** `[both, M]` — optional crowd-pleaser; pure OSC-text, no
   permissions, low risk.

**A3 — Tier 3 (product bets; higher effort / real risk — decide before building):**
9. **Watch party (WebRTC) + screen share** `[quest, L]` — needs a signaling
   backend + TURN (we only have Firestore today). Big new surface; only if it's a
   product direction. *(Movies via `nepu.io` — skip, piracy; see §9.)*
10. **In-app music player (NewPipe)** `[both, L]` — popular but carries YouTube-ToS
    + constant-breakage maintenance risk. Weigh carefully.
11. **Embedded Discord (Vencord) client** — **do not build**; we already have the
    lighter, safer Discord RPC. Listed only to mark it explicitly out of scope.

**A4 — Infra / telemetry (cheap, cross-cutting):**
12. **Device telemetry panel** `[quest, S]` → one `deviceStatus()` call
    (battery/thermal/RAM/wifi/`worn` via `sys.hmt.mounted`) + a low-battery /
    on-head feature gate.
13. **Foreground-app gate** `[quest, S]` → `UsageStatsManager` "is VRChat
    foregrounded" to pause non-essential loops when the user left VRChat.
14. **Opt-in crash upload** `[both, S]` → we have a local crash screen; add a tiny
    opt-in upload (Firestore doc or a minimal endpoint) to surface field crashes.
15. **Meta Horizon Store build + IAP + submission manifest** `[quest, M]` → only if
    we distribute the headset variant to the Horizon Store (CLAUDE.md lists the
    store-submission manifest entries as still deferred). NEXUS proves the Platform
    SDK path.

### B. CHANGING — fix / correct / decide on existing behaviour
1. **Make web login additive without weakening "log in once"** `[both, M]`,
   `VrchatAuthManager` + the `authDead` gate: keep the username/password flow as
   **primary** (it alone gives silent `autoRelogin`); add a **headless-reauth** that
   leans on the preserved provider cookie jar; when `authDead` fires for a
   cookie-only (no-password) session, surface "re-sign-in on the website" instead
   of attempting `autoRelogin`; give `exportSessionBundle` / the auth-transfer a
   **cookie-only variant**. (The design decision from this session's login thread.)
2. **R2/KV cost — the passive-harvest lever** `[backend, decision]`: the remaining
   un-pulled lever (per CLAUDE.md Increment 7/23) is throttling or disabling the
   per-user **passive catalog harvest** (search-box + own-library) if the bill is
   still too high. Decide: keep growth vs cap cost. No code until decided.
3. **OSC-in phone→Quest** `[phone, M, likely won't-fix]`: our OSCQuery/OSC-in is
   headset-only today. NEXUS confirms it advertises `OSC_IP 127.0.0.1` → same-device
   only, so a phone receiving a Quest's OSC needs the phone to advertise its own
   OSCQuery service on the LAN IP. Decide whether that experimental hop is worth it
   or stays Quest-only.
4. **Avatar-resolver ceiling (accept + hold)** `[accepted limitation]`: Quest
   exposes no remote avatar id and `Unpacking Avatar (… by …)` author lines land
   only ~14–44% of the time, so the name+author + image-verified crowd catalog **is**
   the ceiling. No further resolver rework planned — just keep the catalog growing;
   revisit only if VRChat restores a field (the `buildImageFieldsDiag` watchdog will
   show it).
5. **Roster mute/block** `[decided — leave removed]`: removed because VRChat's own
   in-client mute/block is the reliable path; do not re-add.

### C. IMPROVING — polish / extend what exists
1. **Monitor-frame the boot + onboarding screens on headset** `[quest, S]` — the
   M1.5 polish CLAUDE.md flags as not done; frame them for the Quest panel like the
   rest of the UI.
2. **Hold the §8 TTL-cache discipline on every new chatbox source** `[both]` — any
   token added in A2 must cache (weather 10 min, instance 25 s, friend-alerts 30 s,
   HR live-socket) so a per-tick chatbox never drives per-tick requests.
3. **Bound roster tail reads** `[quest, S]` — ours is already FileObserver-instant
   (better than NEXUS's poll); optionally adopt VrcCache's `(size,modified)` tail
   cache + bounded tail size if our reads aren't already capped.
4. **Rich-content deferred polish** `[both, S]` — faststart (moov-at-front)
   transcode + release-retract media cleanup, both noted "still open" in CLAUDE.md.
5. **Headset fixed-panel resize** `[accepted]` — Horizon OS has no hard "no-resize"
   API; the width-responsive framing + 3/2/1-column thresholds already degrade
   gracefully, so leave as-is unless Meta adds an API.
6. **Discord RPC backend-down** `[accepted]` — a healthy socket while Discord's
   presence backend is down is client-undetectable; nothing to do.

### Sequencing
**A1 first** (web login + tokens + lyrics) with **B1** riding alongside A1's web
login; then **A2** (speech, macros, translate, alt now-playing) and the cheap
**A4** infra in parallel; **A3** only as deliberate product decisions; **B/C**
fixes folded in opportunistically. Keep this file + `CLAUDE.md` updated as items
land (the old CLAUDE.md "§4.7" log-reader reference maps to §4.7 here).

---

## 11. Strategic read
NEXUS v1.49 is no longer "a chatbox tool" — it's a sprawling Quest-side VRChat +
media super-app (login, chatbox with lyrics/weather/HR/speech/games, avatar
scripting, music, watch-parties, embedded Discord, Store billing) built as a
native shell around a web UI. VRC-A is the more disciplined, more reliable product
on everything that needs a backend, Discord RPC, moderation, notifications, and
background survival, and it has a better avatar-cloning stack. The high-leverage,
low-risk borrows are **(1) VRChat web login** (unlocks the SSO/Meta audience),
**(2) a batch of TTL-cached chatbox content tokens** (lyrics, weather, date,
uptime, battery, heart-rate), and **(3) offline speech dictation** — all additive,
all cheap on requests/RAM if done with the §8 caching discipline. The big new
categories (music, watch-party, embedded Discord) are real product decisions, not
quick wins, and two of them carry ToS/maintenance risk. The "pause on in-game
typing" idea from before is still not buildable — no such signal exists.
