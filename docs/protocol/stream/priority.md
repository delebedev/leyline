---
summary: "Priority on the GRE stream: rules priority versus visible decisions, and the settings exchange that controls automatic passing."
read_when:
  - "publishing or suppressing an ActionsAvailableReq"
  - "handling SetSettingsReq, SetSettingsResp or PerformActionResp.autoPassPriority"
entity: stream/Priority
---
# Priority

Priority is permission to act, not a requirement to ask the player. The rules
keep every priority window (CR 117.3, CR 117.4) while automatic passing
removes the ones that need no decision. No player receives priority during
the untap step, and the cleanup step normally has none (CR 117.3a, CR 514.3).

Leyline's own policy for which windows become visible is described in
[Priority flow](../../priority-flow.md). This page states only what the stream
carries.

## Requests and state

`turnInfo.priorityPlayer` names the player who holds rules priority. A
visible decision is an `ActionsAvailableReq` addressed to that player. The
GSM action rail (see [messages](messages.md#action-rails)) is board state and
says nothing about whether a decision is pending. A change of
`priorityPlayer`, or an action row, does not by itself mean the client
stops.

An ordinary spell can resolve, and play can pass through the following
phases and into the next turn, without another `ActionsAvailableReq`.

## Settings

The client controls automatic passing through settings. `SetSettingsReq`
carries a change; `SetSettingsResp` returns the accumulated settings after
the change.

| Field | Role |
|---|---|
| `stops` | Persistent phase and step stops, each scoped to the player's team or opponents. |
| `transientStops` | Stops toggled for a single occurrence, scoped the same way. |
| `autoPassOption` | The current priority-control option. |
| `defaultAutoPassOption` | The option restored when the current one clears. |
| `stackAutoPassOption` | A separate yield for the current stack. |
| `smartStopsSetting` | Whether the server adds stops it judges meaningful. |
| `PerformActionResp.autoPassPriority` | A per-action priority-control value sent with an action. |

Usual exchanges:

- The opening settings use `ResolveMyStackEffects` for both the current and
  the default option. A later response can return `Clear` as the current
  option while keeping that default.
- Full control is `autoPassOption=FullControl`; switching it off restores
  `ResolveMyStackEffects`.
- Passing the turn sends `autoPassOption=UnlessOpponentAction` with
  `SetSettingsReq.turnNumber`, followed by a separate `Pass` action. Required
  choices still interrupt the yield. At the turn boundary the current option
  returns to `Clear`.
- Resolving the stack sends `stackAutoPassOption=ResolveAll` followed by a
  separate `Pass`. A later settings response clears the stack option.

## Contract

- <a id="Priority.request-to-holder"></a>**Priority.request-to-holder.** An
  `ActionsAvailableReq` `MUST` be addressed to the seat named by
  `turnInfo.priorityPlayer` in the GSM it follows.
- <a id="Priority.no-untap-request"></a>**Priority.no-untap-request.** An
  `ActionsAvailableReq` `MUST NOT` follow a GSM whose `turnInfo.step` is
  `Untap`.
- <a id="Priority.settings-accumulated"></a>**Priority.settings-accumulated.**
  `SetSettingsResp.settings` `MUST` carry the seat's accumulated settings
  after the change, not only the fields the request changed.
- <a id="Priority.yield-turn"></a>**Priority.yield-turn.** *(provisional)*
  Grounded for passing the turn: `autoPassOption=UnlessOpponentAction` applies
  to the turn named by `SetSettingsReq.turnNumber`, and the next
  `SetSettingsResp` after that turn ends `MUST` carry `autoPassOption=Clear`.

## Open points

- When does a transient stop expire, and does it survive a turn in which its
  step does not occur?
- How long does `PerformActionResp.autoPassPriority=No` hold, and does it
  persist across unrelated responses?
- Does a new opponent action end an `UnlessOpponentAction` yield or a
  `ResolveAll` stack yield, and does a trigger of the yielding player's own
  end it?
- Which stops does `smartStopsSetting` add?
