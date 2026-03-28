package leyline.conformance

import forge.card.CardStateName
import forge.game.event.GameEventCardStatsChanged
import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class DfcTransformTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("transform emits Qualification pAnn for Menace on back face") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Concealing Curtains", human, ZoneType.Battlefield)
            }
            val card = game.humanPlayer.getZone(ZoneType.Battlefield).cards
                .first { it.name == "Concealing Curtains" }

            // Prime the collector's backside cache with front-face state
            game.fireEvent(GameEventCardStatsChanged(card))
            // Drain initial events via baseline snapshot
            base.stateOnlyDiff(game, b, counter)

            // Simulate transform to back face and capture the diff GSM
            val gsm = base.captureAfterAction(b, game, counter) {
                card.setState(CardStateName.Backside, true)
                card.setBackSide(true)
                game.fireEvent(GameEventCardStatsChanged(card))
            }

            val qualAnns = gsm.persistentAnnotationsList.filter {
                AnnotationType.Qualification in it.typeList
            }
            qualAnns.shouldNotBeEmpty()
            val menaceAnn = qualAnns.first()
            menaceAnn.detailUint("grpid") shouldBe 142 // Menace keyword grpId
            menaceAnn.detailUint("QualificationType") shouldBe 40
        }
    })
