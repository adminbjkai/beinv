# beIN SPORTS TR — upstream API (highlights only)

Verified live 2026-08-22. No auth, cookies, or tokens needed. All calls are plain GET.

Used by all clients: the Rust server (`server/src/bein.rs`, `video.rs`), the Apple TV app (`tv/Highlights/API.swift`) and the Android app (`android/app/src/main/java/ai/bjk/highlights/Api.kt`). The native apps hand `highlightVideoUrl` straight to AVPlayer / ExoPlayer.

## Leagues we use

| league | rewriteId | orgId | sportId |
|---|---|---|---|
| Trendyol Süper Lig | `super-lig` | 18 | 1 |
| İngiltere Premier Lig | `ingiltere-premier-ligi` | 17 | 1 |

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

## Other gateway endpoints seen (not used)
`/api/match/{id}`, `/api/match/{id}/lineups|facts|comments`, `/api/live/{id}`,
`/api/fixture/head2head/{a}/between/{b}`, `/api/standing/teamid/{id}`.
