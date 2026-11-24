---
summary: "GroupReq: split a set of cards into groups with destinations; scry, surveil, London mulligan and piles, positional GroupResp and lifecycle."
entity: prompt/GroupReq
---
# GroupReq

`GroupReq` asks a player to split a set of cards into groups. Scry
(CR 701.22), surveil (CR 701.25), the London mulligan's cards put on the
bottom (CR 103.5) and effects that separate cards into piles all use it. Each
group has a specification giving its size bounds and, for most uses, the zone
its cards go to. The answer lists the cards in each group.

Enum values below are written without their type prefix: `Ordered` is
`GroupType_Ordered`, `Scry` is `GroupingContext_Scry`, `Top` is
`SubZoneType_Top`.

## Request

| Field | Role |
|---|---|
| `systemSeatIds` | The player making the split. |
| `msgId` | Echoed by the response as `respId`. |
| `gameStateId` | The GSM the split is made against. |
| `prompt` | The instruction, with one `CardId` parameter; see Uses. |
| `allowCancel` | `No`. |

| `groupReq` field | Role |
|---|---|
| `instanceIds` | Every card to split. |
| `groupSpecs` | One specification per group, in order: `lowerBound`, `upperBound`, and for most uses the destination `zoneType` and `subZoneType`. |
| `groupType` | `Ordered` when the order of cards within a group is part of the answer, `GroupType_Arbitrary` when only membership is. |
| `context` | The use: `Scry`, `Surveil` or `LondonMulligan`. Absent for piles. |
| `sourceId` | The spell or ability performing the split, or the seat for a mulligan. |

## Uses

| Use | `context` | `groupSpecs` | `groupType` | `promptId` |
|---|---|---|---|---|
| Scry N | `Scry` | Library `Top`, then Library `SubZoneType_Bottom`; each with `upperBound` N | `Ordered` | `92` |
| Surveil N | `Surveil` | Library `Top`, then Graveyard; each with `upperBound` N | `Ordered` | `129` |
| London mulligan | `LondonMulligan` | Hand `Top` with both bounds equal to the cards kept, then Library `SubZoneType_Bottom` with both bounds equal to the cards put on the bottom | `Ordered` | `92` |
| Piles | absent | Two groups with no zone and `upperBound=2147483647` | `GroupType_Arbitrary` | Specific to the effect |

For scry and surveil the `CardId` parameter names the source card: `sourceId`
when it is a card, or the ability's `parentId` when `sourceId` is an ability.
For the London mulligan, `sourceId` is the deciding seat and the `CardId`
parameter is `0`.

## Candidate identity

Every ID in `instanceIds` is a card published to the deciding player before
the request: library cards for scry and surveil, hand cards for the mulligan.
For scry and surveil, `sourceId` follows the rule of
[OptionalActionMessage](OptionalActionMessage.md#candidate-identity): the
ability on the stack for an ability, the card for a spell.

## Response

The client answers with `ClientMessageType_GroupResp`, correlated as in
[the message stream](../stream/messages.md#Messages.resp-correlates).

| Field | Role |
|---|---|
| `groupResp.groups` | One row per request group, in the order of `groupSpecs`. Each row lists its cards in `ids`, absent when the group is empty. |
| `groupResp.groupType` | Usually the request's `groupType`. |

Rows also carry `zoneType` and `subZoneType`, but these labels are not
reliable: a surveil that keeps every card on top answers with an empty second
row labelled Library `SubZoneType_Bottom`. A row's meaning comes from its
position.

## Lifecycle

1. A GSM publishes the cards to the deciding player.
2. `GroupReq` follows in the same batch, as in
   [the message stream](../stream/messages.md). A London mulligan's request
   follows the mulligan decision.
3. The client answers.
4. The next GSMs move each group's cards to its destination, or continue the
   effect that uses the piles.

## Contract

- <a id="GroupReq.no-cancel"></a>**GroupReq.no-cancel.** The request `MUST`
  carry `allowCancel=No`.
- <a id="GroupReq.scry-specs"></a>**GroupReq.scry-specs.** A scry request
  `MUST` carry `context=Scry`, `groupType=Ordered`, and exactly two
  `groupSpecs`: Library `Top` then Library `SubZoneType_Bottom`.
- <a id="GroupReq.surveil-specs"></a>**GroupReq.surveil-specs.** A surveil
  request `MUST` carry `context=Surveil`, `groupType=Ordered`, and exactly two
  `groupSpecs`: Library `Top` then Graveyard.
- <a id="GroupReq.mulligan-specs"></a>**GroupReq.mulligan-specs.** A London
  mulligan request `MUST` carry `context=LondonMulligan`, every hand card in
  `instanceIds`, and two `groupSpecs` whose bounds fix the number of cards kept
  and put on the bottom.
- <a id="GroupReq.source-card"></a>**GroupReq.source-card.** *(provisional)*
  For scry and surveil, the `CardId` parameter `MUST` equal `sourceId` when
  `sourceId` names a card, and the ability's `parentId` when `sourceId` names
  an ability.
- <a id="GroupReq.resp-positional"></a>**GroupReq.resp-positional.** The
  server `MUST` read response row *i* as the group of `groupSpecs[i]`, and
  `MUST NOT` take a row's destination from its `zoneType` or `subZoneType`.
- <a id="GroupReq.resp-partition"></a>**GroupReq.resp-partition.** The server
  `MUST NOT` apply a response unless its rows together name every ID in
  `instanceIds` exactly once and each row's size is within its group's bounds.

## Open points

- In an `Ordered` group bound for the library, which end of `ids` is the top?
- Which effects use piles, what do prompts `11283` and `13392` ask, and what
  does `isFacedown` on a group specification mean?
- For some piles requests the client answers with `groupType=Ordered` and
  library labels while the request was `GroupType_Arbitrary`; what does that
  answer mean?
- When does a scry or surveil request carry a `CardId` parameter with no
  value?
