---
summary: "Harmonize: casting a card from the graveyard for its harmonize cost, optionally tapping a creature to reduce it by its power; the graveyard Cast offer, the Selection row that picks the creature, the accepted cast and the stack marker."
entity: mechanic/Harmonize
---
# Harmonize

Harmonize lets a player cast a card from their graveyard for its harmonize
cost and tap up to one untapped creature they control to reduce the generic
part of that cost by the creature's power. The spell is exiled when it leaves
the stack. The protocol carries the cast as an alternative-cost
[Cast](../actions/Cast.md) from the graveyard. The creature is picked through
the `Selection` family of
[CastingTimeOptionsReq](../prompts/CastingTimeOptionsReq.md), and the cast is
recorded by the `CastThroughAbility` branch of
[CastingTimeOption](../annotations/CastingTimeOption.md).

## Rules grounding

Harmonize lets the card be cast from the graveyard by paying its harmonize
cost and tapping up to one untapped creature its controller controls rather
than paying its mana cost. The generic part of the cost is reduced by the
tapped creature's power, and the spell is exiled instead of going anywhere
else when it leaves the stack (CR 702.180a). The creature is chosen with the
alternative cost and tapped as the total cost is paid (CR 702.180b).

## Surfaces

| Surface | Entity pages |
|---|---|
| Graveyard offer | [Cast](../actions/Cast.md) |
| Creature to tap | [CastingTimeOptionsReq](../prompts/CastingTimeOptionsReq.md) `Selection` row |
| Mana payment | [PayCostsReq](../prompts/PayCostsReq.md) |
| Accepted cast | [UserActionTaken](../annotations/UserActionTaken.md) |
| Stack marker | [CastingTimeOption](../annotations/CastingTimeOption.md) `type` `13` |

## Cast lifecycle

1. The card in the graveyard is offered as a `Cast` whose `abilityGrpId` and
   `alternativeGrpId` are both the card's harmonize ability.
2. When the client submits it, the card moves Graveyard to Stack with
   `CastSpell` under a new instance ID. The stack spell carries a
   `CastingTimeOption` row of `type` `13` with the harmonize ability in
   `alternateCostGrpId` and `castAbilityGrpId`.
3. The caster receives a `CastingTimeOptionsReq` with prompt `23` holding one
   `Selection` row. Its `grpId` is the harmonize ability, and its `selectNReq`
   offers the creatures that can be tapped by instance ID, with `maxSel` `1`,
   no minimum, and inner prompt `183`. Choosing no creature taps none.
4. The mana payment that follows asks for the cost already reduced by the
   chosen creature's power.
5. `UserActionTaken` records `actionType` `1` with the harmonize ability in
   both `alternativeGrpId` and `abilityGrpId`.

## Sibling shapes

[Flashback](Flashback.md) casts from the graveyard and exiles the spell too,
with no creature choice. Convoke and improvise also tap permanents to pay,
but they pay mana symbols during the mana payment; harmonize's creature is
chosen with the cast, before the payment, and lowers the cost by its power.

## Contract

- <a id="Harmonize.creature-choice"></a>**Harmonize.creature-choice.**
  *(provisional)* The creature to tap for harmonize `MUST` be offered as the
  `Selection` row of a `CastingTimeOptionsReq` whose `grpId` is the harmonize
  ability and whose `selectNReq` has `maxSel` `1`.
- <a id="Harmonize.offer"></a>**Harmonize.offer.** *(provisional)* A
  harmonize offer from the graveyard `MUST` carry the harmonize ability in
  both `abilityGrpId` and `alternativeGrpId`.
- <a id="Harmonize.accepted"></a>**Harmonize.accepted.** *(provisional)* An
  accepted harmonize cast `MUST` be recorded by `UserActionTaken` with
  `actionType` `1` and the harmonize ability in both `alternativeGrpId` and
  `abilityGrpId`.
- <a id="Harmonize.cast-marker"></a>**Harmonize.cast-marker.** The stack
  spell's `CastingTimeOption` row `MUST` have `type` `13` with the harmonize
  ability in both `alternateCostGrpId` and `castAbilityGrpId`.

## Open points

- How is the tap of the chosen creature published, and in which GSM?
- How is the move to exile published when the spell resolves or is
  countered?
