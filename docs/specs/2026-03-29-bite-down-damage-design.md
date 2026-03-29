# Bite Down / One-Directional Damage — Design Spec

## Problem

Fight-adjacent spells like Bite Down ("target creature you control deals damage equal to its power to target creature you don't control") need correct `DamageDealt` annotation. The key: `affectorId` must be the dealing **creature's** iid, not the spell's iid. If we use the spell, the client attributes damage to the wrong source (wrong damage animation, wrong deathtouch/lifelink attribution).

## Wire shape (from bite-down.md, session `17-04-26`)

### Targeting — two-group SelectTargetsReq

Single `SelectTargetsReq` with two groups in one message:
- Group 1 (`targetIdx=1`, `promptId=152`): your creatures
- Group 2 (`targetIdx=2`, `promptId=2401`): opponent creatures + planeswalkers
- Both share `targetingAbilityGrpId: 121962` (sub-ability)
- Outer `abilityGrpId: 93925` (spell card level)

### Resolution

```
ResolutionStart  affectorId=423 (spell)  grpId=93925
DamageDealt      affectorId=346 (creature)  affectedIds=[372]  damage=3
ObjectIdChanged  affectorId=423  orig_id=372 → new_id=399
ZoneTransfer     affectorId=423  category="SBA_Damage"  zone_src=28 → zone_dest=37
ResolutionComplete  affectorId=423  grpId=93925
```

- `DamageDealt.affectorId = 346` (the dealing creature, Dog 3/1) — NOT 423 (the spell)
- SBA fires in same diff as resolution (no separate gsId)
- Spell affectorId (423) used on ResolutionStart/Complete and ZoneTransfer

## Design

### What already works

Two-group `SelectTargetsReq` is the same shape as Bushwhack (fight). Existing targeting infra in `TargetingHandler` handles multi-group targeting. Resolution/ResolutionComplete annotations already use spell iid.

### What needs fixing

**DamageDealt affectorId.** When Forge resolves a "deal damage equal to power" effect, it fires `GameEventCardDamaged` with the dealing creature as the source. `GameEventCollector` translates this to `GameEvent.DamageDealt`. The annotation pipeline needs to use the **source card's** instanceId as `affectorId`, not the spell's.

Check: does `AnnotationBuilder.damageDealt()` already accept a source parameter? Or does the pipeline hardcode the spell iid?

### Changes

**1. Verify DamageDealt annotation source attribution**

In `AnnotationPipeline` (likely `combatAnnotations()` or `mechanicAnnotations()`), trace how `GameEvent.DamageDealt` maps to the proto annotation. Ensure:
- `affectorId = idResolver(event.sourceCardId)` (the creature)
- `affectedIds = [idResolver(event.targetCardId)]` (the target)
- `damage` detail key = power value

If the pipeline currently uses the resolving spell as affectorId, fix to use the event's source card.

**2. SBA_Damage co-resolution**

Already works — SBA fires in same diff when damage is lethal. No change needed.

### Files touched

| File | Change |
|------|--------|
| `game/AnnotationPipeline.kt` | Verify/fix DamageDealt affectorId source attribution |
| `game/AnnotationBuilder.kt` | Verify `damageDealt()` accepts source card param |
| Puzzle file | `bite-down.pzl` |

### Test plan

**Puzzle:** Your 3/1 creature + opponent's 2/2 creature + Bite Down in hand.
- Cast Bite Down targeting your creature and opponent's creature
- Verify DamageDealt annotation has `affectorId = your creature iid`
- Verify opponent's creature dies (SBA_Damage in same diff)

### Scope

Small — verify existing infra, fix affectorId if wrong. Same fix covers Rabid Bite, Ram Through, and any "creature deals damage" spell.

### Leverage

Every fight/bite spell uses the same pattern. ~20+ cards in Forge with `DealDamage$ Targeted` or similar where a creature is the damage source.
