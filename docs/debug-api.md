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
- `GET /api/id-map` — instanceId cross-reference with status/zone/seat tracking
  - Fields: `instanceId`, `forgeCardId`, `cardName`, `ownerSeatId`, `status` (active/limbo/stale), `forgeZone`, `protoZone`, `protoZoneId`, `grpId`
  - `?active=true` — only current instanceIds (hide retired/limbo ghosts)
  - `?seat=1` or `?seat=2` — filter by owner
  - `?zone=Battlefield` — filter by Forge zone (case-insensitive)
  - `?name=Prince` — filter by card name substring (case-insensitive)
- `GET /api/logs?since=N&level=DEBUG` — Logback stream

## Front Door messages (`FdDebugCollector`)

- `GET /api/fd-messages?since=N` — ring buffer of FD C→S/S→C messages with decoded CmdType, transactionId, JSON payload

Each entry includes `cmdType` (int), `cmdTypeName` (human-readable), `dir` ("C2S"/"S2C"), `envelopeType` ("CMD"/"REQUEST"/"RESPONSE"), `transactionId`, `jsonPayload`.

SSE event type: `fd-message` — emitted on each FD message for real-time updates.

In proxy mode, FD frames are also written to `recordings/<session>/capture/fd-frames.jsonl` (decoded JSONL with CmdType + JSON per frame).

## Recording introspection

- `GET /api/recordings` — discover recording sessions on disk
- `GET /api/recording-summary?id=...` — compact summary for one session
- `GET /api/recording-actions?id=...` — extracted action timeline (supports `?card=`, `?actor=`, `?limit=`)
- `GET /api/recording-messages?id=...` — decoded messages for a session
- `GET /api/recording-compare?left=...&right=...` — action-level comparison between sessions

## Client-side observability (scry)

**scry** (`bin/scry`) parses Player.log for GRE game state, annotations, and client exceptions. It's the client-side counterpart to the debug server.

- `just scry state` — accumulated game state + annotations + errors
- `just scry serve` — HTTP server on :8091 (`/state`, `/errors`, `/health`)
- `just watch-client` — quick snapshot (alias for `scry state --no-cards`)

### Annotation tracking

`scry state` output includes two annotation fields:

- **`annotations`** — annotations from the latest GSM (transient, replaced each diff)
- **`persistent_annotations`** — active persistent annotations across GSMs (Counter, LayeredEffect, etc.), with `diffDeletedPersistentAnnotationIds` removals applied

Each annotation has `types` (prefix-stripped), `affected_ids`, optional `affector_id`, and `details` (KVP flattened to plain dict).

### Comparing server vs client

To check whether annotations reach the wire:

```bash
# What the server sent (debug API)
curl -s http://localhost:8090/api/state | python3 -m json.tool

# What the client received (Player.log)
just scry state --no-cards | python3 -m json.tool
```

If the server shows annotations but scry doesn't, the gap is in the annotation → GRE message assembly path (StateMapper/BundleBuilder).

## State injection

- `POST /api/inject-full` — rebuild current engine state as Full GSM, send to client. No request body needed.
- `POST /api/inject-puzzle` — hot-swap the running game to a new puzzle. Tears down engine, loads `.pzl`, restarts, injects Full GSM + actions.
  - Body: raw `.pzl` content (text/plain)
  - Query param: `?file=<name>` — loads from `matchdoor/src/test/resources/puzzles/<name>.pzl`
  - Sequential injection works — no reconnect needed between puzzles

## Recording analysis (post-game)

- `GET /api/recording-analysis?id=...` — post-game analysis (mechanics, invariants, gsId chain). Auto-generates if missing.
- `GET /api/recording-events?id=...&stream=proto&since=N` — paired event stream from events.jsonl with filtering
- `GET /api/recording-invariants?id=...` — invariant violations shortcut (from analysis.json)
- `GET /api/recording-mechanics` — cross-session mechanic manifest (all mechanics seen across all sessions)
