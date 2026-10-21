package leyline.match

import com.google.common.eventbus.Subscribe
import forge.game.event.GameEventShuffle
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import leyline.IntegrationTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.coord.GameLoopController
import leyline.bridge.types.MulliganPhase
import leyline.bridge.types.SeatId
import leyline.config.EngineSettings
import leyline.domain.deck.DeckSource
import leyline.game.state.GameBridge
import leyline.infra.ListMessageSink
import leyline.testkit.TestCardRegistry
import wotc.mtgo.gre.external.messaging.Messages.*
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

// Failed construction must stop a launched game-loop thread before registry ownership exists.
@Suppress("TierPlacementCheck")
class MatchStartupTest :
    FunSpec({
        tags(IntegrationTag)

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureDeckRegistered("60 Forest")
        }

        fun newBridge() = GameBridge(cardRepository = TestCardRegistry.repo, engineSettings = EngineSettings(mulliganWaitMs = 2_000L))

        listOf("deadline", "interruption", "engine failure", "publication failure").forEach { disposition ->
            test("accepted redraw $disposition fails and closes the registered match once") {
                val registry = MatchRegistry()
                var now = 0L
                var controlled = false
                lateinit var loop: GameLoopController
                lateinit var engine: Thread
                lateinit var pause: RedrawPause
                val interruption = InterruptedException("Authored redraw handoff interruption")
                val bridge =
                    GameBridge(
                        cardRepository = TestCardRegistry.repo,
                        engineSettings = EngineSettings(mulliganWaitMs = 2_000L),
                        mulliganNowMillis = { now },
                        awaitMulliganPoll = {
                            if (controlled) {
                                pause.entered.await(2, TimeUnit.SECONDS) shouldBe true
                                when (disposition) {
                                    "deadline" -> now = 2_000L
                                    "interruption" -> throw interruption
                                    "publication failure" -> pause.release.countDown()
                                    else -> {
                                        engine.interrupt()
                                        engine.join(2_000)
                                        engine.isAlive shouldBe false
                                        loop.failure.shouldNotBeNull()
                                    }
                                }
                            } else {
                                Thread.yield()
                            }
                        },
                    )
                val match =
                    registry.getOrCreateMatch("redraw-$disposition") {
                        Match("redraw-$disposition", bridge).also { it.start(deckList = "60 Forest") }
                    }
                var closes = 0
                match.onStateChanged = { if (it == MatchState.FINISHED) closes++ }
                loop = bridge.gameLoopControllerForTest().shouldNotBeNull()
                engine = loop.getEngineThread().shouldNotBeNull()
                val sink = ListMessageSink()
                var failDelivery = false
                val deliveryFailure = IllegalStateException("Authored redraw delivery failure")
                val output =
                    object : leyline.infra.MessageSink by sink {
                        override fun send(messages: List<GREToClientMessage>) {
                            if (failDelivery) throw deliveryFailure
                            sink.send(messages)
                        }
                    }
                val session = MatchSession(ConnectionState(SeatId(1), match.matchId, output, registry), bridge, paceDelayMs = 0)
                registry.registerSession(match.matchId, SeatId(1), session)
                bridge.cutCoordinator.registerViewer(SeatId(1))
                val handler = MulliganHandler(bridge.engineSettings, registry, { session }, { match.matchId }, { SeatId(1) })
                handler.sendMulliganReq()
                val request = sink.messages.last { it.hasMulliganReq() }
                val priorMessageCount = sink.messages.size
                pause = RedrawPause(bridge.getPlayer(SeatId(1)).shouldNotBeNull().id)
                bridge.getGame().shouldNotBeNull().subscribeToEvents(pause)
                controlled = true
                failDelivery = disposition == "publication failure"
                try {
                    val failure =
                        shouldThrow<Exception> {
                            handler.onMulliganResp(
                                ClientToGREMessage
                                    .newBuilder()
                                    .setType(ClientMessageType.MulliganResp_097b)
                                    .setSystemSeatId(1)
                                    .setGameStateId(request.gameStateId)
                                    .setRespId(request.msgId)
                                    .setMulliganResp(MulliganResp.newBuilder().setDecision(MulliganOption.Mulligan))
                                    .build(),
                            )
                        }
                    when (disposition) {
                        "deadline" -> failure.javaClass shouldBe TimeoutException::class.java
                        "interruption" -> {
                            failure shouldBeSameInstanceAs interruption
                            Thread.currentThread().isInterrupted shouldBe true
                        }
                        "publication failure" -> failure.cause shouldBeSameInstanceAs deliveryFailure
                        else -> failure.cause shouldBeSameInstanceAs loop.failure
                    }
                    Thread.interrupted()
                    match.close()
                    assertSoftly {
                        bridge.mulliganBridge(SeatId(1)).redrawCount shouldBe 1
                        pause.entered.count shouldBe 0L
                        sink.messages.size shouldBe priorMessageCount
                        if (disposition == "publication failure") {
                            bridge.cutCoordinator.failure() shouldBeSameInstanceAs failure
                        } else {
                            bridge.cutCoordinator
                                .failure()
                                .shouldNotBeNull()
                                .cause shouldBeSameInstanceAs failure
                        }
                        registry.getMatch(match.matchId).shouldBeNull()
                        match.state shouldBe MatchState.FINISHED
                        closes shouldBe 1
                        engine.isAlive shouldBe false
                        bridge.getGame().shouldBeNull()
                        bridge.gameLoopControllerForTest().shouldBeNull()
                        bridge.mulliganBridge(SeatId(1)).pendingPrompt().shouldBeNull()
                    }
                    val fresh =
                        registry.getOrCreateMatch(
                            match.matchId,
                        ) { Match(match.matchId, newBridge()).also { it.start(deckList = "60 Forest") } }
                    fresh.state shouldBe MatchState.RUNNING
                    registry.teardownMatch(fresh.matchId, MatchTeardownReason.Disconnect)
                } finally {
                    Thread.interrupted()
                    pause.release.countDown()
                    registry.teardownMatch(match.matchId, MatchTeardownReason.Disconnect, fallbackBridge = bridge)
                }
            }
        }

        test("the session alone continues after keep even when another waiter consumes the signal") {
            val registry = MatchRegistry()
            val bridge =
                GameBridge(
                    cardRepository = TestCardRegistry.repo,
                    runtimeHorizonMode = leyline.bridge.handoff.RuntimeHorizonMode.Observed,
                    engineSettings = EngineSettings(mulliganWaitMs = 2_000L, dieRollWinner = 1, seed = 42L),
                ).apply { priorityWaitMs = 2_000L }
            val match =
                registry.getOrCreateMatch(
                    "post-keep-waiter",
                ) { Match("post-keep-waiter", bridge).also { it.start(deckList = "60 Forest") } }
            val sink = ListMessageSink()
            val session = MatchSession(ConnectionState(SeatId(1), match.matchId, sink, registry), bridge, paceDelayMs = 0)
            registry.registerSession(match.matchId, SeatId(1), session)
            bridge.cutCoordinator.registerViewer(SeatId(1))
            val continuationEntered = CountDownLatch(1)
            val releaseContinuation = CountDownLatch(1)
            var continuations = 0
            val ops =
                object : GameOps by session {
                    override fun onMulliganKeep() {
                        continuations++
                        continuationEntered.countDown()
                        check(releaseContinuation.await(5, TimeUnit.SECONDS))
                        session.onMulliganKeep()
                    }
                }
            val handler = MulliganHandler(bridge.engineSettings, registry, { ops }, { match.matchId }, { SeatId(1) })
            handler.sendMulliganReq()
            val request = sink.messages.last { it.hasMulliganReq() }
            val executor = Executors.newFixedThreadPool(2)
            try {
                val response =
                    executor.submit {
                        handler.onMulliganResp(
                            ClientToGREMessage
                                .newBuilder()
                                .setType(ClientMessageType.MulliganResp_097b)
                                .setSystemSeatId(1)
                                .setGameStateId(request.gameStateId)
                                .setRespId(request.msgId)
                                .setMulliganResp(MulliganResp.newBuilder().setDecision(MulliganOption.AcceptHand))
                                .build(),
                        )
                    }
                continuationEntered.await(2, TimeUnit.SECONDS) shouldBe true
                val competing =
                    executor.submit<Boolean> {
                        bridge.prioritySignal.awaitSignal(2_000)
                    }
                competing.get(2, TimeUnit.SECONDS) shouldBe true
                sink.messages.none { it.hasActionsAvailableReq() } shouldBe true
                releaseContinuation.countDown()
                response.get(10, TimeUnit.SECONDS)
                assertSoftly {
                    continuations shouldBe 1
                    sink.messages.count { it.hasActionsAvailableReq() } shouldBe 1
                    bridge.mulliganBridge(SeatId(1)).pendingPrompt().shouldBeNull()
                    match.state shouldBe MatchState.RUNNING
                }
            } finally {
                releaseContinuation.countDown()
                registry.teardownMatch(match.matchId, MatchTeardownReason.Disconnect)
                executor.shutdownNow()
                executor.awaitTermination(2, TimeUnit.SECONDS) shouldBe true
            }
        }

        listOf(false, true).forEach { tuck ->
            test("accepted ${if (tuck) "tuck" else "keep"} continuation failure preserves its cause through cleanup") {
                val registry = MatchRegistry()
                val bridge = newBridge()
                val match =
                    registry.getOrCreateMatch("continuation-$tuck") {
                        Match("continuation-$tuck", bridge).also { it.start(deckList = "60 Forest") }
                    }
                val engine =
                    bridge
                        .gameLoopControllerForTest()
                        .shouldNotBeNull()
                        .getEngineThread()
                        .shouldNotBeNull()
                val sink = ListMessageSink()
                val session = MatchSession(ConnectionState(SeatId(1), match.matchId, sink, registry), bridge, paceDelayMs = 0)
                registry.registerSession(match.matchId, SeatId(1), session)
                bridge.cutCoordinator.registerViewer(SeatId(1))
                val cause = IllegalStateException("Authored continuation cause")
                val failure =
                    if (tuck) {
                        CancellationException("Authored tuck cancellation").apply {
                            initCause(cause)
                        }
                    } else {
                        InterruptedException("Authored keep interruption").apply { initCause(cause) }
                    }
                val cleanupFailure = IllegalStateException("Authored close notification failure")
                var closes = 0
                match.onStateChanged = {
                    if (it == MatchState.FINISHED) {
                        closes++
                        throw cleanupFailure
                    }
                }
                val ops =
                    object : GameOps by session {
                        override fun onMulliganKeep(): Unit = throw failure
                    }
                val handler = MulliganHandler(bridge.engineSettings, registry, { ops }, { match.matchId }, { SeatId(1) })

                fun decision(option: MulliganOption) =
                    ClientToGREMessage
                        .newBuilder()
                        .setType(ClientMessageType.MulliganResp_097b)
                        .setMulliganResp(MulliganResp.newBuilder().setDecision(option))
                        .build()
                try {
                    if (tuck) {
                        handler.onMulliganResp(decision(MulliganOption.Mulligan))
                        handler.onMulliganResp(decision(MulliganOption.AcceptHand))
                    }
                    val thrown =
                        shouldThrow<Exception> {
                            if (tuck) {
                                val grouping = sink.messages.single { it.hasGroupReq() }.groupReq
                                handler.onGroupResp(
                                    ClientToGREMessage
                                        .newBuilder()
                                        .setType(ClientMessageType.GroupResp_097b)
                                        .setGroupResp(
                                            GroupResp
                                                .newBuilder()
                                                .addGroups(Group.newBuilder().addAllIds(grouping.instanceIdsList.dropLast(1)))
                                                .addGroups(Group.newBuilder().addIds(grouping.instanceIdsList.last())),
                                        ).build(),
                                )
                            } else {
                                handler.onMulliganResp(decision(MulliganOption.AcceptHand))
                            }
                        }
                    assertSoftly {
                        thrown shouldBeSameInstanceAs failure
                        thrown.cause shouldBeSameInstanceAs cause
                        thrown.suppressed.toList().shouldContainExactly(cleanupFailure)
                        Thread.currentThread().isInterrupted shouldBe !tuck
                        closes shouldBe 1
                        registry.getMatch(match.matchId).shouldBeNull()
                        match.state shouldBe MatchState.FINISHED
                        engine.isAlive shouldBe false
                    }
                } finally {
                    Thread.interrupted()
                    match.close()
                }
            }
        }

        listOf(false, true).forEach { withCompanion ->
            test("Commander initial publication reaches mulligan and releases its loop with companion=$withCompanion") {
                val deck = "[Commander]\n1 Ayli, Eternal Pilgrim\n[Deck]\n99 Plains"
                val playerDeck = if (withCompanion) "$deck\n[Sideboard]\n1 Lurrus of the Dream-Den" else deck
                TestCardRegistry.ensureDeckRegistered(playerDeck)
                val bridge = newBridge()
                val match = Match("commander-start-$withCompanion", bridge)
                var thread: Thread? = null
                try {
                    match.start(seed = 42L, deckList1 = playerDeck, deckList2 = deck, variant = "commander")
                    thread =
                        bridge
                            .gameLoopControllerForTest()
                            .shouldNotBeNull()
                            .getEngineThread()
                            .shouldNotBeNull()
                    val coordinator = bridge.cutCoordinator
                    coordinator.registerViewer(SeatId(1))
                    val gameStateId = coordinator.lifecycle.publishInitial(SeatId(1), includeStartingPlayerPrompt = true)
                    val initial = coordinator.drain(SeatId(1)).single()
                    val snapshot = bridge.viewerProjectionCursor().previousSnapshot.shouldNotBeNull()
                    assertSoftly {
                        match.state shouldBe MatchState.RUNNING
                        initial.last().gameStateId shouldBe gameStateId
                        bridge
                            .mulliganBridge(SeatId(1))
                            .pendingPrompt()
                            .shouldNotBeNull()
                            .phase shouldBe MulliganPhase.WaitingKeep
                        (snapshot.seats.single { it.seatId == SeatId(1) }.companion != null) shouldBe withCompanion
                    }
                } finally {
                    match.close()
                }
                assertSoftly {
                    thread.shouldNotBeNull().isAlive shouldBe false
                    bridge.gameLoopControllerForTest().shouldBeNull()
                    bridge.getGame().shouldBeNull()
                    bridge.mulliganBridge(SeatId(1)).pendingPrompt().shouldBeNull()
                    match.state shouldBe MatchState.FINISHED
                }
            }
        }

        test("failed construction closes its started loop once and permits a fresh match") {
            val registry = MatchRegistry()
            val bridge = newBridge()
            val match = Match("failed-start", bridge)
            val cause = IllegalArgumentException("Authored startup dependency failure")
            val failure = IllegalStateException("Authored startup listener failure", cause)
            val states = mutableListOf<MatchState>()
            lateinit var loop: GameLoopController
            lateinit var thread: Thread
            match.onStateChanged = { state ->
                states.add(state)
                if (state == MatchState.RUNNING) {
                    loop = bridge.gameLoopControllerForTest().shouldNotBeNull()
                    thread = loop.getEngineThread().shouldNotBeNull()
                    assertSoftly {
                        thread.isAlive shouldBe true
                        bridge
                            .mulliganBridge(SeatId(1))
                            .pendingPrompt()
                            .shouldNotBeNull()
                            .phase shouldBe MulliganPhase.WaitingKeep
                        registry.getMatch(match.matchId).shouldBeNull()
                    }
                    throw failure
                }
            }

            try {
                val thrown =
                    shouldThrow<IllegalStateException> {
                        registry.getOrCreateMatch(match.matchId) {
                            match.also { it.start(deckList = "60 Forest") }
                        }
                    }
                match.close()
                assertSoftly {
                    thrown shouldBeSameInstanceAs failure
                    thrown.cause shouldBeSameInstanceAs cause
                    registry.getMatch(match.matchId).shouldBeNull()
                    match.state shouldBe MatchState.FINISHED
                    states.shouldContainExactly(MatchState.RUNNING, MatchState.FINISHED)
                    loop.isRunning shouldBe false
                    thread.isAlive shouldBe false
                    bridge.gameLoopControllerForTest().shouldBeNull()
                    bridge.getGame().shouldBeNull()
                    bridge.mulliganBridge(SeatId(1)).pendingPrompt().shouldBeNull()
                }

                val fresh =
                    registry.getOrCreateMatch(match.matchId) {
                        Match(match.matchId, newBridge()).also {
                            it.start(deck1 = DeckSource.ForgeText("60 Forest"), deck2 = DeckSource.ForgeText("60 Forest"))
                        }
                    }
                try {
                    fresh.state shouldBe MatchState.RUNNING
                    fresh.bridge
                        .mulliganBridge(SeatId(1))
                        .pendingPrompt()
                        .shouldNotBeNull()
                        .phase shouldBe MulliganPhase.WaitingKeep
                } finally {
                    registry.teardownMatch(fresh.matchId, MatchTeardownReason.Disconnect)
                }
            } finally {
                match.close()
            }
        }

        test("interrupted construction releases the loop and restores the caller interrupt") {
            val bridge = newBridge()
            val match = Match("interrupted-start", bridge)
            val failure = InterruptedException("Authored startup interruption")
            lateinit var thread: Thread
            match.onStateChanged = { state ->
                if (state == MatchState.RUNNING) {
                    thread =
                        bridge
                            .gameLoopControllerForTest()
                            .shouldNotBeNull()
                            .getEngineThread()
                            .shouldNotBeNull()
                    Thread.currentThread().interrupt()
                    throw failure
                }
            }
            try {
                val thrown =
                    shouldThrow<InterruptedException> {
                        match.start(deck1 = DeckSource.ForgeText("60 Forest"), deck2 = DeckSource.ForgeText("60 Forest"))
                    }
                assertSoftly {
                    thrown shouldBeSameInstanceAs failure
                    Thread.currentThread().isInterrupted shouldBe true
                    thread.isAlive shouldBe false
                    match.state shouldBe MatchState.FINISHED
                }
            } finally {
                Thread.interrupted()
                match.close()
            }
        }

        test("cancelled construction keeps its cause when the close notification also fails") {
            val bridge = newBridge()
            val match = Match("cancelled-start", bridge)
            val failure = CancellationException("Authored startup cancellation")
            val cleanupFailure = IllegalStateException("Authored close listener failure")
            lateinit var thread: Thread
            var closes = 0
            match.onStateChanged = { state ->
                if (state == MatchState.RUNNING) {
                    thread =
                        bridge
                            .gameLoopControllerForTest()
                            .shouldNotBeNull()
                            .getEngineThread()
                            .shouldNotBeNull()
                    throw failure
                }
                if (state == MatchState.FINISHED) {
                    closes++
                    throw cleanupFailure
                }
            }
            try {
                val thrown = shouldThrow<CancellationException> { match.start(deckList = "60 Forest") }
                match.close()
                assertSoftly {
                    thrown shouldBeSameInstanceAs failure
                    thrown.suppressed.toList().shouldContainExactly(cleanupFailure)
                    closes shouldBe 1
                    thread.isAlive shouldBe false
                    match.state shouldBe MatchState.FINISHED
                }
            } finally {
                match.close()
            }
        }
    })

/** A test-only engine barrier after redraw acceptance, before the next hand is available. */
private class RedrawPause(
    private val playerId: Int,
) {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)

    @Subscribe
    fun onShuffle(event: GameEventShuffle) {
        if (event.player.id != playerId) return
        entered.countDown()
        try {
            check(release.await(5, TimeUnit.SECONDS)) { "Redraw test barrier was not released" }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
