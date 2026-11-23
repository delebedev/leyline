---
summary: "EnteredZoneThisTurn annotation: persistent per-zone list of the objects that entered that zone during the current turn; shape, updates and retirement."
read_when:
  - "emitting or consuming EnteredZoneThisTurn"
  - "reviewing per-turn zone-entry state or its retirement at a turn boundary"
entity: annotation/EnteredZoneThisTurn
---
# EnteredZoneThisTurn

## Meaning

Many rules ask whether an object entered a zone this turn: summoning sickness
(CR 302.6) and "entered the battlefield this turn" conditions read that fact.
`EnteredZoneThisTurn` publishes it as persistent state keyed by zone.

## Variants

Persistent only. One row per zone that objects entered during the turn.

## Fields

| Field | Role |
|---|---|
| `affectorId` | The zone ID the objects entered. A zone, not a card and not a `ZoneType` value. |
| `affectedIds` | The instance IDs of the objects that entered that zone this turn. |
| details | None. The row carries no detail keys. |

## Identity model

The affector is a zone ID from the fixed zone layout, for example `28` for the
Battlefield or `27` for the Stack. `affectedIds` uses each object's ID after
its move, the same ID its [ZoneTransfer](ZoneTransfer.md) names.

## Lifecycle

1. The first object to enter a zone during a turn creates that zone's row in
   the GSM that carries the object's `ZoneTransfer`.
2. Each further entry republishes the row under the same `id` with the new
   object added to `affectedIds`.
3. At the turn boundary the rows of the ending turn are deleted through
   `diffDeletedPersistentAnnotationIds`, and the new turn starts without them.

## Sibling shapes

`DamagedThisTurn`, `CrewedThisTurn` and `SaddledThisTurn` are other per-turn
state rows. Unlike them, `EnteredZoneThisTurn` uses a zone as its affector.

## Contract

- <a id="EnteredZoneThisTurn.persistent"></a>**EnteredZoneThisTurn.persistent.**
  `EnteredZoneThisTurn` `MUST` be carried in `persistentAnnotations`, never in
  `annotations`.
- <a id="EnteredZoneThisTurn.zone-affector"></a>**EnteredZoneThisTurn.zone-affector.**
  A row's `affectorId` `MUST` be the zone ID the objects in `affectedIds`
  entered.
- <a id="EnteredZoneThisTurn.no-details"></a>**EnteredZoneThisTurn.no-details.**
  A row `MUST NOT` carry detail keys.
- <a id="EnteredZoneThisTurn.one-row-per-zone"></a>**EnteredZoneThisTurn.one-row-per-zone.**
  The live persistent state `MUST NOT` hold two `EnteredZoneThisTurn` rows with
  the same `affectorId`. An object entering a zone that already has a row is
  added to that row.
- <a id="EnteredZoneThisTurn.with-transfer"></a>**EnteredZoneThisTurn.with-transfer.**
  *(provisional)* For transfers onto the battlefield, the stack or a hand, the
  GSM carrying the `ZoneTransfer` `MUST` carry the destination zone's row with
  the transferred object's ID in `affectedIds`.

## Open points

- When do rows survive a turn boundary, and when is a row deleted during a
  turn?
- Which transfers into a graveyard or exile leave the moved object out of the
  destination row?
