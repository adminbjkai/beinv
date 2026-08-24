# Stage 1: Build Web Frontend
FROM node:22-alpine AS web-builder
WORKDIR /app/web
COPY web/package*.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

# Stage 2: Build Rust Backend
FROM rust:1-slim-bookworm AS server-builder
WORKDIR /app/server
COPY server/Cargo.toml server/Cargo.lock ./
RUN mkdir src && echo "fn main() {}" > src/main.rs
RUN cargo build --release
RUN rm -f target/release/deps/beinv_server* target/release/beinv-server*

COPY server/src ./src
RUN cargo build --release

# Stage 3: Runtime image
FROM debian:bookworm-slim
# ca-certificates: rustls needs a trust store to reach beIN / Akamai / YouTube over TLS.
# curl: used by the healthcheck below.
# ffmpeg + python3 + yt-dlp (latest release): remux La Liga highlights to same-origin
# H.264+AAC MP4 (1080p50 when the source has it). yt-dlp ≥ 2026.08.19 is required for HQ.
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl ffmpeg python3 \
 && curl -fsSL -o /usr/local/bin/yt-dlp https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp \
 && chmod a+rx /usr/local/bin/yt-dlp \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --no-create-home beinv \
 && mkdir -p /tmp/beinv-yt && chown 10001:10001 /tmp/beinv-yt
WORKDIR /app

COPY --from=server-builder /app/server/target/release/beinv-server /app/beinv-server
COPY --from=web-builder /app/web/dist /app/web/dist

ENV BIND=0.0.0.0:8080
ENV WEB_DIST=/app/web/dist
ENV RUST_LOG=info

EXPOSE 8080
USER beinv

# /api/leagues is served from a static table — no upstream call, so this stays cheap.
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8080/api/leagues || exit 1

CMD ["/app/beinv-server"]
