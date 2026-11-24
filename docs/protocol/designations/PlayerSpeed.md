---
summary: "Player Speed designation: the persistent seat-scoped speed value from 1 to 4, its trigger holder and its in-place updates."
entity: designation/PlayerSpeed
---
# Player Speed

Player Speed is the persistent seat-scoped designation carrying a player's
current speed. Max Speed reads this value when it reaches 4.

## Rules grounding

CR 702.179 defines speed as a player value from 1 through 4, and max speed as 4.
The rules state is separate from the protocol row that carries it.

## State

| Field | Role |
|---|---|
| `DesignationType` | `21`. |
| `affectorId` | The seat whose speed this is. |
| `affectedIds` | The seat, followed by a `TriggerHolder` that player controls. |
| `value` | The current speed, 1 through 4. |

The row is persistent. A player with no speed has no row for it.

## Lifecycle

1. The first speed gain publishes a persistent row with `value` `1`.
2. Each increase re-emits the same persistent row with the new `value`, so a row
   for the seat carries the latest speed rather than accumulating values.
3. The row persists while the player has speed.

No `GainDesignation` or `LoseDesignation` row accompanies the updates.

## Sibling shapes

Max Speed is not a designation: it is the condition that reads this value at 4.
The `TriggerHolder` in `affectedIds` is the same object a speed trigger would be
published from, not a second state holder.

## Contract

- <a id="PlayerSpeed.persistent-shape"></a>**PlayerSpeed.persistent-shape.** A
  player's speed state `MUST` be a persistent `Designation` with
  `DesignationType` `21`, `affectorId` naming that seat, and an integer `value`
  from `1` through `4`.
- <a id="PlayerSpeed.trigger-holder"></a>**PlayerSpeed.trigger-holder.** The
  row's `affectedIds` `MUST` name the seat followed by a `TriggerHolder` that
  player controls.
- <a id="PlayerSpeed.update-in-place"></a>**PlayerSpeed.update-in-place.** A
  speed increase `MUST` re-emit the same persistent row with the new `value`,
  and `MUST NOT` add a second speed row for the same seat.

## Open points

- What retires the row when a game ends?
- When is the `TriggerHolder` in `affectedIds` replaced, and does the row survive
  that replacement?
- Does a speed value ever fall, or only rise?
