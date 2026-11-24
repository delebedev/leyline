---
summary: "Paradigm mechanic: a spell that exiles itself, its recurring main-phase trigger holder, and the free cast of an exile copy with its accepted-action and casting-time-option values."
entity: mechanic/Paradigm
---
# Paradigm

A Paradigm spell exiles itself as it resolves. After the first resolution of a
spell with that name, a delayed trigger fires in each of its controller's
precombat main phases and lets them cast a copy of the exiled card without
paying its mana cost. The copy is cast, not put onto the stack: it moves from
Exile to the stack with cast-spell semantics, a free-cast
[CastingTimeOption](../annotations/CastingTimeOption.md) and the copied card's
own target prompt. The trigger lives on a
[TriggerHolder](../object_types/TriggerHolder.md).

## Rules grounding

CR 702.192a defines the two abilities: the self-exile on resolution, and the
delayed triggered ability created by the first resolution of a spell with
that name, which copies the exiled card and lets its controller cast the copy
without paying its mana cost. CR 707.12 covers casting a copy of a card.

## Surfaces

| Surface | Entity pages |
|---|---|
| Self-exile on resolution | [ObjectIdChanged](../annotations/ObjectIdChanged.md), [ZoneTransfer](../annotations/ZoneTransfer.md) `Exile` |
| Recurring trigger | [TriggerHolder](../object_types/TriggerHolder.md), [Ability](../object_types/Ability.md), [AbilityInstanceCreated](../annotations/AbilityInstanceCreated.md) |
| Copy cast | [ZoneTransfer](../annotations/ZoneTransfer.md) `CastSpell`, [UserActionTaken](../annotations/UserActionTaken.md), [CastingTimeOption](../annotations/CastingTimeOption.md) |
| Copy targets | [SelectTargetsReq](../prompts/SelectTargetsReq.md) |

## Resolution lifecycle

1. The spell is cast and resolves its printed effect inside the ordinary
   `ResolutionStart` and `ResolutionComplete` bracket.
2. After `ResolutionComplete`, the same GSM carries `ObjectIdChanged` and a
   `ZoneTransfer` from Stack to Exile with `category` `Exile` for the spell.
3. On the first resolution of that name, the same GSM creates a
   `TriggerHolder` in Limbo whose `objectSourceGrpId` is the card and whose
   `parentId` is the spell's stack instance ID. Its unique ability is the
   Paradigm delayed trigger, `205572`. The holder stays for the rest of the
   game.

## Copy-cast lifecycle

1. In a precombat main phase, `AbilityInstanceCreated` creates a stack
   `Ability` with `grpId` `205572`. Its `affectorId` and `parentId` name the
   original spell's stack instance ID, not the exiled card.
2. As the trigger resolves, a copy card appears in Exile with `isCopy` `true`
   and the trigger as its `parentId`.
3. One GSM then carries `ObjectIdChanged` from the exile copy to a new stack
   instance and a `ZoneTransfer` from Exile to Stack with `category`
   `CastSpell`, both with the trigger as `affectorId`; `UserActionTaken` with
   `actionType` `1`, `alternativeGrpId` `149` and `abilityGrpId` `205572`;
   `ResolutionComplete` and `AbilityInstanceDeleted` for the trigger; and a
   persistent `CastingTimeOption` with `type` `13`, `alternateCostGrpId` `149`
   and `castAbilityGrpId` `205572` on the stack copy. The stack copy keeps
   `isCopy` `true`.
4. A copy that targets uses the copied card's ordinary `SelectTargetsReq`,
   with the stack copy as `sourceId` and the copied card's `grpId` as
   `abilityGrpId`.
5. The copy resolves and exiles itself as in the resolution lifecycle.

No `CastingTimeOptionsReq` or `OptionalActionMessage` precedes an accepted
copy cast.

## Sibling shapes

`alternativeGrpId` `149` is the shared cast-without-paying-its-mana-cost
alternative used by other free casts; the Paradigm cast is identified by
`205572` in `abilityGrpId` and `castAbilityGrpId`. Prepared also produces a
castable `isCopy` card in Exile, but from a designation on a permanent rather
than a delayed trigger.

## Contract

- <a id="Paradigm.self-exile"></a>**Paradigm.self-exile.** A resolving Paradigm
  spell `MUST` move Stack to Exile with `category` `Exile`, after its
  `ResolutionComplete` in the same GSM.
- <a id="Paradigm.trigger-holder"></a>**Paradigm.trigger-holder.** The first
  resolution of a Paradigm spell name `MUST` create a `TriggerHolder` whose
  unique ability is `205572`, whose `objectSourceGrpId` is the card and whose
  `parentId` is the spell's stack instance ID.
- <a id="Paradigm.holder-persists"></a>**Paradigm.holder-persists.** The
  Paradigm `TriggerHolder` `MUST NOT` be deleted when a delayed trigger
  resolves.
- <a id="Paradigm.trigger-ability"></a>**Paradigm.trigger-ability.** The
  delayed trigger `MUST` appear as a stack `Ability` with `grpId` `205572` and
  the card as `objectSourceGrpId`.
- <a id="Paradigm.copy-transfer"></a>**Paradigm.copy-transfer.** The copy cast
  `MUST` move the exile copy Exile to Stack with `category` `CastSpell`, with
  the delayed trigger as `affectorId` of both the `ObjectIdChanged` and the
  `ZoneTransfer`.
- <a id="Paradigm.copy-flag"></a>**Paradigm.copy-flag.** The stack copy `MUST`
  carry `isCopy` `true`.
- <a id="Paradigm.copy-accepted"></a>**Paradigm.copy-accepted.** The accepted
  copy cast `MUST` be recorded by `UserActionTaken` with `actionType` `1`,
  `alternativeGrpId` `149` and `abilityGrpId` `205572`.
- <a id="Paradigm.copy-option"></a>**Paradigm.copy-option.** The stack copy
  `MUST` carry a persistent `CastingTimeOption` with `type` `13`,
  `alternateCostGrpId` `149` and `castAbilityGrpId` `205572`.
- <a id="Paradigm.copy-targets"></a>**Paradigm.copy-targets.**
  *(provisional)* A targeting copy `MUST` be targeted through a
  `SelectTargetsReq` with the stack copy as `sourceId` and the copied card's
  `grpId` as `abilityGrpId`.

## Open points

- What does the stream carry when the player declines to cast the copy?
- What does a second resolution of the same name carry, given that it creates
  no new delayed trigger?
- Does the exiled copy keep `isCopy` after it resolves, and what retires it?
- Why does the delayed trigger's `AbilityInstanceCreated` carry
  `source_zone` Stack when the card is in Exile?
