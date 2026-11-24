---
summary: "Boast: a once-per-turn activated ability of a creature that attacked; an ordinary Activate offer, and the AbilityExhausted row created with the activation and deleted at the next turn."
entity: mechanic/Boast
---
# Boast

Boast is an activated ability a creature can use only in a turn it attacked,
and only once that turn. The activation is an ordinary `Activate` action and
stack [Ability](../object_types/Ability.md); what Boast adds is a persistent
`AbilityExhausted` row on the creature that records the use.

## Rules grounding

A Boast ability can be activated only if its creature attacked this turn and
only once each turn (CR 702.142a).

## Surfaces

| Surface | Entity pages | Boast values |
|---|---|---|
| Offer | [ActionsAvailableReq](../prompts/ActionsAvailableReq.md) | `Activate` on the creature with the card's Boast ability and its `manaCost` |
| Activation | [UserActionTaken](../annotations/UserActionTaken.md) | `actionType` `2`, the Boast ability |
| Use recorded | `AbilityExhausted` | `UsesRemaining` `0`, `AbilityGrpId` the Boast ability |

## Activation lifecycle

1. In a turn the creature attacked, it carries an `Activate` offer with the
   card's Boast ability as `abilityGrpId` and the ability's mana cost in
   `manaCost`, keyed by the same ability.
2. The GSM that applies the activation creates the ability with
   `AbilityInstanceCreated` from the creature, pays its cost with ordinary
   mana abilities and `ManaPaid` rows, and records `UserActionTaken` with
   `actionType` `2` and the Boast ability.
3. The same GSM creates a persistent `AbilityExhausted` row with the creature
   as `affectorId` and `affectedIds`, `AbilityGrpId` the Boast ability,
   `UsesRemaining` `0` and `UniqueAbilityId` the creature's ability slot for
   Boast.
4. The ability resolves later with an ordinary `ResolutionStart`, payload,
   `ResolutionComplete` and `AbilityInstanceDeleted`.
5. The `AbilityExhausted` row is deleted at the start of the next turn.

## Sibling shapes

Other once-per-turn or limited-use activated abilities, such as Exhaust, use
the same `AbilityExhausted` row; Boast is identified by the card's ability.

## Contract

- <a id="Boast.offer"></a>**Boast.offer.** *(provisional)* The Boast
  ability `MUST` be offered as an `Activate` action on its creature with the
  Boast ability as `abilityGrpId`.
- <a id="Boast.exhausted"></a>**Boast.exhausted.** *(provisional)* The GSM
  that applies the activation `MUST` create a persistent `AbilityExhausted`
  row naming the creature as `affectorId` and in `affectedIds`, with
  `AbilityGrpId` the Boast ability and `UsesRemaining` `0`.

## Open points

- Is the offer absent, or present and refused, after the ability was used
  this turn?
- Is the row's deletion tied to the next turn's beginning or to the end of
  the turn the creature attacked?
