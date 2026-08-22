# tvOS app — v2.1 report (2026-08-22)

## What changed
- **Season/Week/Team pickers** (`tv/Highlights/BrowseView.swift`): the SwiftUI `Menu`s (unselectable on a real Apple TV) are replaced by bordered buttons showing the current value that open a `fullScreenCover` `PickerSheet` (focused list / 4-column grid for teams; selecting applies and dismisses). Works in every mode. `BrowseModel.selectSeason` resets the week to that season's default (`currentWeekForFixture`, else last) and reloads; the saved week is only restored on first launch.
- **§0 layout**: League switch → `Season` row → `Week` row with ‹ › (hidden only in By team) → Mode switch (Highlights | Goals | By team) → mode row (Team picker with logo+name, `Matches` | `Goals` sub-switch, `Only <Team> goals` toggle default ON, Play all) → content. League names unchanged/exact.
- **Goals cards** (`Models.swift`, `BrowseView.swift`): `MatchEvent` now decodes `eventTeamSide` → `Side`; `Match.goalRows` walks goals in minute order and computes the running score (own goal/unknown side: no increment, team "—"). `GoalCard` shows minute badge, scorer, scoring-team logo + name, and the running score with the scoring side in emerald. Grouped by match under `MatchHeader` (home logo, scoreline, away logo, week label in By team). Play all is built from the same filtered rows.
- **By team → Goals**: `onlyTeamGoals` filter (default ON) keeps only goals whose scoring team is the selected team; OFF shows both sides. Play all respects it.
- **UI test**: new `HighlightsUITests` target (`tv/project.yml`, `tv/HighlightsUITests/HighlightsUITests.swift`) driven by `XCUIRemote.shared`; accessibility identifiers added to all controls.
- README tvOS section updated (controls, file map, how to run the UI test).

## How verified
```
cd tv && xcodegen generate
xcodebuild test -project Highlights.xcodeproj -scheme Highlights \
  -destination 'id=702267A2-2D26-4D0C-A346-AD07DBA7A5D5' -derivedDataPath build CODE_SIGNING_ALLOWED=NO
# → Test Case '-[HighlightsUITests.HighlightsUITests testSeasonPickerAndGoalsMode]' passed (14.243 seconds).
# → ** TEST SUCCEEDED **
xcrun simctl launch 702267A2-... ai.bjk.highlights
xcrun simctl io 702267A2-... screenshot tv/build/v21-goals.png   # Goals mode (mode persisted from the test)
```
The test: launches, focuses `season.button`, presses select, picks `picker.row.1` (second season), asserts the Season label equals that row's label, moves to the mode row, focuses `mode.goals`, selects, and asserts an element whose label contains `–` (running score) or the `empty` state exists.

## Known gaps
- Not re-verified on the physical Apple TV in this pass (simulator only); the picker uses plain focusable buttons so it should behave identically on device.
- Screenshot taken from the simulator; `CODE_SIGNING_ALLOWED=NO` is needed for the simulator test run with the free team.
- `Only <Team> goals` / Matches|Goals sub-switch state is not persisted (spec §1 only requires league/season/week).
