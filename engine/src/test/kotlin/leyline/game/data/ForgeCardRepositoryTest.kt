package leyline.game.data

import forge.game.keyword.Keyword
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import leyline.UnitTag
import leyline.game.codes.SlotKind

class ForgeCardRepositoryTest :
    FunSpec({
        tags(UnitTag)

        test("Flying identity comes from its keyword declaration without changing printed text or other rows") {
            val repo = ForgeCardRepository.open()
            val data = requireNotNull(repo.findByGrpId(requireNotNull(repo.findGrpIdByName("Atraxa, Praetors' Voice"))))
            val rows = data.abilityIds.map { requireNotNull(repo.findAbilityLocalization(it.first)) }
            assertSoftly {
                rows.first().keyword shouldBe "Flying"
                rows.first().text shouldBe "Flying"
                data.abilityKinds.first() shouldBe SlotKind.Keyword
                rows.drop(1).all { it.keyword == null } shouldBe true
                data.keywordNames.containsAll(listOf("Flying", "Vigilance", "Deathtouch", "Lifelink")) shouldBe true
            }
        }

        test("deck entry resolves typographic apostrophes without discarding punctuation") {
            val repo = ForgeCardRepository.open()
            assertSoftly {
                listOf("Commander's Plate", "Arcum's Astrolabe", "Urza's Saga").forEach { name ->
                    val canonical = requireNotNull(repo.findGrpIdByName(name))
                    repo.findDeckGrpIdByName(name.replace('\'', '\u2019')) shouldBe canonical
                    repo.findDeckGrpIdByNameAndSet(name.lowercase().replace('\'', '\u2019'), "") shouldBe canonical
                    repo.findDeckGrpIdByName(name.replace("'", "")) shouldBe null
                }
                val galleon = requireNotNull(repo.findGrpIdByName("Conqueror's Galleon"))
                repo.findDeckGrpIdByName("Conqueror\u2019s Foothold") shouldBe galleon
                repo.findDeckGrpIdByName("Conqueror\u2019s Galleon // Conqueror\u2019s Foothold") shouldBe galleon
                repo.findDeckGrpIdByName("Conqueror\u2019s Galleon // Urza\u2019s Saga") shouldBe null
                repo.findDeckGrpIdByName("Unknown\u2019s Unprinted Card") shouldBe null
            }
        }

        test("mana-producing loyalty rows stay activated while ordinary mana rows stay mana") {
            val repo = ForgeCardRepository.open()
            val ugin = requireNotNull(repo.findByGrpId(requireNotNull(repo.findGrpIdByName("Ugin, Eye of the Storms"))))
            val ring = requireNotNull(repo.findByGrpId(requireNotNull(repo.findGrpIdByName("Sol Ring"))))
            assertSoftly {
                ugin.abilityKinds.take(3) shouldBe List(3) { SlotKind.Activated }
                ring.abilityKinds.single() shouldBe SlotKind.Mana
            }
        }

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

        test("deck entry resolves multi-face names written with any separator") {
            val repo = ForgeCardRepository.open()
            val split = requireNotNull(repo.findGrpIdByName("Fire // Ice"))
            val fellTheProfane = requireNotNull(repo.findGrpIdByName("Fell the Profane"))
            val etali = requireNotNull(repo.findGrpIdByName("Etali, Primal Conqueror"))
            val ojerKaslem = requireNotNull(repo.findGrpIdByName("Ojer Kaslem, Deepest Growth"))
            assertSoftly {
                listOf("Fire/Ice", "Fire//Ice", "Fire / Ice").forEach { name ->
                    repo.findDeckGrpIdByName(name) shouldBe split
                }
                repo.findDeckGrpIdByName("Insult//Injury") shouldBe
                    requireNotNull(repo.findGrpIdByName("Insult // Injury"))
                repo.findDeckGrpIdByName("Fell the Profane / Fell Mire") shouldBe fellTheProfane
                repo.findDeckGrpIdByName("Etali, Primal Conqueror / Etali, Primal Sickness") shouldBe etali
                repo.findDeckGrpIdByName("Ojer Kaslem, Deepest Growth / Temple of Cultivation") shouldBe ojerKaslem
                repo.findDeckGrpIdByName("Fell the Profane / Made Up Back Face") shouldBe null
            }
        }

        test("unknown printing set codes resolve by name") {
            val repo = ForgeCardRepository.open()
            val skullclamp = requireNotNull(repo.findDeckGrpIdByName("Skullclamp"))
            assertSoftly {
                listOf("PLST", "SPG", "40K", "MOM", "sld").forEach { setCode ->
                    repo.findDeckGrpIdByNameAndSet("Skullclamp", setCode) shouldBe skullclamp
                }
            }
        }

        test("granted keyword without its own cost stays symbolic instead of reading a host card") {
            // "Beam me up" is granted with the host spell's mana cost, so the
            // catalog entry has no cost detail of its own.
            val localization = requireNotNull(localizeGrantedKeyword("Beam me up"))
            assertSoftly {
                localization.keyword shouldBe "Beam me up"
                localization.text shouldBe
                    "You may cast this card from your graveyard for its mana cost " +
                    "if you also return a creature you control to its owner's hand. Then exile this spell. " +
                    "Its beam me up cost is equal to its mana cost."
                localization.manaCost shouldBe emptyList()
            }
        }

        test("a card that grants a host-cost keyword registers without a live host card") {
            val repo = ForgeCardRepository.open()
            val cardId = requireNotNull(repo.findDeckGrpIdByName("Scotty, Miracle Worker"))
            val card = requireNotNull(repo.findByGrpId(cardId))
            val abilityId = requireNotNull(card.grantedKeywordAbilityIds["Beam me up"])
            val localization = requireNotNull(repo.findAbilityLocalization(abilityId))
            localization.keyword shouldBe "Beam me up"
            localization.text.shouldContain("its mana cost")
        }

        test("calculated granted amounts remain symbolic in cold catalog lookup") {
            val repo = ForgeCardRepository.open()
            val cardId = requireNotNull(repo.findDeckGrpIdByName("Kodama of the Center Tree"))
            val card = requireNotNull(repo.findByGrpId(cardId))
            val abilityId = card.grantedKeywordAbilityIds.getValue("Soulshift:N")
            val expected = Keyword.getInstance("Soulshift:X")
            val localization = requireNotNull(repo.findAbilityLocalization(abilityId))
            assertSoftly {
                localization.keyword shouldBe expected.title
                localization.text shouldBe expected.reminderText
                localization.manaCost shouldBe emptyList()
                repo.findGrantedKeywordAbilityGrpId(cardId, "Soulshift:N") shouldBe abilityId
                localizeGrantedKeyword("Soulshift:X")?.keyword shouldBe localization.keyword
                localizeGrantedKeyword("Soulshift:X")?.text shouldBe localization.text
                localizeGrantedKeyword("Soulshift:3")?.keyword shouldBe "Soulshift 3"
                localizeGrantedKeyword("Soulshift:3")?.text shouldBe Keyword.getInstance("Soulshift:3").reminderText
            }
        }
    })
