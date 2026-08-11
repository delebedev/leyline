# Leyline Docs Index

Start here for the public technical docs.

## System

- [`architecture.md`](architecture.md) — modules, runtime services, wire frame, match lifecycle, state-mapping pipeline
- [`architecture-direction.md`](architecture-direction.md) — accepted destination for one Forge runtime owner, typed safe-point inputs, pure projection, and ordered delivery
- [`ai-solved-acceptance.md`](ai-solved-acceptance.md) — turning direct Forge-AI puzzle solutions into backend-neutral scripted acceptance suites
- [`bridge-threading.md`](bridge-threading.md) — current execution domains, critical sections, projection timing, counters, and bridge-side invariants
- [`forge-api-concepts.md`](forge-api-concepts.md) — key Forge API concepts for engine work: controller callbacks, SpellAbility chains, actions, costs, events, snapshots, and prompts
- [`puzzle-harness.md`](puzzle-harness.md) — puzzle fixture strengths, snapshot limits, and when to reach states through setup actions
- [`simclient-iteration.md`](simclient-iteration.md) — fixed-seed simclient loop for prompt, advisor, and engine stall debugging

## Principles

- [`principles-design.md`](principles-design.md) — code-structure rules: bounded contexts, dependency direction, value objects
- [`principles-documentation.md`](principles-documentation.md) — how to document: rationale at the seam, one source of truth, frontmatter

## Decisions

- [`decisions/0001-prompt-interaction-planners.md`](decisions/0001-prompt-interaction-planners.md) — callback-specific planners for Forge prompt classification before `PromptRequest` construction
- [`decisions/0006-single-backbone-core-and-heads.md`](decisions/0006-single-backbone-core-and-heads.md) — leyline as a single backbone: a domain core + engine with native and web protocol heads
- [`decisions/0008-forge-zone-operation-context.md`](decisions/0008-forge-zone-operation-context.md) — immutable Forge cause identity on ordered zone moves, with observable snapshot fallback
- [`decisions/0009-reuse-forge-human-cost-decisions.md`](decisions/0009-reuse-forge-human-cost-decisions.md) — shared Forge human cost rules with frontend-specific controller choice hooks
- [`decisions/0010-bind-priority-actions-at-projection-source.md`](decisions/0010-bind-priority-actions-at-projection-source.md) — executable priority commands bound beside their protocol actions, without reverse reconstruction
- [`decisions/0011-preserve-ability-definition-identity.md`](decisions/0011-preserve-ability-definition-identity.md) — stable ability definitions separated from unique runtime invocations across events, stack projection, and prompts
- [`decisions/0012-bind-prompt-routes-once.md`](decisions/0012-bind-prompt-routes-once.md) — `ResolvedPromptRoute` bound once and carried through emission, re-prompting, and response handling
- [`decisions/0013-finalize-annotation-frames-once.md`](decisions/0013-finalize-annotation-frames-once.md) — collect all state-frame annotation inputs before one ordering and transient-ID finalization pass
- [`decisions/0014-command-yield-engine-boundary.md`](decisions/0014-command-yield-engine-boundary.md) — partially superseded decision that established Forge confinement and value-only projection inputs
- [`decisions/0015-functional-core-imperative-shell.md`](decisions/0015-functional-core-imperative-shell.md) — deterministic imperative Forge runtime around a value-only functional projection core

## Setup

- [`local-client-setup.md`](local-client-setup.md) — minimal local client configuration for end-to-end runs
