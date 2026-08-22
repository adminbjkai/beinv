# Highlights (Android)

Small native Android app (Kotlin, Jetpack Compose, Media3 ExoPlayer) that lists football
match highlights from beinsports.com.tr (Süper Lig and Premier League) and plays them.
Self-contained: it calls the public beIN endpoints directly, no backend.

## Features (v2.3 spec, `docs/FEATURES.md`)

- Layout per §0 parity rules, top to bottom: **League** (`Trendyol Süper Lig` | `İngiltere Premier Lig`) →
  **Season** (always visible; changing it resets the week to that season's default) → **Week** (‹ ›, hidden in
  By team) → **Mode** (`Highlights` | `Goals` | `By team`) → mode row → content. League, season/week (per league),
  mode, team (per league), the `Matches` | `Goals` sub-switch and the `Only <Team> goals` toggle are remembered in
  SharedPreferences and restored on relaunch (and when returning from the player).
- **Highlights**: match grid, goal-count badge.
- **Goals**: goal clips grouped by match (header: home logo, scoreline, away logo). Each card shows minute, scorer,
  scoring team (logo + name from `eventTeamSide` Home/Away) and the **running score after that goal** with the
  scoring side in emerald (unknown side → "—", score unchanged). **Play all** plays the filtered list.
- **By team**: mode row has a **Team** picker (logo + name, A–Z, derived from the season), a `Matches` | `Goals`
  sub-switch and, when Goals is active, an `Only <Team> goals` switch (default ON; OFF shows both sides' goals;
  Play all respects it). Whole season fetched concurrently (6 at a time, cached; "Loading season… x/N"),
  matches newest-first with week label.
- **Playlists (§2b)**: Play all (`Play all · N goals · from 1. Hafta`) / tapping a goal opens the visible goals ordered
  by week ↑ → kick-off ↑ → minute ↑ (`orderedPlaylist()` in `Models.kt`), positioned at the tapped goal. Canonical item
  title `3. Hafta · Beşiktaş 2–1 Trabzonspor · 55' Jota Silva` (`GoalRow.label`) + `x of N`; "Up next: …" /
  "Previous: …" under the title. Portrait: week-grouped list **below** the video (current row emerald, auto-scroll,
  tap jumps) — no overlay. Landscape/fullscreen: "Clips" chrome button toggles a translucent **right drawer (35 %)**
  with the same list; video keeps playing; Back / tap outside closes it (Back: drawer → fullscreen → list).
  After the last clip the player returns to the list.
- Player: one ExoPlayer playlist (full highlight + clips) via `setMediaItems` — next/prev, autoplay next,
  current clip highlighted, best-effort resume per clip. Fullscreen button / auto on landscape rotation
  (immersive, system bars hidden; Back exits fullscreen first). PiP via Home or the "PiP" button.
- Skeleton placeholders, empty state, error + Retry.

## Build

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd android
./gradlew assembleDebug --no-daemon -q
```

`local.properties` must point at the SDK (`sdk.dir=/Users/m17/Library/Android/sdk`).

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Run on the emulator

```
~/Library/Android/sdk/emulator/emulator -avd Pixel_9 -dns-server 8.8.8.8,1.1.1.1 &
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n ai.bjk.highlights/.MainActivity
```

`-dns-server` matters: without it the emulator can come up unable to resolve hostnames
("Unable to resolve host apigateway.beinsports.com.tr"). Real devices are unaffected.

## Files

- `app/src/main/java/ai/bjk/highlights/`
  - `MainActivity.kt` – single activity, Browse ⇄ Player via state; PiP entry + state
  - `Models.kt` – leagues, lenient kotlinx-serialization models (`MatchEvent.eventTeamSide` → `Side`),
    `Match.goalRows(round, week)` running-score walk (`GoalRow`), `orderedPlaylist()`, `Clip` (+ selector
    metadata)/`Playlist`/`Mode`
  - `Api.kt` – OkHttp suspend calls, in-memory caches, concurrent per-season fetch
  - `Prefs.kt` – SharedPreferences (league, season/week per league, mode, team per league, By-team sub-switch/toggle)
  - `BrowseScreen.kt` – top bar (league/season/week/mode/team rows), match grid, goal cards (`GoalCard`),
    team view + `Only <Team> goals` filter, skeletons
  - `PlayerScreen.kt` – ExoPlayer playlist `PlayerView`, fullscreen/immersive, week-grouped clip list (`clipRows`), landscape drawer, `NextPrevBar`
  - `Theme.kt` – dark Material3 scheme (emerald accent, no purple)
- `app/src/main/res/` – strings, theme, adaptive launcher icon (vector only)
- `gradle/libs.versions.toml` – version catalog

## Signing

This is a debug APK signed with the default debug keystore. Not for distribution.
