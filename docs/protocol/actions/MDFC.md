---
summary: "CastMdfc and PlayMdfc actions: selecting the spell or land face of a modal double-faced card from the parent card in hand."
entity: action/MDFC
---
# CastMdfc and PlayMdfc

`CastMdfc` and `PlayMdfc` select one face of a modal double-faced card directly
from the parent card in hand. `CastMdfc` casts the spell face; `PlayMdfc` plays
the land face. Both are face-selection actions with their own action types, not
alternative-cost [`Cast`](Cast.md) actions, and neither carries an
`alternativeGrpId`.

## Meaning and object model

| Stage | Object |
|---|---|
| Hand | Parent `Card` with an `MDFCBack` companion. |
| Offer | `instanceId` is the parent card. |
| `CastMdfc` stack | A `Card` instance carrying the chosen spell face. |
| `PlayMdfc` battlefield | The parent `Card` carrying the chosen land face. |

The parent is the object offered, moved and later annotated on both branches.
The companion identifies the unchosen face and is not part of the offer.

## Offer

### CastMdfc

| Field | Role |
|---|---|
| `actionType` | `CastMdfc`. |
| `instanceId` | The parent card. |
| `manaCost` | The chosen spell face's cost, when the offer charges one. |
| `abilityGrpId`, `sourceId` | Present together on the source branch; they name the spell face's ability and the source object. |

On the established source branch `sourceId` equals `instanceId`. Some offers
carry no `manaCost`.

### PlayMdfc

| Field | Role |
|---|---|
| `actionType` | `PlayMdfc`. |
| `instanceId` | The parent card. |

A `PlayMdfc` offer carries no cost, ability, source or alternative-cost field.
The land face is chosen by the action type alone, and the play is subject to the
ordinary land-play limit.

## Lifecycle

### Spell face

1. The client submits `CastMdfc`.
2. The parent is reallocated to a Stack instance through `ObjectIdChanged`, then
   `ZoneTransfer` with `category` `CastSpell`.
3. The stack card carries the chosen spell face. Target prompts appear when the
   face targets, and mana payment follows the ordinary spell path.
4. `UserActionTaken` records `actionType` `18`.

### Land face

1. The client submits `PlayMdfc`.
2. The parent enters the Battlefield under the chosen land face, within the
   land-play limit for the turn.
3. `UserActionTaken` records `actionType` `19` with `abilityGrpId` `0`.

No stack object and no spell resolution belong to the land branch.

## Sibling shapes

| Axis | `CastMdfc` | `PlayMdfc` | `CastOmen` | Alternative-cost `Cast` |
|---|---|---|---|---|
| Offer fields | `instanceId`, `manaCost`; source branch adds `abilityGrpId` and `sourceId` | `instanceId` only | `instanceId`, `manaCost` | `instanceId`, `manaCost`, `alternativeGrpId` |
| Stack object | yes | no | yes | yes |
| Land-play limit | no | yes | no | no |
| Accepted `actionType` | `18` | `19` | `24` | `1` |

## Contract

- <a id="MDFC.cast-instance"></a>**MDFC.cast-instance.** A `CastMdfc` offer
  `MUST` carry `instanceId` naming the parent card, and `MUST NOT` carry the
  `MDFCBack` companion's instance ID.
- <a id="MDFC.cast-source-pair"></a>**MDFC.cast-source-pair.** *(provisional)*
  When a `CastMdfc` offer carries `sourceId`, it `MUST` also carry
  `abilityGrpId`.
- <a id="MDFC.play-fields"></a>**MDFC.play-fields.** A `PlayMdfc` offer `MUST`
  carry `instanceId` and `MUST NOT` carry `manaCost`, `abilityGrpId`,
  `sourceId`, `grpId`, `alternativeGrpId` or `alternativeSourceZcid`.
- <a id="MDFC.no-alternative"></a>**MDFC.no-alternative.** Neither offer `MUST`
  carry `alternativeGrpId`.
- <a id="MDFC.cast-accepted"></a>**MDFC.cast-accepted.** *(provisional)* An
  accepted `CastMdfc` `MUST` be recorded by `UserActionTaken` with `actionType`
  `18`; only the action-type integer is grounded.
- <a id="MDFC.play-accepted"></a>**MDFC.play-accepted.** An accepted `PlayMdfc`
  `MUST` be recorded by `UserActionTaken` with `actionType` `19` and
  `abilityGrpId` `0`.

## Open points

- When does a `CastMdfc` offer omit `manaCost`?
- Can `sourceId` name an object other than the parent card?
- Does the accepted `CastMdfc` row echo the offer's `abilityGrpId`?
