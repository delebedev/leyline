---
summary: "Omen game object: the Omen half of a card as a non-zone companion; face projection, parent linkage, remapping and library return."
entity: object_type/Omen
---
# Omen

An `Omen` is the card-side companion for the Omen half of an Omen card. The
parent [`Card`](Card.md) stays the gameplay object and the zone member; the
companion carries the Omen face's identity and abilities for display and for the
face-selection cast.

## Object model

| Field | Role |
|---|---|
| `instanceId` | The companion's own object lifetime. |
| `grpId` | The Omen face's definition and characteristics. |
| `parentId` | The current parent `Card` instance. |
| `zoneId`, `ownerSeatId`, `controllerSeatId`, `visibility`, `viewers` | Copied from the parent. |
| `uniqueAbilities[].id` | An ability handle within the companion. |
| `uniqueAbilities[].grpId` | That ability's definition on the Omen face. |

The companion shares the parent's zone, owner, controller, visibility and
viewers, and its ability handles are independent of the parent's. `CastOmen`,
target selection, payment, zone transfers and resolution all name the parent
instance.

## Zone and visibility

The companion is not a zone member. The parent `Card` is the sole entry in
`zones[].objectInstanceIds`.

| State | Parent `Card` | `Omen` companion |
|---|---|---|
| Hand, Library, Battlefield, Graveyard, Exile | The permanent face | The Omen face |
| Omen spell on the Stack | The Omen face | The Omen face |

A move into a hidden Library can briefly expose the parent and companion before
the shuffle retires both.

## Lifecycle

1. The companion appears when its parent becomes visible, with `parentId`
   naming that parent instance.
2. While the parent keeps its instance ID, the companion keeps its own.
3. When `ObjectIdChanged` reallocates the parent, the old companion retires and
   a new companion appears with `parentId` naming the replacement.
4. When the parent leaves the viewer's projection, or the Library projection is
   retired by a shuffle, the companion retires with it.

The companion is not itself a transfer subject; `ObjectIdChanged` and
`ZoneTransfer` describe the parent card.

## Sibling shapes

[`Adventure`](Adventure.md) uses the same parent-and-companion topology and
differs in its cast action and its resolution path. Back-face companions such as
`MDFCBack` expose an alternate face of the same card, while `RoomLeft`,
`RoomRight`, `SplitLeft` and `SplitRight` materialize two named sides at once.

## Contract

- <a id="Omen.face-identity"></a>**Omen.face-identity.** An `Omen` companion's
  `grpId` `MUST` be the Omen face and its `parentId` `MUST` name the parent
  `Card` in the same GSM.
- <a id="Omen.shares-parent-state"></a>**Omen.shares-parent-state.** The
  companion's `zoneId`, `ownerSeatId`, `controllerSeatId` and `visibility`
  `MUST` equal the parent `Card`'s values in the same GSM.
- <a id="Omen.stack-face"></a>**Omen.stack-face.** *(provisional)* While the
  Omen spell is on the Stack, the parent `Card`'s `grpId` `MUST` be the Omen
  face and the companion `MUST` carry the same face.
- <a id="Omen.remap"></a>**Omen.remap.** When the parent receives a new instance
  ID, a companion with `parentId` naming the new ID `MUST` appear, and the
  previous companion `MUST` retire.

## Open points

- Does an Omen companion persist across the Stack-to-Library return when the
  parent instance ID changes, or is it always rebuilt?
- Does the companion ever carry abilities the Omen face does not have, such as a
  cast permission attached at cast time?
