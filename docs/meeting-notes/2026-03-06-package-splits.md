# 2026-03-06 — Module Splits

## Context

- `account` module done (870 LOC, separate Gradle submodule) — clean, works well
- Root module: 28K LOC prod, 16K LOC test, 141 files
- Codebase will ~3x (80-90K LOC) as multiplayer, sealed, draft, direct challenge, social land
- Monolith deployment forever — splits are compile-time isolation + cognitive chunking
- ArchUnit already enforces 7-tier layering; two known violations (`debug↔match` cycle, `frontdoor.wire→debug`)

## Current State

| Package | Files | LOC | Tier | Purpose |
|---|---|---|---|---|
| `game/` + `game/mapper/` | 32 | 6,290 | 1 | Engine wrappers, state mapping, annotations, proto builders |
| `bridge/` | 22 | 5,413 | 0.5 | Forge adapter (PlayerController, cost decisions, bootstrap) |
| `debug/` | 14 | 3,493 | 4 | Live diagnostics, debug HTTP server, session recording |
| `match/` | 11 | 2,647 | 5 | Match orchestration, combat/targeting/mulligan handlers |
| `frontdoor/` | 22 | 2,166 | 2-5 | Front Door protocol, lobby, decks, matchmaking |
| `recording/` | 8 | 1,948 | 2 | Session codecs, timeline analysis |
| `protocol/` | 5 | 1,295 | 2 | Wire framing, FD envelope, handshake messages |
| `conformance/` | 5 | 1,109 | 5 | Structural diff/fingerprint vs real Arena |
| `analysis/` | 4 | 1,000 | 3 | Post-game reports, invariant checking |
| `arena/` | 8 | 717 | 0 | CLI automation (click, OCR, capture) |
| `infra/` | 6 | ~600 | 6 | Netty server, TLS, MessageSink, proxy |
| `config/` | 1 | 167 | 0 | TOML config |
| root | 2 | 332 | 6 | LeylineMain, LeylinePaths |

## `protocol/` Is Not a Module

Initially considered `protocol` as shared foundation for FD + MD. Wrong — it's a grab-bag:

| File | Door | Notes |
|---|---|---|
| `FrameCodec` | Both | 6-byte header framing. The ONE shared primitive. 123 LOC. |
| `FdEnvelope` | FD only | FD's own protobuf envelope (different schema from MD) |
| `CmdType` | FD only | FD command type enum (600+ types) |
| `HandshakeMessages` | MD only | Imports `GameBridge`, `StateMapper`, `ActionMapper` — deep game coupling |
| `ProtoDump` | MD only | Debug/recording concern |

Correct disposition:
- `FrameCodec` → `:app` (one file, both pipelines reference it in LeylineServer)
- `FdEnvelope` + `CmdType` → `:frontdoor`
- `HandshakeMessages` → `:matchdoor` (or stays in root until matchdoor extraction)
- `ProtoDump` → `:matchdoor` or `:tooling`

## Target Module Structure

```
:app              thin composition root (~1,500 LOC, grows slowly)
                  LeylineMain, LeylineServer, CaptureSink, ManagementServer,
                  ProxyHandlers, TlsHelper, DebugSinkAdapter
                  Depends on: :account, :frontdoor, :matchdoor, :tooling

:account          Ktor HTTPS, WAS compat, JWT, registration
                  ~870 LOC. Independent of everything.

:frontdoor        FD protocol + lobby domain
                  FdEnvelope, CmdType, domain/repo/service/wire/handler
                  ~2,200 LOC. Zero coupling to game engine.

:matchdoor        game engine adapter — the big one
                  bridge/*, game/*, game/mapper/*, match/*, config/,
                  protocol/ (FrameCodec, HandshakeMessages, ProtoDump),
                  infra/MessageSink, conformance/StructuralFingerprint+Diff,
                  game/InvariantChecker
                  ~15,000 LOC. Where 60%+ of new code lands.

:tooling          dev diagnostics + recording + analysis + CLI
                  debug/*, recording/*, analysis/*, conformance/CompareMain+etc,
                  arena/*, cli/*, LeylinePaths
                  ~8,200 LOC. Depends on: :matchdoor (api), :frontdoor
```

## Config

Most config stays in `:app` — ports, TLS paths, player DB path.
MatchConfig moved to `:matchdoor` (owns its TOML parsing + config shape).
`:app` loads the TOML file and passes it through.

## What's Challenging

**GameBridge god object.** 690 LOC, implements 6 interfaces (`IdMapping`, `PlayerLookup`,
`ZoneTracking`, `StateSnapshot`, `AnnotationIds`, `EventDrain`), owns 8 subsystems.
`StateMapper.buildFromGame()` takes full `GameBridge`, calls ~15 methods across all
interfaces. Stays internal to `:matchdoor` — not a module boundary problem, but limits
internal package splits.

**Forge coupling is structural.** `WebPlayerController` extends `PlayerControllerHuman`
(30+ overrides, 1,278 LOC). `GameBootstrap` constructs Forge `Match`, `Game`, `Deck`.
Can't put behind interface without wrapping entire Forge engine API. Permanent —
it's the adapter layer's job. Lives entirely in `:matchdoor`.

**`debug↔match` cycle.** RESOLVED. Three interfaces in match/ (`MatchRecorder`,
`MatchDebugSink`, `MatchEventType`) + `DebugSinkAdapter` in root. Zero
`import leyline.debug.*` in match/ code. `:tooling` extraction unblocked.

**Proto pervasive.** 62 import sites across 9 packages. Generated `messages.proto` is
the output format — no anti-corruption layer possible without massive overhead. Every
module touching game state depends on proto. Module splits give organizational isolation,
not API isolation for game pipeline.

**Test infrastructure.** `ConformanceTestBase`, `MatchFlowHarness`, `ValidatingMessageSink`
test the full pipeline (bridge + game + match). Splitting modules needs `testFixtures()`
Gradle plugin or shared test module. Not needed for `:frontdoor` — FD tests are self-contained.

## Build & Test Considerations

**`testGate` dissolves.** With module boundaries, working in `:frontdoor` means running
`:frontdoor:test` (2s). Working in matchdoor means running matchdoor's unit + conformance.
Module boundary gives confidence that changes can't cross-break. CI runs everything.

**Justfile recipes stay unified for cross-cutting commands:**
- `just fmt` → `./gradlew spotlessApply` (aggregates across modules)
- `just test` → `./gradlew test` (all modules)
- Module-specific: raw `./gradlew :frontdoor:test` — no just alias needed

**Per-module Gradle tasks:** each module owns `test`, `spotlessApply`, `detekt`.
Root aggregation tasks fan out automatically.

**ArchUnit:** remove frontdoor rules after extraction — Gradle module boundary is
stronger (import = build failure vs test failure). Test shrinks.

**JaCoCo:** frontdoor classes leave root classpath. Remove FD exclusions. Per-module
reports are fine.

## Gains vs Losses

| Gain | Loss |
|---|---|
| Compile-time boundary enforcement (build failure > test failure) | Build complexity (more build.gradle.kts, api vs impl) |
| Parallel Gradle compilation (matters at 80K+ LOC) | Refactoring friction across module boundaries |
| Cognitive chunking — `:frontdoor` self-contained for new contributor | Cross-module changes touch more files |
| Dependency narrowing — frontdoor doesn't need Forge JARs | Indirection in wiring (module factories vs direct construction) |
| Module-scoped test runs (2s for FD vs 60s gate) | |

## Sequencing

1. **`:frontdoor` extraction** — DONE. Sources + tests + golden data moved to `frontdoor/`.
   FdEnvelope + CmdType moved from `protocol/` into `:frontdoor`. Debug dep broken.
2. **`:matchdoor` extraction** — DONE. 74 source files moved. Proto generation in matchdoor.
   - `debug↔match` cycle broken: `Tap` moved to match/, `MatchRecorder` interface,
     `MatchDebugSink` interface, `MatchEventType` enum — all in match/ package.
     `DebugSinkAdapter` in root bridges real collectors.
   - `DeckCard` dep broken via `CardEntry` in bridge/ — no matchdoor→frontdoor coupling.
   - `ProtoDump` decoupled from `LeylinePaths` via `@Volatile var engineDumpDir`.
   - Root `build.gradle.kts` cleaned: proto plugin removed, forge/protobuf deps
     transitive from matchdoor `api()`, tomlkt removed (only used by MatchConfig).
   - ArchUnit rules trimmed: packages now in matchdoor enforced by Gradle boundary.
   - 4 functions made `public` (were `internal`) for cross-module test access.
3. **`:tooling` extraction** — DONE. 28 prod files moved + LeylinePaths.
   - `LeylinePaths` moved to tooling (8 files imported it, zero leyline deps).
   - `FrameCodec` moved to matchdoor (unblocked DecodeFdCapture in tooling).
   - `DebugSinkAdapter` stays in root (bridges tooling↔matchdoor).
   - Tooling depends on matchdoor (`api`) + frontdoor (`implementation`).
   - ArchUnit test simplified: root only has infra/ + debug/DebugSinkAdapter.
   - 3 test files moved to tooling, 1 (FrameCodecTest) to matchdoor.

**All 4 modules complete.** Root is 7 prod files (~1,500 LOC), 3 test files.
