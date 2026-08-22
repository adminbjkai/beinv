//! Resolve dt-switch redirect → final mp4, and proxy byte ranges to the browser.

use axum::{
    body::Body,
    http::{header, HeaderMap, HeaderValue, StatusCode},
    response::Response,
};

/// Follow the single 302 from dt-switch to the Akamai mp4. Direct mp4 URLs pass through.
pub async fn resolve(noredirect: &reqwest::Client, url: &str) -> anyhow::Result<String> {
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

/// Forward the client's Range header to Akamai and stream the answer back unchanged.
pub async fn proxy(client: &reqwest::Client, mp4: &str, req_headers: &HeaderMap) -> Result<Response, StatusCode> {
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
    builder
        .body(Body::from_stream(resp.bytes_stream()))
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)
}
