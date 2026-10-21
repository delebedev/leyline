package leyline.copilot

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.SimClientTag
import leyline.bridge.types.SeatId
import leyline.testkit.MatchFlowHarness
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ChooseStartingPlayerReq
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.DistributionReq
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.MulliganReq
import wotc.mtgo.gre.external.messaging.Messages.NumericInputReq
import wotc.mtgo.gre.external.messaging.Messages.OrderReq
import wotc.mtgo.gre.external.messaging.Messages.ReplacementEffect
import wotc.mtgo.gre.external.messaging.Messages.SearchReq
import wotc.mtgo.gre.external.messaging.Messages.SelectReplacementReq
import wotc.mtgo.gre.external.messaging.Messages.TeamType

/**
 * Drives the copilot proposal surface with a live Forge game and authored
 * prompts: the service consults the Forge-AI brain through the extracted
 * policy and the GameBridge, so this pins the whole chain
 * bridge → ForgeAiPolicy → SimDecision → CopilotProposalRealizer → CopilotProposal.
 */
@Suppress("MissingAssertSoftly", "TierPlacementCheck")
class CopilotProposalServiceTest :
    FunSpec({
        tags(SimClientTag)

        fun prompt(
            type: GREMessageType,
            configure: GREToClientMessage.Builder.() -> Unit = {},
        ): GREToClientMessage =
            GREToClientMessage
                .newBuilder()
                .setType(type)
                .setGameStateId(17)
                .setMsgId(29)
                .apply(configure)
                .build()

        fun CopilotProposal.decodedResponses(): List<ClientToGREMessage> =
            responses.map { hex ->
                ClientToGREMessage.parseFrom(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            }

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
            proposal.reason shouldBe "no pending prompt for seat 1"
            proposal.promptType shouldBe GREMessageType.PromptReq.name
            proposal.promptKey shouldBe null
            proposal.gameStateId shouldBe null
            proposal.respId shouldBe null
            proposal.responses shouldBe emptyList()
        }

        test("ordinary prompt proposals retain decisions and response correlation") {
            val harness = MatchFlowHarness(seed = 7L, deckList = "60 Forest")
            harness.connectAndKeep()
            val service = CopilotProposalService(harness.bridge, SeatId(2))
            val cases =
                listOf(
                    Triple(
                        prompt(GREMessageType.MulliganReq_aa0d) { setMulliganReq(MulliganReq.getDefaultInstance()) },
                        "keep_hand",
                        emptyList(),
                    ),
                    Triple(
                        prompt(GREMessageType.OrderReq_695e) { setOrderReq(OrderReq.newBuilder().addIds(812).addIds(807)) },
                        "order",
                        listOf(812, 807),
                    ),
                    Triple(
                        prompt(GREMessageType.SearchReq_695e) {
                            setSearchReq(
                                SearchReq
                                    .newBuilder()
                                    .setMinFind(1)
                                    .setMaxFind(1)
                                    .addItemsSought(812)
                                    .addItemsSought(807),
                            )
                        },
                        "search",
                        listOf(812),
                    ),
                    Triple(
                        prompt(GREMessageType.NumericInputReq_695e) {
                            setNumericInputReq(NumericInputReq.newBuilder().setMinValue(5).setMaxValue(5))
                        },
                        "numeric",
                        listOf(5),
                    ),
                )
            val responseTypes =
                listOf(
                    ClientMessageType.MulliganResp_097b,
                    ClientMessageType.OrderResp_097b,
                    ClientMessageType.SearchResp_097b,
                    ClientMessageType.NumericInputResp_097b,
                )

            cases.zip(responseTypes).forEach { (case, responseType) ->
                val (request, intent, ids) = case
                val proposal = service.propose(request)
                proposal.intent shouldBe intent
                proposal.responseIds shouldBe ids
                proposal.reason shouldBe null
                proposal.promptType shouldBe request.type.name
                proposal.seat shouldBe 2
                proposal.promptKey shouldBe "17:29"
                proposal.gameStateId shouldBe 17
                proposal.respId shouldBe 29
                val response = proposal.decodedResponses().single()
                response.type shouldBe responseType
                response.gameStateId shouldBe 17
                response.respId shouldBe 29
                response.systemSeatId shouldBe 2
            }
            service
                .propose(cases[1].first)
                .decodedResponses()
                .single()
                .orderResp.idsList shouldBe listOf(812, 807)
            service
                .propose(cases[2].first)
                .decodedResponses()
                .single()
                .searchResp.itemsFoundList shouldBe listOf(812)
            service
                .propose(cases[3].first)
                .decodedResponses()
                .single()
                .numericInputResp.numericInputValue shouldBe 5
        }

        test("replacement proposal echoes the complete first offered row") {
            val harness = MatchFlowHarness(seed = 7L, deckList = "60 Forest")
            harness.connectAndKeep()
            val first =
                ReplacementEffect
                    .newBuilder()
                    .setObjectInstance(812)
                    .setAffectedObject(807)
                    .setReplacementEffectId(9000)
                    .build()
            val second = first.toBuilder().setReplacementEffectId(9001).build()
            val request =
                prompt(GREMessageType.SelectReplacementReq_695e) {
                    setSelectReplacementReq(SelectReplacementReq.newBuilder().addReplacements(first).addReplacements(second))
                }

            val proposal = CopilotProposalService(harness.bridge, SeatId(1)).propose(request)

            proposal.intent shouldBe "select_replacement"
            proposal.replacementEffectId shouldBe 9000
            proposal
                .decodedResponses()
                .single()
                .selectReplacementResp.replacement shouldBe first
        }

        test("unsupported prompts preserve exact diagnostics and correlation without responses") {
            val harness = MatchFlowHarness(seed = 7L, deckList = "60 Forest")
            harness.connectAndKeep()
            val service = CopilotProposalService(harness.bridge, SeatId(2))

            for (type in listOf(GREMessageType.None_aa0d, GREMessageType.PromptReq)) {
                val proposal = service.propose(prompt(type))
                proposal.intent shouldBe "unrealizable"
                proposal.reason shouldBe "prompt type $type has no copilot decoder"
                proposal.promptType shouldBe type.name
                proposal.seat shouldBe 2
                proposal.promptKey shouldBe "17:29"
                proposal.gameStateId shouldBe 17
                proposal.respId shouldBe 29
                proposal.responses shouldBe emptyList()
            }
        }

        test("invalid ordinary prompt remains an advisor failure rather than unsupported") {
            val harness = MatchFlowHarness(seed = 7L, deckList = "60 Forest")
            harness.connectAndKeep()

            val proposal = CopilotProposalService(harness.bridge, SeatId(1)).propose(prompt(GREMessageType.OrderReq_695e))

            proposal.intent shouldBe "unrealizable"
            proposal.reason shouldBe
                "advisor unavailable: NoForgeChoice: prompt-derived decision did not validate against its legal response domain"
            proposal.promptKey shouldBe "17:29"
            proposal.responses shouldBe emptyList()
        }

        test("Copilot distributes a non-forced total even when the advisor declines it") {
            val harness = MatchFlowHarness(seed = 7L, deckList = "60 Forest")
            harness.connectAndKeep()
            val request =
                prompt(GREMessageType.DistributionReq_695e) {
                    setDistributionReq(
                        DistributionReq
                            .newBuilder()
                            .setMinAmount(7)
                            .setMaxAmount(7)
                            .setMinPerTarget(1)
                            .addTargetIds(812)
                            .addTargetIds(807),
                    )
                }
            val advisor = PromptDecisionAdvisor(ForgeAiPolicy({ harness.bridge }, SeatId(2)))
            advisor.decide(request).shouldBeInstanceOf<PromptDecisionResult.Unavailable>().reason shouldBe
                PromptUnavailableReason.UnsupportedPrompt

            val proposal = CopilotProposalService(harness.bridge, SeatId(2)).propose(request)

            proposal.intent shouldBe "distribute"
            proposal.distribution shouldBe listOf(DistributionAmount(812, 6), DistributionAmount(807, 1))
            proposal.responseIds shouldBe listOf(812, 807)
            proposal.promptKey shouldBe "17:29"
            val response = proposal.decodedResponses().single()
            response.distributionResp.distributionsList.map { it.instanceId to it.amount } shouldBe listOf(812 to 6, 807 to 1)
            response.gameStateId shouldBe 17
            response.respId shouldBe 29
            response.systemSeatId shouldBe 2
        }

        test("starting-player prompt chooses the requesting seat") {
            val harness = MatchFlowHarness(seed = 7L, deckList = "60 Forest")
            harness.connectAndKeep()
            val prompt =
                GREToClientMessage
                    .newBuilder()
                    .setType(GREMessageType.ChooseStartingPlayerReq_695e)
                    .setGameStateId(1)
                    .setMsgId(5)
                    .setChooseStartingPlayerReq(
                        ChooseStartingPlayerReq
                            .newBuilder()
                            .setTeamType(TeamType.Individual)
                            .addSystemSeatIds(1)
                            .addSystemSeatIds(2),
                    ).build()

            val proposal = CopilotProposalService(harness.bridge, SeatId(2)).propose(prompt)

            proposal.intent shouldBe "choose_starting_player"
            proposal.promptKey shouldBe "1:5"
            proposal.gameStateId shouldBe 1
            proposal.respId shouldBe 5
            val response = proposal.decodedResponses().single()
            response.type shouldBe ClientMessageType.ChooseStartingPlayerResp_097b
            response.chooseStartingPlayerResp.systemSeatId shouldBe 2
            response.systemSeatId shouldBe 2
            response.gameStateId shouldBe 1
            response.respId shouldBe 5
        }
    })
