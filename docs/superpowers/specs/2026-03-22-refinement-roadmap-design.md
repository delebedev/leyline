# Refinement Roadmap: Safety + Ergonomic Improvements

**Date:** 2026-03-22
**Context:** Full architectural review session — test quality sweep, architect agent creation, Rich Hickey review round 2, component interaction analysis.

## Goal

Interleaved safety and ergonomic fixes across matchdoor. Each item is an independent PR. Safety items prevent silent bugs. Ergonomic items reduce future change cost. Ordered by leverage — each phase builds on the previous.

## Success criteria

Per the architectural refactoring evaluation framework:
- **Conformance surface** should shrink (fewer files to trace a GSM emission)
- **Test tier shift** should improve (more pure-tier tests enabled)
- **Change cost** should drop (adding annotation #57 or action handler #N requires fewer coordinated changes)
- **Implicit contracts eliminated** should increase (ordering dependencies become compile-time or runtime guarantees)

## Phase 1: Zero-abstraction safety

### 1. Detail key constants

**Problem:** `"counter_type"`, `"effect_id"`, `"category"` and ~9 other detail key strings are duplicated across AnnotationBuilder, PersistentAnnotationStore, BundleBuilder, and test helpers (12 occurrences across 4 files). A typo produces a client NPE with no compile-time warning.

**Change:** Create `object DetailKeys` in `game/` with named constants. Replace all string literals. No new types, no abstraction — just named constants.

**Files:** AnnotationBuilder.kt, PersistentAnnotationStore.kt, BundleBuilder.kt, TestExtensions.kt + new DetailKeys.kt

**Verification:** Compile. Existing tests pass (they already exercise these keys). Grep confirms zero remaining raw string detail keys in production code.

### 2. Type-safe ID lambdas

**Problem:** AnnotationPipeline pure overloads use `(Int) -> Int` lambdas for ID resolution. This erases the ForgeCardId/InstanceId distinction at call sites. Passing instanceId where forgeCardId is expected (or vice versa) compiles and runs — produces wrong annotations silently.

**Change:** Change lambda signatures from `(Int) -> Int` to `(ForgeCardId) -> InstanceId` (and reverse where applicable). `ForgeCardId` and `InstanceId` are existing inline value classes — zero runtime cost.

**Files:** AnnotationPipeline.kt (~10 lambda params), StateMapper.kt (delegating overloads), PersistentAnnotationStore.kt (resolver params), test files (lambda construction sites)

**Verification:** Compile (this IS the verification — type errors surface at compile time). Existing tests pass.

## Phase 2: Message building consolidation

### 3. BundleBuilder: object → class

**Problem:** `BundleBuilder` is a Kotlin `object` (singleton) where every method takes `(game, bridge, matchId, seatId, counter)` — the same 5 parameters repeated across 11 methods. Callers (MatchSession, GamePlayback) restate these on every call.

**Change:** Convert to `class BundleBuilder(val bridge: GameBridge, val matchId: String, val seatId: Int)`. Methods take only `game` (which changes per call as engine state advances) and `counter` (shared atomic). The 3 stable parameters are constructor-injected.

**Files:** BundleBuilder.kt (signature changes), MatchSession.kt (construct once, reuse), GamePlayback.kt (construct once), HandshakeMessages.kt (adapt calls)

**Verification:** Existing tests pass. No behavioral change — pure parameter reduction.

### 4. TargetingHandler: extract GRE message building

**Problem:** TargetingHandler (854 LOC) builds 6 GRE messages inline (SelectNReq, GroupReq, OrderCardsReq, etc.). Message building is BundleBuilder's job. TargetingHandler should orchestrate targeting flow, not construct proto messages.

**Change:** Move the 6 message-building methods to BundleBuilder (now a class from item #3). TargetingHandler calls `bundleBuilder.buildSelectNReq(...)` instead of constructing proto inline.

**Files:** TargetingHandler.kt (-200 LOC), BundleBuilder.kt (+200 LOC, but methods are cohesive with existing message builders)

**Verification:** Existing targeting tests pass. TargetingHandler drops below 700 LOC. Message building is now in one file.

**Dependency:** Requires #3 (BundleBuilder as class).

## Phase 3: Implicit contracts → explicit

### 5. drainEvents return-once safety

**Problem:** `GameEventCollector.drainEvents()` is a destructive read — empties the queue. If called twice in the same build cycle, the second call returns empty and annotations are silently lost. Currently structurally impossible (single call site) but fragile to future changes.

**Change:** Either: (a) make drainEvents idempotent (return same events until `acknowledge()` called), or (b) return a `DrainedEvents` wrapper that must be consumed, making the single-use nature explicit in the type system.

**Files:** GameEventCollector.kt, StateMapper.kt (drain call site), GamePlayback.kt (if it drains separately)

**Verification:** Existing tests pass. Add a test that double-drain returns empty / throws / returns same events (depending on approach chosen).

### 6. revealLibraryForSeat: mutation → parameter

**Problem:** `revealLibraryForSeat` is a flag set on GameBridge before calling buildFromGame, then cleared after. This temporal coupling means the "set before, clear after" ordering is enforced only by code position. If someone calls buildFromGame without setting the flag, library cards are invisible. If they forget to clear it, library is revealed permanently.

**Change:** Pass `revealForSeat: Int?` as a parameter to buildFromGame / buildDiffFromGame. Remove the mutable flag from GameBridge.

**Files:** GameBridge.kt (remove flag), StateMapper.kt (add parameter), callers of buildFromGame (pass parameter or null)

**Verification:** Existing tests pass. Library search tests still reveal correctly. Grep confirms no remaining mutable reveal flag.

## Phase 4: Structural improvements

### 7. AnnotationPipelineTest split by stage

**Problem:** AnnotationPipelineTest.kt is 1134 LOC — the largest test file. It covers all pipeline stages (transfer, combat, mechanic, effect, persistent). Adding a new annotation test requires navigating 1100 lines to find the right section.

**Change:** Split into stage-focused test files:
- `TransferAnnotationPipelineTest.kt` — detectZoneTransfers, annotationsForTransfer
- `CombatAnnotationPipelineTest.kt` — combatAnnotations, damage, life changes
- `MechanicAnnotationPipelineTest.kt` — mechanicAnnotations, attach/detach, counters
- `EffectAnnotationPipelineTest.kt` — effectAnnotations, boost tracking
- `PersistentAnnotationPipelineTest.kt` — computeBatch, lifecycle

Each file has its own ConformanceTestBase setup. Shared fixtures extracted to a companion or test utility.

**Files:** AnnotationPipelineTest.kt → 5 new files

**Verification:** Same test count, same pass rate. `./gradlew :matchdoor:testGate` green.

### 8. ResolvedSessionContext extraction

**Problem:** Match handlers repeat a nullable-guard pattern 26+ times:
```kotlin
val bridge = gameBridge ?: return
val game = bridge.getGame() ?: return
val seatBridge = bridge.seat(ops.seatId)
```
Each handler re-resolves independently. If any guard fails, the handler silently returns — no logging, no error.

**Change:** Extract `data class SessionContext(val game: Game, val bridge: GameBridge, val seatBridge: SeatBridge, val player: Player)`. Resolve once at the synchronized block entry in MatchSession. Pass to handlers. Handlers receive non-null context — no guards needed.

**Files:** New SessionContext.kt, MatchSession.kt (resolve once), CombatHandler.kt, TargetingHandler.kt, AutoPassEngine.kt, MulliganHandler.kt (receive context instead of resolving)

**Verification:** Existing tests pass. Grep confirms no remaining `bridge.getGame() ?: return` in handler methods.

## Deliberately excluded

| Item | Why excluded |
|------|-------------|
| GameFacade over forge.game.Game | Hickey R2: lock IS the snapshot boundary, tolerable |
| PvP event cursor model | Premature — build when PvP starts |
| Annotation registry pattern | Pipeline absorbing growth fine at 56 builders |
| GameBridge info/mechanism split | Hickey R1: "won't do", still correct |
| MatchPhase state machine enum | Nice but doesn't prevent bugs — lifecycle enforced by Netty message ordering |

## Sequencing

```
Phase 1 (no deps):     #1 DetailKeys  ──┐
                        #2 Type-safe IDs ─┤── can be parallel
                                          │
Phase 2 (sequential):  #3 BundleBuilder class ──→ #4 TargetingHandler extraction
                                          │
Phase 3 (no deps):     #5 drainEvents safety ─┐
                        #6 revealLibrary param ─┤── can be parallel
                                                │
Phase 4 (no deps):     #7 Test split ──┐
                        #8 SessionContext ─┤── can be parallel
```

Items within a phase can be parallelized. Phase 2 is sequential (#4 depends on #3). All other phases are independent.
