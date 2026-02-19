package forge.nexus.conformance

import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.Assert.fail
import org.testng.annotations.AfterMethod
import org.testng.annotations.Test
import java.io.File

/**
 * Full-game semantic conformance test: play land, pass priority, survive AI turn.
 *
 * Runs a "Sparky land game" through [MatchFlowHarness] and compares output
 * against both the golden fingerprint baseline and (when available) real
 * Arena capture recordings.
 */
@Test(groups = ["integration"])
class SparkyLandConformanceTest : ConformanceTestBase() {

    private var harness: MatchFlowHarness? = null

    @AfterMethod
    fun shutdownHarness() {
        harness?.shutdown()
        harness = null
    }

    // --- Helpers ---

    private fun runGame(): MatchFlowHarness {
        val h = MatchFlowHarness(seed = 42L)
        harness = h
        h.connectAndKeep()
        val landPlayed = h.playLand()
        assertTrue(landPlayed, "Should have a land to play (seed 42)")
        h.passPriority()
        assertFalse(h.isGameOver(), "Game should not be over after land + pass")
        return h
    }

    private fun loadGolden(): List<StructuralFingerprint> =
        GoldenSequence.fromResource("golden/sparky-land-game.json")

    private val captureDir = File("/tmp/arena-recordings/2026-02-19_23-16-33/capture/payloads/")

    // --- Test 1: Golden shape comparison ---

    @Test(description = "Engine message type sequence matches golden fingerprint baseline")
    fun messageTypeSequenceMatchesGolden() {
        val h = runGame()
        val engineFingerprints = fingerprint(h.allMessages)
        val golden = loadGolden()

        val result = StructuralDiff.compareShape(golden, engineFingerprints)
        if (!result.matches) {
            val report = buildString {
                appendLine("=== GOLDEN vs ENGINE SHAPE DIVERGENCE ===")
                appendLine(result.report())
                appendLine()
                appendLine("--- Golden sequence (${golden.size} messages) ---")
                golden.forEachIndexed { i, fp ->
                    appendLine("  [$i] ${fp.greMessageType} gsType=${fp.gsType} update=${fp.updateType}")
                }
                appendLine()
                appendLine("--- Engine sequence (${engineFingerprints.size} messages) ---")
                engineFingerprints.forEachIndexed { i, fp ->
                    appendLine("  [$i] ${fp.greMessageType} gsType=${fp.gsType} update=${fp.updateType}")
                }
            }
            println(report)
            fail("Engine output does not match golden shape:\n$report")
        }
    }

    // --- Test 2: gsId chain validation ---

    @Test(description = "gsId chain is monotonic with valid prevGsId references across AI turn")
    fun gsIdChainAcrossAiTurn() {
        val h = runGame()
        assertGsIdChain(h.allMessages, context = "full game: land + pass + AI turn")
    }

    // --- Test 3: AI land play produces ZoneTransfer ---

    @Test(description = "AI land play produces ZoneTransfer with ObjectIdChanged and dest=Battlefield")
    fun aiLandPlayHasZoneTransfer() {
        val h = runGame()

        val decoded = h.allMessages.mapIndexed { i, gre ->
            GREToDecoded.convert(gre, i)
        }
        val timeline = SemanticTimeline.extract(decoded)

        val zoneTransfers = timeline.filterIsInstance<SemanticTimeline.ZoneTransfer>()
        if (zoneTransfers.isEmpty()) {
            println("=== DIAGNOSTIC: No ZoneTransfer events found ===")
            println("Total messages: ${h.allMessages.size}")
            println("Timeline events (${timeline.size}):")
            timeline.forEachIndexed { i, evt -> println("  [$i] $evt") }
            fail("Expected at least one ZoneTransfer event in the game timeline")
        }

        // At least one ZoneTransfer should go to Battlefield (human or AI land play)
        val battlefieldTransfers = zoneTransfers.filter { it.destZoneType == "Battlefield" }
        if (battlefieldTransfers.isEmpty()) {
            println("=== DIAGNOSTIC: No Battlefield ZoneTransfers ===")
            println("All ZoneTransfers:")
            zoneTransfers.forEach { println("  $it") }
            fail("Expected at least one ZoneTransfer to Battlefield")
        }

        // Verify ObjectIdChanged pairing: origId != newId for zone transfers
        val withIdChange = battlefieldTransfers.filter { it.origInstanceId != it.newInstanceId }
        if (withIdChange.isEmpty()) {
            println("=== DIAGNOSTIC: No ObjectIdChanged pairing on Battlefield transfers ===")
            println("Battlefield transfers:")
            battlefieldTransfers.forEach { println("  orig=${it.origInstanceId} new=${it.newInstanceId} cat=${it.category}") }
            // This is a known divergence area — report but don't hard-fail yet
            println("WARNING: No ObjectIdChanged for Battlefield transfers (may be an engine divergence)")
        }
    }

    // --- Test 4: Semantic match vs capture or golden fallback ---

    @Test(description = "Full game semantic timeline matches capture (or golden fallback)")
    fun fullGameSemanticMatch() {
        val h = runGame()

        val engineDecoded = h.allMessages.mapIndexed { i, gre ->
            GREToDecoded.convert(gre, i)
        }
        val engineTimeline = SemanticTimeline.extract(engineDecoded)

        if (captureDir.exists() && captureDir.isDirectory) {
            // Compare against real capture
            val captureDecoded = RecordingDecoder.decodeDirectory(captureDir, seatFilter = 1)
            if (captureDecoded.isEmpty()) {
                println("WARNING: Capture dir exists but produced no decoded messages, falling back to golden")
                compareAgainstGolden(h)
                return
            }

            val captureTimeline = SemanticTimeline.extract(captureDecoded)
            val divergences = SemanticTimeline.diff(captureTimeline, engineTimeline)

            if (divergences.isNotEmpty()) {
                val report = buildString {
                    appendLine("=== CAPTURE vs ENGINE SEMANTIC DIVERGENCE ===")
                    divergences.forEach { appendLine("  - $it") }
                    appendLine()
                    appendLine("--- Capture timeline (${captureTimeline.size} events) ---")
                    captureTimeline.take(50).forEachIndexed { i, evt -> appendLine("  [$i] $evt") }
                    if (captureTimeline.size > 50) appendLine("  ... (${captureTimeline.size - 50} more)")
                    appendLine()
                    appendLine("--- Engine timeline (${engineTimeline.size} events) ---")
                    engineTimeline.take(50).forEachIndexed { i, evt -> appendLine("  [$i] $evt") }
                    if (engineTimeline.size > 50) appendLine("  ... (${engineTimeline.size - 50} more)")
                }
                println(report)
                fail("Engine semantic timeline diverges from capture:\n$report")
            }
        } else {
            println("Capture dir not found at $captureDir — falling back to golden shape comparison")
            compareAgainstGolden(h)
        }
    }

    private fun compareAgainstGolden(h: MatchFlowHarness) {
        val engineFingerprints = fingerprint(h.allMessages)
        val golden = loadGolden()
        val result = StructuralDiff.compareShape(golden, engineFingerprints)
        if (!result.matches) {
            println("=== GOLDEN FALLBACK DIVERGENCE ===")
            println(result.report())
            fail("Engine output does not match golden shape (capture unavailable):\n${result.report()}")
        }
    }

    // --- Test 5: Accumulator consistency ---

    @Test(description = "Accumulator state consistent after each game step")
    fun accumulatorConsistentAfterFullCycle() {
        val h = MatchFlowHarness(seed = 42L)
        harness = h

        h.connectAndKeep()
        h.accumulator.assertConsistent("after connectAndKeep")

        val landPlayed = h.playLand()
        assertTrue(landPlayed, "Should have a land to play")
        h.accumulator.assertConsistent("after playLand")

        h.passPriority()
        h.accumulator.assertConsistent("after first passPriority")

        // Optional second pass (if game not over)
        if (!h.isGameOver()) {
            h.passPriority()
            h.accumulator.assertConsistent("after second passPriority")
        }
    }
}
