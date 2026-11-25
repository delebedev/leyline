---
summary: "Teamwork mechanic: an optional additional cost chosen in CastingTimeOptionsReq and paid by tapping creatures with enough total power through a weighted PayCostsReq."
entity: mechanic/Teamwork
---
# Teamwork

Teamwork is an optional additional cost of a spell: tap any number of untapped
creatures you control with total power at least the teamwork number. The
mechanic uses an optional-cost
[CastingTimeOptionsReq](../prompts/CastingTimeOptionsReq.md), a total-weight
object-selection [PayCostsReq](../prompts/PayCostsReq.md) whose weights are
creature powers, and an `AdditionalCost`
[CastingTimeOption](../annotations/CastingTimeOption.md) on the spell.

## Rules grounding

Teamwork is printed as an optional additional cost to tap creatures with a
total power of at least N. Tapping creatures to pay a cost is not targeting
them; the creatures are cost payments (CR 601.2h).

## Surfaces

| Surface | Entity pages |
|---|---|
| Choosing to pay | [CastingTimeOptionsReq](../prompts/CastingTimeOptionsReq.md) optional-cost family |
| Paid marker | [CastingTimeOption](../annotations/CastingTimeOption.md) `AdditionalCost` |
| Creature payment | [PayCostsReq](../prompts/PayCostsReq.md) object-selection family, [TappedUntappedPermanent](../annotations/TappedUntappedPermanent.md) |

## Cast lifecycle

1. The cast moves the spell Hand to Stack with `category` `CastSpell`.
2. The caster receives a `CastingTimeOptionsReq` with an `AdditionalCost` row
   whose `grpId` is a teamwork ability, then a `Done` row. The teamwork
   ability names the teamwork number, not the card: `206066` for Teamwork 2
   and `206198` for Teamwork 3. Both rows carry the spell's own mana cost.
3. Answering with the teamwork row creates a persistent `CastingTimeOption`
   on the spell with `type` `5` and `additionalCostGrpId` the teamwork
   ability.
4. Targets and mana are chosen and paid as for any spell.
5. The creature payment arrives as a `PayCostsReq` whose `promptId` names the
   power total, `8924` for 2 and `8925` for 3: a total-weight selection whose `minSel` is the teamwork number, whose
   `weights` are the candidates' powers, and whose `CardId` parameter is the
   spell ([PayCostsReq.select-threshold](../prompts/PayCostsReq.md#PayCostsReq.select-threshold)).
6. After the `EffectCostResp`, one GSM carries `TappedUntappedPermanent` for
   each chosen creature with the spell as `affectorId`, and the cast's
   `UserActionTaken` with `actionType` `1` and `abilityGrpId` `0`.

Answering `Done` casts the spell without the cost: no `CastingTimeOption` row
of `type` `5` and no creature-payment `PayCostsReq` follow.

## Sibling shapes

Crew and saddle use the same total-power payment, but for an activated
ability: the `CardId` is the ability on the stack and there is no
casting-time option. Bargain and offspring share the optional-cost request,
with no creature payment.

## Contract

- <a id="Teamwork.option-row"></a>**Teamwork.option-row.** The teamwork choice
  `MUST` be an `AdditionalCost` row of `CastingTimeOptionsReq` whose `grpId`
  is the teamwork ability, followed by a `Done` row.
- <a id="Teamwork.paid-option"></a>**Teamwork.paid-option.** A spell cast with
  teamwork paid `MUST` carry a persistent `CastingTimeOption` with `type` `5`
  and `additionalCostGrpId` naming the teamwork ability.
- <a id="Teamwork.payment-prompt"></a>**Teamwork.payment-prompt.**
  *(provisional)* Grounded for Teamwork 2 and 3: the creature payment `MUST`
  be a total-weight object-selection `PayCostsReq` with `promptId` `8924` for
  Teamwork 2 and `8925` for Teamwork 3, `minSel` the teamwork number and each
  candidate's power in `weights`.
- <a id="Teamwork.payment-card-id"></a>**Teamwork.payment-card-id.** The
  creature-payment `PayCostsReq` `MUST` carry, as its `CardId` parameter, the
  same spell instance ID that the teamwork `CastingTimeOptionsReq` row names
  in `affectedId`.
- <a id="Teamwork.tap-affector"></a>**Teamwork.tap-affector.** Each creature
  tapped for teamwork `MUST` carry a `TappedUntappedPermanent` with the spell
  as `affectorId`, in the same GSM as the cast's `UserActionTaken`.
- <a id="Teamwork.decline"></a>**Teamwork.decline.** A cast answered with
  `Done` `MUST NOT` be followed by a teamwork creature-payment `PayCostsReq`.

## Open points

- Which candidates does the payment offer when a creature has power `0`?
