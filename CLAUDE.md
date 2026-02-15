# forge-nexus

MTGA client compat layer — stubs/proxies Arena's Front Door + Match Door so MTGA connects to Forge's engine.

- **Transport:** raw Netty TLS TCP (not HTTP — Arena uses 6-byte framing + protobuf)
- **Depends on:** forge-web (game bridges, bootstrap) — never reverse the dependency
- **Proto:** `src/main/proto/messages.proto` — Arena protobuf schema (from MtgaProto project)
- **Card data:** `CardDb.kt` reads Arena's local SQLite for grpId, types, mana cost
- **Server modes:** `just serve` (hybrid, main dev), `just serve-stub` (offline), `just serve-proxy` (passthrough), `just serve-replay`

## Debug Panel & API

Debug server on `:8090` (auto-starts with `just serve`). Panel: `nexus-debug.html`.

**Response format:** All list endpoints return `{version:1, data:[...], cursor:N}`. Use `cursor` for pagination (`?since=<cursor>`). Non-list endpoints (`/api/state`, `/api/state-diff`) return bare objects.

**Real-time:** `GET /api/events` — SSE stream. Event types: `message`, `log`, `state`, `priority`. Panel auto-connects; falls back to polling.

**Cross-referencing:** Snapshots and priority events include `msgSeq` — the ArenaDebugCollector sequence at capture time. Use to correlate between Messages and States tabs.

**State timeline & diffs** (`GameStateCollector`):

- `GET /api/game-states` — full timeline of structured snapshots
- `GET /api/state-diff?from=X&to=Y` — zone deltas, object changes, player life changes
- `GET /api/state-diff?last=N` — shortcut: diff from N snapshots back to current
- `GET /api/instance-history?id=N` — zone history for a single instanceId
- `GET /api/priority-events?since=N` — priority trace: auto-pass, combat/target prompts, AI waits

**Protocol messages** (`ArenaDebugCollector`):

- `GET /api/messages?since=N` — ring buffer of inbound/outbound protocol messages
- `GET /api/state` — current match state (phase, turn, active player)
- `GET /api/id-map` — instanceId ↔ card name cross-reference (auto-cleans on new match)
- `GET /api/logs?since=N&level=DEBUG` — Logback stream
