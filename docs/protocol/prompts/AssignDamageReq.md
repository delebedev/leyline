---
summary: "AssignDamageReq: divide an attacker's combat damage among its blockers and, with trample, the defending player; assigner rows, slots, AssignDamageResp and rejection."
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
| `canIgnoreBlockers` | Present on some assigners; see Open points. |

| Slot field | Role |
|---|---|
| `instanceId` | A blocking creature, or the seat ID of the defending player. |
| `minDamage` | On a blocker: the damage that counts as lethal for it. |
| `maxDamage` | On the player: the most damage it can receive, which is `totalDamage` minus the blockers' `minDamage`. |
| `assignedDamage` | The proposed damage for this recipient. Absent means `0`. |

One request carries one assigner. The proposal usually assigns lethal damage
to the blocker slots in order until the damage runs out, and any rest to the
player slot. A player slot follows the blocker slots when the attacker has
trample.

## Candidate identity

Blocker slots name creatures by instance ID. A player slot names the defending
player by seat ID, never the deciding seat.

## Response

The client answers with `ClientMessageType_AssignDamageResp`, correlated as in
[the message stream](../stream/messages.md#Messages.resp-correlates).

| Field | Role |
|---|---|
| `assignDamageResp.assigners` | One row per request assigner, with its `instanceId`, `totalDamage` and the same slots in the same order, each with its chosen `assignedDamage`. The client may add `maxDamage` to blocker slots. |

A response the server cannot apply is answered with `IllegalRequest` carrying
`reason=FailureReason_InvalidDamageAssignment`, then a fresh GSM and
`AssignDamageReq`.

## Lifecycle

1. Blockers are committed and the combat damage step begins.
2. `AssignDamageReq` follows a GSM, as in
   [the message stream](../stream/messages.md), for each attacker whose
   division is a choice.
3. The client answers.
4. The server can send `AssignDamageConfirmation`. The next GSMs deal combat
   damage or ask about the next attacker.

## Contract

- <a id="AssignDamageReq.single-assigner"></a>**AssignDamageReq.single-assigner.**
  A request `MUST` carry exactly one `damageAssigners` row, whose
  `decisionPrompt` is `promptId=8` with a `CardId` parameter equal to the
  row's `instanceId`.
- <a id="AssignDamageReq.blocker-slots"></a>**AssignDamageReq.blocker-slots.**
  Every blocking creature of the attacker `MUST` have one slot carrying its
  lethal damage in `minDamage`.
- <a id="AssignDamageReq.trample-slot"></a>**AssignDamageReq.trample-slot.**
  When the attacker has trample, the request `MUST` include a slot for the
  defending player, named by seat ID, whose `maxDamage` is `totalDamage`
  minus the blockers' `minDamage`.
- <a id="AssignDamageReq.resp-slots"></a>**AssignDamageReq.resp-slots.** The
  server `MUST NOT` apply a response whose assigners or slots differ from the
  request's, or that assigns a negative amount.
- <a id="AssignDamageReq.resp-invalid"></a>**AssignDamageReq.resp-invalid.**
  *(provisional)* The server `MUST` answer a response it cannot apply with
  `IllegalRequest` carrying `reason=FailureReason_InvalidDamageAssignment`, and
  `MUST` then publish the request again.

## Open points

- What does `canIgnoreBlockers` mean? Most trample assigners do not carry it.
- A response that omits the assigner's `totalDamage` is rejected; which other
  omissions are?
- Some proposals assign more than `totalDamage` across the blockers, and the
  unchanged proposal is accepted. When, and what is dealt?
- When is the division asked rather than made by the server, and when does
  `AssignDamageConfirmation` follow?
- How are several attackers, or one creature blocking several attackers,
  handled?
