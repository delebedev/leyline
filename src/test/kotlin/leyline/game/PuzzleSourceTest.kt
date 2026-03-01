package leyline.game

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.GameBootstrap

/**
 * Tests for [PuzzleSource] — puzzle loading and metadata parsing.
 *
 * Metadata parsing is pure string logic (unit group).
 * Puzzle construction requires Forge localization (integration group).
 */
class PuzzleSourceTest :
    FunSpec({
        beforeSpec {
            GameBootstrap.initializeLocalization()
        }

        test("parseMetadata extracts fields") {
            val content = """
                [metadata]
                Name:Lightning Bolt Test
                Goal:Win
                Turns:3
                Difficulty:Easy
                
                [state]
                ActivePlayer=Human
            """.trimIndent()

            val meta = PuzzleSource.parseMetadata(content)
            meta.name shouldBe "Lightning Bolt Test"
            meta.goal shouldBe "Win"
            meta.turns shouldBe 3
            meta.difficulty shouldBe "Easy"
        }

        test("parseMetadata handles missing fields") {
            val content = """
                [metadata]
                Name:Minimal
                
                [state]
                ActivePlayer=Human
            """.trimIndent()

            val meta = PuzzleSource.parseMetadata(content)
            meta.name shouldBe "Minimal"
            meta.goal.shouldBeNull()
            meta.turns.shouldBeNull()
            meta.difficulty.shouldBeNull()
        }

        test("loadFromResource returns valid puzzle") {
            val puzzle = PuzzleSource.loadFromResource("puzzles/lands-only.pzl")
            puzzle.shouldNotBeNull()
        }

        test("loadFromText returns valid puzzle") {
            val content = """
                [metadata]
                Name:Inline Test
                Goal:Win
                Turns:5
                
                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanlibrary=Forest;Forest;Forest;Forest;Forest
                ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
            """.trimIndent()

            val puzzle = PuzzleSource.loadFromText(content, "inline-test")
            puzzle.shouldNotBeNull()
        }
    })
