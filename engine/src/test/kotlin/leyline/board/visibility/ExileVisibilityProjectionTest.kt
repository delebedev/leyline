package leyline.board.visibility

import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.AbilityExhaustionFacts
import leyline.game.state.ProjectionState
import leyline.testkit.Board
import leyline.testkit.BoardTest
import leyline.testkit.ClientAccumulator
import leyline.testkit.StateMapperShell
import wotc.mtgo.gre.external.messaging.Messages.CardType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.Visibility

class ExileVisibilityProjectionTest :
    BoardTest({
        for (cardName in listOf("Centaur Courser", "Unsummon", "Brazen Borrower")) {
            for (mayLook in listOf(false, true)) {
                test("face-down opponent exile reveals identity only to its inspection grantee: $cardName $mayLook") {
                    lateinit var exiled: Card
                    val board =
                        startWithBoard { _, _, ai ->
                            exiled = addCard(cardName, ai, ZoneType.Exile)
                        }
                    val originalGrpId = board.bridge.cardRepository.findGrpIdByName(cardName)!!
                    exiled.turnFaceDown(true)
                    if (mayLook) exiled.addMayLookFaceDownExile(board.human)
                    val snapshot = GsmSnapshot.capture(board.game, board.bridge, "test", 1)
                    assertSoftly {
                        for (seat in listOf(1, 2)) {
                            val gsm = projectExile(board, snapshot, seat).gsm
                            val projected = gsm.gameObjectsList.single { it.instanceId == board.instanceId(exiled.id) }
                            projected.grpId shouldBe if (mayLook && seat == 1) originalGrpId else 3
                            projected.isFacedown shouldBe true
                            projected.ownerSeatId shouldBe 2
                            projected.viewersList shouldBe if (mayLook) listOf(1) else emptyList()
                            exiled.isFaceDown shouldBe true
                            if (mayLook && seat == 1) {
                                projected.visibility shouldBe Visibility.Private
                                if (cardName == "Centaur Courser") {
                                    projected.power.value shouldBe 3
                                    projected.toughness.value shouldBe 3
                                }
                                if (cardName == "Unsummon") projected.cardTypesList shouldBe listOf(CardType.Instant)
                                if (cardName == "Brazen Borrower") {
                                    gsm.gameObjectsList.count { it.parentId == projected.instanceId } shouldBe 1
                                }
                            } else {
                                projected.visibility shouldBe Visibility.Public
                                projected.cardTypesList.shouldBeEmpty()
                                projected.uniqueAbilitiesList.shouldBeEmpty()
                                gsm.gameObjectsList.filter { it.parentId == projected.instanceId }.shouldBeEmpty()
                            }
                        }
                    }
                    exiled.turnFaceUp(null)
                    val revealed = GsmSnapshot.capture(board.game, board.bridge, "test", 2)
                    for (seat in listOf(1, 2)) {
                        val gsm = projectExile(board, revealed, seat, snapshot).gsm
                        val projected = gsm.gameObjectsList.single { it.instanceId == board.instanceId(exiled.id) }
                        assertSoftly {
                            projected.grpId shouldBe originalGrpId
                            projected.isFacedown shouldBe false
                            projected.visibility shouldBe Visibility.Public
                            projected.viewersList.shouldBeEmpty()
                        }
                    }
                }
            }
        }
        for (mayLook in listOf(false, true)) {
            test("concealing known linked exile removes every inaccessible child from accumulated state: $mayLook") {
                lateinit var exiled: Card
                val board = startWithBoard { _, _, ai -> exiled = addCard("Brazen Borrower", ai, ZoneType.Exile) }
                val before = GsmSnapshot.capture(board.game, board.bridge, "test", 1)
                val initial = (1..2).associateWith { seat -> projectExile(board, before, seat) }
                exiled.turnFaceDown(true)
                if (mayLook) exiled.addMayLookFaceDownExile(board.human)
                val after = GsmSnapshot.capture(board.game, board.bridge, "test", 2)
                for (seat in 1..2) {
                    val previous = initial.getValue(seat)
                    val diff = projectExile(board, after, seat, before, previous.transition.nextState).gsm
                    val full = projectExile(board, after, seat, state = previous.transition.nextState).gsm
                    val accumulator = ClientAccumulator()
                    accumulator.seedFull(previous.gsm)
                    accumulator.process(GREToClientMessage.newBuilder().setGameStateMessage(diff).build())
                    accumulator.objects shouldBe full.gameObjectsList.associateBy { it.instanceId }
                }
            }
        }
    })

private fun projectExile(
    board: Board,
    snapshot: GsmSnapshot,
    seat: Int,
    previous: GsmSnapshot? = null,
    state: ProjectionState = board.bridge.projectionStateSnapshot(),
) = StateMapperShell
    .buildFromSnapshot(
        snap = snapshot,
        gameStateId = snapshot.gameStateId,
        matchId = "test",
        bridge = board.bridge,
        viewingSeatId = seat,
        prev = previous,
        projectionState = state,
        effectFacts = board.bridge.materializeEffectProjectionFacts(),
        abilityExhaustionFacts = AbilityExhaustionFacts(),
    )
