# Changelog

## v2.7 — 2026-08-24
- **Premier League HD**: official NBC Sports YouTube full-match highlights (`Manchester City v. Bournemouth | PREMIER LEAGUE HIGHLIGHTS | 8/23/2026 | NBC Sports`, match `1510542` = `8bWcZxr_bKE`) remuxed the same way as Süper Lig. Matching uses club aliases + kickoff date so prior seasons do not collide.
- **HD on by default** for Trendyol Süper Lig and Premier League on web, Android and tvOS. Toggle still turns it off (beIN cut). Goal clips stay on beIN. Native HD/La Liga playback goes through `https://beinv.bjk.ai/video/…`.
- **Week rail**: Highlights/Goals default to **All weeks** in one view, grouped by week, with a left-side week list (chips on a phone). Picking a week filters; deep links `r=<n>` still open that round.
- **İspanya La Liga 2025/2026** (beIN season `3850`, slug `laliga-easports-2025`) overlay alongside 2026/2027. Native clients load La Liga weeks from the remux host instead of empty beIN events.

## v2.6 — 2026-08-24
- **İspanya La Liga** added to the league switch (all clients). Web 2026/2027 weeks list official match highlights (LaLiga fixtures + LALIGA channel, refreshed every 5 minutes for the rest of the season). The longest matching cut is used so landscape 1080p50 wins over the vertical Shorts-style duplicate. The server remuxes the highest H.264 + AAC (typically **1080p50 + stereo AAC**) to a same-origin MP4; opening a week prefetches remuxes so playback is usually instant. Falls back to 360p only if the HQ fetch fails. Goals mode is empty by data, same as Premier League. Android/tvOS list the league (seasons from beIN) and show the existing empty-week state because beIN has no La Liga mp4s.
- Web: league chips scroll on a narrow phone; player `object-contain` + Retry overlay; Help & shortcuts at the bottom. Trendyol Süper Lig has an **HD** toggle that plays the official beIN SPORTS Türkiye YouTube özet (1080p remux) for this season’s matches; goal clips stay on the beIN feed. New matchdays are matched automatically.
- Polish/optimisation sweep, with every client **built and run** and the upstream contract
  re-verified live. Fixes: web's "Clips" button moved out from under the cursor mid-click so its own
  click never fired; web swallowed Space/Enter on focused buttons while the player was open;
  switching league re-queried with the previous league's season; one failing week blanked a whole
  "By team" season on the server; `/video/*` rejected requests without the `l/s/r` hint even on a warm
  cache; Android showed `?'` instead of the minute on every goal row of a *match* playlist (and no
  club badge); Android kept a stale resume position after a clip finished.
- Warnings to zero: Android (6 → 0, incl. an `@OptIn` that had no effect and four non-mirroring icons
  in an RTL-enabled app) and tvOS (6 → 0, incl. a retroactive `Int: Identifiable` conformance that is
  a Swift 6 error and five main-actor/`@Sendable` warnings).
- Performance: Android stopped rebuilding the grouped, sorted goal playlist on every recomposition and
  stopped scanning it with deep-equality `indexOf`; tvOS stopped allocating an `ISO8601DateFormatter`
  inside season-wide sort comparators.
- Parity: an unreported score is an en dash on all three clients (was `–` / `-` / `0`).
- Details of the polish pass, including what was *not* verified, in [reports/review-v2.6.md](reports/review-v2.6.md).

## v2.5 — 2026-08-23
- Repo-wide review sweep. Fixes: web crashed opening any match with no events (every Premier League match); web used the *first* week as the default instead of the last (FEATURES §1); Android re-selecting a played clip seeked to its last frame (and closed the player on the final clip); Android leaving fullscreen hard-locked portrait for the rest of the session; tvOS fell back to the *oldest* season and could cache an empty "By team" season permanently.
- Parity: identical goal-count pluralisation and "Full highlight" wording on all clients; minute-less goals now sort the same way everywhere.
- Tidy: dead code and unused deps/imports removed, Docker files documented and hardened, template favicon replaced. Details in [reports/review-v2.5.md](reports/review-v2.5.md).

## v2.4 — 2026-08-22
- Bug-fix & polish sweep on all clients. tvOS: selecting a clip from the info-panel Clips tab now jumps (was a no-op on device). Details per client in docs/reports/*-v2.4.md.

## v2.3 — 2026-08-22
- Playlist UX rethink: week-labelled canonical titles, Up next / Previous in the chrome, clip list below the player (phone portrait), ≤35% side drawer (landscape/desktop), native info panel on tvOS; Play all shows count + first week.

## v2.2 — 2026-08-22
- Playlists: chronological Play all (week → kick-off → minute), autoplay next, next/prev everywhere, in-player glass clip selector (web/Android/tvOS).
- Running score now counts every goal (clip or not) on all clients; clip-less goals are not rendered/playable.

## v2.1 — 2026-08-22
- Parity pass across clients (identical layout/labels), season selectable everywhere, goal cards show scoring team + running score, `Only <Team> goals` filter in By team.

## v2 — 2026-08-22
- All clients: season picker always visible, remembered selection, Goals mode (+ Play all), By-team season view, player next/prev/autoplay, fullscreen (Android/web), polish.

## v1 — 2026-08-22
- Initial web app (Rust axum proxy + React SPA), Apple TV app (SwiftUI/AVKit), Android app (Compose/ExoPlayer).
- Upstream endpoints reverse-engineered and documented.
