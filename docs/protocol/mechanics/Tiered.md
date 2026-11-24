---
summary: "Tiered mechanic: exactly one tier chosen while casting, each with its own additional cost, carried by a single modal CastingTimeOptionsReq row; the encoding of a zero-cost tier."
entity: mechanic/Tiered
---
# Tiered

Tiered is a modal spell whose caster chooses exactly one tier and pays that
tier's additional cost. The mechanic uses the modes family of
[CastingTimeOptionsReq](../prompts/CastingTimeOptionsReq.md) in the same shape
as [Spree](Spree.md), bounded to one choice, and adds no annotation of its
own.

## Rules grounding

CR 702.183a defines tiered: the spell's controller chooses one mode and pays
that mode's additional cost as they cast it.

## Surfaces

| Surface | Entity pages |
|---|---|
| Tier and cost choice | [CastingTimeOptionsReq](../prompts/CastingTimeOptionsReq.md) modes family |
| Cast and resolution | [Cast](../actions/Cast.md), [UserActionTaken](../annotations/UserActionTaken.md) |

## Cast lifecycle

1. The cast moves the spell to the stack; the request follows with the spell
   as the row's `affectedId`.
2. The request has one row, of type `Modal`, with the spell's `grpId` and
   `isRequired` `true`, and no `Done` row
   ([CastingTimeOptionsReq.modal-single](../prompts/CastingTimeOptionsReq.md#CastingTimeOptionsReq.modal-single)).
3. `modalReq` carries `abilityGrpId` the card's tiers ability, `minSel` `1`,
   `maxSel` `1`, one `modalOptions` entry per tier with that tier's
   additional cost in `modeCost`, and `modeCostSolutions` for the tiers that
   can be paid.
4. A tier whose additional cost is `{0}` still carries one `modeCost` entry: a
   mana cost with `color` `ManaColor_Generic` and no `count`, which reads as
   `0`.
5. The client answers with the chosen tier in `chooseModalResp`; payment and
   resolution follow the ordinary spell lifecycle.

## Sibling shapes

Spree shares the row, the per-mode costs and the payment solutions, but its
`maxSel` is the number of offered modes.

## Contract

- <a id="Tiered.selection-bounds"></a>**Tiered.selection-bounds.** A tiered
  request `MUST` carry `minSel` `1` and `maxSel` `1`.
- <a id="Tiered.tier-cost"></a>**Tiered.tier-cost.** Each `modalOptions`
  entry of a tiered request `MUST` carry, in `modeCost`, that tier's
  additional cost and not the spell's mana cost.
- <a id="Tiered.zero-cost"></a>**Tiered.zero-cost.** A tier whose additional
  cost is `{0}` `MUST` carry one `modeCost` entry of type `CostType_Mana` with
  `color` `ManaColor_Generic` and no nonzero `count`.

## Open points

- Does a tier that cannot be paid move to `excludedOptions`, or stay in
  `modalOptions` without a payment solution?
