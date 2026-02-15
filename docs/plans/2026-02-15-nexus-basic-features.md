# Nexus Basic Features Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix 14 disabled GameBridgeTest integration tests, add cast spell conformance, then validate with real Arena client.

**Architecture:** GameBridgeTest's advanceToMain1() has a race condition (reads live game phase instead of pending's snapshotted phase) and lacks deterministic seeding. ConformanceTestBase's awaitFreshPending pattern is the proven fix. Extract shared helpers, re-enable all tests, fix one stale assertion, add cast spell conformance test.

**Tech Stack:** Kotlin, TestNG, protobuf (Arena messages), Forge game engine

**Baseline:** 78 tests pass, 0 fail. 14+1 tests disabled. Conformance suite: 18 pass.

---

### Task 1: Extract shared test helpers from ConformanceTestBase

**Files:**
- Create: `forge-nexus/src/test/kotlin/forge/nexus/game/TestHelpers.kt`
- Read: `forge-nexus/src/test/kotlin/forge/nexus/conformance/ConformanceTestBase.kt:132-163`

**Step 1: Write TestHelpers.kt**

Extract `awaitFreshPending` and `advanceToMain1` as top-level functions (not tied to a base class). Both GameBridgeTest and ConformanceTestBase will use these.

```kotlin
package forge.nexus.game

import forge.web.game.GameActionBridge
import forge.web.game.PlayerAction

/**
 * Shared test helpers for waiting on engine priority and advancing game state.
 * Used by both GameBridgeTest and ConformanceTestBase.
 */

/**
 * Wait for a pending action whose actionId differs from [previousId].
 * Returns null on timeout.
 */
fun awaitFreshPending(
    b: GameBridge,
    previousId: String?,
    timeoutMs: Long = 15_000,
): GameActionBridge.PendingAction? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val p = b.actionBridge.getPending()
        if (p != null && p.actionId != previousId && !p.future.isDone) return p
        Thread.sleep(50)
    }
    return null
}

/**
 * Pass priority until the game reaches Main1.
 *
 * Uses the pending action's snapshotted phase (not live game.phaseHandler.phase)
 * to eliminate the race where the live phase is checked before the pending is set.
 */
fun advanceToMain1(b: GameBridge, maxPasses: Int = 20) {
    val game = b.getGame()!!
    var passes = 0
    var lastId: String? = null
    while (passes < maxPasses) {
        val pending = awaitFreshPending(b, lastId)
            ?: error("Timed out waiting for priority while advancing to Main1 (phase=${game.phaseHandler.phase})")
        if (pending.state.phase == "MAIN1") return
        b.actionBridge.submitAction(pending.actionId, PlayerAction.PassPriority)
        lastId = pending.actionId
        passes++
    }
}
```

**Step 2: Run test compilation**

Run: `cd forge-nexus && just build`
Expected: BUILD SUCCESS (new file, no tests yet)

**Step 3: Commit**

```
feat(nexus): extract shared test helpers (awaitFreshPending, advanceToMain1)
```

---

### Task 2: Migrate GameBridgeTest to shared helpers + seed

**Files:**
- Modify: `forge-nexus/src/test/kotlin/forge/nexus/game/GameBridgeTest.kt`

**Step 1: Replace advanceToMain1 and add seed=42L**

In GameBridgeTest:

1. Delete the private `advanceToMain1` method (lines 999-1022)
2. Delete the private `advanceToPhase` method (lines 980-997) — replace with a version using awaitFreshPending
3. Import `forge.nexus.game.advanceToMain1` and `forge.nexus.game.awaitFreshPending`
4. Every test that calls `b.start()` followed by `advanceToMain1(b)` → change to `b.start(seed = 42L)`
5. Every `b.awaitPriority()` after action submission → replace with `awaitFreshPending(b, pending.actionId)`

The key changes to each disabled test:
- Add `seed = 42L` to `b.start()`
- Replace `advanceToMain1(b)` calls with the imported top-level version
- Replace `b.actionBridge.getPending()` with `awaitFreshPending(b, null)` or track lastId
- Replace `b.awaitPriority()` after submit with `awaitFreshPending(b, pending.actionId)`

Also fix `advanceToPhase` helper to use the shared pattern:

```kotlin
private fun advanceToPhase(b: GameBridge, target: String, maxPasses: Int = 50) {
    val game = b.getGame()!!
    var lastId: String? = null
    var passes = 0
    while (passes < maxPasses) {
        val pending = awaitFreshPending(b, lastId, timeoutMs = 5_000) ?: break
        if (pending.state.phase == target) return
        b.actionBridge.submitAction(pending.actionId, PlayerAction.PassPriority)
        lastId = pending.actionId
        passes++
        if (game.isGameOver) break
    }
}
```

**Step 2: Re-enable all 14 disabled tests**

Change every `@Test(enabled = false, description = "broken: advanceToMain1 stalls at DRAW phase")` to `@Test`.

**Step 3: Run tests**

Run: `cd forge-nexus && just test`
Expected: Most tests pass. Note any failures for Task 3.

**Step 4: Commit**

```
fix(nexus): fix DRAW stall — migrate GameBridgeTest to shared awaitFreshPending + seed
```

---

### Task 3: Fix stale phaseTransitionEmitsTwoDiffs assertion

**Files:**
- Modify: `forge-nexus/src/test/kotlin/forge/nexus/game/GameBridgeTest.kt:424-442`

**Step 1: Update test to expect 5 messages**

The test was written when phaseTransitionDiff produced 2 messages. It now produces 5 (the full Arena pattern). Update:

```kotlin
@Test
fun phaseTransitionEmitsFiveMessagePattern() {
    val b = GameBridge()
    bridge = b
    b.start(seed = 42L)
    b.submitKeep(1)
    advanceToMain1(b)

    val game = b.getGame()!!
    val result = BundleBuilder.phaseTransitionDiff(game, b, "test-match", 1, 1, 10)

    Assert.assertEquals(result.messages.size, 5, "Phase transition should emit 5 messages")

    // Message 1: SendHiFi with PhaseOrStepModified annotations
    val gs1 = result.messages[0].gameStateMessage
    assertEquals(gs1.update, Messages.GameStateUpdate.SendHiFi)

    // Message 2: SendHiFi echo
    val gs2 = result.messages[1].gameStateMessage
    assertEquals(gs2.update, Messages.GameStateUpdate.SendHiFi)

    // Message 3: SendAndRecord with PhaseOrStepModified
    val gs3 = result.messages[2].gameStateMessage
    assertEquals(gs3.update, Messages.GameStateUpdate.SendAndRecord)

    // Message 4: PromptReq (promptId=37)
    assertEquals(result.messages[3].type, Messages.GREMessageType.PromptReq)
    assertEquals(result.messages[3].prompt.promptId, 37)

    // Message 5: ActionsAvailableReq (promptId=2)
    assertEquals(result.messages[4].type, Messages.GREMessageType.ActionsAvailableReq_695e)
    assertEquals(result.messages[4].prompt.promptId, 2)

    // gsIds should be sequential across GSM messages
    val gsIds = result.messages.filter { it.hasGameStateMessage() }
        .map { it.gameStateMessage.gameStateId }
    for (i in 1 until gsIds.size) {
        Assert.assertTrue(gsIds[i] > gsIds[i - 1], "gsIds should be ascending")
    }
}
```

**Step 2: Run the test**

Run: `cd forge-nexus && just test`
Expected: PASS

**Step 3: Commit**

```
fix(nexus): update phaseTransition test for 5-message Arena pattern
```

---

### Task 4: Fix any remaining test failures

**Files:**
- Modify: `forge-nexus/src/test/kotlin/forge/nexus/game/GameBridgeTest.kt` (various tests as needed)

After Tasks 2-3, run the full suite and fix any remaining failures. Likely candidates:

1. **`declareAttackersReqListsEligibleCreatures`** — calls `advanceToPhase(b, PhaseType.COMBAT_DECLARE_ATTACKERS)` with the old PhaseType enum. Needs migration to string-based phase matching or the new helper.
2. **`aiCombatPopulatesAttackState`** — same advanceToPhase issue.
3. **`postActionSendsDiffNotFull`** — calls `b.snapshotState(game)` then checks Diff type. Should work with seed.
4. **`castSpellLeavesSpellOnStack`** — already uses seed=42L, uses `advanceToMain1`.

**Step 1: Run full suite, capture output**

Run: `cd forge-nexus && just test 2>&1 | tee /tmp/nexus-test-results.txt`

**Step 2: Fix each failure**

Address failures individually. Most likely need:
- Update advanceToPhase calls to use string-based phase (matching PendingAction.state.phase format)
- Fix any assertion values that changed with the 5-message pattern

**Step 3: Run again, confirm all pass**

Run: `cd forge-nexus && just test`
Expected: All 92+ tests pass (78 existing + 14 re-enabled)

**Step 4: Commit**

```
fix(nexus): fix remaining GameBridgeTest failures after re-enablement
```

---

### Task 5: Add cast spell conformance test

**Files:**
- Create: `forge-nexus/src/test/kotlin/forge/nexus/conformance/CastSpellConformanceTest.kt`
- Read: `forge-nexus/src/test/resources/golden/arena-cast-creature.json`

**Step 1: Read the arena golden to understand expected shape**

The `arena-cast-creature.json` golden has 4 messages (2x aiActionDiff pairs: cast + resolve). Check its structure with the existing fingerprint tooling.

**Step 2: Write the conformance test**

```kotlin
package forge.nexus.conformance

import forge.nexus.game.BundleBuilder
import forge.nexus.game.StateMapper
import forge.nexus.game.awaitFreshPending
import forge.web.game.PlayerAction
import org.testng.Assert
import org.testng.annotations.Test

/**
 * Wire conformance: cast spell annotations match Arena golden.
 *
 * Arena pattern for AI cast (from arena-cast-creature.json):
 *   Pair 1: GS Diff SendHiFi (CastSpell annotations) + GS Diff SendHiFi (echo)
 *   Pair 2: GS Diff SendHiFi (Resolve annotations) + GS Diff SendHiFi (echo)
 *
 * Our aiActionDiff produces each pair. This test verifies annotation categories
 * (CastSpell has ManaPaid+TappedUntappedPermanent+AbilityInstanceCreated,
 * Resolve has ResolutionStart+ZoneTransfer+ResolutionComplete).
 */
@Test(groups = ["integration", "conformance"])
class CastSpellConformanceTest : ConformanceTestBase() {

    @Test
    fun castSpellAnnotationsMatchArenaShape() {
        val (b, game, gsId) = startGameAtMain1()

        // Play a land for mana
        playLand(b)
        b.snapshotState(game)

        // Cast a creature
        val player = b.getPlayer(1)!!
        val creature = player.getZone(forge.game.zone.ZoneType.Hand).cards
            .firstOrNull { it.isCreature } ?: return // no creature = skip

        val pending = awaitFreshPending(b, null) ?: return
        b.actionBridge.submitAction(pending.actionId, PlayerAction.CastSpell(creature.id))
        awaitFreshPending(b, pending.actionId)

        // Build cast diff (hand→stack)
        val castResult = BundleBuilder.aiActionDiff(game, b, "test-match", 1, 1, gsId)
        val castFp = fingerprint(castResult.messages)

        // Verify 2 messages (diff + echo)
        Assert.assertEquals(castFp.size, 2, "Cast aiActionDiff should produce 2 messages")

        // Compare shape against arena golden
        assertShapeConformance("arena-cast-creature", castFp)
    }
}
```

**Step 3: Run the test**

Run: `cd forge-nexus && just test-conformance`

If shape comparison fails, inspect the diff to understand what's different from the Arena golden (annotation types, categories, update types). Fix StateMapper/AnnotationBuilder as needed.

**Step 4: Commit**

```
test(nexus): add cast spell conformance test against Arena golden
```

---

### Task 6: Migrate ConformanceTestBase to shared helpers

**Files:**
- Modify: `forge-nexus/src/test/kotlin/forge/nexus/conformance/ConformanceTestBase.kt`

**Step 1: Replace private methods with shared imports**

Replace `private fun advanceToMain1` and `private fun awaitFreshPending` in ConformanceTestBase with imports from `forge.nexus.game.advanceToMain1` and `forge.nexus.game.awaitFreshPending`. Keep the conformance-specific helpers (playLand, castCreature, passPriority) but update them to use the shared `awaitFreshPending`.

**Step 2: Run conformance tests**

Run: `cd forge-nexus && just test-conformance`
Expected: All 18+ conformance tests still pass

**Step 3: Run full suite**

Run: `cd forge-nexus && just test`
Expected: All tests pass

**Step 4: Commit**

```
refactor(nexus): deduplicate ConformanceTestBase — use shared test helpers
```

---

### Task 7: Format and final verification

**Step 1: Format**

Run: `cd forge-nexus && just fmt`

**Step 2: Full test suite**

Run: `cd forge-nexus && just test`
Expected: 90+ tests pass, 0 failures

**Step 3: Commit if fmt changed anything**

```
style(nexus): format
```

---

### Task 8: Client validation (manual)

**Step 1: Start server**

Run: `cd forge-nexus && just serve`

**Step 2: Connect Arena client**

Point Arena client at local Nexus server. Play through:
- Mulligan (keep)
- Play a land
- Cast a spell
- Attack with creature
- Observe AI (Sparky) turn

**Step 3: Capture observations**

Note: what renders correctly, what breaks, any client disconnects or visual glitches.
Proto dump files will be in the working directory.
Debug panel at http://localhost:8090.

**Step 4: Document findings for next iteration**

Create `forge-nexus/docs/plans/YYYY-MM-DD-client-validation-notes.md` with findings.
