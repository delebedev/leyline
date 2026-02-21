package forge.nexus.conformance

import org.testng.SkipException
import org.testng.annotations.AfterMethod
import org.testng.annotations.BeforeClass
import org.testng.annotations.Test
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Dumps engine and capture timelines side-by-side for manual inspection.
 *
 * Not run in CI — requires real client capture files.
 */
@Test(groups = ["recording"])
class TimelineDumpTest {

    private lateinit var harness: MatchFlowHarness

    @BeforeClass(alwaysRun = true)
    fun initCardDatabase() {
        forge.web.game.GameBootstrap.initializeCardDatabase()
    }

    @AfterMethod(alwaysRun = true)
    fun cleanup() {
        if (::harness.isInitialized) harness.shutdown()
    }

    @Test(groups = ["recording"])
    fun dumpTimelines() {
        val captureDir = File("/tmp/arena-recordings/2026-02-19_23-16-33/capture/payloads")
        if (!captureDir.isDirectory) {
            throw SkipException("Capture dir not found: $captureDir")
        }

        // --- Engine timeline ---
        // Use AI-first seed (2L) to match capture where AI goes first
        val h = MatchFlowHarness(MatchFlowHarnessTest.AI_FIRST_SEED)
        harness = h
        h.connectAndKeep()
        // Don't play land — in the capture, AI plays first and human
        // just gets priority at turn 2. Pass to trigger AI turn cycle.
        h.passPriority()

        val engineDecoded = h.allMessages.mapIndexed { i, gre ->
            GREToDecoded.convert(gre, i)
        }

        // --- Capture timeline ---
        val captureDecoded = RecordingDecoder.decodeDirectory(captureDir, seatFilter = 1)

        // --- Build output ---
        val sw = StringWriter()
        val out = PrintWriter(sw)

        out.println("=".repeat(100))
        out.println("TIMELINE DUMP — engine vs capture")
        out.println("=".repeat(100))

        out.println()
        out.println("-".repeat(100))
        out.println("ENGINE MESSAGES (${engineDecoded.size})")
        out.println("-".repeat(100))
        for (msg in engineDecoded) {
            printDecodedMessage(out, msg)
        }

        out.println()
        out.println("-".repeat(100))
        out.println("CAPTURE MESSAGES (${captureDecoded.size})")
        out.println("-".repeat(100))
        for (msg in captureDecoded) {
            printDecodedMessage(out, msg)
        }

        // --- Semantic timelines ---
        val engineEvents = SemanticTimeline.extract(engineDecoded)
        val captureEvents = SemanticTimeline.extract(captureDecoded)

        out.println()
        out.println("=".repeat(100))
        out.println("SEMANTIC TIMELINE — ENGINE (${engineEvents.size} events)")
        out.println("=".repeat(100))
        engineEvents.forEachIndexed { i, ev -> out.println("  [$i] $ev") }

        out.println()
        out.println("=".repeat(100))
        out.println("SEMANTIC TIMELINE — CAPTURE (${captureEvents.size} events)")
        out.println("=".repeat(100))
        captureEvents.forEachIndexed { i, ev -> out.println("  [$i] $ev") }

        // --- Diff ---
        val diffs = SemanticTimeline.diff(captureEvents, engineEvents)
        if (diffs.isNotEmpty()) {
            out.println()
            out.println("=".repeat(100))
            out.println("DIVERGENCES (${diffs.size})")
            out.println("=".repeat(100))
            diffs.forEach { out.println("  ! $it") }
        }

        out.flush()
        val text = sw.toString()

        // Print to stdout
        println(text)

        // Write to file
        val outFile = File("/tmp/sparky-land-timeline-diff.txt")
        outFile.writeText(text)
        println("\nWritten to: ${outFile.absolutePath}")
    }

    private fun printDecodedMessage(out: PrintWriter, msg: RecordingDecoder.DecodedMessage) {
        out.println()
        out.println("  [${msg.index}] greType=${msg.greType}  gsId=${msg.gsId}  prevGsId=${msg.prevGsId ?: "-"}  updateType=${msg.updateType ?: "-"}  file=${msg.file}")

        if (msg.turnInfo != null) {
            val ti = msg.turnInfo
            out.println("       turnInfo: phase=${ti.phase} step=${ti.step} turn=${ti.turn} active=${ti.activePlayer} priority=${ti.priorityPlayer} decision=${ti.decisionPlayer}")
        }

        if (msg.zones.isNotEmpty()) {
            out.println("       zones:")
            for (z in msg.zones) {
                out.println("         ${z.type}(z${z.zoneId}) owner=${z.owner} vis=${z.visibility} objs=${z.objectIds}")
            }
        }

        if (msg.objects.isNotEmpty()) {
            out.println("       objects:")
            for (o in msg.objects) {
                val pt = if (o.power != null) " ${o.power}/${o.toughness}" else ""
                val flags = buildList {
                    if (o.isTapped) add("tapped")
                    if (o.hasSummoningSickness) add("sick")
                }.joinToString(",")
                val flagStr = if (flags.isNotEmpty()) " [$flags]" else ""
                out.println("         ${o.type}#${o.instanceId}@z${o.zoneId} grp=${o.grpId}$pt$flagStr")
            }
        }

        if (msg.annotations.isNotEmpty()) {
            out.println("       annotations:")
            for (a in msg.annotations) {
                val affected = if (a.affectedIds.isNotEmpty()) "->${a.affectedIds}" else ""
                val details = if (a.details.isNotEmpty()) " ${a.details}" else ""
                out.println("         ${a.types} affector=${a.affectorId}$affected$details")
            }
        }

        if (msg.persistentAnnotations.isNotEmpty()) {
            out.println("       persistentAnnotations: ${msg.persistentAnnotations.size}")
        }

        if (msg.actions.isNotEmpty()) {
            out.println("       actions:")
            for (a in msg.actions) {
                out.println("         ${a.type} inst=${a.instanceId} grp=${a.grpId} seat=${a.seatId ?: "-"}")
            }
        }

        if (msg.players.isNotEmpty()) {
            out.println("       players: ${msg.players.joinToString { "seat${it.seat}(life=${it.life},${it.status})" }}")
        }

        val flags = buildList {
            if (msg.hasMulliganReq) add("mulligan")
            if (msg.hasActionsAvailableReq) add("actionsAvailable")
            if (msg.hasDeclareAttackersReq) add("declareAttackers")
            if (msg.hasDeclareBlockersReq) add("declareBlockers")
            if (msg.hasSelectTargetsReq) add("selectTargets")
            if (msg.hasIntermissionReq) add("intermission")
        }
        if (flags.isNotEmpty()) {
            out.println("       reqs: $flags")
        }
    }
}
