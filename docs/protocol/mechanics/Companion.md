---
summary: "Companion: the chosen companion's paired seat and card designations from the first GSM, and the Special action with ability 202 that puts it into the hand for {3}."
entity: mechanic/Companion
---
# Companion

A companion is a card outside the game that its owner revealed because their
deck meets its condition. From the first GSM it is marked by two persistent
`Designation` rows, one on the owner's seat and one on the card. Its owner
may pay `{3}` to put it into their hand through a
[Special](../actions/Special.md) action with the shared ability `202`, and
then cast it like any card in the hand.

## Rules grounding

A companion is revealed before the game begins and starts outside the game
(CR 702.139a). Its owner may pay `{3}` to put it into their hand any time
they have priority during a main phase of their turn with an empty stack;
this is a special action and does not use the stack (CR 116.2g).

## Surfaces

| Surface | Entity pages | Companion values |
|---|---|---|
| Chosen companion | `Designation` | `DesignationType` `7` with the card's `grpid`, on the seat and on the card |
| Put into the hand | [Special](../actions/Special.md) | `abilityGrpId` `202`, three generic mana |
| Later cast | [Cast](../actions/Cast.md) | Ordinary cast from the hand |

## Designation lifecycle

1. The first GSM of the game carries two persistent `Designation` rows with
   `DesignationType` `7` and `grpid` set to the companion's `grpId`. One
   names the owner's seat as both `affectorId` and `affectedIds`; the other
   names the companion card's instance the same way.
2. The companion card is projected in its owner's Sideboard zone.

## Put-into-hand lifecycle

1. While the action is available, the companion card carries a `Special`
   offer whose `abilityGrpId` is `202` and whose `manaCost` is three generic
   mana keyed by ability `202`.
2. The accepted action pays the cost, moves the card from the Sideboard to
   the hand under a new instance ID, and is recorded by `UserActionTaken` with
   `actionType` `7` and `abilityGrpId` `202`, as for any special action.
3. Casting the card from the hand is an ordinary `Cast`.

## Sibling shapes

Other cards that start outside the game, such as cards in a sideboard, carry
no `Designation` row. Foretell and plot also use the `Special` rail, but on a
card in the hand, with per-mechanic abilities instead of `202`.

## Contract

- <a id="Companion.designations"></a>**Companion.designations.** A game with
  a companion `MUST` carry, from its first GSM, two persistent `Designation`
  rows with `DesignationType` `7` and `grpid` equal to the companion's
  `grpId`: one naming the owner's seat as `affectorId` and sole
  `affectedIds` entry, and one naming the companion card's instance the same
  way.
- <a id="Companion.offer"></a>**Companion.offer.** Putting the companion into
  the hand `MUST` be offered as a `Special` action on the companion card with
  `abilityGrpId` `202` and a `manaCost` of three generic mana keyed by
  ability `202`.

## Open points

- In which GSM, if any, are the two designation rows deleted after the
  companion is put into the hand?
- Which transfer `category` moves the companion from the Sideboard to the
  hand?
- Is the offer present outside the owner's sorcery timing, and with which
  `ActionsAvailableReq` fields?
