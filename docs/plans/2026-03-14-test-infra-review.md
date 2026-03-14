# Test Infrastructure Review — Matchdoor

**Date:** 2026-03-14

## Current State

| Tier | Time | Tests | Parallelism |
|------|------|-------|-------------|
| Unit | ~8s | 6 classes | sequential |
| Conformance | ~56s | ~21 classes | **sequential** |
| Integration | ~396s | ~18 classes | 4 forks |
| **testGate** | **64s** | Unit+Conf | sequential |

13 disabled tests (`xtest`), 2 tests with `validating=false`.

---

## Challenges

### 1. Setup cost dominates test time

73% of testGate in 3 classes (AiFirstTurnShape 25s, AnnotationOrdering 11s, InstanceIdRealloc 10s). They use `connectAndKeep()` / `startGameAtMain1()` (0.7–3s) when many assertions only need board state (`startWithBoard` — 0.01s).

Tier mismatch: ConformanceTag tests doing full game loops; heavy setup for lightweight assertions.

### 2. Phase overshoot / non-determinism

\#1 flakiness pattern. `passPriority()` and `autoPassAndAdvance()` skip multiple phases/turns unpredictably:
- Combat tests can't reliably stop at DeclareAttackers/DeclareBlockers (6 disabled)
- AI multi-turn playback stalls or overshoots (2 disabled)
- Turn assertions use `>=` instead of `==`

Root cause: AutoPassEngine + priority loop has no "stop at exactly this phase" primitive.

### 3. AI-turn zone transfer validation gap

3 disabled (DiscardHandSizeTest) + 1 (MatchFlowHarnessTest). `ValidatingMessageSink` throws on AI-turn ZoneTransfer — instanceIds not yet in object map when annotation emitted. Pre-existing; invariant checker too strict for AI turns.

### 4. Conformance tests run sequentially

`testConformance` has no `maxParallelForks`. 3 heavy suites dominate — parallelism without rebalancing barely helps. Need both.

### 5. Two harnesses, overlapping concerns

`ConformanceTestBase` and `MatchFlowHarness` serve different tiers but share concepts. New test authors must choose; split isn't always obvious. Some tests use both.

### 6. Puzzle mode gaps

Puzzle setup would fix many disabled tests (deterministic, no mulligan, fast), but:
- `ScriptedPlayerController.declareAttackers()` doesn't fire in puzzle mode
- AutoPassEngine skips AI combat without emitting DeclareBlockersReq
- Can't test AI-initiated combat (the main flaky area)

---

## Proposed Directions

### A. Phase-precise control primitive ← BUILDING THIS

`stopAtPhase(Phase)` or `advanceToExactly(phase, turn)` — pauses engine thread at a specific phase boundary. Fixes overshoot, enables deterministic combat tests, re-enables 6+ disabled tests. See design doc: `2026-03-14-phase-precise-control.md`.

### B. Promote `startWithBoard` as default

Audit every test using `startGameAtMain1()`/`connectAndKeep()`. If it doesn't need the game loop, downgrade. Expected: cut testGate 30–40%.

### C. Parallelize testConformance

Add `maxParallelForks = 4`, split heavy suites so work distributes evenly.

### D. Fix ValidatingMessageSink for AI turns

Relax invariant for AI-turn zone transfers or fix pipeline to register instanceIds before emitting annotations. Re-enables 4 tests.

### E. Fix puzzle-mode AI combat

Make ScriptedPlayerController work in puzzle mode. Re-enables 3 blocker tests.

### F. Unify harness API

Single entry point: `TestGame.board { ... }`, `TestGame.puzzle(...)`, `TestGame.fullGame(seed=42)`. Consistent helpers regardless of tier. Sugar, not restructuring.
