package leyline.match

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.PlayerLossCause
import leyline.game.annotations.AnnotationLossReason
import wotc.mtgo.gre.external.messaging.Messages.ResultReason

class MatchSessionLossReasonTest :
    FunSpec({
        tags(UnitTag)

        test("annotation loss reason follows Forge loss state for natural game endings") {
            assertSoftly {
                annotationLossReasonFor(ResultReason.Game_ae0a, PlayerLossCause.Poison) shouldBe AnnotationLossReason.Poison
                annotationLossReasonFor(ResultReason.Game_ae0a, PlayerLossCause.Milled) shouldBe
                    AnnotationLossReason.DrawFromEmptyLibrary
                annotationLossReasonFor(ResultReason.Game_ae0a, PlayerLossCause.LifeTotal) shouldBe
                    AnnotationLossReason.LifeTotal
            }
        }

        test("concede result reason takes precedence over Forge loss state") {
            annotationLossReasonFor(ResultReason.Concede, PlayerLossCause.Poison) shouldBe AnnotationLossReason.Concede
        }
    })
