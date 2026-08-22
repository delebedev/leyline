package leyline.copilot

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.SimClientTag
import leyline.bridge.types.SeatId
import leyline.testkit.MatchFlowHarness
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.Group
import wotc.mtgo.gre.external.messaging.Messages.SearchFromGroupsReq

/**
 * Drives the copilot proposal surface against a live Forge game (not synthetic
 * messages): the service consults the real Forge-AI brain through the extracted
 * policy and the live GameBridge, so this pins the whole chain
 * bridge → ForgeAiPolicy → SimDecision → ProposalTranslator → CopilotProposal.
 */
@Suppress("MissingAssertSoftly", "TierPlacementCheck")
class CopilotProposalServiceTest :
    FunSpec({
        tags(SimClientTag)

        test("proposes play_land for the opening land-drop AAR against a live game") {
            val harness = MatchFlowHarness(seed = 42L, deckList = "60 Forest")
            harness.connectAndKeep()

            // The opening main-phase AAR offers a land play; the AI should take it.
            val landAar =
                harness.allMessages.lastOrNull { m ->
                    m.type == GREMessageType.ActionsAvailableReq_695e &&
                        m.actionsAvailableReq.actionsList.any { it.actionType == ActionType.Play_add3 }
                }
            landAar.shouldNotBeNull()

            val service = CopilotProposalService(harness.bridge, SeatId(1))
            val proposal = service.propose(landAar)

            proposal.intent shouldBe "play_land"
            val card = proposal.card.shouldNotBeNull()
            card.name shouldBe "Forest"
            proposal.responseIds shouldContain card.instanceId
            proposal.responses.size shouldBe 1
            proposal.promptKey shouldBe "${landAar.gameStateId}:${landAar.msgId}"
            proposal.gameStateId shouldBe landAar.gameStateId
            proposal.respId shouldBe landAar.msgId
        }

        test("null pending prompt yields an unrealizable proposal, never an error") {
            val harness = MatchFlowHarness(seed = 7L, deckList = "60 Forest")
            harness.connectAndKeep()

            val proposal = CopilotProposalService(harness.bridge, SeatId(1)).propose(null)

            proposal.intent shouldBe "unrealizable"
            proposal.reason.shouldNotBeNull()
        }

        test("grouped search proposal carries an injectable echoed-group response") {
            val harness = MatchFlowHarness(seed = 9L, deckList = "60 Forest")
            harness.connectAndKeep()
            val prompt =
                GREToClientMessage
                    .newBuilder()
                    .setType(GREMessageType.SearchFromGroupsReq_695e)
                    .setMsgId(224)
                    .setGameStateId(174)
                    .setSearchFromGroupsReq(
                        SearchFromGroupsReq
                            .newBuilder()
                            .addGroups(
                                Group
                                    .newBuilder()
                                    .setGroupId(5004)
                                    .setMaxSelect(1)
                                    .addIds(42),
                            ),
                    ).build()

            val proposal = CopilotProposalService(harness.bridge, SeatId(1)).propose(prompt)
            val hex = proposal.responses.single()
            val response =
                ClientToGREMessage.parseFrom(
                    ByteArray(hex.length / 2) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() },
                )

            proposal.intent shouldBe "search"
            proposal.responseIds shouldBe listOf(42)
            response.type shouldBe ClientMessageType.SearchFromGroupsResp_097b
            response.respId shouldBe 224
            response.searchFromGroupsResp.groupsList
                .single()
                .groupId shouldBe 5004
        }
    })
