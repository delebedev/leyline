---
summary: "Enlist: an attack alternative whose tap cost is paid through PayCostsReq, then a linked trigger that pumps the attacker by the enlisted creature's power; IDs, identities and ordering."
entity: mechanic/Enlist
---
# Enlist

Enlist lets an attacking creature tap another creature its controller
controls as it attacks; when it does, the attacker gets +X/+0, where X is the
tapped creature's power. The protocol carries it as an alternative row of
[DeclareAttackersReq](../prompts/DeclareAttackersReq.md), a tap cost paid
through [PayCostsReq](../prompts/PayCostsReq.md), and a linked triggered
[Ability](../object_types/Ability.md). No target is chosen.

## Rules grounding

Enlist is a static ability and a linked triggered ability. As the creature
attacks, its controller may tap up to one untapped creature they control that
they didn't choose to attack with and that has haste or has been under their
control since the turn began; when they do, the attacker gets +X/+0 until end
of turn, where X is the tapped creature's power (CR 702.154a). The tap is an
optional cost to attack (CR 702.154b). A creature can't enlist itself
(CR 702.154c).

## Surfaces

| Surface | Entity pages |
|---|---|
| Attack alternative | [DeclareAttackersReq](../prompts/DeclareAttackersReq.md) row with `alternativeGrpId` `261` |
| Tap cost | [PayCostsReq](../prompts/PayCostsReq.md), [TappedUntappedPermanent](../annotations/TappedUntappedPermanent.md) |
| Linked trigger | [AbilityInstanceCreated](../annotations/AbilityInstanceCreated.md), [TriggeringObject](../annotations/TriggeringObject.md) |
| Pump | [LayeredEffectCreated](../annotations/LayeredEffectCreated.md), `PowerToughnessModCreated`, `ModifiedPower` |

## Attack lifecycle

1. The attacker is offered an ordinary row and an Enlist row with
   `alternativeGrpId` `261`, as stated by
   [DeclareAttackersReq.alternative-rows](../prompts/DeclareAttackersReq.md#DeclareAttackersReq.alternative-rows).
   Submitting the Enlist row declares the attack with Enlist.
2. In the declare attackers step, a `PayCostsReq` with prompt `11225` asks
   for the creature to tap: `EffectCostType_Select`, one selection, `context`
   `SelectionContext_NonMana_Payment` and `allowCancel` `Abort`. Its `CardId`
   parameter names the attacker. No `SelectTargetsReq`,
   `PlayerSelectingTargets` or `TargetSpec` takes part.
3. In the GSM after the payment, `TappedUntappedPermanent` names the attacker
   as `affectorId` and the enlisted creature as the tapped object, and
   `AbilityInstanceCreated` creates the Enlist ability from the attacker with
   `source_zone` `28`.
4. A persistent `TriggeringObject` row points from the Enlist ability to the
   enlisted creature, not to the attacker.

## Resolution lifecycle

1. `ResolutionStart` and `ResolutionComplete` carry `grpid` `261`.
2. `LayeredEffectCreated` introduces the pump effect, and
   `PowerToughnessModCreated` names the attacker with `power` equal to the
   enlisted creature's power and `toughness` `0`.
3. The attacker carries a persistent `ModifiedPower` + `LayeredEffect` row
   with `sourceAbilityGRPID` `261` for the rest of the turn.
4. `AbilityInstanceDeleted` names the attacker and retires the ability.

## Sibling shapes

Training and Mentor are attack triggers too, but they create their ability on
declaration without a cost. Mentor chooses a target. Enlist's linked ability
exists only after the tap cost is paid, and its `TriggeringObject` names the
creature that paid it.

## Contract

- <a id="Enlist.cost-prompt"></a>**Enlist.cost-prompt.** *(provisional)* The
  Enlist tap cost `MUST` be a `PayCostsReq` with prompt `11225`, and Enlist
  `MUST NOT` send a `SelectTargetsReq`.
- <a id="Enlist.tap-affector"></a>**Enlist.tap-affector.** The tap of the
  enlisted creature `MUST` carry `TappedUntappedPermanent` with the attacker as
  `affectorId`.
- <a id="Enlist.triggering-enlisted"></a>**Enlist.triggering-enlisted.** The
  Enlist ability's `TriggeringObject` `MUST` name the enlisted creature.
- <a id="Enlist.ability-id"></a>**Enlist.ability-id.** `ResolutionStart` and
  `ResolutionComplete` of the Enlist ability `MUST` carry `grpid` `261`.
- <a id="Enlist.pump-attacker"></a>**Enlist.pump-attacker.** The resolution's
  `PowerToughnessModCreated` `MUST` name the attacker, with `power` equal to
  the enlisted creature's power and `toughness` `0`.

## Open points

- What happens to the linked ability when the enlisted creature leaves the
  battlefield before it resolves?
- How are two Enlist instances on one creature offered?
