---
summary: "SpecialTurnFaceUp action: paying to turn a face-down permanent face up; the alternative-ability offer, accepted row and in-place projection change."
entity: action/Special_TurnFaceUp
---
# SpecialTurnFaceUp

`SpecialTurnFaceUp` pays the cost to turn a face-down permanent face up. It has
its own action type and identifies the per-card face-up ability through
`alternativeGrpId` rather than through the ordinary activation rail.

## Meaning and object model

The action targets a face-down permanent on the Battlefield that the acting
player controls. The permanent keeps its instance ID through the event; its
public projection changes from the face-down envelope to the face-up card. The
action is not a cast and creates no stack object of its own.

## Offer

| Field | Role |
|---|---|
| `actionType` | `SpecialTurnFaceUp`. |
| `instanceId` | The face-down Battlefield permanent. |
| `alternativeGrpId` | The per-card turn-face-up ability and its cost. |
| `manaCost` | The face-up cost, keyed to that ability. |
| `alternativeSourceZcid` | A source-object hint; it may equal `instanceId`. |
| `abilityGrpId` | A branch field that may repeat the face-up ability. |

`grpId` and `facetId` are absent. The action instance plus
`alternativeGrpId` are enough to select the operation, and the branches do not
support an equality rule among `abilityGrpId`, `alternativeGrpId` and
`alternativeSourceZcid`.

## Lifecycle

1. The action is offered on the face-down Battlefield instance with its face-up
   cost.
2. Mana abilities and `ManaPaid` rows represent payment when the cost requires
   mana.
3. A `TurnPermanent` annotation names the same Battlefield instance.
4. The object's face-down projection is replaced by its face-up identity and
   characteristics without reallocating the instance ID.
5. Abilities that the turn-face-up event causes create their own ability
   objects after the projection changes.
6. `UserActionTaken` records `actionType` `8` and retains `alternativeGrpId`.
   Its `abilityGrpId` is either `0` or the same face-up ability ID; a consumer
   accepts both shapes.

The action itself identifies no target selection and no zone transfer.

## Sibling shapes

| Axis | `SpecialTurnFaceUp` | `Activate` | [Special](Special.md) |
|---|---|---|---|
| Primary ability field | `alternativeGrpId` | `abilityGrpId` | `abilityGrpId` |
| Offer object | face-down permanent | ability source | mechanic-specific object |
| Accepted `actionType` | `8` | `2` | `7` |
| Object result | same instance, face-up projection | ability-specific | mechanic-specific |
| Stack or zone implication | none inherent | ability-specific | mechanic-specific |

## Contract

- <a id="Special_TurnFaceUp.offer"></a>**Special_TurnFaceUp.offer.** The offer
  `MUST` carry `instanceId` naming the face-down permanent, a nonzero
  `alternativeGrpId` naming its turn-face-up ability, and `manaCost`.
- <a id="Special_TurnFaceUp.no-cast-fields"></a>**Special_TurnFaceUp.no-cast-fields.**
  The offer `MUST NOT` carry `grpId` or `facetId`.
- <a id="Special_TurnFaceUp.accepted"></a>**Special_TurnFaceUp.accepted.** An
  accepted action `MUST` be recorded by `UserActionTaken` with `actionType` `8`
  and `alternativeGrpId` present.
- <a id="Special_TurnFaceUp.accepted-ability"></a>**Special_TurnFaceUp.accepted-ability.**
  An accepted row's `abilityGrpId` `MUST` be either `0` or the same ability ID
  as its `alternativeGrpId`.
- <a id="Special_TurnFaceUp.turn-transient"></a>**Special_TurnFaceUp.turn-transient.**
  The event `MUST` carry a `TurnPermanent` annotation naming the same instance.
- <a id="Special_TurnFaceUp.keeps-instance"></a>**Special_TurnFaceUp.keeps-instance.**
  The permanent `MUST` keep its instance ID across the turn-face-up event.

## Open points

- When is `alternativeSourceZcid` absent, and what does it identify when it
  differs from `instanceId`?
- What does top-level `abilityGrpId` identify on the offers that carry it, and
  why does it appear only on some branches?
