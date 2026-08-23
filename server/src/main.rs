mod bein;
mod video;

use std::{collections::HashSet, sync::Arc, time::Duration};

use axum::{
    extract::{Path, Query, State},
    http::{HeaderMap, StatusCode},
    response::{Json, Response},
    routing::get,
    Router,
};
use bein::{League, Match, Season, WeekQuery};
use moka::future::Cache;
use tokio::{sync::Semaphore, task::JoinSet};
use tower_http::services::{ServeDir, ServeFile};

#[derive(Clone)]
struct App {
    http: reqwest::Client,
    noredirect: reqwest::Client,
    seasons: Cache<String, Arc<Vec<Season>>>,
    weeks: Cache<String, Arc<Vec<Match>>>,
    /// "{league}:{season}" → every match of the season (all weeks merged)
    season_matches: Cache<String, Arc<Vec<Match>>>,
    /// "m:{matchId}" / "e:{eventId}" → raw upstream url (dt-switch or mp4)
    sources: Cache<String, String>,
    /// raw upstream url → final mp4
    resolved: Cache<String, String>,
}

type ApiResult<T> = Result<Json<T>, (StatusCode, String)>;

fn err(e: impl std::fmt::Display) -> (StatusCode, String) {
    tracing::warn!("{e}");
    (StatusCode::BAD_GATEWAY, e.to_string())
}

async fn leagues() -> Json<&'static [League]> {
    Json(bein::LEAGUES)
}

async fn load_seasons(app: &App, lg: League) -> anyhow::Result<Arc<Vec<Season>>> {
    app.seasons
        .try_get_with(lg.id.to_string(), async { bein::fetch_seasons(&app.http, lg).await.map(Arc::new) })
        .await
        .map_err(|e: Arc<anyhow::Error>| anyhow::anyhow!("{e}"))
}

async fn seasons(State(app): State<App>, Path(lid): Path<String>) -> ApiResult<Arc<Vec<Season>>> {
    let lg = bein::league(&lid).ok_or((StatusCode::NOT_FOUND, "unknown league".into()))?;
    load_seasons(&app, lg).await.map(Json).map_err(err)
}

async fn load_week(app: &App, lg: League, season: u64, round: u32) -> anyhow::Result<Arc<Vec<Match>>> {
    let key = format!("{}:{season}:{round}", lg.id);
    let matches = app
        .weeks
        .try_get_with(key, async {
            let ms = bein::fetch_week(&app.http, lg, season, round).await?;
            for m in &ms {
                if !m.highlight_url.is_empty() {
                    app.sources.insert(format!("m:{}", m.id), m.highlight_url.clone()).await;
                }
                for e in m.events.iter().filter(|e| e.has_video) {
                    app.sources.insert(format!("e:{}", e.id), e.mp4.clone()).await;
                }
            }
            Ok::<_, anyhow::Error>(Arc::new(ms))
        })
        .await
        .map_err(|e: Arc<anyhow::Error>| anyhow::anyhow!("{e}"))?;
    Ok(matches)
}

async fn week(
    State(app): State<App>,
    Path((lid, season, round)): Path<(String, u64, u32)>,
) -> ApiResult<Arc<Vec<Match>>> {
    let lg = bein::league(&lid).ok_or((StatusCode::NOT_FOUND, "unknown league".into()))?;
    load_week(&app, lg, season, round).await.map(Json).map_err(err)
}

/// Every match of a season: all weeks fetched concurrently (max 8 in flight), merged,
/// de-duplicated by match id, sorted by date. Goes through `load_week`, so the sources
/// cache is populated for `/video/*` exactly as for a single week.
async fn season_matches(
    State(app): State<App>,
    Path((lid, season)): Path<(String, u64)>,
) -> ApiResult<Arc<Vec<Match>>> {
    let lg = bein::league(&lid).ok_or((StatusCode::NOT_FOUND, "unknown league".into()))?;
    let key = format!("{}:{season}", lg.id);
    app.season_matches
        .try_get_with(key, async {
            let seasons = load_seasons(&app, lg).await?;
            let rounds: Vec<u32> = seasons
                .iter()
                .find(|s| s.id == season)
                .map(|s| s.weeks.iter().map(|w| w.round).collect())
                .ok_or_else(|| anyhow::anyhow!("unknown season {season}"))?;
            let sem = Arc::new(Semaphore::new(8));
            let mut set = JoinSet::new();
            for r in rounds {
                let (app, sem) = (app.clone(), sem.clone());
                set.spawn(async move {
                    let _permit = sem.acquire().await;
                    load_week(&app, lg, season, r).await
                });
            }
            let mut all: Vec<Match> = Vec::new();
            while let Some(res) = set.join_next().await {
                all.extend(res??.iter().cloned());
            }
            let mut seen = HashSet::new();
            all.retain(|m| seen.insert(m.id));
            all.sort_by(|a, b| a.date.cmp(&b.date));
            Ok::<_, anyhow::Error>(Arc::new(all))
        })
        .await
        .map(Json)
        .map_err(err)
}

/// `/video/{kind}/{id}?l=&s=&r=` — kind is `m` (match highlight) or `e` (event clip).
/// The query lets a cold server repopulate sources for deep links / refreshes.
async fn video(
    State(app): State<App>,
    Path((kind, id)): Path<(String, u64)>,
    Query(q): Query<WeekQuery>,
    headers: HeaderMap,
) -> Result<Response, StatusCode> {
    let key = format!("{kind}:{id}");
    let src = match app.sources.get(&key).await {
        Some(s) => s,
        None => {
            let lg = bein::league(&q.l).ok_or(StatusCode::NOT_FOUND)?;
            load_week(&app, lg, q.s, q.r).await.map_err(|_| StatusCode::BAD_GATEWAY)?;
            app.sources.get(&key).await.ok_or(StatusCode::NOT_FOUND)?
        }
    };
    let mp4 = app
        .resolved
        .try_get_with(src.clone(), video::resolve(&app.noredirect, &src))
        .await
        .map_err(|e| {
            tracing::warn!("resolve failed: {e}");
            StatusCode::BAD_GATEWAY
        })?;
    video::proxy(&app.http, &mp4, &headers).await
}

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(std::env::var("RUST_LOG").unwrap_or_else(|_| "info,tower_http=warn".into()))
        .init();

    let ua = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/126 Safari/537.36";
    let http = reqwest::Client::builder().user_agent(ua).build().unwrap();
    let noredirect = reqwest::Client::builder()
        .user_agent(ua)
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .unwrap();

    let app = App {
        http,
        noredirect,
        seasons: Cache::builder().time_to_live(Duration::from_secs(3600)).build(),
        weeks: Cache::builder().time_to_live(Duration::from_secs(300)).build(),
        season_matches: Cache::builder().time_to_live(Duration::from_secs(600)).build(),
        sources: Cache::builder().time_to_live(Duration::from_secs(86_400)).build(),
        resolved: Cache::builder().time_to_live(Duration::from_secs(86_400)).build(),
    };

    let dist = std::env::var("WEB_DIST").unwrap_or_else(|_| "../web/dist".into());
    // `fallback`, not `not_found_service`: the latter forces the response to 404, so a
    // client-side route would return the SPA with a 404 status.
    let spa = ServeDir::new(&dist).fallback(ServeFile::new(format!("{dist}/index.html")));

    let router = Router::new()
        .route("/api/leagues", get(leagues))
        .route("/api/leagues/{lid}/seasons", get(seasons))
        .route("/api/leagues/{lid}/seasons/{season}/weeks/{round}", get(week))
        .route("/api/leagues/{lid}/seasons/{season}/matches", get(season_matches))
        .route("/video/{kind}/{id}", get(video))
        .fallback_service(spa)
        .with_state(app);

    let addr = std::env::var("BIND").unwrap_or_else(|_| "127.0.0.1:8080".into());
    tracing::info!("listening on http://{addr}");
    let listener = tokio::net::TcpListener::bind(&addr).await.unwrap();
    axum::serve(listener, router).await.unwrap();
}
