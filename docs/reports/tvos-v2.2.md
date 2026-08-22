# tvOS app — v2.2 report (2026-08-22) — §2b Playlists

## What changed
- **One ordering** (`tv/Highlights/Models.swift`): `orderedGoalPlaylist(_:weekName:)` sorts goal rows by week asc → match kick-off asc → minute asc (stable on row id) and maps them to `Clip`s. `BrowseModel.goalClips` uses it over exactly the visible `goalGroups` (mode + team + `Only <Team> goals` already applied); **Play all** and selecting a goal card both open that list (card → `startIndex` of its clip id). `Clip` gained `week`, `score`, `side`, `teamLogo`, `minute`, `scorer` via `GoalRow.clip(week:)`.
- **Player** (`tv/Highlights/PlayerView.swift`):
  - Autoplay next: `AVQueuePlayer.actionAtItemEnd = .advance` (explicit); the `AVPlayerItemDidPlayToEndTime` observer maps the ended item to its queue position to keep `index` in sync, and dismisses the cover when the **last** queued item ends (no loop).
  - Next/previous: transport-bar custom items kept; `skippingBehavior = .skipItem` so Siri Remote right/left page-skip goes to the next/previous clip. The "x of N" position is shown in the selector header.
  - **Clip selector** `ClipSelectorView`: dark translucent panel (`.ultraThinMaterial` over charcoal, emerald 1 pt stroke) listing every item — minute, scoring-team logo, scorer, match scoreline, running score, week label; current row filled emerald; select → `play(from: i)` (rebuilds the queue from that index). Opened via the transport-bar **Clips…** action (presented `overFullScreen` from the `AVPlayerViewController`, `ClipSelectorHost` hosting controller; Menu / `onExitCommand` closes it) and also as the swipe-down info panel's **Clips** tab (`customInfoViewControllers`).
  - **Menu exits the player**: `AVPlayerViewController` inside a SwiftUI `fullScreenCover` swallowed Menu (only hid its chrome), so the cover never dismissed — pre-existing bug. A `UITapGestureRecognizer` with `allowedPressTypes = [.menu]` on the player view now calls SwiftUI `dismiss`.
  - `player.view` accessibility identifier on the player container; `player.clips` on the selector; `clip.<i>` rows.
- **BrowseView**: Play all identifier renamed `playall` → `goals.playall`.
- **UI test** `testPlayAllOpensPlayerAndMenuReturns` (`-reset`): Goals mode → navigates to Play all (down into the grid, right, up) → select → waits for `player.view` → screenshot `tv/build/v22-player.png` → opens the info panel Clips tab (best effort) → `tv/build/v22-clips.png` → Menu (up to 4×) → asserts `player.view` is gone and `mode.goals` is back. Skips (XCTSkip) if the default week has no goal clips.
- README tvOS section updated.

## How verified
```
cd tv && xcodebuild test -project Highlights.xcodeproj -scheme Highlights \
  -destination 'id=702267A2-2D26-4D0C-A346-AD07DBA7A5D5' -derivedDataPath build CODE_SIGNING_ALLOWED=NO
# Test Case '-[HighlightsUITests.HighlightsUITests testPlayAllOpensPlayerAndMenuReturns]' passed (23.918 seconds).
# Test Case '-[HighlightsUITests.HighlightsUITests testSeasonPickerAndGoalsMode]' passed (13.622 seconds).
# ** TEST SUCCEEDED **
```
Screenshots (viewed): `tv/build/v22-player.png` — first clip of Play all ("12' Roland Sallai", Erzurumspor 0-4 Galatasaray) with the transport bar showing the Previous/Next/Clips items and the "Clips" info tab; `tv/build/v22-clips.png` — the selector panel: "Clips · 1 of 4", rows 12'/20'/45'/80' with team logo, scorer, scoreline, running score 0–1…0–4 and week label "2. Hafta", current row emerald. Play all starting at 12' confirms minute ordering.

## Known gaps
- End-of-last-clip dismissal and Siri Remote page-skip are exercised by code paths only (the UI test does not wait ~1 min for a clip to end; `XCUIRemote` cannot swipe). `skippingBehavior = .skipItem` is tvOS 15+ API on a tvOS 17 target.
- Menu in the player always exits to the list (even when the transport bar / info panel is open) — chosen to guarantee "Back returns to the previous level"; Apple's default would first hide the chrome.
- The info-panel variant of the selector relies on AVKit sizing (`preferredContentSize` 1920×420); focus within it is handled by AVKit — verified visually in the simulator, not on a physical Apple TV.
- Simulator only this pass.
