# Annotation Ordering Within a GSM

Observed annotation ordering from real server recordings. Supplements the enforcement rules in `AnnotationOrderEnforcer` and the analysis in `arena-notes/leyline/conformance/message-framing.md`.

## Why ordering matters

The client's `AnnotationEventProcessor` processes annotations sequentially. Each parser sees all events emitted by prior parsers via a shared mutable `List<GameRulesEvent>`. The resulting `UXEvent` queue is a strict FIFO with `IsBlocking` gates — animation playback follows annotation order exactly.

Wrong ordering causes both **state bugs** (incremental entity chain broken) and **visual bugs** (death animation before damage animation).

## Existing rules (enforced)

Rules 1-2 are implemented in `AnnotationOrderEnforcer` and validated by `InvariantChecker`.

### Rule 1: ObjectIdChanged before references

`ObjectIdChanged` populates the client's `newIdToOldIdMap`. Any annotation referencing the new instanceId must come after.

### Rule 2: Same-card incremental entity chaining

When two `INeedList_Changes` parsers modify the same card, precedence order applies:

| Prec | Type | Card ID field |
|------|------|---------------|
| 0 | ControllerChanged | affectedIds |
| 1 | TappedUntappedPermanent | affectedIds |
| 2 | DamageDealt | affectedIds |
| 3 | CounterAdded/Removed | affectedIds |
| 4 | PowerToughnessModCreated | affectedIds |
| 5 | LayeredEffectCreated | affectorId |
| 6 | AttachmentCreated | affectorId |

## New rules (not yet enforced)

### Rule 5: DamageDealt before ZoneTransfer(death) for the same card

**Observed in:** Every combat damage GSM and spell-damage resolution GSM across 38 saved games. Zero exceptions.

**Pattern — combat damage (gsId 309, game 2026-03-30_20-06):**
```
[662] PhaseOrStepModified
[663] DamageDealt         affected=[296]  (Kellan takes 3)
[664] DamageDealt         affected=[300]  (Hurloon takes 3)
      ...LayeredEffectDestroyed (cleanup)...
[673] ObjectIdChanged     affected=[296]  (296→343)
[674] ZoneTransfer        affected=[343]  (Battlefield→Graveyard, SBA_Damage)
[677] ObjectIdChanged     affected=[300]  (300→344)
[678] ZoneTransfer        affected=[344]  (Battlefield→Graveyard, SBA_Damage)
```

**Pattern — spell damage kill (gsId 202, same game):**
```
[453] ResolutionStart
[454] DamageDealt         affected=[313]  (creature takes lethal)
[456] ResolutionComplete
[457] ObjectIdChanged     affected=[321]  (spell 321→327)
[458] ZoneTransfer        affected=[327]  (Stack→Graveyard, Resolve)
[459] ObjectIdChanged     affected=[313]  (creature 313→328)
[460] ZoneTransfer        affected=[328]  (Battlefield→Graveyard, SBA_Damage)
```

**Pattern — combat damage kill, second game (gsId 154, game 2026-03-30_20-33):**
```
[314] PhaseOrStepModified
[315] DamageDealt         affected=[296]
[316] DamageDealt         affected=[301]
[319] DamageDealt         affected=[1]    (player damage)
[320] SyntheticEvent      affected=[1]
[321] ModifiedLife        affected=[1]
[322] ModifiedLife        affected=[2]
[323] ObjectIdChanged     affected=[296]
[324] ZoneTransfer        affected=[319]  (Battlefield→Graveyard)
```

**The rule:** All `DamageDealt` annotations precede all `ObjectIdChanged`/`ZoneTransfer` annotations for creatures dying from that damage.

**Visual impact:** Without this rule, the client queues `ZoneTransferUXEvent` (card moves to graveyard) before `UXEventDamageDealt` (damage projectile/combat animation). The creature visually dies before the hit lands.

**State impact:** `DamageDealtAnnotationParser` implements `INeedList_Changes` and calls `GetPreviousIncrementalEntity` to find the card entity. If `ZoneTransfer` already processed the card, the entity may be in graveyard state.

### Rule 6: Full combat damage ordering

The complete observed order within a combat damage GSM:

```
PhaseOrStepModified (CombatDamage step)
DamageDealt (one per source→target pair, all of them)
DamagedThisTurn (badge, if creature survived)
SyntheticEvent (if player took damage)
ModifiedLife (one per player whose life changed)
LayeredEffectDestroyed (cleanup for dying creatures' effects)
ObjectIdChanged (per dying creature)
ZoneTransfer (per dying creature, SBA_Damage)
AbilityInstanceDeleted (cleanup for dying creatures' abilities)
```

### Rule 7: Resolution ordering

The complete observed order within a spell resolution GSM:

```
ResolutionStart (spell affectorId)
DamageDealt (if damage spell, per target)
SyntheticEvent (if player took damage)
ModifiedLife (if life changed)
ResolutionComplete (spell affectorId)
ObjectIdChanged (spell leaving stack)
ZoneTransfer (spell Stack→Graveyard, category=Resolve)
ObjectIdChanged (killed creature, if any)
ZoneTransfer (creature Battlefield→Graveyard, category=SBA_Damage)
```

Note: spell's OIC/ZT comes before killed creature's OIC/ZT. The spell resolves and leaves the stack, then SBA processes the lethal damage.

## Current leyline gap

Our annotation pipeline in `StateMapper.computeAnnotations()` builds:
1. Stage 1-2: `TransferAnnotations` — ZoneTransfer annotations first
2. Stage 3: `CombatAnnotations` — DamageDealt annotations second

This inverts Rules 5-7. `AnnotationOrderEnforcer` doesn't fix it because it only handles Rules 1-2.

### Fix options

**Option A — Add Rules 5-7 to the enforcer.** Surgical: add edges for DamageDealt→OIC/ZT when the ZT category is SBA_Damage or Destroy. The enforcer's topological sort handles the rest. Minimal code change, explicit about WHY.

**Option B — Reorder pipeline stages.** Move combat annotations before transfer annotations. Broader impact — need to verify no other interactions depend on transfers-first ordering.

Recommendation: Option A. The enforcer is the safety net; this is exactly the kind of ordering constraint it's designed for.

## Data source

All examples from real server games saved in `~/.scry/games/`. Verified with `just scry-ts gsm show <gsId> --json`. The `scry sequences` tool can aggregate annotation ordering across all games for systematic validation.
