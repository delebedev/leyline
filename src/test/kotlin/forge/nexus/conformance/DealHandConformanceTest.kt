package forge.nexus.conformance

import forge.nexus.game.GameBridge
import forge.nexus.game.StateMapper
import forge.nexus.protocol.HandshakeMessages
import forge.web.game.GameBootstrap
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.AfterMethod
import org.testng.annotations.BeforeClass
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Compares dynamically-built pre-mulligan messages against recorded client .bin templates.
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
@Test(groups = ["integration"])
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
        val (msg, _) = HandshakeMessages.dealHandSeat1(6, 2, b)
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
        val (msg, _) = HandshakeMessages.dealHandMulliganSeat2(6, 2, b)
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
        val (msg, _) = HandshakeMessages.mulliganReqSeat1(10, 3, b)
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

    @Test
    fun settingsRespSeat1MatchesRecording() {
        val binFps = RecordingParser.parsePayload(loadBin("settings-resp-seat1.bin"))
        assertEquals(binFps.size, 1, "Recording should have 1 GRE message")

        // Parse the recording's settings to use as client input
        val binMsg = MatchServiceToClientMessage.parseFrom(loadBin("settings-resp-seat1.bin"))
        val binSettings = binMsg.greToClientEvent.getGreToClientMessages(0).setSettingsResp.settings

        val (msg, nextMsgId) = HandshakeMessages.settingsResp(1, 9, 2, binSettings)
        assertEquals(nextMsgId, 10, "Next msgId should be 10")

        val dynFps = msg.greToClientEvent.greToClientMessagesList
            .map { StructuralFingerprint.fromGRE(it) }
        assertEquals(dynFps.size, 1, "Dynamic should have 1 GRE message")

        val golden = binFps[0]
        val actual = dynFps[0]
        assertEquals(actual.greMessageType, golden.greMessageType, "GRE message type")

        // Verify settings round-trip: echoed settings match input
        val echoed = msg.greToClientEvent.getGreToClientMessages(0).setSettingsResp.settings
        assertEquals(echoed, binSettings, "Settings should round-trip exactly")
    }

    @Test
    fun initialBundleSeat2MatchesRecording() {
        val binFps = RecordingParser.parsePayload(loadBin("initial-bundle-seat2.bin"))
        assertEquals(binFps.size, 3, "Recording should have 3 GRE messages (DieRoll + GSM + ChooseStartingPlayerReq)")

        val b = startBridge()
        val deck = StateMapper.buildDeckMessage(b.getDeckGrpIds(2))
        val (msg, _) = HandshakeMessages.initialBundle(2, "test-match", 3, 1, deck, b)
        val dynFps = msg.greToClientEvent.greToClientMessagesList
            .map { StructuralFingerprint.fromGRE(it) }
        assertEquals(dynFps.size, 3, "Dynamic should have 3 GRE messages")

        // Message 0: DieRollResultsResp
        assertEquals(dynFps[0].greMessageType, binFps[0].greMessageType, "DieRoll: GRE type")

        // Message 1: GameStateMessage (Full)
        val goldenGsm = binFps[1]
        val actualGsm = dynFps[1]
        assertEquals(actualGsm.greMessageType, goldenGsm.greMessageType, "GSM: GRE type")
        assertEquals(actualGsm.gsType, goldenGsm.gsType, "GSM: type (Full)")
        assertEquals(actualGsm.updateType, goldenGsm.updateType, "GSM: update type")
        assertEquals(actualGsm.zoneCount, goldenGsm.zoneCount, "GSM: zone count (17)")

        val requiredFields = goldenGsm.fieldPresence - "actions"
        val missing = requiredFields - actualGsm.fieldPresence
        assertTrue(missing.isEmpty(), "GSM: Missing required fields: $missing")

        // Message 2: ChooseStartingPlayerReq
        assertEquals(dynFps[2].greMessageType, binFps[2].greMessageType, "ChooseStartingPlayerReq: GRE type")
    }

    @Test
    fun initialBundleSeat1MatchesRecording() {
        val binFps = RecordingParser.parsePayload(loadBin("initial-bundle-seat1.bin"))
        assertEquals(binFps.size, 3, "Recording should have 3 GRE messages (ConnectResp + DieRoll + GSM)")

        val b = startBridge()
        val deck = StateMapper.buildDeckMessage(b.getDeckGrpIds(1))
        val (msg, _) = HandshakeMessages.initialBundle(1, "test-match", 2, 1, deck, b)
        val dynFps = msg.greToClientEvent.greToClientMessagesList
            .map { StructuralFingerprint.fromGRE(it) }
        assertEquals(dynFps.size, 3, "Dynamic should have 3 GRE messages")

        // Message 0: ConnectResp
        assertEquals(dynFps[0].greMessageType, binFps[0].greMessageType, "ConnectResp: GRE type")

        // Message 1: DieRollResultsResp
        assertEquals(dynFps[1].greMessageType, binFps[1].greMessageType, "DieRoll: GRE type")

        // Message 2: GameStateMessage (Full)
        val goldenGsm = binFps[2]
        val actualGsm = dynFps[2]
        assertEquals(actualGsm.greMessageType, goldenGsm.greMessageType, "GSM: GRE type")
        assertEquals(actualGsm.gsType, goldenGsm.gsType, "GSM: type (Full)")
        assertEquals(actualGsm.updateType, goldenGsm.updateType, "GSM: update type")
        assertEquals(actualGsm.zoneCount, goldenGsm.zoneCount, "GSM: zone count (17)")

        val requiredFields = goldenGsm.fieldPresence - "actions"
        val missing = requiredFields - actualGsm.fieldPresence
        assertTrue(missing.isEmpty(), "GSM: Missing required fields: $missing")

        // Verify ConnectResp has deck
        val connectResp = msg.greToClientEvent.getGreToClientMessages(0).connectResp
        assertEquals(connectResp.status, ConnectionStatus.Success_aa9e, "ConnectResp status")
        assertTrue(connectResp.deckMessage.deckCardsCount > 0, "ConnectResp should have deck")
    }

    @Test
    fun settingsRespSeat2MatchesRecording() {
        val binFps = RecordingParser.parsePayload(loadBin("settings-resp-seat2.bin"))
        assertEquals(binFps.size, 1, "Recording should have 1 GRE message")

        val binMsg = MatchServiceToClientMessage.parseFrom(loadBin("settings-resp-seat2.bin"))
        val binSettings = binMsg.greToClientEvent.getGreToClientMessages(0).setSettingsResp.settings

        val (msg, nextMsgId) = HandshakeMessages.settingsResp(2, 8, 2, binSettings)
        assertEquals(nextMsgId, 9, "Next msgId should be 9")

        val dynFps = msg.greToClientEvent.greToClientMessagesList
            .map { StructuralFingerprint.fromGRE(it) }
        assertEquals(dynFps.size, 1, "Dynamic should have 1 GRE message")

        val golden = binFps[0]
        val actual = dynFps[0]
        assertEquals(actual.greMessageType, golden.greMessageType, "GRE message type")

        val echoed = msg.greToClientEvent.getGreToClientMessages(0).setSettingsResp.settings
        assertEquals(echoed, binSettings, "Settings should round-trip exactly")
    }
}
