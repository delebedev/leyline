---
summary: "Card game object: zone-bearing card, permanent or spell; identity fields, composite-card membership, visibility and lifecycle."
entity: object_type/Card
---
# Card

A `Card` is the zone-bearing object for a card, permanent or spell. It
carries the characteristics currently shown to the viewer and is the identity
that zones, actions, prompts and most card-directed annotations use.

## Object model

| Field | Role |
|---|---|
| `instanceId` | One object lifetime. Not a durable card identity across zone changes. |
| `grpId` | The card face and characteristics currently shown to the viewer. |
| `overlayGrpId` | The visual overlay; it normally follows the shown face. |
| `zoneId` | The object's zone. Membership is decided by `zones[].objectInstanceIds`. |
| `uniqueAbilities[].id` | An ability handle within this object lifetime. |
| `uniqueAbilities[].grpId` | That ability's definition. |

A `Card` is identified by its own instance, not through a parent. Card-side
companions point their `parentId` at the `Card`, or at the `Token` when the
composite is a token copy, such as a token copy of a Room.

## Zone and visibility

For a composite card — Adventure, Omen, Room, split, disturb or modal
double-faced — the `Card` is the zone member. Its companions appear in
`gameObjects`, but the zone lists only the parent `Card`.

Public zones show public `Card` objects. A hand shows its owner's cards as
`Private` objects with that seat in `viewers`. A zone the recipient cannot see
into lists its members without a `Card` object for them.

## Lifecycle

1. A visible card enters the projection as a new `Card` instance.
2. A zone change either keeps the instance ID or allocates a new one.
3. A new ID is announced by `ObjectIdChanged` before any row that uses it.
4. The old ID is parked in Limbo until `diffDeletedInstanceIds` retires it.

Whether a zone change keeps the ID depends on the change; see
[identity and zones](../model/identity-and-zones.md#instance-identity-over-time).
A consumer follows `ObjectIdChanged`; it does not infer continuity from a
matching `grpId`.

## Sibling shapes

[Token](Token.md) is also zone-bearing, but records its creation lineage in
`objectSourceGrpId` and `parentId`. Card-side companions are projections of a
parent card and are never zone members.

## Contract

- <a id="Card.composite-member"></a>**Card.composite-member.** For a
  composite card, the zone `MUST` list the parent `Card` instance and `MUST
  NOT` list its companion objects.
- <a id="Card.companion-parent"></a>**Card.companion-parent.** A card-side
  companion object `MUST` carry `parentId` equal to the instance ID of its
  parent `Card`, or of its parent `Token` when the composite is a token.
- <a id="Card.ability-handles"></a>**Card.ability-handles.** Within one
  object, each `uniqueAbilities[].id` that is present `MUST` be distinct.
  `RevealedCard` and `Ability` objects can list several entries with no
  `id`.

## Open points

- When does a `Card` carry `parentId` of its own?
- How long does an old ID stay in Limbo before `diffDeletedInstanceIds`
  retires it?
