---
summary: "SelectTargetsReq: iterative target selection for a spell or ability; target groups, candidate identity, delta responses, echo, submission and cancellation."
entity: prompt/SelectTargetsReq
---
# SelectTargetsReq

`SelectTargetsReq` asks a player to choose targets for a spell or ability as
it is put on the stack (CR 601.2c, CR 602.2b, CR 603.3d). Targeting is an
exchange: each `SelectTargetsResp` changes the selection, the server answers
with a fresh request showing the whole selection so far, and a separate
`SubmitTargetsReq` commits it.

Enum values below are written without their type prefix: `Select` is
`SelectAction_Select`, `Abort` is `AllowCancel_Abort`.

## Families

| Family | Source | Initial `allowCancel` | Initial `allowUndo` | After a selection |
|---|---|---|---|---|
| Spell or activated ability | The spell, or the ability object on the stack | `Abort` | `true` | `Abort`, `allowUndo=true` |
| Copy of a spell, choosing new targets | The copy on the stack, whose `parentId` is the original | `AllowCancel_Continue` | Absent | Not characterised |
| Triggered ability | The triggered ability object on the stack | `No` | Absent | `Abort`, `allowUndo=true` |

A spell or activated ability can be abandoned while its targets are chosen:
`Abort` cancels the whole cast or activation. A triggered ability has no
parent action to abandon, so its first request is mandatory. That holds for
optional ("you may") triggers too: the player declines an optional trigger
through its own optional-action prompt, not by cancelling targeting.

## Request

| Field | Role |
|---|---|
| `systemSeatIds` | The player choosing targets. |
| `msgId` | Echoed as `respId` by `SelectTargetsResp` and `SubmitTargetsReq`. |
| `gameStateId` | The GSM the request belongs to. Each echo has a new one. |
| `prompt` | `promptId=10`, with no parameters. |
| `allowCancel`, `allowUndo` | The cancellation policy of the whole targeting step; see Families. |
| `informationalUseOnly` | Rare; see Open points. |

| `selectTargetsReq` field | Role |
|---|---|
| `sourceId` | The spell or ability object being put on the stack. |
| `abilityGrpId` | The spell or ability being targeted for. |
| `targets` | One target group per target requirement. |

| Target group field | Role |
|---|---|
| `targetIdx` | Identifies the requirement within the ability. Stable across echoes. It is not a position: a request can carry only group `2`, or groups `1` and `3`. |
| `targets` | The candidates, each `targetInstanceId` with a `legalAction`. |
| `minTargets`, `maxTargets` | Inclusive bounds for this group. An omitted value is zero. "Up to" requirements use `minTargets=0`. |
| `selectedTargets` | How many candidates of the group are selected. Omitted when zero. |
| `prompt` | Instruction for this group: its own prompt ID and a `CardId` parameter. The parameter names either the source object or the card it came from. |
| `targetingAbilityGrpId` | The ability that imposes this requirement. It can differ from the request's `abilityGrpId`. |
| `targetingPlayer` | The choosing seat. |
| `targetSourceZoneId` | The zone candidates come from, when the requirement names one. |

A candidate's `legalAction` is `Select` when choosing it would add it, and
`Unselect` when it is already chosen. `Select` candidates carry a
`highlight` (`Hot`, `Tepid` or `Cold`) as a presentation hint; `Unselect`
candidates carry none.

## Candidate identity

`targetInstanceId` is an object instance ID, or a seat ID (`1` or `2`) when
the candidate is a player. The same object can be a candidate in two groups,
so a candidate is identified by its `targetIdx` and `targetInstanceId`
together, never by the instance ID alone.

## Response

`SelectTargetsResp` changes the selection:

| Field | Role |
|---|---|
| `gameStateId`, `respId` | The current request's `gameStateId` and `msgId`. |
| `selectTargetsResp.target.targetIdx` | The group being changed. |
| `selectTargetsResp.target.targets` | The candidate entries, copied from that group of the request, including `legalAction` and `highlight`. |
| `systemSeatId` | May be present; it carries no additional meaning. |

`SubmitTargetsReq` commits the current selection. It has no payload beyond
`gameStateId` and `respId`, which name the newest request.

Instead of either, the client sends `CancelActionReq` with the request's
`gameStateId` and no `respId` to abandon the parent action when
`allowCancel=Abort`, or `UndoReq` when `allowUndo` is set.

## Lifecycle

1. A GSM with the request's `gameStateId` publishes the source on the stack,
   with a `PlayerSelectingTargets` annotation.
2. `SelectTargetsReq` follows in the same batch. A selection already made
   with the action, for example a target chosen while casting, appears as
   `Unselect` entries in this first request.
3. The client sends `SelectTargetsResp` for one group.
4. The server publishes a new GSM and a fresh `SelectTargetsReq` with that
   GSM's `gameStateId`. It carries every group again; the chosen candidates
   are `Unselect` entries and `selectedTargets` counts them.
5. Steps 3 and 4 repeat until the client sends `SubmitTargetsReq` for the
   newest request.
6. The next GSM carries a `PlayerSubmittedTargets` annotation, and the
   source continues into its next step, such as `PayCostsReq`, or onto the
   stack. Chosen targets of a spell or ability on the stack appear in
   persistent `TargetSpec` annotations. Divided amounts continue into
   `DistributionReq`.

The server answers a `SubmitTargetsReq` it does not accept, such as a second
submission for a request already committed, with `IllegalRequest`; the
rejected message appears in `illegalRequestMessage.invalidMessage`. The
server can also send an empty `SubmitTargetsResp` after a submission.

## Contract

The response rule shared by every request applies here:
[Messages.resp-correlates](../stream/messages.md#Messages.resp-correlates).

- <a id="SelectTargetsReq.follows-state"></a>**SelectTargetsReq.follows-state.**
  The request `MUST` follow, in the same message batch, a GSM with the same
  `gameStateId`.
- <a id="SelectTargetsReq.prompt"></a>**SelectTargetsReq.prompt.** The outer
  prompt `MUST` be `promptId=10` with no parameters.
- <a id="SelectTargetsReq.groups-together"></a>**SelectTargetsReq.groups-together.**
  When the targeting ability has several target requirements, every request
  for it, the first and each echo, `MUST` carry one group for each of those
  requirements.
- <a id="SelectTargetsReq.selected-count"></a>**SelectTargetsReq.selected-count.**
  In every group, `selectedTargets` `MUST` equal the number of candidates
  whose `legalAction` is `Unselect`.
- <a id="SelectTargetsReq.bounds"></a>**SelectTargetsReq.bounds.** In every
  group, `maxTargets` `MUST NOT` be less than `minTargets`.
- <a id="SelectTargetsReq.spell-cancel"></a>**SelectTargetsReq.spell-cancel.**
  A request for a spell being cast or an ability being activated `MUST`
  carry `allowCancel=Abort` and `allowUndo=true`, from the first request on.
- <a id="SelectTargetsReq.trigger-initial"></a>**SelectTargetsReq.trigger-initial.**
  The first request for a triggered ability `MUST` carry `allowCancel=No` and
  no `allowUndo`.
- <a id="SelectTargetsReq.trigger-echo"></a>**SelectTargetsReq.trigger-echo.**
  A request for a triggered ability that follows a `SelectTargetsResp` `MUST`
  carry `allowCancel=Abort` and `allowUndo=true`.
- <a id="SelectTargetsReq.cancel-keeps-bounds"></a>**SelectTargetsReq.cancel-keeps-bounds.**
  `allowCancel=Abort` `MUST NOT` lower a group's `minTargets`: a required
  target keeps `minTargets=1` when the parent action can be abandoned.
- <a id="SelectTargetsReq.resp-one-group"></a>**SelectTargetsReq.resp-one-group.**
  The server `MUST` accept a `SelectTargetsResp` that names one group by
  `targetIdx` and one or more of that group's candidate entries, and `MUST
  NOT` apply one that names a candidate the group does not offer.
- <a id="SelectTargetsReq.echo"></a>**SelectTargetsReq.echo.** After applying
  a `SelectTargetsResp`, the server `MUST` publish a new GSM and then a new
  `SelectTargetsReq` with that GSM's `gameStateId` and the same `sourceId`,
  in which each selected candidate appears with `legalAction=Unselect`.
- <a id="SelectTargetsReq.submit-commits"></a>**SelectTargetsReq.submit-commits.**
  The server `MUST NOT` commit targets before it accepts a `SubmitTargetsReq`
  for the newest request; a `SelectTargetsResp` alone never ends targeting.

## Open points

- How does the client remove a selected target: does it send the
  `Unselect` entry back in a `SelectTargetsResp`?
- What does the server do with `CancelActionReq` against the echoed request
  of a triggered ability, which carries `Abort`?
- What does `allowCancel=Continue` on a copy's first request allow, and do
  other sources use it?
- When does the GSM before a request omit `pendingMessageCount`?
- What does `informationalUseOnly` signal, and how should the client answer
  such a request?
- When does the server send `SubmitTargetsResp`, and what does it confirm?
