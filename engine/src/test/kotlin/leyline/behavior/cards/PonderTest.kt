package leyline.behavior.cards

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.game.mapping.PromptIds
import leyline.testkit.SessionTest
import leyline.testkit.after
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class PonderTest :
    SessionTest({
        for (shuffle in listOf(false, true)) {
            session(
                "Ponder ${if (shuffle) "accepts" else "declines"} shuffle after ordering and draws before fresh priority",
                seed = 42L,
                puzzle = """
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20
                    humanhand=Ponder;Swamp
                    humanbattlefield=Island
                    humanlibrary=Forest;Mountain;Plains;Swamp;Island;Wastes
                    ailibrary=Island;Island;Island;Island
                    """,
                turns = 4,
            ) {
                val initialLibrary = human.getZone(ZoneType.Library).cards.map { it.name }
                holdNextOptionalAction()
                castSpellByName("Ponder") shouldBe true
                passUntil(maxPasses = 4) { allMessages.any { it.hasOrderReq() } } shouldBe true
                val order = allMessages.last { it.hasOrderReq() }
                val orderedIds = order.orderReq.idsList.reversed()
                val orderedNames = orderedIds.map(::cardName)
                respondToOrder(orderedIds)
                val choice = allMessages.last()
                assertSoftly {
                    choice.hasOptionalActionMessage() shouldBe true
                    choice.prompt.promptId shouldBe PromptIds.OPTIONAL_SHUFFLE
                    cardName(choice.optionalActionMessage.sourceId) shouldBe "Ponder"
                    human.getZone(ZoneType.Hand).cards.map { it.name } shouldBe listOf("Swamp")
                    human.getZone(ZoneType.Library).cards.map { it.name } shouldBe orderedNames + initialLibrary.drop(3)
                }

                val resolved = after { respondToOptionalAction(accept = shuffle) }.messages
                val drawn =
                    human
                        .getZone(ZoneType.Hand)
                        .cards
                        .last()
                        .name
                val remaining = human.getZone(ZoneType.Library).cards.map { it.name }
                val completedOrder = listOf(drawn) + remaining
                val shuffled = resolved.flatMap { it.gameStateMessage.annotationsList }.count { AnnotationType.Shuffle in it.typeList }
                assertSoftly {
                    completedOrder.sorted() shouldBe initialLibrary.sorted()
                    shuffled shouldBe if (shuffle) 1 else 0
                    human.getZone(ZoneType.Graveyard).cards.map { it.name } shouldBe listOf("Ponder")
                    resolved.any { it.hasActionsAvailableReq() } shouldBe true
                }
                if (shuffle) {
                    completedOrder shouldNotBe orderedNames + initialLibrary.drop(3)
                } else {
                    drawn shouldBe orderedNames.first()
                    remaining shouldBe orderedNames.drop(1) + initialLibrary.drop(3)
                }
                playLand("Swamp") shouldBe true
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .map { it.name }
                    .sorted() shouldBe listOf("Island", "Swamp")
            }
        }
    })
