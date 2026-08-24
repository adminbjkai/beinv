//! La Liga highlight overlay (2025/2026 and 2026/2027).
//!
//! beIN TR exposes İspanya La Liga seasons/weeks (`ispanya-la-liga`, org 60) but
//! `highlights/events` is empty (`{"Data":{}}`) for every season checked. Fixtures and
//! scores come from LaLiga's public API; full-match highlights come from the official
//! LALIGA YouTube channel and are remuxed to same-origin MP4 by `youtube.rs`.

use std::{
    collections::HashMap,
    sync::Arc,
    time::{Duration, Instant},
};

use serde_json::Value;
use tokio::sync::Mutex;

use crate::bein::{Match, Team};

/// beIN season id for 2026/2027 (verified live 2026-08-23).
pub const BEIN_SEASON_2026: u64 = 3968;
/// beIN season id for 2025/2026 (verified live 2026-08-24).
pub const BEIN_SEASON_2025: u64 = 3850;
const API: &str = "https://apim.laliga.com/public-service/api/v1/matches";

fn slug_for(season: u64) -> Option<&'static str> {
    match season {
        BEIN_SEASON_2026 => Some("laliga-easports-2026"),
        BEIN_SEASON_2025 => Some("laliga-easports-2025"),
        _ => None,
    }
}
const API_KEY: &str = "c13c3a8e2f6b46da9c5c425cf61fab3e";
const YT_CHANNEL: &str = "UCTv-XvfzLX3i4IGWAm4sbmA";
const RSS: &str = "https://www.youtube.com/feeds/videos.xml?channel_id=UCTv-XvfzLX3i4IGWAm4sbmA";
const INNERTUBE: &str = "https://www.youtube.com/youtubei/v1/search?prettyPrint=false";

const SEED: &str = include_str!("data/laliga-youtube.json");

struct SeasonDump {
    loaded: Instant,
    matches: Arc<Vec<Value>>,
}

/// One official highlight candidate. `secs` is used to prefer the landscape cut
/// (~3:15) over the vertical Shorts-style cut (~2:48) of the same match.
#[derive(Clone, Debug)]
pub struct YtVid {
    pub id: String,
    pub title: String,
    pub secs: u32,
}

struct YtIndex {
    loaded: Instant,
    videos: Vec<YtVid>,
}

static SEASON: Mutex<Option<HashMap<String, SeasonDump>>> = Mutex::const_new(None);
static YT: Mutex<Option<YtIndex>> = Mutex::const_new(None);
static BY_MATCH: Mutex<Option<HashMap<u64, String>>> = Mutex::const_new(None);

fn seed_map() -> HashMap<u64, String> {
    serde_json::from_str::<HashMap<String, String>>(SEED)
        .unwrap_or_default()
        .into_iter()
        .filter_map(|(k, v)| k.parse().ok().map(|id: u64| (id, v)))
        .collect()
}

/// Lowercase, strip diacritics and common club suffixes so "RCD Espanyol de Barcelona"
/// matches a title that says "RCD ESPANYOL".
pub fn norm_name(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut prev_space = false;
    for c in s.chars() {
        let c = match c {
            'Á' | 'À' | 'Ä' | 'Â' | 'á' | 'à' | 'ä' | 'â' => 'a',
            'É' | 'È' | 'Ë' | 'Ê' | 'é' | 'è' | 'ë' | 'ê' => 'e',
            'Í' | 'Ì' | 'Ï' | 'Î' | 'í' | 'ì' | 'ï' | 'î' => 'i',
            'Ó' | 'Ò' | 'Ö' | 'Ô' | 'ó' | 'ò' | 'ö' | 'ô' => 'o',
            'Ú' | 'Ù' | 'Ü' | 'Û' | 'ú' | 'ù' | 'ü' | 'û' => 'u',
            'Ñ' | 'ñ' => 'n',
            'Ç' | 'ç' => 'c',
            'Ş' | 'ş' => 's',
            'Ğ' | 'ğ' => 'g',
            'İ' | 'ı' => 'i',
            _ => c,
        };
        let c = c.to_ascii_lowercase();
        if c.is_ascii_alphanumeric() {
            out.push(c);
            prev_space = false;
        } else if !prev_space {
            out.push(' ');
            prev_space = true;
        }
    }
    let s = out.trim().to_string();
    let mut tokens: Vec<&str> = s.split_whitespace().collect();
    tokens.retain(|t| !matches!(*t, "cf" | "fc" | "ud" | "cd" | "sad" | "rc" | "rcd" | "ca" | "r" | "club" | "de"));
    // "espanyol barcelona" → keep espanyol; "real madrid" stays two tokens
    if tokens.len() >= 2 && tokens.last() == Some(&"barcelona") && tokens[tokens.len() - 2] == "espanyol" {
        tokens.pop();
    }
    tokens.join(" ")
}

pub fn is_highlight_title(title: &str) -> bool {
    let u = title.to_ascii_uppercase();
    (u.contains("RESUMEN") || u.contains("HIGHLIGHTS"))
        && u.contains("LALIGA")
        && !u.contains("PREVIA")
        && !u.contains("RUEDA")
        && !u.contains("PRESS")
}

pub fn title_matches(title: &str, home: &str, away: &str) -> bool {
    if !is_highlight_title(title) {
        return false;
    }
    let t = norm_name(title);
    let h = norm_name(home);
    let a = norm_name(away);
    !h.is_empty() && !a.is_empty() && t.contains(&h) && t.contains(&a)
}

/// "3:15" / "2:48" → seconds. Empty/junk → 0.
pub fn parse_clock(s: &str) -> u32 {
    let s = s.trim();
    if s.is_empty() {
        return 0;
    }
    let mut n = 0u32;
    for p in s.split(':') {
        n = n.saturating_mul(60).saturating_add(p.parse().unwrap_or(0));
    }
    n
}

/// True when the title carries `HOME_SCORE - AWAY_SCORE` with digit boundaries
/// (`1-1` must not match `11-1`).
pub fn score_in_title(title: &str, hs: i64, aws: i64) -> bool {
    let t: String = title
        .chars()
        .map(|c| match c {
            '–' | '—' | '−' => '-',
            _ => c,
        })
        .filter(|c| !c.is_whitespace())
        .collect();
    let needle = format!("{hs}-{aws}");
    let bytes = t.as_bytes();
    let n = needle.as_bytes();
    bytes.windows(n.len()).enumerate().any(|(i, w)| {
        w == n
            && (i == 0 || !bytes[i - 1].is_ascii_digit())
            && (i + n.len() >= bytes.len() || !bytes[i + n.len()].is_ascii_digit())
    })
}

/// Longest matching highlight (landscape cuts run ~20–30 s longer than the vertical ones).
/// When scores are known, prefer a title that carries that score so 2025/26 and 2026/27
/// meetings of the same two clubs do not collide.
pub fn pick_best(cands: &[YtVid], home: &str, away: &str) -> Option<String> {
    pick_best_scored(cands, home, away, None, None)
}

pub fn pick_best_scored(cands: &[YtVid], home: &str, away: &str, hs: Option<i64>, aws: Option<i64>) -> Option<String> {
    let teams: Vec<&YtVid> = cands.iter().filter(|v| title_matches(&v.title, home, away)).collect();
    let scored: Vec<&YtVid> = match (hs, aws) {
        (Some(h), Some(a)) => teams.iter().copied().filter(|v| score_in_title(&v.title, h, a)).collect(),
        _ => Vec::new(),
    };
    let pool = if scored.is_empty() { teams } else { scored };
    pool.into_iter().max_by_key(|v| v.secs).map(|v| v.id.clone())
}

pub(crate) fn json_text(v: &Value) -> String {
    if let Some(s) = v.as_str() {
        return s.to_string();
    }
    if let Some(s) = v.get("simpleText").and_then(Value::as_str) {
        return s.to_string();
    }
    v.get("runs")
        .and_then(Value::as_array)
        .map(|rs| rs.iter().filter_map(|r| r.get("text").and_then(Value::as_str)).collect::<Vec<_>>().join(""))
        .unwrap_or_default()
}

fn map_path() -> std::path::PathBuf {
    let dir = std::env::var("BEINV_VIDEO_CACHE").unwrap_or_else(|_| "/tmp/beinv-yt".into());
    std::path::PathBuf::from(dir).join("laliga-map.json")
}

fn s(v: &Value, k: &str) -> String {
    v.get(k).and_then(Value::as_str).unwrap_or("").to_string()
}

fn team(v: &Value) -> Team {
    let name = s(v, "nickname");
    let name = if name.is_empty() { s(v, "boundname") } else { name };
    let logo = v.pointer("/shield/url").and_then(Value::as_str).unwrap_or("").to_string();
    Team { name, logo, score: None }
}

fn team_with_score(v: &Value, score: Option<i64>) -> Team {
    let mut t = team(v);
    t.score = score;
    t
}

async fn load_season(client: &reqwest::Client, slug: &str) -> anyhow::Result<Arc<Vec<Value>>> {
    {
        let g = SEASON.lock().await;
        if let Some(map) = g.as_ref() {
            if let Some(c) = map.get(slug) {
                if c.loaded.elapsed() < Duration::from_secs(300) {
                    return Ok(c.matches.clone());
                }
            }
        }
    }
    let mut all = Vec::new();
    let mut offset = 0u32;
    loop {
        let url = format!("{API}?subscriptionSlug={slug}&limit=100&offset={offset}");
        let v: Value = client
            .get(&url)
            .header("Ocp-Apim-Subscription-Key", API_KEY)
            .header("Accept", "application/json")
            .send()
            .await?
            .error_for_status()?
            .json()
            .await?;
        let batch = v["matches"].as_array().cloned().unwrap_or_default();
        let n = batch.len();
        all.extend(batch);
        let total = v["total"].as_u64().unwrap_or(0) as usize;
        offset += 100;
        if n == 0 || all.len() >= total || offset > 500 {
            break;
        }
    }
    tracing::info!("laliga season dump {slug} {} matches", all.len());
    let arc = Arc::new(all);
    let mut g = SEASON.lock().await;
    let map = g.get_or_insert_with(HashMap::new);
    map.insert(slug.to_string(), SeasonDump { loaded: Instant::now(), matches: arc.clone() });
    Ok(arc)
}

fn load_persisted() -> HashMap<u64, String> {
    let mut m = seed_map();
    if let Ok(s) = std::fs::read_to_string(map_path()) {
        if let Ok(p) = serde_json::from_str::<HashMap<String, String>>(&s) {
            for (k, v) in p {
                if let Ok(id) = k.parse::<u64>() {
                    if crate::youtube::valid_id(&v) {
                        m.insert(id, v);
                    }
                }
            }
        }
    }
    m
}

async fn known_ids() -> HashMap<u64, String> {
    let mut g = BY_MATCH.lock().await;
    if g.is_none() {
        *g = Some(load_persisted());
    }
    g.as_ref().unwrap().clone()
}

async fn remember(id: u64, yt: String) {
    let snap = {
        let mut g = BY_MATCH.lock().await;
        let m = g.get_or_insert_with(load_persisted);
        m.insert(id, yt);
        m.clone()
    };
    tokio::task::spawn_blocking(move || {
        let mut out = HashMap::new();
        for (k, v) in snap {
            out.insert(k.to_string(), v);
        }
        if let Ok(s) = serde_json::to_string(&out) {
            let path = map_path();
            if let Some(dir) = path.parent() {
                let _ = std::fs::create_dir_all(dir);
            }
            let _ = std::fs::write(path, s);
        }
    });
}

async fn load_yt_index(client: &reqwest::Client) -> Vec<YtVid> {
    let mut g = YT.lock().await;
    if let Some(c) = g.as_ref() {
        if c.loaded.elapsed() < Duration::from_secs(300) {
            return c.videos.clone();
        }
    }
    let mut items: Vec<YtVid> = Vec::new();
    // RSS — latest ~15 uploads on the official channel (includes duration)
    if let Ok(xml) = async {
        let r = client.get(RSS).send().await?.error_for_status()?;
        r.text().await
    }
    .await
    {
        for block in xml.split("<entry>") {
            let id = block
                .split("<yt:videoId>")
                .nth(1)
                .and_then(|s| s.split("</yt:videoId>").next())
                .unwrap_or("")
                .to_string();
            let title = block
                .split("<media:title>")
                .nth(1)
                .and_then(|s| s.split("</media:title>").next())
                .unwrap_or("")
                .to_string();
            let secs = block
                .split("<yt:duration seconds=\"")
                .nth(1)
                .and_then(|s| s.split('"').next())
                .and_then(|s| s.parse().ok())
                .unwrap_or(0);
            if !id.is_empty() && is_highlight_title(&title) {
                items.push(YtVid { id, title, secs });
            }
        }
    }
    // Innertube search — official RESUMEN/HIGHLIGHTS for both overlay seasons.
    for query in ["RESUMEN LALIGA EA SPORTS", "RESUMEN LALIGA EA SPORTS 2025"] {
        let body = serde_json::json!({
            "context": { "client": { "clientName": "WEB", "clientVersion": "2.20260101.00.00", "hl": "en" } },
            "query": query,
            "params": "EgIQAQ=="
        });
        if let Ok(v) = async {
            let r = client
                .post(INNERTUBE)
                .header("Content-Type", "application/json")
                .header("X-YouTube-Client-Name", "1")
                .json(&body)
                .send()
                .await?
                .error_for_status()?;
            r.json::<Value>().await
        }
        .await
        {
            walk_videos(&v, &mut items);
        }
    }
    let mut seen = std::collections::HashSet::new();
    // Keep the copy with the higher duration when the same id appears twice.
    items.sort_by_key(|v| std::cmp::Reverse(v.secs));
    items.retain(|v| seen.insert(v.id.clone()));
    tracing::info!("laliga youtube index {} highlights (channel {YT_CHANNEL})", items.len());
    *g = Some(YtIndex { loaded: Instant::now(), videos: items.clone() });
    items
}

fn walk_videos(v: &Value, out: &mut Vec<YtVid>) {
    match v {
        Value::Object(m) => {
            if let Some(id) = m.get("videoId").and_then(Value::as_str) {
                let title = m.get("title").map(json_text).unwrap_or_default();
                let secs = m.get("lengthText").map(|t| parse_clock(&json_text(t))).unwrap_or(0);
                if is_highlight_title(&title) {
                    out.push(YtVid { id: id.to_string(), title, secs });
                }
            }
            for x in m.values() {
                walk_videos(x, out);
            }
        }
        Value::Array(a) => {
            for x in a {
                walk_videos(x, out);
            }
        }
        _ => {}
    }
}

async fn search_one(
    client: &reqwest::Client,
    home: &str,
    away: &str,
    year: &str,
    hs: Option<i64>,
    aws: Option<i64>,
) -> Option<String> {
    let q = format!("{home} {away} RESUMEN LALIGA EA SPORTS {year}");
    let body = serde_json::json!({
        "context": { "client": { "clientName": "WEB", "clientVersion": "2.20260101.00.00", "hl": "en" } },
        "query": q,
        "params": "EgIQAQ=="
    });
    let v: Value = client
        .post(INNERTUBE)
        .header("Content-Type", "application/json")
        .json(&body)
        .send()
        .await
        .ok()?
        .error_for_status()
        .ok()?
        .json()
        .await
        .ok()?;
    let mut items = Vec::new();
    walk_videos(&v, &mut items);
    pick_best_scored(&items, home, away, hs, aws)
}

/// Matches of one beIN week that have a published official highlight.
pub async fn fetch_week(client: &reqwest::Client, season: u64, round: u32) -> anyhow::Result<Vec<Match>> {
    let Some(slug) = slug_for(season) else {
        return Ok(Vec::new());
    };
    let year = if season == BEIN_SEASON_2025 { "2025" } else { "2026" };
    let dump = load_season(client, slug).await?;
    let index = load_yt_index(client).await;
    let mut known = known_ids().await;

    let mut out = Vec::new();
    for raw in dump.iter() {
        let week = raw.pointer("/gameweek/week").and_then(Value::as_u64).unwrap_or(0) as u32;
        if week != round {
            continue;
        }
        let id = raw["id"].as_u64().unwrap_or(0);
        if id == 0 {
            continue;
        }
        let home_v = &raw["home_team"];
        let away_v = &raw["away_team"];
        let home_name = s(home_v, "nickname");
        let away_name = s(away_v, "nickname");
        let status = s(raw, "status");
        if status != "FullTime" {
            continue;
        }
        let hs = raw.get("home_score").and_then(Value::as_i64);
        let aws = raw.get("away_score").and_then(Value::as_i64);
        // Live index wins over the seed: landscape cuts are ~20 s longer than the
        // vertical ones of the same match, so pick_best prefers them. Score in the
        // title disambiguates the same fixture across 2025/26 and 2026/27.
        let yt = pick_best_scored(&index, &home_name, &away_name, hs, aws).or_else(|| known.get(&id).cloned());
        let yt = match yt {
            Some(id) => Some(id),
            None => search_one(client, &home_name, &away_name, year, hs, aws).await,
        };
        let Some(yt) = yt else { continue };
        known.insert(id, yt.clone());
        remember(id, yt.clone()).await;

        let home = team_with_score(home_v, hs);
        let away = team_with_score(away_v, aws);
        let score = format!(
            "{}-{}",
            hs.map(|n| n.to_string()).unwrap_or_else(|| "–".into()),
            aws.map(|n| n.to_string()).unwrap_or_else(|| "–".into())
        );
        let title = format!("{} {score} {} Maç Özeti", home.name, away.name);
        let date = s(raw, "date");
        let date = if date.is_empty() { s(raw, "time") } else { date };
        out.push(Match {
            id,
            round,
            title,
            date,
            home,
            away,
            thumb: format!("https://i.ytimg.com/vi/{yt}/hqdefault.jpg"),
            has_highlight: true,
            has_hd: true,
            events: Vec::new(),
            highlight_url: format!("yt:{yt}"),
            hd_url: format!("yt:{yt}"),
        });
    }
    out.sort_by(|a, b| a.date.cmp(&b.date));
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn norm_strips_suffixes_and_accents() {
        assert_eq!(norm_name("RCD Espanyol de Barcelona"), "espanyol");
        assert_eq!(norm_name("Deportivo Alavés"), "deportivo alaves");
        assert_eq!(norm_name("Atlético de Madrid"), "atletico madrid");
        assert_eq!(norm_name("R. Racing Club"), "racing");
        assert_eq!(norm_name("Celta"), "celta");
        assert_eq!(norm_name("RC Celta"), "celta");
        assert_eq!(norm_name("FC Barcelona"), "barcelona");
        assert_eq!(norm_name("Elche CF"), "elche");
        assert_eq!(norm_name("Beşiktaş"), "besiktas");
        assert_eq!(norm_name("İstanbul"), "istanbul");
    }

    #[test]
    fn official_titles_match_both_teams() {
        assert!(title_matches(
            "DEPORTIVO ALAVÉS 3 - 0 GETAFE CF | RESUMEN LALIGA EA SPORTS",
            "Deportivo Alavés",
            "Getafe CF"
        ));
        assert!(title_matches("ELCHE CF 0 - 5 FC BARCELONA | RESUMEN LALIGA EA SPORTS", "Elche CF", "FC Barcelona"));
        assert!(title_matches(
            "RCD ESPANYOL 1 - 2 REAL MADRID | HIGHLIGHTS LALIGA EA SPORTS",
            "RCD Espanyol de Barcelona",
            "Real Madrid"
        ));
        assert!(title_matches("VALENCIA CF 0 - 0 CELTA | HIGHLIGHTS LALIGA EA SPORTS", "Valencia CF", "Celta"));
        assert!(!title_matches("ELCHE CF vs FC BARCELONA | RUEDA DE PRENSA", "Elche CF", "FC Barcelona"));
        assert!(!title_matches(
            "DEPORTIVO ALAVÉS 3 - 0 GETAFE CF | RESUMEN LALIGA EA SPORTS",
            "Athletic Club",
            "Sevilla FC"
        ));
    }

    #[test]
    fn seed_covers_opening_matchweeks() {
        let m = seed_map();
        assert!(m.len() >= 14);
        assert_eq!(m.get(&102249).unwrap(), "9WlsPzrTSqU");
        assert_eq!(m.get(&102262).unwrap(), "jV-hxmJQKfc");
        assert_eq!(m.get(&102260).unwrap(), "eDzd_2_7vzI");
        assert_eq!(m.get(&98461).unwrap(), "K7r_YgyFevA");
        assert_eq!(m.get(&98457).unwrap(), "mHPTne26Q0I");
    }

    #[test]
    fn pick_best_prefers_the_longer_landscape_cut() {
        let title = "ATLÉTICO DE MADRID 2 - 2 VILLARREAL CF | RESUMEN LALIGA EA SPORTS";
        let cands = [
            YtVid { id: "4dPE5OkzbeQ".into(), title: title.into(), secs: 169 },
            YtVid { id: "eDzd_2_7vzI".into(), title: title.into(), secs: 194 },
        ];
        assert_eq!(pick_best(&cands, "Atlético de Madrid", "Villarreal CF").as_deref(), Some("eDzd_2_7vzI"));
        assert_eq!(parse_clock("3:15"), 195);
        assert_eq!(parse_clock("2:48"), 168);
    }

    #[test]
    fn score_in_title_uses_digit_boundaries() {
        assert!(score_in_title("VILLARREAL CF 5 - 1 ATLÉTICO DE MADRID | RESUMEN LALIGA EA SPORTS", 5, 1));
        assert!(score_in_title("ELCHE CF 0-5 FC BARCELONA | RESUMEN", 0, 5));
        assert!(!score_in_title("TEAM 11 - 1 TEAM | RESUMEN", 1, 1));
        assert!(score_in_title("TEAM 1 - 1 TEAM | RESUMEN", 1, 1));
    }

    #[test]
    fn slug_covers_both_overlay_seasons() {
        assert_eq!(slug_for(3968), Some("laliga-easports-2026"));
        assert_eq!(slug_for(3850), Some("laliga-easports-2025"));
        assert_eq!(slug_for(3717), None);
    }
}
