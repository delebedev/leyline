---
summary: "Reconfigure mechanic: attach and unattach activations of an Equipment creature, their keyword ability IDs, attachment rows and the creature-type change."
entity: mechanic/Reconfigure
---
# Reconfigure

Reconfigure gives an Equipment creature two activated abilities: attach it to
another creature you control, or unattach it. While attached it is not a
creature. Both use the ordinary `Activate` path; the mechanic adds two keyword
ability IDs, the attachment rows and a type change carried by
[ModifiedType](../annotations/ModifiedType.md).

## Rules grounding

CR 702.151a defines the attach and unattach abilities, both activated only as
a sorcery. CR 702.151b makes an attached permanent with reconfigure stop being
a creature until it becomes unattached.

## Surfaces

| Surface | Entity pages |
|---|---|
| Attach and unattach activations | [Ability](../object_types/Ability.md), [UserActionTaken](../annotations/UserActionTaken.md), [ActionsAvailableReq](../prompts/ActionsAvailableReq.md) |
| Attach target | [SelectTargetsReq](../prompts/SelectTargetsReq.md), [TargetSpec](../annotations/TargetSpec.md) |
| Attached state | `AttachmentCreated`, persistent `Attachment`, [ModifiedType](../annotations/ModifiedType.md), [LayeredEffectCreated](../annotations/LayeredEffectCreated.md) |
| Unattach | `RemoveAttachment` |

## Activation lifecycle

1. The permanent is offered as `Activate` actions on its own instance:
   `abilityGrpId` `243` to attach and `244` to unattach. Both offers can
   appear in one request whether or not the permanent is attached.
2. Either activation puts a stack `Ability` whose `grpId` is the card's own
   reconfigure ability; attach and unattach share that `grpId`.
3. Attach targets through the ordinary
   [SelectTargetsReq](../prompts/SelectTargetsReq.md) lifecycle; the
   `TargetSpec` row carries `abilityGrpId` `243`.
4. The accepted activation is recorded by `UserActionTaken` with
   `actionType` `2` and `abilityGrpId` `243` or `244`, the keyword ability
   rather than the stack object's `grpId`
   ([UserActionTaken.activate](../annotations/UserActionTaken.md#UserActionTaken.activate)).

## Attach lifecycle

1. `ResolutionStart` carries the card's reconfigure ability.
2. `AttachmentCreated` names the reconfigure permanent as `affectorId` and the
   host creature as affected, and `LayeredEffectCreated` introduces the type
   change.
3. `ResolutionComplete` and `AbilityInstanceDeleted` close the ability.
4. The same GSM creates the persistent `Attachment` row from the permanent to
   its host and a `ModifiedType` row on the permanent; the permanent's object
   no longer lists `CardType_Creature`.

## Unattach lifecycle

1. `ResolutionStart` carries the card's reconfigure ability.
2. `RemoveAttachment` names the reconfigure permanent as `affectorId`, the
   former host as affected, and `invalidating_grpid` `244`.
3. `ResolutionComplete` and `AbilityInstanceDeleted` close the ability.
4. The `Attachment` and `ModifiedType` rows are deleted, and the permanent's
   object lists `CardType_Creature` again.

## Sibling shapes

Equip also attaches through an activated ability and the same attachment
rows, but it has no unattach activation and changes no type on the
Equipment.

## Contract

- <a id="Reconfigure.mode-offers"></a>**Reconfigure.mode-offers.** A
  reconfigure attach `MUST` be offered as `Activate` with `abilityGrpId` `243`
  and an unattach with `abilityGrpId` `244`, on the reconfigure permanent's
  instance.
- <a id="Reconfigure.shared-stack-ability"></a>**Reconfigure.shared-stack-ability.**
  Attach and unattach activations of one card `MUST` put a stack `Ability`
  with the same `grpId`, the card's reconfigure ability.
- <a id="Reconfigure.attach-target-spec"></a>**Reconfigure.attach-target-spec.**
  The `TargetSpec` of an attach activation `MUST` carry `abilityGrpId` `243`.
- <a id="Reconfigure.attach-rows"></a>**Reconfigure.attach-rows.** When the
  resolution attaches the reconfigure permanent to the host, it `MUST` carry
  `AttachmentCreated` from the reconfigure permanent to the host and create a
  persistent `Attachment` row between them.
- <a id="Reconfigure.attached-not-creature"></a>**Reconfigure.attached-not-creature.**
  While attached, the reconfigure permanent's object `MUST NOT` list
  `CardType_Creature`, and it `MUST` carry a persistent `ModifiedType` row.
- <a id="Reconfigure.unattach-rows"></a>**Reconfigure.unattach-rows.** When
  the resolution unattaches the reconfigure permanent from its host, it `MUST`
  carry `RemoveAttachment` from the reconfigure permanent to the former host
  with `invalidating_grpid` `244`, and `MUST` delete the `Attachment` and
  `ModifiedType` rows.

## Open points

- What does an attach or unattach resolution carry when the attachment does
  not change, for example because the target or the permanent has left the
  battlefield?
- Is the attach or unattach offer that cannot be used marked inactive, or
  rejected on submission?
- What does `RemoveAttachment` carry when the permanent becomes unattached
  because its host leaves the Battlefield?
- What does the response to the attach target prompt carry beyond the shared
  target response?
