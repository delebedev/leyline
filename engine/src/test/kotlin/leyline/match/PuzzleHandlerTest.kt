package leyline.match

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import leyline.IntegrationTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.handoff.PendingActionKind
import leyline.bridge.types.SeatId
import leyline.config.EngineSettings
import leyline.config.RuntimeMatchConfig
import leyline.config.RuntimeMatchConfigRegistry
import leyline.game.generator.PuzzleLibrary
import leyline.game.generator.PuzzleSource
import leyline.infra.ListMessageSink
import leyline.match.ConnectionState
import leyline.match.MatchRegistry
import leyline.match.MatchSession
import leyline.match.PuzzleHandler
import leyline.testkit.TestCardRegistry
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.PerformActionResp
import java.io.File

class PuzzleHandlerTest :
    FunSpec({

        tags(IntegrationTag)

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
        }

        fun tempPuzzleFile(
            name: String,
            activePlayer: String = "Human",
            humanHand: String = "Lightning Bolt",
        ): File =
            File.createTempFile("leyline-$name-", ".pzl").apply {
                writeText(
                    """
                    [metadata]
                    Name:$name
                    Goal:Win
                    Turns:1
                    Difficulty:Easy
                    Description:$name.

                    [state]
                    ActivePlayer=$activePlayer
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=3

                    humanhand=$humanHand
                    humanbattlefield=Mountain
                    humanlibrary=Mountain
                    ailibrary=Mountain
                    """.trimIndent(),
                )
            }

        test("puzzle bridge + initial bundle send ConnectResp/GSM/ActionsAvailable, then enter puzzle loop") {
            val registry = MatchRegistry()
            val sink = ListMessageSink()
            val temp = tempPuzzleFile("bundle")
            try {
                val handler =
                    PuzzleHandler(
                        puzzleIdentity = { temp.nameWithoutExtension },
                        TestCardRegistry.repo,
                        registry,
                        EngineSettings(),
                        PuzzleLibrary(temp.parentFile),
                    )
                val bridge = handler.getOrCreatePuzzleBridge("puzzle-bolt-face")
                val session =
                    MatchSession(
                        connection =
                            ConnectionState(
                                seatId = SeatId(1),
                                matchId = "puzzle-bolt-face",
                                sink = sink,
                                registry = registry,
                            ),
                        gameBridge = bridge,
                        paceDelayMs = 0,
                    )
                handler.sendPuzzleInitialBundle(session, "puzzle-bolt-face", 1)
                val gre = sink.messages
                val actionPrompt = gre.last { it.hasActionsAvailableReq() }
                session.counter.lastPromptMsgId() shouldBe actionPrompt.msgId

                session.onPerformAction(
                    ClientToGREMessage
                        .newBuilder()
                        .setType(ClientMessageType.PerformActionResp_097b)
                        .setSystemSeatId(1)
                        .setGameStateId(actionPrompt.gameStateId)
                        .setRespId(actionPrompt.msgId)
                        .setPerformActionResp(
                            PerformActionResp
                                .newBuilder()
                                .addActions(Action.newBuilder().setActionType(ActionType.Pass)),
                        ).build(),
                )

                assertSoftly {
                    gre.map { it.type }.take(3) shouldBe
                        listOf(
                            GREMessageType.ConnectResp_695e,
                            GREMessageType.GameStateMessage_695e,
                            GREMessageType.ActionsAvailableReq_695e,
                        )
                    gre.first { it.hasGameStateMessage() }.gameStateMessage.actionsCount shouldBe 4
                    sink.messages.none { it.type == GREMessageType.IllegalRequest } shouldBe true
                    session.gameBridge shouldBeSameInstanceAs bridge
                }
                bridge.shutdown()
            } finally {
                temp.delete()
            }
        }

        test("opponent-turn puzzle start releases its synchronization horizon through runtime delivery") {
            val registry = MatchRegistry()
            val sink = ListMessageSink()
            val temp = tempPuzzleFile("opponent-turn", activePlayer = "AI", humanHand = "")
            try {
                val handler =
                    PuzzleHandler(
                        puzzleIdentity = { temp.nameWithoutExtension },
                        TestCardRegistry.repo,
                        registry,
                        EngineSettings(),
                        PuzzleLibrary(temp.parentFile),
                    )
                val bridge = handler.getOrCreatePuzzleBridge("puzzle-opponent-turn")
                val session =
                    MatchSession(
                        connection =
                            ConnectionState(
                                seatId = SeatId(1),
                                matchId = "puzzle-opponent-turn",
                                sink = sink,
                                registry = registry,
                            ),
                        gameBridge = bridge,
                        paceDelayMs = 0,
                    )

                handler.sendPuzzleInitialBundle(session, "puzzle-opponent-turn", 1)

                assertSoftly {
                    sink.messages.any { it.hasGameStateMessage() } shouldBe true
                    sink.messages.none { it.hasActionsAvailableReq() } shouldBe true
                    bridge
                        .seat(SeatId(1))
                        .action
                        .getPending()
                        ?.state
                        ?.kind shouldNotBe PendingActionKind.SYNC_ONLY
                }
                bridge.shutdown()
            } finally {
                temp.delete()
            }
        }

        test("opponent-turn puzzle replacement advances through its synchronization horizon") {
            val registry = MatchRegistry()
            val sink = ListMessageSink()
            val initial = tempPuzzleFile("replacement-initial")
            val replacement = tempPuzzleFile("replacement-opponent-turn", activePlayer = "AI", humanHand = "")
            try {
                val handler =
                    PuzzleHandler(
                        puzzleIdentity = { initial.nameWithoutExtension },
                        TestCardRegistry.repo,
                        registry,
                        EngineSettings(),
                        PuzzleLibrary(initial.parentFile),
                    )
                val bridge = handler.getOrCreatePuzzleBridge("puzzle-replacement")
                val session =
                    MatchSession(
                        connection =
                            ConnectionState(
                                seatId = SeatId(1),
                                matchId = "puzzle-replacement",
                                sink = sink,
                                registry = registry,
                            ),
                        gameBridge = bridge,
                        paceDelayMs = 0,
                    )
                handler.sendPuzzleInitialBundle(session, "puzzle-replacement", 1)
                val initialMessageCount = sink.messages.size

                session.replaceForPuzzle(
                    PuzzleSource.load(PuzzleLibrary(replacement.parentFile).require(replacement.nameWithoutExtension)),
                )

                assertSoftly {
                    bridge
                        .seat(SeatId(1))
                        .action
                        .getPending()
                        ?.state
                        ?.kind shouldNotBe PendingActionKind.SYNC_ONLY
                    sink.messages.drop(initialMessageCount).any { it.hasGameStateMessage() } shouldBe true
                }
                bridge.shutdown()
            } finally {
                initial.delete()
                replacement.delete()
            }
        }

        test("getOrCreatePuzzleBridge reuses existing bridge for later reconnects") {
            val registry = MatchRegistry()
            val temp = tempPuzzleFile("reuse")
            try {
                val handler =
                    PuzzleHandler(
                        puzzleIdentity = { temp.nameWithoutExtension },
                        TestCardRegistry.repo,
                        registry,
                        EngineSettings(),
                        PuzzleLibrary(temp.parentFile),
                    )

                val sink1 = ListMessageSink()
                val first = handler.getOrCreatePuzzleBridge("puzzle-lands-only")
                val session1 =
                    MatchSession(
                        connection =
                            ConnectionState(
                                seatId = SeatId(1),
                                matchId = "puzzle-lands-only",
                                sink = sink1,
                                registry = registry,
                            ),
                        gameBridge = first,
                        paceDelayMs = 0,
                    )
                handler.sendPuzzleInitialBundle(session1, "puzzle-lands-only", 1)

                val sink2 = ListMessageSink()
                val second = handler.getOrCreatePuzzleBridge("puzzle-lands-only")
                val session2 =
                    MatchSession(
                        connection =
                            ConnectionState(
                                seatId = SeatId(1),
                                matchId = "puzzle-lands-only",
                                sink = sink2,
                                registry = registry,
                            ),
                        gameBridge = second,
                        paceDelayMs = 0,
                    )
                handler.sendPuzzleInitialBundle(session2, "puzzle-lands-only", 1)

                assertSoftly {
                    first shouldBeSameInstanceAs second
                    registry.getMatch("puzzle-lands-only")!!.bridge shouldBeSameInstanceAs first
                    sink1.messages.map { it.type }.last() shouldBe GREMessageType.ActionsAvailableReq_695e
                    sink2.messages.map { it.type }.last() shouldBe GREMessageType.ActionsAvailableReq_695e
                }
                first.shutdown()
            } finally {
                temp.delete()
            }
        }

        test("configured puzzle file loads for routed sparky puzzle matches") {
            val temp = File.createTempFile("leyline-puzzle-", ".pzl")
            temp.writeText(
                """
                [metadata]
                Name:CLI Puzzle
                Goal:Win
                Turns:1
                Difficulty:Easy
                Description:CLI override.

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=3

                humanhand=Lightning Bolt
                humanbattlefield=Mountain
                humanlibrary=Mountain
                ailibrary=Mountain
                """.trimIndent(),
            )

            try {
                val registry = MatchRegistry()
                val sink = ListMessageSink()
                val handler =
                    PuzzleHandler(
                        puzzleIdentity = { temp.nameWithoutExtension },
                        TestCardRegistry.repo,
                        registry,
                        EngineSettings(),
                        PuzzleLibrary(temp.parentFile),
                    )
                handler.isPuzzleMatch("puzzle-cli-puzzle").shouldBeTrue()
                val bridge = handler.getOrCreatePuzzleBridge("puzzle-cli-puzzle")
                val session =
                    MatchSession(
                        connection =
                            ConnectionState(
                                seatId = SeatId(1),
                                matchId = "puzzle-cli-puzzle",
                                sink = sink,
                                registry = registry,
                            ),
                        gameBridge = bridge,
                        paceDelayMs = 0,
                    )
                handler.sendPuzzleInitialBundle(session, "puzzle-cli-puzzle", 1)

                val gre = sink.messages

                assertSoftly {
                    gre.map { it.type } shouldContain GREMessageType.ActionsAvailableReq_695e
                    gre
                        .first { it.hasGameStateMessage() }
                        .gameStateMessage.gameInfo.matchID shouldBe "puzzle-cli-puzzle"
                    bridge.isPuzzle.shouldBeTrue()
                }
                bridge.shutdown()
            } finally {
                temp.delete()
            }
        }

        test("match-scoped puzzle config activates only the configured matchId") {
            val configRegistry = RuntimeMatchConfigRegistry()
            val registry = MatchRegistry()
            val sink = ListMessageSink()
            val temp = tempPuzzleFile("match-config")
            try {
                configRegistry.put(RuntimeMatchConfig(matchId = "web-gre-puzzle", puzzle = temp.nameWithoutExtension))
                val handler =
                    PuzzleHandler(
                        puzzleIdentity = { matchId -> configRegistry.get(matchId)?.puzzle },
                        TestCardRegistry.repo,
                        registry,
                        EngineSettings(),
                        PuzzleLibrary(temp.parentFile),
                    )
                handler.isPuzzleMatch("web-gre-puzzle").shouldBeTrue()
                handler.isPuzzleMatch("web-gre-constructed").shouldBeFalse()
                val bridge = handler.getOrCreatePuzzleBridge("web-gre-puzzle")
                val session =
                    MatchSession(
                        connection =
                            ConnectionState(
                                seatId = SeatId(1),
                                matchId = "web-gre-puzzle",
                                sink = sink,
                                registry = registry,
                            ),
                        gameBridge = bridge,
                        paceDelayMs = 0,
                    )
                handler.sendPuzzleInitialBundle(session, "web-gre-puzzle", 1)

                val gre = sink.messages

                assertSoftly {
                    gre.map { it.type } shouldContain GREMessageType.ActionsAvailableReq_695e
                    gre
                        .first { it.hasGameStateMessage() }
                        .gameStateMessage.gameInfo.matchID shouldBe "web-gre-puzzle"
                    bridge.isPuzzle.shouldBeTrue()
                }
                bridge.shutdown()
            } finally {
                temp.delete()
            }
        }
    })
