package leyline.simclient

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.time.LocalDateTime

class PlayerLogWriterTest :
    FunSpec({
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
            sidecar shouldContain "\"deck:Deck \\\"A\\\"\""
            sidecar shouldContain "\"opponent:Blue\\\\Tempo\""
            sidecar shouldContain "\"matchId\": \"match-\\\"quoted\\\"\""
        }
    })
