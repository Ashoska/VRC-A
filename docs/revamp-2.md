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
- Motion read while drawing only; RenderThread loops (AVD) for anything that must move while the app
  is busy; every page passes the lab `perf` check.
- Process per page: mock-up sheet (every state) → user approval → build → lab screenshots / recording →
  merge. Sheets for users come from the lab, not hand-made mocks.

## Pages (in the order a user meets them, starting from the boot screen)
| # | Page | When users see it | Status |
|---|---|---|---|
| 1 | Boot screen (row icons, cat state marks, version chip, timeline, Discord no longer blocks) | every open | DONE 2026-10-23 |
| 2 | Terms of Service gate (+ the rare banned / crash screens, same building blocks) | first open, or when the terms change | next |
| 3 | Onboarding + VRChat login | new users, right after the terms | |
| 4 | Update dialog + What's New | first open after an update | |
| 5 | Home (headset 3-column + phone) | where everyone lands | |
| 6 | Automations | bottom-bar order | |
| 7 | Media | | |
| 8 | VRChat tab + roster | | |
| 9 | Settings | | |
| 10 | Admin (all tabs; the Config tab's VRC-A servers maintenance switch is a placeholder for this) | admin build only | |

Cleanup done at the start: the unused pre-revamp Home / Automations / Media pages
(`LegacyPagesAndSettingsPage.kt`, ~770 lines) were deleted.
