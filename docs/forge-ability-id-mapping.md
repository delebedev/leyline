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

## Key Files

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
