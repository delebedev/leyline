package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import leyline.IntegrationTag
import leyline.bridge.SeatId
import leyline.infra.ListMessageSink
import leyline.match.MatchRegistry
import leyline.match.MatchSession
import leyline.match.PuzzleHandler
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage
import java.io.File

class PuzzleHandlerTest :
    FunSpec({

        tags(IntegrationTag)

        beforeSpec {
            leyline.bridge.GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
        }

        fun greMessages(msg: MatchServiceToClientMessage): List<GREToClientMessage> =
            msg.greToClientEvent.greToClientMessagesList

        fun outbound(channel: EmbeddedChannel): List<MatchServiceToClientMessage> =
            generateSequence { channel.readOutbound<MatchServiceToClientMessage>() }.toList()

        fun channelCtx(): Pair<EmbeddedChannel, ChannelHandlerContext> {
            val probe = object : ChannelInboundHandlerAdapter() {}
            val channel = EmbeddedChannel(probe)
            return channel to (channel.pipeline().context(probe) as ChannelHandlerContext)
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

        test("onPuzzleConnect sends initial bundle and actions, then enters puzzle loop") {
            val registry = MatchRegistry()
            val sink = ListMessageSink()
            val session = MatchSession(seatId = SeatId(1), matchId = "puzzle-bolt-face", sink = sink, registry = registry, paceDelayMs = 0)
            val temp = tempPuzzleFile("bundle")
            try {
                val handler = PuzzleHandler(puzzlePath = { temp.absolutePath }, TestCardRegistry.repo, registry)
                val (channel, ctx) = channelCtx()

                val bridge = handler.onPuzzleConnect(ctx, session, "puzzle-bolt-face", 1)
                val gre = outbound(channel).flatMap(::greMessages)

                gre.map { it.type } shouldContain GREMessageType.ConnectResp_695e
                gre.map { it.type } shouldContain GREMessageType.GameStateMessage_695e
                gre.map { it.type } shouldContain GREMessageType.ActionsAvailableReq_695e
                sink.messages.isNotEmpty().shouldBeTrue()
                session.gameBridge shouldBeSameInstanceAs bridge
                channel.close()
                bridge.shutdown()
            } finally {
                temp.delete()
            }
        }

        test("onPuzzleConnect reuses existing bridge for later reconnects") {
            val registry = MatchRegistry()
            val temp = tempPuzzleFile("reuse")
            try {
                val handler = PuzzleHandler(puzzlePath = { temp.absolutePath }, TestCardRegistry.repo, registry)

                val sink1 = ListMessageSink()
                val session1 = MatchSession(seatId = SeatId(1), matchId = "puzzle-lands-only", sink = sink1, registry = registry, paceDelayMs = 0)
                val (channel1, ctx1) = channelCtx()
                val first = handler.onPuzzleConnect(ctx1, session1, "puzzle-lands-only", 1)

                val sink2 = ListMessageSink()
                val session2 = MatchSession(seatId = SeatId(1), matchId = "puzzle-lands-only", sink = sink2, registry = registry, paceDelayMs = 0)
                val (channel2, ctx2) = channelCtx()
                val second = handler.onPuzzleConnect(ctx2, session2, "puzzle-lands-only", 1)

                first shouldBeSameInstanceAs second
                registry.getMatch("puzzle-lands-only")!!.bridge shouldBeSameInstanceAs first
                outbound(channel1).flatMap(::greMessages).map { it.type } shouldContain GREMessageType.ActionsAvailableReq_695e
                outbound(channel2).flatMap(::greMessages).map { it.type } shouldContain GREMessageType.ActionsAvailableReq_695e
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
                val session = MatchSession(seatId = SeatId(1), matchId = "puzzle-cli-puzzle", sink = sink, registry = registry, paceDelayMs = 0)
                val handler = PuzzleHandler(
                    puzzlePath = { temp.absolutePath },
                    TestCardRegistry.repo,
                    registry,
                )
                val (channel, ctx) = channelCtx()

                handler.isPuzzleMatch("puzzle-cli-puzzle").shouldBeTrue()
                val bridge = handler.onPuzzleConnect(ctx, session, "puzzle-cli-puzzle", 1)

                outbound(channel).flatMap(::greMessages).map { it.type } shouldContain GREMessageType.ActionsAvailableReq_695e
                bridge.isPuzzle.shouldBeTrue()
                channel.close()
                bridge.shutdown()
            } finally {
                temp.delete()
            }
        }
    })
