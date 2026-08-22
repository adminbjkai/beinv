# Android v2.4 report — polish / bug sweep

## Findings and fixes
| # | Item | Finding | Fix |
|---|------|---------|-----|
| 2 | Team restore | Team was `rememberSaveable` only and `LaunchedEffect(league, seasonId) { team = null }` wiped it on every launch. | `Prefs.team(league)` / `saveTeam`; restored on launch and league change, saved on pick (`BrowseScreen.pickTeam`). |
| 2 | By-team sub-switch | `Matches | Goals` and `Only <Team> goals` reverted after returning from the player (BrowseScreen leaves composition → saveable state dropped). | Persisted in `Prefs.teamGoals` / `Prefs.onlyTeam`; initial state read from prefs. |
| 2 | By team empty state | Empty season for a team said "…for this week yet." | `Content(emptyMsg=…)` → "No highlights for <Team> this season yet."; empty texts centred with 24 dp padding. |
| 3 | Truncated titles | Player header title and list rows were `maxLines = 1` (canonical title cut: `…Beşi…`). | Header title and row scorer/subtitle lines → `maxLines = 2`; row subtitle 12 sp. |
| 3 | Paddings | Top bar used 16 dp, content lists/grids 12 dp. | Match grid, goals list, team list, skeleton grid and player list `contentPadding` → 16 dp. |
| 3 | Drawer readability | Compact rows 13 sp / 6 dp vertical. | 14 sp, 8 dp vertical, 10 dp horizontal. |
| 1 | Rapid next/prev | No crash, but `onMediaItemTransition` wrote the raw index unguarded. | Index only applied when `in clips.indices` (defensive). |

Checked and fine (no change): row tap jumps + keeps playing; Up next / Previous update on every transition; after the last clip → list; Back order drawer → fullscreen → list; rotation keeps position; PiP; season change resets week; Retry after connectivity loss; ripple on rows (`clickable` + Material3 indication); status bar colour (`statusBarColor` = `#0B0F0E`, edge-to-edge + `statusBarsPadding`); no purple.

## Premier League Goals mode
Season 3958 = **2026/2027** (current, week 1 played 2026-08-22). Upstream returns the 6 matches with `highlightVideoUrl` but `matchEvents: null`; every other PL week checked (3958 r2 → `{"Data":{}}`, 3828 r30/r37/r38 → 0 events) has no goal events either. So PL Goals mode can only show the empty state ("No goal clips for this selection.") — a data limitation, not a parsing bug. Süper Lig goal cards unaffected (59 Beşiktaş goals, running scores OK).

## Verification (emulator-5554, Pixel 1080×2424, `adb logcat -d | grep -cE 'FATAL|AndroidRuntime'` → 0)
Build: `./gradlew assembleDebug --no-daemon -q` clean (twice); APK → `Highlights-debug.apk`; `adb install -r` Success.
Screenshots in `android/build/`:
- `v24-browse.png` — By team restored on launch; `v24-team-goals.png` — Beşiktaş goals, `Play all · 59 goals · from 1. Hafta`.
- `v24-player.png` — tapped 14' Orkun Kökçü → "57 of 59", 2-line canonical title, Up next / Previous, 32./33./34. Hafta groups, current row emerald.
- `v24-player-jump.png` — tapped Jota Silva row → "58 of 59", still playing; `v24-player-rapid.png` — 12 rapid alternating row taps → "59 of 59", no crash.
- `v24-landscape.png` — `user_rotation 1` during playback, clock continuous (61:48 → 62:07), Clips button only.
- `v24-landscape-drawer.png` — drawer ≈35 %, readable rows; `v24-back1.png` — Back closed drawer only; `v24-back2.png` — Back left fullscreen, still in player.
- `v24-end.png` — last clip ended → back to list (≤10 s later, no PlayerView in `dumpsys`).
- `v24-pl-goals.png` / `v24-season-menu.png` / `v24-season-change.png` — PL Goals empty state; picking 2025/2026 reset week 1. Hafta → 38. Hafta.
- `v24-offline.png` — `svc wifi disable` + `svc data disable`, week 37: "Couldn't load: Unable to resolve host…" + Retry; `v24-retry.png` — after enabling, Retry loaded the 10 matches.
- `v24-relaunch.png` — force-stop + relaunch restored PL / 2025/2026 / 37. Hafta / Highlights.
- `v24-team-empty.png` — By team → Arsenal → Goals: clean empty state; `v24-team-restore.png` — relaunch restored team, Goals sub-switch and toggle.
- `v24-pip.png` — Home while playing → PiP window on the launcher.

## Gaps
- After Back exits a rotation-triggered fullscreen, `requestedOrientation` is locked to portrait until leaving the player (v2.3 behaviour, unchanged).
- PL goal cards cannot be shown until beIN publishes `matchEvents` for PL.
