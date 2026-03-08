## MultistepEffectStarted — field note

**Status:** NOT IMPLEMENTED
**Instances:** 4 across 1 session (2 unique gsIds × 2 seat echo copies)
**Proto type:** `AnnotationType.MultistepEffectStarted` (enum 83)
**Field:** `annotations` (transient only)

### What it means in gameplay

A multi-step interactive effect has begun resolving — the player must make a decision in multiple discrete steps (e.g., look at top card, then decide top or bottom). The client shows a UI overlay indicating the active sub-resolution.

### Detail keys

| Key | Always/Sometimes | Values seen | Meaning |
|-----|-----------------|-------------|---------|
| `SubCategory` | Always | `15` | Integer matching the ability's subcategory enum. 15 = scry. |

### Cards observed

| Card name | grpId | Scenario | Session |
|-----------|-------|----------|---------|
| Temple of Triumph | 94133 | Enters battlefield, triggers "When CARDNAME enters, scry 1" (ability 176406) | `2026-03-06_22-37-41` |

### Lifecycle

Transient annotation. Fires in the same GSM as `ResolutionStart` for the triggering ability (gsId=51 in this session). The companion `MultistepEffectComplete` fires in the next GSM (gsId=52) alongside the `Scry` annotation and `ResolutionComplete`.

Full sequence:
1. **gsId=51:** `ResolutionStart` + `MultistepEffectStarted{SubCategory=15}` → ability begins, client shows scry overlay
2. (client sends scry choice to server)
3. **gsId=52:** `Scry{topIds, bottomIds}` + `MultistepEffectComplete{SubCategory=15}` + `ResolutionComplete` → decision resolved, overlay dismissed

`affectedIds=[1]` = the player seat making the decision. `affectorId=284` = the ability instance on stack.

### Related annotations

- `ResolutionStart` — fires in same GSM, same `affectorId`
- `MultistepEffectComplete` — counterpart, fires with `Scry` in the resolution GSM
- `Scry` (type 65) — the actual scry payload; fires with `MultistepEffectComplete`

### Our code status

- Builder: missing — no `MultistepEffectStarted` method in `AnnotationBuilder.kt`
- GameEvent: missing — `GameEvent.Scry` exists but no `MultistepEffectStarted` event
- Emitted in pipeline: no — `AnnotationPipeline.kt` emits `Scry_af5a` annotation only; no `MultistepEffectStarted` wrapper

### Wiring assessment

**Difficulty:** Easy

`SubCategory` directly mirrors the ability's subcategory field (confirmed: ability 176406 has SubCategory=15). The annotation is a pure bracket around any existing multi-step mechanic emission.

Wiring plan:
1. Emit `MultistepEffectStarted{SubCategory}` immediately before the inner mechanic annotation in `AnnotationPipeline` (alongside `ResolutionStart`)
2. Emit `MultistepEffectComplete{SubCategory}` immediately after the inner mechanic annotation (alongside `ResolutionComplete`)
3. SubCategory value = the ability's subcategory; for scry it is always 15

The main gap: the pipeline needs access to the ability's `SubCategory` at the time `GameEvent.Scry` is processed. Currently the event carries only `seatId`, `topCount`, `bottomCount` — the ability SubCategory would need to be threaded through or looked up.

### Open questions

- Only SubCategory=15 (scry) observed across all recordings. Does MultistepEffect fire for surveil (presumably SubCategory=6?) or other multi-step effects? Need recordings with surveil to confirm.
- The `affectorId` is the ability instance ID (on-stack object). Pipeline currently doesn't always have access to the ability instanceId at `Scry` emission time — this may require a design change or just using the triggering-object ID from context.
