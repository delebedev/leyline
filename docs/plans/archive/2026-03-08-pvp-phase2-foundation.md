# PvP Phase 2 Foundation — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Complete the zero-behavior-change refactoring steps from #60 (PvP Phase 1 foundation) — add lifecycle state to Match, move resource ownership from GameBridge to Match, add per-seat bridge maps, and remove seat-1 gates in MatchHandler.

**Architecture:** Four incremental refactoring steps, each independently shippable. Match gains MatchState enum + close(). GameLoopController/GameEventCollector/GamePlayback ownership moves to Match. GameBridge gets per-seat bridge maps. MatchHandler seat-1 gates become routing-aware. All existing tests must pass unchanged after each step.

**Tech Stack:** Kotlin, Kotest FunSpec, Forge engine, Netty, protobuf

**Existing plans:** `docs/plans/multiplayer/01-match-lifecycle.md`, `docs/plans/multiplayer/02-per-seat-bridges.md`

---

## Task 1: MatchState enum + close() on Match (01-P2)

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/match/Match.kt`
- Test: `matchdoor/src/test/kotlin/leyline/conformance/MatchRegistryTest.kt` (add lifecycle tests)

### Step 1: Write failing tests for MatchState lifecycle

Add to `MatchRegistryTest.kt` (or create `MatchLifecycleTest.kt` — prefer existing file since it already tests Match):

```kotlin
test("new Match starts in WAITING state") {
    val match = Match("m1", GameBridge())
    match.state shouldBe MatchState.WAITING
}

test("close() transitions to FINISHED") {
    val match = Match("m1", GameBridge())
    match.close()
    match.state shouldBe MatchState.FINISHED
}

test("close() is idempotent") {
    val match = Match("m1", GameBridge())
    match.close()
    match.close() // no exception
    match.state shouldBe MatchState.FINISHED
}

test("start() transitions from WAITING to RUNNING") {
    // Can't test with real engine (needs card DB), just verify state field exists
    // Integration tests in MatchFlowHarness cover real start()
    val match = Match("m1", GameBridge())
    match.state shouldBe MatchState.WAITING
}
```

### Step 2: Run tests to verify they fail

```bash
cd /Users/denislebedev/src/leyline && ./gradlew matchdoor:test --tests "leyline.conformance.MatchRegistryTest" --no-daemon -q 2>&1 | tail -20
```

Expected: FAIL — `MatchState` doesn't exist.

### Step 3: Implement MatchState enum + close()

In `Match.kt`:

```kotlin
package leyline.match

import leyline.game.GameBridge
import java.util.concurrent.atomic.AtomicReference

enum class MatchState { WAITING, RUNNING, FINISHED }

class Match(
    val matchId: String,
    val bridge: GameBridge,
) {
    private val _state = AtomicReference(MatchState.WAITING)
    val state: MatchState get() = _state.get()

    /** Callback invoked on state transitions. Registry subscribes to auto-remove finished matches. */
    var onStateChanged: ((MatchState) -> Unit)? = null

    fun start(
        seed: Long? = null,
        deckList: String? = null,
        deckList1: String? = null,
        deckList2: String? = null,
    ) {
        bridge.start(seed, deckList, deckList1, deckList2)
        transition(MatchState.RUNNING)
    }

    /** Idempotent shutdown. Transitions to FINISHED, releases engine resources. */
    fun close() {
        if (!_state.compareAndSet(MatchState.WAITING, MatchState.FINISHED) &&
            !_state.compareAndSet(MatchState.RUNNING, MatchState.FINISHED)) {
            return // already FINISHED
        }
        bridge.shutdown()
        onStateChanged?.invoke(MatchState.FINISHED)
    }

    @Deprecated("Use close()", replaceWith = ReplaceWith("close()"))
    fun shutdown() = close()

    private fun transition(target: MatchState) {
        _state.set(target)
        onStateChanged?.invoke(target)
    }
}
```

Note: keep `shutdown()` as deprecated alias — callers will migrate organically.

### Step 4: Run tests to verify they pass

```bash
cd /Users/denislebedev/src/leyline && ./gradlew matchdoor:test --tests "leyline.conformance.MatchRegistryTest" --no-daemon -q 2>&1 | tail -20
```

Expected: PASS.

### Step 5: Wire close() into game-over and disconnect paths

In `MatchSession.sendGameOver()` (line ~550): after sending `MatchCompleted`, call `registry.getMatch(matchId)?.close()`. Requires adding `getMatch()` to MatchRegistry:

```kotlin
// MatchRegistry.kt — add accessor
fun getMatch(matchId: String): Match? = matches[matchId]
```

In `MatchHandler.channelInactive()` (line ~326): after logging disconnect, check if all sessions for this match are gone and close the match if so.

### Step 6: Wire close() into evictStale()

In `MatchRegistry.evictStale()` — call `match.close()` on each evicted match (currently callers do this manually):

```kotlin
fun evictStale(currentMatchId: String): List<Match> {
    val staleKeys = matches.keys.filter { it != currentMatchId }
    val evicted = staleKeys.mapNotNull { matches.remove(it) }
    staleKeys.forEach {
        sessions.remove(it)
        handlers.remove(it)
    }
    evicted.forEach { it.close() }
    return evicted
}
```

### Step 7: Run full matchdoor test suite

```bash
cd /Users/denislebedev/src/leyline && ./gradlew matchdoor:test --no-daemon -q 2>&1 | tail -30
```

Expected: all tests pass.

### Step 8: Format and commit

```bash
cd /Users/denislebedev/src/leyline && just fmt
git add matchdoor/src/main/kotlin/leyline/match/Match.kt matchdoor/src/main/kotlin/leyline/match/MatchRegistry.kt matchdoor/src/test/kotlin/leyline/conformance/MatchRegistryTest.kt
```

Commit message: `feat: add MatchState lifecycle + deterministic close() (#60 01-P2)`

---

## Task 2: Move resource ownership from GameBridge to Match (01-P3)

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/match/Match.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/game/GameBridge.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/match/MatchSession.kt` (if it reads `bridge.eventCollector` etc.)
- Test: existing tests must pass

### Step 1: Audit who reads GameBridge.loopController, eventCollector, playback

Search for `bridge.loopController`, `bridge.eventCollector`, `bridge.playback`, `gameBridge?.loopController` etc. These are the callers that need to be updated or delegated.

```bash
cd /Users/denislebedev/src/leyline && grep -rn "loopController\|eventCollector\|playback" matchdoor/src/main/kotlin/ --include="*.kt" | grep -v "GameBridge.kt" | grep -v "import"
```

### Step 2: Move fields to Match

Match gains:
```kotlin
var loopController: GameLoopController? = null
    private set
var eventCollector: GameEventCollector? = null
    private set
var playback: GamePlayback? = null
    private set
```

GameBridge loses these fields (or they become internal/delegated).

### Step 3: Update Match.start() to create resources

Move the resource creation from `GameBridge.start()` (lines 326-347) into `Match.start()`:
- After `bridge.start()` returns, the `game` field is set
- Match creates `GameEventCollector`, subscribes to EventBus
- Match creates `GamePlayback`, subscribes to EventBus
- Match stores references; GameBridge retains read-only access via bridge reference (or Match passes them in)

**Key constraint:** `GameEventCollector` takes `GameBridge` in constructor (for seat mapping). `GamePlayback` takes `GameBridge` + counter. These references stay — we're moving ownership, not decoupling.

### Step 4: Update Match.close() to unsubscribe EventBus

```kotlin
fun close() {
    if (!_state.compareAndSet(...)) return
    loopController?.shutdown()
    // Unsubscribe from EventBus before nulling
    bridge.game?.let { g ->
        eventCollector?.let { g.unsubscribeFromEvents(it) }
        playback?.let { g.unsubscribeFromEvents(it) }
    }
    loopController = null
    eventCollector = null
    playback = null
    bridge.shutdown() // now only clears per-seat state
    onStateChanged?.invoke(MatchState.FINISHED)
}
```

**Important:** Check if `Game.unsubscribeFromEvents()` exists. If not, Forge EventBus is Guava — use `game.events.unregister(obj)`. Search for the exact API.

### Step 5: Slim down GameBridge.shutdown()

GameBridge.shutdown() should only clear per-seat state (players map, humanController):

```kotlin
fun shutdown() {
    log.info("GameBridge: clearing per-seat state")
    humanController = null
    game = null
    players.clear()
}
```

### Step 6: Update callers that read bridge.eventCollector / bridge.playback

Any code that accesses `bridge.eventCollector` or `bridge.playback` needs to go through Match instead. If the caller only has a GameBridge reference, consider adding a back-reference or passing Match explicitly.

### Step 7: Run full matchdoor tests

```bash
cd /Users/denislebedev/src/leyline && ./gradlew matchdoor:test --no-daemon -q 2>&1 | tail -30
```

### Step 8: Format and commit

Commit message: `refactor: move GameLoopController/EventCollector/Playback ownership to Match (#60 01-P3)`

---

## Task 3: Per-seat bridge maps on GameBridge (02-P2)

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/GameBridge.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/match/MatchSession.kt` (accessor updates)
- Test: existing tests must pass + new unit test for accessor

### Step 1: Write failing test for per-seat bridge accessor

```kotlin
test("actionBridge(seatId) returns seat-specific bridge") {
    val bridge = GameBridge()
    // Before start(), seat 1 bridge exists (created in constructor)
    bridge.actionBridge(1) shouldBeSameInstanceAs bridge.actionBridge
}
```

### Step 2: Replace singleton bridge fields with maps

In `GameBridge.kt`, replace:

```kotlin
val actionBridge = GameActionBridge(...)
val promptBridge = InteractivePromptBridge(...)
private val seat1MulliganBridge = MulliganBridge(...)
```

With:

```kotlin
private val actionBridges = mutableMapOf<Int, GameActionBridge>()
private val promptBridges = mutableMapOf<Int, InteractivePromptBridge>()
private val mulliganBridges = mutableMapOf<Int, MulliganBridge>()

// Backward-compat accessors (seat 1 default)
val actionBridge: GameActionBridge get() = actionBridge(1)
val promptBridge: InteractivePromptBridge get() = promptBridge(1)

fun actionBridge(seatId: Int): GameActionBridge =
    actionBridges[seatId] ?: error("No action bridge for seat $seatId")
fun promptBridge(seatId: Int): InteractivePromptBridge =
    promptBridges[seatId] ?: error("No prompt bridge for seat $seatId")
fun mulliganBridge(seatId: Int): MulliganBridge =
    mulliganBridges[seatId] ?: error("No mulligan bridge for seat $seatId")
```

### Step 3: Populate maps in constructor (seat 1) and start()

In the `init` block (or field initializer), create seat-1 bridges:

```kotlin
init {
    actionBridges[1] = GameActionBridge(timeoutMs = bridgeTimeoutMs, prioritySignal = prioritySignal)
    promptBridges[1] = InteractivePromptBridge(timeoutMs = bridgeTimeoutMs, prioritySignal = prioritySignal)
    mulliganBridges[1] = MulliganBridge(autoKeep = matchConfig.game.skipMulligan, timeoutMs = bridgeTimeoutMs)
}
```

### Step 4: Update start() to use maps

Replace direct field references in `start()`:
- `this.promptBridge` → `promptBridge(1)` or just `promptBridge` (backward-compat accessor)
- `actionBridge` → same
- `seat1MulliganBridge` → `mulliganBridge(1)`

Update `GameLoopController` creation:
```kotlin
val loop = GameLoopController(
    g,
    actionBridges = actionBridges.values,
    promptBridges = promptBridges.values,
    mulliganBridges = mulliganBridges.values,
)
```

### Step 5: Update MatchSession to use parameterized accessors

Where `MatchSession` accesses `gameBridge?.actionBridge`, update to `gameBridge?.actionBridge(seatId)`. This is the key behavioral prep for PvP — each session talks to its own seat's bridge.

### Step 6: Run full matchdoor tests

```bash
cd /Users/denislebedev/src/leyline && ./gradlew matchdoor:test --no-daemon -q 2>&1 | tail -30
```

### Step 7: Format and commit

Commit message: `refactor: per-seat bridge maps on GameBridge (#60 02-P2)`

---

## Task 4: Remove seat-1 gates in MatchHandler (02-P3)

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/match/MatchHandler.kt` (lines 224-280)
- Test: existing tests must pass

### Step 1: Understand the current gates

In `MatchHandler.processGREMessage()` (lines 224-280), there are 6 `if (seatId == 1)` checks:

| Line | Message Type | Current behavior |
|------|-------------|-----------------|
| 225 | PerformActionResp | Only seat 1 handles |
| 233 | DeclareAttackersResp | Only seat 1 handles |
| 250 | DeclareBlockersResp | Only seat 1 handles |
| 264 | SelectTargetsResp | Only seat 1 handles |
| 270 | CancelActionReq | Only seat 1 handles |
| 276 | SelectNresp | Only seat 1 handles |

Two special cases (lines 244-246, 258-260): `SubmitAttackersReq` and `SubmitBlockersReq` route to seat-1's session regardless of source seat (Arena client bug — can send on either channel).

### Step 2: Replace seat-1 gates with Familiar filtering

The seat-1 gate exists to block the **Familiar** (spectator seat 2) from submitting actions. In PvP, seat 2 is a real player.

**New approach:** Familiar connections are identified during `handleMatchDoorConnect()`. Tag them:

```kotlin
// MatchHandler field
private var isFamiliar = false
```

Set during connect (line ~140-ish): `isFamiliar = (seatId == 2 && /* match is 1vAI */)`. For now, a simple approach: check if the match already has a seat-1 session connected. If seat 2 connects and seat 1 already exists → this is Familiar in 1vAI mode.

Better: add `isHumanVsAi()` check on Match or derive from the bridge's seat config.

### Step 3: Replace all 6 gates

Replace:
```kotlin
if (seatId == 1) {
    s?.onPerformAction(greMsg)
} else {
    log.debug("ignoring ... from Familiar")
}
```

With:
```kotlin
if (isFamiliar) {
    log.debug("ignoring ... from Familiar (seat {})", seatId)
} else {
    s?.onPerformAction(greMsg)
}
```

Apply to all 6 message types.

### Step 4: Keep SubmitAttackers/Blockers routing

The Submit*Req routing to seat-1 is a real Arena client bug workaround, not a Familiar filter. Keep it, but parameterize:

```kotlin
ClientMessageType.SubmitAttackersReq -> {
    // Arena client sends this on either channel (race condition).
    // Combat state lives on acting seat's CombatHandler.
    val target = if (isFamiliar) registry.activeSession() else s
    target?.onDeclareAttackers(greMsg)
}
```

### Step 5: Run full matchdoor tests

```bash
cd /Users/denislebedev/src/leyline && ./gradlew matchdoor:test --no-daemon -q 2>&1 | tail -30
```

### Step 6: Format and commit

Commit message: `refactor: replace seat-1 gates with Familiar filtering in MatchHandler (#60 02-P3)`

---

## Verification Checklist

After all 4 tasks:

1. `./gradlew matchdoor:test` — all green
2. `./gradlew test` — full project green (account + frontdoor + matchdoor + tooling)
3. `just serve` + connect with Arena client — bot match plays normally
4. Familiar (seat 2) still spectates correctly
5. No behavioral change in any scenario

## Risk Notes

- **Task 2 (resource ownership):** Highest risk — moving EventBus subscriptions. If `Game.unsubscribeFromEvents()` doesn't exist, use Guava `EventBus.unregister()` directly. Test with integration tests that start+stop games.
- **Task 3 (per-seat maps):** Low risk — backward-compat accessors mean zero caller changes initially.
- **Task 4 (seat gates):** Medium risk — must ensure Familiar detection is reliable. If unsure, use `seatId == 2 && !match.hasMultipleHumanSeats()` as the Familiar check (conservative: only opens gates when we explicitly create PvP matches in future).
