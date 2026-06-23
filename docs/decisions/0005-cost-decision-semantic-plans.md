---
summary: "ADR: classify cost callbacks into semantic cost plans before request-shape emission."
read_when:
  - "working in CostDecision, CostDecisionPlanner, CostPaymentCoordinator, or cost prompt handling"
  - "adding sacrifice, tap, exile, discard, mana-source, or optional-cost mechanics"
  - "deciding whether a cost prompt branch belongs in Forge callback code, request builders, or match handlers"
---
# ADR 0005: Cost Decision Semantic Plans

## Status

Accepted for incremental implementation.

## Context

Cost payment is one of the remaining pressure points between Forge callbacks and
client prompt emission. The current code already has useful seams: cost visitors
live in `CostDecision`, prompt metadata helpers live in `CostDecisionPlanner`,
and native `PayCostsReq` loops live in the match layer. That is better than one
large handler, but the next boundary is still blurry.

Cost work repeatedly crosses four different concerns:

- Forge callback shape: which `Cost*` visitor or controller override is asking.
- Cost intent: sacrifice, tap, discard, exile, collect evidence, enlist,
  mana-source payment, or choose cost mode.
- Client UI shape: `SelectNReq`, `PayCostsReq`, `CastingTimeOptionsReq`,
  confirmation, or direct auto-answer.
- Answer consumption: translate selected ids, prompt-journal effects, stashed
  choices, and `PaymentDecision` values back into Forge.

Those surfaces are not the same. Several cost intents can share one client UI
shape, and one intent may later need a different client shape without changing
the Forge callback semantics.

## Decision

Introduce callback-family-local semantic cost plans, one family at a time.

The cost planning boundary is:

```text
Forge cost callback context -> semantic CostPlan -> PromptRequest / GRE request -> Forge answer
```

`CostDecision` and cost coordinators may gather Forge context and ask a planner
for a typed plan. The plan names one callback family's gameplay cost intent and
the facts needed to materialize a prompt or consume an answer. It is not a
protobuf request, not a match-layer lifecycle object, and not a shared taxonomy
for every cost family.

A callback-family plan may describe one prompt or a compound mechanic with more
than one payment mode. Compound plans should name the available modes and their
facts, then materialize each chosen mode through the existing prompt policy.
They should not become cross-family registries or move response lifecycle state
into the planner.

Future cost plans should be intent-shaped. Possible families include:

- `SacrificePermanents`
- `TapPermanents`
- `DiscardCards`
- `ExileFromGraveyard`
- `CollectEvidence`
- `ManaSourcePayment`
- `ChooseCostMode`

Compound plans are valid when the mechanic itself offers multiple payment
intents, such as choosing between a sacrifice mode and a graveyard-exile mode.

Outcome cases such as auto-select or unsupported should stay local to the
callback-family planner that needs them. They should not become broad buckets
that hide why a cost did or did not prompt.

`CostDecisionPlanner` can keep small request-policy helpers such as
`CostCardSelectionPlan`, but semantic cost planning should sit one level above
that policy: first decide the callback-local cost intent, then derive the prompt
semantic, weights, candidate refs, side-effect intent, or direct answer.

For example, a collect-evidence planner may return `CollectEvidenceCostPlan`.
Materialization then maps that plan to the existing `CostCardSelectionPlan` with
`PromptSemantic.SelectNCostCollectEvidence`, selection weights, and minimum
selection weight. `PromptSemantic` remains the bridge contract, not the cost
taxonomy.

## Responsibilities

Semantic cost planners own:

- Cost-family classification and precedence for a callback family.
- Gameplay intent names that explain why the cost is being paid.
- The minimal facts that request emission and answer consumption both need.
- Planner-level tests for adjacent generic and mechanic-specific shapes.

They must not own:

- `GREToClientMessage`, `SelectNReq`, `PayCostsReq`, or
  `CastingTimeOptionsReq` construction.
- Netty/session sending.
- `pendingInteraction` lifecycle.
- Response submission to `InteractivePromptBridge`.
- Prompt-journal mutation timing. A planner may name a side-effect intent, but
  the coordinator or visitor that already owns the lifecycle applies and clears
  it.

## Target-Shape Tests

The seam is paying for itself when these statements stay true:

- Adding a new sacrifice-like mechanic extends a sacrifice or graveyard-exile
  plan branch before touching request emission.
- Adding a compound cost exposes its payment modes in one callback-local plan
  before changing prompt routing.
- Changing a client UI shape for one cost intent does not change its Forge
  classification.
- Answer consumption can use fields materialized into `PromptRequest` instead
  of re-deriving cost intent from protocol fields. Store a plan key only after a
  concrete response path proves the materialized request is insufficient.
- Planner tests can prove callback context to cost intent without running a
  match session.
- Existing prompt semantics remain a materialization detail, not the primary
  cost taxonomy.

Wrong-shape signals:

- A generic `SelectCardsCost` plan hides sacrifice, collect evidence, and tap
  costs behind the same name.
- A nullable all-purpose cost context grows fields for every cost family.
- A plan contains protobuf builders, match-layer pending interaction state, or
  is stored directly in match-layer pending state.
- A response handler has to inspect Forge callback objects again because the
  plan lost information needed to consume the answer.

## Consequences

Cost work can move incrementally. Existing visitors may keep building
`PromptRequest`s while one narrow path proves the semantic plan boundary.

`PromptSemantic` remains useful as the bridge contract, but it is an output of
cost planning, not the cost model itself.

Some Forge callback code will stay complex. The goal is not to hide Forge cost
quirks; it is to make the cost intent explicit before client UI and answer
plumbing enter the picture.

Avoid a large framework rewrite. Each migration slice should move one existing
cost family behind a semantic plan, preserve request and answer behavior, and
stop if the boundary adds more ceremony than clarity.
