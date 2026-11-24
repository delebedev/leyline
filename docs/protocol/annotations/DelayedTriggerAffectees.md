---
summary: "DelayedTriggerAffectees annotation: persistent link from a pending delayed trigger to the objects it will act on; TriggerHolder-to-ability handoff under one row id, removesFromZone and mana branches, retirement."
entity: annotation/DelayedTriggerAffectees
---
# DelayedTriggerAffectees

## Meaning

A delayed triggered ability is created now and triggers later (CR 603.7),
often to act on specific objects: exile a warped creature at the next end
step, sacrifice the attacking tokens created by mobilize. The client shows
which objects such a pending trigger will affect. `DelayedTriggerAffectees`
publishes that relation while the trigger waits and while the fired ability
is on the stack.

## Variants

Persistent only, never combined with another type.

| Branch | Shape | Example |
|---|---|---|
| Removal | `removesFromZone` `1` | Warp exiling a creature at end step; tokens sacrificed at end step |
| Plain | `abilityGrpId` only | A delayed trigger that does not remove its objects |
| Mana | `mana_to_add` | A delayed trigger that adds mana later |

## Fields

| Field | Role |
|---|---|
| `affectorId` | The pending trigger's [TriggerHolder](../object_types/TriggerHolder.md) while it waits; the fired [Ability](../object_types/Ability.md) once it is on the stack. |
| `affectedIds` | The objects the delayed trigger will act on. |
| `abilityGrpId` | The delayed ability's definition. It is not the holder's own `grpId`. |
| `removesFromZone` | `1` when the trigger will move the objects out of their zone. |
| `mana_to_add` | On the mana branch, the amount of mana the trigger adds. |

## Identity model

The row's `id` is stable across the trigger's whole life, while
`affectorId` moves from one domain to another: first the holder, an object
in Limbo, then the ability instance on the stack. `affectedIds` are card or
token instance IDs. `abilityGrpId` is a definition shared by every trigger of
that kind.

## Lifecycle

1. The effect that sets up the delayed trigger resolves. A `TriggerHolder`
   appears in Limbo, and the row links it to the objects.
2. The row stays while the trigger waits.
3. The trigger fires: [AbilityInstanceCreated](AbilityInstanceCreated.md)
   introduces the ability, the holder is removed through
   `diffDeletedInstanceIds`, and the row is republished under the same `id`
   with the ability as `affectorId`, all in one GSM.
4. The ability resolves: the row is deleted in the GSM that carries its
   [ResolutionComplete](ResolutionComplete.md).

A trigger that fires as soon as it is created can skip the holder: its row
first appears with the ability as `affectorId`.

## Sibling shapes

- `TemporaryPermanent` marks a permanent that a delayed ability will remove;
  it can accompany this row but is not always present.
- `DisplayCardUnderCard` can show the same relation as a presentation link.
- [TriggeringObject](TriggeringObject.md) links an ability to what caused it;
  this row links a delayed ability to what it will affect.

## Contract

- <a id="DelayedTriggerAffectees.persistent"></a>**DelayedTriggerAffectees.persistent.**
  `DelayedTriggerAffectees` `MUST` be carried in `persistentAnnotations`
  with an `abilityGrpId` detail.
- <a id="DelayedTriggerAffectees.holder-affector"></a>**DelayedTriggerAffectees.holder-affector.**
  While the trigger waits, `affectorId` `MUST` name a `TriggerHolder` in
  Limbo.
- <a id="DelayedTriggerAffectees.handoff"></a>**DelayedTriggerAffectees.handoff.**
  When the trigger fires, the row `MUST` be republished under the same `id`
  with `affectorId` set to the ability that `AbilityInstanceCreated`
  introduces in that GSM.
- <a id="DelayedTriggerAffectees.retired-on-resolution"></a>**DelayedTriggerAffectees.retired-on-resolution.**
  A row whose `affectorId` is an ability `MUST` be deleted in the GSM that
  carries that ability's `ResolutionComplete`.

## Open points

- What exactly does `removesFromZone` select?
- How is a pending trigger's row retired when the trigger never fires?
