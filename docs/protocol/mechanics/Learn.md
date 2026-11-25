---
summary: "Learn: discarding a card to draw, or putting a Lesson from outside the game into the hand; the SelectNReq prompts 147 and 148 over sideboard and hand cards and the Lesson branch's move and reveal."
entity: mechanic/Learn
---
# Learn

Learn is a keyword action. The player may discard a card to draw a card or,
if they did not discard, reveal a Lesson card they own from outside the game
and put it into their hand. Both branches are offered in one
[SelectNReq](../prompts/SelectNReq.md) during resolution, whose pool mixes
sideboard Lessons and hand cards. The Lesson branch is a Sideboard to Hand
[ZoneTransfer](../annotations/ZoneTransfer.md) with a reveal.

## Rules grounding

"You may discard a card. If you do, draw a card. If you didn't discard a card,
you may reveal a Lesson card you own from outside the game and put it into
your hand." (CR 701.48a).

## Surfaces

| Surface | Entity pages |
|---|---|
| Choice | [SelectNReq](../prompts/SelectNReq.md) prompts `147` and `148` |
| Lesson branch | [ZoneTransfer](../annotations/ZoneTransfer.md) `Put`, `RevealedCardCreated`, `CardRevealed` |
| Multistep bracket | `MultistepEffectStarted`, `MultistepEffectComplete` |

## Choice

The learning player receives a `SelectNReq` with `allowCancel` `Continue`;
declining picks neither branch. The request has `context`
`SelectionContext_Resolution`, `optionContext` `OptionContext_Resolution`,
`listType` `SelectionListType_Dynamic`, `idType` `IdType_InstanceId`,
`minSel` `1` and `maxSel` `1`, and its `sourceId` is the object whose
ability learns.

| Prompt | Pool |
|---:|---|
| `147` | Sideboard Lessons and hand cards to discard |
| `148` | Sideboard Lessons only |

The branch is carried by the zone of the chosen instance.

## Lesson lifecycle

1. The ability resolves. `MultistepEffectStarted` opens with `SubCategory`
   `17`.
2. The choice above is answered with a sideboard Lesson.
3. The Lesson gets a new instance ID and moves Sideboard to Hand with
   `category` `Put`.
4. `RevealedCardCreated` and a persistent `CardRevealed` row reveal it, with
   `source_zone` naming the hand.
5. `MultistepEffectComplete` closes with `SubCategory` `17`, and the ability
   is deleted in the same GSM.

## Sibling shapes

A library search also moves a hidden card to the hand and may reveal it, but
through a `SearchReq` over the library. A plain discard prompt selects only
hand cards.

## Contract

- <a id="Learn.prompt"></a>**Learn.prompt.** The learn choice `MUST` be a
  `SelectNReq` with prompt `147` when hand cards can be discarded, or `148`
  when only Lessons are offered, with `allowCancel` `Continue`, `minSel` `1`
  and `maxSel` `1`.
- <a id="Learn.lesson-transfer"></a>**Learn.lesson-transfer.** A chosen
  Lesson `MUST` move Sideboard to Hand with `category` `Put` and be revealed
  with `RevealedCardCreated`.

## Open points

- How is the discard branch published: the discard's category and the draw
  that follows?
- What does `CardRevealed` carry once the Lesson has left the hand?
