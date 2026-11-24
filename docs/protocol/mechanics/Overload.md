---
summary: "Overload mechanic: a hand-cast alternative cost whose spell affects each object instead of a target, its accepted-action and casting-time-option values, and the absent target rows."
entity: mechanic/Overload
---
# Overload

Overload is an alternative cost: cast the spell for its overload cost and
every "target" in its text becomes "each". The mechanic uses an
alternative-cost [Cast](../actions/Cast.md) and a `type` `13`
[CastingTimeOption](../annotations/CastingTimeOption.md), and drops the
targeting rows the spell would otherwise carry.

## Rules grounding

CR 702.96a defines overload as an alternative cost that changes the spell's
text from "target" to "each". CR 702.96b: an overloaded spell has no targets.

## Surfaces

| Surface | Entity pages |
|---|---|
| Cast for the overload cost | [Cast](../actions/Cast.md), [UserActionTaken](../annotations/UserActionTaken.md), [CastingTimeOption](../annotations/CastingTimeOption.md) |
| Text change | [LayeredEffectCreated](../annotations/LayeredEffectCreated.md) |
| Targets | none: no [SelectTargetsReq](../prompts/SelectTargetsReq.md) or [TargetSpec](../annotations/TargetSpec.md) |

## Cast lifecycle

1. The hand card is offered as a `Cast` action whose `alternativeGrpId` is
   the card's overload ability.
2. The cast GSM carries `ObjectIdChanged` and a Hand to Stack `ZoneTransfer`
   with `category` `CastSpell`, `UserActionTaken` with `actionType` `1`,
   `abilityGrpId` `0` and `alternativeGrpId` the overload ability, and a
   persistent `CastingTimeOption` with `type` `13` and `alternateCostGrpId`
   the overload ability, without `castAbilityGrpId`. A `LayeredEffectCreated`
   introduces the text change.
3. No `SelectTargetsReq`, `PlayerSelectingTargets` or `TargetSpec` follows.
4. The spell resolves against each matching object; its effect rows are the
   card's own.

## Sibling shapes

Spectacle, sneak and the hand branch of warp use the same alternative-cost
rail. Overload is recognised by its ability in `alternativeGrpId` together
with the absent target rows; `type` `13` alone does not identify it.

## Contract

- <a id="Overload.accepted"></a>**Overload.accepted.** A cast for the overload
  cost `MUST` be recorded by `UserActionTaken` with `actionType` `1`,
  `abilityGrpId` `0` and `alternativeGrpId` naming the overload ability.
- <a id="Overload.option"></a>**Overload.option.** A spell cast for its
  overload cost `MUST` carry a persistent `CastingTimeOption` with `type` `13`
  and `alternateCostGrpId` naming the overload ability, and `MUST NOT` carry
  `castAbilityGrpId`.
- <a id="Overload.no-targets"></a>**Overload.no-targets.** A spell cast for
  its overload cost `MUST NOT` be followed by a `SelectTargetsReq` for it or
  carry a `TargetSpec`.

## Open points

- What does the layered effect created at cast time describe, and which GSM
  retires it?
