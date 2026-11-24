---
summary: "Mentor: an attack trigger that targets an attacking creature with lesser power and puts a +1/+1 counter on it; ability 171, the SelectTargetsReq prompt 2247 and the counter resolution."
entity: mechanic/Mentor
---
# Mentor

Mentor is an attack trigger: when the creature attacks, it puts a +1/+1
counter on target attacking creature with lesser power. The trigger is a
triggered [Ability](../object_types/Ability.md) with the shared ability ID
`171`. Its target is chosen through a
[SelectTargetsReq](../prompts/SelectTargetsReq.md), and its resolution is an
ordinary counter addition.

## Rules grounding

Mentor means "Whenever this creature attacks, put a +1/+1 counter on target
attacking creature with power less than this creature's power."
(CR 702.134a). Each instance triggers separately (CR 702.134b).

## Surfaces

| Surface | Entity pages |
|---|---|
| Attack trigger | [Ability](../object_types/Ability.md), [AbilityInstanceCreated](../annotations/AbilityInstanceCreated.md), [TriggeringObject](../annotations/TriggeringObject.md) |
| Target | [SelectTargetsReq](../prompts/SelectTargetsReq.md), `TargetSpec` |
| Counter | `CounterAdded`, `Counter`, `PowerToughnessModCreated` |

## Trigger lifecycle

1. Attackers are declared. `AbilityInstanceCreated` names the mentor creature
   as `affectorId` and the ability as affected ID, with `source_zone` `28`, and
   a persistent `TriggeringObject` row points from the ability to the
   creature. With several mentor creatures, the abilities can wait in Pending
   and are put on the stack and targeted one at a time.
2. The controller receives a `SelectTargetsReq` with `abilityGrpId` `171`. Its
   one target group has `targetingAbilityGrpId` `171`, prompt `2247` with a
   `CardId` parameter naming the mentor creature, and `minTargets` and
   `maxTargets` `1`. The candidates are attacking creatures with lesser power.
3. The chosen target is recorded by a persistent `TargetSpec` row with
   `abilityGrpId` `171`, `index` `1` and `promptId` `2247`.

## Resolution lifecycle

1. `ResolutionStart` carries `grpid` `171`.
2. `CounterAdded` names the target with `counter_type` `1` and
   `transaction_amount` `1`, and `PowerToughnessModCreated` reports `power`
   `1` and `toughness` `1`.
3. `ResolutionComplete` carries `grpid` `171`, then `AbilityInstanceDeleted`
   retires the ability.

## Sibling shapes

Training also triggers on attacking and adds a +1/+1 counter, but to the
attacking creature itself and without a target. [Firebending](Firebending.md)
is an attack trigger that adds mana instead.

## Contract

- <a id="Mentor.target-prompt"></a>**Mentor.target-prompt.** A mentor
  trigger's `SelectTargetsReq` `MUST` carry `abilityGrpId` `171` and one
  target group with `targetingAbilityGrpId` `171`, prompt `2247`, and
  `minTargets` and `maxTargets` `1`.
- <a id="Mentor.prompt-source"></a>**Mentor.prompt-source.** That prompt's
  `CardId` parameter `MUST` name the mentor creature.
- <a id="Mentor.counter"></a>**Mentor.counter.** *(provisional)* When the
  target is still legal and no replacement effect changes the number of
  counters, the resolution `MUST` carry `CounterAdded` on the target with `counter_type`
  `1` and `transaction_amount` `1`.

## Open points

- What does `transaction_amount` carry when a replacement effect changes
  the number of counters placed?
- What does the request's `allowCancel` depend on?
- How is a mentor trigger whose target became illegal retired?
