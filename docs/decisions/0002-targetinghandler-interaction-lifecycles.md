---
summary: "ADR: split TargetingHandler by client interaction lifecycle, not by prompt route."
read_when:
  - "working in TargetingHandler, PendingClientInteraction, prompt responses, search prompts, or cost prompts"
  - "extracting match-layer prompt handling code"
  - "deciding whether a prompt refactor belongs in match handlers or bridge interaction planners"
---
# ADR 0002: TargetingHandler Interaction Lifecycles

## Status

Proposed for one staged refactor branch.

This ADR chooses a branch shape for behavior-preserving refactors around
`TargetingHandler`. It does not require a rewrite before prompt behavior can
ship, and it does not change ADR 0001's planner boundary.

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

Split `TargetingHandler` incrementally by interaction lifecycle. Prefer three
stages in one branch, with the cost stage allowed to land as two commits if the
state movement is easier to review.

### Stage 1: Response Submission Helper

Introduce a small match-layer helper for repeated prompt response mapping and
submission.

Candidate name: `PromptResponseSubmitter`.

It may own:

- Pending prompt lookup with the existing timeout/race warning behavior.
- Client id to prompt-index mapping using `PromptResponseMapper`.
- `submitResponse`, `awaitPriority`, and `autoPass` sequencing.
- Shared response paths for `SelectNResp`, `OrderResp`, and `EffectCostResp`.
- Small choice-result side-effect recording if it remains local to Select-N responses.

It must not own:

- Prompt classification.
- GRE request construction.
- Target echo re-prompt behavior.
- Search cursor invalidation or playback draining.

This stage should be first because it is the lowest-risk extraction and reduces
boilerplate before moving larger lifecycles.

### Stage 2: Search Interaction Lifecycle

Extract search-specific send/response handling.

Candidate name: `SearchPromptInteractionHandler`.

It may own:

- `sendSearchReq`.
- `onSearchResp`.
- Search source/host instance id resolution.
- Search prompt id selection.
- Search pending-state creation and consumption.
- The exact post-search sequence: submit response, await priority, drain pending playback, invalidate the bundle cursor, send a real game state, then `autoPass`.

It must not own:

- `PromptClassifier` search detection.
- Generic library visibility policy outside the SearchReq lifecycle.
- Non-search pending interactions.

This stage is independent and gives search a lifecycle owner without disturbing
target selection or cost prompts.

### Stage 3: Cost Interaction Lifecycles

Extract cost-specific client interactions. The stage is conceptually one branch
milestone, but should be split internally if review gets broad.

Candidate names:

- `DeferredCastCostInteractionHandler` for pre-engine cast prompts.
- `PayCostsInteractionHandler` for native `PayCostsReq` mana-source loops.

`DeferredCastCostInteractionHandler` may own:

- Optional-cost prompt checks and responses.
- Hybrid mana type prompt checks and responses.
- Alternate additional cost prompt checks and responses.
- Keyword-cost prompt stashing.
- Deferred cast replay through the pending action bridge.
- Helpers for hybrid/two-generic mana color ordering and optional-cost slot lookup.

`PayCostsInteractionHandler` may own:

- `PerformActionResp` handling for `MakePayment` / `Pass` on PayCosts prompts.
- Mana-source payment accumulation.
- Convoke payment selection and prompt-journal recording.
- PayCosts re-prompt adjustment.
- Convoke count persistent annotation injection for PayCosts bundles.

Both handlers must preserve a single pending-interaction owner per seat. If
moving cost-specific `PendingClientInteraction` variants immediately makes state
ownership unclear, keep the sealed state where it is and delegate through a
small accessor until the lifecycle split proves stable.

## Non-Goals

- Do not introduce a generic prompt interaction framework.
- Do not move planner responsibilities from `bridge.interaction` into `match`.
- Do not move GRE construction out of `RequestBuilder` / `BundleBuilder`.
- Do not turn `SelectNPromptRoute` or `PayCostsPromptRoute` into route-owned response handlers.
- Do not refactor target selection echo re-prompting in this branch unless a stage directly requires it.
- Do not split the file by protocol message type alone; send and response halves of one lifecycle should stay together.

## Risks And Required Invariants

`pendingInteraction` is shared session state. Splitting handlers must not create
multiple independent pending slots for the same seat.

Cancel behavior crosses domains. `CancelActionReq` currently cancels deferred
cast prompts, completes partial mana-source payments, or submits empty target
lists. Cost extraction must either move the relevant cancel branches with the
cost handlers or make the delegation explicit.

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

### Extract Cost Handling First

Rejected as the first stage. Cost handling is the highest-value extraction, but
it moves the most state and crosses cancel handling, pending actions, journals,
and PayCosts re-prompts. A small response-submission helper gives a safer first
cut and reduces duplicated code before larger movement.

### One CostPromptInteractionHandler

Acceptable as a temporary file shape only if the implementation remains small,
but not the preferred final boundary. Deferred cast prompts and native PayCosts
loops have different lifecycles and different state. Keeping them separate makes
review and later mechanic work cheaper.

### Route-Owned Prompt Handlers

Rejected, same reason as ADR 0001. Route objects should not own classification,
request construction, response mapping, and prompt-journal side effects.

### Leave TargetingHandler As The Single Session Handler

Acceptable for tiny fixes, but no longer the right direction. The file is a
high-churn collision point, and its cost/search responsibilities are already
coherent lifecycles that can move without changing protocol behavior.

## Testing Strategy

Each stage should run focused tests before the full gate.

Stage 1:

- `TargetingHandlerSelectNTest`
- Order and effect-cost session tests that exercise prompt-index response mapping.

Stage 2:

- Search prompt/session tests.
- Search request builder tests.
- Library-search conformance tests.

Stage 3:

- Optional-cost session tests.
- Hybrid-mana session tests.
- Stack targeting tests that include optional cost prompts.
- Waterbend and Convoke lifecycle tests.
- Prompt journal tests for optional, keyword, hybrid, and Convoke stashes.

Before merging the branch, run `:matchdoor:testGate` plus static checks.

## Migration Plan

1. Add `PromptResponseSubmitter` and migrate the simplest response path first,
   then Select-N, order, and effect-cost responses.
2. Add `SearchPromptInteractionHandler` and move SearchReq/SearchResp as one
   lifecycle-preserving slice.
3. Add deferred cast cost handling. Keep public `TargetingHandler` methods as
   delegating facades so callers do not churn.
4. Add PayCosts handling and move the mana-source / Convoke maps with it.
5. Only after the handlers are stable, consider narrowing
   `PendingClientInteraction` into lifecycle-specific subtypes. Do not start
   there.

Stop after any stage if the extraction increases the number of concepts a
mechanic change must understand.
