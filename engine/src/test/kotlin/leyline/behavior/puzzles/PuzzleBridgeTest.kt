package leyline.behavior.puzzles

import forge.game.GameStage
import forge.game.GameType
import forge.game.phase.PhaseType
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.generator.PuzzleSource
import leyline.game.mapping.ActionMapper
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.GameBridge
import leyline.infra.ListMessageSink
import leyline.match.ConnectionState
import leyline.match.MatchRegistry
import leyline.match.MatchSession
import leyline.testkit.TestCardRegistry
import leyline.testkit.gsm
import leyline.testkit.StateMapperShell as StateMapper
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
            val b = GameBridge(bridgeTimeoutMs = 5_000, cardRepository = TestCardRegistry.repo)
            bridge = b
            b.priorityWaitMs = 5_000
            b.startPuzzle(puzzle)
            TestCardRegistry.registerPuzzleCards(b.getGame()!!)
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
            val pending = checkNotNull(b.actionBridge(SeatId(1)).getPending())
            assertSoftly {
                pending.state.phase shouldBe "MAIN1"
                pending.state.turn shouldBe 1
            }
        }

        test("puzzle cards registered in repository") {
            val b = startPuzzle("puzzles/simple-attack.pzl")
            // Grizzly Bears should be registered
            val grpId = b.cardRepository.findGrpIdByName("Grizzly Bears")
            grpId.shouldNotBeNull()
            grpId shouldBeGreaterThan 0
        }

        test("puzzle cards registered in InstanceIdRegistry") {
            val b = startPuzzle("puzzles/simple-attack.pzl")
            val game = b.getGame()!!
            val human = b.getPlayer(SeatId(1))!!
            val bears = human.getZone(ZoneType.Battlefield).cards.first { it.name == "Grizzly Bears" }
            val instanceId = b.getOrAllocInstanceId(ForgeCardId(bears.id))
            instanceId.value shouldBeGreaterThan 0
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
            battlefield.groupingBy { it }.eachCount() shouldBe mapOf("Forest" to 2, "Grizzly Bears" to 1)
        }

        test("puzzle hand matches spec") {
            val b = startPuzzle("puzzles/simple-attack.pzl")
            val human = b.getPlayer(SeatId(1))!!
            val hand = human.getZone(ZoneType.Hand).cards.map { it.name }
            hand shouldBe listOf("Giant Growth")
        }

        test("puzzle buildFromSnapshot has stage Play") {
            val b = startPuzzle("puzzles/lands-only.pzl")
            val game = b.getGame()!!
            val snap = GsmSnapshot.capture(game, b, "test-puzzle", 1)
            val gsm =
                StateMapper
                    .buildFromSnapshot(
                        snap,
                        1,
                        "test-puzzle",
                        b,
                        viewingSeatId = 1,
                        effectFacts = b.materializeEffectProjectionFacts(),
                        abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                    ).gsm
            gsm.gameInfo.stage shouldBe ProtoGameStage.Play_a920
        }

        test("puzzle buildFromSnapshot has correct life totals") {
            val b = startPuzzle("puzzles/custom-life.pzl")
            val game = b.getGame()!!
            val snap = GsmSnapshot.capture(game, b, "test-puzzle", 1)
            val gsm =
                StateMapper
                    .buildFromSnapshot(
                        snap,
                        1,
                        "test-puzzle",
                        b,
                        viewingSeatId = 1,
                        effectFacts = b.materializeEffectProjectionFacts(),
                        abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                    ).gsm
            val p1 = gsm.playersList.first { it.systemSeatNumber == 1 }
            val p2 = gsm.playersList.first { it.systemSeatNumber == 2 }
            p1.lifeTotal shouldBe 7
            p2.lifeTotal shouldBe 1
        }

        test("puzzle buildFromSnapshot has battlefield objects") {
            val b = startPuzzle("puzzles/simple-attack.pzl")
            val game = b.getGame()!!
            val snap = GsmSnapshot.capture(game, b, "test-puzzle", 1)
            val gsm =
                StateMapper
                    .buildFromSnapshot(
                        snap,
                        1,
                        "test-puzzle",
                        b,
                        viewingSeatId = 1,
                        effectFacts = b.materializeEffectProjectionFacts(),
                        abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                    ).gsm
            gsm.gameObjectsCount shouldBeGreaterThan 0
        }

        test("puzzle can perform action") {
            val b = startPuzzle("puzzles/lands-only.pzl")
            val pending = checkNotNull(b.actionBridge(SeatId(1)).getPending())
            assertSoftly {
                pending.state.phase shouldBe "MAIN1"
                pending.state.turn shouldBe 1
            }
            b.actionBridge(SeatId(1)).submitTestRuntimeAction(pending.actionId, PlayerAction.PassPriority)
            b.awaitPriority()
            b.getGame().shouldNotBeNull()
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
            val pending = b.actionBridge(SeatId(1)).getPending()
            pending.shouldNotBeNull()
        }

        test("web test 00 produces valid GSM") {
            val b = startPuzzle("puzzles/bolt-face.pzl")
            val game = b.getGame()!!
            val snap = GsmSnapshot.capture(game, b, "test-puzzle", 1)
            val gsm =
                StateMapper
                    .buildFromSnapshot(
                        snap,
                        1,
                        "test-puzzle",
                        b,
                        viewingSeatId = 1,
                        effectFacts = b.materializeEffectProjectionFacts(),
                        abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                    ).gsm
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
            val actions = ActionMapper.buildFromSnapshot(1, GsmSnapshot.capture(game, b, "test", 0), b)
            actions.actionsList.count { it.actionType.name == "Cast" } shouldBe 1
        }

        // --- Puzzle race bug (seat 2 auto-passes seat 1's pending action) ---

        test("seat 2 onPuzzleStart does not advance past Main1") {
            val puzzle = PuzzleSource.loadFromResource("puzzles/lands-only.pzl")
            val registry = MatchRegistry()
            val matchId = "test-puzzle-race"

            // Create shared bridge — startPuzzle blocks until Main1 priority
            val b = GameBridge(bridgeTimeoutMs = 5_000, cardRepository = TestCardRegistry.repo)
            bridge = b
            b.priorityWaitMs = 5_000
            b.startPuzzle(puzzle)
            TestCardRegistry.registerPuzzleCards(b.getGame()!!)
            b.getGame()!!.phaseHandler.phase shouldBe PhaseType.MAIN1

            // Seat 1 (human): connect and run onPuzzleStart — normal path
            val sink1 = ListMessageSink()
            val session1 =
                MatchSession(
                    connection =
                        ConnectionState(
                            seatId = SeatId(1),
                            matchId = matchId,
                            sink = sink1,
                            registry = registry,
                        ),
                    gameBridge = b,
                    paceDelayMs = 0,
                )
            registry.registerSession(matchId, SeatId(1), session1)
            session1.onPuzzleStart()
            b.getGame()!!.phaseHandler.phase shouldBe PhaseType.MAIN1

            val turnBefore = b.getGame()!!.phaseHandler.turn

            // Seat 2 (Familiar): connect and run onPuzzleStart — this is the race
            val sink2 = ListMessageSink()
            val session2 =
                MatchSession(
                    connection =
                        ConnectionState(
                            seatId = SeatId(2),
                            matchId = matchId,
                            sink = sink2,
                            registry = registry,
                        ),
                    gameBridge = b,
                    paceDelayMs = 0,
                )
            registry.registerSession(matchId, SeatId(2), session2)
            session2.onPuzzleStart()

            // Turn must not have advanced — seat 2 must not consume seat 1's actions
            assertSoftly {
                b.getGame()!!.phaseHandler.turn shouldBe turnBefore
                b.getGame()!!.phaseHandler.phase shouldBe PhaseType.MAIN1
                b.actionBridge(SeatId(1)).getPending().shouldNotBeNull()
            }
        }
    })
