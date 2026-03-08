## ModifiedType — field note

**Status:** NOT IMPLEMENTED
**Instances:** 20 across 1 session
**Proto type:** AnnotationType.ModifiedType (appears as multi-type annotation combined with LayeredEffect)
**Field:** persistentAnnotations (always persistent)

### What it means in gameplay

`ModifiedType` marks that a permanent's card type or subtype has been altered by a layered effect — typically a land having its subtype changed (e.g. basic Forest becoming a Forest AND a plains type), or a creature gaining a new type (e.g. Spider becoming a legendary Spider Hero). The client uses it to update the type line display and re-evaluate type-dependent rules (e.g. basic land type matters for color production).

### Detail keys

| Key | Always/Sometimes | Values seen | Meaning |
|-----|-----------------|-------------|---------|
| effect_id | Always | 7002, 7003, 7009, 7010, 7012, 7013, 7017, 7023, 7028 | Synthetic layered effect ID — links to the LayeredEffectCreated event |
| sourceAbilityGRPID | Sometimes | 192752, 192918 | The ability grpId that caused the type modification |
| `<instanceId>` key | Sometimes | `"282": "all"` | When present: the affected card's instanceId as key, value `"all"` — meaning all types/subtypes are replaced |

**Note on the `"282": "all"` pattern:** observed on the Multiversal Passage land (iid=282). The key is the string representation of the card's instanceId. Value `"all"` appears to mean "replace all subtypes" (the land plays as a different basic type). This is distinct from the `sourceAbilityGRPID` pattern, which only adds a type.

### Cards observed

| Card name | grpId | Role | Affected card | Session |
|-----------|-------|------|---------------|---------|
| Multiversal Passage (land) | 97998 | affectorId (self-modifying land) | Self (iid=282) — removes abilities, replaces type | 2026-03-06_22-37-41 gsId=26 |
| Badgermole Cub (via earthbend ability 192752) | 97444 | affectorId (trigger source) | Forest (grp:95199, iid=291,309) and other lands | 2026-03-06_22-37-41 gsId=72,195,215,227 |
| A Most Helpful Weaver (via Spider hero ability 192918) | 97823 | affectorId (trigger source) | Spider (grp:98008, iid=289,303) — becomes legendary Spider Hero | 2026-03-06_22-37-41 gsId=92,160 |
| Spider (base creature) | 98008 | affectedIds | Target of A Most Helpful Weaver ability | 2026-03-06_22-37-41 |
| Willowrush Verge (land) | 95072 | affectedIds | Land modified by Badgermole Cub earthbend | 2026-03-06_22-37-41 gsId=195 |
| Mockingbird | 91597 | affectorId | Resolves — modifies a land type | 2026-03-06_22-37-41 gsId=215 |

**Ability 192752** — "When CARDNAME enters, earthbend 1." (Badgermole Cub): earthbend changes a land's type.
**Ability 192918** — "Put a +1/+1 counter on target creature you control. It becomes a legendary Spider Hero in addition to its other types." (A Most Helpful Weaver).

### Lifecycle

`ModifiedType` is always a **multi-type persistent annotation** — it appears alongside `LayeredEffect` (and sometimes `RemoveAbility`) in the same annotation object. It is never standalone.

Example annotation (A Most Helpful Weaver targeting Spider):
```json
{
  "id": 274,
  "types": ["ModifiedType", "LayeredEffect"],
  "affectorId": 285,
  "affectedIds": [289],
  "details": {
    "sourceAbilityGRPID": 192918,
    "effect_id": 7009
  }
}
```

Example annotation (Multiversal Passage self-modification with RemoveAbility):
```json
{
  "id": 127,
  "types": ["RemoveAbility", "ModifiedType", "LayeredEffect"],
  "affectorId": 282,
  "affectedIds": [282],
  "details": {
    "282": "all",
    "effect_id": 7002
  }
}
```

**Persistence:** the annotation lives as long as the effect is active. For turn-scoped effects (earthbend, Spider hero transformation in some cases), it is deleted via `diffDeletedPersistentAnnotationIds` when the effect expires. For permanent effects (Multiversal Passage plays as a basic type forever), it persists until the land leaves the battlefield.

**Multiple effects on the same card:** when A Most Helpful Weaver grants both a type change and something else, two separate ModifiedType+LayeredEffect annotations are created with different effect IDs (e.g. 7009 and 7010), both with `affectedIds=[289]`.

### Related annotations

- `LayeredEffect` — always co-present on the same annotation object
- `RemoveAbility` — co-present when the type change also strips abilities (Multiversal Passage)
- `LayeredEffectCreated` — fires in the same GSM as the ModifiedType annotation is created
- `LayeredEffectDestroyed` — fires when the effect ends; ModifiedType annotation deleted in same GSM

### Our code status

- Builder: missing
- GameEvent: missing — no event for type-line changes
- Emitted in pipeline: no

### Wiring assessment

**Difficulty:** Hard

`ModifiedType` is inseparable from the `LayeredEffect` annotation system. It cannot be wired independently — requires the same synthetic effect ID infrastructure as `LayeredEffectCreated`/`Destroyed`.

Additional challenges:
1. **Type-change detection in Forge**: Forge applies type changes dynamically (e.g. via `AddType$`, `SetSubtype$` SVars). No event fires when a layered type effect is applied — it's computed fresh each evaluation.
2. **Multi-type annotation structure**: requires emitting a single annotation with `types=["ModifiedType","LayeredEffect"]` (and optionally `"RemoveAbility"`). Our annotation builder would need to support composite type lists.
3. **`sourceAbilityGRPID` mapping**: requires knowing the Arena ability grpId of the Forge ability that caused the change — the same gap as in other ability-sourced annotations.
4. **The `"<instanceId>": "all"` detail key**: the key is a stringified integer. Represents the "replace all types" case (basic land type replacement). Needs special handling.

### Open questions

- Does `ModifiedType` always co-appear with `LayeredEffectCreated` in the same GSM? In all observed instances, yes — but need to confirm for static-ability-based type changes (e.g. if a permanent continuously changes a type, does `LayeredEffectCreated` fire every GSM or just once?).
- The `"all"` value for the instanceId-keyed detail: does it mean "replace all subtypes" specifically, or "replace all types including supertypes and card types"?
