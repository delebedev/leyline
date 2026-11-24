---
summary: "Improvise: tapping artifacts to pay generic mana while casting a spell; an ordinary hand Cast with artifacts as payment sources, and what is not yet characterised."
entity: mechanic/Improvise
---
# Improvise

Improvise lets a player tap untapped artifacts they control to pay generic
mana in the cost of the spell they are casting. It is not an alternative or
additional cost. The spell is an ordinary hand [Cast](../actions/Cast.md)
with no `alternativeGrpId` and no
[CastingTimeOption](../annotations/CastingTimeOption.md) row. The mechanic
appears only in the payment, where artifacts are offered as payment sources.

## Rules grounding

Improvise means "For each generic mana in this spell's total cost, you may tap
an untapped artifact you control rather than pay that mana" (CR 702.126a).
It applies after the total cost is determined.

## Surfaces

| Surface | Entity pages |
|---|---|
| Cast | [Cast](../actions/Cast.md), [ZoneTransfer](../annotations/ZoneTransfer.md) `CastSpell` |
| Payment with artifacts | [PayCostsReq](../prompts/PayCostsReq.md), [TappedUntappedPermanent](../annotations/TappedUntappedPermanent.md) |

## Cast lifecycle

1. The card in the hand is offered as an ordinary `Cast`.
2. The card moves Hand to Stack with `CastSpell` under a new instance ID.
3. During payment, untapped artifacts can pay generic mana. A tapped artifact
   is published with `TappedUntappedPermanent`.
4. `UserActionTaken` records the cast.

## Sibling shapes

Convoke taps creatures instead, and a creature can pay a colored symbol of
its color. Improvise taps artifacts and pays only generic mana. Neither
changes the action type or adds a cast marker.

## Contract

No statement is specific to improvise yet.

## Open points

- Which payment request offers the artifacts, and which fields identify them
  as improvise sources rather than mana abilities?
- Is an artifact tapped for improvise named in a `ManaPaid` row?
