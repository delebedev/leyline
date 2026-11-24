---
summary: "AbilityWordActive annotation: named condition or count tracked for a card, such as Raid, Threshold or a collect-evidence count; AbilityWordName vocabulary, value and threshold, affector domains, rails and updates."
entity: annotation/AbilityWordActive
---
# AbilityWordActive

## Meaning

Ability words such as raid, threshold or delirium have no rules meaning of
their own (CR 207.2c); they label a condition that the card's ability checks.
The client highlights a card when such a condition is met and can show the
count it depends on. `AbilityWordActive` carries that condition for a card,
under a name and, for counted conditions, with the current value and the
target.

The name is an open vocabulary. Besides printed ability words it includes
bookkeeping names for counts and conditions, such as
`CollectEvidenceCount`, `NumberOfCreaturesYouControl`,
`LifeGainedThisTurn` or `StationLevelThreshold`.

## Variants

Persistent for tracked conditions; transient for a few one-time markers.

| Shape | Keys | Example |
|---|---|---|
| Label | `AbilityWordName` only | `Raid`, `Morbid`, `Infusion` |
| Count | `value`, often `AbilityGrpId` | `LifeGainedThisTurn` |
| Count with target | `value`, `threshold`, often `AbilityGrpId` | `Threshold` with target `7`, `CollectEvidenceCount` |
| Specialized | Name-specific keys such as `colors`, `types`, `for-mode`, `choice`, `isActive`, `activeAbilities` | `Delirium` with `types` |
| Transient marker | `AbilityWordName`, on `annotations` | `Convoke` as a convoked spell is cast |

## Fields

| Field | Role |
|---|---|
| `affectorId` | A seat or an object, fixed per name; see Identity model. |
| `affectedIds` | The cards the condition is shown on; usually one. |
| `AbilityWordName` | The condition's name, as a string. |
| `value` | The current count. |
| `threshold` | The count at which the condition is met. Present only with `value`. |
| `AbilityGrpId` | The ability definition that checks the condition, not an instance. |
| other keys | Inputs whose meaning depends on the name. |

## Identity model

Each name uses one `affectorId` domain. Conditions about a player's turn or
board, such as `Raid`, `Morbid`, `Infusion` and `Delirium`, name the seat of
the player they are evaluated for. Counts kept for a card, such as
`Threshold`, `LifeGainedThisTurn` and `CollectEvidenceCount`, name the
affected card itself. `affectedIds` holds instance IDs; `AbilityGrpId` is an
ability definition.

## Lifecycle

1. A persistent row appears when the condition becomes relevant to a card.
2. When the count changes, the row is republished under the same `id` with
   the new `value`.
3. The row is deleted when the condition stops being relevant to the card,
   or ends with the card's instance ID.

A transient row appears only in the GSM of its event.

## Sibling shapes

`AbilityWordActive` never changes what an ability does; the rules engine
evaluates the condition itself. [Qualification](Qualification.md) and
[AddAbility](AddAbility.md) carry rules state that applies to objects.

## Contract

- <a id="AbilityWordActive.name"></a>**AbilityWordActive.name.**
  Every row `MUST` carry `AbilityWordName` as a string detail.
- <a id="AbilityWordActive.threshold-with-value"></a>**AbilityWordActive.threshold-with-value.**
  A row that carries `threshold` `MUST` also carry `value`.
- <a id="AbilityWordActive.update-in-place"></a>**AbilityWordActive.update-in-place.**
  A change of `value` for one card and name `MUST` republish the existing
  persistent row under the same `id`.
- <a id="AbilityWordActive.affector-domain"></a>**AbilityWordActive.affector-domain.**
  *(provisional)* Rows named `Raid`, `Morbid`, `Infusion` or `Delirium` `MUST`
  carry a seat as `affectorId`. Rows named `Threshold`, `LifeGainedThisTurn`
  or `CollectEvidenceCount` `MUST` carry the affected card as `affectorId`.

## Open points

- What selects the transient rail for a name?
- Which names carry `AbilityGrpId`, and which printed ability words have no
  row?
- What do `ValueOfX` and the specialized keys encode?
