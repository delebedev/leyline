---
summary: "DeclareAttackersReq: iterative attack declaration; attacker rows, damage recipients, attack alternatives, delta responses, echo and submission."
entity: prompt/DeclareAttackersReq
---
# DeclareAttackersReq

`DeclareAttackersReq` asks the deciding player which creatures the active
player attacks with and what each attacks (CR 508.1). The deciding player is
normally the active player. Declaration is an exchange: each
`DeclareAttackersResp` changes the declaration, the server answers with a
fresh request showing it, and `SubmitAttackersReq` commits it. The exchange
has the same form as [SelectTargetsReq](SelectTargetsReq.md) and
`DeclareBlockersReq`.

Enum values below are written without their type prefix: `Player` is
`DamageRecType_Player`.

## Request

| Field | Role |
|---|---|
| `systemSeatIds` | The deciding seat, also named by `turnInfo.decisionPlayer`. In every observed game it is the active player. |
| `msgId` | Echoed as `respId` by `DeclareAttackersResp` and `SubmitAttackersReq`. |
| `gameStateId` | The GSM the declaration belongs to. Each echo has a new one. |
| `prompt` | `promptId=6`, with no parameters. |
| `allowCancel`, `allowUndo` | Absent. |

| `declareAttackersReq` field | Role |
|---|---|
| `attackers` | One row per way a creature can attack, with the current selection. |
| `qualifiedAttackers` | The same rows without selections: what may legally attack. |
| `canSubmitAttackers` | Whether the current declaration may be submitted. |
| `hasRestrictions` | Present when attack restrictions apply. |
| `manaCost` | The cost of the declared attack. With no attack cost it holds one empty entry. |

| Attacker row field | Role |
|---|---|
| `attackerInstanceId` | The creature. |
| `legalDamageRecipients` | What it may attack: a player (`Player`, `playerSystemSeatId`) or a planeswalker (`PlanesWalker`, `planeswalkerInstanceId`). |
| `selectedDamageRecipient` | Present when the creature is declared, naming what it attacks. |
| `alternativeGrpId` | The attack alternative this row offers, such as `261` for Enlist. Absent on the ordinary attack row. |

## Attack alternatives

A creature that can attack in more than one way, such as a creature with
Enlist (CR 702.154), appears once per way: an ordinary row without
`alternativeGrpId` and a row with the alternative's ability ID. The
alternative belongs to the row, not to the recipient or the card. Once one of
the rows is selected, the request carries only that row for the creature.

## Candidate identity

`attackerInstanceId` is the creature's instance ID. A recipient is a seat ID
for a player or an instance ID for a planeswalker; its `type` says which.
When a creature has several rows, the row is identified by
`attackerInstanceId` together with `alternativeGrpId`.

## Response

`DeclareAttackersResp` changes the declaration:

| Field | Role |
|---|---|
| `gameStateId`, `respId` | The current request's `gameStateId` and `msgId`. |
| `declareAttackersResp.selectedAttackers` | Rows to change, normally one. A row with `selectedDamageRecipient` declares the creature or changes what it attacks; a row without it removes an already declared creature. The client may copy `legalDamageRecipients` and `alternativeGrpId` from the request. |
| `declareAttackersResp.autoDeclare`, `autoDeclareDamageRecipient` | Declare every creature that can attack, attacking the given recipient. |

`SubmitAttackersReq` commits the current declaration. It has no payload
beyond `gameStateId` and `respId`, which name the newest request. Submitting
with no creature declared declares no attack.

## Lifecycle

1. A GSM opens the declare-attackers step.
2. `DeclareAttackersReq` follows in the same batch, with that GSM's
   `gameStateId`.
3. The client sends `DeclareAttackersResp`.
4. The server publishes a new GSM and a fresh `DeclareAttackersReq` with that
   GSM's `gameStateId`, showing the whole declaration.
5. Steps 3 and 4 repeat until the client sends `SubmitAttackersReq` for the
   newest request.
6. The next GSMs commit the declaration and continue: attack triggers, an
   attack cost to pay, or priority in the declare-attackers step. The server
   can send an empty `SubmitAttackersResp` first.

## Contract

The response rule shared by every request applies here:
[Messages.resp-correlates](../stream/messages.md#Messages.resp-correlates).

- <a id="DeclareAttackersReq.follows-state"></a>**DeclareAttackersReq.follows-state.**
  The request `MUST` follow, in the same message batch, a GSM with the same
  `gameStateId`.
- <a id="DeclareAttackersReq.prompt"></a>**DeclareAttackersReq.prompt.** The
  outer prompt `MUST` be `promptId=6` with no parameters, and `allowCancel`
  `MUST` be absent.
- <a id="DeclareAttackersReq.recipients"></a>**DeclareAttackersReq.recipients.**
  Every attacker row `MUST` carry at least one `legalDamageRecipients` entry.
  A `Player` recipient `MUST` carry `playerSystemSeatId`, and a `PlanesWalker`
  recipient `MUST` carry `planeswalkerInstanceId`.
- <a id="DeclareAttackersReq.qualified"></a>**DeclareAttackersReq.qualified.**
  A row of `qualifiedAttackers` `MUST NOT` carry `selectedDamageRecipient`.
- <a id="DeclareAttackersReq.selected-single-row"></a>**DeclareAttackersReq.selected-single-row.**
  A declared creature `MUST` appear in exactly one row of `attackers`: the
  selected row, with `selectedDamageRecipient`.
- <a id="DeclareAttackersReq.alternative-rows"></a>**DeclareAttackersReq.alternative-rows.**
  *(provisional)* Grounded for Enlist: an undeclared creature that can attack
  with an alternative `MUST` have one row without `alternativeGrpId` and one
  row with `alternativeGrpId=261`.
- <a id="DeclareAttackersReq.empty-mana-cost"></a>**DeclareAttackersReq.empty-mana-cost.**
  *(provisional)* With no attack cost, `manaCost` `MUST` hold exactly one
  empty entry.
- <a id="DeclareAttackersReq.resp-select"></a>**DeclareAttackersReq.resp-select.**
  The server `MUST` treat a `selectedAttackers` row with
  `selectedDamageRecipient` as declaring that creature against that
  recipient, and a row without it as removing that creature from the
  declaration.
- <a id="DeclareAttackersReq.resp-auto"></a>**DeclareAttackersReq.resp-auto.**
  The server `MUST` treat `autoDeclare=true` as declaring every creature with
  an ordinary attack row against `autoDeclareDamageRecipient`.
- <a id="DeclareAttackersReq.echo"></a>**DeclareAttackersReq.echo.** After
  applying a `DeclareAttackersResp`, the server `MUST` publish a new GSM and
  then a new `DeclareAttackersReq` with that GSM's `gameStateId`, in which
  each declared creature's row carries its `selectedDamageRecipient`.
- <a id="DeclareAttackersReq.submit-commits"></a>**DeclareAttackersReq.submit-commits.**
  The server `MUST NOT` commit attackers before it accepts a
  `SubmitAttackersReq` for the newest request, and `MUST` accept one with no
  declared creature as declaring no attack.

## Open points

- Which `alternativeGrpId` values exist besides Enlist's `261`, for example
  for Exert, and do they share Enlist's row layout?
- When is `canSubmitAttackers` false, and what does the client do then?
- How are attack costs, such as a tax per attacker, carried in `manaCost`?
- When does `qualifiedAttackers` list a creature that `attackers` does not?
- Can a battle be a damage recipient, and with which `DamageRecType`?
- When another player controls the active player's turn (CR 723), is the
  request sent to that player's seat?
