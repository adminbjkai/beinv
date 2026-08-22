# Android v2.3 report — Playlists UX (§2b rewrite)

## What changed
- `Models.kt`: `Match.scoreLine` uses an en dash (`2–1`); `GoalRow.label` = canonical title
  `3. Hafta · Beşiktaş 2–1 Trabzonspor · 55' Jota Silva`, used as `Clip.title`; its scoreline is the **running score at that goal** (web parity: `… 0–1 Beşiktaş · 15' Rafa Silva`). `orderedPlaylist()` unchanged.
- `BrowseScreen.kt`: Play all button reads `Play all · N goals · from <first week>`; week label is passed to goal rows in
  week mode too (so the player can label items); per-match week chip hidden when all groups share one week.
- `PlayerScreen.kt`:
  - Header: `x of N` (emerald) + canonical title of the current item; `NextPrevBar` ("Up next: …" / "Previous: …")
    recomputed from `current`, which updates on every `onMediaItemTransition`.
  - **Portrait**: the overlay Clips button / glass panel are gone. The list below the video is now week-grouped
    (`clipRows`: emerald week headers; rows = minute · team logo · scorer · running score, team + match on line 2);
    current row highlighted and auto-scrolled (one row above, so the week header stays visible).
  - **Landscape/fullscreen**: video fills the screen; "Clips" button in the chrome toggles a right drawer
    (`fillMaxWidth(0.35f)`, translucent gradient, emerald accents) with the same grouped list + Up next/Previous;
    video keeps playing and stays visible; tap outside or Back closes it. Back order: drawer → fullscreen → list
    (drawer `BackHandler` declared after the fullscreen one). Drawer closes automatically when leaving fullscreen.
  - `STATE_ENDED` → back to the list (no loop), as in v2.2.

## Why it's better for the user
- Nothing covers the video in portrait; the list is always there, grouped by week so position in the season is obvious.
- Title + "Up next" mean next/prev are never blind; the Play all button states count and starting week up front.
- In landscape the drawer takes ≤ 35 % and the clip keeps playing, so picking a goal does not interrupt watching.

## Verification (emulator-5554, Pixel 1080×2424)
- `./gradlew assembleDebug --no-daemon -q` clean; APK → `Highlights-debug.apk`; `adb install -r` Success.
- By team → Beşiktaş → Goals: button `Play all · 59 goals · from 1. Hafta`.
- `android/build/v23-portrait.png`: "1 of 59", title `1. Hafta · Metro Holding Kayserispor 0–1 Beşi…` (running score, not final),
  `Up next: 1. Hafta · … · 32' Rafa S…`; list below with `1. Hafta` / `2. Hafta` headers, rows 15'/32'/45'/58' with
  Beşiktaş logo and 0–1…0–4, current row emerald.
- `user_rotation 1` → `v23-landscape.png`: full-screen video, "Clips" button top-right only.
- Tap Clips → `v23-landscape-drawer.png`: right drawer ≈35 % width, translucent, `Clips · 1 of 59`, Up next line,
  week-grouped rows; video visible and still playing (clock advanced 14:45 → 14:49). Rotated back (`user_rotation 0`).
- `adb logcat -d | grep -cE 'FATAL|AndroidRuntime'` → 0.

## Gaps
- Drawer uses an alpha gradient, not a real blur (RenderEffect is API 31+ and does not blur a SurfaceView well).
- Tap-outside / Back closing of the drawer and last-clip → list verified by code path, not screenshotted.
- Hardware media keys (`KEYCODE_MEDIA_NEXT`) are not wired (no MediaSession); on-screen transport buttons work.
- Match-highlight playlists (full highlight + clips) have no week labels, so they render ungrouped.
