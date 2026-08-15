package leyline.bridge.handoff

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

class ModalChoiceWindowValueTest :
    FunSpec({
        tags(UnitTag)

        val source = ForgeCardId(17)
        val options =
            listOf(
                ModalChoiceOptionValue(0, 101, listOf(ManaColor.Generic to 2)),
                ModalChoiceOptionValue(2, 103, listOf(ManaColor.Red_afc9 to 1)),
            )
        val excluded = listOf(ModalChoiceOptionValue(1, 102))

        fun value(
            possible: List<ModalChoiceOptionValue> = options,
            blocked: List<ModalChoiceOptionValue> = excluded,
            min: Int = 1,
            max: Int = 1,
            allowRepeat: Boolean = false,
        ) = ModalChoiceWindowValue(
            sourceForgeCardId = source,
            sourceCardGrpId = 9001,
            sourceForgeAbilityId = 44,
            parentGrpId = 9000,
            ctoGrpId = 9001,
            ctoId = 2,
            min = min,
            max = max,
            defaultOptionIndex = 0,
            allowRepeat = allowRepeat,
            possible = possible,
            excluded = blocked,
            triggered = true,
        )

        test("retains full-list indices, grpIds, and per-mode costs") {
            val window = value()
            assertSoftly {
                window.possible.map { it.fullIndex } shouldBe listOf(0, 2)
                window.possible.map { it.grpId } shouldBe listOf(101, 103)
                window.possible.first().cost shouldBe listOf(ManaColor.Generic to 2)
                window.excluded.single().fullIndex shouldBe 1
            }
        }

        test("rejects duplicate or overlapping option identity") {
            shouldThrow<IllegalArgumentException> {
                value(possible = options + ModalChoiceOptionValue(0, 104))
            }
            shouldThrow<IllegalArgumentException> {
                value(blocked = listOf(ModalChoiceOptionValue(3, 103)))
            }
        }

        test("rejects invalid cardinality unless repeats are explicit") {
            shouldThrow<IllegalArgumentException> { value(min = 2, max = 3) }
            value(min = 2, max = 3, allowRepeat = true).max shouldBe 3
        }
    })
