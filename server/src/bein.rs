//! Upstream beIN SPORTS client + lean DTO mapping. See docs/UPSTREAM_API.md.

use serde::{Deserialize, Serialize};
use serde_json::Value;

#[derive(Clone, Copy, Serialize)]
pub struct League {
    pub id: &'static str,
    pub name: &'static str,
    pub org_id: u32,
    pub sport_id: u32,
}

pub const LEAGUES: &[League] = &[
    League { id: "super-lig", name: "Trendyol Süper Lig", org_id: 18, sport_id: 1 },
    League { id: "ingiltere-premier-ligi", name: "İngiltere Premier Lig", org_id: 17, sport_id: 1 },
    League { id: "ispanya-la-liga", name: "İspanya La Liga", org_id: 60, sport_id: 1 },
];

pub fn league(id: &str) -> Option<League> {
    LEAGUES.iter().copied().find(|l| l.id == id)
}

#[derive(Clone, Serialize)]
pub struct Week {
    pub round: u32,
    pub name: String,
    pub is_current: bool,
}

#[derive(Clone, Serialize)]
pub struct Season {
    pub id: u64,
    pub name: String,
    pub is_current: bool,
    pub weeks: Vec<Week>,
}

#[derive(Clone, Serialize)]
pub struct Team {
    pub name: String,
    pub logo: String,
    pub score: Option<i64>,
}

#[derive(Clone, Serialize)]
pub struct Event {
    pub id: u64,
    pub minute: i64,
    pub description: String,
    pub is_goal: bool,
    pub side: Option<String>,
    pub thumb: String,
    /// false when upstream has no clip for this event (still counted in running scores)
    pub has_video: bool,
    #[serde(skip)]
    pub mp4: String,
}

#[derive(Clone, Serialize)]
pub struct Match {
    pub id: u64,
    pub round: u32,
    pub title: String,
    pub date: String,
    pub home: Team,
    pub away: Team,
    pub thumb: String,
    pub has_highlight: bool,
    pub events: Vec<Event>,
    #[serde(skip)]
    pub highlight_url: String,
}

fn s(v: &Value, k: &str) -> String {
    v.get(k).and_then(Value::as_str).unwrap_or("").to_string()
}

fn team(v: &Value) -> Team {
    Team { name: s(v, "name"), logo: s(v, "logo"), score: v.get("matchScore").and_then(Value::as_i64) }
}

pub async fn fetch_seasons(client: &reqwest::Client, lg: League) -> anyhow::Result<Vec<Season>> {
    let url = format!("https://apigateway.beinsports.com.tr/api/organizations/v3/rewriteid/{}", lg.id);
    let v: Value = client.get(&url).send().await?.error_for_status()?.json().await?;
    let seasons = v["Data"]["seasons"].as_array().cloned().unwrap_or_default();
    Ok(seasons
        .iter()
        .map(|sv| Season {
            id: sv["id"].as_u64().unwrap_or(0),
            name: s(sv, "name"),
            is_current: sv["isCurrent"].as_bool().unwrap_or(false),
            weeks: sv["beinSportsFixtureWeekList"]
                .as_array()
                .map(|ws| {
                    ws.iter()
                        .map(|w| Week {
                            round: w["round"].as_u64().unwrap_or(0) as u32,
                            name: s(w, "weekName"),
                            is_current: w["currentWeekForFixture"].as_bool().unwrap_or(false),
                        })
                        .collect()
                })
                .unwrap_or_default(),
        })
        .collect())
}

pub async fn fetch_week(client: &reqwest::Client, lg: League, season: u64, round: u32) -> anyhow::Result<Vec<Match>> {
    let url = format!(
        "https://beinsports.com.tr/api/highlights/events?sp={}&o={}&s={}&r={}&st=0",
        lg.sport_id, lg.org_id, season, round
    );
    let text = client.get(&url).send().await?.error_for_status()?.text().await?;
    let v: Value = serde_json::from_str(&text).unwrap_or(Value::Null);
    let events = v["Data"]["events"].as_array().cloned().unwrap_or_default();
    let mut out: Vec<Match> = events
        .iter()
        .map(|m| {
            let highlight_url = s(m, "highlightVideoUrl");
            Match {
                id: m["matchId"].as_u64().unwrap_or(0),
                round,
                title: s(m, "highLightTitle"),
                date: s(m, "matchDate"),
                home: team(&m["homeTeam"]),
                away: team(&m["awayTeam"]),
                thumb: s(m, "highlightThumbnail"),
                has_highlight: !highlight_url.is_empty(),
                highlight_url,
                events: m["matchEvents"]
                    .as_array()
                    .map(|es| {
                        es.iter()
                            .map(|e| Event {
                                id: e["id"].as_u64().unwrap_or(0),
                                minute: e["minute"].as_i64().unwrap_or(0),
                                description: s(e, "description"),
                                is_goal: e["type"].as_i64() == Some(0),
                                side: e.get("eventTeamSide").and_then(Value::as_str).map(str::to_string),
                                thumb: s(e, "thumbnail"),
                                has_video: !s(e, "sourceVideoUrl").is_empty(),
                                mp4: s(e, "sourceVideoUrl"),
                            })
                            .collect()
                    })
                    .unwrap_or_default(),
            }
        })
        .collect();
    out.sort_by(|a, b| a.date.cmp(&b.date));
    // beIN lists İspanya La Liga seasons/weeks but never publishes highlight mp4s
    // (`{"Data":{}}` for 2024–2027). Overlay official 2026/2027 highlights instead.
    if out.is_empty() && lg.id == "ispanya-la-liga" {
        return crate::laliga::fetch_week(client, season, round).await;
    }
    Ok(out)
}

/// `?l=&s=&r=` hint on `/video/*`, letting a cold server re-fetch the week that owns a
/// source. All optional: a warm cache needs none of them.
#[derive(Deserialize)]
pub struct WeekQuery {
    pub l: Option<String>,
    pub s: Option<u64>,
    pub r: Option<u32>,
}
