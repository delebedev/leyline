package leyline.copilot

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.types.SeatId
import leyline.testkit.SessionTest
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Drives target declaration entirely off consult proposals against the live
 * (validating) session — the targeting analogue of TwoPhaseCombatFidelityTest.
 * SelectTargetsReq is iterative-delta: the consult picks the target, the session
 * echoes a fresh SelectTargetsReq, and a SubmitTargetsReq answers the re-prompt.
 * Each response's respId must echo the answering prompt's msgId; fidelity mode
 * rejects a mismatch, so a wrong envelope fails the walk.
 *
 * Proves the two-round-trip end to end: a consult-driven Giant Growth resolves
 * (the creature is buffed) with zero IllegalRequest.
 */
@Suppress("MissingAssertSoftly")
class ConsultTargetingFidelityTest :
    SessionTest({

        test("consult-driven Giant Growth resolves via select-then-submit under fidelity mode") {
            startPuzzleFile("puzzles/pump-spell.pzl")

            val creatureIid = humanBattlefieldCreatures().first().first
            castSpellByName("Giant Growth").shouldBeTrue()
            harness.drainSink()
            allMessages.any { it.hasSelectTargetsReq() }.shouldBeTrue()

            fun decodeSingle(hex: String): ClientToGREMessage {
                val bytes = ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
                return ClientToGREMessage.parseFrom(bytes)
            }

            // Each round reads the latest SelectTargetsReq, injects the proposed
            // bytes verbatim, and stops once the proposal is the Submit. The
            // proposal's respId must equal the prompt msgId or fidelity rejects it.
            val service = CopilotProposalService(harness.bridge, SeatId(1))
            var rounds = 0
            while (rounds++ < 6) {
                harness.drainSink()
                val prompt = allMessages.last { it.hasSelectTargetsReq() }
                val hex = service.propose(prompt).responses.single()
                val msg = decodeSingle(hex)
                msg.respId shouldBe prompt.msgId
                if (msg.type == ClientMessageType.SubmitTargetsReq) {
                    harness.session.onSubmitTargets(msg)
                    break
                }
                harness.session.onSelectTargets(msg)
            }

            passUntil(maxPasses = 6) { (cardByIid(creatureIid)?.netPower ?: 0) >= 4 }.shouldBeTrue()

            val creature = cardByIid(creatureIid).shouldNotBeNull()
            creature.netPower shouldBeGreaterThanOrEqual 4
            human
                .getZone(ForgeZoneType.Graveyard)
                .cards
                .count { it.name == "Giant Growth" } shouldBe 1
            allMessages.count { it.type == GREMessageType.IllegalRequest } shouldBe 0
        }
    })
