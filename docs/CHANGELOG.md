# Changelog

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
