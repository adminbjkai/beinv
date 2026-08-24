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
| Install / play | **PASS browse** on Pixel_9 emulator (`adb install -r` + `am start`). Player opened on La Liga Alavés–Getafe (`Full highlight` · `1 of 1`). HD URL / 1080p not measured. |

## Emulator browse (Pixel_9, 2026-08-24)

Walked with `uiautomator` + screenshots in `android/build/`:

- Super Lig 2026/2027 Highlights opens on **All weeks**. Week chips: All weeks + `N. Hafta` (current week dotted). Section header `1. Hafta · 9 matches`. HD switch **on**. Shot: `v27-superlig-allweeks.png`.
- Premier League switch shows week-1 cards (Arsenal–Coventry, …). HD still on the mode row.
- La Liga 2026/2027: HD hidden; `Loading season… 0/38` then cards; header `1. Hafta · 6 matches` (Alavés–Getafe, Sevilla–Rayo, …). Shot: `v27-laliga-allweeks.png`.
- Tap Alavés–Getafe → player `Full highlight` / `1 of 1` / PiP.

Still open vs [NATIVE_RESUME.md](../NATIVE_RESUME.md) §3: HD off → beIN mp4; City–Bournemouth 1080p; La Liga 2025/2026 season `3850`; Goals / By team / Play all regression.
