---
summary: "Equip: an activated ability of an Equipment that targets a creature its controller controls and attaches the Equipment on resolution; activation, target and attachment identities."
entity: mechanic/Equip
---
# Equip

Equip is an activated ability of an Equipment: it attaches the Equipment to
a target creature its controller controls, at sorcery speed. It uses an
activated [Ability](../object_types/Ability.md) on the stack, the ordinary
target exchange through [SelectTargetsReq](../prompts/SelectTargetsReq.md),
and attachment rows when it resolves. Equip adds no prompt of its own.

## Rules grounding

"Equip [cost]" means "[Cost]: Attach this permanent to target creature you
control. Activate only as a sorcery." (CR 702.6a). Some equip abilities
restrict the creatures they can target further (CR 702.6c).

## Surfaces

| Surface | Entity pages |
|---|---|
| Activation | [Ability](../object_types/Ability.md), [AbilityInstanceCreated](../annotations/AbilityInstanceCreated.md), [UserActionTaken](../annotations/UserActionTaken.md) |
| Target | [SelectTargetsReq](../prompts/SelectTargetsReq.md), [TargetSpec](../annotations/TargetSpec.md) |
| Attachment | `AttachmentCreated`, `Attachment` |

## Activation lifecycle

1. The player activates the equip ability of an Equipment on the
   battlefield. `AbilityInstanceCreated` names the Equipment as `affectorId`
   and the new ability as affected ID; the ability's `parentId` is the
   Equipment.
2. The target creature is chosen through `SelectTargetsReq`, and the ability
   carries a `TargetSpec` naming it.
3. The cost is paid through the ordinary activated-ability payment, and
   `UserActionTaken` records the activation.

## Resolution lifecycle

1. `ResolutionStart` and `ResolutionComplete` bracket the equip ability.
2. `AttachmentCreated` links the Equipment to the creature, and the
   persistent `Attachment` row records the link. Characteristics the
   Equipment grants travel on layered-effect rows on the creature.
3. `AbilityInstanceDeleted` retires the ability.

Moving the Equipment to another creature repeats the same lifecycle.

## Sibling shapes

An Aura with [Enchant](Enchant.md) is attached as its spell resolves, and the
Aura is the spell. Equip's source stays on the battlefield; the equip ability
is the object that targets and resolves. Reconfigure also attaches its source,
but the permanent stops being a creature while attached.

## Contract

- <a id="Equip.keeps-instance"></a>**Equip.keeps-instance.** *(provisional)*
  The Equipment and the creature `MUST` keep their instance IDs when the
  Equipment is attached or moved to another creature.

## Open points

- Which `actionType` does the accepted equip activation carry in
  `UserActionTaken`?
- When the Equipment moves to another creature, is the old link retired
  before the new one is created, and with which row?
- How is an equip cost of zero paid?
