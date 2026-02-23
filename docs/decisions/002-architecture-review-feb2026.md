# ADR-002: Architecture Review — Feb 2026

## Status
Accepted (review findings)

## Summary

Full architectural review of forge-nexus at ~14.5K LOC, 65 source files, 63 test files. The forge-web reuse strategy is sound. Six structural improvements would make the architecture 10x easier to build upon.

## What Works Well

### 1. Transport-head pattern is the correct architecture

forge-nexus is a **second transport head** on top of forge-web's game orchestration layer, not a parallel implementation. The three bridges (`GameActionBridge`, `InteractivePromptBridge`, `MulliganBridge`) + `WebPlayerController` + `GameLoopController` are shared. This means:

- 157 `PlayerControllerHuman` overrides work for both web UI and Arena client
- Bridge threading model (CompletableFuture blocking) is battle-tested by two consumers
- New engine features (e.g., a new prompt type) automatically work for both transports
- ~7 bridge classes (~2.5K LOC) are amortized across both modules

**Verdict:** Keep this. Don't fork.

### 2. Conformance testing infra is best-in-class

`StructuralFingerprint` → `StructuralDiff` → golden files → `ValidatingMessageSink` → `MatchFlowHarness` is a four-layer verification stack:

- Unit tests: pure logic (AnnotationBuilder, CategoryFromEvents)
- Conformance tests: wire shape vs golden recordings
- Integration tests: full engine boot with scripted actions
- Recording tests: vs real Arena server traffic

Most game server projects have *one* of these. Having all four means conformance gaps are caught at the appropriate layer.

### 3. Event-driven annotation pipeline is composable

The `GameEventCollector → NexusGameEvent → AnnotationBuilder` chain is a clean FP pipeline. Adding a new annotation type is a mechanical 5-step cookbook. The `categoryFromEvents()` priority system (specific events beat generic `ZoneChanged`) is elegant.

### 4. Observability is production-grade

Debug server (:8090) with SSE, state timeline, instance history, recording introspection, and cross-session mechanic manifests. This is well beyond what's needed for a dev project — it accelerates debugging dramatically.

## What Would Make It 10x Stronger

### Improvement 1: Split StateMapper into Pipeline Stages

**Problem:** `StateMapper.kt` (869 LOC) is a God Object — owns zone mapping, object mapping, annotation wiring, diff computation, snapshot management, combat/targeting/selectN request building, and updateType resolution.

**Why it matters:** Every new feature (new annotation, new request type, new zone behavior) touches StateMapper. Contributors can't work in parallel on different features without merge conflicts. The file's cognitive load slows down every session.

**Proposed split:**

```
StateMapper.kt (orchestrator, ~200 LOC)
  ├── DiffEngine.kt          ← detectZoneTransfers, diff computation, snapshot management
  ├── AnnotationPipeline.kt  ← already partially extracted; move remaining wiring here
  ├── RequestBuilder.kt      ← buildDeclareAttackersReq, buildDeclareBlockersReq,
  │                             buildSelectTargetsReq, buildSelectNReq
  └── UpdateTypeResolver.kt  ← resolveUpdateType, per-seat filtering logic
```

The orchestrator calls stages in order. Each stage is independently testable. The pipeline metaphor becomes literal.

**Effort:** M — mechanical refactor, no behavior change. Test coverage already exists.

### Improvement 2: Formalize the Counter Protocol

**Problem:** `gsIdCounter` and `msgIdCounter` live in two locations (SessionOps on the session thread, NexusGamePlayback on the engine thread) synced via `max()` semantics. Learnings §1 and §4 document bugs from this. The `max()` approach is a patch, not a solution.

**Why it matters:** Every new outbound message path must manually check counter sync. Miss it and you get self-referential gsIds or chain gaps. This is the #1 source of production bugs per the learnings doc.

**Proposed fix:** Single-owner counter with atomic increment.

```kotlin
class MessageCounter {
    private val gsId = AtomicInteger(3)  // initial per protocol
    private val msgId = AtomicInteger(0)
    
    fun nextGsId(): Int = gsId.incrementAndGet()
    fun nextMsgId(): Int = msgId.incrementAndGet()
    fun currentGsId(): Int = gsId.get()
    
    // No setters. No sync. No max(). One owner.
}
```

Both threads read via `currentGsId()`. Only the message-sending path calls `nextGsId()`. The engine thread's `NexusGamePlayback` builds proto messages but doesn't assign IDs — the drain path assigns them.

**Effort:** M — requires careful threading analysis. High-value: eliminates an entire class of bugs.

### Improvement 3: Extract a Bridge Interface for Testing

**Problem:** Integration tests boot the full Forge engine (~90s for the suite). Most nexus logic (proto construction, annotation assembly, request building) doesn't need the engine — it needs the *output* of the engine (game state snapshots, events, pending actions).

**Why it matters:** Faster tests = faster iteration. The conformance test group (~5s) already demonstrates this. But new features often require integration tests because there's no way to provide fake game state to the pipeline without the engine.

**Proposed fix:** Extract `GameStateProvider` interface from `GameBridge`:

```kotlin
interface GameStateProvider {
    fun getGame(): Game
    fun getForgeCardId(instanceId: Int): Int
    fun getInstanceId(forgeCardId: Int): Int
    fun drainEvents(): List<NexusGameEvent>
    fun drainReveals(): List<RevealRecord>
    fun getPhaseStopProfile(): PhaseStopProfile
    // ... the read-only surface that StateMapper/BundleBuilder/AnnotationBuilder consume
}
```

Then `StateMapper`, `BundleBuilder`, `AnnotationPipeline` depend on `GameStateProvider`, not `GameBridge`. Tests can provide a stub `GameStateProvider` with synthetic game state. Moves a large class of tests from `integration` (90s) to `unit` (1s).

**Effort:** M-L — interface extraction + test migration. High-value: test velocity compounds.

### Improvement 4: Decouple CardDb from Global State

**Problem:** `CardDb` is a global mutable singleton with `volatile testMode`. Not injected. Multiple configurations can't coexist.

**Why it matters:** Parallel test execution is fragile. Can't run tests with different card databases simultaneously. The `testMode` flag is a code smell — test infrastructure should not require production code to know about tests.

**Proposed fix:** Make `CardDb` an injected dependency. Construct it in `GameBridge.start()` and pass it through to `StateMapper`, `ObjectMapper`, `ActionMapper`. For tests, construct a test-scoped `CardDb` with known cards.

**Effort:** S-M — constructor injection pattern. Well-understood refactor.

### Improvement 5: Snapshot Unification

**Problem:** `prevSnapshot` (diff baseline) vs `lastSentTurnInfo` (client awareness) are two independent timelines that diverge when the engine skips phases. The learnings doc has 3 sections devoted to bugs from this divergence.

**Why it matters:** Every new annotation that depends on "what has the client seen?" must correctly choose between two snapshot sources. The current code has both `prevSnapshot.turnInfo` and `lastSentTurnInfo` as options, and picking wrong is silent.

**Proposed fix:** Make the snapshot *contain* both perspectives:

```kotlin
data class StateSnapshot(
    val forDiff: GameStateCapture,       // what to diff against (computation)
    val lastSentToClient: TurnInfoLite,  // what client has seen (annotations)
    val lastSentGsId: Int                // what gsId client has seen
)
```

Single object, two named accessors. Impossible to confuse which you're reading. `BundleBuilder` uses `.lastSentToClient` for phase annotations, `.forDiff` for delta computation.

**Effort:** S — data class refactor. Eliminates an entire class of bugs.

### Improvement 6: Shared Types Module (forge-bridge)

**Problem:** forge-nexus imports 10 classes from forge-web. Two DTO types (`TargetDto`, `PromptCandidateRefDto`) leak through the bridge API. forge-nexus transitively depends on kotlinx.serialization annotations through forge-web's DTOs.

**Why it matters now:** Harmless coupling today. But as forge-web adds web-specific behavior (WS keepalive, session affinity, CORS headers), the transitive dependency surface grows. The "thin coupling" from the reuse boundary analysis is thin today because forge-web is young.

**Proposed fix (future, when pain justifies it):**

```
forge-bridge/ (new module, ~3K LOC)
  ├── GameActionBridge.kt
  ├── InteractivePromptBridge.kt
  ├── MulliganBridge.kt
  ├── WebPlayerController.kt
  ├── GameLoopController.kt
  ├── GameBootstrap.kt
  ├── PhaseStopProfile.kt
  ├── PlayerAction.kt
  └── BridgeTypes.kt  ← TargetRef, PromptCandidateRef (no @Serializable)
```

Both forge-web and forge-nexus depend on forge-bridge. forge-web adds `@Serializable` via extension. forge-nexus uses the plain types.

**Effort:** L — new Maven module, dependency rewiring. Defer until coupling actually hurts.

## Prioritized Action Plan

| # | Improvement | Effort | Impact | When |
|---|-------------|--------|--------|------|
| 5 | Snapshot unification | S | High — eliminates bug class | Next |
| 4 | CardDb injection | S-M | Medium — test health | Next |
| 2 | Counter protocol | M | High — eliminates bug class | Soon |
| 1 | StateMapper split | M | High — dev velocity | Soon |
| 3 | GameStateProvider interface | M-L | High — test velocity | When integration tests > 2min |
| 6 | forge-bridge module | L | Medium — coupling prevention | When forge-web grows |

## forge-web Reuse Verdict

**The reuse strategy is working well.** 10 classes imported, all from the transport-agnostic orchestration layer. The bridge API (`CompletableFuture` + sealed `PlayerAction`) is the correct abstraction — nexus never touches forge-game directly, and forge-web never knows nexus exists.

Two improvements to lock this in:
1. **Now:** Document the reuse boundary in architecture.md (done — Section 7)
2. **Later:** Extract `forge-bridge` module when coupling pressure justifies it
