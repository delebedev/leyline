---
summary: "Afterlife: a dies trigger that creates Spirit tokens; the trigger born beside the death transfer, its link to the battlefield instance, and the token payload."
entity: mechanic/Afterlife
---
# Afterlife

Afterlife is a dies trigger. When a permanent with Afterlife N dies, a
triggered [Ability](../object_types/Ability.md) goes on the stack in the same
GSM as the death, linked to the permanent's battlefield instance by
[TriggeringObject](../annotations/TriggeringObject.md); it resolves into N
Spirit [Token](../object_types/Token.md) objects.

## Rules grounding

Afterlife N means "When this permanent is put into a graveyard from the
battlefield, create N 1/1 white and black Spirit creature tokens with
flying" (CR 702.135a).

## Surfaces

| Surface | Entity pages | Afterlife values |
|---|---|---|
| Dies trigger | [AbilityInstanceCreated](../annotations/AbilityInstanceCreated.md), [TriggeringObject](../annotations/TriggeringObject.md) | The card's Afterlife N ability; `source_zone` `28` |
| Token creation | [Token](../object_types/Token.md), `TokenCreated` | N Spirit tokens parented to the ability |

## Trigger lifecycle

1. The GSM in which the permanent moves from the Battlefield to the
   Graveyard carries `AbilityInstanceCreated` with the permanent's
   battlefield instance as `affectorId` and `source_zone` `28`, before that
   permanent's `ObjectIdChanged` and `ZoneTransfer`.
2. The ability object has the card's Afterlife ability as `grpId`, such as
   the Afterlife 2 ability of Ministrant of Obligation, the dead card's `grpId`
   as `objectSourceGrpId`, and the battlefield instance as `parentId`.
3. The same GSM creates a persistent `TriggeringObject` row from the ability
   to the battlefield instance, with `source_zone` `28` and no
   `NEW_OBJECT_ID`.

The trigger and its links keep naming the battlefield instance, an ID the
card no longer has once the transfer applies.

## Resolution lifecycle

1. `ResolutionStart` with the Afterlife ability's `grpId`.
2. One `TokenCreated` per Spirit created, with the ability as `affectorId`. Each
   token has the ability as `parentId`, the dead card's `grpId` as
   `objectSourceGrpId`, white and black color, the Spirit subtype, power and
   toughness `1` and flying (`8`) in `uniqueAbilities`.
3. `ResolutionComplete`, then `AbilityInstanceDeleted` naming the
   battlefield instance as `affectorId`. The `TriggeringObject` row is
   deleted in the same GSM.

## Sibling shapes

Other dies triggers use the same birth and link; Afterlife is identified only
by the ability's `grpId`. Token-making spells create tokens parented to the
spell instead of to a triggered ability.

## Contract

- <a id="Afterlife.trigger-with-death"></a>**Afterlife.trigger-with-death.**
  *(provisional)* The Afterlife ability's `AbilityInstanceCreated` `MUST`
  appear in the GSM that moves its source from the Battlefield to the
  Graveyard, name the source's battlefield instance as `affectorId`, and
  carry `source_zone` `28`.
- <a id="Afterlife.triggering-object"></a>**Afterlife.triggering-object.**
  *(provisional)* The Afterlife ability's `TriggeringObject` row `MUST` name
  the source's battlefield instance in `affectedIds`, with `source_zone`
  `28`.
- <a id="Afterlife.tokens"></a>**Afterlife.tokens.** *(provisional)* Each
  Spirit token the resolution creates `MUST` be announced by its own
  `TokenCreated` row with the Afterlife ability as `affectorId`, and the token
  `MUST` carry the ability as `parentId`.

## Open points

- Does an Afterlife source that dies outside combat, or is sacrificed, use
  the same order?
- What does the resolution carry when it creates a number of Spirits other
  than N, for example because a replacement effect changes the number?
- Does the `TriggeringObject` row ever carry `NEW_OBJECT_ID` for Afterlife?
