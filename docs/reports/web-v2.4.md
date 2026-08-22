# Web client v2.4 — polish / bug sweep (2026-08-22)

## Findings and fixes
| # | Check | Result |
|---|---|---|
| 1 | Row click (drawer / below list) jumps and keeps playing | OK — `onIndex` → new `item.key` → `play()` effect (code review). |
| 1 | `n`/`p`, `c`, `f`; Esc closes drawer first, then player | OK — Player listens in capture phase and stops propagation only when the drawer is open; App's Esc closes the player otherwise. |
| 1 | Up next / Previous update on every transition | OK — derived from `items[index±1]` each render; DOM dump shows `Up next: 1. Hafta · … 0–2 Beşiktaş · 32' Rafa Silva`. |
| 1 | Last clip → back to list | OK — `ended` on last index calls `onEnd` (unchanged). |
| 1 | Chrome auto-hide while drawer open | **BUG, fixed** — timer and `onMouseLeave` hid the controls under an open drawer. Now the `poke` timer, `onMouseLeave` and `visible` all respect `clips`; opening the drawer forces the chrome visible. `web/src/components/Player.tsx`. |
| 1 | PiP; Autoplay toggle persists | OK — PiP unchanged; `an` stored in `localStorage` (`beinv.v2`). |
| 2 | URL ↔ localStorage precedence | OK — `init` reads URL first, falls back to storage; state written back to both. |
| 2 | Season change resets week | OK — season `onChange` sets round to the `is_current` week or the first. |
| 2 | Premier League Goals renders | OK — correct id is `ingiltere-premier-ligi` (`premier-league` → 404 "unknown league", expected). `?l=ingiltere-premier-ligi&s=3958&r=1&mode=goals` renders the 0-goal empty state "No goal clips published yet." |
| 2 | By team restores team | OK — `?t=Trabzonspor&g=1` → "Only Trabzonspor goals", `Play all · 61 goals · from 1. Hafta`. |
| 2 | 0-goal empty state | OK — see PL check above. |
| 2 | Error + Retry with server down | Code-reviewed only: `list.isError` renders "Could not load matches … Retry" → `refetch()`. Not exercisable headlessly: the SPA is served by the same server, so with it down the page itself does not load. |
| 3 | Canonical title not truncated | **Fixed** — chrome title now `line-clamp-2`. Drawer rows/chips intentionally show the short form with the full title in `title=`. |
| 3 | Focus rings | OK — global `:focus-visible` outline in `index.css` for button/select/input/a. |
| 3 | Skeleton for By team season load | OK — visible in `v24-mobile.png` ("Loading the whole season…" + pulse cards). |
| 3 | 390 px: no horizontal scroll, list usable | **Fixed** league switch (`flex-wrap`) and root `overflow-x-hidden`. Note: desktop headless Chrome enforces a ~500 px minimum window, so `--window-size=390` crops a 500 px layout; the real 390 px layout was captured through a 390 px iframe and has no overflow. |
| 4 | `npm run lint` | 0 errors (react-hooks/refs warnings only, pre-existing patterns). |

## Changed files
- `web/src/components/Player.tsx` — auto-hide respects the open drawer; title 2-line clamp.
- `web/src/App.tsx` — league switch wraps on narrow widths; root `overflow-x-hidden`.
- `README.md` — drawer note (chrome stays visible while open).

## Verification
- `npm run lint` 0 errors · `npm test` 4/4 pass · `npm run build` clean.
- `web/build-shots/v24-drawer.png` (1440×1000, `…&play=all&clips=1`): chrome visible with drawer open — title `1. Hafta · Metro Holding Kayserispor 0–1 Beşiktaş · 15' Rafa Silva`, `Up next: …32' Rafa Silva`, `Now playing · 1 of 59`, full control bar (⏮ ⏸ ⏭ −10s +10s volume time Autoplay Clips PiP ⛶) uncovered; drawer 35 % with week groups.
- `web/build-shots/v24-mobile.png` (390 px iframe): header/mode rows wrap, no horizontal overflow, By-team skeleton.
- DOM dumps for PL goals / team restore / chrome lines as listed above. Server stopped after each run (`pgrep` empty).

## Gaps
- Error + Retry flow and interactive keys (n/p/c/f/Esc, click-outside, PiP) verified by code review, not interactively.
- Mobile shot shows the loading state (iframe + virtual-time budget did not reach the loaded list); the loaded list-below layout is in `v23-narrow.png`.
- Lint warnings about refs/setState-in-effect remain (no behavioural issue found).
