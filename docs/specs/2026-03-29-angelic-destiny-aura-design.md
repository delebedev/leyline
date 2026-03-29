# Angelic Destiny / Aura Keyword Grant + SBA_UnattachedAura — Design Spec

## Problem

Auras that grant keywords (Angelic Destiny: flying + first strike + Angel type + P/T pump) need three things that don't work today:

1. **Multiple LayeredEffectCreated at resolve** — one per static line (P/T, keyword, type). Currently EffectTracker only handles P/T.
2. **AddAbility pAnn with keyword packing** — multiple keywords in one pAnn (`grpid=[8,6]`).
3. **SBA_UnattachedAura** — when the host leaves BF (by any route), aura goes to GY with `LayeredEffectDestroyed` per effect + `ZoneTransfer(SBA_UnattachedAura)`.

This builds directly on the Overrun keyword grant spec. Overrun is the sorcery case (temporary, EOT cleanup). Angelic Destiny is the aura case (persistent, SBA cleanup).

## Wire shape (from angelic-destiny.md, session `16-45-39`)

### Resolve + attach (gsId 348)

Three `LayeredEffectCreated` transients (effect_ids 7003, 7004, 7005):
- 7003: P/T pump → `[ModifiedPower, ModifiedToughness, LayeredEffect]` pAnn
- 7004: Keyword grant → `[AddAbility, LayeredEffect]` pAnn with `grpid=[8,6]` (flying + first strike), `UniqueAbilityId=[303,304]`
- 7005: Type grant → `[ModifiedType, LayeredEffect]` pAnn

Plus: `AttachmentCreated` transient + `[Attachment]` pAnn.

All `affectorId = 406` (aura iid). All `affectedIds = [373]` (host creature iid).

### SBA_UnattachedAura (gsId 463 — host bounced)

Host creature bounced by Mischievous Pup:
```
LayeredEffectDestroyed  affectorId=406  affectedIds=[7003]
LayeredEffectDestroyed  affectorId=406  affectedIds=[7004]
LayeredEffectDestroyed  affectorId=406  affectedIds=[7005]
ObjectIdChanged         affectedIds=[406]  orig_id=406 → new_id=442  (no affectorId)
ZoneTransfer            affectedIds=[442]  category="SBA_UnattachedAura"  BF→GY  (no affectorId)
```

- Three `LayeredEffectDestroyed` — one per effect. Count matches count at resolve.
- No `affectorId` on ObjectIdChanged or ZoneTransfer (SBA is system-driven)
- Return-to-hand trigger does NOT fire (host was bounced, not killed — `Destination$ Graveyard` check)

### Return-to-hand trigger (host dies)

Not observed in this session (host was bounced, not killed). From Forge script: `T:Mode$ ChangesZone | Origin$ Battlefield | Destination$ Graveyard | ValidCard$ Card.EnchantedBy | ... → ChangeZone(GY→Hand)`. When host dies, aura goes to GY via SBA, then trigger returns it to hand. Category = `"Return"`.

## Design

### Builds on Overrun keyword grant spec

Overrun spec establishes:
- `KeywordGrpIds` registry (flying=8, first_strike=6, etc.)
- `GameEventExtrinsicKeywordAdded` Forge event
- EffectTracker keyword tracking
- `AddAbility` multi-creature pAnn emission

Angelic Destiny reuses all of this. The differences are:

| | Overrun | Angelic Destiny |
|---|---|---|
| Duration | Until EOT | Permanent (until aura leaves) |
| Affected creatures | All you control | One (enchanted) |
| Keyword packing | One keyword (trample) | Multiple (`grpid=[8,6]`) |
| Cleanup | EOT LayeredEffectDestroyed | SBA_UnattachedAura |
| Extra effects | P/T only | P/T + keywords + type grant |

### New work (beyond Overrun spec)

**1. Multi-keyword AddAbility pAnn**

Overrun grants one keyword → one grpId in the pAnn. Angelic Destiny grants two → `grpid=[8,6]` packed in one pAnn with two `UniqueAbilityId` values.

The `AnnotationBuilder.addAbility()` overload from Overrun spec needs to accept `grpIds: List<Int>` (not just `grpId: Int`).

**2. ModifiedType pAnn**

New annotation type: `[ModifiedType, LayeredEffect]`. Needs its own effect_id. Emitted when an aura grants a creature type (Angel). EffectTracker needs to track type grants alongside P/T and keywords.

**3. AttachmentCreated + Attachment pAnn**

Check: does existing aura resolution emit these? If AnnotationPipeline already handles `GameEventCardAttachment` (Forge event), this may already work. Verify.

**4. SBA_UnattachedAura system**

The big piece. When an aura's host leaves the battlefield:

a. **Detect** — need to know which auras are attached to which creatures. Options:
   - Track via `Attachment` pAnn (we emit it at resolve time)
   - Read from Forge state (`card.getEnchanting()` or `card.getAttachedTo()`)
   - Forge fires `GameEventCardDetached` or similar

b. **Emit LayeredEffectDestroyed** — one per effect_id tracked for this aura. EffectTracker already tracks effect_ids; destroying them is the reverse of creating them.

c. **Emit ObjectIdChanged + ZoneTransfer(SBA_UnattachedAura)** — no affectorId (system-driven). The aura gets a new instanceId and moves BF→GY.

d. **Ordering** — in mass removal (Day of Judgment): all creature Destroy annotations first, then ResolutionComplete, then SBA cascade. In bounce: SBA fires in the same diff as the bounce resolution.

**Detection approach:** Use Forge state. After each diff, check if any aura's `card.getEnchanting()` returns null but the aura is still on BF → it's unattached. Emit SBA. This is simpler than event-driven detection because Forge handles the detachment internally.

**5. Return-to-hand trigger (host dies)**

This is a standard triggered ability — Forge handles it. The trigger checks `Destination$ Graveyard`, so it only fires when host goes to GY (death), not when bounced. No special leyline work needed beyond existing trigger infrastructure.

### Files touched

| File | Change |
|------|--------|
| `game/AnnotationBuilder.kt` | Multi-grpId `addAbility()`, `modifiedType()` builder, `sbaUnattachedAura()` |
| `game/EffectTracker.kt` | Type grant tracking, aura effect lifecycle |
| `game/AnnotationPipeline.kt` | SBA_UnattachedAura detection + emission, type grant emission |
| `game/StateMapper.kt` | SBA detection hook after diff build |
| `game/TransferCategory.kt` | Add `SBA_UnattachedAura` variant (if not present) |
| Puzzle file | `aura-keyword-angelic-destiny.pzl` |

### Test plan

**Puzzle 1 — Attach:** Angelic Destiny in hand, creature on BF. Cast → verify 3× LayeredEffectCreated, AddAbility pAnn with `grpid=[8,6]`, creature gains flying + first strike + Angel type.

**Puzzle 2 — Host dies:** Creature with Angelic Destiny, opponent kills it. Verify SBA_UnattachedAura fires (3× LayeredEffectDestroyed + ZoneTransfer), then return-to-hand trigger fires (aura GY→Hand).

**Puzzle 3 — Host bounced:** Creature with Angelic Destiny, bounce spell. Verify SBA_UnattachedAura fires, return-to-hand trigger does NOT fire (aura stays in GY).

### Leverage

SBA_UnattachedAura is a system-level fix — every aura benefits (Knight's Pledge, River's Favor, Pacifism, equipment fall-off). 7 confirmed events across today's recordings. The effect tracking (create N effects at attach, destroy N at detach) is reusable for any continuous-effect source.

### Dependencies

- **Overrun keyword grant spec** — Layer 0 (KeywordGrpIds) + Layer 1 (Forge event) + Layer 2 (EffectTracker) must land first
- **Bounce category fix** ("Return" not "Bounce") — already identified, Layer 0 data fix
