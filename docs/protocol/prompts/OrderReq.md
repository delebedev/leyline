---
summary: "OrderReq: put a set of objects in order; library-top, library-bottom and battlefield uses, OrderResp permutation and lifecycle."
entity: prompt/OrderReq
---
# OrderReq

`OrderReq` asks a player to put a set of objects in order, such as cards being
put on the top or bottom of a library in any order (CR 401.4). The request
lists the objects; the answer lists the same objects in the chosen order.

Enum values below are written without their type prefix: `OrderingForBottom`
is `OrderingContext_OrderingForBottom`, `OrderAsIndicated` is
`OrderingType_OrderAsIndicated`.

## Request

| Field | Role |
|---|---|
| `systemSeatIds` | The player choosing the order. |
| `msgId` | Echoed by the response as `respId`. |
| `gameStateId` | The GSM the order is chosen against. |
| `prompt` | The instruction, with one `CardId` parameter naming the source card. |
| `allowCancel` | `AllowCancel_No`. `allowUndo` may be `true`. |

| `orderReq` field | Role |
|---|---|
| `ids` | Every object to order. Their order in the request is a default, not a choice. |
| `orderingContext` | `OrderingForBottom` when the objects go to the bottom of a library. Absent otherwise. |

## Uses

| Use | `ids` | `orderingContext` | `promptId` |
|---|---|---|---|
| Library bottom | Library cards going to the bottom, such as the cards not chosen by a look-and-pick effect | `OrderingForBottom` | `42` |
| Library top | Library cards going back on top, such as Brainstorm's | absent | `86` |
| Battlefield | Cards an effect has just put onto the battlefield together | absent | `91` |

The `CardId` parameter names the card whose effect asks for the order: on the
stack for a spell, and otherwise the permanent or card the ability came from.

## Candidate identity

Every ID in `ids` is a card published to the choosing player before the
request; library cards are shown to that player only. `OrderReq` has no
`sourceId`.

## Response

The client answers with `ClientMessageType_OrderResp`, correlated as in
[the message stream](../stream/messages.md#Messages.resp-correlates).

| Field | Role |
|---|---|
| `orderResp.ids` | The same IDs as the request, in the chosen order. |
| `orderResp.ordering` | `OrderAsIndicated`. |

## Lifecycle

1. A GSM publishes the objects to order. For a look-and-pick effect this
   follows the [SelectNReq](SelectNReq.md) that chose the kept cards, and
   moves those first.
2. `OrderReq` follows in the same batch, as in
   [the message stream](../stream/messages.md).
3. The client answers.
4. The next GSMs place the objects in the chosen order and the effect
   continues.

## Contract

- <a id="OrderReq.no-cancel"></a>**OrderReq.no-cancel.** The request `MUST`
  carry `allowCancel=AllowCancel_No`.
- <a id="OrderReq.bottom-context"></a>**OrderReq.bottom-context.** A request
  ordering cards for the bottom of a library `MUST` carry
  `orderingContext=OrderingForBottom` and `promptId=42`.
- <a id="OrderReq.top-context"></a>**OrderReq.top-context.** A request
  ordering cards for the top of a library `MUST` carry `promptId=86` and no
  `orderingContext`.
- <a id="OrderReq.source-card"></a>**OrderReq.source-card.** The single
  `CardId` parameter `MUST` name a card: the source of the effect asking for
  the order.
- <a id="OrderReq.resp-permutation"></a>**OrderReq.resp-permutation.** The
  server `MUST NOT` apply an `OrderResp` whose `ids` are not exactly the
  request's `ids`, each once, in some order.

## Open points

- Which end of `ids` is the top of the library, for each use?
- Which effects use the battlefield ordering, and what does the order decide?
- Can `orderResp.ordering` take another value, and what would it mean?
- When does a library-bottom request carry `allowUndo=true`, and what does
  `UndoReq` do against it?
