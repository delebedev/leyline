# Rich Hickey Reviews matchdoor

**Date:** 2026-03-21
**Scope:** matchdoor module — architecture, data flow, state management
**Format:** Thought experiment — "what would Rich Hickey say about this code?"

## 1. `buildFromGame()` lies about what it is

`StateMapper.buildFromGame()` says "build" but mutates. Five categories of
side effect hidden inside a function named "build":

- `bridge.drainEvents()` — destructive read
- `bridge.retireToLimbo()` — mutation
- `bridge.recordZone()` — mutation
- `bridge.snapshotDiffBaseline()` — replacement (called from `buildDiffFromGame`)
- `bridge.nextAnnotationId()` — stateful counter advance

**Proposal:** Split into pure core + effect application. Phase 1: pure function
takes an immutable snapshot of engine state + events, returns a `BuildResult`
(GSM + deferred effects list). Phase 2: apply effects to bridge.

`TransferResult` already does this pattern for zone transfers (deferred
`retiredIds` and `zoneRecordings`). Extend it to the whole pipeline.

**Priority:** Medium. High test payoff — pure core becomes independently testable
without wiring up a real GameBridge.

## 2. GameBridge: six interfaces wearing a trenchcoat

GameBridge implements `IdMapping`, `PlayerLookup`, `ZoneTracking`,
`StateSnapshot`, `AnnotationIds`, `EventDrain`. Owns `InstanceIdRegistry`,
`LimboTracker`, `DiffSnapshotter`, `EffectTracker`, `PersistentAnnotationStore`,
plus three per-seat bridge maps.

`BridgeContracts.kt` defines focused interfaces — good start. But every
consumer still receives the full `GameBridge` and can reach into any of the
six capabilities.

**Proposal:** Split along the information/mechanism boundary:

- **Game state** (IDs, zones, events, annotations) — information, needed by
  the annotation pipeline
- **Game coordination** (action bridges, priority signals, mulligan bridges,
  await methods) — mechanism, needed by MatchSession

Today both take the same 400-line object.

**Priority:** Low. Interfaces already limit coupling at compile time. Real pain
only shows up when testing pipeline stages that pull in the full bridge.

## 3. Annotation pipeline: sequential, not compositional

Called a "four-stage pipeline" but implemented as sequential imperative code
inside `buildFromGame()`. Stages coupled through shared mutable lists and local
variables. Can't test stage 3 without running stages 1–2.

**Proposal:** Each stage becomes `(input) -> output`:

```
snapshotEngine(game, bridge)        // freeze world into values
  -> detectTransfers(snapshot)      // pure
  -> annotateTransfers(transfers)   // pure (already close)
  -> combatAnnotations(snapshot)    // pure
  -> mechanicAnnotations(events)    // already pure
  -> assemble(all results)          // pure
  -> applyEffects(bridge, effects)  // side effects last
```

**Priority:** Medium. Couples with #1 — both are about making the pipeline pure.

## 4. Shared MessageCounter: correct but hidden

`MessageCounter` shared between `MatchSession`, `GamePlayback`, and
`GameBridge`. Two threads mutate it. `connectBridge()` has a sync dance where
one counter is advanced to match the other, then replaced.

**Analysis:** The shared mutable counter is the correct solution. gsIds must be
monotonically increasing across the entire interleaved message stream — ranges
or partitioning would break the client. This is essential complexity, not
accidental.

Rich would call it a genuine identity (succession of states over time). The
Clojure equivalent is an `atom` — exactly a shared mutable reference with
atomic updates.

**Proposal:** Make the sharing explicit in construction rather than runtime sync.
Create the counter once at the MatchHandler level, pass it to both Session and
Bridge construction. The `connectBridge()` sync dance becomes dead code.

For PvP (seat 2 joins existing match), seat 2's session receives the bridge's
existing counter at construction rather than creating its own.

**Priority:** High. Small change, big clarity win. Acting on this now.

## 5. `MatchSession.onPerformAction()`: too many decisions

80-line method handling: stale-action rejection, auto-pass tracking, timer
control, tap logging, recording, action type dispatch (6 branches), post-cast
prompt checking, stack resolution, modal ETB detection, auto-pass entry.

**Proposal:** Decompose into: (1) validate, (2) dispatch to engine,
(3) post-action state machine. Timer, recording, tap logging are cross-cutting
— belong in a wrapper/hook, not inline. Post-cast prompt check and stack
resolution are part of "advance the game" and belong in `autoPassAndAdvance()`
or a dedicated post-action handler.

**Priority:** Low. Method is stable and well-commented. Refactor when it next
needs modification.

## 6. What's already right (do more of it)

- `GameEvent` sealed interface with plain data (Forge card IDs, not engine
  objects) — information, not mechanism. Values, not places.
- `TransferCategory` enum with wire labels — data describing data.
- `mechanicAnnotations()` takes events + ID resolver function, no bridge ref.
- `AnnotationPipeline.annotationsForTransfer()` — pure function, no bridge.

**Pattern to extend:** Freeze the world into values at the boundary, pass values
through pure functions, apply effects at the end. Already done for events and
transfer detection. Extend to the whole pipeline.

## Action items

| # | Item | Priority | Status |
|---|------|----------|--------|
| 4 | MessageCounter: explicit sharing via construction | High | In progress |
| 1 | buildFromGame: pure core + deferred effects | Medium | Backlog |
| 3 | Annotation pipeline: compositional stages | Medium | Backlog |
| 2 | GameBridge: split info/mechanism | Low | Backlog |
| 5 | onPerformAction: decompose | Low | Backlog |
