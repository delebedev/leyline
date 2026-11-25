---
summary: "Spectacle mechanic: a hand-cast alternative cost available after an opponent lost life this turn, the Spectacle condition row, and the cast's accepted-action and casting-time-option values."
entity: mechanic/Spectacle
---
# Spectacle

Spectacle is an alternative cost a player may pay if an opponent lost life
this turn. The mechanic uses an alternative-cost [Cast](../actions/Cast.md), a
`type` `13` [CastingTimeOption](../annotations/CastingTimeOption.md), and an
[AbilityWordActive](../annotations/AbilityWordActive.md) row named
`Spectacle` that marks the card while the condition holds.

## Rules grounding

CR 702.137a defines spectacle as an alternative cost that can be paid if an
opponent lost life this turn.

## Surfaces

| Surface | Entity pages |
|---|---|
| Condition | [AbilityWordActive](../annotations/AbilityWordActive.md) `Spectacle` |
| Cast for the spectacle cost | [Cast](../actions/Cast.md), [UserActionTaken](../annotations/UserActionTaken.md), [CastingTimeOption](../annotations/CastingTimeOption.md) |

## Condition lifecycle

1. When an opponent of the card's owner loses life, a persistent
   `AbilityWordActive` row named `Spectacle` is created on the spectacle card
   in the hand, with the card as `affectorId` and affected.
2. The row is deleted in the first GSM of the next turn.
3. When the card is cast, its hand row is deleted and a new row is created on
   the stack instance in the cast GSM.

## Cast lifecycle

1. The hand card is offered as a `Cast` action whose `alternativeGrpId` is
   the card's spectacle ability.
2. The cast GSM carries `ObjectIdChanged` and a Hand to Stack `ZoneTransfer`
   with `category` `CastSpell`, `UserActionTaken` with `actionType` `1`,
   `abilityGrpId` `0` and `alternativeGrpId` the spectacle ability, and a
   persistent `CastingTimeOption` with `type` `13` and `alternateCostGrpId`
   the spectacle ability, without `castAbilityGrpId`.
3. No `CastingTimeOptionsReq` asks for the choice; the cast action carries it.

## Sibling shapes

Overload, sneak and the hand branch of warp use the same alternative-cost
rail. Only spectacle publishes a condition row for the cost's availability.

## Contract

- <a id="Spectacle.condition-row"></a>**Spectacle.condition-row.**
  *(provisional)* While an opponent has lost life this turn, a spectacle card
  in its owner's hand `MUST` carry a persistent `AbilityWordActive` row named
  `Spectacle` with the card as `affectorId` and affected ID.
- <a id="Spectacle.accepted"></a>**Spectacle.accepted.** A cast for the
  spectacle cost `MUST` be recorded by `UserActionTaken` with `actionType`
  `1`, `abilityGrpId` `0` and `alternativeGrpId` naming the spectacle ability.
- <a id="Spectacle.option"></a>**Spectacle.option.** A spell cast for its
  spectacle cost `MUST` carry a persistent `CastingTimeOption` with `type`
  `13` and `alternateCostGrpId` naming the spectacle ability, and `MUST NOT`
  carry `castAbilityGrpId`.

## Open points

- Why does the `Spectacle` row move to the stack instance, and when is that
  row retired?
- Is the spectacle cast offered at all while the condition does not hold, or
  offered inactive?
