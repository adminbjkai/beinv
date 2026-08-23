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
# ca-certificates: rustls needs a trust store to reach beIN / Akamai over TLS.
# curl: used by the healthcheck below.
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --no-create-home beinv
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
