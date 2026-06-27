---
summary: "ADR: split TargetingHandler by client interaction lifecycle, not by prompt route."
read_when:
  - "working in TargetingHandler, PendingClientInteraction, prompt responses, search prompts, or cost prompts"
  - "extracting match-layer prompt handling code"
  - "deciding whether a prompt refactor belongs in match handlers or bridge interaction planners"
---
# ADR 0002: TargetingHandler Interaction Lifecycles

## Status

Accepted.

This ADR defines the match-layer ownership boundary for client interaction
lifecycles around `TargetingHandler`. It does not change ADR 0001's planner
boundary.

## Context

`TargetingHandler` began as the session-side owner for targeting client
messages. It now handles several interaction lifecycles because they all share
the same final operation: convert a client response into Forge prompt/action
input, advance priority, and send follow-up GRE messages when needed.

Current responsibilities include:

- Target selection: `SelectTargetsResp`, `SubmitTargetsReq`, and echo re-prompts.
- Select-N, order, effect-cost, and group response mapping.
- Search prompt emission and `SearchResp` handling.
- Modal `CastingTimeOptionsReq` and `CastingTimeOptionsResp` handling.
- Deferred cast cost prompts: optional costs, hybrid mana type choices, and alternate additional cost choices.
- Native `PayCostsReq` interaction loops for mana-source payment choices such as Convoke and Waterbend.
- Prompt-journal side effects associated with those responses.

This produces a large, high-churn file whose name no longer describes most of
its contents. The pressure is not only file length: unrelated mechanics often
touch the same state variables, response-submission boilerplate, and prompt
transport code.

## Relationship To ADR 0001

ADR 0001 covers callback-specific prompt planners under
`leyline.bridge.interaction`. Those planners classify Forge callback context
before a `PromptRequest` exists. They must not depend on `match`, protobuf
builders, GRE senders, or session response handlers.

This ADR covers the later session/protocol stage after a `PromptRequest` or
client response exists:

```text
PromptRequest / client response
-> match-layer interaction lifecycle handler
-> prompt/action response submission and GRE follow-up
```

Therefore these extracted classes belong under `leyline.match`, not
`leyline.bridge.interaction`. They are handlers, not planners.

## Problem Statement

The unsafe refactor would be to create route-owned prompt handlers that own
classification, request construction, response mapping, and side effects. That
would make prompt routing a hidden rule engine and would conflict with ADR
0001.

The useful refactor is narrower: split stable client interaction lifecycles
while preserving existing owners for classification and GRE construction.

The target boundary is:

- `PromptClassifier` continues to classify pending prompts.
- `RequestBuilder` and `BundleBuilder` continue to own GRE request and bundle construction.
- `TargetingHandler` remains the session-facing coordinator and public entry point used by `MatchSession` / action performers.
- Extracted handlers own only coherent response lifecycles and their lifecycle-local state.

## Decision

Split `TargetingHandler` by stable client interaction lifecycle, not by prompt
route or protocol message type. `TargetingHandler` remains the session-facing
coordinator and public entry point; extracted handlers own lifecycle-local
state and response sequencing.

### Response Submission

`PromptResponseSubmitter` owns repeated prompt response mapping and submission
for simple non-target prompt responses:

- Pending prompt lookup with the existing timeout/race warning behavior.
- Client id to prompt-index mapping using `PromptResponseMapper`.
- `submitResponse`, `awaitPriority`, and `autoPass` sequencing.
- Shared response paths for `SelectNResp`, `OrderResp`, and `EffectCostResp`.
- Small choice-result side-effect recording if it remains local to Select-N responses.

It does not own:

- Prompt classification.
- GRE request construction.
- Target echo re-prompt behavior.
- Search cursor invalidation or playback draining.

### Search Interaction Lifecycle

`SearchPromptInteractionHandler` owns search-specific send/response handling:

- `sendSearchReq`.
- `onSearchResp`.
- Search source/host instance id resolution.
- Search prompt id selection.
- Search pending-state creation and consumption.
- The post-search sequence: submit response, await priority, drain pending playback, invalidate the bundle cursor, send game state, then `autoPass`.

It does not own:

- `PromptClassifier` search detection.
- Generic library visibility policy outside the SearchReq lifecycle.
- Non-search pending interactions.

### Cost Interaction Lifecycles

`DeferredCastCostInteractionHandler` owns pre-engine cast prompts:

- Optional-cost prompt checks and responses.
- Hybrid mana type prompt checks and responses.
- Alternate additional cost prompt checks and responses.
- Keyword-cost prompt stashing.
- Deferred cast replay through the pending action bridge.
- Helpers for hybrid/two-generic mana color ordering and optional-cost slot lookup.

`PayCostsInteractionHandler` owns native `PayCostsReq` mana-source loops:

- `PerformActionResp` handling for `MakePayment` / `Pass` on PayCosts prompts.
- Mana-source payment accumulation.
- Convoke payment selection and prompt-journal recording.
- PayCosts re-prompt adjustment.
- Convoke count persistent annotation injection for PayCosts bundles.

Both handlers preserve a single pending-interaction owner per seat. Cost-specific
`PendingClientInteraction` variants remain part of the shared session slot; the
cost handlers access that slot through explicit getters/setters rather than
creating independent pending state.

## Non-Goals

- Do not introduce a generic prompt interaction framework.
- Do not move planner responsibilities from `bridge.interaction` into `match`.
- Do not move GRE construction out of `RequestBuilder` / `BundleBuilder`.
- Do not turn `SelectNPromptRoute` or `PayCostsPromptRoute` into route-owned response handlers.
- Do not refactor target selection echo re-prompting under these handlers.
- Do not split the file by protocol message type alone; send and response halves of one lifecycle should stay together.

## Risks And Required Invariants

`pendingInteraction` is shared session state. Splitting handlers must not create
multiple independent pending slots for the same seat.

Cancel behavior crosses domains. `CancelActionReq` cancels deferred cast
prompts, completes partial mana-source payments, or submits empty target lists.
Those branches must stay explicit at the coordinator boundary.

Convoke payment state is distributed across in-memory selection maps and the
prompt journal. The PayCosts extraction must move those pieces together.

Search ordering is fragile. The post-response order must remain:

1. Submit response to the prompt bridge.
2. Await priority.
3. Drain pending playback messages.
4. Invalidate the bundle cursor.
5. Send real game state.
6. Call `autoPass`.

Target selection echo behavior is intentionally left in place. It has a two-step
client protocol and should not be mixed into the generic response helper.

## Alternatives Considered

### Cost Handlers As The Primary Split

Rejected as the top-level decomposition. Cost handling has high internal
cohesion, but it also crosses cancel handling, pending actions, journals, and
PayCosts re-prompts. Making cost the primary split would leave response
submission and search lifecycles without clear owners.

### One CostPromptInteractionHandler

Rejected as the stable boundary. Deferred cast prompts and native PayCosts loops
have different lifecycles and different state. Keeping them separate preserves
the distinction between pre-engine cast replay and in-engine payment loops.

### Route-Owned Prompt Handlers

Rejected, same reason as ADR 0001. Route objects should not own classification,
request construction, response mapping, and prompt-journal side effects.

### Leave TargetingHandler As The Single Session Handler

Acceptable for tiny fixes, but no longer the right direction. The file is a
high-churn collision point, and its cost/search responsibilities are already
coherent lifecycles that can move without changing protocol behavior.

## Consequences

Mechanic changes should usually touch one lifecycle handler plus the shared
coordinator, not the entire prompt surface. The cost of this split is that
`TargetingHandler` remains an orchestration hub with explicit delegation; this
is preferable to hiding prompt routing, request construction, and response
side effects behind route-owned handlers.
