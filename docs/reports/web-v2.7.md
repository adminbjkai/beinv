# Web v2.7.1 — convenience / docs alignment (2026-08-24)

v2.7 product (Premier HD, All weeks rail, La Liga 2025/26) was already live on [beinv.bjk.ai](https://beinv.bjk.ai/). This pass pulled that tree onto the Mac checkout and closed small web gaps plus native compile.

## Checks
| # | Check | Result |
|---|---|---|
| 1 | HD remembered when URL has no `hd` | OK — `ls.hd ?? true` after URL |
| 2 | Week rail scrolls selection into view | OK — `scrollIntoView` on the selected chip |
| 3 | `[` / `]` steps weeks (All weeks ↔ rounds) | OK — ignored while the player is open |
| 4 | Docs match FEATURES (URL `r=all`, `hd`, All weeks default) | OK — README, CHANGELOG, ROADMAP, UPSTREAM_API |
| 5 | `npm test` | **PASS** (all `src/*.test.ts`) |
| 6 | `npm run lint` | 0 errors (pre-existing react-hooks warnings only) |
| 7 | Live API 2026-08-24 | Premier League week 1: 9 matches, all `has_hd`. City–Bournemouth `q=hd` → `video/mp4` 441 MB, `Accept-Ranges: bytes`. La Liga 2025/26 week 1 includes Girona 1–3 Rayo. |
| 8 | Week switch keeps previous cards | OK — `keepPreviousData` on week/season queries |
| 9 | Goals empty on PL / La Liga | OK — “Full-match highlights only — no per-goal clips.” |
| 10 | Arrow keys no longer scroll the page | OK — `preventDefault` on ←/→ in the player |

## Not in this pass
- Production deploy of the v2.7.1 SPA — live site is still the v2.7 build from earlier today.
- Interactive browser click-through of `[`/`]` and HD restore.
