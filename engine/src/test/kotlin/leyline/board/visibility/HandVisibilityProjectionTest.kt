package leyline.board.visibility

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import leyline.game.mapping.StateProjectionCompiler
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.AbilityExhaustionFacts
import leyline.game.state.ProjectionState
import leyline.testkit.Board
import leyline.testkit.BoardTest
import leyline.testkit.StateMapperShell
import wotc.mtgo.gre.external.messaging.Messages.Visibility

class HandVisibilityProjectionTest :
    BoardTest({
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
                    .shouldBeEmpty()
                withdrawn.gsm.diffDeletedInstanceIdsList
                    .filter { it in handIds }
                    .toSet() shouldBe handIds
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
                    .filter { it.instanceId == parentId || it.parentId == parentId }
                    .shouldBeEmpty()
                withdrawn.gsm.diffDeletedInstanceIdsList.toSet() shouldBe family.map { it.instanceId }.toSet()
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
): StateProjectionCompiler.Result =
    StateMapperShell.buildFromSnapshot(
        snap = snapshot,
        gameStateId = snapshot.gameStateId,
        matchId = "hand",
        bridge = board.bridge,
        viewingSeatId = viewer,
        prev = previous,
        projectionState = prior,
        effectFacts = board.bridge.materializeEffectProjectionFacts(),
        abilityExhaustionFacts = AbilityExhaustionFacts(),
    )

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
