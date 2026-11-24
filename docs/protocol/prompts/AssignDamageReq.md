---
summary: "AssignDamageReq: divide an attacker's combat damage among its blockers and, with trample, the defending player; assigner rows, slots, AssignDamageResp and lifecycle."
entity: prompt/AssignDamageReq
---
# AssignDamageReq

`AssignDamageReq` asks the attacking player how an attacking creature divides
its combat damage among the creatures blocking it and, when it has trample,
the player it attacks (CR 510.1c–d). The request carries a proposed division;
the answer returns the division the player chose.

## Request

| Field | Role |
|---|---|
| `systemSeatIds` | The attacking player. |
| `msgId` | Echoed by the response as `respId`. |
| `gameStateId` | The GSM the division is made against. |
| `prompt`, `allowCancel` | Absent. Each assigner carries its own prompt. |

| `damageAssigners` row field | Role |
|---|---|
| `instanceId` | The attacking creature. |
| `totalDamage` | The combat damage it assigns. |
| `assignments` | One slot per recipient; see below. |
| `decisionPrompt` | `promptId=8` with one `CardId` parameter naming the attacker. |
| `canIgnoreBlockers` | Not set on ordinary or trample assigners; see Open points. |

| Slot field | Role |
|---|---|
| `instanceId` | A blocking creature, or the seat ID of the defending player. |
| `minDamage` | On a blocker: the damage that counts as lethal for it. |
| `maxDamage` | On the player: the most damage it can receive, which is `totalDamage` minus the blockers' `minDamage` when that is positive. |
| `assignedDamage` | The proposed damage for this recipient. Absent means `0`. |

One request carries one assigner. A player slot follows the blocker slots
when the attacker has trample. The proposal assigns lethal damage to the
blocker slots in order until the damage runs out, and any damage left over to
the last slot: the player with trample, otherwise the last blocker.

## Candidate identity

Blocker slots name creatures by instance ID. A player slot names the defending
player by seat ID, never the deciding seat.

## Response

The client answers with `ClientMessageType_AssignDamageResp`, correlated as in
[the message stream](../stream/messages.md#Messages.resp-correlates).

| Field | Role |
|---|---|
| `assignDamageResp.assigners` | One row per request assigner, with its `instanceId`, `totalDamage` and the same slots in the same order, each with its chosen `assignedDamage`. The client may add `maxDamage` to blocker slots. |

## Lifecycle

1. Blockers are committed and the combat damage step begins.
2. `AssignDamageReq` follows a GSM, as in
   [the message stream](../stream/messages.md), for each attacker whose
   division is a choice.
3. The client answers.
4. The next GSMs deal combat damage or ask about the next attacker.

## Contract

- <a id="AssignDamageReq.single-assigner"></a>**AssignDamageReq.single-assigner.**
  A request `MUST` carry exactly one `damageAssigners` row, whose
  `decisionPrompt` is `promptId=8` with a `CardId` parameter equal to the
  row's `instanceId`.
- <a id="AssignDamageReq.blocker-slots"></a>**AssignDamageReq.blocker-slots.**
  Every blocking creature of the attacker `MUST` have one slot carrying its
  lethal damage in `minDamage`.
- <a id="AssignDamageReq.trample-slot"></a>**AssignDamageReq.trample-slot.**
  When the attacker has trample and its `totalDamage` exceeds the sum of the
  blockers' `minDamage`, the request `MUST` include a slot for the defending
  player, named by seat ID, whose `maxDamage` is `totalDamage` minus that
  sum.
- <a id="AssignDamageReq.proposal-total"></a>**AssignDamageReq.proposal-total.**
  The proposed `assignedDamage` values of an assigner `MUST` add up to its
  `totalDamage`.
- <a id="AssignDamageReq.resp-slots"></a>**AssignDamageReq.resp-slots.** The
  server `MUST NOT` apply a response whose assigners or slots differ from the
  request's, or that assigns a negative amount.

## Open points

- When a trampling attacker's `totalDamage` does not exceed the sum of the
  blockers' `minDamage`, does the player slot carry `maxDamage` `0`, omit the
  field, or not appear?

- What does `canIgnoreBlockers` mean, and which attackers carry it?
- How does the server answer a response it cannot apply? Is
  `FailureReason_InvalidDamageAssignment` the `IllegalRequest` reason?
- When is the division asked rather than made by the server, and what is
  `AssignDamageConfirmation` for?
- How are several attackers, or one creature blocking several attackers,
  handled?
