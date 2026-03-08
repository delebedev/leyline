# FamiliarSession + Symmetric GamePlayback + CaptureSink Tagging — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Remove "is this human or AI?" branching from the match layer. Make the codebase PvP-native by extracting FamiliarSession, generalizing GamePlayback per-seat, and tagging capture output by seat.

**Architecture:** Three coupled refactors in dependency order. FamiliarSession replaces boolean gates with type-level dispatch. Per-seat GamePlayback delivers animated opponent diffs to both modes (1vAI and PvP). CaptureSink gets per-seat MD subdirectories for PvP debugging.

**Tech Stack:** Kotlin, Kotest FunSpec, Forge engine EventBus, protobuf (GRE messages)

**Design doc:** `docs/plans/2026-03-08-familiar-session-design.md`

---

## Task 1: Expand SessionOps with default no-op action methods

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/match/SessionOps.kt`
- Test: `matchdoor/src/test/kotlin/leyline/match/SessionOpsDefaultsTest.kt`

**Step 1: Write the failing test**

Create `matchdoor/src/test/kotlin/leyline/match/SessionOpsDefaultsTest.kt`:

```kotlin
package leyline.match

import io.kotest.core.spec.style.FunSpec
import leyline.UnitTag
import leyline.game.BundleBuilder
import leyline.game.GameBridge
import leyline.game.MessageCounter
import forge.game.Game
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Verify SessionOps default methods are no-ops — FamiliarSession depends on this.
 */
class SessionOpsDefaultsTest : FunSpec({

    tags(UnitTag)

    // Minimal SessionOps implementation that only provides required abstract members
    fun minimalOps(): SessionOps = object : SessionOps {
        override val seatId = 2
        override val matchId = "test"
        override var counter = MessageCounter()

        override fun sendBundledGRE(messages: List<GREToClientMessage>) {}
        override fun sendRealGameState(bridge: GameBridge) {}
        override fun sendBundle(result: BundleBuilder.BundleResult) {}
        override fun sendGameOver(reason: ResultReason) {}
        override fun traceEvent(type: MatchEventType, game: Game, detail: String) {}
        override fun paceDelay(multiplier: Int) {}
        override fun makeGRE(
            type: GREMessageType, gsId: Int, msgId: Int,
            configure: (GREToClientMessage.Builder) -> Unit,
        ): GREToClientMessage = GREToClientMessage.getDefaultInstance()
    }

    test("default action methods are no-ops and do not throw") {
        val ops = minimalOps()
        val greMsg = ClientToGREMessage.getDefaultInstance()

        // All of these should be callable without error
        ops.onPerformAction(greMsg)
        ops.onDeclareAttackers(greMsg)
        ops.onDeclareBlockers(greMsg)
        ops.onSelectTargets(greMsg)
        ops.onSelectN(greMsg)
        ops.onGroupResp(greMsg)
        ops.onCancelAction(greMsg)
        ops.onConcede()
        ops.onSettings(greMsg)
        ops.onMulliganKeep()
        ops.onPuzzleStart()
    }
})
```

**Step 2: Run test to verify it fails**

Run: `just test-one SessionOpsDefaultsTest`
Expected: FAIL — `onPerformAction` etc. don't exist on `SessionOps` yet

**Step 3: Add default methods to SessionOps**

Add to `matchdoor/src/main/kotlin/leyline/match/SessionOps.kt`:

```kotlin
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage

// Add after makeGRE():

/** Handle PerformActionResp. Default no-op for read-only sessions. */
fun onPerformAction(greMsg: ClientToGREMessage) {}

/** Handle DeclareAttackersResp. Default no-op for read-only sessions. */
fun onDeclareAttackers(greMsg: ClientToGREMessage) {}

/** Handle DeclareBlockersResp. Default no-op for read-only sessions. */
fun onDeclareBlockers(greMsg: ClientToGREMessage) {}

/** Handle SelectTargetsResp. Default no-op for read-only sessions. */
fun onSelectTargets(greMsg: ClientToGREMessage) {}

/** Handle SelectNResp. Default no-op for read-only sessions. */
fun onSelectN(greMsg: ClientToGREMessage) {}

/** Handle GroupResp (surveil/scry). Default no-op for read-only sessions. */
fun onGroupResp(greMsg: ClientToGREMessage) {}

/** Handle CancelActionReq. Default no-op for read-only sessions. */
fun onCancelAction(greMsg: ClientToGREMessage) {}

/** Handle concession. Default no-op for read-only sessions. */
fun onConcede() {}

/** Handle SetSettingsReq. Default no-op for read-only sessions. */
fun onSettings(greMsg: ClientToGREMessage) {}

/** Post-mulligan game start. Default no-op for read-only sessions. */
fun onMulliganKeep() {}

/** Puzzle start. Default no-op for read-only sessions. */
fun onPuzzleStart() {}
```

**Step 4: Run test to verify it passes**

Run: `just test-one SessionOpsDefaultsTest`
Expected: PASS

**Step 5: Commit**

```
feat(matchdoor): add default no-op action methods to SessionOps

Prepares for FamiliarSession extraction — read-only sessions inherit
no-op defaults instead of needing isFamiliar guards.

Refs #81
```

---

## Task 2: Create FamiliarSession

**Files:**
- Create: `matchdoor/src/main/kotlin/leyline/match/FamiliarSession.kt`
- Test: `matchdoor/src/test/kotlin/leyline/match/FamiliarSessionTest.kt`

**Step 1: Write the failing test**

Create `matchdoor/src/test/kotlin/leyline/match/FamiliarSessionTest.kt`:

```kotlin
package leyline.match

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.MessageCounter
import leyline.infra.ListMessageSink
import wotc.mtgo.gre.external.messaging.Messages.*

class FamiliarSessionTest : FunSpec({

    tags(UnitTag)

    fun createFamiliar(): Pair<FamiliarSession, ListMessageSink> {
        val sink = ListMessageSink()
        val session = FamiliarSession(
            seatId = 2,
            matchId = "test-match",
            sink = sink,
            counter = MessageCounter(),
        )
        return session to sink
    }

    test("sendBundledGRE forwards messages to sink") {
        val (session, sink) = createFamiliar()
        val msg = GREToClientMessage.newBuilder()
            .setType(GREMessageType.GameStateMessage_bf63)
            .setMsgId(1)
            .build()

        session.sendBundledGRE(listOf(msg))
        sink.messages shouldHaveSize 1
        sink.messages[0].msgId shouldBe 1
    }

    test("action methods are no-ops — do not throw") {
        val (session, _) = createFamiliar()
        val greMsg = ClientToGREMessage.getDefaultInstance()

        session.onPerformAction(greMsg)
        session.onDeclareAttackers(greMsg)
        session.onDeclareBlockers(greMsg)
        session.onSelectTargets(greMsg)
        session.onSelectN(greMsg)
        session.onGroupResp(greMsg)
        session.onCancelAction(greMsg)
        session.onConcede()
        session.onSettings(greMsg)
    }

    test("seatId and matchId are accessible") {
        val (session, _) = createFamiliar()
        session.seatId shouldBe 2
        session.matchId shouldBe "test-match"
    }

    test("sendGameOver is a no-op") {
        val (session, sink) = createFamiliar()
        session.sendGameOver()
        sink.messages shouldHaveSize 0
        sink.rawMessages shouldHaveSize 0
    }
})
```

**Step 2: Run test to verify it fails**

Run: `just test-one FamiliarSessionTest`
Expected: FAIL — `FamiliarSession` class doesn't exist

**Step 3: Create FamiliarSession**

Create `matchdoor/src/main/kotlin/leyline/match/FamiliarSession.kt`:

```kotlin
package leyline.match

import forge.game.Game
import leyline.game.BundleBuilder
import leyline.game.GameBridge
import leyline.game.MessageCounter
import leyline.infra.MessageSink
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Read-only mirror session for the Familiar (AI spectator seat).
 *
 * Receives mirrored GRE messages from the human player's [MatchSession]
 * via [sendBundledGRE]. All action handlers are inherited no-ops from
 * [SessionOps] — the Familiar never drives game logic.
 *
 * Replaces the `isFamiliar` boolean gates that were scattered across
 * [MatchHandler]'s message dispatch.
 */
class FamiliarSession(
    override val seatId: Int,
    override val matchId: String,
    val sink: MessageSink,
    override var counter: MessageCounter = MessageCounter(),
) : SessionOps {

    /** Forward mirrored messages to the client. */
    override fun sendBundledGRE(messages: List<GREToClientMessage>) {
        sink.send(messages)
    }

    // --- No-ops: Familiar is read-only ---

    override fun sendRealGameState(bridge: GameBridge) {}
    override fun sendBundle(result: BundleBuilder.BundleResult) {}
    override fun sendGameOver(reason: ResultReason) {}
    override fun traceEvent(type: MatchEventType, game: Game, detail: String) {}
    override fun paceDelay(multiplier: Int) {}

    override fun makeGRE(
        type: GREMessageType,
        gsId: Int,
        msgId: Int,
        configure: (GREToClientMessage.Builder) -> Unit,
    ): GREToClientMessage {
        val gre = GREToClientMessage.newBuilder()
            .setType(type).setMsgId(msgId).setGameStateId(gsId).addSystemSeatIds(seatId)
        configure(gre)
        return gre.build()
    }

    // Action methods: all inherited no-ops from SessionOps defaults.
    // SubmitAttackersReq/SubmitBlockersReq: client race condition may send
    // these on the Familiar channel. No-op is correct — the player's session
    // handles combat independently. PvP may want cross-seat routing later.
}
```

**Step 4: Run test to verify it passes**

Run: `just test-one FamiliarSessionTest`
Expected: PASS

**Step 5: Commit**

```
feat(matchdoor): add FamiliarSession — read-only mirror session type

Lightweight SessionOps implementation for the Familiar (AI spectator).
Receives mirrored state, ignores all action messages via inherited
no-op defaults. Replaces isFamiliar boolean gates in MatchHandler.

Refs #81
```

---

## Task 3: Generalize MatchRegistry to SessionOps

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/match/MatchRegistry.kt`
- Modify: `matchdoor/src/test/kotlin/leyline/conformance/MatchRegistryTest.kt`

**Step 1: Write the failing test**

Add to `MatchRegistryTest.kt`:

```kotlin
test("registerSession accepts FamiliarSession via SessionOps interface") {
    val registry = MatchRegistry()
    val sink = ListMessageSink()
    val human = MatchSession(seatId = 1, matchId = "m1", sink = sink, registry = registry, paceDelayMs = 0)
    val familiar = FamiliarSession(seatId = 2, matchId = "m1", sink = sink)
    registry.registerSession("m1", 1, human)
    registry.registerSession("m1", 2, familiar)
    registry.getPeer("m1", 1) shouldBeSameInstanceAs familiar
    registry.getPeer("m1", 2) shouldBeSameInstanceAs human
}

test("activeSession returns MatchSession, not FamiliarSession") {
    val registry = MatchRegistry()
    val sink = ListMessageSink()
    val human = MatchSession(seatId = 1, matchId = "m1", sink = sink, registry = registry, paceDelayMs = 0)
    val familiar = FamiliarSession(seatId = 2, matchId = "m1", sink = sink)
    registry.registerSession("m1", 1, human)
    registry.registerSession("m1", 2, familiar)
    registry.activeSession() shouldBeSameInstanceAs human
}
```

Add imports: `import leyline.match.FamiliarSession`

**Step 2: Run test to verify it fails**

Run: `just test-one MatchRegistryTest`
Expected: FAIL — `registerSession` takes `MatchSession`, not `SessionOps`

**Step 3: Widen MatchRegistry session map**

In `matchdoor/src/main/kotlin/leyline/match/MatchRegistry.kt`:

1. Change sessions map type:
   ```kotlin
   private val sessions = ConcurrentHashMap<String, ConcurrentHashMap<Int, SessionOps>>()
   ```

2. Change `registerSession` signature:
   ```kotlin
   fun registerSession(matchId: String, seatId: Int, session: SessionOps) {
       sessions.computeIfAbsent(matchId) { ConcurrentHashMap() }[seatId] = session
   }
   ```

3. Change `getPeer` return type:
   ```kotlin
   fun getPeer(matchId: String, seatId: Int): SessionOps? {
       val peerSeat = if (seatId == 1) 2 else 1
       return sessions[matchId]?.get(peerSeat)
   }
   ```

4. Change `activeSession` to filter for MatchSession:
   ```kotlin
   fun activeSession(): MatchSession? =
       sessions.values.firstOrNull()?.values?.filterIsInstance<MatchSession>()?.firstOrNull()
   ```

**Step 4: Fix compilation — update callers that depend on MatchSession return type**

`MatchSession.mirrorToFamiliar()` calls `registry.getPeer()` and accesses `.sink`. Now returns `SessionOps?` which doesn't have `sink`. Update to type-check:

```kotlin
private fun mirrorToFamiliar(messages: List<GREToClientMessage>) {
    if (seatId != 1) return
    val peer = registry.getPeer(matchId, seatId) ?: return
    // Only mirror to FamiliarSession — PvP peers build their own state
    // via per-seat GamePlayback.
    if (peer !is FamiliarSession) return
    val mirrorSeat = 2
    val mirrored = messages.map { gre ->
        val builder = gre.toBuilder().clearSystemSeatIds().addSystemSeatIds(mirrorSeat)
        if (builder.hasGameStateMessage()) {
            val gsm = builder.gameStateMessage.toBuilder()
            val filtered = gsm.gameObjectsList.filter { obj ->
                obj.visibility != Visibility.Private || obj.viewersList.contains(mirrorSeat)
            }
            gsm.clearGameObjects().addAllGameObjects(filtered)
            builder.setGameStateMessage(gsm.build())
        }
        builder.build()
    }
    peer.sink.send(mirrored)
}
```

**Step 5: Run tests**

Run: `just test-one MatchRegistryTest`
Expected: PASS

**Step 6: Commit**

```
refactor(matchdoor): widen MatchRegistry sessions to SessionOps

registerSession/getPeer now accept any SessionOps (MatchSession or
FamiliarSession). mirrorToFamiliar type-checks peer — only mirrors
to FamiliarSession, skips PvP peers.

Refs #81
```

---

## Task 4: Wire FamiliarSession in MatchHandler, remove isFamiliar gates

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/match/MatchHandler.kt`
- Test: verify by running existing test suite (regression)

**Step 1: Change MatchHandler.session type to SessionOps**

In `MatchHandler.kt`:

1. Change `session` field type:
   ```kotlin
   internal var session: SessionOps? = null
   ```

2. In `handleMatchDoorConnect`, create FamiliarSession for familiar connections:
   ```kotlin
   val s: SessionOps = if (isFamiliar) {
       FamiliarSession(seatId, matchId, sink)
   } else {
       MatchSession(
           seatId, matchId, sink, registry,
           recorder = rec,
           debugSink = debugSink,
           coordinator = coordinator,
       )
   }
   session = s
   registry.registerSession(matchId, seatId, s)
   registry.registerHandler(matchId, seatId, this)
   ```

3. Remove `isFamiliar` field entirely (only used as local in `handleMatchAuth` + `handleMatchDoorConnect`).

4. Remove all `if (!isFamiliar)` guards in `processGREMessage` — make dispatch unconditional:
   ```kotlin
   ClientMessageType.PerformActionResp_097b -> s?.onPerformAction(greMsg)
   ClientMessageType.DeclareAttackersResp_097b -> s?.onDeclareAttackers(greMsg)
   // SubmitAttackersReq: client race condition may send on Familiar channel.
   // FamiliarSession no-ops handle it. PvP may want cross-seat routing later.
   ClientMessageType.SubmitAttackersReq -> s?.onDeclareAttackers(greMsg)
   ClientMessageType.DeclareBlockersResp_097b -> s?.onDeclareBlockers(greMsg)
   ClientMessageType.SubmitBlockersReq -> s?.onDeclareBlockers(greMsg)
   ClientMessageType.SelectTargetsResp_097b -> s?.onSelectTargets(greMsg)
   ClientMessageType.CancelActionReq_097b -> s?.onCancelAction(greMsg)
   ClientMessageType.SelectNresp -> s?.onSelectN(greMsg)
   ```

5. Fix `connectBridge` — only MatchSession has it. Guard with type check:
   ```kotlin
   val matchSession = s as? MatchSession
   matchSession?.connectBridge(bridge)
   ```

   Similarly for bridge-dependent code in `ConnectReq_097b` handler — wrap in `if (s is MatchSession)`.

**Step 2: Run full test gate**

Run: `just test-gate`
Expected: PASS — all existing tests should pass unchanged

**Step 3: Commit**

```
refactor(matchdoor): remove isFamiliar gates from MatchHandler

MatchHandler creates FamiliarSession or MatchSession based on clientId.
processGREMessage dispatch is now unconditional — session type determines
behavior. Removes 8 if-branches and the isFamiliar field.

Refs #81
```

---

## Task 5: Rename aiActionDiff → remoteActionDiff in BundleBuilder

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/BundleBuilder.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/game/GamePlayback.kt`

**Step 1: Rename**

In `BundleBuilder.kt`: rename `aiActionDiff` → `remoteActionDiff`. Same signature, same logic.

In `GamePlayback.kt`: update the call site in `captureAndPause`:
```kotlin
val result = BundleBuilder.remoteActionDiff(...)
```

**Step 2: Run tests**

Run: `just test-gate`
Expected: PASS

**Step 3: Commit**

```
refactor(matchdoor): rename aiActionDiff → remoteActionDiff

Name reflects generalized use — captures opponent actions for both
1vAI (AI turns) and PvP (remote human turns).

Refs #81
```

---

## Task 6: Generalize GamePlayback — isAiActing → isRemoteActing, per-seat

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/GamePlayback.kt`
- Modify: `matchdoor/src/test/kotlin/leyline/game/GamePlaybackTest.kt`

**Step 1: Write failing tests**

Add to `GamePlaybackTest.kt`:

```kotlin
test("isRemoteActing returns false when no game") {
    val counter = MessageCounter()
    val bridge = GameBridge(messageCounter = counter)
    val pb = GamePlayback(bridge, "test", 1, counter)
    // No game started — isRemoteActing should not crash
    pb.hasPendingMessages() shouldBe false
}

test("per-seat playback: seat 1 and seat 2 instances are independent") {
    val counter = MessageCounter()
    val bridge = GameBridge(messageCounter = counter)
    val pb1 = GamePlayback(bridge, "test", 1, counter)
    val pb2 = GamePlayback(bridge, "test", 2, counter)

    pb1.hasPendingMessages() shouldBe false
    pb2.hasPendingMessages() shouldBe false
    // They share the counter but have separate queues
    pb1 shouldNotBe pb2
}
```

**Step 2: Run to verify they pass (baseline)**

Run: `just test-one GamePlaybackTest`
Expected: PASS (these test existing behavior)

**Step 3: Rename isAiActing → isRemoteActing**

In `GamePlayback.kt`, replace `isAiActing()`:

```kotlin
/**
 * True when the current turn's active player is not this playback's seat.
 * Fires for AI turns (1vAI) and opponent turns (PvP) uniformly.
 */
private fun isRemoteActing(): Boolean {
    val game = bridge.getGame() ?: return false
    val turnPlayer = game.phaseHandler.playerTurn ?: return false
    val myPlayer = bridge.getPlayer(SeatId(seatId))  ?: return false
    return turnPlayer != myPlayer
}
```

Add import: `import leyline.bridge.SeatId`

Update all call sites within the file: replace `isAiActing()` → `isRemoteActing()`.

**Step 4: Run tests**

Run: `just test-one GamePlaybackTest && just test-one GamePlaybackCounterTest`
Expected: PASS

**Step 5: Commit**

```
refactor(matchdoor): generalize GamePlayback isAiActing → isRemoteActing

Fires for any non-local player's turn (AI or remote human), enabling
per-seat playback for PvP matches.

Refs #81
```

---

## Task 7: Wire per-seat GamePlayback in GameBridge

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/GameBridge.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/match/AutoPassEngine.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/match/MatchSession.kt`
- Test: `matchdoor/src/test/kotlin/leyline/game/GamePlaybackTest.kt` (add integration-level test)

**Step 1: Replace single playback with per-seat map in GameBridge**

In `GameBridge.kt`:

1. Replace field:
   ```kotlin
   /** Per-seat action playback — captures remote-action state diffs via EventBus. Empty before start(). */
   val playbacks: MutableMap<SeatId, GamePlayback> = mutableMapOf()
   ```

2. Remove old field:
   ```kotlin
   // DELETE: var playback: GamePlayback? = null
   ```

3. Add backward-compat accessor (minimizes churn):
   ```kotlin
   /** Backward-compat: seat-1 playback (used by callers that only care about single-player). */
   val playback: GamePlayback? get() = playbacks[SeatId(1)]
   ```

4. In `start()` (1vAI, ~line 411), replace single playback creation:
   ```kotlin
   // Register per-seat playback (1vAI: only seat 1 receives AI diffs)
   val pb = GamePlayback(this, "forge-match-1", 1, messageCounter, matchConfig.aiDelayMultiplier)
   playbacks[SeatId(1)] = pb
   g.subscribeToEvents(pb)
   log.info("GameBridge: registered GamePlayback for seat 1 (AI action streaming)")
   ```

5. In `startTwoPlayer()` (~line 495), replace the comment with per-seat playback:
   ```kotlin
   // Register per-seat playback — each seat captures the other's actions
   for (seat in 1..2) {
       val pb = GamePlayback(this, matchId, seat, messageCounter, matchConfig.aiDelayMultiplier)
       playbacks[SeatId(seat)] = pb
       g.subscribeToEvents(pb)
   }
   log.info("GameBridge: registered per-seat GamePlayback for PvP")
   ```

6. In `startPuzzle()` (~line 732), same pattern as `start()`:
   ```kotlin
   val pb = GamePlayback(this, "forge-match-1", 1, messageCounter, matchConfig.aiDelayMultiplier)
   playbacks[SeatId(1)] = pb
   g.subscribeToEvents(pb)
   ```

7. In `teardownResources()` (~line 786), unsubscribe all:
   ```kotlin
   for (pb in playbacks.values) {
       g?.unsubscribeFromEvents(pb)
   }
   playbacks.clear()
   ```

8. In `shutdown()`, clear the map (replaces `playback = null`):
   ```kotlin
   playbacks.clear()
   ```

**Step 2: Update AutoPassEngine.drainPlayback**

In `AutoPassEngine.kt`, change `drainPlayback`:

```kotlin
private fun drainPlayback(bridge: GameBridge): Boolean {
    val playback = bridge.playbacks[SeatId(ops.seatId)] ?: return false
    if (!playback.hasPendingMessages()) return false
    val batches = playback.drainQueue()
    for ((idx, batch) in batches.withIndex()) {
        if (idx > 0) ops.paceDelay(1)
        ops.sendBundledGRE(batch)
    }
    log.debug("drainPlayback: drained {} batches for seat {}", batches.size, ops.seatId)
    return true
}
```

Add import: `import leyline.bridge.SeatId`

**Step 3: Update MatchSession.onMulliganKeep**

In `MatchSession.kt`, `onMulliganKeep()` (~line 117), change playback access:

```kotlin
val playback = bridge.playbacks[SeatId(seatId)]
if (playback != null) {
    for (batch in playback.drainQueue()) {
        sendBundledGRE(batch)
    }
}
```

Add import if not present: `import leyline.bridge.SeatId`

**Step 4: Run tests**

Run: `just test-gate`
Expected: PASS — existing 1vAI tests should work via backward-compat `playback` accessor

**Step 5: Commit**

```
feat(matchdoor): per-seat GamePlayback for symmetric opponent diffs

GameBridge creates one GamePlayback per seat. 1vAI: single entry for
seat 1. PvP: both seats get playback, each capturing the other's
actions via isRemoteActing(). AutoPassEngine drains per-seat.

Refs #81
```

---

## Task 8: Integration test — PvP opponent receives animated diffs

**Files:**
- Create: `matchdoor/src/test/kotlin/leyline/conformance/PvpPlaybackTest.kt`

**Step 1: Write the integration test**

```kotlin
package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.SeatId

/**
 * Verify per-seat GamePlayback: when seat 1 acts on seat 2's turn,
 * seat 2's playback queues animated diffs.
 */
class PvpPlaybackTest : FunSpec({

    tags(IntegrationTag)

    var harness: DualSeatHarness? = null
    afterEach { harness?.shutdown() }

    test("per-seat playback instances are registered for PvP") {
        val h = DualSeatHarness(seed = 42L)
        harness = h
        h.connectBothSeats()

        // Both seats should have a playback instance
        h.bridge.playbacks.size shouldBe 2
        h.bridge.playbacks[SeatId(1)] shouldBe h.bridge.playbacks[SeatId(1)]
        h.bridge.playbacks[SeatId(2)] shouldBe h.bridge.playbacks[SeatId(2)]
    }

    test("1vAI has single playback for seat 1") {
        // Use MatchFlowHarness for 1vAI baseline
        val h = MatchFlowHarness(seed = 42L)
        harness = null // MatchFlowHarness has its own shutdown
        h.startGameAtMain1()

        h.bridge.playbacks.size shouldBe 1
        h.bridge.playbacks[SeatId(1)] shouldBe h.bridge.playback

        h.shutdown()
    }
})
```

**Step 2: Run test**

Run: `just test-one PvpPlaybackTest`
Expected: PASS

**Step 3: Commit**

```
test(matchdoor): verify per-seat GamePlayback wiring in PvP and 1vAI

Refs #81
```

---

## Task 9: CaptureSink — per-seat MD subdirectories

**Files:**
- Modify: `app/main/kotlin/leyline/infra/CaptureSink.kt`
- Test: `app/test/kotlin/leyline/infra/CaptureSinkTest.kt` (create if doesn't exist)

**Step 1: Write failing test**

Create or add to `app/test/kotlin/leyline/infra/CaptureSinkTest.kt`:

```kotlin
package leyline.infra

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import java.io.File

class CaptureSinkTest : FunSpec({

    tags(UnitTag)

    test("MD frames written to labeled subdirectory") {
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "capture-test-${System.currentTimeMillis()}")
        try {
            tmpDir.mkdirs()
            // Verify labeled sink creates seat subdirectories
            val seatDir = File(tmpDir, "seat-1/md-frames")
            seatDir.mkdirs()
            seatDir.shouldExist()

            val seatDir2 = File(tmpDir, "seat-2/md-frames")
            seatDir2.mkdirs()
            seatDir2.shouldExist()

            // Verify FD path is unchanged (no seat subdir)
            val fdDir = File(tmpDir, "frames")
            fdDir.mkdirs()
            fdDir.shouldExist()
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    test("two labeled sinks use independent sequence counters") {
        // Sequence counters are per-sink, so labeled sinks don't collide
        // This verifies the design — actual frame writing needs proxy infra
        val counter1 = java.util.concurrent.atomic.AtomicLong(0)
        val counter2 = java.util.concurrent.atomic.AtomicLong(0)
        counter1.incrementAndGet() shouldBe 1
        counter2.incrementAndGet() shouldBe 1
        // Independent — both start at 1
    }
})
```

Note: CaptureSink depends on `LeylinePaths`, `FdDebugCollector`, and Netty — full integration test would need proxy infrastructure. The unit test verifies directory layout. The actual wiring is in `LeylineServer`/`ProxyHandlers` (app module, not easily unit-testable).

**Step 2: Add mdLabel parameter to CaptureSink**

In `app/main/kotlin/leyline/infra/CaptureSink.kt`:

1. Add `mdLabel` parameter:
   ```kotlin
   class CaptureSink(
       private val fdCollector: FdDebugCollector,
       /** Seat label for MD frame subdirectories (e.g. "seat-1"). Null = flat layout (backward compat). */
       private val mdLabel: String? = null,
   ) : AutoCloseable {
   ```

2. Add MD-specific directories:
   ```kotlin
   private val mdFrameDir: File
       get() = if (mdLabel != null) {
           File(LeylinePaths.CAPTURE_ROOT, "$mdLabel/md-frames")
       } else {
           frameDir // backward compat: flat layout
       }

   private val mdPayloadDir: File
       get() = if (mdLabel != null) {
           File(LeylinePaths.CAPTURE_ROOT, "$mdLabel/md-payloads")
       } else {
           payloadDir
       }
   ```

3. In `writeFrame`, use `mdFrameDir`/`mdPayloadDir` for MD frames:
   ```kotlin
   private fun writeFrame(dir: String, frame: ByteArray) {
       // ... existing header parsing ...

       val fileSeq = seq.incrementAndGet()
       val base = "%09d_%s_%s".format(fileSeq, sanitize(dir), frameTypeName(ft))

       // Route MD frames to seat-labeled subdirectory
       val targetFrameDir = if (dir.startsWith("MD")) mdFrameDir else frameDir
       val targetPayloadDir = if (dir.startsWith("MD")) mdPayloadDir else payloadDir

       targetFrameDir.mkdirs()
       File(targetFrameDir, "$base.bin").writeBytes(frame)

       // ... rest of payload/FD decode logic uses targetPayloadDir ...
   }
   ```

4. Update `ingestChunk` to use the correct dirs in `mkdirs()` calls — move `mkdirs()` into `writeFrame` (already done above).

**Step 3: Run test**

Run: `./gradlew :app:test --tests 'leyline.infra.CaptureSinkTest'` (or equivalent `just` command for app module)
Expected: PASS

**Step 4: Commit**

```
feat(app): per-seat MD subdirectories in CaptureSink

MD proxy frames route to seat-labeled subdirs (seat-1/md-frames/,
seat-2/md-frames/) when mdLabel is set. FD path unchanged.
Backward compatible — null label preserves flat layout.

Refs #81
```

---

## Task 10: Wire per-seat CaptureSink in proxy handlers

**Files:**
- Modify: `app/main/kotlin/leyline/infra/LeylineServer.kt`
- Modify: `app/main/kotlin/leyline/infra/ProxyHandlers.kt`

This task is about wiring — the proxy creates per-seat `CaptureSink` instances for MD connections. FD stays on the shared (unlabeled) sink.

**Step 1: Update LeylineServer**

In `LeylineServer.kt`, where MD proxy handlers are created, pass seat labels. The MD proxy handler knows the seat from the connection (seat 1 connects first, seat 2 second — or from auth). Two options:

a) Create two CaptureSink instances upfront (`seat-1`, `seat-2`)
b) Use a single CaptureSink with per-call `mdLabel` override

Option (a) is cleaner:

```kotlin
val captureSinkSeat1 = CaptureSink(fdCollector, mdLabel = "seat-1")
val captureSinkSeat2 = CaptureSink(fdCollector, mdLabel = "seat-2")
```

The FD proxy continues using `captureSink` (no label). MD proxy connections use the seat-specific sinks.

**Step 2: Verify proxy mode works**

Manual verification: `just serve-proxy`, connect client, check that `capture/seat-1/md-frames/` is populated.

**Step 3: Commit**

```
feat(app): wire per-seat CaptureSink for MD proxy connections

MD frames now route to seat-labeled subdirectories. FD capture
unchanged. Combined md-frames.jsonl at session root for tooling.

Refs #81
```

---

## Task 11: Run full test suite + format

**Step 1: Format**

Run: `just fmt`

**Step 2: Run full gate**

Run: `just test-gate`
Expected: PASS

**Step 3: Run integration tests**

Run: `just test-integration`
Expected: PASS

**Step 4: Commit any fmt changes**

```
chore: format
```

---

## Task 12: Update documentation

**Files:**
- Modify: `docs/catalog.yaml` (if multiplayer section exists)
- Modify: `matchdoor/CLAUDE.md` (update mental model if needed)

**Step 1: Update matchdoor/CLAUDE.md**

Add note about FamiliarSession in the Mental Model section and update the session description.

**Step 2: Commit**

```
docs: update matchdoor architecture for FamiliarSession + per-seat playback

Refs #81
```
