---
summary: "DamageDealt annotation: a source dealt damage to a player or object; amount, damage type for combat, noncombat and fight damage, and the accompanying life change."
entity: annotation/DamageDealt
---
# DamageDealt

## Meaning

`DamageDealt` reports that a source dealt damage to one player or one object
(CR 120.3). The consequences, such as life loss or a creature dying, have rows
of their own.

## Variants

One transient row per source-recipient pair. `type` classifies the damage:

| `type` | Damage |
|---:|---|
| `1` | Combat damage (CR 510.2). |
| `2` | Other noncombat damage: damage a spell or ability deals, such as Lightning Bolt's, and one-sided damage a creature deals because of an effect. |
| `3` | Damage dealt by creatures that fight (CR 701.14); each creature that deals damage in the fight has a row, so a fight usually has two reciprocal rows. |

## Fields

| Field | Role |
|---|---|
| `affectorId` | The source of the damage: the creature, spell or ability instance that dealt it. |
| `affectedIds` | The recipient, as a one-element list: a seat number for a player, an instance ID for an object. |
| `damage` | The amount dealt. A row with `damage` `0` also occurs. |
| `type` | The damage classification above. |
| `markDamage` | A flag, `1` in ordinary rows; see Open points. |

## Identity model

`affectedIds` holds either a seat number or an instance ID; the value range
selects which. A spell that deals damage is named by its stack ID, the same ID
its `ResolutionStart` names.

## Lifecycle

Combat damage appears in the combat damage step's GSM, one row per assignment.
Spell damage appears inside the spell's resolution, between `ResolutionStart`
and `ResolutionComplete`. Damage to a player is accompanied in the same GSM by
`ModifiedLife` for that seat. A creature destroyed by lethal damage moves with
a `ZoneTransfer` whose `category` is `SBA_Damage` or `SBA_Deathtouch`.

## Sibling shapes

`ReplacementEffectApplied` accompanies damage that a replacement effect
modified. `DamagedThisTurn` is the persistent per-turn record of objects that
were dealt damage. `PredictedDirectDamage` announces, before resolution, the
damage a spell on the stack will deal.

## Contract

- <a id="DamageDealt.single-recipient"></a>**DamageDealt.single-recipient.**
  A `DamageDealt` row `MUST` carry exactly one ID in `affectedIds`.
- <a id="DamageDealt.combat-type"></a>**DamageDealt.combat-type.**
  Combat damage `MUST` carry `type` `1`.
- <a id="DamageDealt.noncombat-type"></a>**DamageDealt.noncombat-type.**
  Noncombat damage other than fight damage `MUST` carry `type` `2`.
- <a id="DamageDealt.spell-source"></a>**DamageDealt.spell-source.**
  Damage that a resolving spell deals `MUST` carry `affectorId` equal to the
  spell's stack instance ID.
- <a id="DamageDealt.fight-type"></a>**DamageDealt.fight-type.**
  *(provisional)* For two creatures fighting, each creature's damage `MUST`
  carry `type` `3`.
- <a id="DamageDealt.player-life"></a>**DamageDealt.player-life.**
  When `affectedIds` names a seat and `damage` is greater than `0`, the same
  GSM `MUST` carry a `ModifiedLife` whose `affectedIds` is that seat.

## Open points

- What does `markDamage` signal, and when is it absent or `0`?
- When is a row with `damage` `0` sent?
