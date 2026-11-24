---
summary: "AddAbility annotation: persistent grant of an ability or keyword to objects by a continuous effect; single, packed and multi-object shapes, identities and the effect_id lifecycle."
entity: annotation/AddAbility
---
# AddAbility

## Meaning

A continuous effect can give an object an ability it does not have on its own,
such as flying from an aura or haste from a spell (CR 613.1f).
`AddAbility` publishes the grant as persistent state and ties it to the
effect's identity, so the grant lasts exactly as long as the effect.

## Variants

Persistent only. A grant tied to an effect identity is typed both `AddAbility`
and `LayeredEffect`. Its shape depends on how many abilities and objects one
effect covers:

| Shape | `grpid` | `affectedIds` | Example |
|---|---|---|---|
| Single grant | One entry | One object | An Equipment giving its creature first strike. |
| Packed grant | One entry per granted ability, aligned with `originalAbilityObjectZcid` | One object | An Aura giving flying and first strike. |
| Shared grant | One entry | Several objects, with one `UniqueAbilityId` entry per object | An anthem giving every creature you control vigilance. |

Branch-specific keys, such as `count` and `counter_type` for grants that depend
on counters, appear only on their branches.

## Fields

| Field | Role |
|---|---|
| `affectorId` | The object that supplies the grant, such as the Aura, the Equipment or the permanent with the static ability. |
| `affectedIds` | The objects that receive the ability. |
| `grpid` | The granted ability's definition. |
| `UniqueAbilityId` | The ability slot the grant occupies on the receiving object. |
| `originalAbilityObjectZcid` | The instance ID of the object the granting ability belongs to; it equals `affectorId` on ordinary grants. |
| `sourceAbilityGRPID` | When present, the definition of the ability that produced the grant. |
| `effect_id` | The effect identity introduced by [LayeredEffectCreated](LayeredEffectCreated.md). |

## Identity model

`grpid` and `sourceAbilityGRPID` are card-database identities.
`UniqueAbilityId` is a per-object ability slot, `originalAbilityObjectZcid` and
the envelope IDs are instance IDs, and `effect_id` is an effect identity. None
of these domains is interchangeable with another, even when two values happen
to be equal.

## Lifecycle

1. The effect begins: `LayeredEffectCreated` introduces its identity.
2. The `AddAbility` row carries that identity as `effect_id` and stays in
   `persistentAnnotations` while the grant applies.
3. The effect ends: `LayeredEffectDestroyed` names the identity and the row is
   deleted.

## Sibling shapes

`RemoveAbility` publishes the opposite modification: abilities an effect takes
away. `DynamicAbility` can accompany a grant when the granted ability needs a
generated action entry with an explicit cost; it is a separate row, not a
second copy of the grant. Counters that grant keywords use this row with
`counter_type`, not a separate type.

## Contract

- <a id="AddAbility.persistent"></a>**AddAbility.persistent.**
  `AddAbility` `MUST` be carried in `persistentAnnotations`.
- <a id="AddAbility.layered-cotype"></a>**AddAbility.layered-cotype.**
  A row that carries `effect_id` `MUST` also be typed `LayeredEffect`, and a
  row typed `LayeredEffect` `MUST` carry `effect_id`.

## Open points

- What selects whether a grant carries `sourceAbilityGRPID`?
- How is `UniqueAbilityId` allocated, and how do grants of the same ability
  from independent sources coexist on one object?
- How do `UniqueAbilityId` entries align with `grpid` and `affectedIds` when a
  row grants several abilities to several objects?
- What does a row without `effect_id` and without `LayeredEffect` represent?
