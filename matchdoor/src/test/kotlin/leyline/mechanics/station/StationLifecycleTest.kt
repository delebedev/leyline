package leyline.mechanics.station

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

class StationLifecycleTest :
    SessionTest({
        test("Lumen-Class Frigate station resolves with shared Station ability grpId") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Lumen-Class Frigate;Grizzly Bears;Walking Corpse
                humanlibrary=Plains;Plains;Plains
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
                name = "Station Lumen-Class Frigate",
                validating = true,
            )

            activateAbility("Lumen-Class Frigate").shouldBeTrue()
            val bearIid = human.battlefield.iid("Grizzly Bears")

            val slice = after { respondToEffectCost(listOf(bearIid)) }
            val resolutionGsm =
                slice.messages.gameStateMessages().last { gsm ->
                    gsm.annotationsList.any { AnnotationType.ResolutionStart in it.typeList }
                }

            resolutionGsm.annotation(AnnotationType.ResolutionStart).detailUint("grpid") shouldBe 373
            resolutionGsm.annotation(AnnotationType.ResolutionComplete).detailUint("grpid") shouldBe 373
            resolutionGsm.annotation(AnnotationType.CounterAdded).detailInt("transaction_amount") shouldBe 2
            resolutionGsm.persistentAnnotation(AnnotationType.Counter_803b).detailInt("count") shouldBe 2
            val threshold12 =
                resolutionGsm.persistentAnnotationsList.first {
                    AnnotationType.AbilityWordActive in it.typeList && it.detailInt("threshold") == 12
                }
            threshold12.detailInt("AbilityGrpId") shouldBe 60024
            resolutionGsm.gameObjectsList.first { it.instanceId == bearIid }.isTapped.shouldBeTrue()
            resolutionGsm.annotationsList.map { it.typeList.first() } shouldContain AnnotationType.TappedUntappedPermanent
        }
    })
