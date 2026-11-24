---
summary: "InstanceRevealedToOpponent annotation: durable record that the opponent knows a card in a hidden zone; self-affecting card row, birth with reveals and retirement when the card gets a new instance ID."
entity: annotation/InstanceRevealedToOpponent
---
# InstanceRevealedToOpponent

## Meaning

Once a card in a player's hand has been revealed, or a card was put into the
hand from a public zone, the opponent knows what it is for as long as it
stays there. `InstanceRevealedToOpponent` records that knowledge on the
actual card, so the client can keep showing the opponent which hand cards are
known. It is lasting state, unlike the brief
[RevealedCard](../object_types/RevealedCard.md) view that presents a reveal.

## Variants

Persistent only, never combined with another type, and without details. One
row per known card: revealing a whole hand produces one row per card.

## Fields

| Field | Role |
|---|---|
| `affectorId` | The known card's instance ID. |
| `affectedIds` | The same instance ID, as a one-element list. |
| details | None. No field names the viewer. |

## Identity model

The row names the real [Card](../object_types/Card.md), never a
`RevealedCard` view. When a revealed card also moves, the row names the
card's identity in its destination, the ID that the move's
[ObjectIdChanged](ObjectIdChanged.md) assigns.

## Lifecycle

1. A reveal completes, or a card moves from a public zone into its owner's
   hand. The row appears in the same GSM as any `ObjectIdChanged` and
   `ZoneTransfer` for the card.
2. The row survives turns, priority changes and full state updates while the
   card stays in place.
3. The row is deleted when the card receives a new instance ID, typically
   in the GSM whose `ObjectIdChanged` moves it out of the hand.

Ending the reveal's [CardRevealed](CardRevealed.md) row does not end this
row. A look that only the owner sees creates no row.

## Sibling shapes

[CardRevealed](CardRevealed.md) names the temporary view of a reveal and
lasts one or two GSMs; this row names the real card and lasts until the card
moves. `RevealedCardCreated` and `RevealedCardDeleted` manage the view only.

## Contract

- <a id="InstanceRevealedToOpponent.persistent"></a>**InstanceRevealedToOpponent.persistent.**
  `InstanceRevealedToOpponent` `MUST` be carried in `persistentAnnotations`
  without details.
- <a id="InstanceRevealedToOpponent.self-row"></a>**InstanceRevealedToOpponent.self-row.**
  `affectorId` `MUST` equal the single entry of `affectedIds`, a card
  instance, not a `RevealedCard`.
- <a id="InstanceRevealedToOpponent.retired-on-rename"></a>**InstanceRevealedToOpponent.retired-on-rename.**
  *(provisional)* The row `MUST` be deleted in the GSM whose
  `ObjectIdChanged` gives its card a new instance ID.
- <a id="InstanceRevealedToOpponent.not-library"></a>**InstanceRevealedToOpponent.not-library.**
  *(provisional)* A row `MUST NOT` name a card in a library.

## Open points

- Why do a few rows end without an `ObjectIdChanged` for their card?
- Why do some rows name a card on the battlefield?
