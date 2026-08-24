# Native resume (Android + tvOS) after web v2.7

> **Start here on the Mac.** Web and the Rust backend are done and live on
> [beinv.bjk.ai](https://beinv.bjk.ai/). v2.7.1: Android debug APK assembled and
> walked on Pixel_9; tvOS installed and launched on the Living Room Apple TV
> (`D40125DD-…`). Remaining: HD 1080p probe on device (player opened; resolution
> not measured) and production deploy of the v2.7.1 SPA.

Spec to match: [FEATURES.md](FEATURES.md) v2.7.  
Upstream: [UPSTREAM_API.md](UPSTREAM_API.md) §§C–F.  
What shipped on web: [CHANGELOG.md](CHANGELOG.md) v2.7.

---

## 1. What is already live (do not re-implement)

Reference the web app while you work. If native disagrees with web, **web is
the source of truth**.

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

Landed on `main` in `1371b5e`. Treat this as a **first cut to compile and
verify**, not as a finished port.

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
| `BrowseScreen.kt` | HD switch; `WeekRail` (left column if width > 700 dp, else horizontal chips); All weeks loads `seasonMatches`; `playMatch` rewrites the highlight URL |

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
| `BrowseView.swift` | `hd` + `allWeeks` published + persisted; left `weekRail` (`week.all`); mode row **HD on/off**; All weeks uses `seasonMatches`; playback goes through `playable` |

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
- [x] Android `assembleDebug` green — `android/app/build/outputs/apk/debug/app-debug.apk` (2026-08-24, v2.7.1)
- [x] tvOS `xcodegen generate` + `xcodebuild` green — simulator and **device** (`Debug-appletvos/Highlights.app`)
- [x] Existing unit tests still pass (`HighlightsTests` **TEST SUCCEEDED** 2026-08-24; no Android unit tests)

### B. HD (Super Lig + Premier League)
- [x] Super Lig: HD switch is **on** on first launch (Pixel_9). Played Galatasaray–Çorum (`1 of 15`).
- [x] Toggle HD **off** → same card still plays (`1 of 15`, beIN extras like `İlk 11'ler`). Goal chips stay on beIN.
- [x] Premier League week 1: **Manchester City 2–1 Bournemouth** opened (`1 of 1` · Full highlight · no YouTube chrome). 1080p not probed from the emulator.
- [ ] First tap of a never-cached remux: spinner/buffer is OK for ~15 s; a retry or second tap should be instant.
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
- [ ] 2026/2027 week 2: Atleti–Villarreal (`102260`) is landscape, not portrait.
- [ ] Unplayed future weeks: empty state, not a crash.

### E. Regression (v2.6 behaviour that must still work)
- [x] Super Lig Goals: running score, scoring-team, Play all week → kick-off → minute (`53' Osimhen` then `58' Kyziridis`).
- [x] By team team list A–Z (Only \<Team\> goals control present).
- [x] Player next/prev chrome (`1 of N`, Up next) on Android.
- [ ] Android: PiP, fullscreen Back order (drawer → fullscreen → list).
- [ ] tvOS: Clips tab in the info panel still jumps. **Installed and launched** on Living Room Apple TV 4K; remote walk not automated.

### F. UI tests / identifiers
tvOS UI tests do **not** depend on `week.prev` / `week.button` (those controls were removed). New id: `week.all`, `hd.toggle`. If a test fails because focus order changed (week rail is now beside the grid), fix the test, not the rail.

---

## 4. Known gaps vs web (polish)

1. **Week section headers.** Done in v2.7.1 — All-weeks grids group under `N. Hafta · M matches` on Android and tvOS.
2. **tvOS HD control.** Done in v2.7.1 — `Toggle("HD")` on the mode row (`hd.toggle`).
3. **First remux delay.** Softened in v2.7.1: after a week/season loads, native `GET`s the same `beinv.bjk.ai` week/season route the web app uses, which starts `youtube::warm`. First tap of a never-seen YouTube id can still take ~15 s.
4. **All-weeks first load of La Liga 2025/26** hits 38 weeks through the proxy; some weeks still search YouTube on the server the first time. Show the existing “Loading season… x/N” and do not assume it is instant.
5. **Device QA report.** Compile reports: [android-v2.7.md](reports/android-v2.7.md), [tvos-v2.7.md](reports/tvos-v2.7.md). Playback rows stay open until §3 B–E is walked on a device.

---

## 5. Do not

- Do not talk to YouTube from the app. Playback is always `https://beinv.bjk.ai/video/…`.
- Do not replace Super Lig / Premier League **goal** URLs with the remux host (no per-goal YouTube).
- Do not expect beIN `highlights/events` to ever fill La Liga — overlay only.
- Do not re-derive NBC / LALIGA matching; that lives in `server/src/premier.rs` and `laliga.rs`.

---

## 6. When you are done

1. Walk §3 B–E on a device/emulator and tick the boxes.
2. Add the playback evidence to [reports/android-v2.7.md](reports/android-v2.7.md) and [reports/tvos-v2.7.md](reports/tvos-v2.7.md).
3. Flip the native row in [FEATURES.md](FEATURES.md) § Client status from “built” to “verified” once those rows are walked.
