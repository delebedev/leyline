---
summary: "Cleave: an alternative cost from the hand that removes bracketed text; the per-card Cleave ability on the offer and accepted cast, the type-13 CastingTimeOption, and the stack layered effect."
entity: mechanic/Cleave
---
# Cleave

Cleave is an alternative cost paid from the hand. A spell cast for its cleave
cost loses the words in square brackets while it is on the stack. The cast is
a [Cast](../actions/Cast.md) whose `alternativeGrpId` names the card's
Cleave ability; the cast is marked by a
[CastingTimeOption](../annotations/CastingTimeOption.md) row of `type` `13`,
and the text change is a layered effect on the stack spell.

## Rules grounding

Cleave means "You may cast this spell by paying [cost] rather than paying its
mana cost" and "If this spell's cleave cost was paid, change its text by
removing all text found within square brackets" (CR 702.148a). The text
change is a text-changing effect (CR 702.148b).

## Surfaces

| Surface | Entity pages | Cleave values |
|---|---|---|
| Offer and accepted cast | [Cast](../actions/Cast.md), [UserActionTaken](../annotations/UserActionTaken.md) | `alternativeGrpId` the card's Cleave ability |
| Cast marker | [CastingTimeOption](../annotations/CastingTimeOption.md) | `type` `13`, `alternateCostGrpId` the Cleave ability |
| Text change | [LayeredEffectCreated](../annotations/LayeredEffectCreated.md), `LayeredEffectDestroyed` | On the stack spell, from cast to resolution |

## Cast lifecycle

1. The card in the hand is offered as a `Cast` whose `alternativeGrpId` is
   the card's Cleave ability, beside its ordinary cast.
2. The GSM that moves the card to the stack carries `LayeredEffectCreated`
   with the new stack instance as `affectorId`, then `ObjectIdChanged` and a
   Hand-to-Stack `ZoneTransfer` with `category` `CastSpell`, then
   `PlayerSelectingTargets` for the text that remains.
3. The same GSM creates a persistent `CastingTimeOption` row on the spell
   with `type` `13` and `alternateCostGrpId` the Cleave ability.
4. After targets are submitted, the cleave cost is paid with ordinary
   `ManaPaid` rows, and `UserActionTaken` records `actionType` `1`,
   `abilityGrpId` `0` and `alternativeGrpId` the Cleave ability.

## Resolution lifecycle

The resolution GSM carries the spell's effect, `ResolutionComplete`, then
`LayeredEffectDestroyed` for the text-change effect before the spell's
`Resolve` transfer. The `CastingTimeOption` row is deleted in the same GSM.

## Sibling shapes

Other alternative costs, such as [Disturb](Disturb.md), use the same
`type` `13` row with their own ability. Overload also changes a spell's text,
but replaces "target" with "each" and removes the targeting step.

## Contract

- <a id="Cleave.offer"></a>**Cleave.offer.** *(provisional)* The cleave cast
  `MUST` be offered as a `Cast` with `alternativeGrpId` the card's Cleave
  ability, and the accepted cast's `UserActionTaken` `MUST` carry the same
  `alternativeGrpId`.
- <a id="Cleave.cast-option"></a>**Cleave.cast-option.** A spell cast for
  its cleave cost `MUST` carry a persistent `CastingTimeOption` row with
  `type` `13` and `alternateCostGrpId` the card's Cleave ability.
- <a id="Cleave.text-effect"></a>**Cleave.text-effect.** *(provisional)* The
  GSM that moves a cleaved spell to the stack `MUST` carry
  `LayeredEffectCreated` naming the stack instance, and the resolution `MUST`
  carry the matching `LayeredEffectDestroyed`.

## Open points

- Does a cleaved spell whose remaining text has no target skip
  `PlayerSelectingTargets`?
- Is the text change visible on the stack object's ability list?
