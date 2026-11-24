---
summary: "Saddle mechanic: the saddle activation, its total-power PayCostsReq over helper creatures, the SaddledThisTurn helper roster, and the Saddled designation on resolution."
entity: mechanic/Saddle
---
# Saddle

Saddle is an activated ability of a Mount: tap any number of other untapped
creatures you control with total power at least N to make the Mount saddled
until end of turn. The mechanic uses an `Activate` offer, a total-weight
object-selection [PayCostsReq](../prompts/PayCostsReq.md) whose weights are
creature powers, a persistent `SaddledThisTurn` row naming the helpers, and
the [Saddled](../designations/Saddled.md) designation, which owns the durable
state.

## Rules grounding

CR 702.171a defines saddle as an activated ability, activated only as a
sorcery, whose cost taps other untapped creatures with total power N or
greater and whose effect makes the permanent saddled. CR 702.171b defines
saddled; see [Saddled](../designations/Saddled.md).

## Surfaces

| Surface | Entity pages |
|---|---|
| Activation | [Ability](../object_types/Ability.md), [AbilityInstanceCreated](../annotations/AbilityInstanceCreated.md), [UserActionTaken](../annotations/UserActionTaken.md) |
| Helper payment | [PayCostsReq](../prompts/PayCostsReq.md) object-selection family, [TappedUntappedPermanent](../annotations/TappedUntappedPermanent.md) |
| Helper roster | persistent `SaddledThisTurn` |
| Saddled state | [Saddled](../designations/Saddled.md) |

## Activation lifecycle

1. The Mount is offered as an `Activate` action whose `abilityGrpId` is its
   saddle ability. The ability names the saddle number, not the card.
2. On submission the server creates the stack `Ability` with
   `AbilityInstanceCreated` naming the Mount and `source_zone` the
   Battlefield.
3. The helper payment arrives as a `PayCostsReq`: a total-weight selection
   whose `minSel` is the saddle number, whose `weights` are the candidates'
   powers and whose `CardId` parameter is the saddle ability, not the Mount
   ([PayCostsReq.select-threshold](../prompts/PayCostsReq.md#PayCostsReq.select-threshold)).
   Its `promptId` names the power total: `8929` for 1, `8925` for 3 and
   `8922` for 4, from the same set of total-power prompts that Teamwork uses.
4. After the `EffectCostResp`, one GSM carries `TappedUntappedPermanent` for
   each helper with the saddle ability as `affectorId`, `UserActionTaken`
   with `actionType` `2` and the saddle ability, and a persistent
   `SaddledThisTurn` row with the Mount as `affectorId` and the helpers as
   affected.

## Resolution lifecycle

1. `ResolutionStart` carries the saddle ability.
2. The same GSM carries the `GainDesignation` and persistent `Designation` of
   [Saddled](../designations/Saddled.md), with the saddle ability as the gain
   row's `affectorId`
   ([Saddled.gain-at-resolution](../designations/Saddled.md#Saddled.gain-at-resolution)).
3. `ResolutionComplete` and `AbilityInstanceDeleted` close the ability.

## Sibling shapes

Crew shares the total-power payment and keeps a `CrewedThisTurn` roster in
the same polarity, the Vehicle as `affectorId` and the crew as affected; its
result is the Vehicle becoming a creature through layered effects rather than
a designation. Teamwork pays the same kind of cost while casting a spell, so
its `CardId` is the spell.

## Contract

- <a id="Saddle.payment-prompt"></a>**Saddle.payment-prompt.** The saddle cost
  `MUST` be requested as a total-weight object-selection `PayCostsReq` with
  `minSel` the saddle number, the candidates' powers in `weights`, and the
  saddle ability's instance ID as its `CardId` parameter.
- <a id="Saddle.payment-prompt-id"></a>**Saddle.payment-prompt-id.**
  *(provisional)* Grounded for saddle 1, 3 and 4: the payment's `promptId`
  `MUST` be `8929`, `8925` or `8922` for a saddle number of 1, 3 or 4.
- <a id="Saddle.tap-affector"></a>**Saddle.tap-affector.** Each helper `MUST`
  carry a `TappedUntappedPermanent` with the saddle ability as `affectorId`,
  in the same GSM as the activation's `UserActionTaken`.
- <a id="Saddle.roster"></a>**Saddle.roster.** The GSM that commits the
  payment `MUST` create or update a persistent `SaddledThisTurn` row with the
  Mount as `affectorId` and the helpers among its affected IDs.

## Open points

- How does `SaddledThisTurn` change when the same Mount is saddled twice in a
  turn, and when is it retired?
- Does saddle 2 use `8924`, the total-power-2 prompt of Teamwork?
- Does a creature with power `0` appear among the candidates?
