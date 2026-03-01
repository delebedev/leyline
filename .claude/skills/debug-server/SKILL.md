---
name: debug-server
description: Check leyline debug server logs, game state, protocol messages, and running match status via the :8090 debug API
---

## What I do

Inspect a running leyline server through the debug API on `:8090`. Covers live game state, protocol message history, logs, priority events, state diffs, instance tracking, and recording introspection.

## When to use me

- "check the logs" / "check server logs"
- "what's happening in the game"
- "check running game" / "check match state"
- "why did the client disconnect" / "debug timeout"
- "show me the last few messages"
- "what zone is card X in"
- "trace instanceId N"
- Any time you need to inspect a live or recent leyline session

## Before you start

1. Server must be running (`just serve` from `leyline/`). Debug server auto-starts on `:8090`.
2. **Read `docs/debug-api.md`** for the full endpoint catalog (paths, query params, response shapes, recording endpoints).

## Triage workflow

Escalate through these steps — stop when you find the issue:

1. **Quick health check:** `curl -s http://localhost:8090/api/state | python3 -m json.tool` — is a match active? What phase/turn?
2. **Recent activity:** `curl -s http://localhost:8090/api/messages | python3 -m json.tool` — last protocol messages sent/received
3. **Errors:** `curl -s 'http://localhost:8090/api/logs?level=WARN' | python3 -m json.tool` — warnings and errors
4. **Timeouts:** `curl -s http://localhost:8090/api/priority-events | python3 -m json.tool` — check if engine is blocked waiting for client response (see `BridgeTimeoutDiagnostic` in logs for stack traces)
5. **State inspection:** `curl -s http://localhost:8090/api/game-states | python3 -m json.tool` then `curl -s 'http://localhost:8090/api/state-diff?last=2' | python3 -m json.tool` — what changed in the last state transition
6. **Card tracking:** `curl -s http://localhost:8090/api/id-map | python3 -m json.tool` to resolve instanceId → card name, then `curl -s 'http://localhost:8090/api/instance-history?id=N' | python3 -m json.tool` for zone history

## Client-side crash triage

When the server-side debug API shows correct messages but the client UI is stuck, **always check Player.log for client-side exceptions**. Our debug API only captures server-side state; client crashes (NullReferenceException, ArgumentOutOfRangeException, etc.) are invisible to it.

```bash
# Find the relevant log line (e.g. last game-over message) and read lines after it
grep -n "MatchCompleted\|GameOver\|IntermissionReq" "/Users/denislebedev/Library/Logs/Wizards Of The Coast/MTGA/Player.log" | tail -3
# Then read ~40 lines after that line number:
sed -n '<LINE>,<LINE+40>p' "/Users/denislebedev/Library/Logs/Wizards Of The Coast/MTGA/Player.log"
```

Common client-side crashes and what they mean:
- **NullReferenceException in ProcessRoomState** — missing required fields in MatchGameRoomInfo (e.g. gameRoomConfig)
- **ArgumentOutOfRangeException in CullEventsAfterConcedePostProcess** — indexing empty annotation/object lists in game-over GSMs
- **"Unexpected combination of game type and number"** — missing gameNumber in GameInfo

## Key conventions

- **Pagination:** list endpoints return `{version:1, data:[...], cursor:N}`. Pass `?since=<cursor>` for next page.
- **msgSeq:** snapshots and priority events include `msgSeq` — use to correlate between protocol messages and state snapshots.
- **Client-side logs:** `"/Users/denislebedev/Library/Logs/Wizards Of The Coast/MTGA/Player.log"`. Compare debug API output (what we sent) vs Player.log (what client received) to isolate serialization vs logic issues. See `docs/reading-player-logs.md` for extraction scripts.
