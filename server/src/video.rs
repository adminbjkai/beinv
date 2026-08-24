//! Resolve dt-switch redirect → final mp4, remux YouTube highlights to a local mp4,
//! and proxy byte ranges to the browser.

use std::io::SeekFrom;

use axum::{
    body::Body,
    http::{header, HeaderMap, HeaderValue, StatusCode},
    response::Response,
};
use tokio::io::{AsyncReadExt, AsyncSeekExt};

use crate::youtube;

/// Follow the single 302 from dt-switch to the Akamai mp4. Direct mp4 URLs pass through.
/// `yt:{id}` is remuxed to a cached local file (`file:/path`).
pub async fn resolve(noredirect: &reqwest::Client, url: &str) -> anyhow::Result<String> {
    if let Some(id) = youtube::video_id(url) {
        let path = youtube::ensure_mp4(id).await?;
        return Ok(format!("file:{}", path.display()));
    }
    if url.contains(".mp4") {
        return Ok(url.to_string());
    }
    let resp = noredirect.get(url).send().await?;
    let loc = resp
        .headers()
        .get(header::LOCATION)
        .and_then(|h| h.to_str().ok())
        .ok_or_else(|| anyhow::anyhow!("no redirect from {url} (status {})", resp.status()))?;
    Ok(loc.to_string())
}

/// Parse `bytes=start-end` / `bytes=start-` into an inclusive (start, end) within `len`.
fn parse_range(h: Option<&HeaderValue>, len: u64) -> Option<(u64, u64)> {
    let s = h?.to_str().ok()?;
    let spec = s.strip_prefix("bytes=")?;
    let spec = spec.split(',').next()?.trim();
    let (a, b) = spec.split_once('-')?;
    if a.is_empty() {
        let n: u64 = b.parse().ok()?;
        if n == 0 || n > len {
            return None;
        }
        Some((len - n, len - 1))
    } else {
        let start: u64 = a.parse().ok()?;
        if start >= len {
            return None;
        }
        let end = if b.is_empty() { len - 1 } else { b.parse::<u64>().ok()?.min(len - 1) };
        if end < start {
            return None;
        }
        Some((start, end))
    }
}

async fn serve_file(path: &str, req_headers: &HeaderMap) -> Result<Response, StatusCode> {
    let meta = tokio::fs::metadata(path).await.map_err(|_| StatusCode::NOT_FOUND)?;
    let len = meta.len();
    let range = parse_range(req_headers.get(header::RANGE), len);
    let (status, start, end) = match range {
        Some((s, e)) => (StatusCode::PARTIAL_CONTENT, s, e),
        None => (StatusCode::OK, 0, len.saturating_sub(1)),
    };
    let count = end.saturating_sub(start) + 1;
    let mut file = tokio::fs::File::open(path).await.map_err(|_| StatusCode::NOT_FOUND)?;
    if start > 0 {
        file.seek(SeekFrom::Start(start)).await.map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    }
    let mut buf = vec![0u8; count as usize];
    file.read_exact(&mut buf).await.map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    let mut builder = Response::builder()
        .status(status)
        .header(header::CONTENT_TYPE, "video/mp4")
        .header(header::ACCEPT_RANGES, "bytes")
        .header(header::CONTENT_LENGTH, count)
        .header(header::CACHE_CONTROL, "public, max-age=86400");
    if range.is_some() {
        builder = builder.header(header::CONTENT_RANGE, format!("bytes {start}-{end}/{len}"));
    }
    builder.body(Body::from(buf)).map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)
}

/// Forward the client's Range header to Akamai and stream the answer back unchanged.
/// Local remuxed files (`file:`) are served with the same Range contract.
pub async fn proxy(client: &reqwest::Client, mp4: &str, req_headers: &HeaderMap) -> Result<Response, StatusCode> {
    if let Some(path) = youtube::file_path(mp4) {
        return serve_file(&path.to_string_lossy(), req_headers).await;
    }
    let mut up = client.get(mp4);
    if let Some(r) = req_headers.get(header::RANGE) {
        up = up.header(header::RANGE, r);
    }
    let resp = up.send().await.map_err(|e| {
        tracing::warn!("upstream error: {e}");
        StatusCode::BAD_GATEWAY
    })?;

    let status = StatusCode::from_u16(resp.status().as_u16()).unwrap_or(StatusCode::BAD_GATEWAY);
    let mut builder = Response::builder()
        .status(status)
        .header(header::CONTENT_TYPE, "video/mp4")
        .header(header::ACCEPT_RANGES, "bytes")
        .header(header::CACHE_CONTROL, "public, max-age=86400");
    for k in [header::CONTENT_LENGTH, header::CONTENT_RANGE, header::ETAG, header::LAST_MODIFIED] {
        if let Some(v) = resp.headers().get(&k) {
            if let Ok(hv) = HeaderValue::from_bytes(v.as_bytes()) {
                builder = builder.header(k, hv);
            }
        }
    }
    builder.body(Body::from_stream(resp.bytes_stream())).map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)
}
