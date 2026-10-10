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
| `tap <text> [#n]` / `long <text>` | click / long-press the n-th element whose text or icon description matches (or the switch beside that label) |
| `type <value>` / `typein <field> \| <value>` | set the focused (or first) text field / the field whose label matches |
| `scroll down\|up [n]` / `scrollto <text>` | scroll the main area / until the element is visible |
| `back` | close the top dialog (BACK key to its window), else system back |
| `wait <ms>` | let the app run (app time, 16 ms frames) |
| `tree` | what's on screen and what can be tapped, typed or scrolled |
| `set <prop> <value>` / `get <prop>` | force any state: a view-model field (`set warned true`) or `Object.prop` (`set VrchatPipelineState.authDead true`) |
| `preset <name>` | ready-made states, see below |
| `show <name> [args]` | screens normally behind a gate, see below |
| `root screen\|app` | draw the main screen (default) or the full app with its boot/ToS/onboarding/update gates |
| `hide` | remove what `show` put up |

**Presets:** in-world, offline, friends, incident, outage-minor, status-ok, alerts, no-alerts, sending,
idle, warned, banned, auth-dead, logged-out, nowplaying, paused, ad, roster, roster-empty, manual, owner
(opens the admin panel without the owner account — its data stays empty).

**Shows:** update, update-optional, whatsnew, confirm, confirm-destructive, timezone (dialogs);
boot [2|error|done], crash, banned [reason], tos, onboarding <step 0-7>, login [nocancel] (full screens).

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
websocket) — use presets for what those would show.

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
