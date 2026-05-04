package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.BoardTag
import leyline.testkit.BoardTestBase
import leyline.testkit.TestCardInjector

/**
 * Verifies the saga chapter resolution shape after the fixture migration.
 *
 * Fixture-driven cards mirror the prod `ExposedCardRepository` layout:
 * the client's `Cards.AbilityIds` column orders chapter abilities at leading
 * positions (chapter I at index 0, II at 1, III at 2). `CardData.chapterAbilityGrpIds`
 * is left empty; `ZoneMapper.chapterGrpIdFromCardData` falls back to the
 * positional list.
 *
 * Direct positional-fallback coverage lives in `TestCardFixturesTest`. Here
 * we assert the full inject → register → CardData round-trip for a real Saga
 * (Tribute to Horobi).
 */
class SagaChapterAbilityIdTest :
    FunSpec({
        val base = BoardTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("Tribute to Horobi: 3 distinct chapter ability ids in leading abilityIds positions")
            .config(tags = setOf(BoardTag)) {
                val cardName = "Tribute to Horobi"
                val (b, _, _) = base.startWithBoard { _, _, _ -> }

                TestCardInjector.inject(b, 1, cardName, ZoneType.Battlefield)
                val grpId = b.cardRepository.findGrpIdByName(cardName)!!
                val cardData = b.cardRepository.findByGrpId(grpId)!!

                cardData.abilityIds shouldHaveSize 3
                cardData.abilityIds.map { it.first }.toSet() shouldHaveSize 3 // all distinct
                cardData.abilityIds.forEach { (id, _) ->
                    id shouldNotBe 0
                    id shouldNotBe cardData.grpId
                }
                // Chapter list is intentionally empty — positional fallback path.
                cardData.chapterAbilityGrpIds shouldBe emptyList()
            }

        test("non-saga card has empty chapterAbilityGrpIds")
            .config(tags = setOf(BoardTag)) {
                val cardName = "Grizzly Bears"
                val (b, _, _) = base.startWithBoard { _, _, _ -> }

                TestCardInjector.inject(b, 1, cardName, ZoneType.Battlefield)
                val grpId = b.cardRepository.findGrpIdByName(cardName)!!
                val cardData = b.cardRepository.findByGrpId(grpId)!!

                cardData.chapterAbilityGrpIds shouldBe emptyList()
            }
    })
