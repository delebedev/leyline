# Deployment

## Player Setup (macOS)

```bash
./deploy/setup.sh on    # patch Arena to connect to leyline.games
./deploy/setup.sh off   # restore Arena to stock
```

**`on`:** writes `services.conf` into MTGA StreamingAssets + creates `NPE_VO.bnk` audio stub.
**`off`:** removes `services.conf`.

No cert trust needed (real LE cert). Override path: `MTGA_PATH="/path/to/MTGA.app" ./deploy/setup.sh on`.
Re-run after Arena updates (Epic overwrites the app bundle).

## Docker Image

3-stage build: Maven (forge JARs) → Gradle (proto + Kotlin + installDist) → JRE runtime.

```bash
just docker-build   # build + push to ghcr.io/delebedev/leyline:latest
just deploy         # docker-build + pull + restart on VPS
```

### In the image

- Application JARs + launch script (`installDist`)
- Card scripts (`forge-gui/res/`, minus images/sounds)
- `playtest.toml` + deck files
- `entrypoint.sh`

### NOT in the image

- **Card database** (222MB SQLite) — volume mount
- Images, sounds, skins, adventure data

## Environment Variables

| Variable | Purpose | Default |
|----------|---------|---------|
| `LEYLINE_CARD_DB` | Card database SQLite path | Auto-detect macOS |
| `LEYLINE_FD_HOST` | FrontDoor `host:port` for doorbell + MatchCreated | `localhost:30010` |
| `LEYLINE_DEBUG` | Enable `MTGA_DEBUG` role (debug menu) | Off |
| `JAVA_OPTS` | JVM flags | `-Xmx384m` |

## Ports

| Port | Protocol | Service |
|------|----------|---------|
| 443 | HTTPS | AccountServer (auth + doorbell), via reverse proxy |
| 30010 | TLS TCP | FrontDoor (direct) |
| 30003 | TLS TCP | MatchDoor (direct) |
| 9443 | HTTPS | AccountServer (internal) |
| 8090 | HTTP | Debug panel (internal) |

## Local Dev

```bash
just dev-setup      # gen certs + patch Arena + macOS defaults
just serve          # start server (local mode)
just dev-teardown   # undo
```

Auto-detects card DB from `~/Library/Application Support/com.wizards.mtga/Downloads/Raw/`.

## JWT Roles

| Role | Effect |
|------|--------|
| `WotC_ACCESS` | Normal game access (always on) |
| `MTGA_FeatureToggle` | Feature toggle editor (always on) |
| `MTGA_DEBUG` | Debug panel — `LEYLINE_DEBUG=true` to enable |

Dev mode auto-seeds account `forge@local` / `forge`. Registration and login with bcrypt password verification.
