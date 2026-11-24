---
summary: "SearchReq: search a hidden zone and choose cards from it; searched objects, legal subset, fail to find, SearchResp and lifecycle."
entity: prompt/SearchReq
---
# SearchReq

`SearchReq` asks a player to search a zone, normally their library, and choose
cards from it (CR 701.23). It shows every card in the searched zone and marks
which of them may be chosen. The answer is a flat list of chosen cards.
`SearchFromGroupsReq` is the variant that splits the
legal cards into groups.

Enum values below are written without their type prefix: `Any` is
`AllowFailToFind_Any`, `No` is `AllowCancel_No`.

## Request

| Field | Role |
|---|---|
| `systemSeatIds` | The searching player. |
| `msgId` | Echoed by the response as `respId`. |
| `gameStateId` | The GSM the search is made against. |
| `prompt` | The instruction for this search, with `CardId` parameters; see below. |
| `allowCancel` | `No`. `allowUndo` may be `true`. |

| `searchReq` field | Role |
|---|---|
| `zonesToSearch` | The zone IDs searched, such as a player's library. |
| `itemsToSearch` | Every object shown to the searcher. |
| `itemsSought` | The objects that may be chosen, a subset of `itemsToSearch`. Absent when nothing may be chosen. |
| `maxFind` | The most objects that may be chosen. |
| `minFind` | The fewest, when the search has a minimum. |
| `allowFailToFind` | `Any` when the searcher may choose nothing. |
| `sourceId` | The spell or ability performing the search. |

## Prompt

The prompt ID is specific to the search effect: there is no single prompt for
every search. Every parameter is `parameterName=CardId` of `type=Number`. The
first names the source card: `sourceId` when it is a card, or the ability's
`parentId` when `sourceId` is an ability. A second parameter, when present,
carries a number the instruction uses and does not name an object.

## Candidate identity

Every object in `itemsToSearch` is a game object published in a GSM before
the request, visible to the searcher even though the zone is hidden. The
response names objects by those instance IDs. `sourceId` follows the rule of
[OptionalActionMessage](OptionalActionMessage.md#candidate-identity): the
ability on the stack for an ability, the card for a spell.

## Response

The client answers with `ClientMessageType_SearchResp`, correlated as in
[the message stream](../stream/messages.md#Messages.resp-correlates).

| Field | Role |
|---|---|
| `searchResp.itemsFound` | The chosen instance IDs. An empty `searchResp` chooses nothing. |

## Lifecycle

1. The spell or ability resolves and a GSM publishes the searched objects to
   the searcher.
2. `SearchReq` follows in the same batch, as in
   [the message stream](../stream/messages.md).
3. The client answers.
4. The next GSMs move the chosen objects, shuffle the searched zone when the
   effect says so, and the searched objects are no longer shown.

## Contract

- <a id="SearchReq.no-cancel"></a>**SearchReq.no-cancel.** The request `MUST`
  carry `allowCancel=No`.
- <a id="SearchReq.sought-subset"></a>**SearchReq.sought-subset.** Every ID in
  `itemsSought` `MUST` also be in `itemsToSearch`.
- <a id="SearchReq.items-published"></a>**SearchReq.items-published.** Every
  ID in `itemsToSearch` `MUST` name a game object published to the searcher
  before the request.
- <a id="SearchReq.source-card"></a>**SearchReq.source-card.** The first
  `CardId` parameter `MUST` equal `sourceId` when `sourceId` names a card, and
  `MUST` equal the ability's `parentId` when `sourceId` names an ability.
- <a id="SearchReq.resp-sought"></a>**SearchReq.resp-sought.** The server
  `MUST NOT` apply an `itemsFound` that names an ID outside `itemsSought`,
  names an ID twice, or names more than `maxFind` objects.
- <a id="SearchReq.resp-empty"></a>**SearchReq.resp-empty.** The server `MUST`
  accept an empty `searchResp` as finding nothing when the request carries
  `allowFailToFind=Any`.

## Open points

- Without `allowFailToFind`, must the client choose exactly `maxFind` objects,
  or at least `minFind`? `minFind` is often absent in that case.
- What number does the second `CardId` parameter carry?
- How are searches of several zones, such as a library and a graveyard,
  offered?
- What does `UndoReq` do against a request with `allowUndo=true`?
