---
summary: "CastRight action: casting the right half of a split card; the parent-card offer with its half-companion source rail and the accepted action type."
entity: action/CastRight
---
# CastRight

`CastRight` offers to cast the right half of a split card. The offer names the
parent card and identifies the selected half through the `SplitRight` companion,
so the action type is narrower than an alternative-cost [`Cast`](Cast.md).

## Meaning and object model

| Stage | Object |
|---|---|
| Hand or other zone | Parent split `Card` with `SplitLeft` and `SplitRight` companions. |
| Offer | `instanceId` is the parent card; `sourceId` is the right-half companion. |
| Stack | A `Card` instance carrying the right half's `grpId`. |
| After resolution | The parent card in its destination zone with a fresh companion pair. |

The parent card is the zone-bearing object. A split card's halves are not
separately playable zone objects; the action names one of them without moving
it.

## Offer

| Field | Role |
|---|---|
| `actionType` | `CastRight`. |
| `instanceId` | The parent split card. |
| `sourceId` | The `SplitRight` companion. |
| `abilityGrpId` | The right half's spell ability. |
| `manaCost` | The right half's cost. |

This source rail distinguishes `CastRight` from the parent-card offers that
carry no companion reference, such as `CastAdventure` and the ordinary
`CastOmen` branch. `CastLeft` is the sibling type for the other half.

## Lifecycle

1. The client submits the `CastRight` action.
2. The parent card is reallocated to a Stack instance, with `ObjectIdChanged`
   then a `ZoneTransfer` carrying `category` `CastSpell`.
3. The stack card carries the selected half's `grpId`. Targeting and payment
   follow the ordinary spell path.
4. `UserActionTaken` records `actionType` `11`.

## Sibling shapes

| Axis | `CastRight` | `CastMdfc` | `PlayMdfc` | `CastAdventure` |
|---|---|---|---|---|
| Action identity | parent split card | parent modal double-faced card | parent modal double-faced card | parent Adventure card |
| Source rail | right-half companion plus ability | spell-face branch | none | none |
| Cost | right half's cost | selected spell face | none | Adventure face |
| Accepted `actionType` | `11` | `18` | `19` | `16` |

## Contract

- <a id="CastRight.parent-instance"></a>**CastRight.parent-instance.** The
  offer's `instanceId` `MUST` name the parent split card, and `MUST NOT` name
  either half companion.
- <a id="CastRight.source-rail"></a>**CastRight.source-rail.** The offer `MUST`
  carry `sourceId` naming the `SplitRight` companion and `abilityGrpId` naming
  the right half's spell ability.
- <a id="CastRight.face-cost"></a>**CastRight.face-cost.** The offer's
  `manaCost` `MUST` be the right half's cost.
- <a id="CastRight.accepted"></a>**CastRight.accepted.** *(provisional)* An
  accepted `CastRight` `MUST` be recorded by `UserActionTaken` with `actionType`
  `11`; the accepted row was not isolated with its ability or source fields, so
  only the action type is stated.

## Open points

- Does the accepted row echo the right half's `abilityGrpId` or `sourceId`?
- Is `CastLeft` symmetric in every field, and does any split card family differ?
- Are half companions projected while the card is in a private zone?
