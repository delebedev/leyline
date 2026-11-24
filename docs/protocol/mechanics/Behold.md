---
summary: "Behold: an optional additional cost paid by revealing a qualifying hand card; the OptionalCostPaid CastingTimeOption with costType behold, and the one-GSM reveal of the hand card."
entity: mechanic/Behold
---
# Behold

To behold a quality is to reveal a card with it from the hand or choose a
permanent with it on the battlefield. On the cards seen so far it is an
optional additional cost. The cast is an ordinary [Cast](../actions/Cast.md)
whose accepted `UserActionTaken` does not name Behold; the paid cost is a
[CastingTimeOption](../annotations/CastingTimeOption.md) row of `type` `18`
with `costType` `behold`, beside a reveal of the chosen hand card.

## Rules grounding

"Behold a [quality]" means "Reveal a [quality] card from your hand or choose
a [quality] permanent you control on the battlefield" (CR 701.4a).

## Surfaces

| Surface | Entity pages | Behold values |
|---|---|---|
| Cast submit | [UserActionTaken](../annotations/UserActionTaken.md) | `actionType` `1`, `abilityGrpId` `0` |
| Paid cost | [CastingTimeOption](../annotations/CastingTimeOption.md) | `type` `18`, `costType` `behold`, `costGrpid` the behold cost ability |
| Hand reveal | `RevealedCardCreated`, `CardRevealed` | `source_zone` the caster's hand |

## Cast lifecycle

1. The card moves to the stack and its targets are chosen as for any spell.
2. When the behold cost is paid by revealing a hand card, the GSM that
   completes the cast carries `UserActionTaken` with `actionType` `1` and
   `abilityGrpId` `0`, then `RevealedCardCreated` for a revealed copy of the
   hand card.
3. The same GSM creates a persistent `CastingTimeOption` row on the spell
   with `type` `18`, `costType` `behold` and `costGrpid` the behold cost
   ability, and a persistent `CardRevealed` row from the spell to the
   revealed copy with `source_zone` the caster's hand.
4. The next GSM deletes the `CardRevealed` row; the `CastingTimeOption` row
   is deleted when the spell resolves.

When the cost is not paid, the cast GSM carries none of
`RevealedCardCreated`, `CardRevealed` and the behold `CastingTimeOption`.

## Sibling shapes

[CollectEvidence](CollectEvidence.md) and other optional additional costs
mark the spell with `CastingTimeOption` too, under `type` `5`.
[Casualty](Casualty.md) uses its own `type` `15`.

## Contract

- <a id="Behold.cast-option"></a>**Behold.cast-option.** *(provisional)* A
  spell whose behold cost is paid `MUST` carry a persistent
  `CastingTimeOption` row with `type` `18`, `costType` `behold` and
  `costGrpid` the behold cost ability.
- <a id="Behold.reveal"></a>**Behold.reveal.** *(provisional)* Paying the
  cost by revealing a hand card `MUST` carry, in the cast GSM,
  `RevealedCardCreated` and a persistent `CardRevealed` row from the spell
  with `source_zone` the caster's hand.
- <a id="Behold.unpaid"></a>**Behold.unpaid.** *(provisional)* A cast that
  does not pay the behold cost `MUST NOT` carry the behold
  `CastingTimeOption` row or the reveal rows.

## Open points

- How is choosing a battlefield permanent published?
- What does the caster see: which prompt offers the cost and the cards to
  reveal?
