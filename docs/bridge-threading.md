---
summary: "Current cross-thread bridge contract: execution domains, publication ownership, projection commit, and safe points."
read_when:
  - "modifying GameBridge, MatchSession, MatchCutCoordinator, BundleBuilder, or engine callbacks"
  - "debugging state-id ordering, snapshot timing, delivery ordering, or stale interactions"
  - "adding a producer that closes, commits, or delivers a gameplay cut"
---
# Bridge Threading

This document owns the cross-class execution and ordering constraints that the
type system does not express. System shape lives in
[`architecture.md`](architecture.md); durable direction and rationale live in
[ADR 0015](decisions/0015-functional-core-imperative-shell.md).

## Execution domains

| Domain | Runs | Coordination |
|---|---|---|
| Engine thread | Forge loop, callbacks, event dispatch, safe-point cut commits | Sole owner of the live Forge graph |
| Interactive entrants | Native/web/in-process input and tests | `ConnectionState.sessionLock` serializes `MatchSession` entry |
| Runtime delivery observer | One per live human `MatchConnection` | Waits for coordinator feed notifications, then enters `sessionLock` to drain |
| Spectator pump | Drains its viewer feed and delivers committed output | Coordinator `feedLock` protects publication/drain |
| Sink caller | Observes committed prompt metadata and calls `MessageSink.send` | Runs on the initiating session or pump domain |

Engine confinement makes a live read physically stable only on the engine
thread. It does not make an event callback a safe projection boundary because a
callback can run inside a larger mutation burst.

## Mutable-state ownership

| Mutable place | Owner |
|---|---|
| Live Forge graph | Engine thread |
| Retained executable handles | Owning family runtime under `feedLock`; sessions receive only exact claims or immutable values |
| Client settings and priority presentation policy | `PriorityPolicyRuntime` |
| Interaction correlation, committed viewer feeds, and terminal failure | `MatchCutCoordinator` and its family runtimes under `feedLock` |
| Client-facing identities, cursors, annotations, and logical output | One committed `ProjectionState` behind `GameBridge.projectionLock` |
| Per-connection admission and delivery | `ConnectionState.sessionLock` |

Session handlers parse client messages into immutable values. They do not read
the live Forge graph to reconstruct an action, combat declaration, prompt
answer, or priority decision.

## Lock order

The acquisition order is:

1. `sessionLock`, when a connection is admitting or delivering work;
2. coordinator `feedLock`, for interaction state, cut preparation, installation,
   and committed feeds;
3. `projectionLock`, briefly, for the revision-checked `ProjectionState` swap.

`feedLock` may be acquired without `sessionLock`. No path acquires `feedLock`
while holding `projectionLock`, and no drainer waits for the engine while holding
`feedLock`.

Drainers and event subscribers requesting a future cut use `feedLock`.
Action-window visibility and prompt correlation may be read without `feedLock`
from volatile state written while holding it. Those reads are observation only;
they do not claim or complete a window.

## Publication transaction

When the engine blocks for a visible decision, the delivery signal means the
complete interaction batch is already committed and drainable:

```mermaid
sequenceDiagram
    participant E as Engine thread
    participant C as MatchCutCoordinator
    participant P as Projection core
    participant S as DeliverySignal
    participant M as MatchRuntimeDeliveryObserver

    E->>C: publish immutable interaction window
    C->>P: compile tentative transition
    P-->>C: messages + next projection state
    C->>C: install and enqueue under feedLock
    C->>S: signal after commit
    E->>E: block on exact window
    S-->>M: observer wakes
    M->>C: drain committed batch
```

Under `feedLock`, a prepared cut enqueues all viewer batches, installs its
projection transition under `projectionLock`, runs its install callbacks,
acknowledges any playback boundary, and only then signals delivery. Replacement
batches supplied to the installer are retired after enqueue and restored on a
pre-install failure. That failure removes only the new cut's batches. Once
projection installs, allocation and publication are not rewound. Committed
logical identifiers and output ordinals remain monotonic; sessions and
transports may read their horizons but cannot allocate them.

Every coordinator-backed interaction preserves these rules:

1. Freeze projection values and exact executable handles on the engine thread.
2. Commit the complete state-and-request batch before signalling.
3. Correlate answers to the exact request message and game-state identifiers.
4. Resolve original handles only within the owning runtime.
5. Retire response, timeout, supersession, and teardown through one winner.
6. Treat materialization, install, or delivery failure as terminal when Forge
   cannot safely continue past the unpublished boundary.

Some interactions accept an ordinary default on choice timeout. That is a
prompt policy, not permission to continue after failed publication.

A synchronous default path publishes no window and therefore emits no signal.
Priority `Skip` likewise closes no journal and allocates no protocol state.

Two current paths sit outside the common transaction. `MulliganHandler` resets
instance identities before publishing the redraw cut. Phase-action replacement
installs the new cut, then removes the previous action batch from the installer's
`afterInstall` callback instead of supplying it as a rollback-aware replacement.

## Delivery limits

Projection and delivery have separate timelines:

| Timeline | Advances when | Meaning |
|---|---|---|
| Projection state and viewer cursor | A complete transition installs | Baseline for the next compile |
| Committed feed | The fixed batch is enqueued | Batch may be drained |
| Sink handoff | `MessageSink.send` is invoked successfully | Server delivery attempt, not client acknowledgement |

Never use a viewer cursor as client-awareness state. If behavior depends on
delivery, represent delivery explicitly.

`MatchRuntimeContinuation` waits for one engine horizon, drains committed batches
in order, and acknowledges an exact `SYNC_ONLY` barrier only after successful
delivery. An iterative response that does not release the engine does not wait
for another horizon.

The inbound handler drains the horizon released by its accepted response while
holding `sessionLock`. The connection's delivery observer handles horizons
published after that handler returns by taking the same lock and using the same
drain path. The observer consumes only the coordinator delivery notification; it
does not submit actions or choose progression policy.

In-process harness callers may wait for named sink output, but do not wait on an
engine horizon or consume the observer notification. Player, Familiar, and
spectator sessions drain only their own committed viewer feeds. Raw match
completion is connection-local and follows the committed terminal drain. PvP
transport is outside this fixed-roster delivery model.

### Initial-publication replay

Constructed reconnect has one bounded repeat-publication exception.
`MatchLifecycleRuntime` retains the first initial viewer batches and may
re-enqueue a missing batch under `feedLock` with its original output ordinal and
logical identifiers. It does not install another projection transition, and it
does not duplicate a batch already queued. This is initial-handshake replay, not
a general reconstruction of the latest viewer state after gameplay.

Mulligan is the other pre-game exception: it drives an engine interaction
outside `sessionLock`, while its output still commits through the coordinator
lifecycle runtime.

## Teardown

Connection teardown stops and invalidates its delivery observer before the
connection can be rebound. Puzzle replacement invalidates the old generation,
publishes and delivers the replacement's initial horizon under `sessionLock`,
then arms a new observer generation.

Match teardown removes registered connections and sessions, closes session-owned
workers, detaches connection state, and closes the match. Bridge shutdown
terminalizes coordinator waiters, rejects later cuts, wakes delivery and engine
waiters, stops the game loop, and discards registered viewer feeds including any
committed but undrained batches.

## Safe points and event subscribers

Forge EventBus subscribers execute synchronously on the engine thread, often in
the middle of the operation that raised the event. Ordinary subscribers append
immutable facts and request a cut. They must not:

- inspect Forge for projection after handing work to another thread;
- close the journal or compile a frame;
- allocate protocol identities;
- install, enqueue, deliver, sleep, or wait on external work.

`PhaseHandler` invokes the ordinary completion hook after a successful
`mainLoopStep` mutation burst. Narrow completion hooks own attacker declaration,
blocker declaration, and combat teardown cuts. A combat journal may yield
several ordered frames, but the pending cut compiles them as one private fold
and installs only the final combined transition.

A failed or stale install is not rebuilt from a newer live snapshot. It retains
the immutable cut and terminates the path because rebasing would change the
facts that were originally closed.

## Pre-mutation prompts

Forge can ask for input before performing a mutation that the client must
already present. Such prompts use explicit projection supplements: materialize the
intended state, commit it with the request, then reconcile it after Forge
resumes. The supplement is a value owned by the prompt runtime; it is not a
reason to mutate projection state outside compilation.
