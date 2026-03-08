# Per-Seat Bridge Refactor

## Goal

Make GameBridge seat-aware so two human players can each have independent action/prompt/mulligan bridges. Currently everything is hardcoded to "seat 1 = human, seat 2 = AI". Multiplayer requires N bridges keyed by seatId.

## Current State

### GameBridge.kt — singleton bridges

`GameBridge` creates exactly one of each bridge type (lines 67-80):

```kotlin
val actionBridge = GameActionBridge(...)
val promptBridge = InteractivePromptBridge(...)
private val seat1MulliganBridge = MulliganBridge(...)
```

`start()` (line 296) finds the human player via `!is LobbyPlayerAi` — breaks when both players are human. Only one `WebPlayerController` is wired (line 299-309).

### GameBridge.getPlayer() — hardcoded seat→role mapping

Lines 368-375: seat 1 = first non-AI player, seat 2 = first AI player. Two humans means `getPlayer(2)` returns null.

### StateMapper.kt — hardcoded seat references

- `buildFromGame()` lines 47-48: `val human = bridge.getPlayer(1)`, `val ai = bridge.getPlayer(2)`. Used for turnInfo (active/priority/decision player seat mapping) and zone layout (P1/P2 hand/library/graveyard).
- `buildDiffFromGame()` line 311: same pattern for action embedding.
- `resolveUpdateType()` line 349: `bridge.getPlayer(1)` to determine acting seat.

All three use `getPlayer(N)` only to resolve "which Forge Player object is seat N" — the seat math itself is correct. Fix is in `getPlayer()`, not in StateMapper.

### GsmBuilder.kt — 6 call sites

- `buildDealHand()` lines 43-44: `getPlayer(1)`/`getPlayer(2)` for zone mapping.
- `buildInitialGameState()` lines 194-195: same.
- `buildTransitionState()` line 266: `getPlayer(1)` for active/priority seat.
- `embedActions()` line 354: `getPlayer(1)` for action attribution.
- Lines 284-285: `getPlayer(1)` and `getPlayer(2)` for player info in transition diffs.

Same pattern: seat→Player resolution. All fixed by fixing `getPlayer()`.

### BundleBuilder.kt — 3 call sites

- `postAction()` line 52: `bridge.getPlayer(1)` for phase annotation seat.
- `aiActionDiff()` line 127: `bridge.getPlayer(1)` for active seat.
- `phaseTransitionDiff()` line 251: `bridge.getPlayer(1)` for active/priority.

### MatchHandler.kt — seat 1 gate

Lines 259-299: every action handler checks `if (seatId == 1)` and ignores seat 2. This was correct for Familiar (mirror-only spectator) but blocks a second human player from submitting actions.

### AnnotationPipeline.kt — line 247

`bridge.getPlayer(1)` for combat annotation phase attribution.

### RequestBuilder.kt — lines 176-177

`bridge.getPlayer(1)` and `getPlayer(2)` for combat legality checks.

## Target Design

### Per-seat bridge maps on GameBridge

Replace singleton bridges with maps keyed by seatId:

```kotlin
val actionBridges: Map<Int, GameActionBridge>
val promptBridges: Map<Int, InteractivePromptBridge>
val mulliganBridges: Map<Int, MulliganBridge>
val controllers: Map<Int, WebPlayerController>
```

Accessor: `fun actionBridge(seatId: Int): GameActionBridge`. Throws on missing seat (programming error, not runtime).

### Fix getPlayer() — index by seat, not by role

Replace the `LobbyPlayerAi` check with a `Map<Int, Player>` populated at `start()` time. `getPlayer(seatId)` does a map lookup. Works for 1v1 human-vs-AI, 1v1 human-vs-human, and future N-player.

```kotlin
private val players = mutableMapOf<Int, Player>()
fun getPlayer(seatId: Int): Player? = players[seatId]
```

Populated during `start()` from the `Game.players` list, ordered by seat assignment (seat 1 = first registered, seat 2 = second).

### start() gains seatConfig parameter

Instead of inferring human/AI from `LobbyPlayerAi`, callers declare seat roles:

```kotlin
data class SeatConfig(
    val seatId: Int,
    val isHuman: Boolean,
    val deckList: String,
)
```

For human seats: create bridge instances + `WebPlayerController`. For AI seats: leave engine's default controller (no bridge needed — Forge AI plays itself).

### GameLoopController — already flexible

`GameLoopController` already takes `List<GameActionBridge>`, `List<InteractivePromptBridge>`, `List<MulliganBridge>`. Just pass `.values.toList()` from the new maps.

### MatchHandler action dispatch — remove seat 1 gate

Replace `if (seatId == 1)` checks with `if (seatId in humanSeats)` or (better) unconditionally delegate to `session?.onPerformAction(greMsg)` — the session already knows its seatId and can validate. The Familiar case stays filtered at a higher level (MatchHandler skips creating a session for Familiar connections, or Familiar sessions are read-only).

### MatchSession — parameterize on viewing seat

`sendRealGameState()`, `sendGameOver()`, etc. already receive `seatId` as a constructor parameter. They pass it to `BundleBuilder.postAction(game, bridge, matchId, seatId, counter)`. The only change: use `bridge.actionBridge(seatId)` instead of `bridge.actionBridge`.

### PrioritySignal — shared across all bridges

Keep one `PrioritySignal` instance. All action/prompt bridges signal to it. `awaitPriority()` wakes on any bridge having a pending item. Session checks its own seat's bridge after waking.

## Migration Path

### Phase 1: Fix getPlayer() (zero behavior change) — ✅ Done (`5aa147d`)

`players: MutableMap<Int, Player>` populated in `start()`/`wrapGame()`/`startPuzzle()` via `populateSeatMap()`. `getPlayer()` is now a one-liner map lookup. 13+ callers benefit transitively.

### Phase 2: Per-seat bridge maps (zero behavior change for 1v1 AI) — ✅ Done (`8ed52fb`)

`actionBridges`, `promptBridges`, `mulliganBridges` as `MutableMap<Int, *>`. Parameterized accessors `actionBridge(seatId)` etc. Backward-compat `val actionBridge` / `val promptBridge` properties delegate to seat 1. Seat 1 bridges seeded in `init`. `GameLoopController` receives `.values.toList()`.

### Phase 3: Remove seat 1 gates in MatchHandler — ✅ Done (`8ae07de`)

`isFamiliar` flag set during auth via `clientId.endsWith("_Familiar")`. All 6 `seatId == 1` gates replaced with `!isFamiliar`. `SubmitAttackers/BlockersReq` routing uses `isFamiliar` for Familiar fallback to `activeSession()`.

### Phase 4: Dual-seat start()

1. `start()` accepts `List<SeatConfig>`.
2. Creates bridges + controllers per human seat.
3. AI seats get no bridge — engine's default controller handles them.
4. Both mulligan bridges are wired. `awaitMulliganReady()` waits for all human seats.

## What Doesn't Change

- **PhaseStopProfile** — already takes `humanPlayerId`/`aiPlayerId` as Forge player IDs, not seat numbers. Works as-is.
- **GameLoopController** — already takes `List<*Bridge>`. No changes.
- **MatchRegistry** — already keyed by `(matchId, seatId)`. Works for N seats.
- **AutoPassEngine** — operates on the session's own bridge. Seat-parameterized by construction.
- **CombatHandler / TargetingHandler** — receive `bridge` as parameter, call `bridge.getPlayer(seatId)`. Fixed transitively by fixing `getPlayer()`.
- **AnnotationBuilder** — pure functions taking seat IDs as ints. No bridge dependency.
- **DiffSnapshotter / InstanceIdRegistry / LimboTracker** — shared per-game, not per-seat. Card IDs are global to the match. No changes.
- **CardDb** — global singleton, game-scoped. No changes.
- **MessageCounter** — shared per-match (not per-seat). Both sessions share it via `connectBridge()`. No changes.
- **Conformance tests / MatchFlowHarness** — use the current 1v1 API. Phase 1-2 are backward compatible. Tests updated in Phase 4 when the API changes.

## Risk

**Shared mutable state.** `InstanceIdRegistry`, `DiffSnapshotter`, `LimboTracker` are per-game singletons mutated from both the engine thread and session threads. Currently safe because only one session thread writes (seat 2 is passive). With two active sessions, concurrent `snapshotState()` / `recordZone()` calls need synchronization. Mitigation: these are always called inside `sessionLock` — and both sessions share the same `sessionLock` (via the shared GameBridge). Verify this invariant holds.

**Counter divergence.** Two sessions sharing one `MessageCounter` works only if all counter advances happen inside `sessionLock`. Currently true (MatchSession synchronizes all entry points). Must remain true.

**Bridge-per-seat memory.** Each bridge holds a `CompletableFuture` and ~100 bytes of state. Negligible for 2 seats. Not a concern.

**Test surface.** Phase 1-2 are backward compatible (existing tests pass). Phase 3-4 need new tests: two-human mulligan sequence, cross-seat action rejection, concurrent priority stops. Use MatchFlowHarness with two synthetic sessions.
