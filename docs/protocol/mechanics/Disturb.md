---
summary: "Disturb: casting a double-faced card transformed from the graveyard; the per-card disturb ability in the offer, accepted row and cast option, and the back-face stack object."
entity: mechanic/Disturb
---
# Disturb

Disturb lets a double-faced card be cast from its owner's graveyard
transformed, for its disturb cost. The cast is a [Cast](../actions/Cast.md)
whose alternative ability is the card's own disturb ability; the stack card
carries the back face, and the card's back face is also projected as a
[DisturbBack](../object_types/DisturbBack.md) companion.

## Rules grounding

CR 702.146a lets the card be cast transformed from the graveyard by paying
its disturb cost instead of its mana cost. The resolving permanent enters with
its back face up (CR 702.146b).

## Surfaces

| Surface | Entity pages | Disturb values |
|---|---|---|
| Graveyard cast | [Cast](../actions/Cast.md), [UserActionTaken](../annotations/UserActionTaken.md), [CastingTimeOption](../annotations/CastingTimeOption.md) | The card's disturb ability in `abilityGrpId`, `alternativeGrpId` and the cast option |
| Back face | [Card](../object_types/Card.md), [DisturbBack](../object_types/DisturbBack.md) | The back face's `grpId` on the stack card |

## Graveyard cast lifecycle

1. The graveyard card is offered as a `Cast` on its graveyard instance. Its
   `abilityGrpId` and `alternativeGrpId` both name the card's disturb ability,
   each `manaCost` entry is keyed by that ability, and `alternativeSourceZcid`
   names the same graveyard instance.
2. The cast GSM moves the card from the graveyard to the Stack under a new
   instance ID (`ObjectIdChanged`, `ZoneTransfer` with `category`
   `CastSpell`). The stack `Card` carries the back face's `grpId`, and a
   `DisturbBack` companion with the same `grpId` names it as `parentId`.
3. The same GSM records `UserActionTaken` with `actionType` `1` and the
   disturb ability in both `abilityGrpId` and `alternativeGrpId`, and creates
   a persistent `CastingTimeOption` row with `type` `13` and the disturb
   ability in both `alternateCostGrpId` and `castAbilityGrpId`.
4. The spell resolves like any permanent spell: `ResolutionStart` and
   `ResolutionComplete` with the back face's `grpId`, then a `Resolve`
   transfer to the Battlefield that keeps the instance ID. The
   `CastingTimeOption` row is deleted in that GSM, and the permanent keeps
   its back face.

## Sibling shapes

Flashback and escape are graveyard casts too, but the card keeps its front
face on the stack. A disturb cast is the only one whose `abilityGrpId`,
`alternativeGrpId`, `alternateCostGrpId` and `castAbilityGrpId` all carry the
same per-card ability while the stack card shows the back face.

## Contract

- <a id="Disturb.offer"></a>**Disturb.offer.** A disturb cast `MUST` be
  offered as a `Cast` on the graveyard instance whose `abilityGrpId` and
  `alternativeGrpId` both name the card's disturb ability, with every
  `manaCost` entry keyed by that ability.
- <a id="Disturb.offer-source"></a>**Disturb.offer-source.** *(provisional)*
  The disturb offer's `alternativeSourceZcid` `MUST` equal its `instanceId`.
- <a id="Disturb.accepted"></a>**Disturb.accepted.** An accepted disturb cast
  `MUST` be recorded by `UserActionTaken` with `actionType` `1` and the
  disturb ability in both `abilityGrpId` and `alternativeGrpId`.
- <a id="Disturb.cast-option"></a>**Disturb.cast-option.** A disturb spell
  `MUST` carry a persistent `CastingTimeOption` row with `type` `13` and the
  disturb ability in both `alternateCostGrpId` and `castAbilityGrpId`,
  created in the cast GSM.
- <a id="Disturb.back-face"></a>**Disturb.back-face.** *(provisional)* The
  stack `Card` of a disturb cast `MUST` carry the back face's `grpId`, and the
  permanent it resolves into `MUST` keep that `grpId` and the stack instance
  ID.

## Open points

- What does a disturb offer on `ActionsAvailableReq` carry as `grpId` and
  `facetId`: the front face and the graveyard card, or the back face and its
  companion?
- What happens to a disturb spell that is countered, and to a resolved
  disturb permanent that would go to the graveyard?
