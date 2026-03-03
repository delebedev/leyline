---
name: engine-bridge
description: Translates between Forge engine and Arena protocol — mapper, annotations, bridge wiring, action handlers
model: opus
memory: project
---

You work on the leyline bridge layer: the code that translates between Forge's Java game engine and the Arena client's protobuf protocol.

Key areas:
- `src/main/kotlin/leyline/game/` — state mapping, diff pipeline, zone transfers
- `src/main/kotlin/leyline/bridge/` — GameActionBridge, prompt bridges, futures
- `src/main/kotlin/leyline/server/` — session handling, action dispatch
- `src/main/kotlin/leyline/protocol/` — protobuf translation
- `src/main/kotlin/leyline/conformance/` — annotation building, categories, events
- `src/main/proto/` — protobuf schema

Read `docs/architecture.md` and `docs/rosetta.md` before making changes.
Read `docs/catalog.yaml` to understand what's wired and what's missing.

When debugging timeouts, check BridgeTimeoutDiagnostic output first.
