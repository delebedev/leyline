package leyline.match

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import leyline.IntegrationTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.types.SeatId
import leyline.config.RuntimeMatchConfig
import leyline.config.RuntimeMatchConfigRegistry
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
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.PerformActionResp
import java.io.File

class PuzzleHandlerTest :
    FunSpec({

        tags(IntegrationTag)

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
        }

        fun greMessages(msg: MatchServiceToClientMessage): List<GREToClientMessage> = msg.greToClientEvent.greToClientMessagesList

        fun tempPuzzleFile(name: String): File =
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
            }

        test("puzzle bridge + initial bundle send ConnectResp/GSM/ActionsAvailable, then enter puzzle loop") {
            val registry = MatchRegistry()
            val sink = ListMessageSink()
            val temp = tempPuzzleFile("bundle")
            try {
                val handler = PuzzleHandler(puzzlePath = { temp.absolutePath }, TestCardRegistry.repo, registry)
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
                val gre = sink.rawMessages.flatMap(::greMessages)
                val actionPrompt = gre.last { it.hasActionsAvailableReq() }
                val initialFull = gre.first { it.hasGameStateMessage() }.gameStateMessage
                session.connection.owner.reduce {
                    session.connection.owner.lastPromptMsgId()
                } shouldBe actionPrompt.msgId

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
                val firstDiff = sink.messages.first { it.hasGameStateMessage() }.gameStateMessage

                assertSoftly {
                    gre.map { it.type }.take(3) shouldBe
                        listOf(
                            GREMessageType.ConnectResp_695e,
                            GREMessageType.GameStateMessage_695e,
                            GREMessageType.ActionsAvailableReq_695e,
                        )
                    gre.first { it.hasGameStateMessage() }.gameStateMessage.actionsCount shouldBe 4
                    firstDiff.prevGameStateId shouldBe initialFull.gameStateId
                    sink.messages.none { it.type == GREMessageType.IllegalRequest } shouldBe true
                    session.gameBridge shouldBeSameInstanceAs bridge
                }
                bridge.shutdown()
            } finally {
                temp.delete()
            }
        }

        test("getOrCreatePuzzleBridge reuses existing bridge for later reconnects") {
            val registry = MatchRegistry()
            val temp = tempPuzzleFile("reuse")
            try {
                val handler = PuzzleHandler(puzzlePath = { temp.absolutePath }, TestCardRegistry.repo, registry)

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
                    sink1.rawMessages
                        .flatMap(::greMessages)
                        .map { it.type }
                        .last() shouldBe GREMessageType.ActionsAvailableReq_695e
                    sink2.rawMessages
                        .flatMap(::greMessages)
                        .map { it.type }
                        .last() shouldBe GREMessageType.ActionsAvailableReq_695e
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
                        puzzlePath = { temp.absolutePath },
                        TestCardRegistry.repo,
                        registry,
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

                val gre = sink.rawMessages.flatMap(::greMessages)

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
                configRegistry.put(RuntimeMatchConfig(matchId = "web-gre-puzzle", puzzle = temp.absolutePath))
                val handler =
                    PuzzleHandler(
                        puzzlePath = { matchId -> configRegistry.get(matchId)?.puzzle },
                        TestCardRegistry.repo,
                        registry,
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

                val gre = sink.rawMessages.flatMap(::greMessages)

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
