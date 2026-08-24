# Roadmap

Status legend: ✅ done · 🔨 in progress · ⏳ planned

## v1 (2026-08-22) ✅
- Web (Rust proxy + React), Apple TV, Android clients: league → season → week → highlight playback, clip list.

## v2 — "complete browsing" ✅ (see [FEATURES.md](FEATURES.md))
- Always-visible season picker, remembered selection.
- Goals-only mode with Play all.
- By-team view across a season.
- Player: fullscreen, next/prev clip, autoplay next.
- Polish: skeletons, badges, focus states.

## v2.1 — parity ✅
- Identical layout/labels on all clients; season selectable everywhere; goal cards show scoring team + running score; `Only <Team> goals` filter; tvOS XCUIRemote UI test.

## v2.2 — playlists ✅
- Chronological Play all; autoplay next; next/prev; in-player clip selector.

## v2.3 — playlist UX ✅
- Week-labelled titles, Up next/Previous, clip list below player (portrait) / side drawer (landscape, web) / native info panel (tvOS).

## v2.4 — polish ✅
- Bug sweep (tvOS Clips-tab jump, restore/reset edge cases, visual consistency) on all clients.

## v2.5 — review sweep ✅
- Whole-repo audit: correctness fixes on all three clients, cross-client parity of wording and
  ordering, dead code / unused dependencies removed, Docker packaging documented and hardened,
  docs re-checked line by line against the code ([reports/review-v2.5.md](reports/review-v2.5.md)).

## v2.6 — La Liga + polish ✅
- Third league `İspanya La Liga`. Web: 2026/2027 highlights via LaLiga public fixtures + official LALIGA remux (`yt-dlp`/`ffmpeg`, typically 1080p50 + stereo AAC) through `/video`. Native clients expose the league; weeks stay empty until beIN publishes mp4s.
- Web Süper Lig **HD** toggle: official beIN SPORTS Türkiye YouTube özets remuxed the same way, automatic for 2026/2027.
- Every client built and run (emulator / simulator / headless Chrome / live server) rather than only
  read; upstream contract re-measured. Correctness and a11y fixes on web, resilience fixes on the
  server, a visible metadata bug and hot-path allocations on Android, warnings to zero on Android and
  tvOS ([reports/review-v2.6.md](reports/review-v2.6.md)).

## v3 ⏳
- Favourite team: app opens on that team's latest highlights.
- Search (team / player name in event descriptions).
- Point Android/tvOS La Liga playback at the web remux proxy (or wait for beIN mp4s).
- Other beIN leagues (1. Lig, Ligue 1/2, Portugal, Basketball) — same endpoints, different `orgId` ([UPSTREAM_API.md](UPSTREAM_API.md) lists them).
- Top Shelf (tvOS) / home-screen widget (Android) with latest goals.
- Offline cache of the last fetched week.

## Engineering backlog
- Shared test fixtures: record one week's JSON under `docs/fixtures/` for unit tests across clients.
- Release signing for Android (keystore) and a TestFlight-free sideload script for tvOS.
- Monitor upstream: if `highlights/events` shape changes, all clients' lenient decoders return empty lists — add a smoke check script.

## How to work on this repo
1. Change the spec first ([FEATURES.md](FEATURES.md)), then implement per client.
2. Keep each client's README file-map accurate; root README lists clients and run commands.
3. Add a line under the relevant version here and in [CHANGELOG.md](CHANGELOG.md).
