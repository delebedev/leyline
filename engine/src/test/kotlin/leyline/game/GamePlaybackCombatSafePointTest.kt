package leyline.game

import forge.card.CardType
import forge.card.RemoveType
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.bundle.LogicalSequenceState
import leyline.game.event.FrameEventLog
import leyline.game.event.GameEvent
import leyline.game.state.GameBridge
import leyline.game.state.ProjectionState
import leyline.game.state.ProjectionViewer
import leyline.game.state.ProjectionViewerRole
import leyline.game.state.StaleProjectionTransitionException
import leyline.testkit.BoardTest
import leyline.testkit.humanPlayer
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.Phase
import wotc.mtgo.gre.external.messaging.Messages.Step
import java.util.EnumSet

class GamePlaybackCombatSafePointTest :
    BoardTest({
        fun setup(
            captureLocalActions: Boolean = false,
            events: FrameEventLog? = null,
        ): CombatPlaybackFixture {
            val (bridge, game, counter) =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Battlefield)
                }
            val source =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
            source.addNewPT(0, 0, 123L, 0L)
            source.addChangedCardTypes(
                CardType(listOf("Creature"), true),
                null,
                false,
                EnumSet.noneOf(RemoveType::class.java),
                123L,
                0L,
                true,
                false,
            )
            source.addChangedCardKeywords(listOf("Haste"), null, false, 123L, null)
            val sourceId = ForgeCardId(source.id)
            bridge.promptBridge(SeatId(1)).journal.record(
                PromptSideEffect.ChoiceResult(
                    sourceForgeCardId = sourceId,
                    chooserSeatId = SeatId(1),
                    choiceValue = 1,
                ),
            )
            bridge.recordEarthbendResolution(sourceId, 42, 0, listOf(sourceId))
            setOpenFrame(bridge, events ?: combatDamageFrame(sourceId))
            bridge.cutCoordinator.registerViewers(
                listOf(
                    ProjectionViewer(SeatId(1), ProjectionViewerRole.Player),
                    ProjectionViewer(SeatId(2), ProjectionViewerRole.Observer),
                ),
            )
            val playback = GamePlayback(bridge, 1, captureLocalActions)
            playback.visit(forge.game.event.GameEventCombatEnded(emptyList(), emptyList()))
            return CombatPlaybackFixture(
                bridge = bridge,
                playback = playback,
                counterBefore = counter.snapshot(),
                choiceVersion =
                    bridge
                        .promptBridge(SeatId(1))
                        .journal
                        .snapshotChoiceResults()
                        .single()
                        .version,
                earthbendVersion =
                    bridge
                        .materializeEffectProjectionFacts()
                        .pendingEarthbendResolutions
                        .single()
                        .version,
            )
        }

        test("multi-frame combat cut publishes exact batches and acknowledges shell facts once") {
            val fixture = setup()

            fixture.playback.onCombatEndedCompleted()
            val batches = fixture.playback.drainQueue()
            val messages = batches.flatten()
            val contents = batches.map { it.first().gameStateMessage }
            val echoes = batches.map { it.last().gameStateMessage }
            val expectedFirstGs = fixture.counterBefore.currentGsId + 1

            assertSoftly {
                batches.map { it.map { message -> message.type } } shouldBe
                    listOf(
                        listOf(GREMessageType.GameStateMessage_695e, GREMessageType.PromptReq, GREMessageType.GameStateMessage_695e),
                        listOf(GREMessageType.GameStateMessage_695e, GREMessageType.GameStateMessage_695e),
                        listOf(GREMessageType.GameStateMessage_695e, GREMessageType.GameStateMessage_695e),
                    )
                messages.map { it.msgId } shouldBe
                    (fixture.counterBefore.currentMsgId + 1..fixture.counterBefore.currentMsgId + messages.size).toList()
                messages.map { it.gameStateId } shouldBe
                    listOf(
                        expectedFirstGs,
                        expectedFirstGs,
                        expectedFirstGs + 1,
                        expectedFirstGs + 2,
                        expectedFirstGs + 3,
                        expectedFirstGs + 4,
                        expectedFirstGs + 5,
                    )
                contents.map { it.gameStateId } shouldBe listOf(expectedFirstGs, expectedFirstGs + 2, expectedFirstGs + 4)
                contents.drop(1).map { it.prevGameStateId } shouldBe contents.dropLast(1).map { it.gameStateId }
                echoes.map { it.prevGameStateId } shouldBe contents.map { it.gameStateId }
                echoes.map { it.gameStateId } shouldBe listOf(expectedFirstGs + 1, expectedFirstGs + 3, expectedFirstGs + 5)
                messages.filter { it.hasGameStateMessage() }.all { it.gameStateId == it.gameStateMessage.gameStateId } shouldBe true
                batches
                    .flatten()
                    .flatMap { it.gameStateMessage.annotationsList }
                    .count { AnnotationType.ChoiceResult in it.typeList } shouldBe 1
                batches
                    .flatten()
                    .flatMap { it.gameStateMessage.annotationsList }
                    .count { AnnotationType.LayeredEffectCreated in it.typeList } shouldBe 4
                fixture.bridge
                    .promptBridge(SeatId(1))
                    .journal
                    .snapshotChoiceResults()
                    .shouldBeEmpty()
                fixture.bridge
                    .materializeEffectProjectionFacts()
                    .pendingEarthbendResolutions
                    .shouldBeEmpty()
                fixture.playback.failure() shouldBe null
            }
        }

        test("remote combat end preserves regular damage split") {
            val fixture = setup(captureLocalActions = true, events = regularCombatDamageFrame())

            fixture.playback.onCombatEndedCompleted()

            fixture.playback.drainQueue() shouldHaveSize 2
        }

        test("remote combat end preserves first-strike and regular damage split") {
            val fixture = setup(captureLocalActions = true)

            fixture.playback.onCombatEndedCompleted()

            fixture.playback.drainQueue() shouldHaveSize 3
        }

        test("frame-two compile failure publishes nothing and retains the exact cut and facts") {
            val fixture = setup()
            val before = fixture.bridge.projectionStateSnapshot()
            var compileCount = 0
            fixture.bridge.diffListener = { _, _ ->
                compileCount++
                if (compileCount == 2) error("frame two failed")
            }

            val thrown = shouldThrow<PlaybackTerminalFailure> { fixture.playback.onCombatEndedCompleted() }

            assertPreInstallFailure(fixture, thrown, before)
            assertSoftly {
                thrown.cause?.message shouldBe "frame two failed"
                compileCount shouldBe 2
            }
            fixture.bridge.diffListener = null
        }

        test("second-batch publication failure rolls back all output and retains the exact cut") {
            val fixture = setup()
            val before = fixture.bridge.projectionStateSnapshot()
            var preexisting: List<GREToClientMessage>? = null
            fixture.bridge.cutCoordinator.setBeforeBatchEnqueue(SeatId(1)) { index, batch ->
                if (index == 0) {
                    preexisting = batch.toList()
                    fixture.bridge.cutCoordinator.enqueueCommittedBatchForTest(SeatId(1), checkNotNull(preexisting))
                } else if (index == 1) {
                    error("second batch failed")
                }
            }

            val thrown = shouldThrow<PlaybackTerminalFailure> { fixture.playback.onCombatEndedCompleted() }
            val remaining = fixture.playback.drainQueue()

            assertPreInstallFailure(fixture, thrown, before, checkQueue = false)
            assertSoftly {
                thrown.cause?.message shouldBe "second batch failed"
                remaining shouldBe listOf(checkNotNull(preexisting))
            }
        }

        test("multi-frame stale install rolls back every batch and retains the exact cut") {
            val fixture = setup()
            val competingId = ForgeCardId(9_999_997)
            var wrote = false
            fixture.bridge.diffListener = { _, _ ->
                if (!wrote) {
                    wrote = true
                    fixture.bridge.getOrAllocInstanceId(competingId)
                }
            }

            val thrown = shouldThrow<PlaybackTerminalFailure> { fixture.playback.onCombatEndedCompleted() }

            assertSoftly {
                wrote shouldBe true
                thrown.cause.shouldBeInstanceOf<StaleProjectionTransitionException>()
                fixture.playback.drainQueue().shouldBeEmpty()
                thrown.pendingCut shouldBe fixture.playback.failure()?.pendingCut
                checkNotNull(thrown.pendingCut).projection.frames shouldHaveSize 3
                fixture.bridge
                    .promptBridge(SeatId(1))
                    .journal
                    .snapshotChoiceResults()
                    .single()
                    .version shouldBe fixture.choiceVersion
                fixture.bridge
                    .materializeEffectProjectionFacts()
                    .pendingEarthbendResolutions
                    .single()
                    .version shouldBe
                    fixture.earthbendVersion
            }
            fixture.bridge.diffListener = null
        }
    })

private fun combatDamageFrame(sourceId: ForgeCardId): FrameEventLog =
    FrameEventLog(
        listOf(
            GameEvent.PhaseChanged(SeatId(1), Phase.Combat_a549.number, Step.FirstStrikeDamage_a2cb.number),
            GameEvent.CoinFlipped(SeatId(1), sourceId, 7, 8, 1),
            GameEvent.DamageDealtToPlayer(sourceId, SeatId(2), 1, leyline.game.event.DamageSourceKind.Combat, true),
            GameEvent.LifeChanged(SeatId(2), 20, 19),
            GameEvent.PhaseChanged(SeatId(1), Phase.Combat_a549.number, Step.CombatDamage_a2cb.number),
            GameEvent.DamageDealtToPlayer(sourceId, SeatId(2), 1, leyline.game.event.DamageSourceKind.Combat, true),
            GameEvent.LifeChanged(SeatId(2), 19, 18),
            GameEvent.PhaseChanged(SeatId(1), Phase.Combat_a549.number, Step.EndCombat_a2cb.number),
            GameEvent.CombatEnded,
        ),
    )

private fun regularCombatDamageFrame(): FrameEventLog =
    FrameEventLog(
        listOf(
            GameEvent.PhaseChanged(SeatId(1), Phase.Combat_a549.number, Step.CombatDamage_a2cb.number),
            GameEvent.DamageDealtToPlayer(ForgeCardId(9_999_991), SeatId(2), 1, leyline.game.event.DamageSourceKind.Combat, true),
            GameEvent.LifeChanged(SeatId(2), 20, 19),
            GameEvent.PhaseChanged(SeatId(1), Phase.Combat_a549.number, Step.EndCombat_a2cb.number),
            GameEvent.CombatEnded,
        ),
    )

private fun setOpenFrame(
    bridge: GameBridge,
    events: FrameEventLog,
) {
    val collector = checkNotNull(bridge.eventCollector)
    val frame = collector.javaClass.getDeclaredField("frame")
    frame.isAccessible = true
    frame.set(collector, events.events.toMutableList())
}

private data class CombatPlaybackFixture(
    val bridge: GameBridge,
    val playback: GamePlayback,
    val counterBefore: LogicalSequenceState,
    val choiceVersion: Long,
    val earthbendVersion: Long,
)

private fun assertPreInstallFailure(
    fixture: CombatPlaybackFixture,
    thrown: PlaybackTerminalFailure,
    before: ProjectionState,
    checkQueue: Boolean = true,
) {
    assertSoftly {
        if (checkQueue) fixture.playback.drainQueue().shouldBeEmpty()
        fixture.bridge.projectionStateSnapshot() shouldBe before
        thrown.pendingCut shouldBe fixture.playback.failure()?.pendingCut
        checkNotNull(thrown.pendingCut).projection.frames shouldHaveSize 3
        fixture.bridge
            .promptBridge(SeatId(1))
            .journal
            .snapshotChoiceResults()
            .single()
            .version shouldBe fixture.choiceVersion
        fixture.bridge
            .materializeEffectProjectionFacts()
            .pendingEarthbendResolutions
            .single()
            .version shouldBe fixture.earthbendVersion
        shouldThrow<PlaybackTerminalFailure> { fixture.playback.onCombatEndedCompleted() } shouldBe thrown
        fixture.playback.drainQueue().shouldBeEmpty()
    }
}
