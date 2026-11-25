---
summary: "Madness: a discard that goes to exile, the trigger that lets the owner cast the card for its madness cost, the staged stack spell and its cast marker, and the accepted and declined branches."
entity: mechanic/Madness
---
# Madness

Madness replaces a discard: the card is discarded into exile instead of the
graveyard, and a trigger lets its owner cast it for its madness cost or put
it into the graveyard. The protocol carries the discard as a Hand to Exile
[ZoneTransfer](../annotations/ZoneTransfer.md) with `category` `Discard`, the
window as a triggered [Ability](../object_types/Ability.md) whose resolution
stages the card on the stack with a
[CastingTimeOption](../annotations/CastingTimeOption.md) row, and the decision
as a Cast or a pass.

## Rules grounding

Madness is a replacement effect that exiles a discarded card instead of
putting it into the graveyard, and a trigger that lets its owner cast it by
paying the madness cost or else put it into their graveyard (CR 702.35a). A
madness cast follows the ordinary rules for alternative costs (CR 702.35b).

## Surfaces

| Surface | Entity pages |
|---|---|
| Discard to exile | [ZoneTransfer](../annotations/ZoneTransfer.md) `Discard` |
| Madness trigger | [Ability](../object_types/Ability.md), [AbilityInstanceCreated](../annotations/AbilityInstanceCreated.md) |
| Staged spell | [ZoneTransfer](../annotations/ZoneTransfer.md) `CastSpell`, [CastingTimeOption](../annotations/CastingTimeOption.md) `type` `13` |
| Decision | [ActionsAvailableReq](../prompts/ActionsAvailableReq.md) |
| Accepted cast | [UserActionTaken](../annotations/UserActionTaken.md) |

The madness ability ID depends on the cost, not the card: cards with the same
madness cost share it.

## Discard lifecycle

1. The discard moves the card Hand to Exile with `category` `Discard` under a
   new instance ID. The `affectorId` is the object that caused the discard.
2. In the same GSM, `AbilityInstanceCreated` names the exiled card as
   `affectorId` and the madness ability as affected ID, with `source_zone`
   `29`.

## Window lifecycle

1. The madness ability starts resolving: `ResolutionStart` carries its ability
   ID.
2. The card moves Exile to Stack with `category` `CastSpell` under a new
   instance ID, with the ability as `affectorId`. The stack spell carries a
   persistent `CastingTimeOption` row of `type` `13` with the madness ability
   in both `alternateCostGrpId` and `castAbilityGrpId`.
3. The owner is offered the staged spell as a Cast, or can pass.

## Accepted branch

1. Targets are chosen and the madness cost is paid.
2. `UserActionTaken` records `actionType` `1` with the madness ability in both
   `alternativeGrpId` and `abilityGrpId`.
3. In the same GSM, `ResolutionComplete` carries the madness ability ID and
   `AbilityInstanceDeleted` retires the ability.
4. The spell then resolves through its ordinary lifecycle.

## Declined branch

1. `ResolutionStart` for the madness ability is published again.
2. The card moves to the graveyard with `category` `Put`; the transfer's
   `zone_src` is exile even though the card was staged on the stack.
3. `ResolutionComplete` and `AbilityInstanceDeleted` close the ability in the
   same GSM. No `UserActionTaken` is recorded for the card.

## Sibling shapes

A hand cast of the same card is an ordinary Cast with no `CastingTimeOption`
and no `alternativeGrpId`. [Flashback](Flashback.md) and [Escape](Escape.md)
also cast through an alternative cost from a non-hand zone, but they are
offered at any time the card could be cast and not through a trigger.

## Contract

- <a id="Madness.discard-to-exile"></a>**Madness.discard-to-exile.** A
  discarded madness card `MUST` move Hand to Exile with `category` `Discard`.
- <a id="Madness.trigger-zone"></a>**Madness.trigger-zone.** The madness
  trigger's `AbilityInstanceCreated` `MUST` carry `source_zone` `29`.
- <a id="Madness.cast-marker"></a>**Madness.cast-marker.** The staged spell
  `MUST` carry a `CastingTimeOption` row of `type` `13` with the madness
  ability in both `alternateCostGrpId` and `castAbilityGrpId`.
- <a id="Madness.accepted"></a>**Madness.accepted.** *(provisional)* An
  accepted madness cast `MUST` be recorded by `UserActionTaken` with
  `actionType` `1` and the madness ability in both `alternativeGrpId` and
  `abilityGrpId`.
- <a id="Madness.decline"></a>**Madness.decline.** *(provisional)* When a
  declined madness card goes to the graveyard, it `MUST` move there with
  `category` `Put`, and no `UserActionTaken` `MUST` be recorded for it.

The decision request is stated under
[ActionsAvailableReq](../prompts/ActionsAvailableReq.md).

## Open points

- How is a declined card's move published when a replacement effect sends
  it somewhere other than the graveyard?
- Which fields of the decision request name the staged spell?
- What changes when several discard replacements apply and one is chosen
  through `SelectReplacementReq`?
