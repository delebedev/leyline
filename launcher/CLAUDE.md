# launcher

Tauri v2 desktop app — player-facing wrapper around the leyline server. One-click start, Arena config management, restore to stock.

## Stack

- **Backend:** Rust (Tauri v2, std::process for sidecar, reqwest for health check)
- **Frontend:** vanilla TypeScript + Vite + Bun (no framework)
- **Sidecar:** jlink-bundled leyline server from `just bundle` (embedded as Tauri resource)

## Architecture

**Not a formal Tauri sidecar.** The jlink bundle is a directory tree (jre/ + lib/ + bin/leyline), not a single binary. Tauri's `externalBin` expects target-triple-named binaries. Instead: bundle as Tauri resources, Rust spawns `bin/leyline` via `std::process::Command`.

```
launcher/
├── src-tauri/
│   ├── src/main.rs          Tauri setup, command registration, graceful shutdown
│   ├── src/server.rs        Sidecar lifecycle — spawn, health poll, kill
│   ├── src/arena.rs         MTGA detection, config deploy/restore, launch
│   └── tauri.conf.json      Window config, resource bundling, icon
├── src/
│   ├── index.html           App shell
│   ├── main.ts              All frontend logic — state machine, Tauri invoke calls
│   └── style.css            Dark theme, teal accent
└── resources/
    ├── services.conf         Localhost server config (deployed to Arena)
    └── NPE_VO.bnk           Stub audio bank (56 bytes)
```

## Key modules

**server.rs** — `ServerState` enum (`Stopped | Starting | Running | Error`). `start_server` spawns the sidecar, polls `http://127.0.0.1:8091/health` every 500ms. State changes emitted as Tauri events. `stop_server` kills the child process. App exit kills sidecar automatically.

**arena.rs** — `detect_arena` scans known macOS paths (Epic/Steam), returns path + storefront source. `deploy_config` copies services.conf + NPE_VO.bnk, sets `CheckSC=0`. `restore_arena` reverses everything. Arena path persisted in Tauri app data dir.

**main.ts** — No framework. DOM manipulation, `invoke()` for Tauri commands, `listen()` for server state events. Play button runs: deploy_config → start_server → wait for healthy.

## Dev workflow

```bash
# Frontend hot-reload + Rust backend (full rebuild)
cd launcher && bun tauri dev

# Frontend only (expects `just serve` running separately)
cd launcher && bun run dev

# Production build → .dmg
cd launcher && bun tauri build
```

## Sidecar resolution

1. **Built app:** Tauri resource dir → `leyline/bin/leyline`
2. **Dev fallback:** `../build/bundle/bin/leyline` (from `just bundle`)

If neither exists, Start shows an error. Run `just bundle` before `bun tauri dev` for full e2e.

## Config management

Mirrors `just dev-setup` / `just dev-teardown` from root justfile:

| Action | What it does |
|--------|-------------|
| Deploy | Copy services.conf + NPE_VO.bnk to StreamingAssets, `defaults write CheckSC=0` |
| Restore | Remove services.conf, `defaults delete CheckSC` |

## Adding features

Frontend is intentionally minimal — vanilla TS, no component system. If the UI grows beyond ~3 screens, consider adding Svelte. For now, keep it flat.

Settings that affect the server (format, opponent, auto-pass) will need a config file or CLI flags on the sidecar. Not implemented yet — hardcoded defaults.

## Platform support

macOS arm64 only (dev preview). Cross-platform via CI matrix (Windows/Linux) is planned — jlink cross-builds with target-platform JDKs, Tauri builds on each runner.
