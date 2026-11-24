---
summary: "DamagedThisTurn annotation: single persistent row listing the permanents dealt damage this turn; battlefield-zone affector, growth under one id, shrinking and turn-boundary retirement."
entity: annotation/DamagedThisTurn
---
# DamagedThisTurn

## Meaning

Several effects care whether a permanent was dealt damage this turn, and
damage marked on a permanent stays until the cleanup step (CR 514.2).
`DamagedThisTurn` publishes the set of permanents dealt damage during the
current turn as one persistent row. It carries membership only; each damage
event, with its source and amount, is a [DamageDealt](DamageDealt.md) row.

## Variants

Persistent only, never combined with another type, and without details.
There is no player branch: damage to players does not add members.

## Fields

| Field | Role |
|---|---|
| `affectorId` | Always `28`, the battlefield zone. It names neither a source nor a damaged permanent. |
| `affectedIds` | The permanents dealt damage this turn, by current instance ID. |
| details | None. |

## Identity model

`affectorId` is a zone ID and `affectedIds` are instance IDs (see
[identity and zones](../model/identity-and-zones.md)). Membership belongs to
an instance: a permanent that leaves the battlefield and returns is a new
object and is not a member.

## Lifecycle

1. The first damage to a permanent in a turn: the GSM carries `DamageDealt`
   for it and a new row naming it, or every permanent damaged in that GSM.
2. Later damage in the same turn republishes the row under the same `id`
   with the new permanents added. A permanent that leaves the battlefield is
   removed from the list in the GSM of its `ObjectIdChanged`; the row is
   deleted instead when no member remains.
3. The row is deleted when the next turn begins.

At most one row is live at a time.

## Sibling shapes

[DamageDealt](DamageDealt.md) is the transient event for each damage
result. [EnteredZoneThisTurn](EnteredZoneThisTurn.md) uses the same zone
affector for another turn-scoped set.

## Contract

- <a id="DamagedThisTurn.persistent"></a>**DamagedThisTurn.persistent.**
  `DamagedThisTurn` `MUST` be carried in `persistentAnnotations` without
  details, with `affectorId` `28`.
- <a id="DamagedThisTurn.single-row"></a>**DamagedThisTurn.single-row.**
  The live state `MUST NOT` hold more than one `DamagedThisTurn` row.
- <a id="DamagedThisTurn.member-damaged"></a>**DamagedThisTurn.member-damaged.**
  *(provisional)* An instance ID added to `affectedIds`, at creation or on
  update, `MUST` be named in `affectedIds` of a `DamageDealt` row in the
  same GSM.
- <a id="DamagedThisTurn.grows-in-place"></a>**DamagedThisTurn.grows-in-place.**
  Damage to another permanent while a row is live `MUST` republish that row
  under the same `id`, not create a second row.
- <a id="DamagedThisTurn.member-leaves"></a>**DamagedThisTurn.member-leaves.**
  *(provisional)* When a member receives a new instance ID, the same GSM
  `MUST` republish the row without that member or, when no member remains,
  delete the row.
- <a id="DamagedThisTurn.turn-retire"></a>**DamagedThisTurn.turn-retire.**
  *(provisional)* The row `MUST` be deleted in the first GSM of the next
  turn.

## Open points

- Why do some rows survive the first GSM of the next turn?
- Why is a permanent occasionally added in a GSM without a `DamageDealt` for
  it?
