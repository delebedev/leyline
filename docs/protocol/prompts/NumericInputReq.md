---
summary: "NumericInputReq: ask a player for a number during resolution; bounds, input type, source card, NumericInputResp and lifecycle."
entity: prompt/NumericInputReq
---
# NumericInputReq

`NumericInputReq` asks a player for a number while a spell or ability
resolves, such as how much energy to pay. The request gives the bounds; the
answer is one number. The value of X chosen while casting a spell is asked
inside [CastingTimeOptionsReq](CastingTimeOptionsReq.md) instead.

## Request

| Field | Role |
|---|---|
| `systemSeatIds` | The player choosing. |
| `msgId` | Echoed by the response as `respId`. |
| `gameStateId` | The GSM the choice is made against. |
| `prompt` | The instruction for this choice, with one `CardId` parameter naming the source card. The prompt ID is specific to the effect. |
| `allowCancel` | `AllowCancel_No`. |

| `numericInputReq` field | Role |
|---|---|
| `numericInputType` | `NumericInputType_ChooseAnyAmount` for an amount chosen during resolution. |
| `minValue` | The smallest allowed value. Absent means `0`. |
| `maxValue` | The largest allowed value. |
| `stepSize` | `1`. |
| `sourceId` | The object making the choice: the spell on the stack, or an ID that names no published object; see Open points. |
| `disallowedValues`, `suggestedValues`, `disallowEven`, `disallowOdd` | Further constraints and hints. |

## Response

The client answers with `ClientMessageType_NumericInputResp`, correlated as in
[the message stream](../stream/messages.md#Messages.resp-correlates).

| Field | Role |
|---|---|
| `numericInputResp.numericInputValue` | The chosen number. An absent value is `0`. |

## Lifecycle

1. The resolving spell or ability reaches a choice of number, published in a
   GSM.
2. `NumericInputReq` follows in the same batch, as in
   [the message stream](../stream/messages.md).
3. The client answers.
4. The next GSMs apply the effect with that number.

## Contract

- <a id="NumericInputReq.no-cancel"></a>**NumericInputReq.no-cancel.** The
  request `MUST` carry `allowCancel=AllowCancel_No`.
- <a id="NumericInputReq.any-amount"></a>**NumericInputReq.any-amount.**
  *(provisional)* A number chosen while a spell or ability resolves `MUST` use
  `numericInputType=NumericInputType_ChooseAnyAmount`.
- <a id="NumericInputReq.source-card"></a>**NumericInputReq.source-card.**
  The outer prompt `MUST` carry exactly one parameter, `parameterName=CardId`
  of `type=Number`, naming a card.
- <a id="NumericInputReq.resp-bounds"></a>**NumericInputReq.resp-bounds.** The
  server `MUST NOT` apply a value below `minValue` or above `maxValue`.

## Open points

- What does `sourceId` name when it is not a published object, as on some
  saga and artifact abilities?
- Which other `NumericInputType` values are sent, and for what?
- When are `disallowedValues`, `suggestedValues`, `disallowEven` and
  `disallowOdd` set?
