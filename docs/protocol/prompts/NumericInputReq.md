---
summary: "NumericInputReq: ask a player for a number during resolution; families (amount, X of a cost paid on resolution), bounds, source card, NumericInputResp and lifecycle."
entity: prompt/NumericInputReq
---
# NumericInputReq

`NumericInputReq` asks a player for a number while a spell or ability
resolves. The request gives the bounds; the answer is one number. The value of
X chosen while casting a spell is asked inside
[CastingTimeOptionsReq](CastingTimeOptionsReq.md) instead.

## Families

`numericInputType` separates two families. Both are sent while the source
ability resolves, after its `ResolutionStart`.

| Family | `numericInputType` | Prompt | `sourceId` | Bounds | Next |
|---|---|---|---|---|---|
| Amount chosen by an effect, such as how many counters or how much energy | `ChooseAnyAmount` | Specific to the effect | The resolving object, or an ID that names no published object | Effect-specific `maxValue`, often a `minValue` | The effect applies the number |
| X of a cost the player agreed to pay during resolution, such as "you may pay {X}" | `ChooseX` | `51` | The resolving ability | `maxValue=2147483647` | `PayCostsReq` for the chosen cost |

In the cost family the request follows the `OptionalActionMessage` that
accepts paying the cost, and its `CardId` prompt parameter names the card the
ability comes from rather than the ability.

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
| `numericInputType` | `NumericInputType_ChooseAnyAmount` or `NumericInputType_ChooseX`; see Families. |
| `minValue` | The smallest allowed value. Absent means `0`. |
| `maxValue` | The largest allowed value. |
| `stepSize` | `1`. |
| `sourceId` | The object making the choice; see Families and Open points. |
| `disallowedValues`, `suggestedValues`, `disallowEven`, `disallowOdd` | Further constraints and hints. |

## Response

The client answers with `ClientMessageType_NumericInputResp`, correlated as in
[the message stream](../stream/messages.md#Messages.resp-correlates).

| Field | Role |
|---|---|
| `numericInputResp.numericInputValue` | The chosen number. A choice of `0` is sent as an empty `numericInputResp` with this field absent. |

## Lifecycle

1. The resolving spell or ability reaches a choice of number, published in a
   GSM.
2. `NumericInputReq` follows in the same batch, as in
   [the message stream](../stream/messages.md).
3. The client answers.
4. The next GSMs apply the effect with that number. In the cost-X family the
   chosen cost is paid first, through `PayCostsReq`.

## Contract

- <a id="NumericInputReq.no-cancel"></a>**NumericInputReq.no-cancel.** The
  request `MUST` carry `allowCancel=AllowCancel_No`.
- <a id="NumericInputReq.effect-amount"></a>**NumericInputReq.effect-amount.**
  *(provisional)* A number chosen by a resolving effect, other than the X of
  a cost paid during resolution, `MUST` use
  `numericInputType=NumericInputType_ChooseAnyAmount`.
- <a id="NumericInputReq.cost-x"></a>**NumericInputReq.cost-x.**
  *(provisional)* The X of a cost paid during resolution `MUST` be asked with
  `numericInputType=NumericInputType_ChooseX`, `promptId=51` and `sourceId`
  naming the resolving ability.
- <a id="NumericInputReq.resp-zero"></a>**NumericInputReq.resp-zero.** The
  server `MUST` read a `NumericInputResp` whose `numericInputValue` is absent
  as the number `0`, not as a cancellation.
- <a id="NumericInputReq.source-card"></a>**NumericInputReq.source-card.**
  The outer prompt `MUST` carry exactly one parameter, `parameterName=CardId`
  of `type=Number`, naming a card.
- <a id="NumericInputReq.resp-bounds"></a>**NumericInputReq.resp-bounds.** The
  server `MUST NOT` apply a value below `minValue` or above `maxValue`.

## Open points

- What does `sourceId` name when it is not a published object, as on some
  saga and artifact abilities?
- Is `maxValue` always `2147483647` in the cost-X family, or bounded by what
  the player can pay?
- Do activated abilities and other costs paid during resolution use the
  cost-X family?
- Which other `NumericInputType` values are sent, and for what?
- When are `disallowedValues`, `suggestedValues`, `disallowEven` and
  `disallowOdd` set?
