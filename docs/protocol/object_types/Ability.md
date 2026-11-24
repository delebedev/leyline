---
summary: "Ability game object: an activated or triggered ability on the Stack or in Pending; definition, parent and source identities, and lifecycle."
entity: object_type/Ability
---
# Ability

An `Ability` is an activated or triggered ability given its own object while
it waits on the Stack or in Pending. It keeps separate links to the ability's
definition and to the object it came from.

## Object model

| Field | Role |
|---|---|
| `instanceId` | One ability lifetime. |
| `grpId` | The ability's definition. |
| `parentId` | The instance of the object the ability came from. |
| `objectSourceGrpId` | The source card's definition, used for display and attribution. |
| `zoneId` | Stack or Pending. |

`grpId`, `parentId` and `objectSourceGrpId` are three identity domains. The
parent instance can leave or be replaced while the ability is still live; the
source definition does not stand in for the parent relationship.

## Zone and visibility

Ability objects are public and live on the Stack or in Pending; their ID is a
member of that zone while the ability is live. The source can be in any zone,
or already in Limbo. Its zone at the time is reported by the
`AbilityInstanceCreated` row, not by the ability object.

## Lifecycle

1. `AbilityInstanceCreated` names the new ability's ID in `affectedIds` and
   its source in `affectorId`.
2. An `Ability` object with that ID appears on the Stack or in Pending.
3. Prompts and persistent annotations may use the ability's ID as their
   source or affector while it is live.
4. `ResolutionStart` and `ResolutionComplete` bracket its resolution.
5. `AbilityInstanceDeleted` and `diffDeletedInstanceIds` retire the ability
   and its object.

A mana ability does not use the stack (CR 605.3b). Its
`AbilityInstanceCreated` and `AbilityInstanceDeleted` rows still bracket the
activation, but no `Ability` object exists for it.

## Sibling shapes

[TriggerHolder](TriggerHolder.md) represents a delayed or recurring trigger
before and between live abilities. One holder can outlive several short-lived
`Ability` objects and can hand persistent annotations over to one when it
fires.

## Contract

- <a id="Ability.zone"></a>**Ability.zone.** An `Ability` object `MUST` be
  `Public` and `MUST` be a member of the Stack or the Pending zone.
- <a id="Ability.created"></a>**Ability.created.** In the GSM where an
  `Ability` object first appears, an `AbilityInstanceCreated` row `MUST` name
  its instance ID in `affectedIds`, with the source object's instance ID as
  `affectorId`.
- <a id="Ability.definition"></a>**Ability.definition.** An `Ability`
  object's `grpId` `MUST` be the ability's definition; the source card's
  definition belongs in `objectSourceGrpId`.
- <a id="Ability.parent"></a>**Ability.parent.** An `Ability` object `MUST`
  carry `parentId` naming the instance of the object it came from.
- <a id="Ability.mana-no-object"></a>**Ability.mana-no-object.** A mana
  ability's activation `MUST NOT` produce an `Ability` object.

## Open points

- Which ability families wait in Pending, and for how long?
- When the source has already been replaced, does `parentId` name its old or
  its new instance ID?
