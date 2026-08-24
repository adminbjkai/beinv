# Upstream APIs (highlights)

Verified live 2026-08-22. No auth, cookies, or tokens needed. All calls are plain GET.

Used by all clients: the Rust server (`server/src/bein.rs`, `video.rs`), the Apple TV app (`tv/Highlights/API.swift`) and the Android app (`android/app/src/main/java/ai/bjk/highlights/Api.kt`). The native apps hand `highlightVideoUrl` straight to AVPlayer / ExoPlayer.

## Leagues we use

| league | rewriteId | orgId | sportId |
|---|---|---|---|
| Trendyol Süper Lig | `super-lig` | 18 | 1 |
| İngiltere Premier Lig | `ingiltere-premier-ligi` | 17 | 1 |
| İspanya La Liga | `ispanya-la-liga` | 60 | 1 |

## A. Seasons + weeks  (cache 1 h)

```
GET https://apigateway.beinsports.com.tr/api/organizations/v3/rewriteid/{rewriteId}
```
```json
{ "Data": { "id": 18, "name": "...", "seasons": [
  { "id": 3974, "name": "2026/2027", "isCurrent": true,
    "beinSportsFixtureWeekList": [
      { "round": 1, "stageId": 0, "weekName": "1. Hafta", "currentWeekForFixture": false } ] } ] } }
```

## B. Matches of a week  (cache 5 min current week / 24 h past)

This is exactly what the site's season/week dropdown calls.
```
GET https://beinsports.com.tr/api/highlights/events?sp={sportId}&o={orgId}&s={seasonId}&r={round}&st=0
```
```json
{ "Data": { "events": [ {
  "matchId": 1515711, "matchDate": "2026-08-16T18:30:00Z",
  "highLightTitle": "Beşiktaş 1-0 Eyüpspor Maç Özeti",
  "highlightThumbnail": "https://media01.tr.beinsports.com/img/highlights/1515711.jpg",
  "highlightVideoUrl": "https://dt-switch.akamaized.net/api/er/Get?...&ar=ligtvcomtr_tauri_ozt_20262027_1_bjk_eyup_1h_15agu_ip&secure=1",
  "homeTeam": { "name": "Beşiktaş", "rewriteId": "besiktas", "logo": "...png", "matchScore": 1 },
  "awayTeam": { ... },
  "matchEvents": [ { "id": 40006, "minute": 25, "description": "Vaclav Cerny",
      "type": 0, "eventTeamSide": "Home", "thumbnail": "...jpg",
      "videoUrl": "dt-switch ...", "sourceVideoUrl": "https://dt-vod-beinsports.akamaized.net/.../x.mp4?hdnts=..." } ]
} ] } }
```
- `type`: 0 = goal, 1 = position/other clip.
- **Premier League** matches come back with `matchEvents: null` (only the full highlight exists upstream, checked across several seasons/weeks on 2026-08-22). Goals / By-team-Goals modes are therefore empty for that league by data, not by bug.
- **İspanya La Liga**: the seasons/weeks payload is populated (current 2026/2027 = season id `3968`, 38 weeks) but `highlights/events` is `{"Data":{}}` for every season checked (2024/25–2026/27). The web server does **not** leave the league empty: see [La Liga overlay](#d-la-liga-20262027-overlay-web-server) below.
- Empty/tiny body (`{}`) means the week has no published highlights yet.
- Path/query variants on the HTML page (`/super-lig/2026-2027/1`, `?week=1`) do NOT work.

## C. Video resolution  (cache 24 h)

```
GET {highlightVideoUrl}
→ 302 Location: https://dt-vod-beinsports.akamaized.net/<x>/<xx>/<xxxx>/<ar>.mp4?hdnts=test~hmac=...
```
- Single progressive H.264 MP4 — the only rendition, so it is the highest quality.
- `Accept-Ranges: bytes`, CORS `*`, ~65 MB for a 6-min highlight.
- The `hdnts` value is static and the file also serves without it; no Referer needed.
- Event clips already carry the final mp4 in `sourceVideoUrl` (no redirect).

## D. La Liga 2026/2027 overlay (web server)

Used only when beIN returns no events for `ispanya-la-liga` and the beIN season id is `3968` (2026/2027). Native Android/tvOS clients do not use this path.

**Fixtures + scores** (cache 5 min), public key taken from laliga.com's own `runtimeConfig`:

```
GET https://apim.laliga.com/public-service/api/v1/matches?subscriptionSlug=laliga-easports-2026&limit=100&offset=0
Header: Ocp-Apim-Subscription-Key: c13c3a8e2f6b46da9c5c425cf61fab3e
```

380 matches, `gameweek.week` = 1…38, `status` `FullTime` | `PreMatch`, team shields on `home_team.shield.url`.

**Highlights** are the official [LALIGA YouTube channel](https://www.youtube.com/@LaLiga) (`UCTv-XvfzLX3i4IGWAm4sbmA`) videos titled `HOME s - s AWAY | RESUMEN LALIGA EA SPORTS` (or `HIGHLIGHTS`). This is automatic for the whole season — the same “publish then appear” rhythm as beIN for Süper Lig / Premier League:

1. Every 5 minutes the server re-reads LaLiga fixtures (`FullTime` + score).
2. The same interval it refreshes the official-channel RSS plus a YouTube search.
3. A `FullTime` match is listed once a matching highlight exists. The **longest** matching cut is used so the landscape ~3:15 version wins over the vertical ~2:48 Shorts-style cut of the same game.
4. Opening a week **prefetches** remuxes in the background (2 at a time), so tapping a card is usually instant. The first ever fetch of a brand-new video can still take ~15 s.
5. Match→video ids are remembered in `{BEINV_VIDEO_CACHE}/laliga-map.json` across restarts. `server/src/data/laliga-youtube.json` is only a bootstrap for the opening matchweeks, not a season-long hardcoded list.

**Playback** (`/video/m/{matchId}`): `yt-dlp -f "bv*[vcodec^=avc1]+ba[ext=m4a]/18/b"` takes the highest H.264+AAC the source publishes (typically **1080p50 + stereo AAC**). Merger uses `+faststart`. If the HQ fetch fails, it falls back to progressive 360p so the player still starts. The `<video>` element only ever loads `/video/…`.

Unplayed fixtures are omitted (same as beIN only listing matches that already have a highlight). Goals / By-team-Goals stay empty: there are no per-goal clips.

## Other gateway endpoints seen (not used)
`/api/match/{id}`, `/api/match/{id}/lineups|facts|comments`, `/api/live/{id}`,
`/api/fixture/head2head/{a}/between/{b}`, `/api/standing/teamid/{id}`.
