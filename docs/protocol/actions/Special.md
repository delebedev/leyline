---
summary: "Special action: the costed special-action rail for mechanics such as foretell and plot; mandatory ability, optional alternative fields and the accepted action."
read_when:
  - "offering or accepting a Special action"
  - "distinguishing a special action from an alternative-cost cast"
entity: action/Special
---
# Special

## Meaning and object model

`Special` is the rail for costed special actions (CR 116), used by several
mechanics and card-specific actions, such as foretelling or plotting a card.
`instanceId` is the object acted on; `abilityGrpId` is the special action's
ability. The resulting zone change and object identities belong to the
mechanic, not to the action type.

A special action is not an alternative-cost cast. It has its own action type,
and its accepted row keeps a nonzero `abilityGrpId` instead of carrying
`actionType` `1` plus `alternativeGrpId`.

## Offer

| Field | Role |
|---|---|
| `actionType` | `Special`. |
| `instanceId` | The object acted on. |
| `abilityGrpId` | The special action's ability. Always present. |
| `manaCost` | The action's cost. Entries may carry an ability ID. |
| `alternativeGrpId` | On some branches, a cost ability that can differ from `abilityGrpId`. |
| `alternativeSourceZcid` | On some branches, the object granting the action. |

The branches differ in which optional fields they carry:

```text
{ actionType: Special, instanceId: <object>, abilityGrpId: <special ability>,
  alternativeGrpId: <cost ability>, manaCost: <cost keyed by the cost ability> }

{ actionType: Special, instanceId: <object>, abilityGrpId: <special ability>,
  manaCost: <cost keyed by the special ability> }

{ actionType: Special, instanceId: <object>, abilityGrpId: <special ability>,
  alternativeGrpId: <cost ability>, manaCost: <cost keyed by the cost ability>,
  alternativeSourceZcid: <source> }
```

## Lifecycle

1. The client submits the offered `Special` action.
2. The mechanic's state change follows. Foretelling, for example, moves the
   card from the hand to exile face down under a new instance ID.
3. `UserActionTaken` records `actionType` `7` with the special action's
   nonzero `abilityGrpId`.

## Sibling shapes

| Axis | `Special` | `SpecialTurnFaceUp` | Alternative-cost `Cast` |
|---|---|---|---|
| `actionType` | `7` | `8` | `1` |
| Mandatory ability field | `abilityGrpId` | `alternativeGrpId` | persistent `CastingTimeOption` and alternative fields |
| Optional source field | `alternativeSourceZcid` | `alternativeSourceZcid` | mechanic-specific |
| Accepted row | `actionType` `7`, nonzero `abilityGrpId` | `actionType` `8` with `alternativeGrpId` | `actionType` `1` |

## Contract

- <a id="Special.offer"></a>**Special.offer.** Every `Special` offer `MUST`
  carry `instanceId`, a nonzero `abilityGrpId` and `manaCost`.
- <a id="Special.foretell-plot"></a>**Special.foretell-plot.** Foretelling a
  card from the hand and plotting a card from the hand `MUST` be offered as
  `Special` actions whose `abilityGrpId` is the foretell or plot ability, not
  as `Cast` actions.
- <a id="Special.accepted"></a>**Special.accepted.** An accepted special
  action `MUST` be recorded by `UserActionTaken` with `actionType` `7` and a
  nonzero `abilityGrpId`.

## Open points

- Which mechanics use the `Special` rail, and which offer fields does each
  carry?
- Which offer fields survive into the accepted row?
