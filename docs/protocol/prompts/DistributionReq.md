---
summary: "DistributionReq: divide an amount of damage or counters among chosen targets; bounds, DistributionResp rows, TargetSpec distributions and lifecycle."
entity: prompt/DistributionReq
---
# DistributionReq

`DistributionReq` asks how a spell or ability divides an amount, such as
damage or +1/+1 counters, among the targets just chosen for it (CR 601.2d).
It follows target selection and does not choose targets itself.

## Request

| Field | Role |
|---|---|
| `systemSeatIds` | The player dividing the amount. |
| `msgId` | Echoed by the response as `respId`. |
| `gameStateId` | The GSM the division is made against. |
| `prompt` | `promptId=2234` to divide damage, `4051` to divide counters, with one `CardId` parameter naming the source card. |
| `allowCancel`, `allowUndo` | `AllowCancel_Abort` with `allowUndo=true`: the cast or activation can still be abandoned. |

| `distributionReq` field | Role |
|---|---|
| `targetIds` | The chosen targets: card instance IDs, or a seat ID for a player. |
| `validSelectedTargetIds` | The chosen targets that are still legal. |
| `minAmount`, `maxAmount` | Bounds on the total divided. Equal when the amount is fixed. |
| `minPerTarget` | The least each target receives, normally `1`. |
| `sourceId` | The spell or ability on the stack. |

The `CardId` parameter and `sourceId` follow the rule of
[OptionalActionMessage](OptionalActionMessage.md#candidate-identity):
`sourceId` is the ability on the stack for an ability and the card for a
spell, and the parameter names the card.

## Response

The client answers with `ClientMessageType_DistributionResp`, correlated as in
[the message stream](../stream/messages.md#Messages.resp-correlates).

| Field | Role |
|---|---|
| `distributionResp.distributions` | One row per target: `instanceId` and its `amount`. |

## Lifecycle

1. The client submits targets for the spell or ability with
   [SelectTargetsReq](SelectTargetsReq.md).
2. A GSM publishes the targets in a `TargetSpec` annotation, and
   `DistributionReq` follows in the same batch, as in
   [the message stream](../stream/messages.md).
3. The client answers.
4. The next GSM republishes the same `TargetSpec` with a `distributions`
   detail: one amount per target, in the order of its `affectedIds`. The cast
   or activation then continues.

## Contract

- <a id="DistributionReq.prompt"></a>**DistributionReq.prompt.** The outer
  prompt `MUST` be `promptId=2234` when dividing damage and `promptId=4051`
  when dividing counters, with one `CardId` parameter naming the source card.
- <a id="DistributionReq.targets"></a>**DistributionReq.targets.**
  `targetIds` `MUST` be exactly the targets chosen for the spell or ability,
  and `validSelectedTargetIds` `MUST` be a subset of them.
- <a id="DistributionReq.resp-bounds"></a>**DistributionReq.resp-bounds.** The
  server `MUST NOT` apply a response that names an ID outside `targetIds`,
  gives a target less than `minPerTarget`, or whose total falls outside
  `minAmount`–`maxAmount`.
- <a id="DistributionReq.target-spec"></a>**DistributionReq.target-spec.**
  After applying the response, the server `MUST` republish the targets'
  `TargetSpec` annotation under the same ID with a `distributions` detail
  listing each target's amount in the order of its `affectedIds`.

## Open points

- Which prompt IDs do other divided amounts, such as life or mana, use?
- When do `minAmount` and `maxAmount` differ?
- What happens when a target becomes illegal before the division, and does
  `validSelectedTargetIds` then omit it?
- What do `CancelActionReq` and `UndoReq` do against this request?
