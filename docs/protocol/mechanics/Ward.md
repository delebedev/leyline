---
summary: "Ward mechanic: the trigger when an opponent targets a warded permanent, its links to the targeting spell, and the mana tax PayCostsReq sent to the targeting player."
entity: mechanic/Ward
---
# Ward

Ward is a triggered ability. When a permanent with ward becomes the target of
a spell or ability an opponent controls, the ward trigger counters that spell
or ability unless its controller pays the ward cost. The mechanic uses a
triggered [Ability](../object_types/Ability.md) linked by
[TriggeringObject](../annotations/TriggeringObject.md), and a mana-payment
[PayCostsReq](../prompts/PayCostsReq.md) addressed to the targeting player
while the trigger resolves.

## Rules grounding

CR 702.21a defines ward: it triggers when the permanent becomes the target of
a spell or ability an opponent controls, and counters that spell or ability
unless its controller pays the cost. The trigger belongs to the warded
permanent, so the permanent's controller controls it. CR 702.21b fixes an X in
a ward cost as the ability resolves.

## Surfaces

| Surface | Entity pages |
|---|---|
| Trigger | [Ability](../object_types/Ability.md), [AbilityInstanceCreated](../annotations/AbilityInstanceCreated.md), [TriggeringObject](../annotations/TriggeringObject.md) |
| Tax | [PayCostsReq](../prompts/PayCostsReq.md) mana-payment family, [ManaPaid](../annotations/ManaPaid.md) |
| Resolution | [ResolutionStart](../annotations/ResolutionStart.md), [ResolutionComplete](../annotations/ResolutionComplete.md) |

## Trigger lifecycle

1. The targeting player's `PlayerSubmittedTargets` commits the target.
2. In that GSM, `AbilityInstanceCreated` names the warded permanent as
   `affectorId` and creates the ward `Ability`, with `source_zone` the
   Battlefield. Its `parentId` is the warded permanent and its
   `controllerSeatId` is that permanent's controller.
3. A persistent `TriggeringObject` row names the ward ability as `affectorId`
   and, as affected, the targeting spell or ability and the warded permanent.

The ward ability's `grpId` names the ward cost, not the card: every
permanent with the same ward cost uses the same ability. `Ward {2}` is
`141939`.

## Tax lifecycle

1. `ResolutionStart` carries the ward ability's `grpId`.
2. The targeting player receives a mana-payment `PayCostsReq` with
   `promptId` `11` and `allowCancel` `Continue`. Its `Cost` parameter is the
   ward cost, and its `manaCost` entries name the targeting spell or ability
   in `objectId`.
3. Paying is the ordinary mana-payment path: each mana ability records
   `UserActionTaken` with `actionType` `4`, and each `ManaPaid` names the
   targeting spell or ability as affected, not the ward ability.
4. The GSM that completes payment carries `ResolutionComplete` and
   `AbilityInstanceDeleted` for the ward ability; the targeting spell or
   ability stays on the stack.

## Sibling shapes

An optional additional cost of a spell is asked through
`CastingTimeOptionsReq` while the spell is being cast, after it has moved to
the stack. Ward's tax
is asked while a separate triggered ability resolves, of a different player
from the one who controls that ability.

## Contract

- <a id="Ward.trigger-source"></a>**Ward.trigger-source.** The ward
  ability's `AbilityInstanceCreated` `MUST` name the warded permanent as
  `affectorId`, and the ward `Ability` `MUST` carry the warded permanent as
  `parentId`.
- <a id="Ward.trigger-controller"></a>**Ward.trigger-controller.** The ward
  `Ability` `MUST` carry the warded permanent's controller as
  `controllerSeatId`.
- <a id="Ward.triggering-object"></a>**Ward.triggering-object.** The ward
  ability's `TriggeringObject` row `MUST` name the targeting spell or ability
  among its affected IDs.
- <a id="Ward.cost-ability"></a>**Ward.cost-ability.** *(provisional)*
  Grounded for `Ward {2}`: the ward ability's `grpId` `MUST` be `141939`
  whatever permanent carries it.
- <a id="Ward.tax-prompt"></a>**Ward.tax-prompt.** *(provisional)* Grounded
  for a generic mana ward cost: the tax `MUST` be requested of the targeting
  player as a mana-payment `PayCostsReq` with `promptId` `11` and
  `allowCancel` `Continue`, while the ward ability resolves.
- <a id="Ward.tax-object"></a>**Ward.tax-object.** *(provisional)* Grounded
  for a generic mana ward cost: the tax request's `manaCost` entries `MUST`
  name the targeting spell or ability in `objectId`, and the `ManaPaid` rows
  that pay it `MUST` name the targeting spell or ability as affected.

## Open points

- What does the stream carry when the targeting player declines to pay, and
  in which GSM is the spell countered?
- What do coloured, variable and non-mana ward costs carry?
- What does a ward trigger carry when an ability, not a spell, targets the
  permanent?
