package leyline.match

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.types.SeatId
import leyline.copilot.CopilotProposalService
import leyline.testkit.SessionTest
import leyline.testkit.TestCardRegistry
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.FailureReason
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType

/**
 * Two-phase combat under the response-envelope contract (ResponseEnvelopeGuard). Contract: the
 * DeclareAttackersResp answers the first DeclareAttackersReq and the
 * SubmitAttackersReq answers the re-prompt the host emits after the selection —
 * each echoing that prompt's msgId in respId. The host validates the correlation
 * and rejects a mismatch with an IllegalRequest (ReqRespMismatch).
 *
 * Proves the two-round-trip end to end: a correctly-sequenced attack confirms
 * (opponent takes damage, no IllegalRequest), and a submit carrying a stale
 * respId is rejected.
 */
class TwoPhaseCombatFidelityTest :
    SessionTest({
        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
            TestCardRegistry.ensureCardRegistered("Grizzly Bears")
        }

        test("attack confirms under fidelity mode — opponent takes damage, no IllegalRequest") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                removesummoningsickness=true

                humanbattlefield=Grizzly Bears
                humanlibrary=Forest;Forest;Forest
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
                name = "Two-phase combat fidelity",
                turns = 5,
            )

            passUntil(maxPasses = 30) { allMessages.any { it.hasDeclareAttackersReq() } }.shouldBeTrue()
            val bearIid = humanBattlefieldCreatures().first { it.second == "Grizzly Bears" }.first

            // Two-round-trip: declareAttackers sends the selection, then the
            // submit — submitWithGsId stamps each with the pending prompt's
            // msgId, which advances via the host's echo-back between them.
            harness.declareAttackers(listOf(bearIid))
            passUntil(maxPasses = 30) { ai.life < 20 }.shouldBeTrue()

            // Attack resolved: opponent below starting life, and the host never
            // rejected the submit.
            ai.life shouldBeLessThan 20
            allMessages.count { it.type == GREMessageType.IllegalRequest } shouldBe 0
        }

        test("consult-driven attack walks the two-round-trip to completion under fidelity mode") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                removesummoningsickness=true

                humanbattlefield=Grizzly Bears
                humanlibrary=Forest;Forest;Forest
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
                name = "Consult-driven two-phase combat",
                turns = 5,
            )

            passUntil(maxPasses = 30) { allMessages.any { it.hasDeclareAttackersReq() } }.shouldBeTrue()

            fun decodeSingle(hex: String): ClientToGREMessage {
                val bytes = ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
                return ClientToGREMessage.parseFrom(bytes)
            }

            // Drive declaration purely off consult proposals: each round reads
            // the latest (re-)prompt, injects the proposed bytes verbatim, and
            // stops once the proposal is the Submit. Fidelity mode rejects any
            // envelope the consult stamps wrong, so a mismatch fails the walk.
            val service = CopilotProposalService(harness.bridge, SeatId(1))
            var rounds = 0
            while (rounds++ < 5) {
                harness.drainSink()
                val prompt = allMessages.last { it.hasDeclareAttackersReq() }
                val hex = service.propose(prompt).responses.single()
                val msg = decodeSingle(hex)
                msg.respId shouldBe prompt.msgId
                harness.session.onDeclareAttackers(msg)
                if (msg.type == ClientMessageType.SubmitAttackersReq) break
            }

            passUntil(maxPasses = 30) { ai.life < 20 }.shouldBeTrue()
            ai.life shouldBeLessThan 20
            allMessages.count { it.type == GREMessageType.IllegalRequest } shouldBe 0
        }

        test("submit with a stale respId is rejected (ReqRespMismatch)") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                removesummoningsickness=true

                humanbattlefield=Grizzly Bears
                humanlibrary=Forest;Forest;Forest
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
                name = "Two-phase combat stale respId",
                turns = 5,
            )

            passUntil(maxPasses = 30) { allMessages.any { it.hasDeclareAttackersReq() } }.shouldBeTrue()
            val bearIid = humanBattlefieldCreatures().first { it.second == "Grizzly Bears" }.first

            // Deliberately submit with a wrong respId (1 — never a real prompt
            // msgId here). The host must reject it with an IllegalRequest.
            val staleSubmit =
                ClientToGREMessage
                    .newBuilder()
                    .setType(ClientMessageType.SubmitAttackersReq)
                    .setSystemSeatId(1)
                    .setRespId(1)
                    .setGameStateId(harness.latestPromptGsId())
                    .build()
            val before = allMessages.size
            harness.session.onDeclareAttackers(staleSubmit)
            harness.drainSink()

            val illegal = allMessages.drop(before).filter { it.type == GREMessageType.IllegalRequest }
            illegal.size shouldBe 1
            illegal.single().illegalRequestMessage.reason shouldBe FailureReason.ReqRespMismatch
        }
    })
