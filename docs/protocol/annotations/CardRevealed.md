---
summary: "CardRevealed annotation: short-lived persistent row naming the RevealedCard view of a card revealed from a hidden or private zone; source_zone, affector, the two view lifecycles and retirement."
entity: annotation/CardRevealed
---
# CardRevealed

## Meaning

When an effect reveals a card from a library, hand or sideboard (CR 701.20),
every player sees it briefly. The card is shown through a
[RevealedCard](../object_types/RevealedCard.md) view, and `CardRevealed`
names that view together with the effect that revealed it and the zone the
card is shown for. The row describes the view, not the underlying card, which
can continue into another zone under its own identity.

## Variants

Persistent only, never combined with another type. One row per revealed
view; a reveal of several cards has one row and one view per card. The views
follow one of two lifecycles:

| Lifecycle | View creation | View end |
|---|---|---|
| Unbracketed | The view first appears in the row's GSM without `RevealedCardCreated` | The view is removed through `diffDeletedInstanceIds`, normally in the next GSM |
| Bracketed | `RevealedCardCreated` names the view in the row's GSM | `RevealedCardDeleted` names the view |

Most reveals, such as revealing the top card of a library or a searched card,
use the unbracketed lifecycle; see Open points.

## Fields

| Field | Role |
|---|---|
| `affectorId` | What revealed the card: a resolving spell or ability on the stack, or a permanent whose ability reveals it. |
| `affectedIds` | The `RevealedCard` view, as a one-element list. |
| `source_zone` | The zone the card is shown for, once the reveal's outcome is fixed: its destination when it moves, such as the hand for a searched card or exile for an exiled one, otherwise its current zone. |
| `skin_code` | When present, the cosmetic variant of the revealed card. |

## Identity model

The view in `affectedIds` is neither the underlying [Card](../object_types/Card.md)
nor the card's identity in its destination; see
[RevealedCard](../object_types/RevealedCard.md) for the projection model.
Simultaneous views of the same printing still have distinct view IDs and
distinct rows. `source_zone` uses the zone IDs of
[identity and zones](../model/identity-and-zones.md) and can differ from the
view's own `zoneId`.

## Lifecycle

1. The revealed set and any movement are fixed, typically while the effect
   resolves.
2. In the same GSM the view appears and one `CardRevealed` row names it. Any
   `ObjectIdChanged` and `ZoneTransfer` for the underlying card are in that
   GSM's transient annotations.
3. The row lives for one or two GSMs, without changes.
4. It is deleted at the latest in the GSM that removes the view through
   `diffDeletedInstanceIds`, usually in that GSM. A row can also be deleted
   earlier, while the view remains.

## Sibling shapes

- `RevealedCardCreated` and `RevealedCardDeleted` bracket a view on the
  bracketed lifecycle; they are transient.
- `InstanceRevealedToOpponent` records lasting knowledge of an actual card in
  a hidden zone, after the reveal itself has ended. Both can describe one
  reveal without sharing identity or lifetime.

## Contract

- <a id="CardRevealed.persistent"></a>**CardRevealed.persistent.**
  `CardRevealed` `MUST` be carried in `persistentAnnotations` and `MUST NOT`
  be combined with another annotation type.
- <a id="CardRevealed.view-affected"></a>**CardRevealed.view-affected.**
  `affectedIds` `MUST` contain exactly one entry, a `RevealedCard` object
  that is new in the row's GSM.
- <a id="CardRevealed.one-row-per-view"></a>**CardRevealed.one-row-per-view.**
  At most one `CardRevealed` row `MUST` name a given view over its lifetime.
- <a id="CardRevealed.retired-with-view"></a>**CardRevealed.retired-with-view.**
  *(provisional)* A GSM that removes a view through `diffDeletedInstanceIds`
  `MUST` delete any live row that names it.

## Open points

- What selects the bracketed lifecycle over the unbracketed one?
- Does revealing a permanent that is already public produce a row?
- When is a row deleted while its view remains?
