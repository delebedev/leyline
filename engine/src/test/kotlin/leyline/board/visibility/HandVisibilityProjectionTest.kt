package leyline.board.visibility

import forge.game.player.Player
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.RevealZone
import leyline.bridge.types.SeatId
import leyline.game.bundle.InvariantCheck
import leyline.game.bundle.InvariantChecker
import leyline.game.bundle.InvariantSelection
import leyline.game.event.FrameEventLog
import leyline.game.event.GameEvent
import leyline.game.mapping.StateFrameInput
import leyline.game.mapping.StateProjectionCompiler
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.AbilityExhaustionFacts
import leyline.game.state.MechanicSourceFacts
import leyline.game.state.PersistentFeedFacts
import leyline.game.state.ProjectionState
import leyline.game.state.ProjectionViewerRole
import leyline.game.state.PromptFactKey
import leyline.game.state.PromptProjectionFacts
import leyline.game.state.RevealStarted
import leyline.testkit.Board
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate
import wotc.mtgo.gre.external.messaging.Messages.Visibility

class HandVisibilityProjectionTest :
    BoardTest({
        for (publicReveal in listOf(false, true)) {
            test("reveal lifecycle annotations follow viewer visibility, public: $publicReveal") {
                val board = startWithBoard { _, _, ai -> addCard("Forest", ai, ZoneType.Hand) }
                val hand =
                    board.ai
                        .getZone(ZoneType.Hand)
                        .cards
                        .map { ForgeCardId(it.id) }
                val facts =
                    PromptProjectionFacts(
                        reveals =
                            listOf(
                                PromptProjectionFacts.RevealFact(
                                    PromptFactKey(SeatId(1), 1),
                                    RevealStarted(hand, SeatId(2), lookOnly = !publicReveal),
                                    hasPendingPrompt = true,
                                ),
                            ),
                    )
                val snapshot = handSnapshot(board, 1)
                val events =
                    FrameEventLog(listOf(GameEvent.CardsRevealed(hand, SeatId(2), SeatId(1), RevealZone.HAND, lookOnly = !publicReveal)))
                val chooser = projectHand(board, snapshot, 1, promptFacts = facts, events = events)
                val observer = projectHand(board, snapshot, 1, promptFacts = facts, role = ProjectionViewerRole.Observer, events = events)
                val owner = projectHand(board, snapshot, 2, promptFacts = facts, events = events)
                val ownerClosed = projectHand(board, handSnapshot(board, 2), 2, snapshot, owner.transition.nextState)
                val chooserProxy =
                    chooser.gsm.gameObjectsList
                        .single { it.type == GameObjectType.RevealedCard }
                        .instanceId
                val observerProxies =
                    observer.gsm.gameObjectsList
                        .filter { it.type == GameObjectType.RevealedCard }
                        .map { it.instanceId }
                val chooserClosed = projectHand(board, handSnapshot(board, 2), 1, snapshot, chooser.transition.nextState)
                val observerClosed =
                    projectHand(
                        board,
                        handSnapshot(board, 2),
                        1,
                        snapshot,
                        observer.transition.nextState,
                        role = ProjectionViewerRole.Observer,
                    )
                assertSoftly {
                    chooser.gsm.gameObjectsList
                        .single { it.type == GameObjectType.RevealedCard }
                        .visibility shouldBe
                        if (publicReveal) Visibility.Public else Visibility.Private
                    chooser.gsm.annotationsList
                        .single { AnnotationType.RevealedCardCreated in it.typeList }
                        .affectedIdsList shouldBe
                        listOf(chooserProxy)
                    chooserClosed.gsm.annotationsList
                        .single { AnnotationType.RevealedCardDeleted in it.typeList }
                        .affectedIdsList shouldBe
                        listOf(chooserProxy)
                    owner.gsm.annotationsList
                        .filter { AnnotationType.RevealedCardCreated in it.typeList }
                        .flatMap { it.affectedIdsList }
                        .size shouldBe
                        if (publicReveal) 1 else 0
                    ownerClosed.gsm.annotationsList
                        .filter { AnnotationType.RevealedCardDeleted in it.typeList }
                        .flatMap { it.affectedIdsList }
                        .size shouldBe
                        if (publicReveal) 1 else 0
                    observerProxies.size shouldBe if (publicReveal) 1 else 0
                    observer.gsm.annotationsList
                        .filter { AnnotationType.RevealedCardCreated in it.typeList }
                        .flatMap { it.affectedIdsList } shouldBe
                        observerProxies
                    observerClosed.gsm.annotationsList
                        .filter { AnnotationType.RevealedCardDeleted in it.typeList }
                        .flatMap { it.affectedIdsList } shouldBe
                        observerProxies
                    observer.gsm.zonesList
                        .filter { it.type == wotc.mtgo.gre.external.messaging.Messages.ZoneType.Revealed }
                        .flatMap { it.objectInstanceIdsList } shouldBe observerProxies
                    val checker =
                        InvariantChecker(InvariantSelection.only("observer reveal references", InvariantCheck.AnnotationReferences))
                    listOf(
                        observer.gsm,
                        observerClosed.gsm,
                    ).forEach { checker.process(GREToClientMessage.newBuilder().setGameStateMessage(it).build()) }
                    checker.violations.shouldBeEmpty()
                }
            }
        }

        test("public companion knowledge survives an overlapping private hand look") {
            val board = startWithBoard { _, _, ai -> addCard("Lurrus of the Dream-Den", ai, ZoneType.Hand) }
            val card = board.ai.getCardsIn(ZoneType.Hand).single()
            board.ai.getZone(ZoneType.Command).add(Player.createCompanionEffect(card))
            val hand = listOf(ForgeCardId(card.id))
            val initial = handSnapshot(board, 1)
            val baseline = projectHand(board, initial, 1, role = ProjectionViewerRole.Observer)
            val companionView = baseline.gsm.gameObjectsList.single { it.type == GameObjectType.RevealedCard }
            val facts =
                PromptProjectionFacts(
                    reveals =
                        listOf(
                            PromptProjectionFacts.RevealFact(
                                PromptFactKey(SeatId(1), 1),
                                RevealStarted(hand, SeatId(2), lookOnly = true),
                                hasPendingPrompt = true,
                            ),
                        ),
                )
            val overlapping =
                projectHand(
                    board,
                    handSnapshot(board, 2),
                    1,
                    initial,
                    baseline.transition.nextState,
                    promptFacts = facts,
                    role = ProjectionViewerRole.Observer,
                    events = FrameEventLog(listOf(GameEvent.CardsRevealed(hand, SeatId(2), SeatId(1), RevealZone.HAND, lookOnly = true))),
                )
            val retained =
                overlapping.transition.nextState.viewerCursors
                    .getValue(SeatId(1))
                    .fullState!!
            assertSoftly {
                retained.gameObjectsList.single { it.type == GameObjectType.RevealedCard } shouldBe companionView
                retained.zonesList.single { it.zoneId == ZoneIds.REVEALED_P2 }.objectInstanceIdsList shouldBe
                    listOf(companionView.instanceId)
                overlapping.gsm.diffDeletedInstanceIdsList.shouldBeEmpty()
            }
        }

        test("continuous hand permission grants identities and withdraws them from the same viewer") {
            val board = startPuzzleAtMain1(HAND_INSPECTION_PUZZLE)
            val hand =
                board.ai
                    .getZone(ZoneType.Hand)
                    .cards
                    .toList()
            hand.forEach { it.mayPlayerLook(board.human).shouldBeTrue() }
            val initial = handSnapshot(board, 1)
            val known = projectHand(board, initial, 1)
            val handIds = hand.map { board.instanceId(it.id) }.toSet()
            assertSoftly {
                known.gsm.gameObjectsList
                    .filter { it.instanceId in handIds }
                    .map { it.instanceId }
                    .toSet() shouldBe handIds
                known.gsm.gameObjectsList.filter { it.instanceId in handIds }.forEach {
                    it.visibility shouldBe Visibility.Private
                    it.viewersList.toSet() shouldBe setOf(1, 2)
                }
                known.gsm.zonesList
                    .single { it.zoneId == ZoneIds.P2_HAND }
                    .viewersList shouldBe listOf(2)
            }
            val source =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single { !it.isLand }
            board.game.action.moveToGraveyard(source, null)
            board.game.action.checkStateEffects(true)
            hand.forEach { it.mayPlayerLook(board.human).shouldBeFalse() }
            val withdrawn = projectHand(board, handSnapshot(board, 3), 1, initial, known.transition.nextState)
            assertSoftly {
                withdrawn.gsm.gameObjectsList
                    .filter { it.instanceId in handIds }
                    .map { it.instanceId }
                    .toSet() shouldBe handIds
                withdrawn.gsm.gameObjectsList.filter { it.instanceId in handIds }.forEach {
                    it.visibility shouldBe Visibility.Hidden
                    it.grpId shouldBe 0
                    it.viewersList.shouldBeEmpty()
                }
                withdrawn.gsm.diffDeletedInstanceIdsList
                    .filter { it in handIds }
                    .shouldBeEmpty()
                projectHand(board, handSnapshot(board, 3), 1)
                    .gsm.gameObjectsList
                    .filter { it.instanceId in handIds }
                    .shouldBeEmpty()
                projectHand(
                    board,
                    handSnapshot(board, 3),
                    2,
                ).gsm.gameObjectsList.filter { it.instanceId in handIds }.map { it.instanceId }.toSet() shouldBe
                    handIds
            }
        }

        for ((withdrawPermission, role) in listOf(
            true to ProjectionViewerRole.Player,
            false to ProjectionViewerRole.Player,
            true to ProjectionViewerRole.Observer,
        )) {
            test("closing a public hand reveal respects remaining inspection permission ($withdrawPermission, $role)") {
                val board = startPuzzleAtMain1(HAND_INSPECTION_PUZZLE.replace("Grizzly Bears", "Brazen Borrower"))
                val hand =
                    board.ai
                        .getZone(ZoneType.Hand)
                        .cards
                        .toList()
                val parentId = board.instanceId(hand.single { it.isAdventureCard }.id)
                val revealFacts =
                    PromptProjectionFacts(
                        reveals =
                            listOf(
                                PromptProjectionFacts.RevealFact(
                                    PromptFactKey(SeatId(1), 1),
                                    RevealStarted(hand.map { ForgeCardId(it.id) }, SeatId(2)),
                                    hasPendingPrompt = true,
                                ),
                            ),
                    )
                val initial = handSnapshot(board, 1)
                val revealed = projectHand(board, initial, 1, promptFacts = revealFacts, role = role)
                val family = revealed.gsm.gameObjectsList.filter { it.instanceId == parentId || it.parentId == parentId }
                family.size shouldBe 2
                family.forEach { it.visibility shouldBe Visibility.Public }
                if (withdrawPermission) {
                    val source =
                        board.human
                            .getZone(ZoneType.Battlefield)
                            .cards
                            .single { !it.isLand }
                    board.game.action.moveToGraveyard(source, null)
                    board.game.action.checkStateEffects(true)
                }
                val overlapping = handSnapshot(board, 2)
                val stillRevealed = projectHand(board, overlapping, 1, initial, revealed.transition.nextState, revealFacts, role)
                stillRevealed.transition.nextState.viewerCursors
                    .getValue(SeatId(1))
                    .fullState!!
                    .gameObjectsList
                    .single { it.instanceId == parentId }
                    .visibility shouldBe Visibility.Public
                val closed = projectHand(board, handSnapshot(board, 3), 1, overlapping, stillRevealed.transition.nextState, role = role)
                if (withdrawPermission) {
                    val concealed = closed.gsm.gameObjectsList.single { it.instanceId == parentId }
                    assertSoftly {
                        concealed.visibility shouldBe Visibility.Hidden
                        concealed.grpId shouldBe 0
                        concealed.viewersList.shouldBeEmpty()
                        if (role == ProjectionViewerRole.Observer) {
                            closed.transition.nextState.viewerCursors
                                .getValue(SeatId(1))
                                .fullState!!
                                .zonesList
                                .filter { it.zoneId == ZoneIds.P2_HAND }
                                .flatMap { it.objectInstanceIdsList }
                                .shouldBeEmpty()
                        }
                        closed.gsm.diffDeletedInstanceIdsList
                            .filter { it == parentId }
                            .shouldBeEmpty()
                        family.filter { it.parentId == parentId }.forEach {
                            (it.instanceId in closed.gsm.diffDeletedInstanceIdsList).shouldBeTrue()
                        }
                    }
                } else {
                    assertSoftly {
                        family.forEach { previouslyPublic ->
                            val restored = closed.gsm.gameObjectsList.single { it.instanceId == previouslyPublic.instanceId }
                            restored.visibility shouldBe Visibility.Private
                            restored.viewersList.toSet() shouldBe setOf(1, 2)
                        }
                        closed.gsm.diffDeletedInstanceIdsList
                            .filter { id -> family.any { it.instanceId == id } }
                            .shouldBeEmpty()
                    }
                }
            }
        }

        test("individual hand permissions conceal other slots and retire secondary faces") {
            val board = startPuzzleAtMain1(HAND_INSPECTION_PUZZLE.replace(";Telepathy", "").replace("Grizzly Bears", "Brazen Borrower"))
            val hand =
                board.ai
                    .getZone(ZoneType.Hand)
                    .cards
                    .toList()
            val permitted = hand.single { it.isAdventureCard }
            val concealed = hand.single { !it.isAdventureCard }
            val concealedSnapshot = handSnapshot(board, 1)
            val baseline = projectHand(board, concealedSnapshot, 1)
            baseline.gsm.gameObjectsList
                .filter { it.zoneId == ZoneIds.P2_HAND }
                .shouldBeEmpty()
            permitted.addMayLookTemp(board.human)
            val initial = handSnapshot(board, 2)
            val known = projectHand(board, initial, 1, concealedSnapshot, baseline.transition.nextState)
            val parentId = board.instanceId(permitted.id)
            val family = known.gsm.gameObjectsList.filter { it.instanceId == parentId || it.parentId == parentId }
            assertSoftly {
                family.size shouldBe 2
                family.forEach {
                    it.visibility shouldBe Visibility.Private
                    it.viewersList.toSet() shouldBe setOf(1, 2)
                }
                known.gsm.gameObjectsList
                    .filter { it.instanceId == board.instanceId(concealed.id) }
                    .shouldBeEmpty()
                baseline.gsm.zonesList
                    .single { it.zoneId == ZoneIds.P2_HAND }
                    .objectInstanceIdsCount shouldBe 2
            }
            permitted.removeMayLookTemp(board.human)
            val withdrawn = projectHand(board, handSnapshot(board, 3), 1, initial, known.transition.nextState)
            assertSoftly {
                withdrawn.gsm.gameObjectsList
                    .single { it.instanceId == parentId }
                    .visibility shouldBe Visibility.Hidden
                withdrawn.gsm.gameObjectsList
                    .single { it.instanceId == parentId }
                    .grpId shouldBe 0
                withdrawn.gsm.diffDeletedInstanceIdsList.toSet() shouldBe
                    family.filter { it.parentId == parentId }.map { it.instanceId }.toSet()
            }
        }
    })

private fun handSnapshot(
    board: Board,
    id: Int,
) = GsmSnapshot.capture(board.game, board.bridge, "hand", id)

private fun projectHand(
    board: Board,
    snapshot: GsmSnapshot,
    viewer: Int,
    previous: GsmSnapshot? = null,
    prior: ProjectionState = board.bridge.projectionStateSnapshot(),
    promptFacts: PromptProjectionFacts = PromptProjectionFacts(),
    role: ProjectionViewerRole = ProjectionViewerRole.Player,
    events: FrameEventLog = FrameEventLog.EMPTY,
): StateProjectionCompiler.Result =
    StateProjectionCompiler
        .compileViewers(
            board.bridge.stateProjectionEnvironment,
            prior,
            listOf(
                StateProjectionCompiler.ViewerInput(
                    StateFrameInput(
                        snapshot = snapshot,
                        gameStateId = snapshot.gameStateId,
                        viewingSeatId = viewer,
                        previousSnapshot = previous,
                        events = events,
                        updateType = GameStateUpdate.SendAndRecord,
                        revealForSeat = null,
                        effectFacts = board.bridge.materializeEffectProjectionFacts(),
                        mechanicSourceFacts = MechanicSourceFacts(),
                        persistentFeedFacts = PersistentFeedFacts(),
                        abilityExhaustionFacts = AbilityExhaustionFacts(),
                        promptFacts = promptFacts,
                    ),
                    role = role,
                ),
            ),
        ).viewers
        .single()
        .result

private val HAND_INSPECTION_PUZZLE =
    """
    [metadata]
    Name:Hand Inspection
    Goal:Survive
    Turns:5
    Difficulty:Easy
    Description:Inspect the opposing hand while permission remains active.
    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20
    humanbattlefield=Forest;Forest;Telepathy
    humanhand=Naturalize;Plains
    humanlibrary=Forest;Forest;Forest;Forest;Forest
    aihand=Grizzly Bears;Lightning Bolt
    ailibrary=Forest;Forest;Forest;Forest;Forest
    """.trimIndent()
