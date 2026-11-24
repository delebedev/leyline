---
summary: "SplitLeft game object: the left half of a split card as a non-zone companion; paired projection, selected-face identity and remapping."
entity: object_type/SplitLeft
---
# SplitLeft

A `SplitLeft` is the card-side companion for the left half of a split card. It is
materialized together with `SplitRight` around one parent [`Card`](Card.md),
which stays the spell or card identity used by zones and annotations.

## Object model

| Field | Role |
|---|---|
| `instanceId` | The companion's own object lifetime. |
| `grpId` | The left half's definition and characteristics. |
| `parentId` | The current whole-card or spell `Card`. |
| `zoneId`, `ownerSeatId`, `controllerSeatId`, `visibility`, `viewers` | Copied from the parent. |
| `uniqueAbilities[]` | The left half's abilities. |

The right companion uses a separate instance ID and its own half identity with
the same parent. Only the parent belongs to Stack, Graveyard or Exile; the half
objects are display projections, not separately playable zone objects.

## Zone and visibility

The companion is not a zone member; the parent `Card` is the sole entry in
`zones[].objectInstanceIds`. Both half companions are projected together and
share the parent's zone and visibility.

On the Stack, the parent `Card` adopts the selected half's `grpId` and
characteristics while both companions stay available. After resolution, a
replacement parent in the destination zone carries the whole split-card `grpId`
and receives newly allocated left and right companions.

## Lifecycle

1. Casting the selected half creates a Stack parent and both half companions.
2. The selected half decides the parent spell's `grpId` and does not remove the
   unselected companion.
3. Resolution reallocates the parent into its destination zone when required.
4. Both old companions retire and a new pair appears with `parentId` naming the
   destination parent.
5. Later zone moves repeat the parent-and-pair reallocation.

The companions are not transfer subjects; `ObjectIdChanged` and `ZoneTransfer`
describe the parent card.

## Sibling shapes

`SplitRight` is structurally symmetric and differs through its half-specific
characteristics. Room sides also materialize as a pair, but they persist with a
Battlefield permanent and their unlocked-door state lives on the parent. Back
faces such as [`MDFCBack`](MDFCBack.md) expose an alternate face of one card
rather than two named halves.

## Contract

- <a id="SplitLeft.face-identity"></a>**SplitLeft.face-identity.** A `SplitLeft`
  companion's `grpId` `MUST` be the left half and its `parentId` `MUST` name the
  whole-card `Card` in the same GSM.
- <a id="SplitLeft.pair-together"></a>**SplitLeft.pair-together.** On the Stack, in a
  Graveyard and in Exile, a split card's projection `MUST` carry both half
  companions in the same GSM, with the same `parentId`.
- <a id="SplitLeft.selected-face"></a>**SplitLeft.selected-face.**
  *(provisional)* While the split card is on the Stack, the parent `Card` `MUST`
  carry the selected half's `grpId` and both companions `MUST` remain present.
- <a id="SplitLeft.shares-parent-state"></a>**SplitLeft.shares-parent-state.**
  The companion's `zoneId`, `ownerSeatId`, `controllerSeatId` and `visibility`
  `MUST` equal the parent `Card`'s values in the same GSM.
- <a id="SplitLeft.remap-pair"></a>**SplitLeft.remap-pair.** When the parent
  receives a new instance ID, both half companions `MUST` be replaced by a pair
  whose `parentId` names the new ID.

## Open points

- Does a split card in a hand or library carry the half pair?
- Does a fused or aftermath split card differ in its half identities?
