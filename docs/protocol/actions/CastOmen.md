---
summary: "CastOmen action: casting the Omen half of a card; the parent-card offer, permission branch, stack identity, library return and accepted row."
entity: action/CastOmen
---
# CastOmen

`CastOmen` offers to cast the Omen half of an Omen card. It is a face-selection
cast, not an alternative-cost [`Cast`](Cast.md): the offer names the parent card
and the accepted action carries its own `actionType`.

## Meaning and object model

| Stage | Object |
|---|---|
| Private zone | Parent `Card` with an [`Omen`](../object_types/Omen.md) companion. |
| Offer | `instanceId` is the parent card; `manaCost` is the Omen face's cost. |
| Stack | A `Card` instance carrying the Omen face `grpId`, with the companion beside it. |
| After resolution | The parent card returns to its owner's library with the companion. |

A normal `Cast` offer for the permanent face can coexist with `CastOmen` on the
same parent.

## Offer

| Field | Role |
|---|---|
| `actionType` | `CastOmen`. |
| `instanceId` | The parent card in its current zone. |
| `manaCost` | The Omen face's cost. |
| `sourceId`, `abilityGrpId` | Paired fields on a cast-through-permission branch; they identify the permission, not the Omen face. |

The ordinary offer carries `instanceId` and `manaCost` only. `alternativeGrpId`
is absent on every branch, and an ordinary hand-origin cast installs no
persistent `CastingTimeOption`.

On the permission branch both extra fields appear together, and the mana
requirements are keyed to the permission's ability rather than to the Omen
face's ability.

## Lifecycle

1. The client submits the `CastOmen` action against the parent card.
2. The next GSM reallocates the parent to a Stack instance through
   `ObjectIdChanged`.
3. `ZoneTransfer` moves that instance into Stack with `category` `CastSpell`.
4. The stack card carries the Omen face `grpId`; its companion points back at
   the stack instance and carries the same face.
5. Targeting and payment follow the ordinary spell path.
6. `UserActionTaken` records `actionType` `24` on the stack instance. An
   ordinary cast records `abilityGrpId` `0`; a cast through a permission echoes
   the permission's ability.
7. `ResolutionStart` and `ResolutionComplete` name the stack card and the Omen
   face `grpId`.
8. The stack instance is reallocated to a library identity and `ZoneTransfer`
   moves it from Stack to Library with `category` `Resolve`. The parent card
   with its companion is restored, and a `Shuffle` usually follows.

The Omen spell does not go to the Graveyard as an ordinary instant or sorcery
does.

## Sibling shapes

| Axis | `CastOmen` | `CastAdventure` | Room doors | Alternative-cost `Cast` |
|---|---|---|---|---|
| Offer identity | parent card | parent card | parent room | castable card |
| Cost | Omen face | Adventure face | door face | alternative cost |
| `alternativeGrpId` | absent | absent | absent | present |
| Accepted `actionType` | `24` | `16` | `22`/`23` | `1` |
| Resolution destination | library, parent restored | the Adventure spell's destination | battlefield, parent restored | card-specific |

## Contract

- <a id="CastOmen.parent-instance"></a>**CastOmen.parent-instance.** The offer's
  `instanceId` `MUST` name the parent `Card` and `MUST NOT` name its `Omen`
  companion.
- <a id="CastOmen.face-cost"></a>**CastOmen.face-cost.** The offer's `manaCost`
  `MUST` be the Omen face's cost, not the permanent face's cost.
- <a id="CastOmen.no-alternative-grpid"></a>**CastOmen.no-alternative-grpid.**
  The offer `MUST NOT` carry `alternativeGrpId`.
- <a id="CastOmen.no-hand-option"></a>**CastOmen.no-hand-option.** *(provisional)*
  An ordinary hand-origin `CastOmen` `MUST NOT` install a persistent
  `CastingTimeOption` on the stack instance.
- <a id="CastOmen.permission-pair"></a>**CastOmen.permission-pair.** *(provisional)*
  On a cast through a non-hand permission, `sourceId` and `abilityGrpId` `MUST`
  both be present and `MUST` identify the permission rather than the Omen face.
- <a id="CastOmen.accepted"></a>**CastOmen.accepted.** An accepted `CastOmen`
  `MUST` be recorded by `UserActionTaken` with `actionType` `24`.
- <a id="CastOmen.library-return"></a>**CastOmen.library-return.** *(provisional)*
  The Omen spell's resolution `MUST` move the card from Stack to its owner's
  Library with `category` `Resolve`, restoring the parent card and its
  companion, and `MUST NOT` move it to the Graveyard.

## Open points

- Which non-hand permissions offer `CastOmen`, and do they all use the paired
  `sourceId` and `abilityGrpId` shape?
- Does a permission-branch cast install a `CastingTimeOption` on the stack
  instance?
- How is the private-zone parent and companion projected to the opponent?
