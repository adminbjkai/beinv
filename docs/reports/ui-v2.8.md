# v2.8 — cross-platform interface refinement and validation

Date: 2026-08-24
Scope: presentation, responsiveness, interaction quality, accessibility and player ergonomics across `web/`, `android/` and `tv/`. APIs, persistence, playlist semantics and server behavior are unchanged.

## Strategy

Each client received an independent platform-native audit and implementation pass, followed by an orchestrator review against [FEATURES.md](../FEATURES.md). The shared constraints were:

- preserve League → Season → Mode/Week → content behavior and exact product labels;
- preserve HD/remux routing, saved selection, chronological Play all and canonical clip titles;
- keep video visible while exposing playlist navigation;
- improve high-frequency states before adding decoration: selection, loading/progress, empty, failure/Retry, focus/touch and player transitions;
- validate at the platform boundary, not only through compilation.

## Web

### Changes

- Refined the responsive shell, full-name league rail, selector surfaces, week hierarchy and page metadata.
- Improved match/goal cards, week headers, phone clip chips and loading/empty/error panels.
- Added semantic main/status/alert/group/pressed state and reduced-motion-aware animation/scrolling.
- Split player presentation by viewport: phone controls sit below an unobscured video in two rows; desktop keeps auto-hiding overlay chrome and the ≤35% Clips drawer.
- Preserved URL/localStorage priority, HD switching, keyboard shortcuts, clip ordering and drawer behavior.

### Evidence

| Check | Result |
|---|---|
| `npm test` | PASS — 8/8 |
| `npm run build` | PASS |
| `npm run lint` | PASS — 0 errors; existing React hook/ref advisories remain |
| Chromium 1440×1000 | Live playback, drawer, clip jump 1→2, Esc, keyboard focus |
| Chromium 390×844 / 320×780 | No horizontal overflow; 320 px body and controls measured within viewport; phone controls and clip rail usable |
| Injected list `503` | Error panel → Retry → recovery PASS |
| Injected `/video/` `503` | Player alert → Retry → real playback recovery PASS |
| Persistence | HD URL/localStorage restore PASS |

## Android

### Changes

- Replaced truncated phone league segments with scrollable full-name FilterChips; wide screens retain segmented controls.
- Auto-scrolls restored league/week selection and uses 48 dp selectable week targets.
- Uses adaptive match-card columns. All-weeks remains grouped chronologically; By-team now explicitly preserves its newest-first source order and week badges.
- Added Material card borders/press treatment, stable team-logo fallbacks, structured empty/error states and clearer dark edge-to-edge system bars.
- Added semantics for switches, dropdowns, match cards and current clip rows.
- Added an ExoPlayer error/Retry surface and improved player header/drawer clarity.
- Added an API-33 monochrome launcher variant while retaining the API-26 adaptive icon; explicit backup rules preserve `allowBackup=false` intent.

### Evidence

| Check | Result |
|---|---|
| `./gradlew testDebugUnitTest assembleDebug lintDebug --no-daemon` | PASS (`testDebugUnitTest` has no sources) |
| APK | PASS — 13 MB debug APK installed/relaunched on Pixel_9 |
| Browse | Super Lig All weeks/Goals/By team; Premier highlights/data-empty Goals; La Liga browse |
| Ordering | By-team 2. Hafta before 1. Hafta verified (newest first) |
| Player | Live match/goal playlists, next/previous labels, clip jump 1→2 of 10, portrait list |
| Landscape / PiP | Immersive player, 35% drawer, Back order and PiP over launcher PASS |
| Wide layout | Simulated 800 dp tablet vertical week rail/adaptive cards PASS |
| Runtime | No `FATAL` / `AndroidRuntime` crash in logcat |

Android lint has no source/resource correctness errors. It reports dependency-update notices plus qualifier notices for the intentionally retained v26 adaptive icon and separate v33 monochrome variant. The playback-error surface was compiled but not forced with a broken media URL; TalkBack speech was not manually walked, although semantics were inspected through UI automation.

## tvOS

### Changes

- Made HD a compact native focusable control instead of allowing the default Toggle to consume the mode row.
- Added persistent emerald selection markers while leaving focused foreground contrast to the native tvOS button treatment.
- Contained and clarified the week rail; refined loading/progress/empty/error panels and picker hierarchy.
- Improved match cards with short unclipped dates, team names, week badges, borders and clearer score hierarchy.
- Increased native Clips info-panel row density/readability and added current/focused accessibility labels.
- Enriched the press-and-hold clip picker with thumbnails and remote-sized card targets.
- Added an XCUIRemote regression for HD focus and action.

### Evidence

| Check | Result |
|---|---|
| `xcodegen generate` | PASS |
| Apple TV 4K (3rd generation) simulator build | PASS |
| `HighlightsTests` | PASS — 4/4 |
| `HighlightsUITests` | PASS — 3/3, including HD focus/action |
| Simulator install/launch | PASS |
| Runtime walk | Browse, AVKit playback, Clips info panel, clip jump and Menu return PASS |
| Visual QA | 1080p browse capture checked for focus contrast, safe margins and unclipped short dates |

The v2.8 tvOS pass was validated on the simulator, not reinstalled on the physical Living Room Apple TV. Xcode's AppIntents metadata-skip diagnostic and transient XCTest scene-snapshot notices did not produce compiler warnings or test failures.

## Repository verification

| Check | Result |
|---|---|
| `cargo fmt --check` | PASS |
| `cargo clippy --all-targets` | PASS, no warnings |
| `cargo test` | PASS — 14/14 |
| `git diff --check` | PASS |

## Alignment result

- No API, model, persistence or server behavior changes. The current Rust formatter was applied and two convenience wrappers used only by unit tests are now compiled only for tests, removing Clippy dead-code warnings.
- Exact league/mode/team labels and charcoal `#0B0F0E` / emerald `#19C37D` identity remain aligned.
- Playlist ordering, autoplay/end behavior, next/previous metadata and goal attribution remain aligned.
- Platform differences are intentional: phone/wide navigation, Material semantics and PiP on Android, responsive DOM/player layout on web, and native focus/AVKit behavior on tvOS.
