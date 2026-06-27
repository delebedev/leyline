package leyline.match

import forge.game.player.GameLossReason
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.annotations.AnnotationLossReason
import wotc.mtgo.gre.external.messaging.Messages.ResultReason

class MatchSessionLossReasonTest :
    FunSpec({
        tags(UnitTag)

        test("annotation loss reason follows Forge loss state for natural game endings") {
            assertSoftly {
                annotationLossReasonFor(ResultReason.Game_ae0a, GameLossReason.Poisoned) shouldBe AnnotationLossReason.Poison
                annotationLossReasonFor(ResultReason.Game_ae0a, GameLossReason.Milled) shouldBe
                    AnnotationLossReason.DrawFromEmptyLibrary
                annotationLossReasonFor(ResultReason.Game_ae0a, GameLossReason.LifeReachedZero) shouldBe
                    AnnotationLossReason.LifeTotal
            }
        }

        test("concede result reason takes precedence over Forge loss state") {
            annotationLossReasonFor(ResultReason.Concede, GameLossReason.Poisoned) shouldBe AnnotationLossReason.Concede
        }
    })
