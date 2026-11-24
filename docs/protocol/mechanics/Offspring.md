---
summary: "Offspring mechanic: an optional additional cost chosen in CastingTimeOptionsReq, its AdditionalCost marker, and the enters trigger that creates a 1/1 token copy."
entity: mechanic/Offspring
---
# Offspring

Offspring is an optional additional cost of a creature spell. If it was paid,
the creature has an enters trigger that creates a 1/1 token copy of it. The
mechanic uses an optional-cost
[CastingTimeOptionsReq](../prompts/CastingTimeOptionsReq.md), an
`AdditionalCost` [CastingTimeOption](../annotations/CastingTimeOption.md), and
a triggered [Ability](../object_types/Ability.md) whose resolution creates a
[Token](../object_types/Token.md).

## Rules grounding

CR 702.175a defines offspring as an optional additional cost and a triggered
ability that creates a 1/1 token copy of the permanent when it enters, if the
cost was paid. CR 702.175b treats each instance separately.

## Surfaces

| Surface | Entity pages |
|---|---|
| Choosing to pay | [CastingTimeOptionsReq](../prompts/CastingTimeOptionsReq.md) optional-cost family |
| Paid marker | [CastingTimeOption](../annotations/CastingTimeOption.md) `AdditionalCost` |
| Enters trigger | [Ability](../object_types/Ability.md), [AbilityInstanceCreated](../annotations/AbilityInstanceCreated.md), [TriggeringObject](../annotations/TriggeringObject.md) |
| Token copy | [Token](../object_types/Token.md), `TokenCreated` |

## Cast lifecycle

1. The cast moves the spell Hand to Stack with `category` `CastSpell`.
2. The caster receives a `CastingTimeOptionsReq` with an `AdditionalCost` row
   whose `grpId` is the offspring ability, then a `Done` row. The offspring
   ability names the offspring cost, not the card.
3. Answering with the offspring row creates a persistent `CastingTimeOption`
   on the spell with `type` `5` and `additionalCostGrpId` the offspring
   ability. Payment and the cast's `UserActionTaken` follow.

## Trigger lifecycle

1. The spell's resolution GSM moves it to the Battlefield, deletes the
   `CastingTimeOption` row, and creates the offspring trigger: an `Ability`
   whose `grpId` is the same offspring ability, with
   `AbilityInstanceCreated` from the permanent and a `TriggeringObject` row
   naming the permanent.
2. The trigger's resolution GSM carries `ResolutionStart`, `TokenCreated` from
   the trigger to the new token, `ResolutionComplete` and
   `AbilityInstanceDeleted`, in that order. The token has its own token
   `grpId`, not the parent's, and the trigger as `parentId`.

Answering `Done` casts the creature without the cost: no `CastingTimeOption`
row, no trigger and no token follow.

## Sibling shapes

Teamwork and bargain use the same optional-cost request. Teamwork follows it
with a creature payment; bargain's paid marker is its own `CastingTimeOption`
type. Neither puts a trigger on the permanent.

## Contract

- <a id="Offspring.option-row"></a>**Offspring.option-row.** The offspring
  choice `MUST` be an `AdditionalCost` row of `CastingTimeOptionsReq` whose
  `grpId` is the offspring ability, followed by a `Done` row.
- <a id="Offspring.paid-option"></a>**Offspring.paid-option.** A spell cast
  with offspring paid `MUST` carry a persistent `CastingTimeOption` with
  `type` `5` and `additionalCostGrpId` naming the offspring ability.
- <a id="Offspring.trigger-ability"></a>**Offspring.trigger-ability.** The
  enters trigger of a creature whose offspring cost was paid `MUST` be an
  `Ability` with the offspring ability as `grpId` and the permanent as
  `parentId`.
- <a id="Offspring.token-in-resolution"></a>**Offspring.token-in-resolution.**
  The trigger's `TokenCreated` `MUST` name the trigger as `affectorId` and
  come between its `ResolutionStart` and `ResolutionComplete`.
- <a id="Offspring.decline"></a>**Offspring.decline.** A creature cast with
  the offspring row declined `MUST NOT` produce the offspring trigger.

## Open points

- What does `manaCost` on the `AdditionalCost` row carry: the offspring cost
  alone or added to the spell's cost?
- How do several offspring instances on one spell appear in the request?
