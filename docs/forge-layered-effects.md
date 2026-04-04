---
summary: "How Forge represents continuous/layered effects (CR 613): layer enum, Table<timestamp, staticAbilityId, Value> pattern, and differ strategy."
read_when:
  - "implementing LayeredEffect annotations or P/T tracking"
  - "understanding how Forge computes continuous effects across layers"
  - "debugging lord effects, keyword grants, or stat changes"
---
# Forge Layered Effects System

How Forge represents and computes continuous/layered effects (CR 613).
Written for the leyline differ -- detecting when effects appear/disappear between game states.

## Layer System

`StaticAbilityLayer` enum defines the MTG layer order:

| Layer | Enum              | Purpose                                      |
|-------|--------------------|----------------------------------------------|
| 1     | `COPY`             | Copiable values                              |
| 2     | `CONTROL`          | Control-changing effects                     |
| 3     | `TEXT`             | Text-changing effects                        |
| 4     | `TYPE`             | Type-changing effects                        |
| 5     | `COLOR`            | Color-changing effects                       |
| 6     | `ABILITIES`        | Ability granting/removing                    |
| 7a    | `CHARACTERISTIC`   | Characteristic-defining P/T (e.g. Tarmogoyf)|
| 7b    | `SETPT`            | P/T setting effects (e.g. "becomes a 3/3")   |
| 7c    | `MODIFYPT`         | P/T modifying effects (e.g. +2/+2)           |
| 8     | `RULES`            | Game rule modifications                      |

File: `forge/forge-game/src/main/java/forge/game/staticability/StaticAbilityLayer.java`

## The Table<Long, Long, Value> Pattern

All per-card modification data uses Guava `TreeBasedTable<timestamp, staticAbilityId, Value>`:

```
Row key    = timestamp (long) -- monotonic game clock from Game.getNextTimestamp()
Column key = staticAbilityId (long) -- StaticAbility.getId(), or 0 for resolved spell effects
Value      = the modification data
```

This is **the** key data structure for diffing. Each cell uniquely identifies one effect application.

### P/T Tables on Card

```java
// Card.java, lines 273-276
private Table<Long, Long, Pair<Integer,Integer>> newPTText;               // Layer 3
private Table<Long, Long, Pair<Integer,Integer>> newPTCharacterDefining;  // Layer 7a
private Table<Long, Long, Pair<Integer,Integer>> newPT;                   // Layer 7b (SetPT)
private Table<Long, Long, Pair<Integer,Integer>> boostPT;                // Layer 7c (ModifyPT)
```

Each `Pair<Integer, Integer>` = `(power, toughness)`.

- **newPT** tables: last-write-wins semantics. `getCurrentPower()` iterates all entries; each non-null value **replaces** the running total.
- **boostPT**: additive. `getTempPowerBoost()` **sums** all entries' left values.

### Keyword Tables on Card

```java
// Card.java, lines 129-131
private Table<Long, Long, IKeywordsChange> changedCardKeywordsByText;     // Layer 3
private KeywordsChange changedCardKeywordsByWord;                         // Layer 3 word changes
private Table<Long, Long, KeywordsChange> changedCardKeywords;            // Layer 6
```

### Type Tables on Card

```java
// Card.java, lines 124-126
private Table<Long, Long, ICardChangedType> changedCardTypesByText;                // Layer 3
private Table<Long, Long, ICardChangedType> changedCardTypesCharacterDefining;     // Layer 4 CDA
private Table<Long, Long, ICardChangedType> changedCardTypes;                      // Layer 4
```

## P/T Computation Chain

```
getBasePower()           -- printed value from CardState
  + getCurrentPower()    -- applies all SetPT overrides (newPTText, newPTCharacterDefining, newPT)
                            last non-null value wins
  + getTempPowerBoost()  -- sum of all boostPT entries
  + getPowerBonusFromCounters() -- +1/+1, -1/-1, etc. counters

= getUnswitchedPower()   -- StatBreakdown(currentValue, tempBoost, bonusFromCounters)

getNetPower()            -- applies P/T switching keyword if present
```

### StatBreakdown

```java
public static class StatBreakdown {
    public final int currentValue;     // base + SetPT overrides
    public final int tempBoost;        // sum of all boostPT entries
    public final int bonusFromCounters; // from +1/+1 etc. counters
    public int getTotal() { return currentValue + tempBoost + bonusFromCounters; }
}
```

This is useful for the differ -- you can distinguish whether a P/T change came from a new boost, a counter, or a SetPT override.

## Two Effect Sources

### 1. Continuous (Static Abilities) -- recalculated every check

Source: `StaticAbilityContinuous.applyContinuousAbility()`

- Stored with `timestamp = stAb.getTimestamp()`, `staticId = stAb.getId()`
- Removed and reapplied every time `GameAction.checkStaticAbilities()` runs
- The `StaticEffects` registry maps `StaticAbility -> StaticEffect` (tracks affected cards/players)
- Identity: `StaticAbility` has a stable `id` (monotonic int from `nextId()`)

Example: A lord giving +1/+1 to all creatures:
```
boostPT.put(lordAbility.getTimestamp(), lordAbility.getId(), Pair.of(1, 1))
```

### 2. Resolved Spell Effects (Pump/Animate) -- set once, cleaned up by GameCommand

Source: `PumpEffect.applyPump()`, `AnimateEffectBase`

- Stored with `timestamp = game.getNextTimestamp()`, `staticId = 0`
- Cleanup registered via `addUntilCommand()` (end of turn, leave play, etc.)
- The cleanup GameCommand calls `removePTBoost(timestamp, 0)` etc.
- **staticId is always 0** for these -- no StaticAbility object exists

Example: Giant Growth resolving:
```
gameCard.addPTBoost(3, 3, timestamp, 0)
// later, end of turn:
gameCard.removePTBoost(timestamp, 0)
```

## checkStaticAbilities -- The Full Recalculation

`GameAction.checkStaticAbilities()` is the engine's continuous-effect recalculation. Called frequently (every state-based check, zone change, etc.).

1. **Clear** all existing static effects via `StaticEffects.clearStaticEffects()` -- removes all continuous-effect modifications from cards
2. **Collect** all StaticAbilities with `Continuous` mode from every card in the game
3. **Sort** by timestamp order
4. **Apply** layer by layer (COPY through RULES), resolving dependencies (CR 613.8)
5. **Fire** `GameEventCardStatsChanged` with affected card list

The clear-and-reapply cycle means that between any two priority passes, the full set of active continuous effects is recomputed from scratch. This is significant for diffing: you don't need to track effect add/remove events -- just snapshot the tables.

## Stable Identity for Effects

### Continuous effects (from StaticAbility):
- `StaticAbility.getId()` -- stable int, unique per ability instance
- `StaticAbility.getHostCard()` -- the source card
- The `(timestamp, staticAbilityId)` pair in the Table is the compound key
- Can trace: table entry -> staticAbilityId -> StaticAbility -> hostCard

### Resolved spell effects:
- `staticId = 0` always
- `timestamp` is the only differentiator
- Cannot directly trace back to source card without external bookkeeping
- The timestamp was generated at resolution time from `Game.getNextTimestamp()`

## GameEventCardStatsChanged

Fired after `checkStaticAbilities()` completes. Contains a `Collection<Card>` of all affected cards.

```java
public record GameEventCardStatsChanged(Collection<Card> cards, boolean transform) implements GameEvent
```

- Carries **which** cards changed, but **not what** changed on them
- No before/after delta -- just "these cards' stats are now different"
- The `transform` flag is effectively always false (disabled in constructor)

## Key Files

| File | Role |
|------|------|
| `forge/forge-game/src/main/java/forge/game/staticability/StaticAbilityLayer.java` | Layer enum |
| `forge/forge-game/src/main/java/forge/game/staticability/StaticAbility.java` | Static ability identity, id, timestamp |
| `forge/forge-game/src/main/java/forge/game/staticability/StaticAbilityContinuous.java` | Applies continuous effects per layer |
| `forge/forge-game/src/main/java/forge/game/StaticEffect.java` | Tracks affected cards, removal logic per layer |
| `forge/forge-game/src/main/java/forge/game/StaticEffects.java` | Registry: StaticAbility -> StaticEffect |
| `forge/forge-game/src/main/java/forge/game/GameAction.java` | `checkStaticAbilities()` -- full recalculation |
| `forge/forge-game/src/main/java/forge/game/card/Card.java` | P/T tables, keyword tables, StatBreakdown |
| `forge/forge-game/src/main/java/forge/game/ability/effects/PumpEffect.java` | Resolved +X/+X effects |
| `forge/forge-game/src/main/java/forge/game/event/GameEventCardStatsChanged.java` | Stats-changed event |
