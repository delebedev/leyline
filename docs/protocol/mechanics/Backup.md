---
summary: "Backup: an enters trigger that puts +1/+1 counters on a target creature; the per-card trigger, its target step keyed by the shared Backup ability 287, and the counter at resolution."
entity: mechanic/Backup
---
# Backup

Backup N is a triggered ability. When the creature enters, a triggered
[Ability](../object_types/Ability.md) targets a creature and puts N +1/+1
counters on it; if the target is another creature, it also gains the
source's abilities printed below Backup until end of turn. The trigger has
the card's own Backup ability as `grpId`, while its target step is keyed by
the shared Backup ability `287`.

## Rules grounding

Backup N means "When this creature enters, put N +1/+1 counters on target
creature. If that's another creature, it also gains the non-backup abilities
of this creature printed below this one until end of turn" (CR 702.165a).

## Surfaces

| Surface | Entity pages | Backup values |
|---|---|---|
| Enters trigger | [AbilityInstanceCreated](../annotations/AbilityInstanceCreated.md), [TriggeringObject](../annotations/TriggeringObject.md) | The card's Backup ability; `source_zone` `28` |
| Target step | [SelectTargetsReq](../prompts/SelectTargetsReq.md), [TargetSpec](../annotations/TargetSpec.md) | `abilityGrpId` `287`, `promptId` `1010` |
| Counters | `CounterAdded` | `counter_type` `1`, `transaction_amount` N |

## Trigger lifecycle

1. The GSM in which the creature spell resolves to the Battlefield carries
   `AbilityInstanceCreated` from the new permanent with `source_zone` `28`
   and a persistent `TriggeringObject` row from the ability to the permanent.
   The ability has the card's Backup ability as `grpId`.
2. The trigger's target is chosen through `PlayerSelectingTargets` and
   `PlayerSubmittedTargets`; the persistent `TargetSpec` row carries
   `abilityGrpId` `287`, `promptId` `1010` and the chosen creature.

## Resolution lifecycle

1. `ResolutionStart` with the card's Backup ability as `grpid`.
2. `CounterAdded` from the ability to the target with `counter_type` `1`
   and `transaction_amount` N, and the counter's power and toughness change.
3. `ResolutionComplete`, then `AbilityInstanceDeleted`. The
   `TriggeringObject` and `TargetSpec` rows are deleted in the same GSM.

## Sibling shapes

Mentor and other keyword triggers that target also key their target step by
a shared keyword ability rather than by the card's ability.

## Contract

- <a id="Backup.trigger"></a>**Backup.trigger.** *(provisional)* The Backup
  trigger `MUST` be created from the entering creature with `source_zone`
  `28` in the GSM that puts it onto the Battlefield, with the card's Backup
  ability as `grpId`.
- <a id="Backup.target-spec"></a>**Backup.target-spec.** *(provisional)* The
  Backup trigger's `TargetSpec` row `MUST` carry `abilityGrpId` `287`.
- <a id="Backup.counters"></a>**Backup.counters.** *(provisional)* The
  resolution `MUST` carry `CounterAdded` on the target with `counter_type`
  `1` and `transaction_amount` N.

## Open points

- How are the abilities granted to another target creature published?
- When the creature targets itself, the counter's `PowerToughnessModCreated`
  row names the creature, not the trigger, as `affectorId`; is that also true
  for another target?
