# Learnings — forge-nexus debugging & development

Distilled from Phase 1-2 of the auto-pass/priority/state-transition work (Feb 2026).
Patterns, traps, and heuristics for future sessions.

---

## 1. Two-thread model: always ask "which thread owns this state?"

forge-nexus has two active threads during gameplay:

| Thread | Runs | Blocks on |
|--------|------|-----------|
| **Engine** (game-loop-N) | `PhaseHandler.mainLoopStep()`, game rules | `CompletableFuture.get()` in bridges |
| **Session** (Netty I/O / test main) | `AutoPassEngine`, `CombatHandler`, message sending | `bridge.awaitPriority()` poll loop |

**Trap:** assuming state read on the session thread is stable. The engine thread mutates game state, fires EventBus events, and advances counters concurrently. Any read of engine-side state (phase, hand size, combat) from the session thread is a snapshot of a moving target.

**Example — gsId counter race (Phase 2):**
`NexusGamePlayback.captureAndPause()` runs on the engine thread and advances `gsIdCounter` from 34→35. Meanwhile, the session thread's `ops.gameStateId` is still 34. When `sendDeclareBlockersReq` builds a bundle referencing `gsId=34`, the bridge's `previousState` already has `gsId=35` (set by the engine's capture). Result: `prevGameStateId=35 == gameStateId=35` — self-referential.

**Fix pattern:** after `bridge.awaitPriority()` (which guarantees the engine is blocked), drain any pending playback messages AND sync counters before building outbound bundles:

```kotlin
bridge.awaitPriority()
syncCountersFromPlayback(bridge)  // drain queue + sync ops.gameStateId
sendDeclareBlockersReq(bridge)    // now safe to build bundle
```

**Heuristic:** any time you add a new outbound message path that follows `awaitPriority()`, check whether playback counters need syncing.

---

## 2. "What the engine knows" vs "what the client saw"

Two independent state timelines exist:

- **Diff snapshot** (`DiffSnapshotter.previousState`): last `GameStateMessage` built for diff computation. Advances every time `buildDiffFromGame()` runs — including on the engine thread (playback captures).
- **Last-sent state** (`DiffSnapshotter.lastSentTurnInfo`): last `TurnInfo` actually transmitted to the client via `sendBundledGRE()`.

**Trap:** using the diff snapshot to decide what the client knows. When `PhaseStopProfile` skips phases (e.g., UPKEEP/DRAW) on the engine thread, the snapshot jumps to MAIN1 without sending anything. If you compare current phase against snapshot, you see no change. But the client last saw the previous turn's END phase.

**Example — missing PhaseOrStepModified annotation (Phase 1):**
`BundleBuilder.postAction()` compared `gsBase.turnInfo` against `prevSnapshot.turnInfo`. Both showed MAIN1 (because drainPlayback had snapshotted at MAIN1). No annotation injected. Client didn't know the phase changed.

**Fix:** added `lastSentTurnInfo` tracking. `postAction()` now calls `bridge.isPhaseChangedFromLastSent(gsBase.turnInfo)` which compares against what was actually sent. Updated on every `sendBundledGRE()` call.

**Heuristic:** for any decision that depends on "has the client seen X?", use a dedicated client-tracking field. Never reuse the diff-computation snapshot for client awareness decisions.

---

## 3. Snapshot timing: don't snapshot what you haven't sent

**Trap:** calling `bridge.snapshotState(buildFromGame(...))` at a point where the built state hasn't been sent to the client yet. This advances the diff baseline, and the next diff omits objects that the client never received.

**Example — thin-diff instanceId gap (Phase 2):**
`AutoPassEngine.drainPlayback()` called `bridge.snapshotState(StateMapper.buildFromGame(game, ...))` after draining AI-action diffs. This snapshot captured current engine state (post-Draw). But the client only received diffs up to mid-Draw. When `postAction()` later built a diff at MAIN1, the baseline already included drawn cards → cards omitted from the diff → `ValidatingMessageSink` flagged "action instanceIds missing from objects."

Similarly, `NexusGamePlayback.captureAndPause()` called `buildFromGame()` after `aiActionDiff()`. But `aiActionDiff` internally calls `buildDiffFromGame` which already snapshots. The second `buildFromGame` used the same gsId → `gameStateId == prevGameStateId`.

**Fix:** removed both redundant snapshots. `buildDiffFromGame()` at StateMapper:506 is the single snapshot point — it runs after computing the diff, so the baseline always matches what was sent.

**Heuristic:** snapshot exactly once per sent message, and only after the diff has been computed from the old baseline.

---

## 4. Counter monotonicity: sync, don't clobber

`gsIdCounter` and `msgIdCounter` live in two places: `SessionOps` (session thread) and `NexusGamePlayback` (engine thread, AtomicInteger). They're synced via `seedCounters()` and `getCounters()`.

**Trap:** setting counters with `=` instead of `max()`. The engine thread may have already advanced past the session thread's value between a seed and a read.

**Example:** `seedCounters` originally used plain assignment. If the engine captured an action (advancing gsId to 36) between the session's seed (gsId=35) and the next read, the seed would clobber 36→35.

**Fix:** `seedCounters` uses `updateAndGet { maxOf(it, value) }`. Counter sync reads use `if (nextGs > ops.gameStateId)` guards.

```kotlin
// Wrong: clobbers engine advances
gsIdCounter.set(gsId)

// Right: monotonic
gsIdCounter.updateAndGet { maxOf(it, gsId) }
```

**Heuristic:** counters shared across threads must be monotonic. Use `max` semantics for writes, `>` guards for syncs.

---

## 5. `.ifEmpty {}` on user input is a logic bomb

**Trap:** using `.ifEmpty { fallback }` on a list that represents user selection. "Empty" and "none selected" are different from "not provided."

**Example — involuntary attackers (Phase 2):**
```kotlin
val selectedInstanceIds = resp.selectedAttackersList.map { it.attackerInstanceId }
    .ifEmpty { pendingLegalAttackers }  // BUG
```
Client sends empty list = "I choose to not attack." Code interprets as "client didn't provide a list, use all legal attackers." Human's haste creature attacks involuntarily, gets tapped, can't block on AI's turn.

**Fix:** removed `.ifEmpty { pendingLegalAttackers }`. Empty list means no attackers.

**Heuristic:** never add fallback-to-all on user-selection lists. If a default is needed, make it explicit in the protocol (separate `useDefaults` flag).

---

## 6. Don't consume script actions on failure

**Trap:** consuming a scripted action (dequeue) when the action can't execute yet, assuming it's invalid. It may just be wrong-turn or insufficient-mana.

**Example — ScriptedPlayerController (Phase 2):**
`PlayLand` and `CastSpell` actions were dequeued even when the card wasn't playable (e.g., it's the opponent's turn). The AI's script was exhausted during the human's priority checks, before the AI's actual turn.

**Fix:** only dequeue on success. On failure, return `null` (pass priority). The action stays at the head and retries next priority window.

```kotlin
// Wrong: consume on failure
log.warn("card not playable, passing")
nextAction()  // consumed — lost forever
null

// Right: leave for retry
null  // pass priority, action stays queued
```

**Heuristic:** scripted/queued actions should follow try-on-peek, consume-on-success semantics.

---

## 7. `pendingBlockersSent` lifecycle: clear at start-of-combat, not on response

**Trap:** clearing a "request-in-flight" flag when the response arrives. If a priority window follows in the same step, the flag is false and a duplicate request fires.

**Example — duplicate DeclareBlockersReq (Phase 2):**
`pendingBlockersSent = false` in `onDeclareBlockers()`. After blockers submitted, engine runs a priority window in DECLARE_BLOCKERS step. `checkCombatPhase` sees `!pendingBlockersSent` → sends another `DeclareBlockersReq`. Client gets confused.

**Fix:** clear `pendingBlockersSent` at `COMBAT_DECLARE_ATTACKERS` (new combat round), not on response. The flag stays true for the entire DECLARE_BLOCKERS phase.

**Heuristic:** request-in-flight flags should be cleared at the start of the next logical cycle, not on response receipt.

---

## 8. `awaitPriority()` before sending prompts to the client

**Trap:** sending a request to the client (DeclareBlockersReq) based on detecting a phase, without waiting for the engine to actually block. The engine may not have reached the `awaitAction()` call yet.

**Example — "no pending action" errors (Phase 2):**
`checkCombatPhase` detected `COMBAT_DECLARE_BLOCKERS` phase and sent `DeclareBlockersReq`. Client responded immediately. But the engine hadn't reached `WebPlayerController.declareBlockers()` → `actionBridge.getPending()` was null → "no pending action" recovery path.

**Fix:** `bridge.awaitPriority()` before sending the req.

```kotlin
// Wrong: race
if (phase == DECLARE_BLOCKERS) {
    sendDeclareBlockersReq(bridge)  // engine might not be ready
}

// Right: wait for engine to block
if (phase == DECLARE_BLOCKERS) {
    bridge.awaitPriority()          // engine is now blocked in awaitAction()
    syncCountersFromPlayback(bridge)
    sendDeclareBlockersReq(bridge)
}
```

**Heuristic:** detecting a phase on the session thread means the engine *entered* it, not that it's *blocked and waiting*. Always await before sending.

---

## 9. Test timing: check state at the right turn boundary

**Trap:** asserting on game state at a point where the condition hasn't been reached yet. Turn-based checks (`passUntilTurn(N)`) stop at the start of turn N, before that turn's cleanup.

**Example — DiscardHandSizeTest (Phase 2):**
Test passed until turn 5 and checked `hand <= 7`. But turn 5 is a human turn — draw pushed hand to 8 and cleanup hadn't run. The discard prompt was pending (or timed out).

**Fix:** `passUntilTurn(4)` — turn 4 is the AI turn after turn 3's cleanup, where hand is confirmed at 7.

**Heuristic:** to observe the effect of cleanup/end-of-turn, check during the *next* turn (ideally the opponent's, which has no draw step for the observed player). Diagram the turn structure in comments:

```kotlin
// T1 (human): hand=7, no draw (on play)
// T2 (AI):    human hand unchanged
// T3 (human): draw→8, cleanup→discard→7
// T4 (AI):    human hand=7 ← check here
```

---

## 10. Proto enum obfuscation: always reference the suffixed names

Arena proto enums are obfuscated with hash suffixes: `Phase.Main1_a549`, `Step.None_a2cb`, `SettingStatus.Clear_a3fe`. The suffixes are stable per proto version but not meaningful.

**Trap:** writing `Phase.Main1` (doesn't exist) or searching for `Phase.MAIN1` (Forge's enum, different layer).

**Heuristic:** when working with proto enums, grep the `.proto` file or the generated code for the base name. Always use the suffixed form in Kotlin code. Keep `docs/rosetta.md` as the cross-reference.

---

## 11. ValidatingMessageSink is the single source of truth for wire correctness

The `ValidatingMessageSink` in tests catches:
- Self-referential gsId (`gameStateId == prevGameStateId`)
- gsId chain gaps (`prevGsId not in known set`)
- Missing instanceIds in gameObjects
- Duplicate gsIds

**Trap:** tests passing but wire shape being wrong. Without the sink, a test can reach the right game state via a broken message sequence.

**Heuristic:** all integration tests should use `ValidatingMessageSink` (via `MatchFlowHarness`). If a test times out, check the sink's `violations` list first — it often has the root cause before the timeout fires.

---

## 12. Debugging order for test failures

1. **Read the error message** — ValidatingMessageSink violations are precise
2. **Check thread** — is the failure on game-loop-N (engine) or main (session)?
3. **Check phase/turn** — where in the game loop did it fail?
4. **Trace the counter flow** — add `log.info` with ops.gameStateId + playback.getCounters() at suspected sync points
5. **Check snapshot timing** — when was `bridge.snapshotState()` last called relative to the failing message?
6. **Check if pending messages exist** — `playback.hasPendingMessages()` at the failure point
7. **Run single test** — `just test-one TestClassName` for fast iteration (30-40s vs 2-3min for full suite)
8. **Guard assertions** — add them in production code (like the SELF-REF check in StateMapper) to catch regressions permanently

---

## 13. ConformanceTestBase bypasses MatchSession — seed manually

Tests extending `ConformanceTestBase` construct `GameBridge` directly without `MatchSession`. Any state that `MatchSession` normally initializes (like `lastSentTurnInfo`) must be seeded explicitly in `startGameAtMain1()`.

**Trap:** adding a new tracker to `MatchSession.sendBundledGRE()` and forgetting to seed it in `ConformanceTestBase`. Tests pass in isolation but conformance tests get wrong annotations.

**Heuristic:** when adding new client-tracking state, grep for `ConformanceTestBase.startGameAtMain1()` and seed the new state there.

---

## 14. Integration tests can't use `unit` group for code that touches forge-web DTOs

`PhaseStopProfile` and similar forge-web classes use kotlinx.serialization. Test classes importing them fail at classload time in the `unit` group because `GameBootstrap.initializeCardDatabase()` hasn't run.

**Heuristic:** if a test imports anything from `forge.web.dto.*` or classes that depend on serialization infrastructure, use `integration` group. Pure data classes and mapping functions can stay in `unit`.
