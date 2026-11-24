---
summary: "IntermissionReq: the result of a finished game or match with its post-game options; result, intermission prompt, option rows and lifecycle."
entity: prompt/IntermissionReq
---
# IntermissionReq

`IntermissionReq` tells a player how a game or match ended and offers what
they can do next, such as reveal their hand. It is not a decision the game
waits on: the result needs no answer, and each option names its own response
message.

Enum values below are written without their type prefix: `WinLoss` is
`ResultType_WinLoss`, `Draw` is `ResultType_Draw`.

## Request

| Field | Role |
|---|---|
| `systemSeatIds` | The player told the result. |
| `msgId`, `gameStateId` | The message and the final GSM. |
| `prompt`, `allowCancel` | Absent. The result and each option carry their own prompt. |

| `intermissionReq` field | Role |
|---|---|
| `result` | `scope` (`MatchScope_Match`), `result` (`WinLoss` or `Draw`), `winningTeamId` for a win, and `reason`, such as `ResultReason_Game`, `ResultReason_Concede`, `ResultReason_Timeout` or `ResultReason_Force`. |
| `intermissionPrompt` | The result shown: `promptId=27` with a `WinningTeamId` parameter for a win, `promptId=28` with no parameters for a draw. |
| `options` | Zero or more post-game choices, each an `optionPrompt` and the `responseType` the client sends to take it. |

| Option | `optionPrompt.promptId` | `responseType` |
|---|---|---|
| Draw card | `30` | `ClientMessageType_DrawCardResp` |
| Reveal hand | `29` | `ClientMessageType_RevealHandResp` |

## Response

There is no `IntermissionResp`. To take an option the client sends that
option's `responseType`. Otherwise it sends nothing in reply.

## Lifecycle

1. The GSMs that end the game publish the result in `gameInfo`.
2. `IntermissionReq` follows the last of them, as in
   [the message stream](../stream/messages.md).
3. The client shows the result and can take an option.

## Contract

- <a id="IntermissionReq.no-outer-prompt"></a>**IntermissionReq.no-outer-prompt.**
  The request `MUST NOT` carry an outer `prompt` or `allowCancel`.
- <a id="IntermissionReq.win-result"></a>**IntermissionReq.win-result.** When
  `result.result` is `WinLoss`, the request `MUST` carry `winningTeamId` and an
  `intermissionPrompt` with `promptId=27` and one `WinningTeamId` parameter of
  `type=Number` equal to it.
- <a id="IntermissionReq.draw-result"></a>**IntermissionReq.draw-result.**
  *(provisional)* When `result.result` is `Draw`, the request `MUST NOT` carry
  `winningTeamId` and `MUST` carry an `intermissionPrompt` with `promptId=28`
  and no parameters.
- <a id="IntermissionReq.option-pairs"></a>**IntermissionReq.option-pairs.**
  An option with `promptId=30` `MUST` name `ClientMessageType_DrawCardResp`,
  and one with `promptId=29` `MUST` name `ClientMessageType_RevealHandResp`.

## Open points

- When are options offered, and why does a draw offer only the hand reveal?
- What does the server send in answer to `DrawCardResp` or
  `RevealHandResp`?
- What does a game-scoped result, between games of a match, look like?
