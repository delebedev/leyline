---
summary: "Waterbend mechanic: a cost whose generic part may be paid by tapping artifacts and creatures, offered as MakePayment actions in the mana PayCostsReq, and its optional spell-time choice."
entity: mechanic/Waterbend
---
# Waterbend

Waterbend is a way to pay a cost: each generic mana in the waterbend part may
be paid by tapping an untapped artifact or creature instead. The mechanic
appears as an optional additional cost of a spell, chosen in
[CastingTimeOptionsReq](../prompts/CastingTimeOptionsReq.md), or as the cost of
an activated ability. Either way the tappable permanents are offered as
mana-payment actions in the ordinary mana
[PayCostsReq](../prompts/PayCostsReq.md), not as an object selection.

## Rules grounding

CR 701.67 defines waterbend: while paying a waterbend cost, the player may tap
untapped artifacts and creatures, each paying for one generic mana of that
cost.

## Surfaces

| Surface | Entity pages |
|---|---|
| Choosing to pay (spell) | [CastingTimeOptionsReq](../prompts/CastingTimeOptionsReq.md) optional-cost family |
| Paid marker (spell) | [CastingTimeOption](../annotations/CastingTimeOption.md) `AdditionalCost` |
| Payment | [PayCostsReq](../prompts/PayCostsReq.md) mana family, `MakePayment` actions |

## Spell lifecycle

1. The caster receives a `CastingTimeOptionsReq` with an `AdditionalCost` row
   whose `grpId` is the card's waterbend ability, then a `Done` row.
2. Answering with the waterbend row creates a persistent `CastingTimeOption`
   on the spell with `type` `5` and `additionalCostGrpId` the waterbend
   ability.
3. The payment arrives as a mana `PayCostsReq` with `promptId` `11`. Its
   `manaCost` lists the waterbend part as its own generic entry whose
   `abilityGrpId` is the waterbend ability, beside the spell's own cost
   entries.
4. Each untapped artifact and creature that can pay is a `MakePayment` action
   whose mana option has `abilityGrpId` `384`, `srcInstanceId` the permanent,
   `color` colorless, `count` `1`, and specs `Predictive` and
   `ManaSubstitution`; a creature also has `FromCreature`.
5. Each committed tap is answered with a new `PayCostsReq` in which the
   waterbend entry is one lower and that permanent's action is gone.
6. The cast's `UserActionTaken` has `actionType` `1` and `abilityGrpId` `0`.

## Activated lifecycle

An activated waterbend ability is offered and committed as an ordinary
`Activate`; its payment uses the same `MakePayment` actions.

## Sibling shapes

Convoke and improvise also tap permanents to pay mana. Teamwork, crew and
saddle tap creatures through an object-selection `PayCostsReq` instead.

## Contract

- <a id="Waterbend.option-row"></a>**Waterbend.option-row.** A spell's
  waterbend choice `MUST` be an `AdditionalCost` row of
  `CastingTimeOptionsReq` whose `grpId` is the waterbend ability, followed by
  a `Done` row.
- <a id="Waterbend.paid-option"></a>**Waterbend.paid-option.** A spell cast
  with waterbend paid `MUST` carry a persistent `CastingTimeOption` with
  `type` `5` and `additionalCostGrpId` naming the waterbend ability.
- <a id="Waterbend.payment-action"></a>**Waterbend.payment-action.** Each
  permanent that can pay for waterbend `MUST` be a `MakePayment` action whose
  mana option has `abilityGrpId` `384`, `srcInstanceId` the permanent, and the
  `ManaSubstitution` spec, plus `FromCreature` when it is a creature.
- <a id="Waterbend.cost-entry"></a>**Waterbend.cost-entry.** *(provisional)*
  The waterbend part of a spell's cost `MUST` appear in `manaCost` as a
  separate generic entry whose `abilityGrpId` is the waterbend ability.

## Open points

- Does the activated branch's `manaCost` also carry a waterbend entry with the
  ability's ID?
- Is a permanent that can also produce mana offered as both an activation and
  a `MakePayment` action?
