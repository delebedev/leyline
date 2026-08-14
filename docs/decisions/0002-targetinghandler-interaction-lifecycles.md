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

Current residual responsibilities include:

- Residual Select-N, effect-cost, and group response mapping.
- Modal `CastingTimeOptionsReq` and `CastingTimeOptionsResp` handling.
- Deferred cast cost prompts: optional costs, hybrid mana type choices, and alternate additional cost choices.

Target selection, Search, Top/Bottom Order, card-backed SelectN (including hidden-library Dig resolution and Learn), static-enum SelectN, reveal-backed SelectN, and all PayCosts payments are match-coordinator-owned lifecycles with value-only session adapters. Visible or mixed dynamic resolution choices remain on the residual SelectN path.

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

Residual response-lifecycle handlers belong under `leyline.match`, not
`leyline.bridge.interaction`. Coordinator-backed blocking lifecycles may instead
move to the imperative shell defined by ADR 0015; Search is one such lifecycle.

## Problem Statement

The unsafe refactor would be to create route-owned prompt handlers that own
classification, request construction, response mapping, and side effects. That
would make prompt routing a hidden rule engine and would conflict with ADR
0001.

The useful refactor is narrower: split stable client interaction lifecycles
while preserving existing owners for classification and GRE construction.

The target boundary is:

- `TargetingHandler` dispatches pending prompts from their bound route.
- `RequestBuilder` and `BundleBuilder` continue to own GRE request and bundle construction.
- `TargetingHandler` remains the session-facing entry point used by `MatchSession` / action performers.
- Residual extracted handlers own only coherent response lifecycles and their lifecycle-local state.
- Coordinator-backed routes retain only value-response dispatch in the session layer.

## Decision

Split `TargetingHandler` by stable client interaction lifecycle, not by prompt
route or protocol message type. `TargetingHandler` remains the session-facing
entry point; residual extracted handlers own lifecycle-local state and response
sequencing, while ADR 0015 coordinator migrations reduce it to value dispatch.

### Response Submission

`PromptResponseSubmitter` owns repeated prompt response mapping and submission
for simple non-target prompt responses:

- Pending prompt lookup with the existing timeout/race warning behavior.
- Client id to prompt-index mapping using `PromptResponseMapper`.
- `submitResponse`, `awaitPriority`, and `autoPass` sequencing.
- Shared response paths for `SelectNResp` and residual `EffectCostResp` routes.

It does not own:

- Prompt classification.
- GRE request construction.
- Target echo re-prompt behavior.
- Search cursor invalidation or playback draining.

### Search Interaction Lifecycle

Bound Search routes are coordinator-owned. The engine thread freezes the library,
valid options, source identity, and picker shape. `MatchSearchInteractionRuntime`
materializes and commits the state reveal and `SearchReq` as one cut before signalling.
The session submits only correlated instance IDs; the runtime maps them to the exact
options, resets the reveal baseline, and then releases the engine wait. Timeout
retires the exact window and returns the configured fallback, while publication
and delivery failures use the match terminal path.

`TargetingHandler` retains only thin `SearchResp` dispatch. It does not read the
library, stack, spell ability, or instance-id registry for this lifecycle.

### Ordered-Card Interaction Lifecycle

Bound top- and bottom-library Order routes are coordinator-owned. The engine
thread freezes the source, exact card options, and any pending hand-to-library
move. `MatchOrderInteractionRuntime` materializes and commits the state change
and `OrderReq` as one cut before signalling. The session submits only the
correlated instance-id permutation; the runtime resolves it to the retained
original handles before releasing the engine wait. Timeout retires the window
and returns the default-first order, while publication and delivery failures
use the match terminal path.

### Grouping Interaction Lifecycle

Bound Scry and Surveil routes are coordinator-owned. The engine thread freezes
the source, private candidates, and exact card handles. `MatchGroupingInteractionRuntime`
materializes and commits the private reveal state and `GroupReq` as one cut before
signalling. The session submits only a correlated complete instance-id partition;
the runtime maps it to the retained original handles before releasing the engine wait.
When kept cards require ordering, the arrangement remains pending until the ordered-card
window returns, then records the final top order. Timeout retires the exact window and
returns the existing default partition; publication and delivery failures use the match
terminal path.

### Reveal Choice Lifecycle

Bound RevealChoice routes are coordinator-owned. The engine thread freezes the exact
reveal journal version, owner, full revealed set, selectable card handles, source,
cardinality, and default. `MatchRevealChoiceInteractionRuntime` marks that reveal pending
in the frozen projection facts and commits the state and `SelectNReq` together before
signalling. The session submits only correlated instance IDs. Completion maps them to
the retained handles, stages any source-linked exile, and compare-clears only the
claimed reveal before releasing the engine wait. Timeout and zero-selectable windows
use the same finalization; publication and delivery failures clear the claimed version
without an exile side effect.

### Cost Interaction Lifecycles

`DeferredCastCostInteractionHandler` owns pre-engine cast prompts:

- Optional-cost prompt checks and responses.
- Hybrid mana type prompt checks and responses.
- Alternate additional cost prompt checks and responses.
- Keyword-cost prompt stashing.
- Deferred cast replay through the pending action bridge.
- Helpers for hybrid/two-generic mana color ordering and optional-cost slot lookup.

`MatchManaSourcePaymentRuntime` owns the iterative Convoke, Improvise, and
Waterbend `PayCostsReq` loops:

- The engine thread freezes exact candidate handles, shard choices, source, and mana-cost values.
- Initial state and PayCosts request commit before signalling.
- MakePayment updates the immutable plan and commits the replacement request before delivery acknowledgement.
- Pass and Cancel resolve original prompt indices; timeout returns the configured default.
- Convoke and Improvise payment facts are staged by the replacement cut, corrected before engine progression, and retained until stack-exit consumption.

`MatchOneShotPayCostsRuntime` owns Sacrifice, exile-from-grave, return-unblocked-attacker,
Collect Evidence, Station, Enlist, and Teamwork. It freezes exact option handles and
weights on the engine thread, commits one state-and-request cut before signalling,
and accepts only correlated immutable instance-id selections. The thin PayCosts
session adapter does not retain live cards or mutable payment maps.

## Non-Goals

- Do not introduce a generic prompt interaction framework.
- Do not move planner responsibilities from `bridge.interaction` into `match`.
- Keep migrated interaction construction in value-only materializers.
- Do not turn `SelectNPromptRoute` or `PayCostsPromptRoute` into route-owned response handlers.
- Do not refactor target selection echo re-prompting under these handlers.
- Do not split the file by protocol message type alone; send and response halves of one lifecycle should stay together.

## Risks And Required Invariants

`pendingInteraction` is shared session state. Splitting handlers must not create
multiple independent pending slots for the same seat.

Cancel behavior crosses domains. `CancelActionReq` cancels deferred cast
prompts, finalizes the coordinator-owned partial mana-source selection, or
submits empty target lists. Those branches stay explicit at the coordinator boundary.

Convoke and Improvise selection values and payment facts share one coordinator
transaction; the session no longer owns either state.

Search ordering is fragile. Initial publication commits the reveal state and
`SearchReq` before signalling. A correlated response resets the reveal baseline
inside the coordinator transaction before completing the engine future. The
session then awaits the resulting priority horizon and calls `autoPass`; it does
not build or send an intermediate state frame.

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
