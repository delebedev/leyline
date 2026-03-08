## MultistepEffectComplete — field note

**Status:** NOT IMPLEMENTED
**Instances:** 4 across 1 session (2 unique gsIds × 2 seat echo copies)
**Proto type:** `AnnotationType.MultistepEffectComplete` (enum 84)
**Field:** `annotations` (transient only)

### What it means in gameplay

The multi-step interactive effect that began with `MultistepEffectStarted` has finished resolving. The client dismisses the multi-step overlay and proceeds with the next game action.

### Detail keys

| Key | Always/Sometimes | Values seen | Meaning |
|-----|-----------------|-------------|---------|
| `SubCategory` | Always | `15` | Same subcategory as the paired `MultistepEffectStarted`. 15 = scry. |

### Cards observed

| Card name | grpId | Scenario | Session |
|-----------|-------|----------|---------|
| Temple of Triumph | 94133 | Scry 1 trigger resolves — player kept card on top | `2026-03-06_22-37-41` |

### Lifecycle

Transient annotation. Fires in the same GSM as the inner mechanic annotation (`Scry`) and `ResolutionComplete`. Always paired with a prior `MultistepEffectStarted` in the preceding GSM.

Full sequence (same as `MultistepEffectStarted` note):
1. gsId=51: `MultistepEffectStarted{SubCategory=15}` + `ResolutionStart`
2. gsId=52: `Scry` + `MultistepEffectComplete{SubCategory=15}` + `ResolutionComplete` + `AbilityInstanceDeleted`

`affectedIds=[1]` = player seat. `affectorId=284` = ability instance (same as the paired Started annotation).

### Related annotations

- `MultistepEffectStarted` — counterpart, fires one GSM earlier
- `Scry` (type 65) — fires in same GSM as Complete
- `ResolutionComplete` — fires in same GSM

### Our code status

- Builder: missing — no `MultistepEffectComplete` method in `AnnotationBuilder.kt`
- GameEvent: missing
- Emitted in pipeline: no

### Wiring assessment

**Difficulty:** Easy — identical complexity to `MultistepEffectStarted`. Implement both together as a matched pair.

See `MultistepEffectStarted.md` for full wiring plan. The only difference is emission order: `MultistepEffectComplete` goes in the same annotation batch as the resolved mechanic (e.g., alongside `Scry_af5a`), not before it.

### Open questions

- Same as `MultistepEffectStarted`: surveil SubCategory unknown; need ability instanceId at emission time.
