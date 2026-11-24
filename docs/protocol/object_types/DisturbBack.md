---
summary: "DisturbBack game object: the back face of a card with Disturb as a non-zone companion; face projection, cast-from-graveyard identity and remapping."
entity: object_type/DisturbBack
---
# DisturbBack

A `DisturbBack` is the card-side companion for the back face of a card with
Disturb. It stays linked to the current parent [`Card`](Card.md) whether the
parent shows its front face or has been cast and resolved transformed.

## Object model

| Field | Role |
|---|---|
| `instanceId` | The companion's own object lifetime. |
| `grpId` | The back face's definition and characteristics. |
| `othersideGrpId` | The front face, the reverse direction of the parent's link. |
| `parentId` | The current parent `Card` instance. |
| `zoneId`, `ownerSeatId`, `controllerSeatId`, `visibility`, `viewers` | Copied from the parent. |
| `uniqueAbilities[]` | The back face's abilities. |

The `othersideGrpId` link points in opposite directions on the two objects: a
front-facing parent names the back face, and the companion names the front face.
Only the parent belongs to the zone.

The companion is not a cast marker. It is projected in every zone the parent is
projected in, including private zones.

## Zone and visibility

The companion is not a zone member; the parent `Card` is the sole entry in
`zones[].objectInstanceIds`.

| State | Parent `Card` | `DisturbBack` companion |
|---|---|---|
| Front face in hand, graveyard or an ordinary cast | Front face | Back face |
| Disturb spell on the Stack | Back face | Back face |
| Permanent resolved through Disturb | Back face | Back face |

After a Disturb cast both objects can carry the back-face `grpId` and remain
distinct.

## Lifecycle

1. A visible front-face parent receives a companion.
2. An ordinary cast or zone move reallocates the companion when
   `ObjectIdChanged` reallocates the parent.
3. A Disturb cast maps the Graveyard instance to a Stack `Card` carrying the
   back-face `grpId` and creates a new companion beside it.
4. Stack-to-Battlefield resolution can preserve the parent and companion
   instance IDs.
5. A parent reallocation or disappearance retires the old companion.

The parent receives the cast, resolution and zone-transfer annotations; the
companion does not move zones on its own.

## Sibling shapes

[`MDFCBack`](MDFCBack.md) is the closest topology: both expose a back face
across the parent's lifecycle. Disturb is distinguished by the rules that allow
its back face to be cast from a Graveyard. Daybound and nightbound transforms
produce no parallel back object at all.

## Contract

- <a id="DisturbBack.back-identity"></a>**DisturbBack.back-identity.** A
  `DisturbBack` companion's `grpId` `MUST` be the back face and its
  `othersideGrpId` `MUST` be the front face.
- <a id="DisturbBack.shares-parent-state"></a>**DisturbBack.shares-parent-state.**
  The companion's `zoneId`, `ownerSeatId`, `controllerSeatId` and `visibility`
  `MUST` equal the parent `Card`'s values in the same GSM.
- <a id="DisturbBack.every-zone"></a>**DisturbBack.every-zone.** A visible card
  with a Disturb back face `MUST` carry its companion in every zone the parent
  is projected in, including the Stack, the Battlefield and Exile.
- <a id="DisturbBack.disturb-cast"></a>**DisturbBack.disturb-cast.**
  *(provisional)* A Disturb cast `MUST` produce a Stack `Card` carrying the
  back-face `grpId` with a companion whose `parentId` names that Stack instance.
- <a id="DisturbBack.transform-no-companion"></a>**DisturbBack.transform-no-companion.**
  A permanent that transformed `MUST NOT` gain a companion object whose
  `parentId` names it.
- <a id="DisturbBack.remap"></a>**DisturbBack.remap.** When the parent receives a
  new instance ID, a companion with `parentId` naming the new ID `MUST` appear,
  and the previous companion `MUST` retire.

## Open points

- Is the companion projected for a face-down card in Exile or on the Stack?
- Does a Disturb cast reuse the Graveyard companion or always create a new one?
