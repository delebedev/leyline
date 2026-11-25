---
summary: "Enchant: the static ability that restricts what an Aura spell targets and what an Aura enchants; the target exchange on cast and what is not yet characterised."
entity: mechanic/Enchant
---
# Enchant

Enchant is the static ability of an Aura. It restricts what the Aura spell
can target and what the Aura can enchant. An Aura cast as a spell chooses the
object or player it will enchant through the ordinary target exchange,
[SelectTargetsReq](../prompts/SelectTargetsReq.md); the Aura then enters
attached to it. The enchant ability itself is listed among the card's
abilities and adds no prompt of its own.

## Rules grounding

Enchant is written "Enchant [object or player]" (CR 702.5a). An Aura spell
cast with enchant targets what it can enchant (CR 303.4a), and an Aura put
onto the battlefield without being cast does not target (CR 303.4f).

## Surfaces

| Surface | Entity pages |
|---|---|
| Cast of the Aura | [Cast](../actions/Cast.md), [ZoneTransfer](../annotations/ZoneTransfer.md) `CastSpell` |
| Choice of what to enchant | [SelectTargetsReq](../prompts/SelectTargetsReq.md), `PlayerSelectingTargets`, [TargetSpec](../annotations/TargetSpec.md) |
| Attachment on resolution | `Attachment` |

## Cast lifecycle

1. The Aura moves from the hand to the stack with `CastSpell` under a new
   instance ID.
2. The same cast publishes `PlayerSelectingTargets` for the Aura on the stack,
   and the controller answers a `SelectTargetsReq` listing what the Aura can
   enchant.
3. On resolution the Aura enters the battlefield attached to its target.

## Sibling shapes

Equip also attaches a permanent, but through an activated ability on the
battlefield that targets a creature, not through the cast of the attached
card.

## Contract

- <a id="Enchant.cast-targets"></a>**Enchant.cast-targets.** *(provisional)*
  The cast of an Aura spell from the hand `MUST` carry `PlayerSelectingTargets`
  for the Aura on the stack.

## Open points

- In which GSM is the `Attachment` row created when the Aura resolves, and
  what are its `affectorId` and affected IDs?
- How is an Aura put onto the battlefield without being cast attached, and
  which prompt chooses what it enchants?
