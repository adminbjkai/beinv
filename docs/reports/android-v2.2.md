# Android v2.2 report — Playlists (§2b)

## What changed
- `Models.kt`: `Clip` gained optional selector metadata (`minute`, `scorer`, `logo`, `score`, `week`); `GoalRow` carries
  `round` + `week` (`Match.goalRows(round, week)`); new `orderedPlaylist(rows)` — the single ordering function:
  week asc → `matchDate` asc → minute asc.
- `BrowseScreen.kt`: `Entry(week, round, match)` replaces the `Pair`; `GoalsView` builds `orderedPlaylist` over exactly
  the visible (filtered) rows for Play all, and a single-goal tap opens the same list at `ordered.indexOf(row)`.
  Display grouping (newest-first) is unchanged.
- `PlayerScreen.kt`: title "x of N · …"; overlay "Clips" icon button (top-right of the `PlayerView`) toggling
  `ClipsPanel` — translucent dark gradient (alpha ≈0.87–0.93) with emerald accents, rows = minute · team logo (Coil) ·
  scorer · week · running score, current highlighted + auto-scrolled, tap → `seekTo(index, …)` + play; Back closes it.
  `Player.STATE_ENDED` (after the last item, no loop) now calls `onBack()` → list. Autoplay next / transport next-prev
  are ExoPlayer-native (`playWhenReady` untouched across transitions).
- `README.md` updated.

## Verification
- `./gradlew assembleDebug --no-daemon -q` → clean; APK copied to `Highlights-debug.apk`; `adb install -r` → Success.
- Emulator-5554: By team → Beşiktaş → Goals (Only Beşiktaş goals ON, Play all (59)).
- `android/build/v22-player.png`: "1 of 59 · Beşiktaş · all goals", first clip is KAY–BJK 15' Rafa Silva 0–1
  (week 1 — earliest week, ascending), Clips button visible top-right of the video.
- `android/build/v22-clips.png`: glass panel "Clips · 1 of 59" listing 15' / 32' / 45' … with Beşiktaş logo,
  "1. Hafta", running scores 0–1 / 0–2 / 0–3; first item highlighted in emerald.
- `v22-jump.png`: tapping the 3rd panel item → title "3 of 59", clip 45' Tammy Abraham highlighted, panel closed.
- `KEYCODE_MEDIA_NEXT` has no effect (no MediaSession; expected). On-screen PlayerView next/prev buttons unchanged.
- `adb logcat -d | grep -cE 'FATAL|AndroidRuntime'` → 0.

## Gaps
- No MediaSession, so hardware/headset next/prev keys are not wired (not required by §2b).
- No real `Modifier.blur` (RenderEffect needs API 31+ and would blur the SurfaceView poorly); translucent gradient used instead.
- Autoplay-to-end (last clip → list) verified by code path only (59-clip playlist not played to completion).
- Match-highlight playlists (full highlight + clips) keep their natural order; §2b ordering applies to goal playlists.
