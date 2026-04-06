package leyline.conformance

import forge.game.ability.AbilityKey
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag

/**
 * Validates gsId chain **semantics** beyond structural invariants.
 *
 * Structural checks (gsId monotonicity, prevGsId validity, msgId monotonicity,
 * no self-referential gsIds) are automatic via [ValidatingMessageSink].
 *
 * What remains here: scenario-specific contracts about pendingMessageCount,
 * phase transition bundle structure, and cross-bundle chain continuity.
 *
 * Uses [startWithBoard] (~0.01s) — all checks are on synchronous diff builders.
 */
class GsIdChainTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("remoteActionDiff produces single GSM with no pendingMessageCount") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Plains", human, ZoneType.Hand)
            }

            val result = base.bundleBuilder(b).remoteActionDiff(game, counter)
            result.messages.size shouldBe 1
            result.messages[0].gameStateMessage.pendingMessageCount shouldBe 0
        }

        test("postAction GSM has pendingMessageCount=1 (AAR follows)") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Plains", human, ZoneType.Hand)
            }

            val land = game.humanPlayer.getZone(ZoneType.Hand).cards.first { it.isLand }
            base.captureAfterAction(b, game, counter) {
                game.action.moveToPlay(land, null, AbilityKey.newMap())
            }

            val result = base.postAction(game, b, counter)
            val gsm = result.gsm
            gsm.pendingMessageCount shouldBe 1
        }

        test("phaseTransitionDiff produces 5 messages with correct echo chain") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Plains", human, ZoneType.Hand)
            }

            val result = base.bundleBuilder(b).phaseTransitionDiff(game, counter)
            result.messages.size shouldBe 5

            val gsms = result.messages.filter { it.hasGameStateMessage() }.map { it.gameStateMessage }
            gsms.size shouldBeGreaterThanOrEqual 3

            assertSoftly {
                // msg2 (echo) chains from msg1, no pendingMessageCount
                gsms[1].prevGameStateId shouldBe gsms[0].gameStateId
                gsms[1].pendingMessageCount shouldBe 0

                // msg3 (commit) chains from echo
                gsms[2].prevGameStateId shouldBe gsms[1].gameStateId
            }
        }
    })
