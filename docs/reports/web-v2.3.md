# Web client v2.3 — report (2026-08-22) — spec §2b Playlists (UX rewrite)

## What changed
- `web/src/goals.ts`: `scoreAt(m, minute, eventId?)` (scoreline at that moment, the goal itself counted) and `clipTitle(week, m, score, e)` → canonical `3. Hafta · Beşiktaş 2–1 Trabzonspor · 55' Jota Silva`. `playlistOrder` unchanged.
- `web/src/goals.test.ts`: new test for `clipTitle` / `scoreAt` (4 tests total).
- `web/src/api.ts`: `ClipMeta` gains `title`; `matchPlaylist(m, league, season, meta?)` accepts a per-event meta builder so match playlists also get week label, running score, logo and the canonical title.
- `web/src/components/ClipList.tsx` (new): ordered playlist grouped by week headers; `chips` layout below the player, row layout in the drawer; current item `aria-current`, highlighted and scrolled into view; click jumps.
- `web/src/components/Player.tsx`: right overlay replaced by a glass **side drawer** (`w-[35%]`, only ≥ 900 px), video keeps playing and remains visible; chrome gradients shrink to `right-[35%]` while open so no control is covered. Toggle: Clips button / `c`; Esc (captured before the app-level Esc) and click outside close it. Chrome now shows the canonical title, "Up next: <full title>" and "Previous: <week · minute scorer>", updated on each transition, plus `x of N`.
- `web/src/App.tsx`: `clipMeta(m)` builds week/score/logo/title for every item (goal and match playlists); below-player chips replaced by the week-grouped `ClipList` for both playlist kinds; `ordered` memo feeds `firstWeek` to the Play all button; `?play=all` / `?clips=1` kept.
- `web/src/components/GoalsGrid.tsx`: button text `Play all · N goals · from <first week>`.
- `README.md` Web section updated. Server untouched.

## Why it is better for the user
- The order is explicit before pressing (button says "from 1. Hafta") and every item names its week, so a season-long playlist reads chronologically instead of as anonymous minutes.
- Next/prev are never blind: the chrome says what comes next and what was just played.
- The list never hides the video: drawer ≤ 35 % with controls kept clear on desktop; on narrow screens the same list sits below the player. Esc / click outside dismiss it like a normal drawer.
- The same week-grouped list is always available below the player, so the drawer is a shortcut, not the only way to navigate.

## How verified
- `cd web && npm test` → 4 tests, 4 pass, 0 fail.
- `cd web && npm run build` → clean.
- Server started; `GET /api/leagues` → 200. Headless Chrome (15 s virtual time) on `?l=super-lig&s=3853&r=34&mode=team&t=Beşiktaş&g=1&play=all&clips=1`:
  - `web/build-shots/v23-drawer.png` (1440×1000): drawer "Clips · 1 of 59" on the right 35 %, grouped 1./2./3./5./7./8. Hafta, 15' Rafa Silva 0–1 highlighted; video fully visible on the left; below the player the week-grouped chips (1. Hafta: 15' Rafa Silva 0–1 …).
  - `web/build-shots/v23-narrow.png` (820×1100): no drawer; video uncovered; week-grouped chip list below (1. Hafta … 9. Hafta), current chip highlighted.
  - DOM dump confirmed the canonical title text "1. Hafta · … · 15' Rafa Silva" in the chrome with "Up next: 1. Hafta · … · 32' Rafa Silva", and the button `Play all · 59 goals · from 1. Hafta` (see report log below).
- Server stopped (`pkill -f beinv-server`).

## Known gaps
- In the screenshots the player chrome (title / Up next) is auto-hidden because the clip is playing (2.2 s idle hide, pre-existing); verified via DOM dump instead.
- Drawer/below-list switch is pure CSS at 900 px (the drawer state persists across resizes).
- Click-outside / Esc / `c` verified by code review and build, not interactively.
- Android/tvOS not touched.
