# Phase-Precise Test Control

**Date:** 2026-03-14
**Status:** Design
**Parent:** [test-infra-review](2026-03-14-test-infra-review.md)

## Problem

Tests can't reliably reach a specific game phase. Two root causes:

### 1. AutoPassEngine greedy loop

`MatchFlowHarness.passPriority()` → `MatchSession.onPerformAction(Pass)` → `AutoPassEngine.autoPassAndAdvance()` loops up to 50 times, auto-passing every phase where only Pass is available. One call can skip entire turns.

```
// Expected: turn 1 → turn 1 next phase
// Actual:   turn 1 → turn 3 (skipped everything with no actions)
```

6 disabled combat tests, 2 disabled AI-turn tests stem from this.

### 2. No generalized phase predicate

`advanceToMain1()` exists (bridge-level, one pass at a time, race-free). But there's no `advanceTo(phase)` — every test that needs a different target phase reimplements its own polling loop.

## Design

### Layer 1: Generalized `advanceTo` (TestHelpers.kt)

Bridge-level helper. Submits one `PassPriority` at a time directly to `GameActionBridge`, checks phase after each. Bypasses `AutoPassEngine` entirely — no overshoot possible.

```kotlin
/**
 * Advance engine to a phase matching [predicate] by submitting one
 * PassPriority at a time via the bridge. No AutoPassEngine involvement —
 * each pass is a single engine step.
 *
 * Returns the PendingAction at the target phase (engine is blocked).
 */
fun advanceTo(
    b: GameBridge,
    maxPasses: Int = 50,
    timeoutMs: Long = 15_000,
    predicate: (phase: String, turn: Int) -> Boolean,
): GameActionBridge.PendingAction

// Rewrite advanceToMain1 as thin wrapper:
fun advanceToMain1(b: GameBridge, maxPasses: Int = 20) =
    advanceTo(b, maxPasses) { phase, _ -> phase == "MAIN1" }
```

**How it works:**
1. `awaitFreshPending(b, lastId)` — wait for engine to block at next priority stop
2. Check `predicate(pending.state.phase, game.phaseHandler.turn)`
3. If match → return pending (engine stays blocked, test has control)
4. If no match → `submitAction(PassPriority)`, loop

**Why bridge-level is safe for combat phases:** When the engine calls `WebPlayerController.declareAttackers()`, it posts a pending action with `phase="COMBAT_DECLARE_ATTACKERS"`. Submitting `PassPriority` there = "declare no attackers" (the `when` branch is a no-op). Same for `declareBlockers`. So `advanceTo` naturally passes through combat without hanging.

### Layer 2: Convenience helpers (TestHelpers.kt)

```kotlin
fun advanceToPhase(b: GameBridge, phase: String, turn: Int? = null) =
    advanceTo(b) { p, t -> p == phase && (turn == null || t == turn) }

fun advanceToCombat(b: GameBridge, turn: Int? = null) =
    advanceToPhase(b, "COMBAT_DECLARE_ATTACKERS", turn)

fun advanceToMain2(b: GameBridge, turn: Int? = null) =
    advanceToPhase(b, "MAIN2", turn)
```

### Layer 3: MatchFlowHarness integration

Add bridge-level advancement methods to the harness:

```kotlin
/** Advance to phase via bridge (one pass at a time, no AutoPassEngine). */
fun advanceToPhase(phase: String, turn: Int? = null): PendingAction =
    advanceToPhase(bridge, phase, turn)

/** Advance to Main1 via bridge. */
fun advanceToMain1(): PendingAction =
    advanceToMain1(bridge)

/** Advance to combat via bridge. */
fun advanceToCombat(turn: Int? = null): PendingAction =
    advanceToCombat(bridge, turn)
```

These coexist with `passPriority()` / `passUntilTurn()`. Tests that need precise phase targeting use `advanceToPhase()`. Tests that want production auto-pass behavior keep using `passPriority()`.

### Layer 4: PendingActionState.turn

Add `turn: Int` to `PendingActionState` — the engine already knows the turn when it blocks. Makes the predicate check race-free (no need to read live `game.phaseHandler.turn`).

```kotlin
data class PendingActionState(
    val phase: String,
    val turn: Int,           // NEW
    val activePlayerId: Int,
    val priorityPlayerId: Int,
)
```

Set it in `WebPlayerController.chooseSpellAbilityToPlay()`, `declareAttackers()`, `declareBlockers()`:
```kotlin
val state = PendingActionState(
    phase = handler.phase?.name ?: "UNKNOWN",
    turn = handler.turn,     // NEW
    activePlayerId = ...,
    priorityPlayerId = ...,
)
```

## Phase reachability

`advanceTo` only sees phases where the engine actually blocks (posts a pending action). Two engine-side gates can skip phases silently:

| Gate | What it skips | Impact |
|------|--------------|--------|
| **Smart phase skip** | Own-turn phases with no playable non-mana actions | MAIN1/MAIN2 with lands/creatures: fine. UPKEEP/DRAW: skipped. |
| **PhaseStopProfile** | Own-turn phases not in the profile | HUMAN_DEFAULTS has MAIN1, COMBAT_ATTACKERS, COMBAT_BLOCKERS, MAIN2 |

For the 6 disabled combat tests, target phases are COMBAT_DECLARE_ATTACKERS / COMBAT_DECLARE_BLOCKERS — both in HUMAN_DEFAULTS and have playable combat actions. No reachability issue.

If a test ever needs an exotic phase (UPKEEP, DRAW, COMBAT_BEGIN), it can add it to the PhaseStopProfile before advancing:
```kotlin
bridge.phaseStopProfile!!.setEnabled(humanId, PhaseType.UPKEEP, true)
advanceToPhase(bridge, "UPKEEP")
```

## What this enables

| Disabled test | Current issue | Fix |
|---------------|--------------|-----|
| CombatFlowTest: human declares single attacker | SEND_STATE overshoot (#18) | `advanceToCombat()` + `declareAttackers()` |
| CombatFlowTest: human declares multiple attackers | Same | Same |
| BlockerDeclarationTest: human blocks AI attacker | Flaky multi-turn AI setup | Puzzle + `advanceToCombat()` |
| BlockerDeclarationTest: human declines blocking | Same | Same |
| BlockerDeclarationTest: trade produces creature deaths | Same | Same |
| GameEndTest: lethal damage | 120s multi-turn timeout | Puzzle + `advanceTo` precise loop |

## Non-goals

- **Modifying AutoPassEngine** — production auto-pass logic stays as-is. This is a test-only facility.
- **Replacing passPriority()** — both patterns coexist. `passPriority()` tests production auto-pass; `advanceTo()` is for deterministic setup.
- **Making smartPhaseSkip mutable** — not needed for the 6 target tests. Can revisit if exotic phases needed.

## Implementation order

1. Add `turn` to `PendingActionState` (3 call sites in WebPlayerController)
2. Implement `advanceTo` + convenience helpers in TestHelpers.kt
3. Add harness methods to MatchFlowHarness
4. Rewrite `advanceToMain1` as wrapper (keep signature, change body)
5. Re-enable disabled combat tests using new helpers
6. Run testGate + testIntegration, verify no regressions
