# tvOS app — v2.3 report (2026-08-22) — §2b Playlists, user-experience rewrite

## What changed (vs v2.2)
- **Canonical titles** (`tv/Highlights/Models.swift`, `PlayerView.swift`): `Clip.canonicalTitle` = `3. Hafta · Beşiktaş 2–1 Trabzonspor · 55' Jota Silva` (new `Match.score` with en dash). Each `AVPlayerItem` gets `externalMetadata` title = canonical title, subtitle = `x of N · Up next: <next canonical title>` (`x of N · Last clip` on the final item). Items are built per index when the queue is (re)built, so the native transport bar / info panel shows the right title and "up next" on every item, including after skip/jump.
- **No custom overlay**: `ClipSelectorHost` / `ClipSelectorView` and the transport-bar "Clips…" action are removed. The clip list is only the native swipe-down info panel's **Clips** tab (`customInfoViewControllers`, `ClipListView`): grouped by week headers, rows = minute · team logo · scorer · scoreline · running score; current row emerald and **focused by default** (`@FocusState` set on appear, scrolled to center); select → `play(from:)` rebuilds the queue at that index. Previous/Next transport items and `skippingBehavior = .skipItem` kept.
- **Play all** button (`BrowseView.swift`): `Play all · N goals · from 1. Hafta` (first week of the ordered list).
- **Menu — Apple-standard**: the Menu press gesture on the player view now has `cancelsTouchesInView = false` and only calls `dismiss` when the chrome is hidden; visibility is tracked through `AVPlayerViewControllerDelegate.playerViewController(_:willTransitionToVisibilityOfTransportBar:with:)`. First Menu closes the info panel / hides the transport bar; Menu with chrome hidden exits to the list.
- **UI test**: screenshots renamed `v23-player.png` / `v23-clips.png`; the info-panel step is best effort and not asserted; assertion = player opens via `goals.playall` and Menu (≤4 presses) returns to the browse screen.
- README tvOS section updated.

## Why this is better for the user
- The user always knows *where* they are in the season: the week is the first thing in every title, "x of N" and "Up next: …" are in the same native chrome, so next/previous are never blind.
- The video is never covered: the list is the standard swipe-down panel, which every Apple TV user already knows, and Menu behaves as it does in every other tvOS player (panel → chrome → exit) instead of dropping the user out of playback.
- The Play all button states the count and the starting week, so the ordering is obvious before pressing.

## How verified
```
cd tv && xcodebuild test -project Highlights.xcodeproj -scheme Highlights \
  -destination 'id=702267A2-2D26-4D0C-A346-AD07DBA7A5D5' -derivedDataPath build CODE_SIGNING_ALLOWED=NO
# Test Case '-[HighlightsUITests.HighlightsUITests testPlayAllOpensPlayerAndMenuReturns]' passed (23.803 seconds).
# Test Case '-[HighlightsUITests.HighlightsUITests testSeasonPickerAndGoalsMode]' passed (13.511 seconds).
# ** TEST SUCCEEDED **
```
- `tv/build/v23-player.png` (viewed): transport bar title **"2. Hafta · Erzurumspor FK 0–4 Galatasaray · 12' Roland Sallai"**, subtitle "2026 • 1 of 4 · Up next: 2. Hafta · Erzurumspor FK 0–4 Galatasaray · 20' Yunus A…", Prev/Next items, "Clips" info tab. Title starts with the week.
- `tv/build/v23-clips.png` (viewed): info panel Clips tab with "2. Hafta" header, rows 12'/20'/45'/80' (logo, scorer, scoreline, 0–1…0–4), current row emerald with the focus ring on it.
- Menu behaviour checked with a temporary log in the test: after the first Menu press (info panel open) `player.view` still existed; a subsequent press exited the player. Log removed afterwards.

## Known gaps
- Chrome visibility comes from the transport-bar delegate callback; AVKit reports the info panel as part of that visibility in the simulator, but this is not documented — verify on a physical Apple TV.
- End-of-last-clip dismissal and Siri Remote page-skip are exercised by code paths only (test does not wait for a clip to end; `XCUIRemote` cannot swipe).
- Week header inside the info panel is plain text (the panel's own focus/scroll chrome is AVKit's); simulator only this pass.

- Orchestrator follow-up: `Clip.matchScore` now carries the running score at that goal (was the final result) — parity with web/Android canonical titles; UI tests re-run green, redeployed to Apple TV.
