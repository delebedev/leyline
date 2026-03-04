# Plan: Wire PhaseStopProfile in GameBridge (Nexus)

**Status:** Draft — blocked on PhaseOrStepModified annotation regression  
**Branch:** `fix/combat-and-cleanup-stalls`  
**Recording:** `/tmp/arena-recordings/2026-02-22_10-36-29/engine/`

## Problem

Client stalls at CombatDamage phase. Server sends `ActionsAvailableReq` with
`Cast=1 Pass=1 ActivateMana=1 FloatMana=1` — technically valid priority stop,
but the real Arena client would auto-pass here (CombatDamage is not a default
phase stop). User doesn't respond, bridge times out (120s), engine auto-passes
silently, client never gets updated state.

Root cause: `GameBridge.start()` creates `WebPlayerController` with **no
`phaseStopProfile`** — meaning every phase grants priority to the human. The
real Arena client uses phase stops (Main1, DeclareAttackers, DeclareBlockers,
Main2 for the human player).

## Proposed Fix

Wire `PhaseStopProfile.createDefaults(human.id, ai.id)` in `GameBridge.start()`.
One-line change — adds `phaseStopProfile = phaseStops` to the WebPlayerController
constructor call.

This makes the engine skip non-essential phases (CombatDamage, CombatEnd,
EndOfTurn, Upkeep, Draw, Cleanup) on the engine thread, matching real Arena
behavior.

## Regression: Missing PhaseOrStepModified Annotation

The fix causes `AiFirstTurnShapeTest.phaseTransitionsHaveAnnotations` to fail.

**Symptom:** `gsId=18 phase=Main1 annotations=[]` — the first GSM on the
human's turn has no `PhaseOrStepModified` annotation.

**What the test expects:** Every observed phase/step change in the GSM message
stream has a `PhaseOrStepModified` annotation.

**What happens:** With the profile, the engine skips UPKEEP, DRAW, and stops at
MAIN1. The phase annotation injection in `BundleBuilder.postAction` compares
`prevSnapshot.turnInfo.phase/step` against current. If prevSnapshot already
shows the same phase (MAIN1), no annotation is emitted.

### Why prevSnapshot has the wrong phase

This is the part we don't fully understand yet. Two hypotheses:

**Hypothesis A — drainPlayback snapshot race:**
`AutoPassEngine.drainPlayback()` calls `bridge.snapshotState(StateMapper.buildFromGame(...))`
at line 96 of `AutoPassEngine.kt`. This captures a full game state AFTER AI
actions are drained. If the engine thread has already advanced past the AI's
turn into the human's MAIN1 by the time this snapshot runs, prevSnapshot would
show MAIN1 — same phase as the next `postAction` call, so no annotation.

**Hypothesis B — turn boundary with same phase:**
The AI's turn ends at some phase, engine skips through human's beginning phases,
arrives at MAIN1. The previous snapshot might be from the AI's MAIN1 (same phase
name, different turn). The annotation check only compares phase/step, not turn
number. We tried adding `turnNumber` to the comparison — still failed, so this
hypothesis may be wrong (or prevSnapshot is actually null).

### Needs investigation

- Add temporary logging in `BundleBuilder.postAction` to print `prevSnapshot`
  (phase/step/turn/null) and `gsBase` (phase/step/turn) when `phaseChanged=false`
- Check whether prevSnapshot is null or has matching phase
- If null: find where it gets cleared/not-set on the AI→human turn boundary
- If same phase: determine which code path set prevSnapshot to MAIN1 before
  `postAction` ran

## Key Components & Threading

### Two threads

| Thread | What runs | How it blocks |
|--------|-----------|---------------|
| **Engine thread** | `PhaseHandler.mainGameLoop()` → `chooseSpellAbilityToPlay()` → `WebPlayerController` | Blocks on `GameActionBridge.awaitAction()` (CompletableFuture.get) at priority stops, or `InteractivePromptBridge.requestChoice()` for prompts |
| **Session thread** | `MatchSession.onPerformAction()` → `AutoPassEngine.autoPassAndAdvance()` | Runs when client sends a message (Netty I/O) or MatchFlowHarness calls directly. Synchronized on `sessionLock` |

### Phase skip decision chain (engine thread)

In `WebPlayerController.chooseSpellAbilityToPlay()`:

1. **Auto-pass until end of turn** — `actionBridge.autoPassUntilEndOfTurn` flag
2. **smartPhaseSkip** (line 921-928) — skip if no playable non-mana actions,
   own turn, empty stack, no just-resolved prompt
3. **phaseStopProfile** (line 930-933) — skip if phase not in profile
4. If none skip → `actionBridge.awaitAction()` blocks engine thread

Steps 1-3 return `null` immediately (auto-pass). The engine thread never
blocks, `AutoPassEngine` never runs. This is the fast path.

### State snapshot lifecycle

- `StateMapper.buildDiffFromGame()` calls `bridge.snapshotState(gs)` internally
  → sets `prevSnapshot`
- `AutoPassEngine.drainPlayback()` calls `bridge.snapshotState(buildFromGame())`
  → sets `prevSnapshot`
- `BundleBuilder.postAction()` reads `prevSnapshot` BEFORE calling
  `buildDiffFromGame` (which overwrites it)
- `PhaseOrStepModified` annotation injected when `prevSnapshot.phase/step !=
  gsBase.phase/step`
- Nowhere is `prevSnapshot` explicitly cleared between turns

### AutoPassEngine vs PhaseStopProfile

Independent layers, both skip phases, different threads:

- **PhaseStopProfile**: engine thread, inside `WebPlayerController`. Skips
  before the bridge blocks. Engine keeps running. No messages sent.
- **AutoPassEngine**: session thread, inside `MatchSession`. Runs AFTER engine
  blocks on bridge. Decides what to send to client. Uses
  `BundleBuilder.shouldAutoPass()` (only-Pass check) and combat/prompt handlers.

With profile wired, most non-essential phases never reach AutoPassEngine.

## Files Involved

| File | Role |
|------|------|
| `forge-nexus/src/main/kotlin/forge/nexus/game/GameBridge.kt:176` | WebPlayerController creation — needs `phaseStopProfile` param |
| `forge-web/src/main/kotlin/forge/web/game/PhaseStopProfile.kt` | Profile class, HUMAN_DEFAULTS = Main1, DeclareAttackers, DeclareBlockers, Main2 |
| `forge-web/src/main/kotlin/forge/web/game/WebPlayerController.kt:930` | Profile check in `chooseSpellAbilityToPlay` |
| `forge-nexus/src/main/kotlin/forge/nexus/game/BundleBuilder.kt:45` | PhaseOrStepModified annotation injection |
| `forge-nexus/src/main/kotlin/forge/nexus/server/AutoPassEngine.kt:96` | drainPlayback snapshot |
| `forge-nexus/src/test/kotlin/forge/nexus/conformance/AiFirstTurnShapeTest.kt:149` | Failing test |

## Current State

- GameBridge.kt change written (2 lines: create profile, pass to controller)
- PhaseStopProfileTest assertion added (COMBAT_DAMAGE/COMBAT_END/CLEANUP explicitly false)
- Blocked on AiFirstTurnShapeTest regression — need to understand prevSnapshot
  state at the AI→human turn boundary before fixing annotation injection
