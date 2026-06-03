package leyline.tooling.simclient

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.data.CardData
import leyline.game.data.CardRepository

class SimClientToolTest :
    FunSpec({
        tags(UnitTag)

        test("config parser accepts CLI overrides") {
            val config =
                SimClientConfig.parse(
                    listOf(
                        "--decks",
                        "bears,mono-r-burn",
                        "--opponent-deck",
                        "forest-only",
                        "--seeds",
                        "1..3",
                        "--policy",
                        "forge-ai",
                        "--max-turns",
                        "7",
                        "--game-timeout-seconds",
                        "11",
                        "--resume",
                        "--strict",
                        "--exclude-cards",
                        "Tinybones Joins Up,102468",
                        "--exclude-cards-file",
                        "/tmp/quarantine.txt",
                        "--exclude-policy",
                        "skip-deck",
                        "--shard-index",
                        "1",
                        "--shard-count",
                        "4",
                    ),
                    mapOf("LEYLINE_CARD_DB" to "/tmp/cards.sqlite"),
                )!!

            config.deckSpec shouldBe "bears,mono-r-burn"
            config.opponentDeck shouldBe "forest-only"
            config.seedSpec shouldBe "1..3"
            config.policy shouldBe SimClientPolicyMode.ForgeAi
            config.maxTurns shouldBe 7
            config.gameTimeoutSeconds shouldBe 11
            config.resume shouldBe true
            config.strict shouldBe true
            config.excludeCards shouldBe "Tinybones Joins Up,102468"
            config.excludeCardsFile?.path shouldBe "/tmp/quarantine.txt"
            config.excludePolicy shouldBe SimClientExcludePolicy.SkipDeck
            config.shardIndex shouldBe 1
            config.shardCount shouldBe 4
        }

        test("quarantine replaces name matches with the most common basic land") {
            val result =
                overlayDeck(
                    deckList = "10 Island\n4 Daydream\n2 Practiced Offense",
                    spec = quarantine("Daydream"),
                    policy = SimClientExcludePolicy.ReplaceBasic,
                    cardRepository = null,
                )

            result.skipped shouldBe false
            result.report?.removedCount shouldBe 4
            result.report?.replacement shouldBe "Island"
            result.deckList shouldBe "10 Island\n2 Practiced Offense\n4 Island"
        }

        test("quarantine matches grpIds when card db is available") {
            val result =
                overlayDeck(
                    deckList = "10 Swamp\n4 Tinybones Joins Up",
                    spec = quarantine("90454"),
                    policy = SimClientExcludePolicy.SkipDeck,
                    cardRepository = fakeCardRepository("Tinybones Joins Up" to 90454),
                )

            result.skipped shouldBe true
            result.report
                ?.removed
                ?.single()
                ?.matchedBy shouldBe "grpId"
        }

        test("quarantine reports mixed name and grpId matches in one card-db pass") {
            val result =
                overlayDeck(
                    deckList = "10 Swamp\n4 Daydream\n2 Tinybones Joins Up",
                    spec = quarantine("Daydream", "90454"),
                    policy = SimClientExcludePolicy.ReplaceBasic,
                    cardRepository = fakeCardRepository("Tinybones Joins Up" to 90454),
                )

            result.report?.removedCount shouldBe 6
            result.report?.removed?.map { it.matchedBy } shouldBe listOf("name", "grpId")
            result.deckList shouldBe "10 Swamp\n6 Swamp"
        }

        test("quarantine ignores Arena set suffixes") {
            val result =
                overlayDeck(
                    deckList = "10 Swamp\n4 Tinybones Joins Up (OTJ) 108",
                    spec = quarantine("Tinybones Joins Up"),
                    policy = SimClientExcludePolicy.ReplaceBasic,
                    cardRepository = null,
                )

            result.report?.removedCount shouldBe 4
            result.report
                ?.removed
                ?.single()
                ?.name shouldBe "Tinybones Joins Up"
        }

        test("quarantine rewrites main deck only") {
            val result =
                overlayDeck(
                    deckList = "Deck\n10 Swamp\n4 Tinybones Joins Up (OTJ) 108\nSideboard\n1 Get Out (DSK) 60",
                    spec = quarantine("Tinybones Joins Up", "Get Out"),
                    policy = SimClientExcludePolicy.ReplaceBasic,
                    cardRepository = null,
                )

            result.report?.removedCount shouldBe 4
            result.deckList shouldBe "10 Swamp\n4 Swamp"
        }

        test("help returns no runnable config") {
            SimClientConfig.parse(listOf("--help"), emptyMap()) shouldBe null
        }

        test("config parser keeps multi-word direct args") {
            val config =
                SimClientConfig.parse(
                    listOf("--decks", "Prompt", "Route", "Group", "--seeds", "1", "--exclude-cards", "Grizzly", "Bears"),
                    emptyMap(),
                )!!

            config.deckSpec shouldBe "Prompt Route Group"
            config.excludeCards shouldBe "Grizzly Bears"
        }
    })

private fun quarantine(vararg entries: String): QuarantineSpec =
    QuarantineSpec(
        rawEntries = entries.toList(),
        names = entries.mapNotNull { if (it.toIntOrNull() == null) normalizeCardName(it) else null }.toSet(),
        grpIds = entries.mapNotNull { it.toIntOrNull() }.toSet(),
    )

private fun fakeCardRepository(vararg mappings: Pair<String, Int>): CardRepository {
    val byName = mappings.associate { normalizeCardName(it.first) to it.second }
    return object : CardRepository {
        override fun findByGrpId(grpId: Int): CardData? = null

        override fun findNameByGrpId(grpId: Int): String? = null

        override fun findGrpIdByName(name: String): Int? = byName[normalizeCardName(name)]

        override fun findAllGrpIds(): List<Int> = emptyList()
    }
}
