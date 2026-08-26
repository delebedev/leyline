package leyline.bridge.coord

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.GameActionBridge
import leyline.bridge.handoff.PendingActionKind
import leyline.bridge.handoff.PendingActionState
import leyline.bridge.types.SeatId
import leyline.game.PlaybackTerminalFailure
import leyline.game.state.ProjectionViewer
import leyline.game.state.ProjectionViewerRole
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import java.util.concurrent.CompletableFuture

class MatchActionWindowRuntimeTest :
    BoardTest({
        test("initial action player output is invariant with an observer") {
            fun publish(
                kind: PendingActionKind,
                withObserver: Boolean,
            ): List<List<Byte>> {
                val board =
                    startWithBoard { _, human, _ ->
                        addCard("Forest", human, ZoneType.Hand)
                        addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    }
                val coordinator = board.bridge.cutCoordinator
                coordinator.registerViewers(
                    buildList {
                        if (withObserver) add(ProjectionViewer(SeatId(2), ProjectionViewerRole.Observer))
                        add(ProjectionViewer(SeatId(1), ProjectionViewerRole.Player))
                    },
                )
                val pending =
                    GameActionBridge.PendingAction(
                        actionId = "initial-$kind-$withObserver",
                        state = PendingActionState("Main1", 1, 1, 1, kind = kind),
                        future = CompletableFuture(),
                        windowRuntime = coordinator.actionWindowRuntime(SeatId(1)),
                    )
                coordinator.actions.publish(SeatId(1), pending)
                return coordinator.drain(SeatId(1)).single().map { it.toByteArray().toList() }
            }

            listOf(PendingActionKind.PRIORITY, PendingActionKind.DECLARE_ATTACKERS, PendingActionKind.DECLARE_BLOCKERS).forEach { kind ->
                publish(kind, withObserver = true) shouldBe publish(kind, withObserver = false)
            }
        }

        test("initial action windows give the observer projected state only") {
            listOf(PendingActionKind.PRIORITY, PendingActionKind.DECLARE_ATTACKERS, PendingActionKind.DECLARE_BLOCKERS).forEach { kind ->
                val board =
                    startWithBoard { _, human, _ ->
                        addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    }
                val coordinator = board.bridge.cutCoordinator
                coordinator.registerViewers(
                    listOf(
                        ProjectionViewer(SeatId(1), ProjectionViewerRole.Player),
                        ProjectionViewer(SeatId(2), ProjectionViewerRole.Observer),
                    ),
                )
                val pending =
                    GameActionBridge.PendingAction(
                        actionId = "initial-$kind",
                        state = PendingActionState("Main1", 1, 1, 1, kind = kind),
                        future = CompletableFuture(),
                        windowRuntime = coordinator.actionWindowRuntime(SeatId(1)),
                    )

                coordinator.actions.publish(SeatId(1), pending)

                val player = coordinator.drain(SeatId(1)).single()
                val observer = coordinator.drain(SeatId(2)).single()
                val expectedRequest =
                    when (kind) {
                        PendingActionKind.PRIORITY -> GREMessageType.ActionsAvailableReq_695e
                        PendingActionKind.DECLARE_ATTACKERS -> GREMessageType.DeclareAttackersReq_695e
                        PendingActionKind.DECLARE_BLOCKERS -> GREMessageType.DeclareBlockersReq_695e
                        PendingActionKind.SYNC_ONLY -> error("Unexpected synchronization kind")
                    }
                assertSoftly {
                    player.any { it.type == expectedRequest } shouldBe true
                    observer.size shouldBe 1
                    observer.single().hasGameStateMessage() shouldBe true
                    observer.none { it.type == expectedRequest } shouldBe true
                    observer
                        .single()
                        .gameStateMessage.zonesList
                        .filter { it.visibility == wotc.mtgo.gre.external.messaging.Messages.Visibility.Private }
                        .flatMap { it.objectInstanceIdsList } shouldBe emptyList()
                }
            }
        }

        test("initial action publication rolls back when the observer feed rejects its batch") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Hand)
                }
            val coordinator = board.bridge.cutCoordinator
            coordinator.registerViewers(
                listOf(
                    ProjectionViewer(SeatId(2), ProjectionViewerRole.Observer),
                    ProjectionViewer(SeatId(1), ProjectionViewerRole.Player),
                ),
            )
            val pending =
                GameActionBridge.PendingAction(
                    actionId = "initial-priority-failure",
                    state = PendingActionState("Main1", 1, 1, 1, kind = PendingActionKind.PRIORITY),
                    future = CompletableFuture(),
                    windowRuntime = coordinator.actionWindowRuntime(SeatId(1)),
                )
            val priorProjection = board.bridge.projectionStateSnapshot()
            val priorSequence = board.bridge.committedSequence()
            coordinator.setBeforeBatchEnqueue(SeatId(2)) { _, _ -> error("observer feed unavailable") }

            shouldThrow<PlaybackTerminalFailure> {
                coordinator.actions.publish(SeatId(1), pending)
            }

            assertSoftly {
                coordinator.drain(SeatId(1)).shouldBeEmpty()
                coordinator.drain(SeatId(2)).shouldBeEmpty()
                board.bridge.projectionStateSnapshot() shouldBe priorProjection
                board.bridge.committedSequence() shouldBe priorSequence
                pending.future.isDone shouldBe false
                coordinator.actions.isVisible(pending.actionId) shouldBe false
                coordinator.actions.actionOffersForTest(pending.actionId).shouldBeEmpty()
            }
        }
    })
