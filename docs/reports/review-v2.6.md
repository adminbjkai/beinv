# v2.6 — polish, optimisation and runtime validation sweep

Follow-up to [review-v2.5.md](review-v2.5.md). Where v2.5 was a read-through audit, this pass
**built and ran every client** and verified the upstream contract live, so each finding below is
backed by an observation rather than by reading alone.

## Method

| step | result |
|---|---|
| Upstream contract re-checked live (2026-08-23) | seasons + 4 week payloads across both leagues fetched and inspected |
| Rust server run against live upstream | every route exercised, incl. a 34-week season fetch and Range video proxying |
| Web SPA driven in headless Chrome over CDP | real `mousedown`/`mouseup`/`click` ordering, before *and* after each fix |
| Android debug APK installed on an emulator | browse + match playlist screenshotted |
| tvOS app installed on the simulator | browse screenshotted; unit tests run |

Clean builds were used throughout. Two earlier "baseline" builds were incremental against stale
output directories and **under-reported warnings**; every warning count below comes from a clean or
`--rerun-tasks` build.

## Upstream re-validation

All of [UPSTREAM_API.md](../UPSTREAM_API.md) still holds, and two assumptions are now measured
rather than assumed:

- **Premier League still returns `matchEvents: null`** on every match of the current season — the
  v2.5 crash fix remains load-bearing, not historical.
- Over 877 events (Süper Lig 2025/2026, weeks 1–12): **`minute` was never null**, and `minute == 0`
  occurs *only* on `type: 1` clips (the "İlk 11'ler" line-ups item), never on a goal. The §2b
  "minute-less goals sort as 0" rule is therefore defensive only — it is spec-conformant and cannot
  currently collide with a real 0-minute goal.
- Seasons come back newest-first (`isCurrent` first), confirming the v2.5 tvOS `.first` fallback.

## Web

1. **The "Clips" button moved out from under the cursor mid-click.** `mousedown` anywhere outside
   the drawer closed it, which re-flowed the control bar from `right-[35%]` to `right-0`; the
   right-aligned button group then shifted and the button's own `click` never fired.
   Measured in Chrome: the button jumped **765 px → 1191 px** between `mousedown` and `mouseup`, and
   `elementFromPoint` at the press location returned the control-bar `div`, not the button.
   The drawer still ended up closed — via the outside handler — so the symptom was silent, but the
   button's `onClick` (and its `aria-pressed` state) was dead, and any control that happened to
   occupy the vacated position would have received the click instead. The outside-click handler now
   ignores the toggle itself. Verified before/after with the same script.
2. **Space and Enter were swallowed while the player was open.** The global key handler skipped only
   `INPUT`/`SELECT`, so pressing Space on any focused `<button>` (Close, Retry, Autoplay, a clip
   chip) toggled playback instead of activating the control — a keyboard-only user could not operate
   them. `BUTTON`, `TEXTAREA` and `contenteditable` are now excluded.
3. **Switching league re-queried with the previous league's season.** `seasonId`/`round` survived the
   switch until the new season list arrived, so the week query fired with a league/season pair that
   does not exist and the empty state flashed. Both are cleared on switch; the defaults effect then
   picks the new league's current season and week.
4. Stale comment: the season-defaults effect still said "falls back to first week" after v2.5 changed
   it to the last week.

## Server

5. **One failing week blanked an entire "By team" season.** `season_matches` propagated the first
   error out of ~34 concurrent week fetches, so a single transport hiccup emptied the whole view and
   cached nothing. Failed weeks are now logged and skipped; the request only fails if *every* week
   does. (Weeks with nothing published already decode to an empty list, so this path is reached only
   on a real error.)
6. **`/video/{kind}/{id}` rejected requests without the `l/s/r` hint.** The query was a required
   extractor, so a bare URL was rejected with 400 *before* the handler could consult the source
   cache — even on a warm cache where the hint is unnecessary. The fields are now optional and only
   consulted on a cache miss. Verified live: warm no-query request returns `206`, unknown id `404`.
7. `server/rustfmt.toml` added (`max_width = 120`, `use_small_heuristics = "Max"`) and the tree
   formatted, so `cargo fmt` is now a no-op instead of reflowing the whole file against the repo's
   own style.
8. `Cargo.lock` was out of sync with `Cargo.toml`: it still recorded `beinv-server 0.1.0` and a
   `tower-http` → `tracing` edge that v2.5's feature trim had removed. Regenerated.

## Android

9. **Every goal row in a *match* playlist showed `?'` instead of the minute.** `MatchEvent.clip()`
   never populated the `minute`, `scorer` or team `logo` the row renderer reads — only the goal-mode
   path (`GoalRow.clip()`) did. Confirmed on the emulator before and after: rows now read
   **7' / 11' / 20' / 33'** with the scorer and the correctly-attributed club badge (Fenerbahçe for
   three, Konyaspor for one, matching the 4–2 result).
10. **A completed clip kept a stale resume position.** v2.5 stopped *storing* the end position on an
    auto-transition, but a position saved earlier in that clip survived, so re-selecting it later
    resumed mid-clip. Finishing a clip now clears its entry, matching what the web player does in
    `onEnded`.
11. **`@OptIn(UnstableApi::class)` had no effect** — media3's marker is not `@RequiresOptIn`, so
    Kotlin's `OptIn` was the wrong annotation; `@androidx.annotation.OptIn` is the one that applies.
12. The app declares `supportsRtl="true"` but used four non-mirroring icons (`ArrowBack`, `List`,
    `KeyboardArrowLeft/Right`); all now use their `AutoMirrored` variants.
13. `LocalLifecycleOwner` was the deprecated Compose-UI one; switched to the
    `lifecycle-runtime-compose` package already on the classpath.
14. **`GoalsView` rebuilt the whole grouped, sorted playlist on every recomposition** — for a
    By-team season that is a running-score walk over every match plus an O(n log n) sort, redone on
    each frame. Now `remember`ed, with the `Only <Team> goals` predicate given a stable identity so
    the memo actually holds.
15. Opening one goal scanned the playlist with `indexOf`, i.e. deep structural equality against every
    `GoalRow` (each of which holds a whole `Match` and its event list). Replaced with a precomputed
    `(matchId, eventId) → position` map.
16. The clip row's second line was reassembled by slicing the formatted title
    (`substringAfter(" · ").substringBefore(" · ")`); `Clip.match` now carries it directly.
17. The landscape drawer's auto-scroll indexed `rowIndex[current]` unguarded while the portrait list
    guarded the same access — a latent out-of-bounds. Both now guard.
18. Missing scores rendered as `-`; `Match.clips()` force-unwrapped `highlightVideoUrl!!`.

## tvOS

19. **`extension Int: Identifiable`** — a retroactive conformance on a stdlib type; a warning today
    and an error under the Swift 6 language mode. Replaced with a purpose-built `StartAt` wrapper.
20. **Five Swift concurrency warnings** (pre-existing; masked by an incremental build) where the
    `AVPlayerItemDidPlayToEndTime` observer touched main-actor state from a `@Sendable` closure.
    Delivery is already on `.main`, so the body is now wrapped in `MainActor.assumeIsolated`.
    That call *traps* if the assumption is ever wrong and nothing covered the path, so
    `testEndOfItemAdvancesThenDismisses` was added: it posts a real
    `AVPlayerItemDidPlayToEndTime` and asserts the index advances mid-playlist and the player closes
    after the last clip. **The tvOS target now builds with zero warnings.**
21. **`Match.date` allocated a fresh `ISO8601DateFormatter` on every access** — and `date` is read
    inside sort comparators over a whole season, so this dominated By-team sorting. Now a shared
    `static let`.
22. Missing scores rendered as `0` (so an unreported result read "0 - 0"), against web's en dash.

## Cross-client parity

A score upstream did not report now renders as an en dash `–` on **all three** clients. Before this
pass web showed `–`, Android showed `-`, and tvOS showed `0` — the last being actively misleading,
since it is indistinguishable from a real goalless draw.

## Build status after the sweep

| target | result |
|---|---|
| `web`: `tsc -b && vite build` | clean |
| `web`: `node --test` | 8/8 pass |
| `web`: `oxlint` | 10 warnings, no errors — the same 10 as before this pass |
| `server`: `cargo check` / `clippy --all-targets` / `fmt --check` | all clean |
| `android`: `assembleDebug` (`--rerun-tasks`) | **0 warnings** (was 6) |
| `tvOS`: `xcodebuild build` (clean) | **0 warnings** (was 6) |
| `tvOS`: `HighlightsTests` | 4/4 pass (one new) |

## Not done

- **Docker was not built or run** — no Docker daemon on this machine. The Dockerfile and compose
  file were read and are unchanged since v2.5; their claims remain unverified by execution.
- The tvOS **UI test** (`HighlightsUITests`) was not run; only the unit-test target was. The UI test
  drives the Siri Remote and is slow and focus-order dependent.
- Android **rotation / PiP / fullscreen** paths were not exercised on the emulator; the fullscreen
  and resume fixes are verified by reading and by the clip-list screenshots only.
- The 10 oxlint warnings in `App.tsx` and `Player.tsx` (`exhaustive-deps`, `set-state-in-effect`,
  one `react(refs)`) are unchanged by this pass — the same 10 fire on the v2.5 tree, only at shifted
  line numbers. They are deliberate (effects that intentionally run on a subset of their deps).
  Left alone: silencing them properly means restructuring the state flow, which is a larger change
  than this sweep should carry.
