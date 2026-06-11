# VRC-A — UI Revamp + Onboarding Design Doc

Status: **APPROVED SPEC — not yet implemented.** This captures the full brainstorm
(June 2026 session) so implementation can start from here without re-deriving
decisions. No app code has been changed for this doc.

---

## Goals

1. **More compact, less scattered** — same colors/style family, better placement,
   no repeated/spammed elements, no useless filler.
2. **More functionality at the same time** — reclaimed space gets reinvested in
   *information*, not padding. Every element does double duty.
3. **First-open onboarding tutorial** — guided setup replacing the current
   scattered gates and the Home "Setup Tutorial" card.

## Core design principle

**Collapsed = status, expanded = control.** Every collapsed card answers its own
question (shows a live summary), so nearly everything can default collapsed.
Today collapsed means "blank header you must open to learn anything" — that is
why the app sprawls.

## Visual north star

**The VRChat login screens, NOT the admin panel.** (Owner reviewed both:
login/2FA screens are the cleanest surfaces in the app — clean spacing, trust
messaging, distinct error states, disabled-until-valid buttons.) The admin
panel's *information density* is right (pills, avatars, inline relative times)
but its visual skin is not the target. One consistent language, derived from
the login screens.

## Shared public UI kit (`PublicUiKit.kt` — build FIRST)

- `CompactSectionCard(title, icon, summary, collapsible)` — icon header +
  live summary text; collapsed by default for configure-once content.
- `TogglePill` — icon + label + state color; used in a 2×2 grid.
- `StatusDot` / status chip — green/amber/grey + label.
- `LabeledRow(label, value)` — dense label→value row.
- `TutorialImage(image, caption)` — fixed aspect, rounded corners,
  tap-to-expand full screen with pinch zoom.
- Consistent metrics everywhere: 12dp card padding, 8dp gaps, same title style.

---

## Per-screen plans

### Home
Screenshot findings: "Setup incomplete" banner repeats on EVERY tab; preview
empty state ("nothing active" + big silhouette) wastes ~40% of viewport;
Manual Send permanently expanded at bottom.

- **Setup health checklist** replaces the banner — multi-row card (IP set /
  reachable, battery exemption, Notification Access), each row deep-links to
  the fix. Only visible while something is red; disappears entirely when all
  green. **Lives on Home ONLY** — other tabs at most get a red dot on the Home
  nav icon. (The existing banner + Fix action is the seed of this.)
- **Preview**: keep the original visual identity (two redesigns were already
  reverted — do not restyle it). Make it collapsible: compact "live chip"
  (preview text bubble + SendStatusChip + invisible-border eye toggle inline),
  tap to expand the full 280dp simulation. Empty state collapses short.
- **Start/Stop**: single prominent pill next to the status chip, carrying
  **uptime** ("Sending · 2h 14m"). Uptime persists across OEM kills using the
  RPC-counter pattern (persisted start epoch + heartbeat + grace window keyed
  off `FeatureSessionStore`): **revival continues the timer, swipe resets it.**
  Label framing is "since you pressed Start" (the dead window during a
  kill→watchdog gap is not subtracted).
- **Quick Toggles**: 2×2 grid of `TogglePill`s (Pinned / Cycle / Media / Time).
  Long-press a pill jumps to that feature's edit page. Keep the inline-control
  pattern (Time's UTC dropdown embedded in its row/pill).
- **"Next cycle in 12s"** ticker under the preview while Cycle is on.
- **Connection**: collapses to one line ("Connected → 192.168.x.x · Home") with
  a **live reachability dot** (periodic lightweight ping / last successful OSC
  send). Expands for slot editing. Catches "Started sending into a dead IP".
- **Manual Send**: collapses to a single row that expands.
- **REORDERING IS A HARD REQUIREMENT**: Home stays a reorderable list of
  self-contained cards (`CARD_ORDER` pref + existing Edit mode, growing drag
  handles for all new cards). Every new component must participate.

### Automations
Findings: Pinned tab is one field + presets row + ~60% empty screen; preset
summaries are unreadable run-on lines ("1:… – 2:… – 3:…").

- Drop the inner Pinned/Cycle tab bar; stack both as collapsible cards.
  Collapsed summaries show content: "Pinned · Preset 2 · ON",
  "Cycle · 5 lines · 30s · next: 'brb water'".
- Presets as a horizontal **chip row** (tap to equip, long-press to peek a mini
  preview) instead of the run-on text line.
- Inline **character/line budget meter** in editors (e.g. `87/144`) so the trim
  warning happens before the fact. Cycle's 10-field line editor itself is fine.

### Media
Findings: five progress-bar preset rows at full height with a redundant
"Selected" label; "Detected / Preview" block is debug-flavored.

- Preset rows at half height, keep the glyph preview, kill the "Selected" label
  (highlight = selection).
- Promote a proper now-playing card: detected source app icon, track + artist,
  the exact rendered chatbox line, ad/live state. Raw booleans
  ("Detected: true") move to Settings → Debug.
- Per-source enable rows (Spotify / YouTube / YT Music) inside the expanded card.
- Notification Access here reduces to a one-line warning row only when access
  is missing (onboarding + Settings own the permission otherwise).

### VRChat tab
Findings: TWO stacked identity cards; five collapsed notification sections eat
~600dp of pure configuration; Discord RPC card + duplicated About card.

- **Merge identity cards** into one header row: avatar + name + status dot +
  platform chip + trust rank; Sign out and View Profile as small trailing
  actions. Fix the "stars :0" label (formatting bug/placeholder).
- **Move ALL notification toggle sections to Settings** (they are config, not
  daily use), with per-section master rows showing counts ("Friends activity ·
  6/10 on").
- **Move Discord RPC card to Settings** (see Accounts below); identity header
  keeps a small "RPC ● Connected" status dot.
- Delete the duplicated About/trust-messaging card (copy lives in login +
  Settings → About).
- Tab becomes the live feed: presence header + status banner + alerts +
  **friends online count** (from `friendsCache`, free). Alerts get filter chips
  (Friends / Groups / Bio) on the section header. ("Events since open" idea was
  REJECTED — do not add.)

### Settings
Already the tidiest page. Additions:

- **Accounts section (NEW REQUIREMENT)**: VRChat and Discord account rows.
  - Logged in: name/identity + **Sign out** (VRChat) / Connected pill +
    **Disconnect** (Discord).
  - Logged out: a **mini login section appears inline** (compact form for
    VRChat reusing the login components; Discord opens the login WebView).
  - **OSC hard gate on VRChat logout**: signing out of VRChat force-stops all
    OSC output (`stopSending()` + block at the `VrcaOsc.sendOscMessage`
    chokepoint, same mechanism as the force-update gate) until the user logs
    back into a VRChat account. Re-login unblocks but does NOT auto-start
    sending — user presses Start again. Toggle config is preserved.
  - Note: this introduces a "logged out while in app" state (today the login
    screen is a hard pre-app gate). After onboarding, the hard gate applies
    only to first open; later logouts keep the app usable with OSC blocked.
    Firebase anon auth / liveness / moderation are independent of VRChat auth
    and continue working.
- **Notification toggles** arrive from the VRChat tab (5 collapsible sections +
  master count rows).
- **Storage row**: live measured cache size + "Clear cache" button that opens
  the system App Info page (`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`) —
  apps cannot fully wipe their own cache; the system page is the honest route.
  System Clear Cache does NOT touch `app_webview` (data dir) — the in-app 20 MB
  hourly cap handles that — and never touches logins/settings.
- **"Replay setup tutorial"** row (see Onboarding).
- About card: fix literal dash/asterisk bullets (style them properly).
- Battery-optimization row eventually redundant with the Home health checklist.

### ToS screen (redesign)
Content is good; presentation buries it. Findings: zero hierarchy (headers same
size as body), literal `*` bullets, the contact link renders as a doubled raw
markdown mess, agree control is a Switch.

- Sticky header (title + version pill). Sections as styled blocks: bold header,
  real bullets, spacing between sections.
- Risk section visually distinct (warning-tinted card).
- Agree = **checkbox** (not Switch) + Accept pinned at bottom; optionally
  enable Accept only after scrolling to the end.
- Optional "what changed" line on version-bump re-acceptance.
- Same component reused in onboarding step 1 and the standalone re-acceptance
  gate.

### VRChat login (polish only — it's the north star)
- Tighten the explainer to 2–3 lines.
- 2FA screen adds: **"VRChat will remember this device — you won't be asked
  again."**
- Authenticator-app variant gets its own title (not "Check your email").
- Nice-to-have: "Resend code" with cooldown; segmented digit boxes.
- Keep the differentiated error surfacing (bad credentials vs transient) —
  the auth manager already distinguishes them.
- **VRChat platform status on the login surfaces**: when VRChat itself is
  having issues (status.vrchat.com indicator != none), show a compact warning
  on the login screen — "VRChat is having platform issues right now — login
  may fail and it's not you." Applies to BOTH the onboarding login step and
  the standalone/mini login (Settings re-login). NOTE: the existing status
  banner is fed by `startStatusPagePolling()` in `VrchatPipelineService`,
  which only runs POST-login — so the login surfaces need their own one-shot
  fetch of `status.vrchat.com/api/v2/summary.json` on entry (the admin
  dashboard feed already does this directly via OkHttp; reuse that parsing).
  Re-fetch on a failed login attempt so the warning can appear reactively.

### Update dialog (redesign — same pass)
Findings (v1.6.31 screenshot): long patch notes CLIP — the notes box is a
fixed-height block with no scrolling; overall presentation is plain.

- **Scrollable notes area** with a max height (~50% of screen) so arbitrarily
  long patch logs never clip; the dialog itself never grows past the viewport.
- Header: title + version as a pill (consistent with ToS version pill);
  optionally "current → new" version line.
- Notes rendered with styled bullets (same fix as ToS/About literal-dash
  problem).
- **Download state inline**: progress indicator + state text (downloading /
  verifying / failed with reason) instead of the bare disabled button; keep
  the existing browser-fallback link (added June 2026) visible on failure.
- Forced-update variant (the current default — ALL releases are forced) keeps
  no-dismiss/no-Later; the layout must look intentional without a cancel
  action (single full-width Download button).
- Built from the same kit components so it matches the gate screens.

---

## Onboarding (first-open tutorial)

Pager flow — order is FINAL:

1. **Welcome + ToS** — hard gate (redesigned ToS component).
2. **VRChat login** — hard gate; hosts the Phase-2 ban check (catches evaders
   before any setup effort); pipeline first-run notification baseline seeds
   during onboarding. Shows the **VRChat platform status warning** (one-shot
   status fetch — see "VRChat login" section) so a user whose login fails
   during an outage knows it isn't their credentials.
3. **Permissions** — notification runtime permission (Android 13+),
   Notification Access, battery exemption. NO screenshots here (the app fires
   the exact intents; OEM screens vary). Samsung detected →
   one-line manual "Never sleeping apps" instruction.
4. **IP entry** — writes to **slot 1**; live reachability check (green check
   before leaving the step). Quest gets the illustrated walkthrough (the only
   IP images — see Instructional images); PC/phone are covered by a one-line
   text note ("find it the same way in your phone's Wi-Fi settings, or on PC
   via `ipconfig`"). Mention "the next step also happens in the headset" for
   Quest users.
5. **Enable OSC in VRChat** — illustrated: radial Action Menu → Options → OSC →
   Enabled (2–3 frames).
6. **Notification types** — skippable; the 5 section-level switches with
   defaults on, expandable for detail (NOT all 27 toggles).
7. **Discord RPC** — skippable; existing consent dialog + login WebView.
8. **Test message finale** — optional/skippable: a scoped sender loop pushes a
   fixed **"VRC-A connected ✓"** that STAYS in the chatbox (re-sent on the
   keepalive cadence, ≥500ms floor) until the user advances/exits, then one
   empty send wipes it. Deliberately bypasses the Start/Stop gate: `oscSending`
   stays false, no toggles touched, `FeatureSessionStore` never armed (process
   death mid-tutorial resurrects nothing). The preview shows the same bubble
   simultaneously (teaches preview == chatbox). Failure path shows the two
   usual culprits inline ("OSC enabled? Same Wi-Fi?") with back-links to steps
   4/5.

Mechanics:
- `first_open_complete` DataStore flag gates the whole flow. **Pre-seed the
  flag for existing installs** (device hash + accepted ToS already present) so
  current users never see it.
- **"Replay setup tutorial"** in Settings: hard gates (ToS, login) render as
  completed checkmark steps, not re-prompts.
- Persist current step index — process death mid-onboarding resumes in place.
- Back navigation allowed except across completed hard gates; standard pager
  dots.
- Hard gates: ToS, VRChat login. Everything else skippable.

### Instructional images
- **Baked into the APK** (not remote-hosted): onboarding must work flawlessly
  on first launch / flaky Wi-Fi; all releases are forced updates anyway so
  baked images are effectively updatable.
- WebP ~70–80% quality, tight crop, ≤~800px wide → ~30–80 KB each; 6–8 images
  well under 1 MB total.
- Uniform annotation: one accent-colored circle/arrow marking the tap target,
  rounded corners, uniform crop ratios (pager must not jump heights).
- Needed captures (owner provides raw, we crop/annotate):
  - VRChat radial menu path (2–3 frames)
  - Quest: Settings → Wi-Fi → connected network → IP address — **the ONLY
    IP-step images**. PC and hotspot get NO screenshots; instead a short text
    note on the step: "You can find it the same way in your phone's Wi-Fi
    settings, or on PC by running `ipconfig` and reading the IPv4 address."
    (One image set to maintain; the Quest path is where people actually get
    lost.)

---

## Cross-cutting rules

- Strip repeated teaching copy ("Toggle it on Home. Stop clears instantly.")
  once onboarding exists — it moves into the replayable tutorial.
- Kill duplicated affordances (the "is this reachable somewhere more natural?"
  test — same reasoning that removed per-page enable toggles).
- Section headers carry trailing summary values so users can skip expanding.
- All gate screens (ToS / login) built from the same kit components so they
  look identical inside the onboarding pager and standalone.

## Implementation order (each step its own commit, page-per-commit)

1. **PublicUiKit** (components only, no behavior changes)
2. **Onboarding** (defines the tone for new users; absorbs ToS + login
   redesign + the update dialog redesign + login status warnings)
3. **Settings** (Accounts + OSC logout gate, notification toggles arrive,
   storage row, replay-tutorial row)
4. **Home** (health checklist, compact preview, uptime pill, toggle grid,
   connection row — reorder support throughout)
5. **Automations** → 6. **Media** → 7. **VRChat tab**

Each page revertable independently. Both build variants must compile at every
step; admin panel is untouched by this work.
