---
summary: "Sneak mechanic: a declare-blockers alternative cost that returns an unblocked attacker, its return-cost PayCostsReq, and the cast's accepted-action and casting-time-option values."
entity: mechanic/Sneak
---
# Sneak

Sneak is an alternative cost a player may pay during their declare blockers
step: pay the sneak cost and return an unblocked attacker they control to its
owner's hand. A permanent spell cast this way enters tapped and attacking. The
mechanic uses an alternative-cost [Cast](../actions/Cast.md), the same
return-cost [PayCostsReq](../prompts/PayCostsReq.md) as ninjutsu, and a
[CastingTimeOption](../annotations/CastingTimeOption.md) on the spell.

## Rules grounding

CR 702.190a defines sneak as an alternative cost payable any time its
controller could cast an instant during their declare blockers step, with
returning an unblocked attacker as part of the cost. CR 702.190b makes a
permanent spell whose sneak cost was paid enter tapped and attacking the same
player, planeswalker or battle as the returned creature.

## Surfaces

| Surface | Entity pages |
|---|---|
| Cast for the sneak cost | [Cast](../actions/Cast.md), [UserActionTaken](../annotations/UserActionTaken.md), [CastingTimeOption](../annotations/CastingTimeOption.md) |
| Return cost | [PayCostsReq](../prompts/PayCostsReq.md) object-selection family, [ZoneTransfer](../annotations/ZoneTransfer.md) `Return` |

## Cast lifecycle

1. In the declare blockers step, the hand card is offered as a `Cast` action
   whose `alternativeGrpId` is a sneak cost ability. That ability names a
   cost, not a card.
2. The cast GSM carries `ObjectIdChanged` and a Hand to Stack `ZoneTransfer`
   with `category` `CastSpell`, mana payment naming the spell, and a
   persistent `CastingTimeOption` with `type` `13` and `alternateCostGrpId`
   the sneak cost ability.
3. The return cost arrives in that GSM's batch as a `PayCostsReq` with
   `promptId` `8580`, the same exact-count selection of one unblocked
   attacker that [ninjutsu](Ninjutsu.md) uses, whose `CardId` parameter is
   the spell.
4. After the `EffectCostResp`, one GSM carries `ObjectIdChanged` and a
   Battlefield to Hand `ZoneTransfer` with `category` `Return` for the chosen
   attacker, the spell as `affectorId`, and `UserActionTaken` with
   `actionType` `1`, `abilityGrpId` `0` and `alternativeGrpId` the sneak cost
   ability.
5. The spell resolves through the ordinary spell lifecycle.

## Sibling shapes

Ninjutsu shares the return cost and its prompt, but it is an activated
ability of a card in the hand: the card never goes to the stack and the
return names the ability as `affectorId`. Warp shares the hand-cast rail and
its `CastingTimeOption` shape, without a return cost.

## Contract

- <a id="Sneak.accepted"></a>**Sneak.accepted.** A cast for the sneak cost
  `MUST` be recorded by `UserActionTaken` with `actionType` `1`, `abilityGrpId`
  `0` and `alternativeGrpId` naming the sneak cost ability.
- <a id="Sneak.option"></a>**Sneak.option.** A spell cast for its sneak cost
  `MUST` carry a persistent `CastingTimeOption` with `type` `13` and
  `alternateCostGrpId` naming the sneak cost ability, and `MUST NOT` carry
  `castAbilityGrpId`.
- <a id="Sneak.return-prompt"></a>**Sneak.return-prompt.** The return cost
  `MUST` be requested as an exact-count object-selection `PayCostsReq` with
  `promptId` `8580`, the unblocked attackers in `ids`, and the spell's
  instance ID as its `CardId` parameter.
- <a id="Sneak.return-transfer"></a>**Sneak.return-transfer.** The returned
  attacker `MUST` move Battlefield to Hand with `category` `Return` and the
  spell as `affectorId`, in the same GSM as the cast's `UserActionTaken`.

## Open points

- What does the resolution of a permanent spell cast for its sneak cost carry
  for entering tapped and attacking?
- Does a sneak cast from a zone other than the hand use the same rail?
