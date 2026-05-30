package leyline.simclient

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.string.shouldContain
import leyline.SimClientTag
import java.nio.file.Files
import java.time.LocalDateTime

class SpectatorSimClientTest :
    FunSpec({
        tags(SimClientTag)

        test("spectator AI-vs-AI mirror runs without client decisions") {
            val deck =
                """
                24 Mountain
                36 Lightning Bolt
                """.trimIndent()
            val opponentDeck =
                """
                60 Mountain
                """.trimIndent()
            val tempLog = Files.createTempFile("simclient-spectator-bears-", ".log").toFile()
            var fakeNow = LocalDateTime.of(2026, 5, 1, 12, 0, 0)
            val writer = tempLog.bufferedWriter()
            val playerLog =
                PlayerLogWriter(
                    out = writer,
                    matchId = "simclient-spectator-bears",
                    clock = {
                        fakeNow = fakeNow.plusSeconds(1)
                        fakeNow
                    },
                )
            val stats =
                SpectatorSimClientDriver(
                    deckList = deck,
                    opponentDeckList = opponentDeck,
                    seed = 42L,
                    matchId = "simclient-spectator-bears",
                    log = playerLog,
                ).runOneGame()
            writer.close()

            val contents = tempLog.readText()
            println(
                "SpectatorSimClientTest log: ${tempLog.absolutePath} (${tempLog.length()} bytes), " +
                    "gameOver=${stats.gameOver}, messages=${stats.totalMessages}, durationMs=${stats.durationMs}",
            )
            assertSoftly {
                stats.gameOver.shouldBeTrue()
                stats.totalMessages shouldBeGreaterThan 1
                contents shouldContain "[UnityCrossThreadLogger]"
                contents shouldContain "greToClientEvent"
                contents shouldContain "GREMessageType_GameStateMessage"
            }
        }
    })
