---
summary: "Emerge: an alternative cost that sacrifices a creature and reduces the cost by its mana value; the expected cast surfaces, none characterised yet."
entity: mechanic/Emerge
---
# Emerge

Emerge is an alternative cost: the player casts the spell by paying the
emerge cost and sacrificing a creature, and the cost is reduced by that
creature's mana value. It is expected to use the alternative-cost
[Cast](../actions/Cast.md) offer and the `CastThroughAbility` branch of
[CastingTimeOption](../annotations/CastingTimeOption.md), with the sacrifice
chosen as a cost. None of these surfaces is characterised for emerge yet.

## Rules grounding

"Emerge [cost]" lets the spell be cast by paying that cost and sacrificing a
creature rather than paying its mana cost, and reduces the total cost by the
sacrificed creature's mana value (CR 702.119a). "Emerge from [quality]"
sacrifices a permanent with that quality instead (CR 702.119b). The permanent
is chosen as the emerge cost is chosen and sacrificed as total costs are paid
(CR 702.119c).

## Surfaces

| Surface | Entity pages |
|---|---|
| Alternative-cost cast | [Cast](../actions/Cast.md), [CastingTimeOption](../annotations/CastingTimeOption.md) |
| Sacrifice as a cost | [PayCostsReq](../prompts/PayCostsReq.md) or a cast-time selection; not characterised |
| Sacrifice | [ZoneTransfer](../annotations/ZoneTransfer.md) `Sacrifice` |

## Sibling shapes

Casualty and bargain are additional costs chosen through
[CastingTimeOptionsReq](../prompts/CastingTimeOptionsReq.md); emerge replaces
the mana cost. Exploit sacrifices during the resolution of a trigger, not as a
cost.

## Contract

No statement is specific to emerge yet.

## Open points

- Is emerge offered as a `Cast` with `alternativeGrpId` naming the emerge
  ability, and does the stack spell carry a `CastingTimeOption` row of `type`
  `13`?
- Through which prompt is the creature to sacrifice chosen, and how does the
  offered `manaCost` reflect the reduction before the choice?
- Which ability ID does "emerge from" use?
