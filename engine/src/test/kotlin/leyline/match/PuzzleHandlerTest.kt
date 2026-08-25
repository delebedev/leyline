package leyline.match

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import leyline.IntegrationTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.types.SeatId
import leyline.config.EngineSettings
import leyline.config.RuntimeMatchConfig
import leyline.config.RuntimeMatchConfigRegistry
import leyline.game.generator.PuzzleLibrary
import leyline.infra.ListMessageSink
import leyline.infra.MatchOutput
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

        fun outbound(channel: EmbeddedChannel): List<MatchServiceToClientMessage> =
            generateSequence { channel.readOutbound<MatchServiceToClientMessage>() }.toList()

        fun channelCtx(): Pair<EmbeddedChannel, ChannelHandlerContext> {
            val probe = object : ChannelInboundHandlerAdapter() {}
            val channel = EmbeddedChannel(probe)
            return channel to (channel.pipeline().context(probe) as ChannelHandlerContext)
        }

        fun output(ctx: ChannelHandlerContext) =
            object : MatchOutput {
                override fun send(message: MatchServiceToClientMessage) {
                    ctx.writeAndFlush(message)
                }

                override fun close() {
                    ctx.close()
                }
            }

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
                val handler =
                    PuzzleHandler(
                        puzzleIdentity = { temp.nameWithoutExtension },
                        TestCardRegistry.repo,
                        registry,
                        EngineSettings(),
                        PuzzleLibrary(temp.parentFile),
                    )
                val (channel, ctx) = channelCtx()

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
                handler.sendPuzzleInitialBundle(output(ctx), session, "puzzle-bolt-face", 1)
                val gre = outbound(channel).flatMap(::greMessages)
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
                channel.close()
                bridge.shutdown()
            } finally {
                temp.delete()
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
                val (channel1, ctx1) = channelCtx()
                handler.sendPuzzleInitialBundle(output(ctx1), session1, "puzzle-lands-only", 1)

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
                val (channel2, ctx2) = channelCtx()
                handler.sendPuzzleInitialBundle(output(ctx2), session2, "puzzle-lands-only", 1)

                assertSoftly {
                    first shouldBeSameInstanceAs second
                    registry.getMatch("puzzle-lands-only")!!.bridge shouldBeSameInstanceAs first
                    outbound(channel1).flatMap(::greMessages).map { it.type }.last() shouldBe GREMessageType.ActionsAvailableReq_695e
                    outbound(channel2).flatMap(::greMessages).map { it.type }.last() shouldBe GREMessageType.ActionsAvailableReq_695e
                }
                channel1.close()
                channel2.close()
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
                val (channel, ctx) = channelCtx()

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
                handler.sendPuzzleInitialBundle(output(ctx), session, "puzzle-cli-puzzle", 1)

                val gre = outbound(channel).flatMap(::greMessages)

                assertSoftly {
                    gre.map { it.type } shouldContain GREMessageType.ActionsAvailableReq_695e
                    gre
                        .first { it.hasGameStateMessage() }
                        .gameStateMessage.gameInfo.matchID shouldBe "puzzle-cli-puzzle"
                    bridge.isPuzzle.shouldBeTrue()
                }
                channel.close()
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
                val (channel, ctx) = channelCtx()

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
                handler.sendPuzzleInitialBundle(output(ctx), session, "web-gre-puzzle", 1)

                val gre = outbound(channel).flatMap(::greMessages)

                assertSoftly {
                    gre.map { it.type } shouldContain GREMessageType.ActionsAvailableReq_695e
                    gre
                        .first { it.hasGameStateMessage() }
                        .gameStateMessage.gameInfo.matchID shouldBe "web-gre-puzzle"
                    bridge.isPuzzle.shouldBeTrue()
                }
                channel.close()
                bridge.shutdown()
            } finally {
                temp.delete()
            }
        }
    })
