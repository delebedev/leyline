## DelayedTriggerAffectees — field note

**Status:** NOT IMPLEMENTED
**Instances:** 4 across 1 session (`2026-03-06_22-37-41`)
**Proto type:** `AnnotationType.DelayedTriggerAffectees` (= 74)
**Field:** `persistentAnnotations`

### What it means in gameplay

Marks which object(s) will be affected when a delayed triggered ability eventually fires. The client uses this to highlight the affected card with a visual indicator while the delayed trigger is pending (e.g. a card with a countdown glow, or an exile-return animation pending).

### Detail keys

| Key | Always/Sometimes | Values seen | Meaning |
|-----|-----------------|-------------|---------|
| `abilityGrpId` | Always | 372, 173760 | The grpId of the delayed triggered ability that will fire |
| `removesFromZone` | Sometimes | 1 | Flag indicating the trigger will remove the affected card from its current zone (exile/bounce/sacrifice) |

### Cards observed

**Scenario 1: Quantum Riddler (grp:96646) — "Warp" exile-and-recast delayed trigger**

| gsId | affectorId | affectedIds | abilityGrpId | removesFromZone | Notes |
|------|-----------|------------|--------------|----------------|-------|
| 218 | iid=396 (TriggerHolder, grp=5) | [392] (Quantum Riddler on BF) | 372 | 1 | Created when Quantum Riddler resolves; TriggerHolder represents the pending delayed trigger |
| 237 | iid=401 (Ability on stack, grp=372) | [392] | 372 | 1 | `affectorId` updated to the live ability iid when trigger fires at end step |

Ability 372 = "Exile this at the beginning of the next end step. You may cast it from exile on a later turn." (Warp mechanic's delayed trigger.)

**Scenario 2: Getaway Glamer (grp:90360) — "Return at beginning of next end step" delayed trigger**

| gsId | affectorId | affectedIds | abilityGrpId | removesFromZone | Notes |
|------|-----------|------------|--------------|----------------|-------|
| 217 | iid=332 (TriggerHolder, grp=5) | [331] (Inspiring Overseer in exile) | 173760 | absent | Created when Getaway Glamer resolves; exiled creature waiting to return |
| 229 | iid=335 (Ability on stack, grp=173760) | [331] | 173760 | absent | `affectorId` updated to live ability when trigger fires at end step |

Ability 173760 = "Return it to the battlefield under its owner's control at the beginning of the next end step." (Getaway Glamer's return trigger.)

### Lifecycle

**Two-phase lifecycle tied to a TriggerHolder:**

Phase 1 — Pending (TriggerHolder exists):
- When the source spell resolves and creates a delayed trigger, a `TriggerHolder` object (grp=5) is created in the Limbo zone.
- The `DelayedTriggerAffectees` annotation is created with `affectorId` = the TriggerHolder's instanceId.
- Sibling annotations in the same GSM: `TriggeringObject` (linking the trigger to the source card), `DisplayCardUnderCard` (showing the exile relationship visually), `TemporaryPermanent` (for the exiled card if applicable).

Phase 2 — Trigger fires:
- When the delayed trigger fires (end step), a new Ability object appears on the stack (the live trigger).
- The annotation is **updated in-place**: `affectorId` changes from TriggerHolder iid to the live Ability iid. `affectedIds` stays the same.
- The TriggerHolder object is deleted (`diffDeletedInstanceIds`).
- The annotation persists until the ability resolves.

**Deletion:** After the ability resolves (ZoneTransfer of the affected card), the annotation is deleted.

### Related annotations

In the same GSM as DelayedTriggerAffectees creation:
- `DisplayCardUnderCard` (type unknown) — visual overlay showing the affected card's association
- `TemporaryPermanent` (when applicable) — marks the affected card as a temporary exile
- `TriggeringObject` — links the trigger ability instance to the source card

### Our code status

- Builder: missing — no `delayedTriggerAffectees()` builder in `AnnotationBuilder.kt`
- GameEvent: Forge has `GameEventCardPhased`, `GameEventReturnedToHand`, and delayed trigger infrastructure, but no dedicated "delayed trigger registered" event that would serve as a hook
- Emitted in pipeline: no

### Wiring assessment

**Difficulty:** Hard

The annotation requires tracking a two-phase object lifecycle (TriggerHolder → live Ability) that Arena invents but Forge does not expose. Specifically:

1. **TriggerHolder grp=5:** Arena creates a synthetic "TriggerHolder" object (grp=5) in Limbo to represent a pending delayed trigger. Forge has no equivalent object in its model — delayed triggers are tracked internally in the trigger queue.

2. **Phase 1 creation hook:** Would need to intercept when Forge registers a delayed trigger (the equivalent of "after this spell resolves, do X at end step"). There's no direct GameEvent for this. Would need to hook into Forge's `DelayedTrigger` registration or scan for newly-registered delayed triggers after each spell resolution.

3. **Phase 2 update hook:** When the delayed trigger fires (goes on the stack as a real ability), the annotation `affectorId` must be updated from the TriggerHolder iid to the new Ability iid. This is a mid-lifecycle annotation mutation.

4. **`removesFromZone` flag:** When the trigger will exile/bounce/move the affected card (Warp case), this flag must be set. When it returns a card (Getaway Glamer), it's absent.

The TriggerHolder synthetic object (grp=5) is an Arena fiction — we'd need to invent a parallel system or find where Forge represents "this delayed trigger is pending for this card."

Practically, this is cosmetic (hover preview of what will happen to an exiled/affected card), so it's low priority despite the complexity.

### Open questions

- What grp=5 represents in Arena's object model — is it always TriggerHolder, or multipurpose?
- Does `removesFromZone:1` specifically mean "will exile this card" vs. any zone removal? Getaway Glamer's "return to BF" trigger does NOT set removesFromZone, suggesting it's specifically for exile/leave-zone triggers, not return triggers.
- Are there other `abilityGrpId` values seen with DelayedTriggerAffectees? This recording only shows Warp (372) and Getaway Glamer (173760) — both end-step return/exile patterns.
- Does the annotation appear for any-triggered delayed triggers (e.g. "at the beginning of your next upkeep"), or only end-step ones?
