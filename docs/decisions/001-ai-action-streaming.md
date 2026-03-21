# ADR-001: Per-Action AI State Streaming via EventBus

> **Note:** This ADR uses the original class name "NexusGamePlayback" (now `GamePlayback`) and "forge-nexus" (now "leyline/matchdoor").

## Status
Accepted

## Context

When the AI opponent takes actions (plays lands, casts spells, attacks), the Arena client needs to animate each action individually. Currently, forge-nexus sends **nothing** during the AI's turn — the client waits silently, then receives one large state dump when priority returns to the human.

Real Arena server sends individual `GameStateMessage` diffs (all `SendHiFi`, no `ActionsAvailableReq`) for each AI action, giving the client a frame-by-frame timeline to animate.

### Current flow (broken)
```
AI turn start → (silence, 2-5s) → GS Full + ActionsAvailableReq
```

### Target flow
```
AI turn start → GS Diff (land) → GS Diff (cast) → GS Diff (resolve)
             → GS Diff (combat) → GS Diff (damage) → GS Full + ActionsAvailableReq
```

## Decision

Use the Forge engine's **Guava EventBus** to subscribe to per-action game events and send individual GRE state diffs during AI turns. This mirrors the proven `WebGamePlayback` pattern in forge-web.

### Why EventBus (Option 1) over alternatives

**Option 2 — Polling in MatchHandler:** Race-prone (same class of bug we fixed in `advanceToMain1`). Misses rapid actions. No natural pacing.

**Option 3 — AI controller bridge:** Requires modifying `PlayerControllerAi` in forge-game (upstream code). More invasive, harder to maintain.

**Option 1 wins because:**
- Forge already fires granular events for every action (`GameEventSpellAbilityCast`, `GameEventLandPlayed`, `GameEventCardChangeZone`, etc.)
- `WebGamePlayback` already subscribes to these events and broadcasts state per AI action — 160 lines, proven in production
- Events fire synchronously on the game thread — sleeping the thread provides natural animation pacing with zero race conditions
- State is frozen during sleep, safe to serialize and diff
- Registration is one line: `game.subscribeToEvents(listener)`

### Architecture

```
Engine thread                          Netty I/O thread
─────────────                          ─────────────────
AI plays land
  ↓
game.fireEvent(GameEventLandPlayed)
  ↓
NexusGamePlayback.visit(ev)
  ├─ snapshot state
  ├─ compute diff → GRE messages
  ├─ queue messages (ConcurrentLinkedQueue)
  ├─ Thread.sleep(300ms)  ← pacing
  └─ return (engine continues)
                                       MatchHandler drains queue
                                         ├─ sendBundledGRE(messages)
                                         └─ update msgId/gsId counters
```

**Queue + handler drain** (not direct channel write) because:
- MatchHandler owns protocol counters (`msgId`, `gsId`)
- Handler already has `sendBundledGRE()` for debug recording + socket write
- Keeps all Netty I/O in the handler's context

### Events to subscribe

| Event | Maps to | Delay |
|-------|---------|-------|
| `GameEventLandPlayed` | GS Diff + PlayLand annotations | 300ms |
| `GameEventSpellAbilityCast` | GS Diff + CastSpell annotations | 400ms |
| `GameEventSpellResolved` | GS Diff + Resolution annotations | 400ms |
| `GameEventTurnPhase` | GS Diff + PhaseOrStepModified | 200ms |
| `GameEventAttackersDeclared` | GS Diff + tapped attackers | 400ms |
| `GameEventBlockersDeclared` | GS Diff + block assignments | 400ms |

### Message shape during AI turns

All messages use `SendHiFi` updateType (opponent perspective). No `ActionsAvailableReq` — the human doesn't have priority during AI actions. Each action produces:
1. GS Diff with annotations (zone transfers, state changes)
2. GS Diff empty marker (turnInfo only) — matches real server's double-diff pattern

### Key constraints

- **Thread safety:** EventBus fires on engine thread. Queue handoff to Netty thread via `ConcurrentLinkedQueue`. Handler polls queue in its existing loop.
- **Only during AI turns:** `isAiActing()` check (same as `WebGamePlayback`) skips events on human turns where bridge blocking provides natural pacing.
- **Pacing:** `Thread.sleep()` on engine thread freezes game state during sleep — identical to `WebGamePlayback` approach. Configurable per-event delays.
- **Counter management:** Queue entries include pre-built GRE messages. Handler assigns `msgId`/`gsId` on drain to keep sequence monotonic.

## Consequences

- AI actions become individually visible to the Arena client
- Client can animate each action with proper timing
- Aligns with real Arena server behavior (individual SendHiFi diffs)
- Removes Gap #7 (AI auto-pass — no more spurious ActionsAvailableReq during AI turns)
- Foundation for combat flow (Gap #11) — attacker/blocker events are in the subscription list
- `MatchHandler.autoPassAndAdvance()` simplifies — AI turn handling becomes "drain queue + wait for human priority" instead of the current polling/timeout loop
