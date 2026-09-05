package leyline.game.mapping

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.InMemoryCardRepository
import leyline.game.data.CardProtoBuilder
import leyline.game.event.FrameEventLog
import leyline.game.event.GameEvent
import leyline.game.snapshot.CardSnapshot
import leyline.game.snapshot.GsmSnapshot
import leyline.game.snapshot.SeatSnapshot
import leyline.game.snapshot.StackEntry
import leyline.game.snapshot.StackSnapshot
import leyline.game.snapshot.ZoneSnapshot
import leyline.game.state.AbilityExhaustionFacts
import leyline.game.state.EffectProjectionFacts
import leyline.game.state.MechanicSourceFacts
import leyline.game.state.PendingSubmittedTargets
import leyline.game.state.PersistentFeedFacts
import leyline.game.state.ProjectionState
import leyline.game.state.ProjectionViewerRole
import leyline.game.state.PromptProjectionFacts
import leyline.game.state.ViewerProjectionCursor
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameStateType
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
                    supplements = supplementValues,
                    orderPrompt =
                        OrderPromptProjection.of(
                            candidates,
                            move = OrderZoneMoveFact.of(SeatId(1), moveCards, putOnTop = true),
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

        test("one shared plan renders distinct viewer baselines without renumbering Player output") {
            val previous = GsmSnapshot.forTest(matchId = "compiler", gameStateId = 4)
            val current = GsmSnapshot.forTest(matchId = "compiler", gameStateId = 5)
            val prior =
                ProjectionState.initial().copy(
                    viewerCursors =
                        mapOf(
                            SeatId(1) to ViewerProjectionCursor(previousSnapshot = previous),
                            SeatId(2) to ViewerProjectionCursor(previousSnapshot = null),
                        ),
                )
            val player = compilerInput(current, previous).copy(viewingSeatId = 1)
            val observer = compilerInput(current).copy(viewingSeatId = 2)
            val actions =
                ActionsAvailableReq
                    .newBuilder()
                    .addActions(Action.newBuilder().setActionType(ActionType.Pass))
                    .build()
            val onlyPlayer =
                StateProjectionCompiler.compileViewers(
                    compilerEnvironment(),
                    prior,
                    listOf(StateProjectionCompiler.ViewerInput(player, actions = actions)),
                )
            val both =
                StateProjectionCompiler.compileViewers(
                    compilerEnvironment(),
                    prior,
                    listOf(
                        StateProjectionCompiler.ViewerInput(player, actions = actions),
                        StateProjectionCompiler.ViewerInput(observer, role = ProjectionViewerRole.Observer),
                    ),
                )

            assertSoftly {
                both.viewers.map { it.seatId } shouldContainExactly listOf(SeatId(1), SeatId(2))
                both.viewers[0]
                    .result.gsm.type shouldBe GameStateType.Diff
                both.viewers[1]
                    .result.gsm.type shouldBe GameStateType.Full
                both.viewers[0]
                    .result.gsm.pendingMessageCount shouldBe 1
                both.viewers[0]
                    .result.gsm.actionsCount shouldBe 1
                both.viewers[1]
                    .result.gsm.pendingMessageCount shouldBe 0
                both.viewers[1]
                    .result.gsm.actionsCount shouldBe 0
                both.viewers[0]
                    .result.gsm
                    .toByteArray()
                    .toList() shouldBe
                    onlyPlayer.viewers
                        .single()
                        .result.gsm
                        .toByteArray()
                        .toList()
                both.transition.nextState.identities shouldBe onlyPlayer.transition.nextState.identities
                both.transition.nextState.revision shouldBe prior.revision + 1
                prior.viewerCursors[SeatId(1)]?.previousSnapshot shouldBe previous
                prior.viewerCursors[SeatId(2)]?.previousSnapshot shouldBe null
            }
        }

        test("Observer role redacts seat-private objects in Full and Diff") {
            val playerCard = ForgeCardId(10)
            val observerCard = ForgeCardId(20)
            val initial = privateHandsSnapshot(1, playerCard, observerCard, "Player card", "Observer card")
            val prior = ProjectionState.initial()
            val full =
                StateProjectionCompiler.compileViewers(
                    compilerEnvironment(),
                    prior,
                    listOf(
                        StateProjectionCompiler.ViewerInput(compilerInput(initial).copy(viewingSeatId = 1)),
                        StateProjectionCompiler.ViewerInput(
                            compilerInput(initial).copy(viewingSeatId = 2),
                            role = ProjectionViewerRole.Observer,
                        ),
                    ),
                )
            val playerId =
                full.transition.nextState.identities.forgeIdToInstanceId
                    .getValue(playerCard)
                    .value
            val changed = privateHandsSnapshot(2, playerCard, observerCard, "Player changed", "Observer changed")
            val diff =
                StateProjectionCompiler.compileViewers(
                    compilerEnvironment(),
                    full.transition.nextState,
                    listOf(
                        StateProjectionCompiler.ViewerInput(compilerInput(changed, initial).copy(viewingSeatId = 1)),
                        StateProjectionCompiler.ViewerInput(
                            compilerInput(changed, initial).copy(viewingSeatId = 2),
                            role = ProjectionViewerRole.Observer,
                        ),
                    ),
                )

            assertSoftly {
                full.viewers[0]
                    .result.gsm.gameObjectsList
                    .map { it.instanceId } shouldContainExactly listOf(playerId)
                full.viewers[1]
                    .result.gsm.gameObjectsList
                    .map { it.instanceId } shouldContainExactly emptyList()
                diff.viewers[0]
                    .result.gsm.gameObjectsList
                    .map { it.instanceId } shouldContainExactly listOf(playerId)
                diff.viewers[1]
                    .result.gsm.gameObjectsList
                    .map { it.instanceId } shouldContainExactly emptyList()
                full.viewers[1]
                    .result.gsm.zonesList
                    .filter { it.visibility == Visibility.Private }
                    .flatMap { it.objectInstanceIdsList } shouldContainExactly emptyList()
                diff.viewers[1]
                    .result.gsm.zonesList
                    .filter { it.visibility == Visibility.Private }
                    .flatMap { it.objectInstanceIdsList } shouldContainExactly emptyList()
            }
        }

        test("Seat observer sees its own hand and both hand counts in Full and Diff") {
            val playerCard = ForgeCardId(10)
            val observerCard = ForgeCardId(20)
            val initial = privateHandsSnapshot(1, playerCard, observerCard, "Player card", "Observer card")
            val prior = ProjectionState.initial()
            val full =
                StateProjectionCompiler.compileViewers(
                    compilerEnvironment(),
                    prior,
                    listOf(
                        StateProjectionCompiler.ViewerInput(compilerInput(initial).copy(viewingSeatId = 1)),
                        StateProjectionCompiler.ViewerInput(
                            compilerInput(initial).copy(viewingSeatId = 2),
                            role = ProjectionViewerRole.SeatObserver,
                        ),
                    ),
                )
            val playerId =
                full.transition.nextState.identities.forgeIdToInstanceId
                    .getValue(playerCard)
                    .value
            val observerId =
                full.transition.nextState.identities.forgeIdToInstanceId
                    .getValue(observerCard)
                    .value
            val changed = privateHandsSnapshot(2, playerCard, observerCard, "Player changed", "Observer changed")
            val diff =
                StateProjectionCompiler.compileViewers(
                    compilerEnvironment(),
                    full.transition.nextState,
                    listOf(
                        StateProjectionCompiler.ViewerInput(compilerInput(changed, initial).copy(viewingSeatId = 1)),
                        StateProjectionCompiler.ViewerInput(
                            compilerInput(changed, initial).copy(viewingSeatId = 2),
                            role = ProjectionViewerRole.SeatObserver,
                        ),
                    ),
                )

            assertSoftly {
                full.viewers[0]
                    .result.gsm.gameObjectsList
                    .map { it.instanceId } shouldContainExactly listOf(playerId)
                full.viewers[1]
                    .result.gsm.gameObjectsList
                    .map { it.instanceId } shouldContainExactly listOf(observerId)
                diff.viewers[0]
                    .result.gsm.gameObjectsList
                    .map { it.instanceId } shouldContainExactly listOf(playerId)
                diff.viewers[1]
                    .result.gsm.gameObjectsList
                    .map { it.instanceId } shouldContainExactly listOf(observerId)
                full.viewers[1]
                    .result.gsm.zonesList
                    .filter { it.visibility == Visibility.Private }
                    .flatMap { it.objectInstanceIdsList } shouldContainExactly listOf(playerId, observerId)
                diff.transition.nextState.viewerCursors
                    .getValue(SeatId(2))
                    .fullState!!
                    .zonesList
                    .filter { it.visibility == Visibility.Private }
                    .flatMap { it.objectInstanceIdsList } shouldContainExactly listOf(playerId, observerId)
                full.viewers[1]
                    .result.gsm.actionsCount shouldBe 0
                diff.viewers[1]
                    .result.gsm.actionsCount shouldBe 0
            }
        }

        test("one compile stages order move then supplements and clears exact submitted targets") {
            val cardId = ForgeCardId(10)
            val sourceId = ForgeCardId(20)
            val pending = PendingSubmittedTargets(InstanceId(777), SeatId(1), version = 3)
            val prior =
                ProjectionState
                    .initial()
                    .copy(viewerCursors = mapOf(SeatId(1) to ViewerProjectionCursor(pendingSubmittedTargets = pending)))
            val input = compilerInput(orderSnapshot(cardId))
            val intent =
                ViewerProjectionIntent.of(
                    supplements =
                        listOf(
                            ProjectionSupplement.NewTurnStarted,
                            ProjectionSupplement.PlayerSelectingTargets(cardId, SeatId(1), stackAbilityForgeId = 9),
                            ProjectionSupplement.ReserveTriggeredAbility(10),
                            ProjectionSupplement.SubmitPendingTargets(pending.spellInstanceId, pending.casterSeatId, pending.version),
                        ),
                    orderPrompt =
                        OrderPromptProjection.of(
                            candidateForgeIds = listOf(cardId),
                            sourceForgeId = sourceId,
                            move = OrderZoneMoveFact.of(SeatId(1), listOf(cardId), putOnTop = true),
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
                selecting.affectedIdsList shouldContainExactly
                    listOf(
                        next.identities.forgeIdToInstanceId
                            .getValue(FrameIdResolver.triggerStackAbilityForgeId(9))
                            .value,
                    )
                submitted.affectedIdsList shouldContainExactly listOf(777)
                next.identities.forgeIdToInstanceId shouldContainKey FrameIdResolver.triggerStackAbilityForgeId(9)
                next.identities.forgeIdToInstanceId shouldContainKey FrameIdResolver.triggerStackAbilityForgeId(10)
                next.viewerCursors.getValue(SeatId(1)).previousSnapshot shouldBe first.projectionSnapshot
                next.viewerCursors.getValue(SeatId(1)).pendingSubmittedTargets shouldBe null
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

        test("admitted activation aliases its exact root while an older sibling stays untouched") {
            val sourceId = ForgeCardId(10)
            val olderAbilityId = 30
            val newRootAbilityId = 14
            val newAdmittedAbilityId = 40
            val older = stackAbility(sourceId, olderAbilityId)
            val newRoot = stackAbility(sourceId, newRootAbilityId)
            val newAdmitted = stackAbility(sourceId, newAdmittedAbilityId)
            val previous = stackAbilitySnapshot(1, sourceId, listOf(older, newRoot))
            val current = stackAbilitySnapshot(2, sourceId, listOf(older, newAdmitted))
            val priorEditor = ProjectionState.initial().editor()
            val olderIid =
                priorEditor.identities.getOrAlloc(FrameIdResolver.triggerStackAbilityForgeId(olderAbilityId))
            val newRootIid =
                priorEditor.identities.getOrAlloc(FrameIdResolver.triggerStackAbilityForgeId(newRootAbilityId))
            val prior = priorEditor.freeze()
            val input =
                compilerInput(
                    snapshot = current,
                    previousSnapshot = previous,
                    events =
                        FrameEventLog(
                            listOf(
                                GameEvent.SpellCast(
                                    cardId = sourceId,
                                    seatId = SeatId(1),
                                    isAbility = true,
                                    abilityForgeId = newAdmittedAbilityId,
                                    abilityGrpId = newAdmitted.grpId,
                                    rootAbilityForgeId = newRootAbilityId,
                                    stackAbilityForgeId = newAdmittedAbilityId,
                                ),
                            ),
                        ),
                )

            val next = StateProjectionCompiler.compileOneViewer(compilerEnvironment(), input, prior).transition.nextState

            assertSoftly {
                next.identities.forgeIdToInstanceId.getValue(
                    FrameIdResolver.triggerStackAbilityForgeId(olderAbilityId),
                ) shouldBe olderIid
                next.identities.forgeIdToInstanceId.getValue(
                    FrameIdResolver.triggerStackAbilityForgeId(newAdmittedAbilityId),
                ) shouldBe newRootIid
                olderIid shouldNotBe newRootIid
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

private fun compilerInput(
    snapshot: GsmSnapshot,
    previousSnapshot: GsmSnapshot? = null,
    events: FrameEventLog = FrameEventLog.EMPTY,
): StateFrameInput =
    StateFrameInput(
        gameStateId = snapshot.gameStateId,
        snapshot = snapshot,
        previousSnapshot = previousSnapshot,
        events = events,
        promptFacts = PromptProjectionFacts(),
        updateType = GameStateUpdate.Send,
        viewingSeatId = 1,
        revealForSeat = null,
        effectFacts = EffectProjectionFacts(),
        mechanicSourceFacts = MechanicSourceFacts(),
        abilityExhaustionFacts = AbilityExhaustionFacts(),
        persistentFeedFacts = PersistentFeedFacts(),
    )

private fun stackAbility(
    sourceId: ForgeCardId,
    abilityId: Int,
) = StackEntry(
    forgeCardId = sourceId,
    controller = SeatId(1),
    owner = SeatId(1),
    grpId = 9002,
    sourceCardGrpId = 9001,
    isSpell = false,
    isActivatedAbility = true,
    targets = emptyList(),
    forgeAbilityId = abilityId,
)

private fun stackAbilitySnapshot(
    gameStateId: Int,
    sourceId: ForgeCardId,
    entries: List<StackEntry>,
): GsmSnapshot =
    GsmSnapshot.forTest(
        matchId = "compiler",
        gameStateId = gameStateId,
        objects = mapOf(sourceId to CardSnapshot(sourceId, "Ability Source", 9001, SeatId(1), SeatId(1))),
        zones =
            linkedMapOf(
                ZoneIds.BATTLEFIELD to
                    ZoneSnapshot(ZoneIds.BATTLEFIELD, ZoneType.Battlefield, null, Visibility.Public, listOf(sourceId)),
                ZoneIds.STACK to ZoneSnapshot(ZoneIds.STACK, ZoneType.Stack, null, Visibility.Public, emptyList()),
                ZoneIds.LIMBO to ZoneSnapshot(ZoneIds.LIMBO, ZoneType.Limbo, null, Visibility.Public, emptyList()),
            ),
        stack = StackSnapshot(entries),
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

private fun privateHandsSnapshot(
    gameStateId: Int,
    playerCard: ForgeCardId,
    observerCard: ForgeCardId,
    playerName: String,
    observerName: String,
): GsmSnapshot =
    GsmSnapshot.forTest(
        matchId = "compiler",
        gameStateId = gameStateId,
        seats =
            listOf(
                SeatSnapshot(SeatId(1), 20, 20, 7),
                SeatSnapshot(SeatId(2), 20, 20, 7),
            ),
        objects =
            mapOf(
                playerCard to CardSnapshot(playerCard, playerName, 9001, SeatId(1), SeatId(1)),
                observerCard to CardSnapshot(observerCard, observerName, 9002, SeatId(2), SeatId(2)),
            ),
        zones =
            linkedMapOf(
                ZoneIds.P1_HAND to
                    ZoneSnapshot(ZoneIds.P1_HAND, ZoneType.Hand, SeatId(1), Visibility.Private, listOf(playerCard)),
                ZoneIds.P2_HAND to
                    ZoneSnapshot(ZoneIds.P2_HAND, ZoneType.Hand, SeatId(2), Visibility.Private, listOf(observerCard)),
            ),
    )
