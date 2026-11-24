---
summary: "SelectReplacementReq: choose which of several replacement effects applies first; replacement rows, their identities, whole-row SelectReplacementResp and lifecycle."
entity: prompt/SelectReplacementReq
---
# SelectReplacementReq

`SelectReplacementReq` asks the affected player which of several replacement
effects that apply to the same event to apply first (CR 616.1). For example,
discarding two madness cards at once offers both cards' exile replacements.
The answer is one of the offered rows, returned whole.

## Request

| Field | Role |
|---|---|
| `systemSeatIds` | The affected player. |
| `msgId` | Echoed by the response as `respId`. |
| `gameStateId` | The GSM the choice is made against. |
| `prompt` | `promptId=74`, with no parameters. |
| `allowCancel`, `allowUndo` | `AllowCancel_No` with `allowUndo=true`. |
| `selectReplacementReq.replacements` | One row per applicable replacement effect. |

| Row field | Identity |
|---|---|
| `objectInstance` | The object that has the replacement ability. |
| `uniqueAbilityId` | That ability on that object. |
| `abilityGrpId` | The ability definition. |
| `affectedObject` | The object whose event is being replaced. |
| `replacementEffectId` | The row, unique within the request. |

These are five different identities and none stands in for another.
`isOptional`, `replacementsType`, `gameObjectSelections` and
`conferringObjectZcid` can also appear on a request or row.

## Response

The client answers with `ClientMessageType_SelectReplacementResp`, correlated
as in [the message stream](../stream/messages.md#Messages.resp-correlates).

| Field | Role |
|---|---|
| `selectReplacementResp.replacement` | The chosen row, with every field exactly as the request sent it. |

## Lifecycle

1. A GSM publishes the state before either replacement applies.
2. `SelectReplacementReq` follows in the same batch, as in
   [the message stream](../stream/messages.md).
3. The client answers.
4. The next GSMs apply the chosen replacement, then any other replacement
   that still applies. Later choices, such as whether to cast a card exiled
   with madness, are separate prompts.

## Contract

- <a id="SelectReplacementReq.prompt"></a>**SelectReplacementReq.prompt.**
  *(provisional)* The outer prompt `MUST` be `promptId=74` with no parameters,
  and the request `MUST` carry `allowCancel=AllowCancel_No` and
  `allowUndo=true`.
- <a id="SelectReplacementReq.effect-ids"></a>**SelectReplacementReq.effect-ids.**
  Every row `MUST` carry a `replacementEffectId` that no other row of the
  request carries.
- <a id="SelectReplacementReq.resp-whole-row"></a>**SelectReplacementReq.resp-whole-row.**
  The server `MUST` apply the row equal in every field to the response's
  `replacement`, and `MUST NOT` apply a response that equals no offered row.

## Open points

- What do `affectedObject` and `objectInstance` hold when the replacement
  belongs to a different object from the one affected?
- When are `isOptional`, `replacementsType`, `gameObjectSelections` and
  `conferringObjectZcid` set?
- What does `UndoReq` do against this request?
