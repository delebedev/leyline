---
summary: "CastingTimeOption annotation: persistent record of how a spell on the stack was cast; type branches for alternative costs, kicker, additional costs and X, identities and retirement."
entity: annotation/CastingTimeOption
---
# CastingTimeOption

## Meaning

Casting a spell can involve choices that change it: an alternative cost such
as evoke or flashback, kicker, an additional cost, or the value of X
(CR 601.2b, CR 601.2f). `CastingTimeOption` records the chosen option on the
spell while it is on the stack. It is the record of the decision, not the
request for it: a spell cast through an alternative-cost `Cast` action
carries the row even when no `CastingTimeOptionsReq` was sent.

## Variants

Persistent only, never combined with another type. `type` is a
`CastingTimeOptionType` value and selects the branch:

| `type` | Branch | Branch keys |
|---|---|---|
| `2` ChooseX | A chosen X | `value` |
| `3` Kicker | Kicker paid | `kickerAbilityGrpId` |
| `5` AdditionalCost | An optional additional cost paid | `additionalCostGrpId` |
| `11` ChooseOrCost | A chosen cost among alternatives | `chosenCostPromptId` |
| `13` CastThroughAbility | Cast for an alternative cost or through a permission, such as evoke, flashback, warp or a free cast | `alternateCostGrpId`, `castAbilityGrpId` or both |
| `15` Casualty | Casualty paid | `casualtyAbilityGrpId` |
| `17` Bargain | Bargain paid | none |
| `18` OptionalCostPaid | A named optional cost paid | `costType`, `costGrpid` |

A spell with several options carries one row per option. A key absent from a
row does not take part in that branch; it is not a zero value.

## Fields

| Field | Role |
|---|---|
| `affectorId` | The spell's instance ID on the stack. |
| `affectedIds` | The same spell, as a one-element list. |
| `type` | The branch, as above. |
| `alternateCostGrpId` | The alternative cost's ability definition. It equals the `alternativeGrpId` of the submitted [Cast](../actions/Cast.md) action. |
| `castAbilityGrpId` | The ability definition through which the spell was cast, such as a permission granted by another card. |
| `kickerAbilityGrpId`, `additionalCostGrpId`, `casualtyAbilityGrpId`, `costGrpid` | The definition of the paid cost's ability. |
| `value` | The chosen X. |
| `chosenCostPromptId` | The prompt text of the chosen cost. |
| `costType` | The family of the named optional cost. |

## Identity model

The envelope IDs are the spell's stack instance ID, the ID it receives in the
`ObjectIdChanged` of the cast. Every `...GrpId` detail is an ability
definition, not a card. One generic alternative-cost ability can be shared by
several mechanics; a free cast, for example, uses the same
`alternateCostGrpId` whatever permits it. Read the mechanic from that value
together with the source zone and `castAbilityGrpId`, not from the value
alone.

## Lifecycle

1. The cast moves the card to the stack under a new instance ID
   ([ObjectIdChanged](ObjectIdChanged.md), [ZoneTransfer](ZoneTransfer.md)
   `CastSpell`).
2. An option chosen as part of the cast action appears in the same GSM. An
   option chosen afterwards through a `CastingTimeOptionsReq` appears in the
   GSM that follows the answer.
3. The row stays in `persistentAnnotations` while the spell is on the stack.
4. When the spell leaves the stack, by resolving, being countered or being
   exiled, the row is deleted, normally in the GSM that carries that
   transfer. A few rows outlive the spell.

## Sibling shapes

- `CastingTimeOptionsReq` asks for the choice; this row records the outcome.
- [UserActionTaken](UserActionTaken.md) records the cast action; its
  `alternativeGrpId` matches this row's `alternateCostGrpId`.
- `TemporaryPermanent` can follow from an alternative cost such as evoke; it
  marks the resulting permanent, not the spell.

## Contract

- <a id="CastingTimeOption.persistent"></a>**CastingTimeOption.persistent.**
  `CastingTimeOption` `MUST` be carried in `persistentAnnotations` and
  `MUST NOT` be combined with another annotation type.
- <a id="CastingTimeOption.spell-envelope"></a>**CastingTimeOption.spell-envelope.**
  `affectorId` `MUST` be the spell's stack instance ID, and `affectedIds`
  `MUST` contain exactly that ID.
- <a id="CastingTimeOption.type-keys"></a>**CastingTimeOption.type-keys.**
  A row `MUST` carry the branch keys its `type` requires: `value` for `2`,
  `kickerAbilityGrpId` for `3`, `additionalCostGrpId` for `5`, and
  `alternateCostGrpId` or `castAbilityGrpId` for `13`.
- <a id="CastingTimeOption.alternative-matches-action"></a>**CastingTimeOption.alternative-matches-action.**
  When the GSM carries the `UserActionTaken` of the cast, `alternateCostGrpId`
  `MUST` equal that row's `alternativeGrpId`.
- <a id="CastingTimeOption.retired-with-spell"></a>**CastingTimeOption.retired-with-spell.**
  *(provisional)* The row `MUST` be deleted in the GSM where the spell leaves
  the stack.

## Open points

- What do `costType` values name on the `OptionalCostPaid` branch?
- Why do a few rows outlive the spell they describe?
- Do `Multikicker`, `Replicate` and the other unlisted types produce rows, and
  with which keys?
