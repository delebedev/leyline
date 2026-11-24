---
summary: "SyntheticEvent annotation: transient presentation cue; type 1 announces a player's life loss immediately before its ModifiedLife, with the source and the seat, and a rare object-scoped type 3."
entity: annotation/SyntheticEvent
---
# SyntheticEvent

## Meaning

`SyntheticEvent` carries no rules result of its own. It is a cue that lets
the client sequence an effect it presents: the life-loss branch marks the
moment a player loses life, naming who caused it, just before the
`ModifiedLife` row that carries the amount.

## Variants

Transient only, never combined with another type. `type` selects the branch:

| `type` | Branch | `affectorId` | `affectedIds` |
|---|---|---|---|
| `1` | A player loses life | The source: an attacking creature, a resolving spell or ability, or the permanent whose life payment it is | The seat losing life |
| `3` | Rare object-scoped cue; see Open points | A permanent | The same permanent |

## Fields

| Field | Role |
|---|---|
| `affectorId` | As above. |
| `affectedIds` | As above, one entry. |
| `type` | The branch, as an Int32. |

## Identity model

On `type` `1`, `affectorId` is an instance ID and `affectedIds` is a seat ID.
When several creatures deal combat damage to one player at once, a single row
names one of them as the source; the individual sources are on
[DamageDealt](DamageDealt.md).

## Lifecycle

`type` `1` sits inside one GSM:

1. [DamageDealt](DamageDealt.md) rows, when the life loss comes from damage.
2. `SyntheticEvent` naming the source and the seat.
3. The negative `ModifiedLife` for that seat, immediately after.

For example, a creature attacking unblocked produces `DamageDealt` from the
creature to the player, then `SyntheticEvent` `type` `1`, then `ModifiedLife`
with the negative amount. A life gain never has this cue, and not every life
loss does.

## Sibling shapes

[DamageDealt](DamageDealt.md) is the damage event and `ModifiedLife` the
life-total change. `SyntheticEvent` replaces neither; it marks the loss
between them.

## Contract

- <a id="SyntheticEvent.transient"></a>**SyntheticEvent.transient.**
  `SyntheticEvent` `MUST` be carried in `annotations` with an Int32 `type`
  detail and exactly one `affectedIds` entry.
- <a id="SyntheticEvent.precedes-life-loss"></a>**SyntheticEvent.precedes-life-loss.**
  A `type` `1` row `MUST` be followed immediately by a `ModifiedLife` row with
  a negative `life` for the seat in its `affectedIds`.
- <a id="SyntheticEvent.one-per-combat-loss"></a>**SyntheticEvent.one-per-combat-loss.**
  *(provisional)* When several sources deal combat damage to one player in a
  GSM, that GSM `MUST NOT` carry more than one `type` `1` row for the player.

## Open points

- Which life losses carry no `type` `1` row?
- What does `type` `3` mark?
