---
summary: "Extort: a cast trigger with an optional {W/B} payment that drains each opponent; the surfaces it uses and what is not yet characterised."
entity: mechanic/Extort
---
# Extort

Extort is a triggered ability that fires whenever its controller casts a
spell and offers an optional payment. If it is paid, each opponent loses one
life and the controller gains the life lost. The trigger travels as an
ordinary triggered [Ability](../object_types/Ability.md); the payment and the
life change are not yet characterised.

## Rules grounding

Extort means "Whenever you cast a spell, you may pay {W/B}. If you do, each
opponent loses 1 life and you gain life equal to the total life lost this
way" (CR 702.101a). Each instance triggers separately (CR 702.101b).

## Surfaces

| Surface | Entity pages |
|---|---|
| Cast trigger | [Ability](../object_types/Ability.md), [AbilityInstanceCreated](../annotations/AbilityInstanceCreated.md), [TriggeringObject](../annotations/TriggeringObject.md) |
| Optional payment | Not characterised |
| Life change | `ModifiedLife` |

## Trigger lifecycle

1. The extort permanent's controller casts a spell. The extort ability is
   created with `AbilityInstanceCreated` in the GSM of the cast.
2. The trigger goes on the stack. Its controller decides whether to pay
   {W/B} as it resolves.

## Sibling shapes

Other "you may pay" triggers share the shape of the payment decision. Extort
differs only in being triggered by the controller's own cast.

## Contract

No statement is specific to extort yet.

## Open points

- Through which prompt is the optional {W/B} payment offered, and how is a
  declined payment answered?
- In which GSM do the `ModifiedLife` rows of the drain appear?
- Does the trigger carry a `TriggeringObject` row naming the cast spell?
