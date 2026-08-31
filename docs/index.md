---
summary: "Index of Leyline architecture, operational guides, principles, and retained architecture decisions."
read_when:
  - "finding the canonical document for a system, workflow, or decision"
  - "reviewing documentation ownership or stale cross-references"
---
# Leyline Docs Index

## System

- [`architecture.md`](architecture.md) — current modules, runtime ownership,
  interaction cuts, projection, and delivery
- [`bridge-threading.md`](bridge-threading.md) — current cross-thread invariants
  and transitional ownership
- [`forge-api-concepts.md`](forge-api-concepts.md) — stable Forge concepts used
  by engine adapters

## Testing and operation

- [`logging.md`](logging.md) — runtime diagnostic event contract and output
  policy
- [`ai-solved-acceptance.md`](ai-solved-acceptance.md) — turning direct
  Forge-AI puzzle solutions into backend-neutral scripted suites
- [`puzzle-harness.md`](puzzle-harness.md) — direct puzzle state, history-sensitive
  setup, and Full-state boundaries
- [`simclient-iteration.md`](simclient-iteration.md) — fixed-seed failure
  classification and reproduction loop
- [`local-client-setup.md`](local-client-setup.md) — local native-client setup

## Principles

- [`principles-design.md`](principles-design.md) — code structure and dependency
  direction
- [`principles-documentation.md`](principles-documentation.md) — documentation
  ownership and lifecycle

## Decisions

All ADRs remain part of the decision history, including superseded decisions.

- [`0001`](decisions/0001-prompt-interaction-planners.md) — callback-specific
  prompt interaction planners
- [`0002`](decisions/0002-targetinghandler-interaction-lifecycles.md) — split
  interaction handling by lifecycle
- [`0003`](decisions/0003-actionmapper-action-family-boundaries.md) — action
  mapper family boundaries
- [`0004`](decisions/0004-bundle-and-request-builder-boundaries.md) — bundle and
  request-builder responsibilities
- [`0005`](decisions/0005-cost-decision-semantic-plans.md) — superseded cost
  semantic-plan design
- [`0006`](decisions/0006-single-backbone-core-and-heads.md) — one backbone with
  native and web heads
- [`0007`](decisions/0007-displayed-cost-and-controller-contexts.md) — displayed
  cost and non-interactive controller scopes
- [`0008`](decisions/0008-forge-zone-operation-context.md) — ordered zone-operation
  context
- [`0009`](decisions/0009-reuse-forge-human-cost-decisions.md) — shared Forge
  human cost decisions
- [`0010`](decisions/0010-bind-priority-actions-at-projection-source.md) — bind
  executable actions at projection source
- [`0011`](decisions/0011-preserve-ability-definition-identity.md) — stable
  ability-definition identity
- [`0012`](decisions/0012-bind-prompt-routes-once.md) — bind prompt routes once
- [`0013`](decisions/0013-finalize-annotation-frames-once.md) — finalize transient
  annotation frames once
- [`0014`](decisions/0014-command-yield-engine-boundary.md) — partially
  superseded command/yield boundary
- [`0015`](decisions/0015-functional-core-imperative-shell.md) — functional
  projection core and imperative Forge shell
- [`0016`](decisions/0016-drive-headless-through-match-port.md) — route
  in-process drivers through the match-connection port
- [`0017`](decisions/0017-autonomous-proof-ownership.md) — separate prompt
  decisions from proof execution
