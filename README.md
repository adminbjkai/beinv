# beinv — match highlights

Highlights (özetler) for **Trendyol Süper Lig**, **İngiltere Premier Lig** and **İspanya La Liga**, with league / season / week selection and video playback. Süper Lig and Premier League catalogs come from beinsports.com.tr's public endpoints (no auth); HD full-highlights are official YouTube remuxes (beIN SPORTS Türkiye / NBC Sports). La Liga 2025/2026 and 2026/2027 use the same seasons/weeks table, with official match highlights remuxed by the web server into a same-origin MP4 — see [docs/UPSTREAM_API.md](docs/UPSTREAM_API.md).

Docs: [FEATURES.md](docs/FEATURES.md) (what every client implements) · [NATIVE_RESUME.md](docs/NATIVE_RESUME.md) (**Android / tvOS Mac handoff** — web is live, native source is on `main` but unbuilt) · [ROADMAP.md](docs/ROADMAP.md) (what's next, how to contribute) · [CHANGELOG.md](docs/CHANGELOG.md) · [UPSTREAM_API.md](docs/UPSTREAM_API.md) (the beIN endpoints) · [docs/reports/](docs/reports/) (per-version implementation, QA and validation reports).

Three independent clients live in this repo — all implement the same [feature spec](docs/FEATURES.md): league → season → week rail (All weeks by default), Highlights / Goals / By-team views, HD on by default for Super Lig and Premier League, playlist player with next/prev + autoplay.

| dir | what | run |
|---|---|---|
| [`tv/`](tv/) | **Apple TV app** (SwiftUI + AVKit). Catalog from beIN; HD + La Liga play from `beinv.bjk.ai`. Source updated for v2.7, **build on Mac**. | [NATIVE_RESUME.md](docs/NATIVE_RESUME.md) |
| [`android/`](android/) | **Android app** (Kotlin, Compose, Media3 ExoPlayer). Same split. Source updated for v2.7, **build on Mac**. | [android/README.md](android/README.md) · [NATIVE_RESUME.md](docs/NATIVE_RESUME.md) |
| [`server/`](server/) + [`web/`](web/) | **Web app**: Rust (axum) API/video proxy + React SPA. | see below |

---

## Apple TV app (`tv/`)

v2.7 source is on `main`; the app has **not been built** on this pass. Mac continuation: [docs/NATIVE_RESUME.md](docs/NATIVE_RESUME.md).

- tvOS 17+, SwiftUI, AVKit `AVPlayerViewController` over an `AVQueuePlayer` (native transport bar, Siri Remote, no external packages).
- Files: `HighlightsApp.swift` (entry), `Models.swift` (lenient Codable models, leagues, `Mode`, `MatchEvent.eventTeamSide` → `Side`, `Match.goalRows` running-score computation, `Clip` playlist item with week / running score / team logo, `orderedGoalPlaylist` — the single §2b ordering), `API.swift` (actor: seasons + week GETs, season-wide TaskGroup fetch ~6 weeks at a time, in-memory caches per week/season), `BrowseView.swift` (§0 layout: league buttons → Season → mode bar Highlights | Goals | By team (+ HD) → Team / Matches|Goals / "Only <Team> goals" → left week rail (All weeks) + content; `PickerSheet` full-screen option list used for Season / Week / Team; `GoalCard` + `MatchHeader`; 3-column card grid, skeleton/empty/error+Retry), `PlayerView.swift` (queue player, info-panel `ClipListController` (native `UITableViewController`, week-grouped clip list), clips list), `Theme.swift` (charcoal `#0B0F0E`, emerald `#19C37D`).
- Selection (league/season/week/mode/team/HD/allWeeks) is remembered in `UserDefaults`; By team restores the remembered team once the season's team list is loaded. Changing the season resets to **All weeks**.
- Controls: Season / Team are bordered buttons showing the current value; **select** opens a full-screen picker. Week is a left rail (**All weeks** + rounds). HD on/off sits on the mode row. **Select** a card → playlist (full highlight + every clip, autoplays next, returns to the list after the last one); **press-and-hold** a card → clips list. Goals cards show minute, scorer, scoring team (logo + name from `eventTeamSide`) and the running score with the scoring side in emerald; grouped under a home-logo / scoreline / away-logo header; **Play all** respects the current filter (incl. `Only <Team> goals`). **Play all · N goals · from 1. Hafta** / selecting a goal card opens the same playlist ordered week → kick-off → minute (§2b), at that goal. Every item carries the canonical title `3. Hafta · Beşiktaş 2–1 Trabzonspor · 55' Jota Silva` and the subtitle `x of N · Up next: <next title>` (`Last clip` on the final one) via `externalMetadata`, shown in the native transport bar. Autoplay next (`AVQueuePlayer`, `actionAtItemEnd = .advance`); after the last clip the player returns to the list; Siri Remote right/left page-skip jumps to the next/previous clip (`skippingBehavior = .skipItem`); transport bar custom items **Previous clip / Next clip**. The clip list lives only in the swipe-down info panel's **Clips** tab (`customInfoViewControllers`, a native `UITableViewController` so focus/select are reliable on a real Apple TV): grouped by week, rows = minute · team logo · scorer · scoreline · running score, current row emerald and focused by default, select to jump (queue rebuilt at that clip, playback resumes) — the video is never covered by a custom overlay. **Menu** closes the panel / hides the chrome first; Menu with the chrome hidden exits to the list. Cards show a goal-count badge; By-team cards are labelled with their week.
- Unit tests (`HighlightsTests/PlayerCoordinatorTests.swift`, target `HighlightsTests`, app-hosted): `Coordinator.jump(to:)` rebuilds the `AVQueuePlayer` queue at the index, next/previous bounds, `orderedGoalPlaylist` order + canonical title.
- UI test (`HighlightsUITests/HighlightsUITests.swift`, target `HighlightsUITests`): drives the app with `XCUIRemote` — opens the Season picker, picks the second season, asserts the Season button label changed, switches Mode to Goals and asserts a running score (`x–y`) or the empty state is shown. A second test switches to Goals, focuses **Play all** (`goals.playall`), opens the player (`player.view`), saves screenshots to `tv/build/` (`v23-player.png` / `v24-clips.png` / `v24-jump.png`, path derived from `#filePath` so any checkout works), and — when the Clips tab is reachable — selects the second row and asserts the player jumped (`player.view` label `clip 1`), then presses Menu and asserts the browse screen is back. Controls carry `accessibilityIdentifier`s (`league.<id>`, `season.button`, `week.all`, `hd.toggle`, `mode.<highlights|goals|team>`, `team.button`, `sub.matches/goals`, `only.toggle`, `goals.playall`, `player.view`, `player.clips`, `clip.<i>`, `picker.row.<i>`, `goal.<id>`, `empty`). Run: `cd tv && xcodegen generate && xcodebuild test -project Highlights.xcodeproj -scheme Highlights -destination 'platform=tvOS Simulator,name=Apple TV 4K (3rd generation)' -derivedDataPath build CODE_SIGNING_ALLOWED=NO`.
- App icon + Top Shelf images: `Highlights/Assets.xcassets/App Icon & Top Shelf Image.brandassets` (layered stacks, parallax-ready).

The `.xcodeproj` is generated from `project.yml` with [xcodegen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`) and is git-ignored.

```bash
cd tv && xcodegen generate

# simulator
xcodebuild -project Highlights.xcodeproj -scheme Highlights \
  -destination 'platform=tvOS Simulator,name=Apple TV 4K (3rd generation)' \
  -derivedDataPath build build CODE_SIGNING_ALLOWED=NO
xcrun simctl boot 'Apple TV 4K (3rd generation)'
xcrun simctl install booted build/Build/Products/Debug-appletvsimulator/Highlights.app
xcrun simctl launch booted ai.bjk.highlights

# real Apple TV (paired in Xcode → Devices). Uses the DEVELOPMENT_TEAM in project.yml.
xcodebuild -project Highlights.xcodeproj -scheme Highlights \
  -destination 'id=<device udid from: xcrun devicectl list devices>' \
  -derivedDataPath build -allowProvisioningUpdates build
xcrun devicectl device install app --device <udid> build/Build/Products/Debug-appletvos/Highlights.app
xcrun devicectl device process launch --device <udid> ai.bjk.highlights
```

Signing uses a free personal team, so the install expires after 7 days — re-run the device build/install to renew. Not App Store–distributable (unlicensed content).

---

## Android app (`android/`)

v2.7 source is on `main`; the APK has **not been built** on this pass. Mac continuation: [docs/NATIVE_RESUME.md](docs/NATIVE_RESUME.md).

- minSdk 26, Jetpack Compose + Material3 dark theme (same charcoal/emerald palette), Media3 ExoPlayer with cross-protocol redirects enabled so `highlightVideoUrl` plays directly. HD / La Liga full highlights play from `https://beinv.bjk.ai/video/…`.
- Browse: league segmented buttons, season dropdown, HD switch, left week rail (All weeks + rounds; chips on a phone), 2-column card grid (3 on wide screens). Tap a card → player with a clip list (full highlight + goals/positions).
- Build: `cd android && ./gradlew assembleDebug` with `JAVA_HOME` pointing at a JDK 17+ (Android Studio bundles one) → `android/app/build/outputs/apk/debug/app-debug.apk` (debug-signed, sideload only). Details, `local.properties` setup and the file map are in [android/README.md](android/README.md).

---

## Web app (`server/` + `web/`)

```
browser (React SPA) ──► Rust server :8080 ──► beIN API / Akamai
                          /api/*   JSON, cached in-memory
                          /video/* Range-proxied mp4 stream
                          /        web/dist
```

```bash
cd web && npm install && npm run build     # build SPA once
cd ../server && cargo run --release        # → http://127.0.0.1:8080
```

Dev loop with hot reload: run the server, then `cd web && npm run dev` (Vite proxies `/api` and `/video` to :8080).
Env: `BIND` (default `127.0.0.1:8080`), `WEB_DIST` (default `../web/dist`), `RUST_LOG`.

Unit tests: `cd web && npm test` (`node --test src/*.test.ts`; Node ≥ 22.18 strips types natively, and `tsconfig.app.json` excludes `*.test.ts` from `tsc -b`). Lint: `npm run lint` (oxlint).
Server: `cargo clippy --all-targets` and `cargo fmt` (`server/rustfmt.toml` pins the repo's own width, so `fmt` is a no-op on a clean tree).

### Docker

[`Dockerfile`](Dockerfile) builds both halves and ships one image: Node builds the SPA, Rust builds the server, and a `debian:bookworm-slim` runtime holds just the binary plus `web/dist`. It runs as the non-root `beinv` user and has a `HEALTHCHECK` against `/api/leagues` (a static table, so no upstream call). [`.dockerignore`](.dockerignore) keeps the context to `server/` and `web/` sources — without it `COPY web/ ./` would drop the host's platform-specific `node_modules` on top of the container's `npm ci`.

```bash
docker compose up -d --build     # → http://127.0.0.1:8084
docker compose logs -f
```

[`docker-compose.yml`](docker-compose.yml) publishes container `:8080` on host `127.0.0.1:8084` and declares no volumes — the server is stateless, every cache lives in memory and is rebuilt from upstream on start.

| route | returns |
|---|---|
| `GET /api/leagues` | `[{id,name,org_id,sport_id}]` |
| `GET /api/leagues/{id}/seasons` | `[{id,name,is_current,weeks:[{round,name,is_current}]}]` (cache 1 h) |
| `GET /api/leagues/{id}/seasons/{seasonId}/weeks/{round}` | `[{id,round,title,date,home,away,thumb,has_highlight,events[]}]` (cache 5 min) |
| `GET /api/leagues/{id}/seasons/{seasonId}/matches` | every match of the season: all weeks fetched concurrently (≤ 8 in flight), merged, de-duplicated by id, sorted by date, each tagged with `round` (cache 10 min). Powers "By team". |
| `GET /video/m/{matchId}[?l=&s=&r=]` | full highlight mp4, honours `Range` |
| `GET /video/e/{eventId}[?l=&s=&r=]` | goal / position clip mp4, honours `Range` |

The `l/s/r` query lets a cold server re-fetch the week to find the video source (`r` is the match's own `round`, so it also works for matches found via the season route). It is optional and only consulted on a cache miss, so a warm server answers a bare `/video/{kind}/{id}`. The SPA keeps state in the URL and mirrors it to `localStorage` (`beinv.v2`); URL wins over storage. Params: `l` league · `s` season id · `r` week (round) · `mode` `goals` | `team` (omitted = Highlights) · `t` team name (By team) · `g=1` Goals sub-switch in By team · `og=0/1` "Only <Team> goals" toggle (default `1`, only written when `g=1`) · `m` open match id · `play=all` open "Play all" for the visible goals on load · `clips=1` open the in-player clip selector on load (the last two are read once and not written back). The autoplay-next toggle is stored only in `localStorage`.

Views (mode toggle): **Highlights** (match cards for the week) · **Goals** (goal clips grouped by match, "Play all" runs them as one playlist) · **By team** (team picker derived from the season's matches; that team's matches newest-first labelled by week, with a Matches / Goals sub-toggle and an "Only <Team> goals" toggle). Layout order follows [FEATURES.md §0](docs/FEATURES.md): League → Season → Week (hidden in By team) → Mode → mode row → content; picking a season resets the week to that season's default.

Goal cards show minute, scorer, the scoring team (logo + name from the event's `side`, "—" when unknown) and the running score after that goal (scoring side highlighted); cards are grouped under a match header (home logo · scoreline · away logo). "Play all" plays exactly the cards shown (so it respects the team filter) in playlist order week asc → kick-off asc → minute asc ([§2b](docs/FEATURES.md)); the button reads `Play all · N goals · from 1. Hafta`. Clicking a single card opens the same ordered playlist positioned at that goal. Every item carries the canonical title `3. Hafta · Beşiktaş 2–1 Trabzonspor · 55' Jota Silva` (week · scoreline at that moment · minute scorer, `goals.ts: clipTitle`); the player chrome shows it with `x of N` plus "Up next: …" / "Previous: …". **Clips** (key `c`) opens a glass side drawer (≤ 35 % width, ≥ 900 px viewports; video keeps playing, controls stay uncovered and the chrome does not auto-hide while it is open; Esc / click outside closes) listing the playlist grouped by week, current row highlighted and auto-scrolled, click to jump. The same week-grouped list is always rendered as chips below the player (match playlists too), so on narrow viewports it replaces the drawer.

- `server/src/main.rs` router + caches (seasons / weeks / season_matches / sources / resolved) · `bein.rs` upstream client + DTOs · `laliga.rs` 2026/2027 fixture overlay + official-highlight matching · `youtube.rs` yt-dlp/ffmpeg remux · `video.rs` redirect resolve + local/remote range proxy
- `web/src/App.tsx` selectors, mode toggle, team picker, playlist state, URL + localStorage sync · `api.ts` fetchers, `PlaylistItem` builders · `components/Player.tsx` playlist player (prev/next, autoplay-next toggle, "Now playing · x of N", Up next / Previous lines, Clips side drawer, space/k, ←/→ ±10 s, f, m, n/p, c, Esc, PiP, fullscreen, best-effort resume of the full highlight) · `components/MatchCard.tsx` · `components/GoalsGrid.tsx` goal cards grouped by match · `components/ClipList.tsx` week-grouped playlist (drawer rows / below-player chips) · `goals.ts` pure helpers: running score + scoring-team attribution (`goalRows`), the per-team filter (`goalGroups`), the playlist order (`playlistOrder`), the score-at-minute (`scoreAt`) and the canonical title (`clipTitle`) · `goals.test.ts` + `api.test.ts` cover those helpers and the playlist builders (including a match with a highlight but no events, which is every Premier League match)

Why a proxy for the web but not for tvOS/Android: browsers need a same-origin source for clean range streaming and to hide the 302 hop; AVPlayer and ExoPlayer handle beIN mp4s natively. La Liga highlights are remuxed on the server (`yt-dlp` + `ffmpeg` → cached H.264 MP4 under `BEINV_VIDEO_CACHE`, default `/tmp/beinv-yt`), so the web `<video>` element only ever loads `/video/…`.

## Video notes

beIN serves one progressive H.264 mp4 per highlight/clip (the only rendition → highest quality). Native decoders + byte-range streaming is the smoothest path; no HLS/ABR. La Liga 2026/2027 official highlights are remuxed to the same MP4 contract (best H.264 + AAC, typically 1080p50, `+faststart`) so the in-app player never loads an external video site. Trendyol Süper Lig has an optional **HD** toggle that remuxes the official beIN SPORTS Türkiye YouTube özet the same way; goal clips stay on the beIN mp4.
