//! Remux an official LALIGA YouTube highlight into a local H.264/AAC MP4.
//! The web player only ever sees `/video/m/{id}` — never youtube.com.

use std::{
    collections::HashMap,
    path::{Path, PathBuf},
    sync::Arc,
    time::Duration,
};

use tokio::sync::Mutex;

/// `yt:` + 11-char id, as stored on La Liga matches.
pub fn video_id(url: &str) -> Option<&str> {
    let id = url.strip_prefix("yt:")?;
    valid_id(id).then_some(id)
}

pub fn valid_id(id: &str) -> bool {
    id.len() == 11 && id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
}

fn cache_dir() -> PathBuf {
    PathBuf::from(std::env::var("BEINV_VIDEO_CACHE").unwrap_or_else(|_| "/tmp/beinv-yt".into()))
}

static JOBS: Mutex<Option<HashMap<String, Arc<Mutex<()>>>>> = Mutex::const_new(None);

async fn job_lock(id: &str) -> Arc<Mutex<()>> {
    let mut g = JOBS.lock().await;
    let map = g.get_or_insert_with(HashMap::new);
    map.entry(id.to_string()).or_insert_with(|| Arc::new(Mutex::new(()))).clone()
}

/// Download + ffmpeg-merge to `{cache}/{id}.hq.mp4`. Fast remux (`-c copy`); no re-encode.
pub async fn ensure_mp4(id: &str) -> anyhow::Result<PathBuf> {
    anyhow::ensure!(valid_id(id), "bad youtube id");
    let dir = cache_dir();
    tokio::fs::create_dir_all(&dir).await?;
    // `.hq.mp4` so leftover 360p `{id}.mp4` files from the first pipeline are not reused.
    let dest = dir.join(format!("{id}.hq.mp4"));
    if dest.is_file() {
        if let Ok(m) = dest.metadata() {
            if m.len() > 8_000 {
                return Ok(dest);
            }
        }
    }
    let lock = job_lock(id).await;
    let _g = lock.lock().await;
    if dest.is_file() {
        if let Ok(m) = dest.metadata() {
            if m.len() > 8_000 {
                return Ok(dest);
            }
        }
    }
    let tmp = dir.join(format!("{id}.part.mp4"));
    let _ = tokio::fs::remove_file(&tmp).await;
    let url = format!("https://www.youtube.com/watch?v={id}");
    // Highest H.264 + AAC the source publishes (typically 1080p50 + stereo AAC).
    // Fall back to progressive itag 18 so a flaky DASH/HLS fetch still plays.
    tracing::info!("remux youtube {id} (best avc1+aac)");
    if !ytdlp(&tmp, &url, true, Duration::from_secs(360)).await {
        tracing::warn!("hq remux failed for {id}, falling back to 360p");
        let _ = tokio::fs::remove_file(&tmp).await;
        if !ytdlp(&tmp, &url, false, Duration::from_secs(180)).await {
            let _ = tokio::fs::remove_file(&tmp).await;
            anyhow::bail!("yt-dlp failed for {id}");
        }
    }
    // yt-dlp may write exactly `-o` or `-o`.mp4 depending on merge
    let src = if tmp.is_file() {
        tmp.clone()
    } else {
        let alt = PathBuf::from(format!("{}.mp4", tmp.display()));
        if alt.is_file() {
            alt
        } else {
            anyhow::bail!("yt-dlp produced no file for {id}");
        }
    };
    let remuxed = dir.join(format!("{id}.fast.mp4"));
    let ff = tokio::process::Command::new("ffmpeg")
        .args([
            "-y",
            "-loglevel",
            "error",
            "-i",
            src.to_str().unwrap(),
            "-c",
            "copy",
            "-movflags",
            "+faststart",
            remuxed.to_str().unwrap(),
        ])
        .kill_on_drop(true)
        .status()
        .await?;
    let _ = tokio::fs::remove_file(&src).await;
    if ff.success() && remuxed.is_file() {
        tokio::fs::rename(&remuxed, &dest).await?;
    } else {
        anyhow::bail!("ffmpeg remux failed for {id}");
    }
    Ok(dest)
}

fn ytdlp_bin() -> PathBuf {
    std::env::var("YTDLP").map(PathBuf::from).unwrap_or_else(|_| PathBuf::from("yt-dlp"))
}

/// `hq`: best H.264 video + AAC audio (1080p50 when the source has it).
/// `!hq`: android progressive 360p, which always downloads.
async fn ytdlp(out: &Path, url: &str, hq: bool, timeout: Duration) -> bool {
    let mut cmd = tokio::process::Command::new(ytdlp_bin());
    cmd.args([
        "--no-update",
        "--no-playlist",
        "--no-warnings",
        "--no-progress",
        "--retries",
        "3",
        "--fragment-retries",
        "3",
        "--concurrent-fragments",
        "8",
        "-f",
        if hq { "bv*[vcodec^=avc1]+ba[ext=m4a]/18/b" } else { "18/b" },
        "--merge-output-format",
        "mp4",
        "-o",
        out.to_str().unwrap(),
    ]);
    if !hq {
        cmd.args(["--extractor-args", "youtube:player_client=android"]);
    }
    cmd.args(["--", url]).kill_on_drop(true);
    match tokio::time::timeout(timeout, cmd.status()).await {
        Ok(Ok(st)) => st.success(),
        Ok(Err(e)) => {
            tracing::warn!("yt-dlp spawn: {e}");
            false
        }
        Err(_) => {
            tracing::warn!("yt-dlp timed out ({timeout:?})");
            false
        }
    }
}

pub fn file_path(s: &str) -> Option<&Path> {
    s.strip_prefix("file:").map(Path::new)
}
