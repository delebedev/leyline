---
summary: "CastingTimeOptionsReq: choices made while a spell or ability is put on the stack; option families, Done row, affected object, responses and lifecycle."
entity: prompt/CastingTimeOptionsReq
---
# CastingTimeOptionsReq

`CastingTimeOptionsReq` asks for the choices a player makes while a spell or
ability is being put on the stack (CR 601.2b–c): which modes, whether to pay
an optional cost such as kicker, the value of X, how to pay a hybrid symbol,
and which branch of an alternative cost. Each choice is one row of
`castingTimeOptionReq`; the row's `castingTimeOptionType` selects its
sub-request and the shape of its answer.

Enum values below are written without their type prefix: `Modal` is
`CastingTimeOptionType_Modal`, `Abort` is `AllowCancel_Abort`.

## Families

| Family | Rows | Sub-request | Outer `promptId` |
|---|---|---|---|
| Modes | One `Modal` row | `modalReq` | `23` |
| Optional cost | One row of `Kicker`, `AdditionalCost`, `Bargain` or `Casualty`, then a `Done` row | `manaCost` on the cost row | `23` |
| Value of X | One `ChooseX` row | `numericInputReq` | `51` |
| Hybrid payment | One `ManaType` row per hybrid symbol | `selectManaTypeReq` | `23` |
| Alternative-cost branch | One `ChooseOrCost` row | `selectNReq` over prompt-bound labels | `23` |
| Object choice while casting | One `Selection` row | `selectNReq` over instance IDs | `23` |

The `Done` row of an optional cost stands for casting without that cost. It
carries the base cost in `manaCost` and no `ctoId` or `affectedId`.

## Request

| Field | Role |
|---|---|
| `systemSeatIds` | The player casting or activating. |
| `msgId` | Echoed by the response as `respId`. |
| `gameStateId` | The GSM the choice is made against. |
| `prompt` | `promptId=23` or `51`, with no parameters. |
| `allowCancel`, `allowUndo` | `Abort` with `allowUndo=true` while a cast or activation can still be abandoned. Some triggered abilities' mode choices carry neither; see Open points. |

| Row field | Role |
|---|---|
| `ctoId` | Identifies the row within the request. Absent on `Done`. |
| `castingTimeOptionType` | The family of the row. |
| `affectedId`, `affectorId` | Both the instance ID of the spell or ability being put on the stack. Absent on `Done`. |
| `grpId` | The card or ability definition. |
| `playerIdToPrompt` | The choosing seat. |
| `isRequired` | `true` on rows that must be answered: `Modal`, `ChooseX`, `ManaType`, `ChooseOrCost`, `Selection` and `Done`. Absent on optional cost rows. |
| `manaCost` | The cost this row adds or, on `Done`, the cost without it. |
| `modalReq` | `modalOptions` (one `grpId` per mode), `abilityGrpId`, `minSel`, `maxSel`, and `excludedOptions` for modes that cannot be chosen now. Per-mode costs appear in `modeCostSolutions`. |
| `numericInputReq` | `numericInputType=ChooseX`, `maxValue`, `stepSize` and `sourceId`. |
| `selectManaTypeReq` | `manaColors`, the ways to pay one symbol (for example `ManaColor_TwoGeneric` or a color), and `sourceId`. |
| `selectNReq` | A [SelectNReq](SelectNReq.md)-shaped selection. `ChooseOrCost` uses `IdType_PromptParameterIndex` with one index per branch. `Selection` uses `SelectionContext_Resolution` with `OptionContext_ActivateCast` over instance IDs. |

## Candidate identity

`affectedId` names the object being put on the stack: the card for a spell,
the `Ability` object for an activated or triggered ability, whose `parentId`
is the source card. It is the top of the stack in the GSM before the request.
Modes are identified by their ability `grpId`, not by position.

## Response

The client answers with `ClientMessageType_CastingTimeOptionsResp`,
correlated as in [the message stream](../stream/messages.md#Messages.resp-correlates).

| Family | `castingTimeOptionsResp` payload |
|---|---|
| Modes | `castingTimeOptionResp` with `Modal` and `chooseModalResp.grpIds`, the chosen modes. An empty `chooseModalResp` chooses none. |
| Optional cost | `castingTimeOptionResp` with the cost row's type and `ctoId` to pay the cost, or with `castingTimeOptionType=Done` alone to decline it. |
| Value of X | `castingTimeOptionResp` with `ChooseX`, `ctoId` and `numericInputResp.numericInputValue`. |
| Hybrid payment | `castingTimeOptionResps`: one entry per `ManaType` row, each with its `ctoId` and `selectManaTypeResp.manaColor`. |
| Alternative-cost branch, object choice | `castingTimeOptionResp` with the row's type, `ctoId` and `selectNResp.ids`. |

With `allowCancel=Abort` the client can instead send `CancelActionReq` to
abandon the cast or activation, or `UndoReq`.

## Lifecycle

1. The cast or activation puts the card or ability on the stack, published in
   a GSM.
2. `CastingTimeOptionsReq` follows in the same batch, as in
   [the message stream](../stream/messages.md).
3. The client answers.
4. The cast continues: targets ([SelectTargetsReq](SelectTargetsReq.md)),
   costs ([PayCostsReq](PayCostsReq.md)), or another
   `CastingTimeOptionsReq` for the next choice. A paid optional cost or a chosen
   X appears as a persistent `CastingTimeOption` annotation on
   the stack object; a declined optional cost leaves none.

## Contract

- <a id="CastingTimeOptionsReq.affected-object"></a>**CastingTimeOptionsReq.affected-object.**
  On every row except `Done`, `affectedId` and `affectorId` `MUST` both be the
  instance ID of the spell or ability being put on the stack, an object
  published on the stack before the request.
- <a id="CastingTimeOptionsReq.prompt"></a>**CastingTimeOptionsReq.prompt.**
  The outer prompt `MUST` be `promptId=51` when the request carries a
  `ChooseX` row and `promptId=23` otherwise, with no parameters.
- <a id="CastingTimeOptionsReq.optional-done"></a>**CastingTimeOptionsReq.optional-done.**
  A request whose only choice is an optional cost (`Kicker`,
  `AdditionalCost`, `Bargain` or `Casualty`) `MUST` end with one `Done` row that carries `isRequired=true`
  and no `ctoId` or `affectedId`, and the cost row `MUST NOT` carry
  `isRequired`.
- <a id="CastingTimeOptionsReq.cost-type"></a>**CastingTimeOptionsReq.cost-type.**
  An optional cost row `MUST` use the type of its mechanic: `Kicker` for
  kicker, `Bargain` for bargain, `Casualty` for casualty, and
  `AdditionalCost` for other optional additional costs.
- <a id="CastingTimeOptionsReq.modal-single"></a>**CastingTimeOptionsReq.modal-single.**
  A mode choice `MUST` be a request with exactly one row, of type `Modal`,
  with `isRequired=true` and one `modalOptions` entry per mode.
- <a id="CastingTimeOptionsReq.mana-type-rows"></a>**CastingTimeOptionsReq.mana-type-rows.**
  A hybrid payment `MUST` carry one `ManaType` row per hybrid symbol, each with
  its own `ctoId` and a `selectManaTypeReq` listing the payment choices for
  that symbol.
- <a id="CastingTimeOptionsReq.resp-modes"></a>**CastingTimeOptionsReq.resp-modes.**
  The server `MUST NOT` apply a `chooseModalResp` naming a `grpId` outside
  `modalOptions` or a number of modes outside `minSel`–`maxSel`.
- <a id="CastingTimeOptionsReq.resp-done"></a>**CastingTimeOptionsReq.resp-done.**
  The server `MUST` accept a `castingTimeOptionResp` of type `Done` as
  declining the optional cost when the request offers a `Done` row, and
  `MUST NOT` apply one when it does not.
- <a id="CastingTimeOptionsReq.resp-mana-types"></a>**CastingTimeOptionsReq.resp-mana-types.**
  The server `MUST` accept a hybrid payment answer that carries one
  `castingTimeOptionResps` entry per `ManaType` row, matched by `ctoId`, each
  naming one of that row's `manaColors`.
- <a id="CastingTimeOptionsReq.resp-cost-ctoid"></a>**CastingTimeOptionsReq.resp-cost-ctoid.**
  On every family except modes, the server `MUST` identify the answered row
  by the response's `ctoId`.

## Open points

- Which object, if any, does the `ctoId` in a mode choice's response name? It
  need not equal the `Modal` row's `ctoId`.
- When does a triggered ability's mode choice carry `allowCancel=Abort`, and
  when does it carry no cancellation policy?
- What does `CancelActionReq` do against a triggered ability's mode choice?
- How are several optional costs on one spell, such as kicker with a second
  kicker cost, offered: in one request or one request each?
- Which casts use the `Selection` family, and what do prompts `183` and
  `13201` ask?
