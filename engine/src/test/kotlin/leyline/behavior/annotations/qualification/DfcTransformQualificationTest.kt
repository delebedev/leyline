package leyline.behavior.annotations.qualification

import forge.card.CardStateName
import forge.game.event.GameEventCardStatsChanged
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import leyline.testkit.BoardTest
import leyline.testkit.BundleBuilderTestSupport
import leyline.testkit.detailUint
import leyline.testkit.gsmOrNull
import leyline.testkit.humanPlayer
import leyline.testkit.persistentAnnotation
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class DfcTransformQualificationTest :
    BoardTest({

        test("transform emits Qualification pAnn for Menace on back face") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Concealing Curtains", human, ZoneType.Battlefield)
                }
            val card =
                board.game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.name == "Concealing Curtains" }

            board.game.fireEvent(GameEventCardStatsChanged(card))
            BundleBuilderTestSupport.stateOnly(bundleBuilder(board.bridge), board.bridge, board.game, board.counter).gsmOrNull
                ?: error("front-face state diff returned no GSM")

            val gsm =
                board.snapshotDiff {
                    card.setState(CardStateName.Backside, true)
                    card.setBackSide(true)
                    board.game.fireEvent(GameEventCardStatsChanged(card))
                }

            val menaceAnn = gsm.persistentAnnotation(AnnotationType.Qualification)
            assertSoftly {
                menaceAnn.detailUint("grpid") shouldBe 142
                menaceAnn.detailUint("QualificationType") shouldBe 40
            }
        }
    })
