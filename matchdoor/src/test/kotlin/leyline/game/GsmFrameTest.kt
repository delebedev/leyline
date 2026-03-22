package leyline.game

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.Phase
import wotc.mtgo.gre.external.messaging.Messages.Step

class GsmFrameTest :
    FunSpec({

        tags(UnitTag)

        test("turnInfo builds correct proto from frame fields") {
            val frame = GsmFrame(
                activeSeat = 1,
                prioritySeat = 2,
                turnNumber = 3,
                phase = Phase.Main1_a549,
                step = Step.None_a2cb,
            )

            val ti = frame.turnInfo()
            ti.activePlayer shouldBe 1
            ti.priorityPlayer shouldBe 2
            ti.decisionPlayer shouldBe 2
            ti.turnNumber shouldBe 3
            ti.phase shouldBe Phase.Main1_a549
            ti.step shouldBe Step.None_a2cb
        }

        test("phaseAnnotation produces PhaseOrStepModified with supplied ID") {
            val frame = GsmFrame(
                activeSeat = 2,
                prioritySeat = 1,
                turnNumber = 1,
                phase = Phase.Combat_a549,
                step = Step.DeclareAttack_a2cb,
            )

            var nextId = 100
            val ann = frame.phaseAnnotation { nextId++ }

            ann.id shouldBe 100
            ann.affectedIdsList shouldBe listOf(2)
            ann.typeList.any { it == AnnotationType.PhaseOrStepModified } shouldBe true
        }
    })
