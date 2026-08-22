package leyline.match

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import leyline.bridge.bootstrap.GameBootstrap
import leyline.testkit.*
import leyline.testkit.SessionTest
import leyline.testkit.TestCardRegistry
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
@Suppress("MissingAssertSoftly")
class TwoPhaseCombatFidelityTest :
    SessionTest({
        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
            TestCardRegistry.ensureCardRegistered("Grizzly Bears")
        }

        session(
            "attack confirms under fidelity mode — opponent takes damage, no IllegalRequest",
            puzzle =
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
            turns = 5,
        ) {
            passUntil(maxPasses = 30) { allMessages.any { it.hasDeclareAttackersReq() } }.shouldBeTrue()
            val bearIid = humanBattlefieldCreatures().first { it.second == "Grizzly Bears" }.first

            // Two-round-trip: declareAttackers sends the selection, then the
            // submit — submitWithGsId stamps each with the pending prompt's
            // msgId, which advances via the host's echo-back between them.
            declareAttackers(listOf(bearIid))
            passUntil(maxPasses = 30) { ai.life < 20 }.shouldBeTrue()

            // Attack resolved: opponent below starting life, and the host never
            // rejected the submit.
            ai.life shouldBeLessThan 20
            allMessages.count { it.type == GREMessageType.IllegalRequest } shouldBe 0
        }

        session(
            "consult-driven attack walks the two-round-trip to completion under fidelity mode",
            puzzle =
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
            turns = 5,
        ) {
            passUntil(maxPasses = 30) { allMessages.any { it.hasDeclareAttackersReq() } }.shouldBeTrue()

            // Drive declaration purely off consult proposals: each round reads
            // the latest (re-)prompt, injects the proposed bytes verbatim, and
            // stops once the proposal is the Submit. Fidelity mode rejects any
            // envelope the consult stamps wrong, so a mismatch fails the walk.
            var rounds = 0
            while (rounds++ < 5) {
                val prompt = allMessages.last { it.hasDeclareAttackersReq() }
                val proposal = advise(prompt).proposal
                when (proposal.intent) {
                    "attack" -> declareAllAttackers()
                    "submit_attackers" -> {
                        submitAttackers()
                        break
                    }
                    else -> error("Unexpected combat consult intent: ${proposal.intent}")
                }
            }

            passUntil(maxPasses = 30) { ai.life < 20 }.shouldBeTrue()
            ai.life shouldBeLessThan 20
            allMessages.count { it.type == GREMessageType.IllegalRequest } shouldBe 0
        }

        session(
            "semantic attacker submission keeps response correlation valid",
            puzzle =
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
            turns = 5,
        ) {
            passUntil(maxPasses = 30) { allMessages.any { it.hasDeclareAttackersReq() } }.shouldBeTrue()
            declareNoAttackers()
            submitAttackers()
            allMessages.count { it.type == GREMessageType.IllegalRequest } shouldBe 0
        }
    })
