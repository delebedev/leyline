---
summary: "Impending: an alternative-cost cast whose permanent enters with time counters, is not a creature while it has them, and loses one at each of its controller's end steps; cast marker, counters and the end-step trigger."
entity: mechanic/Impending
---
# Impending

Impending is an alternative cost. A permanent cast for its impending cost
enters with time counters, is not a creature while it has any, and loses one
at the beginning of its controller's end step. The protocol carries the cast
as an alternative-cost [Cast](../actions/Cast.md) recorded by
[CastingTimeOption](../annotations/CastingTimeOption.md). The countdown is
`CounterAdded` and `CounterRemoved` rows for time counters on one battlefield
instance, removed by a triggered [Ability](../object_types/Ability.md).

## Rules grounding

"Impending N—[cost]" is an alternative cost, a replacement effect that makes
the permanent enter with N time counters if that cost was paid, a static
ability that keeps it from being a creature while it has a time counter, and
a trigger that removes one time counter at the beginning of its controller's
end step (CR 702.176a).

## Surfaces

| Surface | Entity pages |
|---|---|
| Alternative-cost cast | [Cast](../actions/Cast.md), [UserActionTaken](../annotations/UserActionTaken.md) |
| Stack marker | [CastingTimeOption](../annotations/CastingTimeOption.md) `type` `13` |
| Time counters on entry | `CounterAdded`, `Counter` |
| End-step trigger | [Ability](../object_types/Ability.md), `CounterRemoved` |

## Cast lifecycle

1. The card in the hand is offered as a `Cast` whose `alternativeGrpId` is
   its impending ability.
2. The card moves Hand to Stack with `CastSpell` under a new instance ID, and
   the stack spell carries a `CastingTimeOption` row of `type` `13` with the
   impending ability in `alternateCostGrpId`.
3. `UserActionTaken` records `actionType` `1`, `abilityGrpId` `0` and the
   impending ability in `alternativeGrpId`.

## Countdown lifecycle

1. The spell resolves onto the battlefield with `Resolve`. With no
   replacement effect changing the number of counters, `CounterAdded` puts N
   time counters on the permanent, with `counter_type` `5` and
   `transaction_amount` N.
2. At each of its controller's end steps while it has time counters, a new
   triggered ability is created from the permanent. When it resolves while
   the permanent is still on the battlefield with a time counter, its
   resolution carries `CounterRemoved` on the permanent with `counter_type` `5` and
   `transaction_amount` `1`, and then the ability is deleted.
3. The permanent keeps its instance ID throughout. When the last counter is
   removed it becomes a creature without moving or changing ID.

## Sibling shapes

Suspend also counts down with time counters, but in exile, and it ends in a
cast. An impending permanent is on the battlefield from the start, and its
countdown changes only whether it is a creature.

## Contract

- <a id="Impending.cast-marker"></a>**Impending.cast-marker.** A spell cast
  for its impending cost `MUST` carry a `CastingTimeOption` row of `type`
  `13` with the impending ability in `alternateCostGrpId`.
- <a id="Impending.accepted"></a>**Impending.accepted.** *(provisional)* An
  accepted impending cast `MUST` be recorded by `UserActionTaken` with
  `actionType` `1`, `abilityGrpId` `0` and the impending ability in
  `alternativeGrpId`.
- <a id="Impending.enter-counters"></a>**Impending.enter-counters.**
  *(provisional)* When no replacement effect changes the number of time
  counters it enters with, the resolution of an impending spell `MUST` carry
  `CounterAdded` on the permanent with `counter_type` `5` and
  `transaction_amount` equal to its impending number.
- <a id="Impending.tick"></a>**Impending.tick.** When an impending
  permanent's end-step trigger resolves while the permanent is still on the
  battlefield with a time counter, the resolution `MUST` carry `CounterRemoved` on it with
  `counter_type` `5` and `transaction_amount` `1`.
- <a id="Impending.keeps-instance"></a>**Impending.keeps-instance.** The
  permanent `MUST` keep its instance ID while time counters are removed,
  including the last one.

## Open points

- Which row tells the client that an impending permanent is not a creature
  while it has time counters, and how does that row end?
- What does `transaction_amount` carry when a replacement effect changes the
  number of time counters the permanent enters with: the number placed or
  the impending number?
- What does the end-step trigger carry when the permanent has left the
  battlefield or has no time counter left as it resolves?
- Does every end-step trigger carry a `TriggeringObject` row?
