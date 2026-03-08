## TemporaryPermanent — field note

**Status:** NOT IMPLEMENTED
**Instances:** 12 across 1 session
**Proto type:** AnnotationType.TemporaryPermanent
**Field:** persistentAnnotations (created when the permanent goes to temporary zone, deleted when it returns)

### What it means in gameplay

`TemporaryPermanent` marks a permanent that has been temporarily exiled and is scheduled to return to the battlefield at a future trigger point. The client uses it to render the card in the exile zone with a "return" indicator (often a clock or arrow overlay) showing the player this card will come back.

Specifically observed: **Getaway Glamer** (grp:90360) exiles a target creature temporarily, and the creature returns at the beginning of the next end step. The annotation tracks the exiled creature (in exile zone, zone 29) while it is "away".

### Detail keys

| Key | Always/Sometimes | Values seen | Meaning |
|-----|-----------------|-------------|---------|
| AbilityGrpId | Always | 173760 | The delayed trigger ability ID that will return the permanent. Ability text: "Return it to the battlefield under its owner's control at the beginning of the next end step." |

### Cards observed

| Card name | grpId | Role | Session |
|-----------|-------|------|---------|
| Getaway Glamer (instant) | 90360 | Source spell that caused the exile | 2026-03-06_22-37-41 |
| Inspiring Overseer (angel creature) | 93645 | The exiled permanent (affectedIds=[331]) | 2026-03-06_22-37-41 |

**Getaway Glamer** text (ability 171728): Exile target nonland permanent you control until the beginning of the next end step.
**Ability 173760**: "Return it to the battlefield under its owner's control at the beginning of the next end step."

**Sequence of events (gsId=212-231):**
1. gsId=212: Getaway Glamer (iid=328, grp:90360) cast targeting Inspiring Overseer (iid=313, grp:93645)
2. gsId=217: Getaway Glamer resolves. Inspiring Overseer exiled (ZoneTransfer zone28→zone29). Gets new instanceId 331. TriggerHolder (iid=332, grp:5) created in zone 30 (Limbo) to track the delayed return trigger. `TemporaryPermanent` annotation created: `affectorId=332 (TriggerHolder), affectedIds=[331]`
3. gsId=217–228: `TemporaryPermanent` persists across every GSM, tracking the creature in exile. The annotation ID changes each GSM (549 → 552 → 555 → 558 → 563 → 566 → 568 → 571 → 573 → 574 → 576 → 577) — each diff GSM deletes the previous and creates a new one.
4. gsId=229: End step arrives. `TemporaryPermanent` annotation (id=577) deleted. Related annotations (`DisplayCardUnderCard`, `DelayedTriggerAffectees`) transition to a new affectorId=335.
5. gsId=231: Inspiring Overseer returns to battlefield (ZoneTransfer zone30→zone28, new instanceId 336). Exile→Limbo→Battlefield transition.

### Lifecycle

The annotation has an unusual **replacement-per-GSM** pattern: rather than a stable persistent annotation that updates in place, each diff GSM deletes the previous `TemporaryPermanent` annotation and creates a new one with a fresh ID. The `diffDeletedPersistentAnnotationIds` removes it, and a new annotation with the same `affectorId`, `affectedIds`, and `details` is added.

This pattern was observed 12 times for a single exile event spanning ~12 GSMs (turns 11 Combat through turn 11 Ending).

The `affectorId` is the **TriggerHolder** object (grp:5, type=TriggerHolder), not the source spell. The TriggerHolder is the mechanism tracking the delayed return trigger.

**Creation:** fires in the same GSM as `ZoneTransfer (Exile)` for the affected permanent.
**Deletion:** fires in the same GSM as the delayed trigger resolution (the permanent returns).

### Related annotations

- `DisplayCardUnderCard` — co-created with `TemporaryPermanent` (same gsId=217). Also references the TriggerHolder and exiled card. Details: `{"TemporaryZoneTransfer": 1, "Disable": 0}`.
- `DelayedTriggerAffectees` — co-created with `TemporaryPermanent`. Details: `{"abilityGrpId": 173760}`. Marks the exiled card as the target of the delayed trigger.
- `ZoneTransfer` (Exile category) — precedes `TemporaryPermanent` in the same GSM.

### Our code status

- Builder: missing
- GameEvent: missing — no event for "permanent temporarily exiled with return trigger"
- Emitted in pipeline: no

### Wiring assessment

**Difficulty:** Medium

The mechanic is well-understood: "exile until end of step" effects with a delayed return trigger. Forge handles these via `DelayedTrigger` in its engine.

**What's needed:**
1. Detect when a permanent is exiled by a "return at end of step" effect. The Forge model likely has a `DelayedTrigger` that fires at end step.
2. Identify the TriggerHolder object. Forge creates an internal trigger object — it needs to be surfaced as a `TriggerHolder` (grp:5) in the serialized game state.
3. Emit `TemporaryPermanent` persistent annotation when the exile occurs, with `AbilityGrpId` = the return trigger's ability ID.
4. Handle the per-GSM replacement pattern: delete and re-create the annotation each diff GSM. (Or confirm whether the client tolerates a stable annotation ID — the pattern may be an artifact of how the server generates diffs.)
5. Emit `DisplayCardUnderCard` and `DelayedTriggerAffectees` alongside it (they appear to be a triplet).
6. Delete all three annotations when the return trigger resolves.

**Key gap:** TriggerHolder (grp:5) is a synthetic object that Forge doesn't expose. The engine's delayed trigger infrastructure would need to be surfaced as a game object.

**Note on the per-GSM replacement:** it's unusual for a persistent annotation to be deleted and recreated every GSM. This may be because the annotation is diff-computed from the game state snapshot rather than explicitly tracked. If we implement this as a computed annotation (regenerated each GSM from game state rather than tracked incrementally), the replacement pattern emerges naturally.

### Open questions

- Does `TemporaryPermanent` fire for other "exile and return" mechanics (Oblivion Ring, Phasing, Flicker effects)? Only one instance observed (Getaway Glamer). Other exile-return cards should be tested.
- Is the per-GSM deletion + recreation pattern required by the client, or would a stable annotation ID also work?
- Does `AbilityGrpId` always refer to the **return** trigger, or could it refer to the exile effect itself?
- grp:5 for TriggerHolder: is this a constant grpId across all sessions, or card-specific?
