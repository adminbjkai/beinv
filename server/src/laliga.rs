//! La Liga 2026/2027 highlight overlay.
//!
//! beIN TR exposes İspanya La Liga seasons/weeks (`ispanya-la-liga`, org 60, season 3968)
//! but `highlights/events` is empty (`{"Data":{}}`) for every season checked. Fixtures and
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
const SUBSCRIPTION: &str = "laliga-easports-2026";
const API: &str = "https://apim.laliga.com/public-service/api/v1/matches";
const API_KEY: &str = "c13c3a8e2f6b46da9c5c425cf61fab3e";
const YT_CHANNEL: &str = "UCTv-XvfzLX3i4IGWAm4sbmA";
const RSS: &str = "https://www.youtube.com/feeds/videos.xml?channel_id=UCTv-XvfzLX3i4IGWAm4sbmA";
const INNERTUBE: &str = "https://www.youtube.com/youtubei/v1/search?prettyPrint=false";

const SEED: &str = include_str!("data/laliga-youtube.json");

struct SeasonDump {
    loaded: Instant,
    matches: Arc<Vec<Value>>,
}

struct YtIndex {
    loaded: Instant,
    /// normalized title → video id (official RESUMEN/HIGHLIGHTS only)
    by_title: Vec<(String, String, String)>, // (id, title, norm_title)
}

static SEASON: Mutex<Option<SeasonDump>> = Mutex::const_new(None);
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
    tokens.retain(|t| {
        !matches!(
            *t,
            "cf" | "fc" | "ud" | "cd" | "sad" | "rc" | "rcd" | "ca" | "r" | "club" | "de"
        )
    });
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

fn s(v: &Value, k: &str) -> String {
    v.get(k).and_then(Value::as_str).unwrap_or("").to_string()
}

fn team(v: &Value) -> Team {
    let name = s(v, "nickname");
    let name = if name.is_empty() { s(v, "boundname") } else { name };
    let logo = v
        .pointer("/shield/url")
        .and_then(Value::as_str)
        .unwrap_or("")
        .to_string();
    Team { name, logo, score: None }
}

fn team_with_score(v: &Value, score: Option<i64>) -> Team {
    let mut t = team(v);
    t.score = score;
    t
}

async fn load_season(client: &reqwest::Client) -> anyhow::Result<Arc<Vec<Value>>> {
    let mut g = SEASON.lock().await;
    if let Some(c) = g.as_ref() {
        if c.loaded.elapsed() < Duration::from_secs(300) {
            return Ok(c.matches.clone());
        }
    }
    let mut all = Vec::new();
    let mut offset = 0u32;
    loop {
        let url = format!("{API}?subscriptionSlug={SUBSCRIPTION}&limit=100&offset={offset}");
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
    tracing::info!("laliga season dump {} matches", all.len());
    let arc = Arc::new(all);
    *g = Some(SeasonDump { loaded: Instant::now(), matches: arc.clone() });
    Ok(arc)
}

async fn known_ids() -> HashMap<u64, String> {
    let mut g = BY_MATCH.lock().await;
    if g.is_none() {
        *g = Some(seed_map());
    }
    g.as_ref().unwrap().clone()
}

async fn remember(id: u64, yt: String) {
    let mut g = BY_MATCH.lock().await;
    g.get_or_insert_with(seed_map).insert(id, yt);
}

async fn load_yt_index(client: &reqwest::Client) -> Vec<(String, String, String)> {
    let mut g = YT.lock().await;
    if let Some(c) = g.as_ref() {
        if c.loaded.elapsed() < Duration::from_secs(900) {
            return c.by_title.clone();
        }
    }
    let mut items: Vec<(String, String, String)> = Vec::new();
    // RSS — latest ~15 uploads on the official channel
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
            if !id.is_empty() && is_highlight_title(&title) {
                let n = norm_name(&title);
                items.push((id, title, n));
            }
        }
    }
    // Innertube search — broader than RSS, still the official "RESUMEN LALIGA EA SPORTS" titles
    let body = serde_json::json!({
        "context": { "client": { "clientName": "WEB", "clientVersion": "2.20260101.00.00", "hl": "en" } },
        "query": "RESUMEN LALIGA EA SPORTS",
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
    // de-dupe by video id, keep first
    let mut seen = std::collections::HashSet::new();
    items.retain(|(id, _, _)| seen.insert(id.clone()));
    tracing::info!("laliga youtube index {} highlights (channel {YT_CHANNEL})", items.len());
    *g = Some(YtIndex { loaded: Instant::now(), by_title: items.clone() });
    items
}

fn walk_videos(v: &Value, out: &mut Vec<(String, String, String)>) {
    match v {
        Value::Object(m) => {
            if let Some(id) = m.get("videoId").and_then(Value::as_str) {
                let title = m
                    .get("title")
                    .map(|t| {
                        if let Some(s) = t.as_str() {
                            s.to_string()
                        } else if let Some(s) = t.get("simpleText").and_then(Value::as_str) {
                            s.to_string()
                        } else {
                            t.get("runs")
                                .and_then(Value::as_array)
                                .map(|rs| {
                                    rs.iter()
                                        .filter_map(|r| r.get("text").and_then(Value::as_str))
                                        .collect::<Vec<_>>()
                                        .join("")
                                })
                                .unwrap_or_default()
                        }
                    })
                    .unwrap_or_default();
                if is_highlight_title(&title) {
                    let n = norm_name(&title);
                    out.push((id.to_string(), title, n));
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

fn lookup<'a>(index: &'a [(String, String, String)], home: &str, away: &str) -> Option<&'a str> {
    index.iter().find(|(_, title, _)| title_matches(title, home, away)).map(|(id, _, _)| id.as_str())
}

async fn search_one(client: &reqwest::Client, home: &str, away: &str) -> Option<String> {
    let q = format!("{home} {away} RESUMEN LALIGA EA SPORTS");
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
    lookup(&items, home, away).map(str::to_string)
}

/// Matches of one beIN week that have a published official highlight.
pub async fn fetch_week(client: &reqwest::Client, season: u64, round: u32) -> anyhow::Result<Vec<Match>> {
    if season != BEIN_SEASON_2026 {
        return Ok(Vec::new());
    }
    let dump = load_season(client).await?;
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
        let yt = if let Some(id) = known.get(&id) {
            Some(id.clone())
        } else if let Some(id) = lookup(&index, &home_name, &away_name) {
            Some(id.to_string())
        } else {
            search_one(client, &home_name, &away_name).await
        };
        let Some(yt) = yt else { continue };
        known.insert(id, yt.clone());
        remember(id, yt.clone()).await;

        let hs = raw.get("home_score").and_then(Value::as_i64);
        let aws = raw.get("away_score").and_then(Value::as_i64);
        let home = team_with_score(home_v, hs);
        let away = team_with_score(away_v, aws);
        let score = format!("{}-{}", hs.map(|n| n.to_string()).unwrap_or_else(|| "–".into()), aws.map(|n| n.to_string()).unwrap_or_else(|| "–".into()));
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
            events: Vec::new(),
            highlight_url: format!("yt:{yt}"),
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
    }

    #[test]
    fn official_titles_match_both_teams() {
        assert!(title_matches(
            "DEPORTIVO ALAVÉS 3 - 0 GETAFE CF | RESUMEN LALIGA EA SPORTS",
            "Deportivo Alavés",
            "Getafe CF"
        ));
        assert!(title_matches(
            "ELCHE CF 0 - 5 FC BARCELONA | RESUMEN LALIGA EA SPORTS",
            "Elche CF",
            "FC Barcelona"
        ));
        assert!(title_matches(
            "RCD ESPANYOL 1 - 2 REAL MADRID | HIGHLIGHTS LALIGA EA SPORTS",
            "RCD Espanyol de Barcelona",
            "Real Madrid"
        ));
        assert!(title_matches(
            "VALENCIA CF 0 - 0 CELTA | HIGHLIGHTS LALIGA EA SPORTS",
            "Valencia CF",
            "Celta"
        ));
        assert!(!title_matches(
            "ELCHE CF vs FC BARCELONA | RUEDA DE PRENSA",
            "Elche CF",
            "FC Barcelona"
        ));
        assert!(!title_matches(
            "DEPORTIVO ALAVÉS 3 - 0 GETAFE CF | RESUMEN LALIGA EA SPORTS",
            "Athletic Club",
            "Sevilla FC"
        ));
    }

    #[test]
    fn seed_covers_opening_matchweeks() {
        let m = seed_map();
        assert_eq!(m.len(), 14);
        assert_eq!(m.get(&102249).unwrap(), "9WlsPzrTSqU");
        assert_eq!(m.get(&102262).unwrap(), "9XiG_TtRGlI");
    }
}
