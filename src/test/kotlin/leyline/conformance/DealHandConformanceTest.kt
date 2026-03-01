package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.game.GsmBuilder
import leyline.game.mapper.PromptIds
import leyline.protocol.HandshakeMessages
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
class DealHandConformanceTest :
    FunSpec({
        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        /** Helper: extract GRE messages from a MatchServiceToClientMessage. */
        fun greMessages(msg: MatchServiceToClientMessage): List<GREToClientMessage> =
            msg.greToClientEvent.greToClientMessagesList

        // --- dealHand ---

        test("dealHand seat 1: 1 GRE msg, Diff GSM with zones and objects") {
            val (b, _, _) = base.startWithBoard { _, human, _ ->
                repeat(7) { base.addCard("Plains", human, ZoneType.Hand) }
                repeat(53) { base.addCard("Plains", human, ZoneType.Library) }
            }
            val (msg, nextMsgId) = HandshakeMessages.dealHand(6, 2, b, seatId = 1)
            val messages = greMessages(msg)

            messages.size shouldBe 1
            nextMsgId shouldBe 7

            val gre = messages[0]
            gre.type shouldBe GREMessageType.GameStateMessage_695e
            gre.msgId shouldBe 6

            val gsm = gre.gameStateMessage
            gsm.type shouldBe GameStateType.Diff
            gsm.update shouldBe GameStateUpdate.SendAndRecord
            gsm.gameStateId shouldBe 2
            gsm.prevGameStateId shouldBe 1

            gsm.zonesCount shouldBe 4
            (gsm.gameObjectsCount > 0).shouldBeTrue()

            gsm.playersCount shouldBe 2
            for (player in gsm.playersList) {
                player.pendingMessageType shouldBe ClientMessageType.MulliganResp_097b
            }
        }

        // --- dealHandMulliganSeat2 ---

        test("dealHandMulliganSeat2: 2 msgs (GSM + MulliganReq)") {
            val (b, _, _) = base.startWithBoard { _, human, ai ->
                repeat(7) { base.addCard("Plains", human, ZoneType.Hand) }
                repeat(53) { base.addCard("Plains", human, ZoneType.Library) }
                repeat(7) { base.addCard("Plains", ai, ZoneType.Hand) }
                repeat(53) { base.addCard("Plains", ai, ZoneType.Library) }
            }
            val (msg, nextMsgId) = HandshakeMessages.dealHandMulliganSeat2(6, 2, b)
            val messages = greMessages(msg)

            messages.size shouldBe 2
            nextMsgId shouldBe 8

            val gsm = messages[0]
            gsm.type shouldBe GREMessageType.GameStateMessage_695e
            gsm.gameStateMessage.type shouldBe GameStateType.Diff
            gsm.gameStateMessage.update shouldBe GameStateUpdate.SendAndRecord
            gsm.gameStateMessage.zonesCount shouldBe 4
            (gsm.gameStateMessage.gameObjectsCount > 0).shouldBeTrue()
            gsm.gameStateMessage.pendingMessageCount shouldBe 1

            val mull = messages[1]
            mull.type shouldBe GREMessageType.MulliganReq_aa0d
            mull.hasPrompt().shouldBeTrue()
            mull.prompt.promptId shouldBe PromptIds.MULLIGAN
        }

        // --- mulliganReqSeat1 ---

        test("mulliganReqSeat1: 3 msgs (thin Diff + PromptReq + MulliganReq)") {
            val (b, _, _) = base.startWithBoard { _, _, _ -> }
            val (msg, nextMsgId) = HandshakeMessages.mulliganReqSeat1(10, 3, b)
            val messages = greMessages(msg)

            messages.size shouldBe 3
            nextMsgId shouldBe 13

            val gsm = messages[0].gameStateMessage
            messages[0].type shouldBe GREMessageType.GameStateMessage_695e
            gsm.type shouldBe GameStateType.Diff
            gsm.update shouldBe GameStateUpdate.SendAndRecord
            gsm.zonesCount shouldBe 0
            gsm.gameObjectsCount shouldBe 0
            gsm.turnInfo.decisionPlayer shouldBe 1
            gsm.pendingMessageCount shouldBe 2
            gsm.prevGameStateId shouldBe 2

            val prompt = messages[1]
            prompt.type shouldBe GREMessageType.PromptReq
            prompt.hasPrompt().shouldBeTrue()
            prompt.prompt.promptId shouldBe PromptIds.STARTING_PLAYER

            val mull = messages[2]
            mull.type shouldBe GREMessageType.MulliganReq_aa0d
            mull.hasPrompt().shouldBeTrue()
            mull.prompt.promptId shouldBe PromptIds.MULLIGAN
        }

        // --- initialBundle ---

        test("initialBundle seat 1: ConnectResp + DieRoll + Full GSM (3 msgs)") {
            val (b, _, _) = base.startWithBoard { _, _, _ -> }
            val deck = GsmBuilder.buildDeckMessage(b.getDeckGrpIds(1))
            val (msg, nextMsgId) = HandshakeMessages.initialBundle(1, ConformanceTestBase.TEST_MATCH_ID, 2, 1, deck, b)
            val messages = greMessages(msg)

            messages.size shouldBe 3
            nextMsgId shouldBe 5

            messages[0].type shouldBe GREMessageType.ConnectResp_695e
            messages[1].type shouldBe GREMessageType.DieRollResultsResp_695e
            messages[2].type shouldBe GREMessageType.GameStateMessage_695e

            val gsm = messages[2].gameStateMessage
            gsm.type shouldBe GameStateType.Full
            gsm.zonesCount shouldBe 17
            gsm.teamsCount shouldBe 2
            gsm.playersCount shouldBe 2
            gsm.gameInfo.stage shouldBe GameStage.Start_a920
        }

        test("initialBundle seat 2: DieRoll + Full GSM + ChooseStartingPlayerReq") {
            val (b, _, _) = base.startWithBoard { _, _, _ -> }
            val deck = GsmBuilder.buildDeckMessage(b.getDeckGrpIds(2))
            val (msg, nextMsgId) = HandshakeMessages.initialBundle(2, ConformanceTestBase.TEST_MATCH_ID, 3, 1, deck, b)
            val messages = greMessages(msg)

            messages.size shouldBe 3
            nextMsgId shouldBe 6

            messages[0].type shouldBe GREMessageType.DieRollResultsResp_695e
            messages[1].type shouldBe GREMessageType.GameStateMessage_695e
            messages[2].type shouldBe GREMessageType.ChooseStartingPlayerReq_695e

            val gsm = messages[1].gameStateMessage
            gsm.type shouldBe GameStateType.Full
            gsm.zonesCount shouldBe 17
            gsm.pendingMessageCount shouldBe 1

            val req = messages[2].chooseStartingPlayerReq
            req.systemSeatIdsCount shouldBe 2
        }

        // --- settingsResp ---

        test("settingsResp round-trips settings and advances msgId") {
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

            messages.size shouldBe 1
            nextMsgId shouldBe 10

            val gre = messages[0]
            gre.type shouldBe GREMessageType.SetSettingsResp_695e
            gre.msgId shouldBe 9
            gre.setSettingsResp.settings shouldBe settings
        }

        test("settingsResp with null settings produces empty resp") {
            val (msg, nextMsgId) = HandshakeMessages.settingsResp(2, 8, 2, null)
            val messages = greMessages(msg)

            messages.size shouldBe 1
            nextMsgId shouldBe 9

            val gre = messages[0]
            gre.type shouldBe GREMessageType.SetSettingsResp_695e
            gre.hasSetSettingsResp().shouldBeTrue()
        }
    })
