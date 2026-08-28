package leyline.match

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.bridge.types.SeatId
import leyline.game.PlaybackTerminalFailure
import leyline.game.state.ProjectionViewer
import leyline.game.state.ProjectionViewerRole
import leyline.infra.MessageSink
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.ResultReason

class GameOverDeliveryTest :
    BoardTest({
        test("lifecycle delivery failure terminalizes the coordinator") {
            val bridge = startWithBoard { _, _, _ -> }.bridge
            val registry = MatchRegistry()
            bridge.cutCoordinator.registerViewers(
                listOf(
                    ProjectionViewer(SeatId(1), ProjectionViewerRole.Player),
                    ProjectionViewer(SeatId(2), ProjectionViewerRole.Observer),
                ),
            )
            val failure = IllegalStateException("lifecycle sink failed")
            val session =
                MatchSession(
                    connection = ConnectionState(SeatId(1), MATCH_ID, failingSink(failure), registry),
                    gameBridge = bridge,
                    paceDelayMs = 0,
                )

            try {
                bridge.cutCoordinator.lifecycle.publishDealHand(SeatId(1))
                shouldTerminalizeDelivery(failure) { session.deliverLifecycle(bridge) } shouldBe
                    bridge.cutCoordinator.failure()
            } finally {
                session.close()
            }
        }

        test("MatchSession game-over delivery failure terminalizes before raw completion") {
            val bridge = startWithBoard { _, _, _ -> }.bridge
            val registry = MatchRegistry()
            bridge.cutCoordinator.registerViewers(
                listOf(
                    ProjectionViewer(SeatId(1), ProjectionViewerRole.Player),
                    ProjectionViewer(SeatId(2), ProjectionViewerRole.Observer),
                ),
            )
            val failure = IllegalStateException("match sink failed")
            val session =
                MatchSession(
                    connection = ConnectionState(SeatId(1), MATCH_ID, failingSink(failure), registry),
                    gameBridge = bridge,
                    paceDelayMs = 0,
                )

            try {
                shouldTerminalizeDelivery(failure) { session.sendGameOver(ResultReason.Concede) } shouldBe
                    bridge.cutCoordinator.failure()
            } finally {
                session.close()
            }
        }

        test("MatchSession drains Familiar terminal output before raw completion") {
            val bridge = startWithBoard { _, _, _ -> }.bridge
            val registry = MatchRegistry()
            bridge.cutCoordinator.registerViewers(
                listOf(
                    ProjectionViewer(SeatId(1), ProjectionViewerRole.Player),
                    ProjectionViewer(SeatId(2), ProjectionViewerRole.Observer),
                ),
            )
            val deliveries = mutableListOf<String>()
            val playerSink =
                object : MessageSink {
                    override fun send(messages: List<GREToClientMessage>) {
                        deliveries += "player"
                    }

                    override fun sendRaw(msg: MatchServiceToClientMessage) {
                        deliveries += "raw"
                    }
                }
            val familiarSink =
                object : MessageSink {
                    override fun send(messages: List<GREToClientMessage>) {
                        deliveries += "familiar"
                    }

                    override fun sendRaw(msg: MatchServiceToClientMessage) = error("Familiar sends no raw completion")
                }
            val session =
                MatchSession(
                    connection = ConnectionState(SeatId(1), MATCH_ID, playerSink, registry),
                    gameBridge = bridge,
                    paceDelayMs = 0,
                )
            val familiar = FamiliarSession(SeatId(2), MATCH_ID, familiarSink, bridge)
            registry.registerSession(MATCH_ID, SeatId(1), session)
            registry.registerSession(MATCH_ID, SeatId(2), familiar)

            session.sendGameOver(ResultReason.Concede)

            deliveries shouldContainExactly listOf("player", "familiar", "raw")
        }

        test("SpectatorSession game-over delivery failure terminalizes the coordinator") {
            val bridge = startWithBoard { _, _, _ -> }.bridge
            val failure = IllegalStateException("spectator sink failed")
            bridge.cutCoordinator.registerViewers(
                listOf(
                    ProjectionViewer(SeatId(1), ProjectionViewerRole.Observer),
                    ProjectionViewer(SeatId(2), ProjectionViewerRole.Observer),
                ),
            )
            val session = SpectatorSession(SeatId(1), MATCH_ID, failingSink(failure), bridge)

            try {
                shouldTerminalizeDelivery(failure) { session.sendGameOver(ResultReason.Concede) } shouldBe
                    bridge.cutCoordinator.failure()
            } finally {
                session.close()
            }
        }
    })

private fun shouldTerminalizeDelivery(
    failure: Exception,
    sendGameOver: () -> Unit,
): PlaybackTerminalFailure {
    val terminal = shouldThrow<PlaybackTerminalFailure> { sendGameOver() }
    terminal.cause shouldBe failure
    return terminal
}

private fun failingSink(failure: Exception): MessageSink =
    object : MessageSink {
        override fun send(messages: List<GREToClientMessage>) = throw failure

        override fun sendRaw(msg: MatchServiceToClientMessage) = error("raw delivery should not run")
    }

private const val MATCH_ID = "test-match"
