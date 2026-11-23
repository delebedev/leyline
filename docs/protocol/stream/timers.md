---
summary: "Match timers on the GRE stream: per-seat timer sets, Timer fields, timer types and behaviors, TimerStateMessage and TimeoutMessage."
read_when:
  - "publishing timers in a GameStateMessage or a TimerStateMessage"
  - "charging a timeout or showing the rope"
entity: stream/Timers
---
# Timers

## Meaning

Arena represents the rope and the timeout policy as a timer set per seat,
plus two side-channel messages. A `TimeoutMessage` reports a charged timeout
and the seat's new count; it is not a notification that the carried timer
reached zero.

## Surfaces

| Message | Role | Timer payload |
|---|---|---|
| `GameStateMessage` | `timers` carries the current timer pool; `players[].timerIds` assigns timers to seats. | Full `TimerInfo` rows, including elapsed and running state. |
| `TimerStateMessage` | Replaces the timer set of the seat named by `seatId`, during setup or reconfiguration. | Timer definitions, normally without elapsed state. |
| `TimeoutMessage` | Reports a charged timeout and the seat's new count. | One timer snapshot plus `seatId` and `timeoutCount`. |

`GameStateMessage.gameInfo` carries the match policy: `maxTimeoutCount`, the
number of charged timeouts before the policy ends the game; `maxPipCount`,
the warning pips available to the client; and `timeoutDurationSec`, the base
timeout duration.

## Seat ownership

Each player lists its timers in `players[].timerIds`. Each seat owns its own
run of timer IDs, so the two seats never share one. A timer's role comes from
its `type`, not from its number.

## Timer fields

```json
{
  "timerId": 9,
  "type": "TimerType_ActivePlayer",
  "durationSec": 61,
  "elapsedSec": 12,
  "elapsedMs": 12345,
  "running": true,
  "behavior": "TimerBehavior_TakeControl",
  "warningThresholdSec": 30
}
```

| Field | Role |
|---|---|
| `timerId` | Identity referenced by a player's `timerIds`. |
| `type` | The timer's gameplay role. |
| `durationSec` | Configured duration of this timer. |
| `elapsedSec`, `elapsedMs` | Elapsed time, on snapshots. |
| `running` | True while the timer advances; absent when it is stopped. |
| `behavior` | What happens when the timer runs out. |
| `warningThresholdSec` | When the client may show the rope warning. |

## Types and behaviors

| Type | Role |
|---|---|
| `Prologue` | Setup or pre-turn window. |
| `ActivePlayer` | Priority window of the active player. |
| `NonActivePlayer` | Response window of the non-active player. |
| `Inactivity` | Longer idle-protection timer. |
| `Delay` | Short animation or effect gate. |
| `Epilogue` | End-of-turn window. |
| `MatchClock` | Match clock, when the format has one. |

| Behavior | Role |
|---|---|
| `TakeControl` | The server takes the default priority action. |
| `Timeout` | Charges a timeout under the match policy. |
| `StartDelayedTimer` | Starts a follow-up timer. |

The other defined behaviors are operations the client applies locally.

## Timeout lifecycle

1. A GSM or `TimerStateMessage` establishes the seat's timer set.
2. Timer snapshots update elapsed and running state.
3. When the server charges a timeout, it sends a `TimeoutMessage` whose
   `timeoutCount` is the seat's new count, carrying the timer active at that
   moment.
4. The client shows the latest `timeoutCount` for that seat.

The carried timer may still have time left; `timeoutCount`, not elapsed
time, is the charged count.

## Contract

- <a id="Timers.seat-runs"></a>**Timers.seat-runs.** A timer ID `MUST` appear
  in the `timerIds` of at most one seat.
- <a id="Timers.state-message-seat"></a>**Timers.state-message-seat.** Every
  timer in a `TimerStateMessage` `MUST` be listed in the `timerIds` of the
  seat named by its `seatId`.
- <a id="Timers.server-behaviors"></a>**Timers.server-behaviors.** A timer
  the server sends `MUST` carry behavior `TakeControl`, `Timeout` or
  `StartDelayedTimer`.
- <a id="Timers.timeout-count"></a>**Timers.timeout-count.** Each
  `TimeoutMessage` for a seat `MUST` carry a `timeoutCount` greater than that
  of the previous `TimeoutMessage` for the same seat.

## Open points

- Which timers make up a seat's set in each format, and with what durations?
- How do warning pips, the per-action allowance and the carried timer
  combine into the charged-timeout policy?
- Does `TimerType` `Decision` have any role on the stream?
