package forge.nexus.conformance

import forge.nexus.game.GameBridge
import forge.nexus.protocol.Templates
import forge.web.game.GameBootstrap
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.AfterMethod
import org.testng.annotations.BeforeClass
import org.testng.annotations.Test

/**
 * Compares dynamically-built pre-mulligan messages against recorded Arena .bin templates.
 *
 * The .bin files are the real server's output. Our dynamic builders must produce
 * structurally equivalent messages: same GRE types, GSM type, update type,
 * annotations, zone count, and prompt fields.
 *
 * Known intentional difference: .bin templates contain embedded actions from the
 * recording's deck. The old pipeline cleared these (stale costs cause "Cost Modified"
 * overlay), and our dynamic builder also omits them. So "actions" in fieldPresence
 * is expected to differ.
 */
class DealHandConformanceTest {

    private var bridge: GameBridge? = null

    @BeforeClass(alwaysRun = true)
    fun initCardDatabase() {
        GameBootstrap.initializeCardDatabase()
    }

    @AfterMethod
    fun tearDown() {
        bridge?.shutdown()
        bridge = null
    }

    private fun loadBin(name: String): ByteArray =
        javaClass.classLoader.getResourceAsStream("arena-templates/$name")!!.readBytes()

    private fun startBridge(): GameBridge {
        val b = GameBridge()
        bridge = b
        b.start(seed = 42L)
        return b
    }

    @Test
    fun dealHandSeat1MatchesRecording() {
        val binFps = RecordingParser.parsePayload(loadBin("deal-hand-seat1.bin"))
        assertEquals(binFps.size, 1, "Recording should have 1 GRE message")

        val b = startBridge()
        val (msg, _) = Templates.dealHandSeat1(6, 2, b)
        val dynFps = msg.greToClientEvent.greToClientMessagesList
            .map { StructuralFingerprint.fromGRE(it) }
        assertEquals(dynFps.size, 1, "Dynamic should have 1 GRE message")

        val golden = binFps[0]
        val actual = dynFps[0]

        // Core structural match
        assertEquals(actual.greMessageType, golden.greMessageType, "GRE message type")
        assertEquals(actual.gsType, golden.gsType, "GSM type")
        assertEquals(actual.updateType, golden.updateType, "Update type")
        assertEquals(actual.annotationTypes, golden.annotationTypes, "Annotation types")
        assertEquals(actual.zoneCount, golden.zoneCount, "Zone count")

        // Field presence: actual must have everything golden has, except "actions"
        // (actions were always cleared by the old pipeline — intentional omission)
        val requiredFields = golden.fieldPresence - "actions"
        val missing = requiredFields - actual.fieldPresence
        assertTrue(missing.isEmpty(), "Missing required fields: $missing")
    }

    @Test
    fun dealHandMulliganSeat2MatchesRecording() {
        val binFps = RecordingParser.parsePayload(loadBin("deal-hand-mulligan-seat2.bin"))
        assertEquals(binFps.size, 2, "Recording should have 2 GRE messages (GSM + MulliganReq)")

        val b = startBridge()
        val (msg, _) = Templates.dealHandMulliganSeat2(6, 2, b)
        val dynFps = msg.greToClientEvent.greToClientMessagesList
            .map { StructuralFingerprint.fromGRE(it) }
        assertEquals(dynFps.size, 2, "Dynamic should have 2 GRE messages")

        // Message 0: GameStateMessage (deal hand)
        val goldenGsm = binFps[0]
        val actualGsm = dynFps[0]
        assertEquals(actualGsm.greMessageType, goldenGsm.greMessageType, "GSM: GRE message type")
        assertEquals(actualGsm.gsType, goldenGsm.gsType, "GSM: type")
        assertEquals(actualGsm.updateType, goldenGsm.updateType, "GSM: update type")
        assertEquals(actualGsm.annotationTypes, goldenGsm.annotationTypes, "GSM: annotation types")
        assertEquals(actualGsm.zoneCount, goldenGsm.zoneCount, "GSM: zone count")

        val requiredFields = goldenGsm.fieldPresence - "actions"
        val missing = requiredFields - actualGsm.fieldPresence
        assertTrue(missing.isEmpty(), "GSM: Missing required fields: $missing")

        // Message 1: MulliganReq
        val goldenMull = binFps[1]
        val actualMull = dynFps[1]
        assertEquals(actualMull.greMessageType, goldenMull.greMessageType, "MulliganReq: GRE type")
        assertEquals(actualMull.hasPrompt, goldenMull.hasPrompt, "MulliganReq: has prompt")
        assertEquals(actualMull.promptId, goldenMull.promptId, "MulliganReq: prompt ID")
    }

    @Test
    fun mulliganReqSeat1MatchesRecording() {
        val binFps = RecordingParser.parsePayload(loadBin("mulligan-req-seat1.bin"))
        assertEquals(binFps.size, 3, "Recording should have 3 GRE messages (GSM + PromptReq + MulliganReq)")

        val b = startBridge()
        val (msg, _) = Templates.mulliganReqSeat1(10, 3, b)
        val dynFps = msg.greToClientEvent.greToClientMessagesList
            .map { StructuralFingerprint.fromGRE(it) }
        assertEquals(dynFps.size, 3, "Dynamic should have 3 GRE messages")

        // Message 0: GameStateMessage (thin Diff — seat 2 status, decisionPlayer=1)
        val goldenGsm = binFps[0]
        val actualGsm = dynFps[0]
        assertEquals(actualGsm.greMessageType, goldenGsm.greMessageType, "GSM: GRE message type")
        assertEquals(actualGsm.gsType, goldenGsm.gsType, "GSM: type")
        assertEquals(actualGsm.updateType, goldenGsm.updateType, "GSM: update type")
        assertEquals(actualGsm.zoneCount, goldenGsm.zoneCount, "GSM: zone count (0)")
        assertEquals(actualGsm.objectCount, goldenGsm.objectCount, "GSM: object count (0)")

        val requiredFields = goldenGsm.fieldPresence - "actions"
        val missing = requiredFields - actualGsm.fieldPresence
        assertTrue(missing.isEmpty(), "GSM: Missing required fields: $missing")

        // Message 1: PromptReq (promptId=37, "who goes first")
        val goldenPrompt = binFps[1]
        val actualPrompt = dynFps[1]
        assertEquals(actualPrompt.greMessageType, goldenPrompt.greMessageType, "PromptReq: GRE type")
        assertEquals(actualPrompt.hasPrompt, goldenPrompt.hasPrompt, "PromptReq: has prompt")
        assertEquals(actualPrompt.promptId, goldenPrompt.promptId, "PromptReq: prompt ID")

        // Message 2: MulliganReq (promptId=34, NumberOfCards=7)
        val goldenMull = binFps[2]
        val actualMull = dynFps[2]
        assertEquals(actualMull.greMessageType, goldenMull.greMessageType, "MulliganReq: GRE type")
        assertEquals(actualMull.hasPrompt, goldenMull.hasPrompt, "MulliganReq: has prompt")
        assertEquals(actualMull.promptId, goldenMull.promptId, "MulliganReq: prompt ID")
    }
}
