package forge.nexus.game

import forge.nexus.bridge.GameBootstrap
import org.testng.Assert.*
import org.testng.annotations.BeforeClass
import org.testng.annotations.Test

/**
 * Tests for [PuzzleSource] — puzzle loading and metadata parsing.
 *
 * Metadata parsing is pure string logic (unit group).
 * Puzzle construction requires Forge localization (integration group).
 */
class PuzzleSourceTest {

    @BeforeClass(alwaysRun = true)
    fun init() {
        GameBootstrap.initializeLocalization()
    }

    @Test(groups = ["unit"])
    fun parseMetadataExtractsFields() {
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
        assertEquals(meta.name, "Lightning Bolt Test")
        assertEquals(meta.goal, "Win")
        assertEquals(meta.turns, 3)
        assertEquals(meta.difficulty, "Easy")
    }

    @Test(groups = ["unit"])
    fun parseMetadataHandlesMissingFields() {
        val content = """
            [metadata]
            Name:Minimal
            
            [state]
            ActivePlayer=Human
        """.trimIndent()

        val meta = PuzzleSource.parseMetadata(content)
        assertEquals(meta.name, "Minimal")
        assertNull(meta.goal)
        assertNull(meta.turns)
        assertNull(meta.difficulty)
    }

    @Test(groups = ["conformance"])
    fun loadFromResourceReturnsValidPuzzle() {
        val puzzle = PuzzleSource.loadFromResource("puzzles/lands-only.pzl")
        assertNotNull(puzzle)
    }

    @Test(groups = ["conformance"])
    fun loadFromTextReturnsValidPuzzle() {
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
        assertNotNull(puzzle)
    }
}
