---
summary: "DeclareBlockersReq: iterative block declaration; blocker rows, block warnings, delta responses merged into the accumulated declaration, echo and submission."
entity: prompt/DeclareBlockersReq
---
# DeclareBlockersReq

`DeclareBlockersReq` asks the defending player which creatures block and
which attacker each blocks (CR 509.1). Declaration is an exchange: each
`DeclareBlockersResp` changes some blockers, the server answers with a fresh
request showing the whole declaration, and `SubmitBlockersReq` commits it.
It has the form of [DeclareAttackersReq](DeclareAttackersReq.md), with a
blocker-to-attacker map as the accumulated state.

## Request

| Field | Role |
|---|---|
| `systemSeatIds` | The defending player. |
| `msgId` | Echoed as `respId` by `DeclareBlockersResp` and `SubmitBlockersReq`. |
| `gameStateId` | The GSM the declaration belongs to. Each echo has a new one. |
| `prompt` | `promptId=7`, with no parameters. |
| `allowCancel`, `allowUndo` | Absent. |

| `declareBlockersReq` field | Role |
|---|---|
| `blockers` | One row per creature that can block. |
| `hasRestrictions`, `hasRequirements` | Present when blocking restrictions or requirements apply, such as an attacker that can't be blocked except by two or more creatures. |
| `blockWarnings` | Problems with the current declaration, each naming an attacker (`instanceId`), a `type` such as `BlockWarningType_InsufficientBlockers`, and a `warningPromptId` such as `117`. |
| `manaCost` | The cost of the declared blocks. With no blocking cost it holds one empty entry. |

| Blocker row field | Role |
|---|---|
| `blockerInstanceId` | The creature. |
| `attackerInstanceIds` | On an unassigned row: the attackers it can legally block. |
| `selectedAttackerInstanceIds` | On an assigned row: the attacker it blocks. An assigned row carries no `attackerInstanceIds`. |
| `maxAttackers` | How many attackers it may block; `1` for ordinary creatures. |
| `mustBlock` | Present when the creature is required to block. |

## Candidate identity

Blockers and attackers are both creature instance IDs. A row is identified by
`blockerInstanceId`; the attackers it names are identified by their own
instance IDs.

## Response

`DeclareBlockersResp` changes the declaration:

| Field | Role |
|---|---|
| `gameStateId`, `respId` | The current request's `gameStateId` and `msgId`. |
| `declareBlockersResp.selectedBlockers` | Rows to change. A row with `selectedAttackerInstanceIds` assigns that blocker; a row without it, or with an empty list, removes its assignment. The client may copy other fields from the request row. |

The response is a delta. It can carry one row or several, and blockers it does
not mention keep their current assignment.

`SubmitBlockersReq` commits the current declaration. It has no payload beyond
`gameStateId` and `respId`, which name the newest request. Submitting with no
assigned blocker declares no blocks when that is legal, that is, when no
blocking requirement can be obeyed (CR 509.1c).

## Lifecycle

1. A GSM opens the declare-blockers step.
2. `DeclareBlockersReq` follows in the same batch, as in
   [the message stream](../stream/messages.md).
3. The client sends `DeclareBlockersResp`.
4. The server merges it into the declaration and publishes a new GSM and a
   fresh `DeclareBlockersReq` with that GSM's `gameStateId`, showing the whole
   declaration and any `blockWarnings` it raises.
5. Steps 3 and 4 repeat until the client sends `SubmitBlockersReq` for the
   newest request.
6. The next GSMs commit the blocks and continue to blocker ordering, triggers
   or priority. The server can send an empty `SubmitBlockersResp` first.

## Contract

- <a id="DeclareBlockersReq.prompt"></a>**DeclareBlockersReq.prompt.** The
  outer prompt `MUST` be `promptId=7` with no parameters, and `allowCancel`
  `MUST` be absent.
- <a id="DeclareBlockersReq.row-shape"></a>**DeclareBlockersReq.row-shape.**
  An unassigned blocker row `MUST` carry `attackerInstanceIds` and no
  `selectedAttackerInstanceIds`. An assigned row `MUST` carry
  `selectedAttackerInstanceIds` and no `attackerInstanceIds`.
- <a id="DeclareBlockersReq.max-attackers"></a>**DeclareBlockersReq.max-attackers.**
  Every blocker row `MUST` carry `maxAttackers`.
- <a id="DeclareBlockersReq.empty-mana-cost"></a>**DeclareBlockersReq.empty-mana-cost.**
  *(provisional)* With no blocking cost, `manaCost` `MUST` hold exactly one
  empty entry.
- <a id="DeclareBlockersReq.resp-delta"></a>**DeclareBlockersReq.resp-delta.**
  The server `MUST` apply a `DeclareBlockersResp` as a change to the current
  declaration: each listed row with attackers assigns that blocker, each
  listed row without attackers removes its assignment, and every blocker not
  listed keeps its assignment.
- <a id="DeclareBlockersReq.resp-legal"></a>**DeclareBlockersReq.resp-legal.**
  The server `MUST NOT` apply a row that assigns a blocker to an attacker
  outside that blocker's legal attackers.
- <a id="DeclareBlockersReq.echo"></a>**DeclareBlockersReq.echo.** After
  applying a `DeclareBlockersResp`, the server `MUST` publish a new GSM and
  then a new `DeclareBlockersReq` with that GSM's `gameStateId`, in which
  every assigned blocker's row carries its `selectedAttackerInstanceIds`.
- <a id="DeclareBlockersReq.submit-commits"></a>**DeclareBlockersReq.submit-commits.**
  The server `MUST NOT` commit blocks before it accepts a `SubmitBlockersReq`
  for the newest request. When declaring no blockers is legal, because no
  blocking requirement could be obeyed (CR 509.1c), it `MUST` accept one with
  no assigned blocker as declaring no blocks.

## Open points

- When is a `BlockWarningType_InsufficientBlockers` warning raised: only when
  an attacker has some but too few blockers, or also when it has none?
- Which other `BlockWarningType` values exist, and does a warning stop
  `SubmitBlockersReq` from being accepted?
- How is a creature that can block several attackers offered, and what does
  `maxAttackers` above `1` look like in a response?
- When is `mustBlock` set, and does it change which responses are accepted?
- How are blocking costs carried in `manaCost`?
