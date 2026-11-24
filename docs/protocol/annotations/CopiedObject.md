---
summary: "CopiedObject annotation: persistent copy effect on a permanent; copyFromGrpid and overlayGrpId, layered-effect fields, identities and retirement through LayeredEffectDestroyed."
entity: annotation/CopiedObject
---
# CopiedObject

## Meaning

A copy effect makes a permanent take on the copiable values of another card
(CR 707.2) while it remains the same object. `CopiedObject` publishes such an
effect as a layered effect on the permanent. The object keeps its instance ID
and its own `grpId`; the card it now looks like is its `overlayGrpId`.

## Rules grounding

Copy effects apply in layer 1 (CR 613.1a), before every other continuous
effect.

## Variants

Persistent only, typed `CopiedObject` and `LayeredEffect`, with
`LayeredEffectType` `CopyObject`. A copy made by a resolving ability carries
`sourceAbilityGRPID`; a copy effect with a duration also carries `Duration`.

## Fields

| Field | Role |
|---|---|
| `affectorId` | The source of the copy effect: the permanent whose ability makes the copy, the affected permanent itself, or a system value such as `4294967293` when no object is the source. |
| `affectedIds` | The permanent that becomes a copy. |
| `copyFromGrpid` | The card definition the permanent copies. |
| `LayeredEffectType` | `CopyObject`, as a string. |
| `sourceAbilityGRPID` | When present, the ability definition that created the copy effect. |
| `Duration` | When present, an integer describing the effect's duration; see Open points. |
| `effect_id` | The effect identity introduced by [LayeredEffectCreated](LayeredEffectCreated.md). |

## Identity model

Five identities meet here: the source permanent, the affected permanent,
the copied card definition, the source ability definition and the effect
identity. The affected object's `grpId` stays its own card; its
`overlayGrpId` equals `copyFromGrpid` while the effect applies.

## Lifecycle

1. The copy effect begins, typically as an ability resolves:
   `LayeredEffectCreated` introduces the identity, the row appears and the
   permanent is republished with `overlayGrpId`, in one GSM.
2. The row stays while the copy effect applies.
3. When the effect ends, `LayeredEffectDestroyed` names the identity and the
   row is deleted.

## Sibling shapes

[ObjectIdChanged](ObjectIdChanged.md) relates two instance IDs of one card
across a zone change; a copy keeps one instance ID and changes what it looks
like. A token created as a copy is a new object and is not described by this
row.

## Contract

- <a id="CopiedObject.layered"></a>**CopiedObject.layered.**
  `CopiedObject` `MUST` be carried in `persistentAnnotations`, typed also
  `LayeredEffect`, with `effect_id`, `copyFromGrpid` and `LayeredEffectType`
  `CopyObject`.
- <a id="CopiedObject.overlay"></a>**CopiedObject.overlay.**
  While the row is live, the affected object's `overlayGrpId` `MUST` equal
  `copyFromGrpid`.
- <a id="CopiedObject.effect-introduced"></a>**CopiedObject.effect-introduced.**
  When the row first appears on an object that was already public in the
  previous GSM, its `effect_id` `MUST` be an identity that a
  `LayeredEffectCreated` introduced in the same GSM or an earlier one. An
  object hidden from the receiving seat is not covered; see
  [LayeredEffectCreated](LayeredEffectCreated.md#open-points).
- <a id="CopiedObject.ends-with-destroyed"></a>**CopiedObject.ends-with-destroyed.**
  *(provisional)* When the copy effect ends, the GSM that deletes the row
  `MUST` carry `LayeredEffectDestroyed` for its `effect_id`.

## Open points

- What does `Duration` encode?
- How are copy-as-enters effects, such as a Clone, published?
- What does an `affectorId` that is not an object identify?
