# tvOS v2.7 / v2.7.1

| | |
|---|---|
| Client | Apple TV (`tv/`) |
| Spec | [FEATURES.md](../FEATURES.md) v2.7 |
| Handoff | [NATIVE_RESUME.md](../NATIVE_RESUME.md) |
| App | `tv/build/Build/Products/Debug-appletvsimulator/Highlights.app` |
| Built | 2026-08-24 — `xcodegen generate` + `xcodebuild` (Apple TV 4K 3rd gen simulator) **BUILD SUCCEEDED** |

## What landed in source

- League switch includes **İspanya La Liga**. HD on the mode row (v2.7.1: `Toggle("HD")`, default on). Left **Week** rail with **All weeks** (`week.all`).
- Playback of HD / La Liga full highlights through `https://beinv.bjk.ai/video/m/…?q=hd`. Goal clips stay on beIN.
- La Liga weeks from the remux host. Changing season resets to All weeks. Retry reloads All weeks (not only a single round).
- v2.7.1: All-weeks **week section headers**; current-week dot; remux-host warm after week/season load; UI test focus path skips the week rail; `-reset` clears `allWeeks`.

## Compile

| Check | Result |
|---|---|
| Simulator `xcodebuild` | **PASS** (2026-08-24, after header / Toggle / retry / warm) |
| `HighlightsTests` | **PASS** — `xcodebuild test -only-testing:HighlightsTests` **TEST SUCCEEDED** |
| `HighlightsUITests` | **PASS** on Living Room Apple TV (`id=da4ce9a44c8732b0fbba7320f2a099ebdcf90d82`) — 2 tests, 0 failures, **TEST SUCCEEDED** |
| Device install | **PASS** — Living Room Apple TV 4K (`D40125DD-4206-59B9-8D73-9BA5D3E59069`): reinstalled + launched after the v2.7.1 build |

## Device checklist

Walked on the Living Room Apple TV: Play all opens the player, Clips tab is reachable (`player.clips`), Menu returns to Goals. See [NATIVE_RESUME.md](../NATIVE_RESUME.md) §3.
