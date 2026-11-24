---
summary: "Cycling: an activated ability from the hand whose cost discards its source; hand source zone, the Discard transfer caused by the ability, and the accepted activation."
entity: mechanic/Cycling
---
# Cycling

Cycling is an activated ability that works from the hand; its cost includes
discarding the card. The activation is an ordinary `Activate` action and
stack [Ability](../object_types/Ability.md); what Cycling adds is a source in
the hand and a Discard [ZoneTransfer](../annotations/ZoneTransfer.md) caused
by the ability itself while it is paid for.

## Rules grounding

Cycling functions only while the card is in the hand and means
"[Cost], Discard this card: Draw a card" (CR 702.29a). Typecycling searches
for a card of the named type instead of drawing (CR 702.29e). The discard is
part of the cost, paid before the ability resolves (CR 602.2b).

## Surfaces

| Surface | Entity pages | Cycling values |
|---|---|---|
| Activation | [Ability](../object_types/Ability.md), [AbilityInstanceCreated](../annotations/AbilityInstanceCreated.md), [UserActionTaken](../annotations/UserActionTaken.md) | Source in the hand; `actionType` `2`, the card's cycling ability |
| Discard cost | [ObjectIdChanged](../annotations/ObjectIdChanged.md), [ZoneTransfer](../annotations/ZoneTransfer.md) | Hand to Graveyard, `category` `Discard` |

## Activation lifecycle

One GSM carries the whole activation, in this order:

1. `AbilityInstanceCreated` from the card in the hand, with the hand's zone
   ID as `source_zone`. The ability on the stack has the card's cycling
   ability as `grpId` and the hand card as `parentId`.
2. The mana payment, when the cost has mana: mana abilities and `ManaPaid`
   rows naming the cycling ability.
3. `ObjectIdChanged` from the hand instance to a graveyard instance and a
   Hand-to-Graveyard `ZoneTransfer` with `category` `Discard`, both with the
   cycling ability as `affectorId`.
4. `UserActionTaken` with `actionType` `2` and the cycling ability in
   `abilityGrpId`, naming the ability.

The card never goes to the stack. A later GSM resolves the ability with
`ResolutionStart` for its `grpId`, the draw or search, `ResolutionComplete`
and `AbilityInstanceDeleted`.

## Sibling shapes

A card with Cycling can still be cast; that is an ordinary `Cast` from the
hand with a `CastSpell` transfer. Abilities that trigger on cycling or on
discarding are separate triggers with their own ability objects.

## Contract

- <a id="Cycling.hand-source"></a>**Cycling.hand-source.** A cycling
  ability's `AbilityInstanceCreated` `MUST` name the card in the hand as
  `affectorId` and carry the hand's zone ID as `source_zone`.
- <a id="Cycling.discard"></a>**Cycling.discard.** When the cycled card goes
  to the graveyard, the activation GSM `MUST` move it Hand to Graveyard with
  `category` `Discard`, with the cycling ability as `affectorId` of both the
  `ObjectIdChanged` and the `ZoneTransfer`.
- <a id="Cycling.accepted"></a>**Cycling.accepted.** The activation `MUST` be
  recorded by `UserActionTaken` with `actionType` `2` and the cycling ability
  in `abilityGrpId`, after the discard transfer.

## Open points

- How is the discard published when a replacement effect sends the card
  elsewhere, such as a madness card going to exile?
- Which prompt and response does typecycling's search use, and in which GSM
  is the found card revealed?
- How is a cycling cost with no mana and an additional non-mana part ordered?
