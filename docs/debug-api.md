# Debug Panel & API

Debug server on `:8090` (auto-starts with `just serve`). Panel: `nexus-debug.html`.

## Response format

All list endpoints return `{version:1, data:[...], cursor:N}`. Use `cursor` for pagination (`?since=<cursor>`). Non-list endpoints (`/api/state`, `/api/state-diff`) return bare objects.

## Real-time events

`GET /api/events` — SSE stream. Event types: `message`, `log`, `state`, `priority`. Panel auto-connects; falls back to polling.

## Cross-referencing

Snapshots and priority events include `msgSeq` — the NexusDebugCollector sequence at capture time. Use to correlate between Messages and States tabs.

## State timeline & diffs (`GameStateCollector`)

- `GET /api/game-states` — full timeline of structured snapshots
- `GET /api/state-diff?from=X&to=Y` — zone deltas, object changes, player life changes
- `GET /api/state-diff?last=N` — shortcut: diff from N snapshots back to current
- `GET /api/instance-history?id=N` — zone history for a single instanceId
- `GET /api/priority-events?since=N` — priority trace: auto-pass, combat/target prompts, AI waits

## Protocol messages (`NexusDebugCollector`)

- `GET /api/messages?since=N` — ring buffer of inbound/outbound protocol messages
- `GET /api/state` — current match state (phase, turn, active player)
- `GET /api/id-map` — instanceId ↔ card name cross-reference (auto-cleans on new match)
- `GET /api/logs?since=N&level=DEBUG` — Logback stream

## Recording introspection

- `GET /api/recordings` — discover recording sessions on disk
- `GET /api/recording-summary?id=...` — compact summary for one session
- `GET /api/recording-actions?id=...` — extracted action timeline (supports `?card=`, `?actor=`, `?limit=`)
- `GET /api/recording-compare?left=...&right=...` — action-level comparison between sessions
