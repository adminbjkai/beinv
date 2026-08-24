# Feature spec (v2.8) — applies to all clients

All three clients (web, Android, Apple TV) implement the same feature set with platform-native UI. Data comes only from the endpoints in [UPSTREAM_API.md](UPSTREAM_API.md).

### Client status (2026-08-24)

| Client | v2.8 state | Built / run |
|---|---|---|
| **Web + Rust server** | v2.7 product behavior is live on [beinv.bjk.ai](https://beinv.bjk.ai/). v2.8 adds the responsive UI/player, structured states, semantics and reduced-motion pass in this checkout; it is not deployed yet. | **Verified locally** in Chromium at 1440, 390 and 320 px with live playback and injected error/retry paths |
| **Android** | v2.8 adds full-name phone league chips, adaptive grids, scroll restoration, structured states, accessibility semantics, playback Retry and monochrome launcher support. | **Verified** on Pixel_9 across all leagues/modes, portrait + landscape drawer, clip jump and PiP; build + lint clean |
| **tvOS** | v2.8 adds compact HD control, persistent focus-safe selection markers, contained week rail, richer cards/states/pickers and more legible native clip lists. | **Verified** on Apple TV 4K simulator (4 unit + 3 XCUIRemote tests); v2.7.1 is the last physical-device install |

Native is an established implementation, not a green-field port. Do not re-implement matching/remux on device.

## 0. Parity rules (every client, same structure and wording)
Top-to-bottom layout, identical labels:
1. **League** switch: `Trendyol Süper Lig` | `İngiltere Premier Lig` | `İspanya La Liga` (exact names).
2. **Season** picker (label "Season") — always visible and selectable in every mode; changing it resets the week view to **All weeks**.
3. **Mode** switch: `Highlights` | `Goals` | `By team`.
4. Mode-specific controls:
   - Trendyol Süper Lig / İngiltere Premier Lig → **HD** toggle.
   - By team → **Team** picker (label "Team", logo + name, A–Z), then a `Matches` | `Goals` sub-switch, and — when Goals is active — a toggle `Only <Team> goals` (default ON).
5. **Week** rail (label "Week") — visible in Highlights and Goals; hidden only in By team. First item is **All weeks** (default): every published match of the season, grouped by week. Picking a week filters to that round. Desktop/tvOS: vertical list on the left. Phone: horizontal chips.
6. Content area.

Every selection change must be reflected immediately and persist (see §1). No mode may dead-end: Back/Esc returns to the previous level.

## 1. Navigation & selection
- **League**: Trendyol Süper Lig / İngiltere Premier Lig / İspanya La Liga.
- **Season**: always-visible picker listing every season from endpoint A (newest first). Default: `isCurrent`.
- **Week**: rail with **All weeks** (default) plus every round. Default for Highlights/Goals is the whole season; a deep link `r=<n>` still opens that week. Cards and section headers show the week name.
- **Remember** last league/season/week (web: URL + localStorage; Android: SharedPreferences; tvOS: UserDefaults).
- **HD** (Trendyol Süper Lig and İngiltere Premier Lig, all clients): toggle labelled `HD`, **on by default**. Off plays the beIN mp4 (including goal clips). On plays the official YouTube full-match remux in the same player (Süper Lig: beIN SPORTS Türkiye; Premier League: NBC Sports). Web: `?q=hd` on `/video/m/{id}`, `hd=1`/`hd=0` in the URL. Native: `https://beinv.bjk.ai/video/m/{id}?…&q=hd`. Goal clips stay on the beIN feed.

## 2. Views (mode toggle)
- **Highlights** (default): match cards for **All weeks** (grouped by week) or one round. Premier League and İspanya La Liga are full-match highlights only (no per-goal clips); Goals / By-team-Goals are empty by data, not by bug. La Liga 2025/2026 and 2026/2027 cards are filled from the overlay in [UPSTREAM_API.md §D](UPSTREAM_API.md). Native clients load that overlay through `https://beinv.bjk.ai`.
- **Goals**: only goal clips (`type == 0`) for the selected week, grouped by match (match header: home logo, scoreline, away logo). Includes **Play all** → sequential playlist of every goal in the week.
  - **Each goal card/row shows**: minute, scorer (event description), **scoring team** (logo + name derived from `eventTeamSide` Home/Away → that match's team), and the **running score after that goal** (e.g. `0–1`, `0–2`, …), computed by walking the match's goals in minute order and incrementing the side that scored. Own-goal/unknown side (`eventTeamSide` null): show the scoreline without the increment and mark the team as "—".
  - Cards are visually attributed to the scoring team: team logo on the card, and the running score highlights the scoring side.
- **By team**: pick a team → that team's matches for the whole selected season (all weeks fetched in parallel, cached per season; show progress while loading). Newest first, each card labelled with its week. Goals mode also applies here (all of that team's goals in the season, Play all).
  - Team list is derived from the season's matches (home/away names + logos), deduplicated, sorted A-Z.
  - In By team → Goals, the `Only <Team> goals` toggle (default ON) filters to goals where the scoring team is the selected team; OFF shows every goal in that team's matches (both sides). Play all respects the filter.

## 2b. Playlists ("Play all") — user experience
Design goal: the playlist reads like the season in order, the user always knows where they are and what comes next, and the video is never hidden by chrome.

- **Order**: week ascending → kick-off time ascending → goal minute ascending. Scope = exactly the visible goals (mode + team + `Only <Team> goals`). Selecting a single goal opens the same ordered list positioned at it.
- **Every item is labelled with its week**. Canonical item title (identical on all clients): `3. Hafta · Beşiktaş 2–1 Trabzonspor · 55' Jota Silva`. The player shows it as the current title plus `x of N`.
- **Up next / Previous**: the player chrome shows the next item's title ("Up next: 4. Hafta · 12' …"), and on web/Android the previous one too, so next/prev are never blind. On tvOS the "Up next" text (or `Last clip`) goes into the item's `externalMetadata` subtitle and the info panel — the native transport bar already shows what came before.
- **Autoplay**: next item starts automatically; after the last item the player closes back to the list. Next/prev always available (web buttons + `n`/`p`; Android transport controls; tvOS Siri Remote skip + transport-bar items).
- **Clip list placement (never covers the video)**:
  - Phone portrait (Android): the ordered list sits **below** the player — grouped by week headers, current row highlighted and auto-scrolled, tap to jump. No overlay, no hamburger.
  - Landscape phone / desktop web: a **side drawer** on the right, ≤ 35 % of the width, translucent (glass), toggled by a "Clips" button (`c` on web); video keeps playing and stays visible; tap outside / Esc / Back closes it.
  - tvOS: the **native swipe-down info panel** (`customInfoViewControllers`) hosts the "Clips" tab: list grouped by week, current highlighted, select to jump. No custom full-screen overlay.
- **Play all button** reads `Play all · N goals · from 1. Hafta` (first week in the list) so the order is obvious before pressing; `N goal` when there is exactly one.
- Goals with no minute reported upstream sort as minute 0 (first) on every client.

## 3. Player
- Plays the highest-quality (only) rendition: `highlightVideoUrl` / event `sourceVideoUrl`.
- **Fullscreen** toggle (Android: landscape immersive, system bars hidden; web: Fullscreen API; tvOS: always full-screen).
- **Playlist**: full highlight + every clip of the match; **next / previous** clip controls; **autoplay next** (on by default) and **loop-free** end state back to the list.
- Resume position when switching back from a clip to the full highlight (best effort).
- Controls: play/pause, seek ±10 s, scrubber with buffered indicator, volume/mute (web), PiP (web, Android), time display. Keyboard on web (space/k, ←/→, f, m, n/p for next/prev, Esc).

## 4. Polish
- Skeleton loaders, empty state ("No highlights published for this week yet."), error state with Retry.
- Team logos, score, date (local tz), goal count badge on cards. A score upstream did not report
  renders as an en dash `–` on every client — never as `0`, which is indistinguishable from a real
  goalless draw.
- Platform-adaptive navigation and grids: phone controls may scroll horizontally without truncating exact labels; wide layouts use their available space without changing information order.
- Touch/focus targets, selected/current state, loading/progress, error/Retry and player navigation expose meaningful platform semantics. tvOS focus uses native high-contrast foregrounds plus a persistent selection marker.
- Web respects reduced-motion preferences. At phone widths its player controls sit below the unobscured video; at desktop widths they remain auto-hiding chrome and leave room for the ≤35% Clips drawer.
- Focus/hover/pressed states; no purple anywhere (charcoal `#0B0F0E`, emerald `#19C37D`).

## Out of scope (for now)
Accounts, favourites sync, notifications, other leagues (1. Lig, Ligue 1/2, …), downloads, HLS/ABR.
