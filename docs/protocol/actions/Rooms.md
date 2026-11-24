---
summary: "CastLeftRoom and CastRightRoom actions: casting one door of a Room enchantment from the hand, unlocking a door on the Battlefield, stack face identity and unlocked-door state."
entity: action/Rooms
---
# CastLeftRoom and CastRightRoom

`CastLeftRoom` and `CastRightRoom` cast one door of a Room enchantment. They
share one lifecycle and differ in the selected side, the accepted action type,
the face carried by the stack card and the unlocked-door designation the
resolution leaves on the parent. A Room already on the Battlefield unlocks its
other door through a `Special` action instead, which does not use the stack.

## Meaning and object model

A Room is one zone-bearing `Card` with a `RoomLeft` and a `RoomRight` companion.
Both companions point at the parent and stay outside the zone's object list;
offers, transfers and designations name the parent instance.

| Stage | Object identity |
|---|---|
| Hand | Parent room `grpId` on the zone-bearing card. |
| Stack | The selected door's face `grpId` on the stack card. |
| Battlefield after resolution | Parent room `grpId` again. |

The stack card is the object that is targeted, paid for and resolved. The face
`grpId` changes for the stack projection without replacing the room's durable
parent identity.

## Offer

Door casts are offered for a Room in the hand. Both doors use the same
minimal shape on the GSM rail:

```text
{ actionType: CastLeftRoom,  instanceId: <parent room>, manaCost: <left door cost> }
{ actionType: CastRightRoom, instanceId: <parent room>, manaCost: <right door cost> }
```

| Field | Role |
|---|---|
| `actionType` | Selects the left or the right door. |
| `instanceId` | The parent room in the hand. |
| `manaCost` | The selected door's cost. |

No `abilityGrpId`, `sourceId` or `alternativeGrpId` appears on either offer.
On `ActionsAvailableReq` each offer also carries `grpId` naming the selected
door's face and `facetId` naming the parent; the GSM rail carries neither.
When both doors are castable from hand, both offers can coexist on the same
parent instance.

## Lifecycle

1. The client submits one door action.
2. A hand-origin parent is reallocated to a Stack identity through
   `ObjectIdChanged` and `ZoneTransfer` with `category` `CastSpell`.
3. The stack card carries the selected door's face `grpId`. Targeting and mana
   payment follow the ordinary spell path.
4. `UserActionTaken` records the door action: `actionType` `22` for the left
   door or `23` for the right door, with `abilityGrpId` `0`.
5. Resolution moves the room to the Battlefield under its parent `grpId`, and
   the door's unlocked designation is recorded on the parent: `LeftUnlocked` for
   the left door, `RightUnlocked` for the right door.

The room stays on the Battlefield after resolution. It is not discarded, exiled
or returned to a library by the door cast itself.

### Unlocking a door on the Battlefield

A player who controls a Room with a locked door may pay that door's cost to
unlock it, any time they have priority in a main phase of their turn while the
stack is empty (CR 709.5e). This is a special action, not a cast:

1. The locked door is offered as a `Special` action on the Battlefield parent.
   Its `abilityGrpId` and `alternativeGrpId` both name the door's unlock
   ability, and `manaCost` is the door's cost.
2. The client submits the action and pays the cost; `ManaPaid` rows name the
   parent.
3. The same GSM carries `GainDesignation` for the door's unlocked designation
   on the parent, with the unlock ability's ID as `affectorId`, the matching
   persistent designation, and `UserActionTaken` with `actionType` `7` and the
   unlock ability in `abilityGrpId` and `alternativeGrpId`.

Nothing goes on the Stack, and the parent keeps its instance ID.

No separate fully-unlocked designation is part of the shape; the two persistent
door designations jointly express that state. The room's shared door-state
ability rows remain on the parent alongside the two door abilities.

## Sibling shapes

| Axis | Room door | `CastOmen` | Alternative-cost `Cast` |
|---|---|---|---|
| Offer discriminator | `CastLeftRoom` or `CastRightRoom` | `CastOmen` | `Cast` plus alternative fields |
| Offer identity | parent room | parent Omen card | castable card |
| `alternativeGrpId` | absent | absent | present |
| Accepted `actionType` | `22` or `23` | `24` | `1` |
| Stack identity | selected door face | Omen face | card-specific |
| Resolution result | Battlefield parent plus a door designation | Library parent | card-specific |
| Second door on the same parent | unlocked by a `Special` action | no | mechanic-specific |

## Contract

- <a id="Rooms.door-offer"></a>**Rooms.door-offer.** A room door offer `MUST`
  carry `instanceId` naming the parent room in the hand and `manaCost`
  carrying the selected door's cost.
- <a id="Rooms.door-discriminator"></a>**Rooms.door-discriminator.** The door
  `MUST` be selected by `actionType`, and the offer `MUST NOT` carry
  `abilityGrpId`, `sourceId` or `alternativeGrpId`. On the GSM rail it `MUST
  NOT` carry `grpId` either.
- <a id="Rooms.request-face"></a>**Rooms.request-face.** A room door offer on
  `ActionsAvailableReq` `MUST` carry `grpId` naming the selected door's face
  and `facetId` naming the parent room.
- <a id="Rooms.stack-face"></a>**Rooms.stack-face.** *(provisional)* The stack
  card produced by a door cast `MUST` carry the selected door's face `grpId`,
  and resolution `MUST` restore the parent room `grpId` on the Battlefield.
- <a id="Rooms.accepted"></a>**Rooms.accepted.** An accepted left-door cast
  `MUST` be recorded by `UserActionTaken` with `actionType` `22`, and an
  accepted right-door cast with `actionType` `23`, each with `abilityGrpId` `0`.
- <a id="Rooms.unlocked"></a>**Rooms.unlocked.** The resolution of a door cast
  `MUST` record the door's unlocked designation on the parent room: `19` for
  the left door and `20` for the right door, on both the transient and the
  persistent rail.
- <a id="Rooms.unlock-offer"></a>**Rooms.unlock-offer.** A locked door of a
  Room on the Battlefield `MUST` be offered as a `Special` action on the
  parent instance whose `abilityGrpId` and `alternativeGrpId` name the door's
  unlock ability and whose `manaCost` is the door's cost, and `MUST NOT` be
  offered as `CastLeftRoom` or `CastRightRoom`.
- <a id="Rooms.unlock-applied"></a>**Rooms.unlock-applied.** An accepted
  unlock `MUST` carry, in one GSM, the door's `GainDesignation` and
  persistent designation on the parent and `UserActionTaken` with `actionType`
  `7` naming the unlock ability, and `MUST NOT` put an object on the Stack.
- <a id="Rooms.stays"></a>**Rooms.stays.** The room `MUST` remain on the
  Battlefield after the door cast resolves.

## Open points

- What closes a door, and is a locked door's state carried by the absence of the
  unlocked designation alone?
- What do the parent's shared door-state ability rows mean before either door
  is unlocked?
