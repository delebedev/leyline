# Leyline Docs Index

Start here for the public technical docs.

## System

- [`architecture.md`](architecture.md) — modules, runtime services, wire frame, match lifecycle, state-mapping pipeline
- [`bridge-threading.md`](bridge-threading.md) — two-thread ownership model, snapshot timing, counter monotonicity, and bridge-side invariants
- [`forge-api-concepts.md`](forge-api-concepts.md) — key Forge API concepts for engine work: controller callbacks, SpellAbility chains, actions, costs, events, snapshots, and prompts
- [`simclient-iteration.md`](simclient-iteration.md) — fixed-seed simclient loop for prompt, advisor, and engine stall debugging

## Principles

- [`principles-design.md`](principles-design.md) — code-structure rules: bounded contexts, dependency direction, value objects
- [`principles-documentation.md`](principles-documentation.md) — how to document: rationale at the seam, one source of truth, frontmatter

## Decisions

- [`decisions/0001-prompt-interaction-planners.md`](decisions/0001-prompt-interaction-planners.md) — callback-specific planners for Forge prompt classification before `PromptRequest` construction
- [`decisions/0006-single-backbone-core-and-heads.md`](decisions/0006-single-backbone-core-and-heads.md) — leyline as a single backbone: a domain core + engine with native and web protocol heads

## Setup

- [`local-client-setup.md`](local-client-setup.md) — minimal local client configuration for end-to-end runs
