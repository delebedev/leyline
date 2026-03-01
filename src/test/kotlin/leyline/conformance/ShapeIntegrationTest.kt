package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.game.BundleBuilder
import leyline.game.MessageCounter

/**
 * Validates BundleBuilder output shape matches client patterns.
 *
 * Structural fingerprinting: message types, updateType, annotation presence,
 * prompt IDs — all against known client expectations.
 *
 * Uses startWithBoard for fast synchronous setup (~0.01s).
 */
class ShapeIntegrationTest :
    FunSpec({
        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("aiActionDiff produces single SendHiFi GSM (no echo)") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Plains", human, ZoneType.Hand)
                base.addCard("Forest", human, ZoneType.Battlefield)
            }

            val result = BundleBuilder.aiActionDiff(game, b, ConformanceTestBase.TEST_MATCH_ID, ConformanceTestBase.SEAT_ID, counter)
            val captured = base.fingerprint(result.messages)

            captured.size shouldBe 1
            captured[0].greMessageType shouldBe "GameStateMessage"
            captured[0].updateType shouldBe "SendHiFi"
        }

        test("declareAttackersBundle produces GS + DeclareAttackersReq with promptId=6") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }

            val result = BundleBuilder.declareAttackersBundle(game, b, ConformanceTestBase.TEST_MATCH_ID, ConformanceTestBase.SEAT_ID, counter)
            val captured = base.fingerprint(result.messages)

            captured.size shouldBe 2
            captured[0].greMessageType shouldBe "GameStateMessage"
            captured[1].greMessageType shouldBe "DeclareAttackersReq"
            captured[1].promptId shouldBe 6
        }

        test("edictalPass produces single EdictalMessage") {
            val result = BundleBuilder.edictalPass(1, MessageCounter(initialGsId = 10, initialMsgId = 0))
            val captured = base.fingerprint(result.messages)

            captured.size shouldBe 1
            captured[0].greMessageType shouldBe "EdictalMessage"
        }
    })
