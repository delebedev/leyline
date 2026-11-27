package leyline.session.costs

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import leyline.bridge.bootstrap.GameBootstrap
import leyline.testkit.MatchFlowHarness
import leyline.testkit.SessionTest
import leyline.testkit.TestCardRegistry
import leyline.testkit.gameStateMessages
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.ManaInfo

/**
 * A cast whose automatic mana payment fails puts mana sources and floating mana back as they were,
 * including mana abilities that cost mana (a Signet) and filters paid with their mana.
 */
class ManaPaymentRollbackTest :
    SessionTest({
        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
            TestCardRegistry.ensureCardRegistered("Orzhov Signet")
            TestCardRegistry.ensureCardRegistered("Fetid Heath")
            TestCardRegistry.ensureCardRegistered("Savage Lands")
            TestCardRegistry.ensureCardRegistered("Ghor-Clan Rampager")
        }

        // Ghor-Clan Rampager costs {2}{R}{G}; Savage Lands is the only red or green source, but the offer stays open.
        val puzzle =
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanhand=Ghor-Clan Rampager
            humanbattlefield=Orzhov Signet;Wastes;Fetid Heath;Savage Lands
            humanlibrary=Plains;Plains;Plains
            ailibrary=Mountain;Mountain;Mountain
            """.trimIndent()

        session("a failed cast keeps both Signet mana floating from the Signet", puzzle = puzzle, fullControl = true) {
            val signetIid = instanceIdOf("Orzhov Signet")
            check(activateMana("Wastes"))
            check(activateMana("Orzhov Signet"))

            check(castSpellByName("Ghor-Clan Rampager"))

            assertSoftly {
                human.hand.card("Ghor-Clan Rampager")
                game().stack.isEmpty shouldBe true
                human.manaPool.map { it.sourceCard.name } shouldBe listOf("Orzhov Signet", "Orzhov Signet")
                allMessages.latestHumanManaPool().map { it.srcInstanceId to it.color } shouldContainExactlyInAnyOrder
                    listOf(signetIid to ManaColor.White_afc9, signetIid to ManaColor.Black_afc9)
                tappedNames() shouldContainExactlyInAnyOrder listOf("Wastes", "Orzhov Signet")
            }
        }

        session("a failed cast untaps the Signet and the land that paid for it", puzzle = puzzle, fullControl = true) {
            check(castSpellByName("Ghor-Clan Rampager"))

            assertSoftly {
                human.hand.card("Ghor-Clan Rampager")
                game().stack.isEmpty shouldBe true
                human.manaPool.totalMana() shouldBe 0
                tappedNames().shouldBeEmpty()
            }
        }
    })

private fun MatchFlowHarness.tappedNames(): List<String> =
    human
        .getZone(ZoneType.Battlefield)
        .cards
        .filter { it.isTapped }
        .map { it.name }

private fun List<GREToClientMessage>.latestHumanManaPool(): List<ManaInfo> =
    gameStateMessages()
        .flatMap { it.playersList }
        .last { it.systemSeatNumber == SessionTest.HUMAN_SEAT }
        .manaPoolList
