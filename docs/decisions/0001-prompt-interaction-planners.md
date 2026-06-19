---
summary: "ADR: centralize Forge prompt classification in callback-specific planners before building PromptRequests."
read_when:
  - "adding or changing PromptSemantic routing"
  - "working in PlayerController, TargetingCoordinator, CostDecision, StaticChoiceCoordinator, PromptClassifier, or TargetingHandler"
  - "deciding where Forge callback classification logic belongs"
---
# ADR 0001: Callback-Specific Prompt Interaction Planners

## Status

Accepted for incremental implementation.

This ADR chooses the direction for prompt-routing structure. It does not require a large rewrite before new prompt behavior can ship. Existing prompt behavior remains authoritative until migrated behind the planner boundary.

## Context

Leyline bridges a synchronous Forge rules engine to an asynchronous client protocol. Forge asks questions through `PlayerController` callbacks and cost-decision visitor methods; the client expects concrete GRE request families such as `SelectNReq`, `SelectTargetsReq`, `SearchReq`, `OrderReq`, `CastingTimeOptionsReq`, `PayCostsReq`, `OptionalActionMessage`, and `GroupReq`.

Those two surfaces are not isomorphic.

Forge callbacks are UI-shaped and overloaded. The same callback can mean different protocol interactions depending on the `SpellAbility`, cost type, selected zone, active reveal context, candidate count, or surrounding coordinator state. The client prompt shape often needs information that is not represented by the callback name alone.

The prompt path is roughly:

```text
Forge callback
-> PlayerController / CostDecision / coordinator
-> PromptRequest(semantic = ...)
-> InteractivePromptBridge
-> PromptClassifier
-> TargetingHandler / RequestBuilder
-> GRE request
-> MatchSession response handler
-> Forge return value
```

`PromptSemantic` is the current contract between an engine callback and a protocol prompt shape. It is useful, but the work of deciding the semantic should happen before callback context is lost.

## Problem Statement

Prompt routing combines concerns that are easy to blur at call sites:

- Intent classification: identify whether a Forge callback means search, reveal-choose, cost payment, static choice, order, targeting, auto-resolve, or a mechanic-specific selection.
- Prompt policy: decide whether to force a client prompt, auto-resolve a mandatory single option, include candidate refs, include unfiltered refs, attach a source id, or use a static list.
- Side-effect planning: decide whether the prompt needs prompt-journal effects such as searched-to-hand, legend victims, reveal lifecycle, collect-evidence context, or tap-affector bookkeeping.
- Protocol routing: map the classified prompt to a GRE request family and response handler.

When these decisions are scattered, a reader often has to trace from Forge callback to coordinator branch to `PromptRequest` fields to classifier to handler to response path to understand one prompt.

## Decision

Adopt callback-specific prompt interaction planners as the long-term architecture for Forge prompt classification.

Planners live under:

```text
matchdoor/src/main/kotlin/leyline/bridge/interaction/
```

The package owns the decision stage between Forge callbacks and `PromptRequest` construction. It classifies the callback context into a typed plan. Coordinators and Forge override classes use that plan to build the existing `PromptRequest` and return Forge objects as they do today.

The boundary is:

```text
Forge callback context -> callback-specific planner -> typed prompt plan -> PromptRequest -> existing routing
```

Planners may depend on Forge types and bridge handoff types. They must not depend on `match`, `game.bundle`, protobuf builders, or session response handlers.

## Planner Responsibilities

Each planner owns precedence for one Forge callback family.

For example, a `chooseCardsForEffect` planner should answer:

- Does an active reveal override normal selection routing?
- Is this a mechanic-specific resolution choice that must prompt with one candidate?
- Should candidates be represented as selectable refs?
- Should the source card be attached to the prompt?
- Should generic mandatory single-option selection auto-resolve?

Plans should start small and callback-local. Shared policy types are acceptable only after repeated plan fields prove stable. The design should grow from migrated call sites, not from an abstract taxonomy guessed upfront.

## Consequences

`TargetingCoordinator` becomes orchestration rather than a growing catalog of Forge script idioms. Its job should be to gather context, ask a planner, build a request, and translate the response back to Forge objects.

`PromptSemantic` remains useful, but it becomes the output of an explicit planning stage rather than the planning stage itself.

Prompt precedence becomes visible in one function per callback family. End-to-end tests still matter for prompt emission and response handling, but planner tests can cover callback context to plan mapping directly.

GRE envelope construction remains protocol-specific. `SelectNReq`, `PayCostsReq`, `OrderReq`, `SearchReq`, and static-list envelopes still need dedicated builders and tests.

Forge callback quirks remain real. Some `SpellAbility` scripts will still require careful shape inspection.

Prompt journal side effects still need lifecycle discipline. The planner can make them visible, but the coordinator or bridge still has to apply and clear them correctly.

## Alternatives Considered

### Universal Interaction Intent Resolver

Rejected as the starting point. A single broad context object would likely become a nullable bag because callback families have different inputs, return types, defaulting behavior, and side effects.

### Route-Owned Prompt Handlers

Rejected for now. Moving detection, request construction, response mapping, and side effects into route objects would make route ordering a hidden rule engine and would couple Forge classification to protocol emission.

### SpellAbility Semantic Adapter

Useful as a helper, not as the top-level architecture. Prompt routing is not only a `SpellAbility` problem: cost visitors, static choices, binary confirmations, active reveal state, and candidate-count policy all need context outside the `SpellAbility`.

### Continue Adding PromptSemantic Branches At Call Sites

Acceptable for very small isolated fixes, but not the direction for prompt-routing generalization. It leaves classification, prompt policy, and side effects scattered.

### Card-Name Or Prompt-Text Routing Tables

Rejected except as temporary diagnostics. Card names and prompt labels do not describe the underlying rules shape.

## Migration Plan

Migrate incrementally. Each step should preserve existing behavior and add planner-level tests for moved rules.

1. Add `bridge.interaction` with one planner for the next prompt-routing change.
2. Start with `chooseCardsForEffect`, then migrate `chooseEntitiesForEffect` and `chooseSingleEntityForEffect` rules.
3. Move reusable `SpellAbility` shape predicates into small helpers such as `SpellAbilityShapes`.
4. Keep `PromptRequest`, `PromptSemantic`, `PromptClassifier`, `TargetingHandler`, and route APIs stable while the boundary proves itself.
5. Migrate cost-specific prompt decisions only after the first planner shape has proven stable.
6. Stop after each coherent slice if the new boundary is not paying for itself.

## Testing Strategy

Planner tests should be small and table-driven where possible.

Preferred test layers:

- Pure planner tests for callback context to plan mapping.
- Coordinator tests for request construction and Forge return values.
- Match-flow tests when response handling, bridge blocking, or engine state transitions matter.
- Acceptance tests only for player-visible prompt flows that need end-to-end confidence.

Each planner test should include negative cases. A classifier that recognizes one mechanic-specific shape must also prove that adjacent generic shapes remain generic.

## Implementation Notes

The first landings apply this boundary to selected card/entity callbacks and cost-decision prompt classification under `leyline.bridge.interaction`. The durable shape is the callback-local planner boundary, not the exact class inventory.

Typed plan fields currently cover:

- Candidate reference policy: whether a prompted request includes selectable refs, and whether resolution prompts mirror them into unfiltered refs.
- Source id policy: whether request materialization attaches the host card id.
- Mandatory-choice policy: whether already-satisfied mandatory card choices auto-resolve or still prompt.
- Auto-return policy: whether `chooseEntities` can return all options when the requested selection is already satisfied.
- Special route policy: explicit non-generic routes such as mutate top-card, active reveal helper, auto-return-first, and normal prompt handling.

What stayed in `TargetingCoordinator`:

- Applying prompt-journal side effects for search, learn, legend rule, and reveal flows.
- Calling special helper methods such as mutate top/bottom and active reveal choice.
- Translating prompt responses back to Forge `Card` and `GameEntity` values.
- Building `PromptRequest` instances where the coordinator already has labels, candidate refs, and fallback behavior in scope.

Cost decision migration is intentionally partial: planners classify cost-card prompt semantics and convoke/improvise prompt policy, while `CostDecision` and `CostPaymentCoordinator` still build requests, translate responses, and own prompt-journal side effects.

What did not migrate yet:

- Static choice prompts.
- Confirm-action prompts.
- Request construction and side-effect execution for cost prompts.
- A universal `InteractionIntent` model.
- A route registry or prompt-routing DSL.
- A generic side-effect runner.

The current seam is intentionally small: planners classify callback context and expose typed policies; existing bridge and protocol routing APIs remain stable.
