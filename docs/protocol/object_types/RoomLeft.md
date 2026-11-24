---
summary: "RoomLeft game object: the left door of a Room enchantment as a non-zone companion; paired projection, stack face identity and parent-owned door state."
entity: object_type/RoomLeft
---
# RoomLeft

A `RoomLeft` is the card-side companion for the left door of a Room
enchantment. It is materialized beside `RoomRight` while a parent
[`Card`](Card.md) remains the room's gameplay identity and sole zone member.

## Object model

| Field | Role |
|---|---|
| `instanceId` | The companion's own object lifetime. |
| `grpId` | The left door's definition and characteristics. |
| `parentId` | The current Room `Card`. |
| `zoneId`, `ownerSeatId`, `controllerSeatId`, `visibility`, `viewers` | Copied from the parent. |
| `uniqueAbilities[]` | The left door's abilities. |

The matching right companion points at the same parent with an independent face
identity and ability set. Neither door companion appears in
`zones[].objectInstanceIds`; door actions, payments, targets, designations and
zone transfers use the parent instance.

## Zone and visibility

The companion is not a zone member. Both door companions are projected together
and share the parent's zone and visibility.

On the Stack, the parent `Card` uses the `grpId` of the door being cast while
both companions keep exposing their own faces. On the Battlefield, the parent
returns to the combined Room `grpId` and both companions persist.

## Lifecycle

1. A visible Room parent receives both door companions.
2. Casting either door can reallocate the parent and both companion lifetimes.
3. When Stack-to-Battlefield resolution preserves the parent instance ID, both
   companion instance IDs persist with it.
4. Unlocking a door changes parent-owned designation state; it does not turn the
   companion into a separate permanent.
5. Parent retirement retires both companions.

## Sibling shapes

`RoomRight` is structurally symmetric; face `grpId`s, names and abilities
distinguish the two. Split-card halves also materialize as a pair, but Room
companions persist with a Battlefield permanent and the door state lives on the
parent. The door actions are described in [Rooms](../actions/Rooms.md).

## Contract

- <a id="RoomLeft.face-identity"></a>**RoomLeft.face-identity.** A `RoomLeft`
  companion's `grpId` `MUST` be the left door face and its `parentId` `MUST` name
  the Room `Card` in the same GSM.
- <a id="RoomLeft.pair-together"></a>**RoomLeft.pair-together.** A Room's
  projection `MUST` carry both door companions in the same GSM, with the same
  `parentId`, in every zone the parent is sent in, including a hand or library
  sent privately to its viewer.
- <a id="RoomLeft.selected-door"></a>**RoomLeft.selected-door.** *(provisional)*
  While a door is on the Stack, the parent `Card` `MUST` carry that door's
  `grpId` and both companions `MUST` remain present.
- <a id="RoomLeft.battlefield-persist"></a>**RoomLeft.battlefield-persist.**
  *(provisional)* When resolution preserves the parent instance ID, both
  companion instance IDs `MUST` persist with it.
- <a id="RoomLeft.unlocked-on-parent"></a>**RoomLeft.unlocked-on-parent.** The
  unlocked state of the left door `MUST` be carried by a designation on the
  parent `Card` and `MUST NOT` change or retire the `RoomLeft` companion.

## Open points

- Does the companion pair survive a parent move that reallocates the parent?
