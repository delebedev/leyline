package leyline.game.mapping

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.data.CardData
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

/**
 * Chapter grpId resolution — pure-function coverage of the trigger-row path.
 *
 * Chapter abilities are the `CardData.abilityIds` rows whose client category
 * is 2 (trigger), in chapter order. Read-ahead sagas lead `abilityIds` with a
 * static "Read ahead" row (category 3) that must be skipped, so resolution
 * filters to trigger rows rather than indexing positionally into
 * [CardData.abilityIds].
 *
 * Integration coverage of the fixture round-trip lives in
 * `SagaChapterAbilityIdTest`.
 */
class ZoneMapperChapterGrpIdTest :
    FunSpec({

        tags(UnitTag)

        fun cardData(
            abilityIds: List<Pair<Int, Int>> = emptyList(),
            abilityCategories: List<Int> = emptyList(),
        ) = CardData(
            grpId = 79552,
            titleId = 1,
            power = "",
            toughness = "",
            colors = emptyList(),
            types = emptyList(),
            subtypes = emptyList(),
            supertypes = emptyList(),
            abilityIds = abilityIds,
            abilityCategories = abilityCategories,
            manaCost = listOf(ManaColor.Generic to 1, ManaColor.Black_afc9 to 1),
        )

        test("read-ahead shape: leading static row is skipped, chapters resolve by trigger rows") {
            // Mirrors a read-ahead saga from the client DB (e.g. The Elder
            // Dragon War): abilityIds leads with the shared "Read ahead"
            // static (category 3), then the three chapter rows (category 2).
            val data =
                cardData(
                    abilityIds =
                        listOf(
                            260 to 0, // Read ahead (static)
                            152810 to 0, // Ch I
                            152811 to 0, // Ch II
                            152812 to 0, // Ch III
                        ),
                    abilityCategories = listOf(3, 2, 2, 2),
                )
            assertSoftly {
                ZoneMapper.chapterGrpIdFromCardData(data, 1) shouldBe 152810
                ZoneMapper.chapterGrpIdFromCardData(data, 2) shouldBe 152811
                ZoneMapper.chapterGrpIdFromCardData(data, 3) shouldBe 152812
            }
        }

        test("classic saga shape: all rows are trigger rows, chapters at leading positions") {
            // Mirrors a classic saga (e.g. Tribute to Horobi): every abilityIds
            // row is a chapter trigger, in chapter order.
            val data =
                cardData(
                    abilityIds =
                        listOf(
                            147926 to 0, // Ch I
                            147927 to 0, // Ch II
                            147760 to 0, // Ch III
                        ),
                    abilityCategories = listOf(2, 2, 2),
                )
            assertSoftly {
                ZoneMapper.chapterGrpIdFromCardData(data, 1) shouldBe 147926
                ZoneMapper.chapterGrpIdFromCardData(data, 2) shouldBe 147927
                ZoneMapper.chapterGrpIdFromCardData(data, 3) shouldBe 147760
            }
        }

        test("out-of-range chapter returns null") {
            val data =
                cardData(
                    abilityIds = listOf(147926 to 0, 147927 to 0),
                    abilityCategories = listOf(2, 2),
                )
            ZoneMapper.chapterGrpIdFromCardData(data, 3) shouldBe null
        }

        test("no trigger rows (statics only) returns null") {
            val data =
                cardData(
                    abilityIds = listOf(260 to 0, 261 to 0),
                    abilityCategories = listOf(3, 3),
                )
            ZoneMapper.chapterGrpIdFromCardData(data, 1) shouldBe null
        }

        test("empty CardData returns null") {
            ZoneMapper.chapterGrpIdFromCardData(cardData(), 1) shouldBe null
        }
    })
