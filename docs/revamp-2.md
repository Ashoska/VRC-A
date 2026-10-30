# VRC-A — Revamp 2 (full app)

Status: **STARTED 2026-10-23.** The first revamp (`docs/ui-revamp.md`) is done; this one redoes the
whole app to the standard the boot screen set. One page at a time, each designed, approved, built,
checked in the UI Lab and merged before the next.

## The standard (set by the boot screen)
- Gremlin design system and the UI build checklist (`docs/systems/ui-kit.md`), headset panel first
  (fixed 1024x640dp), phone layout from the same components.
- VRC-A's own icon language: rounded outline icons on a 24-unit grid, one line weight, the logo's cat
  ears as the recurring touch, one small detail that takes the state colour (`BootIcons`).
- Status shown by the boot cats (`BootCat`: thinking / result faces / reactions) wherever the app shows
  the state of something; colour is never the only signal.
- Motion read while drawing only; Compose loops (the user dropped AVDs, 2026-10-30) for anything that must move while the app
  is busy; every page passes the lab `perf` check.
- Process per page: mock-up sheet (every state) → user approval → build → lab screenshots / recording →
  merge. Sheets for users come from the lab, not hand-made mocks.

## Pages (in the order a user meets them, starting from the boot screen)
Every screen, popup, dialog, banner, overlay and toast is listed under the page it belongs to, and is
revamped with that page. A page is DONE only when everything listed under it is done.

| # | Page | When users see it | Status |
|---|---|---|---|
| 1 | Boot screen | every open | DONE 2026-10-23 (returning + first-install Boot Cat welcome) |
| 2 | Gates | first open, terms change, rare failures | in progress: CrashScreen DONE 2026-10-23; TosGate + BannedScreen next |
| 3 | Onboarding + VRChat login | new users, right after the terms | |
| 4 | Update + What's New | first open after an update, or a forced update | |
| 5 | Home | where everyone lands | |
| 6 | Automations | bottom-bar order | |
| 7 | Media | | |
| 8 | VRChat tab + roster | | |
| 9 | Settings | | |
| 10 | App-wide pieces | everywhere | |
| 11 | Admin | admin build only | |

### What each page includes
1. **Boot screen** (`BootScreen`, `BootRevealOverlay`): row icons, cat state marks, version chip,
   timeline, Discord no longer blocks. Two versions, both built: the returning boot and the first-install welcome where Boot Cat guides the checks (details in `docs/systems/onboarding-startup.md`).
2. **Gates**: `TosGate` (terms + accept), `BannedScreen` (both copies: `VrcaApp` and the one in
   `VrcaScreen`, merged into one when revamped), `CrashScreen` (DONE: safe start, plain-words where/what, 3-day history,
   forced update on top, uploads to the admin Crashes tab, fade into boot; details in `docs/systems/onboarding-startup.md`).
3. **Onboarding + VRChat login**: `OnboardingFlow` (every step), `VrchatLoginScreen` (login, 2FA),
   Discord login WebView + the Discord risk-consent dialog.
4. **Update + What's New**: `UpdateDialog` (normal + forced, browser fallback), `WhatsNewDialog`.
5. **Home**: preview card, Quick Toggles, Connection card, `SetupHealthCard`, `VrcaTimeZoneDialog`
   (time zone picker), `IpField`.
6. **Automations**: page, drag-and-drop editor, its 3 dialogs (`AutomationsPage`).
7. **Media**: Now Playing card, progress bars, media settings.
8. **VRChat tab + roster**: alert cards, Signed up / Pinned, `EventOccurrencesDialog` (Repeats),
   `InstanceListDialog`, roster panel (`InstanceRosterPanel`), its 4 dialogs and 9 toasts.
9. **Settings** (`VrcaSettingsPage`): every section, its 6 dialogs (incl. 3 confirms),
   `SpeechLanguageDialog`, `VoiceCommandsDialog`, `ListenTriggerDialog`, Debug log.
10. **App-wide pieces**: top bar + bottom nav (`VrcaScreen`, its 3 dialogs), banners
    (`VrchatStatusBanner`, `VrchatSessionExpiredBanner`, `GlobalStatusBanner`), the shared dialog kit
    (`VrcaCardDialog`, `VrcaConfirmDialog`, `VrcaSingleSelectDialog`, `PanelDialog`), rich content
    (`RichDocRenderer`, announcements), floating overlays (`StartOverlay`, `MessengerOverlay`),
    `TransferTargetPicker`, notification icons + the persistent notification, the remaining toasts
    (overlay, Spotify redirect, notification actions). The dialog kit is restyled once, so pages
    after it inherit it.
11. **Admin**: all tabs, the admin UI kit, user detail; the Config tab's VRC-A servers maintenance
    switch is a placeholder for this.
    User wish (2026-10-30): the states should feel live and reactive, not static text. Every bot,
    queue and server state should move the moment it changes (counters ticking, bots shown working /
    idle / rate-limited / stopped as it happens, the shard each one is on, pushes landing), using the
    Boot Cat-style marks and the "everything shown is watched state" rule. The Bots tab also drops its
    leftovers from before Worker v49 (the "Liveness queued" number, "✓ checked" wording); its push
    readout is now "+new ~calm ✓font −removed". Data that is already free to watch: the local bot
    progress (`AvatarCatalogSweep` RoleView), the flush readout, the Worker `/health` poll (also
    `writesToday`, `calmWaiting`). Any faster polling or new listener needs a cost check first.

Leftovers to delete (no callers): `ButtonOverlay`, `AboutScreen`, `DependenciesScreen`,
`SettingsScreen` (`ui/settings/`), `MainScreen` (`ui/conversation/`). Checked and removed when the
page that would have used them is revamped.

Cleanup done at the start: the unused pre-revamp Home / Automations / Media pages
(`LegacyPagesAndSettingsPage.kt`, ~770 lines) were deleted.
