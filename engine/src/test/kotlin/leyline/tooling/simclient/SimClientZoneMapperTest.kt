package leyline.tooling.simclient

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import leyline.SimClientTag
import leyline.game.bundle.InvariantSelection
import leyline.game.data.ExposedCardRepository
import leyline.testkit.MatchFlowHarness
import org.jetbrains.exposed.v1.jdbc.Database
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime

class SimClientZoneMapperTest :
    FunSpec({
        tags(SimClientTag)

        test("Cascade/Discover deck keeps zone-listed cards snapshot-backed") {
            val cardDbPath =
                requireNotNull(System.getenv("LEYLINE_CARD_DB")) {
                    "LEYLINE_CARD_DB is not set. Point it at Raw_CardDatabase_*.mtga."
                }
            require(File(cardDbPath).exists()) { "Card database not found at: $cardDbPath" }
            val cardRepo =
                ExposedCardRepository(
                    Database.connect(
                        "jdbc:sqlite:${File(cardDbPath).absolutePath}",
                        "org.sqlite.JDBC",
                    ),
                )
            val deck =
                """
                4 Bloodbraid Elf
                4 Violent Outburst
                4 Geological Appraiser
                2 Hidden Courtyard
                4 Burst Lightning
                4 Llanowar Elves
                4 Elvish Mystic
                4 Shock
                8 Mountain
                8 Forest
                4 Plains
                """.trimIndent()
            val harness =
                MatchFlowHarness(
                    seed = 1L,
                    deckList = deck,
                    validation = InvariantSelection.none("this test only checks mapper warnings"),
                    cardRepositoryOverride = cardRepo,
                )
            val log = Files.createTempFile("simclient-zonemapper-", ".log").toFile()
            var fakeNow = LocalDateTime.of(2026, 5, 1, 12, 0, 0)
            val stats =
                log.bufferedWriter().use { writer ->
                    val playerLog =
                        PlayerLogWriter(
                            out = writer,
                            matchId = "simclient-zonemapper",
                            clock = {
                                fakeNow = fakeNow.plusSeconds(1)
                                fakeNow
                            },
                        )
                    SimClientDriver(harness, playerLog, maxTurns = 30).runOneGame()
                }

            assertSoftly {
                stats.validationViolationsByCheck shouldBe emptyMap()
                stats.warnsByLogger shouldNotContainKey "leyline.game.mapping.ZoneMapper"
            }
        }
    })
