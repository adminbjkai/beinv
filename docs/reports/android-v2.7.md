# Android v2.7 / v2.7.1

| | |
|---|---|
| Client | Android (`android/`) |
| Spec | [FEATURES.md](../FEATURES.md) v2.7 |
| Handoff | [NATIVE_RESUME.md](../NATIVE_RESUME.md) |
| APK | `android/app/build/outputs/apk/debug/app-debug.apk` |
| Built | 2026-08-24 — `./gradlew assembleDebug` exit 0 (JDK from Android Studio JBR) |

## What landed in source

- League switch includes **İspanya La Liga**. HD switch (default on) for Super Lig + Premier League. Week rail with **All weeks** (default); chips on phone, left column when width > 700 dp.
- Full-highlight URL rewritten through `https://beinv.bjk.ai/video/m/…?q=hd` when HD is on or the league is La Liga. Goal clips stay on beIN.
- La Liga weeks decoded from the remux host JSON list, not empty beIN `highlights/events`.
- v2.7.1: All-weeks **week section headers** (`N. Hafta · M matches`); current-week dot on the rail; background `Api.warm()` so remuxes start before the first tap.

## Compile

| Check | Result |
|---|---|
| `assembleDebug` | **PASS** (2026-08-24, after week-header + warm changes) |
| Warnings | not re-counted this pass |
| Install / play | **PASS** on Pixel_9: HD on/off, City–Bournemouth **1920×1080**, La Liga 2025/26 + 2026/27 Atleti, empty week, Goals Play all, PiP |

## Emulator browse (Pixel_9, 2026-08-24)

Walked with `uiautomator` + screenshots in `android/build/`:

- Super Lig 2026/2027 Highlights opens on **All weeks**. Week chips: All weeks + `N. Hafta` (current week dotted). Section header `1. Hafta · 9 matches`. HD switch **on**. Shot: `v27-superlig-allweeks.png`.
- Premier League switch shows week-1 cards (Arsenal–Coventry, …). HD still on the mode row.
- La Liga 2026/2027: HD hidden; `Loading season… 0/38` then cards; header `1. Hafta · 6 matches` (Alavés–Getafe, Sevilla–Rayo, …). Shot: `v27-laliga-allweeks.png`.
- Tap Alavés–Getafe → player `Full highlight` / `1 of 1` / PiP.
- HD **off** Super Lig still plays beIN extras; HD **on** City–Bournemouth is the remux (`ffprobe` 1920×1080). PiP floats over the launcher; Back from the player returns to the list.
- La Liga 2025/2026 week 1: 10 cards, Girona played. 2026/2027 week 2: Atleti–Villarreal opened (`1 of 1`). Week 3 empty: `No highlights published for this week yet.`
- Goals: Super Lig `Play all · 43 goals · from 1. Hafta`. By team A–Z.

See [NATIVE_RESUME.md](../NATIVE_RESUME.md) §3.
