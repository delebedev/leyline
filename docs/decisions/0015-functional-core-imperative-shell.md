---
summary: "ADR: keep Forge as a deterministic imperative runtime and move protocol projection into a value-only functional core."
read_when:
  - "changing Forge ownership, GamePlayback, MatchSession, StateMapper, or projection commit"
  - "deciding where an engine observation may be materialized"
  - "making protocol behavior testable from synthetic state and ordered facts"
  - "introducing an engine command, opaque action token, runtime thread, or output queue"
---
# ADR 0015: Functional Projection Core, Imperative Forge Shell

## Status

Accepted. The functional projection core and match-scoped blocking-prompt
ownership are implemented. Broader runtime convergence remains incremental.

The implemented milestone provides:

- bridge-free state projection over immutable snapshots, ordered facts,
  cut-scoped inputs, and prior `ProjectionState`;
- one revision-checked projection transition for client identity and lifecycle
  history;
- mutation-complete ordinary and combat cuts owned by `MatchCutCoordinator`;
- typed match-scoped owners for blocking prompt families, with exact Forge
  handles retained behind immutable client-facing values.

Explicit remaining work includes `GameBridge` orchestration, the secondary
live-state action-construction path, mulligan and lifecycle/terminal output,
`MatchSession` convergence, and atomic multi-view compilation. These are not
requirements for treating the functional projection and prompt-ownership
milestone as implemented.

This ADR is the durable decision record: it owns the rationale, fixed boundary,
and rejected alternatives. The evolving normative runtime contract, migration
steps, and proof checklist live in
[`architecture-direction.md`](../architecture-direction.md). When operational
details change within this decision's constraints, update that document rather
than restating them here.

This decision partially supersedes
[`ADR 0014`](0014-command-yield-engine-boundary.md). It keeps Forge confinement,
value-only inputs, pure projection, atomic commit, and one logical output order.
It replaces ADR 0014's mandatory supervisor / serial-owner / engine-worker /
outbox topology with one default logical owner: the per-match Forge runtime
thread.

## Context

Leyline combines two systems with different interaction models:

- Forge is a synchronous, callback-driven, mutable rules engine. It is
  deterministic for a fixed initial game, isolated random source, and ordered
  command sequence. The current process-global random source limits that claim
  to isolated match execution until randomness becomes game- or runtime-owned.
- The client protocol is an asynchronous UI contract. It needs viewpoint-
  specific state, intermediate frames, prompts, stable object identities,
  monotonically ordered IDs, and replies that resume blocked engine callbacks.

The current bridge makes the combination work, but ownership is distributed.
The Forge thread mutates rules state and can also build playback output inside
synchronous event subscribers. Session entrants build ordinary output and
submit answers from transport, timer, test, and auto-advance threads. A
spectator pump drains another path. Shared counters, projection cursors,
futures, semaphores, locks, and queues preserve the resulting order.

Four facts must be separated when choosing a replacement:

1. **Engine determinism is useful but not sufficient.** Equal seeds and
   commands can make Forge repeatable. Determinism does not make a mutable
   collection safe to read while it is changing, make an event callback the
   end of its enclosing operation, or make hidden tracker writes reversible.
2. **Resulting state and ordered facts are both required.** A snapshot says
   what is true at a cut. Ordered facts preserve cause, intermediate
   operations, and bracketing that cannot be reconstructed from state alone.
   Neither replaces the other.
3. **Some operations require live engine knowledge.** Projection facts should
   be materialized as values. Legality checks and execution that truly require
   a live Forge handle remain in the runtime and cross the value boundary as
   opaque tokens plus explicit commands.
4. **Most coordination pressure comes from the UI boundary.** Prompts,
   intermediate animation frames, viewpoint projection, output ordering, and
   blocked controller callbacks are legitimate requirements. Multiple logical
   owners for those requirements are not.

The desired testing seam is a value pipe. Protocol behavior should normally be
testable by constructing projection state, a synthetic engine snapshot, and
ordered facts, then invoking the compiler without starting Forge or building a
`GameBridge`.

## Evidence from the withdrawn runtime slice

PR #316 introduced ADR 0014's owner/worker destination. PRs #323 through #331,
#349, and #351, plus their follow-up fixes, implemented a substantial part of
that topology in commits `386b24d7` through `d00c1d9b`. PR #355 withdrew all
sixteen commits after two manifestations of the same ownership error:

- a combat-damage burst was reserved before the Forge thread had finished
  appending facts for the logical operation, collapsing multiple intended
  frames into a later frame and moving the client state chain backwards;
- another game iterated and mutated the same event collection concurrently,
  ending in `ConcurrentModificationException`.

The mechanism was:

```text
Forge operation
  -> synchronous event subscriber appends facts
  -> another owner reserves or iterates the still-open fact sequence
  -> Forge resumes the enclosing operation and appends more facts
```

Protecting reservation did not protect production. Narrowing one reservation
path reduced the failure frequency but did not remove the race because the
producer still declared a cut from inside an arbitrary visitor callback.

The separate, unmerged observation-first stack in PRs #333 through #339 also
provided useful evidence. Immutable action and prompt facts, value-only
crossings, and lower-altitude projection tests worked. A broad
`EngineObservation`, however, eagerly built a full snapshot and priority
candidate view for cuts that did not need both. In the evaluation suite,
engine-step time rose from approximately 20.8 seconds to 99.5 seconds. The
valuable result is typed values at the boundary, not one universal eager
observation or a second runtime owner.

The withdrawn implementation therefore disproves the migration order and the
assumption that an event subscriber is a generally safe yield point. It does
not disprove the value-only functional core.

## Current seams

The implemented milestone is visible in current types:

- `StateMapper.buildDraft` and `StateProjectionCompiler.compileOneViewer` now
  form a bridge-free boundary over immutable snapshots, ordered facts, scoped
  projection facts, and a `StateProjectionEnvironment`.
- `PureDiffReplayTest` covers replay from an explicit `StateFrameInput`, prior
  projection state, and intent. `StateMapperValueBoundaryTest` exercises the
  same direct value boundary without Forge or `GameBridge`.
- `GameBridge` still materializes frame inputs and owns action, combat, and
  event-to-fact seams; those remain incremental migration work rather than
  hidden mapper reads.
- `MatchCutCoordinator` now owns journal close, immutable cut materialization,
  compilation, projection commit, and viewer feed publication for migrated
  playback, Visible priority/action windows, SyncOnly state cuts, explicitly bound Targeting,
  Search, Top/Bottom Order and Scry/Surveil Grouping windows, card-backed (including hidden-library Dig resolution, complete chooser-visible card resolution, and Learn), static-enum, and reveal-backed SelectN windows, all PayCosts windows, and Optional, Numeric, and Damage blocking interactions. Safe direct
  priority skips close no journal and allocate no protocol state. Event
  subscribers only aggregate cut requests; session handlers drain committed
  batches and submit correlated values or opaque action tokens.
- `MatchPromptRuntimeSet` is the one prompt-runtime registry beneath that
  coordinator. It provides one immutable bridge binding and centralizes
  pending visibility, reset, terminal teardown, and delivery-failure dispatch.
  The closest single-window families share correlation, timeout arbitration,
  and retirement while retaining family-specific value freezing and
  materialization.
- Forge's target-selection producer binds `TargetSelection` and freezes exact
  stack-object candidates before publication; candidate-backed `Generic` card
  choices bind the SelectTargets-compatible runtime, preserving existing
  toggle/echo/submit behavior and exact handles without claiming protocol
  conformance. Runtime ownership is selected from this immutable route, while
  a nullable live targeting ability remains only in the Forge shell for legality
  and final resolution.
- `PhaseHandler` provides broad step completion plus narrow UI-neutral combat
  hooks. Incomplete, mixed, or chooser-hidden resolution choices use the named
  `UnclassifiedEntityChoice` policy, which refuses strictly before applying an
  optional-empty or required stable-prefix synchronous default. Candidate-free
  Generic choices use the same explicit synchronous policy, while non-library
  ordering returns its input without allocating a prompt. Modal choice now has
  a coordinator-owned runtime; mulligan, lifecycle output, and multi-view
  compilation remain outside the coordinator boundary.

The current milestone completes blocking prompt-response ownership, including
the residual card compatibility path. It does not claim whole-runtime
convergence: `GameBridge` orchestration, secondary action construction,
lifecycle and terminal output, `MatchSession`, and multi-view compilation remain
separate incremental work.

## Decision

### 1. The Forge runtime thread is the default logical match owner

One per-match runtime thread exclusively owns:

- the live Forge object graph;
- engine callbacks and retained executable handles;
- the ordered fact journal for the current engine operation;
- projection and logical protocol state;
- frame compilation, logical commit, and output sequence assignment.

The same thread advances Forge, materializes a typed input at a safe point,
calls the functional core synchronously, installs the returned state, and
publishes the committed frame batch. It does not expose the mutable Forge graph
to another logical owner.

Transport and scheduler threads may decode client input and complete a bounded
reply primitive that wakes a blocked controller callback. That primitive is an
imperative-shell detail. Those threads must not read Forge, compile frames,
advance projection state, allocate protocol IDs, or decide semantic match
progression.

A delivery executor may flush an immutable committed batch. Ordering is
assigned before handoff; the delivery executor cannot reorder batches or
advance logical state. A lifecycle supervisor may create, observe, cancel, and
clean up runtime threads. It is an operational owner, not a second match-state
owner.

### 2. Forge is a deterministic imperative kernel, not the functional core

For architecture and testing, treat an isolated Forge adapter as a
deterministic transducer:

```text
initial game + random seed + ordered engine commands
    -> ordered typed engine inputs
```

This permits fixed-seed adapter tests, scripted drivers, and replacement of the
engine with a fake sequence in runtime tests. It does not require Forge's
internal object graph to be immutable or serializable.

Forge currently stores randomness in a process-global source. Before
concurrent matches can claim independent deterministic replay, make randomness
game- or runtime-owned through a small Forge seam. Until then, deterministic
replay proof runs with one active match; concurrent game matrices prove runtime
correctness, not per-match replay equality.

The functional core begins after Forge has materialized immutable information.
This ADR authorizes protocol projection only. Rules execution, blocked
interaction handling, semantic match lifecycle, cancellation, and delivery
remain in the imperative shell. Moving semantic lifecycle into another pure
reducer requires separately demonstrated duplication and a separate decision.

### 3. Projection is one explicit value transition

The central operation is conceptually:

```text
compile(
    environment: ProjectionEnvironment,
    state: ProjectionState,
    input: FrameInput,
    viewers: OrderedViewerSet,
) -> ProjectionTransition(nextState, viewerBatches)
```

`ProjectionEnvironment` contains immutable or read-only reference data such as
the card index and protocol configuration. It contains no live game, clock,
reply primitive, transport, allocator, or mutable tracker.

`ProjectionState` contains shared match-projection state plus a map of
viewer-specific state. It owns every logical value whose next value depends on
the previous frame, including:

- previous per-view projected snapshots;
- client object and ability identity allocation;
- annotation, persistent-effect, reveal, crew, delayed-trigger, and similar
  projection lifecycles;
- bound action and prompt identities;
- game-state IDs, message sequencing needed by compilation, and frame cursor
  state;
- synthetic-state reconciliation needed by pre-mutation prompts.

Shared client identities and global sequencing are allocated once while the
compiler folds viewers in a stable declared order over private tentative
state. Visibility and prior baselines remain viewer-specific. The compiler
returns one transition containing every `ViewerBatch`; no viewer batch becomes
visible until all viewer projections succeed and the whole cut commits once.

The exact grouping may use smaller value types. The invariant is that compile
does not mutate a `GameBridge`, tracker, allocator, registry, cursor, or input
object. Monotonic allocation is still state and must be returned in
`nextState`; it is not an exception to purity.

`ProjectionTransition` is a tentative value until commit. If compilation
throws, validation fails, or the caller discards the result, no logical state
has advanced and the immutable pending cut remains available. A successful
transition is installed once before any viewer batch is made visible.

### 4. `FrameInput` is a typed, cut-specific value family

Every input includes the information common to one projection cut:

- an immutable resulting-state snapshot when the cut changes or re-emits
  projected state;
- ordered facts accumulated for that cut;
- a typed cut reason and command correlation;
- stable source identities required by projection.

Cut-specific families add only what they need. Illustrative families are:

- `StateProgressed`: resulting snapshot plus ordered facts;
- `PriorityOffered`: snapshot, facts, immutable action views, and opaque action
  tokens;
- `PromptRequested`: snapshot, facts, typed prompt data, and an opaque retained-
  handle token when revalidation is needed;
- `LifecycleChanged`: mulligan, game-over, reset, or intermission facts;
- `SyntheticIntent`: explicit pre-mutation state needed by a client prompt.

These names are illustrative, not a requirement for one universal hierarchy.
Prefer a small sealed family at each demonstrated seam over a nullable property
bag. Do not enumerate priority candidates, materialize prompt details, or build
an expensive snapshot at a cut that cannot use them.

`FrameInput` must not contain mutable Forge objects, callbacks, futures,
channels, protocol counters, or closures that read live engine state later.

### 5. Snapshot and ordered facts remain separate inputs

The snapshot is the authoritative resulting state for projection. The fact
journal is the authoritative ordered explanation of operations since the prior
committed cut.

Examples of information that belongs in facts rather than inferred from two
snapshots include:

- the cause and ordering of zone moves;
- one logical operation that visits several objects;
- transient combat or damage groupings;
- source/affected relationships needed by annotations;
- an operation that begins and ends with equal visible state.

The fact journal is not a second rules engine. It carries only information
needed to project Forge's completed work. Projection may synthesize deterministic
facts from two snapshots when that is the defined source, but it must not query
the live Forge graph to repair an incomplete input.

### 6. Event subscribers request cuts; safe points materialize them

A synchronous Forge event subscriber runs while the enclosing engine operation
may still have work to do. It may append an immutable fact and request a cut.
It is not, by itself, proof that the logical operation is complete.

A safe point is an explicit Forge-adapter seam with this contract:

1. it runs on the Forge runtime thread;
2. the relevant mutation burst has completed;
3. the fact journal for the cut can be closed without later facts joining the
   same logical operation;
4. the typed `FrameInput` is fully materialized before Forge resumes;
5. the projection transition commits or fails before another cut advances the
   same logical state.

The fact journal follows an explicit runtime state machine:

```text
OpenJournal
  -> PendingCut(immutable FrameInput, prior ProjectionState)
  -> Committed(next ProjectionState, viewer batches)
     or Retrying(the same pending cut)
     or TerminalFailure
```

Closing a journal creates `PendingCut`; it does not consume the input. Forge
does not resume and a new journal does not open until that pending cut commits.
A retry uses the same immutable input and prior state. If materialization or
compilation cannot be retried, the runtime terminates the match rather than
dropping causal facts and continuing from an advanced Forge graph.

Likely safe points include the end of a complete `PhaseHandler.mainLoopStep`
and immediately before a controller callback blocks for client input. Those
points are not sufficient for every intermediate UI frame. When the client
must see a state between two Forge operations, add a narrow Forge hook after
the relevant logical mutation completes. Do not use an arbitrary EventBus
visitor merely because it runs on the correct thread.

Small, coherent Forge changes are explicitly allowed to establish these hooks.
The hook should expose timing, not client protocol types or projection rules.

### 7. Deep engine access has two explicit destinations

When existing projection code reaches through `GameBridge`, classify the need:

1. **Projection information:** bind it where the information is strongest or
   materialize it at the safe point as an immutable field. Missing information
   is an input-shape error; the core must not silently read Forge.
2. **Engine operation:** retain the live handle in a runtime-owned, bounded
   table and expose an opaque token. An answer returns the token; the runtime
   resolves, revalidates, executes, consumes, or rejects it on the Forge
   thread.

Priority actions follow the second shape. The runtime keeps the exact
`SpellAbility` only for the priority window that produced it. The input carries
the token plus immutable projection facts. Target revalidation and similar
operations use the same discipline.

Opaque tokens do not authorize the pure core to call back into Forge. They are
identities carried through protocol state and returned to the shell.

### 8. Output has one logical order, not one mandated queue implementation

All gameplay output receives its logical order from the same runtime commit:
ordinary state changes, playback, prompts, lifecycle transitions, spectator
updates, and terminal output. No transport or side queue may repair ordering
afterward.

An in-memory queue, append-only outbox, or synchronous send can implement the
handoff. A durable outbox is optional until retry or recovery requirements
justify it. The invariant is one commit order and immutable batches, not a
specific queue class.

Connection negotiation and authentication may remain head-local when their
meaning does not depend on match progression. Delivery failure, backpressure,
and disconnect notification are shell concerns; they do not permit a head to
advance or rewrite projection state.

## Implementation constraint

Migration order is part of this decision: make projection honestly pure, then
introduce typed cut inputs, then establish safe points, then move logical
ownership, and only then delete superseded coordination. Information and
purity must precede topology.

The maintained slice definitions, pending-cut rules, test altitudes,
performance checks, and convergence checklist live in
[`architecture-direction.md`](../architecture-direction.md). At minimum they
must prove that equal immutable inputs produce equal complete multi-view
transitions, a failed cut retains its exact input and does not resume Forge,
subscribers cannot expose a still-open fact sequence, and all viewer batches
receive order from one atomic logical commit.

## Consequences

Most protocol behavior becomes cheap to test. A test can push a synthetic
snapshot and ordered facts into the compiler and inspect both the frame batch
and next projection state. Forge-backed tests concentrate on rules execution,
fact production, safe-point timing, and adapter agreement.

The runtime has one fewer semantic handoff. Projection cannot race the Forge
operation that produced its input because it runs synchronously on the same
owner after a declared safe point.

The value boundary becomes stricter. Adding a protocol feature may require a
new typed fact or projection-state field instead of a convenient live read.
That cost is intentional: missing information becomes visible and testable.

Projection work now consumes time on the Forge runtime thread. It must remain
bounded, use cut-specific inputs, and avoid unnecessary enumeration. If later
evidence requires parallel compilation, it must operate on a complete immutable
input and preserve single commit authority; parallelism is not the default.

Deterministic Forge execution improves repeatability but does not promise
transparent recovery, arbitrary cancellation, or process reconstruction.

## Non-goals

- Rewriting Forge as an immutable or purely functional engine.
- Replacing Forge as the rules and legality authority.
- Moving semantic match lifecycle into a pure reducer under this decision.
- Introducing a generic engine-query, prompt, or effects DSL.
- Mirroring the complete Forge object model in `FrameInput`.
- Changing client-visible frame semantics while moving ownership.
- Moving every prompt or action family in one change.
- Requiring a durable outbox, child process, virtual thread, or distributed
  runtime.
- Promising restart from an arbitrary failed engine state.

## Alternatives considered

### Keep ADR 0014's separate serial owner and worker as the mandatory target

Rejected as the default. It can provide process or resource isolation, but
purity and rapid tests do not require a second match-state owner. Without a
proven safe point it adds an ordering boundary around still-shared work. A
future decision may reintroduce a separate worker when an operational
requirement justifies the extra command/yield hop.

### Project from snapshots only

Rejected. Equal before/after state can hide a meaningful operation, and state
alone loses cause, ordering, and grouping required by protocol annotations and
intermediate frames.

### Project from events only

Rejected. Events are not a complete rules-state model and do not provide a
stable per-view baseline for diffs, visibility, or object lifecycle.

### Permit the core to query Forge for missing facts

Rejected. It restores temporal coupling, prevents synthetic fixture tests, and
makes retry depend on a live graph that may already have advanced.

### Rewrite Forge into a functional state transition

Rejected. The scope is large, the project already has a capable rules engine,
and the desired protocol test seam can be obtained with a deterministic
imperative adapter plus immutable inputs.

## Relationship to earlier decisions

[`ADR 0006`](0006-single-backbone-core-and-heads.md) still defines one engine
backbone shared by native and web heads. This decision narrows ownership inside
that backbone.

[`ADR 0010`](0010-bind-priority-actions-at-projection-source.md) keeps its
bind-at-source and bounded-lifetime rules. The exact executable handle lives in
the runtime thread's opaque-token table rather than a separate worker.
[`ADR 0011`](0011-preserve-ability-definition-identity.md) and
[`ADR 0012`](0012-bind-prompt-routes-once.md) provide source-bound values for
typed inputs. [`ADR 0013`](0013-finalize-annotation-frames-once.md) provides the
single-finalizer rule inside one pure projection transition.

[`ADR 0014`](0014-command-yield-engine-boundary.md) remains the rationale for
confining Forge and eliminating live crossings. This decision supersedes its
mandatory actor topology, process-ready contract preference, outbox mechanism,
and claim that callback timing alone identifies a coherent observation cut.
