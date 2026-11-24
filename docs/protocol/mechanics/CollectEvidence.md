---
summary: "Collect evidence: an optional additional cost paid by exiling graveyard cards; the CollectEvidenceCount row, the AdditionalCost CastingTimeOption, and the Exile transfers caused by the spell."
entity: mechanic/CollectEvidence
---
# Collect evidence

Collect evidence N exiles cards from the player's graveyard with total mana
value N or more. On the cards seen so far it is an optional additional cost
of a spell: the spell is cast through an ordinary [Cast](../actions/Cast.md),
a persistent [AbilityWordActive](../annotations/AbilityWordActive.md) row
counts the graveyard's mana value against N, a `CastingTimeOption` row
records the chosen cost, and the exiled cards are
[ZoneTransfer](../annotations/ZoneTransfer.md) rows caused by the spell.

## Rules grounding

To collect evidence N is to exile any number of cards from your graveyard
with total mana value N or greater (CR 701.59a). A player can't choose to
collect evidence if they can't exile enough (CR 701.59b).

## Surfaces

| Surface | Entity pages | Collect evidence values |
|---|---|---|
| Count | [AbilityWordActive](../annotations/AbilityWordActive.md) | `CollectEvidenceCount`, `threshold` N, `value`, `AbilityGrpId` the card's collect evidence ability |
| Chosen cost | `CastingTimeOption` | `type` `5`, `additionalCostGrpId` the collect evidence ability |
| Payment | [ObjectIdChanged](../annotations/ObjectIdChanged.md), [ZoneTransfer](../annotations/ZoneTransfer.md) | Graveyard to Exile, `category` `Exile`, the spell as `affectorId` |
| Cast submit | [UserActionTaken](../annotations/UserActionTaken.md) | `actionType` `1`, `abilityGrpId` `0` |

## Cast lifecycle

1. The GSM that moves the spell to the stack creates a persistent
   `AbilityWordActive` row on the spell: the spell as `affectorId` and
   `affectedIds`, `AbilityWordName` `CollectEvidenceCount`, `threshold` N,
   `AbilityGrpId` the card's collect evidence ability, and `value` the total
   mana value of the cards in the player's graveyard.
2. When the player chooses to collect evidence, the next GSM creates a
   persistent `CastingTimeOption` row on the spell with `type` `5` and
   `additionalCostGrpId` the same ability.
3. The GSM that completes the cast moves each chosen card from the Graveyard
   to Exile with `ObjectIdChanged` and a `ZoneTransfer` with `category`
   `Exile`, each with the spell as `affectorId`, then records
   `UserActionTaken` with `actionType` `1` and `abilityGrpId` `0`.
4. The same GSM republishes the count row with `value` lowered by the exiled
   cards' mana value.

## Sibling shapes

Other optional additional costs, such as kicker, use the same
`CastingTimeOption` `type` `5` row without a count row. Delirium-style
counts also use `AbilityWordActive` with `threshold` and `value`, but are not
tied to a cost.

## Contract

- <a id="CollectEvidence.count"></a>**CollectEvidence.count.**
  *(provisional)* While a spell with collect evidence is being cast, it
  `MUST` carry a persistent `CollectEvidenceCount` row with `threshold` N,
  `AbilityGrpId` the collect evidence ability, and `value` the total mana
  value of its controller's graveyard.
- <a id="CollectEvidence.option"></a>**CollectEvidence.option.**
  *(provisional)* Choosing to collect evidence `MUST` create a persistent
  `CastingTimeOption` row on the spell with `type` `5` and
  `additionalCostGrpId` the collect evidence ability.
- <a id="CollectEvidence.exile"></a>**CollectEvidence.exile.**
  *(provisional)* Each card exiled to collect evidence `MUST` move Graveyard
  to Exile with `category` `Exile` and the spell as `affectorId`, before the
  cast's `UserActionTaken`.

## Open points

- Which prompt selects the cards, and does it weight them by mana value?
- After the spell resolves, rows naming its stack instance and its graveyard
  instance can both remain; when are they deleted?
- How do collect evidence costs of activated abilities look?
