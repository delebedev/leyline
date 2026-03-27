package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import leyline.ConformanceTag
import leyline.bridge.ForgeCardId
import leyline.bridge.SeatId
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Reveal annotation pipeline: verifies that card reveals captured via
 * InteractivePromptBridge.recordReveal() produce RevealedCardCreated
 * annotations in the GSM.
 */
class RevealAnnotationTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("reveal produces RevealedCardCreated annotation") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Lightning Bolt", human, ZoneType.Hand)
            }
            val handCard = with(base) { game.humanPlayer.firstCardIn(ZoneType.Hand) }

            val gsm = base.captureAfterAction(b, game, counter) {
                b.promptBridge(1).recordReveal(listOf(ForgeCardId(handCard.id)), SeatId(1))
            }

            val instanceId = b.getOrAllocInstanceId(ForgeCardId(handCard.id))
            val revealAnn = gsm.annotationOrNull(AnnotationType.RevealedCardCreated)
            revealAnn.shouldNotBeNull()
            revealAnn.affectedIdsList.contains(instanceId.value).shouldBeTrue()
        }

        test("multi card reveal produces multiple annotations") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Lightning Bolt", human, ZoneType.Hand)
                base.addCard("Giant Growth", human, ZoneType.Hand)
                base.addCard("Grizzly Bears", human, ZoneType.Hand)
            }
            val handCards = game.humanPlayer.getZone(ZoneType.Hand).cards.toList()

            val gsm = base.captureAfterAction(b, game, counter) {
                b.promptBridge(1).recordReveal(handCards.map { ForgeCardId(it.id) }, SeatId(1))
            }

            val revealAnns = gsm.annotationsList.filter {
                AnnotationType.RevealedCardCreated in it.typeList
            }
            revealAnns shouldHaveAtLeastSize handCards.size
        }

        test("no reveal produces no annotation") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Forest", human, ZoneType.Hand)
            }

            val gsm = base.captureAfterAction(b, game, counter) { /* no reveal */ }

            gsm.annotationOrNull(AnnotationType.RevealedCardCreated).shouldBeNull()
        }
    })
