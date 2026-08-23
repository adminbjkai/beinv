# Repo review sweep — v2.5 (2026-08-23)

Whole-repo audit of the server, the three clients and every doc: find real defects, remove
cruft, and make the docs match the code. No new features.

## Correctness fixes

| # | Client | Defect | Fix |
|---|---|---|---|
| 1 | web | Opening a match with a highlight but **no events** threw `Cannot read properties of undefined (reading 'minute')`: `matchPlaylist` passed `m.events[0]` into the event-keyed `meta` callback. Per [UPSTREAM_API.md §B](../UPSTREAM_API.md) *every* Premier League match has `matchEvents: null`, so clicking any of those cards did nothing, and a `?m=` deep link to one threw inside an effect and blanked the page. | Guard on the first event; `api.test.ts` covers it. `web/src/api.ts` |
| 2 | web | Default week was the **first** week; [FEATURES §1](../FEATURES.md) and both native clients use `currentWeekForFixture`, else the **last**. | `defaultWeek()` helper used by both call sites. `web/src/App.tsx` |
| 3 | server | SPA fallback used `not_found_service`, which *forces* a 404 — client-side routes were served the SPA with a 404 status. | `.fallback(ServeFile…)`, which preserves 200. `server/src/main.rs` |
| 4 | Android | `onPositionDiscontinuity` stored a resume position on **auto-transition**, where the position is the clip's end. Re-selecting an already-played clip seeked to its last frame and instantly skipped on; on the final clip `STATE_ENDED` fired and closed the player. | Ignore `DISCONTINUITY_REASON_AUTO_TRANSITION`. `PlayerScreen.kt` |
| 5 | Android | Leaving fullscreen set `SCREEN_ORIENTATION_PORTRAIT` — a hard lock, so the user could not rotate back to landscape for the rest of the player session. The `BackHandler` was live whenever the device was merely rotated, so one Back press was enough. | Release to `SCREEN_ORIENTATION_UNSPECIFIED` and track an `fsDismissed` flag (re-armed on rotation to portrait). `PlayerScreen.kt` |
| 6 | tvOS | Season fallback was `seasons.last` — the **oldest** season, since the list is newest-first. Web and Android both fall back to the newest. | `seasons.first`. `BrowseView.swift` |
| 7 | tvOS | `loadSeason()` ran with no rounds when the week list had not arrived, and `API.seasonMatches` caches unconditionally — so "By team" could cache an empty season for the whole run. Android already had this guard. | `guard !rounds.isEmpty`. `BrowseView.swift` |
| 8 | tvOS | The UI test wrote screenshots to a hardcoded author-machine path, so no checkout but the original produced them (silently, via `try?`). | Derive from `#filePath`. `HighlightsUITests.swift` |

## Cross-client parity

- **Pluralisation**: web and tvOS rendered `Play all · 1 goals`. Both now match Android's `1 goal`.
- **Wording**: tvOS labelled the first playlist item `Full highlights`; web and Android say `Full highlight`. tvOS aligned — [FEATURES §0](../FEATURES.md) requires identical labels.
- **Ordering**: a goal with no minute sorted *last* on Android (`?: Int.MAX_VALUE`) and *first* on web/tvOS. Android aligned to `?: 0`, and FEATURES §2b now states the rule.

## Tidy

- `server/src/main.rs`: dropped the dead `_assert_into_response` and its now-unused `IntoResponse` import.
- `server/Cargo.toml`: tower-http `cors` + `trace` features were enabled but never used — removed. Version `0.1.0` → `2.5.0`.
- `web/src/components/Player.tsx`: `toggle` / `fullscreen` were ternaries-as-statements (2 oxlint warnings) and dropped their promise rejections.
- `web/src/App.tsx` + `GoalsGrid.tsx`: removed the unused `Match` argument from `onPlay`.
- `web/public/`: deleted `icons.svg`, an unrelated social-icon sprite that nothing referenced. `favicon.svg` was a **purple** template logo — unreferenced, and [FEATURES §4](../FEATURES.md) says no purple anywhere; replaced with a charcoal/emerald mark and linked from `index.html`.
- `web/index.html`: `theme-color` was `#0a0a0f`, not the palette's `#0B0F0E`; added the `fonts.gstatic.com` preconnect (only `fonts.googleapis.com` was there, and the font files come from gstatic).
- `android/.../PlayerScreen.kt`: three unused imports (`itemsIndexed`, `LazyListState`, `WindowCompat`) and one redundantly fully-qualified `Color.Black`.

## Packaging

`Dockerfile` and `docker-compose.yml` were untracked and undocumented. Now: a `.dockerignore` (without it `COPY web/ ./` overwrites the container's `npm ci` output with the host's platform-specific `node_modules` and the build breaks), a non-root `beinv` user, a `HEALTHCHECK` on `/api/leagues`, no phantom `beinv_data` volume (the server is stateless), and a Docker section in the README.

## Doc drift corrected

- `docs/FEATURES.md` header said v2.3; `android/README.md` said "v2.3 spec".
- FEATURES §2b claimed every client shows a "Previous:" line — tvOS only emits "Up next" / "Last clip".
- README described Android as having "season/week dropdowns with ‹ ›"; only the week row has steppers.
- README's tvOS accessibility-identifier list omitted `league.<id>`.
- README and `android/README.md` hardcoded the author's macOS paths (`/Applications/Android Studio.app/…`, `sdk.dir=/Users/m17/…`, `~/Library/Android/sdk/…`), which no Linux clone can follow.

## Verification

| Check | Result |
|---|---|
| `cd web && npm test` | 8 passed, 0 failed (4 pre-existing + 4 new in `api.test.ts`) |
| `cd web && npm run build` | exit 0 (`tsc -b` + vite) |
| `cd web && npx oxlint` | 0 errors; the 2 real warnings are gone. 10 remain, all deliberate patterns (mount-once effects, the `latest` ref) |
| `docker build` | exit 0 — this is what compiles the Rust server, since the host has no C linker |
| container smoke test | runs as `uid=10001(beinv)`; `HEALTHCHECK` reports `healthy`; `/api/leagues` returns both leagues; `/` and `/favicon.svg` serve 200 |

**Not verified here**: the Kotlin and Swift changes are not compiler-checked — this machine has no
JDK/Android SDK and no Xcode. They are small and local, but they need a build on a dev machine
before release.

## Known gaps (deliberately not changed)

- tvOS does not persist the By-team sub-state (`teamGoals`, `onlyTeamGoals`) and keys its
  preferences globally rather than per league; Android does both per league. A behaviour change,
  left for a follow-up.
- Android's dependency stack (AGP 8.5.2, Kotlin 2.0.21, Compose BOM 2024.09.03, media3 1.4.1,
  `compileSdk`/`targetSdk` 34) and tvOS's `SWIFT_VERSION: "5.0"` are frozen well behind current.
  Upgrading needs a device build to validate, so it is not part of this sweep.
- `android/app/build.gradle.kts` declares `compose-ui-tooling-preview` (no `@Preview` in the tree)
  and `lifecycle-runtime-compose` (no direct use); `Models.kt` force-unwraps `highlightVideoUrl!!`,
  safe only because `Api.matches` filters blanks first.
