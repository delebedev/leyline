---
summary: "TriggerHolder game object: a delayed or recurring trigger anchored in Limbo; identity fields, persistent-annotation ownership and lifecycle families."
entity: object_type/TriggerHolder
---
# TriggerHolder

A `TriggerHolder` represents a delayed or recurring triggered ability while no
single live ability owns its whole lifecycle. It is a public Limbo object that
anchors persistent state before, between or during later
[Ability](Ability.md) instances.

## Object model

| Field | Role |
|---|---|
| `instanceId` | One pending or recurring trigger lifetime. |
| `grpId` | The fixed holder value `5`, not a card definition. |
| `objectSourceGrpId` | Display identity for the holder. Depending on the trigger family it names a card or an ability definition. |
| `parentId` | The originating card or stack object, when that relationship exists. The parent may retire first. |
| `uniqueAbilities[].grpId` | The delayed or recurring ability that can later fire. |

`objectSourceGrpId` is not one identity domain. Read it as source metadata for
the trigger family, not always as a card or always as an ability.

## Zone and visibility

Holders are public members of Limbo. Persistent annotations can use the
holder's ID as `affectorId`, so the client can show a pending card, a linked
permanent or a recurring effect before any live ability exists.

## Lifecycle families

### Pending one-shot trigger

A holder is created when the delayed ability is set up, and persistent
annotations attach to it. When the trigger event happens, the holder is
deleted and a live `Ability` is created. Persistent rows can move their
affector to that ability before they retire after it resolves.

### Recurring trigger

A recurring effect keeps one holder across turns while a short-lived
`Ability` is created and deleted for each recurrence. Those abilities do not
replace the holder.

### Delayed cleanup

A later sacrifice or removal can use a holder to group the permanents it will
affect. These holders share the Limbo identity model but not necessarily the
annotations that delayed-return effects use.

## Sibling shapes

`Ability` is the live object that resolves; `TriggerHolder` owns the longer
pending or recurring interval. [RevealedCard](RevealedCard.md) is the other
protocol utility, and carries temporary card disclosure rather than ability
state.

## Contract

- <a id="TriggerHolder.shape"></a>**TriggerHolder.shape.** A `TriggerHolder`
  object `MUST` have `grpId` `5`, `zoneId` `30` (Limbo) and `Public`
  visibility.
- <a id="TriggerHolder.stable-id"></a>**TriggerHolder.stable-id.** One
  pending or recurring trigger `MUST` keep one holder instance ID for its
  whole interval, and a recurrence's live `Ability` `MUST NOT` replace it.
- <a id="TriggerHolder.before-affector"></a>**TriggerHolder.before-affector.**
  The holder object `MUST` exist in the GSM where a persistent annotation
  first names it as `affectorId`, or earlier.
- <a id="TriggerHolder.per-trigger"></a>**TriggerHolder.per-trigger.** Two
  pending triggers from the same source `MUST` have distinct holders.

## Open points

- Which delayed-trigger families move persistent annotations from the holder
  to the live ability when they fire?
- How does the client tell apart simultaneous holders from one source?
- When does a holder have no `parentId`?
