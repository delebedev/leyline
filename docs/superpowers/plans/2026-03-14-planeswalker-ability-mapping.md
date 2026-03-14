# Planeswalker Ability Mapping Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix multi-ability activated ability dispatch so planeswalkers (and any card with 2+ activated abilities) activate the correct ability, not always the first one.

**Architecture:** Two-site fix. ActionMapper assigns per-ability abilityGrpId when building actions (outbound). MatchSession reverse-lookups abilityGrpId→index when processing client response (inbound). PuzzleCardRegistrar already generates ordered synthetic abilityGrpIds — no changes needed there.

**Tech Stack:** Kotlin, Forge engine, protobuf Actions

---

### Task 1: Fix ActionMapper — per-ability abilityGrpId assignment

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/mapper/ActionMapper.kt:70-89`

The current code uses `cardData.abilityIds.firstOrNull()` for ALL abilities. Fix: track a running index of non-mana activated abilities and use it to index into `abilityIds` (skipping keyword entries).

- [ ] **Step 1: Fix abilityGrpId assignment in the Activate loop**

In `ActionMapper.kt`, the loop at line 70-89 iterates `card.spellAbilities` and builds Activate actions. Replace the hardcoded `firstOrNull()` with index-based lookup:

```kotlin
// Activate — non-mana activated abilities (only with legality checks)
if (checkLegality) {
    val cardData = bridge.cards.findByGrpId(grpId)
    val keywordCount = cardData?.keywordAbilityGrpIds?.size ?: 0
    var activateIndex = 0
    for (ability in card.spellAbilities) {
        ability.setActivatingPlayer(player)
        if (!ability.isActivatedAbility) continue
        if (ability.isManaAbility()) continue
        if (!ability.canPlay()) {
            activateIndex++
            continue
        }
        val actionBuilder = Action.newBuilder()
            .setActionType(ActionType.Activate_add3)
            .setInstanceId(instanceId)
            .setGrpId(grpId)
            .setFacetId(instanceId)
            .setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.Activate_add3))
        if (cardData != null) {
            val abilitySlot = keywordCount + activateIndex
            val abilityEntry = cardData.abilityIds.getOrNull(abilitySlot)
            if (abilityEntry != null) actionBuilder.setAbilityGrpId(abilityEntry.first)
        }
        activateIndex++
        builder.addActions(actionBuilder)
    }
}
```

Key changes:
- Move `cardData` lookup outside the inner loop (was redundant per-iteration)
- Track `activateIndex` across ALL non-mana activated abilities (including non-playable ones — they still occupy abilityIds slots)
- Offset by `keywordCount` since keywords occupy the first N abilityIds entries
- Increment `activateIndex` even for non-playable abilities (they still have abilityGrpId slots)

- [ ] **Step 2: Run `just fmt` to format**

Run: `just fmt`

- [ ] **Step 3: Commit**

```bash
git add matchdoor/src/main/kotlin/leyline/game/mapper/ActionMapper.kt
git commit -m "fix(matchdoor): per-ability abilityGrpId in ActionMapper

Assign correct abilityGrpId to each Activate action based on its
position in the card's ability list, offset by keyword count.
Fixes multi-ability cards (planeswalkers, charm creatures) always
getting the first ability's grpId."
```

---

### Task 2: Fix MatchSession — abilityGrpId→index reverse lookup

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/match/MatchSession.kt:256-271`

The current code hardcodes `abilityIndex = 0`. Fix: look up the card's abilityIds, find which index matches the incoming abilityGrpId, offset by keywords.

- [ ] **Step 1: Replace hardcoded abilityIndex with reverse lookup**

In `MatchSession.kt`, replace the `Activate_add3` handler:

```kotlin
ActionType.Activate_add3 -> {
    val forgeCardId = bridge.getForgeCardId(InstanceId(action.instanceId))
    // Resolve abilityGrpId → Forge ability index.
    // CardData.abilityIds = [keyword0, keyword1, ..., activate0, activate1, ...]
    // The activate entries start after keywordAbilityGrpIds.size.
    val abilityIndex = resolveAbilityIndex(action, bridge)
    val submitted = if (forgeCardId != null) {
        bridge.actionBridge.submitAction(
            pending.actionId,
            PlayerAction.ActivateAbility(forgeCardId, abilityIndex),
        )
    } else {
        bridge.actionBridge.submitAction(pending.actionId, PlayerAction.PassPriority)
    }
    Tap.actionResult(action.actionType, action.instanceId, forgeCardId?.value, submitted)
}
```

- [ ] **Step 2: Add resolveAbilityIndex helper method**

Add as a private companion or top-level function in MatchSession.kt (or nearby):

```kotlin
/**
 * Map Arena abilityGrpId → Forge ability index for multi-ability cards.
 *
 * CardData.abilityIds layout: [keyword0, keyword1, ..., activate0, activate1, ...]
 * Keyword entries occupy the first keywordAbilityGrpIds.size slots.
 * Activated ability entries follow. The Forge ability index is the offset
 * from the first activated ability entry.
 *
 * Falls back to 0 if lookup fails (single-ability cards, missing data).
 */
private fun resolveAbilityIndex(action: Action, bridge: GameBridge): Int {
    val abilityGrpId = action.abilityGrpId
    if (abilityGrpId == 0) return 0

    val cardGrpId = action.grpId
    val cardData = bridge.cards.findByGrpId(cardGrpId) ?: return 0
    val keywordCount = cardData.keywordAbilityGrpIds.size

    // Find which slot in abilityIds matches this abilityGrpId
    val slotIndex = cardData.abilityIds.indexOfFirst { it.first == abilityGrpId }
    if (slotIndex < 0) return 0

    // Subtract keyword slots to get the activated ability index
    val abilityIndex = slotIndex - keywordCount
    return if (abilityIndex >= 0) abilityIndex else 0
}
```

- [ ] **Step 3: Run `just fmt`**

Run: `just fmt`

- [ ] **Step 4: Commit**

```bash
git add matchdoor/src/main/kotlin/leyline/match/MatchSession.kt
git commit -m "fix(matchdoor): resolve abilityGrpId→index for Activate actions

Replace hardcoded abilityIndex=0 with reverse lookup through
CardData.abilityIds. Maps Arena abilityGrpId to Forge's per-card
ability index, accounting for keyword ability slots.
Fixes planeswalkers always activating their first ability."
```

---

### Task 3: Integration test — Planeswalker sacrifice puzzle

**Files:**
- Create: `matchdoor/src/test/kotlin/leyline/conformance/PlaneswalkerSacrificeTest.kt`

- [ ] **Step 1: Write the integration test**

```kotlin
package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.ForgeCardId
import wotc.mtgo.gre.external.messaging.Messages.ActionType

class PlaneswalkerSacrificeTest : FunSpec({

    tags(IntegrationTag)

    var harness: MatchFlowHarness? = null
    afterEach { harness?.shutdown(); harness = null }

    test("Liliana -2 forces sacrifice, attack for lethal") {
        val pzl = """
            [metadata]
            Name:Liliana Sacrifice
            Goal:Win
            Turns:1
            Difficulty:Easy
            Description:Cast Liliana, -2 to force sacrifice, attack for lethal.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=2

            humanhand=Liliana of the Veil
            humanbattlefield=Grizzly Bears;Swamp;Swamp;Swamp
            humanlibrary=Swamp
            aibattlefield=Centaur Courser
            ailibrary=Mountain
        """.trimIndent()

        val h = MatchFlowHarness(seed = 42L, validating = false)
        harness = h
        h.connectAndKeepPuzzleText(pzl)

        val human = h.game().registeredPlayers.first()
        val ai = h.game().registeredPlayers.last()

        h.phase() shouldBe "MAIN1"

        // Cast Liliana of the Veil (1BB)
        h.castSpellByName("Liliana of the Veil").shouldBeTrue()

        // Resolve onto battlefield
        repeat(5) {
            if (h.isGameOver()) return@repeat
            if (human.getZone(ZoneType.Battlefield).cards.any { it.name.contains("Liliana") }) return@repeat
            h.passPriority()
        }
        human.getZone(ZoneType.Battlefield).cards
            .any { it.name.contains("Liliana") }.shouldBeTrue()

        // Find Liliana and activate -2 (second ability, index 1)
        val liliana = human.getZone(ZoneType.Battlefield).cards
            .first { it.name.contains("Liliana") }
        val lilianaIid = h.bridge.getOrAllocInstanceId(ForgeCardId(liliana.id)).value
        val lilianaGrpId = h.bridge.cards.findGrpIdByName("Liliana of the Veil") ?: 0

        // Look up the -2 ability's abilityGrpId (second activated ability)
        val cardData = h.bridge.cards.findByGrpId(lilianaGrpId)!!
        val keywordCount = cardData.keywordAbilityGrpIds.size
        // Liliana has no keywords, so abilityIds[1] = second loyalty ability (-2)
        val minus2AbilityGrpId = cardData.abilityIds.getOrNull(keywordCount + 1)?.first ?: 0

        val activateMsg = performAction {
            actionType = ActionType.Activate_add3
            instanceId = lilianaIid
            grpId = lilianaGrpId
            abilityGrpId = minus2AbilityGrpId
        }
        h.session.onPerformAction(activateMsg)
        h.drainSink()

        // Target opponent (seatId 2)
        h.selectTargets(listOf(2))

        // Resolve -2 — Courser should be sacrificed
        repeat(10) {
            if (h.isGameOver()) return@repeat
            if (ai.getZone(ZoneType.Battlefield).cards.none { it.isCreature }) return@repeat
            h.passPriority()
        }

        ai.getZone(ZoneType.Battlefield).cards
            .filter { it.isCreature }.isEmpty().shouldBeTrue()

        // Advance to combat, attack with Bears
        repeat(10) {
            if (h.isGameOver()) return@repeat
            if (h.phase() == "COMBAT_DECLARE_ATTACKERS") return@repeat
            h.passPriority()
        }

        val bearsIid = h.humanBattlefieldCreatures()
            .first { it.second == "Grizzly Bears" }.first
        h.declareAttackers(listOf(bearsIid))

        // Pass through combat
        repeat(10) {
            if (h.isGameOver()) return@repeat
            h.passPriority()
        }

        h.isGameOver().shouldBeTrue()
        human.hasWon().shouldBeTrue()
        human.hasLost().shouldBeFalse()
        ai.life shouldBe 0
    }
})
```

- [ ] **Step 2: Run test — should pass after Tasks 1+2**

Run: `just test-one PlaneswalkerSacrificeTest`
Expected: PASS

- [ ] **Step 3: Run with validating=true to check invariants**

Change `validating = false` to `validating = true` and re-run.
If it fails with affectedId violations, note them — that's the combat regression (known, not this PR's scope). Switch back to `validating = false` for commit.

- [ ] **Step 4: Commit**

```bash
git add matchdoor/src/test/kotlin/leyline/conformance/PlaneswalkerSacrificeTest.kt
git commit -m "test(conformance): Liliana -2 sacrifice puzzle integration test

Verifies multi-ability planeswalker dispatch: cast Liliana of the Veil,
activate -2 (not +1), force opponent sacrifice, attack for lethal.
Refs #112."
```

---

### Task 4: Activated ability test — Goblin Fireslinger

**Files:**
- Create: `matchdoor/src/test/kotlin/leyline/conformance/ActivatedAbilityPuzzleTest.kt`

- [ ] **Step 1: Write integration test for the existing puzzle**

```kotlin
package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.ForgeCardId
import wotc.mtgo.gre.external.messaging.Messages.ActionType

class ActivatedAbilityPuzzleTest : FunSpec({

    tags(IntegrationTag)

    var harness: MatchFlowHarness? = null
    afterEach { harness?.shutdown(); harness = null }

    test("Goblin Fireslinger tap-to-ping kills opponent") {
        val pzl = """
            [metadata]
            Name:Ping for Lethal
            Goal:Win
            Turns:1
            Difficulty:Easy
            Description:Tap Goblin Fireslinger to ping opponent for lethal.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=1

            humanbattlefield=Goblin Fireslinger
            humanlibrary=Mountain
            aibattlefield=Centaur Courser
            ailibrary=Mountain
        """.trimIndent()

        val h = MatchFlowHarness(seed = 42L, validating = false)
        harness = h
        h.connectAndKeepPuzzleText(pzl)

        val human = h.game().registeredPlayers.first()
        val ai = h.game().registeredPlayers.last()

        h.phase() shouldBe "MAIN1"

        // Find Fireslinger and activate its tap ability
        val fireslinger = human.getZone(ZoneType.Battlefield).cards
            .first { it.name == "Goblin Fireslinger" }
        val iid = h.bridge.getOrAllocInstanceId(ForgeCardId(fireslinger.id)).value
        val grpId = h.bridge.cards.findGrpIdByName("Goblin Fireslinger") ?: 0

        // Single activated ability — abilityGrpId from first non-keyword slot
        val cardData = h.bridge.cards.findByGrpId(grpId)!!
        val keywordCount = cardData.keywordAbilityGrpIds.size
        val tapAbilityGrpId = cardData.abilityIds.getOrNull(keywordCount)?.first ?: 0

        val activateMsg = performAction {
            actionType = ActionType.Activate_add3
            instanceId = iid
            this.grpId = grpId
            abilityGrpId = tapAbilityGrpId
        }
        h.session.onPerformAction(activateMsg)
        h.drainSink()

        // Target opponent (seatId 2)
        h.selectTargets(listOf(2))

        // Resolve
        repeat(10) {
            if (h.isGameOver()) return@repeat
            h.passPriority()
        }

        h.isGameOver().shouldBeTrue()
        human.hasWon().shouldBeTrue()
        ai.life shouldBe 0
    }
})
```

- [ ] **Step 2: Run test**

Run: `just test-one ActivatedAbilityPuzzleTest`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add matchdoor/src/test/kotlin/leyline/conformance/ActivatedAbilityPuzzleTest.kt
git commit -m "test(conformance): Goblin Fireslinger activated ability puzzle test

Verifies tap-to-ping activated ability: activate, target opponent,
resolve damage. Single-ability card validates basic Activate path.
Refs #111."
```

---

### Task 5: Update catalog and docs

**Files:**
- Modify: `docs/catalog.yaml` — update planeswalker and activated ability status
- Modify: `docs/rosetta.md` — update Activate action type status if needed

- [ ] **Step 1: Update catalog.yaml**

Update `cast-planeswalker` status from `partial` to note ability dispatch fixed.
Update any activated ability entries.

- [ ] **Step 2: Commit**

```bash
git add docs/catalog.yaml
git commit -m "docs: update catalog — planeswalker ability dispatch fixed"
```

---

### Task 6: Push and update issues

- [ ] **Step 1: Run test gate**

Run: `just testGate`
Expected: All unit + conformance tests pass.

- [ ] **Step 2: Run integration tests**

Run: `just testIntegration`
Note any failures — combat regression failures are expected/known.

- [ ] **Step 3: Push**

```bash
git push
```

- [ ] **Step 4: Update GitHub issues**

Comment on #111 and #112 with test results.
