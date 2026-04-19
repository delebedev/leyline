package leyline.game.mapping

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.data.CardData
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

/**
 * Chapter grpId resolution — pure-function coverage of both resolver paths.
 *
 * Test tier is at the [CardData] level so we can exercise the production-shape
 * CardData (chapter grpIds embedded in [CardData.abilityIds] leading slots,
 * [CardData.chapterAbilityGrpIds] empty) — the branch that `ExposedCardRepository`
 * actually hits. Integration tests in `SagaChapterAbilityIdTest` already cover
 * the populated-[chapterAbilityGrpIds] path used by `AbilityIdDeriver` in tests
 * and puzzles.
 */
class ZoneMapperChapterGrpIdTest :
    FunSpec({

        tags(UnitTag)

        fun cardData(
            abilityIds: List<Pair<Int, Int>> = emptyList(),
            chapterAbilityGrpIds: List<Int> = emptyList(),
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
            manaCost = listOf(ManaColor.Generic to 1, ManaColor.Black_afc9 to 1),
            chapterAbilityGrpIds = chapterAbilityGrpIds,
        )

        test("populated chapterAbilityGrpIds: first-choice path") {
            val data = cardData(
                abilityIds = emptyList(),
                chapterAbilityGrpIds = listOf(10001, 10002, 10003),
            )
            ZoneMapper.chapterGrpIdFromCardData(data, 1) shouldBe 10001
            ZoneMapper.chapterGrpIdFromCardData(data, 2) shouldBe 10002
            ZoneMapper.chapterGrpIdFromCardData(data, 3) shouldBe 10003
        }

        test("prod shape: empty chapterAbilityGrpIds, chapters at leading abilityIds positions") {
            // Mirrors what ExposedCardRepository produces from the card DB's
            // `Cards.AbilityIds` column for a 3-chapter saga: the chapter
            // grpIds are the first three entries, no chapterAbilityGrpIds
            // populated separately.
            val data = cardData(
                abilityIds = listOf(
                    147926 to 0, // Ch I
                    147927 to 0, // Ch II
                    147760 to 0, // Ch III
                ),
                chapterAbilityGrpIds = emptyList(),
            )
            ZoneMapper.chapterGrpIdFromCardData(data, 1) shouldBe 147926
            ZoneMapper.chapterGrpIdFromCardData(data, 2) shouldBe 147927
            ZoneMapper.chapterGrpIdFromCardData(data, 3) shouldBe 147760
        }

        test("populated list wins over abilityIds positional fallback") {
            // If both are present, chapterAbilityGrpIds takes precedence —
            // AbilityIdDeriver-allocated grpIds (chapter-specific synthetics)
            // are trusted over the positional slot layout.
            val data = cardData(
                abilityIds = listOf(99999 to 0, 88888 to 0),
                chapterAbilityGrpIds = listOf(10001, 10002),
            )
            ZoneMapper.chapterGrpIdFromCardData(data, 1) shouldBe 10001
            ZoneMapper.chapterGrpIdFromCardData(data, 2) shouldBe 10002
        }

        test("out-of-range chapter returns null on either path") {
            val pop = cardData(chapterAbilityGrpIds = listOf(10001, 10002, 10003))
            ZoneMapper.chapterGrpIdFromCardData(pop, 4) shouldBe null

            val fallback = cardData(abilityIds = listOf(147926 to 0, 147927 to 0))
            ZoneMapper.chapterGrpIdFromCardData(fallback, 3) shouldBe null
        }

        test("empty CardData returns null") {
            ZoneMapper.chapterGrpIdFromCardData(cardData(), 1) shouldBe null
        }
    })
