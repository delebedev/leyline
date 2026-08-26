package leyline.bridge.coord

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.GameActionBridge
import leyline.bridge.handoff.PendingActionKind
import leyline.bridge.handoff.PendingActionState
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.PlaybackTerminalFailure
import leyline.game.state.PendingSubmittedTargets
import leyline.game.state.ProjectionViewer
import leyline.game.state.ProjectionViewerRole
import leyline.game.state.ViewerProjectionCursor
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.Visibility
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

        test("initial priority keeps pending submitted targets on Player across roster orders") {
            data class Published(
                val player: List<GREToClientMessage>,
                val observer: List<GREToClientMessage>,
                val projection: leyline.game.state.ProjectionState,
            )

            fun publish(observerFirst: Boolean): Published {
                val board =
                    startWithBoard { _, human, _ ->
                        addCard("Forest", human, ZoneType.Hand)
                        addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    }
                val prior =
                    board.bridge.projectionStateSnapshot().copy(
                        viewerCursors =
                            mapOf(
                                SeatId(1) to
                                    ViewerProjectionCursor(
                                        pendingSubmittedTargets = PendingSubmittedTargets(InstanceId(777), SeatId(1), version = 3),
                                    ),
                                SeatId(2) to ViewerProjectionCursor(),
                            ),
                    )
                board.bridge.replaceProjectionStateForTest(prior)
                val coordinator = board.bridge.cutCoordinator
                coordinator.registerViewers(
                    if (observerFirst) {
                        listOf(
                            ProjectionViewer(SeatId(2), ProjectionViewerRole.Observer),
                            ProjectionViewer(SeatId(1), ProjectionViewerRole.Player),
                        )
                    } else {
                        listOf(
                            ProjectionViewer(SeatId(1), ProjectionViewerRole.Player),
                            ProjectionViewer(SeatId(2), ProjectionViewerRole.Observer),
                        )
                    },
                )
                val pending =
                    GameActionBridge.PendingAction(
                        actionId = "initial-priority-pending-$observerFirst",
                        state = PendingActionState("Main1", 1, 1, 1, kind = PendingActionKind.PRIORITY),
                        future = CompletableFuture(),
                        windowRuntime = coordinator.actionWindowRuntime(SeatId(1)),
                    )

                coordinator.actions.publish(SeatId(1), pending)

                return Published(
                    player = coordinator.drain(SeatId(1)).single(),
                    observer = coordinator.drain(SeatId(2)).single(),
                    projection = board.bridge.projectionStateSnapshot(),
                )
            }

            val playerFirst = publish(observerFirst = false)
            val observerFirst = publish(observerFirst = true)

            fun List<GREToClientMessage>.annotations() =
                filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.annotationsList }

            assertSoftly {
                playerFirst.player.map { it.toByteArray().toList() } shouldBe
                    observerFirst.player.map { it.toByteArray().toList() }
                playerFirst.player.annotations().count { AnnotationType.PlayerSubmittedTargets in it.typeList } shouldBe 1
                observerFirst.player.annotations().count { AnnotationType.PlayerSubmittedTargets in it.typeList } shouldBe 1
                playerFirst.observer.annotations().none { AnnotationType.PlayerSubmittedTargets in it.typeList } shouldBe true
                observerFirst.observer.annotations().none { AnnotationType.PlayerSubmittedTargets in it.typeList } shouldBe true
                playerFirst.observer
                    .single()
                    .gameStateMessage
                    .zonesList
                    .filter { it.visibility == Visibility.Private }
                    .flatMap { it.objectInstanceIdsList } shouldBe emptyList()
                playerFirst.observer
                    .single()
                    .gameStateMessage
                    .gameObjectsList
                    .none { it.visibility == Visibility.Private } shouldBe true
                observerFirst.observer
                    .single()
                    .gameStateMessage
                    .zonesList
                    .filter { it.visibility == Visibility.Private }
                    .flatMap { it.objectInstanceIdsList } shouldBe emptyList()
                observerFirst.observer
                    .single()
                    .gameStateMessage
                    .gameObjectsList
                    .none { it.visibility == Visibility.Private } shouldBe true
                playerFirst.projection.viewerCursors
                    .getValue(SeatId(1))
                    .pendingSubmittedTargets shouldBe null
                observerFirst.projection.viewerCursors
                    .getValue(SeatId(1))
                    .pendingSubmittedTargets shouldBe null
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
                    ProjectionViewer(SeatId(1), ProjectionViewerRole.Player),
                    ProjectionViewer(SeatId(2), ProjectionViewerRole.Observer),
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
