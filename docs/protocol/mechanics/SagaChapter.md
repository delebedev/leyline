---
summary: "Saga chapter mechanic: lore counters added on entry and each precombat main phase, one chapter Ability per threshold crossed, and the final-chapter sacrifice in the resolution GSM."
entity: mechanic/SagaChapter
---
# Saga chapter

A Saga gains lore counters, and each chapter ability triggers when the lore
count reaches its chapter number. After the final chapter leaves the stack the
Saga is sacrificed. The mechanic uses `CounterAdded` and a persistent `Counter`
row of the lore type, one triggered [Ability](../object_types/Ability.md) per
chapter, and a [ZoneTransfer](../annotations/ZoneTransfer.md) with `category`
`Sacrifice`.

## Rules grounding

CR 714.2b: a chapter ability triggers when lore counters go from below its
chapter number to at least it. CR 714.3a and 714.3c: a Saga enters with a lore
counter, and its controller adds one in each precombat main phase as a
turn-based action. CR 714.4: a Saga whose lore count is at least its final
chapter number, with no chapter ability on the stack, is sacrificed as a
state-based action. CR 702.155 defines read ahead.

## Surfaces

| Surface | Entity pages |
|---|---|
| Lore count | `CounterAdded`, persistent `Counter` with `counter_type` `108` |
| Chapter trigger | [Ability](../object_types/Ability.md), [AbilityInstanceCreated](../annotations/AbilityInstanceCreated.md), [ResolutionStart](../annotations/ResolutionStart.md), [ResolutionComplete](../annotations/ResolutionComplete.md), [AbilityInstanceDeleted](../annotations/AbilityInstanceDeleted.md) |
| Final sacrifice | [ZoneTransfer](../annotations/ZoneTransfer.md) `Sacrifice` |

## Chapter lifecycle

1. Each lore increase is a `CounterAdded` with the Saga as `affectorId` and
   affected, `counter_type` `108` and `transaction_amount` the number added.
   The same GSM creates or updates the Saga's persistent `Counter` row.
2. For each chapter threshold crossed, outside the read ahead case below,
   the same GSM creates a chapter
   `Ability` with `AbilityInstanceCreated` from the Saga, `source_zone` the
   Battlefield, and the Saga as `parentId`. Each chapter has its own ability
   `grpId`.
3. A targeted chapter chooses targets as any triggered ability does.
4. The chapter resolves inside `ResolutionStart` and `ResolutionComplete` with
   its `grpid`, then `AbilityInstanceDeleted`.

A Saga with read ahead enters with the chosen number of lore counters in one
`CounterAdded`. For the rest of the turn it entered, a chapter triggers only
when the lore count equals that chapter's number, so entering skips the
lower chapters.

## Final-chapter lifecycle

When the Saga is sacrificed after its final chapter resolves, that chapter's
resolution GSM carries its effect rows,
`ResolutionComplete`, `AbilityInstanceDeleted`, and then the Saga's
`ObjectIdChanged` and Battlefield to Graveyard `ZoneTransfer` with `category`
`Sacrifice`; the lore `Counter` row is deleted there. A transforming Saga
instead exiles and returns itself as its back face inside the chapter's
resolution.

## Sibling shapes

Station also keeps a counter threshold, but its thresholds are static ability
levels, not triggers.

## Contract

- <a id="SagaChapter.lore-counter"></a>**SagaChapter.lore-counter.** A lore
  increase `MUST` be a `CounterAdded` with `counter_type` `108`, with the Saga
  as `affectorId` and affected ID.
- <a id="SagaChapter.chapter-ability"></a>**SagaChapter.chapter-ability.** A
  triggered chapter `MUST` be its own `Ability` with the Saga as `parentId`,
  created by `AbilityInstanceCreated` from the Saga in the GSM of the lore
  increase.
- <a id="SagaChapter.final-sacrifice"></a>**SagaChapter.final-sacrifice.**
  *(provisional)* Grounded for a non-transforming Saga: when the Saga is
  sacrificed in the GSM in which its final chapter finishes resolving, the
  `Sacrifice` transfer `MUST` follow that chapter's `AbilityInstanceDeleted`
  in that GSM. A sacrifice delayed by another chapter ability still on the
  stack is not covered.

## Open points

- How are several chapters crossed by one lore increase ordered?
- What does the client see when the final chapter is countered?
- What does the final chapter's resolution carry when the Saga is not
  sacrificed then, for example because another of its chapter abilities is
  still on the stack or its lore count fell below the final chapter?
- With read ahead, which rows does a later lore increase in the entry turn
  carry for chapters it crosses without landing on?
