---
summary: "TappedUntappedPermanent annotation: a permanent became tapped or untapped; the tapped flag, the permanent's identity and the mana-ability affector."
read_when:
  - "emitting or consuming TappedUntappedPermanent"
  - "tapping a permanent for mana, to attack or to pay a cost, or untapping it"
entity: annotation/TappedUntappedPermanent
---
# TappedUntappedPermanent

## Meaning

`TappedUntappedPermanent` reports that a permanent changed between tapped and
untapped (CR 110.5). The permanent's game object carries the resulting
`isTapped` state; this row is the event.

## Variants

One transient row per permanent whose status changed. `tapped` selects the
direction: `1` tapped, `0` untapped.

## Fields

| Field | Role |
|---|---|
| `affectorId` | What caused the change, by cause; see Identity model. |
| `affectedIds` | The permanent, as a one-element list. |
| `tapped` | Int32 `1` when the permanent became tapped, `0` when it became untapped. |

## Identity model

- **Mana ability cost.** The affector is the mana ability instance that the
  activation's [AbilityInstanceCreated](AbilityInstanceCreated.md) created, and
  `affectedIds` is that row's source.
- **Untap step and attacking.** The row either has no affector or names the
  permanent itself; see Open points.
- **Other costs and effects.** The affector names the responsible spell,
  ability instance or object, or is absent.

## Lifecycle

Tapping for mana is the second row of the mana-ability sequence, after
`AbilityInstanceCreated` and before `UserActionTaken`. The untap step publishes
one row with `tapped` `0` for each permanent that untaps. Declaring attackers
publishes one row with `tapped` `1` for each attacker that taps.

## Contract

- <a id="TappedUntappedPermanent.single-permanent"></a>**TappedUntappedPermanent.single-permanent.**
  A `TappedUntappedPermanent` row `MUST` carry exactly one ID in
  `affectedIds`.
- <a id="TappedUntappedPermanent.flag"></a>**TappedUntappedPermanent.flag.**
  `tapped` `MUST` be an Int32 detail whose value is `1` for a permanent that
  became tapped and `0` for one that became untapped.
- <a id="TappedUntappedPermanent.mana-ability-affector"></a>**TappedUntappedPermanent.mana-ability-affector.**
  When a mana ability's cost taps its source, `affectorId` `MUST` be the
  instance in that activation's `AbilityInstanceCreated` `affectedIds`, and
  `affectedIds` `MUST` be that row's `affectorId`.

## Open points

- What selects between no affector and the permanent itself for untap-step and
  attack taps?
