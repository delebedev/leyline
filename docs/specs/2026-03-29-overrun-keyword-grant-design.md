# Overrun / Keyword Grant Infrastructure — Design Spec

## Problem

When Overrun resolves, creatures get +3/+3 and trample until end of turn. The P/T boost works (EffectTracker handles it). But the keyword grant is invisible — no `AddAbility` pAnn, no `uniqueAbilities` update on creature gameObjects, no `LayeredEffectCreated` for the keyword layer. The client shows the stat bump but not the trample badge.

This blocks #31 (keyword-granting spells) and affects every card that grants keywords: Overrun, Angelic Destiny, equipment, lord effects.

## Wire shape (from card spec, sessions `16-55-19` + `17-04-26`)

Per Overrun cast, the real server emits **two** LayeredEffects:

**Layer 1 — P/T pump** (already works):
- `LayeredEffectCreated` transient: `affectorId=<spell_iid>`, `affectedIds=[effectId]`
- `ModifiedPower+ModifiedToughness+LayeredEffect` pAnn per creature: `power=3, toughness=3, effect_id`
- `PowerToughnessModCreated` transient per creature

**Layer 2 — Keyword grant** (missing):
- `LayeredEffectCreated` transient: `affectorId=<spell_iid>`, `affectedIds=[effectId]`
- Single `AddAbility+LayeredEffect` pAnn:
  - `affectedIds = [all creature iids]` (flat list, not one-per-creature)
  - `grpId = 14` (trample)
  - `effect_id` linking to the keyword LayeredEffect
  - `originalAbilityObjectZcid = <spell_iid>`
  - One `UniqueAbilityId` int per creature in a repeated field
- Each creature's `gameObject` gains `uniqueAbilities { id: N; grpId: 14 }`

**EOT cleanup:** Both `LayeredEffectDestroyed` fire in one diff at end step. Creature gameObjects lose the `uniqueAbilities` entry.

## Design

### Key decision: how to detect keyword grants

Forge fires `GameEventCardStatsChanged` (bulk, no per-card keyword detail) when `PumpAllEffect` resolves. Two options:

**A. Forge fork — add per-card keyword event** (recommended)

Add `GameEventExtrinsicKeywordAdded(card, keyword, timestamp)` to Forge, fired from `Card.addChangedCardKeywords()`. Clean event-driven pipeline, same pattern as existing events.

**B. State diff — compare extrinsic keywords before/after**

At diff-build time, compare each card's `getExtrinsicKeywords()` against previous snapshot. Infer additions/removals. Fragile — depends on snapshot timing, no source card attribution.

**Going with A** — it's the same pattern we use for everything else (event-driven), and the Forge fork is ours to modify.

### Implementation layers

**Layer 0 — Data registration**

Register keyword grpIds in a new `KeywordGrpIds` object (separate from `KeywordQualifications` which is annotation-specific):

```kotlin
object KeywordGrpIds {
    private val table = mapOf(
        "Flying" to 8,
        "First Strike" to 6,
        "Trample" to 14,
        "Vigilance" to 15,
        "Lifelink" to 12,
        "Reach" to 13,
        "Menace" to 142,
    )
    fun forKeyword(keyword: String): Int? = table[keyword]
}
```

**Layer 1 — Forge event**

In our Forge fork, add to `Card.addChangedCardKeywords()`:
```java
game.fireEvent(new GameEventExtrinsicKeywordAdded(this, keyword, timestamp));
```

Subscribe in `GameEventCollector`:
```kotlin
data class KeywordGranted(
    val cardId: ForgeCardId,
    val keyword: String,
    val timestamp: Long,
) : GameEvent
```

**Layer 2 — EffectTracker keyword support**

Extend EffectTracker to track keyword grants alongside P/T boosts:
- `KeywordEntry(timestamp, keyword, grpId)` per card
- `diffKeywords()` — compare current vs tracked → emit created/destroyed
- Allocate effect IDs from the same counter as P/T (they share the ID space)

**Layer 3 — Annotation emission**

In `mechanicAnnotations()` or a new `effectAnnotations()` stage:
- Group keyword grants by (keyword, timestamp, source) → one `AddAbility` pAnn per keyword type
- Emit `LayeredEffectCreated` transient with allocated effect ID
- Emit `AddAbility+LayeredEffect` persistent annotation:
  - `affectedIds` = all granted creature iids
  - `grpId` = keyword grpId from `KeywordGrpIds`
  - `uniqueAbilityIds` = one sequential ID per creature (coordinate with `CardProtoBuilder` seqId)
  - `effect_id` = allocated effect ID
  - `originalAbilityObjectZcid` = source spell iid

**Layer 4 — uniqueAbilities on gameObject**

This should work automatically. StateMapper's snapshot-diff rebuilds creature gameObjects from Forge state. If Forge's `Card` object has the extrinsic keyword, `CardProtoBuilder` needs to include it in `uniqueAbilities`. Currently it only reads static card DB abilities.

Fix: in `CardProtoBuilder.buildObjectInfo()`, also iterate `card.getExtrinsicKeywords()` and append `UniqueAbilityInfo` entries with the keyword's grpId.

**Layer 5 — EOT cleanup**

EffectTracker's `diffKeywords()` handles this: when the keyword expires at EOT (Forge removes it from the card), the diff detects the removal and emits `LayeredEffectDestroyed`.

### What this unblocks

| Card | What works after |
|------|-----------------|
| Overrun | Full wire: P/T pump + trample badge + EOT cleanup |
| Angelic Destiny | Keyword grant (flying + first strike) — persistent, no EOT cleanup |
| Sire of Seven Deaths | uniqueAbilities display for all 7 keywords |
| Any lord / equipment / aura granting keywords | Same pipeline |

### Files touched

| File | Change |
|------|--------|
| `game/KeywordGrpIds.kt` | New — keyword name → grpId mapping |
| `forge/.../Card.java` | Fire `GameEventExtrinsicKeywordAdded` |
| `forge/.../GameEventExtrinsicKeywordAdded.java` | New Forge event class |
| `bridge/GameEventCollector.kt` | Subscribe, emit `GameEvent.KeywordGranted` |
| `game/GameEvent.kt` | Add `KeywordGranted` variant |
| `game/EffectTracker.kt` | Keyword tracking parallel to P/T boosts |
| `game/AnnotationBuilder.kt` | Multi-creature `addAbility()` overload |
| `game/AnnotationPipeline.kt` | Handler in `mechanicAnnotations()` |
| `game/CardProtoBuilder.kt` | Include extrinsic keywords in `uniqueAbilities` |
| Puzzle file | `keyword-grant-overrun.pzl` |

### Test plan

**Puzzle:** 3 creatures on BF + Overrun in hand + 5 forests.
- Cast Overrun → verify all 3 creatures get +3/+3 AND trample uniqueAbility
- Pass to end step → verify both LayeredEffectDestroyed fire, uniqueAbilities removed

**MatchFlowHarness integration test:**
- Cast Overrun → assert `AddAbility` pAnn has correct affectedIds count
- Assert `LayeredEffectCreated` count = 2 (P/T + keyword)
- Assert each creature gameObject has `uniqueAbilities` with `grpId=14`
- Pass to EOT → assert `LayeredEffectDestroyed` count = 2

### Risks

1. **Forge event timing** — `addChangedCardKeywords` may fire before `addPTBoost` completes. Need both in the same diff. Verify event ordering.
2. **uniqueAbilityId coordination** — IDs must not collide with static card abilities. Use a high starting value (e.g., 1000+) for extrinsic grants.
3. **Multiple keyword types** — Overrun grants one keyword (trample). Cards granting multiple keywords (Angelic Destiny: flying + first strike) need one `AddAbility` pAnn per keyword type, each with its own effect ID.
