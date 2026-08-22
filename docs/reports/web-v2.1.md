# Web client v2.1 — report (2026-08-22)

## What changed
- `web/src/goals.ts` (new): `goalRows(match)` walks goal clips (`is_goal`) in minute order, increments the side from `event.side` (Home/Away), attributes the team (null → "—"); `goalGroups(matches, onlyTeam?)` groups by match and optionally keeps only goals scored by `onlyTeam` (running score still counts every goal).
- `web/src/goals.test.ts` (new): `node --test` fixture test (ordering, null side, team filter). `npm test` script added; `tsconfig.app.json` excludes `src/**/*.test.ts` from the SPA build.
- `web/src/components/GoalsGrid.tsx`: takes `groups`; each card shows minute, scorer, running score badge (scoring side in emerald), and a footer with the scoring team's logo + name (or "—"). Match header unchanged (home logo · scoreline · away logo).
- `web/src/App.tsx`: §0 layout labels ("Season", "Week" with ‹ ›, "Team"); "Only <Team> goals" switch shown only when By team → Goals, default ON, persisted as `og=0/1` in URL (when `g=1`) and `localStorage`; goal groups memoised via the helper; Play all and single-goal playlists are built from the same filtered groups.
- `README.md` Web section: URL params incl. `og`, goal card description, file map with `goals.ts` + test command. `.gitignore`: `web/build-shots/`.
- Server: untouched (already returns `side`).

## How verified
- `cd web && npm test` → 2 tests, 2 pass, 0 fail.
- `cd web && npm run build` → `tsc -b && vite build` clean (248.9 kB JS).
- `cd server && cargo build --release` clean; server run on :8080, `GET /api/leagues` → 200.
- Headless Chrome (1440×1000, 12 s virtual time):
  - `web/build-shots/v21-goals.png` — `?l=super-lig&s=3974&r=1&mode=goals`: Season/Week labels, 24 goals · 9 matches, cards show team logo + name and running score (e.g. Galatasaray–Çorum 1–0, 1–1, 1–2, 2–2).
  - `web/build-shots/v21-team-goals.png` — `?l=super-lig&s=3853&r=34&mode=team&t=Beşiktaş&g=1`: Week hidden, "Team" picker, Matches | Goals, "Only Beşiktaş goals" ON; only Beşiktaş goals listed while scores still count opponents' goals (Rizespor 2–2 Beşiktaş → 2–1, 2–2).
- Server stopped afterwards (`pkill -f beinv-server`).

## Known gaps
- Running score assumes the upstream goal list is complete; if a goal has no clip it is missing from the count (spec-compliant, but the final running score can differ from the match score).
- Toggle OFF state and the season-change week reset were verified by code review, not screenshot.
- Android/tvOS clients not touched.
