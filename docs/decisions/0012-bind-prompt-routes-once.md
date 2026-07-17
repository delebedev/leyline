---
summary: "ADR: resolve each prompt route once and carry the typed route through request emission and response handling."
read_when:
  - "working in PromptRequest, PromptRouteResolver, ResolvedPromptRoute, TargetingHandler, or prompt response handling"
  - "adding or changing a PromptSemantic or GRE prompt route"
  - "deciding where prompt-family routing belongs"
---
# ADR 0012: Bind Prompt Routes Once

## Status

Accepted and implemented.

## Context

Leyline translates synchronous Forge choices into asynchronous client prompt
interactions. Callback-specific planners classify Forge context before it is
lost, `PromptRequest` carries the choice across the engine-thread handoff, and
the match layer emits a GRE request and later maps the client response back to
the blocked Forge call.

The current path represents the prompt route several times:

```text
Forge callback
-> callback-specific planner
-> PromptRequest.semantic
-> PromptSemanticRouteMetadata family
-> ClassifiedPrompt
-> SelectNPromptRoutes concrete route
-> RequestBuilder request shape
-> response-handler semantic checks
```

`PromptSemanticRouteMetadata` maps each semantic to a broad request family.
`PromptClassifier` then collapses both `SelectN` and `PayCosts` into
`ClassifiedPrompt.SelectN`. `TargetingHandler` reads the semantic again to
recover the distinction through `SelectNPromptRoutes`. Request construction
and response handling perform further route lookups and maintain additional
semantic subsets.

The concrete `SelectNPromptRoute` and `PayCostsPromptRoute` values already show
the useful shape: protocol selection is data. The missing invariant is that one
resolved value should survive for the whole pending interaction.

## Problem Statement

The route family is currently an agreement among independent representations:

- the `PromptSemantic` selected by a producer;
- the semantic-to-family table;
- the concrete Select-N or Pay-Costs route table;
- populated fields in `PromptRequest`;
- request-builder branches;
- response-handler semantic checks.

Adding a semantic can satisfy the compiler in the family table while omitting
the concrete route row. The mismatch is discovered later as a runtime
"missing route" failure. Existing routes also lose information during
classification and reconstruct it before emission.

This makes one prompt expensive to trace and allows request emission and
response handling to disagree about the interaction that is pending.

## Decision

Resolve one immutable, typed prompt route when the prompt enters the pending
interaction lifecycle. Carry that value through classification, request
construction, emission, re-prompting, and response handling.

Conceptually:

```text
ResolvedPromptRoute
  Grouping(context)
  ModalChoice
  SelectN(selectNRoute)
  PayCosts(payCostsRoute)
  Search
  Order(orderKind)
  Targeting
  AutoResolve
```

The exact names may follow existing vocabulary. The required properties are:

- every non-generic pending prompt has exactly one resolved route;
- `SelectN` and `PayCosts` remain distinct variants;
- detailed Select-N and Pay-Costs route descriptors travel with their family;
- the route retains its `PromptSemantic` for diagnostics and mapping docs;
- `PromptRequest` does not store a second independent semantic value after
  migration;
- generic fallback resolves once to `Targeting` or `AutoResolve` and is not
  reconsidered later.

If callback planners continue to return `PromptSemantic` during migration, one
exhaustive resolver converts that semantic to a concrete route. That resolver
is the only semantic-to-route catalog. `PromptSemanticRouteMetadata` and the
parallel family table are removed.

The resolved route is a value, not a handler. It may carry immutable request
shape and response-policy data, but it must not inspect Forge state, build GRE
messages, submit responses, mutate journals, or own interaction lifecycle
state.

## Lifecycle Boundary

Route resolution happens no later than creation of the pending prompt. Once a
prompt is visible to the match layer, both its choice payload and resolved
route are fixed.

```text
Forge callback context
-> callback-specific planner
-> PromptRequest + resolved route
-> PendingPrompt
-> match-layer dispatch
-> request builder
-> GRE request
-> matching response lifecycle
-> Forge answer
```

Re-prompts retain the same route unless the interaction explicitly creates a
new prompt. Updating candidate lists, remaining payment amounts, or selection
cardinality must not cause route reclassification.

## Ownership

This decision preserves the existing boundaries:

- `bridge.interaction` planners own Forge callback classification and prompt
  policy before `PromptRequest` construction.
- The handoff layer owns immutable pending prompt values.
- `TargetingHandler` performs session dispatch directly from the already-resolved route.
- Match-layer lifecycle handlers continue to own sequencing, local state,
  response submission, and prompt-journal effects.
- `RequestBuilder` and focused protocol builders continue to own pure GRE
  request construction.
- `BundleBuilder` continues to own GSM/request pairing and message ordering.

This extends ADR 0001's planner boundary, ADR 0002's lifecycle ownership, and
ADR 0004's builder boundary. It does not replace any of them.

## Generic Prompts

`Generic` remains an explicit degraded input for Forge GUI adapters whose
callback context has not yet been assigned a semantic route.

The existing fallback remains:

- candidate references present: resolve to `Targeting`;
- no candidate references: resolve to `AutoResolve`.

That decision occurs once. A resolved generic prompt must be observable in the
same diagnostics used today so load-bearing generic paths can be migrated to
explicit planner routes without changing this ADR.

## Migration

1. Add a typed resolved-route hierarchy and one exhaustive semantic resolver.
2. Characterize the current semantic-to-family and semantic-to-concrete-route
   matrix with table-driven tests before deleting either table.
3. Attach the resolved route to the pending prompt handoff. Keep any temporary
   `semantic` field derived from the route, never independently writable.
4. Dispatch directly from distinct `ResolvedPromptRoute.SelectN` and
   `ResolvedPromptRoute.PayCosts` variants carrying their concrete descriptors.
5. Pass the bound route into `RequestBuilder` and Pay-Costs builders; remove
   route lookups from request construction and re-prompt paths.
6. Use the bound route in response submission and Pay-Costs interaction loops;
   replace hand-maintained semantic subsets with route variants or immutable
   route policy data.
7. Remove `PromptSemanticRouteMetadata`, duplicate route predicates, and
   runtime missing-route branches that the resolved type makes impossible.
8. Update prompt architecture docs and route tests to describe the single
   route authority.

Migration may use short-lived compatibility accessors, but the final state
must not keep independently writable `semantic` and `route` fields.

## Required Invariants

- One pending prompt has one resolved route for its lifetime.
- The emitted GRE request family agrees with the route variant.
- The accepted client response family agrees with the emitted request family.
- Re-prompts preserve their original route.
- `SelectN` and `PayCosts` are never collapsed and recovered through semantic
  inspection.
- Every explicit semantic resolves exhaustively at compile time or in one
  table-driven catalog test; missing concrete route data is not a late runtime
  discovery.
- Generic fallback remains explicit and observable.
- Route values contain no Forge objects and own no mutable lifecycle state.
- Existing callback-planner precedence and match-thread ordering remain
  unchanged.

## Verification

The implementation must prove behavior preservation at each boundary:

- Resolver tests cover every `PromptSemantic`, including the generic fallback.
- Classifier tests assert distinct `SelectN` and `PayCosts` results carrying
  their concrete route.
- Request-builder tests cover grouping, modal, search, order, targeting,
  static Select-N, dynamic Select-N, fixed-card Pay-Costs, weighted Pay-Costs,
  and mana-source Pay-Costs.
- Response tests prove that Select-N, Effect-Cost, Group, Order, Search, modal,
  and mana-source payment replies use the bound route and return the same Forge
  answers as before.
- Re-prompt tests cover shrinking candidate lists for Convoke, Improvise, and
  Waterbend without route reclassification.
- Existing prompt-route deck suites run through simclient with no new route,
  fallback, timeout, or invariant failures.
- The focused engine tests and `just test-gate` pass.

## Non-Goals

- Replacing every `PromptRequest` payload with family-specific sealed payloads.
  That remains a possible follow-up when invalid optional-field combinations
  prove costly after route binding.
- Rewriting callback-specific planners or moving their Forge classification
  into the route catalog.
- Creating route-owned prompt handlers or a generic prompt DSL.
- Moving request construction out of `RequestBuilder` and focused builders.
- Consolidating match-layer interaction lifecycle handlers.
- Changing GRE prompt shapes, prompt ids, response semantics, thread ordering,
  or bundle sequencing.
- Refactoring Full/Diff state projection.

## Consequences

Adding a prompt route becomes one explicit classification followed by ordinary
typed dispatch. Request emission, re-prompting, and response handling consume
the same value instead of maintaining parallel semantic knowledge.

The change crosses bridge handoff, match dispatch, request building, and
response handling, so it requires broad characterization tests. That migration
cost buys deletion of the duplicate family table, the Pay-Costs collapse and
recovery path, repeated route lookups, and several semantic subset predicates.

`PromptRequest` still has optional family-specific payload fields after this
decision. Route binding removes the larger correctness hazard first and gives
a later payload-typing change a stable family discriminator.

## Implementation

`ResolvedPromptRoute` and `PromptRouteResolver` live in the bridge handoff
package. `PromptRequest` stores the resolved route and derives `semantic` from
it for diagnostics. The resolver is exhaustive over `PromptSemantic`; Generic
resolves once from candidate presence.

`ResolvedPromptRoute.SelectN` carries `SelectNPromptRoute`, while
`ResolvedPromptRoute.PayCosts` carries `PayCostsPromptRoute`. The descriptors
contain immutable request-shape and response-policy data. Match lifecycle
handlers and request builders consume those descriptors; builder behavior
remains in the game bundle package.

## Alternatives Considered

### Add A `ClassifiedPrompt.PayCosts` Variant Only

Rejected as incomplete. It removes the most visible collapse but leaves the
parallel route tables and response-side semantic subsets intact.

### Keep Recomputing One Central Route

Better than parallel tables, but weaker than binding. Candidate lists and
request fields can change during re-prompts; retaining the original route makes
interaction identity explicit and prevents later data from changing routing.

### Move Behavior Onto Route Objects

Rejected. Route-owned builders, response submission, and journal effects would
hide lifecycle and ordering rules inside a new dispatch framework. Routes are
immutable facts consumed by existing owners.

### Replace `PromptRequest` With All Family Payloads Now

Deferred. Family-specific payloads could remove invalid field combinations,
but migrating every producer at once is larger than necessary to establish one
route authority. Bind the route first; type payloads where subsequent pressure
justifies it.
