//! Optional HD overlay for Trendyol Süper Lig 2026/2027.
//!
//! Default playback stays the beIN mp4 (same as before). When the client asks for
//! `q=hd`, the full highlight is remuxed from the official beIN SPORTS Türkiye
//! YouTube channel (`UCPe9vNjHF1kEExT5kHwc7aw`), same pipeline as La Liga.
//! Goal clips stay on beIN. Matching is automatic for the rest of the season.

use std::{
    collections::HashMap,
    time::{Duration, Instant},
};

use serde_json::Value;
use tokio::sync::Mutex;

use crate::bein::Match;
use crate::laliga::{self, json_text, parse_clock, YtVid};

pub const BEIN_SEASON_2026: u64 = 3974;
const YT_CHANNEL: &str = "UCPe9vNjHF1kEExT5kHwc7aw";
const RSS: &str = "https://www.youtube.com/feeds/videos.xml?channel_id=UCPe9vNjHF1kEExT5kHwc7aw";
const INNERTUBE: &str = "https://www.youtube.com/youtubei/v1/search?prettyPrint=false";
const SEED: &str = include_str!("data/superlig-youtube.json");

struct YtIndex {
    loaded: Instant,
    videos: Vec<YtVid>,
}

static YT: Mutex<Option<YtIndex>> = Mutex::const_new(None);
static BY_MATCH: Mutex<Option<HashMap<u64, String>>> = Mutex::const_new(None);

const SPONSORS: &[&str] = &[
    "corendon", "tumosan", "trendyol", "sipay", "yilport", "mondihome", "atakas", "bitexen",
    "vavacars", "ikas", "reeder", "onvo", "hellas", "rams", "eminevim", "bellona", "pasha",
    "solanis", "misli", "ikascasino",
];

fn seed_map() -> HashMap<u64, String> {
    serde_json::from_str::<HashMap<String, String>>(SEED)
        .unwrap_or_default()
        .into_iter()
        .filter_map(|(k, v)| k.parse().ok().map(|id: u64| (id, v)))
        .collect()
}

fn map_path() -> std::path::PathBuf {
    let dir = std::env::var("BEINV_VIDEO_CACHE").unwrap_or_else(|_| "/tmp/beinv-yt".into());
    std::path::PathBuf::from(dir).join("superlig-map.json")
}

/// Drop leading shirt-sponsor tokens so "Corendon Alanyaspor" matches a title that says "Alanyaspor".
pub fn club_key(name: &str) -> String {
    let n = laliga::norm_name(name);
    let mut toks: Vec<&str> = n.split_whitespace().collect();
    while toks.len() > 1 && SPONSORS.contains(&toks[0]) {
        toks.remove(0);
    }
    toks.join(" ")
}

pub fn sl_highlight(title: &str) -> bool {
    let n = laliga::norm_name(title);
    n.contains("super lig")
        && n.contains("highlights")
        && n.contains("ozet")
        && !n.contains("1 lig")
        && !n.contains("mac sonu")
        && !n.contains("shorts")
}

fn title_has_club(title_norm: &str, club: &str) -> bool {
    if club.is_empty() {
        return false;
    }
    if title_norm.contains(club) {
        return true;
    }
    club.split_whitespace()
        .last()
        .map(|last| last.len() >= 5 && title_norm.contains(last))
        .unwrap_or(false)
}

pub fn pick_best(cands: &[YtVid], home: &str, away: &str) -> Option<String> {
    let h = club_key(home);
    let a = club_key(away);
    cands
        .iter()
        .filter(|v| {
            if !sl_highlight(&v.title) {
                return false;
            }
            let t = laliga::norm_name(&v.title);
            title_has_club(&t, &h) && title_has_club(&t, &a)
        })
        .max_by_key(|v| v.secs)
        .map(|v| v.id.clone())
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

fn walk_videos(v: &Value, out: &mut Vec<YtVid>) {
    match v {
        Value::Object(m) => {
            if let Some(id) = m.get("videoId").and_then(Value::as_str) {
                let title = m.get("title").map(json_text).unwrap_or_default();
                let secs = m.get("lengthText").map(|t| parse_clock(&json_text(t))).unwrap_or(0);
                if sl_highlight(&title) {
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

async fn load_yt_index(client: &reqwest::Client) -> Vec<YtVid> {
    let mut g = YT.lock().await;
    if let Some(c) = g.as_ref() {
        if c.loaded.elapsed() < Duration::from_secs(300) {
            return c.videos.clone();
        }
    }
    let mut items: Vec<YtVid> = Vec::new();
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
            if !id.is_empty() && sl_highlight(&title) {
                items.push(YtVid { id, title, secs });
            }
        }
    }
    let body = serde_json::json!({
        "context": { "client": { "clientName": "WEB", "clientVersion": "2.20260101.00.00", "hl": "en" } },
        "query": "Highlights/Özet | Trendyol Süper Lig - 2026/27",
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
    let mut seen = std::collections::HashSet::new();
    items.sort_by_key(|v| std::cmp::Reverse(v.secs));
    items.retain(|v| seen.insert(v.id.clone()));
    tracing::info!("superlig youtube index {} highlights (channel {YT_CHANNEL})", items.len());
    *g = Some(YtIndex { loaded: Instant::now(), videos: items.clone() });
    items
}

async fn search_one(client: &reqwest::Client, home: &str, away: &str) -> Option<String> {
    let q = format!("{} {} Highlights/Özet Trendyol Süper Lig", club_key(home), club_key(away));
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
    pick_best(&items, home, away)
}

/// Attach YouTube HD sources onto beIN matches of the current season. Does not replace beIN urls.
pub async fn attach_hd(client: &reqwest::Client, season: u64, matches: &mut [Match]) {
    if season != BEIN_SEASON_2026 || matches.is_empty() {
        return;
    }
    let index = load_yt_index(client).await;
    let mut known = known_ids().await;
    for m in matches.iter_mut() {
        let yt = pick_best(&index, &m.home.name, &m.away.name)
            .or_else(|| known.get(&m.id).cloned());
        let yt = match yt {
            Some(id) => Some(id),
            None => search_one(client, &m.home.name, &m.away.name).await,
        };
        let Some(yt) = yt else { continue };
        known.insert(m.id, yt.clone());
        remember(m.id, yt.clone()).await;
        m.has_hd = true;
        m.hd_url = format!("yt:{yt}");
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn club_key_drops_shirt_sponsors() {
        assert_eq!(club_key("Corendon Alanyaspor"), "alanyaspor");
        assert_eq!(club_key("Tümosan Konyaspor"), "konyaspor");
        assert_eq!(club_key("Beşiktaş"), "besiktas");
        assert_eq!(club_key("İstanbul Başakşehir"), "istanbul basaksehir");
        assert_eq!(club_key("Çaykur Rizespor"), "caykur rizespor");
    }

    #[test]
    fn picks_official_full_highlight_not_a_goal_clip() {
        let cands = [
            YtVid {
                id: "short".into(),
                title: "Nuno Lima'dan Galibiyet Golü! | Alanyaspor - Beşiktaş | Trendyol Süper Lig".into(),
                secs: 40,
            },
            YtVid {
                id: "QJ31yOM88UQ".into(),
                title: "Alanyaspor - Beşiktaş - Highlights/Özet | Trendyol Süper Lig - 2026/27".into(),
                secs: 435,
            },
        ];
        assert_eq!(
            pick_best(&cands, "Corendon Alanyaspor", "Beşiktaş").as_deref(),
            Some("QJ31yOM88UQ")
        );
        assert!(sl_highlight("Alanyaspor - Beşiktaş - Highlights/Özet | Trendyol Süper Lig - 2026/27"));
        assert!(!sl_highlight("Vanspor - İstanbulspor | 3. Hafta Maç ÖZETİ | Trendyol 1. Lig - 2026/27"));
    }

    #[test]
    fn seed_has_besiktas_week2() {
        let m = seed_map();
        assert_eq!(m.get(&1515722).unwrap(), "QJ31yOM88UQ");
    }
}
