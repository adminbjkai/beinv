# Upstream APIs (highlights)

Verified live 2026-08-22. No auth, cookies, or tokens needed. All calls are plain GET.

Used by all clients: the Rust server (`server/src/bein.rs`, `video.rs`), the Apple TV app (`tv/Highlights/API.swift`) and the Android app (`android/app/src/main/java/ai/bjk/highlights/Api.kt`). Süper Lig / Premier League catalogs still come from beIN on native; HD full-highlights and every La Liga video play from `https://beinv.bjk.ai/video/…`. Native **source** for that split is on `main`; binaries are not rebuilt yet — [NATIVE_RESUME.md](NATIVE_RESUME.md).

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
- **İspanya La Liga**: the seasons/weeks payload is populated (current 2026/2027 = season id `3968`; 2025/2026 = `3850`; 38 weeks each) but `highlights/events` is `{"Data":{}}` for every season checked (2024/25–2026/27). The server does **not** leave those two seasons empty: see [La Liga overlay](#d-la-liga-overlay-web-server) below.
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

## D. La Liga overlay (web server + native via remux host)

Used when beIN returns no events for `ispanya-la-liga` and the beIN season id is `3968` (2026/2027, slug `laliga-easports-2026`) or `3850` (2025/2026, slug `laliga-easports-2025`). Native Android/tvOS load the same weeks from `https://beinv.bjk.ai/api/leagues/ispanya-la-liga/seasons/{id}/weeks/{round}` and play `/video/m/{id}`.

**Fixtures + scores** (cache 5 min, keyed per slug), public key taken from laliga.com's own `runtimeConfig`:

```
GET https://apim.laliga.com/public-service/api/v1/matches?subscriptionSlug=laliga-easports-2026&limit=100&offset=0
GET https://apim.laliga.com/public-service/api/v1/matches?subscriptionSlug=laliga-easports-2025&limit=100&offset=0
Header: Ocp-Apim-Subscription-Key: c13c3a8e2f6b46da9c5c425cf61fab3e
```

380 matches per season, `gameweek.week` = 1…38, `status` `FullTime` | `PreMatch`, team shields on `home_team.shield.url`. Score in the YouTube title disambiguates the same two clubs across seasons.

**Highlights** are the official [LALIGA YouTube channel](https://www.youtube.com/@LaLiga) (`UCTv-XvfzLX3i4IGWAm4sbmA`) videos titled `HOME s - s AWAY | RESUMEN LALIGA EA SPORTS` (or `HIGHLIGHTS`). This is automatic for the whole season — the same “publish then appear” rhythm as beIN for Süper Lig / Premier League:

1. Every 5 minutes the server re-reads LaLiga fixtures (`FullTime` + score).
2. The same interval it refreshes the official-channel RSS plus a YouTube search.
3. A `FullTime` match is listed once a matching highlight exists. The **longest** matching cut is used so the landscape ~3:15 version wins over the vertical ~2:48 Shorts-style cut of the same game.
4. Opening a week **prefetches** remuxes in the background (2 at a time), so tapping a card is usually instant. The first ever fetch of a brand-new video can still take ~15 s.
5. Match→video ids are remembered in `{BEINV_VIDEO_CACHE}/laliga-map.json` across restarts. `server/src/data/laliga-youtube.json` is only a bootstrap for the opening matchweeks, not a season-long hardcoded list.

**Playback** (`/video/m/{matchId}`): `yt-dlp -f "bv*[vcodec^=avc1]+ba[ext=m4a]/18/b"` takes the highest H.264+AAC the source publishes (typically **1080p50 + stereo AAC**). Merger uses `+faststart`. If the HQ fetch fails, it falls back to progressive 360p so the player still starts. The `<video>` element only ever loads `/video/…`.

Unplayed fixtures are omitted (same as beIN only listing matches that already have a highlight). Goals / By-team-Goals stay empty: there are no per-goal clips.

## E. Süper Lig HD overlay

Default Süper Lig playback is the official [beIN SPORTS Türkiye](https://www.youtube.com/@beINSPORTSTurkiye) remux (`q=hd`, channel `UCPe9vNjHF1kEExT5kHwc7aw`). Turning HD off falls back to the beIN mp4 from §C (goal clips always stay on beIN). `has_hd` on the match JSON; `?q=hd` on `/video/m/{id}`.

Titles look like `Alanyaspor - Beşiktaş - Highlights/Özet | Trendyol Süper Lig - 2026/27` (shirt-sponsor prefixes dropped). Same automatic loop as §D: RSS + search every 5 minutes, longest matching cut, prefetch on week load, disk map `{BEINV_VIDEO_CACHE}/superlig-map.json`. Bootstrap seed: `server/src/data/superlig-youtube.json` (e.g. Beşiktaş week 2 2026/27 = `QJ31yOM88UQ`). Season id `3974` only. 1. Lig / press-conference / single-goal clips are ignored.

## F. Premier League HD overlay

Same toggle as §E, **on by default**, for İngiltere Premier Lig 2026/2027 (beIN season `3958`). Source is the official [NBC Sports](https://www.youtube.com/@NBCSports) channel (`UCqZQlzSHbVJrwrn5XvzrzcA`). Titles: `Manchester City v. Bournemouth | PREMIER LEAGUE HIGHLIGHTS | 8/23/2026 | NBC Sports`. Matching uses both clubs (aliases: `Newcastle Utd.` → Newcastle, `Brighton and Hove Albion` → Brighton, `Tottenham` → Tottenham Hotspur), the kickoff `M/D/YYYY` so prior seasons do not collide, and duration ≥ 8 minutes so goal clips are dropped. RSS on this channel is mixed-sport and usually has no PL highlights — Innertube search is the live index. Disk map `{BEINV_VIDEO_CACHE}/premier-map.json`. Bootstrap seed: `server/src/data/premier-youtube.json` (City–Bournemouth week 1 = `8bWcZxr_bKE` / match `1510542`).

## Other gateway endpoints seen (not used)
`/api/match/{id}`, `/api/match/{id}/lineups|facts|comments`, `/api/live/{id}`,
`/api/fixture/head2head/{a}/between/{b}`, `/api/standing/teamid/{id}`.
