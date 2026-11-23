---
summary: "Prompt family overview: requests that suspend the game until one seat answers, and their responses."
read_when:
  - "finding the page for a GRE prompt type"
  - "emitting or parsing a request and its correlated response"
---
# Prompts

A prompt suspends game progression while one seat supplies a decision. The
request defines the legal choice domain; the response correlates to the
request through `respId` and `gameStateId`. The typed request payload is the
semantic discriminator; `promptId` selects instruction text.

| Prompt | Role |
|---|---|
| [ActionsAvailableReq](ActionsAvailableReq.md) | Take a priority action or pass; also the cast-permission window. |
| [PayCostsReq](PayCostsReq.md) | Pay a cost: mana, objects chosen to pay it, or counters gathered from several permanents. |
| [SelectNReq](SelectNReq.md) | Choose values from a bounded domain: trigger order, discard, resolution choices, mana colors. |
| [SelectTargetsReq](SelectTargetsReq.md) | Choose targets for a spell or ability, one change at a time, then submit. |
