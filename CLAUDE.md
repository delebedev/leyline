# forge-nexus

MTGA client compat layer — stubs/proxies Arena's Front Door + Match Door so MTGA connects to Forge's engine.

- **Transport:** raw Netty TLS TCP (not HTTP — Arena uses 6-byte framing + protobuf)
- **Depends on:** forge-web (game bridges, bootstrap) — never reverse the dependency
- **Proto:** `src/main/proto/messages.proto` — Arena protobuf schema (from MtgaProto project)
- **Card data:** `CardDb.kt` reads Arena's local SQLite for grpId, types, mana cost
- **Server modes:** `just serve` (hybrid, main dev), `just serve-stub` (offline), `just serve-proxy` (passthrough), `just serve-replay`

## Debug Panel & API

Debug server on `:8090` (auto-starts with `just serve`). Panel: `nexus-debug.html`.

**State timeline & diffs** (`GameStateCollector`): structured snapshots extracted from every outbound `GameStateMessage`, keyed by gsId. Use for debugging state change issues.

- `GET /api/game-states` — full timeline of structured snapshots
- `GET /api/state-diff?from=X&to=Y` — zone deltas, object changes, player life changes between two gsIds
- `GET /api/instance-history?id=N` — zone history for a single instanceId across all snapshots
- `GET /api/priority-events?since=N` — priority trace: auto-pass decisions, combat/target prompts, AI waits

**Protocol messages** (`ArenaDebugCollector`): raw GRE message log with JSON expansion.

- `GET /api/messages?since=N` — ring buffer of inbound/outbound protocol messages
- `GET /api/state` — current match state (phase, turn, active player)
- `GET /api/id-map` — instanceId ↔ card name cross-reference
- `GET /api/logs?since=N&level=DEBUG` — Logback stream
