---
summary: "Foretell: exiling a card face down with a special action, the face-down exile state, and the later cast from exile for its foretell cost; IDs, transfer category and accepted rows."
entity: mechanic/Foretell
---
# Foretell

Foretell lets a player pay {2} during their turn to exile a card from their
hand face down, then cast it on a later turn for its foretell cost. The
protocol carries the preparation as a [Special](../actions/Special.md) action,
the exiled card as a face-down object with a
[FaceDown](../annotations/FaceDown.md) row, and the later cast as an
alternative-cost [Cast](../actions/Cast.md) from exile recorded by
[CastingTimeOption](../annotations/CastingTimeOption.md).

## Rules grounding

While a player has priority during their turn, they may pay {2} and exile a
card with foretell from their hand face down, and cast it after the current
turn has ended by paying its foretell cost (CR 702.143a). Foretelling is a
special action and does not use the stack (CR 702.143b).

## Surfaces

| Surface | Entity pages |
|---|---|
| Foretell from hand | [Special](../actions/Special.md), [UserActionTaken](../annotations/UserActionTaken.md) |
| Face-down exile state | [FaceDown](../annotations/FaceDown.md), `SuppressedPowerAndToughness` |
| Cast from exile | [Cast](../actions/Cast.md), [CastingTimeOption](../annotations/CastingTimeOption.md) `type` `13` |

Each card has its own foretell ability. That one ID keys the special action,
the {2} cost, the later cast and its cost. The `FaceDown` row instead carries
the shared foretell ability, `208`.

## Foretell lifecycle

1. The card in the hand is offered as a `Special` action whose `abilityGrpId`
   is the card's foretell ability and whose `manaCost` is {2}, keyed by that
   ability.
2. The client submits it and the cost is paid with mana abilities. Nothing
   goes on the stack.
3. In the GSM of the payment, the card gets a new instance ID and moves Hand
   to Exile with `category` `Foretell`, and `UserActionTaken` records
   `actionType` `7` with the foretell ability in `abilityGrpId`.
4. The exile object is face down for its owner, with a self-affecting
   persistent `FaceDown` row with `REASON` `5` and a self-affecting
   `SuppressedPowerAndToughness` row.

## Cast lifecycle

1. On a later turn the exile object is offered as a `Cast` whose
   `alternativeGrpId` is the card's foretell ability, with no `abilityGrpId`.
   Its `manaCost` entries are the foretell cost, keyed by that ability.
2. The accepted cast gives the card a new instance ID and moves it Exile to
   Stack with `CastSpell`. The stack spell is public and carries a
   `CastingTimeOption` row of `type` `13` with the foretell ability in
   `alternateCostGrpId`.
3. After targets and payment, `UserActionTaken` records `actionType` `1` with
   the foretell ability in `alternativeGrpId`.

## Sibling shapes

| Axis | Foretell | Plot |
|---|---|---|
| Preparation | `Special` from the hand | `Special` from the hand |
| Hand to Exile `category` | `Foretell` | `Exile` |
| Exile state | Face down, `FaceDown` and `SuppressedPowerAndToughness` | Face up, [Plotted](../designations/Plotted.md) designation |
| Later cast | The card's foretell cost | Without paying its mana cost |

## Contract

- <a id="Foretell.transfer-category"></a>**Foretell.transfer-category.** The
  move that foretells a card `MUST` be a Hand to Exile `ZoneTransfer` with
  `category` `Foretell`.
- <a id="Foretell.special-cost"></a>**Foretell.special-cost.**
  *(provisional)* The foretell `Special` offer's `abilityGrpId` and the
  ability ID on its `manaCost` entries `MUST` be the card's own foretell
  ability.
- <a id="Foretell.cast-offer"></a>**Foretell.cast-offer.** *(provisional)* A
  foretold card in exile `MUST` be offered as a `Cast` whose
  `alternativeGrpId` is the card's foretell ability and which carries no
  `abilityGrpId`.
- <a id="Foretell.accepted-cast"></a>**Foretell.accepted-cast.**
  *(provisional)* An accepted cast of a foretold card `MUST` be recorded by
  `UserActionTaken` with `actionType` `1` and the foretell ability in
  `alternativeGrpId`.

The preparation offer is stated by
[Special.foretell-plot](../actions/Special.md#Special.foretell-plot) and its
accepted row by [Special.accepted](../actions/Special.md#Special.accepted).
The face-down row is stated by
[FaceDown.reason-ability](../annotations/FaceDown.md#FaceDown.reason-ability).

## Open points

- How is the foretold card projected to the opponent while it is in exile?
- How does a card foretold by another effect differ?
