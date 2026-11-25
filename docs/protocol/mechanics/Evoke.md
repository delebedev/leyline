---
summary: "Evoke: an alternative-cost cast whose permanent is sacrificed when it enters; the cast marker, the TemporaryPermanent row that links the paid cost to the sacrifice trigger, and the sacrifice."
entity: mechanic/Evoke
---
# Evoke

Evoke is an alternative cost: a card cast for its evoke cost is sacrificed
when it enters the battlefield. The protocol carries the paid cost from the
cast to the sacrifice. The cast is an alternative-cost
[Cast](../actions/Cast.md) recorded by
[CastingTimeOption](../annotations/CastingTimeOption.md). A
[TemporaryPermanent](../annotations/TemporaryPermanent.md) row marks the card
from the stack to the battlefield, and the sacrifice is a triggered
[Ability](../object_types/Ability.md) that carries the evoke ability's ID.

## Rules grounding

Evoke means "You may cast this card by paying [cost] rather than paying its
mana cost" and "When this permanent enters, if its evoke cost was paid, its
controller sacrifices it" (CR 702.74a). A card cast for its mana cost has no
evoke trigger.

## Surfaces

| Surface | Entity pages |
|---|---|
| Alternative-cost cast | [Cast](../actions/Cast.md), [UserActionTaken](../annotations/UserActionTaken.md) |
| Stack marker | [CastingTimeOption](../annotations/CastingTimeOption.md) `type` `13` |
| Paid-cost marker | [TemporaryPermanent](../annotations/TemporaryPermanent.md) |
| Sacrifice trigger | [Ability](../object_types/Ability.md), [ZoneTransfer](../annotations/ZoneTransfer.md) `Sacrifice` |

## Cast lifecycle

1. The card in the hand is offered as a `Cast` whose `alternativeGrpId` is
   its evoke ability.
2. The card moves to the stack with `CastSpell`. The stack card carries a
   `CastingTimeOption` row of `type` `13` with the evoke ability in
   `alternateCostGrpId`.
3. The stack card also carries a persistent `TemporaryPermanent` row whose
   `AbilityGrpId` is the evoke ability.
4. `UserActionTaken` records `actionType` `1`, `abilityGrpId` `0` and the
   evoke ability in `alternativeGrpId`.

## Sacrifice lifecycle

1. The spell resolves onto the battlefield with `Resolve`. The
   `TemporaryPermanent` row stays on the permanent.
2. The evoke trigger is created as an ability object whose `grpId` is the
   evoke ability and whose `parentId` is the permanent.
3. When the trigger resolves and the sacrifice puts the permanent into the
   graveyard, the permanent gets a new instance ID and moves Battlefield to
   Graveyard with `category` `Sacrifice`.
4. In the same GSM, `ResolutionComplete` and `AbilityInstanceDeleted` retire
   the trigger, and the `TemporaryPermanent` row is deleted.

The card's own enters triggers are separate abilities and can resolve before
or after the sacrifice.

## Sibling shapes

Dash and blitz also mark a permanent that leaves later, but the departure
happens at end of turn. Evoke's departure is a trigger that fires as the
permanent enters, and its `grpId` is the evoke ability itself.

## Contract

- <a id="Evoke.accepted"></a>**Evoke.accepted.** *(provisional)* An accepted
  evoke cast `MUST` be recorded by `UserActionTaken` with `actionType` `1`,
  `abilityGrpId` `0` and the evoke ability in `alternativeGrpId`.
- <a id="Evoke.temporary-permanent"></a>**Evoke.temporary-permanent.**
  *(provisional)* A card cast for its evoke cost `MUST` carry a persistent
  `TemporaryPermanent` row whose `AbilityGrpId` is the evoke ability from the
  stack on. When the evoke trigger sacrifices the permanent, the row `MUST`
  last until that GSM.
- <a id="Evoke.trigger-identity"></a>**Evoke.trigger-identity.**
  *(provisional)* The evoke sacrifice trigger `MUST` be an ability object whose
  `grpId` is the evoke ability and whose `parentId` is the permanent.
- <a id="Evoke.sacrifice"></a>**Evoke.sacrifice.** *(provisional)* When the
  trigger's resolution sacrifices the permanent and it goes to the graveyard,
  the resolution `MUST` move it Battlefield to Graveyard with `category`
  `Sacrifice` and delete the `TemporaryPermanent` row in the same GSM.
- <a id="Evoke.normal-cast"></a>**Evoke.normal-cast.** A card with evoke cast
  for its mana cost `MUST NOT` carry a `TemporaryPermanent` or
  `CastingTimeOption` row, and `MUST NOT` be sacrificed by an evoke trigger.

## Open points

- Which evoke cards carry an `AbilityWordActive` row named
  `ManaPaymentCondition` in the payment GSM, and what does it mark?
- What does the trigger's resolution carry, and what happens to the
  `TemporaryPermanent` row, when the permanent is not sacrificed into the
  graveyard, for example because it left first or a replacement effect sends
  it elsewhere?
