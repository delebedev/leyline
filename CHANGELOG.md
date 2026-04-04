# Changelog

Domain-grouped. Maintained per-PR — add to `[Unreleased]`, finalized at release.

## [Unreleased]

### Launcher
- Rust/Tauri launcher with structured logging, health checks, and Start/Stop toggle
- Local CA cert generation (rcgen) with OS trust store integration
- Windows support: NSIS installer, Arena path detection, cert store, sidecar startup
- Cross-platform CI matrix (macOS, Windows, Linux)
- Headless `launcher-smoke` test for sidecar + TLS verification

### Engine
- Priority system: typed abstractions, autoPass wiring, opponent stops, configurable decision timer
- ShouldStopEvaluator for deck-based stop profiles
- Combat handler COMBAT_DAMAGE guard
- Safety net: SEND_STATE checks actions before sending to client

### Protocol
- Front Door deck serving from `.txt` files
- AccountServer supports both RSA and ECDSA key loading
- Pure-Java TLS via BouncyCastle (no openssl/keytool subprocesses)

### Infrastructure
- Maven → Gradle migration with Kotest, power-assert, detekt
- TestNG → Kotest FunSpec migration (unit, conformance, integration groups)
- Docker deployment removed — launcher + jlink replaces it
- Python arena/scry tools removed — replaced by `arena-ts` and `scry-ts`
- CI: GitHub Actions gate + integration tests, release pipeline with parallel macOS + Windows builds

### Docs
- Documentation strategy overhaul: `read_when` frontmatter on 45+ docs
- Documentation principles expanded (10 rules)
- Stale plans/specs archived and compressed into `wire-spec-archive.md`
