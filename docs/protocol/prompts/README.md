---
summary: "Prompt family overview: every GRE request that asks a player for a decision, the messages around them, and where each is described."
read_when:
  - "finding the page for a GRE prompt type"
  - "emitting or parsing a request and its correlated response"
---
# Prompts

A prompt is a GRE request that asks one player for a decision. Its typed
payload defines what can be chosen. The client answers with the matching
response message, correlated to the request as in
[the message stream](../stream/messages.md#Messages.resp-correlates). A
request usually follows the GSM it is decided against, in the same batch.

The payload type, not the `promptId`, says what is being decided. A
`promptId` selects the instruction the player reads, so one payload type can
carry many prompt IDs and related effects can share one. Prompt parameters
name the objects and values that instruction mentions.

Most requests carry a cancellation policy: `allowCancel`, and sometimes
`allowUndo`. While a spell is being cast or an ability activated, requests
usually allow `AllowCancel_Abort`, which abandons the whole cast. Requests
during resolution usually carry `AllowCancel_No`.

## Requests with a page

| Prompt | Role |
|---|---|
| [ActionsAvailableReq](ActionsAvailableReq.md) | Take a priority action or pass; also the cast-permission window. |
| [AssignDamageReq](AssignDamageReq.md) | Divide an attacker's combat damage among blockers and, with trample, the player. |
| [CastingTimeOptionsReq](CastingTimeOptionsReq.md) | Choose modes, optional costs, X, hybrid payment or a cost branch while casting. |
| [ChooseStartingPlayerReq](ChooseStartingPlayerReq.md) | The die-roll winner chooses who takes the first turn. |
| [DeclareAttackersReq](DeclareAttackersReq.md) | Declare attackers and what they attack, one change at a time, then submit. |
| [DeclareBlockersReq](DeclareBlockersReq.md) | Declare blockers and what they block, one change at a time, then submit. |
| [DistributionReq](DistributionReq.md) | Divide damage or counters among the targets just chosen. |
| [GroupReq](GroupReq.md) | Split cards into groups: scry, surveil, London mulligan, piles. |
| [IntermissionReq](IntermissionReq.md) | Show how a game or match ended and offer post-game options. |
| [NumericInputReq](NumericInputReq.md) | Choose a number while a spell or ability resolves. |
| [OptionalActionMessage](OptionalActionMessage.md) | Answer yes or no to an optional effect. |
| [OrderReq](OrderReq.md) | Put objects in order, such as cards going to the top or bottom of a library. |
| [PayCostsReq](PayCostsReq.md) | Pay a cost: mana, objects chosen to pay it, or counters gathered from several permanents. |
| [SearchFromGroupsReq](SearchFromGroupsReq.md) | Search a hidden zone where the legal cards are split into groups. |
| [SearchReq](SearchReq.md) | Search a hidden zone and choose cards from it. |
| [SelectNReq](SelectNReq.md) | Choose values from a bounded domain: trigger order, discard, resolution choices, mana colors. |
| [SelectReplacementReq](SelectReplacementReq.md) | Choose which of several replacement effects applies first. |
| [SelectTargetsReq](SelectTargetsReq.md) | Choose targets for a spell or ability, one change at a time, then submit. |

## Other request types

These types have no page yet. The roles follow from their message shapes.

| Type | Role |
|---|---|
| `MulliganReq` | Keep the opening hand or mulligan. Carries `mulliganType` (`MulliganType_London`), `freeMulliganCount` and `mulliganCount`, with `promptId=34`; answered by `MulliganResp` with `MulliganOption_AcceptHand` or `MulliganOption_Mulligan`. The London mulligan's cards to put on the bottom are then chosen with [GroupReq](GroupReq.md). |
| `GatherReq` | Collect an amount from several sources into one destination. The same shape appears inside [PayCostsReq](PayCostsReq.md) for counter costs. |
| `SelectCountersReq` | Choose counters, as `counterPairs`, between `minSel` and `maxSel`. |
| `SelectFromGroupsReq` | Choose objects from groups, outside a search. |
| `SelectNgroupReq` | Choose between `minSel` and `maxSel` whole groups, answered with group IDs. |
| `StringInputReq` | Enter text for a source, answered with `inputValue`. |
| `SubmitDeckReq` | Submit a deck, such as between games of a match. |
| `OrderCombatDamageReq` | A request type with no payload of its own. |
| `RevealHandReq` | A request type with no payload of its own. |

## Messages that ask nothing

| Type | Role |
|---|---|
| `PromptReq` | Shows an instruction and expects no answer, for example `promptId=37` naming the player who goes first, `36` with a player and a number of cards, or `46` with a coin flip result. |
| `EdictalMessage` | Carries, in `edictMessage`, a client message that the server has applied on the player's behalf, such as a `PerformActionResp` or `CancelActionReq`. |
