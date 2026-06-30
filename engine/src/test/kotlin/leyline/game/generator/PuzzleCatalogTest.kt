package leyline.game.generator

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import java.nio.file.Files

/**
 * Tests for [PuzzleCatalog] — directory enumeration and metadata mapping.
 * Pure file IO + string parsing; no Forge localization required.
 */
class PuzzleCatalogTest :
    FunSpec({

        tags(UnitTag)

        test("lists pzl files with parsed metadata, sorted by name") {
            val dir = Files.createTempDirectory("puzzle-catalog").toFile()
            dir.resolve("alpha.pzl").writeText(
                """
                [metadata]
                Name:Zeta Puzzle
                Goal:Win
                Turns:4
                Difficulty:Tutorial
                Description:Do the thing.

                [state]
                ActivePlayer=Human
                """.trimIndent(),
            )
            dir.resolve("beta.pzl").writeText(
                """
                [metadata]
                Name:Alpha Puzzle
                Goal:Survive

                [state]
                ActivePlayer=Human
                """.trimIndent(),
            )
            dir.resolve("ignored.txt").writeText("not a puzzle")

            val entries = PuzzleCatalog.list(dir)

            assertSoftly {
                entries.map { it.name } shouldBe listOf("Alpha Puzzle", "Zeta Puzzle")
                val zeta = entries.single { it.filename == "alpha" }
                zeta.name shouldBe "Zeta Puzzle"
                zeta.goal shouldBe "Win"
                zeta.turns shouldBe 4
                zeta.difficulty shouldBe "Tutorial"
                zeta.description shouldBe "Do the thing."
            }
        }

        test("returns empty list for missing directory") {
            val missing = Files.createTempDirectory("puzzle-catalog").toFile().resolve("nope")
            PuzzleCatalog.list(missing) shouldBe emptyList()
        }
    })
