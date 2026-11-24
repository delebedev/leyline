---
summary: "Ascend: the player-owned Ascend count row, the City's Blessing detail and designation gained at ten permanents, and the blessing that stays when the count drops."
entity: mechanic/Ascend
---
# Ascend

Ascend counts the permanents a player controls toward ten and, once reached,
gives that player the city's blessing for the rest of the game. The count is
a persistent [AbilityWordActive](../annotations/AbilityWordActive.md) row
owned by the player; the blessing is a `GainDesignation` row and a persistent
`Designation` row on the player's seat, plus a `City's Blessing` detail on
the count row.

## Rules grounding

On a permanent, Ascend means "If you control ten or more permanents, you get
the city's blessing for the rest of the game"; on an instant or sorcery it is
checked when the spell resolves (CR 702.131b, CR 702.131a). The city's
blessing is a designation with no rules meaning of its own (CR 702.131c).

## Surfaces

| Surface | Entity pages | Ascend values |
|---|---|---|
| Count | [AbilityWordActive](../annotations/AbilityWordActive.md) | `AbilityWordName` `Ascend`, `threshold` `10`, `value` the permanent count |
| Blessing | `GainDesignation`, `Designation` | `DesignationType` `6`, `PromptMessage` `126` on the persistent row |
| Blessing on the count | [AbilityWordActive](../annotations/AbilityWordActive.md) | `City's Blessing` `1` |

## Count lifecycle

1. While its controller has an Ascend permanent, the game carries one
   persistent `AbilityWordActive` row with the player's seat as `affectorId`,
   the Ascend permanents in `affectedIds`, `AbilityWordName` `Ascend`,
   `threshold` `10` and `value` the number of permanents the player controls.
2. Each change to that number or to the set of Ascend permanents republishes
   the row under the same annotation ID.

## Blessing lifecycle

1. The GSM in which the count reaches `10` carries a transient
   `GainDesignation` with the seat as `affectorId` and `affectedIds` and
   `DesignationType` `6`.
2. The same GSM creates a persistent `Designation` row on the seat with
   `DesignationType` `6` and `PromptMessage` `126`, and adds the integer
   `City's Blessing` `1` to the count row.
3. Later GSMs keep both the `Designation` row and the `City's Blessing`
   detail when `value` falls below `10`.

## Sibling shapes

Threshold-style ability words also publish `AbilityWordActive` rows with
`threshold` and `value`, but on a card rather than a seat, and without a
designation. [Companion](Companion.md) also puts a `Designation` row on a
seat, with `DesignationType` `7`.

## Contract

- <a id="Ascend.count"></a>**Ascend.count.** The Ascend row `MUST` name the
  player's seat as `affectorId` and the player's Ascend permanents in
  `affectedIds`, with `threshold` `10` and `value` the number of permanents
  the player controls.
- <a id="Ascend.blessing"></a>**Ascend.blessing.** *(provisional)* The GSM in
  which a player gets the city's blessing `MUST` carry `GainDesignation` and a
  new persistent `Designation` row on the seat, both with `DesignationType`
  `6`, and `MUST` add `City's Blessing` `1` to the Ascend row.
- <a id="Ascend.blessing-stays"></a>**Ascend.blessing-stays.**
  *(provisional)* Once given, the `Designation` row and the `City's Blessing`
  detail `MUST` stay when `value` falls below `10`.

## Open points

- How does an instant or sorcery with Ascend publish the count and the
  blessing?
- Does the count row stay once the player controls no Ascend permanent?
