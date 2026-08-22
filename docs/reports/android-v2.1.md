# Android v2.1 report

## What changed
- `Models.kt`: exact league names (`Trendyol Süper Lig` / `İngiltere Premier Lig`); `MatchEvent.eventTeamSide`
  + `Side` enum; `GoalRow` + `Match.goalRows()` — walks all goal events in minute order, increments the scoring side,
  null side leaves the score unchanged and team = null ("—"); `GoalRow.clip()` subtitle "Team · h–a".
- `BrowseScreen.kt`: top bar reordered per §0 (League → Season → Week [hidden in By team] → Mode → mode row).
  By team mode row: "Team" dropdown with logos (team list hoisted from season data), `Matches | Goals` sub-switch,
  `Only <Team> goals` Switch (default ON) shown only when Goals active. New `GoalCard` (team logo, minute + scorer,
  team name, running score with scoring side in emerald). `GoalsView` takes a `goalFilter` applied to rows and
  Play all. `Dropdown` gained optional item icons / leading icon. Season picker unchanged in behaviour (selectable
  in all modes, resets week to default).
- `README.md`: features / file map updated. Player (playlist, fullscreen, PiP) untouched.

## Verification
- `./gradlew assembleDebug --no-daemon -q` → clean (no errors); APK copied to `Highlights-debug.apk`.
- `adb install -r` → Success; launched on emulator-5554; driven with `adb shell input tap`.
- `android/build/v21-goals.png`: Goals mode, week 34 — e.g. Çaykur Rizespor 2-2 Beşiktaş rows `1–0, 2–0, 2–1, 2–2`
  with Rizespor / Beşiktaş logos and names; scoring side highlighted. Play all (27).
- `android/build/v21-team-goals.png`: By team → Beşiktaş → Goals, "Only Beşiktaş goals" ON — only Beşiktaş rows
  (e.g. 55' Jota Silva 2–1, 62' Vaclav Cerny 2–2), week labels on headers, Play all (59).
- Tapped a goal card → player opened, Back returned to list.
- `adb logcat -d | grep -E 'FATAL|AndroidRuntime'` → 0 lines.

## Known gaps
- `Only <Team> goals` state is not persisted across launches (spec only requires league/season/week persistence).
- Own goals: upstream `eventTeamSide` presumably reports the benefiting side; if it is null the row shows "—" and
  no increment, as specified, so the running score may lag the final scoreline for such matches.
- Premier League path exercised only via build/logic, not screenshotted.
