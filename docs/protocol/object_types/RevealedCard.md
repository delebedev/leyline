---
summary: "RevealedCard game object: a temporary projection of a card shown from a private or hidden zone; source zone versus Revealed-zone membership, and lifecycle."
entity: object_type/RevealedCard
---
# RevealedCard

A `RevealedCard` is a temporary projection of a card whose own zone does not
show that card to every viewer. It lets the client render the card during a
reveal, search or similar interaction without replacing the zone-bearing
[Card](Card.md).

## Object model

The object has its own `instanceId` and copies the revealed card's `grpId`,
characteristics and visible abilities. It has no `parentId` and no
`objectSourceGrpId`: its link to the underlying card is the interaction it
belongs to, not a field.

## Zone and visibility

A `RevealedCard`'s `zoneId` is the card's source zone, such as a hand or a
library. Its ID is at the same time a member of the owner's Revealed zone.
The two answer different questions: `zoneId` says where the card is, and the
Revealed zone's member list holds the projection.

The object is normally `Public` even when its source zone is private or
hidden. It never becomes a member of the source zone.

## Lifecycle

1. A new `RevealedCard` ID is allocated when the card becomes renderable.
2. `RevealedCardCreated` names that ID as both its affector and its affected
   object.
3. The ID stays in the Revealed zone while the card is shown.
4. `RevealedCardDeleted` ends the disclosure, and `diffDeletedInstanceIds`
   removes the object.

The reveal lifecycle is independent of any `ObjectIdChanged` or
`ZoneTransfer` that moves the underlying card.

## Sibling shapes

`Card` owns the real zone membership and gameplay identity; `RevealedCard`
owns only the temporary projection. [TriggerHolder](TriggerHolder.md) is the
other protocol utility, and represents ability state rather than hidden
information.

## Contract

- <a id="RevealedCard.source-zone"></a>**RevealedCard.source-zone.** A
  `RevealedCard` object's `zoneId` `MUST` be the underlying card's zone, and
  its ID `MUST` be a member of the owner's Revealed zone and of no other zone.
- <a id="RevealedCard.created"></a>**RevealedCard.created.** In the GSM where
  a `RevealedCard` first appears, a `RevealedCardCreated` row `MUST` carry its
  instance ID as both `affectorId` and the only `affectedIds` entry.
- <a id="RevealedCard.no-lineage"></a>**RevealedCard.no-lineage.** A
  `RevealedCard` object `MUST NOT` carry `parentId`.

## Open points

- When does a search or look use a `RevealedCard` rather than ordinary
  private visibility?
- When is a `RevealedCard` `Private`, and to whom?
- What ends a disclosure that is never followed by `RevealedCardDeleted`?
