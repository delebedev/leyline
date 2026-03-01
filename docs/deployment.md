# Deployment

Two deployment targets: local dev and hosted server.

## Architecture

```
Arena Client (macOS)
  │
  ├─ HTTPS ──► leyline.games:443 ──► Traefik ──► leyline:9443 (MockWAS)
  │                                   (LE cert)     /auth/*, /api/*
  │
  ├─ TLS TCP ──► leyline.games:30010 ──► Netty FrontDoor (LE cert via certs-dumper)
  │
  └─ TLS TCP ──► leyline.games:30003 ──► Netty MatchDoor (LE cert via certs-dumper)
```

- **Traefik** handles HTTPS :443 (existing VPS infra, DNS-01 via Cloudflare)
- **FD/MD** are raw TLS TCP on dedicated ports, bypass Traefik
- **traefik-certs-dumper** extracts LE PEM files from Traefik's `acme.json` into a shared volume
- **Cloudflare DNS:** `leyline.games` → grey-cloud A record → VPS IP
- Asset bundles: client downloads from `assets.mtgarena.wizards.com` (WotC CDN, unchanged)

## Player Setup (macOS)

Download and run `setup.sh`:

```bash
./setup.sh on    # patch Arena to connect to leyline.games
./setup.sh off   # restore Arena to stock
```

**`on` mode:**
1. Writes `services.conf` into `MTGA.app/.../StreamingAssets/` (points client at leyline.games)
2. Ensures `NPE_VO.bnk` audio stub exists (prevents client crash)

**`off` mode:**
1. Removes `services.conf` (Arena reverts to stock server list)

No cert trust needed — real Let's Encrypt cert. No `defaults write` needed.
Override MTGA path: `MTGA_PATH="/path/to/MTGA.app" ./setup.sh on`

### After Arena Updates

Epic Games updates overwrite the app bundle. Re-run `./setup.sh on` after any MTGA update.

## Server Modes

The deployed server runs **stub mode** — fake auth (MockWAS) + Forge game engine.
One game at a time, AI opponent (Sparky).

| Mode | FD | MD | Use case |
|------|----|----|----------|
| `stub` (deployed) | Fake auth | Forge engine | AI matches |
| `hybrid` (local default) | Proxy to real Arena | Forge engine | Dev + real auth |
| `proxy` | Proxy | Proxy | Traffic capture |

## Docker Image

Multi-stage build:

```
Stage 1 (upstream):  Maven — builds forge submodule JARs
Stage 2 (build):     Gradle — compiles leyline (proto + Kotlin), runs installDist
Stage 3 (runtime):   eclipse-temurin:17-jre-jammy — minimal JRE
```

Build locally: `just docker-build`

### What's in the image

- Application JARs + launch script (from Gradle `installDist`)
- Forge game resources: card scripts (`forge-gui/res/`), minus images/sounds (~50MB saved)
- `playtest.toml` + deck files
- JVM flags baked into launch script (Netty reflective access)

### What's NOT in the image

- **Card database** (222MB SQLite) — mounted as volume at runtime
- **TLS certificates** — mounted from `traefik-certs-dumper` output volume
- Images, sounds, skins, adventure data (not needed for headless server)

## Environment Variables

| Variable | Purpose | Default |
|----------|---------|---------|
| `LEYLINE_CERT_PATH` | TLS cert file (PEM) for FD/MD/WAS | Self-signed fallback |
| `LEYLINE_KEY_PATH` | TLS key file (PEM) for FD/MD/WAS | Self-signed fallback |
| `LEYLINE_CARD_DB` | Path to Arena card database SQLite | Auto-detect from `~/Library/...` |
| `LEYLINE_FD_HOST` | FrontDoor `host:port` for doorbell response | `localhost:30010` |
| `JAVA_OPTS` | JVM flags (memory, etc.) | `-Xmx384m` in Docker |

CLI args (`--fd-cert`, `--fd-key`, etc.) take precedence over env vars.

## Docker Compose (VPS)

See `deploy/docker-compose.yml`. Two services:

- **leyline** — the game server. Ports 30010/30003 exposed directly. Port 9443 proxied via Traefik.
- **certs-dumper** — watches Traefik's `acme.json`, extracts PEM certs to shared volume.

### Volumes

| Volume | Purpose |
|--------|---------|
| `certs` | PEM certs extracted by certs-dumper (shared between certs-dumper and leyline) |
| `traefik_data` | External — Traefik's ACME cert storage |
| `./data/carddb/` | Bind mount — card database SQLite |

### Traefik Integration

Leyline joins the `traefik_proxy` network. Traefik labels route `Host(leyline.games)` to MockWAS on port 9443. Traefik issues the LE cert via DNS-01 (Cloudflare).

**Note:** MockWAS uses self-signed TLS internally. Traefik needs `insecureSkipVerify` for this backend. This requires a Traefik `serversTransport` config (see VPS runbook).

## Local Dev Setup

```bash
just dev-setup    # one-time: gen certs + patch Arena + macOS defaults
just serve        # start server (hybrid mode)
```

### What `dev-setup` does

1. `just gen-certs` — generates mitmproxy-CA-signed certs (if missing)
2. Copies localhost `services.conf` into Arena's StreamingAssets
3. Ensures `NPE_VO.bnk` audio stub exists
4. Sets `CheckSC=0` and `HashFilesOnStartup=0` (macOS `defaults write`)

### Undo

```bash
just dev-teardown   # remove services.conf + clear defaults
```

### Card Database

Local dev auto-detects the SQLite DB from `~/Library/Application Support/com.wizards.mtga/Downloads/Raw/`.
Requires Arena to have been launched at least once (downloads card data on first run).

## Building & Deploying

```bash
just docker-build   # build image locally
just docker-push    # push to ghcr.io/delebedev/leyline:latest
just deploy         # build + push + restart on VPS
```

### Updating the Card Database on VPS

After Arena patches with new cards:

```bash
scp ~/Library/Application\ Support/com.wizards.mtga/Downloads/Raw/Raw_CardDatabase_*.mtga \
    denis@vps:/opt/leyline/data/carddb/Raw_CardDatabase.mtga
ssh denis@vps "cd /opt/leyline && docker compose restart leyline"
```

## services.conf

The client reads `services.conf` from its StreamingAssets directory. Two variants:

| Variant | Location | Points to |
|---------|----------|-----------|
| Local dev | `src/main/resources/services.conf` | `localhost` (all ports) |
| Production | `deploy/services.conf` | `leyline.games` (443 for WAS, 30010/30003 for FD/MD) |

### JWT Roles

MockWAS issues JWTs with `alg: none` (no signature). Roles:

| Role | Effect |
|------|--------|
| `WotC_ACCESS` | Normal game access |
| `MTGA_DEBUG` | Debug panel (Alt key in client) |
| `MTGA_FeatureToggle` | Feature toggle editor |

All three are included by default. No account/password verification — any credentials work.

## Ports

| Port | Protocol | Service | Traefik? |
|------|----------|---------|----------|
| 443 | HTTPS | WAS + doorbell | Yes — Traefik terminates TLS |
| 30010 | TLS TCP | FrontDoor | No — direct, UFW open |
| 30003 | TLS TCP | MatchDoor | No — direct, UFW open |
| 8090 | HTTP | Debug panel | No — internal only |
| 9443 | HTTPS | MockWAS (internal) | Backend for Traefik |

## Migration Path

```
Phase 1 (done):  Dev-only, localhost, just serve. Debug menu working.
Phase 2 (done):  just dev-setup, remote Tailscale client, recording/replay.
Phase 3 (now):   Docker image, Hetzner VPS, player setup script.
Phase 4 (next):  Launcher script, auto-detect Arena updates, opt-in debug.
Phase 5 (later): Proxy mode for real-server captures, multi-player.
```
