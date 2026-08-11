package leyline.copilot

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.SimClientTag
import leyline.bridge.types.SeatId
import leyline.testkit.MatchFlowHarness
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType

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
            proposal.card.shouldNotBeNull().name shouldBe "Forest"
            proposal.responseIds shouldContain proposal.card!!.instanceId
            proposal.responses.size shouldBe 1
        }

        test("null pending prompt yields an unrealizable proposal, never an error") {
            val harness = MatchFlowHarness(seed = 7L, deckList = "60 Forest")
            harness.connectAndKeep()

            val proposal = CopilotProposalService(harness.bridge, SeatId(1)).propose(null)

            proposal.intent shouldBe "unrealizable"
            proposal.reason.shouldNotBeNull()
        }
    })
