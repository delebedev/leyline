---
summary: "ActionsAvailableReq: priority prompt; action sets, prompt regimes, action identity, PerformActionResp and lifecycle."
entity: prompt/ActionsAvailableReq
---
# ActionsAvailableReq

`ActionsAvailableReq` gives the priority player the actions they may take now:
play a land, cast a spell, activate an ability, use a special action, or pass.
It is both the decision and the client's model of what each object can do: the
same request lists visible actions that cannot be taken yet, so the client can
show them as unavailable instead of hiding them.

Enum values below are written without their type prefix: `Pass` is
`ActionType_Pass`, `No` is `AllowCancel_No`.

## Families

| Family | Outer `promptId` | Prompt parameters | `allowCancel` | Offered actions |
|---|---|---|---|---|
| Priority | `2` | None | Absent | Every legal action of the player, plus `Pass` |
| Cast-permission window | Card- or mechanic-specific, never `2` | One `CardId` number | `No` | The permitted `Cast` actions, plus `Pass` |

A cast-permission window asks whether the player casts a card an effect has
just allowed them to cast, for example a card exiled by madness, cascade or
discover. Its prompt ID selects the instruction for that permission; the
`Cast` actions and `Pass` are the whole decision.

## Request

| Field | Role |
|---|---|
| `systemSeatIds` | The priority player. |
| `msgId` | Echoed by the response as `respId`. |
| `gameStateId` | The GSM the actions were computed against. |
| `prompt` | `promptId=2` for priority; a permission-specific prompt with a `CardId` parameter for a cast-permission window. |
| `allowCancel` | Absent on ordinary priority; `No` on a cast-permission window. |
| `allowUndo` | `true` when the client may take back its last action with `UndoReq`. |
| `actionsAvailableReq.actions` | Actions that may be submitted now. |
| `actionsAvailableReq.inactiveActions` | Actions an object exposes that cannot be taken now. Optional. |

Both lists use the `Action` message. The fields an action carries depend on
its `actionType`:

| `Action` field | Role |
|---|---|
| `actionType` | What the action does: `Play`, `Cast`, `Activate`, `Activate_Mana`, `Special`, `FloatMana`, `Pass` and their face- or mode-specific variants such as `CastAdventure`, `PlayMDFC` or `CastLeftRoom`. |
| `instanceId` | The object that plays, casts or activates. |
| `grpId`, `facetId` | The card definition and face being played or cast. |
| `abilityGrpId` | The ability definition: the activated ability, or the casting ability on a cast. |
| `uniqueAbilityId` | The ability's per-object identity, from the object's `uniqueAbilities`. Two copies of one ability on an object share `abilityGrpId` and differ here. |
| `sourceId`, `alternativeGrpId`, `alternativeSourceZcid` | On a cast granted by another effect: the granting ability, the alternative casting ability, and the object the permission comes from. |
| `manaCost` | The cost requirement, one entry per color group with a `count`. |
| `targets`, `autoTapSolution`, `manaPaymentOptions`, `manaSelections`, `shouldStop`, `isBatchable` | Execution and presentation detail on active actions. |
| `disqualifyingSourceId` | On an inactive action: the object that currently forbids it, when one does. |

`Pass` carries only its `actionType`.

Inactive actions omit execution detail: they carry no `targets`, payment
options or auto-tap solution, but keep `manaCost` in the same shape as an
active action. An action moves between the two lists as the game changes:
mana becomes available, a permanent untaps, a land play is used, or a
restriction ends. When `disqualifyingSourceId` is absent the action is
usually unaffordable rather than forbidden.

The GSM before the request also carries an `actions` summary of reduced
action rows tagged with `seatId`. The request is the decision; the summary
does not replace it.

## Candidate identity

An offered action is identified by its `actionType` together with the
identities its type uses: `instanceId`, `grpId` and `facetId` for plays and
casts; `instanceId`, `abilityGrpId` and `uniqueAbilityId` for activations.
`uniqueAbilityId` and `abilityGrpId` are separate domains and are not
interchangeable. Inactive rows share the domain but are never candidates.

In a cast-permission window the `CardId` parameter names the object whose
permission the window offers. It is not always the instance ID of the card
being cast.

## Response

The client answers with `ClientMessageType_PerformActionResp`.

| Field | Role |
|---|---|
| `gameStateId` | The request's `gameStateId`. |
| `respId` | The request's `msgId`. |
| `performActionResp.actions` | Exactly one action, copied from the request's `actions`. |
| `performActionResp.autoPassPriority` | The client's auto-pass preference sent with the action: `Yes` or `No`. Optional. |
| `systemSeatId` | May be present; it carries no additional meaning. |

Declining to act is the `Pass` action, not an empty response. The client also
leaves an `ActionsAvailableReq` unanswered with `ConcedeReq`, or with
`UndoReq` when the request sets `allowUndo`.

## Lifecycle

1. A GSM publishes the priority state, with `pendingMessageCount` counting the
   messages that follow it in the batch.
2. Any `PromptReq` carried in the same batch, such as the starting-player or
   coin-flip notice, comes between that GSM and the request.
3. `ActionsAvailableReq` follows, with the GSM's `gameStateId`.
4. The client answers with one `PerformActionResp`.
5. The submitted action proceeds into its own lifecycle: a cast or activation
   continues into cost, target and payment prompts; a pass lets the game
   advance. The next priority request replaces both action lists; earlier
   lists are never updated in place.

## Contract

- <a id="ActionsAvailableReq.follows-state"></a>**ActionsAvailableReq.follows-state.**
  The request `MUST` follow, in the same message batch, a GSM with the same
  `gameStateId`.
- <a id="ActionsAvailableReq.pending-count"></a>**ActionsAvailableReq.pending-count.**
  That GSM's `pendingMessageCount` `MUST` equal the number of messages that
  follow it in the batch, counting each `PromptReq` between the GSM and the
  request as well as the request itself.
- <a id="ActionsAvailableReq.pass-offered"></a>**ActionsAvailableReq.pass-offered.**
  `actions` `MUST` contain exactly one `Pass` action, and that action `MUST`
  carry no field other than `actionType`.
- <a id="ActionsAvailableReq.priority-prompt"></a>**ActionsAvailableReq.priority-prompt.**
  On the priority family the outer prompt `MUST` be `promptId=2` with no
  parameters, and `allowCancel` `MUST` be absent.
- <a id="ActionsAvailableReq.permission-window"></a>**ActionsAvailableReq.permission-window.**
  On a cast-permission window the outer prompt `MUST` have a `promptId` other
  than `2` and exactly one parameter, `parameterName=CardId` of
  `type=Number`, and `allowCancel` `MUST` be `No`.
- <a id="ActionsAvailableReq.permission-actions"></a>**ActionsAvailableReq.permission-actions.**
  On a cast-permission window every action in `actions` `MUST` be a `Cast` or
  the `Pass` action.
- <a id="ActionsAvailableReq.inactive-no-execution"></a>**ActionsAvailableReq.inactive-no-execution.**
  An action in `inactiveActions` `MUST NOT` carry `targets`,
  `autoTapSolution`, `manaPaymentOptions` or `manaSelections`.
- <a id="ActionsAvailableReq.resp-correlates"></a>**ActionsAvailableReq.resp-correlates.**
  A `PerformActionResp` answers the request whose `msgId` equals its `respId`
  and whose `gameStateId` equals its own. A response for which either differs
  `MUST NOT` be applied to the request.
- <a id="ActionsAvailableReq.resp-single"></a>**ActionsAvailableReq.resp-single.**
  `performActionResp.actions` carries exactly one action. The server `MUST`
  resolve it against the request's `actions` by `actionType`, `instanceId`
  and `abilityGrpId`.
- <a id="ActionsAvailableReq.resp-offered"></a>**ActionsAvailableReq.resp-offered.**
  The server `MUST NOT` apply an action that matches no entry of the
  request's `actions`, including one that matches only an entry of
  `inactiveActions`.

## Open points

- What selects the prompt ID of a cast-permission window, and which object
  does its `CardId` parameter name for each permission?
- What does `autoPassPriority` ask the server to do after the submitted
  action, and does it change which later priority requests are sent?
- Which inactive actions carry `disqualifyingSourceId`, and which object does
  it name for each kind of restriction?
- When does an inactive action carry `shouldStop`?
- How does the GSM `actions` summary relate to the request's two lists?
