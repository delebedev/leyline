---
summary: "LoyaltyActivationsRemaining annotation: persistent self row telling the client a planeswalker can still activate a loyalty ability this turn; ActivationsRemaining, retirement on activation and reissue at the next turn."
entity: annotation/LoyaltyActivationsRemaining
---
# LoyaltyActivationsRemaining

## Meaning

A player can activate a loyalty ability of each planeswalker they control
only once each turn, at sorcery speed (CR 606.3).
`LoyaltyActivationsRemaining` marks a planeswalker that still has an
activation available. The row ends when the activation is used and returns
for the next turn.

## Variants

Persistent only, never combined with another type.

## Fields

| Field | Role |
|---|---|
| `affectorId` | The planeswalker. |
| `affectedIds` | The same planeswalker, as a one-element list. |
| `ActivationsRemaining` | The number of loyalty activations left this turn, as an Int32. |

## Identity model

The row names the planeswalker's current battlefield instance, not a loyalty
ability object. Each availability period has its own annotation `id`: the
row is deleted and a new one created, never republished across turns.

## Lifecycle

1. A planeswalker enters the battlefield: the row appears in the GSM of its
   `ZoneTransfer`.
2. A loyalty ability is activated: the row is deleted in the GSM that pays
   the loyalty cost and carries the activation's
   [UserActionTaken](UserActionTaken.md).
3. The next turn begins: in the GSM carrying `NewTurnStarted`, a new row
   appears for the planeswalker. Since loyalty abilities are activated on
   their controller's turn, this is usually the opponent's turn.
4. A planeswalker that leaves the battlefield loses its row.

## Sibling shapes

Loyalty `Counter`, `CounterAdded` and `CounterRemoved` rows describe the
loyalty counters; whether an activation is still available is not derived
from them.

## Contract

- <a id="LoyaltyActivationsRemaining.self-row"></a>**LoyaltyActivationsRemaining.self-row.**
  `LoyaltyActivationsRemaining` `MUST` be carried in `persistentAnnotations`
  with `ActivationsRemaining`, and `affectorId` `MUST` equal the single entry
  of `affectedIds`.
- <a id="LoyaltyActivationsRemaining.retire-on-activate"></a>**LoyaltyActivationsRemaining.retire-on-activate.**
  *(provisional)* Activating a loyalty ability of the planeswalker `MUST`
  delete its row in the GSM carrying the activation's `UserActionTaken`.
- <a id="LoyaltyActivationsRemaining.new-turn"></a>**LoyaltyActivationsRemaining.new-turn.**
  *(provisional)* The GSM carrying `NewTurnStarted` `MUST` give every
  planeswalker on the battlefield without a row a new row.

## Open points

- How are values above `1`, and effects that grant extra loyalty
  activations, published?
