//! Optional HD overlay for İngiltere Premier Lig 2026/2027.
//!
//! Default playback stays the beIN mp4. When the client asks for `q=hd`, the full
//! highlight is remuxed from the official NBC Sports YouTube channel
//! (`UCqZQlzSHbVJrwrn5XvzrzcA`), same pipeline as Süper Lig / La Liga.
//! Titles look like `Manchester City v. Bournemouth | PREMIER LEAGUE HIGHLIGHTS | 8/23/2026 | NBC Sports`.

use std::{
    collections::HashMap,
    time::{Duration, Instant},
};

use serde_json::Value;
use tokio::sync::Mutex;

use crate::bein::Match;
use crate::laliga::{self, json_text, parse_clock, YtVid};

pub const BEIN_SEASON_2026: u64 = 3958;
const YT_CHANNEL: &str = "UCqZQlzSHbVJrwrn5XvzrzcA";
const RSS: &str = "https://www.youtube.com/feeds/videos.xml?channel_id=UCqZQlzSHbVJrwrn5XvzrzcA";
const INNERTUBE: &str = "https://www.youtube.com/youtubei/v1/search?prettyPrint=false";
const SEED: &str = include_str!("data/premier-youtube.json");

struct YtIndex {
    loaded: Instant,
    videos: Vec<YtVid>,
}

static YT: Mutex<Option<YtIndex>> = Mutex::const_new(None);
static BY_MATCH: Mutex<Option<HashMap<u64, String>>> = Mutex::const_new(None);

fn seed_map() -> HashMap<u64, String> {
    serde_json::from_str::<HashMap<String, String>>(SEED)
        .unwrap_or_default()
        .into_iter()
        .filter_map(|(k, v)| k.parse().ok().map(|id: u64| (id, v)))
        .collect()
}

fn map_path() -> std::path::PathBuf {
    let dir = std::env::var("BEINV_VIDEO_CACHE").unwrap_or_else(|_| "/tmp/beinv-yt".into());
    std::path::PathBuf::from(dir).join("premier-map.json")
}

/// Keys that appear in NBC titles. "Newcastle Utd." must match "Newcastle United";
/// "Manchester City" must not also match "Manchester United".
pub fn club_keys(name: &str) -> Vec<String> {
    let n = laliga::norm_name(name).replace(" utd", " united");
    let extra: &[&str] = match n.as_str() {
        s if s.starts_with("newcastle") => &["newcastle"],
        "brighton and hove albion" => &["brighton"],
        "nottingham forest" => &["nottingham forest", "nottingham"],
        "crystal palace" => &["crystal palace"],
        "leeds united" => &["leeds"],
        "hull city" => &["hull"],
        "coventry city" => &["coventry"],
        "ipswich town" => &["ipswich"],
        "tottenham" | "tottenham hotspur" => &["tottenham"],
        "wolverhampton wanderers" | "wolves" => &["wolves", "wolverhampton"],
        "west ham united" | "west ham" => &["west ham"],
        "leicester city" => &["leicester"],
        "sunderland" | "sunderland afc" => &["sunderland"],
        "afc bournemouth" | "bournemouth" => &["bournemouth"],
        "manchester united" => &["manchester united"],
        "manchester city" => &["manchester city"],
        "aston villa" => &["aston villa"],
        "nottingham" => &["nottingham"],
        _ => &[],
    };
    let mut keys = vec![n];
    for e in extra {
        if !keys.iter().any(|k| k == e) {
            keys.push((*e).to_string());
        }
    }
    keys
}

pub fn pl_highlight(title: &str) -> bool {
    let n = laliga::norm_name(title);
    n.contains("premier league")
        && n.contains("highlights")
        && !n.contains("extended")
        && !n.contains("preview")
        && !n.contains("pre match")
        && !n.contains("postgame")
        && !n.contains("shorts")
        && !n.contains("top 10")
        && !n.contains("best goals")
}

fn title_has_club(title_norm: &str, name: &str) -> bool {
    club_keys(name).iter().any(|k| k.len() >= 4 && title_norm.contains(k))
}

/// NBC titles carry `M/D/YYYY` (no leading zeros), equal to the kickoff calendar day.
pub fn date_token(iso: &str) -> Option<String> {
    let d = iso.get(..10)?;
    let y: u32 = d.get(..4)?.parse().ok()?;
    let m: u32 = d.get(5..7)?.parse().ok()?;
    let day: u32 = d.get(8..10)?.parse().ok()?;
    Some(format!("{m}/{day}/{y}"))
}

pub fn pick_best(cands: &[YtVid], home: &str, away: &str) -> Option<String> {
    pick_best_dated(cands, home, away, "")
}

pub fn pick_best_dated(cands: &[YtVid], home: &str, away: &str, iso: &str) -> Option<String> {
    let teams: Vec<&YtVid> = cands
        .iter()
        .filter(|v| {
            if !pl_highlight(&v.title) {
                return false;
            }
            // Goal clips are 1–4 min; full NBC highlights are ~8–16 min. Keep unknown duration.
            if v.secs > 0 && v.secs < 480 {
                return false;
            }
            let t = laliga::norm_name(&v.title);
            title_has_club(&t, home) && title_has_club(&t, away)
        })
        .collect();
    let dated: Vec<&YtVid> = if let Some(tok) = date_token(iso) {
        teams.iter().copied().filter(|v| v.title.contains(&tok)).collect()
    } else {
        Vec::new()
    };
    let pool = if dated.is_empty() { teams } else { dated };
    pool.into_iter().max_by_key(|v| v.secs).map(|v| v.id.clone())
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
                if pl_highlight(&title) {
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
            if !id.is_empty() && pl_highlight(&title) {
                items.push(YtVid { id, title, secs });
            }
        }
    }
    let body = serde_json::json!({
        "context": { "client": { "clientName": "WEB", "clientVersion": "2.20260101.00.00", "hl": "en" } },
        "query": "PREMIER LEAGUE HIGHLIGHTS NBC Sports 2026",
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
    tracing::info!("premier youtube index {} highlights (channel {YT_CHANNEL})", items.len());
    *g = Some(YtIndex { loaded: Instant::now(), videos: items.clone() });
    items
}

async fn search_one(client: &reqwest::Client, home: &str, away: &str) -> Option<String> {
    let q = format!(
        "{} v. {} PREMIER LEAGUE HIGHLIGHTS NBC Sports",
        club_keys(home).first().cloned().unwrap_or_default(),
        club_keys(away).first().cloned().unwrap_or_default()
    );
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
    pick_best_dated(&items, home, away, "")
}

/// Attach YouTube HD sources onto beIN matches of the current season. Does not replace beIN urls.
pub async fn attach_hd(client: &reqwest::Client, season: u64, matches: &mut [Match]) {
    if season != BEIN_SEASON_2026 || matches.is_empty() {
        return;
    }
    let index = load_yt_index(client).await;
    let mut known = known_ids().await;
    for m in matches.iter_mut() {
        let yt = pick_best_dated(&index, &m.home.name, &m.away.name, &m.date).or_else(|| known.get(&m.id).cloned());
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
    fn club_keys_nbc_names() {
        assert!(club_keys("Newcastle Utd.").iter().any(|k| k == "newcastle"));
        assert!(club_keys("Brighton and Hove Albion").iter().any(|k| k == "brighton"));
        assert!(club_keys("Bournemouth").iter().any(|k| k == "bournemouth"));
        assert!(club_keys("Manchester City").iter().any(|k| k == "manchester city"));
        assert!(club_keys("Manchester United").iter().any(|k| k == "manchester united"));
        assert!(!club_keys("Manchester City").iter().any(|k| k == "manchester united"));
        assert!(club_keys("Tottenham").iter().any(|k| k == "tottenham"));
        assert!(club_keys("Hull City").iter().any(|k| k == "hull"));
    }

    #[test]
    fn picks_nbc_full_highlight() {
        let cands = [
            YtVid {
                id: "short".into(),
                title: "Manchester City v. Bournemouth | PREMIER LEAGUE HIGHLIGHTS | 8/23/2026 | NBC Sports".into(),
                secs: 40,
            },
            YtVid {
                id: "8bWcZxr_bKE".into(),
                title: "Manchester City v. Bournemouth | PREMIER LEAGUE HIGHLIGHTS | 8/23/2026 | NBC Sports".into(),
                secs: 865,
            },
            YtVid {
                id: "other".into(),
                title: "Arsenal v. Coventry City | PREMIER LEAGUE HIGHLIGHTS | 8/21/2026 | NBC Sports".into(),
                secs: 800,
            },
        ];
        assert_eq!(pick_best(&cands, "Manchester City", "Bournemouth").as_deref(), Some("8bWcZxr_bKE"));
        assert!(pl_highlight("Manchester City v. Bournemouth | PREMIER LEAGUE HIGHLIGHTS | 8/23/2026 | NBC Sports"));
        assert!(!pl_highlight("2026 Vuelta a España, Stage 2 | EXTENDED HIGHLIGHTS | 8/23/2026 | Cycling on NBC"));
    }

    #[test]
    fn seed_has_city_bournemouth_week1() {
        let m = seed_map();
        assert_eq!(m.get(&1510542).unwrap(), "8bWcZxr_bKE");
        assert_eq!(m.len(), 9);
    }

    #[test]
    fn newcastle_utd_matches_united_title() {
        let cands = [YtVid {
            id: "j_18Q-ziTKQ".into(),
            title: "Newcastle United v. Liverpool | PREMIER LEAGUE HIGHLIGHTS | 8/23/2026 | NBC Sports".into(),
            secs: 900,
        }];
        assert_eq!(pick_best(&cands, "Newcastle Utd.", "Liverpool").as_deref(), Some("j_18Q-ziTKQ"));
    }

    #[test]
    fn date_token_is_us_mdy_without_leading_zeros() {
        assert_eq!(date_token("2026-08-23T18:00:00Z").as_deref(), Some("8/23/2026"));
        assert_eq!(date_token("2026-08-03T12:00:00Z").as_deref(), Some("8/3/2026"));
        let old = YtVid {
            id: "old".into(),
            title: "Everton v. Crystal Palace | PREMIER LEAGUE HIGHLIGHTS | 5/19/2025 | NBC Sports".into(),
            secs: 900,
        };
        let now = YtVid {
            id: "Fl3b1fK05M0".into(),
            title: "Everton v. Crystal Palace | PREMIER LEAGUE HIGHLIGHTS | 8/22/2026 | NBC Sports".into(),
            secs: 645,
        };
        assert_eq!(
            pick_best_dated(&[old, now], "Everton", "Crystal Palace", "2026-08-22T14:00:00Z").as_deref(),
            Some("Fl3b1fK05M0")
        );
    }
}
