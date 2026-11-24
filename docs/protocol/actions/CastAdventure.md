---
summary: "CastAdventure action: casting the Adventure half of a card; the parent-card offer, face cost, stack identity and accepted row."
entity: action/CastAdventure
---
# CastAdventure

`CastAdventure` offers to cast the Adventure half of an Adventure card. It is a
face-selection cast: the offer names the parent card, not the
[`Adventure`](../object_types/Adventure.md) companion, and the accepted action
carries its own `actionType`.

## Meaning and object model

| Stage | Object |
|---|---|
| Private zone | Parent `Card` with an `Adventure` companion beside it. |
| Offer | `instanceId` is the parent card. |
| Stack | A new `Card` instance carrying the Adventure face `grpId`. |
| After resolution | The card follows the Adventure spell's own destination. |

A normal [`Cast`](Cast.md) offer for the permanent face can coexist on the same
parent card.

## Offer

| Field | Role |
|---|---|
| `actionType` | `CastAdventure`. |
| `instanceId` | The parent card in its current zone. |
| `manaCost` | The Adventure face's cost. |

No `abilityGrpId`, `sourceId`, `alternativeGrpId` or `alternativeSourceZcid`
appears. The Adventure identity travels as the action type plus the parent
`instanceId`; the companion's own instance ID is never offered.

## Lifecycle

1. The client submits the `CastAdventure` action.
2. The next GSM reallocates the parent to a Stack instance: `ObjectIdChanged`,
   then a `ZoneTransfer` into Stack with `category` `CastSpell`.
3. The stack card carries the Adventure face `grpId`. Targeting and payment
   follow the ordinary spell path.
4. `UserActionTaken` records `actionType` `16` with `abilityGrpId` `0`,
   affecting the stack instance.

The accepted row does not echo the Adventure ability. By accept time the
affected object is the stack instance produced by the cast.

## Sibling shapes

| Axis | `CastAdventure` | `CastOmen` | Room doors | Alternative-cost `Cast` |
|---|---|---|---|---|
| Offer identity | parent card | parent card | parent room | castable card |
| Cost | Adventure face | Omen face | door face | alternative cost |
| `alternativeGrpId` | absent | absent | absent | present |
| Accepted `actionType` | `16` | `24` | `22`/`23` | `1` |

## Contract

- <a id="CastAdventure.parent-instance"></a>**CastAdventure.parent-instance.**
  The offer's `instanceId` `MUST` name the parent `Card` and `MUST NOT` name
  its `Adventure` companion.
- <a id="CastAdventure.face-cost"></a>**CastAdventure.face-cost.** The offer's
  `manaCost` `MUST` be the Adventure face's cost, not the permanent face's
  cost.
- <a id="CastAdventure.minimal-fields"></a>**CastAdventure.minimal-fields.**
  The offer `MUST NOT` carry `abilityGrpId`, `sourceId`, `alternativeGrpId` or
  `alternativeSourceZcid`.
- <a id="CastAdventure.no-grp-id"></a>**CastAdventure.no-grp-id.** The offer
  `MUST NOT` carry `grpId`.
- <a id="CastAdventure.accepted"></a>**CastAdventure.accepted.** An accepted
  `CastAdventure` `MUST` be recorded by `UserActionTaken` with `actionType`
  `16` and `abilityGrpId` `0`.

## Open points

- Which zones and non-hand permissions offer `CastAdventure`, and do they add
  a source rail?
- Where does the card go when the Adventure spell's effect sends it somewhere
  other than the graveyard?
