---
summary: "Unearth mechanic: a graveyard activation that returns the card to the Battlefield, its haste effect, the Unearthed designation and the pending end-step exile."
entity: mechanic/Unearth
---
# Unearth

Unearth is an activated ability that works only while its card is in a
graveyard. Its resolution returns the card to the Battlefield with haste; a
delayed trigger exiles it at the next end step, and it is exiled instead if it
would leave the Battlefield another way. The mechanic is an activation, not a
cast: the card itself never goes to the stack. It uses an `Activate` offer on
a graveyard card, a `Return` [ZoneTransfer](../annotations/ZoneTransfer.md), a
designation, and the delayed-trigger rows
[DelayedTriggerAffectees](../annotations/DelayedTriggerAffectees.md) and
[TemporaryPermanent](../annotations/TemporaryPermanent.md).

## Rules grounding

CR 702.84a defines unearth: activate only as a sorcery from the graveyard;
return the card to the Battlefield; it gains haste; exile it at the beginning
of the next end step; and if it would leave the Battlefield, exile it instead.

## Surfaces

| Surface | Entity pages |
|---|---|
| Activation from the graveyard | [Ability](../object_types/Ability.md), [AbilityInstanceCreated](../annotations/AbilityInstanceCreated.md), [ManaPaid](../annotations/ManaPaid.md), [UserActionTaken](../annotations/UserActionTaken.md) |
| Return | [ObjectIdChanged](../annotations/ObjectIdChanged.md), [ZoneTransfer](../annotations/ZoneTransfer.md) `Return` |
| Haste | [LayeredEffectCreated](../annotations/LayeredEffectCreated.md), [AddAbility](../annotations/AddAbility.md) |
| Returned-card marker | `Designation` `26`, see [designations](../designations/README.md) |
| Pending exile | [TriggerHolder](../object_types/TriggerHolder.md), [DelayedTriggerAffectees](../annotations/DelayedTriggerAffectees.md), [TemporaryPermanent](../annotations/TemporaryPermanent.md) |

## Activation lifecycle

1. The graveyard card is offered as an `Activate` action whose `abilityGrpId`
   is its unearth ability.
2. On submission the server creates the stack `Ability` with
   `AbilityInstanceCreated` naming the graveyard card and `source_zone` the
   graveyard. The card keeps its graveyard instance ID.
3. `ManaPaid` rows name the ability, and `UserActionTaken` records
   `actionType` `2` with the unearth ability.

No `CastingTimeOption`, `alternativeGrpId` or Graveyard-to-Stack transfer is
part of the activation.

## Resolution lifecycle

One GSM carries, in order:

1. `ResolutionStart` for the unearth ability.
2. `ObjectIdChanged` reallocating the graveyard card, and a `ZoneTransfer`
   from Graveyard to Battlefield with `category` `Return` and the ability as
   `affectorId`.
3. `LayeredEffectCreated` from the ability for the haste effect.
4. `ResolutionComplete` and `AbilityInstanceDeleted`.

The same GSM creates:

- a `TriggerHolder` in Limbo for the end-step exile, whose
  `objectSourceGrpId` is the unearth ability;
- an `AddAbility` row granting haste to the returned permanent, sharing the
  `effect_id` of the `LayeredEffectCreated`;
- a `Designation` with `DesignationType` `26` on the returned permanent, with
  `source_grpid` the card's `grpId` and `conjuration_type`;
- `DelayedTriggerAffectees` and `TemporaryPermanent` rows from the trigger
  holder to the returned permanent, naming the delayed exile ability.

`TemporaryPermanent` is then reissued under a new ID in later GSMs, as its own
page describes
([TemporaryPermanent.reissue-deletes-old](../annotations/TemporaryPermanent.md#TemporaryPermanent.reissue-deletes-old)).

## Sibling shapes

Escape and flashback also use a card in a graveyard, but they cast it: the
card moves to the stack under a `Cast` action with an alternative cost and a
`CastingTimeOption` row. Embalm and eternalize activate from the graveyard like
unearth, but their resolution creates a token instead of returning the card.

## Contract

- <a id="Unearth.graveyard-source"></a>**Unearth.graveyard-source.** The unearth
  ability's `AbilityInstanceCreated` `MUST` name the graveyard card as
  `affectorId` and carry the graveyard zone in `source_zone`, and the card
  `MUST` keep its graveyard instance ID while the ability is on the stack.
- <a id="Unearth.no-cast"></a>**Unearth.no-cast.** An unearth activation `MUST`
  be recorded with `actionType` `2`, and `MUST NOT` move the card to the stack
  or create a `CastingTimeOption` row.
- <a id="Unearth.return-transfer"></a>**Unearth.return-transfer.** A resolving
  unearth ability `MUST` move its card Graveyard to Battlefield with
  `category` `Return` and the ability as `affectorId`, between its
  `ResolutionStart` and `ResolutionComplete`.
- <a id="Unearth.designation"></a>**Unearth.designation.** *(provisional)*
  Grounded for a returned creature card: the resolution GSM `MUST` create a
  persistent `Designation` with `DesignationType` `26` whose `affectorId` and
  affected ID are the returned permanent and whose `source_grpid` is the
  card's `grpId`.
- <a id="Unearth.pending-exile"></a>**Unearth.pending-exile.** The resolution
  GSM `MUST` create `DelayedTriggerAffectees` and `TemporaryPermanent` rows
  whose `affectorId` is the end-step exile's trigger holder and whose affected
  ID is the returned permanent.

## Open points

- What does the end-step exile carry when it resolves, and which rows retire
  with it?
- What does the replacement exile carry when the permanent would leave the
  Battlefield another way?
- What values does `conjuration_type` take on the designation, and does every
  unearthed card carry the same one?
