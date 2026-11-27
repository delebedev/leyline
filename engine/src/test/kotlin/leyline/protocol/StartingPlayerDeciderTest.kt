package leyline.protocol

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.bridge.types.SeatId
import leyline.game.bundle.LifecycleMessageMaterializer
import leyline.game.bundle.LogicalSequencePlanner
import leyline.game.state.ProjectionViewer
import leyline.game.state.ProjectionViewerRole
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType

/**
 * protocol: ChooseStartingPlayerReq.decider — the request goes to exactly one seat, the die-roll winner.
 * protocol: ChooseStartingPlayerReq.pending-type — only the decider's initial state marks the pending response.
 */
class StartingPlayerDeciderTest :
    BoardTest({
        for (winner in listOf(1, 2)) {
            test("the starting-player request goes to the die-roll winner, seat $winner") {
                val (bridge, _, _) = startWithBoard { _, _, _ -> }
                val bundle =
                    LifecycleMessageMaterializer.initialBundles(
                        viewers =
                            listOf(
                                ProjectionViewer(SeatId(1), ProjectionViewerRole.Player),
                                ProjectionViewer(SeatId(2), ProjectionViewerRole.Player),
                            ),
                        matchId = "starting-player",
                        gameStateId = 1,
                        planner = LogicalSequencePlanner(bridge.projectionStateSnapshot().sequence),
                        bridge = bridge,
                        dieRollWinner = winner,
                    )
                val requests =
                    bundle.viewers.flatMap { (_, messages) ->
                        messages.filter { it.type == GREMessageType.ChooseStartingPlayerReq_695e }
                    }
                val pendingSeats =
                    bundle.viewers
                        .filter { (_, messages) ->
                            messages.single { it.type == GREMessageType.GameStateMessage_695e }.gameStateMessage.pendingMessageCount > 0
                        }.map { it.first.value }
                val decisionPlayers =
                    bundle.viewers.map { (_, messages) ->
                        messages
                            .single { it.type == GREMessageType.GameStateMessage_695e }
                            .gameStateMessage.turnInfo.decisionPlayer
                    }
                val pendingPlayers =
                    bundle.viewers
                        .flatMap { (_, messages) ->
                            messages
                                .single { it.type == GREMessageType.GameStateMessage_695e }
                                .gameStateMessage.playersList
                                .filter { it.pendingMessageType == ClientMessageType.ChooseStartingPlayerResp_097b }
                                .map { it.systemSeatNumber }
                        }.distinct()
                assertSoftly {
                    requests.size shouldBe 1
                    decisionPlayers.distinct() shouldContainExactly listOf(winner)
                    pendingPlayers shouldContainExactly listOf(winner)
                    requests.flatMap { it.systemSeatIdsList } shouldContainExactly listOf(winner)
                    pendingSeats shouldContainExactly listOf(winner)
                }
            }
        }
    })
