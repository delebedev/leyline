# AbilityRegistry + Resolution Wiring — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a per-card AbilityRegistry that maps Forge trait IDs (SpellAbility, Trigger, StaticAbility) to Arena abilityGrpId slots, then wire it into mana actions and the sourceAbilityGrpId resolver.

**Architecture:** AbilityRegistry is constructed at card registration time (PuzzleCardRegistrar), alongside the existing AbilityIdDeriver output. It stores the reverse mapping: Forge internal ID → abilityGrpId slot. Three consumers: (1) mana action builder replaces `firstOrNull()`, (2) sourceAbilityResolver generalizes beyond Prowess, (3) future trigger attribution (out of scope for this PR).

**Tech Stack:** Kotlin, Forge engine APIs (`SpellAbility.getId()`, `StaticAbility.getId()`, `Trigger.getId()`, `isIntrinsic()`, `isManaAbility()`), protobuf Actions/Annotations.

---

## Prior work (already landed)

The March 14 plan landed two fixes that are now in main:
- **ActionMapper** (lines 98-124): per-ability abilityGrpId indexing for `Activate` actions — correct.
- **MatchSession.resolveAbilityIndex** (lines 493-502): inbound abilityGrpId → Forge ability index — correct.

These covered the Activate action path. What remains:

## What this plan covers

1. **AbilityRegistry data structure** — maps Forge trait IDs to abilityGrpId slots per card
2. **Mana action fix** — `ActionMapper` lines 223, 294 use `firstOrNull()`. Wrong for cards with keywords (keyword grpId ≠ mana ability grpId). Needs registry lookup by SpellAbility ID.
3. **sourceAbilityGrpId generalization** — `StateMapper.buildSourceAbilityResolver` (line 456) is hardcoded to `PT_BOOST_KEYWORDS = setOf("PROWESS")`. Registry enables: staticId from ptBoostTable → AbilityRegistry → abilityGrpId. Works for any effect, not just Prowess.

## What this plan does NOT cover

- **Trigger tracking** — triggers aren't in `abilityIds` today. Adding them changes the slot layout and affects `resolveAbilityIndex`. Separate issue.
- **AbilityInstanceCreated grpId** — needs trigger tracking first.
- **AddAbility/RemoveAbility annotations** — #200 scope (design task).

## File structure

| File | Action | Responsibility |
|------|--------|---------------|
| `matchdoor/src/main/kotlin/leyline/game/AbilityRegistry.kt` | Create | Data class + construction from Card + CardData |
| `matchdoor/src/main/kotlin/leyline/game/CardData.kt` | Modify | Add `registry: AbilityRegistry?` field |
| `matchdoor/src/main/kotlin/leyline/game/PuzzleCardRegistrar.kt` | Modify | Build registry at card registration |
| `matchdoor/src/main/kotlin/leyline/game/mapper/ActionMapper.kt` | Modify | Replace `firstOrNull()` with registry lookup |
| `matchdoor/src/main/kotlin/leyline/game/StateMapper.kt` | Modify | Replace keyword-hardcoded resolver with registry |
| `matchdoor/src/test/kotlin/leyline/game/AbilityRegistryTest.kt` | Create | Unit tests for registry construction |
| `matchdoor/src/test/kotlin/leyline/conformance/ActionFieldConformanceTest.kt` | Modify | Add multi-ability abilityGrpId conformance |

---

### Task 1: AbilityRegistry data structure

**Files:**
- Create: `matchdoor/src/main/kotlin/leyline/game/AbilityRegistry.kt`

- [ ] **Step 1: Write the AbilityRegistry class**

```kotlin
package leyline.game

import forge.game.card.Card

/**
 * Maps Forge trait IDs to Arena abilityGrpId slots for a single card.
 *
 * Built at card registration time from the live Forge [Card] object.
 * Slots are ordered: keywords first, then non-mana activated abilities
 * (matching [AbilityIdDeriver] layout).
 *
 * Three lookup paths:
 * - [forSpellAbility]: SpellAbility.getId() → abilityGrpId (mana + activated)
 * - [forStaticAbility]: StaticAbility.getId() → abilityGrpId (effect tracking)
 * - [forTrigger]: Trigger.getId() → abilityGrpId (future: trigger attribution)
 */
class AbilityRegistry private constructor(
    private val spellAbilityMap: Map<Int, Int>,   // SA id → abilityGrpId
    private val staticAbilityMap: Map<Int, Int>,   // Static id → abilityGrpId
    private val triggerMap: Map<Int, Int>,          // Trigger id → abilityGrpId
) {
    /** Resolve a SpellAbility's Forge ID to its abilityGrpId, or null. */
    fun forSpellAbility(forgeId: Int): Int? = spellAbilityMap[forgeId]

    /** Resolve a StaticAbility's Forge ID to its abilityGrpId, or null. */
    fun forStaticAbility(forgeId: Int): Int? = staticAbilityMap[forgeId]

    /** Resolve a Trigger's Forge ID to its abilityGrpId, or null. */
    fun forTrigger(forgeId: Int): Int? = triggerMap[forgeId]

    companion object {
        /**
         * Build registry from a live Forge card and its derived [CardData].
         *
         * Walks the card's intrinsic traits and assigns each to the matching
         * abilityGrpId slot from [cardData.abilityIds]. Slot layout must match
         * [AbilityIdDeriver]: keywords first (via keyword expansions), then
         * non-mana activated abilities.
         */
        fun build(card: Card, cardData: CardData): AbilityRegistry {
            val abilityIds = cardData.abilityIds
            if (abilityIds.isEmpty()) return EMPTY

            val spellAbilityMap = mutableMapOf<Int, Int>()
            val staticAbilityMap = mutableMapOf<Int, Int>()
            val triggerMap = mutableMapOf<Int, Int>()

            val keywordCount = cardData.keywordAbilityGrpIds.size

            // Keywords occupy first N slots. Each keyword may expand to
            // multiple Forge objects (StaticAbility + Trigger + SpellAbility).
            // Group all sub-traits of each keyword into its slot's abilityGrpId.
            val keywords = card.rules?.mainPart?.keywords?.toList() ?: emptyList()
            for ((i, kw) in keywords.withIndex()) {
                val grpId = abilityIds.getOrNull(i)?.first ?: continue
                // Forge expands keywords into sub-traits accessible via KeywordInterface
                val kwInstance = card.keywordInstances
                    ?.firstOrNull { it.keyword.name.equals(kw, ignoreCase = true) }
                    ?: continue
                for (sa in kwInstance.abilities ?: emptyList()) {
                    spellAbilityMap[sa.id] = grpId
                }
                for (trigger in kwInstance.triggers ?: emptyList()) {
                    triggerMap[trigger.id] = grpId
                }
                for (static in kwInstance.staticAbilities ?: emptyList()) {
                    staticAbilityMap[static.id] = grpId
                }
            }

            // Non-mana activated abilities occupy slots after keywords.
            var activateIndex = 0
            for (sa in card.spellAbilities ?: emptyList()) {
                if (!sa.isActivatedAbility) continue
                if (sa.isManaAbility()) {
                    // Mana abilities aren't in abilityIds slots, but we still
                    // want to look them up. Assign to the FIRST slot as fallback
                    // (matches basic land behavior where slot 0 = mana ability).
                    // For cards with keywords, this maps mana SA → slot 0 too,
                    // which is imperfect but safe (mana actions don't carry
                    // keyword grpIds on the wire).
                    spellAbilityMap.putIfAbsent(sa.id, abilityIds.first().first)
                    continue
                }
                val slot = keywordCount + activateIndex
                val grpId = abilityIds.getOrNull(slot)?.first
                if (grpId != null) spellAbilityMap[sa.id] = grpId
                activateIndex++
            }

            // Intrinsic static abilities not from keywords — map to nearest slot.
            // These drive effect tracking (e.g., equipment +1/+1 static → effect_id).
            for (static in card.staticAbilities ?: emptyList()) {
                if (!static.isIntrinsic) continue
                if (static.id in staticAbilityMap) continue // already from keyword
                // Non-keyword statics don't have dedicated slots.
                // Map to slot 0 as best-effort for sourceAbilityGrpId.
                staticAbilityMap.putIfAbsent(static.id, abilityIds.first().first)
            }

            // Intrinsic triggers not from keywords
            for (trigger in card.triggers ?: emptyList()) {
                if (!trigger.isIntrinsic) continue
                if (trigger.id in triggerMap) continue // already from keyword
                triggerMap.putIfAbsent(trigger.id, abilityIds.first().first)
            }

            return AbilityRegistry(spellAbilityMap, staticAbilityMap, triggerMap)
        }

        val EMPTY = AbilityRegistry(emptyMap(), emptyMap(), emptyMap())
    }
}
```

- [ ] **Step 2: Run `just fmt`**

Run: `just fmt`

- [ ] **Step 3: Commit**

```bash
git add matchdoor/src/main/kotlin/leyline/game/AbilityRegistry.kt
git commit -m "feat(matchdoor): AbilityRegistry — Forge trait ID to abilityGrpId mapping

Per-card registry built at card registration from live Forge Card.
Three lookup paths: SpellAbility, StaticAbility, Trigger.
Slots match AbilityIdDeriver layout (keywords first, then activated).
Refs #160."
```

---

### Task 2: Unit test AbilityRegistry with a planeswalker

**Files:**
- Create: `matchdoor/src/test/kotlin/leyline/game/AbilityRegistryTest.kt`

- [ ] **Step 1: Write the unit test**

Uses `ConformanceTestBase.startWithBoard` to get a live Card object — same fast path as ActionFieldConformanceTest (~0.01s).

```kotlin
package leyline.game

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.ConformanceTag
import leyline.conformance.ConformanceTestBase

class AbilityRegistryTest : FunSpec({

    tags(ConformanceTag)

    val base = ConformanceTestBase()
    beforeSpec { base.initCardDatabase() }
    afterEach { base.tearDown() }

    test("planeswalker — 3 loyalty abilities get distinct abilityGrpIds") {
        val (b, _, _) = base.startWithBoard { _, human, _ ->
            base.addCard("Liliana of the Veil", human, ZoneType.Battlefield)
        }

        val player = b.getPlayer(leyline.bridge.SeatId(1))!!
        val liliana = player.getZone(ZoneType.Battlefield).cards
            .first { it.name.contains("Liliana") }
        val grpId = b.cards.findGrpIdByName("Liliana of the Veil")!!
        val cardData = b.cards.findByGrpId(grpId)!!
        val registry = AbilityRegistry.build(liliana, cardData)

        // Liliana has 3 activated (loyalty) abilities, no keywords
        cardData.abilityIds.size shouldBe 3

        // Each loyalty SpellAbility should map to a distinct abilityGrpId
        val loyaltyAbilities = liliana.spellAbilities
            .filter { it.isActivatedAbility && !it.isManaAbility() }
        loyaltyAbilities.size shouldBe 3

        val resolvedGrpIds = loyaltyAbilities.map { sa ->
            registry.forSpellAbility(sa.id).shouldNotBeNull()
        }
        // All distinct
        resolvedGrpIds.toSet().size shouldBe 3
        // Match the abilityIds order
        resolvedGrpIds shouldBe cardData.abilityIds.map { it.first }
    }

    test("single-ability creature — registry still works") {
        val (b, _, _) = base.startWithBoard { _, human, _ ->
            base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
        }

        val player = b.getPlayer(leyline.bridge.SeatId(1))!!
        val bears = player.getZone(ZoneType.Battlefield).cards
            .first { it.name == "Grizzly Bears" }
        val grpId = b.cards.findGrpIdByName("Grizzly Bears")!!
        val cardData = b.cards.findByGrpId(grpId)!!
        val registry = AbilityRegistry.build(bears, cardData)

        // Grizzly Bears: no activated abilities, no keywords.
        // abilityIds has 1 slot (the max(1, ...) floor).
        cardData.abilityIds.size shouldBe 1

        // No SpellAbility lookups should resolve (vanilla creature)
        val activated = bears.spellAbilities
            .filter { it.isActivatedAbility && !it.isManaAbility() }
        activated.size shouldBe 0
    }

    test("basic land — mana SpellAbility resolves to well-known grpId") {
        val (b, _, _) = base.startWithBoard { _, human, _ ->
            base.addCard("Forest", human, ZoneType.Battlefield)
        }

        val player = b.getPlayer(leyline.bridge.SeatId(1))!!
        val forest = player.getZone(ZoneType.Battlefield).cards
            .first { it.name == "Forest" }
        val grpId = b.cards.findGrpIdByName("Forest")!!
        val cardData = b.cards.findByGrpId(grpId)!!
        val registry = AbilityRegistry.build(forest, cardData)

        // Forest has well-known abilityGrpId 1005
        cardData.abilityIds.first().first shouldBe 1005

        // Mana ability should resolve to the well-known ID
        val manaAbility = forest.manaAbilities.first()
        registry.forSpellAbility(manaAbility.id) shouldBe 1005
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `just test-one AbilityRegistryTest`
Expected: Compilation error — `AbilityRegistry` not yet on `CardData`. But the class itself should be found since Task 1 creates it.

NOTE: If the test can't call `AbilityRegistry.build()` because the Forge Card APIs (`keywordInstances`, `staticAbilities`, etc.) don't match the plan's assumptions, **stop and investigate**. Read the Forge `Card` class to find the correct accessor names. The plan's API names are based on the issue description, not verified against Forge source.

- [ ] **Step 3: Fix any Forge API mismatches, re-run until green**

The Forge Card API is in `forge/forge-game/src/main/java/forge/game/card/Card.java`. Key accessors to verify:
- `card.getKeywords()` or similar for keyword instances
- `card.getStaticAbilities()` for static abilities
- `card.getTriggers()` for triggers
- `card.getSpellAbilities()` for spell abilities
- Each has `.getId()` or `.id` for the Forge internal ID

Adapt `AbilityRegistry.build()` to match actual Forge APIs.

- [ ] **Step 4: Commit**

```bash
git add matchdoor/src/test/kotlin/leyline/game/AbilityRegistryTest.kt
git commit -m "test(matchdoor): AbilityRegistry unit tests

Planeswalker (3 distinct loyalty slots), vanilla creature (regression),
basic land (mana ability well-known ID). Refs #160."
```

---

### Task 3: Wire registry into CardData and PuzzleCardRegistrar

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/CardData.kt:10-23`
- Modify: `matchdoor/src/main/kotlin/leyline/game/PuzzleCardRegistrar.kt:117-131`

- [ ] **Step 1: Add `registry` field to CardData**

In `CardData.kt`, add the field with a default of `null` (backward-compatible):

```kotlin
data class CardData(
    val grpId: Int,
    val titleId: Int,
    // ... existing fields ...
    val keywordAbilityGrpIds: Map<String, Int> = emptyMap(),
    val registry: AbilityRegistry? = null,  // NEW
)
```

- [ ] **Step 2: Build registry in PuzzleCardRegistrar.registerCard()**

In `PuzzleCardRegistrar.kt`, after line 117 (`val (abilityIds, keywordAbilityGrpIds) = deriveAbilityIds(card)`), build the registry and pass it to CardData:

```kotlin
val (abilityIds, keywordAbilityGrpIds) = deriveAbilityIds(card)

// Build after deriveAbilityIds so cardData has the slots
val cardData = CardData(
    grpId = grpId,
    // ... existing fields ...
    abilityIds = abilityIds,
    keywordAbilityGrpIds = keywordAbilityGrpIds,
    registry = null, // placeholder — built below
)
val registry = AbilityRegistry.build(card, cardData)
return cardData.copy(registry = registry)
```

Alternatively, build CardData first without registry, then `copy()` with it. The registry construction needs abilityIds from CardData.

- [ ] **Step 3: Run AbilityRegistryTest to confirm it still passes**

Run: `just test-one AbilityRegistryTest`
Expected: PASS

- [ ] **Step 4: Run `just fmt` and commit**

```bash
just fmt
git add matchdoor/src/main/kotlin/leyline/game/CardData.kt \
        matchdoor/src/main/kotlin/leyline/game/PuzzleCardRegistrar.kt
git commit -m "feat(matchdoor): wire AbilityRegistry into CardData + PuzzleCardRegistrar

Registry built at card registration time, stored on CardData.
Enables lookup from Forge trait IDs to abilityGrpId slots.
Refs #160."
```

---

### Task 4: Fix mana action `firstOrNull()` with registry lookup

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/mapper/ActionMapper.kt:222-224,293-294`

- [ ] **Step 1: Fix `buildActivateManaAction` (line 223)**

Replace:
```kotlin
val abilityGrpId = cardData?.abilityIds?.firstOrNull()?.first ?: 0
```

With:
```kotlin
val abilityGrpId = cardData?.registry?.forSpellAbility(sa.id)
    ?: cardData?.abilityIds?.firstOrNull()?.first
    ?: 0
```

The registry lookup uses the specific mana SpellAbility's Forge ID. Falls back to `firstOrNull()` when registry is null (non-puzzle CardData from DB).

Note: `sa` is `card.manaAbilities.first()` on line 224. Move the `sa` declaration before the `abilityGrpId` line so it's available.

- [ ] **Step 2: Fix `buildAutoTapSolution` (line 294)**

Replace:
```kotlin
val abilityGrpId = cardDataLookup(grpId)?.abilityIds?.firstOrNull()?.first ?: 0
```

With:
```kotlin
val cardData = cardDataLookup(grpId)
val abilityGrpId = cardData?.registry?.forSpellAbility(sa.id)
    ?: cardData?.abilityIds?.firstOrNull()?.first
    ?: 0
```

Where `sa` is the mana SpellAbility from the inner loop at line 287.

- [ ] **Step 3: Run existing tests to verify no regression**

Run: `just test-one ActionFieldConformanceTest`
Expected: PASS (basic lands still get correct abilityGrpId via registry)

Run: `just test-one ActionMapperPureTest`
Expected: PASS

- [ ] **Step 4: Run `just fmt` and commit**

```bash
just fmt
git add matchdoor/src/main/kotlin/leyline/game/mapper/ActionMapper.kt
git commit -m "fix(matchdoor): mana action abilityGrpId via registry lookup

Replace firstOrNull() with AbilityRegistry.forSpellAbility() for
ActivateMana and AutoTapSolution builders. Correct abilityGrpId on
cards where first slot is a keyword, not the mana ability.
Falls back to firstOrNull when registry unavailable.
Refs #160."
```

---

### Task 5: Generalize sourceAbilityGrpId resolver

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/StateMapper.kt:446-470`

- [ ] **Step 1: Replace keyword-hardcoded resolver with registry-based lookup**

The current `buildSourceAbilityResolver` scans for Prowess keyword only. Replace with a registry lookup using `staticId` from the boost table.

The `staticId` in `EffectTracker.BoostEntry` is `cell.columnKey` from Forge's `ptBoostTable` — this is a `StaticAbility.getId()`. The registry maps it to abilityGrpId.

Replace the `buildSourceAbilityResolver` method:

```kotlin
/**
 * Build a resolver: cardInstanceId → sourceAbilityGRPID.
 *
 * Uses AbilityRegistry to map the effect's staticId (from ptBoostTable)
 * to the source ability's abilityGrpId. Falls back to keyword lookup
 * when registry is unavailable.
 */
private fun buildSourceAbilityResolver(
    battlefieldCards: List<Pair<Int, String>>,
    cardDataLookup: (String) -> CardData?,
    currentBoosts: Map<Int, List<EffectTracker.BoostEntry>>,
): (Int) -> Int? {
    val instanceIdToName = battlefieldCards.toMap()

    // Pre-build: for each instanceId with active boosts, find the
    // abilityGrpId of the FIRST boost's static ability via registry.
    val resolved = mutableMapOf<Int, Int>()
    for ((instanceId, entries) in currentBoosts) {
        if (entries.isEmpty()) continue
        val name = instanceIdToName[instanceId] ?: continue
        val cardData = cardDataLookup(name) ?: continue

        // Try registry first — maps staticId directly
        val registry = cardData.registry
        if (registry != null) {
            val staticId = entries.first().staticId.toInt()
            val grpId = registry.forStaticAbility(staticId)
            if (grpId != null) {
                resolved[instanceId] = grpId
                continue
            }
        }

        // Fallback: keyword lookup (original Prowess path)
        for (keyword in PT_BOOST_KEYWORDS) {
            cardData.keywordAbilityGrpIds[keyword]?.let {
                resolved[instanceId] = it
            }
        }
    }

    return { instanceId -> resolved[instanceId] }
}
```

- [ ] **Step 2: Update the call site in `buildFromGame`**

The call at line 398 needs to pass `currentBoosts`:

```kotlin
val sourceAbilityResolver = buildSourceAbilityResolver(battlefieldCards, { name ->
    bridge.cards.findByName(name)
}, boosts)  // boosts = bridge.snapshotBoosts() result
```

Check what the current variable name is for the snapshot result and pass it through.

- [ ] **Step 3: Run effect lifecycle tests**

Run: `just test-one EffectLifecycleTest`
Expected: PASS

Run: `just test-one AnnotationPipelineTest`
Expected: PASS

- [ ] **Step 4: Run `just fmt` and commit**

```bash
just fmt
git add matchdoor/src/main/kotlin/leyline/game/StateMapper.kt
git commit -m "feat(matchdoor): registry-based sourceAbilityGrpId resolver

Replace Prowess-only keyword lookup with AbilityRegistry.forStaticAbility().
Maps any effect's staticId to the source ability's abilityGrpId.
Enables correct VFX for equipment buffs, aura grants, lord effects.
Falls back to keyword lookup when registry unavailable.
Refs #160."
```

---

### Task 6: Conformance test — multi-ability abilityGrpId on actions

**Files:**
- Modify: `matchdoor/src/test/kotlin/leyline/conformance/ActionFieldConformanceTest.kt`

- [ ] **Step 1: Add planeswalker action field test**

Add a new test case in `ActionFieldConformanceTest`:

```kotlin
test("Activate action fields — planeswalker loyalty abilities get distinct abilityGrpIds") {
    val (b, game, _) = base.startWithBoard { _, human, _ ->
        // Liliana of the Veil: 3 loyalty abilities
        base.addCard("Swamp", human, ZoneType.Battlefield)
        base.addCard("Swamp", human, ZoneType.Battlefield)
        base.addCard("Swamp", human, ZoneType.Battlefield)
        base.addCard("Liliana of the Veil", human, ZoneType.Battlefield)
    }

    val actions = ActionMapper.buildActions(game, 1, b)
    val activateActions = actions.actionsList
        .filter { it.actionType == ActionType.Activate_add3 }

    assertSoftly {
        // Liliana should have 3 Activate actions (one per loyalty ability)
        activateActions.size shouldBe 3

        // Each should have a non-zero, distinct abilityGrpId
        for (a in activateActions) {
            a.abilityGrpId shouldNotBe 0
            a.instanceId shouldNotBe 0
            a.grpId shouldNotBe 0
        }

        val grpIds = activateActions.map { it.abilityGrpId }
        grpIds.toSet().size shouldBe 3
    }
}
```

- [ ] **Step 2: Run test**

Run: `just test-one ActionFieldConformanceTest`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add matchdoor/src/test/kotlin/leyline/conformance/ActionFieldConformanceTest.kt
git commit -m "test(conformance): planeswalker 3 distinct abilityGrpIds on Activate actions

Verifies each loyalty ability gets its own abilityGrpId in the action
proto, not all sharing the first slot. Refs #160."
```

---

### Task 7: Test gate + issue update

- [ ] **Step 1: Run full test gate**

Run: `just test-gate`
Expected: All green.

- [ ] **Step 2: Run integration tests**

Run: `just test-integration`
Note failures — pre-existing combat regressions are known. New failures = investigate.

- [ ] **Step 3: Update issue #160**

Comment with:
- What landed: AbilityRegistry, mana fix, sourceAbilityGrpId generalization
- What's deferred: trigger tracking (triggers not in abilityIds), AbilityInstanceCreated grpId
- Test evidence: AbilityRegistryTest, ActionFieldConformanceTest results

- [ ] **Step 4: Update systems map**

In `docs/systems-map.md`, update the Targeting/Interaction row or Continuous Effects row to reflect that sourceAbilityGrpId now works beyond Prowess.

---

## Unknowns flagged during planning

1. **Forge Card API names** — The plan uses `card.keywordInstances`, `kwInstance.abilities`, `kwInstance.triggers`, `kwInstance.staticAbilities`. These names are from the issue description, not verified against Forge source (`forge/forge-game/src/main/java/forge/game/card/Card.java`). Task 2 Step 3 explicitly gates on verifying these. If they don't exist, the implementer must read the Forge Card class and adapt.

2. **Mana abilities without dedicated slots** — AbilityIdDeriver doesn't allocate slots for mana abilities. The registry maps mana SpellAbilities to slot 0 as a fallback. This is correct for basic lands (slot 0 = mana) but imperfect for cards like Llanowar Elves with a keyword + mana ability. The wire data shows mana actions carry abilityGrpId, so this needs validation against recordings once proxy captures include Llanowar Elves or similar.

3. **Non-intrinsic traits** — The registry skips `isIntrinsic = false` traits (granted by equipment/auras). This is correct for now — granted abilities belong to the granting card's registry, not the receiving card. But verify this assumption holds when effects from auras show up in ptBoostTable.
