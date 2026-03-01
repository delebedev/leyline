package leyline.game

import org.testng.Assert.assertEquals
import org.testng.Assert.assertNull
import org.testng.annotations.AfterMethod
import org.testng.annotations.Test

@Test(groups = ["unit"])
class CardDbTest {

    @AfterMethod(alwaysRun = true)
    fun cleanup() {
        CardDb.clear()
    }

    // --- parseTokenGrpIds ---

    @Test
    fun parseTokenGrpIdsSingleEntry() {
        val result = CardDb.parseTokenGrpIds("99866:94161")
        assertEquals(result, mapOf(99866 to 94161))
    }

    @Test
    fun parseTokenGrpIdsMultipleEntries() {
        val result = CardDb.parseTokenGrpIds("99866:94161,175756:94156")
        assertEquals(result, mapOf(99866 to 94161, 175756 to 94156))
    }

    @Test
    fun parseTokenGrpIdsEmpty() {
        assertEquals(CardDb.parseTokenGrpIds(null), emptyMap<Int, Int>())
        assertEquals(CardDb.parseTokenGrpIds(""), emptyMap<Int, Int>())
        assertEquals(CardDb.parseTokenGrpIds("  "), emptyMap<Int, Int>())
    }

    @Test
    fun parseTokenGrpIdsMalformed() {
        val result = CardDb.parseTokenGrpIds("abc:def,99866:94161")
        assertEquals(result, mapOf(99866 to 94161), "Malformed entries should be skipped")
    }

    // --- tokenGrpIdForCard ---

    @Test
    fun tokenGrpIdForCardSingleToken() {
        CardDb.testMode = true
        CardDb.registerData(
            CardDb.CardData(
                grpId = 1000, titleId = 1, power = "", toughness = "",
                colors = emptyList(), types = emptyList(), subtypes = emptyList(),
                supertypes = emptyList(), abilityIds = emptyList(), manaCost = emptyList(),
                tokenGrpIds = mapOf(5000 to 94161),
            ),
            "Resolute Reinforcements",
        )
        val result = CardDb.tokenGrpIdForCard(1000)
        assertEquals(result, 94161, "Single token should return directly")
    }

    @Test
    fun tokenGrpIdForCardMultipleTokensMatchByName() {
        CardDb.testMode = true
        CardDb.registerData(
            CardDb.CardData(
                grpId = 2000, titleId = 1, power = "", toughness = "",
                colors = emptyList(), types = emptyList(), subtypes = emptyList(),
                supertypes = emptyList(), abilityIds = emptyList(), manaCost = emptyList(),
                tokenGrpIds = mapOf(5000 to 94161, 5001 to 94156),
            ),
            "Some Card",
        )
        // Register token names so lookup works
        CardDb.register(94161, "Soldier")
        CardDb.register(94156, "Cat")

        assertEquals(CardDb.tokenGrpIdForCard(2000, "Soldier"), 94161)
        assertEquals(CardDb.tokenGrpIdForCard(2000, "Cat"), 94156)
    }

    @Test
    fun tokenGrpIdForCardUnknownSource() {
        CardDb.testMode = true
        assertNull(CardDb.tokenGrpIdForCard(9999), "Unknown source should return null")
    }

    @Test
    fun tokenGrpIdForCardNoTokens() {
        CardDb.testMode = true
        CardDb.registerData(
            CardDb.CardData(
                grpId = 3000, titleId = 1, power = "", toughness = "",
                colors = emptyList(), types = emptyList(), subtypes = emptyList(),
                supertypes = emptyList(), abilityIds = emptyList(), manaCost = emptyList(),
                tokenGrpIds = emptyMap(),
            ),
            "Plain Card",
        )
        assertNull(CardDb.tokenGrpIdForCard(3000), "Card with no tokens should return null")
    }
}
