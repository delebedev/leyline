---
summary: "Adventure game object: the Adventure half of a card as a non-zone companion; face projection, parent linkage and remapping."
entity: object_type/Adventure
---
# Adventure

An `Adventure` is the card-side companion for the Adventure half of an Adventure
card. The parent [`Card`](Card.md) stays the gameplay object and the zone
member; the companion carries the Adventure face's identity and abilities for
display and for the face-selection cast.

## Object model

| Field | Role |
|---|---|
| `instanceId` | The companion's own object lifetime. |
| `grpId` | The Adventure face's definition and characteristics. |
| `parentId` | The current parent `Card` instance. |
| `zoneId`, `ownerSeatId`, `controllerSeatId`, `visibility`, `viewers` | Copied from the parent. |
| `uniqueAbilities[].id` | An ability handle within the companion. |
| `uniqueAbilities[].grpId` | That ability's definition on the Adventure face. |

The companion shares the parent's zone, owner, controller, visibility and
viewers, and its ability handles are independent of the parent's. Actions, zone
transfers, targeting and resolution name the parent instance, never the
companion.

## Zone and visibility

The companion is not a zone member. The parent `Card` is the sole entry in
`zones[].objectInstanceIds`.

| State | Parent `Card` | `Adventure` companion |
|---|---|---|
| Hand, Exile, Graveyard | The permanent face | The Adventure face |
| Adventure spell on the Stack | The Adventure face | The Adventure face |
| Permanent spell on the Stack | The permanent face | The Adventure face |
| Battlefield | The permanent face | The Adventure face |

A viewer that cannot see the parent does not receive the companion either, since
the two share visibility and viewers.

## Lifecycle

1. The companion appears when its parent becomes visible, with `parentId`
   naming that parent instance.
2. While the parent keeps its instance ID, the companion keeps its own.
3. When `ObjectIdChanged` reallocates the parent, the old companion retires and
   a new companion appears with `parentId` naming the replacement.
4. When the parent leaves the viewer's projection, the companion retires with
   it.

`ObjectIdChanged` and `ZoneTransfer` name the parent card; the companion is not
itself a transfer subject.

## Sibling shapes

`Omen` uses the same parent-and-companion topology and differs in its cast
action and its resolution path. Back-face companions such as
`MDFCBack` expose an alternate face of the same card, while `RoomLeft`,
`RoomRight`, `SplitLeft` and `SplitRight` materialize two named sides at once.

## Contract

- <a id="Adventure.face-identity"></a>**Adventure.face-identity.** An
  `Adventure` companion's `grpId` `MUST` be the Adventure face and its
  `parentId` `MUST` name the parent `Card` in the same GSM.
- <a id="Adventure.shares-parent-state"></a>**Adventure.shares-parent-state.**
  The companion's `zoneId`, `ownerSeatId`, `controllerSeatId` and `visibility`
  `MUST` equal the parent `Card`'s values in the same GSM.
- <a id="Adventure.stack-face"></a>**Adventure.stack-face.** *(provisional)*
  While the Adventure spell is on the Stack, the parent `Card`'s `grpId` `MUST`
  be the Adventure face and the companion `MUST` carry the same face.
- <a id="Adventure.remap"></a>**Adventure.remap.** When the parent receives a
  new instance ID, a companion with `parentId` naming the new ID `MUST` appear,
  and the previous companion `MUST` retire.

## Open points

- Does the companion survive a parent move the same GSM keeps the parent
  instance ID, or is it rebuilt whenever the projection changes?
- Which zone transitions of the parent retire the companion without an explicit
  deletion row?
