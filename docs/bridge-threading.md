---
summary: "Two-thread model (engine vs session), diff snapshot rules, counter monotonicity, and concurrency traps in the Forge bridge."
read_when:
  - "debugging thread-safety or state snapshot issues"
  - "modifying GameBridge, StateMapper, or BundleBuilder"
  - "understanding which thread owns which state"
---
# Engine Heuristics

Patterns and traps for working with the two-thread Forge engine model.

## Two-thread model: "which thread owns this state?"

| Thread | Runs | Blocks on |
|--------|------|-----------|
| **Engine** (game-loop-N) | `PhaseHandler.mainLoopStep()`, game rules | `CompletableFuture.get()` in bridges |
| **Session** (Netty I/O / test main) | `AutoPassEngine`, `CombatHandler`, message sending | `bridge.awaitPriority()` poll loop |

State read on the session thread is a snapshot of a moving target. The engine thread mutates game state, fires EventBus events, and advances counters concurrently.

## Diff snapshot vs last-sent state

Two independent state timelines:
- **Diff snapshot** (`previousState`): last GSM built for diff computation. Advances on every `buildDiffFromGame()` — including engine-thread captures.
- **Last-sent state** (`lastSentTurnInfo`): last `TurnInfo` actually transmitted to the client.

**Rule:** for any decision that depends on "has the client seen X?", use a dedicated client-tracking field. Never reuse the diff-computation snapshot for client awareness.

## Don't snapshot what you haven't sent

Calling `snapshotState(buildFromGame(...))` before the built state is sent advances the diff baseline. The next diff omits objects the client never received.

**Rule:** snapshot exactly once per sent message, only after the diff has been computed from the old baseline.

## Counter monotonicity

`gsIdCounter` and `msgIdCounter` live on two threads. Sync with `max()`, not assignment:

```kotlin
// Wrong: clobbers engine advances
gsIdCounter.set(gsId)

// Right: monotonic
gsIdCounter.updateAndGet { maxOf(it, gsId) }
```

## awaitPriority() before sending prompts

Detecting a phase on the session thread means the engine *entered* it, not that it's *blocked and waiting*. Always `bridge.awaitPriority()` before building outbound messages. Then sync counters from playback before building bundles.

## .ifEmpty{} on user input is a logic bomb

Empty list = "user chose nothing." Not "user didn't provide a list." Never add fallback-to-all on user-selection lists.

## ValidatingMessageSink is the truth

Catches self-referential gsId, chain gaps, missing instanceIds, duplicates. All integration tests should use it via `MatchFlowHarness`. On timeout, check `violations` first — it often has the root cause.

## ConformanceTestBase needs manual seeding

Tests extending `ConformanceTestBase` construct `GameBridge` directly without `MatchSession`. Any state that `MatchSession` normally initializes must be seeded in `startGameAtMain1()`. When adding new client-tracking state, grep for it.

## Debugging order for test failures

1. Read ValidatingMessageSink violations
2. Check thread (engine vs session)
3. Check phase/turn
4. Trace counter flow (gsId + msgId at sync points)
5. Check snapshot timing
6. Check pending messages
7. Run single test: `just test-one TestClassName`
