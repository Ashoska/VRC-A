# VRC-NEXUS teardown + VRC-A gap analysis

A full reverse-engineering writeup of the competitor APK the user supplied
(`com.deize.vrcnexus.quest`, "VRC-NEXUS", **version 0.4.8 / code 53**), followed
by a gap analysis of what VRC-A is missing and what is worth borrowing — scoped
to things that are **mobile- and Quest-compatible**.

This is a facts/techniques document (log formats, public APIs, OSC/OSCQuery
behaviour, permission models). No third-party source code is copied into the
repo; the APK and its decompiled output were analysed in a scratch directory and
were **not** committed. Learn the *technique*, write our own code.

> TL;DR of the two questions that kicked this off:
> 1. **Can we detect the user typing/sending in VRChat's OWN in-game chatbox?**
>    **No.** Neither the VRChat logs nor OSC expose in-game chatbox typing or
>    text. NEXUS doesn't do it either — it can't. See §6.
> 2. **Does OSCQuery let us truly confirm the chatbox landed (vs a blind IP ping)?**
>    **Partly.** OSCQuery/OSC-in confirms *VRChat is running with OSC enabled and
>    reachable* — a far better "our chatbox WILL be received" signal than a ping —
>    but it does **not** echo `/chatbox/input`, so you can't confirm a specific
>    message rendered. And it exposes live avatar state (mute, AFK, movement…).
>    See §6.

---

## 1. What VRC-NEXUS actually is

A **Capacitor hybrid app**: a thin native Android shell (Capacitor/Cordova
bridge) wrapping a Vue web app, plus a handful of **custom native plugins** that
do the things a WebView can't (UDP sockets, log-file reading, MediaSession,
foreground services). All the product logic (UI, VRChat API calls, feature
wiring) lives in one minified JS bundle; the native plugins are dumb bridges the
JS calls into.

Critically, **NEXUS runs ON the Quest headset itself** (it's a Quest-side APK),
so it talks to VRChat over `127.0.0.1` — OSC in/out and log files are all local.
This is the single most important scoping fact for the gap analysis (§9–10):
NEXUS's cleverest features are "free" only because it shares a device with
VRChat. It can also target a custom LAN host for chatbox send.

Stack: Capacitor 6-ish, Vue 3, `@capacitor/{app,browser,filesystem,preferences}`
(stock plugins), **no native `.so` libraries at all**. Quality is low/AI-authored
(the user's own words — "the kid was too lazy to code"): copious duplicated
try/catch, a demo/mock mode gate, but the ideas are sound.

---

## 2. Build facts & permissions (from the decoded manifest)

- `appId` = `com.deize.vrcnexus.quest`, label `VRC-NEXUS`
- `versionName` 0.4.8 / `versionCode` 53, `targetSdk` 34, `minSdk` 22
- Permissions:
  - `INTERNET`
  - `ACCESS_WIFI_STATE`, **`CHANGE_WIFI_MULTICAST_STATE`** ← required for
    OSCQuery mDNS discovery (NsdManager) + a WiFi multicast/high-perf lock
  - `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`
  - `POST_NOTIFICATIONS`, `WAKE_LOCK`
  - `READ_MEDIA_IMAGES`, `READ_EXTERNAL_STORAGE` (maxSdk 32),
    **`MANAGE_EXTERNAL_STORAGE`** ← "All files access", for reading VRChat logs
  - a private `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (Capacitor boilerplate)
  - Notification-listener access is via the `BIND_NOTIFICATION_LISTENER_SERVICE`
    component permission on `NexusNotificationListener` (granted by the user in
    Settings, not a `uses-permission`).

Note it does **not** request battery-optimization exemption or any OEM
allow-list — VRC-A's background-survival stack is far more sophisticated.

---

## 3. File-by-file inventory

### 3.1 Web assets (`assets/public/…`)
| File | What it is |
|---|---|
| `index.html`, `assets/index-*.css` | Vue app shell + styles |
| `assets/index-5B-LUbNU.js` (~316 KB) | **The entire app**: UI, VRChat API client, feature logic, OSC message building, chatbox config schema, avatar-DB search, lyrics/translate/weather calls |
| `assets/web-*.js` (4 files) | Vendor/runtime chunks |
| `cordova.js`, `cordova_plugins.js`, `native-bridge.js` | Capacitor bridge glue |
| `capacitor.config.json` | `appId`/`appName`, `androidScheme:https`, `allowMixedContent:true` |
| `capacitor.plugins.json` | Registers the 4 stock Capacitor plugins |
| `assets/dexopt/baseline.prof*` | ART baseline profile (perf only) |

### 3.2 Native classes (`classes.dex`, package `com.deize.vrcnexus.quest`)
| Class | Role |
|---|---|
| `MainActivity` | Capacitor `BridgeActivity`; registers the 6 custom plugins |
| `OscPlugin` + `OscUtil` (+`OscUtil$Msg`) | OSC send/receive, OSC encode/decode, OSCQuery discovery, starts Chatbox/Script services |
| `ChatboxService` | Foreground service; the **native chatbox compositor + sender loop** |
| `ScriptService` | Foreground service; the **avatar-OSC automation/macro engine** |
| `NowPlayingPlugin` (+`$Snapshot`,`$LyricLine`) | MediaSession now-playing + **LRCLIB synced lyrics** |
| `NexusNotificationListener` | `NotificationListenerService` (media-session source) |
| `SpotifyBroadcastListener` | `BroadcastReceiver` for Spotify's metadata/playback broadcasts |
| `VrcCachePlugin` (+`$Doc`,`$Hit`,`$Player`) | **VRChat log-file reader + instance roster scanner** |
| `VrcUploadPlugin` | Authenticated multipart image upload to the VRChat API |
| `UpdaterPlugin` | Download APK + launch package installer (self-update) |
| `BatteryAlertPlugin` + `BatteryAlertService` | Headset low-battery watcher + alert |
| `R` | Generated resource IDs |

---

## 4. Native systems, in depth

### 4.1 OSC transport — `OscUtil`
A minimal hand-rolled OSC 1.0 codec (no library):
- `oscString()` — null-terminated, 4-byte-aligned OSC strings.
- Builders: `msgFloat(addr,f)` `,f`; `msgInt(addr,i)` `,i`; `msgBool(addr,b)`
  `,T`/`,F`; **`msgChatbox(text)` → address `/chatbox/input`, typetag `,sTF`**
  (string + **True = send immediately/bypass keyboard** + **False = no
  notification sound**), text `truncateUtf16`-capped to **144** UTF-16 units.
- `decodeFirst(bytes,len)` — decodes an inbound packet's address + first arg
  (handles `f i s T F d`). Used to read VRChat's OSC output.
- `sendAll(sock, bytes, hosts[], port)` — fan a packet to multiple hosts.

> VRC-A parity note: this is exactly what `VrcaOsc` already does. Same 144 cap,
> same `,sTF` chatbox typetag. Nothing to learn here except confirmation our
> wire format is correct.

### 4.2 `OscPlugin` — the bridge the JS drives
Plugin methods (JS-callable):
- `send({host,port,data})` — base64 OSC packet out (default port **9000**).
- `startListen({port})` / `stopListen()` — **bind a UDP receive socket on port
  9001** (VRChat's OSC OUTPUT port) and stream every packet up to JS as base64
  via a `notifyListeners("osc", …)` event. Also, natively, it maintains a live
  `params` map:
  - `/avatar/change` → clears `params` and kicks an OSCQuery re-scan.
  - `/avatar/parameters/<name>` → stores the latest value in `params` (keyed by
    `<name>`).
- `discoverParams()` — **OSCQuery discovery**: uses Android `NsdManager` (mDNS)
  to find VRChat's advertised OSCQuery HTTP service, `httpGet`s the JSON tree,
  and `walkOscQueryJson` recursively collects every `FULL_PATH` under
  `/avatar/parameters/*` that has a `TYPE` — i.e. it enumerates the current
  avatar's full parameter list without VRChat sending anything.
- `noteParams({names})` — JS can seed known param names.
- `onAvatarChanged()` auto-rescans with backoff (`4s,4s,6s,8s,10s`) after an
  avatar swap (params churn until VRChat republishes).
- `localIp()` — first non-loopback IPv4 (for the "send to this IP" UI).
- `chatboxStart/Stop/Update` and `scriptStart/Stop` — configure + launch the two
  foreground services below.

So NEXUS has a **live model of the local avatar's OSC parameters**, fed by both
push (OSC-in on 9001) and pull (OSCQuery). That is the enabling primitive for its
"mute / AFK / movement / any-param" chatbox lines and its script engine's
auto-detection.

### 4.3 `ChatboxService` — native chatbox compositor
A foreground service (channel `nexus_osc_bg`) holding a `PARTIAL_WAKE_LOCK` + a
WiFi lock, running a 250 ms tick loop that composes a chatbox string from a
JSON config and sends `/chatbox/input` to all `hosts:port` at most every
`sendMs` (default 1500, floored to 400) — with a fast path for lyrics (re-send
when the lyric line changes, min 600 ms apart).

**It appends the exact same invisible-background egg VRC-A uses**:
`EGG_SUFFIX = ""` (U+0003 + U+001F) — confirming our "minimal
background / skinny bubble" trick is the shared community approach.

Chatbox **line types** it can compose (`lineFor`):
| id | Output | Source |
|---|---|---|
| `lyrics` | current synced lyric line | LRCLIB (NowPlaying) |
| `media` | `Title — Artist (m:ss/m:ss)` | MediaSession/Spotify |
| `time` | 12/24h, optional seconds, optional `My time:` prefix | device clock |
| `date` | `EEE, MMM d` | device clock |
| `uptime` | `Up 1h 23m` | service start time |
| `battery` | `Battery 84% ⚡` | `BATTERY_CHANGED` |
| `mic` | `🔇 Muted` / `🎤 Mic on` | OSC param `MuteSelf` |
| `afk` | `💤 AFK` | OSC param `AFK` |
| `movement` | `🪑 Sitting` / `🧍 Standing` / `🏃 1.4 m/s` | OSC params `Seated`, `VelocityX`, `VelocityZ` |
| `vrcparam` | `Label: value` for **any** avatar param | OSC params map |
| `personal` | cycling free-text statuses (interval) | config |
| `weather` | text (JS fills from open-meteo) | JS |
| `players` / `instance` | instance size / world+instance | JS (from log roster) |
| `translate` | a line translated to another language | JS (Google Translate) |

### 4.4 `ScriptService` — avatar-OSC automation / macro engine
A foreground service (channel `nexus_script_bg`) that runs a JSON "program" of
blocks against the avatar's OSC parameters — a full little scripting language:
| block | effect |
|---|---|
| `set` | set `/avatar/parameters/<p>` to on/off/int/float |
| `wait` | sleep N ms |
| `chatbox` | send `/chatbox/input` text inline |
| `random` | set a param to a random value in `[min,max]` |
| `input` | pulse a VRChat action `/input/<Action>` (e.g. Jump), 120 ms |
| `height` | set/`sweep` `/avatar/eyeheight` |
| `ramp` | linear `sweep` a param `from→to` over N s (optional ping-pong, ~20 steps/s) |
| `hue` / `emission` | **auto-detect** hue/emission-ish params by name (`hue,tint,rainbow,chroma,…` / `emiss,glow,bloom,…`, skipping built-ins) and sweep/set them |
| `loop` | repeat child blocks N times (0 = forever) |

This is avatar *control*, not just chatbox — colour cycling, height sweeps, input
macros, timed parameter sequences. VRC-A has nothing in this category.

### 4.5 `NowPlayingPlugin` — media + **synced lyrics**
- Media snapshot from three sources, in order: active `MediaSession`s (via
  `MediaSessionManager.getActiveSessions` bound to the notification listener) →
  media-session tokens pulled off active notifications → the Spotify broadcast
  fallback. Picks the "best" controller (playing > known music pkg > any).
  Live position extrapolated from `PlaybackState` + `elapsedRealtime`.
- **Lyrics via LRCLIB** (`https://lrclib.net/api/get` then `/api/search`,
  free/no-key): fetches `syncedLyrics` (LRC `[mm:ss.xx]` format), parses to a
  sorted `(timestampMs, text)` list, and **binary-searches the current line by
  playback position** — updated in the chatbox as fast as every 600 ms. Caches
  per track; retries failed lookups after an 8 s cooldown.

### 4.6 `SpotifyBroadcastListener`
`BroadcastReceiver` for Spotify's (legacy but still-emitted) broadcasts
`com.spotify.music.metadatachanged` / `playbackstatechanged` — reads
`track/artist/length/playing/playbackPosition`. A no-notification-access
fallback source; data considered stale after 20 s.

### 4.7 `VrcCachePlugin` — VRChat log reader + instance roster
The headline native system. VRChat writes a rolling text **output log** while
running (Quest included, when Logging is set to FULL). NEXUS reads it to
reconstruct **who is in your current instance** — data the VRChat API does *not*
expose.

Access strategy (tries all, in order):
1. Direct file read of `Android/data/<vrcpkg>/files`, `/sdcard/Documents/Logs`,
   `/sdcard/Documents/VRChat`, `/sdcard/VRChat/Logs` (needs `MANAGE_EXTERNAL_
   STORAGE` / All-files access). VRChat Quest package is
   `com.vrchat.VRChatAndroid` (or legacy `com.vrchat.oculus.quest`).
2. **SAF** `ACTION_OPEN_DOCUMENT_TREE` (initial URI `primary:Documents/Logs`) +
   `takePersistableUriPermission` — a folder the user grants once, re-resolved
   from `getPersistedUriPermissions`.
3. ADB fallback: it literally hands the user the command
   `adb shell appops set <pkg> MANAGE_EXTERNAL_STORAGE allow` and checks
   `Settings.Global adb_enabled`.

It tails the newest ~6 log files (last N MB each), then scans lines with these
**regexes** (the important part — the log grammar):
```
world+instance : Joining\s+(wrld_…{36}):(\S+)
player join     : OnPlayerJoined\s+(.+?)(?:\s+\((usr_…{36})\))?\s*$
player leave    : OnPlayerLeft\s+(.+?)(?:\s+\((usr_…{36})\))?\s*$
avatar switch   : Switching\s+(.+?)\s+to avatar\s+(.+?)\s*$
avatar unpack   : Unpacking Avatar\s+\((.+?)\s+by\s+(.+?)\)\s*$
bare id scan    : (avtr|wrld|usr)_…{36}
```
It joins these into a **per-instance roster**: for each present player it resolves
`{ name, usr_id, avatarName, avatarCreator, avatar_id }` by correlating
`OnPlayerJoined`, `Switching … to avatar …`, `Unpacking Avatar (… by …)`, and any
`usr_`/`avtr_` co-occurrence. A `Joining wrld_…` line resets the roster (new
instance). `OnPlayerLeft` removes people. Two JS entry points: `scan` (raw id
frequency counts by type) and `instanceUsers` (the assembled roster + world +
instance).

This is genuinely powerful: **instance co-occupants (including non-friends) and
their currently-worn avatars + avatar authors + avatar IDs** — impossible via the
VRChat API. The scanned `avtr_`/`usr_` IDs then feed the avatar-DB search (§7).

### 4.8 `VrcUploadPlugin`
Authenticated multipart POST of a base64 PNG to a VRChat API URL (the JS supplies
the URL, `Cookie`, `User-Agent`, and a `tag` field). Used with `/api/1/file/…`
for VRChat image assets (e.g. a rendered-image upload path). Confirms the JS
holds a logged-in VRChat session cookie.

### 4.9 `UpdaterPlugin`
Downloads an APK to `cacheDir/updates/update.apk` and fires
`ACTION_VIEW` via `FileProvider` (`application/vnd.android.package-archive`) to
launch the system installer. Update manifest source:
`raw.githubusercontent.com/XnotCykoX/vrc-nexus-quest/main/update.json`.
(VRC-A's forced-update + directed-release system is far more capable.)

### 4.10 `BatteryAlertPlugin` / `BatteryAlertService`
Foreground service watching `BATTERY_CHANGED`; fires a high-priority
"Headset battery low" notification when level ≤ threshold (default 20%, clamped
1–90), with +5% hysteresis and reset-on-charging. On Quest this = **headset**
battery.

---

## 5. VRChat log line catalog (Quest = same format as PC)

VRChat's output log format is identical across PC and Quest/Android (Unity
`Debug.Log`), so PC log knowledge fills the gaps NEXUS didn't bother to parse.
Requires **Settings → Debug → Logging = FULL** in VRChat. Line prefix is roughly:
`2026.07.28 12:00:00 Log        -  [Behaviour] …`.

NEXUS parses only the six rows marked ✅ below. The rest are the **additional
signal VRC-A could extract** from the same file (all Quest-available):

| Log line (substring) | Exposes | NEXUS? |
|---|---|---|
| `[Behaviour] Joining wrld_<id>:<instanceId>~<tags>` | world id + full instance string incl. access tags & nonce | ✅ (id+instance) |
| `[Behaviour] Joining or Creating Room: <World Name>` | human world **name** | ❌ |
| `[Behaviour] Entering Room: <World Name>` | world name (confirm) | ❌ |
| `[Behaviour] OnPlayerJoined <name> (usr_<id>)` | co-occupant join | ✅ |
| `[Behaviour] OnPlayerLeft <name> (usr_<id>)` | co-occupant leave | ✅ |
| `[Behaviour] Switching <name> to avatar <avatarName>` | their worn avatar name | ✅ |
| `[Behaviour] Unpacking Avatar (<name> by <author>)` | avatar author | ✅ |
| bare `usr_`/`wrld_`/`avtr_` UUIDs anywhere | id harvesting | ✅ |
| `[Behaviour] OnLeftRoom` / `Successfully left room` | you left the instance | ❌ |
| `[Behaviour] Received Notification:<…>` (invite/requestInvite/friendRequest) | incoming VRChat notifications | ❌ |
| `[Video Playback] / [Video Playback] Attempting to resolve URL '<url>'` | the video/URL currently playing in-world | ❌ |
| `[String Download] / [Image Download] Attempting to load … '<url>'` | world string/image loads | ❌ |
| `[Behaviour] Destination fetching…` / `[Behaviour] Portal…` | portal drops/joins | ❌ |
| `[USharpVideo]/[ProTV]` etc. | world-specific player state | ❌ |
| `Took screenshot to: <path>` (VRC camera) | photo taken | ❌ |
| `[API]`/`[Network]` request lines | connectivity/latency, session | ❌ |

**Instance access-type tags** inside the `Joining wrld_…:<instanceId>` string
(same grammar VRC-A already parses from a location string):
`~region(us|use|usw|eu|jp)`, `~private(usr_…)` (invite/invite+),
`~friends(usr_…)` (friends), `~hidden(usr_…)` (friends+),
`~group(grp_…)~groupAccessType(members|plus|public)`, `~canRequestInvite`,
and `~nonce(<token>)` (the join token). Public instances have no owner tag.

**What logs do NOT contain** (important negatives): your own or others' **chatbox
text/typing**, mic mute state, trust rank, voice activity, or OSC values. Those
come from OSC/OSCQuery (mute/AFK/movement) or the API (rank), not the log. See §6.

---

## 6. OSC & OSCQuery: what's exposed (and the typing answer)

VRChat's OSC surface, and precisely what a companion app can and can't observe:

**Sent TO VRChat (`:9000`, we already do all of this):**
- `/chatbox/input` `,sTF` (text, sendNow, sound) — the chatbox.
- `/chatbox/typing` `,T/,F` — the typing indicator. **This is something YOU
  send**, not something VRChat reports; NEXUS sends it too (`Ys("/chatbox/typing",
  [{type:"b",value:!!e}])`). There is no inbound equivalent.
- `/avatar/parameters/<p>`, `/input/<Action>`, `/avatar/eyeheight` — avatar/input
  control (NEXUS's script engine; VRC-A doesn't use these).

**Received FROM VRChat (`:9001` + OSCQuery):**
- `/avatar/change` — avatar swapped (id in the value).
- `/avatar/parameters/<p>` — every avatar parameter VRChat publishes, including
  the built-ins: `MuteSelf`, `AFK`, `VelocityX/Y/Z`, `Grounded`, `Seated`,
  `Upright`, `InStation`, `Voice`, `Viseme`, `GestureLeft/Right`, `TrackingType`,
  `IsLocal`, `AngularY`, `Earmuffs`, `ScaleFactor`, etc.
- **OSCQuery** advertises VRChat's OSC endpoint over mDNS (`_oscjson._tcp` /
  `_osc._udp`) and serves a JSON tree of every available parameter path + type —
  so you can enumerate the current avatar's params without waiting for pushes.

**Answering the two questions directly:**

1. **In-game chatbox typing/sends — NOT observable.** There is no log line and no
   OSC output for the text a user types in VRChat's own keyboard chatbox, nor for
   "the user is typing in-game". So the requested feature — *pause our automated
   chatbox for 20 s when the user types/sends in VRChat's own chatbox* — **cannot
   be built**; there's no signal to trigger on. NEXUS doesn't attempt it. Our
   existing manual-send hold (`MANUAL_HOLD_MS`, keyed on OUR app's manual send) is
   the only version of that behaviour that's possible. An in-game keyboard
   message and our OSC output simply fight over the chatbox with no arbitration —
   inherent to every OSC chatbox tool.

2. **"Did the chatbox land?" — OSCQuery gives a real liveness signal, not an
   echo.** VRChat only advertises its OSCQuery service (and only binds/accepts
   OSC) while it's running with OSC enabled. So discovering that service =
   "VRChat is up, OSC is on, our chatbox WILL be received" — strictly better than
   pinging an IP (which answers even when VRChat is closed or OSC is off). But
   `/chatbox/input` is never echoed back, so you still can't confirm a *specific
   message* rendered. The genuinely new capability OSC-in unlocks isn't send
   confirmation — it's **reading live avatar state** (mute, AFK, movement, any
   param) to drive chatbox lines and UI.

**Quest/mobile caveat (critical):** OSC-in and OSCQuery are trivial for NEXUS
because it runs **on the Quest** (`127.0.0.1`). VRChat sends its OSC output to a
single configured target (localhost by default) and discovers OSCQuery peers on
the LAN. For a VRC-A **phone companion** to receive a Quest's OSC output, VRC-A
would have to advertise its own OSCQuery service so the Quest's VRChat discovers
it and sends there — plausible on the same LAN but unproven and fiddly. The clean
path is: **these features light up fully when VRC-A itself runs on the Quest**
(it's an Android APK — it can). See §10 for how to scope each.

---

## 7. VRChat API + third-party services used by the JS

**VRChat API (`api.vrchat.cloud/api/1`, cookie session held in JS):** `auth/user`
(login/current user), `auth/twofactorauth/{totp,otp,emailotp}/verify` (2FA),
`auth/user/friends`, `auth/user/notifications`, `users/{id}`,
`users/{id}/mutualFriends`, `worlds/{id}`, `worlds/favorites`, `instances/{id}`,
`avatars/{id}`, `avatars/favorites`, `invite/{id}`,
`invite/myself/to/{location}` (self-invite), `file/image` + `file/{id}/{ver}/file`
(image up/download). So NEXUS is a fair VRChat companion: login+2FA, friends,
presence, world/instance/avatar info, favourites, invites & self-invite.

**Avatar databases (from the log-scanned `avtr_`/`usr_` IDs):**
`api.avtrdb.com/v2/avatar/search`, `avatarwbvrcxsearch.worldbalancer.com`,
`avtr.icu`, `avtr.zuxi.dev`, `vrcavatarsearch.nekosunevr.co.uk`, `vrcx.avtr.zip`,
`vrcx.vrcdb.com`, `paw-api.amelia.fun`, routed through a Cloudflare Worker CORS
proxy (`vrc-nexus-community-proxy.deizeljkite.workers.dev`). → an **avatar
finder/database** feature.

**Media/misc:** LRCLIB (lyrics), Spotify Web API OAuth
(`accounts.spotify.com/authorize` + `api.spotify.com/v1/me/player/currently-
playing`, a proper now-playing beyond the broadcast), Last.fm
(`ws.audioscrobbler.com/2.0/`, scrobble/now-playing), open-meteo forecast +
geocoding (weather line), Google Translate unofficial endpoint (translate line).

---

## 8. Feature matrix — NEXUS vs VRC-A

| Capability | NEXUS | VRC-A |
|---|---|---|
| OSC chatbox send (`,sTF`, 144 cap, egg suffix) | ✅ | ✅ |
| Pinned / cycling text | ✅ (`personal`) | ✅ (richer: sub-lines, presets, drag editor) |
| NowPlaying in chatbox | ✅ | ✅ (much richer: ad detection, title cleaning, YT/YTM handling, progress presets) |
| **Synced lyrics in chatbox (LRCLIB)** | ✅ | ❌ |
| Time / date / uptime lines | ✅ | time only |
| **Battery line + low-battery alert** | ✅ | ❌ |
| Weather line | ✅ | ❌ |
| Translate line | ✅ | ❌ |
| **Live avatar-param lines (mute/AFK/movement/any)** | ✅ | ❌ (no OSC-in) |
| **OSC-in (:9001) + OSCQuery** | ✅ | ❌ |
| **Avatar-OSC automation/macros** (set/ramp/hue/input/height/loop) | ✅ | ❌ |
| **Log-based instance roster + avatar scan** | ✅ | ❌ |
| **Avatar-database search** | ✅ | ❌ |
| VRChat login + 2FA, friends, presence, invites, self-invite | ✅ | ✅ (plus WebSocket pipeline, notifications, ban system) |
| Discord Rich Presence | ❌ | ✅ |
| Admin/moderation, Firestore sync, directed releases | ❌ | ✅ |
| Friend-activity notifications, group events, announcements | ❌ | ✅ |
| Background survival (OEM killers, watchdog, restore) | minimal | ✅ (extensive) |
| In-app rich content / update system | basic | ✅ (rich engine) |

VRC-A is the more mature product on the social/admin/RPC/background axes; NEXUS
wins purely on the **local-device** features (OSC-in, logs, lyrics, scripting)
that come free from running on the Quest.

---

## 9. What VRC-A is missing — gap analysis

Everything below is technically Quest-compatible; the ⚠️ marks features whose
*full* value needs VRC-A running **on the Quest** (or an OSCQuery-advertise hop
from a phone). Ordered by value ÷ effort.

### Tier 1 — high value, low risk, works from a phone today
1. **Synced lyrics as a NowPlaying option** (LRCLIB). Free, no key, no new
   permission, pure additive. Fetch `syncedLyrics`, parse LRC, binary-search by
   `positionMs`, feed one line into the chatbox at the current music-refresh
   cadence. Reuses our existing NowPlaying position tracking. This is the single
   best borrow. Gate behind a "Show lyrics" toggle; fall back to the normal music
   line when no synced lyrics exist. Respect the 144/142 budget.
2. **Weather + date + uptime chatbox lines.** Trivial. Weather = open-meteo
   (free, no key; geocode a city the user types, no location permission needed).
   Date/uptime are local. Slot them into the existing cycle/token system —
   arguably just new `{weather}`/`{date}`/`{uptime}` dynamic tokens alongside our
   `{time}/{song}/{world}/{players}`.
3. **Headset/phone battery**: a `{battery}` token + optional low-battery
   notification. On Quest this reports the headset; on a phone companion it's the
   phone (less useful) — so gate the line's usefulness on where it runs, but it's
   cheap.

### Tier 2 — high value, needs OSC-in (best on-Quest ⚠️)
4. **OSC-in (:9001) + OSCQuery param model.** The enabling primitive. Add a UDP
   receive socket + `NsdManager` OSCQuery discovery (needs
   `CHANGE_WIFI_MULTICAST_STATE` + a multicast lock) to maintain a live
   avatar-parameter map. Unlocks:
   - **`{mute}` / `{afk}` / `{movement}` / `{vrcparam:<name>}` chatbox lines** —
     live mute, AFK, sitting/standing/velocity, or any avatar param, exactly like
     NEXUS. Genuinely new class of chatbox content for us.
   - **A real "VRChat is live + OSC on" connection signal** for the send gate /
     Home connection card (OSCQuery service present) — much better than a ping.
   - ⚠️ On a phone companion this needs VRC-A to advertise an OSCQuery service so
     the Quest's VRChat targets it; ship it enabled when running on-device and
     mark it experimental for phone→Quest.
5. **Avatar-OSC automation ("OSC macros").** A small block engine (set / wait /
   ramp / random / input-pulse / eyeheight / hue-emission auto-detect / loop) that
   sends `/avatar/parameters/*`, `/input/*`, `/avatar/eyeheight`. Distinct product
   surface from chatbox; big for power users. Medium effort. Reuses the OSCQuery
   param list from #4 for the hue/emission auto-detect. On-Quest ⚠️ (or LAN send
   to the Quest's :9000, which we already do for chatbox — so the SEND direction
   works from a phone; only the auto-detect READ needs OSCQuery).

### Tier 3 — high value but permission-heavy (on-Quest ⚠️)
6. **Log-based instance roster + avatar scan.** Read VRChat's FULL log to show
   *who is in your instance right now* (incl. non-friends) with their worn
   avatars, authors, and `avtr_` IDs — data the API can't give. Requires the log
   files to be on the **same device** (so this is on-Quest only, or a
   phone-with-share-hack), plus All-files access (`MANAGE_EXTERNAL_STORAGE`) or a
   SAF folder grant on `Documents/Logs`, and the user setting VRChat Logging =
   FULL. Use the §5 grammar. Higher friction, but a marquee feature. Pair with:
7. **Avatar-database lookup** of scanned/seen `avtr_` IDs (avtrdb et al.) — "what
   avatar is that / find a public copy". Only worthwhile alongside #6.

### Tier 4 — nice-to-have
8. **Translate a chatbox line** to another language (unofficial Google Translate
   endpoint — note it's unofficial/rate-limited; LibreTranslate is a cleaner
   dependency). 
9. **Last.fm scrobble / now-playing** source, and **Spotify Web API OAuth**
   now-playing as an alternative to notification/broadcast reading (helps when
   notification access is denied).

### Not worth copying
- NEXUS's updater/self-update (ours is better), its foreground-service and
  background model (ours is far more robust), its VRChat login/presence (we have
  a superior WebSocket pipeline), and anything Discord/admin (NEXUS has none).

---

## 10. Recommended concrete changes for VRC-A

1. **Ship synced lyrics now** (Tier 1.1) — a `music_lyrics` local pref +
   `LrcLibLyrics` helper (fetch/parse/binary-search) wired into
   `buildNowPlayingLines()` as an alternate line when enabled and available. No
   Firestore, no new permission, Quest- and phone-compatible. Throwaway-test the
   LRC parser + line-at-position logic per the no-permanent-tests policy.
2. **Add `{weather}`, `{date}`, `{uptime}`, `{battery}` dynamic tokens** to the
   existing token resolver (`resolveTokens`) so they work in Pinned + Cycle for
   free. Weather via open-meteo with a user-entered city (no location permission).
3. **Prototype OSC-in + OSCQuery** (Tier 2.4) as an opt-in "Avatar status"
   feature: new receive socket + NsdManager discovery + a live param map, feeding
   `{mute}`/`{afk}`/`{movement}`/`{param:Name}` tokens and a stronger "VRChat OSC
   live" indicator. Ship enabled when VRC-A runs on-device (Quest); flag
   phone→Quest as experimental (requires OSCQuery advertise). Add
   `CHANGE_WIFI_MULTICAST_STATE`.
4. **Consider an "OSC macros" tab** (Tier 2.5) later — the SEND direction already
   works over our existing LAN chatbox path; only param auto-detect needs #3.
5. **Log-reader is a bigger bet** (Tier 3.6/3.7): only pursue if we're willing to
   ask for All-files/SAF access and target the on-Quest install. If we do, reuse
   the §5 grammar verbatim and surface an "Instance roster" view + avatar lookup.
6. **Update `docs/ui-revamp.md` / CLAUDE.md** as any of these land, per repo
   convention.

**One-line strategic read:** VRC-A already beats NEXUS everywhere that needs a
backend, Discord, or robustness. The only real gaps are **local-device** tricks
NEXUS gets free by living on the headset — of those, **synced lyrics** and a few
**extra chatbox tokens** are pure wins to add immediately, **OSC-in/OSCQuery**
avatar-state lines are the highest-leverage new capability (best realised by
running VRC-A on the Quest), and the **log-based instance roster** is the marquee
feature to weigh against its permission friction. The user's specific
"pause-on-in-game-typing" idea is **not buildable** — no such signal exists.

---

## 11. ADDENDUM — complete VRChat API client + full feature map

**Correction / honesty note:** §7's first pass under-counted the JS. The native
code (§4) was decompiled and read in full, but the initial JS scan only pulled a
partial endpoint list, so it **missed a large part of NEXUS's feature surface** —
group moderation, an auto-invite growth tool, user/world/avatar search, friend
requests, notification accept/decline, invite-message slots, VRChat "prints", and
instance-close. This addendum is the complete client, extracted from the app's
actual method+path table and its Vue component list. NEXUS is best described not
as a chatbox tool but as a **full VRChat companion client + group tooling**.

### 11.1 Complete VRChat API client (every call the app can make)
**Auth / self**
- `GET /auth/user` — login / current user
- `POST /auth/twofactorauth/{totp,otp,emailotp}/verify` — 2FA
- `GET /auth/user/friends?offline=&n=&offset=` — friends (paginated, both passes)
- `GET /auth/user/notifications?type=all&n=` — notifications
- `PUT /auth/user/notifications/{id}/accept` — **accept** a notification (friend req / invite)
- `PUT /auth/user/notifications/{id}/hide` — **decline / hide** a notification

**Users / social**
- `GET /users/{id}` — profile / presence
- `GET /users?search=&n=` — **user search**
- `GET /users/{id}/groups` — a user's groups
- `GET /users/{id}/mutualFriends`, `/users/{id}/mutuals/friends` — mutual friends
- `POST /user/{id}/friendRequest` — **send friend request**
- `GET /message/{id}/{slot}`, `PUT /message/{id}/{slot}/…` — **invite / response message slots** (custom invite messages)
- `GET /prints/user/{id}`, `DELETE /prints/{id}` — **VRChat "prints"** (photos): list / delete

**Worlds**
- `GET /worlds/{id}` — world info
- `GET /worlds/favorites?n=&offset=` — favorite worlds
- `GET /worlds?search=&sort=relevance` — **world search**
- `GET /worlds?userId=&releaseStatus=public` — a user's public worlds

**Avatars**
- `GET /avatars/{id}` — avatar info
- `PUT /avatars/{id}/select` — **wear / switch avatar**
- `GET /avatars/favorites?n=&offset=` — favorite avatars
- `GET /avatars?user=me&releaseStatus=all&sort=_created_at` — **your own uploaded avatars**
- Avatar-DB search across `avtrdb / nsvr / paw / vrcdb / vrcwb / prismic` via a Cloudflare proxy

**Instances / invites**
- `GET /instances/{id}` — instance info
- `DELETE /instances/{id}?hardClose=true|false` — **close / hard-close an instance**
- `POST /invite/{userId}` — invite a user to your instance
- `POST /invite/myself/to/{world}:{instance}` — self-invite

**Groups — moderation + growth (the big miss)**
- `GET /groups/{id}` — group info
- `GET /groups/{id}/members?n=&offset=` — members list
- `DELETE /groups/{id}/members/{userId}` — **kick member**
- `GET /groups/{id}/roles` — roles
- `GET /groups/{id}/bans?n=&offset=`, `POST /groups/{id}/bans`, `DELETE /groups/{id}/bans/{userId}` — **ban / unban**
- `GET /groups/{id}/auditLogs?n=&offset=` — **audit logs** (moderation history)
- `GET /groups/{id}/instances` — group instances
- `GET /groups/{id}/posts?n=`, `POST /groups/{id}/posts` — read / **create** group posts / announcements
- `POST /groups/{id}/invites {userId, confirmOverrideBlock}` — **invite a user to the group** (the auto-invite primitive)

**Files**
- `GET /files?tag=&n=` — list files by tag
- `DELETE /file/{id}` — delete a file
- `POST /file/image` (native `VrcUpload`) — upload an image

### 11.2 Auto-invite to group (the feature the teardown missed)
Instance Tools → **Scan instance** (reads the roster from VRChat's log, §4.7) →
**Invite all to group** loops `POST /groups/{id}/invites` over everyone in the
instance, spaced by a user-set **"Gap between invites"** to respect the rate
limit. An **"Auto-invite new joiners"** mode keeps the log scan running and
invites people as they enter. Pure combination of the log roster + the group
invite endpoint — a group-growth / recruiting tool.

### 11.3 Full feature / tab map (Vue components)
Home · VRChat · **Users** (search + `UserModal`) · **Players** (instance roster
UI over `CacheReader`) · **Alerts** (notifications + accept/decline) ·
**Moderation** (group members/roles/bans/audit/kick/close-instance) ·
**InviteTools** (auto-invite) · **Scripts** (OSC avatar automation, §4.4) ·
**CacheReader** (log reader, §4.7) · **GroupCalendar** · **MutualNetwork**
(mutual-friends graph) · **RecentWorlds** + `WorldModal` (world search/favorites) ·
**VrcInventory** (your avatars/worlds) · **Translator** · **SymbolPicker**
(emoji/symbols into chatbox) · **MagicChatbox** (the chatbox composer) ·
Community · Developer · DiscordPrompt · Settings · Help. Plus ~40 language packs
and ~10 themes (Ember/Emerald/Midnight/Rose/Slate/…).

### 11.4 Gap-analysis additions (mobile + Quest compatible, VRC-A lacks)
New candidates surfaced by the corrected pass, on top of §9:
- ★ **Auto-invite to group** (log roster → `POST /groups/{id}/invites`, rate-gapped
  + auto-invite-new-joiners). Standout for group owners/recruiters; headset-side
  (needs the roster), API action works from any device. Ties directly into our
  planned log reader.
- **Group moderation suite** — members / roles / bans / kick / audit logs /
  close-instance / post announcements. A mobile group-mod tool; all API, works on
  a phone. Sizable but self-contained.
- **User / world / avatar search + profiles** (`/users?search=`, `/worlds?search=`,
  own avatars, favorites) — general companion browsing VRC-A doesn't do.
- **Notification actions** — accept/decline friend requests + invites from the app
  (`/notifications/{id}/accept|hide`). Small, high-utility.
- **Invite tooling** — invite a user to your instance, self-invite (VRC-A has
  self-invite on the admin side), custom invite-message slots.
- **Mutual-friends network** graph.
- **Prints** — view/manage VRChat photos.

Priority read: **auto-invite + the group-moderation suite** are the genuinely
differentiated adds here (nobody positions a *mobile* group-mod + recruiting tool),
and both lean on the same log-reader/account work already planned. Search +
notification actions are cheap quality-of-life. The rest are optional breadth.
