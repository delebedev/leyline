---
summary: "SelectNReq: bounded selection prompt; families, request, candidate identity, response and lifecycle."
entity: prompt/SelectNReq
---
# SelectNReq

`SelectNReq` asks one seat to choose values from a bounded choice domain. The
same request type serves simultaneous-trigger ordering, discard, resolution
choices over objects or enum values, and mana color choices. The
`context` and `optionContext` pair identifies the family; `listType`,
`staticList` and `idType` identify what the offered values are.

Enum values below are written without their type prefix: `TriggeredAbility`
is `SelectionContext_TriggeredAbility`, `Static` is `SelectionListType_Static`.

## Families

| Family | `context` | `optionContext` | `listType` | Offered values |
|---|---|---|---|---|
| Trigger ordering | `TriggeredAbility` | `Stacking` | `Dynamic` | Pending ability instance IDs |
| Turn-based discard | `Discard` | `TurnBased` | `Dynamic` | Hand object instance IDs |
| Discard as a cost | `Discard` | `Payment` | `Static` | Hand object instance IDs |
| Resolution choice | `Resolution` | `Resolution` | `Dynamic`, `Static` or `StaticSubset` | Objects, enum values, definition IDs or prompt-bound values |
| Mana color choice | `ManaFromAbility` | `Resolution` | `Static` | `StaticList_Colors` values |

`listType=Static` does not by itself mean an enum domain: the cost-discard
family pairs it with object instance IDs. The enum-domain branches are the
ones that set `staticList`.

## Request

The outer GRE message carries the envelope; `selectNReq` carries the
selection.

| Field | Role |
|---|---|
| `systemSeatIds` | The chooser. |
| `msgId` | Echoed by the response as `respId`. |
| `gameStateId` | The GSM the decision is made against. |
| `prompt` | Instruction for the whole decision, with family- or card-specific parameters. Parameter shapes differ between families. |
| `allowCancel` | Absent, `No` or `Continue`. It describes the interaction affordance and never widens the selection bounds. |

| `selectNReq` field | Role |
|---|---|
| `minSel`, `maxSel` | Inclusive bounds on the number of selected values. Both are plain scalars; an omitted value is zero. |
| `context`, `optionContext` | Together identify the family. |
| `listType` | `Dynamic` (values computed for this decision), `Static` (a fixed list) or `StaticSubset` (allowed members of a fixed enum list). |
| `ids` | The offered values, in the domain named by `staticList` or `idType`. Not always instance IDs. |
| `idType` | How `ids` are read when no `staticList` is set: `InstanceId`, `CardGrpId`, `AbilityGrpId` or `PromptParameterIndex`. |
| `staticList` | The enum list that supplies the domain, for example `StaticList_Colors`. |
| `sourceId` | The object whose effect asks for the choice, on source-backed branches. |
| `prompt` | Labels the choice inside the selection. Separate from the outer prompt. |
| `unfilteredIds` | Every object the player sees for this choice; `ids` is the legal subset. |
| `validationType` | `NonRepeatable`: a value is selected at most once. |
| `minWeight`, `maxWeight` | Bounds on the summed weight of the selection. |

## Candidate identity

| Shape | Request | Response values |
|---|---|---|
| Objects | `idType=InstanceId`, object instance IDs in `ids` | Selected instance IDs |
| Complete enum list | `Static`, `staticList`, no `ids`, no `idType` | Selected enum values |
| Restricted enum list | `StaticSubset`, `staticList`, allowed enum values in `ids`, no `idType` | Selected enum values |
| Definitions | `Dynamic`, `idType=CardGrpId` or `AbilityGrpId` | Selected definition IDs |
| Prompt-bound values | `Dynamic`, `idType=PromptParameterIndex`, `ids` aligned with the inner prompt's parameters | Selected parameter indices |
| Filtered reveal | Every seen object in `unfilteredIds`, legal subset in `ids` | Selected legal instance IDs |

A filtered reveal lets the client show every looked-at or revealed card while
allowing only the legal ones to be picked. A color choice over card colors
uses `StaticList_CardColors` as a restricted list, which can include
colorless.

## Response

The client answers with `ClientMessageType_SelectNResp`.

| Field | Role |
|---|---|
| `gameStateId` | The request's `gameStateId`. |
| `respId` | The request's `msgId`. |
| `selectNResp.ids` | Selected values, in the request's domain. |
| `selectNResp.useArbitrary` | Asks for automatic ordering on the trigger-ordering family. |
| `systemSeatId` | May be present; it carries no additional meaning. |

An empty `selectNResp: {}` selects nothing. It answers an optional choice the
player declines, and a choice whose `ids` offers no legal candidate.

On the trigger-ordering family the client can ask the server to order the
abilities itself: `ids=[0]` with `useArbitrary=OrderingType_OrderArbitraryOnce`.
The `0` is a placeholder, not one of the offered ability IDs.

## Lifecycle

1. A GSM publishes the decision state with `pendingMessageCount` counting the
   request.
2. `SelectNReq` follows that GSM in the same message batch with the same
   `gameStateId`.
3. The client answers with a correlated `SelectNResp`.
4. The next GSM commits the outcome or advances to a follow-up prompt.

Family-specific steps:

- Trigger ordering: the abilities being ordered appear in the Pending zone in
  the GSM before the request.
- Turn-based discard: the chosen hand objects move to the graveyard in the GSM
  after the response.
- Resolution choices: private or revealed candidates are projected to the
  chooser before the request. A look-and-pick effect can continue into
  `OrderReq` for the cards not chosen.

## Contract

- <a id="SelectNReq.follows-state"></a>**SelectNReq.follows-state.** The
  request `MUST` follow, in the same message batch, a GSM with the same
  `gameStateId` whose `pendingMessageCount` counts it.
- <a id="SelectNReq.resp-correlates"></a>**SelectNReq.resp-correlates.** A
  `SelectNResp` answers the request whose `msgId` equals its `respId` and whose
  `gameStateId` equals its own. A response for which either differs `MUST NOT`
  be applied to the request.
- <a id="SelectNReq.trigger-order"></a>**SelectNReq.trigger-order.** On the
  trigger-ordering family the request `MUST` use `TriggeredAbility` with
  `Stacking`, a `Dynamic` list whose `ids` are the instance IDs of the
  abilities being ordered, and no `sourceId`. Those abilities `MUST` be in the
  Pending zone in the preceding GSM.
- <a id="SelectNReq.trigger-order-arbitrary"></a>**SelectNReq.trigger-order-arbitrary.**
  On the trigger-ordering family the server `MUST` accept `ids=[0]` with
  `useArbitrary=OrderingType_OrderArbitraryOnce` as a request to order the
  abilities automatically.
- <a id="SelectNReq.cleanup-discard"></a>**SelectNReq.cleanup-discard.** A
  turn-based discard `MUST` use `Discard` with `TurnBased`, a `Dynamic` list,
  `idType=InstanceId` and hand instance IDs in `ids`, with no `sourceId`,
  `minWeight` or `maxWeight`.
- <a id="SelectNReq.cost-discard"></a>**SelectNReq.cost-discard.**
  *(provisional)* A discard paid as a cost `MUST` use `Discard` with `Payment`, `listType=Static`,
  `idType=InstanceId` and hand instance IDs in `ids`, with no `staticList`.
- <a id="SelectNReq.enum-domain"></a>**SelectNReq.enum-domain.** When
  `staticList` is set, `idType` `MUST` be absent, and every value in `ids` and
  in `selectNResp.ids` `MUST` be an enum value of that list.
- <a id="SelectNReq.enum-complete"></a>**SelectNReq.enum-complete.** With
  `listType=Static` and `staticList` set, `ids` `MUST` be empty; every value
  of the list is offered.
- <a id="SelectNReq.enum-subset"></a>**SelectNReq.enum-subset.** With
  `listType=StaticSubset`, `ids` `MUST` list exactly the allowed enum values.
- <a id="SelectNReq.card-colors"></a>**SelectNReq.card-colors.**
  `StaticList_CardColors` values `MUST` be read as `0` colorless, `1` white,
  `2` blue, `3` black, `4` red and `5` green.
- <a id="SelectNReq.filtered-reveal"></a>**SelectNReq.filtered-reveal.** When
  `unfilteredIds` is present it `MUST` contain every object shown for the
  choice, and `ids` `MUST` be the subset of it that may legally be selected.
- <a id="SelectNReq.resp-domain"></a>**SelectNReq.resp-domain.** Except for
  the automatic trigger-order answer, every value in `selectNResp.ids` `MUST`
  be an offered value: a member of `ids`, or of the whole `staticList` when
  the list is complete. The server `MUST NOT` apply a response that selects
  anything else, or a number of values outside `minSel`–`maxSel`.
- <a id="SelectNReq.resp-empty-ok"></a>**SelectNReq.resp-empty-ok.** An empty
  `selectNResp` `MUST` be treated as a successful answer selecting nothing,
  not as a cancellation or an error, whenever selecting nothing is within the
  bounds.
- <a id="SelectNReq.weight-sentinels"></a>**SelectNReq.weight-sentinels.**
  *(provisional)* On resolution requests with no weight constraint, `minWeight` and `maxWeight` are the signed 32-bit minimum and
  maximum.
- <a id="SelectNReq.mana-color"></a>**SelectNReq.mana-color.**
  *(provisional)* A color choice made while a mana ability resolves uses
  `ManaFromAbility` with `Resolution`, `listType=Static` and
  `StaticList_Colors`.

## Open points

- What `SelectNResp` sequence orders triggers one at a time, as distinct from
  the automatic-order answer?
- How does a request to sacrifice permanents while an ability resolves, such
  as Annihilator's, lay out its outer and inner prompts, and does its
  `sourceId` name the resolving ability or the card it came from?
- Do restricted sacrifice prompts ("sacrifice a creature", edicts that let
  each player choose) share that layout?
- What roles do `weights`, `hotIds`, `zoneIds` and `shouldCancel` play, and on
  which families?
- Which mana-producing contexts besides a resolving mana ability use
  `ManaFromAbility`?
