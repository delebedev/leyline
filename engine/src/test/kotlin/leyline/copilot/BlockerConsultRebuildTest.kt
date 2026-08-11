package leyline.copilot

import forge.game.zone.ZoneType
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.testkit.SessionTest
import wotc.mtgo.gre.external.messaging.Messages.Blocker
import wotc.mtgo.gre.external.messaging.Messages.DeclareBlockersReq
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

/**
 * Blocker consult without a combat under way — the hydrated-game condition.
 * The DeclareBlockersReq itself names the attackers, so the policy rebuilds a
 * combat from the prompt and asks the AI to block on it, instead of degrading
 * to submit-no-blocks the moment `game.combat` is null.
 */
class BlockerConsultRebuildTest :
    SessionTest({

        test("consult proposes the free block when no live combat exists") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Centaur Courser
                aibattlefield=Raging Goblin
                humanlibrary=Forest;Forest
                ailibrary=Mountain;Mountain
                """.trimIndent(),
                name = "Blocker rebuild",
            )

            val bridge = harness.bridge

            fun iidOf(
                seat: Int,
                name: String,
            ): Int {
                val card =
                    bridge
                        .getPlayer(SeatId(seat))!!
                        .getZone(ZoneType.Battlefield)
                        .cards
                        .first { it.name == name }
                return bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value
            }

            val courser = iidOf(1, "Centaur Courser")
            val goblin = iidOf(2, "Raging Goblin")

            // The game hosts no combat (Main1) — exactly what a hydrated
            // consult sees. The prompt carries the attacker set.
            val prompt =
                GREToClientMessage
                    .newBuilder()
                    .setType(GREMessageType.DeclareBlockersReq_695e)
                    .setMsgId(99)
                    .setGameStateId(1)
                    .setDeclareBlockersReq(
                        DeclareBlockersReq
                            .newBuilder()
                            .addBlockers(
                                Blocker
                                    .newBuilder()
                                    .setBlockerInstanceId(courser)
                                    .addAttackerInstanceIds(goblin)
                                    .setMaxAttackers(1),
                            ),
                    ).build()

            val proposal = CopilotProposalService(bridge, SeatId(1)).propose(prompt)

            // A 3/3 blocking a 1/1 is a free kill — the AI takes it, and the
            // block decision proves combat was rebuilt from the prompt.
            proposal.intent shouldBe "block"
            proposal.blocks.firstOrNull().shouldNotBeNull().let {
                it.blocker.instanceId shouldBe courser
                it.attacker.instanceId shouldBe goblin
            }
            proposal.responses.shouldNotBeEmpty()
        }
    })
