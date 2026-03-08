## ModifiedCost — field note

**Status:** NOT IMPLEMENTED
**Instances:** 3 across 2 sessions
**Proto type:** AnnotationType.ModifiedCost = 113
**Field:** persistentAnnotations

### What it means in gameplay

Part of a Room door-unlock layered effect bundle. When a Room enchantment's door is unlocked, the card's cost display, name, and text change to reflect the unlocked state. ModifiedCost is one of five co-types on a single persistent annotation that together represent this state change.

### CRITICAL: This is not a standalone annotation

ModifiedCost **never appears alone**. In both sessions, the persistent annotation carrying this type also has:
- `TextChange` (type 27)
- `ModifiedName` (type 76)
- `RemoveAbility` (type unknown)
- `LayeredEffect` (type 18)

All five types appear on the **same annotation ID** with the **same details**. Arena's proto allows multiple types on a single annotation message — this is that pattern.

Example from `2026-03-01_00-18-46` gsId=100:
```json
{
  "id": 242,
  "types": ["ModifiedCost","TextChange","ModifiedName","RemoveAbility","LayeredEffect"],
  "affectorId": 310,
  "affectedIds": [310],
  "details": {"310": 174405, "effect_id": 7003}
}
```

### Detail keys

| Key | Always/Sometimes | Values seen | Meaning |
|-----|-----------------|-------------|---------|
| effect_id | Always | 7003, 7005 | Synthetic layered effect ID (same namespace as LayeredEffect annotations) |
| 310 | Sometimes (66%) | 174405 | Key = affectedId (instanceId); Value = the door-unlock trigger abilityGrpId (Unholy Annex // Ritual Chamber: abilityId 174405) |
| 316 | Sometimes (33%) | 174281 | Key = affectedId (instanceId); Value = the door-unlock trigger abilityGrpId (Grand Entryway // Elegant Rotunda: abilityId 174281) |

The "sometimes" keys follow a pattern: the key name is the instanceId of the affected Room card, and the value is the `abilityGrpId` of the door-unlock trigger that fired. This is an unusual protocol pattern — the key itself is dynamic (the instanceId), not a fixed string.

### Cards observed

| Card name | grpId | Scenario | Session |
|-----------|-------|----------|---------|
| Unholy Annex // Ritual Chamber | 92196 | Left door unlocked (Unholy Annex resolves), instanceId=310 | 2026-03-01_00-18-46 gsId=100 T5 Main1 |
| Grand Entryway // Elegant Rotunda | 92073 | Right door unlocked (Elegant Rotunda resolves), instanceId=316 | 2026-03-06_22-37-41 gsId=161 T8 Main1 |

### Lifecycle

Persistent annotation. Created when a Room door is unlocked (alongside `GainDesignation`, `Designation`, `LayeredEffectCreated`, `ResolutionStart`, `ResolutionComplete`, `ZoneTransfer`).

The annotation persists on the battlefield for the remainder of the game as long as the Room enchantment remains. In the `00-18-46` session, the annotation (id=242) survives through the rest of the recorded session with no deletion.

### Related annotations

All of these appear in the same GSM as the five-type annotation:
- `GainDesignation` (transient) — records the door-unlock state change
- `Designation` (persistent) — persists the DesignationType for the Room
- `LayeredEffectCreated` (transient) — creates the effect with the same `effect_id`
- `ResolutionStart` / `ResolutionComplete` — the door-half spell resolving
- `ZoneTransfer` (Resolve) — the Room entering the battlefield

See also: `TextChange.md`, `ModifiedName.md` — identical structure and lifecycle.

### Our code status

- Builder: missing
- GameEvent: no Room door-unlock event currently wired for annotation emission
- Emitted in pipeline: no

### Wiring assessment

**Difficulty:** Hard (tied to Room door-unlock annotation system)

The five-type bundle (ModifiedCost + TextChange + ModifiedName + RemoveAbility + LayeredEffect) must be emitted as a **single persistent annotation with all five types set**. This is not five separate annotations.

Implementation requires:
1. Detecting Room door-unlock events in the Forge bridge
2. Allocating a synthetic effect ID from the 7000+ counter
3. Building the annotation with all five types set
4. Using the dynamic `{instanceId: abilityGrpId}` detail key pattern

The hardest part is the dynamic detail key (instanceId as key name) — the annotation builder would need to handle this pattern. See also the `LayeredEffect` annotation infrastructure requirement.

### Open questions

- What does `RemoveAbility` in the co-type bundle specifically mean? Likely the locked-door abilities are removed when the door is unlocked (the "pay to unlock" cost goes away).
- Is the same five-type bundle emitted for every Room card, or only for specific unlock scenarios?
- When both doors of a Room are unlocked, is there a second annotation (different effect_id) or does the same annotation accumulate?
