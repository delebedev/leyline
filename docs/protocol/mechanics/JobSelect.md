---
summary: "Job select: an Equipment enters trigger that creates a 1/1 Hero token and attaches the Equipment to it; ability ID 364, the trigger and the token-then-attach resolution."
entity: mechanic/JobSelect
---
# Job select

Job select is an enters trigger of an Equipment: it creates a 1/1 colorless
Hero creature token and attaches the Equipment to it. The trigger is a
triggered [Ability](../object_types/Ability.md) with the shared ability ID
`364`. Its resolution creates a [Token](../object_types/Token.md) and an
attachment in one GSM. No player choice is involved.

## Rules grounding

Job select means "When this Equipment enters, create a 1/1 colorless Hero
creature token, then attach this Equipment to it." (CR 702.182a).

## Surfaces

| Surface | Entity pages |
|---|---|
| Enters trigger | [Ability](../object_types/Ability.md), [AbilityInstanceCreated](../annotations/AbilityInstanceCreated.md), [TriggeringObject](../annotations/TriggeringObject.md) |
| Hero token | [Token](../object_types/Token.md), `TokenCreated` |
| Attachment | `AttachmentCreated`, `Attachment` |

## Trigger lifecycle

1. The Equipment spell resolves onto the battlefield with `Resolve`. In the
   same GSM, `AbilityInstanceCreated` names the Equipment as `affectorId` and
   the job select ability as affected ID, with `source_zone` `28`.
2. A persistent `TriggeringObject` row points from the ability to the
   Equipment.

## Resolution lifecycle

The trigger resolves in one GSM, in this order:

1. `ResolutionStart` with `grpid` `364`.
2. `TokenCreated` with the ability as `affectorId` and the Hero token as
   affected ID.
3. `AttachmentCreated` with the Equipment as `affectorId` and the token as
   affected ID. A persistent `Attachment` row with the same identities records
   the link.
4. `ResolutionComplete` with `grpid` `364`.
5. `AbilityInstanceDeleted`, naming the Equipment and the ability.

## Sibling shapes

[Equip](Equip.md) also attaches an Equipment, but through an activated
ability that targets an existing creature. Job select creates its creature
and attaches without a target or a cost.

## Contract

- <a id="JobSelect.ability-id"></a>**JobSelect.ability-id.** *(provisional)*
  `ResolutionStart` and `ResolutionComplete` of a job select trigger `MUST`
  carry `grpid` `364`.
- <a id="JobSelect.token-then-attach"></a>**JobSelect.token-then-attach.**
  *(provisional)* The resolution `MUST` carry `TokenCreated` for the Hero
  token before the `AttachmentCreated` that attaches the Equipment to it, in
  the same GSM.
- <a id="JobSelect.attachment"></a>**JobSelect.attachment.** *(provisional)*
  That `AttachmentCreated` `MUST` name the Equipment as `affectorId` and the
  token as its affected ID.

## Open points

- What happens when the Equipment has left the battlefield before the
  trigger resolves?
