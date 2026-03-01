package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Reveal annotation pipeline: verifies that card reveals captured via
 * InteractivePromptBridge.recordReveal() produce RevealedCardCreated
 * annotations in the GSM.
 */
class RevealAnnotationTest :
    FunSpec({
        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("reveal produces RevealedCardCreated annotation") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Lightning Bolt", human, ZoneType.Hand)
            }
            val handCard = with(base) { game.humanPlayer.firstCardIn(ZoneType.Hand) }

            val gsm = base.captureAfterAction(b, game, counter) {
                b.promptBridge.recordReveal(listOf(handCard.id), 1)
            }

            val instanceId = b.getOrAllocInstanceId(handCard.id)
            val revealAnn = gsm.annotationOrNull(AnnotationType.RevealedCardCreated)
            revealAnn.shouldNotBeNull()
            (instanceId in revealAnn.affectedIdsList).shouldBeTrue()
        }

        test("multi card reveal produces multiple annotations") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Lightning Bolt", human, ZoneType.Hand)
                base.addCard("Giant Growth", human, ZoneType.Hand)
                base.addCard("Grizzly Bears", human, ZoneType.Hand)
            }
            val handCards = game.humanPlayer.getZone(ZoneType.Hand).cards.toList()

            val gsm = base.captureAfterAction(b, game, counter) {
                b.promptBridge.recordReveal(handCards.map { it.id }, 1)
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
