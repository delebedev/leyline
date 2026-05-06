package leyline.game.mapping

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.annotations.AnnotationConstants
import leyline.game.codes.DetailKeys
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Unit pins for the game-scope Day/Night state-tail diff. Covers the three
 * shapes (neither→Day, neither→Night, Day↔Night flip) plus the no-op
 * stay-in-state case.
 */
class DayNightTransientsTest :
    FunSpec({
        tags(UnitTag)

        fun designationType(ann: AnnotationInfo): Int =
            ann.detailsList
                .first { it.key == DetailKeys.DESIGNATION_TYPE }
                .getValueInt32(0)

        test("neither -> Day: single GainDesignation lite, no Lose") {
            val out = mutableListOf<AnnotationInfo>()
            insertDayNightDesignationTransients(out, prevDayTime = null, curDayTime = false)

            out shouldHaveSize 1
            out[0].typeList shouldContainExactly listOf(AnnotationType.GainDesignation)
            out[0].affectedIdsList shouldContainExactly listOf(0)
            out[0].affectorId shouldBe 0
            designationType(out[0]) shouldBe AnnotationConstants.DESIGNATION_TYPE_DAY
            out[0].detailsList shouldHaveSize 1
        }

        test("neither -> Night: single GainDesignation lite, no Lose") {
            val out = mutableListOf<AnnotationInfo>()
            insertDayNightDesignationTransients(out, prevDayTime = null, curDayTime = true)

            out shouldHaveSize 1
            out[0].typeList shouldContainExactly listOf(AnnotationType.GainDesignation)
            designationType(out[0]) shouldBe AnnotationConstants.DESIGNATION_TYPE_NIGHT
        }

        test("Day -> Night: Lose{Day} paired with Gain{Night}") {
            val out = mutableListOf<AnnotationInfo>()
            insertDayNightDesignationTransients(out, prevDayTime = false, curDayTime = true)

            out shouldHaveSize 2
            out[0].typeList shouldContainExactly listOf(AnnotationType.LoseDesignation)
            designationType(out[0]) shouldBe AnnotationConstants.DESIGNATION_TYPE_DAY
            out[1].typeList shouldContainExactly listOf(AnnotationType.GainDesignation)
            designationType(out[1]) shouldBe AnnotationConstants.DESIGNATION_TYPE_NIGHT
            out.forEach { ann ->
                ann.affectedIdsList shouldContainExactly listOf(0)
                ann.affectorId shouldBe 0
                // Lite shape — no APSC on transient.
                ann.detailsList shouldHaveSize 1
            }
        }

        test("Night -> Day: Lose{Night} paired with Gain{Day}") {
            val out = mutableListOf<AnnotationInfo>()
            insertDayNightDesignationTransients(out, prevDayTime = true, curDayTime = false)

            out shouldHaveSize 2
            designationType(out[0]) shouldBe AnnotationConstants.DESIGNATION_TYPE_NIGHT
            designationType(out[1]) shouldBe AnnotationConstants.DESIGNATION_TYPE_DAY
            out.forEach { it.detailsList shouldHaveSize 1 }
        }

        test("no transition: empty out (APSC ticks via persistent re-emit, not transients)") {
            for ((prev, cur) in listOf(null to null, false to false, true to true)) {
                val out = mutableListOf<AnnotationInfo>()
                insertDayNightDesignationTransients(out, prev, cur)
                out.shouldBeEmpty()
            }
        }
    })
