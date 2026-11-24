---
summary: "MDFCBack game object: the back face of a modal double-faced card as a non-zone companion; face projection, parent linkage and remapping."
entity: object_type/MDFCBack
---
# MDFCBack

An `MDFCBack` is the card-side companion for the back face of a modal
double-faced card. The parent [`Card`](Card.md) remains the gameplay identity and
the zone member; the companion exposes the alternate face, whether or not that
face has been chosen.

## Object model

| Field | Role |
|---|---|
| `instanceId` | The companion's own object lifetime. |
| `grpId` | The back face's definition and characteristics. |
| `othersideGrpId` | The face the parent currently shows. |
| `parentId` | The current parent `Card` instance. |
| `zoneId`, `ownerSeatId`, `controllerSeatId`, `visibility`, `viewers` | Copied from the parent. |
| `uniqueAbilities[]` | The back face's abilities. |

The companion shares the parent's zone, owner, controller, visibility and
viewers and never joins the zone's object list. The parent owns the action, the
zone transfer, the Battlefield presence and the later annotations.

## Zone and visibility

The companion is not a zone member; the parent `Card` is the sole entry in
`zones[].objectInstanceIds`.

| State | Parent `Card` | `MDFCBack` companion |
|---|---|---|
| Front face shown | Front face | Back face |
| Back spell cast or back land played | Back face | Back face |

When the back face is selected, the parent and companion carry the same
back-face `grpId` and remain distinct objects with distinct instance IDs.

## Lifecycle

1. The companion appears beside a visible parent, with `parentId` naming it.
2. While the parent keeps its instance ID, the companion keeps its own.
3. When `ObjectIdChanged` reallocates the parent, the old companion retires and
   a new companion appears with `parentId` naming the replacement.
4. A back-land play creates a Battlefield parent carrying the land-face `grpId`
   with a matching companion; only the parent enters the Battlefield member
   list.
5. An explicit object deletion retires an abandoned companion lifetime.

The companion is not a transfer subject: `ObjectIdChanged` and `ZoneTransfer`
describe the parent card.

## Sibling shapes

`DisturbBack` uses the same back-face topology and differs in the rules that
allow the back face to be cast from a Graveyard. `Adventure` and `Omen` expose a
named half of a card whose main face stays playable, rather than the alternate
face of a modal card. Ordinary transforming permanents produce no parallel back
object.

## Contract

- <a id="MDFCBack.back-identity"></a>**MDFCBack.back-identity.** An `MDFCBack`
  companion's `grpId` `MUST` be the card's other face, and its
  `othersideGrpId` `MUST` be the face the parent `Card` currently shows.
- <a id="MDFCBack.shares-parent-state"></a>**MDFCBack.shares-parent-state.** The
  companion's `zoneId`, `ownerSeatId`, `controllerSeatId` and `visibility`
  `MUST` equal the parent `Card`'s values in the same GSM.
- <a id="MDFCBack.selected-back"></a>**MDFCBack.selected-back.** When the back
  face is selected, the parent `Card` and the companion `MUST` carry the same
  back-face `grpId` and `MUST` remain distinct objects with distinct instance
  IDs.
- <a id="MDFCBack.remap"></a>**MDFCBack.remap.** When the parent receives a new
  instance ID, a companion with `parentId` naming the new ID `MUST` appear, and
  the previous companion `MUST` retire.

## Open points

- Is the companion projected in every zone the parent occupies, including
  Command?
- Does the companion's `othersideGrpId` ever name something other than the
  parent's shown face?
