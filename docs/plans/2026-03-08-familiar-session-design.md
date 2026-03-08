# Design: FamiliarSession extraction, symmetric GamePlayback, CaptureSink tagging

Issue: #81 | Date: 2026-03-08

Three coupled refactors that remove "is this human or AI?" branching and make the codebase PvP-native.

## 1. FamiliarSession extraction

### Session type hierarchy

```
SessionOps (interface — already exists)
├── MatchSession  — full game orchestration (human player, real actions)
└── FamiliarSession — read-only mirror (receives state, ignores actions)
```

**FamiliarSession** (~30 lines):
- Constructor: `(seatId, matchId, sink: MessageSink, counter: MessageCounter)`
- `sendBundledGRE` → `sink.send(messages)` (no debug sink, no recorder, no mirror)
- All action methods → no-op (inherited default impls from SessionOps)
- `sendGameOver`, `sendRealGameState`, `sendBundle`, `traceEvent`, `paceDelay` → no-op

### SessionOps expansion

Add action dispatch methods with default no-op implementations:

```kotlin
interface SessionOps {
    // existing: sendBundledGRE, sendRealGameState, sendBundle, sendGameOver, ...

    // action dispatch — default no-op, MatchSession overrides
    fun onPerformAction(greMsg: ClientToGREMessage) {}
    fun onDeclareAttackers(greMsg: ClientToGREMessage) {}
    fun onDeclareBlockers(greMsg: ClientToGREMessage) {}
    fun onSelectTargets(greMsg: ClientToGREMessage) {}
    fun onSelectN(greMsg: ClientToGREMessage) {}
    fun onGroupResp(greMsg: ClientToGREMessage) {}
    fun onCancelAction(greMsg: ClientToGREMessage) {}
    fun onConcede() {}
    fun onSettings(greMsg: ClientToGREMessage) {}
    fun onMulliganKeep() {}
    fun onPuzzleStart() {}
}
```

### MatchHandler changes

Single decision point in `handleMatchDoorConnect`:

```kotlin
val session: SessionOps = if (isFamiliar) {
    FamiliarSession(seatId, matchId, sink, counter)
} else {
    MatchSession(seatId, matchId, sink, registry, ...)
}
```

`processGREMessage` becomes unconditional — all 8 `if (!isFamiliar)` guards removed. MatchHandler drops the `isFamiliar` field entirely.

**SubmitAttackersReq / SubmitBlockersReq:** FamiliarSession no-ops handle the client race condition where Familiar sends these. Comment noting PvP may want cross-seat routing in the future.

### MatchRegistry generalization

Session map widens from `MatchSession` to `SessionOps`:

```kotlin
private val sessions = ConcurrentHashMap<String, ConcurrentHashMap<Int, SessionOps>>()
```

`getPeer()` returns `SessionOps?`. `activeSession()` (debug API) filters/casts to `MatchSession`.

### mirrorToFamiliar

Type-checks the peer:
- `is FamiliarSession` → mirror (rewrite systemSeatIds, filter Private objects)
- `is MatchSession` → skip (PvP peer builds own state via per-seat playback)

## 2. Symmetric GamePlayback

### Per-seat instances

GameBridge creates one GamePlayback per seat that receives messages:

```kotlin
// GameBridge
val playbacks: Map<SeatId, GamePlayback>  // replaces single `playback`
```

- **1vAI:** single entry for seat 1 (seat 2 is AI, no client)
- **PvP:** two entries, one per seat

### isRemoteActing()

`isAiActing()` generalizes — fires when the current turn player is not this playback's seat:

```kotlin
private fun isRemoteActing(): Boolean {
    val game = bridge.getGame() ?: return false
    val turnPlayer = game.phaseHandler.playerTurn ?: return false
    val myPlayer = bridge.getPlayer(SeatId(seatId)) ?: return false
    return turnPlayer != myPlayer
}
```

Captures AI turns (1vAI) and opponent turns (PvP) uniformly. Same events, same animation delays, same fidelity.

### Draining

Each MatchSession drains its own seat's playback:

```kotlin
// AutoPassEngine.drainPlayback
val playback = bridge.playbacks[SeatId(ops.seatId)] ?: return false
```

### Diff perspective

`BundleBuilder.aiActionDiff` already takes `seatId` for visibility filtering. Renamed to `remoteActionDiff`. Same logic, name reflects generalized use.

### Mirror interaction

- **1vAI:** seat 1 drains playback, sends to client, `mirrorToFamiliar()` copies to FamiliarSession. Unchanged flow.
- **PvP:** each seat drains independently. No mirroring — playback IS the delivery mechanism.

### EventBus lifecycle

Both per-seat GamePlayback instances subscribe to the same Forge EventBus. Each filters via `isRemoteActing()`. Both unsubscribed on `Match.close()` — `GameBridge.shutdown()` iterates `playbacks.values`.

## 3. CaptureSink tagging

### Scope

FD is always seat 1's perspective (one FD connection per client process) — no per-seat splitting needed. Only MD has two connections that need seat tagging.

### Layout

```
capture/
  fd-frames.jsonl           # single FD stream (unchanged)
  frames/                   # FD binary frames (unchanged)
  payloads/                 # FD binary payloads (unchanged)
  seat-1/
    md-frames/              # MD binary frames for seat 1
    md-payloads/
  seat-2/
    md-frames/              # MD binary for seat 2 (Familiar or PvP opponent)
    md-payloads/
  md-frames.jsonl           # combined decoded timeline (unchanged)
```

### Implementation

Add `label: String` to CaptureSink for MD frame ingestion:

```kotlin
class CaptureSink(
    private val fdCollector: FdDebugCollector,
    private val label: String = "default",  // "seat-1", "seat-2"
)
```

MD frame directories resolve via label. FD path untouched.

### JSONL

`FdFrameRecord` gains optional `seatId: Int?` field (for future use). `flushMdFrames` reads from all seat subdirs, writes combined `md-frames.jsonl` at session root. Zero tooling breakage — `just rec-*` commands see the same file.

## Test coverage

### FamiliarSession
- **Unit:** FamiliarSession receives mirrored messages, ignores action calls
- **Unit:** MatchHandler creates correct session type based on clientId
- **Integration:** existing 1vAI MatchFlowHarness tests pass (regression)

### GamePlayback
- **Unit:** `isRemoteActing()` fires for opponent, not for self
- **Unit:** per-seat playback queues diffs independently
- **Integration:** DualSeatHarness — seat A plays land, seat B receives animated diff
- **Regression:** existing 1vAI tests pass with single-entry playback map

### CaptureSink
- **Unit:** labeled sink writes MD frames to correct subdirectory
- **Unit:** two sinks with different labels don't collide on seq/filenames
- **Integration:** combined md-frames.jsonl merges both seats correctly

## Dependency order

```
FamiliarSession extraction → Symmetric GamePlayback → CaptureSink tagging
     (structural)               (behavioral)            (observability)
```

FamiliarSession first: cleans up session type hierarchy that GamePlayback changes touch. CaptureSink is independent but logically last.
