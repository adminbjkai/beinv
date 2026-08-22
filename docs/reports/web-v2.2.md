# Web client v2.2 — report (2026-08-22) — spec §2b Playlists

## What changed
- `web/src/goals.ts`: `playlistOrder(groups)` — pure sort of goal groups by week (`round`) asc → kick-off (`date`) asc → match id; rows inside a group are already minute-asc. Used for Play all and for single-card clicks (same ordered list, positioned at the clicked goal).
- `web/src/goals.test.ts`: new `node --test` case with three matches across two weeks supplied out of order (weeks 34, 2, 2; kick-offs swapped) → expects `2/1/30, 2/1/90, 2/2/5, 34/3/10, 34/3/70`.
- `web/src/api.ts`: `PlaylistItem` gains optional `week`, `score`, `logo`; `clipItem(m, e, ctx, meta?)` spreads them in (`ClipMeta` type). Match playlists unchanged.
- `web/src/components/Player.tsx`: `Clips` button in the chrome (shown when the playlist has > 1 item) and key `c` toggle a right-side, scrollable `.glass` overlay listing every item: minute, scoring-team logo, scorer, week label, running score; current item highlighted (emerald ring/tint, `aria-current`) and scrolled into view; click jumps via `onIndex`. New prop `initialClips` (from `?clips=1`). Autoplay-next, next/prev, "x of N" untouched.
- `web/src/App.tsx`: `allGoals()` now builds items from `playlistOrder(groups)` with week label / running score / logo; `playAll()` shared by the Play all button and the new `?play=all` URL param (fires once when the visible goal groups exist); `?clips=1` passed to the Player. Both params are read-only (not written back to the URL).
- `README.md` Web section: URL params (`play=all`, `clips=1`), ordering rule, Clips overlay, key `c`, file map (`playlistOrder`).
- Server: untouched.

## How verified
- `cd web && npm test` → 3 tests, 3 pass, 0 fail.
- `cd web && npm run build` → `tsc -b && vite build` clean (251.4 kB JS).
- Server started (`./target/release/beinv-server`), `GET /api/leagues` → 200.
- Headless Chrome 1440×1000, 15 s virtual time, `?l=super-lig&s=3853&r=34&mode=team&t=Beşiktaş&g=1&play=all&clips=1` → `web/build-shots/v22-player.png`: player open, "Playing all goals · 59 clips", Clips overlay (59) on the right; first item highlighted = 15' Rafa Silva · 1. Hafta · 0–1, followed by 32'/45'/58' (1. Hafta), then 2., 3., 5., 7. Hafta — earliest week first, ascending. Only Beşiktaş logos in the list (Only-team filter ON respected).
- Server stopped afterwards (`pkill -f beinv-server`).

## Known gaps
- Key `c` and click-to-jump were verified by code review and the build, not by an interactive screenshot (headless shot uses `clips=1`).
- The Clips panel covers the right part of the chrome (Autoplay/PiP/fullscreen buttons) while open; close it with ✕ or `c`.
- Ties within the same week and kick-off fall back to match id (spec does not define this).
- Android/tvOS not touched.
