package leyline.tooling.simclient

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import leyline.UnitTag
import leyline.game.annotations.AnnotationBuilder
import leyline.game.iid
import leyline.game.sid
import leyline.game.wid
import leyline.testkit.greMessage
import java.io.StringWriter
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

        test("writeBundle normalizes annotation enum suffixes") {
            val out = StringWriter()
            val writer =
                PlayerLogWriter(
                    out = out,
                    matchId = "match-annotations",
                    clock = { LocalDateTime.of(2026, 5, 1, 12, 0, 0) },
                )

            writer.writeBundle(
                listOf(
                    greMessage(msgId = 2, gsId = 1) {
                        addAnnotations(
                            AnnotationBuilder.zoneTransfer(
                                100.iid,
                                srcZoneId = 31,
                                destZoneId = 27,
                                category = "CastSpell",
                            ),
                        )
                        addAnnotations(
                            AnnotationBuilder.damageDealt(
                                100.iid,
                                targetId = 2.wid,
                                amount = 3,
                            ),
                        )
                        addAnnotations(
                            AnnotationBuilder.scry(
                                1.sid,
                                topIds = listOf(101),
                                bottomIds = emptyList(),
                            ),
                        )
                    },
                ),
            )

            val log = out.toString()
            val annotationTypes =
                Regex("\"type\":\\[\"(AnnotationType_[^\"]+)\"\\]")
                    .findAll(log)
                    .map { it.groupValues[1] }
                    .toList()

            annotationTypes shouldBe
                listOf(
                    "AnnotationType_ZoneTransfer",
                    "AnnotationType_DamageDealt",
                    "AnnotationType_Scry",
                )
            assertSoftly {
                log shouldNotContain "ZoneTransfer_af5a"
                log shouldNotContain "DamageDealt_af5a"
                log shouldNotContain "Scry_af5a"
            }
        }
    })
