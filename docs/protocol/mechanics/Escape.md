---
summary: "Escape: casting a card from the graveyard for its escape cost; the graveyard Cast offer, the exile-cost PayCostsReq, the accepted cast and the stack marker."
entity: mechanic/Escape
---
# Escape

Escape lets a player cast a card from their graveyard for its escape cost,
which usually combines mana with exiling other cards from that graveyard. The
protocol carries it as an alternative-cost [Cast](../actions/Cast.md) offered
from the graveyard, a [PayCostsReq](../prompts/PayCostsReq.md) that selects
the cards to exile, and the `CastThroughAbility` branch of
[CastingTimeOption](../annotations/CastingTimeOption.md) on the stack spell.

## Rules grounding

"Escape [cost]" lets the card be cast from its owner's graveyard by paying
that cost rather than its mana cost (CR 702.138a). A spell or permanent
escaped if it was cast from a graveyard with an escape ability (CR 702.138b);
some permanents enter with counters or abilities only if they escaped
(CR 702.138c, CR 702.138d).

## Surfaces

| Surface | Entity pages |
|---|---|
| Graveyard offer | [Cast](../actions/Cast.md) with the escape ability in `alternativeGrpId` |
| Cards to exile | [PayCostsReq](../prompts/PayCostsReq.md) with prompt `5500` |
| Accepted cast | [UserActionTaken](../annotations/UserActionTaken.md), [ZoneTransfer](../annotations/ZoneTransfer.md) |
| Stack marker | [CastingTimeOption](../annotations/CastingTimeOption.md) `type` `13` |

## Cast lifecycle

1. The card in the graveyard is offered as a `Cast` whose `alternativeGrpId`
   is the card's escape ability.
2. When the client submits it, the card moves Graveyard to Stack with
   `CastSpell` under a new instance ID. The stack spell carries a
   `CastingTimeOption` row of `type` `13` whose `alternateCostGrpId` and
   `castAbilityGrpId` are both the escape ability.
3. The mana part of the cost is paid through the ordinary payment.
4. A `PayCostsReq` with prompt `5500` and a `CardId` parameter naming the
   stack spell asks for the graveyard cards to exile. It is an
   `EffectCostType_Select` request whose `minSel` and `maxSel` both equal the
   number of cards to exile, as stated by
   [PayCostsReq.select-exact](../prompts/PayCostsReq.md#PayCostsReq.select-exact).
5. The GSM after the answer moves each chosen card Graveyard to Exile with
   `category` `Exile` and the stack spell as `affectorId`, and carries
   `UserActionTaken` with `actionType` `1` and the escape ability in both
   `alternativeGrpId` and `abilityGrpId`.
6. The spell then resolves like any other spell.

`CastingTimeOption` ends with the stack object. That a resulting permanent
escaped is not carried by a general marker; a card that depends on it shows
the result through its own state.

## Sibling shapes

| Mechanic | Difference from escape |
|---|---|
| [Flashback](Flashback.md) | Uses its own ability, has no exile cost, and the spell is exiled when it leaves the stack. |
| [Harmonize](Harmonize.md) | Taps a creature to reduce the cost, and the spell is exiled when it leaves the stack. |
| `Disturb` | Casts the card's back face. |

Escape imposes no move to exile when the spell resolves.

## Contract

- <a id="Escape.exile-prompt"></a>**Escape.exile-prompt.** *(provisional)*
  The cards to exile for an escape cost `MUST` be selected through a
  `PayCostsReq` with prompt `5500` whose `CardId` parameter names the stack
  spell.
- <a id="Escape.exile-affector"></a>**Escape.exile-affector.** *(provisional)*
  Each card exiled for the escape cost `MUST` move Graveyard to Exile with
  `category` `Exile` and the escaping spell as `affectorId`.
- <a id="Escape.accepted"></a>**Escape.accepted.** *(provisional)* An accepted
  escape cast `MUST` be recorded by `UserActionTaken` with `actionType` `1`
  and the escape ability in both `alternativeGrpId` and `abilityGrpId`.
- <a id="Escape.cast-marker"></a>**Escape.cast-marker.** *(provisional)* The
  escaping spell's `CastingTimeOption` row `MUST` have `type` `13` with the
  escape ability in both `alternateCostGrpId` and `castAbilityGrpId`.

## Open points

- How is an escape cost that exiles nothing but mana, or that adds another
  cost, requested?
- Does the escape offer carry `grpId` and `facetId`?
