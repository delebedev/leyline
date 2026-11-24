---
summary: "Mutate: an alternative-cost cast targeting a non-Human creature with the same owner that merges into it; the per-card Cast offer, the target prompt, the cast marker, the merge LayeredEffect without a zone transfer, and the mutate trigger."
entity: mechanic/Mutate
---
# Mutate

Mutate is an alternative cost. A creature spell cast for its mutate cost
targets a non-Human creature with the same owner. If the target is still
legal as the spell resolves, the spell merges with it instead of entering the
battlefield. The protocol carries the cast as a [Cast](../actions/Cast.md)
from the hand with a per-card `alternativeGrpId`, the target as a
[SelectTargetsReq](../prompts/SelectTargetsReq.md), and the merge as a
persistent `LayeredEffect` that links the mutating card to the target, with no
zone transfer to the battlefield.

## Rules grounding

Mutate lets a creature card be cast for its mutate cost as a mutating
creature spell that targets a non-Human creature with the same owner
(CR 702.140a). If the target is illegal as the spell resolves, it resolves as
an ordinary creature spell (CR 702.140b). Otherwise it merges with the target,
on top of it or under it (CR 702.140c). Abilities that trigger whenever the
creature mutates trigger when a spell merges with it (CR 702.140d).

## Surfaces

| Surface | Entity pages |
|---|---|
| Cast offer | [Cast](../actions/Cast.md) |
| Stack marker | [CastingTimeOption](../annotations/CastingTimeOption.md) `type` `13` |
| Target | [SelectTargetsReq](../prompts/SelectTargetsReq.md), `TargetSpec` |
| Merge | `LayeredEffect`, `LayeredEffectCreated` |
| Mutate trigger | [Ability](../object_types/Ability.md), [AbilityInstanceCreated](../annotations/AbilityInstanceCreated.md), [TriggeringObject](../annotations/TriggeringObject.md) |

Each card has its own mutate ability, which keys the offer, its cost, the
cast marker and the target group. The shared mutate ID `203` keys the target
request and the merge effect.

## Cast lifecycle

1. The card in the hand is offered as a `Cast` with `abilityGrpId` `0` and
   the card's mutate ability in `alternativeGrpId`. Its `manaCost` entries are
   keyed by the same ability.
2. The card moves Hand to Stack with `CastSpell` under a new instance ID. The
   stack spell carries a persistent `CastingTimeOption` row of `type` `13`
   with the mutate ability in `alternateCostGrpId` and no `castAbilityGrpId`.
3. The caster receives a `SelectTargetsReq` with `abilityGrpId` `203`,
   `allowCancel` `Abort` and `allowUndo` `true`. Its one target group has
   prompt `141` with a `CardId` parameter naming the stack spell,
   `targetSourceZoneId` `28`, `targetingAbilityGrpId` the card's mutate
   ability, and `minTargets` and `maxTargets` `1`.
4. The chosen target is recorded by a persistent `TargetSpec` row on the stack
   spell with `abilityGrpId` `0`, `index` `1` and `promptId` `141`.
5. `UserActionTaken` records `actionType` `1`, `abilityGrpId` `0` and the
   mutate ability in `alternativeGrpId`.

## Merge lifecycle

1. The caster decides whether the mutating card goes on top of the target or
   under it.
2. The spell leaves the stack with no `ZoneTransfer` to the battlefield. The
   target keeps its instance ID on the battlefield, and the mutating card
   appears as a public object in the Suppressed zone `24`.
3. A persistent `LayeredEffect` row, created with `LayeredEffectCreated`,
   names the mutating card as `affectorId` and the target as affected ID. It
   carries `abilityGrpId` `203`, `isTop` `1` when the mutating card is on top
   and `0` when it is under, and `abilityGRPIDs` listing the abilities the
   mutating card adds.
4. The merged permanent's `uniqueAbilities` gain those abilities, and its
   `abilityOriginalCardGrpIds` name the card each ability came from. With
   `isTop` `1` the permanent shows the mutating card's characteristics.
5. `ResolutionComplete` names the spell with the card's `grpid`.
6. Any "whenever this creature mutates" trigger is created from the merged
   permanent, with `source_zone` `28`.

## Sibling shapes

Other alternative costs from the hand, such as [Impending](Impending.md),
put a new permanent onto the battlefield. A mutate spell whose target became
illegal resolves the same way, as an ordinary creature spell.

## Contract

- <a id="Mutate.offer"></a>**Mutate.offer.** A mutate offer `MUST` carry
  `abilityGrpId` `0`, the card's mutate ability in `alternativeGrpId` and the
  same ability on its `manaCost` entries.
- <a id="Mutate.target-prompt"></a>**Mutate.target-prompt.** The mutate
  target request `MUST` carry `abilityGrpId` `203` and one target group with
  prompt `141`, `targetSourceZoneId` `28` and `targetingAbilityGrpId` the
  card's mutate ability.
- <a id="Mutate.cast-marker"></a>**Mutate.cast-marker.** *(provisional)* The
  stack spell's `CastingTimeOption` row `MUST` have `type` `13` with the
  mutate ability in `alternateCostGrpId`, and `MUST NOT` carry
  `castAbilityGrpId`.
- <a id="Mutate.merge-effect"></a>**Mutate.merge-effect.** A successful merge
  `MUST` create a persistent `LayeredEffect` from the mutating card to the
  target with `abilityGrpId` `203` and `isTop` naming the order.
- <a id="Mutate.no-transfer"></a>**Mutate.no-transfer.** A successful merge
  `MUST NOT` publish a `ZoneTransfer` from the Stack for the mutating card,
  and the target `MUST` keep its instance ID.

## Open points

- Which request asks whether the mutating card goes on top or under, and with
  which fields?
- How are the components published when a merged permanent leaves the
  battlefield?
