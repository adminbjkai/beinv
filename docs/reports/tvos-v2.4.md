# tvOS app — v2.4 report (2026-08-22) — Clips-tab jump bug + polish

## Bug: selecting a clip in the swipe-down "Clips" tab does nothing (real Apple TV)
**Reproduction.** New UI-test step: after Play all opens the player, press down until the info panel's Clips tab (`player.clips`) exists, press down once more (row 2), select, and read the player view's accessibility label, which mirrors the coordinator's current index (`clip <i>`). Plus app-hosted unit tests for the coordinator.

**Root cause.** The jump function itself was correct (unit test `testJumpRebuildsQueueAtIndex`: 4-item playlist → `jump(to: 2)` → `currentItem` is item 2, `items().count == 2`; the simulator UI step also went `clip 0 → clip 1`). The failure is in the hosted SwiftUI list used inside AVKit's info panel: the `UIHostingController` rows were not reliable focus/press targets in the panel — they did not even appear in the accessibility tree (`clip.<i>` not found), and the hosted `ScrollView`/`Button` only receives the select press when SwiftUI's focus system and AVKit's agree, which they do in the simulator but not consistently on device. The week header was also clipped and rows sat on the video without a readable backing, so even a successful jump was hard to notice.

**Fix** (`tv/Highlights/PlayerView.swift`): the Clips tab is now a native `ClipListController: UITableViewController` — week section headers, `ClipCell` rows (minute · team logo · scorer · scoreline · running score; emerald when current, white ring + 1.02 scale when focused, charcoal 78 % backing otherwise), `preferredFocusEnvironments` = current row, `didSelectRowAt` → `Coordinator.jump(to:)` (rebuilds the queue at that index and calls `play()`), table reloads on `$index` via Combine so the highlight follows autoplay/skip. `play(from:)` was renamed `jump(to:)` and is the single jump entry point for Play all, goal cards, Prev/Next and the Clips tab. The info panel closes on its own after the jump (AVKit resets its chrome when the queue is rebuilt — seen in `v24-jump.png`: transport bar with the new title); there is no public API to dismiss it explicitly.

## Polish
- Browse focus order: each control row (League, Season, Week, Mode) is a `focusSection()` so up/down moves League → Season → Week → Mode → content without skipping rows.
- By team: the chosen team is saved to `UserDefaults` (`team`) and restored after the season's team list loads (`BrowseModel.loadSeason`).
- Goal-card scorer: 2 lines with `minimumScaleFactor(0.85)` in a fixed 56 pt slot, so long names don't truncate at 1080p.
- Verified unchanged: Play all is `.disabled` with 0 goals; Goals shows "No goal clips for this selection."; By team shows "Choose a team above." / "No matches in this season yet."; error state has Retry.
- Note: `Clip.matchScore` (the scoreline inside the canonical title) is the running score at that goal (`Erzurumspor FK 0–2 Galatasaray · 20' …`), per the cross-client parity comment in `Models.swift`.

## Verification
```
cd tv && xcodegen generate && xcodebuild test -project Highlights.xcodeproj -scheme Highlights \
  -destination 'id=702267A2-2D26-4D0C-A346-AD07DBA7A5D5' -derivedDataPath build CODE_SIGNING_ALLOWED=NO
# Test Case '-[HighlightsTests.PlayerCoordinatorTests testJumpRebuildsQueueAtIndex]' passed (0.012 seconds).
# Test Case '-[HighlightsTests.PlayerCoordinatorTests testNextPreviousBounds]' passed (0.003 seconds).
# Test Case '-[HighlightsTests.PlayerCoordinatorTests testOrderedGoalPlaylist]' passed (0.003 seconds).
# Test Case '-[HighlightsUITests.HighlightsUITests testPlayAllOpensPlayerAndMenuReturns]' passed (26.515 seconds).
# Test Case '-[HighlightsUITests.HighlightsUITests testSeasonPickerAndGoalsMode]' passed (13.503 seconds).
# ** TEST SUCCEEDED **
```
- `tv/build/v24-clips.png` (viewed): Clips tab with "2. Hafta" header, rows 12'/20'/45'/80' with logos, current row emerald + focus ring, readable charcoal rows.
- `tv/build/v24-jump.png` (viewed): after selecting row 2 the panel is closed and the transport bar shows "2. Hafta · … · 20' Yunus Akgün", "2 of 13"; the test asserts the label moved `clip 0 → clip 1`.
- New target `HighlightsTests` in `tv/project.yml` (scheme runs unit + UI tests).

## Gaps
- The device failure is reproduced only indirectly (hosted rows absent from the accessibility tree in the simulator); confirm on the physical Apple TV after deploy.
- The info panel's dismissal after a jump relies on AVKit behaviour, not an API.
- Team restore applies only when By team loads a season in which that team exists.
