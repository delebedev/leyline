package leyline.conformance

import forge.game.GameStage
import forge.game.GameType
import forge.game.phase.PhaseType
import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.ForgeCardId
import leyline.bridge.GameBootstrap
import leyline.bridge.PlayerAction
import leyline.bridge.SeatId
import leyline.game.GameBridge
import leyline.game.PuzzleSource
import leyline.game.StateMapper
import leyline.game.mapper.ActionMapper
import leyline.infra.ListMessageSink
import leyline.match.MatchRegistry
import leyline.match.MatchSession
import wotc.mtgo.gre.external.messaging.Messages.GameStage as ProtoGameStage

/**
 * Integration tests for puzzle mode through [GameBridge.startPuzzle].
 *
 * Verifies:
 * - Puzzle game creation (GameType.Puzzle, age=Play)
 * - Card registration in CardRepository + InstanceIdRegistry
 * - Engine reaches priority without mulligan
 * - Life totals and zone contents match puzzle spec
 * - Actions available after puzzle start
 */
class PuzzleBridgeTest :
    FunSpec({

        tags(IntegrationTag)

        var bridge: GameBridge? = null

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
        }

        afterEach {
            bridge?.shutdown()
            bridge = null
        }

        fun startPuzzle(resourcePath: String): GameBridge {
            val puzzle = PuzzleSource.loadFromResource(resourcePath)
            val b = GameBridge(bridgeTimeoutMs = 5_000, cards = TestCardRegistry.repo)
            bridge = b
            b.priorityWaitMs = 5_000
            b.startPuzzle(puzzle)
            return b
        }

        test("start puzzle sets GameType Puzzle") {
            val b = startPuzzle("puzzles/lands-only.pzl")
            val game = b.getGame()!!
            game.rules.gameType shouldBe GameType.Puzzle
            b.isPuzzle.shouldBeTrue()
        }

        test("start puzzle sets GameStage Play") {
            val b = startPuzzle("puzzles/lands-only.pzl")
            val game = b.getGame()!!
            game.age shouldBe GameStage.Play
        }

        test("start puzzle reaches Main1") {
            val b = startPuzzle("puzzles/lands-only.pzl")
            val game = b.getGame()!!
            game.phaseHandler.phase shouldBe PhaseType.MAIN1
        }

        test("start puzzle has pending actions") {
            val b = startPuzzle("puzzles/lands-only.pzl")
            val pending = b.actionBridge(1).getPending()
            pending.shouldNotBeNull()
        }

        test("puzzle cards registered in repository") {
            val b = startPuzzle("puzzles/simple-attack.pzl")
            // Grizzly Bears should be registered
            val grpId = b.cards.findGrpIdByName("Grizzly Bears")
            grpId.shouldNotBeNull()
            grpId shouldBeGreaterThan 0
        }

        test("puzzle cards registered in InstanceIdRegistry") {
            val b = startPuzzle("puzzles/simple-attack.pzl")
            val game = b.getGame()!!
            val human = b.getPlayer(SeatId(1))!!
            val bears = human.getZone(ZoneType.Battlefield).cards.first { it.name == "Grizzly Bears" }
            val instanceId = b.getOrAllocInstanceId(ForgeCardId(bears.id))
            (instanceId.value > 0).shouldBeTrue()
            // Verify reverse lookup
            val forgeId = b.getForgeCardId(instanceId)
            forgeId?.value shouldBe bears.id
        }

        test("puzzle life totals match spec") {
            val b = startPuzzle("puzzles/custom-life.pzl")
            val human = b.getPlayer(SeatId(1))!!
            val ai = b.getPlayer(SeatId(2))!!
            human.life shouldBe 7
            ai.life shouldBe 1
        }

        test("puzzle battlefield matches spec") {
            val b = startPuzzle("puzzles/simple-attack.pzl")
            val human = b.getPlayer(SeatId(1))!!
            val battlefield = human.getZone(ZoneType.Battlefield).cards.map { it.name }
            battlefield shouldContain "Grizzly Bears"
            battlefield shouldContain "Forest"
        }

        test("puzzle hand matches spec") {
            val b = startPuzzle("puzzles/simple-attack.pzl")
            val human = b.getPlayer(SeatId(1))!!
            val hand = human.getZone(ZoneType.Hand).cards.map { it.name }
            hand shouldContain "Giant Growth"
        }

        test("puzzle buildFromGame has stage Play") {
            val b = startPuzzle("puzzles/lands-only.pzl")
            val game = b.getGame()!!
            val gsm = StateMapper.buildFromGame(game, 1, "test-puzzle", b, viewingSeatId = 1)
            gsm.gameInfo.stage shouldBe ProtoGameStage.Play_a920
        }

        test("puzzle buildFromGame has correct life totals") {
            val b = startPuzzle("puzzles/custom-life.pzl")
            val game = b.getGame()!!
            val gsm = StateMapper.buildFromGame(game, 1, "test-puzzle", b, viewingSeatId = 1)
            val p1 = gsm.playersList.first { it.systemSeatNumber == 1 }
            val p2 = gsm.playersList.first { it.systemSeatNumber == 2 }
            p1.lifeTotal shouldBe 7
            p2.lifeTotal shouldBe 1
        }

        test("puzzle buildFromGame has battlefield objects") {
            val b = startPuzzle("puzzles/simple-attack.pzl")
            val game = b.getGame()!!
            val gsm = StateMapper.buildFromGame(game, 1, "test-puzzle", b, viewingSeatId = 1)
            (gsm.gameObjectsCount > 0).shouldBeTrue()
        }

        test("puzzle can perform action") {
            val b = startPuzzle("puzzles/lands-only.pzl")
            // The puzzle should have actions available (at least Pass)
            val pending = b.actionBridge(1).getPending()
            pending.shouldNotBeNull()
            // Pass priority
            b.actionBridge(1).submitAction(pending.actionId, PlayerAction.PassPriority)
            b.awaitPriority()
            // Should reach another priority stop or advance phases
            // (just verifying the engine doesn't crash)
        }

        // --- WEB_ puzzle smoke tests ---

        test("web test 00 one bolt loads") {
            val b = startPuzzle("puzzles/bolt-face.pzl")
            val game = b.getGame()!!
            b.isPuzzle.shouldBeTrue()
            game.phaseHandler.phase shouldBe PhaseType.MAIN1
            // Human: 1 Mountain on battlefield, Lightning Bolt in hand
            val human = b.getPlayer(SeatId(1))!!
            val hand = human.getZone(ZoneType.Hand).cards.map { it.name }
            val battlefield = human.getZone(ZoneType.Battlefield).cards.map { it.name }
            hand shouldContain "Lightning Bolt"
            battlefield shouldContain "Mountain"
            // AI at 3 life
            val ai = b.getPlayer(SeatId(2))!!
            ai.life shouldBe 3
            // Should have actions (Cast Lightning Bolt + Pass at minimum)
            val pending = b.actionBridge(1).getPending()
            pending.shouldNotBeNull()
        }

        test("web test 00 produces valid GSM") {
            val b = startPuzzle("puzzles/bolt-face.pzl")
            val game = b.getGame()!!
            val gsm = StateMapper.buildFromGame(game, 1, "test-puzzle", b, viewingSeatId = 1)
            gsm.gameInfo.stage shouldBe ProtoGameStage.Play_a920
            // Should have game objects (Mountain on bf, Lightning Bolt in hand)
            gsm.gameObjectsCount shouldBeGreaterThanOrEqual 2
            // Life totals
            val p1 = gsm.playersList.first { it.systemSeatNumber == 1 }
            val p2 = gsm.playersList.first { it.systemSeatNumber == 2 }
            p1.lifeTotal shouldBe 20
            p2.lifeTotal shouldBe 3
        }

        test("web test 00 actions include Cast") {
            val b = startPuzzle("puzzles/bolt-face.pzl")
            val game = b.getGame()!!
            val actions = ActionMapper.buildActions(game, 1, b)
            val actionTypes = actions.actionsList.map { it.actionType.name }
            actionTypes.any { it == "Cast" }.shouldBeTrue()
        }

        // --- Puzzle race bug (seat 2 auto-passes seat 1's pending action) ---

        test("seat 2 onPuzzleStart does not advance past Main1") {
            val puzzle = PuzzleSource.loadFromResource("puzzles/lands-only.pzl")
            val registry = MatchRegistry()
            val matchId = "test-puzzle-race"

            // Create shared bridge — startPuzzle blocks until Main1 priority
            val b = GameBridge(bridgeTimeoutMs = 5_000, cards = TestCardRegistry.repo)
            bridge = b
            b.priorityWaitMs = 5_000
            b.startPuzzle(puzzle)
            b.getGame()!!.phaseHandler.phase shouldBe PhaseType.MAIN1

            // Seat 1 (human): connect and run onPuzzleStart — normal path
            val sink1 = ListMessageSink()
            val session1 = MatchSession(
                seatId = 1,
                matchId = matchId,
                sink = sink1,
                registry = registry,
                paceDelayMs = 0,
            )
            session1.connectBridge(b)
            registry.registerSession(matchId, 1, session1)
            session1.onPuzzleStart()
            b.getGame()!!.phaseHandler.phase shouldBe PhaseType.MAIN1

            val turnBefore = b.getGame()!!.phaseHandler.turn

            // Seat 2 (Familiar): connect and run onPuzzleStart — this is the race
            val sink2 = ListMessageSink()
            val session2 = MatchSession(
                seatId = 2,
                matchId = matchId,
                sink = sink2,
                registry = registry,
                paceDelayMs = 0,
            )
            session2.connectBridge(b)
            registry.registerSession(matchId, 2, session2)
            session2.onPuzzleStart()

            // Turn must not have advanced — seat 2 must not consume seat 1's actions
            b.getGame()!!.phaseHandler.turn shouldBe turnBefore
            // Phase must still be Main1
            b.getGame()!!.phaseHandler.phase shouldBe PhaseType.MAIN1
            // Seat 1's pending action must still be available
            b.actionBridge(1).getPending().shouldNotBeNull()
        }
    })
