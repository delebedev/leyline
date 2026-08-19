package leyline.behavior.mechanics.saga

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.game.mapping.ZoneMapper
import leyline.testkit.BoardTest
import leyline.testkit.TestCardInjector

/**
 * Verifies the saga chapter resolution shape after the fixture migration.
 *
 * Fixture-driven cards mirror the prod `ExposedCardRepository` layout:
 * the client's `Cards.AbilityIds` column lists chapter abilities as trigger
 * rows (client category 2) in chapter order. `ZoneMapper.chapterGrpIdFromCardData`
 * resolves chapters by filtering to trigger rows — which also skips the
 * leading static "Read ahead" row of read-ahead sagas (category 3).
 *
 * Direct resolver coverage lives in `ZoneMapperChapterGrpIdTest`. Here we
 * assert the full inject → register → CardData round-trip for a real Saga
 * (Tribute to Horobi).
 */
class SagaChapterAbilityIdTest :
    BoardTest({

        test("Tribute to Horobi: 3 distinct chapter ability ids as trigger rows") {
            val cardName = "Tribute to Horobi"
            val (b, _, _) = startWithBoard { _, _, _ -> }

            TestCardInjector.inject(b, 1, cardName, ZoneType.Battlefield)
            val grpId = b.cardRepository.findGrpIdByName(cardName)!!
            val cardData = b.cardRepository.findByGrpId(grpId)!!

            cardData.abilityIds shouldHaveSize 3
            cardData.abilityIds.map { it.first }.toSet() shouldHaveSize 3 // all distinct
            cardData.abilityIds.forEach { (id, _) ->
                id shouldNotBe 0
                id shouldNotBe cardData.grpId
            }
            // All rows are chapter triggers, in chapter order.
            cardData.abilityCategories shouldBe listOf(2, 2, 2)
            // Resolution through the fixture-derived CardData pins chapter order.
            assertSoftly {
                ZoneMapper.chapterGrpIdFromCardData(cardData, 1) shouldBe 147926
                ZoneMapper.chapterGrpIdFromCardData(cardData, 2) shouldBe 147927
                ZoneMapper.chapterGrpIdFromCardData(cardData, 3) shouldBe 147760
            }
        }

        test("non-saga card resolves no chapter grpId") {
            val cardName = "Grizzly Bears"
            val (b, _, _) = startWithBoard { _, _, _ -> }

            TestCardInjector.inject(b, 1, cardName, ZoneType.Battlefield)
            val grpId = b.cardRepository.findGrpIdByName(cardName)!!
            val cardData = b.cardRepository.findByGrpId(grpId)!!

            ZoneMapper.chapterGrpIdFromCardData(cardData, 1) shouldBe null
        }
    })
