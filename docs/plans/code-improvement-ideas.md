# Code Improvement Ideas

Structural changes that gate iteration speed. Not file splits — these are about boundaries, patterns, and ergonomics.

## Deferred / Not worth it now

**A. FD command registry.** Stubs are already table-driven (~28 entries). Remaining real-logic branches are ~18, each 3-12 lines of thin glue. Extracting into separate handler objects would add indirection without reducing complexity — commands need ctx/txId/writer/services, and the real logic lives in services which are already independently tested. Splitting dispatch by CmdType range (4xx decks, 6xx events) creates tiny dispatchers with 3-5 branches each — wrong unit. Decoupling from Netty would lose integration coverage of framing/envelope which is what matters. Revisit if real-logic branches grow past ~30 or individual branches get complex.

## B. Cross-door state → PendingMatch registry

**Problem:** FD→MD communication is two `@Volatile` strings on LeylineServer (`selectedDeckId`, `selectedEventName`). Works for single-player AI. Breaks on:
- 2+ concurrent matches (second overwrites first)
- Sealed/draft (pool selection, deck building phase between FD and MD)
- Direct challenge (two players, invite flow)
- Match history (no record of what was requested)

**Current flow:**
1. FD handler writes `selectedDeckId` and `selectedEventName` on Netty IO thread
2. MD handler reads them on client connect (ConnectReq)
3. Values are consumed once, no cleanup, no correlation

**Target:** `PendingMatch` data class + `PendingMatchRegistry` (concurrent map, keyed by matchId or playerId).

```
data class PendingMatch(
    val matchId: String,
    val playerId: PlayerId,
    val deckId: DeckId,
    val eventName: String,
    val formatId: String?,
    val createdAt: Instant,
    // sealed: sealedPoolId, draftId
    // direct challenge: opponentId, challengeId
)
```

FD creates `PendingMatch`, pushes `MatchCreated` with matchId. MD looks up by matchId on ConnectReq, removes after consumption. No shared mutable state on LeylineServer.

**Effort:** Medium. Touches LeylineServer, FrontDoorHandler (match creation paths), MatchHandler (connect path). Tests via MatchFlowHarness.

**Unlocks:** Multiplayer, sealed, draft, direct challenge — every roadmap feature that involves match creation.

---

## D. MatchHandler command registry

**Problem:** `MatchHandler` dispatches client messages via `when` on `ClientMessageType`. Adding a new action handler (declare blockers, modal choice, scry, surveil) means editing the dispatch method + wiring manually. Same scaling problem as FD's dispatch.

**Current shape:** MatchHandler receives `ClientToMatchServiceMessage`, switches on type, calls the right `MatchSession` method. Each branch is 5-15 lines of proto field extraction + bridge call.

**Target:** Map of `ClientMessageType → (MatchSession, ClientToMatchServiceMessage) -> Unit`. Each handler is a standalone function or object. MatchHandler becomes a thin dispatcher.

```kotlin
private val handlers = mapOf(
    PERFORM_ACTION_RESP to ::onPerformAction,
    DECLARE_ATTACKERS_RESP to ::onDeclareAttackers,
    DECLARE_BLOCKERS_RESP to ::onDeclareBlockers,
    SELECT_TARGETS_RESP to ::onSelectTargets,
    // new handlers: just add a line
)
```

Handlers live on MatchSession (where the logic already is). MatchHandler's job is just proto extraction → typed call.

**Effort:** Low. Mechanical refactor — no logic changes, just restructuring dispatch. Can be done incrementally (move one handler at a time).

**Unlocks:** Faster action handler development. Each new client action = one function, not a when-branch edit.

---

## E. Test harness DSL

**Problem:** `MatchFlowHarness` is powerful but verbose. Every conformance test repeats:
- Puzzle file loading or deck setup
- Bridge + game bootstrap
- Message sink setup
- Manual step-through (advance to phase, perform action, check annotation)

Writing a new test takes 30-50 lines of boilerplate before the first assertion. This directly slows mechanic shipping — the test is harder to write than the feature.

**Target:** Builder DSL on top of MatchFlowHarness.

```kotlin
matchTest("bolt to face deals 3 damage") {
    puzzle("bolt-face.pzl")

    advanceTo(Phase.MAIN1)
    castSpell("Lightning Bolt") targeting "opponent"

    expectAnnotation(AnnotationType.DAMAGE) {
        amount(3)
        target("opponent")
    }
    expectLife(opponent = 17)
}
```

The DSL wraps existing harness methods — no new test infrastructure, just ergonomic sugar. Key helpers:
- `puzzle(name)` / `decks(p1, p2)` — setup
- `advanceTo(phase)` — skip priority passes until phase
- `castSpell(name)`, `activateAbility(name)`, `declareAttackers(...)` — actions by card name (harness resolves to instanceId)
- `expectAnnotation(type) { ... }` — assert on next annotation
- `expectZone(card, zone)` — assert card location
- `expectLife(...)` — life total check

**Effort:** Medium. Needs careful design so DSL doesn't become a leaky abstraction. Start with 3-4 common patterns, expand as tests reveal needs.

**Unlocks:** 3-5x faster test authoring for new mechanics. Lower barrier for writing conformance tests. Tests become readable documentation of expected behavior.

---

## F. GameBridge subsystem decomposition

**Problem:** GameBridge is 690 LOC implementing 6 interfaces (`IdMapping`, `PlayerLookup`, `ZoneTracking`, `StateSnapshot`, `AnnotationIds`, `EventDrain`) and owning 8 subsystems. Adding a new game capability (adventure cards, MDFCs, sagas, classes) means touching the god object — reading 690 LOC to find the right insertion point, risking unrelated breakage.

`StateMapper.buildFromGame()` takes full `GameBridge` and calls ~15 methods across all interfaces. This tight coupling means you can't test subsystems in isolation.

**Current subsystems:** InstanceIdRegistry, LimboTracker, GameEventCollector, MessageCounter, PhaseStopProfile, DiffSnapshotter, GamePlayback, AnnotationPipeline.

**Target:** Subsystem registry pattern. GameBridge becomes a coordinator that holds subsystems, delegates by contract.

```kotlin
class GameBridge(
    private val subsystems: List<GameSubsystem>,
) {
    // Subsystems register capabilities they provide
    inline fun <reified T> get(): T = subsystems.filterIsInstance<T>().single()
}

interface GameSubsystem {
    fun onGameStart(game: Game) {}
    fun onTurnStart(turn: Int) {}
    fun onPhaseChange(phase: Phase) {}
    fun onReset() {}
}
```

Each subsystem implements the interfaces it needs. `StateMapper` asks for specific capabilities: `bridge.get<IdMapping>()`, `bridge.get<ZoneTracking>()`. New mechanics add a subsystem without touching GameBridge.

**Effort:** High. Requires careful interface extraction, updating StateMapper and all callers, extensive test verification. Risk of over-engineering if done wrong.

**Unlocks:** Localized mechanic additions. New game capabilities don't touch the god object. Subsystems testable in isolation. But only worth doing when GameBridge is actively painful (3+ mechanics blocked by it).

**Recommendation:** Defer until GameBridge becomes a bottleneck. Current 690 LOC is large but manageable. Do B, D, E first — they have better effort/impact ratios.
