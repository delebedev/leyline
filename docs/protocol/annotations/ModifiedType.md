---
summary: "ModifiedType annotation: persistent type-line change by a continuous effect; source, temporary-type, perpetual and type-replacement branches, effect_id link and retirement."
entity: annotation/ModifiedType
---
# ModifiedType

## Meaning

A continuous effect can change an object's card types, subtypes or
supertypes: a Vehicle becomes an artifact creature when crewed, a land becomes
a creature, an Equipment with reconfigure stops being a creature while
attached. `ModifiedType` publishes that the object's type line is under such
an effect and ties it to the effect's identity. The object's own `cardTypes`,
`subtypes` and `superTypes` carry the resulting type line; the row does not
list the types.

## Rules grounding

Type-changing effects apply in layer 4 (CR 613.1d). The same effect can also
change power and toughness, abilities or color in other layers; each layer is
a separate effect with its own identity (CR 613.6).

## Variants

Persistent only, typed `ModifiedType` and `LayeredEffect`, with `effect_id`.
Branches are told apart by their extra detail keys:

| Branch | Discriminator | Examples |
|---|---|---|
| Plain | `effect_id` only | Static type changes: a Spacecraft at its station threshold, an attached reconfigure Equipment that is not a creature, an impending permanent that is not a creature. |
| Ability-sourced | `sourceAbilityGRPID` | A land animated by a resolving ability. |
| Temporary card type | `temporaryCardType`, usually with `sourceAbilityGRPID` | An artifact that becomes an artifact creature until end of turn. |
| Perpetual | `perpetualAffectedId` with `perpetualSuperType` or `perpetualSubType` | A digital-only effect that perpetually adds a supertype or subtype. |
| Type replacement | Co-typed `RemoveAbility`, with one instance-ID key per affected object | An effect that turns a permanent into a basic land type and removes its abilities. |

## Fields

| Field | Role |
|---|---|
| `affectorId` | The object the effect comes from. A self-applied change, such as crew or reconfigure, names the affected object itself. |
| `affectedIds` | The objects whose type line changes; usually one. |
| `effect_id` | The effect identity introduced by [LayeredEffectCreated](LayeredEffectCreated.md). |
| `sourceAbilityGRPID` | When present, the definition of the ability that created the effect. |
| `temporaryCardType` | A list of `CardType` values (for example `1` Artifact, `2` Creature, `5` Land) naming the types the effect projects. The list can repeat values; see Open points. |
| `perpetualAffectedId` | The perpetually changed object; one of `affectedIds`. |
| `perpetualSuperType`, `perpetualSubType` | The supertype or subtype the perpetual effect adds. |
| `<instanceId>` keys | Present only on the type-replacement branch; they belong to [RemoveAbility](RemoveAbility.md). |

## Identity model

`affectorId`, `affectedIds` and `perpetualAffectedId` are instance IDs.
`sourceAbilityGRPID` is an ability definition. `effect_id` is an effect
identity, shared with the `LayeredEffectCreated` and `LayeredEffectDestroyed`
that bracket it. An effect that changes type and power together publishes
separate rows, each with its own `effect_id`.

## Lifecycle

1. The effect begins, typically while a spell or ability resolves:
   `LayeredEffectCreated` introduces the identity, and in the same GSM the
   `ModifiedType` row appears and the object is published with its new type
   line.
2. The row stays in `persistentAnnotations` while the effect applies.
3. When an effect created by a spell or ability ends while the object stays
   on the battlefield, `LayeredEffectDestroyed` names the identity, the row
   is deleted and the object is republished with its original type line, in
   one GSM. A static type change that stops applying, such as reconfigure's
   when the Equipment becomes unattached, can delete its plain row without a
   `LayeredEffectDestroyed`.
4. When the object leaves the battlefield, the row is deleted with its
   `ObjectIdChanged` and `ZoneTransfer`.
5. An effect that is recomputed destroys its old identity before introducing
   the new one in the same GSM.

## Sibling shapes

- `ModifiedPower`, `ModifiedToughness` and [AddAbility](AddAbility.md)
  publish other layers of the same rules operation under their own
  `effect_id`.
- [RemoveAbility](RemoveAbility.md) shares a row with `ModifiedType` only when
  one effect both replaces types and removes abilities.
- `CrewedThisTurn` records that a Vehicle was crewed; the type change itself
  is this row.

## Contract

- <a id="ModifiedType.persistent"></a>**ModifiedType.persistent.**
  `ModifiedType` `MUST` be carried in `persistentAnnotations`, typed also
  `LayeredEffect`, with an `effect_id` detail.
- <a id="ModifiedType.effect-introduced"></a>**ModifiedType.effect-introduced.**
  The row's `effect_id` `MUST` be an identity that a `LayeredEffectCreated`
  introduced in the same GSM or an earlier one.
- <a id="ModifiedType.perpetual-affected"></a>**ModifiedType.perpetual-affected.**
  When `perpetualAffectedId` is present, its value `MUST` be one of
  `affectedIds`.
- <a id="ModifiedType.expiry-destroys"></a>**ModifiedType.expiry-destroys.**
  When the effect of a row carrying `sourceAbilityGRPID` or
  `temporaryCardType` ends while the affected object stays on the
  battlefield, the GSM that deletes the row `MUST` carry
  `LayeredEffectDestroyed` for its `effect_id`.

## Open points

- Why does `temporaryCardType` repeat its values, and what makes the list
  grow when the row is republished?
- What selects the plain branch over the ability-sourced branch for
  effects created by a resolving ability?
- When does a plain row end with `LayeredEffectDestroyed`, and when without?
