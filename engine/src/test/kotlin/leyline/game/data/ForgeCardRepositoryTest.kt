package leyline.game.data

import forge.game.keyword.Keyword
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag

class ForgeCardRepositoryTest :
    FunSpec({
        tags(UnitTag)

        test("recipient-dependent granted costs resolve from cold catalogs in either lookup order") {
            val grants =
                mapOf(
                    "Falkenrath Gorger" to "Madness:ManaCost",
                    "Aminatou, Veil Piercer" to "Miracle:ManaCost:4",
                )
            val first = ForgeCardRepository.open()
            val second = ForgeCardRepository.open()
            grants.keys.forEach(first::findDeckGrpIdByName)
            grants.keys.reversed().forEach(second::findDeckGrpIdByName)

            grants.forEach { (name, keyword) ->
                val cardId = requireNotNull(first.findDeckGrpIdByName(name))
                val card = requireNotNull(first.findByGrpId(cardId))
                val abilityId = card.grantedKeywordAbilityIds.getValue(keyword)
                val localization = requireNotNull(first.findAbilityLocalization(abilityId))
                val definition = Keyword.getInstance(keyword)
                val costDescription =
                    if (keyword.startsWith("Miracle:")) {
                        "Its miracle cost is equal to its mana cost reduced by {4}."
                    } else {
                        "Its madness cost is equal to its mana cost."
                    }
                assertSoftly {
                    localization.keyword shouldBe definition.keyword.toString()
                    localization.text shouldBe "${definition.keyword.reminderText} $costDescription"
                    localization.manaCost shouldBe emptyList()
                    requireNotNull(first.findAbilityInfo(abilityId)).manaCost shouldBe emptyList()
                    second.findDeckGrpIdByName(name) shouldBe cardId
                    second.findByGrpId(cardId) shouldBe card
                    second.findAbilityLocalization(abilityId) shouldBe localization
                    first.findGrantedKeywordAbilityGrpId(cardId, keyword) shouldBe abilityId
                    first.findAbilityLocalization(abilityId) shouldBe localization
                }
            }
        }

        test("fixed and nonmana granted costs retain Forge titles and reminder text") {
            val repo = ForgeCardRepository.open()
            val grants =
                mapOf(
                    "Ashling, the Limitless" to "Evoke:4",
                    "Tectonic Reformation" to "Cycling:R",
                    "Cultist of the Absolute" to "Ward:PayLife<3>",
                )
            grants.forEach { (name, keyword) ->
                val cardId = requireNotNull(repo.findDeckGrpIdByName(name))
                val abilityId = requireNotNull(repo.findGrantedKeywordAbilityGrpId(cardId, keyword))
                requireNotNull(repo.findByGrpId(cardId)).grantedKeywordAbilityIds.getValue(keyword) shouldBe abilityId
                val definition = Keyword.getInstance(keyword)
                val localization = requireNotNull(repo.findAbilityLocalization(abilityId))
                assertSoftly {
                    localization.keyword shouldBe definition.title
                    localization.text shouldBe definition.reminderText
                    localization.manaCost shouldBe requireNotNull(repo.findAbilityInfo(abilityId)).manaCost
                }
            }
        }

        test("symbolic localization formats cost placeholders without binding or evaluating a recipient") {
            val keyword = "Cycling:ManaCost"
            val localization = requireNotNull(localizeGrantedKeyword(keyword))
            assertSoftly {
                localization.keyword shouldBe "Cycling"
                localization.text shouldBe
                    "its mana cost, Discard this card: Draw a card. " +
                    "Its cycling cost is equal to its mana cost."
                localization.manaCost shouldBe emptyList()
                localizeGrantedKeyword("Not a Forge keyword") shouldBe null
            }
        }
    })
