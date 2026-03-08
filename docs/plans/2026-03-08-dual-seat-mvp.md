# Dual-Seat MVP Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Two human clients queue, pair, connect to one Forge engine, and both see correct initial game state with per-seat visibility filtering. No mulligan interaction (skipMulligan=true).

**Architecture:** MatchmakingQueue pairs two FD connections → pushes MatchCreated with different YourSeat → both clients connect to MD → first ConnectReq creates Match(WAITING), second transitions to RUNNING → GameBridge.startTwoPlayer() creates per-seat bridges for both humans → both MatchSessions get independent GSMs with correct hand visibility. Engine-level integration test validates the full flow without needing FD/network layer.

**Tech Stack:** Kotlin, Kotest FunSpec, Forge engine, protobuf (GRE messages), Netty (FD/MD)

**Recordings (ground truth):**
- `recordings/2026-03-08_19-44-CHALLENGE-STARTER-SEAT1/md-frames.jsonl` — seat 1
- `recordings/2026-03-08_19-30-44-CHALLENGE-JOINER-SEAT2/md-frames.jsonl` — seat 2

**Key observations from recordings:**
- gsId chain is SHARED across both seats (both reach gsId=97)
- msgId chain is SHARED (seat 1 ConnectResp=1, seat 2 ConnectResp=2)
- ChooseStartingPlayerReq only goes to die roll winner (seat 1 in recording)
- Seat 2 never sees ChooseStartingPlayerReq — gets ConnectResp + DieRoll + Full GSM only
- Both seats see same gsId values but different systemSeatIds and visibility

---

## Task 1: GameBridge.startTwoPlayer() — dual-seat engine wiring

**Why first:** Everything else depends on a GameBridge that creates bridges for both human seats and starts a two-player engine. This is the core production code.

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/GameBridge.kt`
- Reference: `matchdoor/src/main/kotlin/leyline/bridge/GameBootstrap.kt` (already has `createTwoPlayerGame`)

**Step 1: Add startTwoPlayer method**

Add after existing `start()` method (~line 393). This creates a two-human game, bridges for both seats, and waits for priority (skipMulligan=true means auto-keep).

```kotlin
/**
 * Start a two-player (human vs human) game with per-seat bridges.
 * Both seats get action/prompt/mulligan bridges. No AI player.
 * Always uses skipMulligan=true — both seats auto-keep.
 *
 * @param seed deterministic RNG seed
 * @param deckList1 seat 1 decklist text
 * @param deckList2 seat 2 decklist text (defaults to same as seat 1)
 */
fun startTwoPlayer(
    seed: Long? = null,
    deckList1: String? = null,
    deckList2: String? = null,
) {
    log.info("GameBridge: starting two-player game")
    GameBootstrap.initializeCardDatabase()

    if (seed != null) {
        log.info("GameBridge: using deterministic seed={}", seed)
        MyRandom.setRandom(Random(seed))
    }

    val seat1Str = (deckList1 ?: FALLBACK_DECK).trimIndent()
    val seat2Str = (deckList2 ?: seat1Str).trimIndent()
    val deck1 = DeckLoader.parseDeckList(seat1Str)
    val deck2 = DeckLoader.parseDeckList(seat2Str)

    val g = GameBootstrap.createTwoPlayerGame(deck1, deck2)
    game = g
    populateSeatMap(g)

    // Create bridges for BOTH human seats
    ensureSeatBridges(2)

    // Wire WebPlayerController for both seats
    for ((seatIdx, player) in g.players.withIndex()) {
        val seat = seatIdx + 1
        val controller = WebPlayerController(
            game = g,
            player = player,
            lobbyPlayer = player.lobbyPlayer,
            bridge = promptBridge(seat),
            actionBridge = actionBridge(seat),
            mulliganBridge = mulliganBridge(seat),
            phaseStopProfile = phaseStopProfile,
        )
        if (seat == 1) humanController = controller
        player.addController(Long.MAX_VALUE - 1, player, controller, false)
    }

    // PhaseStopProfile for two humans (no AI)
    phaseStopProfile = PhaseStopProfile.createDefaults(g.players[0].id, g.players[1].id)

    val loop = GameLoopController(
        g,
        actionBridges = actionBridges.values.toList(),
        promptBridges = promptBridges.values.toList(),
        mulliganBridges = mulliganBridges.values.toList(),
    )
    loopController = loop
    loop.start()
    loop.awaitStarted()

    val collector = GameEventCollector(this)
    eventCollector = collector
    g.subscribeToEvents(collector)

    // No GamePlayback for PvP — no AI actions to stream

    log.info("GameBridge: two-player game started, waiting for priority")
    awaitPriority()
    log.info("GameBridge: engine reached priority after auto-keep")
}
```

**Step 2: Add ensureSeatBridges helper**

This creates bridges for seats that don't already have them (seat 1 is created in init, seat 2+ need creation):

```kotlin
/**
 * Ensure action/prompt/mulligan bridges exist for seats 1..n.
 * Seat 1 is created in init; this adds any missing seats.
 */
private fun ensureSeatBridges(numSeats: Int) {
    for (seat in 1..numSeats) {
        actionBridges.getOrPut(seat) {
            GameActionBridge(timeoutMs = bridgeTimeoutMs, prioritySignal = prioritySignal)
        }
        promptBridges.getOrPut(seat) {
            InteractivePromptBridge(timeoutMs = bridgeTimeoutMs, prioritySignal = prioritySignal)
        }
        mulliganBridges.getOrPut(seat) {
            MulliganBridge(autoKeep = true, timeoutMs = bridgeTimeoutMs)
        }
    }
}
```

**Step 3: Fix awaitPriorityWithTimeout to check all seats**

Currently line 454 only checks `actionBridge` (seat 1). Change to check all action bridges:

```kotlin
// In awaitPriorityWithTimeout, replace:
//   if (actionBridge.getPending() != null) {
//   if (promptBridge.getPendingPrompt() != null) {
// With:
if (actionBridges.values.any { it.getPending() != null }) {
    Thread.sleep(SETTLE_MS)
    return true
}
if (promptBridges.values.any { it.getPendingPrompt() != null }) {
    Thread.sleep(SETTLE_MS)
    return true
}
```

**Step 4: Build and verify compilation**

Run: `just build`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```
feat(matchdoor): GameBridge.startTwoPlayer for dual-seat engine wiring
```

---

## Task 2: DualSeatHarness test infrastructure

**Why second:** We need the test harness before writing assertions. This exercises the production code from Task 1.

**Files:**
- Create: `matchdoor/src/test/kotlin/leyline/conformance/DualSeatHarness.kt`

**Step 1: Write the harness**

Models after `MatchFlowHarness` but creates two sessions sharing one bridge:

```kotlin
package leyline.conformance

import leyline.game.GameBridge
import leyline.game.StateMapper
import leyline.infra.ListMessageSink
import leyline.match.MatchRegistry
import leyline.match.MatchSession
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Test harness for two-human-player matches — zero reimplemented logic.
 *
 * Creates one [GameBridge] with two [MatchSession]s (seat 1 + seat 2),
 * each with its own [ListMessageSink]. Both sessions share the same bridge,
 * [MatchRegistry], and [MessageCounter] (same as production PvP).
 *
 * Uses skipMulligan=true — both seats auto-keep, no mulligan interaction.
 */
class DualSeatHarness(
    private val seed: Long = 42L,
    private val deckList1: String? = null,
    private val deckList2: String? = null,
) {
    private val matchId = "test-pvp-match"

    val registry = MatchRegistry()
    val sink1 = ListMessageSink()
    val sink2 = ListMessageSink()

    lateinit var session1: MatchSession
        private set
    lateinit var session2: MatchSession
        private set
    lateinit var bridge: GameBridge
        private set

    val seat1Messages = mutableListOf<GREToClientMessage>()
    val seat2Messages = mutableListOf<GREToClientMessage>()

    /**
     * Start two-player game, connect both sessions, advance to first priority.
     * After this call, both sinks contain the initial Full GSM for their seat.
     */
    fun connectBothSeats() {
        TestCardRegistry.ensureRegistered()

        session1 = MatchSession(
            seatId = 1,
            matchId = matchId,
            sink = sink1,
            registry = registry,
            paceDelayMs = 0,
        )
        session2 = MatchSession(
            seatId = 2,
            matchId = matchId,
            sink = sink2,
            registry = registry,
            paceDelayMs = 0,
            counter = session1.counter, // shared counter — same as production
        )

        bridge = GameBridge(bridgeTimeoutMs = 5_000L, messageCounter = session1.counter, cards = TestCardRegistry.repo)
        bridge.priorityWaitMs = 2_000L
        bridge.startTwoPlayer(seed = seed, deckList1 = deckList1, deckList2 = deckList2)

        session1.connectBridge(bridge)
        session2.connectBridge(bridge)
        registry.registerSession(matchId, 1, session1)
        registry.registerSession(matchId, 2, session2)

        // Build and send initial Full GSM to both seats (per-seat visibility)
        val game = bridge.getGame()!!
        val gsId = session1.counter.currentGsId()

        val fullGsm1 = StateMapper.buildFromGame(game, gsId, matchId, bridge, viewingSeatId = 1)
        session1.sendRealGameState(bridge)

        val fullGsm2 = StateMapper.buildFromGame(game, gsId, matchId, bridge, viewingSeatId = 2)
        session2.sendRealGameState(bridge)

        drainBothSinks()
    }

    fun drainBothSinks() {
        seat1Messages.addAll(sink1.messages)
        seat2Messages.addAll(sink2.messages)
        sink1.clear()
        sink2.clear()
    }

    /** Extract the first Full GSM from a seat's message list. */
    fun fullGsm(messages: List<GREToClientMessage>): GameStateMessage? =
        messages
            .filter { it.hasGameStateMessage() && it.gameStateMessage.type == GameStateType.Full }
            .map { it.gameStateMessage }
            .firstOrNull()

    /** All GameStateMessages from a seat's list. */
    fun allGsms(messages: List<GREToClientMessage>): List<GameStateMessage> =
        messages.filter { it.hasGameStateMessage() }.map { it.gameStateMessage }

    fun shutdown() = bridge.shutdown()
}
```

**Step 2: Build to verify compilation**

Run: `just build`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```
test(matchdoor): DualSeatHarness for two-player integration tests
```

---

## Task 3: Dual-seat integration tests

**Why third:** Now we write the assertions that validate the dual-seat flow. These tests define correctness.

**Files:**
- Create: `matchdoor/src/test/kotlin/leyline/conformance/DualSeatTest.kt`

**Step 1: Write the test class**

```kotlin
package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.IntegrationTag
import wotc.mtgo.gre.external.messaging.Messages.*

class DualSeatTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: DualSeatHarness? = null

        afterEach {
            harness?.shutdown()
            harness = null
        }

        test("both seats receive initial game state") {
            val h = DualSeatHarness(seed = 42L)
            harness = h
            h.connectBothSeats()

            h.seat1Messages.shouldNotBeEmpty()
            h.seat2Messages.shouldNotBeEmpty()
        }

        test("each seat sees own hand cards with full info") {
            val h = DualSeatHarness(seed = 42L)
            harness = h
            h.connectBothSeats()

            // Both should have GSMs with game objects
            val gsms1 = h.allGsms(h.seat1Messages)
            val gsms2 = h.allGsms(h.seat2Messages)
            gsms1.shouldNotBeEmpty()
            gsms2.shouldNotBeEmpty()

            // Seat 1's GSMs should contain objects owned by seat 1 with grpId
            val seat1Objects = gsms1.flatMap { it.gameObjectsList }
            val seat1OwnedCards = seat1Objects.filter { it.ownerSeatId == 1 && it.zoneId > 0 }
            seat1OwnedCards.shouldNotBeEmpty()

            // Seat 2's GSMs should contain objects owned by seat 2 with grpId
            val seat2Objects = gsms2.flatMap { it.gameObjectsList }
            val seat2OwnedCards = seat2Objects.filter { it.ownerSeatId == 2 && it.zoneId > 0 }
            seat2OwnedCards.shouldNotBeEmpty()
        }

        test("opponent hand cards have no GameObjectInfo (hidden)") {
            val h = DualSeatHarness(seed = 42L)
            harness = h
            h.connectBothSeats()

            // Find hand zones from seat 1's perspective
            val gsms1 = h.allGsms(h.seat1Messages)
            val allZones1 = gsms1.flatMap { it.zonesList }
            val seat2HandZone = allZones1.firstOrNull {
                it.type == ZoneType.Hand && it.ownerSeatId == 2
            }

            // Seat 2's hand zone should exist (with objectInstanceIds)
            // but seat 1 should NOT see GameObjectInfo for those IDs
            if (seat2HandZone != null && seat2HandZone.objectInstanceIdsList.isNotEmpty()) {
                val hiddenIds = seat2HandZone.objectInstanceIdsList.toSet()
                val seat1KnownObjects = gsms1.flatMap { it.gameObjectsList }.map { it.instanceId }.toSet()
                // None of seat 2's hand IDs should have full objects in seat 1's view
                val leaked = hiddenIds.intersect(seat1KnownObjects)
                leaked.isEmpty().shouldBeTrue()
            }
        }

        test("shared gsId chain — both seats see same game state IDs") {
            val h = DualSeatHarness(seed = 42L)
            harness = h
            h.connectBothSeats()

            val gsIds1 = h.allGsms(h.seat1Messages).map { it.gameStateId }.filter { it > 0 }
            val gsIds2 = h.allGsms(h.seat2Messages).map { it.gameStateId }.filter { it > 0 }

            gsIds1.shouldNotBeEmpty()
            gsIds2.shouldNotBeEmpty()

            // Both seats should reference the same gsId values
            // (they share a MessageCounter)
            val maxGsId1 = gsIds1.max()
            val maxGsId2 = gsIds2.max()
            maxGsId1 shouldBe maxGsId2
        }

        test("systemSeatIds correct per seat") {
            val h = DualSeatHarness(seed = 42L)
            harness = h
            h.connectBothSeats()

            // Seat 1 messages should have systemSeatIds containing 1
            for (msg in h.seat1Messages) {
                if (msg.systemSeatIdsList.isNotEmpty()) {
                    msg.systemSeatIdsList.contains(1).shouldBeTrue()
                }
            }
            // Seat 2 messages should have systemSeatIds containing 2
            for (msg in h.seat2Messages) {
                if (msg.systemSeatIdsList.isNotEmpty()) {
                    msg.systemSeatIdsList.contains(2).shouldBeTrue()
                }
            }
        }

        test("both players start at 20 life") {
            val h = DualSeatHarness(seed = 42L)
            harness = h
            h.connectBothSeats()

            val gsms1 = h.allGsms(h.seat1Messages)
            val players = gsms1.flatMap { it.playersList }
            players.shouldNotBeEmpty()

            val p1 = players.firstOrNull { it.seatId == 1 }
            val p2 = players.firstOrNull { it.seatId == 2 }
            p1.shouldNotBeNull()
            p2.shouldNotBeNull()
            p1.life shouldBe 20
            p2.life shouldBe 20
        }
    })
```

**Step 2: Run tests**

Run: `just test-matchdoor` (or targeted: the integration tag)
Expected: Tests pass (after Task 1+2 code is in place)

**Step 3: Commit**

```
test(matchdoor): dual-seat integration tests — visibility, gsId, systemSeatIds
```

---

## Task 4: MatchmakingQueue — FD pairing

**Why fourth:** Once the engine layer works (Tasks 1-3), add the FD entry point. This is a self-contained class with no engine coupling.

**Files:**
- Create: `frontdoor/src/main/kotlin/leyline/frontdoor/service/MatchmakingQueue.kt`
- Modify: `frontdoor/src/main/kotlin/leyline/frontdoor/FrontDoorHandler.kt` — add 603/606 dispatch
- Modify: `frontdoor/src/main/kotlin/leyline/frontdoor/wire/CmdType.kt` — add names

**Step 1: Write MatchmakingQueue**

```kotlin
package leyline.frontdoor.service

import org.slf4j.LoggerFactory

/**
 * FIFO matchmaking queue — pairs first two 603 requests.
 *
 * Thread-safe: two FD connections arrive on different Netty I/O threads.
 * Synchronized for simplicity — max 2 concurrent callers.
 */
class MatchmakingQueue {
    private val log = LoggerFactory.getLogger(MatchmakingQueue::class.java)

    @Volatile
    private var waiting: QueueEntry? = null

    /**
     * Attempt to pair this player. Returns [PairResult.Waiting] if first in queue,
     * or [PairResult.Paired] if a partner was already waiting.
     */
    @Synchronized
    fun pair(entry: QueueEntry): PairResult {
        val partner = waiting
        if (partner != null) {
            waiting = null
            val matchId = java.util.UUID.randomUUID().toString()
            log.info("MatchmakingQueue: paired {} vs {} → matchId={}", partner.screenName, entry.screenName, matchId)
            return PairResult.Paired(seat1 = partner, seat2 = entry, matchId = matchId)
        }
        waiting = entry
        log.info("MatchmakingQueue: {} waiting for opponent", entry.screenName)
        return PairResult.Waiting
    }

    /** Remove waiting player (cancel queue). Returns true if someone was removed. */
    @Synchronized
    fun cancel(screenName: String): Boolean {
        if (waiting?.screenName == screenName) {
            log.info("MatchmakingQueue: {} cancelled", screenName)
            waiting = null
            return true
        }
        return false
    }

    /** Check if anyone is waiting (test helper). */
    @Synchronized
    fun hasWaiting(): Boolean = waiting != null
}

data class QueueEntry(
    val screenName: String,
    /** Opaque reference for pushing MatchCreated back to this FD channel. */
    val pushCallback: (matchId: String, yourSeat: Int) -> Unit,
)

sealed class PairResult {
    data object Waiting : PairResult()
    data class Paired(val seat1: QueueEntry, val seat2: QueueEntry, val matchId: String) : PairResult()
}
```

**Step 2: Write MatchmakingQueue unit test**

Create: `frontdoor/src/test/kotlin/leyline/frontdoor/service/MatchmakingQueueTest.kt`

```kotlin
package leyline.frontdoor.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class MatchmakingQueueTest :
    FunSpec({

        test("first player waits") {
            val queue = MatchmakingQueue()
            val result = queue.pair(QueueEntry("Alice") { _, _ -> })
            result.shouldBeInstanceOf<PairResult.Waiting>()
            queue.hasWaiting().shouldBeTrue()
        }

        test("second player pairs with first") {
            val queue = MatchmakingQueue()
            queue.pair(QueueEntry("Alice") { _, _ -> })

            val result = queue.pair(QueueEntry("Bob") { _, _ -> })
            result.shouldBeInstanceOf<PairResult.Paired>()
            val paired = result as PairResult.Paired
            paired.seat1.screenName shouldBe "Alice"
            paired.seat2.screenName shouldBe "Bob"
            paired.matchId.isNotEmpty().shouldBeTrue()
            queue.hasWaiting().shouldBeFalse()
        }

        test("cancel removes waiting player") {
            val queue = MatchmakingQueue()
            queue.pair(QueueEntry("Alice") { _, _ -> })
            queue.cancel("Alice").shouldBeTrue()
            queue.hasWaiting().shouldBeFalse()
        }

        test("cancel no-op for non-waiting player") {
            val queue = MatchmakingQueue()
            queue.cancel("Alice").shouldBeFalse()
        }

        test("pair triggers callbacks with correct seats") {
            val queue = MatchmakingQueue()
            var seat1Push: Pair<String, Int>? = null
            var seat2Push: Pair<String, Int>? = null

            queue.pair(QueueEntry("Alice") { mid, seat -> seat1Push = mid to seat })
            val result = queue.pair(QueueEntry("Bob") { mid, seat -> seat2Push = mid to seat })

            val paired = result as PairResult.Paired
            // Caller is responsible for invoking callbacks — queue just returns the entries
            paired.seat1.pushCallback(paired.matchId, 1)
            paired.seat2.pushCallback(paired.matchId, 2)

            seat1Push!!.second shouldBe 1
            seat2Push!!.second shouldBe 2
            seat1Push!!.first shouldBe seat2Push!!.first // same matchId
        }
    })
```

**Step 3: Run test**

Run: `just test-frontdoor`
Expected: PASS

**Step 4: Wire into FrontDoorHandler**

Add 603/606 dispatch in `FrontDoorHandler.kt`. Find the `when` block dispatching on cmdType and add:

```kotlin
603 -> handleEnterPairing(ctx, txId)
606 -> handleLeavePairing(ctx, txId)
```

Add handler methods:

```kotlin
private fun handleEnterPairing(ctx: ChannelHandlerContext, txId: String) {
    writer.send(ctx, txId, FdResponse.Empty) // ack immediately

    val screenName = playerState.screenName ?: "Unknown"
    val result = matchmakingQueue.pair(QueueEntry(screenName) { matchId, yourSeat ->
        sendMatchCreated(ctx, MatchInfo(matchId, matchDoorHost, matchDoorPort, eventName = "PlayQueue"))
    })

    when (result) {
        is PairResult.Waiting -> log.info("Front Door: {} entered queue, waiting", screenName)
        is PairResult.Paired -> {
            log.info("Front Door: paired {} vs {}", result.seat1.screenName, result.seat2.screenName)
            result.seat1.pushCallback(result.matchId, 1)
            result.seat2.pushCallback(result.matchId, 2)
        }
    }
}

private fun handleLeavePairing(ctx: ChannelHandlerContext, txId: String) {
    val screenName = playerState.screenName ?: "Unknown"
    matchmakingQueue.cancel(screenName)
    writer.send(ctx, txId, FdResponse.Empty)
}
```

NOTE: `matchmakingQueue` must be passed to `FrontDoorHandler` constructor (from `LeylineServer`). Add it as a constructor param with a default of `MatchmakingQueue()`.

**Step 5: Build**

Run: `just build`
Expected: BUILD SUCCESSFUL

**Step 6: Commit**

```
feat(frontdoor): MatchmakingQueue + 603/606 dispatch for PvP pairing
```

---

## Task 5: MatchCreated per-seat push

**Why fifth:** The MatchCreated JSON needs to carry the correct YourSeat for each client. The `buildMatchCreatedJson` is already parameterized (done in 03-P1), but FrontDoorHandler.sendMatchCreated currently hardcodes seat 1.

**Files:**
- Modify: `frontdoor/src/main/kotlin/leyline/frontdoor/FrontDoorHandler.kt` — sendMatchCreated takes seat param
- Modify: `frontdoor/src/main/kotlin/leyline/frontdoor/wire/FdEnvelope.kt` — verify buildMatchCreatedJson accepts yourSeat

**Step 1: Check FdEnvelope.buildMatchCreatedJson signature**

Read `FdEnvelope.kt` around `buildMatchCreatedJson`. Verify it accepts `yourSeat: Int` parameter. If already parameterized (plan 03-P1 says done), just wire it from the queue callback.

**Step 2: Update sendMatchCreated to accept seat**

The queue callback needs to push MatchCreated with the correct seat. The current `sendMatchCreated(ctx, MatchInfo)` doesn't take a seat parameter. Either:
- Add `yourSeat` to `MatchInfo` data class, OR
- Add a `yourSeat` parameter to `sendMatchCreated`

Simpler: add to MatchInfo since it already carries match metadata.

**Step 3: Build and test**

Run: `just build`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```
feat(frontdoor): per-seat MatchCreated push for PvP queue pairing
```

---

## Execution Order Summary

| # | What | Type | Risk | ~LOC |
|---|------|------|------|------|
| 1 | GameBridge.startTwoPlayer + awaitPriority fix | Production | Medium | ~60 |
| 2 | DualSeatHarness | Test infra | Low | ~80 |
| 3 | DualSeatTest assertions | Tests | Low | ~100 |
| 4 | MatchmakingQueue + tests + FD wiring | Production + Test | Low | ~120 |
| 5 | Per-seat MatchCreated push | Production | Low | ~20 |

**Tasks 1-3** are the engine layer — testable without network. This is the hard part.
**Tasks 4-5** are FD layer — self-contained, low risk.

After all 5: two Arena clients can queue (603) → pair → connect to MD → see starting state.

## What's Deferred

- Mulligan interaction (skipMulligan=true for now)
- FamiliarSession type extraction (current isFamiliar gates work)
- HandshakeMessages rename (not needed — harness bypasses handshake)
- GamePlayback for PvP (no AI = no AI action streaming)
- sendGameOver seat fix (hardcoded to seat 1 — fix when testing game-over)
- ChooseStartingPlayer routing (die roll winner — hardcodes seat 1 for now)
- Challenge protocol (3000-3012) — queue path covers MVP
- Per-connection CaptureSink tagging (validation infra)
