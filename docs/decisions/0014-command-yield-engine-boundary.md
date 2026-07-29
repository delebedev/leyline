---
summary: "ADR: isolate the live Forge graph behind value-only commands and immutable yields, with one serial match owner committing projection and delivery."
read_when:
  - "changing engine/session ownership or bridge threading"
  - "introducing an engine worker, match supervisor, frame transaction, or outbox"
  - "deciding whether a runtime concern belongs inside or outside the Forge execution domain"
---
# ADR 0014: Constrain Forge Behind a Command/Yield Boundary

## Status

Accepted direction; implementation is incremental.

## Context

Leyline adapts a synchronous, callback-driven Java rules engine to asynchronous
client connections. Before this decision was implemented, Forge mutation,
observation, protocol construction, sequencing, and delivery could advance
through several cross-thread paths. Shared counters, a projection cursor,
pending futures, signals, locks, and playback ordering repair preserved
coherence while distributing match authority.

Recent decisions bind executable actions, ability identity, prompt routes, and
annotation finalization at their originating seams. They produce the values
needed for a narrower runtime boundary. The next architectural constraint is
to stop sharing the live engine graph and protocol progression across that
boundary.

## Decision

Converge the match runtime on the normative shape in
[`architecture-direction.md`](../architecture-direction.md):

- a supervisor owns execution-domain lifecycle and worker cleanup after a
  terminal match decision;
- one serial match owner reduces client commands and engine yields, decides
  semantic match lifecycle, and commits terminal output;
- one engine worker exclusively owns the live Forge object graph;
- the worker accepts typed, value-only commands and returns immutable yields;
- a pure compiler derives frame plans and next projection state from immutable
  inputs;
- the match owner commits a successful plan once and appends its output to one
  ordered outbox;
- native and web transports remain thin adapters over the same match runtime.

The command/yield contracts must not contain mutable Forge objects, callbacks,
futures, channels, or protocol-counter references. They should be suitable for
an eventual process boundary without requiring one now.

Exact executable action handles remain in a worker-owned, priority-window table
keyed by opaque tokens. An action yield carries a token and immutable projection
facts; the response returns the token; the worker resolves and consumes the
retained handle. Completion, supersession, cancellation, and failure clear the
table. This preserves ADR 0010's exact bind-at-source command without passing a
mutable `SpellAbility` through session or projection state. It also relocates
the pending-window ownership that ADR 0010 assigned to the session into the
serial match owner and worker.

The same discipline retires the other live crossings: the targeting-prompt
`SpellAbility` retained for re-prompt legality becomes a worker-resolved
handle behind a re-validation command, and priority candidate lists cross the
boundary as immutable facts plus tokens instead of live ability references.

Forge remains the authority for rules, legality, costs, engine identity,
causes, and final game state. Leyline remains the authority for interaction
binding, client projection, frame cuts, visibility, protocol IDs, and delivery.

## Migration and maintenance discipline

The current contracts in
[`bridge-threading.md`](../bridge-threading.md) remain mandatory until
ownership has actually moved. Further slices must:

1. identify the responsibility and its current authority;
2. introduce an immutable boundary value or single-owner operation;
3. preserve existing frame-cut and client-visible behavior;
4. route every producer through the new authority;
5. delete the superseded write path or state its concrete deletion condition;
6. prove retry and failure behavior before committing state.

An actor facade around still-shared state is not a completed slice. Nor is a
pure mapper useful if callers can continue mutating its counters, cursor, or
output afterward.

## Consequences

Match progression, projection commit, and delivery order become ordinary
serial logic rather than a cross-thread protocol. Pure projection fixtures can
cover a much larger part of protocol correctness, while Forge-backed tests
focus on rule execution and coherent observation cuts.

The boundary creates explicit data types and may initially add adapters while
old paths are retired. Immutable observations can be larger than direct reads,
and every required projection fact must be extracted before the worker yields.
These costs are accepted because they make ownership and missing data visible.

A dedicated thread remains a valid worker implementation. Safe hard
termination, memory isolation, and engine reconstruction are not provided by
this decision. A child-process worker, checkpoint format, or deterministic
engine replay would each require a separate decision and proof.

## Non-goals

- Rewriting the engine runtime in one change.
- Replacing Forge as the rules authority.
- Making the engine module protobuf-free.
- Introducing a generic engine or prompt DSL.
- Moving every callback into one universal event type.
- Changing annotation emission, bracketing, or frame boundaries as part of an
  ownership migration.
- Promising transparent recovery after arbitrary engine failure.
- Adopting process isolation, virtual threads, or multi-tenant scheduling now.

## Alternatives considered

### Keep the shared cross-thread bridge indefinitely

Rejected as the target. It is correct when its full contract is preserved, but
new projection and lifecycle work continues to depend on shared atomics,
signals, cursors, and ordering repair across owners.

### Add a match actor in front of the existing bridge

Rejected unless ownership moves with it. Serializing inbound messages while
engine callbacks still build protocol output and mutate shared projection state
adds a queue without removing coordination.

### Build protocol frames directly in Forge callbacks

Rejected. Callback timing is the right place to cut a coherent observation,
not to own client visibility, counters, retries, or transport delivery.

### Start with one Forge process per match

Deferred. Process isolation may eventually provide stronger termination and
resource boundaries, but it is not required to establish value-only contracts,
single ownership, pure projection, or ordered delivery.

## Relationship to earlier decisions

This decision refines the inside of the shared engine established by
[`ADR 0006`](0006-single-backbone-core-and-heads.md). It amends
[`ADR 0010`](0010-bind-priority-actions-at-projection-source.md): the
bind-at-source rule is kept, while the exact executable handle and the
pending-window ownership 0010 assigned to the session move into the
worker-owned token table. It
also relies on the producer-bound values established by
[`ADR 0011`](0011-preserve-ability-definition-identity.md), and
[`ADR 0012`](0012-bind-prompt-routes-once.md), and extends the single-finalizer
principle from [`ADR 0013`](0013-finalize-annotation-frames-once.md) to match
projection commit and delivery.
