---
summary: "Decayed: an attack trigger with the shared ability 214 that sets up an end-of-combat sacrifice; the Limbo trigger holder, the TemporaryPermanent row, and the cleanup ability that sacrifices the attacker."
entity: mechanic/Decayed
---
# Decayed

A Decayed creature can't block, and when it attacks it is sacrificed at end
of combat. The attack half is two abilities: a trigger with the shared
Decayed ability `214` resolves into a delayed cleanup held by a
[TriggerHolder](../object_types/TriggerHolder.md) and announced by a
[TemporaryPermanent](../annotations/TemporaryPermanent.md) row; at end of
combat the cleanup ability sacrifices the creature.

## Rules grounding

Decayed means "This creature can't block" and "When this creature attacks,
sacrifice it at end of combat" (CR 702.147a).

## Surfaces

| Surface | Entity pages | Decayed values |
|---|---|---|
| Attack trigger | [AbilityInstanceCreated](../annotations/AbilityInstanceCreated.md), [TriggeringObject](../annotations/TriggeringObject.md) | `grpId` `214`, `source_zone` `28` |
| Delayed cleanup | [TriggerHolder](../object_types/TriggerHolder.md), [TemporaryPermanent](../annotations/TemporaryPermanent.md) | Holder `objectSourceGrpId` `214`; row `AbilityGrpId` the cleanup ability |
| Sacrifice | [ZoneTransfer](../annotations/ZoneTransfer.md) | Battlefield to Graveyard, `category` `Sacrifice` |

## Attack lifecycle

1. The GSM that declares the attacker carries `AbilityInstanceCreated`
   from it with `source_zone` `28` and a persistent `TriggeringObject` row
   from the ability to the attacker. The ability has `grpId` `214`.
2. When the trigger resolves while the attacker is still on the battlefield,
   the GSM carries `ResolutionStart` and
   `ResolutionComplete` with `grpid` `214` and `AbilityInstanceDeleted`, and
   creates a persistent `TemporaryPermanent` row from a `TriggerHolder` in
   Limbo to the attacker, with `AbilityGrpId` the cleanup ability. The holder
   has `objectSourceGrpId` `214`, the attacker as `parentId` and the cleanup
   ability in `uniqueAbilities`.
3. Until end of combat, the `TemporaryPermanent` row is republished in every
   GSM under a new annotation ID, the previous ID deleted.

## Cleanup lifecycle

1. The GSM that enters the end of combat step creates the cleanup ability
   with `AbilityInstanceCreated` from the attacker, and deletes the
   `TemporaryPermanent` and `TriggeringObject` rows.
2. With the attacker still on the battlefield, the cleanup resolves with
   `ResolutionStart` for its `grpid`, `ObjectIdChanged` and a Battlefield-to-Graveyard `ZoneTransfer` with
   `category` `Sacrifice` and the cleanup ability as `affectorId`, then
   `ResolutionComplete` and `AbilityInstanceDeleted`.

## Sibling shapes

Other "sacrifice it at end of combat" and "at the beginning of the next end
step" effects use the same holder and `TemporaryPermanent` row with their own
ability. Decayed is identified by `214` on the trigger and the holder.

## Contract

- <a id="Decayed.trigger"></a>**Decayed.trigger.** *(provisional)* The
  Decayed attack trigger `MUST` have `grpId` `214` and be created from the
  attacker with `source_zone` `28`, in the GSM that declares it as an
  attacker.
- <a id="Decayed.holder"></a>**Decayed.holder.** *(provisional)* When the
  resolution schedules the end-of-combat sacrifice of the attacker, it `MUST`
  create a persistent `TemporaryPermanent` row from a `TriggerHolder` whose
  `objectSourceGrpId` is `214` to the attacker, with `AbilityGrpId` the
  cleanup ability.
- <a id="Decayed.sacrifice"></a>**Decayed.sacrifice.** *(provisional)* When
  the cleanup ability sacrifices the attacker at end of combat and it goes to
  the graveyard, the move `MUST` be Battlefield to Graveyard with `category`
  `Sacrifice` and the cleanup ability as `affectorId`.

## Open points

- How is "can't block" published: is the creature left out of the blocker
  offers, or offered and refused?
- Is the cleanup ability the same for every Decayed card?
- What do the trigger and the cleanup carry when the attacker is not
  sacrificed into the graveyard, for example because it left the battlefield
  first?
- Why is the trigger's `AbilityInstanceDeleted` repeated in the cleanup
  birth GSM?
