---
summary: "PayCostsReq: cost payment prompt; mana payment, object-selection costs and counter gathering, their responses and cancellation."
entity: prompt/PayCostsReq
---
# PayCostsReq

`PayCostsReq` asks a player to pay a cost of a spell or ability that has
already begun (CR 601.2g–h, CR 602.2b). One request type carries three kinds
of payment: paying mana, paying a cost by choosing objects (sacrificing,
tapping, untapping, exiling or returning them), and removing counters from
several permanents at once. `payCostsReq` names the kind: a `manaCost` for
mana, an `effectCostReq` for the others.

Enum values below are written without their type prefix: `Select` is
`EffectCostType_Select`, `Abort` is `AllowCancel_Abort`.

## Families

| Family | Discriminator | Outer prompt | Response |
|---|---|---|---|
| Mana payment | `manaCost` present | `11` or `140`, one `Cost` parameter | `PerformAutoTapActionsResp`, or `PerformActionResp` for one payment step |
| Object-selection cost | `effectCostReq.effectCostType=Select` | Cost-specific, one `CardId` parameter | `EffectCostResp` with `costSelection` |
| Counter gathering | `effectCostReq.effectCostType=GatherCounters` | Cost-specific, one `CardId` parameter | `EffectCostResp` with `gatherResp` |

The prompt ID of an object-selection cost follows the instruction the player
reads, such as "sacrifice", "tap untapped creatures with total power N" or
"return an unblocked attacker", not the mechanic that asked for the cost.

## Request

| Field | Role |
|---|---|
| `systemSeatIds` | The paying player. |
| `msgId` | Echoed by the response as `respId`. |
| `gameStateId` | The GSM the payment is made against. |
| `prompt` | Mana: the remaining cost as a mana-symbol string in a `Cost` parameter of `type=NonLocalizedString`, for example `oB`. Other families: a `CardId` parameter naming the spell or ability whose cost is paid. |
| `allowCancel` | `Abort` when the player may abandon the spell or ability. `Continue` on some mana payments; see Open points. |
| `allowUndo` | `true` alongside `Abort`. |

### Mana payment

| `payCostsReq` field | Role |
|---|---|
| `manaCost` | The cost still to pay: one entry per color group, with `count` and the `objectId` being paid for. |
| `paymentActions.actions` | Ways to pay one part: `Activate_Mana` for a mana ability, `Make_Payment` for paying with an object directly, as convoke or improvise do. Each carries `manaPaymentOptions` describing the mana it would provide. |
| `paymentSelection` | Mana already in the pool that may be spent: a `SelectNReq`-shaped selection with `SelectionContext_ManaPool`, `OptionContext_Payment`, `Dynamic` and `IdType_ManaId`. |
| `autoTapActionsReq.autoTapSolutions` | Complete payments the server proposes, each a list of `autoTapActions`. Optional. |

### Object-selection cost

`effectCostReq.costSelection` is a `SelectNReq`-shaped selection over the
objects that can pay the cost. `paymentActions` is empty.

| `costSelection` field | Role |
|---|---|
| `context`, `optionContext` | `SelectionContext_NonMana_Payment` with `OptionContext_Payment`, which tells cost candidates from targets. |
| `listType`, `idType` | `Dynamic` and `IdType_InstanceId`. |
| `ids` | The objects that can pay the cost. |
| `weights` | One weight per entry of `ids`, in the same order. |
| `minSel`, `maxSel` | The bound the payment must meet; see below. |
| `minWeight`, `maxWeight` | The signed 32-bit minimum and maximum. |
| `validationType` | `NonRepeatable`. |

The selection takes one of two forms:

| Form | `weights` | `minSel` | `maxSel` | Paid when |
|---|---|---|---|---|
| Exact count, such as "sacrifice two creatures" | All `1` | The count | The count | The number of selected objects is the count. |
| Total weight, such as "tap creatures with total power 4" or collect evidence | The power, mana value or other measure of each object | The threshold | `2147483647` | The selected objects' weights add up to at least the threshold. |

### Counter gathering

| `effectCostReq.gatherReq` field | Role |
|---|---|
| `sources` | Each permanent that can give counters: `sourceId` and `maxAmount`. |
| `amountToGather` | The total number of counters to remove. |
| `destinationId` | The ability on the stack the counters pay for. |

The request does not repeat the counter type; the cost and the sources'
capacities establish it.

## Candidate identity

Object-selection `ids` and gathering `sourceId` values are object instance
IDs of permanents or cards that pay the cost. The `CardId` parameter and
`destinationId` name the spell or ability being paid for, which is on the
stack (or still pending), not the permanent it came from. Mana payment
actions are identified like priority actions, by `actionType`, `instanceId`
and `abilityGrpId`; pool mana is identified by `manaId`.

## Response

| Family | Response | Payload |
|---|---|---|
| Mana payment | `PerformAutoTapActionsResp` | `index` of the chosen entry of `autoTapSolutions`; omitted when `0`. |
| Mana payment | `PerformActionResp` | One action copied from `paymentActions`. |
| Object-selection cost | `EffectCostResp` | `costSelection.ids`: the chosen objects. Bounds and weights are not repeated. `effectCostType` may be present. |
| Counter gathering | `EffectCostResp` | `effectCostType=GatherCounters` and `gatherResp.gatherings`: `{instanceId, amount}` per source used. |

Every response carries `gameStateId` and `respId` naming the request.

With `allowCancel=Abort` the client can instead send `CancelActionReq` with
the request's `gameStateId` and no `respId`. The server abandons the spell or
ability and returns to priority. An empty or short payment is never a
cancellation.

## Lifecycle

1. A GSM publishes the spell or ability on the stack.
2. `PayCostsReq` follows in the same batch, with that GSM's `gameStateId`.
3. The client answers with the family's response, or cancels.
4. For a single mana payment step (`PerformActionResp`), the server applies
   it and sends a new GSM and a new `PayCostsReq` for the rest of the cost.
5. Otherwise the next GSM applies the whole payment: tapped or untapped
   permanents, objects moved to their new zones, removed counters or spent
   mana. The spell or ability then continues to its next step.

## Contract

- <a id="PayCostsReq.follows-state"></a>**PayCostsReq.follows-state.** The
  request `MUST` follow, in the same message batch, a GSM with the same
  `gameStateId`.
- <a id="PayCostsReq.mana-prompt"></a>**PayCostsReq.mana-prompt.** On the
  mana-payment family the outer prompt `MUST` carry exactly one parameter,
  `parameterName=Cost` of `type=NonLocalizedString`.
- <a id="PayCostsReq.mana-pool-selection"></a>**PayCostsReq.mana-pool-selection.**
  On the mana-payment family `paymentSelection` `MUST` use
  `SelectionContext_ManaPool`, `OptionContext_Payment`, a `Dynamic` list and
  `IdType_ManaId`.
- <a id="PayCostsReq.effect-prompt"></a>**PayCostsReq.effect-prompt.** On the
  object-selection and counter-gathering families the outer prompt `MUST`
  carry a `CardId` parameter naming the spell or ability being paid for.
- <a id="PayCostsReq.select-shape"></a>**PayCostsReq.select-shape.** On the
  object-selection family `costSelection` `MUST` use
  `SelectionContext_NonMana_Payment`, `OptionContext_Payment`, a `Dynamic`
  list, `IdType_InstanceId` and `NonRepeatable`, with one `weights` entry per
  entry of `ids`.
- <a id="PayCostsReq.select-exact"></a>**PayCostsReq.select-exact.** An
  exact-count cost `MUST` set every weight to `1` and `minSel` equal to
  `maxSel`, the required count.
- <a id="PayCostsReq.select-threshold"></a>**PayCostsReq.select-threshold.**
  A total-weight cost `MUST` carry the threshold in `minSel`, set `maxSel` to
  `2147483647`, and carry each candidate's measure in `weights`; `minWeight`
  and `maxWeight` stay the signed 32-bit extremes.
- <a id="PayCostsReq.select-resp"></a>**PayCostsReq.select-resp.** The server
  `MUST NOT` apply an `EffectCostResp` that selects an object outside `ids`,
  selects one twice, or does not meet the cost: the count on an exact-count
  cost, the summed weight on a total-weight cost.
- <a id="PayCostsReq.effect-cancel"></a>**PayCostsReq.effect-cancel.** On the
  object-selection and counter-gathering families the request `MUST` carry
  `allowCancel=Abort` and `allowUndo=true`.
- <a id="PayCostsReq.cancel-keeps-bounds"></a>**PayCostsReq.cancel-keeps-bounds.**
  `allowCancel=Abort` `MUST NOT` lower the cost's bound: an empty or short
  `EffectCostResp` is not a cancellation, and the server `MUST NOT` apply it.
- <a id="PayCostsReq.gather-shape"></a>**PayCostsReq.gather-shape.**
  *(provisional)* On the counter-gathering family, grounded for a single
  counter cost of an ability on the stack, `destinationId` `MUST` be that
  ability's instance ID and `paymentActions` `MUST` be empty.
- <a id="PayCostsReq.gather-resp"></a>**PayCostsReq.gather-resp.**
  *(provisional)* On the counter-gathering family the server `MUST NOT` apply
  a `gatherResp` whose amounts are not positive, exceed a source's
  `maxAmount`, name a source not in `sources`, or do not add up to
  `amountToGather`.
- <a id="PayCostsReq.auto-tap-index"></a>**PayCostsReq.auto-tap-index.**
  *(provisional)* On the mana-payment family the server `MUST` accept a
  `PerformAutoTapActionsResp` without `index` as choosing the first entry of
  `autoTapSolutions`.
- <a id="PayCostsReq.mana-step"></a>**PayCostsReq.mana-step.**
  *(provisional)* On the mana-payment family, after applying a
  `PerformActionResp` that names one of `paymentActions` while part of the
  cost remains, the server `MUST` send a new GSM and a new `PayCostsReq` for
  the remaining cost.
- <a id="PayCostsReq.resp-correlates"></a>**PayCostsReq.resp-correlates.** A
  response answers the request whose `msgId` equals its `respId` and whose
  `gameStateId` equals its own. A response for which either differs
  `MUST NOT` be applied to the request.

## Open points

- What distinguishes mana-payment prompt `11` from prompt `140`?
- What does `allowCancel=Continue` on a mana payment allow, and what does
  `CancelActionReq` do against it?
- When does a mana payment carry no `autoTapActionsReq`, and how should the
  client pay then?
- Which object does `manaCost.objectId` name when an ability, not a spell, is
  paid for?
- Does counter gathering name the counter type when the sources hold more
  than one kind?
- When does the server reject a `CancelActionReq` against a payment with
  `IllegalRequest`?
- What does a request with neither `manaCost` nor `effectCostReq`, such as
  prompt `94` with no parameters, ask the player to do?
