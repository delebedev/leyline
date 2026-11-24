---
summary: "SearchFromGroupsReq: search a hidden zone where the legal cards are split into groups; group rows, grouping style, SearchFromGroupsResp and lifecycle."
entity: prompt/SearchFromGroupsReq
---
# SearchFromGroupsReq

`SearchFromGroupsReq` is the form of [SearchReq](SearchReq.md) in which the
cards that may be found are split into groups, for example the basic lands
and the other lands a spell can find. The answer names the group it chooses
from as well as the cards.

Enum values below are written without their type prefix: `SingleGroup` is
`GroupingStyle_SingleGroup`, `Any` is `AllowFailToFind_Any`.

## Request

| Field | Role |
|---|---|
| `systemSeatIds` | The searching player. |
| `msgId` | Echoed by the response as `respId`. |
| `gameStateId` | The GSM the search is made against. |
| `prompt` | The instruction for this search, with one `CardId` parameter naming the source card. The prompt ID is specific to the effect. |
| `allowCancel` | `AllowCancel_No`. |

| `searchFromGroupsReq` field | Role |
|---|---|
| `zonesToSearch` | The zone IDs searched. |
| `groups` | One row per group: `groupId`, the legal `ids` in that group, and `maxSelect`, the most cards that may come from it. |
| `groupingStyle` | How a selection may span groups. `SingleGroup`: every card found comes from one group. |
| `maxFind` | The most cards found in total. |
| `allowFailToFind` | `Any` when the searcher may find nothing. |
| `sourceId` | The spell performing the search. |

A `groupId` identifies a group within one request only. Two requests for the
same effect can use different IDs for the same kind of card.

## Candidate identity

Every ID in `groups` is a card in the searched zone, published to the
searcher before the request, and appears in only one group.

## Response

The client answers with `ClientMessageType_SearchFromGroupsResp`, correlated as
in [the message stream](../stream/messages.md#Messages.resp-correlates).

| Field | Role |
|---|---|
| `searchFromGroupsResp.groups` | The group searched, echoed with its `groupId` and `maxSelect`, and `ids` narrowed to the cards found. |

## Lifecycle

1. The spell resolves and a GSM publishes the searched cards to the searcher.
2. `SearchFromGroupsReq` follows in the same batch, as in
   [the message stream](../stream/messages.md).
3. The client answers.
4. The next GSMs move the found cards and shuffle the searched zone.

## Contract

- <a id="SearchFromGroupsReq.no-cancel"></a>**SearchFromGroupsReq.no-cancel.**
  The request `MUST` carry `allowCancel=AllowCancel_No`.
- <a id="SearchFromGroupsReq.disjoint-groups"></a>**SearchFromGroupsReq.disjoint-groups.**
  A card ID `MUST NOT` appear in more than one group of a request.
- <a id="SearchFromGroupsReq.resp-group"></a>**SearchFromGroupsReq.resp-group.**
  Under `SingleGroup`, the server `MUST NOT` apply a response that names more
  than one group, a `groupId` the request did not offer, a card outside that
  group's `ids`, or more cards than the group's `maxSelect` or `maxFind`.

## Open points

- How does the client find nothing: an empty `groups`, or a group row with no
  `ids`?
- Which other `groupingStyle` values are sent, and what do they allow?
- Is the source ever an ability, and what then is `sourceId`?
