package forge.nexus.conformance

import forge.game.zone.ZoneType
import forge.nexus.game.GsmBuilder
import forge.nexus.game.mapper.PromptIds
import forge.nexus.protocol.HandshakeMessages
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Structural tests for pre-mulligan handshake messages produced by [HandshakeMessages].
 *
 * Verifies message counts, ordering, GRE types, gsId/msgId advancement,
 * pendingMessageCount, and seat-specific bundle composition.
 *
 * Field-level coverage is handled by [GoldenFieldCoverageTest] — this class
 * focuses on bundle shape and value correctness.
 */
@Test(groups = ["conformance"])
class DealHandConformanceTest : ConformanceTestBase() {

    /** Helper: extract GRE messages from a MatchServiceToClientMessage. */
    private fun greMessages(msg: MatchServiceToClientMessage): List<GREToClientMessage> =
        msg.greToClientEvent.greToClientMessagesList

    // --- dealHand ---

    @Test(description = "dealHand seat 1: 1 GRE msg, Diff GSM with zones and objects")
    fun dealHandSeat1Structure() {
        val (b, _, _) = startWithBoard { _, human, _ ->
            // 7 cards in hand to simulate a dealt hand
            repeat(7) { addCard("Plains", human, ZoneType.Hand) }
            repeat(53) { addCard("Plains", human, ZoneType.Library) }
        }
        val (msg, nextMsgId) = HandshakeMessages.dealHand(6, 2, b, seatId = 1)
        val messages = greMessages(msg)

        assertEquals(messages.size, 1, "Should produce 1 GRE message")
        assertEquals(nextMsgId, 7, "Next msgId should advance by 1")

        val gre = messages[0]
        assertEquals(gre.type, GREMessageType.GameStateMessage_695e, "GRE type")
        assertEquals(gre.msgId, 6, "msgId")

        val gsm = gre.gameStateMessage
        assertEquals(gsm.type, GameStateType.Diff, "GSM type should be Diff")
        assertEquals(gsm.update, GameStateUpdate.SendAndRecord, "Update type")
        assertEquals(gsm.gameStateId, 2, "gsId")
        assertEquals(gsm.prevGameStateId, 1, "prevGsId = gsId - 1")

        // 4 zones: hand + library for each player
        assertEquals(gsm.zonesCount, 4, "Zone count (hand+library per player)")
        // Objects: viewing seat's hand cards
        assertTrue(gsm.gameObjectsCount > 0, "Should have game objects for viewing seat's hand")

        // Both players present with MulliganResp pending
        assertEquals(gsm.playersCount, 2, "Should have 2 players")
        for (player in gsm.playersList) {
            assertEquals(
                player.pendingMessageType,
                ClientMessageType.MulliganResp_097b,
                "Player seat ${player.systemSeatNumber} should have MulliganResp pending",
            )
        }
    }

    // --- dealHandMulliganSeat2 ---

    @Test(description = "dealHandMulliganSeat2: 2 msgs (GSM + MulliganReq)")
    fun dealHandMulliganSeat2Structure() {
        val (b, _, _) = startWithBoard { _, human, ai ->
            repeat(7) { addCard("Plains", human, ZoneType.Hand) }
            repeat(53) { addCard("Plains", human, ZoneType.Library) }
            // Seat 2 (AI) also needs cards for dealHandMulliganSeat2
            repeat(7) { addCard("Plains", ai, ZoneType.Hand) }
            repeat(53) { addCard("Plains", ai, ZoneType.Library) }
        }
        val (msg, nextMsgId) = HandshakeMessages.dealHandMulliganSeat2(6, 2, b)
        val messages = greMessages(msg)

        assertEquals(messages.size, 2, "Should produce 2 GRE messages")
        assertEquals(nextMsgId, 8, "Next msgId should advance by 2")

        // Message 0: GSM with deal-hand state
        val gsm = messages[0]
        assertEquals(gsm.type, GREMessageType.GameStateMessage_695e, "msg[0] GRE type")
        assertEquals(gsm.gameStateMessage.type, GameStateType.Diff, "msg[0] GSM type")
        assertEquals(gsm.gameStateMessage.update, GameStateUpdate.SendAndRecord, "msg[0] update")
        assertEquals(gsm.gameStateMessage.zonesCount, 4, "msg[0] zone count")
        assertTrue(gsm.gameStateMessage.gameObjectsCount > 0, "msg[0] should have objects")
        assertEquals(gsm.gameStateMessage.pendingMessageCount, 1, "msg[0] pendingMessageCount (MulliganReq follows)")

        // Message 1: MulliganReq with promptId=34
        val mull = messages[1]
        assertEquals(mull.type, GREMessageType.MulliganReq_aa0d, "msg[1] GRE type")
        assertTrue(mull.hasPrompt(), "msg[1] should have prompt")
        assertEquals(mull.prompt.promptId, PromptIds.MULLIGAN, "msg[1] promptId = MULLIGAN (34)")
    }

    // --- mulliganReqSeat1 ---

    @Test(description = "mulliganReqSeat1: 3 msgs (thin Diff + PromptReq + MulliganReq)")
    fun mulliganReqSeat1Structure() {
        val (b, _, _) = startWithBoard { _, _, _ -> }
        val (msg, nextMsgId) = HandshakeMessages.mulliganReqSeat1(10, 3, b)
        val messages = greMessages(msg)

        assertEquals(messages.size, 3, "Should produce 3 GRE messages")
        assertEquals(nextMsgId, 13, "Next msgId should advance by 3")

        // Message 0: thin Diff GSM (seat 2 status, decisionPlayer=1)
        val gsm = messages[0].gameStateMessage
        assertEquals(messages[0].type, GREMessageType.GameStateMessage_695e, "msg[0] GRE type")
        assertEquals(gsm.type, GameStateType.Diff, "msg[0] GSM type")
        assertEquals(gsm.update, GameStateUpdate.SendAndRecord, "msg[0] update")
        assertEquals(gsm.zonesCount, 0, "msg[0] should have 0 zones (thin Diff)")
        assertEquals(gsm.gameObjectsCount, 0, "msg[0] should have 0 objects (thin Diff)")
        assertEquals(gsm.turnInfo.decisionPlayer, 1, "msg[0] decisionPlayer should be seat 1")
        assertEquals(gsm.pendingMessageCount, 2, "msg[0] pendingMessageCount (PromptReq + MulliganReq)")
        assertEquals(gsm.prevGameStateId, 2, "msg[0] prevGsId = gsId - 1")

        // Message 1: PromptReq (promptId=37, "who goes first")
        val prompt = messages[1]
        assertEquals(prompt.type, GREMessageType.PromptReq, "msg[1] GRE type")
        assertTrue(prompt.hasPrompt(), "msg[1] should have prompt")
        assertEquals(prompt.prompt.promptId, PromptIds.STARTING_PLAYER, "msg[1] promptId = STARTING_PLAYER (37)")

        // Message 2: MulliganReq (promptId=34)
        val mull = messages[2]
        assertEquals(mull.type, GREMessageType.MulliganReq_aa0d, "msg[2] GRE type")
        assertTrue(mull.hasPrompt(), "msg[2] should have prompt")
        assertEquals(mull.prompt.promptId, PromptIds.MULLIGAN, "msg[2] promptId = MULLIGAN (34)")
    }

    // --- initialBundle ---

    @Test(description = "initialBundle seat 1: ConnectResp + DieRoll + Full GSM (3 msgs)")
    fun initialBundleSeat1Structure() {
        val (b, _, _) = startWithBoard { _, _, _ -> }
        val deck = GsmBuilder.buildDeckMessage(b.getDeckGrpIds(1))
        val (msg, nextMsgId) = HandshakeMessages.initialBundle(1, TEST_MATCH_ID, 2, 1, deck, b)
        val messages = greMessages(msg)

        assertEquals(messages.size, 3, "Should produce 3 GRE messages")
        assertEquals(nextMsgId, 5, "Next msgId should advance by 3")

        // Message order: ConnectResp → DieRollResultsResp → Full GSM
        assertEquals(messages[0].type, GREMessageType.ConnectResp_695e, "msg[0] = ConnectResp")
        assertEquals(messages[1].type, GREMessageType.DieRollResultsResp_695e, "msg[1] = DieRollResultsResp")
        assertEquals(messages[2].type, GREMessageType.GameStateMessage_695e, "msg[2] = GameStateMessage")

        // Full GSM structural checks
        val gsm = messages[2].gameStateMessage
        assertEquals(gsm.type, GameStateType.Full, "GSM type = Full")
        assertEquals(gsm.zonesCount, 17, "Zone count (9 shared + 4 per player)")
        assertEquals(gsm.teamsCount, 2, "2 teams")
        assertEquals(gsm.playersCount, 2, "2 players")
        assertEquals(gsm.gameInfo.stage, GameStage.Start_a920, "Stage = Start")
    }

    @Test(description = "initialBundle seat 2: DieRoll + Full GSM + ChooseStartingPlayerReq")
    fun initialBundleSeat2Structure() {
        val (b, _, _) = startWithBoard { _, _, _ -> }
        val deck = GsmBuilder.buildDeckMessage(b.getDeckGrpIds(2))
        val (msg, nextMsgId) = HandshakeMessages.initialBundle(2, TEST_MATCH_ID, 3, 1, deck, b)
        val messages = greMessages(msg)

        assertEquals(messages.size, 3, "Should produce 3 GRE messages")
        assertEquals(nextMsgId, 6, "Next msgId should advance by 3")

        // Seat 2: no ConnectResp — order is DieRoll → Full GSM → ChooseStartingPlayerReq
        assertEquals(messages[0].type, GREMessageType.DieRollResultsResp_695e, "msg[0] = DieRollResultsResp")
        assertEquals(messages[1].type, GREMessageType.GameStateMessage_695e, "msg[1] = GameStateMessage")
        assertEquals(messages[2].type, GREMessageType.ChooseStartingPlayerReq_695e, "msg[2] = ChooseStartingPlayerReq")

        val gsm = messages[1].gameStateMessage
        assertEquals(gsm.type, GameStateType.Full, "GSM type = Full")
        assertEquals(gsm.zonesCount, 17, "Zone count")
        assertEquals(gsm.pendingMessageCount, 1, "pendingMessageCount (ChooseStartingPlayerReq follows)")

        val req = messages[2].chooseStartingPlayerReq
        assertEquals(req.systemSeatIdsCount, 2, "ChooseStartingPlayerReq lists 2 seats")
    }

    // --- settingsResp ---

    @Test(description = "settingsResp round-trips settings and advances msgId")
    fun settingsRespRoundTrip() {
        val settings = SettingsMessage.newBuilder()
            .addStops(
                Stop.newBuilder()
                    .setStopType(StopType.PrecombatMainPhase)
                    .setAppliesTo(SettingScope.Team_ac6e)
                    .setStatus(SettingStatus.Set),
            )
            .setAutoPassOption(AutoPassOption.ResolveMyStackEffects)
            .build()

        val (msg, nextMsgId) = HandshakeMessages.settingsResp(1, 9, 2, settings)
        val messages = greMessages(msg)

        assertEquals(messages.size, 1, "Should produce 1 GRE message")
        assertEquals(nextMsgId, 10, "Next msgId should advance by 1")

        val gre = messages[0]
        assertEquals(gre.type, GREMessageType.SetSettingsResp_695e, "GRE type")
        assertEquals(gre.msgId, 9, "msgId")
        assertEquals(gre.setSettingsResp.settings, settings, "Settings should round-trip exactly")
    }

    @Test(description = "settingsResp with null settings produces empty resp")
    fun settingsRespNullSettings() {
        val (msg, nextMsgId) = HandshakeMessages.settingsResp(2, 8, 2, null)
        val messages = greMessages(msg)

        assertEquals(messages.size, 1, "Should produce 1 GRE message")
        assertEquals(nextMsgId, 9, "Next msgId should advance by 1")

        val gre = messages[0]
        assertEquals(gre.type, GREMessageType.SetSettingsResp_695e, "GRE type")
        assertTrue(gre.hasSetSettingsResp(), "Should have SetSettingsResp")
    }
}
