package leyline.match

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
import leyline.testkit.TestCardRegistry
import java.util.concurrent.CancellationException

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
