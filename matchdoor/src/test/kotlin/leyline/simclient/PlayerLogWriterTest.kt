package leyline.simclient

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import java.nio.file.Files
import java.time.LocalDateTime

class PlayerLogWriterTest :
    FunSpec({
        tags(UnitTag)

        test("sidecar escapes deck labels and records opponent tag") {
            val dir = Files.createTempDirectory("simclient-sidecar").toFile()
            val logFile = dir.resolve("quoted.log")

            writeSimClientSidecar(
                logFile = logFile,
                matchId = "match-\"quoted\"",
                runLabel = "Deck \"A\"",
                opponentRunLabel = "Blue\\Tempo",
                seed = 7,
                generatedAt = LocalDateTime.of(2026, 5, 1, 12, 0, 0),
            )

            val sidecar = dir.resolve("quoted.meta.json").readText()
            sidecar shouldBe
                """
                {
                  "cards": [],
                  "tags": ["simclient", "deck:Deck \"A\"", "opponent:Blue\\Tempo", "seed:7"],
                  "notes": [],
                  "provenance": {
                    "source": "simclient",
                    "confidence": "explicit",
                    "matchId": "match-\"quoted\"",
                    "eventName": "simclient-Deck \"A\"-vs-Blue\\Tempo",
                    "recordedAt": "2026-05-01T12:00:00"
                  }
                }
                """.trimIndent()
        }
    })
