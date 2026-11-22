package leyline.board.visibility

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.bridge.types.ForgeCardId
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.AbilityExhaustionFacts
import leyline.testkit.Board
import leyline.testkit.BoardTest
import leyline.testkit.StateMapperShell
import wotc.mtgo.gre.external.messaging.Messages.CardType
import wotc.mtgo.gre.external.messaging.Messages.SubType
import wotc.mtgo.gre.external.messaging.Messages.Visibility
import forge.card.CardType as ForgeCardType

class FaceDownProjectionTest :
    BoardTest({
        for (name in listOf("Forest", "Lumithread Field")) {
            test("ordinary face-down $name conceals identity in both viewer projections and restores it face up") {
                val board =
                    startWithBoard { _, human, _ ->
                        addCard(name, human, ZoneType.Battlefield)
                        addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    }
                val card = board.human.battlefield.card(name)
                card.turnFaceDown() shouldBe true
                val snapshot = GsmSnapshot.capture(board.game, board.bridge, "test", 1)
                val source = snapshot.objects.getValue(ForgeCardId(card.id))
                val iid = board.instanceId(card.id)
                val controller = faceDownProjection(board, snapshot, 1).gameObjectsList.single { it.instanceId == iid }
                val opponent = faceDownProjection(board, snapshot, 2).gameObjectsList.single { it.instanceId == iid }
                assertSoftly {
                    source.faceDownKind shouldBe null
                    source.isFaceDown shouldBe true
                    controller.grpId shouldBe source.grpId
                    controller.visibility shouldBe Visibility.Private
                    controller.viewersList shouldBe listOf(1)
                    opponent.grpId shouldBe 3
                    opponent.visibility shouldBe Visibility.Public
                    for (obj in listOf(controller, opponent)) {
                        obj.isFacedown shouldBe true
                        obj.overlayGrpId shouldBe 3
                        obj.name shouldBe 0
                        obj.cardTypesList shouldBe listOf(CardType.Creature)
                        obj.superTypesCount shouldBe 0
                        obj.subtypesCount shouldBe 0
                        obj.colorCount shouldBe 0
                        obj.uniqueAbilitiesCount shouldBe 0
                        obj.power.value shouldBe 2
                        obj.toughness.value shouldBe 2
                    }
                }
                card.turnFaceUp(false, null) shouldBe true
                val up = GsmSnapshot.capture(board.game, board.bridge, "test", 2)
                for (seat in listOf(1, 2)) {
                    val obj = faceDownProjection(board, up, seat).gameObjectsList.single { it.instanceId == iid }
                    assertSoftly {
                        obj.isFacedown shouldBe false
                        obj.grpId shouldBe source.grpId
                        obj.visibility shouldBe Visibility.Public
                        obj.name shouldNotBe 0
                        obj.cardTypesList shouldBe listOf(if (name == "Forest") CardType.Land_a80b else CardType.Enchantment)
                    }
                }
            }
        }

        test("an effect-created face-down Forest remains a noncreature without printed abilities or stats") {
            val board = startWithBoard { _, human, _ -> addCard("Grizzly Bears", human, ZoneType.Battlefield) }
            val card = board.human.battlefield.card("Grizzly Bears")
            card.faceDownState.setType(ForgeCardType(listOf("Land", "Forest"), false))
            card.turnFaceDown() shouldBe true
            val snapshot = GsmSnapshot.capture(board.game, board.bridge, "test", 1)
            for (seat in listOf(1, 2)) {
                val obj = faceDownProjection(board, snapshot, seat).gameObjectsList.single { it.instanceId == board.instanceId(card.id) }
                assertSoftly {
                    obj.isFacedown shouldBe true
                    obj.cardTypesList shouldBe listOf(CardType.Land_a80b)
                    obj.subtypesList shouldBe listOf(SubType.Forest)
                    obj.hasPower() shouldBe false
                    obj.hasToughness() shouldBe false
                    obj.uniqueAbilitiesList.map { it.grpId } shouldBe listOf(1005)
                    if (seat == 2) obj.grpId shouldBe 3
                }
            }
        }
    })

private fun faceDownProjection(
    board: Board,
    snapshot: GsmSnapshot,
    seat: Int,
) = StateMapperShell
    .buildFromSnapshot(
        snap = snapshot,
        gameStateId = snapshot.gameStateId,
        matchId = "test",
        bridge = board.bridge,
        viewingSeatId = seat,
        effectFacts = board.bridge.materializeEffectProjectionFacts(),
        abilityExhaustionFacts = AbilityExhaustionFacts(),
    ).gsm
