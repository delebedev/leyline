package leyline.mechanics.cost

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel

class GraveyardExileCostLifecycleTest :
    SessionTest({
        val puzzle =
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanbattlefield=Swamp;Swamp
            humangraveyard=Scrapheap Scrounger;Grizzly Bears
            humanlibrary=Swamp;Swamp;Swamp
            ailibrary=Plains;Plains;Plains
            """.trimIndent()

        session("required graveyard exile payment returns Scrapheap Scrounger", puzzle = puzzle) {
            activateAbilityFromGraveyard("Scrapheap Scrounger").shouldBeTrue()
            val payment = allMessages.last { it.hasPayCostsReq() }
            val selection = payment.payCostsReq.effectCostReq.costSelection
            val bearIid = human.graveyard.iid("Grizzly Bears")
            assertSoftly {
                selection.minSel shouldBe 1
                selection.maxSel shouldBe 1
                payment.allowCancel shouldBe AllowCancel.Abort
            }

            respondToEffectCost(listOf(bearIid))
            passUntilResolved(maxPasses = 8)

            assertSoftly {
                human.battlefield.card("Scrapheap Scrounger").name shouldBe "Scrapheap Scrounger"
                human.exile.card("Grizzly Bears").name shouldBe "Grizzly Bears"
                game().stackZone.isEmpty.shouldBeTrue()
            }
        }

        session("cancelling graveyard exile payment pays nothing and does not replay the prompt", puzzle = puzzle) {
            activateAbilityFromGraveyard("Scrapheap Scrounger").shouldBeTrue()
            val paymentCount = allMessages.count { it.hasPayCostsReq() }

            cancelAction()

            assertSoftly {
                human.graveyard.card("Scrapheap Scrounger").name shouldBe "Scrapheap Scrounger"
                human.graveyard.card("Grizzly Bears").name shouldBe "Grizzly Bears"
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .filter { it.isLand }
                    .all { !it.isTapped }
                    .shouldBeTrue()
                allMessages.count { it.hasPayCostsReq() } shouldBe paymentCount
                game().stackZone.isEmpty.shouldBeTrue()
            }
        }
    })
