---
summary: "PhaseOrStepModified annotation: the game entered a new phase or step; phase and step values, the seat whose turn it is, and the rows at a turn boundary."
entity: annotation/PhaseOrStepModified
---
# PhaseOrStepModified

## Meaning

A turn is a sequence of phases and steps (CR 500.1). `PhaseOrStepModified`
reports each phase or step the game enters. `turnInfo` carries the resulting
position; this row is the event.

## Variants

One transient row per phase or step entered. A GSM that crosses several steps
carries one row for each, in the order the game entered them.

## Fields

| Field | Role |
|---|---|
| `affectorId` | Absent. |
| `affectedIds` | The seat whose turn the phase or step belongs to, as a one-element list. The beginning-phase row with `step` `0` that precedes `NewTurnStarted` names seat `0` instead. |
| `phase` | The `Phase` enum integer: `1` Beginning, `2` precombat main, `3` Combat, `4` postcombat main, `5` Ending. |
| `step` | The `Step` enum integer, for example `1` Untap, `2` Upkeep, `3` Draw, `5` declare attackers, `7` combat damage, `10` Cleanup. `0` for a phase without steps. |

## Lifecycle

The usual turn boundary is one GSM that carries, in order:

1. the row for the ending turn's cleanup step, `phase` `5` and `step` `10`,
   naming the seat whose turn ends;
2. a row for the beginning phase with `step` `0`, naming seat `0`;
3. `NewTurnStarted`, whose `affectorId` and `affectedIds` are both the seat
   whose turn starts;
4. the untap step row, `phase` `1` and `step` `1`;
5. the upkeep step row, `phase` `1` and `step` `2`.

The new turn's rows name the seat whose turn starts. Other forms occur:

- When the cleanup step was entered in an earlier GSM, the boundary GSM
  starts with the beginning-phase row.
- When a turn ends before its ending phase, the boundary GSM starts with the
  row of the step the game was in instead of the cleanup row.
- The first turn of the game carries `NewTurnStarted` with no row before it,
  sometimes followed by the untap and upkeep rows.

## Sibling shapes

`NewTurnStarted` marks the start of a turn and carries the new active seat as
both affector and affected. It carries no phase or step.

## Contract

- <a id="PhaseOrStepModified.no-affector"></a>**PhaseOrStepModified.no-affector.**
  A `PhaseOrStepModified` row `MUST NOT` carry an `affectorId`.
- <a id="PhaseOrStepModified.turn-seat"></a>**PhaseOrStepModified.turn-seat.**
  For a row whose `step` is not `0`, `affectedIds` `MUST` be exactly the seat
  whose turn the step belongs to.
- <a id="PhaseOrStepModified.main-phase-step"></a>**PhaseOrStepModified.main-phase-step.**
  A row whose `phase` is `2` or `4` `MUST` carry `step` `0`.
- <a id="PhaseOrStepModified.step-order"></a>**PhaseOrStepModified.step-order.**
  Within one turn, the rows in a GSM `MUST` appear in the order the game
  entered the phases and steps.
