---
summary: "Opening-hand mechanic: an ability used from the opening hand before the first turn, its dedicated accepted-action type, and the Hand to Battlefield Put transfer."
entity: mechanic/OpeningHand
---
# Opening hand

Some cards let their owner act with them from the opening hand before the
first turn begins, such as starting the game with the card on the
battlefield. The mechanic uses an [Ability](../object_types/Ability.md)
created from the hand card, a `UserActionTaken` of its own `actionType`, and a
Hand to Battlefield [ZoneTransfer](../annotations/ZoneTransfer.md) with
`category` `Put`. It is not a cast, a land play or an ordinary activation.

## Rules grounding

CR 103.6 lets cards allow actions from the opening hand after mulligans and
before the first turn begins.

## Surfaces

| Surface | Entity pages |
|---|---|
| Ability from hand | [Ability](../object_types/Ability.md), [AbilityInstanceCreated](../annotations/AbilityInstanceCreated.md), [ResolutionStart](../annotations/ResolutionStart.md), [AbilityInstanceDeleted](../annotations/AbilityInstanceDeleted.md) |
| Put onto the battlefield | [ObjectIdChanged](../annotations/ObjectIdChanged.md), [ZoneTransfer](../annotations/ZoneTransfer.md) `Put` |
| Accepted action | [UserActionTaken](../annotations/UserActionTaken.md) `actionType` `15` |

## Put lifecycle

One GSM, before the first turn's upkeep ends, carries in order:

1. `AbilityInstanceCreated` from the hand card with `source_zone` the Hand.
2. `ResolutionStart` with `grpid` the opening-hand ability.
3. `ObjectIdChanged` from the ability, giving the card a new instance ID, and
   `ZoneTransfer` from the ability with Hand to Battlefield and `category`
   `Put`. Card-specific `LayeredEffectCreated` rows may precede them.
4. `AbilityInstanceDeleted` from the old hand instance.
5. `UserActionTaken` with `actionType` `15` and `abilityGrpId` the
   opening-hand ability, affected to the ability.

The GSM carries no `ResolutionComplete`, no prompt and no `ManaPaid`. Turn
phase rows follow.

## Sibling shapes

A later cast of the same card is an ordinary cast: Hand to Stack with
`category` `CastSpell` and `UserActionTaken` `actionType` `1`.

## Contract

- <a id="OpeningHand.accepted"></a>**OpeningHand.accepted.** An opening-hand
  action `MUST` be recorded by `UserActionTaken` with `actionType` `15` and
  `abilityGrpId` naming the opening-hand ability.
- <a id="OpeningHand.put-transfer"></a>**OpeningHand.put-transfer.** A card
  put onto the battlefield from the opening hand `MUST` move by a Hand to
  Battlefield `ZoneTransfer` with `category` `Put` whose `affectorId` is the
  opening-hand ability.
- <a id="OpeningHand.no-resolution-complete"></a>**OpeningHand.no-resolution-complete.**
  *(provisional)* The opening-hand put GSM `MUST NOT` carry a
  `ResolutionComplete` for the opening-hand ability.

## Open points

- What does the client see when a player declines an opening-hand action?
- How do opening-hand abilities that reveal a card and keep it in hand
  appear?
