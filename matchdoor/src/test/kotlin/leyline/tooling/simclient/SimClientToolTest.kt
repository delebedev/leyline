package leyline.tooling.simclient

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag

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
            config.shardIndex shouldBe 1
            config.shardCount shouldBe 4
        }

        test("help returns no runnable config") {
            SimClientConfig.parse(listOf("--help"), emptyMap()) shouldBe null
        }
    })
