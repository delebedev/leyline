# 01 — Match Lifecycle Object

## Goal

Extract a `Match` abstraction from `GameBridge` that owns the full lifecycle of a single game: engine thread, bridges, event collector, playback, phase stops, and cleanup. State machine: `WAITING -> RUNNING -> FINISHED`. Today's 1vAI flow auto-transitions `WAITING -> RUNNING` on first connect — zero behavior change.

Clean separation enables:
- N concurrent matches (each Match owns its resources, no cross-contamination)
- Deterministic `close()` (engine thread, futures, EventBus subscriptions — all torn down in one place)
- Future PvP: `WAITING` state holds match open until both seats connect

## Current State

**GameBridge** is a god object. It:
- Creates and owns the Forge `Game` engine instance
- Holds `GameActionBridge`, `InteractivePromptBridge`, `MulliganBridge` (all seat-1 only)
- Owns `InstanceIdRegistry`, `LimboTracker`, `DiffSnapshotter` (per-seat state masquerading as per-match)
- Manages `GameEventCollector` and `GamePlayback` (EventBus subscribers)
- Owns `PhaseStopProfile`
- Provides `start()`, `awaitPriority()`, `shutdown()` — lifecycle verbs mixed with protocol helpers
- Has `MessageCounter` shared between engine thread and MatchSession

**MatchRegistry** already maps `matchId -> GameBridge` and `matchId -> (seatId -> MatchSession)`. It has `evictStale()` for cleanup — but eviction is ad-hoc (called on next ConnectReq, not on game end).

**MatchHandler.processGREMessage** (ConnectReq branch) does the actual creation:
```kotlin
val bridge = registry.getOrCreateBridge(matchId) {
    GameBridge(...).also { it.start(...) }
}
```

**Resource leak today:** `GameBridge.shutdown()` nulls out fields but doesn't unsubscribe EventBus listeners or interrupt the engine thread cleanly. Stale bridges accumulate until the next `evictStale()` call.

## Target Design

### Match class

New `Match` (package `leyline.game`) wraps everything `GameBridge` currently owns, plus lifecycle state:

```
Match
  state: MatchState (WAITING | RUNNING | FINISHED)
  matchId: String
  game: Game
  bridge: GameBridge   (per-seat state moves out; see plan 02)
  loopController: GameLoopController
  eventCollector: GameEventCollector
  playback: GamePlayback
  close()              (idempotent, transitions to FINISHED)
```

**State transitions:**
- `WAITING` — Match created, engine not started. For 1vAI: never visible (auto-transition). For PvP: holds until both seats register.
- `RUNNING` — `startEngine()` called, game loop thread alive, bridges accepting input.
- `FINISHED` — game over or explicit close. All resources released. Terminal state.

**close() contract:**
1. Transition state to `FINISHED`
2. `loopController.shutdown()` — interrupts engine thread
3. Unsubscribe `eventCollector` and `playback` from EventBus
4. Complete any pending `CompletableFuture`s with cancellation (unblocks stuck bridge waits)
5. Fire `onStateChanged` listener (registry observes this to remove the entry)
6. Idempotent — second call is no-op

Match does **not** reference MatchRegistry — dependency flows one way (registry -> match). Match exposes a state-change listener; registry subscribes on creation and removes the entry when it sees `FINISHED`.

### MatchRegistry changes

- `getOrCreateMatch(matchId, factory)` replaces `getOrCreateBridge`. Registry subscribes its cleanup listener to the new Match before returning it.
- `evictStale()` calls `match.close()` instead of `bridge.shutdown()` — listener handles registry removal
- Add post-finish hook for secondary cleanup (recorder flush, debug collector clear)

### GameBridge shrinks

GameBridge retains per-seat protocol state (ID registries, diff snapshotting, annotation counters, persistent annotations). Match owns the engine + loop + global resources. GameBridge becomes a "seat view" of the match.

This split is formalized in plan 02 (per-seat bridge refactor). For this plan, GameBridge stays as-is internally but is **owned by** Match instead of by MatchRegistry directly.

## Migration Path

**Phase 1: Wrap.** ✅ Done (`2dc0dc3`). `Match` wraps `GameBridge`, `MatchRegistry` stores `Match`, `MatchHandler`/`PuzzleHandler` create `Match`. Zero behavior change.

**Phase 2: Lifecycle state.** Add `MatchState` enum and `close()`. Wire `close()` into game-over path (`MatchSession.sendGameOver`) and disconnect handler (`MatchHandler.channelInactive`). Replace `evictStale()` ad-hoc cleanup with deterministic `close()` calls.

**Phase 3: Resource ownership.** Move `GameLoopController`, `GameEventCollector`, `GamePlayback` ownership from `GameBridge` to `Match`. GameBridge.shutdown() becomes just clearing per-seat state; Match.close() handles the heavy lifting.

Each phase is a single commit, independently shippable, no behavior changes until phase 3 (where cleanup becomes deterministic).

## What Doesn't Change

- **MatchSession** — still dispatches game actions, still locks on `sessionLock`, still builds bundles via `BundleBuilder`
- **MatchHandler** — still handles pre-mulligan handshake, still owns Netty channel context
- **FrontDoorService** — untouched (plan 03 covers FD changes)
- **GameBootstrap** — still creates `Game` instances via factory methods
- **Forge engine threading model** — engine thread still blocks on `CompletableFuture.get()`, MatchSession still completes futures from Netty I/O thread
- **MessageCounter sharing** — shared counter between session and engine thread stays the same
- **Familiar mirroring** — seat 1 still mirrors to seat 2 via `MatchRegistry.getPeer()`
