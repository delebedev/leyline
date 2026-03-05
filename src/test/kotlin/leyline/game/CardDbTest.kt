package leyline.game

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag

class CardDbTest :
    FunSpec({

        tags(UnitTag)

        lateinit var repo: InMemoryCardRepository

        beforeEach {
            repo = InMemoryCardRepository()
        }

        // --- parseTokenGrpIds ---

        test("parse token grp ids single entry") {
            val result = parseTokenGrpIds("99866:94161")
            result shouldBe mapOf(99866 to 94161)
        }

        test("parse token grp ids multiple entries") {
            val result = parseTokenGrpIds("99866:94161,175756:94156")
            result shouldBe mapOf(99866 to 94161, 175756 to 94156)
        }

        test("parse token grp ids empty") {
            parseTokenGrpIds(null) shouldBe emptyMap<Int, Int>()
            parseTokenGrpIds("") shouldBe emptyMap<Int, Int>()
            parseTokenGrpIds("  ") shouldBe emptyMap<Int, Int>()
        }

        test("parse token grp ids malformed") {
            val result = parseTokenGrpIds("abc:def,99866:94161")
            result shouldBe mapOf(99866 to 94161)
        }

        // --- tokenGrpIdForCard ---

        test("token grp id for card single token") {
            repo.registerData(
                CardData(
                    grpId = 1000, titleId = 1, power = "", toughness = "",
                    colors = emptyList(), types = emptyList(), subtypes = emptyList(),
                    supertypes = emptyList(), abilityIds = emptyList(), manaCost = emptyList(),
                    tokenGrpIds = mapOf(5000 to 94161),
                ),
                "Resolute Reinforcements",
            )
            val result = repo.tokenGrpIdForCard(1000)
            result shouldBe 94161
        }

        test("token grp id for card multiple tokens match by name") {
            repo.registerData(
                CardData(
                    grpId = 2000, titleId = 1, power = "", toughness = "",
                    colors = emptyList(), types = emptyList(), subtypes = emptyList(),
                    supertypes = emptyList(), abilityIds = emptyList(), manaCost = emptyList(),
                    tokenGrpIds = mapOf(5000 to 94161, 5001 to 94156),
                ),
                "Some Card",
            )
            // Register token names so lookup works
            repo.register(94161, "Soldier")
            repo.register(94156, "Cat")

            repo.tokenGrpIdForCard(2000, "Soldier") shouldBe 94161
            repo.tokenGrpIdForCard(2000, "Cat") shouldBe 94156
        }

        test("token grp id for card unknown source") {
            repo.tokenGrpIdForCard(9999).shouldBeNull()
        }

        test("token grp id for card no tokens") {
            repo.registerData(
                CardData(
                    grpId = 3000, titleId = 1, power = "", toughness = "",
                    colors = emptyList(), types = emptyList(), subtypes = emptyList(),
                    supertypes = emptyList(), abilityIds = emptyList(), manaCost = emptyList(),
                    tokenGrpIds = emptyMap(),
                ),
                "Plain Card",
            )
            repo.tokenGrpIdForCard(3000).shouldBeNull()
        }
    })
