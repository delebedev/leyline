# Annotation Ordering Within a GSM

Annotation ordering rules derived from real server Player.log analysis. 24 games, 1141 interactions, verified with `scry sequences`.

Annotations within a GSM are processed sequentially — animation playback follows annotation order. Wrong ordering = wrong visual sequence (e.g., creature goes to graveyard before damage animation plays).

---

## Enforced rules

Rules 1-2 are implemented in `AnnotationOrderEnforcer` and validated by `InvariantChecker`.

### Rule 1: ObjectIdChanged before references

ObjectIdChanged must precede any annotation referencing its new instanceId in affectedIds or affectorId. The identity mapping must exist before downstream annotations use it.

Violation: wrong card animates, phantom objects.

### Rule 2: Same-card incremental entity chaining

When two annotations both modify the same card incrementally, precedence order:

| Prec | Type | Card ID field |
|------|------|---------------|
| 0 | ControllerChanged | affectedIds |
| 1 | TappedUntappedPermanent | affectedIds |
| 2 | DamageDealt | affectedIds |
| 3 | CounterAdded/Removed | affectedIds |
| 4 | PowerToughnessModCreated | affectedIds |
| 5 | LayeredEffectCreated | affectorId |
| 6 | AttachmentCreated | affectorId |

Violation: corrupted card state — wrong P/T, missing counters, incorrect controller.

---

## Not yet enforced

### Rule 3: ModifiedLife deduplication

ModifiedLife must come after any prior life-delta annotation for the same player. It deduplicates by subtracting already-accounted deltas. Wrong order = double life update.

Priority: low. Documented in message-framing.md.

### Rule 4: Forward ID scan

ObjectIdChanged before ZoneTransfer for correct create-vs-transfer animation decision. Largely covered by Rule 1. Visual only.

Priority: low.

### Rule 5: DamageDealt before death

All DamageDealt annotations precede ObjectIdChanged/ZoneTransfer for creatures dying from that damage. **Zero exceptions across 125 combat + 61 targeted spell instances.**

Observed (combat, gsId 309):
```
DamageDealt         affected=[296]  (Kellan takes 3)
DamageDealt         affected=[300]  (Hurloon takes 3)
...
ObjectIdChanged     affected=[296]  (296→343)
ZoneTransfer        affected=[343]  (→ Graveyard, SBA_Damage)
ObjectIdChanged     affected=[300]  (300→344)
ZoneTransfer        affected=[344]  (→ Graveyard, SBA_Damage)
```

Observed (spell damage, gsId 202):
```
ResolutionStart
DamageDealt         affected=[313]  (lethal)
ResolutionComplete
ObjectIdChanged     affected=[321]  (spell → graveyard)
ZoneTransfer        affected=[327]  (Resolve)
ObjectIdChanged     affected=[313]  (creature → graveyard)
ZoneTransfer        affected=[328]  (SBA_Damage)
```

Violation: creature visually dies before the hit animation plays.

**Priority: high. This is causing visible animation bugs.**

### Rule 6: Combat damage block order

Full observed ordering, 125 instances:

```
PhaseOrStepModified (CombatDamage step)
DamageDealt         (one per source→target pair)
DamagedThisTurn     (badge, if creature survived)
SyntheticEvent      (if player took damage)
ModifiedLife        (one per player whose life changed)
LayeredEffectDestroyed (cleanup for dying creatures' effects)
ObjectIdChanged     (per dying creature)
ZoneTransfer        (per dying creature, SBA_Damage)
AbilityInstanceDeleted (cleanup for dying creatures' abilities)
```

### Rule 7: Resolution block order

Full observed ordering, 250 untargeted + 61 targeted instances:

```
ResolutionStart     (spell affectorId)
DamageDealt         (if damage spell)
SyntheticEvent      (if player took damage)
ModifiedLife        (if life changed)
ResolutionComplete  (spell affectorId)
ObjectIdChanged     (spell leaving stack)
ZoneTransfer        (Stack→Graveyard, Resolve)
ObjectIdChanged     (killed creature, if any)
ZoneTransfer        (Battlefield→Graveyard, SBA_Damage)
```

Spell OIC/ZT before killed creature OIC/ZT — the spell leaves the stack, then SBA processes lethal damage.

### Rule 8: Cast order

97% consistent across 61 targeted casts, 86% across 286 land plays:

```
ObjectIdChanged     (hand id → stack/battlefield id)
ZoneTransfer        (CastSpell or PlayLand)
PlayerSelectingTargets (targeted spells only)
UserActionTaken
```

### Rule 9: Draw order

96% consistent, 357 instances:

```
PhaseOrStepModified
ObjectIdChanged     (library id → hand id)
ZoneTransfer        (Draw)
```

---

## Current leyline gap

`StateMapper.computeAnnotations()` builds transfers (stage 1-2) before combat (stage 3). This inverts Rules 5-7 — ZoneTransfer appears before DamageDealt.

`AnnotationOrderEnforcer` only handles Rules 1-2. It does not reorder across these stages.

### Fix

Add Rules 5-7 to the enforcer. Edges: DamageDealt → OIC/ZT when ZT category is SBA_Damage or Destroy. The topological sort handles the rest.

---

## Verification

`scry sequences` extracts annotation ordering across all saved games. Per-slot `annotationOrder` field shows canonical order; `orderConsistency` shows what fraction of instances match.

```bash
just scry-ts sequences --type COMBAT_DAMAGE   # POS → DD → SE → ML
just scry-ts sequences --type TARGETED_SPELL   # slot 5: RS → DD → RC → OIC → ZT
```
