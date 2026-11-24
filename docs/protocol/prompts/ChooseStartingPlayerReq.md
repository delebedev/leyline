---
summary: "ChooseStartingPlayerReq: the die-roll winner chooses who takes the first turn; deciding seat, candidate seats, ChooseStartingPlayerResp and game-start lifecycle."
entity: prompt/ChooseStartingPlayerReq
---
# ChooseStartingPlayerReq

`ChooseStartingPlayerReq` asks the player who won the die roll which player
takes the first turn (CR 103.1). It is the first decision of a game and comes
before the mulligan.

## Request

| Field | Role |
|---|---|
| `systemSeatIds` | The deciding seat: the die-roll winner. |
| `msgId` | Echoed by the response as `respId`. |
| `gameStateId` | The first GSM of the game, `1`. |
| `prompt`, `allowCancel` | Absent. |
| `chooseStartingPlayerReq.teamType` | `TeamType_Individual`. |
| `chooseStartingPlayerReq.systemSeatIds` | The seats that can be chosen, `[1, 2]`. |

The outer `systemSeatIds` names who decides; the inner one lists who can be
chosen. They are different lists.

## Response

The client answers with `ClientMessageType_ChooseStartingPlayerResp`,
correlated as in
[the message stream](../stream/messages.md#Messages.resp-correlates).

| `chooseStartingPlayerResp` field | Role |
|---|---|
| `systemSeatId` | The seat chosen to start. |
| `teamId` | That seat's team. With individual teams it equals `systemSeatId`. |
| `teamType` | `TeamType_Individual`. |

## Lifecycle

1. `DieRollResultsResp` reports each seat's roll.
2. The first GSM, `gameStateId=1`, has `pendingMessageCount=1` and marks the
   deciding player with `pendingMessageType=ClientMessageType_ChooseStartingPlayerResp`.
3. `ChooseStartingPlayerReq` follows.
4. The client can send `SetSettingsReq` before answering. It then sends
   `ChooseStartingPlayerResp`.
5. The next GSM starts the opening hands, and the mulligan follows.

## Contract

- <a id="ChooseStartingPlayerReq.decider"></a>**ChooseStartingPlayerReq.decider.**
  The request `MUST` be sent to exactly one seat, the one with the highest
  `rollValue` in `DieRollResultsResp`.
- <a id="ChooseStartingPlayerReq.candidates"></a>**ChooseStartingPlayerReq.candidates.**
  In a two-player game, `chooseStartingPlayerReq` `MUST` carry
  `teamType=TeamType_Individual` and `systemSeatIds=[1, 2]`, in that order.
- <a id="ChooseStartingPlayerReq.pending-type"></a>**ChooseStartingPlayerReq.pending-type.**
  The GSM before the request `MUST` set the deciding player's
  `pendingMessageType` to `ClientMessageType_ChooseStartingPlayerResp`, and
  no other player's.
- <a id="ChooseStartingPlayerReq.resp-candidate"></a>**ChooseStartingPlayerReq.resp-candidate.**
  The server `MUST NOT` apply a response whose `systemSeatId` is not among the
  request's candidates.

## Open points

- What follows when the decider chooses the other seat to start?
- What do games with more than two players or with teams send?
