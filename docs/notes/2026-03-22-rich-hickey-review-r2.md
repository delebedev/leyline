# Rich Hickey Reviews matchdoor — Round 2

**Date:** 2026-03-22
**Scope:** Follow-up review — same module, fresh eyes, targeted questions
**Prior:** [Round 1](2026-03-21-rich-hickey-review.md) — 4 of 6 items resolved

---

## 1. forge.game.Game leaking into match/

27 `bridge.getGame()` calls across `MatchSession`, `CombatHandler`, `AutoPassEngine`, `TargetingHandler`. What do they read?

| Caller | Reads from Game |
|--------|----------------|
| AutoPassEngine.autoPassAndAdvance | `isGameOver`, `phaseHandler.{phase, turn, playerTurn, priorityPlayer}`, `stack.isEmpty` |
| AutoPassEngine.checkHumanActions | (same phase/priority data via buildActions) |
| CombatHandler.checkCombatPhase | `phaseHandler.combat.{attackers}`, phase, turn info |
| CombatHandler.onDeclareAttackers | `players` (to find defender), phase |
| TargetingHandler (14 calls) | `getGame()` for traceEvent, `findById()`, `stack.firstOrNull()`, `spellAbilities` |
| MatchSession.onPerformAction | `stack.isEmpty`, phase (traceEvent) |
| MatchSession.traceEvent | `phaseHandler.{phase, turn, priorityPlayer}`, `stack.size()` |

The access pattern is overwhelmingly: phase/turn metadata, stack state, combat state, and card lookup. These are all **reads**. No match/ code mutates the Game — mutation happens through the bridge's action/prompt submission, which unblocks the engine thread.

**Essential or accidental?** Essential. match/ is the orchestrator. It needs to know the game state to decide what to do next. The Game object is the canonical source of that information. A `GameSnapshot` value type would freeze these reads into a struct — but the Game is already frozen from match/'s perspective: the engine thread is blocked on a CompletableFuture whenever match/ has the sessionLock. The synchronized block IS the snapshot boundary.

Adding a formal GameSnapshot would:
- Cost: ~30 lines of mapping code, one more allocation per decision point, one more concept to maintain
- Buy: ability to pass snapshot to pure functions for testing... but these aren't pure functions. They're orchestration — they call `bridge.awaitPriority()`, send messages, submit actions. Testing them requires the bridge anyway.

The only call that gives me pause is `TargetingHandler.checkOptionalCosts` (line 565-571), which reaches through `game.findById()` into `card.spellAbilities` and calls `sa.setActivatingPlayer()`. That's a mutation through getGame() — setting state on a Forge object. It's constrained (only sets context for the cost check), but it breaks the "reads only" contract.

**Proposal:** Move the `setActivatingPlayer` + `getOptionalCostValues` call into the bridge layer — it's engine interaction, not orchestration. match/ should ask "does this card have optional costs?" and get back a data answer.

Everything else: fine. The Game leak is not a leak — it's a read window during a frozen-engine interval.

**Priority:** Low. The `setActivatingPlayer` issue is real but narrow.

---

## 2. drainEvents() — essential or accidental complexity?

`GameEventCollector.drainEvents()` is a destructive ConcurrentLinkedQueue poll. Called from `StateMapper.buildFromGame()` (line 63) and indirectly via `GamePlayback.captureAndPause()` (which calls `BundleBuilder.remoteActionDiff` → `StateMapper.buildDiffFromGame` → `buildFromGame`).

The drain model means: if you drain and then fail before using the events, they're gone. If two callers drain, one gets nothing.

In practice, double-drain is impossible. `buildFromGame` is the only drain site. The session thread and engine thread never call it concurrently — the engine thread calls it from GamePlayback (during `captureAndPause`, engine-thread-only), and the session thread calls it from the sessionLock-synchronized handlers. The shared MessageCounter's atomicity ensures ordering, and the awaitPriority/CompletableFuture handshake ensures mutual exclusion.

A log-with-cursor would:
- Enable per-seat replay for PvP (each seat drains to its own cursor position) — **real future value**
- Make double-drain bugs impossible — **no current value** (they can't happen today)
- Add cursor bookkeeping — cost proportional to seat count

**Essential or accidental?** The destructive read is accidental for the concept (events are a log of what happened) but essential for the current architecture (1vAI, single consumer, no replay). The mismatch will surface when PvP needs per-seat event streams.

**Proposal:** No action now. When PvP requires per-seat event replay, replace `ConcurrentLinkedQueue` with an append-only list + per-consumer cursor. `drainEvents()` becomes `eventsSince(cursor)`. Flag it in a PvP planning doc.

**Priority:** Low. Correct for 1vAI. Known technical debt for PvP.

---

## 3. TargetingHandler — 854 LOC of what exactly?

I count 14 public/internal methods. The state is one `@Volatile var pendingInteraction: PendingClientInteraction?`. Let me map the actual flow:

```
Client message       → Method                     → State transition
SelectTargetsResp    → onSelectTargets()           → pendingInteraction = TargetSelection
SubmitTargetsReq     → onSubmitTargets()           → pendingInteraction = null, submit to engine
SelectNResp          → onSelectN()                 → direct submit (no pending state)
GroupResp            → onGroupResp()               → direct submit
CastingTimeOptions   → checkOptionalCosts()        → pendingInteraction = OptionalCost
CastingTimeOptionsR  → onCastingTimeOptions()      → dispatch on pending type (Modal vs OptionalCost)
CancelActionReq      → onCancelAction()            → submit empty, clear
SearchResp           → onSearchResp()              → pendingInteraction = null, submit
(auto-pass loop)     → checkPendingPrompt()        → classify → send or auto-resolve
(post-cast)          → handlePostCastPrompt()      → classify → send or fallback
```

The state machine is real but implicit. `pendingInteraction` is the state. The sealed interface `PendingClientInteraction` with variants `ModalChoice`, `TargetSelection`, `OptionalCost`, `Search` — that's already data-as-state-machine. The transitions are just "set it in method A, read and clear in method B."

Could this be made explicit? Yes: a `when(state) { is Idle -> ...; is AwaitingTargets -> ...; is AwaitingModal -> ... }` at the top of each entry point. But it wouldn't reduce complexity — it would move the same branches into a different shape. The current shape (one method per client message type) maps 1:1 to the protocol, which is the right seam for a protocol adapter.

The real issue isn't the state machine shape. It's that **six of the fourteen methods build and send GRE messages inline** (`sendCastingTimeOptionsReq`, `sendSelectTargetsReq`, `sendSelectNReq`, `sendGroupReqForSurveilScry`, `sendSearchReq`, `sendAttackerEchoBack` — wait, that last one is CombatHandler). Each one constructs proto builders, resolves IDs, builds GSM diffs, sets up zone state. That's not orchestration — that's message construction mixed into the handler.

`sendGroupReqForSurveilScry` (lines 777-847) is the worst: 70 lines building a reveal diff + GroupReq inline, with `ObjectMapper.buildCardObject` calls, zone ID lookups, proto builder chains. This is `BundleBuilder` work wearing a `TargetingHandler` costume.

**Essential or accidental?** The state machine is essential. The inline message construction is accidental.

**Proposal:** Extract `sendGroupReqForSurveilScry`, `sendSearchReq`, and `sendCastingTimeOptionsReq` message-building logic into `BundleBuilder` (or `RequestBuilder` — it already exists for `buildSelectTargetsRePrompt`). TargetingHandler calls `BundleBuilder.surveilScryBundle(...)`, gets a `BundleResult`, sends it. Estimated: -150 LOC from TargetingHandler, +120 LOC in BundleBuilder (net simplification from not repeating the makeGRE/proto pattern).

**Priority:** Medium. The file is at 854 LOC — near the 500 LOC guidance. Extracting message construction is mechanical and would bring it under 700.

---

## 4. The 5-tuple parameter group

`BundleBuilder.postAction(game, bridge, matchId, seatId, counter)` — appears in `postAction`, `stateOnlyDiff`, `phaseTransitionDiff`, `remoteActionDiff`, `declareAttackersBundle`, `declareBlockersBundle`, `echoAttackersBundle`, `echoBlockersBundle`, `selectTargetsBundle`, `selectNBundle`, `castingTimeOptionsBundle`. Eleven methods, same five parameters.

Hickey's test: does the bag have its own invariants?

- `matchId` is constant for the lifetime of the match
- `seatId` is constant for the lifetime of the session
- `counter` is shared mutable state (the atom)
- `game` is a snapshot (frozen by engine thread blocking)
- `bridge` is the adapter (owns all ID mappings, zone tracking, etc.)

`matchId` and `seatId` don't change. They're configuration, not parameters. `counter` has a lifecycle (created once, shared between session and playback). `game` is ephemeral (valid only during the synchronized block). `bridge` is the long-lived adapter.

So: `(matchId, seatId)` are constants that should be captured at construction, not threaded. `(game, bridge, counter)` are the real parameters. Of those, `bridge` already owns the counter (via `bridge.messageCounter`). So the actual information content is `(game, bridge)` plus two constants.

Would I put methods on a `BundleContext(matchId, seatId, bridge, counter)`? No — because `game` is the critical parameter that changes every call, and leaving it out of the context makes the context incomplete. Would I put methods on a `BundleContext(game, bridge, matchId, seatId, counter)`? That's just BundleBuilder with a constructor.

The real fix is simpler: BundleBuilder is an `object` (Kotlin singleton). It should be a class, constructed once per match with `(matchId, seatId, bridge, counter)`, exposing methods that take only `game`. Or: since `bridge` already knows the `counter`, and `matchId`/`seatId` are match-level constants, BundleBuilder could take `(bridge, matchId, seatId)` at construction and `game` per-call.

**Essential or accidental?** Accidental. The repetition is a sign that BundleBuilder's construction boundary is wrong.

**Proposal:** Convert BundleBuilder from `object` to a class constructed with `(bridge: GameBridge, matchId: String, seatId: Int)`. Counter comes from `bridge.messageCounter`. Methods take only `game: Game`. Eleven method signatures shrink from 5 params to 1.

**Priority:** Medium. Pure mechanical refactor, high readability payoff, zero behavior change.

---

## 5. Has impurity crept back?

Round 1 established gather/compute/apply phases in `buildFromGame`. Let me audit the current COMPUTE section (lines 146-155 of StateMapper.kt):

```kotlin
// ═══ COMPUTE: annotation pipeline (stages 1-5) ═══
val transferResult = AnnotationPipeline.detectZoneTransfers(gameObjects, zones, bridge, events)
val (annotations, transferPersistent, combatResult) =
    computeAnnotations(events, transferResult, actingSeat, bridge)
val remaining = computeRemainingAnnotations(
    events, annotations, transferPersistent, initEffectDiff, effectDiff,
    persistSnapshot, startPersistentId, startAnnotationId, bridge,
)
```

Three calls in COMPUTE. What does each do with `bridge`?

1. **`detectZoneTransfers(... bridge, events)`** — the bridge-param overload delegates to the pure overload, adapting `bridge.getForgeCardId`, `bridge.reallocInstanceId`, `bridge.getOrAllocInstanceId`, `bridge.diff.allZones()`, and one `bridge.getGame()` call (for `manaAbilityGrpIdResolver`). The pure overload is genuinely pure. But `bridge.reallocInstanceId` is a **mutation** — it allocates a new ID in the InstanceIdRegistry. This is in the COMPUTE section.

   Wait — `reallocInstanceId` returns an `IdReallocation(old, new)` value. The allocation is a side effect, but the registry is append-only (new IDs are monotonically assigned). The deferred effects (retireToLimbo, recordZone) are properly in APPLY. The ID allocation itself is... technically impure but operationally safe. The pure overload takes `idAllocator: (Int) -> IdReallocation` as a function param, so tests can substitute a fake allocator. Tolerable.

2. **`computeAnnotations(events, transferResult, actingSeat, bridge)`** — calls `AnnotationPipeline.annotationsForTransfer` (pure) and `AnnotationPipeline.combatAnnotations(events, bridge)`. The combat bridge overload reads `bridge.getDiffBaselineState()` (for previous life totals) and `bridge.getPlayer()` (for current life). Both are reads from state captured in GATHER. Tolerable — these are lookups, not mutations.

3. **`computeRemainingAnnotations(..., bridge)`** — calls `bridge.getOrAllocInstanceId` (via `resolveInstanceId` lambda) and `bridge.getForgeCardId` (via `resolveForgeCardId` lambda). Also calls `buildSourceAbilityResolver(bridge)` which calls `bridge.getGame()` — reaching back into the live engine during COMPUTE.

The `buildSourceAbilityResolver` (lines 455-494) is the impurity creep. It builds a closure that, for each `(instanceId, staticId)` pair, calls:
- `bridge.getForgeCardId()` — lookup, fine
- `findCard(game, forgeCardId)` — traverses the live Game object
- `bridge.cards.findGrpIdByName(card.name)` — lookup, fine
- `card.staticAbilities` — reads live Forge card state
- `parentKeyword.{abilities, triggers, staticAbilities}` — reads live Forge keyword state

This resolver captures `bridge.getGame()` and navigates deep into live engine objects. It's a read, and the engine is frozen (CompletableFuture blocked), but it's philosophically impure — the resolver closes over a mutable Game, not a snapshot.

**Essential or accidental?** The ability resolution requires walking Forge's internal object graph. That graph is not serializable — you can't snapshot `StaticAbility` and `Keyword` objects into plain data without reimplementing Forge's type system. So the closure-over-frozen-Game is a pragmatic choice.

The `reallocInstanceId` in COMPUTE is accidental but harmless (append-only, function-parameterized for tests).

**Verdict:** Purity has held. The `buildSourceAbilityResolver` closure is the one place where COMPUTE reaches into live engine state, and it's doing so because Forge's object model doesn't offer a serializable view. The pure overloads are being used — `detectZoneTransfers` has a pure version, `combatAnnotations` has a pure version, `mechanicAnnotations` takes function params.

`computeBatch` in `PersistentAnnotationStore` is genuinely pure: it takes `Map<Int, AnnotationInfo>`, lists of annotations, and resolver functions. No bridge reference. Well done.

**Proposal:** No action. The one impure spot (ability resolver) is load-bearing and the engine is frozen.

**Priority:** None. This is working correctly.

---

## 6. What else do I see?

### 6a. GameEventCollector captures events AND mutates bridge state

`GameEventCollector.isSearchedToHand()` (line 350-355) and `isLegendRuleVictim()` (line 366-371) call `bridge.promptBridge(seat).searchedToHandCards.remove()` and `.legendRuleVictims.remove()`. These are **destructive reads from bridge state during event collection** — consuming one-shot flags set by `WebPlayerController`.

This is the pattern the round 1 review warned about: side effects hiding in what looks like a classifier. The event collector's job is to translate Forge events to `GameEvent` sealed variants. Consuming bridge flags is a different concern — it's correlating events with bridge-level metadata.

The correlation is needed (without the flag, you can't distinguish mill from surveil in a Library→GY zone change). But consuming the flag in the event visitor complects event translation with bridge-state management.

**Essential or accidental?** The correlation is essential. Consuming the flag in the collector is accidental — it could be consumed during annotation building instead (pass the flag set alongside events, consume during `categoryFromEvents`).

**Proposal:** Move the flag-consumption to `categoryFromEvents` or the event drain site. GameEventCollector emits a raw `ZoneChanged` for Library→GY; the annotation pipeline checks the searchedToHand/legendRule sets when categorizing. Flags get consumed in GATHER, not during event collection (engine thread).

**Priority:** Low. Works correctly today. The risk is a future bug where the flag is consumed but the event is dropped (or vice versa), and the one-shot nature makes it non-reproducible.

### 6b. TargetingHandler.sendSearchReq mutates bridge state

Line 689-691:
```kotlin
bridge.revealLibraryForSeat = ops.seatId
ops.sendRealGameState(bridge)
bridge.revealLibraryForSeat = null
```

Setting a mutable field on bridge, calling a method that reads it, clearing it. This is communication-through-mutation — a temporal coupling where the meaning depends on call order. If `sendRealGameState` ever becomes async, or if another thread reads `revealLibraryForSeat` between set and clear, the behavior breaks.

**Proposal:** Pass the reveal-seat as an explicit parameter to the state-building call chain rather than stashing it on the bridge. `sendRealGameState(bridge, revealLibraryForSeat = seatId)` → `BundleBuilder.postAction(..., revealLibraryForSeat)` → `buildFromGame(..., viewingSeatId)`. The `viewingSeatId` parameter already exists for this purpose — wire it through.

**Priority:** Medium. Temporal coupling is a classic complecting pattern and this one has a clean fix path.

### 6c. CombatHandler.pendingLegalAttackers is mutable state shared across methods

`pendingLegalAttackers`, `lastDeclaredAttackerIds`, `lastDeclaredBlockAssignments`, `pendingBlockersSent` — four mutable fields tracking combat declaration state. Guarded by sessionLock (documented), but the lifecycle is implicit: set in `sendDeclareAttackersReq`, read in `onDeclareAttackers`, cleared in submit. If a cancel arrives between set and submit, the state may be stale.

This is fine. Combat is genuinely stateful (the client toggles attackers one at a time, then submits). The state tracks the client's iterative selection. It's an explicit accumulator, not hidden mutation.

No action needed.

---

## Action items table

| # | Item | Priority | Relationship to round 1 |
|---|------|----------|------------------------|
| 4 | BundleBuilder: object → class, capture (bridge, matchId, seatId) | Medium | New — reduces 5-tuple to 1-param |
| 3 | TargetingHandler: extract message construction to BundleBuilder/RequestBuilder | Medium | New — address 854 LOC, keep <500 guidance |
| 6b | revealLibraryForSeat: parameter, not mutable field | Medium | New — decomplect temporal coupling |
| 1 | checkOptionalCosts: move setActivatingPlayer into bridge layer | Low | New — minor Game mutation in match/ |
| 6a | Event flag consumption: move from collector to annotation pipeline | Low | New — separate event translation from flag management |
| 2 | drainEvents → cursor model for PvP | Low | New — future PvP enabler, document as tech debt |

---

## What's already right (do more of it)

Round 1 items that stuck and are working well:

- **gather/compute/apply phases in `buildFromGame`** — the three-section structure is clear, the COMPUTE section is genuinely mutation-free (one pragmatic exception noted), and the APPLY section is small. This pattern has held through significant feature additions (mana payments, effect tracking, persistent annotations, search).

- **Pure overloads with bridge-delegating wrappers** — `detectZoneTransfers`, `combatAnnotations`, `mechanicAnnotations` all have pure versions taking function params, wrapped by bridge-param convenience overloads. `PurePipelineTest` exercises the pure paths. This is the right architecture for growing the annotation system.

- **`PersistentAnnotationStore.computeBatch` is exemplary** — takes an immutable snapshot, returns a `BatchResult`, caller applies. Five lifecycle steps (effects, transfers, mechanics, detachments, exile-source cleanup) all implemented as pure operations on the active map. This is the gold standard for the module.

- **`PendingClientInteraction` sealed interface** — state machine as data. Variants carry exactly the information needed to resume the flow. Created in one method, consumed in another. No enum + mutable fields anti-pattern.

- **`GsmFrame` value type** — round 1 suggested it, it exists, it's used throughout BundleBuilder. Plain data extracted from the Game once, threaded as a value. Exactly right.

- **Shared MessageCounter via construction** — round 1's highest-priority item, implemented cleanly. No sync dance, no seeding, just one counter passed at creation time.
