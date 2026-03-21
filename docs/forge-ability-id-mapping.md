# Forge Ability ID Mapping

How Forge identifies abilities internally, and how to map those IDs to Arena's `abilityGrpId`.

## Arena's abilityGrpId model

Arena assigns each ability on a card a unique integer (`abilityGrpId`) stored in the client's card database. These IDs appear in:

- `UniqueAbilityInfo` on card objects (ability slots the client renders)
- `Action.abilityGrpId` on activated/mana ability actions
- `sourceAbilityGRPID` detail key on `LayeredEffect` annotations (drives VFX — Prowess glow vs generic buff)
- `AbilityInstanceCreated` annotations (which ability triggered)
- `TargetSpec` annotations
- `UserActionTaken` annotations
- `ManaInfo.abilityGrpId` / `ManaSelection.abilityGrpId` in mana payment

The client DB stores abilities as ordered pairs: `"abilityGrpId:textId"` (e.g. `"1005:227393 2010:300000"`). The positional order matches the card's text layout — ability slot 0 is the first ability printed on the card.

## Forge's internal ability ID system

### Three independent ID counters

Forge has three trait types, each with its own global auto-increment counter:

| Type | Class | Counter | ID method |
|------|-------|---------|-----------|
| SpellAbility | `SpellAbility` | `private static int maxId` | `getId()` → int |
| StaticAbility | `StaticAbility` | `private static int maxId` | `getId()` → int |
| Trigger | `Trigger` | `private static int maxId` | `getId()` → int |

All three extend `CardTraitBase`, which provides `getHostCard()` → the source `Card`.

IDs are assigned at construction time via `++maxId` (global, not per-card). They are **not stable across game instances** — a fresh game allocates IDs from 1 again. Within a game, each ID is unique per type but the same integer can appear in different type namespaces.

### Keyword expansion

Keywords like Prowess, Flying, Lifelink decompose into sub-abilities:

```
KeywordInstance.createTraits(card):
  → CardFactoryUtil.addTriggerAbility()    // trigger sub-abilities
  → CardFactoryUtil.addSpellAbility()      // activated sub-abilities
  → CardFactoryUtil.addStaticAbility()     // static sub-abilities
```

Each expanded sub-ability gets its own `StaticAbility.id` / `Trigger.id` / `SpellAbility.id`.

A keyword's `StaticAbility` (e.g. Prowess's "gets +1/+1" continuous effect) has a **different ID than the Trigger** that fires it. The `StaticAbility.getId()` is what lands in `ptBoostTable`.

### CardState stores abilities in FCollections

```java
// CardState.java
private final FCollection<SpellAbility> abilities = new FCollection<>();
private FCollection<Trigger> triggers = new FCollection<>();
private FCollection<StaticAbility> staticAbilities = new FCollection<>();
```

`getSpellAbilities()`, `getStaticAbilities()`, `getTriggers()` each return a **dynamically computed** list: intrinsic abilities + changed traits (from other effects) + keyword-expanded abilities. The base intrinsic order comes from card script parsing.

## Card construction order (readCardFace)

`CardFactory.readCardFace()` populates abilities in this order:

```java
// CardFactory.java:373-381
for (String r : face.getReplacements())      // 1. ReplacementEffects
    c.addReplacementEffect(...)
for (String s : face.getStaticAbilities())    // 2. StaticAbilities (printed)
    c.addStaticAbility(s)
for (String t : face.getTriggers())           // 3. Triggers (printed)
    c.addTrigger(...)
c.addIntrinsicKeywords(face.getKeywords())    // 4. Keywords → expand to sub-traits

// Then later:
CardFactoryUtil.addAbilityFactoryAbilities(c, face.getAbilities())  // 5. Activated abilities
```

This means the **intrinsic order** is: replacement effects, then static abilities, then triggers, then keyword expansions, then activated abilities. This is the same order every time the same card is created — it is **deterministic for a given card script**.

However, this is NOT the same order Arena uses. Arena's ability slot ordering matches the card's printed text top-to-bottom. Forge's ordering splits by trait type, then appends keywords.

## ptBoostTable call chain

### Continuous effects (anthems, auras, static P/T mods)

```
GameAction.checkStaticAbilities()
  → StaticAbilityContinuous.applyContinuousAbility(stAb, affectedCards, layer)
    → at MODIFYPT layer:
        affectedCard.addPTBoost(powerBonus, toughnessBonus,
            se.getTimestamp(),   // StaticEffect timestamp (game timestamp)
            stAb.getId())        // StaticAbility.id (global auto-increment int)
```

**Key**: `staticId` in ptBoostTable = `StaticAbility.getId()` = the continuous-mode `StaticAbility` that produces the P/T modification.

### Resolved spells (Giant Growth, pump effects)

```
PumpEffect.resolve()
  → gameCard.addPTBoost(a, d, timestamp, 0)   // staticId = 0 (always)

PumpAllEffect.resolve()
  → tgtC.addPTBoost(a, d, timestamp, 0)       // staticId = 0 (always)
```

Resolved spell pump effects always use `staticId = 0`. They cannot be traced back to a specific ability via the ptBoostTable alone.

### Perpetual effects

```
PerpetualPTBoost.apply()
  → c.addPTBoost(power, toughness, timestamp, 0)  // staticId = 0
```

Also uses `staticId = 0`.

### Summary: ptBoostTable key interpretation

| Source | timestamp | staticId | Traceable? |
|--------|-----------|----------|------------|
| Continuous effect (anthem, aura) | Game timestamp | `StaticAbility.getId()` | Yes — can find source card + ability |
| Resolved spell (Giant Growth) | Spell's timestamp | `0` | No — only know when, not which ability |
| Perpetual effect | Effect timestamp | `0` | No |

## Navigating from staticId to source card

For continuous effects (staticId != 0), the path is:

```
staticId (Long in ptBoostTable)
  → StaticAbility with matching getId()
    → stAb.getHostCard()     // the Card that has this ability
      → card.getStaticAbilities()  // all static abilities on that card
        → indexOf(stAb)       // position among static abilities
```

There is **no global lookup** function `Game.findStaticAbilityById(int)`. To find a `StaticAbility` by its `id`, you must scan all cards in all zones:

```kotlin
fun findStaticAbilityById(game: Game, id: Int): StaticAbility? {
    for (player in game.players) {
        for (zone in ZoneType.values()) {
            for (card in player.getZone(zone).cards) {
                for (stAb in card.staticAbilities) {
                    if (stAb.id == id) return stAb
                }
            }
        }
    }
    return null
}
```

This is expensive. The StaticEffects system (`Game.getStaticEffects()`) maps `StaticAbility → StaticEffect`, but there is no reverse index from int ID.

## Current leyline approach and its limitations

### Ability ID derivation (CardDataDeriver / PuzzleCardRegistrar)

Both use the same logic:

```kotlin
private fun deriveAbilityIds(card: Card): List<Pair<Int, Int>> {
    val keywordCount = card.rules?.mainPart?.keywords?.count() ?: 0
    val spellAbilityCount = maxOf(0, (card.spellAbilities?.size ?: 1) - 1) // minus cast spell
    val totalCount = maxOf(1, keywordCount + spellAbilityCount)
    return (0 until totalCount).map { nextAbilityGrpId.getAndIncrement() to 0 }
}
```

Problems:
1. **No correlation back**: the assigned `abilityGrpId` values are sequential counters with no link to which Forge ability they represent
2. **Count is approximate**: it counts keywords and non-mana spell abilities but ignores static abilities, triggers, and replacement effects as independent slots
3. **Order doesn't match**: Arena orders by printed text position; this counts by type

### Workarounds in existing code

1. **ActionMapper** (line 82-83): `cardData.abilityIds.firstOrNull()` — always uses first ability for every activated ability on the card. Works for single-ability cards; breaks for multi-ability cards.

2. **ActionMapper** (line 181, 249): Same `.firstOrNull()` pattern for mana abilities.

3. **MatchSession** (line 248-251): `val abilityIndex = 0` — hardcoded to first activated ability. TODO comment acknowledges the gap.

4. **AnnotationPipeline** (line 445-451): `layeredEffect()` call omits `sourceAbilityGrpId` entirely — the parameter exists but is never populated.

5. **EffectTracker**: Uses `(cardInstanceId, timestamp, staticId)` fingerprint for diffing lifecycle. The `staticId` is carried through but never mapped to `abilityGrpId`.

## Recommended approach

### Build a per-card ability registry at card instantiation time

When a Forge `Card` is created and registered in the bridge (either from client DB or synthetic derivation), build a mapping from each Forge trait ID to an abilityGrpId slot index.

```kotlin
data class AbilitySlot(
    val slotIndex: Int,          // 0-based position in abilityIds list
    val abilityGrpId: Int,       // from CardData.abilityIds[slotIndex].first
    val forgeTraitIds: Set<Int>, // SpellAbility/Trigger/StaticAbility IDs that belong to this slot
)

class AbilityRegistry(private val card: Card, private val cardData: CardData) {
    private val slots: List<AbilitySlot>
    private val staticIdToSlot: Map<Int, AbilitySlot>
    private val triggerIdToSlot: Map<Int, AbilitySlot>
    private val spellAbilityIdToSlot: Map<Int, AbilitySlot>
}
```

### Slot assignment strategy

The core challenge: matching Forge's type-segregated trait model to Arena's linear ability slot model. Arena ability slots correspond 1:1 with the card's printed text lines. Forge's card scripts define abilities in a structured way but the ordering across types is:

1. Iterate the card's **intrinsic static abilities** (from `face.getStaticAbilities()`)
2. Iterate the card's **intrinsic triggers** (from `face.getTriggers()`)
3. Iterate keyword expansions (each keyword may produce sub-triggers, sub-statics, sub-spells)
4. Iterate **activated abilities** (from `face.getAbilities()`)

For cards with real Arena abilityGrpIds (from client DB), the slot count is known. For synthetic cards, we control the assignment.

**For continuous-effect tracing (the ptBoostTable problem)**:

When a `StaticAbility` is created on a card, record a mapping:

```
StaticAbility.getId() → (card.id, slotIndex)
```

This requires intercepting `StaticAbility` construction or scanning after card setup. The scan approach is simpler and doesn't require engine modification:

```kotlin
fun buildAbilityRegistry(card: Card, cardData: CardData): AbilityRegistry {
    // After card is fully constructed, scan its intrinsic traits
    val statics = card.currentState.staticAbilities  // intrinsic only
    val triggers = card.currentState.triggers
    val abilities = card.currentState.abilities       // spell abilities

    // Assign slot indices based on matching to cardData.abilityIds
    // For keywords: group the keyword's sub-traits under one slot
    // ...
}
```

### What this enables

1. **LayeredEffect annotations**: `staticId` from ptBoostTable → `AbilityRegistry.staticIdToSlot` → `abilityGrpId` → set `sourceAbilityGRPID` detail key. Prowess buffs get the correct Prowess abilityGrpId, driving correct VFX.

2. **Activate actions**: When iterating `card.spellAbilities` for activated ability actions, use `spellAbility.getId()` → `AbilityRegistry.spellAbilityIdToSlot` → `abilityGrpId`. Multi-ability cards (planeswalkers, modal cards) get correct ability identification.

3. **Trigger identification**: When a triggered ability fires, `SpellAbility.getSourceTrigger()` returns `Trigger.getId()`. Map through `triggerIdToSlot` → `abilityGrpId`.

4. **Inbound action dispatch**: Client sends `abilityGrpId` in `PerformActionResp`. Map to slot index → find the correct `SpellAbility` in the card's activated abilities list, instead of hardcoding index 0.

### Engine modifications: avoidable

No Forge engine changes are needed. All required information is accessible through existing public APIs:

- `StaticAbility.getId()` / `Trigger.getId()` / `SpellAbility.getId()` — all public
- `CardTraitBase.getHostCard()` — public
- `Card.getStaticAbilities()` / `getTriggers()` / `getSpellAbilities()` — public
- `CardTraitBase.isIntrinsic()` — distinguishes printed vs granted abilities
- `KeywordInterface.getStaticAbilities()` / `getTriggers()` / `getAbilities()` — public, lets us group sub-traits per keyword

The one limitation is there is no global `staticId → StaticAbility` index. Building one in the bridge is straightforward — either scan at registration time (static, fast) or maintain a map as cards enter/leave play (dynamic, more work).

### Complication: keyword sub-trait grouping

A keyword like Prowess creates:
- 1 Trigger (fires on noncreature spell cast)
- 1 SpellAbility (the triggered ability's effect — pump until EOT)

These are separate Forge objects with separate IDs, but Arena treats them as one abilityGrpId slot. The bridge needs to know which sub-traits belong to which keyword.

`KeywordInterface` exposes `getStaticAbilities()`, `getTriggers()`, `getAbilities()`, which return the sub-traits for that keyword. After `card.addIntrinsicKeywords()`, iterating `card.currentState.intrinsicKeywords` (the `KeywordCollection`) gives access to each `KeywordInterface` and its sub-traits. This is the grouping information.

### Complication: dynamic trait changes

When another card grants abilities (e.g., an Equipment granting Flying), new traits are added via the `changedCardTraits` system (timestamp-keyed). These don't get abilityGrpId slots — they aren't part of the card's printed text. They may appear in `getStaticAbilities()` results but `isIntrinsic()` returns false. The bridge should only map intrinsic traits.

For continuous effects from granted abilities (e.g., an Equipment's static +2/+2), the `staticId` in ptBoostTable comes from the **Equipment's** `StaticAbility`, not the equipped creature's. The `AbilityRegistry` for the Equipment card would have the mapping. `stAb.getHostCard()` returns the Equipment, and the registry lookup would use that card's data.

## Places in leyline that would benefit

| File | Line | Current workaround | What mapping enables |
|------|------|--------------------|---------------------|
| `ActionMapper.kt` | 82-84 | `cardData.abilityIds.firstOrNull()` for all activate actions | Correct per-ability `abilityGrpId` |
| `ActionMapper.kt` | 181 | `.firstOrNull()` for mana ability | Correct mana ability `abilityGrpId` |
| `ActionMapper.kt` | 249 | `.firstOrNull()` for auto-tap solution | Correct mana ability `abilityGrpId` |
| `MatchSession.kt` | 248-251 | `abilityIndex = 0` hardcoded | Correct ability dispatch for multi-ability cards |
| `AnnotationPipeline.kt` | 445 | `layeredEffect()` without `sourceAbilityGrpId` | Drives correct VFX (Prowess glow vs generic buff) |
| `AnnotationBuilder.kt` | 262-267 | `abilityInstanceCreated` without `abilityGrpId` | Which specific ability created the stack instance |
| `AnnotationBuilder.kt` | 520-528 | `abilityAdded` has `AbilityGrpId` param but callers may not populate it | Correct ability identification for granted abilities |

## Key files

### Forge engine (read-only)
- `forge/forge-game/src/main/java/forge/game/staticability/StaticAbility.java` — `getId()` (line 70), global counter `maxId` (line 54)
- `forge/forge-game/src/main/java/forge/game/staticability/StaticAbilityContinuous.java` — `addPTBoost(..., stAb.getId())` (line 702)
- `forge/forge-game/src/main/java/forge/game/ability/effects/PumpEffect.java` — `addPTBoost(a, d, timestamp, 0)` (line 62)
- `forge/forge-game/src/main/java/forge/game/card/Card.java` — `boostPT` table (line 276), `addPTBoost()` (line 4603)
- `forge/forge-game/src/main/java/forge/game/card/CardFactory.java` — `readCardFace()` ability ordering (lines 345-412)
- `forge/forge-game/src/main/java/forge/game/card/CardState.java` — `abilities`, `triggers`, `staticAbilities` FCollections (lines 83-86)
- `forge/forge-game/src/main/java/forge/game/keyword/KeywordInstance.java` — `createTraits()` expansion (lines 108-111)
- `forge/forge-game/src/main/java/forge/game/CardTraitBase.java` — `getHostCard()`, `isIntrinsic()`, base for all traits
- `forge/forge-game/src/main/java/forge/game/StaticEffect.java` — wraps `StaticAbility` when applied, carries `ability` reference + `timestamp`
- `forge/forge-game/src/main/java/forge/game/StaticEffects.java` — `StaticAbility → StaticEffect` map (line 42)

### Leyline bridge layer
- `matchdoor/src/main/kotlin/leyline/game/CardData.kt` — `abilityIds: List<Pair<Int, Int>>`
- `matchdoor/src/main/kotlin/leyline/game/EffectTracker.kt` — `BoostEntry(timestamp, staticId, ...)`, fingerprinting
- `matchdoor/src/main/kotlin/leyline/game/AnnotationPipeline.kt` — `effectAnnotations()` builds LayeredEffect without sourceAbilityGRPID
- `matchdoor/src/main/kotlin/leyline/game/mapper/ActionMapper.kt` — `.firstOrNull()` workaround throughout
- `matchdoor/src/main/kotlin/leyline/match/MatchSession.kt` — `abilityIndex = 0` hardcode (line 251)
- `matchdoor/src/main/kotlin/leyline/game/PuzzleCardRegistrar.kt` — synthetic ability ID assignment
- `matchdoor/src/test/kotlin/leyline/conformance/CardDataDeriver.kt` — test-only ability derivation
