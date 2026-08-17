package leyline.mechanics.station

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.annotation
import leyline.testkit.detailInt
import leyline.testkit.detailUint
import leyline.testkit.gameStateMessages
import leyline.testkit.persistentAnnotation
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import leyline.testkit.after

class StationLifecycleTest :
    SessionTest({
        session(
            "Lumen-Class Frigate station resolves with shared Station ability grpId",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Lumen-Class Frigate;Grizzly Bears;Walking Corpse
                humanlibrary=Plains;Plains;Plains
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
            validating = true,
        ) {
            activateAbility("Lumen-Class Frigate").shouldBeTrue()
            val bearIid = human.battlefield.iid("Grizzly Bears")

            val slice = after { respondToEffectCost(listOf(bearIid)) }
            val gsms = slice.messages.gameStateMessages()
            val tapGsm =
                gsms.first { gsm ->
                    gsm.annotationsList.any { AnnotationType.TappedUntappedPermanent in it.typeList }
                }
            val counterGsm =
                gsms.first { gsm ->
                    gsm.annotationsList.any { AnnotationType.CounterAdded in it.typeList }
                }
            val resolutionGsm =
                gsms.last { gsm ->
                    gsm.annotationsList.any { AnnotationType.ResolutionStart in it.typeList }
                }

            val threshold12 =
                counterGsm.persistentAnnotationsList.first {
                    AnnotationType.AbilityWordActive in it.typeList && it.detailInt("threshold") == 12
                }
            assertSoftly {
                resolutionGsm.annotation(AnnotationType.ResolutionStart).detailUint("grpid") shouldBe 373
                resolutionGsm.annotation(AnnotationType.ResolutionComplete).detailUint("grpid") shouldBe 373
                counterGsm.annotation(AnnotationType.CounterAdded).detailInt("transaction_amount") shouldBe 2
                counterGsm.persistentAnnotation(AnnotationType.Counter_803b).detailInt("count") shouldBe 2
                threshold12.detailInt("AbilityGrpId") shouldBe 60024
                tapGsm.gameObjectsList
                    .first { it.instanceId == bearIid }
                    .isTapped
                    .shouldBeTrue()
                tapGsm.annotationsList.map { it.typeList.first() } shouldContain AnnotationType.TappedUntappedPermanent
            }
        }
    })
