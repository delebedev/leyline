## TextChange — field note

**Status:** NOT IMPLEMENTED
**Instances:** 3 across 2 sessions
**Proto type:** AnnotationType.TextChange = 27
**Field:** persistentAnnotations

### What it means in gameplay

Part of a Room door-unlock layered effect bundle. When a Room enchantment's door is unlocked, the card's rules text updates to reflect the unlocked state. TextChange is one of five co-types on a single persistent annotation.

### CRITICAL: This is not a standalone annotation

TextChange **never appears alone**. It is always co-typed with `ModifiedCost`, `ModifiedName`, `RemoveAbility`, and `LayeredEffect` on the **same annotation ID**. See `ModifiedCost.md` for the full proto structure — it is identical.

The variance report shows TextChange with the same instances, sessions, files, and detail keys as ModifiedCost. They are the same 3 proto messages viewed from a different type filter.

### Detail keys

Identical to `ModifiedCost`. See `ModifiedCost.md`.

| Key | Always/Sometimes | Values seen | Meaning |
|-----|-----------------|-------------|---------|
| effect_id | Always | 7003, 7005 | Synthetic layered effect ID |
| 310 | Sometimes (66%) | 174405 | key=instanceId, value=unlock trigger abilityGrpId |
| 316 | Sometimes (33%) | 174281 | key=instanceId, value=unlock trigger abilityGrpId |

### Cards observed

Same as ModifiedCost.md — Unholy Annex // Ritual Chamber (grp:92196) and Grand Entryway // Elegant Rotunda (grp:92073).

### Lifecycle

Identical to `ModifiedCost.md` — persistent, created at door-unlock, survives on battlefield.

### Related annotations

See `ModifiedCost.md`. These five types form a single annotation bundle: `ModifiedCost`, `TextChange`, `ModifiedName`, `RemoveAbility`, `LayeredEffect`.

### Our code status

- Builder: missing
- Emitted in pipeline: no

### Wiring assessment

**Difficulty:** Hard — must be implemented together with ModifiedCost, ModifiedName, RemoveAbility, and LayeredEffect as a single multi-typed annotation.

Do not implement this in isolation. See `ModifiedCost.md` for the full wiring plan.

### Open questions

See `ModifiedCost.md` — shared open questions for the entire bundle.
