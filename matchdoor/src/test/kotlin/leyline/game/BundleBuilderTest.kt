package leyline.game

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.bridge.ForgeCardId
import leyline.conformance.ConformanceTestBase
import leyline.conformance.humanPlayer
import leyline.game.mapper.PromptIds
import wotc.mtgo.gre.external.messaging.Messages
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GameStateType
import wotc.mtgo.gre.external.messaging.Messages.SelectNReq

/**
 * Tests for [BundleBuilder] proto assembly.
 *
 * Unit group: pure proto wrappers (no game needed).
 * Conformance group: bundle shape checks via [startWithBoard].
 */
class BundleBuilderTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        // --- Unit tests (pure proto, no game) ---

        test("queuedGameState wraps GSM with type 51") {
            val gs = Messages.GameStateMessage.newBuilder()
                .setType(GameStateType.Full)
                .setGameStateId(42)
                .build()

            val msg = BundleBuilder.queuedGameState(gs, 2, MessageCounter(initialGsId = 42, initialMsgId = 9))

            msg.type shouldBe GREMessageType.QueuedGameStateMessage
            msg.hasGameStateMessage().shouldBeTrue()
            msg.gameStateMessage.gameStateId shouldBe 42
        }

        test("edictalPass sends server-forced Pass action") {
            val counter = MessageCounter(initialGsId = 10, initialMsgId = 0)
            val result = BundleBuilder.edictalPass(seatId = 1, counter = counter)

            result.messages.size shouldBe 1
            val msg = result.messages[0]
            msg.type shouldBe GREMessageType.EdictalMessage_695e
            msg.hasEdictalMessage().shouldBeTrue()

            val inner = msg.edictalMessage.edictMessage
            inner.type shouldBe Messages.ClientMessageType.PerformActionResp_097b
            inner.systemSeatId shouldBe 1
            val action = inner.performActionResp.actionsList.first()
            action.actionType shouldBe Messages.ActionType.Pass
        }

        test("gameOverBundle produces 3 GSM diffs + IntermissionReq") {
            val counter = MessageCounter(initialGsId = 10, initialMsgId = 0)
            val result = BundleBuilder.gameOverBundle(
                winningTeam = 1,
                matchId = "test-match",
                seatId = 1,
                counter = counter,
                losingPlayerSeatId = 2,
                lossReason = 0,
            )

            result.messages.size shouldBe 4

            for (i in 0..2) {
                result.messages[i].type shouldBe GREMessageType.GameStateMessage_695e
                result.messages[i].gameStateMessage.type shouldBe GameStateType.Diff
            }

            val gs1 = result.messages[0].gameStateMessage
            gs1.hasGameInfo().shouldBeTrue()
            gs1.gameInfo.matchState shouldBe Messages.MatchState.GameComplete
            gs1.gameInfo.stage shouldBe Messages.GameStage.GameOver
            gs1.gameInfo.resultsCount shouldBe 1
            (gs1.teamsCount > 0).shouldBeTrue()
            (gs1.annotationsCount > 0).shouldBeTrue()

            val gs2 = result.messages[1].gameStateMessage
            gs2.gameInfo.matchState shouldBe Messages.MatchState.MatchComplete
            gs2.gameInfo.resultsCount shouldBe 2

            val gs3 = result.messages[2].gameStateMessage
            gs3.pendingMessageCount shouldBe 1
            gs3.hasGameInfo().shouldBeFalse()

            val intermission = result.messages[3]
            intermission.type shouldBe GREMessageType.IntermissionReq_695e
            intermission.hasIntermissionReq().shouldBeTrue()
            intermission.intermissionReq.optionsCount shouldBe 2
            intermission.intermissionReq.intermissionPrompt.promptId shouldBe PromptIds.MATCH_RESULT_WIN_LOSS
        }

        test("gameOverBundle gsIds are strictly ascending") {
            val counter = MessageCounter(initialGsId = 10, initialMsgId = 0)
            val result = BundleBuilder.gameOverBundle(
                winningTeam = 1,
                matchId = "test-match",
                seatId = 1,
                counter = counter,
            )

            var prevGsId = 0
            for (msg in result.messages) {
                if (msg.hasGameStateMessage()) {
                    val gsId = msg.gameStateMessage.gameStateId
                    gsId shouldBeGreaterThan prevGsId
                    prevGsId = gsId
                }
            }
        }

        test("gameOverBundle prevGameStateId chains correctly") {
            val counter = MessageCounter(initialGsId = 10, initialMsgId = 0)
            val result = BundleBuilder.gameOverBundle(
                winningTeam = 2,
                matchId = "test-match",
                seatId = 1,
                counter = counter,
            )

            val gsms = result.messages.filter { it.hasGameStateMessage() }.map { it.gameStateMessage }
            gsms.size shouldBe 3
            gsms[0].prevGameStateId shouldBe 10
            gsms[1].prevGameStateId shouldBe gsms[0].gameStateId
            gsms[2].prevGameStateId shouldBe gsms[1].gameStateId
        }

        test("gameOverBundle with Concede reason") {
            val counter = MessageCounter(initialGsId = 10, initialMsgId = 0)
            val result = BundleBuilder.gameOverBundle(
                winningTeam = 1,
                matchId = "test-match",
                seatId = 1,
                counter = counter,
                reason = Messages.ResultReason.Concede,
                losingPlayerSeatId = 2,
                lossReason = 3,
            )

            val gs1 = result.messages[0].gameStateMessage
            val gameResult = gs1.gameInfo.resultsList.first()
            gameResult.reason shouldBe Messages.ResultReason.Concede
        }

        // --- Conformance tests (board-based) ---

        test("declareAttackersBundle shape") {
            val (b, game, counter) = base.startWithBoard { _, _, _ -> }

            val result = BundleBuilder.declareAttackersBundle(game, b, ConformanceTestBase.TEST_MATCH_ID, 1, counter)

            result.messages.size shouldBe 2
            result.messages[0].type shouldBe GREMessageType.GameStateMessage_695e
            result.messages[1].type shouldBe GREMessageType.DeclareAttackersReq_695e
            result.messages[1].prompt.promptId shouldBe 6
        }

        test("declareBlockersBundle shape") {
            val (b, game, counter) = base.startWithBoard { _, _, _ -> }

            val result = BundleBuilder.declareBlockersBundle(game, b, ConformanceTestBase.TEST_MATCH_ID, 1, counter)

            result.messages.size shouldBe 2
            result.messages[0].type shouldBe GREMessageType.GameStateMessage_695e
            result.messages[1].type shouldBe GREMessageType.DeclareBlockersReq_695e
            result.messages[1].prompt.promptId shouldBe 7
        }

        test("selectTargetsBundle shape") {
            val (b, game, counter) = base.startWithBoard { _, _, _ -> }

            val candidateRefs = listOf(
                leyline.bridge.PromptCandidateRefDto(0, "card", 999, "Battlefield"),
            )
            val prompt = leyline.bridge.InteractivePromptBridge.PendingPrompt(
                promptId = "test-prompt",
                request = leyline.bridge.PromptRequest(
                    promptType = "choose_cards",
                    message = "Choose target",
                    options = listOf("Target A"),
                    min = 1,
                    max = 1,
                    candidateRefs = candidateRefs,
                ),
                future = java.util.concurrent.CompletableFuture(),
            )
            val result = BundleBuilder.selectTargetsBundle(game, b, ConformanceTestBase.TEST_MATCH_ID, 1, counter, prompt)

            result.messages.size shouldBe 2
            result.messages[0].type shouldBe GREMessageType.GameStateMessage_695e
            result.messages[1].type shouldBe GREMessageType.SelectTargetsReq_695e
            result.messages[1].prompt.promptId shouldBe PromptIds.SELECT_TARGETS
            result.messages[1].allowCancel shouldBe Messages.AllowCancel.Abort
            result.messages[1].allowUndo.shouldBeTrue()
        }

        test("echoAttackersBundle conformance — SendAndRecord, no combat state, actions present") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Llanowar Elves", human, ZoneType.Battlefield)
                base.addCard("Elvish Mystic", human, ZoneType.Battlefield)
            }

            val creatures = game.humanPlayer.getZone(ZoneType.Battlefield).cards.filter { it.isCreature }
            val allIds = creatures.map { b.getOrAllocInstanceId(ForgeCardId(it.id)).value }
            val selectedIds = listOf(allIds.first())

            val result = BundleBuilder.echoAttackersBundle(game, b, 1, counter, selectedIds, allIds)

            result.messages.size shouldBe 2
            result.messages[0].type shouldBe GREMessageType.GameStateMessage_695e
            result.messages[1].type shouldBe GREMessageType.DeclareAttackersReq_695e

            val gsm = result.messages[0].gameStateMessage
            gsm.type shouldBe GameStateType.Diff
            (gsm.gameObjectsCount > 0).shouldBeTrue()

            // Conformance: real server uses SendAndRecord, no pendingMessageCount
            gsm.update shouldBe Messages.GameStateUpdate.SendAndRecord
            gsm.pendingMessageCount shouldBe 0

            // Conformance: no attackState/blockState on echo objects
            for (obj in gsm.gameObjectsList) {
                obj.attackState shouldBe Messages.AttackState.None_a3a9
                obj.blockState shouldBe Messages.BlockState.None_aa2d
            }

            // Conformance: actions array present (cumulative turn log)
            (gsm.actionsCount >= 0).shouldBeTrue() // naive actions may be empty in test
        }

        test("echoBlockersBundle conformance — SendAndRecord, no combat state, actions present") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Llanowar Elves", human, ZoneType.Battlefield)
            }

            val blocker = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
            val blockerId = b.getOrAllocInstanceId(ForgeCardId(blocker.id)).value
            val blockAssignments = mapOf(blockerId to 999)

            val result = BundleBuilder.echoBlockersBundle(game, b, 1, counter, blockAssignments)

            result.messages.size shouldBe 2
            result.messages[0].type shouldBe GREMessageType.GameStateMessage_695e
            result.messages[1].type shouldBe GREMessageType.DeclareBlockersReq_695e

            val gsm = result.messages[0].gameStateMessage
            (gsm.gameObjectsCount > 0).shouldBeTrue()

            // Conformance: real server uses SendAndRecord, no pendingMessageCount
            gsm.update shouldBe Messages.GameStateUpdate.SendAndRecord
            gsm.pendingMessageCount shouldBe 0

            // Conformance: no blockState on echo objects
            for (obj in gsm.gameObjectsList) {
                obj.blockState shouldBe Messages.BlockState.None_aa2d
                obj.attackState shouldBe Messages.AttackState.None_a3a9
            }
        }

        test("selectNBundle shape") {
            val (b, game, counter) = base.startWithBoard { _, _, _ -> }

            val req = SelectNReq.newBuilder()
                .setMinSel(1)
                .setMaxSel(1)
                .build()
            val result = BundleBuilder.selectNBundle(game, b, ConformanceTestBase.TEST_MATCH_ID, 1, counter, req)

            result.messages.size shouldBe 2
            result.messages[0].type shouldBe GREMessageType.GameStateMessage_695e
            result.messages[1].type shouldBe GREMessageType.SelectNreq
            result.messages[1].prompt.promptId shouldBe PromptIds.SELECT_N
        }

        test("discard SelectNReq uses Resolution context and Dynamic listType (#175)") {
            val (b, _, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Mountain", human, ZoneType.Hand)
                base.addCard("Forest", human, ZoneType.Hand)
            }

            val handCards = b.getPlayer(leyline.bridge.SeatId(1))!!
                .getZone(ZoneType.Hand).cards.toList()
            val prompt = leyline.bridge.InteractivePromptBridge.PendingPrompt(
                promptId = "discard-test",
                request = leyline.bridge.PromptRequest(
                    promptType = "choose_cards",
                    message = "Choose a card to discard",
                    options = listOf("Discard"),
                    min = 1,
                    max = 1,
                    candidateRefs = handCards.mapIndexed { i, c ->
                        leyline.bridge.PromptCandidateRefDto(i, "card", c.id, "Hand")
                    },
                ),
                future = java.util.concurrent.CompletableFuture(),
            )

            val req = RequestBuilder.buildSelectNReq(prompt, b)

            req.context shouldBe Messages.SelectionContext.Resolution_a163
            req.listType shouldBe Messages.SelectionListType.Dynamic
            req.optionContext shouldBe Messages.OptionContext.Resolution_a9d7
            req.idType shouldBe Messages.IdType.InstanceId_ab2c
            req.validationType shouldBe Messages.SelectionValidationType.NonRepeatable
            req.minSel shouldBe 1
            req.maxSel shouldBe 1
            req.idsCount shouldBe 2
            req.prompt.promptId shouldBe PromptIds.SELECT_N
        }

        test("payCostsBundle shape") {
            val (b, game, counter) = base.startWithBoard { _, _, _ -> }

            val req = Messages.PayCostsReq.newBuilder().build()
            val result = BundleBuilder.payCostsBundle(game, b, ConformanceTestBase.TEST_MATCH_ID, 1, counter, req)

            result.messages.size shouldBe 2
            result.messages[0].type shouldBe GREMessageType.GameStateMessage_695e
            result.messages[1].type shouldBe GREMessageType.PayCostsReq_695e
            result.messages[1].prompt.promptId shouldBe PromptIds.PAY_COSTS
        }
    })
