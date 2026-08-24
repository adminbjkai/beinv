# Native resume (Android + tvOS) after the v2.8 UI pass

> **Start here on the Mac.** The v2.7 product/backend remains live on
> [beinv.bjk.ai](https://beinv.bjk.ai/). The v2.8 checkout has been browser-tested
> locally, built/installed/walked on Pixel_9, and built/installed/UI-tested on the
> Apple TV 4K simulator. v2.7.1 remains the latest physical Living Room Apple TV
> install (`D40125DD-…`). Remaining outside this source task: production web deploy
> and, if desired, installing v2.8 on the physical Apple TV.

Spec to match: [FEATURES.md](FEATURES.md) v2.8.
Upstream: [UPSTREAM_API.md](UPSTREAM_API.md) §§C–F.
Current UI validation: [reports/ui-v2.8.md](reports/ui-v2.8.md).

---

## 1. What is already live (do not re-implement)

Reference the web app while you work. Web is the source of truth for product
data and behavior; visual treatment remains platform-native.

| Feature | Web / backend (verified 2026-08-24) |
|---|---|
| **Premier League HD** | Official NBC Sports remux. City–Bournemouth week 1 match `1510542` = YouTube `8bWcZxr_bKE`. Playback: `/video/m/1510542?l=ingiltere-premier-ligi&s=3958&r=1&q=hd` → `video/mp4`, **1920×1080** H.264 + AAC, no YouTube iframe. All 9 week-1 matches `has_hd: true`. |
| **Süper Lig HD** | Official beIN SPORTS Türkiye remux. Default **on**. Goal clips stay on beIN. Season `3974` only. Example: Beşiktaş week 2 `1515722` = `QJ31yOM88UQ`. |
| **HD default** | Super Lig + Premier League: toggle labelled `HD`, on unless `hd=0`. La Liga has no toggle (the remux *is* the only source). |
| **Week rail** | Highlights / Goals: left **Week** list. First item **All weeks** (default) = whole season, grouped by week with a `1. Hafta` header. Picking a week filters. Phone: horizontal chips. Deep link `r=1` still opens that week. Hidden in By team. Changing season resets to All weeks. |
| **La Liga 2026/2027** | beIN season `3968`, slug `laliga-easports-2026`. Overlay when beIN `highlights/events` is empty. |
| **La Liga 2025/2026** | beIN season `3850`, slug `laliga-easports-2025`. Week 1 already returns **10** FullTime highlights (Girona 1–3 Rayo … Real Madrid 1–0 Osasuna). Later weeks fill automatically (score-matched official RESUMEN). |
| **Native video host** | `https://beinv.bjk.ai` — HTTPS MP4 with `Accept-Ranges`. First-ever remux of a new YouTube id can take ~15 s; after that it is cached. User-Agent already used on Android ExoPlayer. |

Live URLs to keep open in a browser while testing native:

- [City–Bournemouth HD](https://beinv.bjk.ai/?l=ingiltere-premier-ligi&s=3958&r=1&m=1510542)
- [Premier League all weeks](https://beinv.bjk.ai/?l=ingiltere-premier-ligi&s=3958&r=all)
- [Süper Lig (HD on)](https://beinv.bjk.ai/?l=super-lig)
- [La Liga 2025/2026 week 1](https://beinv.bjk.ai/?l=ispanya-la-liga&s=3850&r=1)
- [La Liga 2026/2027 week 2 (Atleti)](https://beinv.bjk.ai/?l=ispanya-la-liga&s=3968&r=2&m=102260)

---

## 2. What is already in the native source

The v2.7 data/playback port is established and v2.8 adds a verified native UI
refinement pass. Do not re-port the server matching/remux logic to either client.

### Shared behaviour (both apps)

- HD toggle on Super Lig + Premier League, **default ON** (prefs / UserDefaults key `hd`).
- Full-highlight URL when HD (or La Liga):  
  `https://beinv.bjk.ai/video/m/{matchId}?l={leagueId}&s={seasonId}&r={round}&q=hd`
- Goal clips **unchanged** (still beIN `sourceVideoUrl`).
- Super Lig / Premier League **catalog** still from beIN (so goal events keep working).
- La Liga **catalog + video** from `https://beinv.bjk.ai/api/leagues/ispanya-la-liga/seasons/{id}/weeks/{round}` (beIN events are empty).
- **All weeks** is the Highlights/Goals default (`allWeeks`, default `true`). Season fetch of every round, cards labelled with the week name. Picking a week filters. Hidden in By team.

### Android (`android/`)

| File | What changed |
|---|---|
| `Models.kt` | `BEINV`, `League.usesHdToggle()`, `hdVideoUrl()`, `Match.playable(...)`, `BeinvMatch` DTO |
| `Api.kt` | La Liga weeks decoded from the beinv JSON list, not beIN `highlights/events` |
| `Prefs.kt` | `hd` (default true), `allWeeks` (default true) |
| `BrowseScreen.kt` | Full-name phone league chips; adaptive grid; auto-scrolling `WeekRail`; preserved By-team newest-first order; Material cards/logo fallbacks; structured states and semantics |
| `PlayerScreen.kt` | Accessible clip selection, explicit playback Retry, portrait list, landscape 35% drawer and PiP |

Build (Mac):

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
# first clone: echo "sdk.dir=$HOME/Library/Android/sdk" > android/local.properties
cd android
./gradlew assembleDebug --no-daemon
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n ai.bjk.highlights/.MainActivity
```

Emulator DNS: boot with `-dns-server 8.8.8.8,1.1.1.1` or `beinsports.com.tr` / `beinv.bjk.ai` will not resolve.

### tvOS (`tv/`)

| File | What changed |
|---|---|
| `Models.swift` | `League.usesHdToggle` / `isLaLiga`, `Beinv` host helpers, `BeinvMatch`, `Match.playable(...)` |
| `API.swift` | La Liga weeks from `Beinv.host` `/api/leagues/.../weeks/{round}` |
| `BrowseView.swift` | Compact HD control, focus-safe selected markers, contained week rail, richer cards/pickers/state panels; `hd` + `allWeeks` remain persisted |
| `PlayerView.swift` | Taller accessible native Clips rows and richer press-and-hold clip selection; AVKit queue behavior unchanged |

Build (Mac):

```bash
cd tv
brew install xcodegen   # if needed
xcodegen generate
xcodebuild -project Highlights.xcodeproj -scheme Highlights \
  -destination 'platform=tvOS Simulator,name=Apple TV 4K (3rd generation)' \
  -derivedDataPath build build CODE_SIGNING_ALLOWED=NO
```

Device install still uses the `DEVELOPMENT_TEAM` in `project.yml` (7-day free-team expiry).

---

## 3. Mac checklist (do these in order)

Tick against the **web** URLs in §1. Screenshot into `android/build/` and `tv/build/` if you add a report.

### A. Compile
- [x] Android v2.8 `testDebugUnitTest assembleDebug lintDebug` green — 13 MB `android/app/build/outputs/apk/debug/app-debug.apk`; installed/launched on Pixel_9 (no Android unit-test sources)
- [x] tvOS v2.8 `xcodegen generate` + simulator build/test green — 4 unit + 3 `XCUIRemote` UI tests; installed/launched on Apple TV 4K simulator
- [x] Web v2.8 tests/build/lint green (lint: no errors; existing hook advisories), plus Chromium checks at 1440/390/320 px

### B. HD (Super Lig + Premier League)
- [x] Super Lig: HD switch is **on** on first launch (Pixel_9). Played Galatasaray–Çorum (`1 of 15`).
- [x] Toggle HD **off** → same card still plays (`1 of 15`, beIN extras like `İlk 11'ler`). Goal chips stay on beIN.
- [x] Premier League week 1: **Manchester City 2–1 Bournemouth** opened (`1 of 1` · Full highlight · no YouTube chrome). Same remux `ffprobe`’d at **1920×1080** H.264.
- [x] Cached remux Range GET of City–Bournemouth returned in **0.02 s**. First-ever remux of a new YouTube id can still take ~15 s (by design).
- [x] HD preference survives process death (relaunch still had HD on / last league).

### C. Week rail + All weeks
- [x] Highlights opens on **All weeks** (not a single round).
- [x] Left rail (tv / tablet) or chips (phone): **All weeks** + every `N. Hafta`. Current week dotted on Android.
- [x] Cards (or section headers) show the week name (`1. Hafta · 9 matches` / `10 matches`).
- [x] Tapping `1. Hafta` filters to that round.
- [x] Changing season resets to All weeks (La Liga 2025/2026).
- [x] By team still hides the week rail and loads the whole season (A–Z team list).
- [x] Goals + All weeks: Super Lig `Play all · 43 goals · from 1. Hafta`. Premier League / La Liga Goals stay empty by data.

### D. La Liga
- [x] Season picker includes **2025/2026** and **2026/2027**.
- [x] 2025/2026 week 1: **10** cards, including Girona 1–3 Rayo. Played Girona (`1 of 1` Full highlight).
- [x] 2026/2027 week 2: Atleti–Villarreal (`102260`) opened on Pixel_9 (`1 of 1` · Full highlight). Remux `ffprobe` **1920×1080 @ 50 fps** (landscape).
- [x] Unplayed future weeks: La Liga 2026/27 `3. Hafta` shows `No highlights published for this week yet.` (no crash).

### E. Regression (v2.6 behaviour that must still work)
- [x] Super Lig Goals: running score, scoring-team, Play all week → kick-off → minute (`53' Osimhen` then `58' Kyziridis`).
- [x] By team team list A–Z (Only \<Team\> goals control present).
- [x] Player next/prev chrome (`1 of N`, Up next) on Android.
- [x] Android PiP floats over the launcher; landscape immersive player, 35% drawer, clip jump and Back order were walked on Pixel_9 in v2.8.
- [x] tvOS Clips tab: v2.8 simulator `HighlightsUITests` opens `player.clips`, jumps, and returns with Menu; v2.7.1 previously passed the same path on the Living Room Apple TV.

### F. UI tests / identifiers
tvOS UI tests do **not** depend on `week.prev` / `week.button` (those controls were removed). Current ids include `week.all` and `hd.toggle`; `testHdToggleIsRemoteOperable` protects focus/action behavior. If a test fails because focus order changed, verify the actual focus graph before changing either the test or UI.

---

## 4. Known gaps / release state

1. **Week section headers.** Done in v2.7.1 — All-weeks grids group under `N. Hafta · M matches` on Android and tvOS.
2. **tvOS HD control.** Done in v2.7.1 — `Toggle("HD")` on the mode row (`hd.toggle`).
3. **First remux delay.** Softened in v2.7.1: after a week/season loads, native `GET`s the same `beinv.bjk.ai` week/season route the web app uses, which starts `youtube::warm`. First tap of a never-seen YouTube id can still take ~15 s.
4. **All-weeks first load of La Liga 2025/26** hits 38 weeks through the proxy; some weeks still search YouTube on the server the first time. Show the existing “Loading season… x/N” and do not assume it is instant.
5. **v2.8 validation report.** [ui-v2.8.md](reports/ui-v2.8.md). Android was walked on Pixel_9; tvOS was walked on simulator, not reinstalled on the physical Apple TV.
6. **Web deploy.** v2.8 is in source and locally browser-tested; production remains on the earlier live build until the Docker deployment is run in its deployment environment.

---

## 5. Do not

- Do not talk to YouTube from the app. Playback is always `https://beinv.bjk.ai/video/…`.
- Do not replace Super Lig / Premier League **goal** URLs with the remux host (no per-goal YouTube).
- Do not expect beIN `highlights/events` to ever fill La Liga — overlay only.
- Do not re-derive NBC / LALIGA matching; that lives in `server/src/premier.rs` and `laliga.rs`.

---

## 6. When you are done

1. Re-run §3 for any future native behavior change.
2. Add new evidence to a versioned report without rewriting the v2.7 historical reports.
3. Distinguish simulator/emulator validation from physical-device installs in README and FEATURES status rows.
