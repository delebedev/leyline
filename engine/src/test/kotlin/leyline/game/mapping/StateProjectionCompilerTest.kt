package leyline.game.mapping

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.InMemoryCardRepository
import leyline.game.data.CardProtoBuilder
import leyline.game.event.FrameEventLog
import leyline.game.snapshot.CardSnapshot
import leyline.game.snapshot.GsmSnapshot
import leyline.game.snapshot.ZoneSnapshot
import leyline.game.state.AbilityExhaustionFacts
import leyline.game.state.EffectProjectionFacts
import leyline.game.state.MechanicSourceFacts
import leyline.game.state.PendingSubmittedTargets
import leyline.game.state.PersistentFeedFacts
import leyline.game.state.ProjectionState
import leyline.game.state.PromptFactKey
import leyline.game.state.PromptProjectionFacts
import leyline.game.state.ViewerProjectionCursor
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate
import wotc.mtgo.gre.external.messaging.Messages.Visibility
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

class StateProjectionCompilerTest :
    FunSpec({
        tags(UnitTag)

        test("viewer intent defensively freezes ordered supplements and order values") {
            val supplementValues = mutableListOf<ProjectionSupplement>(ProjectionSupplement.NewTurnStarted)
            val candidates = mutableListOf(ForgeCardId(10))
            val moveCards = mutableListOf(ForgeCardId(10))
            val intent =
                ViewerProjectionIntent.of(
                    supplementValues,
                    OrderPromptProjection.of(
                        candidates,
                        move = OrderZoneMoveFact.of(SeatId(1), moveCards, putOnTop = true, version = 4),
                    ),
                )

            supplementValues += ProjectionSupplement.ReserveTriggeredAbility(7)
            candidates += ForgeCardId(11)
            moveCards += ForgeCardId(11)

            assertSoftly {
                intent.supplements shouldContainExactly listOf(ProjectionSupplement.NewTurnStarted)
                intent.orderPrompt?.candidateForgeIds shouldContainExactly listOf(ForgeCardId(10))
                intent.orderPrompt?.move?.forgeCardIds shouldContainExactly listOf(ForgeCardId(10))
            }
        }

        test("one compile stages order move then supplements and clears exact submitted targets") {
            val cardId = ForgeCardId(10)
            val sourceId = ForgeCardId(20)
            val pending = PendingSubmittedTargets(InstanceId(777), SeatId(1), version = 3)
            val prior =
                ProjectionState
                    .initial()
                    .copy(viewerCursors = mapOf(0 to ViewerProjectionCursor(pendingSubmittedTargets = pending)))
            val input = compilerInput(orderSnapshot(cardId))
            val intent =
                ViewerProjectionIntent.of(
                    supplements =
                        listOf(
                            ProjectionSupplement.NewTurnStarted,
                            ProjectionSupplement.PlayerSelectingTargets(cardId, SeatId(1), reserveTriggeredAbilityForgeId = 9),
                            ProjectionSupplement.ReserveTriggeredAbility(10),
                            ProjectionSupplement.SubmitPendingTargets(pending.spellInstanceId, pending.casterSeatId, pending.version),
                        ),
                    orderPrompt =
                        OrderPromptProjection.of(
                            candidateForgeIds = listOf(cardId),
                            sourceForgeId = sourceId,
                            move = OrderZoneMoveFact.of(SeatId(1), listOf(cardId), putOnTop = true, version = 5),
                        ),
                )

            val first = StateProjectionCompiler.compileOneViewer(compilerEnvironment(), input, prior, intent)
            val retry = StateProjectionCompiler.compileOneViewer(compilerEnvironment(), input, prior, intent)
            val next = first.transition.nextState
            val newCardId = next.identities.forgeIdToInstanceId.getValue(cardId)
            val annotationTypes = first.gsm.annotationsList.map { it.typeList.single() }
            val submitted = first.gsm.annotationsList.single { it.typeList == listOf(AnnotationType.PlayerSubmittedTargets) }
            val changed = first.gsm.annotationsList.single { it.typeList == listOf(AnnotationType.ObjectIdChanged) }
            val transfer = first.gsm.annotationsList.single { it.typeList == listOf(AnnotationType.ZoneTransfer_af5a) }
            val newTurn = first.gsm.annotationsList.single { it.typeList == listOf(AnnotationType.NewTurnStarted) }
            val selecting = first.gsm.annotationsList.single { it.typeList == listOf(AnnotationType.PlayerSelectingTargets) }

            shouldThrow<IllegalStateException> {
                StateProjectionCompiler.compileOneViewer(
                    compilerEnvironment(),
                    input,
                    first.transition.nextState,
                    ViewerProjectionIntent.of(
                        listOf(
                            ProjectionSupplement.SubmitPendingTargets(
                                pending.spellInstanceId,
                                pending.casterSeatId,
                                pending.version,
                            ),
                        ),
                    ),
                )
            }

            assertSoftly {
                first.gsm.toByteArray().toList() shouldBe retry.gsm.toByteArray().toList()
                first.transition shouldBe retry.transition
                prior.identities.forgeIdToInstanceId shouldBe emptyMap()
                first.projectionSnapshot.zones
                    .getValue(ZoneIds.P1_HAND)
                    .contents shouldBe emptyList()
                first.projectionSnapshot.zones
                    .getValue(ZoneIds.P1_LIBRARY)
                    .contents shouldContainExactly listOf(cardId)
                annotationTypes.indexOf(AnnotationType.ObjectIdChanged) shouldBe
                    annotationTypes.indexOf(AnnotationType.ZoneTransfer_af5a) - 1
                changed.affectedIdsList shouldContainExactly listOf(100)
                transfer.affectedIdsList shouldContainExactly listOf(newCardId.value)
                newTurn.affectedIdsList shouldContainExactly listOf(1)
                selecting.affectedIdsList shouldContainExactly listOf(newCardId.value)
                submitted.affectedIdsList shouldContainExactly listOf(777)
                next.identities.forgeIdToInstanceId shouldContainKey FrameIdResolver.triggerStackAbilityForgeId(9)
                next.identities.forgeIdToInstanceId shouldContainKey FrameIdResolver.triggerStackAbilityForgeId(10)
                next.viewerCursors.getValue(0).previousSnapshot shouldBe first.projectionSnapshot
                next.viewerCursors.getValue(0).pendingSubmittedTargets shouldBe null
                first.transition.acknowledgements.pendingOrderMove shouldBe PromptFactKey(SeatId(1), 5)
                next.limboInstanceIds shouldBe setOf(100)
                next.protoZones[newCardId.value] shouldBe ZoneIds.P1_LIBRARY
                first.gsm.annotationsList.map { it.id } shouldContainExactly
                    (50 until 50 + first.gsm.annotationsCount).toList()
                next.persistentAnnotations.nextAnnotationId shouldBe 50 + first.gsm.annotationsCount
            }
        }

        test("order candidates are private objects even without a pending move") {
            val cardId = ForgeCardId(10)
            val snapshot = orderSnapshot(cardId)
            val result =
                StateProjectionCompiler.compileOneViewer(
                    compilerEnvironment(),
                    compilerInput(snapshot),
                    ProjectionState.initial(),
                    ViewerProjectionIntent.of(orderPrompt = OrderPromptProjection.of(listOf(cardId))),
                )
            val instanceId =
                result.transition.nextState.identities.forgeIdToInstanceId
                    .getValue(cardId)
                    .value
            val cardObject = result.gsm.gameObjectsList.single { it.instanceId == instanceId }

            assertSoftly {
                result.projectionSnapshot shouldBe snapshot
                cardObject.visibility shouldBe Visibility.Private
                cardObject.viewersList shouldContainExactly listOf(1)
                result.transition.acknowledgements.pendingOrderMove shouldBe null
            }
        }

        test("reservation-only supplement allocates identity and version mismatch leaves prior unchanged") {
            val prior = ProjectionState.initial()
            val input = compilerInput(GsmSnapshot.forTest(matchId = "compiler", gameStateId = 1))
            val reserved =
                StateProjectionCompiler.compileOneViewer(
                    compilerEnvironment(),
                    input,
                    prior,
                    ViewerProjectionIntent.of(listOf(ProjectionSupplement.ReserveTriggeredAbility(44))),
                )
            val mismatched =
                ViewerProjectionIntent.of(
                    listOf(ProjectionSupplement.SubmitPendingTargets(InstanceId(9), SeatId(1), version = 1)),
                )

            shouldThrow<IllegalStateException> {
                StateProjectionCompiler.compileOneViewer(compilerEnvironment(), input, prior, mismatched)
            }

            assertSoftly {
                reserved.transition.nextState.identities.forgeIdToInstanceId shouldContainKey
                    FrameIdResolver.triggerStackAbilityForgeId(44)
                reserved.gsm.annotationsList.any { it.typeList.contains(AnnotationType.PlayerSelectingTargets) } shouldBe false
                reserved.gsm.annotationsList.any { it.typeList.contains(AnnotationType.PlayerSubmittedTargets) } shouldBe false
                prior shouldBe ProjectionState.initial()
            }
        }
    })

private fun compilerEnvironment(): StateProjectionEnvironment {
    val cards = InMemoryCardRepository()
    return StateProjectionEnvironment(
        CardProtoBuilder(cards),
        MatchProjectionConfig(isBrawlOrCommander = false),
        ProjectionCardReferences(cards),
    )
}

private fun compilerInput(snapshot: GsmSnapshot): StateFrameInput =
    StateFrameInput(
        gameStateId = snapshot.gameStateId,
        snapshot = snapshot,
        previousSnapshot = null,
        events = FrameEventLog.EMPTY,
        promptFacts = PromptProjectionFacts(),
        updateType = GameStateUpdate.Send,
        viewingSeatId = 1,
        revealForSeat = null,
        effectFacts = EffectProjectionFacts(),
        mechanicSourceFacts = MechanicSourceFacts(),
        abilityExhaustionFacts = AbilityExhaustionFacts(),
        persistentFeedFacts = PersistentFeedFacts(),
    )

private fun orderSnapshot(cardId: ForgeCardId): GsmSnapshot =
    GsmSnapshot.forTest(
        matchId = "compiler",
        gameStateId = 1,
        objects =
            mapOf(
                cardId to CardSnapshot(cardId, "Ordered Card", 9001, SeatId(1), SeatId(1)),
            ),
        zones =
            linkedMapOf(
                ZoneIds.P1_HAND to
                    ZoneSnapshot(ZoneIds.P1_HAND, ZoneType.Hand, SeatId(1), Visibility.Private, listOf(cardId)),
                ZoneIds.P1_LIBRARY to
                    ZoneSnapshot(ZoneIds.P1_LIBRARY, ZoneType.Library, SeatId(1), Visibility.Hidden, emptyList()),
            ),
    )
