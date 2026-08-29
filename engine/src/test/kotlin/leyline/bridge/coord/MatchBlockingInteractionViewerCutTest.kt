package leyline.bridge.coord

import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.BlockingInteraction
import leyline.bridge.handoff.CommanderReturnPromptContext
import leyline.bridge.handoff.CommanderZone
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.GamePlayback
import leyline.game.PlaybackCutReason
import leyline.game.PlaybackCutRequest
import leyline.game.PlaybackTerminalFailure
import leyline.game.mapping.ZoneIds
import leyline.game.state.ProjectionState
import leyline.game.state.ProjectionViewerRole
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.Visibility
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MatchBlockingInteractionViewerCutTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:blocking interaction viewer cut
            Goal:Win
            Turns:1

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanhand=Forest
            humanbattlefield=Forest
            humanlibrary=Forest
            ailibrary=Forest
            """.trimIndent()

        test("snapshot blocking prompts keep Player output and project state only to observers") {
            data class Publication(
                val player: List<GREToClientMessage>,
                val observer: List<GREToClientMessage>,
                val cleanup: List<List<GREToClientMessage>>,
                val projectionAfterInitial: ProjectionState,
                val promptInstanceId: Int,
                val priorRevision: Long,
                val priorOrdinal: Long,
            )

            fun publish(
                commander: Boolean,
                withObserver: Boolean,
            ): Publication {
                val board = startPuzzleAtMain1(puzzle)
                val coordinator = board.bridge.cutCoordinator
                coordinator.drain(SeatId(1))
                if (withObserver) coordinator.registerViewer(SeatId(2), ProjectionViewerRole.Observer)
                val source =
                    board.human
                        .getZone(ZoneType.Battlefield)
                        .cards
                        .first()
                val sourceId = ForgeCardId(source.id)
                val oldInstanceId = board.bridge.getOrAllocInstanceId(sourceId).value
                val promptInstanceId = board.bridge.reserveInstanceId().value
                val interaction =
                    BlockingInteraction.Optional(
                        sourceId = sourceId,
                        forceSnapshotBeforePrompt = true,
                        customPromptId = null,
                        commanderReturn =
                            if (commander) {
                                CommanderReturnPromptContext(
                                    oldInstanceId,
                                    promptInstanceId,
                                    CommanderZone.Battlefield,
                                    CommanderZone.Graveyard,
                                    1,
                                    "Destroy",
                                )
                            } else {
                                null
                            },
                    )
                val prior = board.bridge.projectionStateSnapshot()
                val finished = CountDownLatch(1)
                Thread {
                    coordinator.awaitOptional(interaction, 3_000, false)
                    finished.countDown()
                }.start()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                var pending = coordinator.currentBlockingInteraction()
                while (pending == null && System.nanoTime() < deadline) {
                    Thread.onSpinWait()
                    pending = coordinator.currentBlockingInteraction()
                }
                val exact = checkNotNull(pending)
                val player = coordinator.drain(SeatId(1)).single()
                val observer = if (withObserver) coordinator.drain(SeatId(2)).single() else emptyList()
                val projectionAfterInitial = board.bridge.projectionStateSnapshot()
                coordinator.submitOptionalAnswer(exact.interactionId, exact.gameStateId, true) shouldBe true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                val cleanup = coordinator.drain(SeatId(1))
                if (withObserver) coordinator.drain(SeatId(2)).shouldBeEmpty()
                if (commander) {
                    projectionAfterInitial.limboInstanceIds shouldContain promptInstanceId
                    board.bridge.projectionStateSnapshot().limboInstanceIds shouldNotContain promptInstanceId
                }
                return Publication(
                    player,
                    observer,
                    cleanup,
                    projectionAfterInitial,
                    promptInstanceId,
                    prior.revision,
                    prior.sequence.committedOutputOrdinal,
                )
            }

            listOf(false, true).forEach { commander ->
                val playerOnly = publish(commander, withObserver = false)
                val withObserver = publish(commander, withObserver = true)
                val observerGsm = withObserver.observer.single().gameStateMessage
                assertSoftly {
                    withObserver.player.map { it.toByteArray().toList() } shouldBe
                        playerOnly.player.map { it.toByteArray().toList() }
                    withObserver.observer.size shouldBe 1
                    withObserver.observer.single().hasGameStateMessage() shouldBe true
                    withObserver.observer.none { it.hasOptionalActionMessage() } shouldBe true
                    observerGsm.actionsList.shouldBeEmpty()
                    observerGsm.zonesList
                        .filter { it.visibility == Visibility.Private }
                        .flatMap { it.objectInstanceIdsList }
                        .shouldBeEmpty()
                    observerGsm.gameObjectsList.none { it.visibility == Visibility.Private } shouldBe true
                    observerGsm.gameObjectsList.none { it.instanceId == withObserver.promptInstanceId } shouldBe true
                    withObserver.projectionAfterInitial.revision shouldBe withObserver.priorRevision + 1
                    withObserver.projectionAfterInitial.sequence.committedOutputOrdinal shouldBe withObserver.priorOrdinal + 1
                    if (commander) {
                        withObserver.cleanup.flatten().any {
                            it.gameStateMessage.diffDeletedInstanceIdsList.contains(
                                withObserver.promptInstanceId,
                            )
                        } shouldBe
                            true
                    } else {
                        withObserver.cleanup.shouldBeEmpty()
                    }
                }
            }
        }

        test("snapshot blocking prompts project an explicit source absent from zones") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            coordinator.registerViewer(SeatId(2), ProjectionViewerRole.Observer)

            val battlefieldSource =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
            val source = Card.fromPaperCard(battlefieldSource.getPaperCard(), board.human)
            val sourceId = ForgeCardId(source.id)
            val interaction = BlockingInteraction.Optional(sourceId, true, null, null)
            board.bridge.findCard(sourceId) shouldBe null

            val finished = CountDownLatch(1)
            Thread {
                coordinator.awaitOptional(
                    interaction = interaction,
                    sourceCard = source,
                    timeoutMs = 3_000,
                    defaultOnTimeout = false,
                )
                finished.countDown()
            }.start()

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var pending = coordinator.currentBlockingInteraction()
            while (pending == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                pending = coordinator.currentBlockingInteraction()
            }
            val exact = checkNotNull(pending)

            val player = coordinator.drain(SeatId(1)).single()
            val observer = coordinator.drain(SeatId(2)).single()
            val optional = player.single { it.hasOptionalActionMessage() }.optionalActionMessage
            val observerGsm = observer.single { it.hasGameStateMessage() }.gameStateMessage
            val sourceObject = observerGsm.gameObjectsList.single { it.instanceId == optional.sourceId }

            assertSoftly {
                optional.sourceId shouldBe board.bridge.getOrAllocInstanceId(sourceId).value
                sourceObject.zoneId shouldBe ZoneIds.STACK
                observerGsm.zonesList
                    .single { it.zoneId == ZoneIds.STACK }
                    .objectInstanceIdsList shouldContain optional.sourceId
                coordinator.submitOptionalAnswer(exact.interactionId, exact.gameStateId, true) shouldBe true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
            }
        }

        test("snapshot blocking publication failure preserves its pending lifecycle") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            coordinator.registerViewer(SeatId(2), ProjectionViewerRole.Observer)
            GamePlayback(board.bridge, 1)
            val request = PlaybackCutRequest(PlaybackCutReason.PhaseChanged, 0, false)
            coordinator.requestPlaybackCut(SeatId(1), request)
            val sourceId =
                ForgeCardId(
                    board.human
                        .getZone(ZoneType.Battlefield)
                        .cards
                        .first()
                        .id,
                )
            val interaction = BlockingInteraction.Optional(sourceId, true, null, null)
            val prior = board.bridge.projectionStateSnapshot()
            coordinator.setBeforeBatchEnqueue(SeatId(2)) { _, _ -> error("observer feed unavailable") }

            shouldThrow<PlaybackTerminalFailure> {
                coordinator.awaitOptional(interaction, 3_000, false)
            }

            assertSoftly {
                coordinator.drain(SeatId(1)).shouldBeEmpty()
                coordinator.drain(SeatId(2)).shouldBeEmpty()
                board.bridge.projectionStateSnapshot() shouldBe prior
                board.bridge.committedSequence() shouldBe prior.sequence
                coordinator.requestedPlaybackCut(SeatId(1)) shouldBe request
                coordinator.currentBlockingInteraction() shouldBe null
            }
        }
    })
