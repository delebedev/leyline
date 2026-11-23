---
summary: "ObjectIdChanged annotation: an object received a new instance ID; fields, ordering against dependent rows, Limbo retirement and the resolve-to-battlefield exception."
read_when:
  - "emitting or consuming ObjectIdChanged"
  - "allocating a new instance ID during a zone change"
  - "ordering annotations that name a renamed object"
entity: annotation/ObjectIdChanged
---
# ObjectIdChanged

## Meaning

A card that changes zones becomes a new object with no memory of its previous
existence (CR 400.7). The protocol models this by giving the object a new
instance ID. `ObjectIdChanged` records the mapping from the old ID to the new
one so that the client can carry the object's presentation across the change.

## Variants

One transient row per renamed object. The type has no persistent branch.

## Fields

| Field | Role |
|---|---|
| `affectorId` | When present, the source responsible for the change. Many rows have none. |
| `affectedIds` | The old instance ID, as a one-element list. |
| `orig_id` | The instance ID before the change. |
| `new_id` | The instance ID after the change. |

## Identity model

`orig_id` and `new_id` are instance IDs in the same domain. After the row, the
object is named only by `new_id`. The retired `orig_id` is parked in the Limbo
zone (`zoneId` `30`), which lists retired IDs in `objectInstanceIds` without a
game object for them. Limbo is a holding list, not a destination: parking an ID
there carries no `ZoneTransfer`.

Rows in the same GSM that describe the moved object, such as
[ZoneTransfer](ZoneTransfer.md) and
[EnteredZoneThisTurn](EnteredZoneThisTurn.md), name it by `new_id`. Detail keys
defined to hold an earlier identity, such as `ReplacementSourceZcid` on
[ReplacementEffect](ReplacementEffect.md), name it by `orig_id`.

## Lifecycle

1. A zone change allocates `new_id` for the object.
2. In the GSM that publishes the change, `ObjectIdChanged` maps `orig_id` to
   `new_id`, and the Limbo zone lists `orig_id`.
3. Later rows in that GSM name the object by `new_id`. A dependent
   `ZoneTransfer` follows the `ObjectIdChanged` in `annotations`.

A permanent spell that resolves onto the battlefield keeps its stack instance
ID: its `ZoneTransfer` with `category` `Resolve` names the stack ID and no
`ObjectIdChanged` accompanies it. A spell that resolves into any other zone,
such as an instant going to the graveyard, receives a new ID.

Casting a spell renames the card when it moves from hand to the stack, and
playing a land renames it when it moves from hand to the battlefield.

## Sibling shapes

`Shuffle` also renames objects, through its `OldIds` and `NewIds` lists, and
carries no `ObjectIdChanged`. `diffDeletedInstanceIds` removes game objects
from the state; it is not a rename.

## Contract

- <a id="ObjectIdChanged.affected-orig"></a>**ObjectIdChanged.affected-orig.**
  An `ObjectIdChanged` row `MUST` carry `affectedIds` equal to exactly its
  `orig_id`.
- <a id="ObjectIdChanged.precedes-transfer"></a>**ObjectIdChanged.precedes-transfer.**
  When a `ZoneTransfer` in the same GSM names the row's `new_id`, the
  `ObjectIdChanged` `MUST` precede that `ZoneTransfer` in `annotations`.
- <a id="ObjectIdChanged.limbo"></a>**ObjectIdChanged.limbo.**
  The GSM that carries an `ObjectIdChanged` row `MUST` list its `orig_id` in
  the Limbo zone's `objectInstanceIds`.
- <a id="ObjectIdChanged.resolve-battlefield-keeps-id"></a>**ObjectIdChanged.resolve-battlefield-keeps-id.**
  A permanent spell that resolves onto the battlefield `MUST NOT` be renamed:
  its `Resolve` `ZoneTransfer` names the stack instance ID and no
  `ObjectIdChanged` maps that ID in the same GSM.
- <a id="ObjectIdChanged.resolve-elsewhere-renames"></a>**ObjectIdChanged.resolve-elsewhere-renames.**
  A spell that resolves into a zone other than the battlefield `MUST` be
  renamed by an `ObjectIdChanged` in the GSM of its `Resolve` `ZoneTransfer`.

## Open points

- What does `affectorId` identify when present, and what selects its presence?
- Is ordering against other rows that name `new_id` constrained beyond
  `ZoneTransfer`?
- Can one object be renamed more than once in a single GSM, and how is the
  chain ordered when it is?
