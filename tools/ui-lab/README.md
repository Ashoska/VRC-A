# UI Lab

Runs the REAL app UI on the JVM (Robolectric native graphics), drives it like a user and saves
PNGs of what's on screen, popups and menus included. No APK, no device.

    tools/ui-lab/ui.sh shot admin                                   # Home → ui-shots/admin-home.png
    tools/ui-lab/ui.sh run headset "preset roster; shot roster"      # commands, one-shot
    tools/ui-lab/ui.sh run admin --script tools/ui-lab/scripts/tour.txt
    tools/ui-lab/ui.sh serve headset &                              # LIVE: app keeps running…
    tools/ui-lab/uictl.sh "tap Settings" "shot settings"             # …each command ~0.4 s
    tools/ui-lab/uictl.sh quit
    tools/ui-lab/sheet.py ui-shots/sheet.png ui-shots/a.png ui-shots/b.png   # contact sheet

Options: `--size phone|small|tablet|headset` (default: phone for admin/public, the Quest panel
1024x640dp for headset), `--tall` (phone sizes only — 3x height: the whole scrolling page in one
shot; refused for headset, whose panel is fixed: scroll with `scrollto` like the device does),
`--qualifiers Q`, `--settle MS`, `--firestore`, `--no-vrchat`. A one-shot run is ~15-25 s
including recompiling edited code; in live mode a command is ~0.4 s (code edits need a restart).

## Commands (`UiLabDriver.kt`)

| | |
|---|---|
| `shot <name>` | PNG of the whole screen, dialogs and menus drawn over it (dimmed) |
| `tap <text> [#n]` / `long <text>` | click / long-press the n-th element whose text or icon description matches (or the switch beside that label). Runs the element's own action, so it reaches it even when something covers it; use `touch` to tap like a finger |
| `touch <where> [hold MS]` / `press <where>` | a REAL tap / long press (700 ms): pointer events go to the window under the point (a dialog or open menu takes every touch, so a touch outside it closes it), then Compose's own hit testing picks the element. Says what it landed on, with `(!)` when something else covers the element, nothing there takes a touch, or a dialog on top got it |
| `drag <where> to <where> [hold MS] [over MS] [stay MS]` | a real drag, a move every frame (`by dx,dy` in dp instead of a target; `->` instead of `to` when a label contains " to "). `hold` first for long-press drags (the Chatbox editor: `drag 1 to 3 hold 600`), `stay` rests before lifting (its 2 s hover-to-arm: `stay 2200`). Sliders report their new value |
| `swipe up\|down\|left\|right [on <where>] [dist DP] [over MS]` | a quick flick the finger's way (`swipe up` scrolls a list down; flings like a device), from the element, else the middle of the biggest scroll area |
| `hover <where>` / `hover off` | the headset pointer resting on something: hover highlights and anything else that reacts to hovering |
| `targets [minDp] [name]` | every element that can be tapped right now: covered at its centre by another one, tiny (both sides < 32 dp), or small (a side < 48 dp; Compose widens small ones to 48 dp where nothing else is near, so worth a look rather than certain misses). With a name, also `<name>.png` with them boxed (red covered, orange tiny, yellow small) |
| `type <value>` / `typein <field> \| <value>` | set the focused (or first) text field / the field whose label matches |
| `scroll down\|up [n]` / `scrollto <text>` | scroll the main area / until the element is visible |
| `back` | close the top dialog (BACK key to its window), else system back |
| `wait <ms>` | let the app run (app time, 16 ms frames) |
| `tree` | what's on screen and what can be tapped, typed or scrolled |
| `set <prop> <value>` / `get <prop>` | force any state: a view-model field (`set warned true`) or `Object.prop` (`set VrchatPipelineState.authDead true`); a field that's currently null takes a typed literal (`12L`, `12`, `1.5f`, `true`) |
| `preset <name>` | ready-made states, see below |
| `show <name> [args]` | screens normally behind a gate, see below |
| `root screen\|app` | draw the main screen (default) or the full app with its boot/ToS/onboarding/update gates |
| `hide` | remove what `show` put up |
| `record start <name> [ms]` … `record stop` | record an animation: a frame every `ms` of app time (default 32) through every `wait` and the settle after each action in between, then `<name>-frames.png` (a timestamped filmstrip, up to 24 frames in a grid: the motion readable as one image) and `<name>.gif` (real speed, loops; built from `<name>-frames/` by `gif.py`, which `ui.sh`/`uictl.sh` run afterwards). E.g. `record start expand; tap Manual Send; wait 300; record stop`. Dialogs fade and scale in/out like on a device (Robolectric skips window animations, so the lab emulates Android's 200 ms dialog animation); everything Compose animates is captured |
| `debuglog` | print the app's debug log (Settings > Debug: main-thread stalls + notes, e.g. the boot timeline written by `AppBoot`) |
| `perf start <name> [heavy=N]` … `perf stop` | **lag check**: counts every composable call per frame (Compose's tracing hook) and times each frame's main-thread work. Reports frames with >= N calls (default 1000, a likely Quest stall, with the composables that ran, VRC-A's own first), the longest run of frames each re-running >= 20 VRC-A composables (an animation read in composition instead of while drawing, with what re-ran), and lab frame times. Writes `ui-shots/<name>-perf.txt` (every frame). Start it BEFORE `show` to include the first frame. |

`<where>` = `<text> [#n] [@fx,fy]` (a point inside that element's box as fractions, default its centre:
`touch slider @0.8,0.5` sets a slider to ~80%) | `slider [#n]` (sliders have no text) | `x,y` (dp on screen).

**Toasts:** Robolectric never draws them; the lab queues each like Android (2 s short, 3.5 s long, one at a
time), draws it over everything (dark rounded box above the bottom edge, fading in and out) and names it in
the result of the command that caused it (`... | toast "Not signed in to VRChat"`).

**Presets:** in-world, offline, friends, incident, outage-minor, status-ok, alerts, no-alerts, sending,
idle, warned, banned, auth-dead, logged-out, nowplaying, paused, ad, roster, roster-empty, manual,
cycle (four cycle lines, to drag in the Chatbox editor), owner
(opens the admin panel without the owner account — its data stays empty), osc-params (sample VRChat
avatar params for the dictation Listen trigger).

**Voice to text (headset):** `voice` installs a stand-in English pack (sparse files at the exact sizes
the app checks, so it counts as installed); `voice-listening`, `voice-hearing`, `voice-loading`,
`voice-stopping`, `voice-paused` (by a voice command), `voice-trigger` (by the Listen trigger),
`voice-learning` ("Teach my voice" 2 of 3), `voice-teach-loading` (Teach my voice waiting for the model), `voice-downloading`,
`voice-stopped` and `voice-restored` (the "Android closed VRC-A" note, off / back on) add a state on top, each clearing the last.
The engine itself can't run on the JVM. Expand Manual Send to see the row:
`ui.sh run headset "preset voice-loading; tap Manual Send; shot v"`.

**Shows:** update, update-optional, whatsnew, confirm, confirm-destructive, timezone (dialogs);
boot [mid|error|done|incident|offline|noaccess|signin|vrcdown|live|livefail|demo] [none] (samples), headsetboot [onboarded|full] [discord[=ms]|discordfail[=ms]] (the real boot run; `discord=ms` turns Discord RPC on and simulates it (Connecting from the start, Connected/Failed after ms of app time, default 6000: the real hidden discord.com WebView can't run here); `full` = the whole real start as a returning user: VrcaApp, app built behind the boot screen, fold + reveal into the app), crash, banned [reason], tos, onboarding <step 0-7>, login [nocancel] (full screens).

Everything else is reached the way a user gets there: tap through the tabs, sub-tabs, cards,
chips and buttons (`tree` lists what's tappable).

## Accounts and data

- **VRChat alt:** `tools/ui-lab/vrc-login.sh` logs the alt in. The password is an API credential on the
  environment for `api.vrchat.cloud`, added by the agent proxy, so it's never on this machine.
  `vrc-login.sh <code>` finishes with the emailed code. Cookies stay in git-ignored `ui-shots/.vrc/`.
  Every run is then signed in as the alt with its real presence (`--no-vrchat` skips it);
  `vrc-login.sh status` checks the session.
- **`--firestore`:** the real Firebase project with a fixed lab device id per variant
  (`sha256("uilab:<flavor>")`), so runs never pile up new user docs. Without it, Firebase is offline.
- Presets and toggles start fresh on every run.

Not rendered: the Discord WebView, notifications, OSC, and the live VRChat connection (pipeline
websocket) — use presets for what those would show. Drawing is software rendering, which skips elevation
shadows; the app's are faint (a hardware-rendered roster screen differed by at most 24/255 per pixel), so
this only matters if a design leans on shadows or blur (Robolectric's `HardwareRenderingScreenshot` worked
in a test, ~0.2 s per window, if that's ever needed). No on-screen keyboard (`type` sets the text).

## How it works

`UiLabTest.kt` starts the app (`UiLabApp`: fake or real Firebase, fake AndroidKeyStore, the alt's
session seeded into `VrchatAuthManager`) and hosts `UiLabScenes.Root` in an activity. The driver
finds elements through Compose semantics (every window: dialogs first) and runs their actions.
`shot` draws every window in z-order at its position. Robolectric's choreographer is paused at
16 ms frames so endless animations (spinners) can't lock the clock. Logs of every command with its
time go to `ui-shots/.lab.log`.

The app runs like a headset's frame loop: one 16 ms frame of app time per step, then a layout pass
on every window (Robolectric never draws by itself, and Compose lays out inside draw — without the
pass, scrolling and bring-into-view acted on stale layouts). `delay()` uses the same app clock
(`kotlinx.coroutines.main.delay`), so timed UI (e.g. Manual Send scrolling itself into view after
it expands) behaves as on the device however slow a JVM frame is. `wait` never runs app time faster
than real time. `tap <label>` also reaches the switch beside a label; `back` closes the top dialog.
