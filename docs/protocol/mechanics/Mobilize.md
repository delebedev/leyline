---
summary: "Mobilize: an attack trigger that creates tapped and attacking Warrior tokens and sacrifices them at the next end step; count-specific ability IDs, one TokenCreated per token, the delayed-cleanup rows and the sacrifice."
entity: mechanic/Mobilize
---
# Mobilize

Mobilize N is an attack trigger: it creates N tapped and attacking 1/1 red
Warrior tokens and sacrifices them at the beginning of the next end step. The
trigger is a triggered [Ability](../object_types/Ability.md). Its resolution
creates [Token](../object_types/Token.md) objects and a delayed cleanup held
by a [TriggerHolder](../object_types/TriggerHolder.md), which is recorded by
[DelayedTriggerAffectees](../annotations/DelayedTriggerAffectees.md) and
[TemporaryPermanent](../annotations/TemporaryPermanent.md).

## Rules grounding

"Mobilize N" means "Whenever this creature attacks, create N tapped and
attacking 1/1 red Warrior creature tokens. Sacrifice them at the beginning of
the next end step." (CR 702.181a).

## Surfaces

| Surface | Entity pages |
|---|---|
| Attack trigger | [Ability](../object_types/Ability.md), [AbilityInstanceCreated](../annotations/AbilityInstanceCreated.md), [TriggeringObject](../annotations/TriggeringObject.md) |
| Warrior tokens | [Token](../object_types/Token.md), `TokenCreated` |
| Delayed cleanup | [TriggerHolder](../object_types/TriggerHolder.md), [DelayedTriggerAffectees](../annotations/DelayedTriggerAffectees.md), [TemporaryPermanent](../annotations/TemporaryPermanent.md) |
| Sacrifice | [ZoneTransfer](../annotations/ZoneTransfer.md) `Sacrifice`, `TokenDeleted` |

Each count has its own trigger ability and its own cleanup ability:

| N | Trigger | Cleanup |
|---:|---:|---:|
| 1 | `188698` | `189931` |
| 3 | `188696` | `189930` |

## Trigger lifecycle

1. Attackers are declared. `AbilityInstanceCreated` names the mobilize
   creature as `affectorId` and the ability as affected ID, with `source_zone`
   `28`, and a persistent `TriggeringObject` row points from the ability to
   the creature. Simultaneous mobilize triggers can wait in Pending.
2. In a later GSM of the same step, the ability resolves: `ResolutionStart`
   carries the trigger ability ID, one `TokenCreated` per token names the
   ability as `affectorId`, then `ResolutionComplete` and
   `AbilityInstanceDeleted` close it.
3. The tokens enter tapped and attacking. One persistent
   `EnteredZoneThisTurn` row groups them.

## Cleanup lifecycle

1. The resolution GSM creates a trigger holder in Limbo with `grpId` `5`,
   whose ability is the cleanup ability. One persistent
   `DelayedTriggerAffectees` row with the holder as `affectorId` groups all
   the tokens, with `removesFromZone` `1` and the cleanup ability. One
   `TemporaryPermanent` row per token carries the same holder and cleanup
   ability.
2. At the next end step the cleanup resolves: `ResolutionStart` and
   `ResolutionComplete` carry the cleanup ability ID.
3. Each token still on the battlefield under the cleanup controller's
   control gets a new instance ID, moves
   Battlefield to Graveyard with `category` `Sacrifice` and then gets
   `TokenDeleted`. A token that already left gets no second transfer.

When no player takes priority in the end step, the cleanup can appear in the
first GSM of the next turn instead.

## Sibling shapes

Evoke's sacrifice also uses a temporary row, but on the evoked permanent
itself and through an enters trigger. Other "create tokens, sacrifice them at
end step" effects use the same cleanup rows with their own abilities.

## Contract

- <a id="Mobilize.ability-ids"></a>**Mobilize.ability-ids.** The trigger's
  `ResolutionStart` and `ResolutionComplete` `MUST` carry the count-specific
  trigger ability, and the cleanup rows `MUST` carry the paired cleanup
  ability.
- <a id="Mobilize.token-rows"></a>**Mobilize.token-rows.** The resolution
  `MUST` carry one `TokenCreated` per token, with the ability as `affectorId`.
- <a id="Mobilize.cleanup-rows"></a>**Mobilize.cleanup-rows.** The resolution
  GSM `MUST` create one `DelayedTriggerAffectees` row over all the tokens and
  one `TemporaryPermanent` row per token, both with the trigger holder as
  `affectorId`.
- <a id="Mobilize.sacrifice"></a>**Mobilize.sacrifice.** When the cleanup
  sacrifices a token and it goes to the graveyard, the move `MUST` be
  Battlefield to Graveyard with `category` `Sacrifice`.

The holder is stated by [TriggerHolder](../object_types/TriggerHolder.md)
and the grouping by
[DelayedTriggerAffectees.holder-affector](../annotations/DelayedTriggerAffectees.md#DelayedTriggerAffectees.holder-affector).

## Open points

- Which IDs does mobilize 2 use?
- What does the cleanup carry for a token another player controls by then,
  which cannot be sacrificed?
